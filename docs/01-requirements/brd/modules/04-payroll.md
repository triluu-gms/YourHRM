# YourHRM — BRD Module 04: Tính lương (`payroll` / schema `pay`)

| Mục | Giá trị |
|---|---|
| Tài liệu | Yêu cầu nghiệp vụ — Module Tính lương |
| Phiên bản | Bản nháp v0.3 (2026-10-16) |
| Người phụ trách | BA – Tính lương |
| Tiền tố yêu cầu | `BR-PAY-NNN`, `NFR-PAY-NNN`; giao diện `IF-PAY-C-NN` (tiêu thụ) / `IF-PAY-P-NN` (cung cấp) |
| Liên quan | Charter §4–§5; các BRD 01-core, 02-people, 03-time-attendance, 05-recruitment, 06-saas-platform |
| Trạng thái | Chờ PM, chuyên viên phân tích tích hợp, chuyên gia tư vấn lao động/thuế VN rà soát |
| Ghi chú thay đổi | v0.2 (2026-10-16): việc chốt kỳ được đổi sang giao thức **close hold** (quyết định X-03, ADR-0019, [gap-analysis](../gap-analysis.md) §7 v1.1) — P2, BR-PAY-071/074/112, mới BR-PAY-082/083/084, §6 `pay_period`/`payroll_run`, chú giải thời điểm §7, IF-PAY-C-19..27, mới IF-PAY-C-34, IF-PAY-P-05/06, §7.3. "Locked" nay chỉ dành cho kỳ chấm công; một run đã duyệt là chỉ đọc. Việc chuyển đổi dữ liệu từ Oracle được thay bằng **import số dư đầu kỳ** dùng chung (quyết định D-09) — P1, BR-PAY-122, A5, R7. Truy cập phiếu lương trên mobile chuyển sang Phase 2 (OD-9/X-04) — BR-PAY-101, IF-PAY-P-11. Đơn vị tính phí theo D-10 được ghi chú tại IF-PAY-P-17. Các chỉnh sửa được đánh dấu "(changed 2026-10-16, X-03/D-09)". v0.3 (2026-10-16): áp dụng bảng chủ sở hữu duy nhất đã được phê duyệt ([gap-analysis](../gap-analysis.md) §5, quyết định **R2-02**): tham số pháp lý lưu trong Legal Parameter Service của SaaS, `pay.legal_param_set` = snapshot theo từng run của phiên bản gói tham số (BR-PAY-010; #1); lịch ngày lễ lấy từ T&A (IF-PAY-C-06; #2); thu nhập thỏa thuận gồm GROSS/NET và lương net thỏa thuận được tiêu thụ từ People, lương được đóng băng theo từng run (BR-PAY-020/072, IF-PAY-C-10/11; #24, GAP-047); Payroll sở hữu 08/CK-TNCN và ủy quyền quyết toán (#4), việc gán nhóm trả lương và phương pháp ngày công chuẩn (mới BR-PAY-085, IF-PAY-P-19; #25), lịch sử đóng BHXH kể cả trước go-live (#20), thời gian làm việc tính trợ cấp thôi việc (BR-PAY-078; #29); tỷ giá từ Core (#19); các trang ESS nằm trong shell SaaS (#17); báo cáo đưa vào reporting store của SaaS (#18). GAP-048: tình trạng cư trú tiêu thụ từ People + báo cáo kiểm tra 183 ngày (mới BR-PAY-065). GAP-049: cơ quan thuế, tần suất kê khai và quyết định tỷ lệ TNLĐ-BNN đọc từ pháp nhân trong Core (BR-PAY-045/056); trạng thái khóa của pháp nhân được cung cấp cho Core (mới BR-PAY-087, IF-PAY-P-20). GAP-050: bên tiêu thụ các sự kiện People ảnh hưởng đến lương (mới BR-PAY-086, IF-PAY-C-35..40). R2-16: làm việc đồng thời ở nhiều pháp nhân (BR-PAY-081). R2-17: PDF phiếu lương có mật khẩu do nhân viên tự đặt (BR-PAY-102). R2-18: thang bảng lương/khung lương luôn bật (IF-PAY-P-02). Các chỉnh sửa được đánh dấu "(changed 2026-10-16, R2-02 §5 #n)" hoặc kèm ID quyết định/gap. Các cột ưu tiên không đổi (PM đồng bộ). |

> **Phase:** phase triển khai của mọi yêu cầu chỉ được xác định trong [mvp-scope.md](../mvp-scope.md) §9; MoSCoW ở đây là mức ưu tiên nghiệp vụ. (2026-10-16)

> **Giá trị pháp lý.** Mọi tỷ lệ, mức trần, bậc thuế, khoản giảm trừ, ngưỡng, lương tối thiểu và mã biểu mẫu nêu dưới đây là
> ví dụ được đánh dấu `[verify-legal]`. Không giá trị nào được hard-code: tất cả là **tham số có hiệu lực theo ngày, có phiên bản**
> (BR-PAY-010). Hệ thống phải tính bất kỳ kỳ nào bằng bộ tham số có hiệu lực trong kỳ đó.

---

## 1. Mục đích & phạm vi

### 1.1 Mục đích
Tính toán, phê duyệt, chi trả, hạch toán và báo cáo thu nhập của người lao động cho các pháp nhân Việt Nam, bao gồm
bảo hiểm bắt buộc (BHXH/BHYT/BHTN/TNLĐ-BNN), kinh phí công đoàn và thuế thu nhập cá nhân (thuế TNCN), để tenant có thể chạy
bảng lương hằng tháng tuân thủ đầy đủ mà không cần bảng tính, và nộp các tờ khai liên quan cho Bảo hiểm Xã hội Việt Nam
(VSS / BHXH) và Tổng cục Thuế (GDT / Cục Thuế).

### 1.2 Trong phạm vi
| # | Lĩnh vực |
|---|---|
| 1 | Danh mục thành phần lương: thu nhập, phụ cấp (chịu thuế / chịu thuế một phần / không chịu thuế, có / không tính đóng BHXH), khấu trừ, chi phí người sử dụng lao động, khoản thông tin |
| 2 | Cấu trúc lương và formula engine cấu hình được theo pháp nhân / ngạch bậc / nhóm nhân viên |
| 3 | Hợp đồng GROSS và NET, quy đổi net sang gross (gross-up) |
| 4 | Tính theo tỷ lệ (proration) cho người mới vào, người nghỉ việc, thay đổi lương/phụ cấp, nghỉ không lương; các phương pháp ngày công chuẩn |
| 5 | Tiền làm thêm giờ và làm việc ban đêm từ kết quả chấm công |
| 6 | BHXH / BHYT / BHTN / TNLĐ-BNN bắt buộc (người lao động + người sử dụng lao động), kinh phí công đoàn (2%) và đoàn phí công đoàn |
| 7 | Thuế TNCN: cá nhân cư trú (lũy tiến), không cư trú (20% cố định), khấu trừ 10% với thu nhập vãng lai, bản cam kết 08/CK-TNCN, kê khai tháng/quý, quyết toán cuối năm (05/QTT-TNCN), chứng từ khấu trừ điện tử |
| 8 | Hồ sơ khai BHXH (D02-LT và liên quan) và đối chiếu số đóng hằng tháng |
| 9 | Các run lương: hằng tháng, ngoài kỳ, thưởng/lương tháng 13/Tết, truy lĩnh, quyết toán khi nghỉ việc |
| 10 | Khoản vay, tạm ứng lương và khấu trừ trả góp |
| 11 | Phiếu lương (ESS, PDF có mật khẩu, e-mail) |
| 12 | File chi lương ngân hàng (VCB, BIDV, Techcombank, VietinBank, ACB, MB, …) |
| 13 | Xuất bút toán GL, phân bổ chi phí theo trung tâm chi phí / dự án |
| 14 | Khóa, quy trình phê duyệt, audit, đa pháp nhân |

### 1.3 Ngoài phạm vi (hoặc do module khác sở hữu)
| Hạng mục | Chủ sở hữu |
|---|---|
| Hồ sơ nhân viên gốc, hợp đồng, *thỏa thuận* lương (thu nhập thỏa thuận gồm cơ sở GROSS/NET và lương net thỏa thuận — People là hệ thống gốc), sổ người phụ thuộc, tài khoản ngân hàng, số sổ BHXH, tình trạng cư trú thuế | `people` (changed 2026-10-16, R2-02 §5 #3, #5, #24; GAP-048) |
| Giá trị tham số pháp lý (tỷ lệ, mức trần, bậc thuế, giảm trừ, lương tối thiểu, bản đồ vùng, mẫu ngày lễ) | Legal Parameter Service của `saas-platform` — Payroll đọc và snapshot theo từng run (changed 2026-10-16, R2-02 §5 #1) |
| Lịch ngày lễ | `time-attendance` (changed 2026-10-16, R2-02 §5 #2) |
| Ghi nhận chấm công, bảng công, duyệt nghỉ phép và làm thêm giờ, phân loại ca / giờ đêm | `time-attendance` |
| Pháp nhân, cây tổ chức, trung tâm chi phí, vùng, workflow engine, thông báo, audit store, RBAC | `core` |
| Khởi tạo tenant, gói thuê bao, khóa mã hóa, cổng e-mail/SMS, lưu trữ file, nhà cung cấp chữ ký điện tử | `saas-platform` |
| Mô hình hóa lương đề nghị (offer) (payroll chỉ cung cấp dịch vụ ước tính chi phí) | `recruitment` |
| Ghi sổ kế toán (YourHRM chỉ xuất dữ liệu; không phải hệ thống kế toán) | ERP bên ngoài |
| Nộp trực tiếp qua API tới VSS / GDT (Phase 2 qua T-VAN; MVP tạo file để tải lên VssID/I-VAN và eTax/HTKK) | payroll, Phase 2 |
| Xử lý hồ sơ hưởng chế độ BHXH (trợ cấp ốm đau/thai sản do VSS chi trả, mẫu 01B-HSB) | payroll, Phase 2 (`S`) |
| Duyệt đề nghị thanh toán chi phí | `people` (payroll chỉ chi trả các khoản hoàn ứng đã duyệt) |

---

## 2. Tác nhân & persona

| Tác nhân | Mô tả | Quyền chính |
|---|---|---|
| Quản trị viên tính lương (Chuyên viên C&B) | Cấu hình thành phần, chạy lương, xử lý ngoại lệ | Cấu hình, tính toán, gửi duyệt, tạo file |
| Trưởng bộ phận tính lương / Trưởng C&B | Rà soát chênh lệch, duyệt run, mở khóa có giải trình | Duyệt/từ chối, mở khóa, xem tất cả |
| Kế toán trưởng / Tài chính | Duyệt giải ngân chi trả, sử dụng file xuất GL, đối chiếu khoản phải nộp BHXH/thuế TNCN | Duyệt chi trả, xuất GL, xem tổng hợp |
| Giám đốc pháp nhân (Người đại diện theo pháp luật) | Phê duyệt cuối khi chính sách tenant yêu cầu; ký tờ khai | Phê duyệt, ký điện tử |
| Quản trị viên nhân sự | Duy trì dữ liệu People làm đầu vào cho tính lương | Đọc kết quả lương trong phạm vi của mình (cấu hình được) |
| Quản lý trực tiếp | Xem báo cáo chi phí của nhóm (không xem lương cá nhân trừ khi được cấp quyền) | Báo cáo tổng hợp |
| Nhân viên (ESS) | Xem phiếu lương, chứng từ thuế TNCN, dư nợ vay; nộp 08/CK-TNCN, ủy quyền quyết toán thuế TNCN | Chỉ dữ liệu của bản thân |
| Lao động vãng lai / nhà thầu (người nhận không phải nhân viên) | Nhận phí dịch vụ, bị khấu trừ 10% và nhận chứng từ | Chứng từ của bản thân (cổng thông tin hoặc e-mail) |
| Kiểm toán viên (nội bộ/bên ngoài) | Kiểm tra chỉ đọc các run và thay đổi | Chỉ đọc, audit log |
| Tenant Admin | Phân quyền vai trò tính lương và phạm vi dữ liệu theo pháp nhân | RBAC (qua `core`) |
| Quản trị nội dung pháp lý nền tảng (nhà vận hành SaaS) | Phát hành gói tham số pháp lý quốc gia (tỷ lệ, mức trần, bậc thuế) | Duy trì bộ tham số toàn cục (qua `saas-platform`) |
| Hệ thống bên ngoài | Ngân hàng, ERP/kế toán, cổng VSS, GDT eTax / HTKK, T-VAN | Trao đổi file / API |

---

## 3. Quy trình nghiệp vụ

### P1 — Thiết lập tính lương (một lần, sau đó khi có thay đổi)
1. Nền tảng phát hành gói tham số pháp lý quốc gia (lương tối thiểu, mức tham chiếu / lương cơ sở, tỷ lệ BHXH/BHYT/BHTN/TNLĐ-BNN, mức trần, biểu thuế TNCN, giảm trừ, mức trần miễn thuế) kèm ngày hiệu lực.
2. Quản trị viên tính lương của tenant tạo các nhóm trả lương theo pháp nhân (tần suất trả lương, ngày chốt, ngày trả lương, phương pháp ngày công chuẩn, tiền tệ, tài khoản ngân hàng chi trả lấy từ `legal_entity_bank_account` của Core) và quy tắc mặc định gán nhân viên vào nhóm trả lương (pháp nhân + loại nhân viên) (changed 2026-10-16, R2-02 §5 #5b, #25).
3. Quản trị viên cấu hình thành phần lương và công thức, cấu trúc lương theo ngạch bậc / nhóm nhân viên, ánh xạ GL, mẫu phiếu lương.
4. Quản trị viên nạp số dư đầu kỳ (thu nhập/thuế TNCN/BHXH lũy kế từ đầu năm, lịch sử đóng BHXH, lịch sử lương 6 tháng, dư nợ vay) qua chức năng import số dư đầu kỳ (BR-PAY-122), sau đó chạy thử bảng lương và so sánh với bảng lương gần nhất của tenant. Không chuyển đổi dữ liệu từ Oracle hay chạy song song (changed 2026-10-16, D-09).
- *Ngoại lệ:* tenant cần tỷ lệ không chuẩn (ví dụ TNLĐ-BNN 0,3% đã được chấp thuận cho pháp nhân) → quyết định tỷ lệ TNLĐ-BNN được ghi trên pháp nhân trong Core (tỷ lệ, số quyết định, thời hạn hiệu lực, minh chứng — BR-CORE-002) và Payroll áp dụng nếu gói pháp lý cho phép giá trị đó; các ghi đè được phép khác là ghi đè theo pháp nhân trong Legal Parameter Service, có hiệu lực theo ngày, kèm phê duyệt và đính kèm minh chứng (changed 2026-10-16, GAP-049; R2-02 §5 #1).

### P2 — Run lương hằng tháng (luồng chuẩn)
1. **T-n:** Các thay đổi People trong kỳ có hiệu lực (tuyển mới, nghỉ việc, thay đổi lương, người phụ thuộc). T&A chốt kỳ chấm công (`CLOSED`) và phát hành Attendance Result có phiên bản (IF-PAY-C-20) (changed 2026-10-16, X-03).
2. Quản trị viên mở run cho một nhóm trả lương + kỳ; hệ thống snapshot dữ liệu đầu vào (nhân viên trong phạm vi, hợp đồng, thu nhập — các phiên bản bản ghi lương People, được đóng băng cho run — kết quả T&A, dữ liệu biến đổi, khoản vay, phiên bản gói pháp lý) (changed 2026-10-16, R2-02 §5 #1, #24).
3. Quản trị viên import/nhập dữ liệu biến đổi (thưởng, phụ cấp một lần, khấu trừ) qua UI hoặc mẫu Excel.
4. Hệ thống tính: tổng thu nhập → tính theo tỷ lệ → làm thêm giờ → BHXH/BHYT/BHTN/KPCĐ → thu nhập tính thuế → thuế TNCN → khấu trừ → thực lĩnh; nhân viên NET được gross-up.
5. Hệ thống tạo danh sách ngoại lệ (thực lĩnh âm, thực lĩnh dưới ngưỡng, thiếu tài khoản ngân hàng, thiếu MST, chênh lệch > x% so với kỳ trước, lương dưới mức tối thiểu vùng, thiếu dữ liệu T&A).
6. Quản trị viên sửa và tính lại (từng người hoặc toàn bộ); **gửi** duyệt. Khi gửi, hệ thống đặt một **close hold** lên mọi phiên bản Attendance Result mà run sử dụng (BR-PAY-082); nếu có hold bị từ chối thì việc gửi thất bại và run giữ trạng thái `Calculated`.
7. Trưởng bộ phận tính lương → Tài chính (→ Giám đốc) phê duyệt qua workflow của `core`. Trạng thái run chuyển thành **Approved** (chỉ đọc); T&A khóa các phiên bản đang được hold (`CLOSED → LOCKED`).
8. Hệ thống tạo file ngân hàng, bút toán GL, khoản phải nộp BHXH/KPCĐ/thuế TNCN, phiếu lương; phiếu lương được phát hành lên ESS vào ngày công bố.
9. Sau khi ngân hàng xác nhận, run được đánh dấu **Paid**; kỳ được đóng.
- *Ngoại lệ (changed 2026-10-16, X-03):* kỳ T&A chưa `CLOSED` → run chỉ có thể tính ở chế độ *tạm tính* và không thể gửi duyệt; T&A yêu cầu mở lại một kỳ đang được hold → Payroll giải phóng hold, run quay về `Calculated` với cờ stale và phải tính lại trên phiên bản mới; dữ liệu nhân viên thay đổi sau snapshot → hệ thống gắn cờ "dữ liệu đầu vào đã thay đổi kể từ lần tính" và chặn phê duyệt cho đến khi tính lại hoặc chủ động hoãn sang truy lĩnh; bị từ chối thì run quay về Draft kèm nhận xét và giải phóng hold.

### P3 — Run ngoài kỳ (thưởng, lương tháng 13, Tết, điều chỉnh, tạm ứng)
1. Quản trị viên tạo run ngoài kỳ với loại run và tập nhân viên được chọn (bộ lọc hoặc import danh sách).
2. Chỉ các thành phần được bật cho loại run mới được tính; thuế TNCN được tính theo BR-PAY-052 (lũy kế trong tháng).
3. Phê duyệt và chi trả như P2.

### P4 — Truy lĩnh
1. Có thay đổi hồi tố (tăng lương có hiệu lực trong kỳ đã đóng, điều chỉnh T&A muộn, đăng ký người phụ thuộc muộn).
2. Hệ thống phát hiện các kỳ đã đóng bị ảnh hưởng và tính chênh lệch theo từng thành phần cho từng kỳ gốc.
3. Khoản chênh lệch được chi trả trong run mở kế tiếp dưới dạng thành phần truy lĩnh, có điều chỉnh BHXH cho các tháng gốc và thuế TNCN theo BR-PAY-064.
4. Chi tiết truy lĩnh được hiển thị trên phiếu lương và trong báo cáo truy lĩnh.

### P5 — Quyết toán khi chấm dứt hợp đồng
1. People ghi nhận nghỉ việc (ngày làm việc cuối cùng, lý do, việc tuân thủ thời hạn báo trước) → IF-PAY-C-08.
2. T&A cung cấp dữ liệu công cuối cùng và số ngày phép năm chưa nghỉ cần thanh toán.
3. Payroll tính: lương theo tỷ lệ, tiền phép chưa nghỉ, thời gian làm việc và trợ cấp thôi việc / mất việc làm (Bộ luật Lao động Điều 46–47; Payroll sở hữu việc tính toán, People cung cấp các giai đoạn và điều kiện hưởng), bồi thường/khấu trừ vi phạm thời hạn báo trước, khoản vay/tạm ứng lương còn dư nợ, các **khoản tạm ứng tiền mặt** còn tồn và **hoàn trả chi phí đào tạo** từ People, khấu trừ thu hồi tài sản, lương tháng 13 theo tỷ lệ (nếu có chính sách), thuế TNCN (changed 2026-10-16, R2-02 §5 #29; GAP-050).
4. Quyết toán được phê duyệt và chi trả trong thời hạn luật định (14 ngày làm việc, có thể kéo dài đến 30 ngày trong trường hợp cụ thể) `[verify-legal]`; bảng quyết toán được gửi cho người lao động.
5. Khai báo giảm BHXH (D02-LT) và hỗ trợ chốt sổ BHXH (xuất dữ liệu).

### P6 — Chu kỳ bảo hiểm bắt buộc hằng tháng
1. Payroll tính số đóng và mức lương đóng BHXH của từng nhân viên trong tháng.
2. Hệ thống tạo danh sách tăng / giảm / điều chỉnh (D02-LT) từ các sự kiện People và T&A (tuyển mới, nghỉ việc, nghỉ không lương ≥ 14 ngày làm việc, thai sản, thay đổi mức lương đóng).
3. Quản trị viên xuất file / XML cho cổng VSS; đối chiếu thông báo hằng tháng của VSS (C12-TS) với khoản phải nộp trên bảng lương; liệt kê chênh lệch.
4. Nộp tiền đóng trước hạn luật định (trước ngày cuối cùng của tháng với phương thức đóng hằng tháng) `[verify-legal]`.

### P7 — Kê khai thuế TNCN tháng/quý
1. Khi đóng run, số thuế TNCN đã khấu trừ được tổng hợp theo pháp nhân (MST) và theo kỳ tính thuế.
2. Hệ thống tạo dữ liệu tờ khai 05/KK-TNCN (XML tương thích HTKK/eTax) theo tháng hoặc quý tùy theo **tần suất kê khai thuế TNCN và cơ quan thuế của pháp nhân trong Core** (changed 2026-10-16, GAP-049).

### P8 — Quyết toán thuế TNCN cuối năm (quyết toán)
1. Nhân viên đủ điều kiện nộp giấy ủy quyền (08/UQ-QTT-TNCN hoặc mẫu hiện hành) qua ESS; nhân viên không đủ điều kiện được gắn cờ.
2. Hệ thống tính lại thuế năm cho nhân viên đã ủy quyền (thu nhập năm, giảm trừ năm, biểu thuế năm) và xác định số khấu trừ thừa/thiếu.
3. Quản trị viên rà soát; các điều chỉnh được xử lý trong một run (hoàn thuế hoặc khấu trừ bổ sung) theo chính sách tenant.
4. Hệ thống tạo 05/QTT-TNCN và các phụ lục 05-1/BK-QTT-TNCN, 05-2/BK-QTT-TNCN, 05-3/BK-QTT-TNCN.
- *Ngoại lệ:* nhân viên vào làm giữa năm từ đơn vị khác → không đủ điều kiện trừ khi đáp ứng các điều kiện; hệ thống hiển thị lý do về điều kiện.

### P9 — Thu nhập vãng lai (không có hợp đồng lao động hoặc hợp đồng < 3 tháng)
1. Quản trị viên ghi nhận người nhận (cá nhân, số giấy tờ tùy thân, MST), dịch vụ, số tiền gộp.
2. Nếu số tiền gộp mỗi lần chi ≥ ngưỡng và không có 08/CK-TNCN hợp lệ → khấu trừ 10%; người không cư trú → 20%.
3. Chi trả qua file ngân hàng; cấp chứng từ khấu trừ; đưa vào 05/KK và 05-2/BK.

### P10 — Khoản vay và tạm ứng
1. Đề nghị (ESS hoặc quản trị viên) → phê duyệt qua workflow → giải ngân (file ngân hàng hoặc run ngoài kỳ).
2. Tạo lịch trả nợ; các kỳ trả góp được khấu trừ trong các run; hỗ trợ tất toán trước hạn và tạm dừng; dư nợ còn lại được thu hồi khi quyết toán nghỉ việc.

### P11 — Phân phối phiếu lương & chứng từ
1. Khi công bố, phiếu lương xuất hiện trên ESS; tùy chọn gửi e-mail dưới dạng **PDF có mật khẩu**, với mật khẩu do nhân viên đặt trên ESS và không bao giờ được gửi trong cùng e-mail (BR-PAY-102; R2-17) (changed 2026-10-16, R2-17).
2. Chứng từ khấu trừ thuế TNCN (chứng từ điện tử) được cấp theo yêu cầu hoặc vào cuối năm; gửi qua ESS/e-mail.

---

## 4. Yêu cầu nghiệp vụ

### 4.1 Cấu hình, tham số & thành phần lương
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-001 | Hệ thống phải duy trì danh mục thành phần lương theo từng tenant, mỗi thành phần có: mã, tên VN/EN, loại (thu nhập, phụ cấp, khấu trừ, đóng góp của người sử dụng lao động, thông tin), phương pháp tính (cố định, công thức, nhập tay, import, lấy từ T&A), tiền tệ, quy tắc làm tròn, nhóm và thứ tự hiển thị trên phiếu lương. | M | |
| BR-PAY-002 | Mỗi thành phần phải có các cờ độc lập: cách xử lý thuế TNCN (chịu thuế / miễn thuế / miễn một phần có tham chiếu mức trần), có tính vào lương đóng BHXH (có/không), có tính vào cơ sở KPCĐ, có tính theo tỷ lệ (có/không và cơ sở tính tỷ lệ), áp dụng GROSS/NET, tính vào cơ sở lương tháng 13, tính vào cơ sở thanh toán phép, tính vào cơ sở trợ cấp thôi việc. | M | Cách xử lý thuế và BHXH là độc lập (ví dụ phụ cấp điện thoại có thể miễn thuế TNCN nhưng vẫn tính đóng BHXH). |
| BR-PAY-003 | Phụ cấp miễn thuế một phần phải tham chiếu tham số mức trần có hiệu lực theo ngày; phần vượt mức trần tự động chịu thuế. Được thiết lập sẵn: ăn giữa ca (mức trần ví dụ 730.000 VND/tháng khi chi bằng tiền `[verify-legal]`), đồng phục chi bằng tiền (mức trần ví dụ 5.000.000 VND/năm `[verify-legal]`), khoán điện thoại/xăng xe/công tác phí theo quy chế công ty (miễn thuế đến mức quy chế công ty), nhà ở (phần chịu thuế giới hạn 15% tổng thu nhập chịu thuế `[verify-legal]`). | M | Mức trần theo năm (đồng phục) cần theo dõi lũy kế từ đầu năm. |
| BR-PAY-004 | Thành phần lương phải có hiệu lực theo ngày và có phiên bản; thay đổi không bao giờ làm thay đổi kết quả của các run đã duyệt. | M | |
| BR-PAY-005 | Hệ thống phải cung cấp formula engine an toàn theo tenant, hỗ trợ phép tính số học, điều kiện (IF/CASE), MIN/MAX/ROUND, tra cứu (bảng ngạch bậc, bảng tỷ lệ), tham chiếu tới thành phần khác, thuộc tính nhân viên, số lượng từ T&A, tham số và giá trị lũy kế từ đầu năm; thứ tự phụ thuộc phải được tự động giải quyết và tham chiếu vòng bị từ chối khi lưu. | M | Không thực thi mã tùy ý; ngôn ngữ biểu thức chạy trong sandbox. |
| BR-PAY-006 | Việc soạn công thức phải có kiểm tra hợp lệ, tô sáng cú pháp, chế độ thử với một nhân viên/kỳ được chọn hiển thị các giá trị trung gian, và lịch sử phiên bản có so sánh khác biệt. | M | M → P2 (changed 2026-10-16, X-04) |
| BR-PAY-007 | Cấu trúc lương phải gom các thành phần kèm giá trị/công thức mặc định và gán được theo pháp nhân, ngạch/bậc, nhóm nhân viên (ví dụ văn phòng, nhà máy, kinh doanh, người nước ngoài) và loại hợp đồng, có cơ chế ưu tiên khi nhiều cấu trúc cùng khớp. | M | |
| BR-PAY-008 | Nhóm trả lương phải xác định pháp nhân, tần suất (hằng tháng ở MVP; nửa tháng `C`), ngày bắt đầu/kết thúc kỳ (hỗ trợ ngày chốt không theo tháng dương lịch, ví dụ 26–25), ngày chốt làm thêm giờ (tùy chọn), ngày trả lương, phương pháp ngày công chuẩn, tài khoản ngân hàng chi trả (một `legal_entity_bank_account` của pháp nhân trong Core), quy trình phê duyệt và mẫu phiếu lương. Payroll là chủ sở hữu duy nhất của nhóm trả lương và lịch trả lương (`pay.PayCalendarPublished`) (changed 2026-10-16, R2-02 §5 #5b, #25). | M | |
| BR-PAY-009 | Phương pháp ngày công chuẩn phải cấu hình được theo nhóm trả lương: (a) số cố định (ví dụ 26, 24, 22); (b) số ngày làm việc thực tế theo lịch làm việc của nhân viên trong kỳ; (c) ngày dương lịch; (d) số giờ cố định (trả lương theo giờ). Ngày lễ được hưởng lương tính là ngày được trả lương. Payroll là **chủ sở hữu duy nhất** của phương pháp này; phương pháp được công bố trong lịch trả lương và T&A dùng nó để tính `STD_DAYS`/`STD_HOURS` (BR-ATT-016) (changed 2026-10-16, R2-02 §5 #25). | M | Lịch làm việc lấy từ T&A. |
| BR-PAY-010 | Mọi tham số pháp lý (lương tối thiểu vùng và bản đồ đơn vị hành chính → vùng, mức tham chiếu / lương cơ sở, tỷ lệ BHXH/BHYT/BHTN/TNLĐ-BNN/KPCĐ, mức trần đóng, biểu thuế TNCN, giảm trừ gia cảnh bản thân và người phụ thuộc, ngưỡng và tỷ lệ khấu trừ thu nhập vãng lai, thuế suất người không cư trú, mức trần miễn thuế, hệ số làm thêm giờ tối thiểu, thời hạn quyết toán) được **lưu trữ và phát hành bởi Legal Parameter Service của saas-platform** và được Payroll đọc theo ngày hiệu lực (`getParam(key, scope, asOfDate)`); ghi đè theo tenant/pháp nhân chỉ được phép khi gói tham số gắn cờ "luật cho phép", có audit và phê duyệt trong service đó. Payroll không quản lý giá trị pháp lý gốc: `pay.legal_param_set` giữ **snapshot theo từng run của phiên bản gói tham số** (và các giá trị đã dùng), để mỗi kết quả đều tái lập được (changed 2026-10-16, R2-02 §5 #1). | M | Xem §5. Thay đổi giữa kỳ theo BR-PAY-024. |
| BR-PAY-011 | Hệ thống phải cung cấp sẵn các mẫu cấu hình Việt Nam (danh mục thành phần, cấu trúc cho văn phòng/nhà máy, ánh xạ GL theo hệ thống tài khoản Thông tư 200/133 hoặc văn bản thay thế) để tenant sao chép và điều chỉnh. | S | Rút ngắn thời gian onboarding. |
| BR-PAY-012 | Quy tắc làm tròn phải cấu hình được theo thành phần và cho lương thực lĩnh (ví dụ làm tròn đến 1 VND, 1.000 VND); chênh lệch làm tròn phải truy vết được. | M | |
| BR-PAY-013 | Đa tiền tệ: hợp đồng bằng USD (hoặc ngoại tệ khác) phải được quy đổi sang VND theo loại tỷ giá/ngày cấu hình được (ví dụ tỷ giá mua của ngân hàng vào ngày trả lương) đọc từ **bảng tỷ giá của Core** (BR-CORE-088, IF-PAY-C-33), tỷ giá được lưu trên kết quả; lương đóng BHXH và thuế TNCN luôn tính bằng VND (changed 2026-10-16, R2-02 §5 #19). | S | Phổ biến với người nước ngoài. Phase 2 (R2-09). |

### 4.2 Gross / net và tính thu nhập
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-020 | Hệ thống phải hỗ trợ thỏa thuận lương GROSS và NET cho từng nhân viên, lấy từ **bản ghi lương trong People** (`pay_basis`, `agreed_net_amount`, `people.SalaryChanged`), không lấy từ hợp đồng. Với NET, hệ thống phải gross-up theo phương pháp lặp hoặc giải tích sao cho lương thực lĩnh tính ra bằng lương net thỏa thuận sau khi trừ BHXH/BHYT/BHTN phần người lao động, đoàn phí công đoàn (nếu có cấu hình) và thuế TNCN (changed 2026-10-16, R2-02 §5 #24; GAP-047). | M | |
| BR-PAY-021 | Quy đổi net sang gross phải hỗ trợ phạm vi cấu hình được: thành phần nào được đảm bảo net (ví dụ lương cơ bản + phụ cấp cố định) và thành phần nào là gross (ví dụ thưởng, làm thêm giờ) — phạm vi cho từng nhân viên lấy từ bản ghi lương People (`net_guaranteed_components`), mặc định theo quy tắc của nhóm trả lương — và người sử dụng lao động có chịu BHXH trên phần gross-up hay không. Kết quả phải hội tụ trong phạm vi 1 VND (changed 2026-10-16, GAP-047). | M | |
| BR-PAY-022 | Tính theo tỷ lệ cho người vào/nghỉ giữa kỳ phải dùng số ngày được trả lương ÷ số ngày công chuẩn (theo BR-PAY-009) với các thành phần tính theo tỷ lệ; các thành phần không tính theo tỷ lệ tuân theo quy tắc cấu hình (đủ / không / theo ngưỡng, ví dụ hưởng đủ nếu ≥ 15 ngày). | M | |
| BR-PAY-023 | Số ngày nghỉ không lương, nghỉ không phép và đình chỉ công việc từ T&A phải giảm các thành phần tính theo tỷ lệ theo đơn vị cấu hình (ngày/giờ). | M | |
| BR-PAY-024 | Khi lương hoặc phụ cấp thay đổi trong kỳ, hệ thống phải chia kỳ thành các đoạn theo ngày hiệu lực và tính theo tỷ lệ cho từng đoạn, hiển thị các đoạn trong chi tiết phiếu lương. | M | Cũng áp dụng khi tham số pháp lý thay đổi giữa kỳ. |
| BR-PAY-025 | Tiền làm thêm giờ phải được tính từ số giờ làm thêm của T&A theo loại với hệ số cấu hình được cho từng loại, không thấp hơn mức tối thiểu luật định: ngày thường 150%, ngày nghỉ hằng tuần 200%, ngày lễ/ngày nghỉ có hưởng lương 300% (chưa kể tiền lương ngày lễ), làm việc ban đêm +30%, làm thêm giờ ban đêm thêm +20% đơn giá ngày `[verify-legal]`. | M | Bộ luật Lao động 2019 Điều 98. |
| BR-PAY-026 | Cơ sở đơn giá giờ làm thêm phải cấu hình được (chỉ lương cơ bản hoặc lương cơ bản + các phụ cấp chỉ định) và hệ thống phải tính phần được miễn thuế TNCN của tiền làm thêm giờ/làm đêm (phần chênh lệch cao hơn tiền lương giờ làm việc bình thường). | M | Thông tư 111/2013 Điều 3 `[verify-legal]`. |
| BR-PAY-027 | Hệ thống phải kiểm tra lương tháng cho giờ làm việc bình thường (đủ tháng) không thấp hơn lương tối thiểu vùng áp dụng cho nơi làm việc của nhân viên; với nhân viên trả lương theo giờ cũng kiểm tra lương tối thiểu giờ. Vi phạm là ngoại lệ (không tự động sửa). | M | Nghị định về lương tối thiểu vùng. |
| BR-PAY-028 | Phụ cấp ca, thưởng chuyên cần, phụ cấp độc hại/nguy hiểm, phụ cấp trách nhiệm, thâm niên, KPI/hoa hồng và các đầu vào khác phải được hỗ trợ dưới dạng thành phần công thức/nhập tay. | M | |
| BR-PAY-029 | Dữ liệu biến đổi phải import được qua mẫu Excel/CSV có kiểm tra hợp lệ (nhân viên, thành phần, kỳ, số tiền) và qua API; các lần import có phiên bản và có thể hoàn tác trước khi khóa. | M | |
| BR-PAY-030 | Người sử dụng lao động không trả lương cho những ngày nghỉ ốm/thai sản do BHXH chi trả trừ khi chính sách tenant có bù thêm; các ngày này bị loại khỏi số ngày được trả lương và được tính vào logic trạng thái BHXH (BR-PAY-043). | M | |
| BR-PAY-031 | Các ngày nghỉ có hưởng lương (nghỉ phép năm, nghỉ việc riêng có lương, ngày lễ) phải được trả theo cơ sở cấu hình (lương theo hợp đồng) theo Bộ luật Lao động. | M | |

### 4.3 Bảo hiểm bắt buộc & công đoàn
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-040 | Hệ thống phải tính số đóng BHXH (hưu trí & tử tuất, ốm đau & thai sản), BHYT, BHTN và TNLĐ-BNN cho người lao động và người sử dụng lao động theo tỷ lệ có hiệu lực theo ngày (§5.1). | M | |
| BR-PAY-041 | Mức lương đóng BHXH phải được xác định từ dữ liệu People theo quy tắc cấu hình được: lương hợp đồng + phụ cấp + các khoản bổ sung thường xuyên được gắn cờ tính đóng BHXH (BR-PAY-002), hoặc mức lương đóng BHXH được khai báo riêng; mức này phải ≥ lương tối thiểu vùng áp dụng và bị giới hạn trần: BHXH và BHYT ở 20 × mức tham chiếu (lương cơ sở), BHTN ở 20 × lương tối thiểu vùng `[verify-legal]`. | M | Luật BHXH 2024 Điều 31; Luật Việc làm. |
| BR-PAY-042 | Mức lương đóng BHXH của tháng phải là mức đã khai báo với VSS (không phải lương thực trả theo tỷ lệ), trừ khi nhân viên thay đổi mức đóng trong tháng, theo quy định của VSS. | M | |
| BR-PAY-043 | Hệ thống phải xác định nghĩa vụ đóng BHXH hằng tháng của từng nhân viên: không đóng BHXH/BHYT/BHTN khi nhân viên không làm việc và không hưởng lương từ 14 ngày làm việc trở lên trong tháng (BHYT xử lý theo quy định VSS), miễn đóng cho các tháng nghỉ thai sản, quy tắc cho người vào/nghỉ trong tháng theo quy định VSS `[verify-legal]`. | M | Dùng số ngày không lương/nghỉ hưởng BHXH từ T&A. |
| BR-PAY-044 | Điều kiện tham gia: BHXH bắt buộc với người lao động có hợp đồng lao động từ 1 tháng trở lên (kể cả bán thời gian có lương ≥ mức lương đóng BHXH thấp nhất), với các ngoại lệ cấu hình được (ví dụ người đang hưởng lương hưu, người nước ngoài thuộc diện miễn như di chuyển nội bộ doanh nghiệp, người lao động đã đủ tuổi nghỉ hưu); tỷ lệ/chế độ cho lao động nước ngoài cấu hình được `[verify-legal]`. | M | Điều kiện tham gia lấy từ bản ghi đăng ký BHXH trong People. |
| BR-PAY-045 | Tỷ lệ TNLĐ-BNN theo pháp nhân (chuẩn 0,5%, giảm còn 0,3% cho pháp nhân đủ điều kiện khi được chấp thuận) `[verify-legal]` phải được đọc theo ngày hiệu lực từ **quyết định tỷ lệ TNLĐ-BNN trên pháp nhân trong Core** (BR-CORE-002, IF-PAY-C-01) và kiểm tra theo các giá trị gói pháp lý cho phép; không có quyết định nghĩa là áp dụng tỷ lệ chuẩn (changed 2026-10-16, GAP-049). | M | |
| BR-PAY-046 | Kinh phí công đoàn (người sử dụng lao động, 2% quỹ lương đóng BHXH) phải được tính theo pháp nhân bất kể đã thành lập công đoàn hay chưa nếu luật yêu cầu; đoàn phí công đoàn (người lao động, ví dụ 1% lương đóng BHXH, tối đa 10% mức tham chiếu) chỉ được khấu trừ với đoàn viên được gắn cờ trong People `[verify-legal]`. | M | Luật Công đoàn 2024. |
| BR-PAY-047 | Hệ thống phải tạo dữ liệu khai báo VSS: danh sách tăng/giảm/điều chỉnh D02-LT, dữ liệu đăng ký người tham gia mới (dữ liệu TK1-TS khi áp dụng), và đối chiếu số đóng với thông báo C12-TS của VSS; xuất ra Excel/XML khớp phiên bản mẫu hiện hành của VSS (phiên bản mẫu cấu hình được) `[verify-legal]`. | M | Quyết định 595/QĐ-BHXH và các văn bản thay thế. |
| BR-PAY-048 | Điều chỉnh BHXH hồi tố (thay đổi mức lương đóng có hiệu lực hồi tố, báo tăng muộn) phải tạo các dòng điều chỉnh theo từng tháng gốc, kèm ước tính tiền lãi chậm đóng. | S | |
| BR-PAY-049 | Hệ thống phải theo dõi khoản BHXH còn nợ khi lương thực lĩnh của nhân viên không đủ để khấu trừ phần người lao động, và thu hồi trong các run tiếp theo. | S | |

### 4.4 Thuế thu nhập cá nhân
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-050 | Cá nhân cư trú: thuế TNCN từ tiền lương, tiền công = biểu thuế lũy tiến áp dụng cho thu nhập tính thuế hằng tháng sau khi trừ thu nhập miễn thuế, bảo hiểm bắt buộc phần người lao động, giảm trừ bản thân, giảm trừ người phụ thuộc (đã đăng ký và có hiệu lực trong People) và các khoản giảm trừ được phép (hưu trí tự nguyện trong mức trần, từ thiện) `[verify-legal]`. | M | |
| BR-PAY-051 | Biểu thuế TNCN (số bậc, ngưỡng, thuế suất) phải hoàn toàn dựa trên tham số; hệ thống phải hỗ trợ tối thiểu: biểu 7 bậc 5–35% (đến kỳ tính thuế 2025) và biểu 5 bậc sửa đổi 5/10/20/30/35% với ngưỡng mới; giảm trừ bản thân 11,0 triệu → 15,5 triệu VND/tháng và người phụ thuộc 4,4 triệu → 6,2 triệu VND/tháng từ kỳ tính thuế 2026 `[verify-legal]`. | M | Nghị quyết 954/2020; Nghị quyết 110/2025/UBTVQH15; Luật Thuế TNCN 2025. |
| BR-PAY-052 | Khi có nhiều lần chi trả trong cùng tháng (run hằng tháng + thưởng/ngoài kỳ), thuế TNCN phải được tính trên thu nhập tính thuế lũy kế của tháng với giảm trừ chỉ áp dụng một lần, trừ đi số thuế đã khấu trừ. Tenant có thể chọn "tính thuế theo tháng chi trả" hoặc "tính thuế theo tháng phát sinh" cho từng loại run. | M | |
| BR-PAY-053 | Người không cư trú (theo tình trạng cư trú có hiệu lực theo ngày trong People, BR-PPL-023 / IF-PAY-C-13) phải chịu thuế cố định 20% trên tổng thu nhập chịu thuế từ tiền lương, tiền công, không được giảm trừ; thay đổi tình trạng cư trú giữa năm phải được xử lý bằng báo cáo tính lại (changed 2026-10-16, GAP-048). | M | |
| BR-PAY-054 | Thu nhập vãng lai (hợp đồng dịch vụ, hợp đồng lao động < 3 tháng, người không phải nhân viên): khấu trừ 10% trên mỗi lần chi ≥ ngưỡng (ví dụ 2.000.000 VND `[verify-legal]`) trừ khi có bản cam kết 08/CK-TNCN hợp lệ (năm tính thuế, ước tính thu nhập dưới ngưỡng, đã có MST) trong hồ sơ; người nhận không cư trú 20%. | M | Thông tư 111/2013 Điều 25. |
| BR-PAY-055 | Hệ thống phải là **chủ sở hữu** của các bản cam kết 08/CK-TNCN (nhân viên và người nhận vãng lai ngoài People): ghi nhận trên các trang ESS của Payroll hoặc do quản trị viên nhập, lưu và theo dõi theo từng người nhận theo năm, cảnh báo khi số chi trả lũy kế vượt ngưỡng đã cam kết, và tự động chuyển sang khấu trừ (changed 2026-10-16, R2-02 §5 #4). | M | People chỉ hiển thị trạng thái. |
| BR-PAY-056 | Hệ thống phải tạo dữ liệu tờ khai thuế TNCN 05/KK-TNCN theo tháng hoặc quý cho từng MST pháp nhân và **cơ quan thuế quản lý**, theo định dạng XML HTKK/eTax, sử dụng **tần suất kê khai thuế TNCN** được lưu theo năm tính thuế trên pháp nhân trong Core (BR-CORE-002) `[verify-legal]` (changed 2026-10-16, GAP-049). | M | |
| BR-PAY-057 | Quyết toán thuế TNCN: hệ thống phải tính lại thuế TNCN năm của từng nhân viên cho năm tính thuế (thu nhập tính thuế năm, 12 × giảm trừ tháng hoặc theo số tháng thực tế có người phụ thuộc, biểu thuế năm), xác định số hoàn/nộp thêm, và tạo 05/QTT-TNCN kèm các phụ lục 05-1/BK-QTT-TNCN (cá nhân cư trú, lũy tiến), 05-2/BK-QTT-TNCN (khấu trừ 10%/20%), 05-3/BK-QTT-TNCN (người phụ thuộc) `[verify-legal]`. | M | Hạn nộp: ngày cuối cùng của tháng thứ 3 sau khi kết thúc năm `[verify-legal]`. |
| BR-PAY-058 | Hệ thống phải đánh giá điều kiện ủy quyền quyết toán của từng nhân viên (hợp đồng lao động ≥ 3 tháng, vẫn đang làm việc tại thời điểm quyết toán, điều kiện về nguồn thu nhập) và thu thập ủy quyền điện tử qua ESS. | M | |
| BR-PAY-059 | Hệ thống phải phát hành chứng từ khấu trừ thuế TNCN điện tử với số thứ tự liên tục theo ký hiệu của từng pháp nhân, kết xuất XML + PDF, và truyền tới cơ quan thuế khi được yêu cầu `[verify-legal]`. **Phase 1 (changed 2026-10-16, R3-02):** chữ ký điện tử thuộc P2, do đó hệ thống tạo **dữ liệu** chứng từ (XML theo định dạng của cơ quan thuế) và một **PDF chưa ký**; khách hàng ký và phát hành chứng từ qua công cụ của cơ quan thuế hoặc nhà cung cấp hóa đơn/chứng từ điện tử hiện có của mình. Ký số qua dịch vụ chữ ký điện tử của nền tảng và truyền trực tiếp sẽ có ở Phase 2. Phương án Phase 1 phải được chuyên gia tư vấn pháp lý xác nhận cho năm 2027 `[verify-legal]`. | M | Nghị định 123/2020, Nghị định 70/2025, Thông tư 32/2025. |
| BR-PAY-060 | Giảm trừ người phụ thuộc chỉ được áp dụng từ tháng người phụ thuộc được đăng ký có hiệu lực (và có MST khi bắt buộc) đến tháng kết thúc; đăng ký muộn được xem xét khi quyết toán. | M | Dữ liệu người phụ thuộc lấy từ People. |
| BR-PAY-061 | Kiểm tra MST (MST = số CCCD đối với cá nhân từ 1/7/2025); thiếu/sai MST là ngoại lệ và chặn việc phát hành chứng từ điện tử `[verify-legal]`. | M | |
| BR-PAY-062 | Thuế TNCN do người sử dụng lao động chịu thay (với hợp đồng NET) và các khoản lợi ích bằng hiện vật do người sử dụng lao động chi trả (nhà ở, học phí, phí bảo hiểm) phải được tính vào thu nhập chịu thuế theo cách xử lý cấu hình. | M | |
| BR-PAY-063 | Thu nhập chịu thuế từ đơn vị trước trong năm (với nhân sự điều chuyển trong cùng tập đoàn) phải ghi nhận được để quyết toán. | S | |
| BR-PAY-064 | Thuế TNCN cho truy lĩnh: cấu hình được — tính thuế theo tháng chi trả (mặc định) hoặc tính lại các tháng gốc kèm tờ khai điều chỉnh. | S | |
| BR-PAY-065 | **Báo cáo kiểm tra cư trú 183 ngày.** Hằng tháng và theo yêu cầu, hệ thống phải đếm số ngày có mặt tại Việt Nam của mỗi nhân viên nước ngoài hoặc biệt phái trong năm dương lịch và trong 12 tháng kể từ ngày đầu tiên đến (từ ngày đến/rời đi trong People và, khi có, dữ liệu có mặt từ T&A), gắn cờ các nhân viên có tình trạng tính ra khác với tình trạng cư trú trong People, và gửi **đề xuất** cho HR (People IN-32). Payroll không bao giờ tự thay đổi tình trạng cư trú; HR phê duyệt thay đổi trong People (BR-PPL-023), việc này kích hoạt báo cáo tính lại theo BR-PAY-053 (new 2026-10-16, GAP-048). | M | Luật Thuế TNCN; Thông tư 111/2013 `[verify-legal]` |

### 4.5 Run lương, vòng đời & kiểm soát
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-070 | Các loại run: Thường kỳ, Ngoài kỳ, Thưởng, Lương tháng 13, Thưởng Tết, Điều chỉnh, Quyết toán nghỉ việc, Tạm ứng, Thu nhập vãng lai, Điều chỉnh quyết toán thuế TNCN. Mỗi loại run xác định các thành phần được bật, phương pháp tính thuế TNCN và luồng phê duyệt. | M | |
| BR-PAY-071 | Vòng đời run: `DRAFT` → `CALCULATED` → `SUBMITTED` → `APPROVED` → `PAID` → `CLOSED`; các trạng thái phụ `REJECTED` (→ `DRAFT`), `UNLOCKED` (→ `DRAFT`), `REVERSED`. `APPROVED` thay thế trạng thái "Locked" riêng trước đây: run đã duyệt là chỉ đọc. Mở khóa một run đã duyệt cần vai trò đặc quyền, lý do và phê duyệt thứ hai, được audit đầy đủ, và trả kỳ T&A về `LOCKED → CLOSED`. (changed 2026-10-16, X-03) | M | Trạng thái thống nhất, gap-analysis §7.1 |
| BR-PAY-072 | Run phải snapshot toàn bộ dữ liệu đầu vào (People gồm **phiên bản bản ghi lương được dùng cho từng nhân viên**, T&A, phiên bản gói pháp lý, phiên bản công thức) tại thời điểm tính, để kết quả tái lập được; khi tính lại phải hiển thị những gì đã thay đổi. Mức lương mà một run đã duyệt sử dụng được đóng băng và không bao giờ bị thay đổi bởi các chỉnh sửa People sau đó; những chỉnh sửa này được xử lý dưới dạng truy lĩnh (BR-PAY-076) (changed 2026-10-16, R2-02 §5 #24). | M | |
| BR-PAY-073 | Phải tính được cho toàn bộ nhân viên, một tập con được lọc hoặc một nhân viên, và ở chế độ "mô phỏng" (không lưu trạng thái phê duyệt). | M | |
| BR-PAY-074 | Báo cáo ngoại lệ & kiểm tra hợp lệ trước khi gửi duyệt: thực lĩnh âm/bằng 0, thực lĩnh dưới tỷ lệ % cấu hình, thiếu tài khoản ngân hàng, thiếu MST, thiếu số BHXH, lương dưới mức tối thiểu vùng, chênh lệch giữa các kỳ > ngưỡng, nhân viên có trong People nhưng không có trong run (và ngược lại), kỳ chấm công chưa `CLOSED` hoặc bị stale (changed 2026-10-16, X-03), nhân viên đã nghỉ việc vẫn được trả lương. Mức chặn hay cảnh báo cấu hình được. | M | |
| BR-PAY-075 | Phê duyệt phải dùng workflow engine của `core` với luồng nhiều cấp cấu hình được theo pháp nhân và loại run; người duyệt xem được tổng hợp, chênh lệch và chi tiết trong phạm vi dữ liệu của mình. | M | |
| BR-PAY-076 | Truy lĩnh: các thay đổi hồi tố ảnh hưởng đến kỳ đã khóa (lương, phụ cấp, điều chỉnh T&A, người phụ thuộc) phải được tự động phát hiện, tính chênh lệch theo kỳ và thành phần, và chi trả trong run mở kế tiếp bằng các thành phần truy lĩnh kèm báo cáo truy lĩnh. | M | |
| BR-PAY-077 | Run quyết toán nghỉ việc phải tính: lương theo tỷ lệ, tiền phép năm chưa nghỉ, trợ cấp thôi việc (0,5 tháng lương cho mỗi năm làm việc không thuộc thời gian đóng BHTN) hoặc trợ cấp mất việc làm, bồi thường/khấu trừ vi phạm thời hạn báo trước, thu hồi khoản vay / tạm ứng lương, **khấu trừ tạm ứng tiền mặt** (`AdvanceDeductionRequested`, IF-PAY-C-40) và **hoàn trả chi phí đào tạo** (`EmployeeTerminated`, IF-PAY-C-08), số dư bồi thường thiệt hại vật chất, lương tháng 13 theo tỷ lệ (theo chính sách), thuế TNCN; và tạo bảng quyết toán `[verify-legal]` (changed 2026-10-16, GAP-050). | M | Bộ luật Lao động Điều 46–48, 62, 113. |
| BR-PAY-078 | **Payroll tính thời gian làm việc và số tiền trợ cấp thôi việc / mất việc làm** (chủ sở hữu duy nhất): cơ sở = tiền lương bình quân 6 tháng liền kề theo hợp đồng trước khi nghỉ việc; thời gian làm việc = các giai đoạn làm việc do People cung cấp trừ các giai đoạn đã được chi trả trợ cấp (People) trừ số tháng đã đóng BHTN từ lịch sử đóng BHXH của chính Payroll (kể cả các tháng trước go-live từ import số dư đầu kỳ). Kết quả (thời gian làm việc, số tháng loại trừ, cơ sở, số tiền) được công bố cho People để hiển thị (IF-PAY-P-10) (changed 2026-10-16, R2-02 §5 #29). | M | |
| BR-PAY-079 | Đảo một run đã chi trả (ví dụ chi sai ngân hàng) phải tạo một run đảo ngược có audit đầy đủ, không xóa. | S | |
| BR-PAY-080 | Việc đóng kỳ lương phải bị chặn nếu chưa hoàn tất chi trả ngân hàng, xuất GL, hoặc tạo khoản phải nộp BHXH/thuế TNCN (cấu hình được). | S | |
| BR-PAY-081 | Hệ thống phải hỗ trợ đa pháp nhân: mỗi nhóm trả lương thuộc một pháp nhân. **Làm việc đồng thời (Phase 1, mức cơ bản — R2-16):** nhân viên có quan hệ lao động tại hai pháp nhân của cùng tenant được trả lương trong nhóm trả lương của từng pháp nhân; mỗi pháp nhân khấu trừ thuế TNCN trên khoản chi của mình; quan hệ lao động chính cho mục đích `PIT_FAMILY_DEDUCTION` (People, BR-PPL-004, `people.PrimaryEmploymentChanged`, có hiệu lực theo ngày) áp dụng giảm trừ bản thân và người phụ thuộc. Bảo hiểm được tính **theo từng loại bảo hiểm cho từng quan hệ lao động** từ các bản ghi tham gia trong People, không theo một cờ duy nhất `[verify-legal]`; các bản ghi tham gia áp dụng tham số pháp lý `insurance.multi_contract_rule` cho từng loại bảo hiểm (BHXH, BHYT, BHTN, TNLĐ-BNN), với giá trị `FIRST_CONTRACT` hoặc `HIGHEST_SALARY` hoặc `EACH_EMPLOYER` (Legal Parameter Service, 06 PR-06). Mặc định: BHXH, BHTN và BHYT = `FIRST_CONTRACT` (quyết định của sponsor R3-03), TNLĐ-BNN = `EACH_EMPLOYER`; tất cả `[verify-legal]` chờ chuyên gia tư vấn lao động (changed 2026-10-16, R3-03) (quan hệ lao động chính cho mục đích `SOCIAL_INSURANCE` = hợp đồng đầu tiên); các dòng D02-LT được tạo tương ứng theo từng người sử dụng lao động. Các quan hệ lao động còn lại khấu trừ thuế TNCN không có giảm trừ gia cảnh (biểu lũy tiến, hoặc 10% khi quy định về hợp đồng yêu cầu) `[verify-legal]`. Báo cáo ngoại lệ gắn cờ người có 0 hoặc 2 quan hệ lao động chính cho một mục đích trong một tháng, và quan hệ lao động chưa có quyết định tham gia cho một loại bảo hiểm. Giới hạn làm thêm giờ liên pháp nhân thuộc Phase 2 (R2-08) (changed 2026-10-16, R2-16). | M | Quy tắc BHXH cho nhiều hợp đồng theo Luật BHXH 2024 `[verify-legal]`. |
| BR-PAY-082 | **Close hold khi gửi duyệt.** Việc gửi duyệt một run phải đặt, thông qua close-hold API của T&A (IF-PAY-C-34), một hold lên từng phiên bản Attendance Result được ghi trong snapshot của run (idempotent theo run id + phiên bản). Việc gửi bị từ chối nếu có kỳ chưa `CLOSED`, phiên bản không phải là hiện hành, hoặc T&A không khả dụng. Các run quyết toán nghỉ việc và run điều chỉnh đặt hold lên phiên bản kết quả riêng lẻ / ngoài kỳ theo cùng cách. (new 2026-10-16, X-03) | M | ADR-0019 |
| BR-PAY-083 | **Giải phóng hold.** Hệ thống phải giải phóng hold bất cứ khi nào run rời trạng thái `SUBMITTED` mà không phải do được duyệt: bị từ chối (→ `DRAFT`), quản trị viên rút lại (→ `CALCULATED`), hủy, T&A yêu cầu giải phóng (→ `CALCULATED`, đặt cờ stale, chặn phê duyệt cho đến khi tính lại trên phiên bản mới), và mở khóa (→ `UNLOCKED`). Gửi lại sẽ đặt lại hold. Mọi lần giải phóng đều nêu lý do và được audit. (new 2026-10-16, X-03) | M | |
| BR-PAY-084 | **Cổng phê duyệt và xác nhận khóa.** Chỉ được phê duyệt khi mọi kỳ chấm công trong phạm vi đều `CLOSED` với hold đang hiệu lực của run này trên phiên bản hiện hành và không có cờ stale hoặc cờ "dữ liệu đầu vào đã thay đổi" chưa xử lý. Khi phê duyệt, hệ thống phát hành `pay.PayrollRunStatusChanged(APPROVED, versions consumed)`; việc khóa của T&A không thể thất bại, nên một phê duyệt không bao giờ bị rollback. Báo cáo đối chiếu hằng đêm liệt kê các run và kỳ có trạng thái không khớp nhau (ví dụ run `APPROVED` với kỳ `CLOSED`). (new 2026-10-16, X-03) | M | Thay thế cơ chế duyệt-rồi-rollback (GAP-027) |
| BR-PAY-085 | **Gán nhóm trả lương.** Payroll phải sở hữu liên kết nhân viên → nhóm trả lương có hiệu lực theo ngày: một quy tắc mặc định theo tenant (pháp nhân + loại nhân viên, tùy chọn địa điểm) gán từng quan hệ lao động khi có `people.EmployeeHired` / `AssignmentChanged`, có thể ghi đè thủ công kèm lý do; mỗi lần gán phát hành `pay.PayGroupAssigned` (IF-PAY-P-19) cho T&A và People. Quan hệ lao động đang hoạt động mà không có nhóm trả lương là một ngoại lệ. (new 2026-10-16, R2-02 §5 #25) | M | People không có trường nhóm trả lương. |
| BR-PAY-086 | **Các sự kiện People ảnh hưởng đến lương.** Hệ thống phải tiêu thụ và áp dụng: `people.RewardApproved` → dữ liệu biến đổi của thành phần khen thưởng trong kỳ đích; `people.ContractChanged(SUSPENDED/RESUMED)` → dừng/tiếp tục trả lương và đóng BHXH trong thời gian tạm hoãn; `people.TemporaryTransferChanged` → thành phần lương bảo lưu (giữ lương cũ trong thời gian luật định, lương mới ≥ 85% và ≥ lương tối thiểu vùng) `[verify-legal]`; `people.SecondmentChanged` → pháp nhân chi trả / nhóm trả lương trong thời gian biệt phái; `people.AppraisalFinalised` / điểm KPI → đầu vào cho công thức KPI hoặc thưởng (S); `people.AdvanceDeductionRequested` → lệnh khấu trừ (tạm ứng tiền mặt) trong run kế tiếp hoặc khi quyết toán nghỉ việc; hoàn trả chi phí đào tạo từ `people.EmployeeTerminated` → khoản mục quyết toán nghỉ việc. Mỗi sự kiện được áp dụng đều truy vết được trên dòng kết quả. (new 2026-10-16, GAP-050) | M | IF-PAY-C-35..40 |
| BR-PAY-087 | **Trạng thái khóa của pháp nhân.** Hệ thống phải tính và cung cấp, theo pháp nhân và tại một ngày, kỳ lương gần nhất mà bất kỳ nhóm trả lương nào của pháp nhân có run `APPROVED`, `PAID` hoặc `CLOSED` (và có đang mở khóa hay không), để Core cảnh báo hoặc chặn các thay đổi tổ chức hồi tố và thay đổi pháp nhân (IF-PAY-P-20). (new 2026-10-16, GAP-049) | M | Core C-16, BR-CORE-015 |

### 4.6 Khoản vay, tạm ứng & khấu trừ
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-090 | Tạm ứng lương: đề nghị qua ESS hoặc quản trị viên, quy tắc hạn mức (ví dụ ≤ x% lương đã làm đến thời điểm đề nghị), phê duyệt qua workflow, chi trả trong run tạm ứng hoặc qua file ngân hàng, tự động khấu trừ trong run thường kỳ kế tiếp. | M | Bộ luật Lao động Điều 101. |
| BR-PAY-091 | Khoản vay của nhân viên: gốc, lãi (tùy chọn, lãi phẳng/dư nợ giảm dần), lịch trả nợ, thành phần khấu trừ trả góp, tạm dừng, trả nợ trước hạn, sao kê dư nợ trên phiếu lương và ESS. | S | S → P1, chỉ không tính lãi (changed 2026-10-16, D-09) |
| BR-PAY-092 | Khấu trừ để bồi thường thiệt hại phải tuân thủ mức trần luật định mỗi tháng (≤ 30% tiền lương thực trả hằng tháng sau khi trích nộp bảo hiểm và thuế TNCN) `[verify-legal]`. | M | Bộ luật Lao động Điều 102. |
| BR-PAY-093 | Thứ tự ưu tiên khấu trừ khi lương thực lĩnh không đủ (theo luật định → theo quyết định của tòa án → tạm ứng → khoản vay → khác), với việc chuyển kỳ sau các khoản chưa thu hồi được. | M | |
| BR-PAY-094 | Các khoản hoàn ứng chi phí đã duyệt từ People phải được chi trả qua bảng lương dưới dạng thành phần không chịu thuế (hoặc file chi trả riêng) có tham chiếu đến ID đề nghị. | S | |

### 4.7 Phiếu lương & ESS
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-100 | Mẫu phiếu lương cấu hình được theo nhóm trả lương (song ngữ VN/EN), hiển thị thu nhập, phụ cấp, chi tiết làm thêm giờ, BHXH/BHYT/BHTN, KPCĐ, thuế TNCN, khấu trừ, thực lĩnh, số ngày được trả lương, số dư phép, tổng lũy kế từ đầu năm, chi tiết truy lĩnh, dư nợ vay. | M | Bộ luật Lao động Điều 95.3 yêu cầu thông báo bảng kê trả lương mỗi lần trả lương. |
| BR-PAY-101 | Phiếu lương được phát hành lên ESS (responsive web ở Phase 1; ứng dụng mobile ở Phase 2 — changed 2026-10-16, OD-9/X-04) vào ngày/giờ công bố cấu hình sau khi được duyệt; nhân viên xem được lịch sử và tải PDF. | M | |
| BR-PAY-102 | Gửi phiếu lương qua e-mail dưới dạng **PDF có mật khẩu** (quyết định của sponsor R2-17; ngoại lệ của BR-CORE-074), có theo dõi trạng thái gửi và gửi lại. **Quy tắc mật khẩu:** (a) nhân viên tự đặt mật khẩu phiếu lương trên ESS (xác thực tăng cường), tối thiểu 8 ký tự, không suy ra được từ ngày sinh, số giấy tờ tùy thân/MST hoặc mã nhân viên; (b) mật khẩu **không bao giờ được gửi** qua e-mail, SMS hoặc Zalo, dù trong cùng tin nhắn hay tin nhắn khác; HR không xem được; (c) PDF được mã hóa bằng AES-256; (d) **không có mật khẩu mặc định, ban đầu hoặc suy ra** (ngày sinh + chữ số giấy tờ tùy thân bị từ chối vì quá yếu theo PDPL); cho đến khi nhân viên đặt mật khẩu, e-mail chỉ chứa thông báo kèm deep link ESS an toàn và **không có tệp đính kèm**; (e) chỉ đặt lại trên ESS với xác thực tăng cường; (f) mọi lần gửi, gửi lại và đổi mật khẩu đều được audit. Tenant có thể tắt đính kèm e-mail (chỉ gửi deep link) (changed 2026-10-16, R2-17). **Nhân viên không có địa chỉ e-mail** (changed 2026-10-16, R3-05): HR in phiếu lương PDF (mẫu BR-PAY-100, đợt in không mã hóa theo nhóm trả lương, có audit) và trao cho nhân viên mỗi lần trả lương (Bộ luật Lao động Điều 95(3)); không cấp mã kích hoạt, và các nhân viên này không có quyền truy cập ESS ở Phase 1. Run hiển thị danh sách "cần in" gồm các nhân viên không có e-mail. | M | E-mail qua trung tâm thông báo của Core (P-14) và các delivery adapter của saas-platform (R2-02 §5 #14). PDPL `[verify-legal]`. |
| BR-PAY-103 | Nhân viên có thể gửi câu hỏi về phiếu lương từ ESS, được chuyển cho quản trị viên tính lương kèm theo dõi SLA. | C | |
| BR-PAY-104 | Các trang ESS của Payroll (trong ESS shell của saas-platform) phải hiển thị phiếu lương, chứng từ thuế TNCN, tổng hợp thu nhập năm, giảm trừ người phụ thuộc đã áp dụng, đặt/đặt lại mật khẩu phiếu lương (BR-PAY-102), và cho phép nộp 08/CK-TNCN và ủy quyền quyết toán, do Payroll sở hữu (changed 2026-10-16, R2-02 §5 #4, #17; R2-17). | M | |

### 4.8 Chi trả, kế toán & phân bổ chi phí
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-110 | File chi lương ngân hàng phải được tạo theo từng tài khoản ngân hàng chi trả theo định dạng riêng của từng ngân hàng (VCB, BIDV, Techcombank, VietinBank, ACB, MB, VPBank, Sacombank) qua các mẫu file cấu hình được (CSV/XLSX/fixed-width/XML), bao gồm tách cùng ngân hàng và liên ngân hàng (Napas/Citad). | M | Định dạng theo từng ngân hàng; công cụ thiết kế mẫu giảm công phát triển cho mỗi ngân hàng. |
| BR-PAY-111 | Hỗ trợ chia lương thực lĩnh vào tối đa 2 tài khoản và trả bằng tiền mặt (kèm danh sách chi tiền mặt), và chi trả cho người nhận vãng lai. | S | |
| BR-PAY-112 | Việc tạo file chi trả yêu cầu run ở trạng thái `APPROVED` (changed 2026-10-16, X-03) và phê duyệt của Tài chính; hash của file được lưu; việc tạo lại được audit. Trạng thái chi trả (đã gửi, đã chi, bị từ chối theo từng dòng) có thể import ngược lại. | M | |
| BR-PAY-113 | Xuất bút toán GL: ánh xạ cấu hình được mỗi thành phần với tài khoản nợ/có (ví dụ 622/627/641/642 với 334, 3383, 3384, 3386, 3382, 3335, 141) kèm các phân đoạn (pháp nhân, trung tâm chi phí, dự án, phòng ban), ở định dạng CSV/Excel và API cho ERP (MISA, Fast, SAP B1, Oracle) `[verify-legal]` (hệ thống tài khoản theo Thông tư 200/2014 hoặc văn bản thay thế, Thông tư 133/2016). | M | |
| BR-PAY-114 | Phân bổ chi phí: chi phí nhân viên (gross + đóng góp của người sử dụng lao động + KPCĐ) phải được phân bổ vào trung tâm chi phí/dự án theo gán mặc định từ People/Core hoặc theo tỷ lệ %/theo thời gian từ bảng công dự án của T&A. | M | Giờ dự án từ T&A (`S`). |
| BR-PAY-115 | Trích trước: trích trước hằng tháng dự phòng lương tháng 13 / thưởng và nghĩa vụ phép chưa nghỉ. | C | |

### 4.9 Bảo mật, audit & dữ liệu
| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-PAY-120 | Bảo mật theo phạm vi dữ liệu: dữ liệu lương chỉ hiển thị cho người dùng có vai trò tính lương cho pháp nhân/nhóm trả lương cụ thể; các nhóm trả lương "bảo mật" (lãnh đạo) chỉ giới hạn cho người dùng được chỉ định. | M | RBAC từ `core`. |
| BR-PAY-121 | Mọi thay đổi đối với thành phần, công thức, tham số, dữ liệu đầu vào tính lương của nhân viên và trạng thái run phải được audit (ai, khi nào, trước/sau, lý do). | M | Audit store trong `core`. |
| BR-PAY-122 | Import số dư đầu kỳ (các mẫu Excel dùng chung trên import engine của Core, không chuyển đổi dữ liệu từ Oracle cũ): theo từng nhân viên và pháp nhân, thu nhập chịu thuế lũy kế từ đầu năm tính thuế hiện hành, thuế TNCN đã khấu trừ, mức lương đóng và số đóng BHXH/BHYT/BHTN; lịch sử đóng BHXH trước go-live (tháng, mức đóng, đơn vị) vào `si_contribution_opening` — Payroll sở hữu toàn bộ lịch sử BHXH (R2-02 §5 #20); 6–12 tháng lịch sử lương phục vụ trợ cấp thôi việc và bình quân 6 tháng; khoản vay/tạm ứng còn dư nợ. Báo cáo kiểm tra hợp lệ, chạy thử (dry-run) và báo cáo đối chiếu với bảng lương gần nhất của tenant, được tenant ký xác nhận. (changed 2026-10-16, D-09) | M | Cần cho go-live giữa năm và quyết toán thuế TNCN cuối năm. |
| BR-PAY-123 | Lưu trữ hồ sơ lương và tờ khai trong thời hạn luật định (ví dụ ≥ 10 năm với chứng từ kế toán) `[verify-legal]`. | M | Luật Kế toán. |
| BR-PAY-124 | API ước tính chi phí lương cho offer tuyển dụng và hoạch định định biên (đầu vào: số tiền gross/net, vùng, tình trạng cư trú, số người phụ thuộc; đầu ra: gross, net, chi phí người sử dụng lao động). | S | |
| BR-PAY-125 | Công cụ tính lương cho HR/ESS (mô phỏng gross↔net) theo tham số hiện hành. | C | |

---

## 5. Quy định tuân thủ của Việt Nam

Mọi giá trị đều `[verify-legal]`; tất cả được mô hình hóa thành tham số có hiệu lực theo ngày (BR-PAY-010).

### 5.1 Tỷ lệ đóng bảo hiểm bắt buộc (người lao động Việt Nam)
| Quỹ | Người sử dụng lao động | Người lao động | Cơ sở / mức trần | Căn cứ pháp lý |
|---|---|---|---|---|
| BHXH – Hưu trí & tử tuất | 14% | 8% | Mức lương đóng BHXH, trần 20 × mức tham chiếu | Luật BHXH 2024 (Luật 41/2024/QH15, hiệu lực 1/7/2025) |
| BHXH – Ốm đau & thai sản | 3% | – | như trên | Luật BHXH 2024 |
| BHXH – Tai nạn lao động & bệnh nghề nghiệp | 0,5% (0,3% nếu được chấp thuận) | – | như trên | Luật An toàn, vệ sinh lao động 2015, Nghị định 58/2020 |
| Bảo hiểm y tế (BHYT) | 3% | 1,5% | trần 20 × lương cơ sở / mức tham chiếu | Luật BHYT (sửa đổi 2024) |
| Bảo hiểm thất nghiệp (BHTN) | 1% | 1% | trần 20 × lương tối thiểu vùng | Luật Việc làm 2025 (hiệu lực 1/1/2026) / 2013 |
| **Tổng** | **21,5%** | **10,5%** | | |
| Kinh phí công đoàn | 2% | – | Mức lương đóng BHXH | Luật Công đoàn 2024 |
| Đoàn phí công đoàn | – | 1% (tối đa 10% mức tham chiếu) | Mức lương đóng BHXH | Điều lệ Công đoàn |

Người lao động nước ngoài: BHXH (hưu trí, ốm đau/thai sản, TNLĐ-BNN) và BHYT áp dụng cho người nước ngoài đủ điều kiện có giấy phép lao động và hợp đồng ≥ 12 tháng (trường hợp miễn: di chuyển nội bộ doanh nghiệp, đã đủ tuổi nghỉ hưu); BHTN không áp dụng. `[verify-legal]`

### 5.2 Giá trị tham chiếu
| Tham số | Giá trị ví dụ | Hiệu lực | Căn cứ pháp lý |
|---|---|---|---|
| Lương cơ sở / mức tham chiếu (lương cơ sở / mức tham chiếu) | 2.340.000 VND | 1/7/2024 | Nghị định 73/2024; Luật BHXH 2024 Điều 7 |
| Lương tối thiểu vùng – Vùng I / II / III / IV | 4.960.000 / 4.410.000 / 3.860.000 / 3.450.000 VND/tháng | 1/7/2024 | Nghị định 74/2024 |
| Lương tối thiểu vùng – Vùng I / II / III / IV | 5.310.000 / 4.730.000 / 4.140.000 / 3.700.000 VND/tháng | 1/1/2026 | Nghị định 293/2025 (danh mục vùng được ánh xạ lại sau sáp nhập tỉnh năm 2025) |
| Mức đóng BHXH/BHYT tối đa | 20 × mức tham chiếu (ví dụ 46.800.000 VND) | | |
| Mức đóng BHTN tối đa | 20 × lương tối thiểu vùng (ví dụ Vùng I 106.200.000 VND từ 1/1/2026) | | |

Vùng được xác định theo **nơi làm việc** (đơn vị hành chính) của nhân viên từ Core/People, không theo địa chỉ đăng ký của pháp nhân.

### 5.3 Thuế TNCN
| Quy định | Ví dụ | Căn cứ pháp lý |
|---|---|---|
| Cư trú | ≥ 183 ngày tại VN trong năm dương lịch / 12 tháng liên tục, hoặc có nơi ở thường xuyên | Luật Thuế TNCN; Thông tư 111/2013 |
| Giảm trừ bản thân | 11.000.000 VND/tháng (đến 2025) → 15.500.000 VND/tháng (từ kỳ tính thuế 2026) | NQ 954/2020; NQ 110/2025/UBTVQH15 |
| Giảm trừ người phụ thuộc | 4.400.000 → 6.200.000 VND/tháng/người phụ thuộc | như trên |
| Biểu thuế cư trú (cũ, 7 bậc) | ≤5tr 5%; 5–10tr 10%; 10–18tr 15%; 18–32tr 20%; 32–52tr 25%; 52–80tr 30%; >80tr 35% | Luật Thuế TNCN 2007 (sửa đổi) |
| Biểu thuế cư trú (mới, 5 bậc) | ≤10tr 5%; 10–30tr 10%; 30–60tr 20%; 60–100tr 30%; >100tr 35% — kỳ áp dụng theo quy định chuyển tiếp | Luật Thuế TNCN 2025 |
| Không cư trú | 20% cố định trên tổng thu nhập từ tiền lương, tiền công | Luật Thuế TNCN |
| Vãng lai / hợp đồng ngắn hạn | 10% trên các khoản chi ≥ 2.000.000 VND (trừ khi có 08/CK-TNCN); ngưỡng có thể thay đổi theo Luật Thuế TNCN 2025 | Thông tư 111/2013 Điều 25 |
| Khoản miễn thuế | Phần chênh lệch làm thêm giờ/làm đêm; ăn giữa ca trong mức trần; đồng phục trong mức trần; điện thoại/văn phòng phẩm theo quy chế; chi phí chuyển vùng một lần cho người nước ngoài; trợ cấp do BHXH chi trả | Thông tư 111/2013 Điều 2–3; Thông tư 92/2015 |
| Lợi ích nhà ở | Chịu thuế tối đa 15% tổng thu nhập chịu thuế (chưa gồm nhà ở) | Thông tư 111/2013 |
| Kê khai | 05/KK-TNCN theo tháng (hạn ngày 20 tháng sau) hoặc theo quý (ngày cuối cùng của tháng đầu quý sau) | Luật Quản lý thuế; Nghị định 126/2020 |
| Quyết toán | 05/QTT-TNCN + 05-1, 05-2, 05-3/BK-QTT-TNCN; hạn ngày cuối cùng của tháng thứ 3 sau khi kết thúc năm | Thông tư 80/2021 |
| Chứng từ điện tử | Chứng từ khấu trừ thuế điện tử, định dạng theo Nghị định 123/2020 được sửa đổi bởi Nghị định 70/2025 | Nghị định 70/2025; Thông tư 32/2025 |
| Mã số thuế cá nhân | Số Căn cước công dân (CCCD) được dùng làm MST từ 1/7/2025 | Thông tư 86/2024 |

### 5.4 Quy định của Bộ luật Lao động liên quan đến tiền lương
| Quy định | Tóm tắt | Căn cứ pháp lý |
|---|---|---|
| Hệ số làm thêm giờ | ≥150% ngày thường, ≥200% ngày nghỉ hằng tuần, ≥300% ngày lễ (chưa kể tiền lương ngày lễ); làm đêm +30%; làm thêm giờ ban đêm +20% đơn giá ngày | Bộ luật Lao động 2019 Điều 98; Nghị định 145/2020 Điều 55–57 |
| Trả lương | Trả đúng hạn, bằng tiền mặt hoặc chuyển khoản; người sử dụng lao động chịu phí chuyển khoản; trả chậm ≥ 15 ngày → phải đền bù tiền lãi | Điều 94–97 |
| Bảng kê trả lương | Người sử dụng lao động phải thông báo bảng kê trả lương (phiếu lương) mỗi lần trả lương | Điều 95.3 |
| Khấu trừ | Chỉ để bồi thường thiệt hại, ≤ 30% tiền lương tháng sau khi trích nộp bảo hiểm bắt buộc và thuế TNCN | Điều 102 |
| Tạm ứng | Tạm ứng theo thỏa thuận; tạm ứng 30 ngày lương khi nghỉ để thực hiện nghĩa vụ quân sự v.v. | Điều 101 |
| Quyết toán nghỉ việc | Trong vòng 14 ngày làm việc sau khi chấm dứt hợp đồng (tối đa 30 ngày trong các trường hợp được liệt kê) | Điều 48 |
| Trợ cấp thôi việc / mất việc làm | Thôi việc 0,5 tháng/năm; mất việc làm 1 tháng/năm (tối thiểu 2 tháng); cơ sở = tiền lương bình quân 6 tháng liền kề | Điều 46–47 |
| Thanh toán phép năm | Ngày phép chưa nghỉ được thanh toán khi nghỉ việc hoặc khi không sử dụng | Điều 113 |
| Hồ sơ | Lưu sổ lương, cung cấp dữ liệu tiền lương cho công đoàn khi được yêu cầu | Nghị định 145/2020 |

### 5.5 Quản lý BHXH
| Quy định | Tóm tắt | Căn cứ pháp lý |
|---|---|---|
| Miễn đóng trong tháng | Không đóng BHXH khi người lao động không làm việc & không hưởng lương từ 14 ngày làm việc trở lên trong tháng; các tháng nghỉ thai sản được miễn đóng (vẫn được tính thời gian) | Luật BHXH 2024; hướng dẫn của VSS |
| Hạn đăng ký | Đăng ký trong vòng 30 ngày kể từ ngày bắt đầu hợp đồng | Luật BHXH 2024 |
| Hạn đóng | Hằng tháng: chậm nhất ngày cuối cùng của tháng; áp dụng tiền lãi chậm đóng | Luật BHXH 2024 Điều 34, 40 |
| Biểu mẫu | D02-LT (danh sách tăng/giảm/điều chỉnh), TK1-TS (đăng ký người tham gia), C12-TS (thông báo hằng tháng của VSS); phiên bản mẫu do VSS ban hành | Quyết định 595/QĐ-BHXH, Quyết định 490/QĐ-BHXH; các văn bản thay thế theo Luật BHXH 2024 |
| Hợp đồng ngắn hạn | Hợp đồng từ 1 tháng → BHXH bắt buộc | Luật BHXH 2024 Điều 2 |

### 5.6 Bảo vệ dữ liệu
| Quy định | Tóm tắt | Căn cứ pháp lý |
|---|---|---|
| Dữ liệu lương, thuế, ngân hàng, BHXH là dữ liệu cá nhân (tài chính ⇒ nhạy cảm) | Sự đồng ý / căn cứ pháp lý, ghi log truy cập, thông báo vi phạm | Nghị định 13/2023; Luật Bảo vệ dữ liệu cá nhân 2025 (hiệu lực 1/1/2026) |

---

## 6. Thực thể nghiệp vụ chính (schema `pay`)

| Thực thể | Mục đích | Thuộc tính chính |
|---|---|---|
| `legal_param_set` / `legal_param_value` | **Snapshot theo từng run** của phiên bản gói pháp lý saas-platform và các giá trị đã dùng (bản gốc trong Legal Parameter Service) (changed 2026-10-16, R2-02 §5 #1) | run_id, phiên bản gói, phạm vi (toàn cục/ghi đè theo tenant/pháp nhân), các khóa và giá trị đã dùng, thời điểm snapshot |
| `pit_schedule` / `pit_bracket` | Các phiên bản biểu thuế TNCN lũy tiến | schedule_id, kỳ hiệu lực, cận dưới/cận trên, thuế suất, số trừ theo phương pháp rút gọn |
| `salary_component` | Danh mục thành phần lương | mã, tên, loại, phương pháp, các cờ (thuế TNCN, BHXH, KPCĐ, tính theo tỷ lệ, gross/net), tham chiếu tham số mức trần, tham chiếu ánh xạ GL, phiên bản |
| `formula` / `formula_version` | Định nghĩa biểu thức | biểu thức, phụ thuộc, trạng thái, người tạo, phiên bản, ngày hiệu lực |
| `salary_structure` / `structure_line` | Bộ thành phần theo ngạch bậc/nhóm | structure_id, pháp nhân, ngạch bậc, nhóm, thành phần, giá trị mặc định, ưu tiên |
| `pay_group` | Tập nhân viên & lịch tính lương | pháp nhân, tần suất, ngày chốt, ngày chốt làm thêm giờ, ngày trả lương, phương pháp ngày công chuẩn, tài khoản ngân hàng chi trả (tham chiếu `legal_entity_bank_account` của Core), luồng phê duyệt, quy tắc gán mặc định (changed 2026-10-16, R2-02 §5 #5b, #25) |
| `pay_group_assignment` | Liên kết nhân viên → nhóm trả lương có hiệu lực theo ngày (new 2026-10-16, R2-02 §5 #25) | employment_id, pay_group_id, valid_from/to, nguồn (quy tắc/thủ công), lý do |
| `pay_period` | Kỳ theo nhóm trả lương | ngày bắt đầu, ngày kết thúc, ngày trả lương, trạng thái kỳ T&A (CLOSED/LOCKED) và phiên bản kết quả hiện hành, trạng thái (OPEN/INPUT_CUTOFF/CLOSED) (changed 2026-10-16, X-03) |
| `employee_pay_profile` | Thiết lập riêng cho tính lương của nhân viên; thu nhập thỏa thuận, tình trạng cư trú, cờ KPCĐ, ngân hàng và MST là bản sao chỉ đọc được cache từ dữ liệu People (People là chủ sở hữu) (changed 2026-10-16, R2-02 §5 #24; GAP-048) | nhân viên, cấu trúc lương, cache quan hệ lao động chính theo mục đích và việc tham gia bảo hiểm theo loại (R2-16), cache: cơ sở gross/net và lương net thỏa thuận, mức lương đóng BHXH, tình trạng cư trú, đoàn viên công đoàn, tham chiếu tài khoản ngân hàng, MST |
| `variable_input` / `input_batch` | Dữ liệu đầu vào theo kỳ và các lần import | nhân viên, thành phần, kỳ, số tiền/số lượng, nguồn, lô, trạng thái |
| `payroll_run` | Header của run | loại, nhóm trả lương, kỳ, trạng thái, tham chiếu snapshot, phiên bản phê duyệt, tổng số, các hold chấm công (kỳ, phiên bản kết quả, trạng thái hold), cờ stale, thông tin mở khóa (changed 2026-10-16, X-03) |
| `payroll_result` / `result_line` | Kết quả theo nhân viên và các dòng theo thành phần | run, nhân viên, đoạn, thành phần, số lượng, đơn giá, số tiền, chịu thuế, cơ sở BHXH, retro_of_period |
| `input_snapshot` | Dữ liệu đầu vào được đóng băng để tái lập | run, nhân viên, hash payload, phiên bản nguồn |
| `si_contribution` | BHXH/BHYT/BHTN/TNLĐ-BNN/KPCĐ hằng tháng theo nhân viên | kỳ, mức đóng, tỷ lệ, số tiền NLĐ/NSDLĐ, trạng thái (đóng/miễn), tham chiếu tờ khai |
| `si_contribution_opening` | Lịch sử đóng BHXH trước go-live (new 2026-10-16, R2-02 §5 #20) | nhân viên, tháng, mức đóng, đơn vị BHXH, cờ quỹ (BHXH/BHTN), nguồn (import số dư đầu kỳ / sổ BHXH / bảng kê của VSS), lô import |
| `residency_check` | Kết quả kiểm tra 183 ngày và đề xuất cho HR (new 2026-10-16, GAP-048) | cá nhân, năm tính thuế, số ngày tại VN, khoảng 12 tháng, tình trạng tính ra, tình trạng trong People, trạng thái đề xuất |
| `si_declaration` / `si_declaration_line` | D02-LT và các file VSS khác | pháp nhân, kỳ, loại (tăng/giảm/điều chỉnh), các dòng, file, trạng thái, đối chiếu VSS |
| `pit_withholding` | Thuế TNCN theo nhân viên theo tháng/run | thu nhập, miễn thuế, giảm trừ, thu nhập tính thuế, thuế, phương pháp (lũy tiến/10%/20%) |
| `pit_declaration` | Đầu ra 05/KK và 05/QTT | MST pháp nhân, kỳ, loại, XML, trạng thái |
| `pit_commitment` | 08/CK-TNCN | người nhận, năm, thu nhập ước tính, hợp lệ, tệp đính kèm |
| `pit_finalisation_auth` | Ủy quyền quyết toán | nhân viên, năm, đủ điều kiện, lý do, ngày ký |
| `pit_certificate` | Chứng từ khấu trừ điện tử | ký hiệu, số, người nhận, kỳ, số tiền, XML, chữ ký, trạng thái |
| `casual_payee` / `casual_payment` | Người nhận không phải nhân viên và các khoản chi | số giấy tờ, MST, tình trạng cư trú, số tiền, khấu trừ, ngân hàng |
| `loan` / `loan_schedule` | Khoản vay và tạm ứng | loại, gốc, lãi, lịch trả, dư nợ, trạng thái |
| `deduction_order` | Khấu trừ định kỳ, theo quyết định của tòa án, bồi thường thiệt hại | loại, số tiền/%, mức trần, ưu tiên, kỳ |
| `retro_item` | Các khoản chênh lệch truy lĩnh được phát hiện | nhân viên, kỳ gốc, thành phần, chênh lệch, run đích |
| `final_settlement` | Quyết toán nghỉ việc | nhân viên, tham chiếu nghỉ việc, các khoản mục, bảng quyết toán, hạn, trạng thái |
| `payslip` / `payslip_delivery` | Phiếu lương đã công bố và log gửi | run, nhân viên, tham chiếu PDF, thời điểm công bố, trạng thái e-mail (đính kèm / chỉ link), thời điểm xem |
| `payslip_password` | Mật khẩu PDF phiếu lương do nhân viên đặt (R2-17) | nhân viên, bí mật mật khẩu (mã hóa, không bao giờ hiển thị), thời điểm đặt, số lần đặt lại; thay đổi được audit (new 2026-10-16, R2-17) |
| `bank_file_template` / `bank_payment_file` | Định dạng ngân hàng và các file đã tạo | ngân hàng, định dạng, trường; run, hash file, các dòng, trạng thái |
| `gl_mapping` / `gl_journal` | Ánh xạ kế toán và các lô xuất | thành phần → tài khoản, phân đoạn; các dòng bút toán, trạng thái xuất |
| `cost_allocation` | Quy tắc và kết quả phân bổ | nhân viên, trung tâm chi phí/dự án, %, nguồn, kỳ |

---

## 7. Giao diện liên module

Chú giải thời điểm: **RT** = thời gian thực/sự kiện khi thay đổi; **D** = đồng bộ hằng ngày; **P-cut** = tại thời điểm chốt lương (snapshot khi tính); **P-close** = khi kỳ T&A ở trạng thái `CLOSED` và phiên bản Attendance Result đã được phát hành (thay thế "P-lock" trước đây; phiên bản được hold khi gửi duyệt run và được T&A khóa khi run được duyệt — changed 2026-10-16, X-03); **On-demand** = payroll gọi API khi cần.
Mọi dữ liệu tiêu thụ phải **có hiệu lực theo ngày** (payroll tính các kỳ quá khứ cho truy lĩnh) và **truy vấn được theo thời điểm** (giá trị tại ngày X như được biết tại thời điểm Y là mong muốn cho mục đích audit).

### 7.1 Tiêu thụ
| ID | Nội dung | Từ module | Thời điểm | Sử dụng cho / ghi chú |
|---|---|---|---|---|
| IF-PAY-C-01 | Pháp nhân: tên, mã số thuế (MST), địa chỉ đăng ký, mã đơn vị BHXH (mã đơn vị BHXH), cơ quan thuế, tần suất kê khai thuế TNCN (tháng/quý, theo năm tính thuế), quyết định tỷ lệ TNLĐ-BNN (tỷ lệ, tham chiếu quyết định, thời hạn hiệu lực), tài khoản ngân hàng chi trả | core | RT khi thay đổi (`core.LegalEntityChanged`); đọc tại P-cut | Tờ khai, file ngân hàng, tỷ lệ. Các trường được lưu trên pháp nhân trong Core (BR-CORE-002) (changed 2026-10-16, GAP-049; R2-02 §5 #5b). |
| IF-PAY-C-02 | Địa điểm làm việc với đơn vị hành chính → **vùng** lương tối thiểu (I–IV), có hiệu lực theo ngày (ánh xạ sau sáp nhập tỉnh năm 2025) | core | RT; đọc tại P-cut | Kiểm tra lương tối thiểu vùng, trần BHTN, mức sàn BHXH. |
| IF-PAY-C-03 | Đơn vị tổ chức, trung tâm chi phí, dự án, mã phân đoạn GL, có hiệu lực theo ngày | core | RT | Phân bổ chi phí, xuất GL, báo cáo. |
| IF-PAY-C-04 | Workflow engine: luồng phê duyệt, phiên bản, quyết định, ủy quyền | core | On-demand | Phê duyệt run, mở khóa, khoản vay/tạm ứng, ghi đè tham số. |
| IF-PAY-C-05 | Người dùng, vai trò, phạm vi dữ liệu (pháp nhân / nhóm trả lương / nhóm bảo mật), dịch vụ audit log, thông báo (trong ứng dụng) | core | RT | Bảo mật BR-PAY-120/121, thông báo phiếu lương. |
| IF-PAY-C-06 | Lịch ngày lễ theo pháp nhân / địa điểm (API lịch `att` ATT-P-16, `att.HolidayCalendarChanged`) — T&A là chủ sở hữu duy nhất; tenant có Payroll nhưng không có T&A dùng mẫu ngày lễ quốc gia trong gói pháp lý (R2-18) | time-attendance | Hằng năm + RT | Phương pháp ngày công chuẩn (b), tiền lương ngày lễ (changed 2026-10-16, R2-02 §5 #2; R2-18). |
| IF-PAY-C-07 | Hồ sơ nhân viên gốc: mã nhân viên, họ tên, ngày sinh, giới tính, quốc tịch, CCCD/hộ chiếu (MST), e-mail cá nhân, trạng thái nhân viên, ngày vào làm, ngày tính thâm niên | people | RT/D; đọc tại P-cut | Tập nhân viên, địa chỉ e-mail nhận phiếu lương, thuế TNCN, BHXH. Ngày sinh/số giấy tờ **không** được dùng làm mật khẩu phiếu lương (R2-17). |
| IF-PAY-C-08 | Nghỉ việc (`people.TerminationScheduled`, `people.EmployeeTerminated`): ngày chấm dứt, ngày làm việc cuối cùng, lý do (từ chức, sa thải, cắt giảm, hết hạn hợp đồng), căn cứ pháp lý, việc tuân thủ thời hạn báo trước, điều kiện hưởng trợ cấp thôi việc / mất việc làm, các giai đoạn làm việc và các giai đoạn đã được chi trả, hoàn trả chi phí đào tạo, tạm ứng tiền mặt còn tồn, số dư bồi thường thiệt hại vật chất, trạng thái hoàn trả tài sản | people | RT khi phê duyệt nghỉ việc; ngày chấm dứt | Quyết toán nghỉ việc (P5), thời gian làm việc tính trợ cấp thôi việc/mất việc làm do Payroll tính (BR-PAY-078), báo giảm D02-LT. Cần có trước hạn quyết toán ít nhất 3 ngày làm việc (changed 2026-10-16, R2-02 §5 #29; GAP-050). |
| IF-PAY-C-09 | Phân công: pháp nhân, phòng ban, vị trí, ngạch/bậc, nhóm/loại nhân viên, địa điểm làm việc, tỷ lệ phân bổ mặc định theo trung tâm chi phí/dự án, phân công đồng thời, cờ quan hệ lao động chính, ngày hiệu lực. Nhóm trả lương **không** phải là trường của People: Payroll gán nhóm trả lương (BR-PAY-085) | people | RT; đọc tại P-cut | Xác định cấu trúc lương, quy tắc nhóm trả lương, vùng, phân bổ chi phí, đa pháp nhân (changed 2026-10-16, R2-02 §5 #25; R2-16). |
| IF-PAY-C-10 | Hợp đồng: loại (không xác định thời hạn, xác định thời hạn, thời vụ < 1 tháng, thử việc, dịch vụ/cộng tác viên, thực tập), ngày bắt đầu/kết thúc, tỷ lệ % lương thử việc, loại thời gian làm việc (toàn thời gian/bán thời gian, theo giờ) | people | RT | Điều kiện tham gia BHXH, phương pháp tính thuế TNCN (lũy tiến hay 10%), tính theo tỷ lệ. Cơ sở GROSS/NET đã chuyển sang bản ghi lương (IF-PAY-C-11) (changed 2026-10-16, GAP-047). |
| IF-PAY-C-11 | Thu nhập thỏa thuận (`people.SalaryChanged`; People là hệ thống gốc): id + phiên bản bản ghi lương, **cơ sở trả lương GROSS/NET, số tiền net thỏa thuận, phạm vi thành phần được đảm bảo net**, lương cơ bản, tiền tệ, ngạch/bậc lương, phụ cấp cố định (mã thành phần, số tiền, ngày hiệu lực), mức lương đóng BHXH khai báo (nếu khai báo riêng), tham chiếu quyết định / phụ lục, lịch sử thay đổi lương kèm lý do | people | RT; thay đổi có ngày hiệu lực trong quá khứ kích hoạt truy lĩnh | Thu nhập, quy đổi net sang gross (BR-PAY-020/021), cơ sở BHXH, truy lĩnh (BR-PAY-076); phiên bản được dùng được đóng băng theo từng run (BR-PAY-072). Mã thành phần phải tham chiếu danh mục của payroll (IF-PAY-P-01) (changed 2026-10-16, R2-02 §5 #24; GAP-047). |
| IF-PAY-C-12 | Người phụ thuộc cho thuế TNCN: họ tên, quan hệ, ngày sinh, số giấy tờ/MST, thời gian đăng ký có hiệu lực từ/đến, trạng thái đăng ký với cơ quan thuế | people | RT; đọc tại P-cut | Giảm trừ người phụ thuộc (BR-PAY-060), phụ lục 05-3/BK-QTT. |
| IF-PAY-C-13 | Hồ sơ thuế (`people.PersonalDataChanged`): tình trạng cư trú thuế có hiệu lực theo ngày (cư trú/không cư trú) và căn cứ, ngày đến và rời VN, MST cá nhân | people | RT | Phương pháp tính thuế TNCN (BR-PAY-053), báo cáo kiểm tra 183 ngày (BR-PAY-065). 08/CK-TNCN **không** được tiêu thụ: Payroll sở hữu nó (BR-PAY-055) (changed 2026-10-16, GAP-048; R2-02 §5 #4). |
| IF-PAY-C-14 | Tài khoản ngân hàng: ngân hàng, chi nhánh, số tài khoản, tên chủ tài khoản, tỷ lệ chia chính/phụ, ngày hiệu lực, trạng thái xác minh | people | RT; đọc tại P-cut và khi tạo file ngân hàng | File ngân hàng; thiếu → ngoại lệ. |
| IF-PAY-C-15 | Đăng ký BHXH: số BHXH (mã số BHXH), trạng thái đăng ký, lý do miễn (đang hưởng lương hưu, người nước ngoài được miễn), nơi đăng ký KCB ban đầu (KCB ban đầu), ngày bắt đầu, việc lưu giữ sổ BHXH, và **việc tham gia theo từng loại bảo hiểm (BHXH / BHYT / BHTN / TNLĐ-BNN) cho từng quan hệ lao động** có hiệu lực theo ngày (BR-PPL-004, R2-16) `[verify-legal]` | people | RT | Điều kiện tham gia BR-PAY-044, D02-LT/TK1-TS. Lịch sử đóng BHXH (kể cả tại đơn vị trước / trước go-live) thuộc Payroll, được nạp qua import số dư đầu kỳ (changed 2026-10-16, R2-02 §5 #20). |
| IF-PAY-C-16 | Cờ đoàn viên công đoàn và ngày hiệu lực | people | RT | Đoàn phí BR-PAY-046. |
| IF-PAY-C-17 | Đề nghị thanh toán chi phí đã duyệt cần hoàn ứng (ID đề nghị, số tiền, cách xử lý thuế, trung tâm chi phí) | people | Hằng ngày cho đến P-cut | BR-PAY-094. |
| IF-PAY-C-18 | Quyết định kỷ luật / bồi thường thiệt hại kèm số tiền cần khấu trừ, và các khoản khấu trừ theo quyết định của tòa án | people | RT | BR-PAY-092/093. |
| IF-PAY-C-19 | Lịch làm việc / mẫu làm việc chuẩn của nhân viên theo ngày (số ngày & giờ làm việc theo lịch trong kỳ) | time-attendance | P-close (và on-demand khi mô phỏng) | Phương pháp ngày công chuẩn (b), đơn giá giờ. |
| IF-PAY-C-20 | **Attendance Result của kỳ đã chốt** (có phiên bản) theo nhân viên theo kỳ: số ngày theo lịch, số ngày làm việc thực tế, số ngày được trả lương, số ngày nghỉ có lương theo loại nghỉ, số ngày lễ được trả lương, số ngày nghỉ không lương, số ngày nghỉ không phép, số ngày đình chỉ, số phút đi muộn/về sớm (nếu bị phạt) | time-attendance | **P-close** (`att.AttendancePeriodClosed(version)`), bắt buộc cho tính toán `FINAL` và được hold khi gửi duyệt; hạn **chốt** của T&A phải sớm hơn ngày duyệt lương ít nhất 2 ngày làm việc (changed 2026-10-16, X-03) | Tính theo tỷ lệ, số ngày được trả lương, quy tắc 14 ngày của BHXH. |
| IF-PAY-C-21 | Số giờ làm thêm theo loại: ngày thường, ngày nghỉ hằng tuần, ngày lễ, **làm thêm giờ ban đêm** cho từng loại, cộng số giờ làm việc ban đêm trong giờ bình thường; kèm trạng thái phê duyệt (chỉ làm thêm giờ đã duyệt) và quy đổi sang nghỉ bù (số giờ đã quy đổi sang nghỉ bù không được trả tiền) | time-attendance | P-close | Tiền làm thêm giờ BR-PAY-025/026, phần miễn thuế TNCN. Đồng thời là tổng làm thêm giờ theo tháng/năm để báo cáo giới hạn luật định. |
| IF-PAY-C-22 | Số ngày nghỉ hưởng BHXH: ốm đau, con ốm, thai sản, khám thai, sảy thai, điều trị TNLĐ-BNN, dưỡng sức, kèm khoảng ngày và số ngày làm việc | time-attendance | P-close | Loại khỏi phần người sử dụng lao động trả, miễn đóng BHXH (≥ 14 ngày làm việc), miễn đóng khi thai sản, dữ liệu hồ sơ hưởng chế độ BHXH (Phase 2). |
| IF-PAY-C-23 | Số lượng theo ca: số ca theo loại ca, số ngày làm công việc độc hại, số giờ trực, số suất ăn (nếu phụ cấp ăn theo ngày làm việc) | time-attendance | P-close | Phụ cấp ca / ăn / độc hại. |
| IF-PAY-C-24 | Số dư phép: số ngày phép năm còn lại cần thanh toán khi nghỉ việc hoặc cuối năm (số ngày, và cơ sở đơn giá ngày tham chiếu nếu T&A tính) | time-attendance | Khi nghỉ việc (P5) và cuối năm | Thanh toán phép BR-PAY-077. |
| IF-PAY-C-25 | **Điều chỉnh hồi tố** sau khi khóa: theo nhân viên theo kỳ `LOCKED`, các **dòng chênh lệch** (có giá trị quyết định) kèm lý do và người duyệt, được đưa vào Attendance Result của kỳ `OPEN` kế tiếp, hoặc vào **Attendance Result ngoài kỳ** cho người nghỉ việc không còn kỳ mở; `att.RetroDeltaCreated` RT để theo dõi (changed 2026-10-16, X-03; GAP-028) | time-attendance | Sự kiện RT + Attendance Result kế tiếp / kết quả ngoài kỳ | Truy lĩnh BR-PAY-076; kết quả ngoài kỳ làm đầu vào cho run quyết toán nghỉ việc / run điều chỉnh. T&A giữ các phiên bản đã điều chỉnh để audit. |
| IF-PAY-C-26 | Số giờ dự án theo nhân viên theo kỳ (để phân bổ chi phí theo thời gian) | time-attendance | P-close | BR-PAY-114 (`S`). |
| IF-PAY-C-27 | Các sự kiện trạng thái kỳ chấm công: `att.AttendancePeriodClosed(version)`, `att.AttendancePeriodReopened`, `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked` (changed 2026-10-16, X-03) | time-attendance | RT | Phát hiện stale (mở lại → run bị stale), xác nhận hold, xác nhận khóa, đối chiếu (BR-PAY-084). |
| IF-PAY-C-28 | Lương của nhân viên mới từ offer đã chấp nhận (chỉ khi chưa tạo trong People) — thông thường qua People | recruitment → people | n/a (gián tiếp) | Payroll không tiêu thụ trực tiếp từ Recruitment. |
| IF-PAY-C-29 | Legal Parameter Service: `getParam(key, scope, asOfDate)` → giá trị + phiên bản gói (tỷ lệ, mức trần, lương tối thiểu và bản đồ vùng, biểu thuế TNCN, giảm trừ, ngưỡng vãng lai, hạn quyết toán, phiên bản mẫu biểu); `saas.LegalParameterPackPublished` | saas-platform | Khi phát hành (có hiệu lực theo ngày); đọc đồng bộ khi tính | BR-PAY-010; mỗi run snapshot phiên bản gói (changed 2026-10-16, R2-02 §5 #1). |
| IF-PAY-C-30 | Quyền sử dụng theo gói thuê bao của tenant/pháp nhân (đã bật module payroll, giới hạn số nhân viên) | saas-platform | RT | Thực thi giấy phép. |
| IF-PAY-C-31 | Trung tâm thông báo (e-mail gồm cả phiếu lương đính kèm được mã hóa) và dịch vụ tài liệu (lưu trữ, tạo PDF) từ **core** (P-14, P-21); chữ ký số / HSM cho chứng từ điện tử & tờ khai và khóa mã hóa theo tenant từ **saas-platform** | core; saas-platform | On-demand | Phiếu lương, chứng từ điện tử, mã hóa (changed 2026-10-16, R2-02 §5 #14, #15b). |
| IF-PAY-C-32 | Job scheduler / queue chạy nền có theo dõi tiến độ cho các run lớn | saas-platform | On-demand | NFR-PAY-001. |
| IF-PAY-C-33 | Tỷ giá (cặp tiền tệ, loại tỷ giá, ngày tỷ giá, nguồn) | core (P-26, BR-CORE-088) | Hằng ngày / tại ngày tỷ giá | BR-PAY-013 (changed 2026-10-16, R2-02 §5 #19). |
| IF-PAY-C-34 | **Close-hold API** (T&A ATT-P-17): `acquireHold(period, version, runId)`, `releaseHold(period, runId, reason)`, `getHold(period)`; đồng bộ, idempotent theo run id + phiên bản (new 2026-10-16, X-03) | time-attendance | Khi gửi duyệt run (acquire); khi bị từ chối, rút lại, hủy, T&A yêu cầu giải phóng, mở khóa (release) | BR-PAY-082/083. Acquire bị từ chối → gửi duyệt thất bại. |
| IF-PAY-C-41 | `people.PrimaryEmploymentChanged` (person_id, employment_id, mục đích PIT_FAMILY_DEDUCTION / SOCIAL_INSURANCE, valid_from/to) | people | RT; đọc tại P-cut | Giảm trừ gia cảnh và người sử dụng lao động đóng BHXH/BHTN theo kỳ (BR-PAY-081); thay đổi trong kỳ đã đóng kích hoạt truy lĩnh (new 2026-10-16, R2-16) |
| IF-PAY-C-35 | `people.RewardApproved` (số tiền, kỳ lương đích, cờ chịu thuế, mã thành phần) | people | RT; áp dụng tại P-cut | Dữ liệu biến đổi khen thưởng (BR-PAY-086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-36 | `people.ContractChanged(SUSPENDED / RESUMED)` (lý do, từ/đến) | people | RT | Dừng/tiếp tục trả lương và BHXH trong thời gian tạm hoãn; D02-LT (BR-PAY-086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-37 | `people.TemporaryTransferChanged` (số ngày đã dùng trong năm, lương bảo lưu đến ngày) | people | RT | Thành phần lương bảo lưu (BR-PAY-086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-38 | `people.SecondmentChanged` (pháp nhân gốc / pháp nhân tiếp nhận / pháp nhân chi trả, cờ thu hồi chi phí, từ/đến) | people | RT | Pháp nhân chi trả và nhóm trả lương trong kỳ (BR-PAY-085/086) (new 2026-10-16, GAP-050). |
| IF-PAY-C-39 | `people.AppraisalFinalised` / điểm KPI định kỳ (kỳ, điểm, xếp loại) | people | Khi đóng chu kỳ / hằng tháng | Đầu vào cho công thức KPI và thưởng (BR-PAY-086; S) (new 2026-10-16, GAP-050). |
| IF-PAY-C-40 | `people.AdvanceDeductionRequested` (tạm ứng tiền mặt; số tiền, kỳ, tham chiếu sự đồng ý) | people | Tạm ứng quá hạn / khi nghỉ việc | Lệnh khấu trừ trong run kế tiếp hoặc khi quyết toán nghỉ việc (BR-PAY-077/086); thay thế People OUT-29 chưa có bên tiêu thụ (new 2026-10-16, GAP-050; R2-02 §5 #21). |

### 7.2 Cung cấp
| ID | Nội dung | Tới module | Thời điểm | Ghi chú |
|---|---|---|---|---|
| IF-PAY-P-01 | Danh mục thành phần lương (mã, tên, loại, thành phần nào là "phụ cấp theo hợp đồng" được nhập trong People) | people, recruitment | RT | Thu nhập trong People phải tham chiếu các mã này; mẫu offer tuyển dụng sử dụng chúng. |
| IF-PAY-P-02 | Thang bảng lương (thang bảng lương: số tiền/hệ số theo ngạch × bậc, thời gian nâng bậc), khung lương theo ngạch (min-mid-max), cấu trúc lương, phụ cấp mặc định — `pay.CompensationCatalogueChanged`. Payroll là chủ sở hữu duy nhất; Core chỉ giữ danh mục ngạch. **Năng lực nền tảng, luôn bật** bất kể tenant mua module nào (R2-18) | people, recruitment | RT | Thu nhập mặc định khi tuyển dụng/thăng chức; kiểm tra hợp lệ lương và offer (changed 2026-10-16, R2-02 §5 #7; R2-18). |
| IF-PAY-P-03 | Tổng hợp kết quả lương theo nhân viên theo kỳ (gross, net, cơ sở BHXH, chi phí người sử dụng lao động) | people (màn hình 360 nhân viên, đánh giá/xét tăng lương), saas-platform (reporting store) | Khi run được duyệt | Giới hạn theo phạm vi dữ liệu (changed 2026-10-16, R2-02 §5 #18). |
| IF-PAY-P-04 | Lương bình quân 6 tháng gần nhất và lịch sử lương | people (hiển thị khi nghỉ việc) | On-demand | Bỏ T&A khỏi danh sách bên tiêu thụ (T&A không tính tiền) (changed 2026-10-16, R2-02 §5 #29). |
| IF-PAY-P-05 | Lịch kỳ lương `pay.PayCalendarPublished` (ngày chốt công, ngày chốt làm thêm giờ tùy chọn, **hạn chốt của T&A**, hạn phê duyệt, ngày trả lương, phương pháp ngày công chuẩn) theo nhóm trả lương (changed 2026-10-16, X-03) | time-attendance, core (thông báo) | Khi tạo kỳ | T&A dùng để lên lịch chốt; nhắc việc. |
| IF-PAY-P-06 | Sự kiện trạng thái run lương `pay.PayrollRunStatusChanged` (CALCULATED, SUBMITTED, APPROVED, REJECTED, PAID, CLOSED, UNLOCKED, REVERSED) kèm **các phiên bản chấm công đã dùng** và watermark của People (changed 2026-10-16, X-03) | time-attendance, people, core | RT | Khi APPROVED, T&A khóa phiên bản đang được hold; khi UNLOCKED, T&A trả về `LOCKED → CLOSED`; thay đổi đối với kỳ `LOCKED` trở thành các dòng chênh lệch truy lĩnh (IF-PAY-C-25). People được cảnh báo khi chỉnh sửa thu nhập hồi tố trong các kỳ đã khóa. |
| IF-PAY-P-07 | Đơn giá giờ/ngày theo nhân viên (cho xem trước giá trị làm thêm giờ, thanh toán phép) | time-attendance | On-demand | Tùy chọn; payroll vẫn là hệ thống gốc về tiền. |
| IF-PAY-P-08 | Trạng thái đóng BHXH theo nhân viên theo tháng, lịch sử mức lương đóng BHXH kể cả các tháng trước go-live, trạng thái dòng D02-LT, và **API số năm đóng BHXH** (`pay.SiContributionPosted`) | people (sổ/hồ sơ BHXH), time-attendance (quyền nghỉ ốm, ATT-C-20), saas-platform (báo cáo) | Khi run được duyệt; theo yêu cầu | Payroll sở hữu toàn bộ lịch sử đóng BHXH; People hiển thị; hỗ trợ chốt sổ BHXH khi nghỉ việc (changed 2026-10-16, R2-02 §5 #20). |
| IF-PAY-P-09 | Dư nợ vay/tạm ứng | people (checklist nghỉ việc) | On-demand / khi nghỉ việc | Hoàn tất thủ tục quyết toán nghỉ việc. |
| IF-PAY-P-10 | Bảng quyết toán nghỉ việc và trạng thái (`pay.FinalSettlementStatusChanged`: số tiền, thời gian làm việc và cơ sở tính trợ cấp thôi việc, hạn, ngày chi trả) | people | Khi duyệt / chi trả | Đóng quy trình nghỉ việc; theo dõi hạn (Payroll sở hữu đồng hồ đếm hạn) (changed 2026-10-16, R2-02 §5 #29). |
| IF-PAY-P-11 | Các trang ESS của Payroll: phiếu lương, mật khẩu phiếu lương, chứng từ thuế TNCN, tổng hợp thu nhập năm, sao kê khoản vay, 08/CK-TNCN và ủy quyền quyết toán | saas-platform (ESS/MSS shell; responsive web ở Phase 1, ứng dụng mobile ở Phase 2 — OD-9/X-04) | Khi công bố | SaaS sở hữu shell; Payroll sở hữu các trang và API này (changed 2026-10-16, R2-02 §5 #4, #17). |
| IF-PAY-P-12 | Tác vụ phê duyệt (run, mở khóa, tạm ứng, khoản vay, ghi đè tham số) | core (hộp thư workflow) | RT | |
| IF-PAY-P-13 | Sự kiện audit | core (audit) | RT | |
| IF-PAY-P-14 | Thông báo (đã công bố phiếu lương, chờ phê duyệt, cảnh báo ngoại lệ, 08/CK hết hạn) | core (dịch vụ thông báo) | RT | |
| IF-PAY-P-15 | API ước tính chi phí lương (gross↔net, chi phí người sử dụng lao động) | recruitment (offer), people (mô phỏng thay đổi thu nhập, kiểm tra NET) | On-demand | BR-PAY-124. Bỏ Core khỏi danh sách bên tiêu thụ (Core không giữ ngân sách chi phí) (changed 2026-10-16, R2-02 §5 #8). **Dịch vụ nền tảng mức API, luôn bật** khi đã xây dựng, bất kể tenant mua module nào (R2-18, R2-C9); phase của nó theo BR-PAY-124 trong mvp-scope.md §9 (P2). |
| IF-PAY-P-16 | Chi phí nhân sự thực tế theo trung tâm chi phí/dự án/kỳ | saas-platform (reporting store), recruitment (Phase 2 ngân sách chi phí kế hoạch nhân sự so với thực tế) | Khi run được duyệt | Bỏ Core khỏi danh sách bên tiêu thụ (changed 2026-10-16, R2-02 §5 #8, #18). |
| IF-PAY-P-17 | Chỉ số sử dụng (số phiếu lương đã xử lý, số nhân viên được trả lương mỗi tháng) | saas-platform (tính phí) | Hằng tháng | Chỉ mang tính thông tin. Đơn vị tính phí là người đang làm việc vào cuối tháng **theo pháp nhân** (quyết định D-10), đếm từ People; số liệu của payroll chỉ dùng để đối chiếu (changed 2026-10-16, D-10). |
| IF-PAY-P-18 | File chi trả ngân hàng, bút toán GL, file VSS, XML thuế TNCN | bên ngoài (ngân hàng, ERP, VSS, GDT) qua dịch vụ tích hợp/file của saas-platform | Khi tạo | Phase 1: khách hàng tự tải file xuất lên; API trực tiếp thuộc Phase 2 (R2-01). |
| IF-PAY-P-19 | `pay.PayGroupAssigned` (quan hệ lao động, nhóm trả lương, ngày hiệu lực, nguồn) | time-attendance, people (mức sẵn sàng onboarding) | RT khi gán theo quy tắc hoặc thủ công | BR-PAY-085 (new 2026-10-16, R2-02 §5 #25). |
| IF-PAY-P-20 | Trạng thái khóa của pháp nhân tại một ngày (kỳ đã khóa gần nhất trên tất cả nhóm trả lương của pháp nhân, đang mở khóa hay không) | core | Truy vấn đồng bộ | BR-PAY-087; Core C-16, BR-CORE-015 (new 2026-10-16, GAP-049). |
| IF-PAY-P-21 | Đề xuất kiểm tra cư trú 183 ngày (cá nhân, năm, số ngày tại VN, tình trạng đề xuất) | people | Hằng tháng / theo yêu cầu | BR-PAY-065; People IN-32 (new 2026-10-16, GAP-048). |

### 7.3 Tóm tắt mốc thời gian (chu kỳ tháng, ví dụ chốt ngày 25, trả lương ngày 5)
| Ngày | Sự kiện | Chủ sở hữu |
|---|---|---|
| Cuối kỳ (ngày 25) | Chốt các thay đổi People ảnh hưởng đến kỳ; hạn nhập dữ liệu biến đổi | people / payroll |
| +2 ngày làm việc | Hạn chốt của T&A: kỳ `CLOSED`, phát hành phiên bản Attendance Result, IF-PAY-C-20..27 sẵn sàng (changed 2026-10-16, X-03) | time-attendance |
| +3 | Chốt dữ liệu đầu vào, tính `FINAL`, xử lý xong ngoại lệ (run `CALCULATED`) | payroll |
| +3 … +4 | Run được gửi duyệt → close hold được cấp trên (các) phiên bản kết quả | payroll / time-attendance |
| +5 | Hoàn tất phê duyệt (run `APPROVED`) → kỳ T&A `LOCKED`; gửi file ngân hàng | payroll / core workflow / time-attendance |
| Ngày trả lương | Chi trả lương, công bố phiếu lương | payroll |
| Trước ngày 20 tháng sau | 05/KK-TNCN (đơn vị kê khai theo tháng) | payroll |
| Trước ngày cuối cùng của tháng | Nộp tiền đóng BHXH/BHYT/BHTN | payroll / finance |

---

## 8. Báo cáo & phân tích

| Báo cáo | Mô tả | Ưu tiên |
|---|---|---|
| Bảng lương (bảng lương) | Theo run, theo nhân viên, mọi thành phần; xuất Excel với cột cấu hình được; phần chữ ký | M |
| Tổng hợp lương theo pháp nhân / phòng ban / trung tâm chi phí / dự án | Tổng theo thành phần, số lượng nhân sự | M |
| Báo cáo chênh lệch | So sánh giữa các kỳ theo nhân viên/thành phần, tô sáng theo ngưỡng | M |
| Báo cáo ngoại lệ | Kết quả kiểm tra hợp lệ (BR-PAY-074) | M |
| Báo cáo đóng BHXH/BHYT/BHTN/KPCĐ | Mức đóng, tỷ lệ, phần NLĐ/NSDLĐ, lý do miễn theo nhân viên; đối chiếu với C12-TS của VSS | M |
| D02-LT và các đầu ra VSS liên quan | File tờ khai và danh sách | M |
| Báo cáo thuế TNCN hằng tháng & 05/KK-TNCN | Thu nhập chịu thuế, giảm trừ, thuế TNCN theo nhân viên; XML | M |
| Bộ hồ sơ quyết toán thuế TNCN | 05/QTT-TNCN + phụ lục; đối chiếu năm theo nhân viên (hoàn/nộp thêm) | M |
| Sổ theo dõi chứng từ thuế TNCN | Chứng từ đã phát hành/đã hủy theo ký hiệu | M |
| Sổ theo dõi thu nhập vãng lai & khấu trừ | Người nhận chịu 10%/20%, trạng thái 08/CK | M |
| Tổng hợp / đối chiếu chi trả ngân hàng | Theo file ngân hàng, trạng thái | M |
| Báo cáo bút toán GL | Nợ/có theo tài khoản và phân đoạn | M |
| Báo cáo chi phí lao động | Gross + đóng góp của người sử dụng lao động + KPCĐ theo trung tâm chi phí/dự án, xu hướng | M |
| Báo cáo truy lĩnh | Chênh lệch theo kỳ gốc/thành phần | M |
| Sổ theo dõi khoản vay/tạm ứng | Số dư và biến động | S |
| Bảng quyết toán nghỉ việc | Theo nhân viên, tiếng Việt/tiếng Anh | M |
| Báo cáo tuân thủ lương tối thiểu | Nhân viên gần/dưới mức tối thiểu vùng | S |
| Báo cáo chi phí làm thêm giờ | Chi phí làm thêm giờ theo loại, phòng ban, so với giới hạn luật định (số giờ từ T&A) | S |
| Bảng kê thu nhập lũy kế từ đầu năm | Theo nhân viên | M |
| Dashboard phân tích | Xu hướng chi phí lương, số nhân sự so với chi phí, lương bình quân theo ngạch, chỉ số chênh lệch lương theo giới tính | C |

Mọi báo cáo đều tuân thủ phạm vi dữ liệu (BR-PAY-120), xuất được ra Excel/PDF, và chạy trên kết quả đã duyệt trừ khi chọn rõ "tạm tính".

---

## 9. Yêu cầu phi chức năng

| ID | Yêu cầu | Ưu tiên |
|---|---|---|
| NFR-PAY-001 | Hiệu năng tính toán: một run thường kỳ cho 10.000 nhân viên phải hoàn tất trong ≤ 15 phút; tính lại một nhân viên ≤ 5 giây; run chạy bất đồng bộ có chỉ báo tiến độ và không làm giảm hiệu năng của tenant khác (hạn mức job theo tenant). | M |
| NFR-PAY-002 | Độ chính xác & tính tất định: cùng dữ liệu đầu vào + phiên bản tham số/công thức cho ra kết quả giống hệt đến từng VND; phép tính tiền tệ dùng số thập phân fixed-point (không dùng floating point). | M |
| NFR-PAY-003 | Khả năng tái lập: mọi run đã duyệt có thể tính lại từ snapshot và cho ra cùng kết quả trong ≥ 10 năm. | M |
| NFR-PAY-004 | Bảo mật (changed 2026-10-16, X-01): mọi dữ liệu được mã hóa khi lưu trữ (mức storage) và khi truyền; tài khoản ngân hàng và mã số thuế/BHXH được mã hóa ở mức cột bằng khóa của tenant; số tiền lương KHÔNG mã hóa ở mức cột nhưng được bảo vệ bằng RLS, DB role theo module, view che dữ liệu và audit truy cập; PDF phiếu lương có mật khẩu; hiển thị che số tài khoản ngân hàng. | M |
| NFR-PAY-005 | Cô lập tenant: dữ liệu lương không bao giờ vượt ranh giới tenant (row-level security / kiểm tra schema) kể cả trong job chạy nền và file xuất. | M |
| NFR-PAY-006 | Khả năng audit: audit log bất biến cho cấu hình, dữ liệu đầu vào, thay đổi trạng thái, tạo file, mở khóa; lưu trữ theo BR-PAY-123. | M |
| NFR-PAY-007 | Linh hoạt pháp lý: một bộ tham số pháp lý mới (ví dụ thay đổi lương tối thiểu) phải triển khai được bằng cấu hình mà không cần phát hành mã; cấu trúc biểu thuế TNCN mới không cần thay đổi schema. | M |
| NFR-PAY-008 | Bộ kiểm thử hồi quy: bộ kiểm thử golden-file tự động gồm ≥ 200 kịch bản tính lương (GROSS/NET, cư trú/không cư trú, tính theo tỷ lệ, mức trần, làm thêm giờ, truy lĩnh, nghỉ việc) được chuyên gia tư vấn lương VN xác nhận, chạy ở mỗi lần phát hành và mỗi lần thay đổi tham số. | M |
| NFR-PAY-009 | Tính sẵn sàng: tính lương và ESS phiếu lương sẵn sàng 99,5% hằng tháng; năng lực đỉnh được hoạch định cho ngày 1–10 hằng tháng và dịp Tết. | M |
| NFR-PAY-010 | Tương thích file: file XML/Excel được tạo phải được kiểm tra theo mẫu hiện hành của HTKK/eTax/VSS và ngân hàng; phiên bản mẫu cấu hình được không cần phát hành. | M |
| NFR-PAY-011 | Bản địa hóa: UI và đầu ra VN/EN; định dạng VND (dấu phân cách hàng nghìn ".", không có số thập phân) theo locale người dùng; dấu tiếng Việt trong file ngân hàng theo quy định của từng ngân hàng (một số ngân hàng yêu cầu không dấu). | M |
| NFR-PAY-012 | Khả năng sử dụng: quản trị viên tính lương có thể hoàn tất run hằng tháng cho 500 nhân viên (không có ngoại lệ) trong < 30 phút thao tác trực tiếp. | S |
| NFR-PAY-013 | Khả năng mở rộng: hỗ trợ tenant đến 10.000 nhân viên mỗi pháp nhân và 50 pháp nhân mỗi tenant. | S |

---

## 10. Giả định, câu hỏi mở, rủi ro

### 10.1 Giả định
| # | Giả định |
|---|---|
| A1 | Payroll là hệ thống gốc về tiền (đơn giá, kết quả, chi trả); People là hệ thống gốc về thu nhập thỏa thuận và dữ liệu gốc cá nhân/thuế/BHXH; T&A về số lượng thời gian. |
| A2 | MVP chỉ hỗ trợ tần suất hằng tháng; nửa tháng/hằng tuần thuộc Phase 2+. |
| A3 | MVP tạo file để tải lên thủ công lên VSS (VssID/I-VAN) và eTax/HTKK; API T-VAN trực tiếp ở Phase 2. |
| A4 | Nền tảng duy trì tập trung các gói tham số pháp lý quốc gia trong Legal Parameter Service của saas-platform; tenant không thể thay đổi giá trị quốc gia ngoại trừ các ghi đè được phép; Payroll snapshot phiên bản gói theo từng run (changed 2026-10-16, R2-02 §5 #1). |
| A7 | Năng lực nền tảng luôn bật (R2-18): thang bảng lương/khung lương và danh mục thành phần luôn sẵn có cho People và Recruitment kể cả khi không đăng ký module payroll; tenant có Payroll nhưng không có T&A cung cấp dữ liệu công qua import chấm công Excel, tạo ra Attendance Result chuẩn (changed 2026-10-16, R2-18). |
| A5 | Tenant thí điểm có thể cung cấp số dư đầu kỳ trong các mẫu Excel (thu nhập/thuế TNCN/BHXH lũy kế năm tính thuế hiện hành, lịch sử đóng BHXH, ít nhất 6 tháng lịch sử lương, dư nợ vay); không có chuyển đổi dữ liệu từ Oracle (changed 2026-10-16, D-09). |
| A6 | Tích hợp host-to-host với ngân hàng nằm ngoài MVP; tải file lên internet banking nằm trong phạm vi. |

### 10.2 Câu hỏi mở
| # | Câu hỏi | Gửi tới |
|---|---|---|
| Q1 | Cách áp dụng chuyển tiếp chính xác của Luật Thuế TNCN 2025 đối với thu nhập từ tiền lương, tiền công (biểu 5 bậc từ 1/1/2026 hay 1/7/2026?) và ngưỡng khấu trừ thu nhập vãng lai mới. | Chuyên gia tư vấn thuế |
| Q2 | Giá trị mức tham chiếu (mức tham chiếu) và ngày hiệu lực khi thay thế lương cơ sở cho mức trần BHXH/BHYT và mức tối đa đoàn phí năm 2026. | Chuyên gia tư vấn pháp lý |
| Q3 | Bộ biểu mẫu VSS và XML schema cập nhật ban hành theo Luật BHXH 2024 (thay thế Quyết định 595/490). | Chuyên gia tư vấn pháp lý / VSS |
| Q4 | ~~Ai sở hữu lịch ngày lễ?~~ **Đã đóng 2026-10-16 (R2-02 §5 #2):** T&A; Payroll đọc API lịch `att` (IF-PAY-C-06). | Chuyên viên phân tích tích hợp |
| Q5 | ~~Ai sở hữu việc ghi nhận 08/CK-TNCN và đăng ký người phụ thuộc?~~ **Đã đóng 2026-10-16 (R2-02 §5 #3, #4):** People sở hữu người phụ thuộc (đăng ký, phê duyệt, `DependantChanged`); Payroll sở hữu 08/CK-TNCN và ủy quyền quyết toán (các trang ESS của Payroll). | Chuyên viên phân tích tích hợp |
| Q6 | ~~Ai host ESS?~~ **Đã đóng 2026-10-16 (R2-02 §5 #17):** saas-platform sở hữu ESS/MSS shell; Payroll sở hữu các trang và API phiếu lương/thuế TNCN của mình. | PM / Kiến trúc sư |
| Q7 | T&A có cung cấp tổng hợp kỳ có phiên bản kèm chênh lệch cho truy lĩnh (IF-PAY-C-25) hay payroll phải tự tính chênh lệch bằng cách so sánh các bản tổng hợp đã điều chỉnh? **Đã đóng 2026-10-16 (X-03, GAP-028):** chênh lệch của T&A có giá trị quyết định; các phiên bản điều chỉnh chỉ được giữ để audit. | BA T&A |
| Q8 | ~~Có cần phân công đồng thời giữa các pháp nhân trong MVP không?~~ **Đã đóng 2026-10-16 (R2-16):** có, hỗ trợ cơ bản — quan hệ lao động chính mang giảm trừ gia cảnh thuế TNCN và BHXH; mỗi pháp nhân khấu trừ thuế TNCN trên khoản chi của mình (BR-PAY-081). Quy tắc BHXH/BHYT/BHTN/TNLĐ-BNN cho nhiều hợp đồng là tham số pháp lý `insurance.multi_contract_rule` (mặc định FIRST_CONTRACT cho BHXH/BHTN/BHYT, EACH_EMPLOYER cho TNLĐ-BNN; changed 2026-10-16, R3-03) và vẫn `[verify-legal]`. | PM / BA People |
| Q9 | Những ngân hàng nào bắt buộc cho file ngân hàng của tenant thí điểm? | Khách hàng thí điểm |
| Q10 | Có cần phân bổ chi phí theo giờ dự án trong MVP không (IF-PAY-C-26)? | PM |
| Q11 | Kinh phí công đoàn có áp dụng cho pháp nhân chưa có công đoàn cơ sở theo quy định Luật Công đoàn 2024 không, và trên cơ sở nào? | Chuyên gia tư vấn pháp lý |

### 10.3 Rủi ro
| # | Rủi ro | Tác động | Giảm thiểu |
|---|---|---|---|
| R1 | Thay đổi pháp lý thường xuyên trong 2025–2026 (Luật BHXH 2024, Luật Thuế TNCN 2025, giảm trừ mới, lương tối thiểu, sáp nhập tỉnh) | Tính sai, bị phạt do không tuân thủ | Tham số có hiệu lực theo ngày (BR-PAY-010), vai trò quản trị nội dung pháp lý, kiểm thử golden (NFR-PAY-008), chuyên gia tư vấn ký xác nhận |
| R2 | T&A chốt muộn, hoặc yêu cầu mở lại một kỳ đang được hold (changed 2026-10-16, X-03) | Chậm trả lương | Lịch tính lương (IF-PAY-P-05), cảnh báo hạn chốt, run tạm tính, giải phóng hold kèm cờ stale (BR-PAY-083) |
| R3 | Tenant dùng sai formula engine dẫn đến kết quả sai | Trả lương sai | Chế độ thử, quản lý phiên bản, mẫu cấu hình, phê duyệt thay đổi công thức |
| R4 | Độ phức tạp của truy lĩnh giữa các module (thay đổi hồi tố trong People, điều chỉnh T&A) | Kết quả không nhất quán | Dữ liệu đầu vào có hiệu lực theo ngày, có phiên bản ở mọi module; dịch vụ phát hiện truy lĩnh; phân định rõ chủ sở hữu trong gap register |
| R5 | Thay đổi định dạng của ngân hàng/VSS/GDT | File bị từ chối | Mẫu cấu hình được (NFR-PAY-010) |
| R6 | Lộ lọt dữ liệu lương | Thiệt hại pháp lý và uy tín | NFR-PAY-004/005, RBAC theo phạm vi dữ liệu, audit |
| R7 | Chất lượng số dư đầu kỳ (lũy kế từ đầu năm, lịch sử BHXH, lịch sử lương) (changed 2026-10-16, D-09) | Quyết toán thuế TNCN, trợ cấp thôi việc sai | Kiểm tra hợp lệ theo mẫu, báo cáo đối chiếu với bảng lương gần nhất của tenant có tenant ký xác nhận (BR-PAY-122); run thực tế đầu tiên được so sánh với phiếu lương trước đó của tenant |
| R8 | Các trường hợp biên khi quy đổi net sang gross (mức trần, ranh giới bậc thuế, nhiều run trong tháng) | Lệch lương net | Kiểm thử hội tụ, phương pháp được tài liệu hóa |
