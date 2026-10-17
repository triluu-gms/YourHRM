# Hướng dẫn ước lượng: Backlog Giai đoạn 1 (v0.1, 2026-10-16)

Hướng dẫn mà mọi người ước lượng đều tuân theo, để story point mang cùng một ý nghĩa ở mọi module.

## 1. Phạm vi
- Đầu vào: mọi yêu cầu được xếp hạng **P1** trong [mvp-scope.md](../brd/mvp-scope.md) §9 (260 BR + 58 NFR), lấy từ BRD của các module trong [../brd/modules/](../brd/modules/).
- Đọc [decision-log.md](../../00-project-management/decision-log.md) để nắm các quyết định ảnh hưởng đến phạm vi: không có mobile, không có tích hợp trực tiếp (live), không có SSO, chỉ xuất file + e-mail, nhập dữ liệu chấm công từ Excel và từ file xuất của thiết bị, phiếu lương PDF có mật khẩu, nhập số dư đầu kỳ (không migration), BHXH nhiều hợp đồng là một tham số pháp lý.
- Kiến trúc: [architecture-overview.md](../../02-architecture/architecture-overview.md) v0.3, [DESIGN.md](../../../database/postgresql/DESIGN.md) v0.3.

## 2. Các file backlog
| File | Người phụ trách | Nội dung |
|---|---|---|
| `core-backlog.md` | BA Core | BR-CORE P1 |
| `people-backlog.md` | BA People | BR-PPL P1 |
| `time-attendance-backlog.md` | BA T&A | BR-ATT P1 |
| `payroll-backlog.md` | BA Payroll | BR-PAY P1 |
| `saas-platform-backlog.md` | BA SaaS | BR-SAAS P1 |
| `technical-enablers-backlog.md` | Solution architect | Công việc kỹ thuật xuyên suốt và các NFR P1: walking skeleton, tenancy/RLS, outbox/event bus, Keycloak, runtime job queue, (formula engine và điều phối kỳ chạy lương nằm trong `payroll-backlog.md`; Legal Parameter Service nằm trong `saas-platform-backlog.md` — đã điều chỉnh 2026-10-16) CI/CD, môi trường, observability, bảo mật, kiểm thử hiệu năng, spike SP-0/1/2/4/5/6/7/8/9 |

## 3. Định dạng story
Mỗi file gồm:
1. **Bảng epic:** Epic ID (`EP-<MOD>-NN`), tên, mục tiêu, số story, story point (SP).
2. **Bảng story:** `ID | Epic | Story | BR/NFR refs | SP | Depends on | Notes`
   - ID: `US-<MOD>-NNN` (MOD = CORE, PPL, ATT, PAY, SAAS, TECH).
   - Story: "Là <persona>, tôi muốn <năng lực> để <lợi ích>." Một dòng, tiếp theo là 1–3 tiêu chí chấp nhận ngắn, phân tách bằng `;`.
   - BR/NFR refs: mọi yêu cầu P1 mà story đáp ứng. Một story có thể bao phủ nhiều BR; một BR lớn được tách thành nhiều story.
   - Depends on: ID story hoặc enabler thuộc bất kỳ backlog nào, kể cả `US-TECH-…`.
3. **Kiểm tra độ bao phủ:** danh sách mọi BR/NFR P1 của module cùng các ID story bao phủ chúng. Mọi ID P1 đều phải xuất hiện; mục nào chưa được bao phủ phải nêu rõ lý do.
4. **Tổng cộng:** SP theo từng epic và tổng, kèm **ghi chú rủi ro** (các story có độ bất định cao, đánh dấu `[high-uncertainty]`).

## 4. Thang story point (Fibonacci, tương đối)
Một point bao gồm toàn bộ story cho một developer có đủ kỹ năng: API và logic backend, database migration, màn hình frontend, unit test và integration test, cùng kiểm thử ở mức developer. Không bao gồm BA, kiểm thử hệ thống của QA, PM hay DevOps (các phần này nằm trong hệ số overhead, §6).

| SP | Mốc tham chiếu | Ví dụ |
|---|---|---|
| **1** | Cấu hình hoặc thêm một trường vào màn hình và API có sẵn | Thêm "dân tộc" vào hồ sơ cá nhân |
| **2** | Danh sách hoặc tra cứu chỉ đọc đơn giản, quy tắc kiểm tra hợp lệ nhỏ | Danh mục ngân hàng có tìm kiếm |
| **3** | Một thực thể CRUD: danh sách + form + API + kiểm tra hợp lệ + test | Trung tâm chi phí |
| **5** | Thực thể có lịch sử theo ngày hiệu lực **hoặc** một luồng phê duyệt **hoặc** một mẫu nhập Excel | Lịch sử phân công công việc; đơn nghỉ phép có phê duyệt |
| **8** | Quy tắc nghiệp vụ hoặc phép tính phức tạp với nhiều trường hợp, hoặc một file xuất theo quy định pháp luật | Phân loại OT thành 6 loại; xuất file VSS D02-LT |
| **13** | Rất phức tạp: logic ở mức engine, nhiều trường hợp biên về pháp lý | Tính thuế TNCN hằng tháng gồm cả cư trú & làm việc đồng thời nhiều nơi |
| **>13** | Không được phép: tách story | |

Quy tắc:
- Ước lượng so với **các ví dụ tham chiếu**, không theo giờ.
- Các engine dùng chung (workflow, import engine, tài liệu/PDF, thông báo, audit, tham số pháp lý, formula engine) chỉ được xây dựng **một lần** trong backlog của module sở hữu. Story của các module khác chỉ *sử dụng* chúng: chỉ tính công việc đăng ký và cấu hình.
- Logic theo quy định pháp luật có `[verify-legal]` được cộng thêm 1 bậc Fibonacci nếu quy định vẫn chưa rõ; đánh dấu `[high-uncertainty]`.
- Mẫu số dư đầu kỳ được ước lượng trong module sở hữu (People: hồ sơ nhân viên; Payroll: lũy kế từ đầu năm (YTD)/lịch sử BHXH/khoản vay; T&A: số dư phép và OT lũy kế từ đầu năm).

## 5. Định nghĩa hoàn thành
Code đã được merge với test đạt, đã kiểm thử cách ly tenant, đã áp dụng audit và phân quyền, có nhãn vi/en, API được tài liệu hóa, BA đã review trên môi trường test.

## 6. Quy đổi point sang thời gian (PM tổng hợp)
- Giả định **1 SP ≈ 1 ngày công developer** của một full-stack developer trình độ trung bình. Đây là giả định lập kế hoạch, sẽ hiệu chỉnh lại sau sprint 2.
- Overhead triển khai cộng thêm vào công sức developer: kiểm thử hệ thống của QA, hỗ trợ BA, UX, DevOps, PM ≈ **+40%**.
- Kế hoạch 16 tuần: **12 tuần xây dựng** (6 sprint hai tuần) + 4 tuần kiểm thử tích hợp hệ thống, UAT, chuyển đổi pilot và ổn định.
- Hệ số tập trung 0.7 (họp, review, sửa lỗi): một developer hoàn thành ≈ 0.7 × 10 = **7 SP mỗi sprint**.
