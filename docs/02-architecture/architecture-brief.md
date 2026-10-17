# Bản định hướng kiến trúc từ PM (2026-10-16)

Đầu vào: [BRD](../01-requirements/brd/BRD.md), [BRD các module](../01-requirements/brd/modules/), [gap analysis](../01-requirements/brd/gap-analysis.md) (§5 bảng chủ sở hữu duy nhất, §6 danh mục event, §7 trình tự khóa kỳ, §9 đầu vào kiến trúc). Quyết định của nhà tài trợ: [decision-log](../00-project-management/decision-log.md).

## Các quyết định nền tảng (PM chốt; architect có thể phản biện qua ADR, nhưng phải thiết kế dựa trên chúng)
1. **Quyền sở hữu:** bảng chủ sở hữu duy nhất trong gap-analysis §5 được chấp nhận làm baseline làm việc. Điểm đáng chú ý: Legal Parameter Service nằm ở nền tảng; SaaS xác thực, Core phân quyền; T&A sở hữu lịch làm việc, công tác, phân công lịch làm việc; Payroll sở hữu thang/bậc lương, nhóm lương, lịch sử đóng BHXH; People sở hữu hồ sơ lương (bao gồm cờ GROSS/NET) và người phụ thuộc.
2. **Schema:** thêm schema `saas` cho dữ liệu nền tảng (tenant, gói dịch vụ, thuê bao, hóa đơn, bộ tham số pháp lý, dữ liệu danh mục dùng chung). Tập cuối cùng: `saas`, `core`, `hr`, `att`, `pay`, `rec` (+ kho dữ liệu báo cáo, tách riêng).
3. **Hình thái triển khai:** bắt đầu dưới dạng **modular monolith** (Spring Boot, mỗi domain một module Gradle/Maven, ranh giới được cưỡng chế). Các module chỉ giao tiếp qua API công bố và domain event, không bao giờ qua bảng của nhau. Worker tính lương và tiếp nhận dữ liệu chấm công phải có khả năng scale out độc lập.
4. **Tenancy:** mặc định dùng pool chung (`tenant_id` trên mọi dòng thuộc tenant + PostgreSQL RLS + lớp bảo vệ ở tầng ứng dụng). Gói Enterprise có cơ sở dữ liệu riêng với cùng schema, được chọn thông qua tenant routing. Toàn bộ dữ liệu cá nhân được lưu trữ tại Việt Nam.
5. **Event:** transactional outbox cho từng module, một event envelope chuẩn, và gap-analysis §6 là danh mục v1.
6. **Giá trị pháp lý:** có hiệu lực theo thời gian và được quản lý phiên bản; mọi kết quả tính lương ghi nhận phiên bản tham số đã dùng, và kết quả phải tái lập được trong 10 năm.
7. **MVP (Phase 1):** chỉ các tính năng lõi của Core, People, T&A, Payroll, cùng tenancy/identity/tham số pháp lý của nền tảng, web quản trị và web ESS responsive, và import số dư đầu kỳ. Không chuyển đổi từ Oracle (D-09); không có tính năng mobile (X-04, OD-9). Tuyển dụng thuộc P2.

## Phân chia trách nhiệm
| Chủ đề | Solution architect | Data architect |
|---|---|---|
| View context/container/component, bản đồ module | ✔ | |
| Công nghệ event (broker), outbox relay, saga (khóa kỳ, nghỉ việc, tuyển mới) | ✔ | bảng outbox/inbox |
| Identity, authN/authZ, cưỡng chế phạm vi dữ liệu | ✔ | RLS policy, khóa thân thiện với phạm vi |
| Payroll engine (sandbox công thức, điều phối kỳ tính lương, xử lý song song) | ✔ | lưu trữ snapshot/kết quả |
| Báo cáo & phân tích | lựa chọn công nghệ, pipeline | mô hình dữ liệu báo cáo |
| Frontend, ESS/mobile, API gateway, integration hub | ✔ | |
| Hạ tầng, môi trường, CI/CD, observability, DR | ✔ | backup/PITR, partitioning |
| Triển khai tenancy, khóa, effective dating, audit, mã hóa PII, lưu trữ/crypto-shredding | sử dụng | ✔ |
| Legal Parameter Service | API & caching | bảng & quản lý phiên bản |
| Import số dư đầu kỳ (thay cho chuyển đổi từ Oracle, D-09) | pipeline import & mẫu | bảng đích, kiểm tra hợp lệ & đối soát |

Đánh số ADR: solution architect 0001–0019, data architect 0020–0039. Định dạng: `docs/02-architecture/adr/NNNN-kebab-title.md` (Bối cảnh, Quyết định, Phương án thay thế, Hệ quả, Trạng thái: Đề xuất).
