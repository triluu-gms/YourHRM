# YourHRM

Xây dựng hệ thống HRM mới trên cơ sở dữ liệu PostgreSQL, nghiệp vụ trên Java Spring Boot Framework, hệ thống báo cáo, frontend hiện đại và chuyên nghiệp, đáp ứng các quy định đặc thù của Việt Nam về tính lương, làm thêm giờ, bảo hiểm và thuế TNCN.

Kiến trúc cơ sở dữ liệu và ứng dụng hỗ trợ multi-tenant, cho phép xây dựng ứng dụng SaaS.

## Cấu trúc repository

| Thư mục | Mục đích | Phụ trách |
|---|---|---|
| `docs/` | Quản lý dự án, yêu cầu, kiến trúc, UX, kiểm thử, triển khai, hướng dẫn sử dụng | PM / BA / Architect |
| `database/postgresql/` | Mô hình dữ liệu đích: DDL, dữ liệu seed, kiểm thử schema, `DESIGN.md` | DB / Architect |
| `backend/` | API và nghiệp vụ, mỗi domain một module | Nhóm Backend |
| `frontend/` | Giao diện web, mỗi domain một module | Nhóm Frontend |
| `tests/` | Kiểm thử liên hệ thống: end-to-end, hiệu năng | QA |
| `infra/` | Docker, pipeline CI/CD, cấu hình theo từng môi trường | DevOps |
| `scripts/` | Script hỗ trợ cho developer và vận hành | Tất cả |

## Các module chức năng

Các module được đặt tương ứng với các schema PostgreSQL, để code, tài liệu và kiểm thử dùng chung một tên gọi.
Ví dụ các schema dưới đây:
| Module | DB schema | Phạm vi |
|---|---|---|
| `core` | `core` | Công ty, cây tổ chức, vị trí, người dùng / vai trò / quyền, quy trình & phê duyệt, thông báo, thiết lập, audit |
| `people` | `hr` | Hồ sơ cá nhân & nhân viên, phân công, hợp đồng, tiếp nhận / nghỉ việc, đánh giá, đào tạo, chi phí |
| `time-attendance` | `att` | Ngày lễ, ca làm việc, lịch phân ca, check-in, chấm công, làm thêm giờ, nghỉ phép |
| `payroll` | `pay` | Thành phần & cơ cấu lương, bảo hiểm, thuế TNCN, kỳ tính lương, phiếu lương, chi trả qua ngân hàng |
| `recruitment` | `rec` | Kế hoạch nhân sự, yêu cầu tuyển dụng, vị trí đang tuyển, ứng viên, phỏng vấn, thư mời nhận việc |

## Bắt đầu


> Không bao giờ commit file `.env`. Thông tin đăng nhập cơ sở dữ liệu Oracle cũ chỉ được lưu ở máy cục bộ.
