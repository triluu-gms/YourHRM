# YourHRM — Điều lệ dự án (Bản nháp v0.1, 2026-10-16)

## 1. Tầm nhìn
Một nền tảng HRM SaaS multi-tenant cho các tổ chức Việt Nam (từ SME đến doanh nghiệp lớn, khoảng 20–10.000 nhân viên mỗi tenant), mặc định xử lý đúng các quy định đặc thù của Việt Nam về tính lương, làm thêm giờ, BHXH và thuế TNCN, đồng thời đủ linh hoạt để cấu hình theo chính sách riêng của từng khách hàng.

## 2. Ràng buộc cố định (từ README)
- Cơ sở dữ liệu: PostgreSQL. Mỗi module một schema: `core`, `hr`, `att`, `pay`, `rec`, cộng thêm `saas` cho dữ liệu nền tảng (tenant, gói thuê bao, bộ tham số pháp lý, dữ liệu danh mục dùng chung) — *điều chỉnh được phê duyệt ngày 2026-10-16 (GAP-014, X-07)*.
- Backend: Java Spring Boot, mỗi domain một module (`backend/src/modules/<module>`), dùng chung `auth`, `audit`, `workflow`, `common`.
- Hệ thống báo cáo + frontend web hiện đại, chuyên nghiệp.
- Multi-tenant ngay từ ngày đầu (SaaS).
- Không chuyển đổi dữ liệu từ hệ thống cũ (quyết định D-09, 2026-10-16): YourHRM được xây dựng như một hệ thống mới. Khách hàng nạp số dư đầu kỳ (nhân viên, lương/thuế TNCN/BHXH lũy kế từ đầu năm, số ngày phép còn lại) qua các mẫu import Excel.

## 3. Nhóm dự án & trách nhiệm
| Vai trò | Sản phẩm bàn giao |
|---|---|
| PM | Điều lệ, BRD tổng hợp, sổ đăng ký gap, lộ trình, RAID |
| BA – Core | `docs/01-requirements/brd/modules/01-core.md` |
| BA – People | `docs/01-requirements/brd/modules/02-people.md` |
| BA – Time & Attendance | `docs/01-requirements/brd/modules/03-time-attendance.md` |
| BA – Payroll | `docs/01-requirements/brd/modules/04-payroll.md` |
| BA – Recruitment | `docs/01-requirements/brd/modules/05-recruitment.md` |
| BA – SaaS Platform & các vấn đề xuyên suốt | `docs/01-requirements/brd/modules/06-saas-platform.md` |
| Chuyên viên phân tích tích hợp | `docs/01-requirements/brd/gap-analysis.md` |
| Solution architect | `docs/02-architecture/architecture-overview.md`, ADR |
| Data architect | `database/postgresql/DESIGN.md`, ADR về dữ liệu |

## 4. Quy ước tài liệu
- Mã yêu cầu: `BR-<MOD>-NNN` (nghiệp vụ), `NFR-<MOD>-NNN` (phi chức năng). MOD ∈ `CORE, PPL, ATT, PAY, REC, SAAS`.
- Độ ưu tiên: MoSCoW. `M` = cần cho MVP / tenant trả phí đầu tiên.
- Mọi BRD module có cùng các mục (xem §5) để có thể truy vết gap một cách máy móc.
- Các giá trị pháp lý (tỷ lệ, mức trần, lương tối thiểu vùng, biểu thuế, giảm trừ) **không bao giờ được hard-code**: chúng là các tham số có hiệu lực theo thời gian. Mọi giá trị được trích dẫn trong BRD được đánh dấu `[verify-legal]` cho đến khi được chuyên gia tư vấn lao động/thuế Việt Nam kiểm tra.
- Ngôn ngữ tài liệu: tiếng Anh; thuật ngữ giao diện được ghi thêm tiếng Việt khi hữu ích.

## 5. Mẫu BRD module
1. Mục đích & phạm vi (trong / ngoài)
2. Tác nhân & persona
3. Quy trình nghiệp vụ (đánh số, luồng chính + các ngoại lệ chính)
4. Yêu cầu nghiệp vụ (bảng: ID, yêu cầu, độ ưu tiên, ghi chú)
5. Quy định tuân thủ của Việt Nam (kèm căn cứ pháp lý)
6. Các thực thể nghiệp vụ chính (tên, mục đích, thuộc tính chính)
7. Giao tiếp giữa các module — **Tiêu thụ** (cái gì / từ module nào / khi nào) và **Cung cấp** (cái gì / cho module nào / khi nào)
8. Báo cáo & phân tích
9. Yêu cầu phi chức năng riêng của module
10. Giả định, câu hỏi mở, rủi ro

## 6. Phân giai đoạn (đề xuất)
- **Phase 0** — BRD, gap analysis, bản nháp kiến trúc (sprint này).
- **MVP (Phase 1)** — chỉ các tính năng lõi của Core + People + T&A (cơ bản) + Payroll (lương tháng theo quy định VN, BHXH/BHYT/BHTN, thuế TNCN); web quản trị + web ESS responsive; onboarding tenant; import số dư đầu kỳ. Không có tính năng mobile (X-04, OD-9).
- **Phase 2** — Tuyển dụng, đánh giá, đào tạo, chi phí, phân ca nâng cao, toàn bộ tính năng mobile (app native, check-in qua mobile/GPS/selfie), trình thiết kế báo cáo, white-label, BI (Superset).
- **Phase 3** — Tích hợp marketplace, trợ lý AI, mở rộng ra khu vực.
