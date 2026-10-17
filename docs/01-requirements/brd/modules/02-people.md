# YourHRM — BRD phân hệ: Nhân sự (`people`, schema `hr`)

| Mục | Giá trị |
|---|---|
| Tài liệu | `docs/01-requirements/brd/modules/02-people.md` |
| Phiên bản | Bản nháp v0.2 — 2026-10-16 (v0.1 chỉnh sửa nhỏ ngày 2026-10-16 cho D-09: ghi chú BR-PPL-010, IN-28, NFR-PPL-007, Q3, RK2) |
| Ghi chú thay đổi | v0.2 (2026-10-16): áp dụng bảng chủ sở hữu duy nhất đã được phê duyệt ([gap-analysis](../gap-analysis.md) §5, quyết định **R2-02**): People sở hữu mức đãi ngộ đã thỏa thuận bao gồm GROSS/NET và lương net thỏa thuận, dưới quyền `compensation`, chỉ được thay đổi qua quyết định/phụ lục, mọi lượt xem/thay đổi đều được ghi audit (BR-PPL-040/044, OUT-07; #24, GAP-047); công tác được lấy từ T&A (BR-PPL-113, bỏ OUT-30; #6); cam kết 08/CK và ủy quyền quyết toán thuế TNCN do Payroll sở hữu (bỏ OUT-18; #4); việc cấp tài khoản người dùng do Core thực hiện dựa trên sự kiện của People (bỏ OUT-21; #9); thời gian làm việc tính trợ cấp thôi việc do Payroll tính (BR-PPL-074; #29); consent/DSR/lưu trữ nằm trong registry của SaaS (#27); tham số pháp lý lấy từ Legal Parameter Service của SaaS (IN-10; #1); tài liệu qua dịch vụ tài liệu của Core (IN-24/25; #15b); các trang ESS nằm trong shell của SaaS (BR-PPL-120; #17). GAP-048: cư trú thuế và dữ liệu đầu vào cho phép thử 183 ngày (BR-PPL-023, OUT-15). GAP-053: thực thi ánh xạ tái cơ cấu và `people.ReorgMappingCompleted` (BR-PPL-039, OUT-35). R2-16: làm việc đồng thời tại nhiều pháp nhân (BR-PPL-004). R2-18: các chức năng cơ bản của People luôn bật. Các chỉnh sửa được đánh dấu "(changed 2026-10-16, R2-02 §5 #n)" hoặc bằng mã quyết định/gap. Các cột ưu tiên không đổi (PM đồng bộ). |
| Chủ sở hữu | BA – People |
| Tiền tố yêu cầu | `BR-PPL-NNN`, `NFR-PPL-NNN` |
| Liên quan | Điều lệ dự án §4–§6; BRD 01-core, 03-time-attendance, 04-payroll, 05-recruitment, 06-saas-platform |
| Trạng thái | Chờ PM / chuyên viên phân tích tích hợp review (gap analysis) |

> **Giai đoạn:** giai đoạn triển khai của mọi yêu cầu chỉ được xác định trong [mvp-scope.md](../mvp-scope.md) §9; MoSCoW ở đây là mức ưu tiên nghiệp vụ. (2026-10-16)

> Mọi giá trị pháp lý (thời hạn, tỷ lệ phần trăm, số tiền, hạn chót) nêu ở đây chỉ là ví dụ. Chúng được lưu dưới dạng **tham số có hiệu lực theo ngày, tenant có thể ghi đè**, không bao giờ hard-code, và được đánh dấu `[verify-legal]` cho đến khi chuyên gia tư vấn lao động/thuế Việt Nam xác nhận.

---

## 1. Mục đích & phạm vi

### 1.1 Mục đích
Phân hệ People là **hệ thống gốc (system of record) cho mọi người đang làm việc, đã từng làm việc hoặc sắp làm việc** cho một tenant: họ là ai (person), họ được tuyển dụng theo hình thức nào (employment, hợp đồng), họ ở vị trí nào và được trả lương ở mức ngạch/bậc nào (phân công, lịch sử lương), và mọi việc xảy ra với họ trong suốt vòng đời lao động (tiếp nhận → thay đổi → đánh giá / đào tạo / kỷ luật → nghỉ việc). Phân hệ phát hành các sự kiện nhân sự chính thức mà Chấm công (Time & Attendance), Tính lương (Payroll), Tuyển dụng (Recruitment) và Core tiêu thụ.

### 1.2 Trong phạm vi
1. **Person và nhân viên**: mỗi người thật có một Person trên mỗi tenant; một hoặc nhiều Employment (tái tuyển dụng, làm việc đồng thời tại nhiều pháp nhân của cùng tenant).
2. **Thông tin cá nhân**: định danh (CCCD / căn cước, xác thực VNeID mức 2, hộ chiếu cho người nước ngoài), mã số thuế cá nhân (MST cá nhân), số sổ BHXH (số sổ BHXH / mã số BHXH), tài khoản ngân hàng, địa chỉ, thành viên gia đình & người phụ thuộc giảm trừ thuế TNCN, người liên hệ khẩn cấp, học vấn, chứng chỉ/giấy phép hành nghề, giấy phép lao động (người nước ngoài).
3. **Phân công công việc có hiệu lực theo ngày**: tuyển mới, điều chuyển, thăng chức, giáng chức, biệt phái, tạm thời chuyển người lao động làm công việc khác (Điều 29), kiêm nhiệm, thay đổi tuyến báo cáo.
4. **Mức đãi ngộ đã thỏa thuận (system of record)**: bản ghi lương bao gồm cơ sở GROSS/NET và lương net thỏa thuận, lịch sử lương / thay đổi ngạch bậc (diễn biến lương) dưới dạng sự kiện nhân sự cấp cho bảng lương (changed 2026-10-16, R2-02 §5 #24).
5. **Hợp đồng lao động** theo Bộ luật Lao động 2019: thử việc, xác định thời hạn (≤36 tháng), không xác định thời hạn, quy tắc chuyển đổi, phụ lục, tạm hoãn, hợp đồng lao động điện tử.
6. **Danh mục công việc tiếp nhận / nghỉ việc (onboarding / offboarding checklist)**.
7. **Thôi việc & chấm dứt hợp đồng**: thời hạn báo trước, điều kiện hưởng trợ cấp thôi việc / trợ cấp mất việc làm, kích hoạt quyết toán cuối cùng, bàn giao.
8. **Kỷ luật & khen thưởng** (kỷ luật / khen thưởng) bao gồm trình tự xử lý kỷ luật theo luật định.
9. **Đánh giá / hiệu suất**: chu kỳ KPI và OKR, xếp loại, hiệu chỉnh (calibration).
10. **Đào tạo**: khóa học, buổi học, điểm danh, chứng chỉ, cam kết đào tạo.
11. **Đề nghị thanh toán chi phí & tạm ứng** (tạm ứng công tác / hoàn ứng — Phase 2 trừ khi pilot cần); tạm ứng lương và khoản vay thuộc Payroll (changed 2026-10-16, R2-02 §5 #21/#21b).
12. **Các trang ESS và MSS** cho các tính năng People (hồ sơ, trang chủ, yêu cầu), được host trong shell ESS/MSS của saas-platform (changed 2026-10-16, R2-02 §5 #17).
13. **Tài liệu & văn bản nhân sự**: quyết định, hợp đồng, giấy xác nhận, văn bản theo mẫu, chữ ký điện tử.
14. **Dòng thời gian nhân viên**: một chế độ xem theo trình tự thời gian cho tất cả sự kiện nhân sự.
15. **Báo cáo theo luật định của Việt Nam**: sổ quản lý lao động, báo cáo tình hình sử dụng lao động 6 tháng (Nghị định 145/2020), thông báo biến động lao động hằng tháng gửi Trung tâm Dịch vụ việc làm.
16. **Bảo vệ dữ liệu cá nhân**: kiểm soát dữ liệu nhạy cảm theo Luật BVDLCN 2025 / Nghị định 13/2023; People đăng ký các trường của mình vào danh mục dữ liệu cá nhân (PD inventory) của saas-platform và lưu tham chiếu tới consent registry, điều phối DSR và bộ lập lịch lưu trữ của saas-platform (changed 2026-10-16, R2-02 §5 #27).

**Nền tảng cơ bản (R2-18):** các chức năng cơ bản của People — person, employment, phân công, hợp đồng, bản ghi lương, người phụ thuộc, tài khoản ngân hàng, hồ sơ thuế/BHXH — luôn được bật, bất kể tenant đăng ký những phân hệ nào, vì Payroll và T&A phụ thuộc vào chúng (changed 2026-10-16, R2-18).

### 1.3 Ngoài phạm vi (thuộc phân hệ khác)
| Chủ đề | Phân hệ sở hữu |
|---|---|
| Pháp nhân, cây tổ chức, vị trí, danh mục công việc, tài khoản người dùng của tenant và việc cấp tài khoản, vai trò, workflow engine, thông báo, kho audit, dịch vụ tài liệu (bao gồm điều phối chữ ký điện tử), import engine | `core` (changed 2026-10-16, R2-02 §5 #9, #12, #15b) |
| Ca làm việc, xếp ca, chấm công vào/ra, làm thêm giờ, loại nghỉ/số dư phép, ngày lễ, **gán lịch làm việc**, **yêu cầu công tác** | `time-attendance` (changed 2026-10-16, R2-02 §5 #6, #26) |
| Thành phần lương, thang bảng lương và khung lương, **gán nhóm trả lương (pay group)**, đóng & khai báo BHXH/BHYT/BHTN (D02-LT v.v.) và **lịch sử đóng BHXH**, tính & quyết toán thuế TNCN, **cam kết 08/CK-TNCN và ủy quyền quyết toán thuế TNCN**, phiếu lương, file thanh toán ngân hàng, việc tính tiền quyết toán cuối cùng và **thời gian làm việc tính trợ cấp thôi việc / mất việc làm** | `payroll` (changed 2026-10-16, R2-02 §5 #4, #20, #25, #29) |
| Yêu cầu tuyển dụng, ứng viên, phỏng vấn, offer | `recruitment` |
| Cấp tenant, gói/giấy phép, object storage, **Legal Parameter Service**, các connector chữ ký điện tử/hợp đồng điện tử và VNeID, shell ESS/MSS, consent registry, PD inventory, điều phối DSR, bộ lập lịch lưu trữ, job scheduler, kho báo cáo | `saas-platform` (changed 2026-10-16, R2-02 §5 #1, #15, #16, #17, #18, #27) |
| Hạch toán chi phí vào sổ cái | Kế toán bên ngoài (qua payroll / export) |

---

## 2. Tác nhân & persona

| Tác nhân | Mô tả | Tương tác chính |
|---|---|---|
| Quản trị viên nhân sự (C&B / HR Ops) | Duy trì hồ sơ nhân viên, hợp đồng, quyết định | Tuyển, thay đổi, chấm dứt, tạo tài liệu, báo cáo theo luật định |
| HR Business Partner | Hỗ trợ quản lý về thay đổi, kỷ luật, đánh giá | Phê duyệt/khởi tạo điều chuyển, vụ việc kỷ luật, hiệu chỉnh |
| Trưởng phòng / Giám đốc nhân sự | Sở hữu chính sách nhân sự, ký quyết định (hoặc người được ủy quyền) | Phê duyệt, ký, xem phân tích |
| Quản lý trực tiếp (MSS) | Quản lý cấp dưới trực tiếp/gián tiếp | Phê duyệt yêu cầu, khởi tạo thay đổi, đánh giá, phê duyệt chi phí |
| Nhân viên (ESS) | Bất kỳ nhân viên đang làm việc nào | Xem/cập nhật dữ liệu của mình, người phụ thuộc, liên kết phiếu lương, đánh giá, đề nghị thanh toán, xin nghỉ việc |
| Nhân viên cũ | Đã nghỉ việc, giữ quyền truy cập ESS hạn chế theo chính sách | Tải tài liệu (ví dụ: xác nhận công tác, chứng từ khấu trừ thuế TNCN), tái tuyển dụng |
| Người sắp nhận việc (pre-hire) | Đã nhận offer, chưa bắt đầu làm việc | Hoàn thành biểu mẫu tiếp nhận, tải lên tài liệu, đưa ra consent |
| Người đại diện theo pháp luật / người ký được ủy quyền | Ký hợp đồng và quyết định thay mặt pháp nhân | Ký điện tử |
| Tài chính / Kế toán | Chi tạm ứng/chi phí | Xem các đề nghị đã duyệt, quyết toán tạm ứng |
| Giảng viên / Điều phối L&D | Tổ chức đào tạo | Khóa học, buổi học, điểm danh, cam kết đào tạo |
| Đại diện công đoàn (CĐCS) | Tham gia cuộc họp xử lý kỷ luật | Được ghi nhận là người tham dự |
| Cán bộ bảo vệ dữ liệu (DPO) | Giám sát việc xử lý dữ liệu cá nhân | Sổ consent, DSAR, nhật ký truy cập |
| Hệ thống (tích hợp) | Các phân hệ khác / job định kỳ | Tiêu thụ/phát sự kiện |

---

## 3. Quy trình nghiệp vụ

### P1. Tuyển dụng (tuyển mới, từ Recruitment hoặc trực tiếp)
1. Recruitment phát `rec.CandidateHired` chỉ tới People (hoặc HR tạo tuyển trực tiếp) (changed 2026-10-16, R2-02 §5 #5, #24).
2. Hệ thống đối chiếu Person hiện có theo CCCD / hộ chiếu / mã số thuế / số BHXH; nếu tìm thấy → luồng **tái tuyển dụng hoặc tuyển đồng thời** (P2), nếu không thì tạo Person.
3. HR hoàn thiện Employment: pháp nhân, mã nhân viên, ngày vào làm, ngày tính thâm niên, loại hình làm việc, nhóm người lao động.
4. HR tạo Assignment đầu tiên (vị trí, đơn vị tổ chức, ngạch/bậc công việc, quản lý, địa điểm làm việc, trung tâm chi phí, chế độ thời giờ làm việc theo hợp đồng). Lịch làm việc do T&A gán và pay group do Payroll gán (changed 2026-10-16, R2-02 §5 #25, #26).
5. HR tạo Hợp đồng (hợp đồng thử việc hoặc hợp đồng lao động) và **Bản ghi lương** từ các điều khoản offer, bao gồm cơ sở trả lương GROSS/NET và lương net thỏa thuận; People là system of record cho dữ liệu này và Payroll nhận biết qua `SalaryChanged` (changed 2026-10-16, R2-02 §5 #24; GAP-047).
6. Người sắp nhận việc nhận lời mời ESS: điền dữ liệu cá nhân, tài khoản ngân hàng, người phụ thuộc, tải tài liệu lên, đưa ra các consent theo Luật BVDLCN.
7. Khởi tạo checklist tiếp nhận (P9).
8. Khi được phê duyệt và đến ngày hiệu lực: Employment chuyển sang `Active`; phát các sự kiện `EmployeeHired`, `AssignmentChanged`, `SalaryChanged`, `ContractSigned`.
- **Ngoại lệ**: xung đột trùng person → review gộp thủ công; không đến nhận việc trước ngày bắt đầu → hủy tuyển, sự kiện `HireCancelled`; thiếu dữ liệu bắt buộc tại ngày bắt đầu → employment vẫn active nhưng bị gắn cờ "chưa đầy đủ" và payroll được cảnh báo.

### P2. Tái tuyển dụng / làm việc đồng thời
1. Tìm thấy Person trùng khớp.
2. Nếu người đó có Employment đang active trong **cùng** pháp nhân → chặn (dùng thay đổi phân công thay thế).
3. Nếu có Employment active ở **pháp nhân khác** thuộc cùng tenant → làm việc đồng thời (Phase 1, R2-16); HR ghi nhận employment chính **theo từng mục đích** (giảm trừ gia cảnh thuế TNCN; bảo hiểm xã hội), có hiệu lực theo ngày, và việc tham gia bảo hiểm theo từng loại (BHXH, BHYT, BHTN, BHTNLĐ-BNN) cho mỗi employment (BR-PPL-004); mỗi pháp nhân tự khấu trừ thuế TNCN trên khoản chi trả của mình (changed 2026-10-16, R2-16).
4. Nếu là nhân viên cũ → kiểm tra cờ đủ điều kiện tái tuyển; tạo Employment mới với ngày vào làm mới; thời gian làm việc trước đó có thể được công nhận để tính thâm niên (theo chính sách tenant).
- **Ngoại lệ**: tái tuyển trong khoảng thời gian đã được chi trả trợ cấp thôi việc → cảnh báo (số năm làm việc tính trợ cấp thôi việc trong tương lai loại trừ các giai đoạn đã chi trả).

### P3. Thay đổi công việc (điều chuyển, thăng chức, giáng chức, biệt phái, tạm thời chuyển làm công việc khác, kiêm nhiệm)
1. HR / quản lý khởi tạo yêu cầu thay đổi với ngày hiệu lực và mã lý do.
2. Workflow chuyển tới người phê duyệt (core workflow).
3. Khi được phê duyệt, một **Quyết định** được tạo; phụ lục hợp đồng được tạo khi điều khoản hợp đồng thay đổi.
4. Tạo dòng Assignment mới có hiệu lực theo ngày; dòng trước được đóng ngày kết thúc (không ghi đè).
5. Phát sự kiện `AssignmentChanged` (+ `SalaryChanged` nếu lương thay đổi).
- **Ngoại lệ**: ngày hiệu lực hồi tố → gắn cờ để payroll truy lĩnh/truy thu; tạm thời chuyển làm công việc khác vượt giới hạn cộng dồn theo luật mà không có sự đồng ý → chặn; các thay đổi có ngày hiệu lực tương lai có thể bị hủy trước ngày hiệu lực (`AssignmentChangeCancelled`).

### P4. Thay đổi lương / ngạch bậc
1. Được kích hoạt bởi thăng chức, xét duyệt hằng năm, kết quả đánh giá, đạt thử việc, hoặc đột xuất.
2. Người dùng có quyền `compensation` (C&B / HR được ủy quyền) ghi nhận ngạch/bậc mới, cơ sở trả lương (GROSS/NET), lương cơ bản hoặc lương net thỏa thuận, lương đóng BHXH, các khoản phụ cấp được hưởng, ngày hiệu lực, lý do.
3. Kiểm tra đối chiếu với thang bảng lương / khung lương của payroll và mức lương tối thiểu vùng (gói pháp lý) (changed 2026-10-16, R2-02 §5 #1, #7).
4. Phê duyệt → quyết định hoặc phụ lục hợp đồng → sự kiện `SalaryChanged`. Bản ghi lương **chỉ** được thay đổi thông qua quyết định hoặc phụ lục hợp đồng đã phê duyệt; mọi lượt xem và thay đổi đều được audit. Payroll đóng băng mức lương đã dùng trong mỗi kỳ chạy lương (changed 2026-10-16, R2-02 §5 #24).

### P5. Vòng đời hợp đồng
1. Hợp đồng thử việc (hoặc điều khoản thử việc) → ghi nhận kết quả thử việc trước ngày kết thúc → thông báo kết quả.
2. Đạt → tạo hợp đồng lao động (xác định hoặc không xác định thời hạn); không đạt → nghỉ việc (P8) không cần báo trước.
3. Bộ giám sát hết hạn cảnh báo HR N ngày trước khi hợp đồng xác định thời hạn kết thúc; HR chọn gia hạn / chuyển đổi / chấm dứt.
4. Quy tắc chuyển đổi: sau 2 hợp đồng xác định thời hạn liên tiếp, hợp đồng tiếp theo phải là không xác định thời hạn (trừ các ngoại lệ theo luật).
5. Tiếp tục làm việc sau khi hết hạn mà không có hợp đồng mới → hệ thống gắn cờ rủi ro tự động chuyển đổi.
6. Phụ lục cho các thay đổi; tạm hoãn vì các lý do theo luật định.
7. Hợp đồng điện tử: gửi ký điện tử qua nền tảng; lưu file PDF đã ký; đồng bộ trạng thái.

### P6. Chu kỳ đánh giá (KPI / OKR)
1. HR cấu hình chu kỳ (kỳ, đối tượng, mẫu, thang xếp loại, trọng số).
2. Đặt mục tiêu → quản lý phê duyệt → rà soát giữa năm → tự đánh giá → quản lý đánh giá → hiệu chỉnh → xếp loại cuối cùng → xác nhận.
3. Công bố kết quả (`AppraisalFinalised`) để xét lương / thưởng.

### P7. Kỷ luật & khen thưởng
1. Ghi nhận vi phạm với ngày, bằng chứng.
2. Hệ thống kiểm tra thời hiệu xử lý và các quy tắc về đối tượng được bảo vệ.
3. Mời họp xử lý kỷ luật (nhân viên, đại diện công đoàn) → ghi biên bản.
4. Ban hành quyết định về hình thức kỷ luật; nếu có thiệt hại vật chất, lập phương án bồi thường.
5. Khen thưởng: đề xuất → phê duyệt → quyết định; khen thưởng bằng tiền được gửi sang payroll.

### P8. Nghỉ việc (thôi việc, chấm dứt, hết hạn hợp đồng, nghỉ hưu, tử vong, sa thải, không đạt thử việc, thỏa thuận chấm dứt)
1. Khởi tạo: nhân viên nộp đơn nghỉ việc qua ESS, hoặc HR/quản lý khởi tạo chấm dứt.
2. Hệ thống tính thời hạn báo trước bắt buộc theo loại hợp đồng và lý do; kiểm tra việc báo trước; gắn cờ nhân viên thuộc đối tượng được bảo vệ.
3. Workflow phê duyệt; tạo quyết định chấm dứt.
4. Checklist nghỉ việc (P9): bàn giao, trả tài sản, ngày thu hồi quyền truy cập.
5. Ghi nhận ngày làm việc cuối cùng; sự kiện `TerminationScheduled`; vào ngày đó phát `EmployeeTerminated`.
6. Kích hoạt quyết toán cuối cùng sang payroll kèm điều kiện hưởng trợ cấp thôi việc / mất việc làm, căn cứ pháp lý, các giai đoạn làm việc và các giai đoạn đã được chi trả (Payroll tính thời gian làm việc và số tiền trợ cấp thôi việc), cờ thanh toán phép chưa nghỉ, tạm ứng và khoản hoàn trả cam kết đào tạo còn tồn (changed 2026-10-16, R2-02 §5 #29).
7. Trả lại giấy tờ: yêu cầu chốt sổ BHXH, xác nhận công tác, yêu cầu chứng từ khấu trừ thuế TNCN.
- **Ngoại lệ**: rút đơn nghỉ việc trước ngày hiệu lực (có sự đồng ý của người sử dụng lao động) → `TerminationCancelled`; chấm dứt với nhân viên được bảo vệ → chặn trừ khi có ngoại lệ hợp pháp được lập hồ sơ.

### P9. Checklist tiếp nhận / nghỉ việc
1. Mẫu theo pháp nhân / nhóm nhân viên, gồm các đầu việc, người phụ trách (HR, IT, quản lý, nhân viên), số ngày đến hạn.
2. Khởi tạo khi tuyển/chấm dứt; theo dõi đầu việc; nhắc việc quá hạn; ghi nhận hoàn thành.

### P10. Đề nghị thanh toán chi phí & tạm ứng
1. Nhân viên đề nghị tạm ứng (mục đích, số tiền, ngày dự kiến quyết toán) → phê duyệt → Tài chính chi.
2. Nhân viên nộp đề nghị thanh toán kèm chứng từ (dữ liệu hóa đơn điện tử) → liên kết với tạm ứng → quản lý / Tài chính phê duyệt.
3. Số tiền thực trả được hoàn (qua payroll hoặc Tài chính); tạm ứng chưa quyết toán quá hạn → yêu cầu khấu trừ gửi payroll (có sự đồng ý của nhân viên).

### P11. Báo cáo lao động theo luật định
1. Hằng tháng: tổng hợp biến động lao động (tuyển mới/chấm dứt) cho thông báo gửi Trung tâm Dịch vụ việc làm.
2. 6 tháng: tổng hợp báo cáo tình hình sử dụng lao động (Nghị định 145/2020) theo từng pháp nhân → HR review → xuất theo mẫu quy định → ghi nhật ký nộp.

### P12. Yêu cầu về dữ liệu cá nhân
1. Nhân viên yêu cầu truy cập/chỉnh sửa/rút lại consent qua ESS.
2. DPO/HR xem xét; chỉnh sửa qua phê duyệt; việc rút consent chỉ áp dụng cho xử lý không bắt buộc, có hiển thị hệ quả.

---

## 4. Yêu cầu nghiệp vụ

### 4.1 Person & employment
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-001 | Hệ thống phải lưu đúng một bản ghi Person cho mỗi cá nhân thực trên mỗi tenant, được định danh duy nhất bằng số định danh quốc gia (số định danh cá nhân 12 chữ số trên CCCD) hoặc, với người nước ngoài, số hộ chiếu + quốc tịch. | M | Kiểm tra trùng khi tạo/import; chức năng gộp có audit. |
| BR-PPL-002 | Một Person có thể có nhiều Employment theo thời gian (tái tuyển dụng) và đồng thời tại các pháp nhân khác nhau của cùng tenant; tối đa một Employment active trên mỗi pháp nhân tại bất kỳ ngày nào. | M | |
| BR-PPL-003 | Mỗi Employment phải có: pháp nhân, mã nhân viên (duy nhất trong tenant, mẫu cấu hình được theo pháp nhân), ngày vào làm, ngày vào làm ban đầu, ngày tính thâm niên, trạng thái lao động (Pre-hire, Active, Suspended, Notice, Terminated), nhóm người lao động (người Việt Nam / người nước ngoài / chuyên gia biệt phái), loại hình làm việc (toàn thời gian, bán thời gian, thời vụ, thực tập, cộng tác viên - hợp đồng dịch vụ). | M | Cộng tác viên (hợp đồng dịch vụ) không phải là người lao động theo luật lao động nhưng cần được payroll xử lý thuế TNCN. |
| BR-PPL-004 | Với việc làm việc đồng thời tại hai hoặc nhiều pháp nhân của cùng tenant (hỗ trợ cơ bản trong Phase 1, R2-16), một người có **tối đa một employment đang hiệu lực trên mỗi pháp nhân** (BR-PPL-002). HR phải chỉ định **employment chính theo từng mục đích**, có hiệu lực theo ngày (valid_from / valid_to): mục đích `PIT_FAMILY_DEDUCTION` (giảm trừ bản thân và người phụ thuộc) và mục đích `SOCIAL_INSURANCE`. Tại bất kỳ ngày nào cũng có đúng một employment chính cho mỗi người theo mỗi mục đích; hai mục đích có thể trỏ tới các employment khác nhau. Các employment khác được pháp nhân tương ứng trả lương và khấu trừ thuế mà không có giảm trừ gia cảnh. Ngoài ra, **việc tham gia bảo hiểm phải biểu diễn được theo từng loại bảo hiểm (BHXH, BHYT, BHTN, BHTNLĐ-BNN) trên từng employment**, có hiệu lực theo ngày, được suy ra từ tham số pháp lý `insurance.multi_contract_rule` theo từng loại bảo hiểm (BHXH, BHYT, BHTN, BHTNLĐ-BNN), với các giá trị `FIRST_CONTRACT` hoặc `HIGHEST_SALARY` hoặc `EACH_EMPLOYER` (Legal Parameter Service, 06 PR-06). Mặc định: BHXH, BHTN và BHYT = `FIRST_CONTRACT` (quyết định của nhà tài trợ R3-03), BHTNLĐ-BNN = `EACH_EMPLOYER`; tất cả đều `[verify-legal]` chờ chuyên gia tư vấn lao động (changed 2026-10-16, R3-03). Hệ thống phải ngăn việc người phụ thuộc hoặc giảm trừ bản thân được kê khai qua hai employment cùng lúc, và phải phát **`people.PrimaryEmploymentChanged`** (OUT-36) mỗi khi employment chính được thiết lập hoặc thay đổi cho một mục đích. (changed 2026-10-16, R2-16; architecture round-2 v0.3, DESIGN.md §7.3) | M | Payroll tiêu thụ employment chính theo mục đích và việc tham gia theo loại (BR-PAY-081). Quy tắc bảo hiểm cho nhiều hợp đồng theo Luật BHXH 2024 / Luật BHYT `[verify-legal]` (BA trưởng). Giới hạn làm thêm giờ hằng năm liên pháp nhân là Phase 2 (R2-08). Tính phí đếm mỗi người một lần trên mỗi pháp nhân (D-10). |
| BR-PPL-005 | Khi tái tuyển dụng, hệ thống phải hiển thị lịch sử làm việc trước đó, lý do nghỉ việc và cờ đủ điều kiện tái tuyển, đồng thời cho phép HR chọn có tính thời gian làm việc trước đó vào thâm niên hay không (theo chính sách). | M | |
| BR-PPL-006 | Hệ thống phải duy trì dòng thời gian nhân viên bất biến liệt kê mọi sự kiện nhân sự (tuyển dụng, phân công, lương, hợp đồng, kỷ luật, khen thưởng, đánh giá, đào tạo, nghỉ không lương dài hạn/tạm hoãn, nghỉ việc) với ngày hiệu lực, ngày nhập, người thực hiện và tài liệu nguồn. | M | Read model được xây dựng từ sự kiện. |

### 4.2 Thông tin cá nhân
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-010 | Ghi nhận định danh: họ tên (tiếng Việt có dấu + dạng không dấu), ngày sinh, giới tính, quốc tịch, dân tộc, tôn giáo (không bắt buộc, nhạy cảm), quê quán, địa chỉ thường trú & nơi ở hiện tại theo đơn vị hành chính hai cấp sau năm 2025 (tỉnh / xã). | M | Danh mục đơn vị hành chính lấy từ dữ liệu tham chiếu của core; địa chỉ 3 cấp cũ được giữ cho import số dư đầu kỳ và tài liệu cũ (changed 2026-10-16, D-09). `[verify-legal]` |
| BR-PPL-011 | Ghi nhận giấy tờ tùy thân với loại (CCCD/căn cước, hộ chiếu, giấy phép lao động, thẻ tạm trú), số, ngày cấp, nơi cấp, ngày hết hạn, ảnh scan; cảnh báo trước khi hết hạn (số ngày cấu hình được). | M | |
| BR-PPL-012 | Hỗ trợ xác thực danh tính dựa trên VNeID (đọc mã QR định danh điện tử mức 2 / chip NFC) khi connector của nền tảng được bật; ghi nhận trạng thái, phương thức và thời điểm xác thực. | S | Connector từ saas-platform. |
| BR-PPL-013 | Ghi nhận mã số thuế cá nhân; mặc định bằng số định danh cá nhân 12 chữ số khi pháp luật coi hai mã này là tương đương, giữ MST 10 chữ số cũ cho lịch sử. | M | Thông tư 86/2024/TT-BTC `[verify-legal]` |
| BR-PPL-014 | Ghi nhận số sổ BHXH / mã số BHXH, số thẻ BHYT và nơi đăng ký khám chữa bệnh ban đầu; ghi nhận trạng thái lưu giữ sổ BHXH (người sử dụng lao động giữ / đã trả). | M | |
| BR-PPL-015 | Ghi nhận một hoặc nhiều tài khoản ngân hàng (ngân hàng, chi nhánh, số tài khoản, tên chủ tài khoản) với ngày hiệu lực và cách chia lương (số tiền hoặc %); có đúng một tài khoản nhận lương mặc định; tên chủ tài khoản phải khớp với tên nhân viên (cảnh báo). | M | Danh mục ngân hàng lấy từ dữ liệu tham chiếu của core. |
| BR-PPL-016 | Ghi nhận thành viên gia đình (quan hệ, họ tên, ngày sinh, số định danh, mã số thuế, nghề nghiệp, liên hệ) và gắn cờ những người đăng ký là **người phụ thuộc giảm trừ thuế TNCN** với tháng bắt đầu giảm trừ, tháng kết thúc và hồ sơ chứng minh; kiểm tra theo quy tắc điều kiện người phụ thuộc (ví dụ: con dưới 18 tuổi, hoặc từ 18 tuổi trở lên đang đi học/khuyết tật không có thu nhập; cha mẹ/vợ chồng không có thu nhập vượt ngưỡng). | M | Tham số ngưỡng `[verify-legal]` |
| BR-PPL-017 | Việc đăng ký và thay đổi người phụ thuộc phải được HR phê duyệt và tạo sự kiện `DependantChanged`; hệ thống phải tạo mẫu/danh sách đăng ký người phụ thuộc để nộp cơ quan thuế. | M | Mẫu 07/ĐK-NPT-TNCN hoặc mẫu hiện hành `[verify-legal]` |
| BR-PPL-018 | Ghi nhận người liên hệ khẩn cấp (tối thiểu 1 người cho nhân viên đang làm việc, cấu hình được). | M | |
| BR-PPL-019 | Ghi nhận học vấn (trình độ, cơ sở đào tạo, chuyên ngành, năm tốt nghiệp, bản scan bằng cấp), chứng chỉ & giấy phép hành nghề (loại, đơn vị cấp, số, ngày cấp/hết hạn) có cảnh báo hết hạn; chứng chỉ huấn luyện an toàn lao động được gắn cờ khi công việc yêu cầu. | M | |
| BR-PPL-020 | Ghi nhận dữ liệu người lao động nước ngoài: số giấy phép lao động / giấy xác nhận miễn giấy phép, thời hạn, chức danh trên giấy phép, pháp nhân; chặn ngày kết thúc phân công vượt thời hạn giấy phép nếu không gia hạn; cảnh báo 45/60 ngày trước khi hết hạn (cấu hình được). | M | Nghị định 219/2025/NĐ-CP (thay thế 152/2020) `[verify-legal]` |
| BR-PPL-021 | Ghi nhận các thuộc tính về tình trạng đặc biệt làm căn cứ cho các quy tắc ở hạ nguồn: đang mang thai/nuôi con dưới 12 tháng tuổi (kèm ngày), khuyết tật, chưa thành niên (dưới 18 tuổi), người lao động cao tuổi, đoàn viên công đoàn, đảng viên/đoàn viên (không bắt buộc), phân loại nghề nặng nhọc/độc hại. | M | Cấp cho T&A (giảm giờ làm) và các chế độ bảo vệ. |
| BR-PPL-022 | Nhân viên có thể yêu cầu thay đổi dữ liệu cá nhân qua ESS; các trường được cấu hình sẽ cập nhật trực tiếp, các trường khác cần HR phê duyệt kèm tệp minh chứng; giá trị trước đó được giữ lại theo ngày hiệu lực. | M | |
| BR-PPL-023 | **Cư trú thuế.** HR phải duy trì, cho mỗi người, **tình trạng cư trú thuế** có hiệu lực theo ngày (cư trú / không cư trú) với căn cứ (≥ 183 ngày trong một năm dương lịch hoặc 12 tháng liên tục, có nơi ở thường xuyên, thuê nhà ≥ 183 ngày), **(các) ngày nhập cảnh và (các) ngày xuất cảnh** Việt Nam, và số ngày có mặt tại Việt Nam theo năm (không bắt buộc). Các thay đổi do HR phê duyệt và được phát trong `PersonalDataChanged` (OUT-15). People không tính phép thử 183 ngày: Payroll chạy báo cáo kiểm tra 183 ngày và đề xuất thay đổi ngược lại cho HR, HR phê duyệt tại đây (BR-PAY-065). (new 2026-10-16, GAP-048) | M | Luật Thuế TNCN; Thông tư 111/2013 `[verify-legal]`. Chủ yếu cho chuyên gia nước ngoài và người lao động nước ngoài. |

### 4.3 Phân công & bố trí trong tổ chức
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-030 | Phân công phải có hiệu lực theo ngày (từ/đến) với đầy đủ lịch sử; không ghi đè tại chỗ các dòng lịch sử; việc đính chính được ghi nhận là đính chính kèm lý do. | M | |
| BR-PPL-031 | Mỗi phân công gồm: pháp nhân, đơn vị tổ chức, vị trí (từ core), công việc, ngạch/cấp bậc công việc, quản lý trực tiếp (theo nhân viên hoặc theo vị trí), địa điểm làm việc, trung tâm chi phí (có thể chia %), chế độ thời giờ làm việc **theo hợp đồng** (toàn/bán thời gian, số giờ mỗi tuần — dùng trong hợp đồng và BR-PPL-035), FTE %, loại phân công (chính, kiêm nhiệm, biệt phái, tạm thời chuyển làm công việc khác). Phân công **không có pay group** (Payroll gán, `pay.PayGroupAssigned`) và **không có lịch làm việc** (gán lịch của T&A) (changed 2026-10-16, R2-02 §5 #25, #26). | M | |
| BR-PPL-032 | Hỗ trợ danh mục lý do thay đổi: điều chuyển, thăng chức, giáng chức, biệt phái, tạm thời chuyển làm công việc khác, tái cơ cấu, đổi chức danh vị trí, đổi quản lý, đổi địa điểm, đổi trung tâm chi phí; lý do quyết định các bước phê duyệt và tài liệu bắt buộc. | M | |
| BR-PPL-033 | Hỗ trợ **kiêm nhiệm** trong cùng một employment, mỗi vị trí có quản lý, FTE và phụ cấp kiêm nhiệm (không bắt buộc) riêng; một phân công được gắn cờ là chính. | M | |
| BR-PPL-034 | Hỗ trợ **biệt phái** sang pháp nhân khác (pháp nhân gốc và pháp nhân tiếp nhận, pháp nhân chi trả, cờ phân bổ lại chi phí, ngày bắt đầu/kết thúc), không chấm dứt employment tại pháp nhân gốc. | S | |
| BR-PPL-035 | Áp dụng quy tắc tạm thời chuyển người lao động làm công việc khác (Điều 29): số ngày cộng dồn trong một năm dương lịch vượt giới hạn luật định cần có văn bản đồng ý của người lao động; báo trước ít nhất N ngày làm việc; quy tắc bảo vệ tiền lương được gắn cờ gửi payroll. | M | 60 ngày làm việc/năm, báo trước 3 ngày làm việc, ≥85% và ≥ mức lương tối thiểu vùng, giữ nguyên lương cũ 30 ngày làm việc `[verify-legal]` |
| BR-PPL-036 | Hỗ trợ thay đổi hàng loạt (tái cơ cấu): chọn nhân viên, áp dụng cùng một thay đổi với một ngày hiệu lực, tạo hàng loạt quyết định cá nhân. | S | Việc dùng engine này cho ánh xạ tái cơ cấu là BR-PPL-039 (M) (changed 2026-10-16, GAP-053). |
| BR-PPL-037 | Cho phép thay đổi có hiệu lực tương lai và hồi tố; thay đổi hồi tố ảnh hưởng tới kỳ lương đã đóng sẽ gắn cờ retro trên sự kiện được phát. | M | |
| BR-PPL-038 | Người đảm nhiệm vị trí: hệ thống phải duy trì thông tin nhân viên nào đang giữ vị trí nào và thể hiện tình trạng trống/vượt định biên so với định biên vị trí từ core. | M | |
| BR-PPL-039 | **Thực thi ánh xạ tái cơ cấu.** Khi nhận `core.ReorgActivated`, hệ thống phải áp dụng bảng ánh xạ của gói cho mọi nhân viên bị ảnh hưởng với ngày hiệu lực của gói: tạo các dòng phân công mới có hiệu lực theo ngày (đơn vị tổ chức, vị trí, quản lý, trung tâm chi phí), tạo hàng loạt quyết định cá nhân, và phát `AssignmentChanged` cho từng nhân viên (lý do `reorg`, package id). Sau đó hệ thống phải phát **`people.ReorgMappingCompleted{package_id, moved, failed, fix list}`** tới Core (OUT-35). Các dòng lỗi (ví dụ vị trí đích đã đủ người khi kiểm soát vị trí chặt chẽ, thiếu ánh xạ) được đưa vào danh sách cần sửa theo từng nhân viên; sau khi sửa và chạy lại, hệ thống phát lại sự kiện. Idempotent theo gói và nhân viên. (new 2026-10-16, GAP-053) | M | Dùng engine thay đổi hàng loạt của BR-PPL-036. Sau đó Core phát `core.ReorgPackageStatusChanged` (Core C-12). Xử lý 5.000 nhân viên theo NFR-PPL-004. |

### 4.4 Lịch sử lương
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-040 | People là **system of record cho mức đãi ngộ đã thỏa thuận** (R2-02 §5 #24). Duy trì các bản ghi lương có hiệu lực theo ngày cho mỗi employment: thang lương / ngạch / bậc, **cơ sở trả lương (GROSS / NET)**, lương cơ bản (theo hợp đồng), **số tiền net thỏa thuận** (bắt buộc khi cơ sở là NET) và **phạm vi thành phần được đảm bảo net** (những thành phần được đảm bảo net, ví dụ lương cơ bản + phụ cấp cố định, so với gross, ví dụ thưởng, làm thêm giờ), lương đóng BHXH, các khoản phụ cấp lương thỏa thuận trong hợp đồng (mã thành phần payroll), tiền tệ, tần suất trả lương, % lương thử việc, lý do, quyết định/phụ lục liên kết. Bản ghi lương **chỉ** được tạo hoặc thay đổi thông qua quyết định hoặc phụ lục hợp đồng đã phê duyệt (workflow); không thể chỉnh sửa trực tiếp. Mỗi bản ghi được phê duyệt phát `SalaryChanged` (OUT-07). (changed 2026-10-16, R2-02 §5 #24; GAP-047) | M | Định nghĩa thang lương và danh mục thành phần do payroll sở hữu. Payroll đóng băng phiên bản bản ghi lương được dùng cho mỗi kỳ chạy (BR-PAY-072). |
| BR-PPL-041 | Kiểm tra mức lương so với min/max của thang lương / khung lương payroll cho ngạch (IF-PAY-P-02) và so với mức lương tối thiểu vùng áp dụng (gói pháp lý, theo vùng của địa điểm làm việc) tại ngày hiệu lực; chặn nếu dưới mức tối thiểu, cảnh báo nếu ngoài khung. Với bản ghi NET, việc kiểm tra dùng số tiền gross quy đổi từ trình mô phỏng payroll (changed 2026-10-16, R2-02 §5 #1, #7). | M | Tham số mức lương tối thiểu vùng `[verify-legal]` |
| BR-PPL-042 | Hỗ trợ lịch nâng bậc: tính ngày đủ điều kiện nâng bậc tiếp theo từ quy tắc ngạch và việc kéo dài do kỷ luật; tạo danh sách đến hạn cho HR. | S | Hình thức kỷ luật "kéo dài thời hạn nâng lương" làm lùi ngày. |
| BR-PPL-043 | Hỗ trợ đợt xét duyệt lương: ngân sách theo đơn vị tổ chức, quản lý đề xuất dựa trên kết quả đánh giá, phê duyệt, phát hàng loạt `SalaryChanged`. | C | Phase 2. |
| BR-PPL-044 | Quyền xem dữ liệu lương bị giới hạn theo vai trò và phạm vi dữ liệu: xem hoặc thay đổi bản ghi lương cần quyền **`compensation`** (được đăng ký trong danh mục quyền của Core), cấp cho C&B và HR được ủy quyền; quản lý chỉ xem được lương nếu được cấp quyền. **Mọi lượt xem và mọi thay đổi** bản ghi lương đều được ghi vào audit log của Core (ai, khi nào, bản ghi nào, mục đích) (changed 2026-10-16, R2-02 §5 #24). | M | |

### 4.5 Hợp đồng lao động
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-050 | Hỗ trợ các loại hợp đồng: hợp đồng thử việc, xác định thời hạn (≤36 tháng), không xác định thời hạn, thời vụ/ngắn hạn (<1 tháng, nếu áp dụng), học nghề, thực tập, hợp đồng dịch vụ/cộng tác viên (không phải hợp đồng lao động), và phụ lục hợp đồng. | M | Bộ luật Lao động 2019 Điều 20, 24 `[verify-legal]` |
| BR-PPL-051 | Thử việc: thời gian tối đa được kiểm tra theo cấp bậc công việc (ví dụ: ≤180 ngày với người quản lý doanh nghiệp; ≤60 ngày với trình độ cao đẳng trở lên; ≤30 ngày với trình độ trung cấp/công nhân kỹ thuật; ≤6 ngày làm việc với công việc khác); chỉ thử việc một lần cho một công việc; không thử việc với hợp đồng < 1 tháng; lương thử việc ≥ 85% mức lương của công việc. | M | Điều 24–27 `[verify-legal]` |
| BR-PPL-052 | Kết quả thử việc (đạt/không đạt) phải được ghi nhận và thông báo cho nhân viên trước khi kết thúc thử việc; hệ thống cảnh báo HR/quản lý N ngày trước đó; nếu đạt, tạo hợp đồng lao động (hoặc xác nhận tiếp tục nếu điều khoản thử việc nằm trong hợp đồng). | M | Điều 27 `[verify-legal]` |
| BR-PPL-053 | Thời hạn hợp đồng xác định thời hạn không được vượt quá 36 tháng; hệ thống đếm số hợp đồng xác định thời hạn liên tiếp trên mỗi employment và **bắt buộc loại không xác định thời hạn** cho hợp đồng tiếp theo sau hai hợp đồng xác định thời hạn, trừ các ngoại lệ theo luật (người lao động cao tuổi, người lao động nước ngoài, người quản lý doanh nghiệp nhà nước, hợp đồng theo nhiệm kỳ của cán bộ công đoàn v.v.). | M | Điều 20.2, 149, 151 `[verify-legal]` |
| BR-PPL-054 | Giám sát hết hạn: cảnh báo HR và quản lý tại các mốc cấu hình được (ví dụ 45/30/15 ngày) trước khi hợp đồng kết thúc; người sử dụng lao động thông báo không gia hạn; nếu nhân viên tiếp tục làm việc quá 30 ngày sau khi hết hạn mà không có hợp đồng mới, gắn cờ tự động chuyển đổi (xác định → không xác định thời hạn). | M | Điều 20.2 `[verify-legal]` |
| BR-PPL-055 | Phụ lục hợp đồng ghi nhận thay đổi điều khoản (lương, vị trí, nơi làm việc, thời giờ làm việc) với ngày hiệu lực; phụ lục không được thay đổi thời hạn hợp đồng (kiểm tra). | M | Điều 22 `[verify-legal]` |
| BR-PPL-056 | Tạm hoãn hợp đồng vì các lý do luật định (nghĩa vụ quân sự, bị tạm giữ/tạm giam, nghỉ thai sản quá thời gian luật định theo thỏa thuận, được bầu vào cơ quan dân cử, lý do khác theo thỏa thuận) với ngày bắt đầu/kết thúc; trạng thái lao động `Suspended`; sự kiện `ContractSuspended` / `ContractResumed`. | M | Điều 30–31 `[verify-legal]` |
| BR-PPL-057 | Văn bản hợp đồng được tạo từ mẫu của tenant (song ngữ Việt/Anh), đánh số theo dãy số của pháp nhân, do người ký được ủy quyền ký theo ma trận ủy quyền. | M | |
| BR-PPL-058 | Hợp đồng lao động điện tử: gửi ký điện tử qua nhà cung cấp chữ ký điện tử/hợp đồng điện tử của nền tảng, theo dõi trạng thái (nháp, đã gửi, nhân viên đã ký, người sử dụng lao động đã ký, hoàn tất, bị từ chối, hết hạn), lưu file đã ký + bằng chứng chứng thư số; hỗ trợ nộp/đăng ký lên nền tảng hợp đồng lao động điện tử quốc gia khi được yêu cầu. | S→M | Bộ luật Lao động Điều 14; Nghị định về hợp đồng lao động điện tử (ví dụ 337/2025/NĐ-CP) `[verify-legal]` — trở thành M nếu bắt buộc theo quy mô tenant. |
| BR-PPL-059 | Tham chiếu thỏa ước lao động tập thể (TƯLĐTT) theo pháp nhân: lưu văn bản và thời hạn hiệu lực; mẫu hợp đồng có thể tham chiếu tới thỏa ước. | C | |

### 4.6 Tiếp nhận & nghỉ việc
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-060 | Mẫu checklist cấu hình được theo pháp nhân, nhóm nhân viên và sự kiện (tiếp nhận, nghỉ việc, điều chuyển); đầu việc có vai trò phụ trách, số ngày đến hạn, cờ bắt buộc, yêu cầu tệp đính kèm. | M | |
| BR-PPL-061 | Cổng thông tin cho người sắp nhận việc: những người được mời hoàn thiện dữ liệu cá nhân, tải tài liệu lên (CCCD, bằng cấp, giấy khám sức khỏe, ảnh, sổ BHXH), ký các consent trước ngày làm việc đầu tiên. | M | |
| BR-PPL-062 | Nghỉ việc bao gồm biên bản bàn giao, danh sách tài sản cần trả, ngày thu hồi quyền truy cập, phỏng vấn nghỉ việc (khảo sát không bắt buộc), xác nhận hoàn tất thủ tục từ các bộ phận được cấu hình (IT, Tài chính, Hành chính). | M | |
| BR-PPL-063 | Tiến độ checklist và các đầu việc quá hạn hiển thị trên dashboard của HR và quản lý; nhắc việc qua thông báo của core. | M | |

### 4.7 Nghỉ việc
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-070 | Hỗ trợ các lý do nghỉ việc được ánh xạ tới căn cứ pháp lý: hết hạn hợp đồng, thỏa thuận chấm dứt, người lao động đơn phương chấm dứt, người sử dụng lao động đơn phương chấm dứt (Điều 36), cho thôi việc do thay đổi cơ cấu/lý do kinh tế (Điều 42–43), sa thải (kỷ luật), không đạt thử việc, nghỉ hưu, tử vong, mất tích/tuyên bố của Tòa án, chấm dứt hoạt động. Mỗi lý do mang một mã luật định dùng cho BHXH/BHTN và báo cáo. | M | |
| BR-PPL-071 | Tính thời hạn báo trước bắt buộc theo loại hợp đồng và lý do: ví dụ không xác định thời hạn 45 ngày, xác định thời hạn 12–36 tháng 30 ngày, xác định thời hạn < 12 tháng 3 ngày làm việc; ngành nghề đặc thù theo Nghị định 145/2020; các trường hợp không cần báo trước của người lao động (Điều 35.2) và người sử dụng lao động (Điều 36.2). Hiển thị số ngày thiếu và hệ quả bồi thường khi không tuân thủ thời hạn báo trước. | M | `[verify-legal]` |
| BR-PPL-072 | Đơn xin nghỉ việc qua ESS: nhân viên đề xuất ngày làm việc cuối cùng, lý do, nhận xét; quản lý và HR phê duyệt; cho phép rút đơn trước khi được duyệt / sau khi duyệt nếu có sự đồng ý. | M | |
| BR-PPL-073 | Kiểm tra đối tượng được bảo vệ: chặn việc người sử dụng lao động đơn phương chấm dứt / sa thải đối với nhân viên đang mang thai, nghỉ thai sản, nuôi con dưới 12 tháng tuổi, đang nghỉ ốm điều trị trong thời hạn luật định, v.v., trừ khi có ngoại lệ hợp pháp được lập hồ sơ. | M | Điều 37, 122.4, 137 `[verify-legal]` |
| BR-PPL-074 | Xác định điều kiện hưởng **trợ cấp thôi việc** hoặc **trợ cấp mất việc làm** từ căn cứ pháp lý, và cung cấp cho payroll: các giai đoạn làm việc với người sử dụng lao động này (bao gồm thời gian làm việc trước đó được công nhận), các giai đoạn đã được chi trả (trợ cấp thôi việc / mất việc làm đã trả trước đó), căn cứ pháp lý và điều kiện hưởng (OUT-13). **Payroll tính** thời gian làm việc tính trợ cấp thôi việc (loại trừ các tháng đã đóng BHTN từ lịch sử BHXH của Payroll), lương bình quân 6 tháng và số tiền; People hiển thị kết quả của Payroll trên hồ sơ nghỉ việc (IN-19) và không tính lại (changed 2026-10-16, R2-02 §5 #29). | M | Điều 46–47, Nghị định 145 Điều 8 `[verify-legal]` |
| BR-PPL-075 | Khi được phê duyệt, phát `TerminationScheduled`; vào ngày làm việc cuối cùng+1 phát `EmployeeTerminated` kèm kích hoạt quyết toán cuối cùng; hạn quyết toán (ví dụ 14 ngày làm việc, có thể kéo dài đến 30 ngày) được theo dõi kèm cảnh báo. | M | Điều 48 `[verify-legal]` |
| BR-PPL-076 | Theo dõi các nghĩa vụ sau nghỉ việc: chốt & trả sổ BHXH, trả lại giấy tờ gốc, giấy xác nhận công tác, yêu cầu chứng từ khấu trừ thuế TNCN, cờ đủ điều kiện tái tuyển, thời hạn truy cập ESS chỉ đọc. | M | |
| BR-PPL-077 | Hỗ trợ cho thôi việc hàng loạt (Điều 42–44): hồ sơ phương án sử dụng lao động, bằng chứng trao đổi với công đoàn, thông báo cho cơ quan quản lý lao động cấp tỉnh trước khi chấm dứt. | C | |

### 4.8 Kỷ luật & khen thưởng
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-080 | Hồ sơ vụ việc kỷ luật: ngày vi phạm, điều khoản nội quy lao động bị vi phạm, bằng chứng, thư mời họp, người tham dự (bao gồm đại diện công đoàn), biên bản, quyết định, hình thức (khiển trách, kéo dài thời hạn nâng lương ≤ 6 tháng, cách chức, sa thải), thời gian áp dụng, ngày xóa kỷ luật. | M | Điều 122–125 `[verify-legal]` |
| BR-PPL-081 | Kiểm tra thời hiệu xử lý (ví dụ 6 tháng; 12 tháng đối với vi phạm liên quan đến tài chính/tài sản/bí mật) và cấm hình thức phạt tiền/cắt lương thay cho xử lý kỷ luật. | M | Điều 123, 127 `[verify-legal]` |
| BR-PPL-082 | Tạm đình chỉ công việc trong thời gian điều tra, với số ngày tối đa (ví dụ 15, đến 90 trong trường hợp đặc biệt) và khoản tạm ứng lương (ví dụ 50%) chuyển sang payroll. | S | Điều 128 `[verify-legal]` |
| BR-PPL-083 | Trách nhiệm vật chất (bồi thường thiệt hại): số tiền bồi thường và kế hoạch trả góp; khoản khấu trừ hằng tháng có giới hạn (ví dụ ≤ 30% tiền lương thực trả hằng tháng) gửi sang payroll. | S | Điều 129, 102 `[verify-legal]` |
| BR-PPL-084 | Tự động xóa kỷ luật sau thời hạn luật định nếu không tái phạm; giữ hồ sơ nhưng đánh dấu đã xóa kỷ luật. | S | Điều 126 `[verify-legal]` |
| BR-PPL-085 | Khen thưởng: loại (tiền, giấy khen, danh hiệu), cấp (công ty, nhà nước), số tiền, cờ xử lý thuế, quyết định; khen thưởng bằng tiền đã duyệt được phát sang payroll cho kỳ lương mục tiêu. | M | |

### 4.9 Đánh giá / hiệu suất
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-090 | Cấu hình chu kỳ đánh giá (năm, 6 tháng, quý, thử việc) với bộ lọc đối tượng, mẫu (KPI, OKR, năng lực, kết hợp), trọng số, thang xếp loại và hạn chót từng giai đoạn. | S | Phase 2 trừ đánh giá thử việc (M). |
| BR-PPL-091 | Thư viện KPI và phân rã mục tiêu (công ty → đơn vị → cá nhân); OKR với rà soát tiến độ key result. | S | |
| BR-PPL-092 | Tự đánh giá, quản lý đánh giá, đánh giá 360/đồng nghiệp (không bắt buộc); phiên hiệu chỉnh với hướng dẫn phân bổ; xếp loại cuối cùng bị khóa sau khi xác nhận. | S | |
| BR-PPL-093 | Phát `AppraisalFinalised` (xếp loại, điểm, kỳ) cho payroll (thưởng hiệu suất / thành phần lương theo KPI) và xét lương. | S | Điểm KPI hằng tháng cho lương gắn KPI là M nếu tenant sử dụng — xem câu hỏi mở Q4. |
| BR-PPL-094 | Biểu mẫu đánh giá thử việc liên kết với kết quả thử việc (BR-PPL-052). | M | |

### 4.10 Đào tạo
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-100 | Danh mục khóa học, buổi học (nội bộ/bên ngoài, chi phí, giảng viên, lịch), ghi danh (đề cử / tự đăng ký có phê duyệt), điểm danh và kết quả, cấp chứng chỉ cập nhật BR-PPL-019. | S | |
| BR-PPL-101 | Cam kết đào tạo / hợp đồng đào tạo: chi phí, thời gian cam kết; khi nghỉ việc trước hạn, tính khoản hoàn trả theo tỷ lệ và gửi sang quyết toán cuối cùng. | S | Điều 62 `[verify-legal]` |
| BR-PPL-102 | Theo dõi đào tạo bắt buộc (ví dụ các nhóm huấn luyện an toàn, vệ sinh lao động) với ngày hết hạn và báo cáo tuân thủ. | S | Nghị định 44/2016 `[verify-legal]` |
| BR-PPL-103 | Thời gian đào tạo vào ngày làm việc có thể được gửi sang T&A dưới dạng thời gian đào tạo có hưởng lương. | C | |

### 4.11 Đề nghị thanh toán chi phí & tạm ứng
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-110 | Danh mục loại chi phí với hạn mức theo chính sách (theo khoản/ngày/chuyến/ngạch), chứng từ bắt buộc, các trường hóa đơn điện tử VAT (số hóa đơn, MST người bán, ngày). | S | |
| BR-PPL-111 | Đề nghị tạm ứng → phê duyệt → trạng thái đã chi; quyết toán đề nghị thanh toán với tạm ứng; số dư phải trả cho/thu từ nhân viên. | S | |
| BR-PPL-112 | Các khoản hoàn ứng đã duyệt được chuyển, theo tenant **theo từng loại chi phí**, hoặc sang payroll (`ExpenseReimbursementApproved` → thành phần lương, chịu thuế/không chịu thuế theo cờ của loại chi phí) hoặc sang file xuất kế toán tài chính (xuất file trong Phase 1); tạm ứng quá hạn chưa quyết toán được gửi sang payroll để khấu trừ (`AdvanceDeductionRequested`, kèm bản ghi đồng ý) (changed 2026-10-16, R2-02 §5 #21). | S | |
| BR-PPL-113 | Công tác **thuộc sở hữu của T&A** (yêu cầu, phê duyệt, ảnh hưởng tới chấm công, BR-ATT-070). Các tính năng chi phí và công tác phí của People tiêu thụ `att.BusinessTripApproved` (ATT-P-08, IN-16) để điền sẵn và kiểm tra đề nghị thanh toán chi phí công tác; People không có yêu cầu công tác riêng (changed 2026-10-16, R2-02 §5 #6). | S | |

### 4.12 ESS / MSS
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-120 | Các trang ESS của People: xem hồ sơ, phân công, hợp đồng, lịch sử lương (nếu chính sách cho phép), tài liệu, dòng thời gian; sửa dữ liệu cá nhân (BR-PPL-022); quản lý người phụ thuộc (yêu cầu); thay đổi tài khoản ngân hàng (có xác thực step-up); xin nghỉ việc; đề nghị thanh toán chi phí; đánh giá; đăng ký đào tạo; yêu cầu văn bản nhân sự. Các trang này được gắn vào **shell ESS/MSS của saas-platform** (điều hướng, xác thực, step-up; responsive web trong Phase 1); các trang nghỉ phép/chấm công thuộc T&A, các trang phiếu lương, chứng từ khấu trừ thuế TNCN, 08/CK-TNCN và ủy quyền quyết toán thuộc Payroll, hộp thư phê duyệt thuộc Core (changed 2026-10-16, R2-02 §5 #4, #17). | M | |
| BR-PPL-121 | MSS: danh sách nhóm & sơ đồ tổ chức, phê duyệt đang chờ, khởi tạo thay đổi (điều chuyển, thăng chức, đề xuất lương trong phạm vi), kết quả thử việc, đánh giá, đề xuất kỷ luật, đầu việc tiếp nhận. | M | |
| BR-PPL-122 | Ủy quyền phê duyệt của quản lý trong thời gian vắng mặt dùng cơ chế ủy quyền của core. | M | |
| BR-PPL-123 | ESS/MSS có tiếng Việt và tiếng Anh, responsive trên di động. | M | |

### 4.13 Tài liệu, quyết định & văn bản nhân sự
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-130 | Tạo quyết định cho: tuyển dụng/bổ nhiệm, kết quả thử việc, điều chuyển, thăng chức/giáng chức, kiêm nhiệm, biệt phái, thay đổi lương, khen thưởng, kỷ luật, tạm hoãn, chấm dứt, nghỉ hưu; mỗi quyết định có số quyết định (theo dãy số của pháp nhân, theo năm), ngày, người ký, ngày hiệu lực, văn bản căn cứ pháp lý. | M | |
| BR-PPL-131 | Quản lý mẫu: mẫu tenant có thể chỉnh sửa với các trường trộn (merge field), có phiên bản, song ngữ. | M | |
| BR-PPL-132 | Văn bản nhân sự theo yêu cầu: xác nhận công tác, xác nhận thu nhập (từ dữ liệu payroll), hỗ trợ xin visa, giấy xác nhận kinh nghiệm làm việc; yêu cầu qua ESS có phê duyệt và tự động tạo văn bản. | S | |
| BR-PPL-133 | Kho tài liệu nhân viên với danh mục, thời hạn lưu trữ theo danh mục, mức độ bảo mật và lịch sử phiên bản. | M | |
| BR-PPL-134 | Ký số quyết định/văn bản (chứng thư số của tổ chức) qua chữ ký điện tử của nền tảng. | S | |

### 4.14 Báo cáo theo luật định & sổ quản lý lao động
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-140 | Duy trì **sổ quản lý lao động** dạng điện tử theo từng pháp nhân với các trường dữ liệu theo quy định, sẵn sàng cho thanh tra và có thể xuất ra. | M | Nghị định 145/2020 Điều 3 `[verify-legal]` |
| BR-PPL-141 | Tạo **báo cáo tình hình thay đổi về lao động 6 tháng** theo từng pháp nhân theo mẫu quy định (ví dụ Mẫu 01/PLI), cho các kỳ kết thúc tháng 6 và tháng 12, kèm cảnh báo hạn chót (ví dụ trước ngày 5 tháng 6 / 5 tháng 12), và ghi nhật ký nộp. | M | Nghị định 145/2020 Điều 4 `[verify-legal]` |
| BR-PPL-142 | Tạo **thông báo biến động lao động hằng tháng** gửi Trung tâm Dịch vụ việc làm địa phương (phục vụ BHTN) liệt kê tuyển mới/chấm dứt, kèm cảnh báo hạn chót. | M | Luật Việc làm 2025 + nghị định hướng dẫn `[verify-legal]` |
| BR-PPL-143 | Các báo cáo về người lao động nước ngoài (giải trình nhu cầu, báo cáo định kỳ tình hình sử dụng) theo quy định. | S | `[verify-legal]` |
| BR-PPL-144 | Khai trình sử dụng lao động lần đầu trong vòng 30 ngày kể từ khi pháp nhân bắt đầu hoạt động (mẫu). | C | `[verify-legal]` |

### 4.15 Bảo vệ dữ liệu cá nhân
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PPL-150 | Phân loại mọi trường dữ liệu cá nhân là cơ bản hay **nhạy cảm** (ví dụ sức khỏe, sinh trắc học, dân tộc, tôn giáo, quan điểm chính trị, tiền án tiền sự, vị trí, tài chính/ngân hàng, đoàn viên công đoàn) trong từ điển dữ liệu. | M | Nghị định 13/2023 Điều 2; Luật BVDLCN 2025 `[verify-legal]` |
| BR-PPL-151 | Consent theo từng mục đích được ghi nhận trong **consent registry của saas-platform** (mục đích, phiên bản thông báo, chủ thể = person id, thời điểm, kênh, rút lại); People chỉ lưu tham chiếu và gọi `hasConsent()` trước khi xử lý dữ liệu nhạy cảm không bắt buộc; việc xử lý theo căn cứ hợp pháp (hợp đồng lao động/nghĩa vụ pháp lý) được ghi nhận mà không cần consent khi pháp luật cho phép. Consent từ tuyển dụng được chuyển mục đích trong cùng registry khi tuyển dụng (changed 2026-10-16, R2-02 §5 #27). | M | |
| BR-PPL-152 | Các trường nhạy cảm được che mặc định trên UI và trong file xuất; việc hiển thị cần quyền và được ghi log (ai, khi nào, trường nào, mục đích). | M | |
| BR-PPL-153 | Các yêu cầu của chủ thể dữ liệu (truy cập, chỉnh sửa, xuất, rút consent, hạn chế, xóa khi pháp luật cho phép) được mở và theo dõi bởi **điều phối DSR của saas-platform** (điểm vào là ESS); People cung cấp các handler xuất và ẩn danh hóa cho dữ liệu People (OUT-33) (changed 2026-10-16, R2-02 §5 #27). | M | `[verify-legal]` cho thời hạn phản hồi |
| BR-PPL-154 | Quy tắc lưu trữ: sau khi nghỉ việc, dữ liệu cá nhân được lưu theo lịch lưu trữ của từng danh mục (ví dụ thời hạn lưu trữ theo luật lao động/BHXH/thuế) do **bộ lập lịch lưu trữ của saas-platform** quản lý với mức sàn pháp lý; People đăng ký các danh mục của mình và thực thi callback ẩn danh hóa/xóa, có cơ chế ghi đè khi bị phong tỏa pháp lý (legal hold) (changed 2026-10-16, R2-02 §5 #27). | S | |

---

## 5. Quy định tuân thủ pháp luật Việt Nam

| # | Quy định (tóm tắt) | Căn cứ pháp lý | Áp dụng cho |
|---|---|---|---|
| C1 | Loại hợp đồng: không xác định thời hạn và xác định thời hạn (≤ 36 tháng). Sau 2 hợp đồng xác định thời hạn, hợp đồng tiếp theo phải là không xác định thời hạn (có ngoại lệ). Tiếp tục làm việc 30 ngày sau khi hết hạn mà không có hợp đồng mới → trở thành không xác định thời hạn (từ xác định thời hạn). | Bộ luật Lao động 2019 (45/2019/QH14) Điều 20 `[verify-legal]` | BR-PPL-053/054 |
| C2 | Thời gian thử việc tối đa theo công việc; chỉ một lần cho một công việc; lương thử việc ≥ 85%; thông báo kết quả khi kết thúc thử việc; không thử việc với hợp đồng < 1 tháng. | BLLĐ Điều 24–27 `[verify-legal]` | BR-PPL-051/052 |
| C3 | Hợp đồng lao động điện tử có giá trị như hợp đồng giấy; nền tảng quốc gia & điều kiện theo nghị định hướng dẫn. | BLLĐ Điều 14; Luật Giao dịch điện tử 2023; Nghị định 337/2025/NĐ-CP `[verify-legal]` | BR-PPL-058 |
| C4 | Phụ lục có thể sửa đổi điều khoản nhưng không được sửa đổi thời hạn hợp đồng. | BLLĐ Điều 22 `[verify-legal]` | BR-PPL-055 |
| C5 | Tạm thời chuyển người lao động làm công việc khác: tối đa 60 ngày làm việc/năm nếu không có sự đồng ý, báo trước 3 ngày làm việc, lương theo công việc mới ≥ 85% lương cũ và ≥ mức tối thiểu vùng, giữ nguyên lương cũ 30 ngày làm việc. | BLLĐ Điều 29 `[verify-legal]` | BR-PPL-035 |
| C6 | Các trường hợp tạm hoãn hợp đồng và việc trở lại làm việc trong vòng 15 ngày. | BLLĐ Điều 30–31 `[verify-legal]` | BR-PPL-056 |
| C7 | Người lao động báo trước: 45 ngày (không xác định thời hạn), 30 ngày (12–36 tháng), 3 ngày làm việc (< 12 tháng); ngành nghề đặc thù dài hơn (ví dụ 120 ngày) theo Nghị định 145. Người sử dụng lao động báo trước với các căn cứ Điều 36 tương tự. | BLLĐ Điều 35–36; Nghị định 145/2020 Điều 7 `[verify-legal]` | BR-PPL-071 |
| C8 | Nhân viên được bảo vệ (mang thai, nghỉ thai sản, nuôi con < 12 tháng) không bị đơn phương chấm dứt/xử lý kỷ luật, trừ trường hợp chấm dứt hoạt động v.v. | BLLĐ Điều 37, 122, 137 `[verify-legal]` | BR-PPL-073 |
| C9 | Trợ cấp thôi việc: ½ tháng lương cho mỗi năm làm việc đối với người làm việc ≥ 12 tháng, trừ thời gian đã tham gia BHTN và thời gian đã được chi trả; trợ cấp mất việc làm: 1 tháng cho mỗi năm, tối thiểu 2 tháng; lương làm căn cứ = bình quân tiền lương theo hợp đồng của 6 tháng liền kề trước khi chấm dứt. | BLLĐ Điều 46–47; Nghị định 145 Điều 8 `[verify-legal]` | BR-PPL-074 |
| C10 | Thanh toán đầy đủ các khoản trong vòng 14 ngày làm việc (có thể kéo dài đến 30 ngày trong các trường hợp được liệt kê); người sử dụng lao động phải hoàn tất xác nhận thời gian đóng BHXH và trả lại giấy tờ. | BLLĐ Điều 48 `[verify-legal]` | BR-PPL-075/076 |
| C11 | Kỷ luật: chỉ gồm các hình thức khiển trách, kéo dài thời hạn nâng lương ≤ 6 tháng, cách chức, sa thải; thời hiệu 6/12 tháng; cấm phạt tiền; trình tự có sự tham gia của công đoàn; tạm đình chỉ ≤ 15 ngày (≤ 90 ngày trường hợp đặc biệt) với tạm ứng 50%; xóa kỷ luật sau 3/6 tháng (không phải sa thải) hoặc 3 năm với cách chức. | BLLĐ Điều 122–128; Nghị định 145 Điều 69–72 `[verify-legal]` | BR-PPL-080–084 |
| C12 | Bồi thường thiệt hại vật chất bằng khấu trừ lương tối đa 30% tiền lương thực trả hằng tháng. | BLLĐ Điều 102, 129 `[verify-legal]` | BR-PPL-083 |
| C13 | Sổ quản lý lao động trong vòng 30 ngày kể từ khi hoạt động; báo cáo tình hình sử dụng lao động 6 tháng (trước ngày 5 tháng 6 và 5 tháng 12) qua cổng thông tin quốc gia về lao động. | Nghị định 145/2020 Điều 3–4 `[verify-legal]` | BR-PPL-140/141 |
| C14 | Thông báo biến động lao động hằng tháng cho Trung tâm Dịch vụ việc làm (BHTN). | Luật Việc làm 2025 (74/2025/QH15) & nghị định hướng dẫn `[verify-legal]` | BR-PPL-142 |
| C15 | Người lao động nước ngoài cần giấy phép lao động/miễn giấy phép; hợp đồng xác định thời hạn không vượt quá thời hạn giấy phép; gia hạn. | BLLĐ Điều 151–155; Nghị định 219/2025/NĐ-CP `[verify-legal]` | BR-PPL-020, 053 |
| C16 | Tuổi nghỉ hưu theo lộ trình tăng dần (đến 62 với nam / 60 với nữ). Dùng cho cảnh báo nghỉ hưu. | BLLĐ Điều 169; Nghị định 135/2020 `[verify-legal]` | Báo cáo R7 |
| C17 | Người phụ thuộc thuế TNCN: đăng ký, điều kiện (tuổi, ngưỡng thu nhập), mỗi người phụ thuộc chỉ tính cho một người nộp thuế; mức giảm trừ gia cảnh (ví dụ 15,5 triệu VND cho bản thân / 6,2 triệu VND cho mỗi người phụ thuộc từ 2026). | Luật Thuế TNCN & các văn bản sửa đổi; Thông tư 111/2013; Nghị quyết 110/2025/UBTVQH15 `[verify-legal]` | BR-PPL-016/017 (mức giảm trừ do payroll sử dụng) |
| C18 | Số định danh cá nhân được dùng làm mã số thuế cá nhân. | Luật Quản lý thuế; Thông tư 86/2024/TT-BTC `[verify-legal]` | BR-PPL-013 |
| C19 | Dữ liệu cá nhân: consent, phân loại dữ liệu nhạy cảm, đánh giá tác động, quyền của chủ thể dữ liệu, thông báo vi phạm (ví dụ 72 giờ). | Luật BVDLCN 2025 (91/2025/QH15, hiệu lực 1/1/2026) và nghị định hướng dẫn; Nghị định 13/2023/NĐ-CP `[verify-legal]` | BR-PPL-150–154 |
| C20 | Hợp đồng đào tạo và hoàn trả chi phí nếu người lao động nghỉ việc trước thời hạn cam kết. | BLLĐ Điều 61–62 `[verify-legal]` | BR-PPL-101 |
| C21 | Nội quy lao động bắt buộc (phải đăng ký) với người sử dụng lao động có ≥ 10 người lao động; việc xử lý kỷ luật phải viện dẫn nội quy. | BLLĐ Điều 118–119 `[verify-legal]` | BR-PPL-080 |
| C22 | Lao động nữ nuôi con < 12 tháng: nghỉ 60 phút mỗi ngày có hưởng lương; lao động nữ mang thai làm công việc nặng nhọc từ tháng thứ 7: giảm 1 giờ/ngày. | BLLĐ Điều 137 `[verify-legal]` | BR-PPL-021 → T&A |

---

## 6. Thực thể nghiệp vụ chính

| Thực thể | Mục đích | Thuộc tính chính |
|---|---|---|
| `hr.person` | Cá nhân thực (theo phạm vi tenant) | person_id, họ tên pháp lý, ngày sinh, giới tính, quốc tịch, dân tộc, tôn giáo (nhạy cảm), national_id_no, tax_code, si_code, ảnh, trạng thái xác thực |
| `hr.tax_residency` | Cư trú thuế TNCN có hiệu lực theo ngày (new 2026-10-16, GAP-048) | person_id, trạng thái (cư trú/không cư trú), căn cứ, valid_from/to, các ngày nhập cảnh, các ngày xuất cảnh, days_in_vn theo năm (không bắt buộc), nguồn (HR / đề xuất 183 ngày của payroll), tham chiếu phê duyệt |
| `hr.person_identity_doc` | Giấy tờ tùy thân & giấy phép | doc_type, số, ngày/nơi cấp, ngày hết hạn, tệp |
| `hr.person_address` | Địa chỉ (có hiệu lực theo ngày) | loại, tỉnh, xã, dòng địa chỉ, từ/đến |
| `hr.person_contact` | Điện thoại, email | loại, giá trị, chính |
| `hr.emergency_contact` | Người liên hệ khẩn cấp | họ tên, quan hệ, điện thoại |
| `hr.family_member` | Thành viên gia đình | quan hệ, họ tên, ngày sinh, số định danh, mã số thuế, tình trạng thu nhập |
| `hr.pit_dependant` | Đăng ký người phụ thuộc (People là chủ sở hữu, R2-02 §5 #3) | family_member_id, employment_id (employment kê khai — phải là employment chính, R2-16), from_month, to_month, trạng thái, tham chiếu đăng ký thuế |
| `hr.bank_account` | Tài khoản nhận lương | ngân hàng, chi nhánh, số tài khoản, chủ tài khoản, tỷ lệ chia, mặc định, từ/đến |
| `hr.education`, `hr.certificate` | Bằng cấp, chứng chỉ | trình độ, cơ sở đào tạo, chuyên ngành, năm / loại, đơn vị cấp, ngày hết hạn |
| `hr.work_permit` | Giấy phép lao động của người nước ngoài | số, loại (giấy phép/miễn), công việc, từ/đến |
| `hr.special_status` | Cờ bảo vệ & chế độ | loại (mang thai, con<12 tháng, khuyết tật, chưa thành niên, công đoàn…), từ/đến |
| `hr.employment` | Quan hệ lao động với một pháp nhân (tối đa một employment đang hiệu lực cho mỗi người trên mỗi pháp nhân) | employment_id, person_id, legal_entity_id, employee_no, ngày vào làm, ngày tính thâm niên, trạng thái, nhóm người lao động, đủ điều kiện tái tuyển |
| `hr.primary_employment` | Employment chính theo từng mục đích, có hiệu lực theo ngày (changed 2026-10-16, R2-16; DESIGN.md §7.3) | person_id, mục đích (PIT_FAMILY_DEDUCTION / SOCIAL_INSURANCE), employment_id, valid_from/to, tham chiếu phê duyệt |
| `hr.insurance_participation` | Việc tham gia theo từng loại bảo hiểm trên từng employment, có hiệu lực theo ngày (changed 2026-10-16, R2-16) `[verify-legal]` | employment_id, loại bảo hiểm (BHXH / BHYT / BHTN / BHTNLĐ-BNN), có tham gia (Y/N), lý do (giá trị quy tắc được áp dụng từ tham số pháp lý `insurance.multi_contract_rule`: FIRST_CONTRACT / HIGHEST_SALARY / EACH_EMPLOYER, hoặc miễn) (changed 2026-10-16, R3-03), valid_from/to |
| `hr.assignment` | Bố trí công việc có hiệu lực theo ngày | employment_id, loại, position_id, org_unit_id, công việc, ngạch, quản lý, địa điểm, (các) trung tâm chi phí, FTE, chế độ làm việc theo hợp đồng, từ/đến, lý do, decision_id, reorg_package_id — không có pay group, không có lịch làm việc (changed 2026-10-16, R2-02 §5 #25, #26) |
| `hr.salary_record` | Mức đãi ngộ đã thỏa thuận có hiệu lực theo ngày — system of record (changed 2026-10-16, R2-02 §5 #24; GAP-047) | employment_id, thang lương/ngạch/bậc, pay_basis (GROSS/NET), lương cơ bản, agreed_net_amount, net_guaranteed_components, lương đóng BHXH, phụ cấp theo hợp đồng (mã thành phần), tiền tệ, % thử việc, từ/đến, lý do, decision_id hoặc appendix_id (bắt buộc), phiên bản |
| `hr.contract` | Hợp đồng lao động / thử việc / dịch vụ | loại, số, ngày ký, ngày bắt đầu, ngày kết thúc, thứ tự (hợp đồng xác định thời hạn thứ n), người ký, trạng thái ký điện tử, tệp |
| `hr.contract_appendix` | Sửa đổi | contract_id, nội dung thay đổi, ngày hiệu lực |
| `hr.contract_suspension` | Giai đoạn tạm hoãn | lý do, từ/đến, ngày trở lại làm việc |
| `hr.decision` | Quyết định | số, loại, ngày ký, ngày hiệu lực, người ký, employment liên quan, tệp |
| `hr.hr_event` | Dòng thời gian / outbox | loại sự kiện, employment, ngày hiệu lực, payload, cờ retro, trạng thái phát |
| `hr.separation` | Hồ sơ thôi việc/chấm dứt | mã lý do, căn cứ pháp lý, ngày báo trước, thời hạn báo trước bắt buộc, LWD, ngày chấm dứt, access_revocation_date, loại trợ cấp và điều kiện hưởng, các giai đoạn làm việc và các giai đoạn đã được chi trả (đầu vào), tham chiếu kết quả trợ cấp thôi việc của payroll (hiển thị), hạn quyết toán (hiển thị đồng hồ của payroll), trạng thái (changed 2026-10-16, R2-02 §5 #9, #29) |
| `hr.checklist_template`, `hr.checklist`, `hr.checklist_task` | Tiếp nhận/nghỉ việc | mẫu, người phụ trách, hạn, trạng thái |
| `hr.discipline_case` | Kỷ luật | vi phạm, bằng chứng, cuộc họp, hình thức, thời gian, ngày xóa kỷ luật, bồi thường |
| `hr.reward` | Khen thưởng | loại, cấp, số tiền, cờ thuế, quyết định, kỳ lương |
| `hr.appraisal_cycle`, `hr.appraisal`, `hr.goal` | Hiệu suất | chu kỳ, mẫu, mục tiêu, điểm, xếp loại cuối cùng |
| `hr.course`, `hr.training_session`, `hr.training_enrolment`, `hr.training_bond` | Đào tạo | khóa học, buổi học, điểm danh, kết quả, chi phí & thời gian cam kết |
| `hr.expense_claim`, `hr.expense_line`, `hr.cash_advance` | Chi phí | loại, số tiền, dữ liệu hóa đơn, trạng thái, luồng chuyển (payroll/tài chính) |
| `hr.business_trip` | Đã bỏ — thuộc sở hữu của time-attendance (R2-02); đề nghị thanh toán chi phí tham chiếu trip id của T&A (changed 2026-10-16, R2-02 §5 #6) | — |
| `hr.document` | Tài liệu nhân viên (tham chiếu metadata tới dịch vụ tài liệu của Core) | danh mục, Core document id, mức bảo mật, lớp lưu trữ (changed 2026-10-16, R2-02 §5 #15b) |
| `hr.consent_record` → `hr.consent_ref` | Tham chiếu tới consent registry của saas-platform (changed 2026-10-16, R2-02 §5 #27) | person_id, mục đích, registry consent id |
| `hr.data_request` | Đã bỏ — thuộc sở hữu của điều phối DSR của saas-platform (R2-02); People xử lý các callback xuất/ẩn danh hóa (changed 2026-10-16, R2-02 §5 #27) | — |
| `hr.statutory_report` | Báo cáo lao động | loại, pháp nhân, kỳ, tệp đã tạo, thời điểm nộp |

Mọi bảng đều có `tenant_id`, các cột audit, và (khi phù hợp) `valid_from`/`valid_to`.

---

## 7. Giao diện liên phân hệ

Tên sự kiện là tên logic (`hr.<Event>`); cơ chế truyền tải (outbox → message bus hay API đồng bộ) do kiến trúc quyết định. Mỗi sự kiện mang: `tenant_id`, `person_id`, `employment_id`, `legal_entity_id`, `effective_date`, `event_id`, `occurred_at`, cờ `retro`, `correlation_id` (quyết định/hồ sơ).

### 7.1 Tiêu thụ

| # | Nội dung | Từ phân hệ | Khi nào / kích hoạt | Dùng cho |
|---|---|---|---|---|
| IN-01 | Pháp nhân (tên, mã số thuế, địa chỉ, mã đơn vị BHXH, vùng lương tối thiểu, người đại diện theo pháp luật) | core | Tra cứu tham chiếu; khi thay đổi | Employment, hợp đồng, quyết định, báo cáo theo luật định |
| IN-02 | Cây đơn vị tổ chức (có hiệu lực theo ngày) và trung tâm chi phí; `core.ReorgActivated` kèm bảng ánh xạ; `core.ReorgPackageStatusChanged` | core | Tra cứu; `core.OrgUnitChanged` / sự kiện tái cơ cấu | Phân công; thực thi ánh xạ tái cơ cấu (BR-PPL-039) (changed 2026-10-16, GAP-053) |
| IN-03 | Vị trí (kèm định biên, công việc, ngạch, vị trí cha, vị trí quản lý) và danh mục công việc / ngạch công việc | core | Tra cứu; `PositionChanged` / `PositionClosed` | Phân công, người đảm nhiệm, vị trí trống, xác định quản lý |
| IN-04 | Địa điểm làm việc (địa chỉ, tỉnh, vùng lương tối thiểu) | core | Tra cứu | Phân công, kiểm tra lương tối thiểu (vùng) |
| IN-05 | Người dùng, vai trò, quyền, phạm vi dữ liệu (theo pháp nhân/đơn vị tổ chức), quyền ở mức trường cho dữ liệu nhạy cảm | core | Mỗi request | Kiểm soát truy cập, ESS/MSS, che dữ liệu |
| IN-06 | Workflow engine (chuỗi phê duyệt, ủy quyền, SLA) | core | Yêu cầu thay đổi, nghỉ việc, kỷ luật, chi phí, người phụ thuộc, thay đổi dữ liệu | Phê duyệt |
| IN-07 | Thông báo (email, in-app, push) và mẫu | core | Cảnh báo và nhắc việc | Cảnh báo hết hạn, nhắc việc checklist, hạn chót |
| IN-08 | Dịch vụ audit log | core | Mỗi lần ghi / hiển thị dữ liệu nhạy cảm | Audit, nhật ký truy cập theo Luật BVDLCN |
| IN-09 | Dữ liệu tham chiếu: đơn vị hành chính (2 cấp từ 2025), quốc tịch, dân tộc, ngân hàng/chi nhánh, trình độ học vấn, quan hệ, dãy số đánh số văn bản | core | Tra cứu | Kiểm tra dữ liệu cá nhân |
| IN-10 | Tham số luật định: mức lương tối thiểu vùng theo vùng & ngày; lộ trình tuổi nghỉ hưu; giới hạn báo trước/thử việc theo luật; hạn quyết toán (dưới dạng tham số) — qua `getParam(key, scope, asOfDate)`; `saas.LegalParameterPackPublished` | saas-platform (Legal Parameter Service) | Kiểm tra tại ngày hiệu lực | BR-PPL-041, 051, 071 (changed 2026-10-16, R2-02 §5 #1) |
| IN-11 | `rec.CandidateHired` (payload v1, recruitment §7.3) — bàn giao tuyển dụng **chỉ tới People**: dữ liệu cá nhân của ứng viên, dữ liệu luật định, tài khoản ngân hàng, tài liệu, tham chiếu consent, yêu cầu tuyển dụng & vị trí, pháp nhân, ngày bắt đầu, mức đãi ngộ thỏa thuận (cơ sở trả lương GROSS/NET, lương cơ bản, lương net thỏa thuận, phụ cấp), loại hợp đồng & điều khoản thử việc, recruiter/quản lý tuyển dụng | recruitment | Hành động tuyển sau khi chấp nhận offer | Tạo pre-hire và bản ghi lương (P1); People là chủ sở hữu tài khoản ngân hàng và mức đãi ngộ thỏa thuận (changed 2026-10-16, R2-02 §5 #5, #24) |
| IN-12 | `rec.HireAmended` / `rec.HireWithdrawn` (ứng viên bội ước) | recruitment | Trước ngày bắt đầu | Cập nhật hoặc hủy pre-hire (`HireCancelled`) (changed 2026-10-16, R2-02 §5 #24) |
| IN-13 | Số dư phép (phép năm còn lại, tính đến ngày làm việc cuối cùng) và các giai đoạn nghỉ không lương | time-attendance | Khi có `TerminationScheduled` và tại LWD | Đầu vào quyết toán cuối cùng (thanh toán phép chưa nghỉ), xác nhận hoàn tất thủ tục nghỉ việc |
| IN-14 | Các giai đoạn nghỉ không lương / vắng mặt dài > ngưỡng, và các giai đoạn nghỉ thai sản | time-attendance | Khi được phê duyệt | Tính thâm niên, tạm hoãn hợp đồng, loại trừ năm làm việc khi tính trợ cấp thôi việc, tình trạng được bảo vệ |
| IN-15 | Trạng thái hoàn tất chấm công cho kỳ cuối cùng | time-attendance | Trước quyết toán cuối cùng | Xác nhận hoàn tất thủ tục nghỉ việc |
| IN-16 | `att.BusinessTripApproved` (ngày, nửa ngày, điểm đến trong nước/nước ngoài, trung tâm chi phí; ATT-P-08) và xác nhận tham dự đào tạo (không bắt buộc) | time-attendance | Khi phê duyệt/thay đổi/hủy chuyến công tác; khóa kỳ | Điền sẵn và kiểm tra chi phí công tác và công tác phí (BR-PPL-113) (changed 2026-10-16, R2-02 §5 #6) |
| IN-17 | Thang lương / khung lương theo ngạch / bậc và danh mục phụ cấp (mã, chịu thuế, đóng BHXH) — `pay.CompensationCatalogueChanged` | payroll | Tra cứu | Bản ghi lương, điều khoản phụ cấp trong hợp đồng (changed 2026-10-16, R2-02 §5 #7) |
| IN-18 | Lịch sử đóng BHXH để hiển thị (tháng, căn cứ, trạng thái, số tháng cộng dồn; `pay.SiContributionPosted`) — Payroll sở hữu toàn bộ lịch sử BHXH bao gồm các tháng trước go-live | payroll | Khi phê duyệt kỳ lương; theo yêu cầu | Hiển thị hồ sơ BHXH, chốt sổ BHXH (People không tính trợ cấp thôi việc) (changed 2026-10-16, R2-02 §5 #20, #29) |
| IN-19 | Kết quả trợ cấp thôi việc / mất việc làm của Payroll (thời gian làm việc, số tháng BHTN bị loại trừ, lương bình quân 6 tháng, số tiền), các khoản quyết toán cuối cùng (thanh toán phép, khấu trừ), đồng hồ hạn chót và trạng thái (`pay.FinalSettlementStatusChanged`) | payroll | Sau kỳ chạy quyết toán cuối cùng | Nội dung quyết định chấm dứt, hiển thị và đóng hồ sơ nghỉ việc (changed 2026-10-16, R2-02 §5 #29) |
| IN-20 | Dữ liệu thu nhập cho văn bản nhân sự (xác nhận thu nhập) và liên kết phiếu lương cho ESS | payroll | Theo yêu cầu | BR-PPL-132, ESS |
| IN-21 | Trạng thái chốt sổ BHXH / trạng thái khai báo BHXH cho tuyển mới & chấm dứt | payroll | Sau khi khai báo | Nghĩa vụ sau nghỉ việc (BR-PPL-076), checklist tiếp nhận |
| IN-22 | Trạng thái khấu trừ tạm ứng / chi trả hoàn ứng chi phí | payroll | Sau kỳ chạy lương | Trạng thái quyết toán tạm ứng |
| IN-23 | Ngữ cảnh tenant, các phân hệ/tính năng được bật, giới hạn gói (số nhân viên active tối đa) | saas-platform | Mỗi request; khi tuyển | Bật/tắt tính năng, kiểm tra giấy phép trước khi kích hoạt employment |
| IN-24 | Dịch vụ tài liệu (tải lên, quét virus, phiên bản, lớp lưu trữ, signed URL) trên object storage của saas-platform | core (P-21) | Tải lên/tạo tài liệu | Tài liệu (changed 2026-10-16, R2-02 §5 #15b) |
| IN-25 | Điều phối ký điện tử và trạng thái ký (Core P-21), dùng các connector chữ ký điện tử / hợp đồng lao động điện tử của saas-platform; callback trạng thái | core (điều phối) / saas-platform (connector) | Ký hợp đồng/quyết định | BR-PPL-058, 134 (changed 2026-10-16, R2-02 §5 #15b) |
| IN-26 | Connector VNeID / xác thực danh tính | saas-platform | Pre-hire / thay đổi dữ liệu | BR-PPL-012 |
| IN-27 | Consent registry (`hasConsent()`, `saas.ConsentChanged`), đăng ký trường trong PD inventory, điều phối DSR (`saas.DataSubjectRequestOpened`), bộ lập lịch lưu trữ | saas-platform | Yêu cầu của chủ thể dữ liệu; hằng ngày | BR-PPL-151/153/154 (changed 2026-10-16, R2-02 §5 #27) |
| IN-28 | Import engine bao gồm chế độ số dư đầu kỳ và các mẫu (changed 2026-10-16, D-09) | core (P-22) | Onboarding tenant / go-live | Nạp hàng loạt person, employment, hợp đồng và lương hiện tại, người phụ thuộc, và lịch sử làm việc cần cho trợ cấp thôi việc; People sở hữu các loại import này (changed 2026-10-16, R2-02 §5 #12) |
| IN-29 | `core.UserStatusChanged` (người dùng, liên kết nhân viên, trạng thái) | core | Khi cấp / hạ quyền | Liên kết nhân viên ↔ người dùng; mục sẵn sàng tiếp nhận "tài khoản người dùng" (`people.OnboardingReadinessChanged`) (new 2026-10-16, R2-02 §5 #9) |
| IN-30 | `pay.PayGroupAssigned` và trạng thái gán lịch làm việc của T&A | payroll, time-attendance | Khi gán theo quy tắc hoặc thủ công | Các mục sẵn sàng tiếp nhận "pay group", "lịch làm việc"; People không lưu pay group hay lịch làm việc (new 2026-10-16, R2-02 §5 #25, #26) |
| IN-31 | Shell ESS/MSS (điều hướng, xác thực, step-up, responsive web trong Phase 1) | saas-platform | Runtime | Host các trang ESS/MSS của People (BR-PPL-120) (new 2026-10-16, R2-02 §5 #17) |
| IN-32 | Đề xuất từ kiểm tra cư trú 183 ngày (người, năm, số ngày tại Việt Nam, trạng thái đề xuất) | payroll | Hằng tháng / theo yêu cầu | HR xem xét và phê duyệt thay đổi cư trú (BR-PPL-023) (new 2026-10-16, GAP-048) |

### 7.2 Cung cấp

| # | Nội dung (sự kiện / dữ liệu) | Tới phân hệ | Khi nào / kích hoạt | Payload chính |
|---|---|---|---|---|
| OUT-01 | `EmployeeHired` (bao gồm tái tuyển dụng, tuyển đồng thời) | payroll, time-attendance, core, recruitment, saas-platform | Employment được kích hoạt vào ngày vào làm (cũng có bản xem trước ngày tương lai khi phê duyệt) | hire_type NEW/REHIRE/CONCURRENT, employee_no, pháp nhân, ngày vào làm, ngày tính thâm niên, loại hình làm việc, nhóm người lao động, employment chính theo mục đích tại thời điểm tuyển (giảm trừ gia cảnh thuế TNCN, bảo hiểm xã hội — thay đổi sau đó qua OUT-36, R2-16), tham chiếu phân công & lương ban đầu, loại hợp đồng, cờ xem trước (changed 2026-10-16, R2-16; R2-02 §5 #9) |
| OUT-02 | `HireCancelled` | payroll, time-attendance, core, recruitment | Hủy pre-hire | employment_id, lý do |
| OUT-03 | API đọc dữ liệu gốc nhân viên (hiện tại & tại một ngày): person, employment, phân công, tóm tắt hợp đồng, tóm tắt lương (cần quyền) | tất cả phân hệ | Theo yêu cầu | Truy vấn theo ngày |
| OUT-04 | `AssignmentChanged` (điều chuyển, thăng chức, giáng chức, tái cơ cấu, đổi quản lý, đổi địa điểm, đổi trung tâm chi phí, bắt đầu/kết thúc kiêm nhiệm) | time-attendance, payroll, core, recruitment | Ngày hiệu lực (và khi phê duyệt với thay đổi có hiệu lực tương lai) | đơn vị tổ chức, vị trí, công việc, ngạch, quản lý, địa điểm, tỷ lệ chia trung tâm chi phí, FTE, chế độ thời giờ làm việc, loại phân công cũ/mới, cờ retro |
| OUT-05 | `SecondmentStarted` / `SecondmentEnded` | payroll, time-attendance, core | Ngày hiệu lực | pháp nhân gốc/tiếp nhận, pháp nhân chi trả, cờ phân bổ lại chi phí |
| OUT-06 | `TemporaryTransferStarted` / `Ended` kèm quy tắc bảo vệ tiền lương | payroll, time-attendance | Ngày hiệu lực | số ngày đã dùng trong năm, lương được bảo vệ đến ngày |
| OUT-07 | `people.SalaryChanged` — People là system of record cho mức đãi ngộ đã thỏa thuận | payroll | Ngày hiệu lực (quyết định / phụ lục đã duyệt) | salary record id + phiên bản, thang lương/ngạch/bậc, **cơ sở trả lương GROSS/NET, số tiền net thỏa thuận, phạm vi thành phần được đảm bảo net**, lương cơ bản, lương đóng BHXH, phụ cấp theo hợp đồng (mã & số tiền), % thử việc, tiền tệ, cờ retro, số quyết định / phụ lục (changed 2026-10-16, R2-02 §5 #24; GAP-047) |
| OUT-08 | `ContractSigned` / `ContractRenewed` / `ContractConverted` / `ContractAppendixSigned` | payroll, time-attendance, core | Khi hoàn tất ký | loại hợp đồng, số, ngày bắt đầu/kết thúc, thời hạn theo tháng (căn cứ cho việc bắt buộc tham gia BHXH khi ≥ 1 tháng và quy tắc khấu trừ thuế TNCN 10% với hợp đồng < 3 tháng), cờ thử việc |
| OUT-09 | `ProbationStarted` / `ProbationCompleted` (đạt/không đạt) | payroll, time-attendance, recruitment | Bắt đầu; ngày có kết quả | % lương thử việc, ngày kết thúc, kết quả (chất lượng tuyển dụng cho recruitment) |
| OUT-10 | `ContractExpiring` | core (thông báo), recruitment (kế hoạch tuyển bù) | Các mốc cấu hình trước ngày kết thúc | ngày kết thúc, hành động khuyến nghị |
| OUT-11 | `ContractSuspended` / `ContractResumed` | payroll, time-attendance | Ngày hiệu lực | lý do, từ/đến (dừng trả lương, xử lý BHXH, không yêu cầu chấm công) |
| OUT-12 | `people.TerminationScheduled` | payroll, time-attendance, core, recruitment | Nghỉ việc được phê duyệt | ngày làm việc cuối cùng, ngày chấm dứt, mã lý do, căn cứ pháp lý, tuân thủ báo trước (số ngày thiếu), cờ được bảo vệ, **access_revocation_date** (mặc định 23:59 ngày LWD; căn cứ để Core hạ quyền người dùng) (changed 2026-10-16, R2-02 §5 #9) |
| OUT-13 | `people.EmployeeTerminated` + **kích hoạt quyết toán cuối cùng** | payroll, time-attendance, core, recruitment, saas-platform | Ngày sau LWD | LWD, lý do/căn cứ pháp lý, mã chấm dứt BHXH/BHTN, điều kiện hưởng trợ cấp thôi việc hay mất việc làm, **các giai đoạn làm việc và các giai đoạn đã được chi trả** (Payroll tính thời gian làm việc tính trợ cấp thôi việc), bồi thường do thiếu thời gian báo trước (cả hai chiều), hoàn trả cam kết đào tạo, tạm ứng còn tồn, số dư bồi thường vật chất, tài sản chưa trả (changed 2026-10-16, R2-02 §5 #29; đồng hồ hạn quyết toán thuộc Payroll) |
| OUT-14 | `TerminationCancelled` (rút đơn nghỉ việc) | payroll, time-attendance, core, recruitment | Khi hủy | employment_id |
| OUT-15 | `people.PersonalDataChanged` (họ tên, số định danh, quốc tịch, **tình trạng cư trú thuế với valid from/to và căn cứ, ngày nhập cảnh/xuất cảnh**, địa chỉ, mã số thuế, mã BHXH) | payroll, core, time-attendance | Khi được phê duyệt | các trường thay đổi (tối thiểu hóa), ngày hiệu lực — payroll cần mã số thuế, mã BHXH, quốc tịch và tình trạng cư trú có hiệu lực theo ngày để xác định phương pháp tính thuế TNCN (BR-PPL-023) (changed 2026-10-16, GAP-048) |
| OUT-16 | `BankAccountChanged` | payroll | Khi được phê duyệt (đã xác thực step-up) | tài khoản, tỷ lệ chia, mặc định, hiệu lực từ |
| OUT-17 | `DependantChanged` (đăng ký / kết thúc / chuyển sang employment khác) | payroll | Khi HR phê duyệt | dependant id, quan hệ, mã số thuế, từ/đến tháng, employment kê khai — căn cứ giảm trừ gia cảnh thuế TNCN |
| OUT-18 | Đã bỏ — thuộc sở hữu của payroll (R2-02). Cam kết Mẫu 08/CK-TNCN và ủy quyền quyết toán thuế TNCN là tài liệu năm tính thuế của Payroll (BR-PAY-055/058/104), được thu thập trên các trang ESS của Payroll; People chỉ hiển thị trạng thái (changed 2026-10-16, R2-02 §5 #4) | — | — | Giữ ID; không dùng lại |
| OUT-19 | `SpecialStatusChanged` (mang thai, con < 12 tháng, khuyết tật, chưa thành niên, cao tuổi, công việc nặng nhọc/độc hại, đoàn viên công đoàn) | time-attendance, payroll | Khi được phê duyệt | loại, từ/đến — T&A giảm giờ làm/thêm ngày nghỉ; payroll đoàn phí công đoàn, các chế độ BHXH |
| OUT-20 | Dữ liệu thâm niên / thời gian làm việc (ngày tính thâm niên, thời gian làm việc trước đó được công nhận, các giai đoạn bị loại trừ) | time-attendance, payroll | Khi thay đổi | Tăng phép năm theo thâm niên (T&A), phụ cấp thâm niên (payroll) |
| OUT-21 | Đã bỏ — thuộc sở hữu của core (R2-02). People không phát `UserProvisioningRequested` / `UserDeactivationRequested`: Core tạo và hạ quyền người dùng từ `EmployeeHired` (OUT-01) và `TerminationScheduled.access_revocation_date` (OUT-12); tuyến báo cáo được truyền trong `AssignmentChanged` (OUT-04) (changed 2026-10-16, R2-02 §5 #9) | — | — | Giữ ID; không dùng lại |
| OUT-22 | Người đảm nhiệm vị trí & vị trí trống (đủ/trống/vượt định biên theo vị trí) | core, recruitment | Khi phân công thay đổi | position_id, người đảm nhiệm, FTE đã dùng |
| OUT-23 | Hồ sơ ứng viên nội bộ (kỹ năng, học vấn, chứng chỉ, xếp loại đánh giá — có consent) | recruitment | Khi tìm kiếm | cho luân chuyển nội bộ |
| OUT-24 | Tra cứu điều kiện tái tuyển / nhân viên cũ theo số định danh | recruitment | Khi tạo ứng viên | cờ đủ điều kiện, LWD gần nhất, nhóm lý do (không có chi tiết nhạy cảm) |
| OUT-25 | `RewardApproved` (bằng tiền) | payroll | Khi có quyết định | số tiền, kỳ lương, cờ chịu thuế, mã thành phần |
| OUT-26 | `DisciplineDecided` có ảnh hưởng tới payroll: kéo dài thời hạn nâng lương, cách chức (→ kèm `SalaryChanged`), % tạm ứng lương khi tạm đình chỉ, lịch khấu trừ bồi thường vật chất | payroll | Khi có quyết định | loại, số tiền, kỳ, giới hạn |
| OUT-27 | `AppraisalFinalised` / điểm KPI định kỳ | payroll | Khóa chu kỳ (hoặc hằng tháng với lương theo KPI) | kỳ, điểm, xếp loại — thưởng hiệu suất / lương theo KPI |
| OUT-28 | `ExpenseReimbursementApproved` | payroll (hoặc file xuất tài chính) | Khi phê duyệt cuối cùng | số tiền, loại, cờ chịu thuế, kỳ lương |
| OUT-29 | `AdvanceDeductionRequested` | payroll | Tạm ứng quá hạn chưa quyết toán / khi chấm dứt | số tiền, kỳ, tham chiếu consent |
| OUT-30 | Đã bỏ — thuộc sở hữu của time-attendance (R2-02). `att.BusinessTripApproved` do T&A phát và People tiêu thụ (IN-16) (changed 2026-10-16, R2-02 §5 #6) | — | — | Giữ ID; không dùng lại |
| OUT-31 | `TrainingAttendanceRecorded` (không bắt buộc) | time-attendance | Khi đóng buổi học | ngày, số giờ |
| OUT-32 | Số nhân viên active theo pháp nhân (ảnh chụp hằng ngày) | saas-platform | Hằng ngày / khi tuyển & chấm dứt | phục vụ tính phí & giới hạn gói |
| OUT-33 | Danh mục dữ liệu cá nhân, trạng thái consent và gói xuất DSAR cho dữ liệu People; xác nhận hoàn tất ẩn danh hóa | saas-platform | Khi có DSAR / job lưu trữ | gói dữ liệu theo từng người |
| OUT-34 | Dữ liệu dữ kiện về headcount & nghỉ việc (theo tổ chức, pháp nhân, nhóm) cho báo cáo/BI | saas-platform (kho báo cáo) | Hằng ngày | dữ kiện ảnh chụp (changed 2026-10-16, R2-02 §5 #18) |
| OUT-36 | `people.PrimaryEmploymentChanged` | payroll, time-attendance | Employment chính được thiết lập hoặc thay đổi cho một mục đích | person_id, employment_id, mục đích (PIT_FAMILY_DEDUCTION / SOCIAL_INSURANCE), valid_from, valid_to; việc tham gia bảo hiểm theo loại được truyền kèm (BR-PPL-004) (new 2026-10-16, R2-16) |
| OUT-35 | `people.ReorgMappingCompleted` | core | Sau khi áp dụng `core.ReorgActivated` và sau mỗi lần sửa và chạy lại | package_id, số đã chuyển, số lỗi, tham chiếu danh sách cần sửa theo nhân viên (BR-PPL-039) (new 2026-10-16, GAP-053) |

---

## 8. Báo cáo & phân tích

| # | Báo cáo | Đối tượng | Ghi chú |
|---|---|---|---|
| R1 | Danh sách nhân viên (tại ngày, bộ lọc, cột cấu hình được, che dữ liệu) | HR | Xuất Excel |
| R2 | Headcount & FTE theo pháp nhân / đơn vị tổ chức / địa điểm / nhóm, xu hướng | HR, ban lãnh đạo | |
| R3 | Báo cáo biến động: tuyển mới, điều chuyển, thăng chức, chấm dứt trong kỳ | HR | |
| R4 | Tỷ lệ nghỉ việc & giữ chân (tự nguyện/không tự nguyện, theo nhóm thâm niên, đáng tiếc) | HR, ban lãnh đạo | |
| R5 | Danh sách hợp đồng sắp hết hạn & sắp kết thúc thử việc; hợp đồng sắp chạm quy tắc chuyển đổi | HR | Nguồn cảnh báo hằng ngày |
| R6 | Danh sách giấy tờ tùy thân / giấy phép lao động / chứng chỉ sắp hết hạn | HR | |
| R7 | Dự báo nghỉ hưu (12–24 tháng tới) theo lộ trình luật định | HR | |
| R8 | Sổ quản lý lao động | HR, thanh tra | BR-PPL-140 |
| R9 | Báo cáo tình hình sử dụng lao động 6 tháng (Nghị định 145) | HR | BR-PPL-141 |
| R10 | Thông báo biến động lao động hằng tháng (Trung tâm Dịch vụ việc làm) | HR | BR-PPL-142 |
| R11 | Sổ đăng ký người phụ thuộc (thuế TNCN) và thay đổi theo tháng | HR, payroll | |
| R12 | Lịch sử thay đổi lương & tác động chi phí của các thay đổi | C&B | Cần quyền |
| R13 | Sổ kỷ luật & khen thưởng | HR | |
| R14 | Phân bổ đánh giá & kết quả hiệu chỉnh | HR, ban lãnh đạo | Phase 2 |
| R15 | Số giờ đào tạo, chi phí trên mỗi nhân viên, tuân thủ (đào tạo bắt buộc) | L&D | Phase 2 |
| R16 | Tuổi nợ chi phí & tạm ứng (tạm ứng còn tồn) | Tài chính | |
| R17 | Mức độ hoàn thành checklist tiếp nhận/nghỉ việc & đầu việc quá hạn | HR | |
| R18 | Nhân khẩu học (tuổi, giới tính, học vấn, thâm niên) | HR | Trường nhạy cảm chỉ hiển thị dạng tổng hợp |
| R19 | Luật BVDLCN: độ bao phủ consent, nhật ký truy cập dữ liệu nhạy cảm, SLA DSAR | DPO | |
| R20 | Báo cáo chất lượng dữ liệu (thiếu trường bắt buộc: mã số thuế, mã BHXH, ngân hàng, người liên hệ khẩn cấp) | HR | Kiểm tra trước khi chạy lương |

---

## 9. Yêu cầu phi chức năng (riêng cho phân hệ)

| ID | Yêu cầu | Ưu tiên |
|---|---|---|
| NFR-PPL-001 | Hiệu lực theo ngày: mọi truy vấn nhân viên phải hỗ trợ "tại ngày X" và "như đã biết vào ngày Y" (lịch sử đính chính) cho phân công, lương, hợp đồng. | M |
| NFR-PPL-002 | Việc phát sự kiện mang tính giao dịch (outbox) — không có thay đổi nhân sự nào được commit mà không có sự kiện tương ứng; gửi at-least-once với `event_id` idempotent. | M |
| NFR-PPL-003 | Trang hồ sơ nhân viên tải ≤ 2 s (p95) với tenant tối đa 10.000 nhân viên; tìm kiếm danh sách nhân viên ≤ 1 s (p95). | M |
| NFR-PPL-004 | Thao tác hàng loạt (thay đổi hàng loạt, tạo tài liệu) xử lý 5.000 nhân viên trong ≤ 10 phút, bất đồng bộ, có tiến độ và báo cáo lỗi theo từng dòng. | S |
| NFR-PPL-005 | Các trường nhạy cảm (số định danh, tài khoản ngân hàng, sức khỏe, tôn giáo, dân tộc) được mã hóa ở mức trường; được che trong log và các bản sao không phải môi trường production. | M |
| NFR-PPL-006 | Mọi lượt đọc dữ liệu nhạy cảm và mọi lượt ghi đều được log với người thực hiện, mục đích, trước/sau (với thao tác ghi). | M |
| NFR-PPL-007 | Import dữ liệu nhân viên ban đầu từ các mẫu Excel chuẩn (BR-SAAS-069): person, employment (bao gồm ngày vào làm ban đầu và ngày tính thâm niên), phân công hiện tại, hợp đồng hiện tại (kèm số hợp đồng xác định thời hạn trước đó), lương hiện tại cùng lịch sử lương cần cho trợ cấp thôi việc và lương bình quân, người phụ thuộc (kèm tháng đăng ký), tài khoản ngân hàng. Được kiểm tra kèm báo cáo đối soát; chạy lại idempotent. Không migrate cơ sở dữ liệu cũ (changed 2026-10-16, D-09). | M |
| NFR-PPL-008 | Cô lập tenant chặt chẽ: không đối chiếu person giữa các tenant. | M |
| NFR-PPL-009 | UI song ngữ Việt/Anh; collation tiếng Việt và tìm kiếm tên không phân biệt dấu. | M |
| NFR-PPL-010 | Tài liệu được tạo ở định dạng PDF/A để lưu trữ; mẫu hiển thị đúng font tiếng Việt. | M |
| NFR-PPL-011 | ESS dùng được trên trình duyệt di động (chiều rộng ≥ 360 px) với xác thực step-up khi thay đổi tài khoản ngân hàng. | M |
| NFR-PPL-012 | Thời hạn lưu trữ tài liệu và legal hold cấu hình được theo danh mục; việc xóa có thể kiểm chứng được. | S |

---

## 10. Giả định, câu hỏi mở, rủi ro

### 10.1 Giả định
- A1. Các danh mục vị trí, đơn vị tổ chức, công việc, ngạch được quản lý gốc trong `core`; People chỉ tham chiếu tới chúng.
- A2. Payroll tính mọi khoản tiền (trợ cấp thôi việc, mất việc làm, thanh toán phép, khấu trừ); People chỉ cung cấp điều kiện hưởng, dữ liệu đầu vào và kích hoạt.
- A3. Các khai báo BHXH/BHYT/BHTN (tuyển mới/chấm dứt/thay đổi lương) do `payroll` tạo từ sự kiện của People; People chỉ lưu mã BHXH và trạng thái lưu giữ sổ.
- A4. Nghỉ phép và chấm công (bao gồm nghỉ thai sản như một loại nghỉ) được quản lý gốc trong `time-attendance`; People chỉ lưu các trạng thái làm thay đổi chế độ pháp lý (tạm hoãn, tình trạng đặc biệt).
- A5. Một tenant có thể gồm nhiều pháp nhân; làm việc đồng thời tại nhiều pháp nhân của một tenant được hỗ trợ trong Phase 1 (cơ bản, R2-16: employment chính mang giảm trừ gia cảnh thuế TNCN và BHXH); không hỗ trợ làm việc đồng thời giữa các tenant (changed 2026-10-16, R2-16).
- A7. People sở hữu mức đãi ngộ đã thỏa thuận; Payroll chỉ sở hữu các kết quả lương phái sinh và hồ sơ trả lương (R2-02 §5 #24).
- A6. Các connector chữ ký điện tử và VNeID do `saas-platform` cung cấp dưới dạng dịch vụ dùng chung.

### 10.2 Câu hỏi mở
| # | Câu hỏi | Người phụ trách |
|---|---|---|
| Q1 | ~~Ai sở hữu các tham số luật định?~~ **Đã đóng 2026-10-16 (R2-02 §5 #1):** Legal Parameter Service của saas-platform; People đọc theo ngày hiệu lực (IN-10). | PM / Core BA / Payroll BA |
| Q2 | ~~Thang lương và danh mục phụ cấp được quản lý gốc ở payroll hay core?~~ **Đã đóng 2026-10-16 (R2-02 §5 #7):** payroll sở hữu thang lương, khung lương và danh mục thành phần; Core chỉ sở hữu danh mục ngạch. | Payroll BA |
| Q3 | ~~Payroll có cung cấp các giai đoạn tham gia BHTN từ lịch sử BHXH của mình không?~~ **Đã đóng 2026-10-16 (R2-02 §5 #20):** Payroll sở hữu toàn bộ lịch sử đóng BHXH bao gồm các tháng trước go-live (import số dư đầu kỳ); People chỉ lưu số BHXH và tình trạng lưu giữ sổ, và Payroll tính phần loại trừ khi tính trợ cấp thôi việc (§5 #29). | Payroll BA |
| Q4 | Điểm KPI hằng tháng cho lương gắn KPI: tenant pilot có cần trong MVP không? Nếu có sẽ chuyển BR-PPL-093 thành `M`. | PM |
| Q5 | ~~Luồng hoàn ứng chi phí?~~ **Đã đóng 2026-10-16 (R2-02 §5 #21):** theo tenant **theo từng loại chi phí** — thành phần lương hoặc file xuất tài chính (file trong Phase 1; API kế toán là Phase 2, R2-01). Đề nghị thanh toán chi phí và tạm ứng là Phase 2 trừ khi pilot cần. | PM / Tài chính |
| Q6 | ~~Công tác: People hay T&A sở hữu?~~ **Đã đóng 2026-10-16 (R2-02 §5 #6):** T&A sở hữu yêu cầu công tác; People tiêu thụ `att.BusinessTripApproved`. | T&A BA |
| Q7 | Hợp đồng cộng tác viên/dịch vụ (không phải hợp đồng lao động) được xử lý như một Employment trong People hay như một thực thể "contractor" riêng chỉ để tính thuế TNCN? | Payroll BA |
| Q8 | Hợp đồng lao động điện tử: những nhà cung cấp nào và việc nộp lên nền tảng quốc gia có bắt buộc với tenant pilot trong năm 2026 không? | Chuyên gia tư vấn pháp lý |
| Q9 | Thời hạn lưu trữ dữ liệu nhân sự sau khi nghỉ việc (tương tác giữa luật lao động, BHXH, thuế, Luật BVDLCN). | Chuyên gia tư vấn pháp lý / DPO |
| Q10 | Consent từ tuyển dụng: People có thể tái sử dụng consent của ứng viên không, hay phải thu thập consent mới của nhân viên ở giai đoạn pre-hire? | DPO / Recruitment BA |
| Q11 | ~~ESS cho nhân viên cũ có nằm trong MVP không?~~ **Đã đóng 2026-10-16 (OD-8, GAP-036):** có — vai trò `FORMER_EMPLOYEE` của Core với đăng nhập bằng OTP qua e-mail; People thu thập e-mail cá nhân khi nghỉ việc. | PM |

### 10.3 Rủi ro
| # | Rủi ro | Tác động | Giảm thiểu |
|---|---|---|---|
| RK1 | Thay đổi pháp luật thường xuyên (Luật BVDLCN 2025, Luật Việc làm 2025, nghị định hợp đồng điện tử, sáp nhập đơn vị hành chính 2025) | Làm lại, không tuân thủ | Tham số hóa; cổng review `[verify-legal]`; chuyên gia tư vấn pháp lý ký duyệt |
| RK2 | Chất lượng dữ liệu ban đầu do khách hàng cung cấp (trùng lặp, thiếu số định danh/mã số thuế, địa chỉ 3 cấp) (changed 2026-10-16, D-09) | Đối chiếu person sai, lỗi tính lương | Báo cáo chất lượng dữ liệu R20, công cụ gộp, import chỉ kiểm tra (validate-only) và đối soát số dư đầu kỳ (BR-SAAS-072) |
| RK7 | Employment chính theo mục đích hoặc việc tham gia bảo hiểm theo loại bị sai hoặc thiếu đối với nhân viên làm việc đồng thời (R2-16) | Giảm trừ gia cảnh bị áp dụng hai lần hoặc không áp dụng; BHXH/BHYT/BHTN được khai báo bởi sai pháp nhân | Kiểm tra: đúng một employment chính cho mỗi người theo mỗi mục đích tại bất kỳ ngày nào (BR-PPL-004); báo cáo ngoại lệ payroll; review pháp lý các quy tắc theo loại `[verify-legal]` |
| RK3 | Thay đổi hồi tố có hiệu lực theo ngày sau khi đã khóa kỳ lương | Sai lương | Cờ retro trên sự kiện; quy trình truy lĩnh/truy thu của payroll được thống nhất trong gap analysis |
| RK4 | Sai lệch hợp đồng sự kiện giữa People và các bên tiêu thụ | Lỗi tích hợp | Schema sự kiện có phiên bản, contract test |
| RK5 | Lộ dữ liệu nhạy cảm (ngân hàng, định danh, sức khỏe) | Chế tài pháp lý, uy tín | Mã hóa ở mức trường, che dữ liệu, log truy cập, báo cáo DPO |
| RK6 | Phạm vi rộng (đánh giá, đào tạo, chi phí) so với timeline MVP | Chậm tiến độ | MVP = tất cả mục `M` (bao gồm đánh giá thử việc BR-PPL-094); chi phí (BR-PPL-110–113) hoãn lại trừ khi Q5 được giải quyết; Phase 2 cho đánh giá/đào tạo đầy đủ |
