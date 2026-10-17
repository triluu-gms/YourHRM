# YourHRM — BRD Module 01: Core (`core`)

| Hạng mục | Giá trị |
|---|---|
| Module | `core` (DB schema `core`, backend `backend/src/modules/core` + dùng chung `auth`, `audit`, `workflow`, `common`) |
| Tiền tố yêu cầu | `BR-CORE-NNN`, `NFR-CORE-NNN` |
| Phiên bản / ngày | Bản nháp v0.2, 2026-10-16 (v0.1 chỉnh sửa nhỏ 2026-10-16 theo D-09/OD-9: BR-CORE-005, 081, 114, P-07, P-22, R-01) |
| Ghi chú thay đổi | v0.2 (2026-10-16): áp dụng bảng chủ sở hữu duy nhất đã phê duyệt ([gap-analysis](../gap-analysis.md) §5, quyết định **R2-02**). Xác thực, SSO, MFA, session và API client chuyển sang saas-platform (bỏ BR-CORE-038/039/042, bỏ P-05, trỏ lại P-09); các tham số pháp lý, bao gồm bảng ánh xạ vùng, được đọc từ SaaS Legal Parameter Service (BR-CORE-006, A-03); bỏ khung lương theo ngạch bậc (BR-CORE-021); xuất danh sách chuyển sang SaaS reporting framework (bỏ BR-CORE-113); gửi thông báo qua SaaS delivery adapter (BR-CORE-070/073); dịch vụ tài liệu dựa trên SaaS object storage; cấp tài khoản người dùng chỉ được kích hoạt bởi sự kiện People (P-04, C-07/C-08); master tỷ giá mới (BR-CORE-088). GAP-049: cơ quan thuế, kỳ khai thuế TNCN và quyết định mức đóng BHTNLĐ-BNN gắn với pháp nhân (BR-CORE-002); trạng thái khóa của pháp nhân được đọc từ Payroll (C-16). GAP-053: kết quả ánh xạ tái cơ cấu `people.ReorgMappingCompleted` (C-12). R2-17: ngoại lệ phiếu lương PDF đối với BR-CORE-074. Các chỉnh sửa được đánh dấu "(thay đổi 2026-10-16, R2-02 §5 #n)" hoặc kèm mã quyết định/gap. Cột độ ưu tiên và giai đoạn không thay đổi (PM đồng bộ). |
| Tác giả | BA – Core |
| Trạng thái | Bản nháp để review (Phase 0) |
| Liên quan | [Project charter](../../../00-project-management/project-charter.md), 02-people, 03-time-attendance, 04-payroll, 05-recruitment, 06-saas-platform |

> **Giai đoạn:** giai đoạn triển khai của mọi yêu cầu chỉ được xác định trong [mvp-scope.md](../mvp-scope.md) §9; MoSCoW ở đây là độ ưu tiên nghiệp vụ. (2026-10-16)

> Lưu ý quy ước: các giá trị pháp lý là tham số có hiệu lực theo ngày, không bao giờ hard-code. Mọi số liệu pháp lý trong tài liệu này được đánh dấu `[verify-legal]` cho đến khi được chuyên gia tư vấn lao động/thuế Việt Nam xác nhận.

---

## 1. Mục đích & phạm vi

### 1.1 Mục đích
Core là nền tảng mà mọi module khác dựa vào bên trong một tenant. Core sở hữu:
- **Cơ cấu người sử dụng lao động**: ai là người sử dụng lao động theo pháp luật (pháp nhân), người lao động làm việc ở đâu (chi nhánh / địa điểm làm việc), tổ chức được cấu trúc ra sao (đơn vị tổ chức, có hiệu lực theo ngày) và kiến trúc công việc (chức danh, vị trí, ngạch bậc, trung tâm chi phí).
- **Phân quyền**: tài khoản người dùng tenant, vai trò, quyền, bảo mật theo phạm vi dữ liệu, che giấu trường dữ liệu. Xác thực (mật khẩu, MFA, SSO, session, API client) do saas-platform sở hữu (thay đổi 2026-10-16, R2-02 §5 #10).
- **Engine dùng chung**: workflow & phê duyệt, trung tâm thông báo (gửi qua kênh bằng adapter của saas-platform), import engine, dịch vụ tài liệu (dựa trên object storage của saas-platform), dịch vụ và kho audit. Xuất dữ liệu và báo cáo do saas-platform sở hữu (thay đổi 2026-10-16, R2-02 §5 #12–#15b, #18).
- **Dữ liệu tham chiếu dùng chung**: đơn vị hành chính Việt Nam (sau sáp nhập 2025), quốc tịch, dân tộc, tôn giáo, ngân hàng, tiền tệ, danh mục mã chung, cấu hình tenant.

### 1.2 Trong phạm vi
| # | Lĩnh vực |
|---|---|
| S1 | Hồ sơ công ty của tenant và **pháp nhân** (1..n mỗi tenant), mỗi pháp nhân có MST, mã đơn vị BHXH, vùng lương tối thiểu, tài khoản ngân hàng, người đại diện theo pháp luật, hình ảnh con dấu/chữ ký |
| S2 | Chi nhánh / địa điểm làm việc (địa chỉ, vùng lương tối thiểu, dữ liệu geo-fence cho chấm công, múi giờ) |
| S3 | Cây tổ chức: đơn vị tổ chức, loại đơn vị, người quản lý, tái cơ cấu có hiệu lực theo ngày (sáp nhập, chia tách, di chuyển, đổi tên, giải thể) kèm lịch sử và thay đổi có hiệu lực trong tương lai |
| S4 | Kiến trúc công việc: nhóm công việc, chức danh, vị trí (định biên), danh mục ngạch bậc (mã, thứ tự, cấu trúc — không có mức lương; thang bảng lương và khung lương do payroll sở hữu), trung tâm chi phí (thay đổi 2026-10-16, R2-02 §5 #7) |
| S5 | Tài khoản người dùng tenant, vai trò, quyền, quy tắc phạm vi dữ liệu, che giấu trường dữ liệu, ủy quyền truy cập. SSO (OIDC / SAML), MFA, mật khẩu và session được sử dụng từ saas-platform (thay đổi 2026-10-16, R2-02 §5 #10) |
| S6 | Engine workflow & phê duyệt chung (nhiều cấp, định tuyến có điều kiện, song song, ủy quyền, thay thế, SLA, leo thang, thu hồi, phê duyệt trên mobile — phê duyệt trên mobile thuộc Phase 2, thay đổi 2026-10-16, OD-9) |
| S7 | Trung tâm thông báo: mã sự kiện, mẫu, người nhận/phạm vi, tùy chọn, khung giờ yên lặng, bản tổng hợp, hộp thư trong ứng dụng, quy tắc dự phòng. Việc gửi qua kênh (SMTP/DKIM, push, ZNS, SMS) và tài khoản nhà cung cấp là adapter của saas-platform (thay đổi 2026-10-16, R2-02 §5 #14) |
| S8 | Cấu hình tenant & danh mục mã chung; kho master data toàn cục và admin API (tỉnh/phường, quốc tịch, dân tộc, tôn giáo, ngân hàng, tiền tệ, loại giấy tờ tùy thân); tỷ giá (thay đổi 2026-10-16, R2-02 §5 #19, #28) |
| S9 | Dịch vụ và kho audit (thay đổi dữ liệu + sự kiện bảo mật, bao gồm sự kiện do identity của saas-platform và back-office của operator ghi vào), bất biến, có thể tìm kiếm (thay đổi 2026-10-16, R2-02 §5 #13) |
| S10 | Dịch vụ tài liệu (metadata, phân quyền truy cập theo bản ghi, quản lý phiên bản, mẫu, lớp lưu trữ, điều phối ký điện tử) dựa trên object storage của saas-platform (thay đổi 2026-10-16, R2-02 §5 #15/#15b) |
| S11 | Import engine (mẫu, kiểm tra hợp lệ, staging, dry-run, mặc định all-or-nothing, job bất đồng bộ, Excel/CSV) bao gồm chế độ số dư đầu kỳ. Xuất dữ liệu thuộc reporting framework của saas-platform (thay đổi 2026-10-16, R2-02 §5 #12, #18) |
| S12 | Dịch vụ sinh số thứ tự / đánh số (mã nhân viên, số văn bản) và tiện ích lịch không phụ thuộc ngày lễ dùng cho SLA của workflow |

### 1.3 Ngoài phạm vi (do module khác sở hữu)
| Hạng mục | Chủ sở hữu |
|---|---|
| Khởi tạo tenant, gói thuê bao, thanh toán, feature flag theo gói, super-admin nền tảng, thao tác liên tenant, vị trí lưu trữ dữ liệu của nền tảng | saas-platform (`06`) |
| Hồ sơ cá nhân / nhân viên, phân công công việc, hợp đồng, headcount thực tế | people (`02`) |
| Ngày lễ, lịch làm việc, ca làm việc, nghỉ phép | time-attendance (`03`) — Core chỉ cung cấp interface tính ngày làm việc *sử dụng* lịch ngày lễ của T&A |
| **Tham số pháp lý**: mức lương tối thiểu và bảng ánh xạ đơn vị hành chính → vùng, tỷ lệ và mức trần BHXH/BHYT/BHTN/BHTNLĐ-BNN, biểu thuế TNCN, giới hạn làm thêm giờ, mẫu ngày lễ, v.v. | Legal Parameter Service của saas-platform (`06`) — Core xác định địa điểm → vùng bằng cách đọc bảng ánh xạ của pack (thay đổi 2026-10-16, R2-02 §5 #1) |
| Thành phần lương, thang bảng lương, khung lương theo ngạch bậc | payroll (`04`) (thay đổi 2026-10-16, R2-02 §5 #7) |
| Xác thực, SSO, MFA, session, API client và thông tin xác thực | saas-platform (`06`) (thay đổi 2026-10-16, R2-02 §5 #10) |
| Object storage, job scheduler / queue, sổ đăng ký sự đồng ý, danh mục dữ liệu cá nhân, điều phối DSR, bộ lập lịch lưu trữ | saas-platform (`06`) (thay đổi 2026-10-16, R2-02 §5 #15, #16, #27) |
| Yêu cầu tuyển dụng, kế hoạch nhân sự (Phase 2; kế hoạch ghi ngân sách FTE và vị trí đề xuất vào Core thông qua API) | recruitment (`05`) (thay đổi 2026-10-16, R2-02 §5 #8) |
| Reporting framework, reporting store, mọi loại xuất dữ liệu bao gồm xuất danh sách, BI | saas-platform (`06`) — Core cung cấp predicate phạm vi và che giấu dữ liệu (P-10) (thay đổi 2026-10-16, R2-02 §5 #18) |

---

## 2. Tác nhân & persona

| Tác nhân | Mô tả | Hoạt động Core điển hình |
|---|---|---|
| Tenant Owner / Super Admin (Quản trị hệ thống khách hàng) | Quản trị viên cao nhất phía khách hàng, được tạo khi khởi tạo tenant | Cấu hình pháp nhân, SSO, vai trò, cấu hình; cấp quyền quản trị |
| HR Administrator (Quản trị nhân sự) | Vận hành nhân sự cho một hoặc nhiều pháp nhân | Duy trì cây tổ chức, vị trí, chức danh; chạy import; cấu hình workflow & mẫu |
| Chuyên viên C&B | Lương thưởng & phúc lợi | Duy trì danh mục ngạch bậc và trung tâm chi phí; khung lương nằm ở payroll (thay đổi 2026-10-16, R2-02 §5 #7) |
| Line Manager (Quản lý trực tiếp) | Quản lý một nhánh cây tổ chức | Phê duyệt yêu cầu; xem nhánh của mình |
| Nhân viên (người dùng ESS) | Mọi nhân viên có tài khoản đăng nhập | Phê duyệt khi được ủy quyền; nhận thông báo; quản lý tùy chọn cá nhân |
| Người phê duyệt / Người được ủy quyền / Người thay thế | Bất kỳ người dùng nào thực hiện một bước workflow | Phê duyệt, từ chối, trả lại, ủy quyền, bình luận |
| Người dùng tài chính | Kế toán / hạch toán chi phí lương | Duy trì ánh xạ trung tâm chi phí; chỉ xem tổ chức |
| Kiểm toán viên (nội bộ / bên ngoài) | Chỉ đọc | Tìm kiếm audit log, xuất bằng chứng |
| Quản trị viên IT (khách hàng) | IT phía khách hàng | Ánh xạ scope của API client sang quyền/phạm vi dữ liệu; SSO, IP allowlist và thông tin xác thực API client được cấu hình trong saas-platform (thay đổi 2026-10-16, R2-02 §5 #10) |
| Hệ thống (scheduler / tích hợp) | Tác nhân không phải con người | Chạy bộ đếm SLA, kích hoạt theo ngày hiệu lực, gửi thông báo, job import |
| Platform Operator | Nhân viên YourHRM (saas-platform) | Duy trì master data toàn cục (tỉnh thành, ngân hàng) qua giao diện back-office và quy trình phát hành của saas-platform, nạp dữ liệu vào kho của Core; truy cập hỗ trợ (break-glass) (thay đổi 2026-10-16, R2-02 §5 #28) |

---

## 3. Quy trình nghiệp vụ

### P-01 Thiết lập cơ cấu người sử dụng lao động của tenant (cấu hình ban đầu)
1. saas-platform khởi tạo tenant và tạo tài khoản Tenant Owner (Core nhận `TenantProvisioned`). Quá trình khởi tạo tạo pháp nhân đầu tiên thông qua API của Core; Core là master duy nhất của pháp nhân, saas-platform chỉ giữ bên thanh toán và giới hạn số pháp nhân theo gói (thay đổi 2026-10-16, R2-02 §5 #23).
2. Tenant Owner nhập hồ sơ tập đoàn/công ty (tên, logo, ngôn ngữ mặc định, múi giờ `Asia/Ho_Chi_Minh`, tiền tệ VND).
3. Tenant Owner tạo ≥1 **pháp nhân**: tên pháp lý (VN/EN), tên viết tắt, mã số thuế doanh nghiệp (MST), mã đơn vị BHXH, số/ngày đăng ký kinh doanh, địa chỉ đăng ký (tỉnh/phường), người đại diện theo pháp luật, vùng lương tối thiểu, tài khoản ngân hàng mặc định, tháng bắt đầu năm tài chính.
4. Hệ thống kiểm tra định dạng MST và tính duy nhất trong tenant; kích hoạt pháp nhân.
5. HR Admin tạo chi nhánh / địa điểm làm việc, mỗi địa điểm gắn với đúng một pháp nhân, có địa chỉ, vùng và (tùy chọn) tọa độ địa lý và bán kính.
6. HR Admin xây dựng cây tổ chức (gốc theo từng pháp nhân hoặc một gốc cấp tập đoàn), chức danh, ngạch bậc, vị trí, trung tâm chi phí — thủ công hoặc qua import (P-09).
7. Ngoại lệ: gói thuê bao giới hạn số pháp nhân (từ saas-platform) → hệ thống chặn việc tạo và hiển thị thông báo giới hạn gói.

### P-02 Tái cơ cấu tổ chức có hiệu lực theo ngày (tái cơ cấu tổ chức)
1. HR Admin mở một **gói tái cơ cấu** với ngày hiệu lực (quá khứ, hôm nay hoặc tương lai).
2. Thêm các thay đổi: tạo đơn vị, đổi tên, chuyển sang đơn vị cha mới, sáp nhập A+B→C, chia tách A→A1+A2, đổi người quản lý, đổi trung tâm chi phí, giải thể đơn vị.
3. Hệ thống tính toán tác động: các vị trí, nhân viên bị ảnh hưởng (qua people), yêu cầu tuyển dụng đang mở (qua recruitment), các workflow instance đang hoạt động được định tuyến tới người quản lý bị ảnh hưởng, thay đổi phạm vi dữ liệu.
4. Gói được trình phê duyệt qua workflow engine (process type `CORE_REORG`).
5. Khi được phê duyệt và đến ngày hiệu lực, scheduler kích hoạt gói một cách nguyên tử: tạo các phiên bản tổ chức mới, kết thúc các phiên bản cũ (ngày kết thúc = ngày hiệu lực − 1).
6. Core phát sự kiện `OrgUnitChanged` / `ReorgActivated`; module people ánh xạ lại phân công công việc theo bảng ánh xạ trong gói; recruitment và workflow định tuyến lại.
7. Ngoại lệ: (a) đơn vị sắp giải thể vẫn còn nhân viên đang làm việc chưa được ánh xạ → chặn; (b) gói hồi tố vượt qua kỳ lương đã khóa → cảnh báo và yêu cầu quyền `CORE_REORG_BACKDATE`; payroll được thông báo để xử lý hồi tố; (c) các gói tương lai xung đột trên cùng một đơn vị → chặn cho đến khi được giải quyết.

### P-03 Quản lý vị trí
1. HR Admin tạo vị trí: mã, chức danh (job title), đơn vị tổ chức, địa điểm, khoảng ngạch bậc, trung tâm chi phí, vị trí báo cáo trực tiếp, ngân sách định biên (FTE), trạng thái (đề xuất / hoạt động / đóng băng / đóng), ngày hiệu lực.
2. Phê duyệt tùy chọn (`CORE_POSITION`).
3. Module people phân công nhân viên vào vị trí; Core cung cấp mức lấp đầy = FTE ngân sách − FTE đã lấp (FTE đã lấp do people cung cấp).
4. Recruitment tạo yêu cầu tuyển dụng cho các vị trí còn trống.
5. Ngoại lệ: đóng vị trí đang có người đảm nhiệm → chặn; đóng băng → chặn yêu cầu tuyển dụng mới.

### P-04 Vòng đời người dùng & quyền truy cập
Core sở hữu tài khoản người dùng tenant và **chỉ phản ứng với sự kiện People**; People không phát "yêu cầu" cấp tài khoản nào (thay đổi 2026-10-16, R2-02 §5 #9).
1. People phát `people.EmployeeHired` (bao gồm cả bản xem trước trước ngày nhận việc khi được phê duyệt) → Core tự động tạo người dùng (nếu cấu hình tenant `auto_create_user = true`) với vai trò ESS mặc định, login = email công việc hoặc mã nhân viên, locale, và phát `core.UserStatusChanged(ACTIVE)`; sau đó saas-platform tạo credential/identity.
2. Liên kết kích hoạt được gửi qua thông báo (email ở Phase 1). Với SSO (saas-platform), identity được liên kết ở lần đăng nhập SSO đầu tiên qua claim email / `employee_code`.
3. HR Admin gán thêm vai trò kèm phạm vi dữ liệu (ví dụ "HR Officer" phạm vi = Pháp nhân A).
4. Vai trò quản lý được **suy ra** tự động: người quản lý của một đơn vị tổ chức nhận vai trò `LINE_MANAGER` với phạm vi = nhánh của mình (không cần cấp thủ công).
5. Khi nhận `people.TerminationScheduled`, Core lên lịch hạ quyền truy cập tại `access_revocation_date` (mặc định: 23:59 ngày làm việc cuối cùng). Tại thời điểm đó các phê duyệt đang chờ được giao lại (P-07 bước 8), session bị thu hồi qua saas-platform và người dùng chuyển sang vai trò `FORMER_EMPLOYEE` (phiếu lương, chứng từ khấu trừ thuế TNCN, thư xác nhận của bản thân) trong khoảng thời gian do tenant thiết lập (OD-8); sau khoảng thời gian đó tài khoản bị vô hiệu hóa hoàn toàn. Mỗi thay đổi phát `core.UserStatusChanged` (thay đổi 2026-10-16, R2-02 §5 #9).
6. Ngoại lệ: tái tuyển dụng kích hoạt lại cùng người dùng; một người dùng có thể tồn tại mà không có hồ sơ nhân viên (kiểm toán viên bên ngoài, tư vấn viên) với ngày hết hạn.

### P-05 Single sign-on — Đã bỏ — do saas-platform sở hữu (R2-02)
Cấu hình SSO, ánh xạ claim, bắt buộc áp dụng và phương án dự phòng khi IdP gián đoạn là quy trình P7 của saas-platform (BR-SAAS-040/041). Core chỉ ánh xạ identity đã xác thực (PR-03) sang tài khoản người dùng tenant và các vai trò của nó (thay đổi 2026-10-16, R2-02 §5 #10).

### P-06 Cấu hình workflow
1. HR Admin chọn một **process type** do module công bố (ví dụ `ATT_LEAVE_REQUEST`, `ATT_OT_REQUEST`, `PPL_CONTRACT_NEW`, `PAY_RUN_APPROVAL`, `REC_REQUISITION`, `CORE_REORG`).
2. Định nghĩa một hoặc nhiều **workflow definition** với điều kiện áp dụng (pháp nhân, đơn vị tổ chức, loại nhân viên, thuộc tính yêu cầu như số ngày nghỉ > 3, số giờ làm thêm, số tiền).
3. Định nghĩa các bước: cách xác định người phê duyệt (quản lý trực tiếp, quản lý cấp N, người giữ vị trí, vai trò trong phạm vi, người dùng chỉ định, trường động từ yêu cầu, HR của pháp nhân), loại bước (một người, any-of, all-of, nhánh song song), SLA (giờ/ngày làm việc), quy tắc leo thang, hành động tự động khi quá hạn (leo thang / tự động phê duyệt / tự động từ chối — chỉ tự động phê duyệt nếu process type cho phép), quy tắc bỏ qua (người phê duyệt = người yêu cầu, người phê duyệt trùng lặp).
4. Công bố một phiên bản; các instance đang chạy giữ nguyên phiên bản lúc bắt đầu.

### P-07 Thực hiện phê duyệt
1. Module gửi yêu cầu → Core tạo workflow instance kèm snapshot tóm tắt yêu cầu và chuỗi người phê duyệt đã xác định.
2. Người phê duyệt nhận thông báo (trong ứng dụng + email; push/Zalo ở Phase 2) kèm deep link và phê duyệt trên web app; phê duyệt từ mobile app hoặc liên kết thao tác trong email (dùng một lần, có ký, có thời hạn) thuộc Phase 2 (thay đổi 2026-10-16, OD-9).
3. Người phê duyệt: phê duyệt, từ chối (bắt buộc nêu lý do), trả lại để chỉnh sửa (về người yêu cầu hoặc một bước trước), yêu cầu bổ sung thông tin, chuyển tiếp (nếu được phép), thêm bình luận/tệp đính kèm.
4. Ủy quyền: công việc của người phê duyệt vắng mặt được chuyển tới người được ủy quyền đã định nghĩa cho khoảng thời gian / process type; hành động được ghi nhận "do B thực hiện thay mặt A".
5. Thay thế: HR Admin có thể giao lại một công việc đang mở cho người dùng khác (bắt buộc nêu lý do).
6. Bộ đếm SLA chạy theo thời gian làm việc (lịch làm việc của tenant từ T&A); nhắc nhở ở các mức % SLA có thể cấu hình; khi vi phạm → leo thang lên quản lý cấp kế tiếp hoặc vai trò được chỉ định.
7. Khi có kết quả cuối cùng, Core gọi lại module sở hữu (`WorkflowCompleted` kèm kết quả) — module áp dụng tác động nghiệp vụ.
8. Ngoại lệ: người phê duyệt bị vô hiệu hóa / vị trí trống → cơ chế xác định chuyển sang phương án dự phòng (quản lý của quản lý → HR của pháp nhân) và ghi log; người yêu cầu rút lại trước quyết định cuối cùng; thay đổi tổ chức giữa chừng → các bước đang mở được xác định lại theo cấu hình tenant (`reresolve_on_reorg`).

### P-08 Gửi thông báo
1. Một module phát sự kiện thông báo với mã mẫu, người nhận (người dùng, vai trò trong phạm vi, email/số điện thoại bên ngoài) và payload dữ liệu.
2. Core render mẫu theo ngôn ngữ của người nhận (VI/EN), áp dụng tùy chọn kênh và khung giờ yên lặng, loại bỏ trùng lặp và đưa vào hàng đợi theo từng kênh.
3. Core chuyển từng tin nhắn tới channel delivery adapter của saas-platform, adapter gửi đi có retry và trả về trạng thái gửi; Core áp dụng quy tắc dự phòng sang kênh kế tiếp (ví dụ Zalo ZNS lỗi → SMS → email; Zalo và SMS thuộc Phase 2) (thay đổi 2026-10-16, R2-02 §5 #14).
4. Hộp thư trong ứng dụng hiển thị số chưa đọc; các mục có deep link tới bản ghi.

### P-09 Import dữ liệu
1. Người dùng tải về mẫu có định kiểu (Excel) cho một loại import do module công bố (ví dụ đơn vị tổ chức, vị trí, nhân viên, số dư phép đầu kỳ, lịch sử lương).
2. Tải tệp lên → Core lưu tệp, phân tích vào staging, chạy các validator do module cung cấp.
3. Người dùng xem lỗi theo từng dòng (tệp lỗi có thể tải về), sửa và tải lên lại. Commit **mặc định là all-or-nothing**; chỉ commit các dòng hợp lệ khi loại import cho phép commit một phần (thay đổi 2026-10-16, R2-02 §5 #12).
4. Commit chạy bất đồng bộ; tóm tắt kết quả + audit trail cho từng job import; rollback một lô trong vòng 24 h nếu chưa có giao dịch phụ thuộc (thay đổi 2026-10-16, R2-02 §5 #12).

### P-10 Điều tra audit
1. Kiểm toán viên lọc audit log theo loại thực thể, bản ghi, người dùng, ngày, hành động, IP.
2. Xem giá trị trước/sau; xuất ra Excel/CSV (bản thân việc xuất cũng được audit).

---

## 4. Yêu cầu nghiệp vụ

### 4.1 Công ty, pháp nhân, địa điểm
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-001 | Một tenant phải có một hồ sơ tập đoàn/công ty và **1..n pháp nhân**; số lượng tối đa bị giới hạn bởi gói thuê bao của tenant (từ saas-platform). | M | Hồ sơ tập đoàn chứa nhận diện thương hiệu, ngôn ngữ mặc định, múi giờ, tiền tệ. |
| BR-CORE-002 | Mỗi pháp nhân phải lưu: tên pháp lý VI/EN, tên viết tắt, mã số thuế doanh nghiệp (MST), mã đơn vị BHXH, số/ngày/nơi cấp đăng ký kinh doanh, địa chỉ đăng ký (tỉnh + phường), điện thoại, email, người đại diện theo pháp luật (họ tên, chức vụ, giấy tờ tùy thân), vùng lương tối thiểu mặc định, tháng bắt đầu năm tài chính, (các) tài khoản ngân hàng mặc định, hình ảnh con dấu và chữ ký người có thẩm quyền, trạng thái có hiệu lực theo ngày. Pháp nhân cũng phải lưu, có hiệu lực theo ngày: **cơ quan thuế quản lý**, **kỳ khai thuế TNCN** (theo tháng / theo quý, theo từng năm tính thuế) và **quyết định mức đóng BHTNLĐ-BNN** (mức áp dụng, ví dụ mức chuẩn hoặc mức thấp hơn, kèm số quyết định, cơ quan ban hành, hiệu lực từ/đến và tệp minh chứng) (thay đổi 2026-10-16, GAP-049). | M | MST, mã BHXH, cơ quan thuế, kỳ khai thuế và quyết định mức đóng BHTNLĐ-BNN được payroll sử dụng cho khai báo và đóng thuế TNCN/BHXH (IF-PAY-C-01). Bản thân giá trị mức đóng BHTNLĐ-BNN phải là giá trị được legal pack cho phép `[verify-legal]`. |
| BR-CORE-003 | MST phải được kiểm tra là 10 chữ số (trụ sở chính) hoặc 13 chữ số dạng `NNNNNNNNNN-NNN` (đơn vị phụ thuộc/chi nhánh) và là duy nhất trong tenant. | M | Định dạng `[verify-legal]`. |
| BR-CORE-004 | Các thuộc tính pháp nhân ảnh hưởng đến tính toán theo luật định (MST, mã BHXH, vùng, địa chỉ) phải **có hiệu lực theo ngày**; lịch sử phải được lưu giữ và truy vấn được "tại ngày". | M | Payroll cần giá trị có hiệu lực trong kỳ lương. |
| BR-CORE-005 | Hệ thống phải hỗ trợ chi nhánh / địa điểm làm việc gắn với đúng một pháp nhân, gồm: mã, tên, loại (trụ sở chính, chi nhánh, văn phòng đại diện, nhà máy, cửa hàng, công trường), địa chỉ (tỉnh/phường + đường), vùng lương tối thiểu (mặc định kế thừa từ bảng vùng của phường, có thể ghi đè kèm lý do), múi giờ, tọa độ địa lý và bán kính (m), danh sách Wi-Fi/IP được phép, mã số thuế phụ thuộc (13 chữ số) nếu chi nhánh khai thuế TNCN riêng, ngày hiệu lực. | M | Tọa độ, bán kính và danh sách Wi-Fi/IP chỉ dùng cho check-in trên mobile/web, thuộc Phase 2. Ở Phase 1 đây là các trường tùy chọn (thay đổi 2026-10-16, OD-9). |
| BR-CORE-006 | Hệ thống phải xác định **vùng lương tối thiểu** (I–IV) của từng pháp nhân/địa điểm tại một ngày bằng cách đọc bảng ánh xạ đơn vị hành chính → vùng của **Legal Parameter Service** thuộc saas-platform (`getParam(key, scope, asOfDate)`). Core không làm master bảng ánh xạ; tenant chỉ được ghi đè ở những nơi legal pack đánh dấu "pháp luật cho phép", kèm phê duyệt và audit trong Legal Parameter Service. (thay đổi 2026-10-16, R2-02 §5 #1) | M | Bảng ánh xạ và mức lương theo vùng là tham số của legal pack (saas-platform). `[verify-legal]` |
| BR-CORE-007 | Khi địa chỉ của pháp nhân/địa điểm hoặc bảng ánh xạ vùng của legal pack thay đổi (`saas.LegalParameterPackPublished`), hệ thống phải tính lại vùng hiệu lực và phát `core.LocationChanged` (subtype `LocationRegionChanged`) kèm ngày hiệu lực (thay đổi 2026-10-16, R2-02 §5 #1). | M | Kích hoạt kiểm tra lương tối thiểu ở payroll/people. |
| BR-CORE-008 | Phải ghi nhận được quan hệ giữa các pháp nhân (công ty mẹ/con, đơn vị cung cấp dịch vụ dùng chung) phục vụ báo cáo và phạm vi dữ liệu liên pháp nhân. | C | |

### 4.2 Cơ cấu tổ chức
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-010 | Hệ thống phải duy trì cây tổ chức phân cấp gồm các đơn vị tổ chức với **loại đơn vị** có thể cấu hình (ví dụ Khối, Phòng, Ban, Bộ phận, Tổ/Nhóm) và độ sâu tối thiểu 12 cấp. | M | |
| BR-CORE-011 | Mỗi đơn vị tổ chức phải lưu: mã (duy nhất trong tenant, không thay đổi sau khi đã sử dụng), tên VI/EN, loại, đơn vị cha, pháp nhân, địa điểm chính, vị trí quản lý (và/hoặc người dùng quản lý), trung tâm chi phí, trạng thái, ngày bắt đầu/kết thúc hiệu lực, thứ tự sắp xếp. | M | Một đơn vị thuộc một pháp nhân; cho phép đơn vị ảo cấp tập đoàn với `legal_entity = null`. |
| BR-CORE-012 | Mọi thuộc tính của đơn vị tổ chức và quan hệ cha-con phải **có hiệu lực theo ngày**; hệ thống phải trả lời được "cây tổ chức tại ngày D" và "lịch sử của đơn vị X". | M | |
| BR-CORE-013 | Thay đổi tổ chức phải được thực hiện thông qua **gói tái cơ cấu** (tạo, đổi tên, di chuyển, sáp nhập, chia tách, giải thể, đổi người quản lý, đổi trung tâm chi phí) với ngày hiệu lực, phân tích tác động, phê duyệt và kích hoạt nguyên tử theo lịch (P-02). | M | Đổi tên đơn giản có thể không cần gói khi cấu hình tenant cho phép. |
| BR-CORE-014 | Sáp nhập/chia tách/giải thể phải có **ánh xạ** các vị trí và nhân viên bị ảnh hưởng sang đơn vị đích; việc kích hoạt bị chặn khi còn nhân viên đang làm việc chưa được ánh xạ. | M | Ánh xạ do module people thực hiện khi nhận `ReorgActivated`; People trả về `people.ReorgMappingCompleted{package_id, moved, failed}` và Core đặt gói sang `ACTIVE` (0 lỗi) hoặc `ACTIVATED_WITH_ERRORS` và phát `core.ReorgPackageStatusChanged` (thay đổi 2026-10-16, GAP-053). |
| BR-CORE-015 | Tái cơ cấu hồi tố phải yêu cầu quyền riêng và phải thông báo cho payroll và T&A về các kỳ bị ảnh hưởng. Việc kiểm tra sử dụng **trạng thái khóa của pháp nhân** do payroll cung cấp, được suy ra từ các kỳ lương của mọi nhóm lương thuộc pháp nhân (IF-PAY-P-20) (thay đổi 2026-10-16, GAP-049). | M | |
| BR-CORE-016 | Hệ thống phải hiển thị sơ đồ tổ chức tương tác (đơn vị và/hoặc vị trí cùng người đảm nhiệm, ảnh, cờ vị trí trống), có thể xuất ra PDF/PNG. | S | Dữ liệu người đảm nhiệm lấy từ people. |
| BR-CORE-017 | Hệ thống phải hỗ trợ tuyến báo cáo ma trận / phụ (quản lý chức năng) bên cạnh phân cấp trực tuyến. | C | Cơ chế xác định người phê duyệt của workflow có thể nhắm tới quản lý chức năng. |

### 4.3 Kiến trúc công việc & trung tâm chi phí
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-020 | Hệ thống phải duy trì nhóm công việc và **chức danh** với mã, tên VI/EN, nhóm, mô tả/tệp JD đính kèm, khoảng ngạch bậc mặc định, mã nghề nghiệp, cờ "nghề, công việc nặng nhọc, độc hại, nguy hiểm" kèm phân loại. | M | Cờ độc hại chi phối tuổi nghỉ hưu, số ngày nghỉ phép và quy tắc BHXH ở các module khác. `[verify-legal]` |
| BR-CORE-021 | Hệ thống phải duy trì **danh mục ngạch bậc** (ngạch, bậc, cấp bậc): cấu trúc ngạch bậc, mã, tên và thứ tự ngạch bậc, có hiệu lực theo ngày. Khung lương tham chiếu (min/mid/max) đã bị bỏ — do payroll sở hữu (thang bảng lương và khung lương theo ngạch bậc, IF-PAY-P-02) (thay đổi 2026-10-16, R2-02 §5 #7). | M | People và recruitment kiểm tra mức lương theo khung lương của payroll, không theo Core. |
| BR-CORE-022 | Hệ thống phải duy trì **vị trí** như các suất định biên: mã, chức danh, đơn vị tổ chức, địa điểm, khoảng ngạch bậc, vị trí báo cáo trực tiếp, ngân sách FTE, trung tâm chi phí, trạng thái (đề xuất/hoạt động/đóng băng/đóng), ngày hiệu lực, cờ vị trí trọng yếu. | M | |
| BR-CORE-023 | Việc sử dụng vị trí phải cấu hình được theo tenant: `position_control = strict` (mọi phân công cần một vị trí trống), `soft` (cảnh báo) hoặc `off` (chỉ dùng chức danh). | M | Tránh ép doanh nghiệp vừa và nhỏ phải kiểm soát vị trí. |
| BR-CORE-024 | Hệ thống phải cung cấp mức lấp đầy vị trí (ngân sách, đã lấp, còn trống, đang tuyển) kết hợp ngân sách của Core với people (đã lấp) và recruitment (yêu cầu tuyển dụng đang xử lý). | S | |
| BR-CORE-025 | Hệ thống phải duy trì **trung tâm chi phí** (mã, tên, pháp nhân, mã phân đoạn kế toán, người phụ trách, ngày hiệu lực) và trung tâm chi phí mặc định cho từng đơn vị tổ chức và từng vị trí; phân bổ trung tâm chi phí theo tỷ lệ phần trăm phải được hỗ trợ ở cấp phân công công việc (do people sở hữu) dựa trên danh sách của Core. | M | Hạch toán chi phí lương / xuất GL. |
| BR-CORE-026 | Hệ thống phải duy trì danh mục mã **loại nhân viên** (ví dụ chính thức, thử việc, thời vụ, thực tập, người nước ngoài, cộng tác viên/CTV) dùng được làm điều kiện trong workflow, phạm vi dữ liệu và quy tắc tính lương. | M | |

### 4.4 Người dùng, vai trò, quyền, phạm vi dữ liệu, SSO
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-030 | Hệ thống phải quản lý tài khoản người dùng tenant (login, tên hiển thị, email, di động, mã nhân viên liên kết hoặc không có, locale, trạng thái ACTIVE / FORMER_EMPLOYEE / DEACTIVATED, ngày hết hạn, liên kết tới identity của saas-platform). Một tài khoản người dùng thuộc đúng một tenant. Credential, yếu tố MFA và SSO subject do saas-platform nắm giữ (thay đổi 2026-10-16, R2-02 §5 #9, #10). | M | Identity liên tenant do saas-platform xử lý. Thay đổi trạng thái phát `core.UserStatusChanged`. |
| BR-CORE-031 | Quyền phải được định nghĩa theo **chức năng** (menu/hành động: xem, tạo, sửa, xóa, phê duyệt, xuất, import, xem dữ liệu nhạy cảm) và nhóm thành **vai trò**; tenant có thể nhân bản vai trò hệ thống và tạo vai trò tùy chỉnh. | M | Các module đăng ký danh mục quyền của mình khi deploy. |
| BR-CORE-032 | Một phép gán vai trò phải kèm **phạm vi dữ liệu**: toàn tenant; (các) pháp nhân; (các) địa điểm; (các) đơn vị tổ chức có/không bao gồm nhánh con; nhánh của mình với tư cách quản lý (suy ra); chỉ bản thân; quy tắc tùy chỉnh (loại nhân viên, khoảng ngạch bậc). Quyền hiệu lực = hợp của các phép gán. | M | Ví dụ: HR Officer → Pháp nhân A; Quản lý → nhánh của mình; Payroll → pháp nhân A+B. |
| BR-CORE-033 | Phạm vi `LINE_MANAGER` và `SELF` phải được suy ra tự động từ dữ liệu tổ chức/vị trí và tự động thay đổi khi tái cơ cấu hoặc điều chuyển mà không cần quản trị viên thao tác. | M | |
| BR-CORE-034 | **Độ nhạy cảm cấp trường**: lương, tài khoản ngân hàng, số giấy tờ tùy thân, thông tin sức khỏe, dữ liệu gia đình/người phụ thuộc và các loại dữ liệu cá nhân phải có thể che giấu theo vai trò (quyền `view-sensitive`). | M | Luật BVDLCN: dữ liệu cá nhân nhạy cảm. `[verify-legal]` Dữ liệu lương (mức lương thỏa thuận) chỉ hiển thị khi có quyền **`compensation`** do people đăng ký cho C&B và HR được ủy quyền; mọi lượt xem đều được audit (thay đổi 2026-10-16, R2-02 §5 #24). |
| BR-CORE-035 | Lọc theo phạm vi dữ liệu phải được thực thi phía server trên mọi truy vấn, báo cáo, xuất dữ liệu, API và việc xác định người nhận thông báo, thông qua một dịch vụ phân quyền duy nhất của Core. | M | |
| BR-CORE-036 | Phép gán vai trò phải hỗ trợ ngày bắt đầu/kết thúc và workflow phê duyệt cho các vai trò đặc quyền (`CORE_ROLE_GRANT`). | S | |
| BR-CORE-037 | Quy tắc phân tách nhiệm vụ phải cấu hình được (ví dụ cùng một người dùng không thể vừa lập vừa duyệt một kỳ lương); vi phạm sẽ chặn phép gán hoặc hành động. | S | |
| BR-CORE-038 | Đã bỏ — do saas-platform sở hữu (R2-02). Đăng nhập cục bộ, chính sách mật khẩu, MFA và quản lý session là BR-SAAS-038/039/043; Core sử dụng identity đã xác thực (C-22) (thay đổi 2026-10-16, R2-02 §5 #10). | M | Giữ ID; không tái sử dụng. |
| BR-CORE-039 | Đã bỏ — do saas-platform sở hữu (R2-02). SSO (OIDC/SAML), liên kết JIT và SCIM là BR-SAAS-040/041/042; việc gán vai trò vẫn nằm ở Core (thay đổi 2026-10-16, R2-02 §5 #10). | M (OIDC), S (SAML), C (SCIM) | Giữ ID; không tái sử dụng. |
| BR-CORE-040 | Hệ thống phải hỗ trợ **ủy quyền truy cập** ("act as" trong một khoảng thời gian, giới hạn chức năng), khác với ủy quyền phê duyệt, được audit đầy đủ. | C | |
| BR-CORE-041 | Nhân viên hỗ trợ của nền tảng chỉ được truy cập dữ liệu tenant thông qua các session break-glass có giới hạn thời gian, được tenant phê duyệt, và được ghi vào audit log của tenant. | M | Cơ chế do saas-platform sở hữu; Core ghi log và thực thi. |
| BR-CORE-042 | Đã bỏ — do saas-platform sở hữu (R2-02). API client và credential (scope, IP allowlist, xoay vòng, hết hạn) là BR-SAAS-056. Core chỉ giữ ánh xạ từ API scope sang quyền và phạm vi dữ liệu, được dịch vụ phân quyền đánh giá (BR-CORE-035) (thay đổi 2026-10-16, R2-02 §5 #10). | S | Giữ ID; không tái sử dụng. |

### 4.5 Engine workflow & phê duyệt
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-050 | Core phải cung cấp một workflow engine chung; mỗi module đăng ký **process type** với: schema tóm tắt yêu cầu, các trường điều kiện, các hành động được phép, endpoint/sự kiện callback, có cho phép tự động phê duyệt khi quá hạn hay không. | M | |
| BR-CORE-051 | Workflow definition phải cấu hình được mà không cần code (trình thiết kế dạng biểu mẫu): điều kiện áp dụng (pháp nhân, đơn vị tổ chức, địa điểm, loại nhân viên, ngạch bậc, bất kỳ trường yêu cầu nào đã đăng ký), các bước có thứ tự, quản lý phiên bản, ngày kích hoạt. | M | |
| BR-CORE-052 | Cơ chế xác định người phê duyệt phải bao gồm: quản lý trực tiếp; quản lý cấp trên N cấp; quản lý của một đơn vị chỉ định; người giữ một vị trí; người dùng có vai trò R trong phạm vi của người yêu cầu; người dùng chỉ định; người phê duyệt do người yêu cầu chọn từ danh sách đã lọc; trường từ yêu cầu (ví dụ quản lý dự án); quản lý chức năng. | M | |
| BR-CORE-053 | Loại bước: một người phê duyệt; any-of (phản hồi đầu tiên được chấp nhận); all-of (đồng thuận); nhánh song song có điểm hợp; bỏ qua có điều kiện; bước chỉ thông báo (FYI). | M | |
| BR-CORE-054 | Hành động: gửi, phê duyệt, từ chối (bắt buộc nêu lý do), trả lại người yêu cầu / bước trước, yêu cầu bổ sung thông tin, rút lại (người yêu cầu, trước quyết định cuối cùng), chuyển tiếp (nếu được phép), bình luận, đính kèm tệp, phê duyệt hàng loạt. | M | |
| BR-CORE-055 | **Ủy quyền phê duyệt**: người dùng có thể ủy quyền các công việc đang chờ và công việc mới trong một khoảng ngày, tùy chọn theo process type, cho người dùng khác; người được ủy quyền thực hiện "thay mặt"; ủy quyền không được nối chuỗi quá một cấp; người được ủy quyền phải thỏa mãn quy tắc phạm vi dữ liệu đối với yêu cầu. | M | Tự động gợi ý ủy quyền khi người phê duyệt có đơn nghỉ phép đã được duyệt (sự kiện từ T&A). |
| BR-CORE-056 | **Thay thế / giao lại**: HR Admin có thể giao lại bất kỳ công việc đang mở nào kèm lý do bắt buộc; dự phòng tự động khi người phê duyệt đã xác định không hoạt động, không tồn tại hoặc trùng với người yêu cầu (cấu hình được: bỏ qua, lên một cấp, HR của pháp nhân). | M | |
| BR-CORE-057 | **SLA & leo thang**: mỗi bước có SLA tính theo giờ/ngày làm việc dựa trên lịch làm việc của người yêu cầu (từ T&A); nhắc nhở tại các ngưỡng cấu hình được; khi vi phạm: leo thang, giao lại, tự động phê duyệt (chỉ khi được phép) hoặc tự động từ chối; báo cáo SLA. | M | |
| BR-CORE-058 | Engine phải lưu snapshot chuỗi người phê duyệt khi gửi và xác định lại các bước đang mở khi tổ chức thay đổi theo cấu hình tenant. | M | |
| BR-CORE-059 | Phê duyệt phải thực hiện được từ web, mobile app và liên kết thao tác qua email/Zalo (có ký, dùng một lần, hết hạn ≤72h, xác thực tăng cường cho các process type nhạy cảm). | S | |
| BR-CORE-060 | Engine phải phát các sự kiện vòng đời (`WorkflowSubmitted`, `StepAssigned`, `StepCompleted`, `WorkflowCompleted`, `WorkflowWithdrawn`, `SlaBreached`) và cung cấp hộp thư "Việc của tôi / Yêu cầu của tôi" thống nhất cho mọi module. | M | |
| BR-CORE-061 | Toàn bộ lịch sử phê duyệt (ai, thay mặt ai, khi nào, hành động, bình luận, IP/thiết bị) phải bất biến và in được thành vết phê duyệt trên văn bản. | M | |

### 4.6 Thông báo
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-070 | Core phải cung cấp **trung tâm thông báo** cho các kênh: hộp thư trong ứng dụng (Core), và email, push trên mobile (FCM/APNs), **Zalo OA / ZNS**, SMS được gửi qua **channel delivery adapter** của saas-platform (SMTP/DKIM, push, ZNS, SMS, tài khoản nhà cung cấp), các adapter này trả về trạng thái gửi. Các module chỉ gọi Core (thay đổi 2026-10-16, R2-02 §5 #14). | M (trong ứng dụng, email), S (push, Zalo), C (SMS) | Mẫu ZNS cần Zalo phê duyệt — cần thời gian chuẩn bị. |
| BR-CORE-071 | Mẫu thông báo phải được quản lý theo mã sự kiện, kênh và ngôn ngữ (VI/EN) với placeholder, cho phép tenant ghi đè mẫu hệ thống và xem trước. | M | |
| BR-CORE-072 | Người dùng phải quản lý được tùy chọn kênh theo từng nhóm, trừ các nhóm bắt buộc (bảo mật, thông báo có phiếu lương, công việc workflow) chỉ được đổi kênh, không được tắt. | S | |
| BR-CORE-073 | Trung tâm thông báo phải hỗ trợ gửi theo lịch, khung giờ yên lặng, bản tổng hợp hằng ngày, loại bỏ trùng lặp, dự phòng kênh và hiển thị trạng thái gửi (đã gửi/đã nhận/đã đọc/lỗi). Việc gửi và retry do delivery adapter của saas-platform thực hiện (thay đổi 2026-10-16, R2-02 §5 #14). | S | |
| BR-CORE-074 | Nội dung thông báo chứa dữ liệu nhạy cảm (ví dụ lương) không được gửi **không mã hóa** qua email/Zalo/SMS; mặc định là deep link bảo mật. **Ngoại lệ cho phiếu lương (R2-17):** payroll có thể đính kèm phiếu lương vào e-mail dưới dạng **PDF có mật khẩu** (BR-PAY-102). Quy tắc chỉ dùng deep link **được nới lỏng chỉ cho phiếu lương**. Mật khẩu do nhân viên tự đặt trong ESS; không có mật khẩu mặc định, ban đầu hay suy ra (ngày sinh + các chữ số giấy tờ tùy thân bị từ chối vì quá yếu theo Luật BVDLCN); mật khẩu không bao giờ được gửi qua e-mail, SMS hay Zalo. Cho đến khi nhân viên đặt mật khẩu, e-mail chỉ chứa thông báo kèm deep link, không có tệp đính kèm. Việc gửi và gửi lại đều được audit. Tin nhắn Zalo/SMS không bao giờ chứa số tiền (thay đổi 2026-10-16, R2-17; GAP-057). | M | Rà soát theo Luật BVDLCN `[verify-legal]` |
| BR-CORE-075 | Tenant phải gửi được thông báo chung (bản tin nội bộ) tới các nhóm đối tượng xác định theo phạm vi dữ liệu (pháp nhân, đơn vị, địa điểm, loại nhân viên) kèm xác nhận đã đọc. | C | |

### 4.7 Cấu hình & master data
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-080 | Core phải sở hữu **kho master data toàn cục và admin API của kho** (tenant chỉ đọc); platform operator quản lý dữ liệu qua giao diện back-office và quy trình phát hành của saas-platform (publish → nạp có phiên bản vào `core` → `core.MasterDataReleased`) (thay đổi 2026-10-16, R2-02 §5 #28). Nội dung: đơn vị hành chính (34 tỉnh/thành phố trực thuộc trung ương và các phường/xã/đặc khu theo mô hình chính quyền địa phương hai cấp, hiệu lực từ 2025-07-01), kèm lịch sử/ánh xạ từ 63 tỉnh thành và quận huyện trước sáp nhập. | M | Nghị quyết 202/2025/QH15 `[verify-legal]` |
| BR-CORE-081 | Mô hình đơn vị hành chính phải giữ mã cũ và **bảng ánh xạ cũ→mới** để các địa chỉ theo mô hình cũ (import số dư đầu kỳ từ hồ sơ của khách hàng, CMND cũ, nơi sinh) vẫn hợp lệ (thay đổi 2026-10-16, D-09), và địa chỉ có thể hiển thị theo dạng cũ hoặc mới. | M | Nơi sinh / quê quán dùng đơn vị hành chính lịch sử. |
| BR-CORE-082 | Danh mục toàn cục phải bao gồm: quốc gia/quốc tịch (ISO 3166), 54 dân tộc Việt Nam, tôn giáo, loại giấy tờ tùy thân (CCCD, CMND cũ, hộ chiếu), ngân hàng và chi nhánh (kèm mã SBV/NAPAS, SWIFT), tiền tệ (ISO 4217), ngôn ngữ, trình độ học vấn, tình trạng hôn nhân, quan hệ, giới tính. | M | Danh mục dân tộc theo bảng mã của Tổng cục Thống kê `[verify-legal]` |
| BR-CORE-083 | Tenant phải định nghĩa được **danh mục mã tùy chỉnh** (ví dụ lý do nghỉ việc, loại hình đào tạo) và mở rộng một số danh mục toàn cục bằng giá trị riêng của tenant, gồm mã, nhãn VI/EN, thứ tự sắp xếp, cờ hoạt động và ngày hiệu lực. | M | |
| BR-CORE-084 | Cấu hình tenant phải phân cấp (tenant → pháp nhân → địa điểm) với giá trị có định kiểu, kiểm tra hợp lệ, hiệu lực theo ngày khi được đánh dấu, và audit thay đổi. | M | ví dụ định dạng mã nhân viên, `auto_create_user`, `position_control`. |
| BR-CORE-085 | Core phải cung cấp **dịch vụ đánh số**: chuỗi số cấu hình được theo tenant/pháp nhân/loại văn bản với mẫu (tiền tố, năm, mã pháp nhân, bộ đếm đệm số 0), tùy chọn không ngắt quãng và quy tắc đặt lại. | M | Mã nhân viên, số hợp đồng, số quyết định. |
| BR-CORE-086 | Core phải cung cấp **framework trường tùy chỉnh** duy nhất (trường do người dùng định nghĩa) trên các thực thể đã đăng ký của mọi module — tối thiểu nhân viên, phân công công việc và đơn vị tổ chức với các kiểu cơ bản — gồm kiểu, kiểm tra hợp lệ, nhãn VI/EN, cờ nhạy cảm, quyền cấp trường và phạm vi dữ liệu, đưa vào import/xuất và báo cáo. saas-platform không cung cấp framework thứ hai (bỏ PR-05); việc công thức lương tham chiếu tới trường tùy chỉnh kiểu số là bước sau (thay đổi 2026-10-16, R2-02 §5 #11). | M | §5 #11 xếp các kiểu cơ bản là M: các thuộc tính của tenant trong mẫu master nhân viên số dư đầu kỳ phải có nơi lưu (độ ưu tiên đổi S → M 2026-10-16, R2-02, PM đã phê duyệt). Giai đoạn vẫn là P2 theo mvp-scope.md §9 (trường tùy chỉnh được hoãn). |
| BR-CORE-087 | Cập nhật master data toàn cục (ví dụ ngân hàng mới, đơn vị hành chính mới) phải được phân phối tới mọi tenant kèm ngày hiệu lực và thông báo thay đổi (`core.MasterDataReleased`, phát hành từ back-office của saas-platform); các tham chiếu riêng của tenant tới giá trị đã ngừng sử dụng phải được báo cáo (thay đổi 2026-10-16, R2-02 §5 #28). | M | |
| BR-CORE-088 | Core phải duy trì **bảng tỷ giá** như master data: cặp tiền tệ, loại tỷ giá (ví dụ mua vào / bán ra của ngân hàng / tỷ giá trung tâm), ngày tỷ giá, nguồn, giá trị; nhập thủ công và import Excel; cho phép các dòng ghi đè của tenant (R2-11). Nguồn cấp tự động từ SBV/ngân hàng qua integration hub của saas-platform thuộc giai đoạn sau. Payroll đọc tỷ giá có hiệu lực tại ngày tỷ giá (IF-PAY-C-33) (mới 2026-10-16, R2-02 §5 #19). | S | Tính lương đa tiền tệ thuộc Phase 2 (R2-09); bảng chỉ cần khi tenant trả lương bằng ngoại tệ. |

### 4.8 Audit log
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-090 | Core phải ghi một bản ghi audit cho mọi thao tác tạo/sửa/xóa dữ liệu nghiệp vụ của mọi module: tenant, loại thực thể, id bản ghi, hành động, các trường thay đổi với giá trị trước/sau (giá trị nhạy cảm được che hoặc mã hóa), người dùng, người dùng được thay mặt, thời điểm (UTC + giờ địa phương), IP, user agent, kênh (UI/API/import/hệ thống), correlation id. | M | |
| BR-CORE-091 | Các sự kiện bảo mật phải được ghi log: đăng nhập thành công/thất bại, MFA, đổi mật khẩu, thay đổi vai trò/quyền, thay đổi cấu hình SSO, session break-glass, xuất và tải xuống dữ liệu cá nhân, xem các trường nhạy cảm. Sự kiện identity và back-office của operator do saas-platform tạo ra và **được ghi vào kho audit của Core**; chỉ các sự kiện operator cấp nền tảng không gắn với tenant mới vào `saas.audit_event` (thay đổi 2026-10-16, R2-02 §5 #13). | M | Core là dịch vụ và kho audit duy nhất của tenant. |
| BR-CORE-092 | Audit log phải chỉ cho phép ghi thêm và có khả năng phát hiện giả mạo (hash chain hoặc WORM storage); không người dùng nào, kể cả quản trị viên tenant, được sửa hoặc xóa bản ghi. | M | |
| BR-CORE-093 | Tìm kiếm audit phải hỗ trợ bộ lọc (khoảng ngày, người dùng, thực thể, bản ghi, hành động, module) và xuất dữ liệu; truy cập yêu cầu `AUDIT_VIEW` và bản thân việc truy cập cũng được audit. | M | |
| BR-CORE-094 | Thời hạn lưu trữ audit phải cấu hình được theo từng nhóm với mức sàn pháp lý mà tenant không thể hạ thấp: sự kiện bảo mật ≥ 2 năm; sự kiện liên quan đến lương và kế toán ≥ 10 năm `[verify-legal]`; việc xóa sau thời hạn lưu trữ phải được ghi log (thay đổi 2026-10-16, R2-02 §5 #13). | M | Xem §5. Thống nhất với BR-SAAS-085, BR-PAY-123, NFR-ATT-007. |

### 4.9 Tài liệu & tệp
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-100 | Core phải cung cấp **API dịch vụ tài liệu** mà mọi module gọi (thay đổi 2026-10-16, R2-02 §5 #15/#15b): tải lên (giới hạn dung lượng/loại tệp theo tenant), quét mã độc trước khi cho phép sử dụng, lưu trong **object storage của saas-platform** được cô lập theo tenant (lưu trữ tại VN, prefix theo tenant, mã hóa/KMS, quét AV, signed URL — PR-16), metadata (module sở hữu, thực thể, id bản ghi, loại tài liệu, mức độ bảo mật, lớp lưu trữ), quản lý phiên bản. | M | |
| BR-CORE-101 | Quyền truy cập tệp phải tuân theo quyền và phạm vi dữ liệu của bản ghi sở hữu; tải xuống qua signed URL ngắn hạn; truy cập tệp mật được audit. | M | |
| BR-CORE-102 | Core phải cung cấp **mẫu văn bản** (DOCX/HTML → PDF) với các trường trộn do module cung cấp (ví dụ hợp đồng lao động, quyết định, giấy xác nhận), đánh số qua BR-CORE-085 và tiêu đề thư/con dấu của pháp nhân. | M | Việc sinh hợp đồng được people sử dụng. |
| BR-CORE-103 | Core phải sở hữu **điều phối ký điện tử**: gửi văn bản đi ký điện tử, theo dõi trạng thái ký và lưu tệp đã ký cùng bằng chứng chứng thư số. Các connector nhà cung cấp chữ ký điện tử / hợp đồng điện tử (CA được cấp phép tại VN) là connector integration-hub của saas-platform (BR-SAAS-063) (thay đổi 2026-10-16, R2-02 §5 #15b). | S | Luật Giao dịch điện tử 2023 `[verify-legal]`. Ký điện tử thuộc Phase 2 (R2-01). |
| BR-CORE-104 | Lớp lưu trữ phải điều khiển việc lưu trữ/xóa tự động, có ghi đè khi bị giữ theo yêu cầu pháp lý (legal hold). Lớp lưu trữ của tài liệu là một **khóa trỏ vào chính sách lưu trữ của saas-platform** (bộ lập lịch lưu trữ duy nhất với mức sàn pháp lý, BR-SAAS-082) (thay đổi 2026-10-16, R2-02 §5 #27). | S | |

### 4.10 Import / export
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-CORE-110 | Core phải cung cấp **import engine duy nhất** trong đó các module đăng ký loại import (định nghĩa mẫu, ánh xạ trường, validator, commit handler, undo handler, cờ cho phép commit một phần). Commit mặc định là **all-or-nothing**; chỉ commit một phần nếu loại import cho phép; rollback trong vòng 24 h nếu chưa có giao dịch phụ thuộc. Các yêu cầu import của saas-platform (BR-SAAS-069/070) là yêu cầu đối với engine này (thay đổi 2026-10-16, R2-02 §5 #12). | M | |
| BR-CORE-111 | Import phải chạy dưới dạng job bất đồng bộ với staging, thông báo kiểm tra hợp lệ theo từng dòng (VI/EN), dry-run, tệp lỗi có thể tải về, commit, theo dõi tiến độ và lịch sử job; ≥50,000 dòng mỗi tệp. | M | |
| BR-CORE-112 | Mẫu phải được sinh dưới dạng Excel với dropdown từ danh mục mã, gợi ý kiểu dữ liệu và một sheet hướng dẫn; tra cứu phải chấp nhận mã hoặc tên. | M | |
| BR-CORE-113 | Đã bỏ — do saas-platform sở hữu (R2-02). Xuất danh sách và báo cáo (Excel/CSV/PDF, bất đồng bộ, hết hạn sau 7 ngày, có audit) thuộc framework báo cáo và xuất dữ liệu của saas-platform (BR-SAAS-046/053); Core cung cấp predicate phạm vi dữ liệu và chính sách che giấu trường (P-10) (thay đổi 2026-10-16, R2-02 §5 #18). | M | Giữ ID; không tái sử dụng. |
| BR-CORE-114 | Core phải cung cấp **chế độ import số dư đầu kỳ** của import framework cho việc go-live của mọi tenant: nạp hàng loạt, bỏ qua workflow, giữ id tham chiếu bên ngoài để truy vết, hiệu lực theo ngày tại thời điểm chuyển đổi go-live, và báo cáo đối chiếu. Chế độ này thay thế chế độ chuyển đổi dữ liệu Oracle cũ (thay đổi 2026-10-16, D-09). | M | Được dùng bởi các mẫu BR-SAAS-069/071. Không chuyển đổi cơ sở dữ liệu cũ. |
| BR-CORE-115 | Xuất dữ liệu theo lịch / tích hợp đầu ra (SFTP, webhook) phải cấu hình được theo tenant. | C | |

---

## 5. Quy định tuân thủ Việt Nam

| # | Quy định | Căn cứ pháp lý | Tác động tới Core |
|---|---|---|---|
| VN-01 | Từ 2025-07-01 Việt Nam có 34 đơn vị hành chính cấp tỉnh và chính quyền địa phương hai cấp (tỉnh → xã/phường/đặc khu); bỏ cấp huyện. | Nghị quyết 202/2025/QH15; Luật Tổ chức chính quyền địa phương 72/2025/QH15 `[verify-legal]` | Master data đơn vị hành chính (BR-CORE-080/081); địa chỉ chỉ mô hình tỉnh + phường cho dữ liệu mới; giữ dữ liệu ba cấp lịch sử. |
| VN-02 | Lương tối thiểu vùng áp dụng theo địa bàn nơi người sử dụng lao động hoạt động; 4 vùng được ánh xạ theo đơn vị hành chính; khi người sử dụng lao động có đơn vị ở nhiều vùng, mỗi đơn vị áp dụng vùng của mình. | Bộ luật Lao động 2019 Điều 91; Nghị định 74/2024/NĐ-CP, được cập nhật theo đơn vị hành chính mới bởi một nghị định năm 2025 (ví dụ Nghị định 293/2025/NĐ-CP hiệu lực từ 2026-01-01) `[verify-legal]` | Gán vùng ở cấp pháp nhân và địa điểm, có hiệu lực theo ngày (BR-CORE-005/006/007). |
| VN-03 | Mã số thuế doanh nghiệp = 10 chữ số; đơn vị phụ thuộc 13 chữ số; từ 2025-07-01 cá nhân dùng số định danh cá nhân 12 chữ số làm mã số thuế. | Luật Quản lý thuế 38/2019/QH14; Thông tư 86/2024/TT-BTC `[verify-legal]` | Kiểm tra MST (BR-CORE-003); people/payroll dùng số định danh cá nhân làm mã số thuế TNCN. |
| VN-04 | Người sử dụng lao động đăng ký tham gia BHXH và được cấp mã đơn vị BHXH; khai báo theo từng đơn vị. | Luật Bảo hiểm xã hội 41/2024/QH15 (hiệu lực từ 2025-07-01) `[verify-legal]` | Mã đơn vị BHXH theo từng pháp nhân (và theo chi nhánh nếu chi nhánh khai báo riêng). |
| VN-05 | Xử lý dữ liệu cá nhân yêu cầu sự đồng ý, giới hạn mục đích, bảo vệ dữ liệu nhạy cảm (sức khỏe, tài chính, sinh trắc học, dân tộc, tôn giáo…), quyền của chủ thể dữ liệu và đánh giá tác động. | Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15 (hiệu lực từ 2026-01-01); Nghị định 13/2023/NĐ-CP và văn bản hướng dẫn kế nhiệm `[verify-legal]` | Độ nhạy cảm & che giấu trường (BR-CORE-034), audit truy cập (BR-CORE-091), hồ sơ đồng ý (cung cấp cho people), lưu trữ (BR-CORE-094/104). |
| VN-06 | Một số dữ liệu của người dùng Việt Nam phải được lưu trữ tại Việt Nam theo các điều kiện của quy định về an ninh mạng. | Luật An ninh mạng 2018; Nghị định 53/2022/NĐ-CP `[verify-legal]` | Vùng hosting là quyết định của saas-platform; storage của Core phải cấu hình được theo vùng. |
| VN-07 | Văn bản điện tử, chữ ký điện tử và hợp đồng điện tử (bao gồm hợp đồng lao động điện tử) có giá trị pháp lý khi đáp ứng điều kiện; nền tảng hợp đồng lao động điện tử có thể chịu quy định riêng. | Luật Giao dịch điện tử 20/2023/QH15; Bộ luật Lao động Điều 14; các nghị định liên quan `[verify-legal]` | Dịch vụ tài liệu + adapter ký điện tử (BR-CORE-102/103). |
| VN-08 | Chứng từ kế toán (bao gồm bảng lương là chứng từ kế toán) được lưu trữ tối thiểu 10 năm; hồ sơ lao động lưu trữ theo quy định về lao động. | Luật Kế toán 88/2015/QH13; Nghị định 174/2016/NĐ-CP `[verify-legal]` | Lớp lưu trữ và thời hạn lưu trữ audit tối thiểu. |
| VN-09 | Danh mục nghề, công việc nặng nhọc, độc hại, nguy hiểm được ban hành bằng thông tư của Bộ LĐTBXH (nay là Bộ Nội vụ); ảnh hưởng tuổi nghỉ hưu, nghỉ phép, phụ cấp. | Thông tư 11/2020/TT-BLĐTBXH (và các văn bản sửa đổi) `[verify-legal]` | Cờ & phân loại chức danh (BR-CORE-020). |
| VN-10 | Văn bản chính thức dùng tiếng Việt; cho phép song ngữ. Ngày theo định dạng DD/MM/YYYY; tiền tệ VND không có phần thập phân. | Nghị định 30/2020/NĐ-CP (văn bản hành chính) `[verify-legal]` | Mặc định bản địa hóa, mẫu văn bản. |
| VN-11 | Phân loại dân tộc theo danh mục chính thức gồm 54 dân tộc. | Quyết định 121-TCTK/PPCĐ (Tổng cục Thống kê) `[verify-legal]` | Danh mục master data (BR-CORE-082). |

---

## 6. Thực thể nghiệp vụ chính

| Thực thể | Mục đích | Thuộc tính chính |
|---|---|---|
| `company_group` | Hồ sơ công ty cấp tenant | tenant_id, name, logo, default_locale, timezone, currency |
| `legal_entity` (+ `_version`) | Người sử dụng lao động / người nộp thuế theo pháp luật (master duy nhất; saas-platform chỉ giữ bên thanh toán — R2-02 §5 #23) | code, legal_name_vi/en, tax_code, si_unit_code, reg_no, address(province_id, ward_id, street), legal_rep, min_wage_region, fiscal_year_start, tax_authority, pit_declaration_frequency, oa_od_rate + oa_od_decision_ref/valid_from/to (thay đổi 2026-10-16, GAP-049), status, eff_from/eff_to |
| `legal_entity_bank_account` | Tài khoản ngân hàng chi trả | legal_entity_id, bank_id, branch, account_no, account_name, purpose (lương, BHXH, thuế), cờ mặc định |
| `location` (+ `_version`) | Chi nhánh / địa điểm làm việc | code, name, type, legal_entity_id, address, min_wage_region (suy ra/ghi đè), timezone, lat/long, radius_m, danh sách wifi/ip, dependent_tax_code, ngày hiệu lực |
| `admin_unit` | Master tỉnh/phường toàn cục | code, name, level, parent_id, valid_from/valid_to, replaced_by |
| `admin_unit_mapping` | Ánh xạ đơn vị hành chính cũ→mới | old_unit_id, new_unit_id, effective_date, mapping_type (merge/split/rename) |
| `min_wage_region_map` | Cache chỉ đọc của bảng ánh xạ đơn vị hành chính → vùng của legal pack (master nằm trong Legal Parameter Service của saas-platform) (thay đổi 2026-10-16, R2-02 §5 #1) | admin_unit_id, region (I–IV), eff_from/eff_to, phiên bản pack |
| `org_unit_type` | Danh mục loại đơn vị | code, name, gợi ý cấp |
| `org_unit` (+ `_version`) | Nút cây tổ chức | code, name, type, parent_id, legal_entity_id, location_id, manager_position_id, cost_centre_id, status, ngày hiệu lực |
| `reorg_package` / `reorg_change` / `reorg_mapping` | Tái cơ cấu có hiệu lực theo ngày | effective_date, status, workflow_instance_id; loại thay đổi, đơn vị nguồn/đích; ánh xạ vị trí/nhân viên |
| `job_family`, `job_title` | Kiến trúc công việc | code, name, family, hazardous_category, khoảng ngạch bậc mặc định, tài liệu JD |
| `grade_structure`, `grade` | Danh mục ngạch bậc (cấp / bậc) | code, name, order, structure, ngày hiệu lực — không có khung lương (thay đổi 2026-10-16, R2-02 §5 #7) |
| `position` (+ `_version`) | Suất định biên | code, job_title_id, org_unit_id, location_id, khoảng ngạch bậc, reports_to_position_id, fte_budget, cost_centre_id, status, cờ trọng yếu |
| `cost_centre` | Đối tượng hạch toán chi phí | code, name, legal_entity_id, gl_segment, owner, ngày hiệu lực |
| `employee_category` | Danh mục mã loại nhân viên | code, name, flags (is_probation, is_expat…) |
| `user_account` | Tài khoản người dùng tenant (credential và MFA nằm ở saas-platform) | login, email, mobile, employee_id (nullable), locale, status (ACTIVE/FORMER_EMPLOYEE/DEACTIVATED), expiry, identity_ref (saas-platform), access_revocation_at (thay đổi 2026-10-16, R2-02 §5 #9, #10) |
| `role`, `permission`, `role_permission` | RBAC | mã vai trò, hệ thống/tùy chỉnh; mã chức năng, hành động |
| `role_assignment` | Người dùng ↔ vai trò kèm phạm vi | user_id, role_id, scope_type, scope_values, ngày hiệu lực, granted_by |
| `sod_rule` | Phân tách nhiệm vụ | các quyền xung đột |
| `identity_provider` | Đã bỏ — do saas-platform sở hữu (R2-02): "Identity Provider Config" (thay đổi 2026-10-16, R2-02 §5 #10) | — |
| `api_client` → `api_scope_mapping` | Ánh xạ API scope → quyền/phạm vi dữ liệu; API client và secret do saas-platform sở hữu (thay đổi 2026-10-16, R2-02 §5 #10) | mã api scope, quyền, quy tắc phạm vi |
| `process_type` | Loại workflow do module đăng ký | code, module, summary schema, callback, flags |
| `workflow_definition` / `workflow_step_def` | Luồng đã cấu hình | điều kiện, phiên bản, các bước (cơ chế xác định, loại, SLA, leo thang) |
| `workflow_instance` / `workflow_task` / `workflow_action` | Runtime | tham chiếu yêu cầu, snapshot, trạng thái; người được giao, on_behalf_of, due_at; hành động, bình luận, thời điểm |
| `approval_delegation` | Ủy quyền | người ủy quyền, người được ủy quyền, process type, từ/đến |
| `notification_template` / `notification` / `notification_delivery` | Nhắn tin | mã sự kiện, kênh, locale, nội dung; người nhận; trạng thái kênh |
| `user_notification_pref` | Tùy chọn | nhóm, kênh, khung giờ yên lặng |
| `code_list` / `code_value` | Danh mục mã chung | mã danh mục, mã giá trị, nhãn, phạm vi (toàn cục/tenant), ngày hiệu lực |
| `country`, `ethnicity`, `religion`, `bank`, `bank_branch`, `currency`, `id_doc_type` | Master data toàn cục | Mã ISO / mã chính thức |
| `exchange_rate` | Tỷ giá (mới 2026-10-16, R2-02 §5 #19) | cặp tiền tệ, loại tỷ giá, ngày tỷ giá, giá trị, nguồn, phạm vi (toàn cục / tenant ghi đè) |
| `setting_definition` / `setting_value` | Cấu hình tenant | key, type, level, value, ngày hiệu lực |
| `sequence` | Đánh số | mẫu, phạm vi, giá trị hiện tại, quy tắc đặt lại |
| `custom_field_def` / `custom_field_value` | UDF | thực thể đích, kiểu, kiểm tra hợp lệ, độ nhạy cảm |
| `audit_event` | Audit log | xem BR-CORE-090 |
| `document` / `document_version` / `doc_template` | Tệp & mẫu (dữ liệu nhị phân trong object storage của saas-platform) | tham chiếu chủ sở hữu, loại, mức độ bảo mật, lớp lưu trữ (khóa trỏ vào chính sách lưu trữ của saas-platform), storage key, hash |
| `import_type` / `import_job` / `import_row` | Import engine (engine duy nhất; bao gồm lô số dư đầu kỳ) | mẫu, chế độ, trạng thái, số lượng, lỗi, tham chiếu rollback |
| `export_job` | Đã bỏ — thuộc framework báo cáo/xuất dữ liệu do saas-platform sở hữu (R2-02) (thay đổi 2026-10-16, R2-02 §5 #18) | — |

---

## 7. Interface liên module

Kiểu interface: API nội bộ đồng bộ cho truy vấn (các dịch vụ Core chỉ đọc), domain event bất đồng bộ (outbox → message bus) cho thay đổi trạng thái. Mọi interface đều theo phạm vi tenant và có nhận biết phạm vi dữ liệu.

### 7.1 Sử dụng

| # | Nội dung | Từ module | Khi nào / tác nhân kích hoạt | Dùng cho |
|---|---|---|---|---|
| C-01 | `TenantProvisioned` (tenant id, gói, liên hệ chủ sở hữu, locale, vùng hosting) | saas-platform | Tạo tenant | Tạo company group, người dùng Owner, khởi tạo vai trò hệ thống, mẫu, cấu hình |
| C-02 | Quyền lợi & giới hạn theo gói (số pháp nhân tối đa, số người dùng tối đa, module/tính năng được bật, kênh được phép ví dụ Zalo/SMS, hạn mức lưu trữ) | saas-platform | Khi đăng nhập/cấu hình, khi có `PlanChanged` | Thực thi giới hạn (BR-CORE-001), ẩn tính năng bị tắt, kiểm soát kênh |
| C-03 | `TenantSuspended` / `TenantReactivated` / `TenantTerminated` | saas-platform | Thay đổi vòng đời | Chặn đăng nhập, dừng scheduler/thông báo, bắt đầu xuất/xóa dữ liệu |
| C-04 | Lệnh phát hành master data từ giao diện back-office và quy trình phát hành của operator (đơn vị hành chính, ngân hàng, quốc tịch…) vào kho và admin API của Core; bảng ánh xạ vùng **không còn** là master data (legal pack, C-23) | saas-platform (giao diện operator) | Khi công bố | Nạp có phiên bản, `core.MasterDataReleased` (BR-CORE-080/087) (thay đổi 2026-10-16, R2-02 §5 #28) |
| C-05 | Cấp session hỗ trợ break-glass | saas-platform | Khi yêu cầu hỗ trợ được tenant phê duyệt | Thực thi và audit (BR-CORE-041) |
| C-06 | Dịch vụ nền tảng: channel delivery adapter kèm tài khoản nhà cung cấp (email; push/Zalo/SMS ở Phase 2) và trạng thái gửi, object storage, khóa KMS, message bus | saas-platform | Runtime | Gửi thông báo, lưu trữ tài liệu, mã hóa (thay đổi 2026-10-16, R2-02 §5 #14, #15) |
| C-07 | `people.EmployeeHired` (hire_type NEW/REHIRE/CONCURRENT, employee id, họ tên, email công việc, di động, ngày nhận việc, pháp nhân, cờ xem trước) | people | Khi phê duyệt (xem trước) và vào ngày nhận việc | Tự động tạo/kích hoạt lại người dùng (P-04); `EmployeeRehired` được gộp vào `hire_type` (thay đổi 2026-10-16, R2-02 §5 #9) |
| C-08 | `people.TerminationScheduled` (access_revocation_date, LWD) và `people.EmployeeTerminated` | people | Nghỉ việc được phê duyệt / ngày chấm dứt | Lên lịch hạ quyền, thu hồi session, giao lại công việc & ủy quyền, chuyển sang `FORMER_EMPLOYEE` (P-04); People không gửi sự kiện yêu cầu cấp tài khoản (thay đổi 2026-10-16, R2-02 §5 #9) |
| C-09 | `AssignmentChanged` (nhân viên, vị trí, đơn vị tổ chức, địa điểm, pháp nhân, quản lý, loại, ngạch bậc, ngày hiệu lực) | people | Điều chuyển / thăng chức | Tính lại phạm vi suy ra (SELF/LINE_MANAGER), xác định lại các bước workflow đang mở |
| C-10 | Tra cứu phân công hiện tại: nhân viên → vị trí, đơn vị tổ chức, quản lý, pháp nhân, địa điểm, loại, ngạch bậc (tại ngày) | people | Đồng bộ khi xác định người phê duyệt, đánh giá phạm vi, xác định đối tượng thông báo | Cơ chế xác định người phê duyệt, phạm vi dữ liệu, đối tượng nhận |
| C-11 | Mức lấp đầy vị trí (FTE đã lấp theo vị trí, người đảm nhiệm) | people | Theo yêu cầu / `AssignmentChanged` | Vị trí trống, sơ đồ tổ chức, kiểm tra kiểm soát vị trí |
| C-12 | `people.ReorgMappingCompleted{package_id, moved, failed, fix list ref}` — kết quả thực hiện ánh xạ tái cơ cấu (BR-PPL-039, OUT-35) | people | Sau `ReorgActivated` và sau mỗi lần sửa và thử lại | Đặt gói `ACTIVE` / `ACTIVATED_WITH_ERRORS`, phát `core.ReorgPackageStatusChanged`, báo cáo lỗi (thay đổi 2026-10-16, GAP-053) |
| C-13 | Dữ liệu trường trộn cho tài liệu (nhân viên, hợp đồng) | people / payroll / recruitment | Khi sinh tài liệu | Mẫu văn bản (BR-CORE-102) |
| C-14 | Lịch làm việc & ngày lễ theo pháp nhân/địa điểm/nhân viên (API lịch `att`, ATT-P-16; T&A là chủ sở hữu duy nhất của lịch ngày lễ của tenant). Tenant không dùng T&A sử dụng mẫu ngày lễ quốc gia của legal pack (baseline, R2-18) | time-attendance | Tính SLA của workflow | SLA theo thời gian làm việc (BR-CORE-057) (thay đổi 2026-10-16, R2-02 §5 #2; R2-18) |
| C-15 | Nghỉ phép / vắng mặt đã duyệt của người dùng (khoảng thời gian) | time-attendance | Khi đơn nghỉ được duyệt | Gợi ý/tự động kích hoạt ủy quyền phê duyệt (BR-CORE-055) |
| C-16 | **Trạng thái khóa của pháp nhân** tại ngày (kỳ gần nhất mà bất kỳ nhóm lương nào của pháp nhân có kỳ lương ở trạng thái `APPROVED`/`PAID`/`CLOSED`), do payroll suy ra từ mọi nhóm lương của pháp nhân (IF-PAY-P-20) | payroll | Khi tái cơ cấu hồi tố / thay đổi thuộc tính pháp nhân | Cảnh báo/chặn thay đổi hồi tố (BR-CORE-015) (thay đổi 2026-10-16, GAP-049) |
| C-17 | Yêu cầu tuyển dụng đang xử lý theo vị trí | recruitment | Theo yêu cầu | Pipeline vị trí (BR-CORE-024), phân tích tác động tái cơ cấu |
| C-18 | Đăng ký process type, danh mục quyền, loại import, mã sự kiện thông báo, thực thể đích của trường tùy chỉnh, loại tài liệu | mọi module (people, time-attendance, payroll, recruitment, saas-platform) | Deploy / khởi động | Cấu hình các engine |
| C-19 | Xác nhận callback của workflow (tác động nghiệp vụ đã áp dụng / thất bại) | mọi module | Sau `WorkflowCompleted` | Đóng instance hoặc tạo công việc xử lý lỗi |
| C-20 | Validator import và commit/undo handler | mọi module | Trong job import | Import framework |
| C-21 | Thông tin liên hệ của ứng viên cho thông báo bên ngoài (email/điện thoại, sự đồng ý) | recruitment | Thông báo cho ứng viên | Gửi tới người không phải người dùng |
| C-22 | **Identity đã xác thực** (PR-03): identity người dùng, phương thức xác thực, mức MFA, SSO claim, step-up; API thu hồi session | saas-platform | Mọi request; khi hạ quyền người dùng | Ánh xạ identity → tài khoản người dùng tenant → vai trò và phạm vi; thu hồi session trong P-04 (mới 2026-10-16, R2-02 §5 #10) |
| C-23 | **Legal Parameter Service** `getParam(key, scope, asOfDate)`: bảng ánh xạ đơn vị hành chính → vùng và các giá trị pháp lý khác; `saas.LegalParameterPackPublished` | saas-platform | Đồng bộ, theo ngày hiệu lực; khi công bố | Xác định vùng (BR-CORE-006/007), mức sàn lưu trữ (mới 2026-10-16, R2-02 §5 #1) |
| C-24 | **Job scheduler / queue** (nhận biết tenant, hạn mức, tạm dừng theo trạng thái tenant, API tiến độ) | saas-platform | Runtime | Job kích hoạt theo ngày hiệu lực (NFR-CORE-015), bộ đếm SLA, job import (mới 2026-10-16, R2-02 §5 #16) |
| C-25 | Ngân sách FTE của kế hoạch nhân sự đã duyệt và các vị trí đề xuất, được ghi qua API vị trí của Core (Phase 2) | recruitment | Khi kế hoạch được duyệt | Ngân sách FTE của vị trí vẫn là ngân sách định biên chính thức (BR-CORE-022) (mới 2026-10-16, R2-02 §5 #8) |
| C-26 | API kiểm tra sự đồng ý, khóa chính sách lưu trữ, yêu cầu điều phối DSR | saas-platform | Runtime / DSR | Lớp lưu trữ (BR-CORE-104), xuất DSR P-25 (mới 2026-10-16, R2-02 §5 #27) |

### 7.2 Cung cấp

| # | Nội dung | Tới module | Khi nào | Ghi chú |
|---|---|---|---|---|
| P-01 | Master pháp nhân & tra cứu tại ngày: MST, mã đơn vị BHXH, địa chỉ, người đại diện theo pháp luật, tài khoản ngân hàng (tài khoản chi trả, `legal_entity_bank_account`), năm tài chính, cơ quan thuế, kỳ khai thuế TNCN, quyết định mức đóng BHTNLĐ-BNN, trạng thái | people, payroll, recruitment, time-attendance, saas-platform (liên kết bên thanh toán) | Truy vấn đồng bộ; sự kiện `core.LegalEntityChanged` | Khai báo của payroll, hợp đồng, thư mời nhận việc (thay đổi 2026-10-16, GAP-049; R2-02 §5 #5b, #23) |
| P-02 | Master địa điểm: địa chỉ, vùng, múi giờ, geo-fence (lat/long, bán kính), danh sách Wi-Fi/IP, mã số thuế phụ thuộc | time-attendance (check-in), payroll (vùng, thuế TNCN chi nhánh), people (nơi làm việc trên hợp đồng), recruitment (địa điểm công việc) | Truy vấn đồng bộ; `LocationChanged` | |
| P-03 | **Vùng lương tối thiểu** của địa điểm/pháp nhân tại ngày, xác định từ bảng ánh xạ của legal pack; sự kiện `core.LocationChanged` (subtype `LocationRegionChanged`) | payroll (kiểm tra lương tối thiểu & mức sàn BHXH), people (kiểm tra lương trên hợp đồng) | Đồng bộ; sự kiện | Mức lương là tham số của legal pack (thay đổi 2026-10-16, R2-02 §5 #1) |
| P-04 | Cây tổ chức tại ngày; chi tiết đơn vị; cấp trên/cấp dưới; quản lý đơn vị; `OrgUnitChanged`, `ReorgActivated` (kèm bảng ánh xạ) | people, time-attendance, payroll, recruitment | Đồng bộ; sự kiện | People thực hiện ánh xạ nhân viên |
| P-05 | Chức danh (bao gồm cờ & phân loại độc hại), nhóm công việc, danh mục ngạch bậc (không có khung lương) | people, payroll, recruitment, time-attendance (phép tăng thêm cho công việc độc hại) | Đồng bộ; `core.JobCatalogueChanged` | Khung lương lấy từ payroll IF-PAY-P-02 (thay đổi 2026-10-16, R2-02 §5 #7) |
| P-06 | Vị trí: định nghĩa, ngân sách FTE, trạng thái, báo cáo cho, trung tâm chi phí; chế độ kiểm soát vị trí; `PositionChanged` | people (kiểm tra phân công), recruitment (yêu cầu tuyển dụng cho vị trí trống) | Đồng bộ; sự kiện | |
| P-07 | Trung tâm chi phí & trung tâm chi phí mặc định theo đơn vị/vị trí | payroll (hạch toán chi phí/GL), people (phân bổ phân công) | Đồng bộ | |
| P-08 | Danh mục loại nhân viên | people, time-attendance, payroll, recruitment | Đồng bộ | |
| P-09 | Ngữ cảnh người dùng hiện tại (tài khoản người dùng tenant, employee id, tenant, locale, vai trò, phạm vi) xây dựng trên identity đã xác thực của saas-platform (C-22). Bản thân việc xác thực là PR-03 của saas-platform | mọi module, frontend | Mọi request | (thay đổi 2026-10-16, R2-02 §5 #10) |
| P-10 | **Dịch vụ phân quyền & phạm vi dữ liệu**: `can(user, permission, record)`, predicate lọc phạm vi cho truy vấn, chính sách che giấu trường | mọi module bao gồm reporting | Mọi request / truy vấn | Điểm thực thi duy nhất (BR-CORE-035) |
| P-11 | `core.UserStatusChanged` (người dùng, liên kết nhân viên, trạng thái ACTIVE / FORMER_EMPLOYEE / DEACTIVATED) — thay thế `UserCreated` / `UserDeactivated` | saas-platform (credential, session, SCIM), people (mục sẵn sàng onboarding "tài khoản người dùng") | Khi thay đổi | (thay đổi 2026-10-16, R2-02 §5 #9) |
| P-12 | **Workflow engine**: API gửi/rút lại, hộp công việc, callback `WorkflowCompleted` kèm kết quả & vết phê duyệt, sự kiện SLA | people (hợp đồng, thay đổi, nghỉ việc, chi phí, đánh giá), time-attendance (nghỉ phép, làm thêm giờ, điều chỉnh công, đổi ca), payroll (kỳ lương, điều chỉnh, tệp chi trả ngân hàng), recruitment (yêu cầu tuyển dụng, offer), core (tái cơ cấu, vị trí, cấp vai trò) | Runtime | |
| P-13 | Tra cứu ủy quyền phê duyệt (ai thực hiện thay ai, khi nào) | mọi module | Runtime | |
| P-14 | **Trung tâm thông báo**: send(mã sự kiện, người nhận, payload, tệp đính kèm mã hóa tùy chọn), mẫu, tùy chọn, hộp thư trong ứng dụng, trạng thái gửi (gửi qua adapter của saas-platform) | mọi module (ví dụ phiếu lương sẵn sàng, phiếu lương PDF có mật khẩu — R2-17, đơn nghỉ được duyệt, hợp đồng sắp hết hạn, thư mời phỏng vấn) | Runtime | Các module chỉ gọi Core (thay đổi 2026-10-16, R2-02 §5 #14; R2-17) |
| P-15 | Master data toàn cục: đơn vị hành chính (34 tỉnh + phường, đơn vị lịch sử, ánh xạ), quốc gia/quốc tịch, dân tộc, tôn giáo, loại giấy tờ tùy thân, ngân hàng/chi nhánh kèm mã, tiền tệ, trình độ học vấn, quan hệ | people (địa chỉ, giấy tờ tùy thân, tài khoản ngân hàng, người phụ thuộc), payroll (tệp chi trả ngân hàng), recruitment (dữ liệu ứng viên), saas-platform (địa chỉ tenant) | Đồng bộ | |
| P-16 | Danh mục mã chung & danh mục tùy chỉnh của tenant | mọi module | Đồng bộ | |
| P-17 | Dịch vụ cấu hình tenant/pháp nhân/địa điểm | mọi module | Đồng bộ | |
| P-18 | Dịch vụ đánh số (mã nhân viên, số hợp đồng, số quyết định, số kỳ lương, số yêu cầu tuyển dụng) | people, payroll, recruitment, time-attendance | Runtime | |
| P-19 | Framework trường tùy chỉnh | mọi module | Runtime | |
| P-20 | Dịch vụ audit: record(event), API truy vấn; log sự kiện bảo mật | mọi module; saas-platform (audit nền tảng, break-glass) | Runtime | |
| P-21 | API dịch vụ tài liệu: tải lên/tải xuống, quét virus, quản lý phiên bản, lớp lưu trữ, signed URL; mẫu văn bản → PDF; điều phối ký điện tử (connector ở saas-platform) | people (hợp đồng, quyết định, giấy xác nhận, tài liệu nhân viên), payroll (phiếu lương PDF, tờ khai), recruitment (CV, thư mời nhận việc), time-attendance (minh chứng nghỉ phép) | Runtime | Các module chỉ gọi Core; storage là PR-16 của saas-platform (thay đổi 2026-10-16, R2-02 §5 #15/#15b) |
| P-22 | Import engine bao gồm chế độ import số dư đầu kỳ (thay đổi 2026-10-16, D-09); xuất dữ liệu là PR-08 của saas-platform | mọi module, saas-platform (onboarding số dư đầu kỳ) | Runtime | (thay đổi 2026-10-16, R2-02 §5 #12, #18) |
| P-23 | Bộ tính ngày/giờ làm việc (bọc lịch của T&A) | mọi module để tính hạn | Runtime | Phụ thuộc C-14 |
| P-24 | Chỉ số sử dụng của tenant: người dùng hoạt động, pháp nhân, dung lượng đã dùng, số thông báo đã gửi theo kênh | saas-platform (thanh toán, hạn mức) | Hằng ngày | |
| P-25 | Log truy cập dữ liệu cá nhân & xuất audit trail của một người dùng (phục vụ yêu cầu của chủ thể dữ liệu) | saas-platform (điều phối DSR), people | Theo yêu cầu | VN-05 (thay đổi 2026-10-16, R2-02 §5 #27) |
| P-26 | Tỷ giá tại ngày tỷ giá (BR-CORE-088) | payroll (BR-PAY-013) | Đồng bộ | (mới 2026-10-16, R2-02 §5 #19) |
| P-27 | API ghi vào kho audit cho các sự kiện identity và operator của saas-platform (BR-CORE-091) | saas-platform | Runtime | (mới 2026-10-16, R2-02 §5 #13) |

---

## 8. Báo cáo & phân tích

| # | Báo cáo | Đối tượng | Nội dung |
|---|---|---|---|
| R-01 | Báo cáo / sơ đồ cơ cấu tổ chức tại ngày | HR, ban lãnh đạo | Cây gồm đơn vị, quản lý, headcount (từ people), vị trí trống |
| R-02 | Lịch sử thay đổi tổ chức | HR, kiểm toán viên | Các gói tái cơ cấu và thay đổi ở cấp đơn vị trong một khoảng thời gian |
| R-03 | Sổ đăng ký vị trí & vị trí trống | HR, C&B, recruitment | Ngân sách so với đã lấp so với đang tuyển theo đơn vị/pháp nhân |
| R-04 | Sổ đăng ký pháp nhân & địa điểm | HR, tài chính | MST, mã BHXH, vùng, địa chỉ, lịch sử hiệu lực |
| R-05 | Rà soát người dùng & quyền truy cập | Quản trị viên, kiểm toán viên | Người dùng, vai trò, phạm vi, lần đăng nhập cuối, tài khoản không hoạt động, vai trò đặc quyền, xung đột SoD — hỗ trợ tái chứng nhận quyền truy cập định kỳ |
| R-06 | Sự kiện đăng nhập & bảo mật | IT, kiểm toán viên | Đăng nhập thất bại, MFA, khóa tài khoản, lỗi SSO |
| R-07 | SLA & thông lượng workflow | HR, ban lãnh đạo | Yêu cầu theo process type, thời gian phê duyệt trung bình, vi phạm SLA, đang chờ theo người phê duyệt |
| R-08 | Phê duyệt đang chờ theo người phê duyệt / ủy quyền đang hiệu lực | Quản lý, HR | |
| R-09 | Tình trạng gửi thông báo | Quản trị viên | Đã gửi/lỗi theo kênh, ước tính chi phí Zalo ZNS |
| R-10 | Báo cáo audit trail | Kiểm toán viên | Các thay đổi đã lọc kèm giá trị trước/sau |
| R-11 | Báo cáo truy cập dữ liệu cá nhân | DPO / kiểm toán viên | Ai đã xem/xuất dữ liệu nhạy cảm |
| R-12 | Log job import/export | Quản trị viên | Job, số dòng, lỗi |
| R-13 | Tác động thay đổi master data | Quản trị viên | Các bản ghi tham chiếu đơn vị hành chính/ngân hàng đã ngừng sử dụng |

---

## 9. Yêu cầu phi chức năng

| ID | Yêu cầu | Ưu tiên |
|---|---|---|
| NFR-CORE-001 | Cô lập tenant: mọi bảng Core đều có `tenant_id` (trừ master data toàn cục) và được bảo vệ bằng row-level security; không API nào trả về dữ liệu liên tenant. Việc cô lập được kiểm thử tự động. | M |
| NFR-CORE-002 | Kiểm tra phân quyền/phạm vi dữ liệu tăng thêm ≤20 ms p95 mỗi request; predicate phạm vi phải thân thiện với index (ví dụ materialised org path/closure table). | M |
| NFR-CORE-003 | Truy vấn cây tổ chức "tại ngày" cho tenant 10,000 nhân viên với 2,000 đơn vị trả về trong ≤1 s p95. | M |
| NFR-CORE-004 | Workflow engine: gửi ≤500 ms p95; ≥50 lượt gửi/s mỗi node nền tảng; không mất công việc (transactional outbox, sự kiện at-least-once, callback idempotent). | M |
| NFR-CORE-005 | Thông báo: trong ứng dụng gửi tới ≤5 s; email/push/Zalo vào hàng đợi ≤1 phút p95 lúc cao điểm (ví dụ phát hành phiếu lương cho 10,000 nhân viên hoàn tất ≤30 phút). | M |
| NFR-CORE-006 | Tính sẵn sàng của đăng nhập 99.9% hằng tháng; việc xác thực token SSO không phụ thuộc vào lời gọi đồng bộ tới IdP ở mỗi request. | M |
| NFR-CORE-007 | Mật khẩu (do saas-platform nắm giữ, R2-02 §5 #10) được băm bằng thuật toán thích ứng hiện đại (Argon2id/bcrypt); secret và trường nhạy cảm được mã hóa khi lưu trữ bằng khóa theo tenant (KMS); TLS 1.2+ khi truyền. | M |
| NFR-CORE-008 | Việc ghi audit log là một phần của giao dịch nghiệp vụ hoặc được đảm bảo qua outbox; kho audit có khả năng phát hiện giả mạo; truy vấn audit 1 năm cho một bản ghi ≤3 s. | M |
| NFR-CORE-009 | Import 50,000 dòng kiểm tra hợp lệ ≤5 phút; xuất 100,000 dòng ≤5 phút bất đồng bộ. | S |
| NFR-CORE-010 | Tệp: dung lượng tải lên tối đa cấu hình được (mặc định 20 MB), quét mã độc ≤60 s; signed URL hết hạn ≤15 phút. | M |
| NFR-CORE-011 | Bản địa hóa: mọi nhãn UI, mẫu, nhãn danh mục mã, thông báo kiểm tra hợp lệ bằng VI và EN; hỗ trợ đầy đủ dấu tiếng Việt trong tìm kiếm (tìm kiếm không phân biệt dấu) và xuất dữ liệu (UTF-8, tương thích Excel). | M |
| NFR-CORE-012 | Khả năng tiếp cận WCAG 2.1 AA cho các màn hình Core; responsive cho phê duyệt trên mobile. | S |
| NFR-CORE-013 | Toàn bộ cấu hình Core (vai trò, workflow, mẫu, cấu hình, danh mục mã) có thể xuất/import giữa các môi trường của cùng một tenant (ví dụ UAT → PROD). | S |
| NFR-CORE-014 | Bảo mật: OWASP ASVS L2; chống brute-force; giới hạn tần suất theo tenant/người dùng/API client. | M |
| NFR-CORE-015 | Job kích hoạt theo ngày hiệu lực chạy lúc 00:00 Asia/Ho_Chi_Minh, idempotent và có thể chạy lại. | M |

---

## 10. Giả định, câu hỏi mở, rủi ro

### 10.1 Giả định
| # | Giả định |
|---|---|
| A-01 | Một người dùng thuộc một tenant; tư vấn viên phục vụ nhiều tenant được cấp các tài khoản riêng (identity đa tenant là chủ đề của saas-platform). |
| A-02 | Người sử dụng lao động theo pháp luật của một nhân viên là pháp nhân của đơn vị tổ chức / vị trí trong phân công của họ; module people lưu tường minh và Core kiểm tra tính nhất quán. |
| A-03 | **Mức** lương tối thiểu, bảng ánh xạ đơn vị hành chính → vùng, mức trần BHXH và thuế suất TNCN nằm trong Legal Parameter Service của saas-platform; Core chỉ xác định vùng của một địa điểm từ bảng ánh xạ của pack (thay đổi 2026-10-16, R2-02 §5 #1). |
| A-04 | Ngày lễ và lịch làm việc do time-attendance làm master; Core sử dụng chúng cho SLA. |
| A-05 | Master data toàn cục (đơn vị hành chính, ngân hàng) được lưu trong Core và phục vụ qua admin API của Core; platform operator quản lý qua quy trình phát hành back-office của saas-platform (thay đổi 2026-10-16, R2-02 §5 #28). |
| A-07 | Các năng lực baseline luôn bật bất kể tenant mua module nào (R2-18): Core, People cơ bản, tham số pháp lý, thang bảng lương/khung lương, import chấm công từ Excel. Chức năng Core không bao giờ phụ thuộc vào module chưa đăng ký; khi không có T&A, lịch SLA dùng mẫu ngày lễ quốc gia của legal pack (thay đổi 2026-10-16, R2-18). |
| A-06 | Zalo OA/ZNS được dùng với OA và tài khoản ZNS riêng của tenant (tenant chịu chi phí ZNS). |

### 10.2 Câu hỏi mở
| # | Câu hỏi | Người phụ trách |
|---|---|---|
| Q-01 | Workflow engine được tự xây dựng hay dựa trên một BPMN engine nhúng (Flowable/Camunda)? Ảnh hưởng tới UX trình thiết kế và khả năng hỗ trợ nhánh song song. | Solution architect |
| Q-02 | Mỗi pháp nhân luôn có mã đơn vị BHXH riêng, hay chi nhánh có thể khai báo BHXH riêng (mã BHXH theo chi nhánh)? Cần ý kiến chuyên gia tư vấn; mô hình hiện cho phép mã ở cấp chi nhánh. | BA Payroll / pháp lý |
| Q-03 | ~~Bảng vùng có nên cho phép tenant ghi đè hay không, hay hoàn toàn do nền tảng kiểm soát?~~ **Đã đóng 2026-10-16 (R2-02 §5 #1):** bảng ánh xạ là tham số của legal pack; tenant chỉ được ghi đè ở những nơi pack đánh dấu "pháp luật cho phép", do Legal Parameter Service quản lý. | PM / pháp lý |
| Q-04 | Danh sách chính xác các phường/xã sau sáp nhập và bộ mã chính thức (bảng mã của Tổng cục Thống kê) cùng tần suất cập nhật. | Platform operator `[verify-legal]` |
| Q-05 | ~~Ai sở hữu "kế hoạch headcount" so với "ngân sách vị trí"?~~ **Đã đóng 2026-10-16 (R2-02 §5 #8):** ngân sách FTE vị trí của Core là ngân sách định biên chính thức; kế hoạch nhân sự của recruitment (Phase 2) ghi ngân sách FTE và vị trí đề xuất vào Core qua API và sở hữu ngân sách chi phí. Trong MVP vị trí phải tồn tại trước khi có yêu cầu tuyển dụng. | BA Core + BA Recruitment |
| Q-06 | Nhà cung cấp chữ ký điện tử/hợp đồng điện tử nào cần hỗ trợ trong MVP? | PM |
| Q-07 | Có cần đơn vị tổ chức cấp tập đoàn (liên pháp nhân) cho MVP không (ví dụ phòng HR dùng chung phục vụ nhiều pháp nhân)? | Tenant thí điểm |
| Q-08 | Vị trí lưu trữ dữ liệu: toàn bộ dữ liệu và tệp của tenant có bắt buộc phải nằm trên hạ tầng đặt tại VN không? | saas-platform / pháp lý |
| Q-09 | ~~Tenant thí điểm có cần cấp tài khoản qua SCIM không?~~ **Đã chuyển 2026-10-16 (R2-02 §5 #10):** SCIM là yêu cầu của saas-platform (BR-SAAS-042); tenant thí điểm không cần SSO (R2-04). | Tenant thí điểm |
| Q-10 | ~~Thông báo phiếu lương qua Zalo có nên bao gồm số tiền không?~~ **Đã đóng 2026-10-16 (R2-17):** không gửi số tiền qua Zalo/SMS; phiếu lương được gửi qua e-mail dưới dạng PDF có mật khẩu (ngoại lệ BR-CORE-074, BR-PAY-102) hoặc qua ESS. | PM / DPO |

### 10.3 Rủi ro
| # | Rủi ro | Tác động | Giảm thiểu |
|---|---|---|---|
| R-01 | Dữ liệu sáp nhập đơn vị hành chính chưa đầy đủ/đang thay đổi; địa chỉ trong tệp số dư đầu kỳ của khách hàng dùng mô hình 63 tỉnh/quận huyện cũ (thay đổi 2026-10-16, D-09) | Sai địa chỉ, gán vùng, khai báo | Giữ đơn vị lịch sử + bảng ánh xạ; báo cáo import các địa chỉ chưa ánh xạ; cập nhật do nền tảng quản lý |
| R-02 | Ánh xạ vùng sau sáp nhập khác với trực giác (tỉnh sau sáp nhập chứa nhiều vùng ở cấp phường) | Trả lương thấp hơn mức tối thiểu | Vùng ở cấp phường, có hiệu lực theo ngày, được chuyên gia tư vấn xác minh `[verify-legal]` |
| R-03 | Engine phạm vi dữ liệu trở thành nút thắt hiệu năng | Danh sách/báo cáo chậm | Closure table, cache predicate phạm vi, load test NFR-CORE-002 |
| R-04 | Workflow engine bị tổng quát hóa quá mức; chậm bàn giao cho MVP | Trễ MVP | Tập con MVP: tuần tự + any-of + ủy quyền + SLA; nhánh song song ở Phase 2 |
| R-05 | Thời gian phê duyệt mẫu Zalo ZNS và chi phí | Kênh chưa sẵn sàng khi go-live | Email/trong ứng dụng là baseline MVP; Zalo ở mức S |
| R-06 | Tương tác giữa tái cơ cấu có hiệu lực theo ngày với hồi tố lương và T&A | Sai hạch toán chi phí/chấm công | Kiểm tra trạng thái khóa của pháp nhân (C-16, từ payroll), quyền hồi tố, sự kiện kèm ngày hiệu lực |
| R-07 | Nghĩa vụ tuân thủ Luật BVDLCN (2026) mở rộng (DPIA, log sự đồng ý) | Rủi ro pháp lý | Cờ nhạy cảm, audit truy cập, hỗ trợ DSR (P-25); rà soát pháp lý |
| R-08 | Các nhóm module bỏ qua dịch vụ phân quyền của Core và tự kiểm tra | Rò rỉ dữ liệu giữa các phạm vi | Quy tắc kiến trúc + kiểm thử tự động trên mọi API |
