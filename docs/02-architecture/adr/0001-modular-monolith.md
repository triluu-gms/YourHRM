# ADR-0001: Modular monolith với Spring Modulith và các runtime role được scale riêng

- **Trạng thái:** Đã chấp nhận (2026-10-16, baseline 3 của bản định hướng; ngoại lệ X-02 cho dữ liệu tham chiếu toàn cục chỉ đọc). **Amendment 1 (2026-10-16, R2-01) — Đã chấp nhận (R3-11):** runtime role `ingest` được hoãn sang Phase 2 (quyết định 5).
- **Ngày:** 2026-10-16
- **Người quyết định:** Solution architect (người đề xuất), PM, tech lead
- **Liên quan:** Bản định hướng kiến trúc B2/B3; [architecture-overview](../architecture-overview.md) §3; ADR-0004, ADR-0011

## Bối cảnh

YourHRM có sáu module nghiệp vụ (`saas-platform`, `core`, `people`, `time-attendance`, `payroll`, `recruitment`) tương ứng với sáu schema PostgreSQL. Baseline của PM chốt một modular monolith với ranh giới được cưỡng chế, trong đó các module chỉ giao tiếp qua API công bố và domain event, còn việc tính lương và tiếp nhận dữ liệu chấm công có thể scale độc lập. Nhóm có quy mô nhỏ đến vừa và phải bàn giao các tính năng lõi của bốn module MVP cùng nền tảng và chức năng import số dư đầu kỳ (không chuyển đổi từ Oracle, D-09) trong tiến độ gấp. Các luồng xuyên module có hiệu lực theo thời gian (tuyển mới, khóa kỳ, nghỉ việc) diễn ra thường xuyên và hưởng lợi từ lời gọi in-process với kiểu dữ liệu chặt chẽ.

## Quyết định

1. Xây dựng **một ứng dụng Spring Boot** tổ chức dưới dạng Gradle multi-project: mỗi module một subproject (`backend/src/modules/*`) và mỗi thư viện shared kernel một subproject (`backend/src/shared/*`).
2. Dùng **Spring Modulith 2.x** để khai báo module, kiểm tra phụ thuộc (`ApplicationModules.verify()`), chạy integration test theo phạm vi module và sinh tài liệu module; bổ sung các rule **ArchUnit** (không truy cập `internal` xuyên module, không dùng `double` trong payroll, không có SQL xuyên schema).
3. Chiều phụ thuộc compile-time được phép: `recruitment → payroll → time-attendance → people → core → saas-platform → shared/*`; các module tính năng có thể gọi trực tiếp `saas-platform`. Luồng ngược chiều dùng event (schema trong `shared/contracts`) hoặc SPI do bên tiêu thụ khai báo.
4. Mỗi module sở hữu schema của mình; database role của module không có grant trên schema khác. **Ngoại lệ duy nhất (X-02, chấp nhận ngày 2026-10-16):** `SELECT` chỉ đọc trên các bảng tham chiếu toàn cục của Core/SaaS được liệt kê trong ADR-0021 (quốc gia, tiền tệ, đơn vị hành chính, ngân hàng, danh mục mã, tỷ giá, dòng tenant của chính mình), được bảo vệ bởi một DB test (`sys.cross_schema_grant_violations()`) và một rule ArchUnit. Việc enqueue job và append audit là phần plumbing của nền tảng, được liệt kê trong cùng bảng.
5. Triển khai cùng một image ở các **runtime role** scale độc lập: `api` (REST stateless) và `worker` (job, partition tính lương, outbox relay, job phân phối event, import và xuất file; nhiều Deployment cho mỗi worker pool). Một ứng dụng **`ingest` mỏng, tách riêng cho dữ liệu chấm công trực tuyến là role của Phase 2 (Amendment 1, R2-01)**: Phase 1 không có connector thiết bị trực tuyến, và file xuất từ thiết bị được worker pool `general` import thông qua import engine của Core. Điều khoản cấu trúc của baseline B3 (tiếp nhận dữ liệu chấm công scale riêng) vẫn được giữ: `src/ingest/` và Helm chart `hrm-ingest` được tạo khi bắt đầu connector Phase 2, không cần thay đổi module.

## Các phương án đã cân nhắc

- **Microservices cho từng module ngay từ đầu.** Bị loại: giao dịch phân tán và chi phí vận hành (6+ đơn vị triển khai, DB riêng cho từng service, hợp đồng qua mạng) đối với một nhóm chưa ổn định được ranh giới domain; làm chậm MVP.
- **Monolith không có cấu trúc.** Bị loại: ranh giới bị xói mòn, mâu thuẫn với baseline chủ sở hữu duy nhất và khiến việc tách module sau này không thể thực hiện.
- **Chỉ dùng JPMS module.** Bị loại nếu là cơ chế duy nhất: tích hợp với Spring kém; không bao quát hợp đồng event hay việc chia nhỏ test.

## Hệ quả

- + Triển khai đơn giản, lời gọi in-process, một transaction cho mỗi use case của module, refactor nhanh.
- + Worker (và ở Phase 2 là ingestion) scale riêng mà không cần codebase riêng.
- + Một module có thể được tách ra sau này (API dựa trên DTO; event đã đi qua outbox và một bus trung lập về transport, Kafka từ Phase 2, ADR-0004 rev. 2).
- − Một lỗi hoặc rò rỉ bộ nhớ ở một module ảnh hưởng đến toàn bộ tiến trình `api`; được giảm thiểu bằng bulkhead (worker pool riêng, timeout) và observability tốt.
- − Đòi hỏi kỷ luật CI: kiểm tra Modulith và ArchUnit làm fail build.
- − Tất cả module dùng chung một release train; flag theo tenant (ADR-0003) tách việc mở tính năng khỏi việc triển khai.
