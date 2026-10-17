# ADR-0028: Bảng transactional outbox và inbox theo từng module

- **Trạng thái:** Đề xuất (sửa đổi 2026-10-16, R2-01: gửi event ở Phase 1 qua `saas.job`, quyết định 7)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect (ADR-0004 sở hữu relay và Kafka)
- **Liên quan:** Architecture brief B5; gap-analysis §6 (envelope); NFR-PPL-002, NFR-CORE-004, NFR-REC-005; ADR-0004, ADR-0019; [DESIGN.md](../../../database/postgresql/DESIGN.md) §9.1; DDL `sys.create_outbox_inbox()`

## Bối cảnh

Mọi thay đổi nghiệp vụ đã commit phải sinh ra event tương ứng (không dual write), được gửi ít nhất một lần và được tiêu thụ một cách idempotent. ADR-0004 đã chọn một polling relay trong ứng dụng, giữ các dòng đã phát hành trong 7 ngày. Revision 2 của ADR-0004 (R2-01) gửi event ở Phase 1 qua các delivery job trên job queue PostgreSQL và bổ sung Kafka ở Phase 2. Bảng outbox thuộc nhóm bảng nóng nhất hệ thống: khoảng hai dòng cho mỗi thao tác ghi nghiệp vụ, được insert, update một lần, rồi loại bỏ.

## Quyết định

1. Mỗi schema module có `<schema>.outbox_event` và `<schema>.inbox_message`, được tạo bởi cùng một template procedure để chúng giống hệt nhau.
2. **Các cột outbox** mang envelope theo gap §6: `id` (= `event_id`, UUIDv7), `tenant_id`, `legal_entity_id`, `aggregate_type`, `aggregate_id`, `event_type` (được kiểm tra theo `<module>.<Aggregate><Verb>`), `schema_version`, `occurred_at`, `effective_date`, `correlation_id`, `causation_id`, `is_retro`, `person_id`, `employment_id`, `payload jsonb`, `headers jsonb` (bao gồm cờ `initial_load`, ADR-0037), `created_at` và `published_at`.
3. **Partition theo khoảng hằng ngày** trên `created_at`; partition cũ hơn 7 ngày bị **drop**. Không có `DELETE` và không có vacuum churn. Một partial index trên các dòng chưa phát hành phục vụ lượt quét `FOR UPDATE SKIP LOCKED` của relay.
4. **Inbox:** khử trùng lặp theo `(consumer, event_id)` kèm trạng thái, số lần thử và lỗi gần nhất. Partition hằng tháng giữ 90 ngày. Vì PK của bảng partition phải chứa partition key, template của consumer lấy `pg_advisory_xact_lock(hash(consumer, event_id))` và kiểm tra tồn tại trước khi áp dụng.
5. RLS áp dụng cho app role. Relay có một read policy riêng và chỉ có `UPDATE (published_at)`.
6. Tối thiểu hoá payload: id và tên các trường đã thay đổi; giá trị nhạy cảm chỉ gửi tới một consumer duy nhất được ủy quyền (ADR-0004 quyết định 8; topic giới hạn bằng ACL ở Phase 2). Không bao giờ chứa giá trị P3.
7. **Gửi event ở Phase 1 (ADR-0004 rev. 2):** relay, trong transaction thiết lập `published_at`, gọi `saas.enqueue_event_delivery()`, hàm này chèn một dòng `saas.job` cho mỗi consumer đã đăng ký (queue `events`, `idempotency_key = consumer:event_id`, `ordering_key = consumer:aggregate_id`). Job chỉ giữ một con trỏ (source cluster, schema, `event_id`, `created_at`), nên payload nghiệp vụ không bao giờ rời schema của producer ngoại trừ khi vào handler của consumer. `saas.claim_jobs()` phục vụ các làn có thứ tự (một job chưa kết thúc cho mỗi ordering key, cũ nhất trước). Việc khử trùng lặp ở inbox của quyết định 4 không thay đổi và cũng hấp thụ các lần gửi trùng sau khi relay bị crash.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Một bảng outbox dùng chung cho mọi module | Grant ghi chéo module; backlog của một module chặn các module khác; phá vỡ quyền sở hữu schema. |
| Debezium outbox router trên luồng nghiệp vụ | ADR-0004 chỉ giữ CDC cho reporting. Thiết kế bảng vẫn sẽ hoạt động với nó. |
| Xoá các dòng đã phát hành | Tải vacuum nặng vào giờ cao điểm; drop partition thì không tốn chi phí. |
| Inbox không có partition | Bảng tăng tới hàng trăm triệu dòng; purge bằng `DELETE` tốn kém như trên. |

## Hệ quả

- \+ Thay đổi trạng thái cùng event là nguyên tử; dọn dẹp rẻ; cấu trúc giống hệt nhau ở mọi nơi.
- − Tính duy nhất của inbox dựa vào template của consumer (advisory lock cộng kiểm tra) thay vì một PK thuần.
- − Tạo trước partition là một phụ thuộc vận hành (pg_partman hoặc `sys.create_range_partitions` qua scheduler); thiếu partition sẽ làm transaction nghiệp vụ thất bại một cách rõ ràng, đây là chủ đích.
