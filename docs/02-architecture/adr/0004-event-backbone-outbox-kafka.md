# ADR-0004: Event backbone — transactional outbox và relay trong ứng dụng; bus dựa trên DB ở Phase 1, Apache Kafka ở Phase 2

- **Trạng thái:** Đã chấp nhận (2026-10-16, R3-11) — **revision 2 (2026-10-16, decision log R2-01)**. Revision 1 (cùng ngày, cũng ở trạng thái Proposed) đưa Kafka vào Phase 1. Không có quyết định đã chấp nhận nào bị thay đổi; baseline B5 của bản định hướng (outbox cho từng module, envelope chuẩn, danh mục v1) được giữ nguyên.
- **Ngày:** 2026-10-16
- **Liên quan:** Bản định hướng kiến trúc B5; gap-analysis §6, §9 A-2, GAP-017/019/020/063; NFR-PPL-002, NFR-CORE-004, NFR-REC-005; decision log R2-01 / §7.2; [architecture-overview](../architecture-overview.md) §7.2; ADR-0011 (job queue), ADR-0012 (nguồn cấp dữ liệu báo cáo), ADR-0016, ADR-0028 (bảng outbox/inbox); DESIGN.md §9.1

## Bối cảnh

Cả sáu BRD đều giả định có transactional outbox và một event bus với cơ chế phân phối at-least-once và consumer idempotent. Gap-analysis §6 là danh mục chuẩn v1 với một envelope chung.

Revision 1 chọn Kafka ngay từ đầu vì cùng một hạ tầng sẽ phục vụ ba loại tải: business event, **bộ đệm tiếp nhận dữ liệu chấm công** (đỉnh 500 lượt chấm công/s toàn nền tảng, X-05) và **CDC sang kho dữ liệu báo cáo**, cộng thêm replay và mirror cho DR. Quyết định R2-01 (2026-10-16) loại bỏ mọi tích hợp ngoài trực tuyến khỏi Phase 1: không có connector máy chấm công hay endpoint tiếp nhận (dữ liệu chấm công chỉ đến từ file xuất của thiết bị được import), không có webhook công khai, không có consumer event bên ngoài. Điều đó loại bỏ hai loại tải từng biện minh cho Kafka ở Phase 1:

| Động lực dùng Kafka trong rev. 1 | Phase 1 sau R2-01 |
|---|---|
| Bộ đệm burst dữ liệu chấm công (topic `att.raw_punch`, 500/s) | Không còn: file xuất từ thiết bị được import bằng job chia chunk; X-05 và SP-3 chuyển sang Phase 2 |
| Webhook công khai / consumer bên ngoài | Không còn: Phase 2 (ADR-0014) |
| CDC sang báo cáo (Debezium → Kafka) | Thay bằng logical replication native của PostgreSQL (điều chỉnh ADR-0012) |
| Business event giữa 5 module | Vẫn còn, nhưng ở khối lượng pilot: ước tính ≤ 2 event mỗi lần ghi nghiệp vụ, trung bình vài event/s, các đợt burst ngắn vài trăm/s khi import và công bố bảng lương |
| Replay cho consumer mới | Được đáp ứng bằng job re-sync từ API của chủ sở hữu (vốn đã bắt buộc, DESIGN §2.2) cộng với cửa sổ outbox 7 ngày |

Chi phí Kafka vẫn phải trả ở Phase 1: Strimzi + 3 broker trải trên nhiều zone, Apicurio, MirrorMaker 2 sang DC2, giám sát, nâng cấp, runbook DR và đào tạo nhóm — cho một nhóm nhỏ với kế hoạch 16 tuần (C-7) và trên một nhà cung cấp VN có thể không cung cấp Kafka managed (AR-5). Mọi thành phần khác trong thiết kế vốn đã dựa trên PostgreSQL: job queue (ADR-0011) cung cấp enqueue trong transaction, retry, trạng thái dead, replay bởi operator, chia sẻ công bằng theo tenant và scale bằng KEDA.

## Quyết định

1. **Outbox cho từng schema module**, được ghi trong transaction nghiệp vụ (bảng: ADR-0028). Không thay đổi.
2. **Polling relay trong ứng dụng** tại `hrm-worker`: poll outbox của từng cluster bằng `FOR UPDATE SKIP LOCKED` (role `yhrm_relay`), đánh dấu các dòng đã publish; dòng đã publish được giữ 7 ngày. Debezium *không* nằm trên luồng nghiệp vụ. Không thay đổi.
3. **Transport của Phase 1 — bus dựa trên DB trên job queue (không có Kafka):**
   - Trong cùng transaction đánh dấu một dòng outbox đã publish, relay gọi `saas.enqueue_event_delivery()` (SECURITY DEFINER), hàm này insert **một job phân phối cho mỗi consumer đã đăng ký** vào `saas.job` cục bộ của cluster (queue `events`, type `bus.Deliver`, `idempotency_key = consumer:event_id`).
   - **Thứ tự:** `ordering_key = consumer:aggregate_id`; `saas.claim_jobs()` chạy tối đa một job chưa kết thúc cho mỗi ordering key, cũ nhất trước. Do đó mỗi consumer nhận các event của một aggregate theo thứ tự commit, chính là thứ tự theo key mà Kafka cung cấp.
   - **Payload:** job mang một con trỏ (cluster nguồn, schema, `event_id`, `created_at`), không bao giờ mang payload nghiệp vụ, vì `saas.job` có thể được đọc bởi mọi role module của tenant (plumbing grant X-02) và các payload như `people.SalaryChanged` chứa số tiền (X-01). Worker phân phối đọc event qua kết nối của relay và chạy handler của consumer trong transaction, role và inbox riêng của module consumer.
   - **Đăng ký (subscription)** được khai báo trong code (`@EventConsumer(eventType, consumer)` ở mỗi module) và được biên dịch thành registry khi khởi động; thêm consumer không cần thay đổi hạ tầng. Consumer mới back-fill bằng cách re-sync từ API của chủ sở hữu.
   - **Lỗi:** retry theo hàm mũ (1 s … 10 phút, 10 lần) bởi job framework, sau đó chuyển `DEAD` = trạng thái dead-letter của Phase 1, kèm dashboard replay cho operator (NFR-REC-005). Một lần phân phối `DEAD` phải được replay trong cửa sổ outbox 7 ngày, nếu không consumer phải re-sync.
   - **Event của cluster nền tảng** (`saas.LegalParameterPackPublished`, `core.MasterDataReleased`) được relay enqueue trên mọi pool cluster và dedicated cluster với sentinel tenant.
   - **SLO:** độ trễ relay p95 < 5 s, độ trễ phân phối p95 < 10 s; cả hai hiển thị trên dashboard của operator.
4. **Phase 2 — Apache Kafka 4.x (KRaft) trên Strimzi (hoặc dịch vụ managed của nhà cung cấp) cùng Apicurio Registry**, được đưa vào khi điều kiện kích hoạt đầu tiên sau đây xảy ra: (a) connector máy chấm công trực tuyến / `hrm-ingest` (X-05 500 lượt chấm công/s), (b) webhook công khai hoặc bất kỳ consumer event bên ngoài nào, (c) độ trễ relay hoặc phân phối vượt SLO kéo dài, hoặc khối lượng queue `events` vượt ~2 M job/ngày mỗi cluster **[đo trong SP-2]**, (d) một consumer cần replay quá 7 ngày. Thiết kế Kafka như trong rev. 1: mỗi module producer và phiên bản chính một topic (`people.events.v1`), key `tenant_id:aggregate_id`, RF 3, `min.insync.replicas=2`, retention 14 ngày, `att.punch.raw`, DLT cho mỗi consumer group, MirrorMaker 2 cho DR. Việc chuyển đổi chỉ thay thế bước transport của relay (port `EventTransport`: `JobQueueTransport` → `KafkaTransport`); outbox, envelope, inbox và handler của consumer không thay đổi, và cả hai transport có thể chạy song song theo từng loại event trong quá trình cut-over.
5. **Envelope** như trong gap §6, bổ sung `causation_id` và cờ `initial_load` (import số dư đầu kỳ, ADR-0037); tên tương thích CloudEvents (Kafka header ở Phase 2, `headers jsonb` trong outbox).
6. **Schema:** mỗi event một JSON Schema trong `shared/contracts`; tương thích ngược và consumer-driven contract test được cưỡng chế **trong CI** ở Phase 1. Apicurio Registry được đưa vào cùng Kafka ở Phase 2 (cùng các schema, được đăng ký từ repository).
7. **Consumer:** khử trùng lặp qua inbox theo `(consumer, event_id)`, handler idempotent, kiểm tra phiên bản theo phiên bản của aggregate. Consumer không được giả định thứ tự đến trùng với thứ tự ngày hiệu lực (overview §7.2).
8. **Tối thiểu hóa payload:** giá trị nhạy cảm không được mang theo, trừ khi gửi cho consumer duy nhất được phép (ví dụ `people.SalaryChanged` → Payroll); ở Phase 1, payload không bao giờ rời khỏi dòng outbox của producer ngoại trừ khi vào handler của consumer đó; ở Phase 2, topic được giới hạn bằng ACL.
9. API in-process đồng bộ vẫn là mặc định cho các truy vấn theo chiều phụ thuộc được phép (ADR-0001).

## Các phương án đã cân nhắc

- **Giữ Kafka ở Phase 1 (rev. 1).** Bỏ được một bước migration sau này, nhưng phải trả toàn bộ chi phí vận hành cho loại tải mà pilot không có; bước migration nhỏ vì outbox đã tách producer khỏi transport. Bị loại cho Phase 1.
- **Event publication registry của Spring Modulith (JPA) với listener in-process.** Gần về ý tưởng, nhưng registry của nó là một bảng cho mỗi ứng dụng, không phải cho mỗi schema module, và không có chia sẻ công bằng theo tenant, làn có thứ tự hay replay bởi operator; chúng ta sẽ phải tự xây lại các phần đó. Bị loại; Modulith vẫn được dùng để kiểm tra ranh giới.
- **PostgreSQL LISTEN/NOTIFY làm bus.** Không bền vững, không hoạt động qua PgBouncer transaction mode; tối đa chỉ dùng làm tín hiệu đánh thức worker.
- **RabbitMQ ở Phase 1.** Thêm một broker phải vận hành, không có replay, không có phương án CDC; không có lợi ích so với job queue ở khối lượng pilot.
- **Debezium Outbox Event Router.** Đưa Kafka Connect vào luồng nghiệp vụ; không dùng trước Phase 2.

## Hệ quả

- + Phase 1 không có message broker phải vận hành, bảo mật, mirror hay nâng cấp; DR chính là DR của cơ sở dữ liệu (dòng outbox và job được nhân bản cùng cluster).
- + Phân phối, retry, dead letter, tính công bằng và giám sát tái sử dụng job framework (ADR-0011) vốn đằng nào cũng phải xây.
- + Code của consumer trung lập về transport, nên Kafka ở Phase 2 chỉ là thay đổi relay cộng với hạ tầng.
- − Queue `events` thêm tải ghi và làm phình `saas.job` (một dòng cho mỗi consumer mỗi event): giảm thiểu bằng claim theo lô, partial index, `fillfactor 80` và purge hằng ngày các job đã hoàn tất; đo trong SP-2.
- − Cửa sổ replay là 7 ngày (thời gian giữ outbox) thay vì 14 ngày của Kafka; các khoảng trống cũ hơn được xử lý bằng job re-sync.
- − Hạng mục công việc Phase 2: đưa Kafka vào (hạ tầng, `KafkaTransport`, Apicurio, mirror DR, runbook) ≈ 3–4 tuần công developer cộng phần vận hành.
- − Tính nhất quán sau cùng (eventual consistency) giữa các module vẫn phải được thể hiện trên UX (trạng thái chờ, checklist sẵn sàng).
- − Quản trị danh mục không thay đổi: chuyên viên phân tích tích hợp sở hữu việc đặt tên; v1.1 bổ sung `att.AttendancePeriodHoldGranted/Released`, `core.ReorgPackageStatusChanged`, `people.OnboardingReadinessChanged` (X-06); `people.PrimaryEmploymentChanged` được đề xuất cho R2-16 (DESIGN.md §7.3).
