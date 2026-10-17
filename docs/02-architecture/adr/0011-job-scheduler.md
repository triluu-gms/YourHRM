# ADR-0011: Job scheduler và queue công bằng giữa các tenant trên PostgreSQL

- **Trạng thái:** Đề xuất (rev. 2026-10-16, R2-01: queue còn mang các lần phân phối event của Phase 1 — queue `events`, làn có thứ tự)
- **Ngày:** 2026-10-16
- **Liên quan:** gap-analysis GAP-034, §9 A-8; NFR-SAAS-005 (≤ 20% mỗi tenant), NFR-CORE-005/015, NFR-PAY-001; [architecture-overview](../architecture-overview.md) §9.5

## Bối cảnh

Nền tảng SaaS sở hữu job scheduler/queue. Các job bao gồm chunk tính lương, import, xuất dữ liệu, lô tài liệu, thông báo, kích hoạt effective dating lúc 00:00 ICT, chuyển kỳ, nhắc nhở/escalation, lưu trữ, handler cho DSR và các lời gọi connector. Nó phải nhận biết tenant (chia sẻ công bằng, quota), nhận biết trạng thái tạm ngưng, nằm trong cùng transaction với thay đổi nghiệp vụ, quan sát được (tiến độ), và scale worker theo chiều ngang.

## Quyết định

1. **Bảng queue trong PostgreSQL** với `tenant_id`, `queue`, `type`, `priority`, `payload` (có phiên bản), `state`, `attempts`, `not_before`, `idempotency_key`, `progress`. Vị trí: **theo từng cluster** (quyết định cùng Data architect, DESIGN.md §3.1, `saas.job` là LOCAL) để enqueue nằm trong cùng transaction với thay đổi nghiệp vụ. `ordering_key` tùy chọn: tối đa một job chưa kết thúc cho mỗi key được claim, cũ nhất trước (làn có thứ tự, dùng cho phân phối event).
2. **Worker** claim bằng `FOR UPDATE SKIP LOCKED` với **lựa chọn chia sẻ công bằng**: trong số các tenant có job sẵn sàng, chọn tenant có tỷ lệ đang chạy/được phép thấp nhất; giới hạn mỗi tenant = 20% slot của pool (tối thiểu 2; có thể dành slot riêng cho Enterprise); làn ưu tiên interactive > payroll > batch.
3. **Pool:** `payroll`, `general`, `events` (event bus của Phase 1, ADR-0004 rev. 2), `reporting`, và `integration` (Phase 2, ADR-0016) — các Deployment riêng của image worker; KEDA scale theo độ sâu của queue.
4. **Trigger:** db-scheduler (an toàn khi chạy cluster) cho lịch cron/lịch biểu; lịch tạo ra các job trong queue.
5. **Ngữ nghĩa:** at-least-once với handler idempotent; retry theo hàm mũ; trạng thái dead kèm retry bởi operator; heartbeat và visibility timeout; hủy; API tiến độ (SSE).
6. **Trạng thái tenant:** job của tenant `SUSPENDED_LOCKED` bị tạm dừng, ngoại trừ danh sách cho phép (truy cập phiếu lương, xuất dữ liệu, DSR).

## Các phương án đã cân nhắc

- **Quartz:** không có tính công bằng/quota; chế độ cluster dùng nhiều khóa.
- **JobRunr:** trải nghiệm developer tốt; tính công bằng và quota multi-tenant đòi hỏi tính năng bản Pro hoặc fork.
- **Kafka làm job queue:** không có enqueue trong transaction, retry/hủy/tiến độ theo từng job không thuận tiện. (Ngược lại, job queue được dùng làm transport event ở Phase 1, ADR-0004 rev. 2.)
- **Chỉ dùng Spring Batch:** là framework xử lý theo lô, không phải scheduler có tính công bằng.

## Hệ quả

- + Tính công bằng và quota là tính năng hạng nhất; enqueue trong transaction tránh mất job hoặc job ma.
- + Không cần thêm hạ tầng.
- − Tải PostgreSQL do polling: giảm thiểu bằng tín hiệu đánh thức ngắn `LISTEN/NOTIFY`, claim theo lô và partial index; đo trong SP-1/SP-2.
- − Chúng ta tự sở hữu code scheduler (~3–4 tuần công developer).
