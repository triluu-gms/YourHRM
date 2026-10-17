# ADR-0007: Workflow và approval engine tự phát triển trong Core

- **Trạng thái:** Đã chấp nhận (2026-10-16, OD-4)
- **Ngày:** 2026-10-16
- **Liên quan:** Core BRD §4.5 (BR-CORE workflow), NFR-CORE-004; BRD DP6, RK5; gap-analysis GAP-063; [architecture-overview](../architecture-overview.md) §9.2

## Bối cảnh

Core phải cung cấp phê duyệt cho nghỉ phép, làm thêm giờ, điều chỉnh công, thay đổi nhân sự, kỳ tính lương, yêu cầu tuyển dụng và nhiều loại khác: các bước tuần tự và any-of/all-of (MVP), nhánh song song (P2), điều kiện, xác định người phê duyệt theo tổ chức/vị trí/vai trò/phạm vi, ủy quyền (bao gồm tự động gợi ý từ đơn nghỉ phép), SLA tính theo thời gian làm việc, escalation, nhắc nhở, trả lại cho người yêu cầu, và link thao tác qua e-mail/Zalo (P2). Định nghĩa quy trình do quản trị viên HR cấu hình cho từng tenant, không phải do developer. BRD cảnh báo tính tổng quát của workflow engine là rủi ro phình phạm vi (RK5). Mục tiêu: gửi yêu cầu ≤ 500 ms p95, ≥ 50 lượt gửi/s mỗi node, không mất task.

## Quyết định

Xây dựng một **approval engine điều khiển bằng bảng** trong `core` (phần triển khai) với API/SPI phía client trong `shared/workflow`:

- Mô hình định nghĩa: loại yêu cầu → định nghĩa có phiên bản → các bước có thứ tự (chế độ, approver resolver, điều kiện bằng YFL, SLA, escalation, cho phép trả lại, cho phép ủy quyền). Các instance đang chạy giữ nguyên phiên bản định nghĩa của mình.
- Runtime: `workflow_instance`, `workflow_task`, `workflow_action` trong `core`; command là idempotent; timer (SLA, nhắc nhở, escalation) là job của scheduler (ADR-0011); SLA theo thời gian làm việc dùng SPI `WorkingCalendarProvider` của T&A.
- Tích hợp: module sở hữu khởi tạo instance qua `WorkflowService.start(requestType, subjectRef, context)` và phản ứng với event `core.WorkflowStepCompleted` / `core.WorkflowCompleted` trong transaction của chính mình.
- Giao diện: hộp thư phê duyệt (ESS/MSS), trình chỉnh sửa định nghĩa dạng danh sách bước theo form (không có canvas BPMN).
- Các tiến trình *hệ thống* chạy dài (saga) **không** được mô hình hóa trong workflow engine; chúng dùng process manager của module (ADR-0019).

## Các phương án đã cân nhắc

- **Flowable 7 (embedded):** BPMN/DMN trưởng thành, nhưng vẫn phải tự xây quản lý định nghĩa multi-tenant, quản lý phiên bản theo tenant và giao diện cấu hình thân thiện với HR; BPMN nặng hơn mức cần thiết; thêm ~200 bảng và một mô hình persistence thứ hai. Là phương án dự phòng mạnh nếu P3 cần mô hình hóa quy trình tùy ý.
- **Camunda 8:** cluster Zeebe và giấy phép cho môi trường production; gánh nặng vận hành và lưu trữ dữ liệu trong nước; bị loại.
- **Temporal:** rất tốt cho saga hệ thống, không phù hợp với phê duyệt của con người do tenant cấu hình; thêm một cluster.

## Hệ quả

- + Ngữ nghĩa phê duyệt khớp chính xác với BRD; cấu hình của tenant đơn giản và an toàn.
- + Không thêm cluster hay giấy phép; tái sử dụng YFL, scheduler, trung tâm thông báo, audit.
- − Chúng ta tự sở hữu code engine (ước tính 6–8 tuần công developer cho phạm vi MVP); nhánh song song ở P2 làm tăng độ phức tạp.
- − Các yêu cầu về tính năng BPM tổng quát phải bị từ chối hoặc kích hoạt đánh giá lại Flowable.
