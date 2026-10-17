# ADR-0019: Giao thức saga — chốt kỳ với close hold, nghỉ việc, tuyển mới, tái cơ cấu

- **Trạng thái:** Đã chấp thuận (2026-10-16, X-03 — payroll hold khi chốt kỳ; X-06 event mới)
- **Ngày:** 2026-10-16
- **Liên quan:** gap-analysis §7, GAP-017/019/027/028/035/036/040/053/070, §9 A-4; ADR-0004, ADR-0009, ADR-0011; [architecture-overview](../architecture-overview.md) §7.3–7.4

## Bối cảnh

Một số kết quả nghiệp vụ trải dài qua nhiều module với các transaction riêng biệt: chốt kỳ T&A ↔ Payroll (Attendance Result có phiên bản, bắt tay CLOSED/LOCKED, phát hiện dữ liệu cũ, truy lĩnh/truy thu, mở khoá), luồng nghỉ việc (T&A chốt cá nhân, People chấm dứt hợp đồng, Payroll quyết toán cuối cùng trong hạn luật định), tuyển mới (Recruitment/People → mức sẵn sàng ở Core/T&A/Payroll), và tái cơ cấu (gói của Core → thay đổi hàng loạt ở People → kích hoạt ở Core). Gap-analysis §7.2 bước 9 đề xuất rằng nếu T&A từ chối khoá khi phê duyệt, phê duyệt của Payroll sẽ bị rollback — tức là một rollback bất đồng bộ đối với một phê duyệt của con người.

## Quyết định

1. **Phong cách:** mỗi saga được điều phối bởi module sở hữu kết quả, thông qua một **process manager** được lưu bền (bảng trạng thái, các bước idempotent, timeout qua scheduler), xây trên một nền nhỏ trong `shared/common`. Phản ứng ở các module khác là các event handler idempotent thông thường.
2. **Chốt kỳ — close hold:** khi một kỳ chạy lương được *submit*, Payroll giữ (hold) đúng phiên bản Attendance Result qua một API T&A đồng bộ, idempotent. Trong khi bị giữ, T&A từ chối mở lại trừ khi Payroll giải phóng hold (kỳ chạy → `CALCULATED`, stale). Khi phê duyệt, bước `CLOSED → LOCKED` của T&A không thể thất bại vì phiên bản đã được đảm bảo. Mở khoá (đặc quyền, cần phê duyệt lần hai) là con đường duy nhất `LOCKED → CLOSED`; đảo ngược không động đến T&A. Event mới: `att.AttendancePeriodHoldGranted/Released`. Một job đối soát hằng đêm kiểm tra tính nhất quán giữa kỳ chạy và kỳ công.
3. **Nghỉ việc:** `FinalSettlementProcess` của Payroll bắt đầu khi có `people.TerminationScheduled` (đồng hồ hạn chót tính từ ngày chấm dứt, khoá legal pack), chờ `att.LeaverAttendanceClosed` và `people.EmployeeTerminated` (hoặc HR override kèm lý do), chạy quyết toán ngoài kỳ, và phát `pay.FinalSettlementStatusChanged`; cảnh báo ở mốc 50/80/100% hạn chót; bị huỷ bởi `people.TerminationCancelled`.
4. **Tuyển mới:** `HireProcess` của People phát `people.EmployeeHired(preview)`, theo dõi mức sẵn sàng (tài khoản người dùng, nhóm lương, lịch làm việc, hợp đồng, ngân hàng, mã số thuế) từ các event phía sau, phát `people.OnboardingReadinessChanged`, và phát `EmployeeHired` cuối cùng vào ngày bắt đầu làm việc; `HireCancelled` bù trừ.
5. **Tái cơ cấu:** `ReorgPackage` của Core chuyển `PENDING_MAPPING → ACTIVE | ACTIVATED_WITH_ERRORS` dựa trên `people.ReorgMappingCompleted`; phát `core.ReorgPackageStatusChanged`.
6. Mọi message của saga đều mang correlation id và phiên bản nghiệp vụ; mọi bước đều idempotent; trạng thái process hiển thị trong giao diện quản trị.

## Các phương án đã cân nhắc

- **Phê duyệt rồi rollback (gap §7.2 bước 9 như đã viết):** hoạt động được, nhưng đảo ngược một phê duyệt của con người một cách bất đồng bộ và gây bối rối cho người phê duyệt; giữ làm phương án dự phòng nếu BA từ chối hold.
- **Choreography thuần:** không ai sở hữu trạng thái tổng thể hay hạn chót; kém phù hợp với hạn quyết toán luật định.
- **Workflow engine / Temporal cho saga:** thêm hạ tầng; approval engine nội bộ chỉ dành cho phê duyệt của con người (ADR-0007).
- **Distributed transaction (XA) giữa các schema:** vi phạm tính tự chủ của module và cản trở việc tách module.

## Hệ quả

- + Không có rollback phê duyệt; hạn chót rõ ràng; trạng thái process quan sát được.
- − BA của T&A và Payroll cập nhật BRD của họ (gap §7) cho cơ chế hold, đã được chấp thuận là X-03 ngày 2026-10-16; ba event mới được thêm vào catalogue v1.1 (X-06).
- − Phải xây và kiểm thử code process manager cho từng saga (contract test + scenario test).
