# ADR-0006: Cưỡng chế phân quyền — snapshot được cache, scope predicate, masking trường dữ liệu

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Liên quan:** gap-analysis §9 A-5, GAP-010; NFR-CORE-002 (≤ 20 ms p95), NFR-PPL-006; [architecture-overview](../architecture-overview.md) §6.3; khóa thân thiện với phạm vi và closure table trong kiến trúc dữ liệu (DESIGN.md)

## Bối cảnh

Core sở hữu RBAC với phạm vi dữ liệu (bản thân, cấp dưới trực tiếp/toàn bộ cấp dưới, nhánh cây tổ chức, pháp nhân, địa điểm, tùy chỉnh) và masking trường dữ liệu (lương, ngân hàng, CCCD, sức khỏe). Mọi module, mọi báo cáo và mọi lần xuất dữ liệu phải áp dụng cùng một bộ quy tắc. Mục tiêu chi phí phụ trội là ≤ 20 ms p95 mỗi request. Mỗi tenant có tối đa 10.000 nhân viên và 2.000 đơn vị tổ chức.

## Quyết định

1. Core tính toán một **snapshot phân quyền hiệu lực** cho mỗi tài khoản người dùng: tập quyền, danh sách scope grant (theo nhóm quyền), field profile. Được cache trong Caffeine (60 s) và Valkey; bị vô hiệu hóa bởi `core.RoleAssignmentChanged`, `people.AssignmentChanged`, `core.OrgUnitChanged`, `core.ReorgActivated`.
2. **Kiểm tra thô** bằng `@RequiresPermission("pay.run.approve")` trên application service (Spring method security, đánh giá dựa trên snapshot).
3. **Phạm vi dữ liệu theo dòng** qua SPI `ScopePredicate` trong `shared/auth`: Core chuyển scope grant thành predicate thân thiện với index (đường dẫn tổ chức được materialise / closure key, tập employment id cho phạm vi nhỏ). Repository của module phải áp dụng predicate cho mọi danh sách/truy vấn dữ liệu liên quan đến cá nhân; rule ArchUnit gắn cờ các method repository trên aggregate có phạm vi mà không có tham số predicate.
4. **Masking trường dữ liệu** khi serialise: `@Sensitive(category)` trên các trường DTO; một Jackson module mask các giá trị không được field profile cho phép; giá trị đã mask không bao giờ rời khỏi server. Xuất dữ liệu và báo cáo dùng cùng profile.
5. **Kho dữ liệu báo cáo** nhận một bảng `report_scope(user_account_id, employment_id)` được materialise, duy trì từ các snapshot (ADR-0012).
6. **Truy cập hỗ trợ:** support grant trong `TenantContext` áp dụng profile "mask toàn bộ dữ liệu nhạy cảm" trừ khi grant của tenant cho phép một loại dữ liệu.
7. Các lần đọc dữ liệu nhạy cảm và các lần bị từ chối được audit bất đồng bộ qua audit outbox.

## Các phương án đã cân nhắc

- **Đẩy phạm vi dữ liệu xuống PostgreSQL RLS:** sẽ cần session setting theo từng user với danh sách id lớn; khiến execution plan không ổn định; RLS được dành riêng cho cách ly tenant.
- **Policy engine bên ngoài (OPA/Cedar) cho mỗi request:** linh hoạt nhưng thêm bước gọi mạng và một ngôn ngữ policy thứ hai; phạm vi vẫn cần chuyển sang SQL. Cân nhắc lại cho các quy tắc ABAC ở P3.
- **Masking ở frontend:** bị loại; dữ liệu vẫn rời khỏi server.

## Hệ quả

- + Chi phí khi cache hit < 1 ms; ngân sách tính toán khi cache nguội ≤ 20 ms (đo trong SP-2).
- + Một bộ quy tắc cho màn hình, API, xuất dữ liệu và BI.
- − Developer phải dùng predicate SPI một cách nhất quán; được cưỡng chế bằng test (bộ test phạm vi cho từng endpoint).
- − Việc vô hiệu hóa cache phải tin cậy; TTL 60 s giới hạn độ cũ của dữ liệu.
