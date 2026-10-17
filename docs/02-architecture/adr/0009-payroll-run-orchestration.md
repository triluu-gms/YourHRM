# ADR-0009: Điều phối kỳ tính lương — snapshot đầu vào và tính toán song song theo partition

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Liên quan:** NFR-SAAS-002, NFR-PAY-001/002/003, NFR-SAAS-017; gap-analysis §7, GAP-031, GAP-064; ADR-0008, ADR-0010, ADR-0011, ADR-0019; lưu trữ snapshot/kết quả trong kiến trúc dữ liệu (DESIGN.md); [architecture-overview](../architecture-overview.md) §8

## Bối cảnh

Một kỳ tính lương tháng cho 10.000 nhân viên phải hoàn thành trong ≤ 15 phút (1.000 nhân viên trong ≤ 2 phút; một nhân viên ≤ 5 s) trong khi các tenant khác cũng đang tính toán trên cùng pool với tải gấp 3 lần trong khoảng ngày 25–5. Kết quả phải tái lập được trong ≥ 10 năm và được ghi nhận phiên bản bộ tham số pháp lý, cấu hình và code. Đầu vào đến từ People (bi-temporal), T&A (Attendance Result có phiên bản), dữ liệu đầu vào biến đổi của Payroll và Legal Parameter Service. Quan hệ lao động đồng thời ở nhiều pháp nhân đòi hỏi các quy tắc xuyên pháp nhân cho từng cá nhân.

## Quyết định

1. **State machine của kỳ tính lương** theo gap §7.1 với các trạng thái con kỹ thuật `SNAPSHOTTING`, `CALCULATING`, `FAILED` giữa `DRAFT` và `CALCULATED`.
2. **Snapshot trước tiên:** khi tính FINAL, kỳ tính lương niêm phong một snapshot bất biến (tập nhân viên, dữ liệu People tại cuối kỳ theo trạng thái được biết tại watermark, các phiên bản Attendance Result, dữ liệu đầu vào biến đổi, kết quả `resolveSet` của LPS, phiên bản cấu hình đã biên dịch, phiên bản engine, các flag hành vi tính toán, kết quả kỳ trước/lũy kế từ đầu năm). Engine chỉ đọc từ snapshot.
3. **Partitioning:** tập nhân viên được chia thành các chunk khoảng 250 nhân viên, nhóm theo `person_id` để các quan hệ lao động đồng thời nằm chung một chunk; mỗi chunk là một job trên worker pool `payroll` (ADR-0011, chia sẻ công bằng với giới hạn 20% slot mỗi tenant).
4. **Thực thi chunk:** nạp khối lượng lớn phần dữ liệu của chunk (một truy vấn cho mỗi loại đầu vào), đánh giá DAG đã biên dịch cho từng nhân viên trong bộ nhớ, ghi kết quả bằng `COPY` vào vùng staging của kỳ; idempotent (xóa và ghi lại theo từng chunk).
5. **Hoàn tất:** tổng hợp, kiểm tra hợp lệ (lương tối thiểu, thực lĩnh âm, thiếu tài khoản ngân hàng/MST, ngưỡng chênh lệch), chuyển sang `CALCULATED`, publish `pay.PayrollRunStatusChanged`.
6. **Tính tất định:** không dùng đồng hồ/số ngẫu nhiên trong kernel; thứ tự ổn định; `BigDecimal` với quy tắc làm tròn tường minh cho từng phần tử; các dòng kết quả được băm.
7. **Kernel có phiên bản:** `payroll/engine` là một artifact có phiên bản riêng; các kernel cũ vẫn nạp được cho job `ReproduceRun`; golden pack được gắn tag theo phiên bản kernel.
8. **Truy lĩnh/truy thu (retro):** tính lại các kỳ đã phê duyệt bị ảnh hưởng với đầu vào "theo trạng thái được biết hiện tại" và tham số của chính kỳ đó; chuyển phần chênh lệch của các phần tử vào kỳ hiện tại.
9. **Net-to-gross:** nghịch đảo giải tích tuyến tính từng đoạn + chia đôi số nguyên (≤ 40 vòng lặp), lấy mức gross nhỏ nhất có net ≥ mục tiêu.
10. **Mục tiêu thiết kế** 10k nhân viên ≤ 5 phút khi cao điểm, kiểm chứng trong SP-1; NFR 15 phút là mức trần cứng.

## Các phương án đã cân nhắc

- **Tính bằng SQL / stored procedure:** nhanh với phép cộng đơn giản nhưng công thức do tenant cấu hình; ảnh hưởng đến khả năng tái lập và kiểm thử; gắn logic vào DB.
- **Spring Batch:** dùng được cho việc chia chunk, nhưng job repository của nó không công bằng giữa các tenant và trùng lặp với scheduler của chúng ta; có thể tái sử dụng có chọn lọc các reader/writer của nó.
- **Tính toán phân tán (Spark/Flink):** không cần thiết ở quy mô này; tốn chi phí vận hành.
- **Kafka để phân phối công việc:** không có enqueue trong transaction cùng trạng thái kỳ; bị loại để dùng job queue PostgreSQL.

## Hệ quả

- + Hiệu năng có thể dự đoán, scale theo số worker slot; cách ly giữa các tenant.
- + Khả năng audit và tái lập đầy đủ từ snapshot + các phiên bản được ghi nhận.
- − Dung lượng lưu trữ snapshot tăng dần (10 năm × tất cả các kỳ); thiết kế lưu trữ và nén thuộc trách nhiệm của Data architect.
- − Tính lại cho một nhân viên phải nạp và niêm phong lại một snapshot từng phần; được thiết kế để ≤ 5 s.
