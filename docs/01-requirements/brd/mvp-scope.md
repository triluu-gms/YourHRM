# YourHRM — Xếp hạng lại phạm vi MVP (Phase 1 / 2 / 3)

| Mục | Giá trị |
|---|---|
| Tài liệu | `docs/01-requirements/brd/mvp-scope.md` |
| Phiên bản | 0.3 — 2026-10-16 (đã áp dụng vòng 3) |
| Tác giả | Lead Business Analyst |
| Trạng thái | Đã áp dụng vòng 1–3 (bao gồm đồng bộ phạm vi cuối cùng R2-02). Điểm còn mở: §11.4 (R2-C2 chờ xác nhận pháp lý về quy tắc bảo hiểm khi có nhiều hợp đồng; R2-C9/R2-C10 đã đóng hoặc chỉ còn là vấn đề quy trình) |
| Căn cứ | [BRD.md](BRD.md) §5, §9, §10 · [decision-log.md](../../00-project-management/decision-log.md) X-04, OD-9, D-09, D-10, R2-01 – R2-19 (§7, §7.1, §7.2), R3-01 – R3-11 (§8) · [project-charter.md](../../00-project-management/project-charter.md) §6 |
| Ảnh chụp dữ liệu | ID yêu cầu và độ ưu tiên cũ được đọc từ sáu BRD module vào ngày 2026-10-16. Các BRD module đang được chủ sở hữu chỉnh sửa song song; mọi ID được thêm sau này phải được xếp hạng tại đây trước khi backlog bị đóng băng. |

| Phiên bản | Thay đổi |
|---|---|
| 0.1 | Xếp hạng lại vòng 1 (X-04, OD-9, D-09, D-10): P1 = 281 BR, 61 NFR. |
| 0.2 | Vòng 2 (R2-01 – R2-19): áp dụng Phương án B (27 trên 29 mục cắt giảm), mọi tích hợp bên ngoài trực tuyến (live) → P2, BR-ATT-063 và BR-SAAS-073 → P1, thêm cột baseline (R2-18). **P1 = 254 BR, 58 NFR.** Các dòng thay đổi trong §9 được đánh dấu "(R2, 2026-10-16)". Thêm §11 mới (các thay đổi vòng 2) và §12 (danh sách đồng bộ nhãn phase cho các BRD module). |
| 0.2 (đồng bộ cuối) | Đồng bộ phạm vi cuối cùng R2-02: xếp hạng 9 yêu cầu BA mới (7 P1, 2 P2); 5 dòng được đánh dấu `Removed` do chuyển quyền sở hữu (BR-CORE-038, 039, 042, 113, BR-SAAS-027), các năng lực P1 của chúng được xác nhận trong các dòng SaaS xếp P1 + baseline; thêm giá trị baseline `API` (R2-C9); đồng bộ cột Phase của T&A với §9; thêm banner phase vào mọi BRD module, thay thế danh sách nhãn ở §12. **P1 = 259 BR, 58 NFR.** |
| 0.3 | Vòng 3 (decision-log §8, R3-01 – R3-11): BR-ATT-075 (R3-06), BR-PPL-019 bản tối giản (R3-07) và BR-SAAS-037 bản tối giản (R3-08) quay lại P1; hóa đơn điện tử của nhà cung cấp BR-SAAS-014/015 → P2 (R3-01). Chứng từ khấu trừ thuế TNCN điện tử trong P1 = dữ liệu + PDF chưa ký (R3-02). Quy tắc bảo hiểm khi có nhiều hợp đồng trở thành tham số pháp lý theo từng loại bảo hiểm (R3-03). Nhân viên không có e-mail nhận phiếu lương PDF in giấy (R3-05). Các xung đột §11.4 R2-C1, C3–C8 và C11 đã được giải quyết. **P1 = 260 BR, 58 NFR.** Các dòng thay đổi trong §9 được đánh dấu "(R3, 2026-10-16)". |

## 1. Mục đích

Ngày 2026-10-16, sponsor quyết định cắt giảm MVP. Tài liệu này gán cho mọi BR và NFR trong sáu BRD module một phase bàn giao — **P1** (Phase 1 / MVP, pilot 16 tuần), **P2** hoặc **P3** — và ghi lại lý do cho từng mục. Từ nay, **phase mới tại đây được ưu tiên hơn độ ưu tiên MoSCoW trong các BRD module** khi quyết định những gì đưa vào Phase 1. MoSCoW vẫn dùng để xếp thứ tự các mục *trong cùng* một phase.

Các quyết định được áp dụng:

| Quyết định | Ý nghĩa đối với phạm vi |
|---|---|
| X-04 | Cắt giảm MVP. "Phase 1 chỉ gồm các tính năng cốt lõi của mỗi module." Toàn bộ tính năng ứng dụng di động chuyển sang Phase 2. Điểm xuất phát: 310 BR Must (không tính Tuyển dụng). Mục tiêu: khoảng 180–200. |
| OD-9 | Không có PWA, mobile, chấm công bằng GPS hay selfie trong Phase 1. Dữ liệu chấm công của pilot lấy từ máy chấm công và import Excel. ESS vẫn nằm trong Phase 1 dưới dạng ứng dụng web responsive. |
| D-09 | Không chuyển đổi dữ liệu từ Oracle, không staging, không chạy song song. Thay vào đó là một cơ chế **import số dư đầu kỳ** chung từ các template Excel: hồ sơ nhân viên gốc, thu nhập/thuế TNCN/BHXH lũy kế từ đầu năm (YTD), lịch sử đóng BHXH, số dư phép và các khoản vay. Những dữ liệu này cần cho go-live giữa năm và cho quyết toán thuế TNCN cuối năm. |
| D-10 | Nhân viên tính phí = người đang làm việc tại thời điểm cuối tháng, đếm theo từng pháp nhân. |
| Charter §6 | Tuyển dụng, đánh giá, đào tạo, chi phí, xếp ca nâng cao, công cụ thiết kế báo cáo, white-label và BI (Superset) thuộc Phase 2. |
| Quy tắc của sponsor | Mọi nội dung bắt buộc theo luật để tuân thủ tính lương tại Việt Nam vẫn nằm trong Phase 1: BHXH/BHYT/BHTN, thuế TNCN bao gồm quyết toán, hợp đồng lao động, giới hạn làm thêm giờ và luật về nghỉ phép. |
| R2-01, R2-19 (vòng 2) | Phê duyệt các mục cắt giảm mở rộng của Phương án B. **Không có tích hợp bên ngoài trực tuyến trong Phase 1**: connector máy chấm công, Zalo OA/ZNS, SMS, push, ký điện tử, VNeID, API kế toán và ngân hàng, trang tuyển dụng, public API/webhook và runtime của integration hub đều là P2. Phase 1 giữ lại thông báo qua e-mail và trong ứng dụng, **xuất file** (file thanh toán ngân hàng, file kê khai VSS, XML thuế TNCN cho eTax, bút toán GL dạng Excel), import Excel (bao gồm template file xuất từ máy chấm công cho dữ liệu chấm công), nhập liệu thủ công và yêu cầu điều chỉnh công. |
| R2-03, R2-07, R2-14, R2-16, R2-17, R2-18 (vòng 2) | So sánh bảng lương một tháng (BR-SAAS-073) và dữ liệu hồ sơ hưởng chế độ BHXH (BR-ATT-063, bản tối giản) → P1. Kỳ lương thưởng/ngoài chu kỳ vẫn là P1; các quy tắc chính sách Tết/lương tháng 13 → P2. Làm việc kiêm nhiệm cơ bản giữa các pháp nhân thuộc P1. Phiếu lương dạng PDF có mật khẩu gửi qua e-mail thuộc P1. Các năng lực baseline (luôn bật) được đánh dấu trong §9. Chi tiết: §11. |

## 2. Phương pháp & quy tắc xếp hạng

1. **Trích xuất.** Mọi dòng bảng có ô đầu tiên là `BR-<MOD>-NNN` hoặc `NFR-<MOD>-NNN` trong mục §4 và §9 của sáu BRD module được trích xuất bằng `awk` (§10). Kết quả là 566 dòng, cộng thêm 2 yêu cầu mới từ D-09. Độ ưu tiên tách đôi được đếm theo chữ cái đứng đầu, giống BRD §9. Ví dụ, BR-CORE-039 "M/S/C" được tính là M, và BR-PPL-058 "S→M" được tính là S.
2. **Các quy tắc được áp dụng theo thứ tự sau. Quy tắc đầu tiên khớp sẽ được áp dụng.**

| # | Quy tắc | Phase |
|---|---|---|
| R1 | Nghĩa vụ theo luật định của người sử dụng lao động tại VN (BHXH/BHYT/BHTN/BHTNLĐ-BNN/kinh phí công đoàn, thuế TNCN bao gồm 05/KK, 05/QTT và chứng từ điện tử, lương tối thiểu, hợp đồng lao động và thử việc, thời hạn báo trước, trợ cấp thôi việc, giới hạn và loại làm thêm giờ, luật về nghỉ phép, sổ quản lý lao động và báo cáo lao động, PDPL, hóa đơn điện tử, lưu trữ dữ liệu trong nước) | P1, kể cả khi độ ưu tiên cũ là S |
| R2 | Cần thiết để go-live giữa năm mà không chuyển đổi dữ liệu (D-09): framework import, template số dư đầu kỳ, đối soát, số dư phép và khoản vay | P1 |
| R3 | Dữ liệu hoặc xử lý mà một đầu ra theo luật định phụ thuộc vào (pháp nhân, tổ chức, cá nhân, quan hệ lao động, phân công, lương, lịch làm việc, bảng công, khóa kỳ, kỳ tính lương, file ngân hàng, GL) | P1 |
| R4 | Baseline về bảo mật, cô lập, kiểm toán và quyền riêng tư (sản phẩm không thể go-live nếu thiếu) | P1 |
| R5 | ESS/MSS qua **web responsive**: hồ sơ, nghỉ phép, làm thêm giờ, điều chỉnh công, phiếu lương, chứng từ thuế TNCN, hộp thư phê duyệt | P1 |
| R6 | Mọi tính năng ứng dụng di động, PWA, chấm công bằng GPS/Wi-Fi/selfie/web, push, phê duyệt trên di động, hoặc bất cứ thứ gì phụ thuộc vào chúng (OD-9) | P2 |
| R7 | Toàn bộ module hoặc năng lực bị hoãn theo charter: Tuyển dụng, đánh giá, đào tạo, chi phí, xếp ca, công cụ thiết kế báo cáo, dashboard/BI, white-label, public API/webhook, tự đăng ký, gói dedicated | P2 (mục cũ là `C` chuyển sang P3) |
| R8 | Tiện ích, tự động hóa hoặc khả năng cấu hình mà Phase 1 có giải pháp thay thế (bước thủ công, cấu hình seed sẵn, quy tắc đơn giản hơn) | P2 |
| R9 | Mọi mục còn lại giữ ánh xạ MoSCoW: M → P1, S → P2, C → P3. Nhãn phase riêng của module (cột Phase của T&A) được tôn trọng khi nó muộn hơn ánh xạ này. | — |

**Các ghi đè của vòng 2 (áp dụng trước R1–R9).** (a) Một tích hợp bên ngoài trực tuyến, hoặc một yêu cầu chỉ phục vụ cho tích hợp đó (NFR về tốc độ nhận dữ liệu chấm công và bộ đệm offline), là P2. File xuất hoặc nhập mà khách hàng tải lên hoặc tải xuống thủ công vẫn giữ phase theo R1–R9. (b) Danh sách Phương án B (§4) là P2, trừ khi một quyết định cụ thể hơn của vòng 2 giữ mục đó ở P1 (BR-PAY-102 theo R2-17, BR-ATT-037 theo R2-01 "nhập liệu thủ công"). (c) Một quyết định vòng 2 rõ ràng cho một ID cụ thể (R2-03, R2-07) được ưu tiên hơn mọi quy tắc khác.

3. **Đếm.** Mọi con số trong §3 là đầu ra của script ở §10, chạy trên dữ liệu trích xuất được join với file xếp hạng.

## 3. Kết quả

Mọi con số dưới đây là đầu ra của script §10, chạy ngày 2026-10-16 sau vòng 3. "R1" là phiên bản 0.1 của tài liệu này (các quyết định vòng 1). "R3" là phiên bản hiện tại: vòng 2 bao gồm đồng bộ R2-02, cộng với vòng 3 (R3-01 – R3-11). Đợt đồng bộ R2-02 đã xếp hạng 9 yêu cầu BA mới và loại 5 dòng khỏi module cũ do chuyển quyền sở hữu (phase `Removed`, không tính vào mọi con số).

### 3.1 Yêu cầu nghiệp vụ (BR), R1 so với R3 (cuối cùng)

"Tổng BR" chỉ đếm các dòng đang hiệu lực. Các dòng bị loại được hiển thị riêng. M/S/C cũ là giá trị MoSCoW trước R1; "Mới" nghĩa là được thêm vào ngày 2026-10-16.

| Module | Tổng BR | M cũ | S cũ | C cũ | Mới | Bị loại | R1 P1 | R1 P2 | R1 P3 | **R3 P1** | R3 P2 | R3 P3 | Δ P1 (R3 − R1) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Core | 75 | 59 | 10 | 5 | 1 | 4 | 52 | 21 | 5 | **45** | 25 | 5 | −7 (5 Phương án B; 2 bị loại: 038, 113) |
| People | 95 | 65 | 23 | 5 | 2 | 0 | 50 | 38 | 5 | **47** | 43 | 5 | −3 (5 Phương án B; +1 mới BR-PPL-023; +1 BR-PPL-019 khôi phục, R3-07) |
| Time & Attendance | 80 | 61 | 16 | 3 | 0 | 0 | 61 | 19 | 0 | **55** | 25 | 0 | −6 (7 Phương án B, 1 connector; +1 BR-ATT-063; +1 BR-ATT-075 khôi phục, R3-06) |
| Payroll | 91 | 72 | 12 | 3 | 4 | 0 | 72 | 12 | 3 | **71** | 17 | 3 | −1 (5 Phương án B; +4 mới BR-PAY-065, 085–087) |
| Recruitment | 63 | 42 | 20 | 1 | 0 | 0 | 0 | 62 | 1 | **0** | 62 | 1 | 0 (toàn bộ module là P2) |
| SaaS Platform | 90 | 55 | 25 | 6 | 4 | 1 | 46 | 39 | 4 | **42** | 44 | 4 | −4 (5 Phương án B, 1 connector; +1 BR-SAAS-073; +2 mới BR-SAAS-090/091; +1 BR-SAAS-037 khôi phục, R3-08; −2 hóa đơn điện tử BR-SAAS-014/015, R3-01) |
| **Tổng** | **494** | **354** | **106** | **23** | **11** | **5** | **281** | **191** | **18** | **260** | **216** | **18** | **−21** |
| **Tổng không tính Tuyển dụng** | **431** | **312** | **86** | **22** | **11** | **5** | **281** | **129** | **17** | **260** | **154** | **17** | **−21** |

**Vòng 2 và 3 đưa P1 từ 281 xuống 260 như thế nào (giảm ròng −21):**

| Thay đổi | Ảnh hưởng đến P1 |
|---|---|
| Cắt giảm theo Phương án B (29 mục được liệt kê, 2 mục được giữ bởi các quyết định cụ thể sau đó) | −27 |
| BR tích hợp trực tuyến chuyển sang P2 (BR-ATT-021, BR-SAAS-065) | −2 |
| Nâng lên P1 (BR-ATT-063, BR-SAAS-073) | +2 |
| BR mới của R2-02 xếp P1 (BR-PPL-023, BR-PAY-065, 085, 086, 087, BR-SAAS-090, 091) | +7 |
| Dòng P1 bị loại do chuyển sang SaaS; năng lực vẫn là P1 trong BR-SAAS-038/039/043/046 (BR-CORE-038, 113) | −2 |
| Tạm tính sau vòng 2 (P1 = 259) | −22 |
| Vòng 3: khôi phục về P1 (BR-ATT-075 R3-06, BR-PPL-019 R3-07, BR-SAAS-037 R3-08) | +3 |
| Vòng 3: hóa đơn điện tử của nhà cung cấp chuyển sang P2 (BR-SAAS-014/015, R3-01) | −2 |
| **Ròng (P1 = 260)** | **−21** |

Hai BR mới được xếp P2: BR-CORE-088 và BR-PPL-039. Chi tiết theo từng ID: §11.

Ghi chú vòng 1 (không đổi). Baseline Must của R1 là **316**. Con số này bao gồm sáu BR Must về giữ khóa kỳ của X-03 (BR-ATT-079, 087, 088 và BR-PAY-082, 083, 084), vẫn là P1. Trong R1:
- bốn BR Should cũ được nâng lên P1 (BR-ATT-005, BR-ATT-049, BR-PAY-091, BR-SAAS-087)
- hai BR của D-09 được thêm vào (BR-SAAS-088, 089)
- mười mục S/C của T&A gắn nhãn "MVP" chuyển sang P2.

### 3.2 Yêu cầu phi chức năng (NFR), R1 so với R3 (cuối cùng; vòng 3 không thay đổi NFR nào)

| Module | Tổng NFR | M cũ | S cũ | R1 P1 | R1 P2 | **R3 P1** | R3 P2 | R3 P3 |
|---|---|---|---|---|---|---|---|---|
| Core | 15 | 12 | 3 | 13 | 2 | **13** | 2 | 0 |
| People | 12 | 10 | 2 | 10 | 2 | **10** | 2 | 0 |
| Time & Attendance | 11 | 9 | 2 | 9 | 2 | **7** | 4 | 0 |
| Payroll | 13 | 11 | 2 | 11 | 2 | **11** | 2 | 0 |
| Recruitment | 11 | 8 | 3 | 0 | 11 | **0** | 11 | 0 |
| SaaS Platform | 22 | 19 | 3 | 18 | 4 | **17** | 5 | 0 |
| **Tổng** | **84** | **69** | **15** | **61** | **23** | **58** | **26** | **0** |

R1: NFR-CORE-009 (thông lượng import) được nâng lên P1 để phục vụ việc nạp số dư đầu kỳ, và NFR-SAAS-019 (xuất toàn bộ dữ liệu tenant) chuyển sang P2 cùng với offboarding tenant. R2: NFR-ATT-001 (tốc độ nhận dữ liệu chấm công), NFR-ATT-004 (bộ đệm offline của connector) và NFR-SAAS-004 (đột biến dữ liệu chấm công) chuyển sang P2 cùng với connector trực tuyến (R2-01). Do đó Spike SP-3 và con số của X-05 không còn là điều kiện chặn (gate) của P1.

### 3.3 Thành phần của 260 BR P1

Các nhóm dựa theo cột lý do. Phần chênh lệch R2/R3 được phân bổ thủ công từ các nhóm của R1, nên các con số này mang tính tham khảo, không phải đầu ra của awk.

| Nhóm | Core | People | T&A | Payroll | SaaS | **Tổng R3** | Tổng R1 |
|---|---|---|---|---|---|---|---|
| Theo luật định (luật VN, bao gồm PDPL) | 9 | 34 | 22 | 36 | 9 | **110** | 107 |
| Import số dư đầu kỳ, so sánh & tính phí (D-09/D-10, R2-03) | 5 | 0 | 2 | 2 | 7 | **16** | 16 |
| Baseline bảo mật, quyền riêng tư & kiểm toán | 9 | 1 | 0 | 2 | 9 | **21** | 24 |
| ESS web | 2 | 3 | 3 | 2 | 2 | **12** | 13 |
| Nền tảng / xử lý cốt lõi mà các đầu ra theo luật định phụ thuộc vào (bao gồm giữ khóa kỳ X-03, bộ lập lịch job, nhóm lương) | 20 | 9 | 28 | 29 | 15 | **101** | 121 |
| **Tổng** | 45 | 47 | 55 | 71 | 42 | **260** | 281 |

### 3.4 Các năng lực baseline (R2-18)

Cột **Baseline** trong §9 đánh dấu các năng lực luôn bật, bất kể tenant đăng ký module nào (gap-analysis GAP-060, quyết định R2-18, được thực thi bởi BR-SAAS-091). Chúng phải được xây dựng và kiểm thử trong mọi cấu hình tenant, bao gồm "Payroll không có T&A" và "People không có Payroll". Cột này có hai giá trị:
- `B` nghĩa là toàn bộ yêu cầu là baseline.
- `API` nghĩa là chỉ giao diện đọc của nó là dịch vụ luôn bật (R2-C9; ý kiến số 2 của các kiến trúc sư).

| Năng lực baseline (R2-18, BR-SAAS-091) | Các ID được đánh dấu trong §9 | Số lượng |
|---|---|---|
| Core (toàn bộ module Core P1) | mọi dòng Core có phase P1 | 45 BR + 13 NFR (`B`) |
| Dịch vụ định danh và xuất dữ liệu chuyển từ Core sang SaaS (R2-02) | BR-SAAS-038 chính sách mật khẩu, 039 MFA, 043 phiên đăng nhập (trước đây là BR-CORE-038); BR-SAAS-046 xuất báo cáo và xuất danh sách (trước đây là BR-CORE-113); BR-SAAS-091 quy tắc quyền sử dụng baseline | 5 BR (`B`) |
| Thông tin cơ bản của People (cá nhân, quan hệ lao động, định danh, hồ sơ thuế/BHXH bao gồm tình trạng cư trú thuế, ngân hàng, người phụ thuộc, phân công, bản ghi hợp đồng, bản ghi lương và việc kiểm tra hợp lệ) | BR-PPL-001–004, 010, 011, 013–017, 020, 021, 023, 030–032, 040, 041, 044, 050; NFR-PPL-001, 002, 005, 007, 008 | 21 BR + 5 NFR (`B`) |
| Tham số pháp lý (bao gồm template ngày lễ quốc gia) | BR-PAY-010, NFR-PAY-007 (vùng lương tối thiểu, BR-CORE-006, đã nằm trong Core) | 1 BR + 1 NFR (`B`) |
| Thang/bậc lương và danh mục thành phần lương (gap-analysis §5 #7, do Payroll sở hữu) | BR-PAY-001, 002 (ngạch/bậc, BR-CORE-021, nằm trong Core). Bản thân bảng thang lương chưa có dòng BR, vì vậy dòng đó phải được đánh dấu `B` khi được thêm vào | 2 BR (`B`) |
| Import chấm công từ Excel (tạo ra Attendance Result chuẩn) | BR-ATT-025 | 1 BR (`B`) |
| **Tổng `B`** | | **75 BR + 19 NFR** |
| Dịch vụ luôn bật ở mức API | API đọc lịch ngày lễ và lịch làm việc: T&A **ATT-P-16** (MoSCoW S → M, R2-02), dựa trên BR-ATT-001, 002 và 011 (P1). Công cụ mô phỏng gross↔net: Payroll **IF-PAY-P-15** dựa trên BR-PAY-124 (P2), nên chỉ trở thành luôn bật khi được xây dựng ở P2 | 4 BR (`API`) |

## 4. Vì sao P1 là 260 chứ không phải 180–200

**Không thể đạt mục tiêu mà không vi phạm quy tắc tuân thủ.** Ước tính 180–200 trong X-04 giả định sẽ tiết kiệm được nhờ hoãn đánh giá, đào tạo, chi phí, công cụ thiết kế báo cáo và white-label. Trong các BRD module, các mục đó hầu như đều đã là `S`/`C`: chỉ một mục (BR-PPL-094, đánh giá thử việc) là Must. Mức cắt giảm thực sự đến từ:
- Tuyển dụng (−42 BR Must, vốn đã nằm ngoài con số 316)
- mobile và chấm công check-in (OD-9)
- tự động hóa thương mại
- các tính năng People không theo luật định trong §5
- ở vòng 2, các mục cắt giảm của Phương án B và việc loại bỏ tích hợp trực tuyến.

Sau đó, đợt đồng bộ một-chủ-sở-hữu R2-02 đã thêm 7 BR P1 để lấp các khoảng trống trong chuỗi xử lý hoặc trong phạm vi luật định (tình trạng cư trú thuế, kiểm tra 183 ngày, nhóm lương, bộ lập lịch job, và các mục khác). Vòng 3 khôi phục ba mục nhỏ thuộc luật định hoặc PDPL (BR-ATT-075, BR-PPL-019, BR-SAAS-037) và đưa hóa đơn điện tử của chính nhà cung cấp ra ngoài (BR-SAAS-014/015, R3-01).

Ba yếu tố tạo nên mức sàn:

1. **110 BR P1 là theo luật định.** BHXH, thuế TNCN (hằng tháng, quyết toán, chứng từ điện tử, tình trạng cư trú), hợp đồng, chấm dứt hợp đồng, thời hạn thanh toán khi nghỉ việc, luật về làm thêm giờ và nghỉ phép, sổ quản lý lao động và báo cáo, và PDPL không thể cắt giảm theo quy tắc của sponsor. Riêng Payroll có 36 mục. Hóa đơn điện tử của chính nhà cung cấp được đáp ứng bên ngoài sản phẩm (R3-01).
2. **Khoảng 101 BR là chuỗi xử lý mà các đầu ra đó cần.** Bao gồm pháp nhân → tổ chức → cá nhân → phân công → lương → nhóm lương → lịch làm việc → bảng công → khóa kỳ (bao gồm giữ khóa X-03) → kỳ tính lương → ngân hàng/GL, và bộ lập lịch job để chạy chúng.
3. **D-09 làm tăng khối lượng công việc.** Không có Oracle, việc go-live giữa năm phụ thuộc vào số dư đầu kỳ từ Excel (16 BR, bao gồm phần so sánh của R2-03).

**Phương án B — đã áp dụng ở vòng 2 (R2-19).** 29 BR này được liệt kê là không theo luật định, mỗi mục đều có giải pháp thay thế trong Phase 1. 27 mục chuyển sang P2 ở vòng 2; hai mục vẫn ở P1 vì một quyết định vòng 2 cụ thể hơn, đưa ra sau đó, bao trùm chúng. Vòng 3 khôi phục thêm ba mục (BR-ATT-075, BR-PPL-019, BR-SAAS-037), nên **còn 24 mục ở P2**.

| Module | Các ID | Trạng thái R2 | Giải pháp thay thế trong Phase 1 |
|---|---|---|---|
| Core (5) | BR-CORE-051, 053, 058, 071, 083 | tất cả P2 | Luồng phê duyệt do nhà cung cấp seed sẵn, chỉ tuần tự; template và danh mục mã do nhà cung cấp duy trì; không xác định lại người phê duyệt khi tái cơ cấu |
| People (5) | BR-PPL-005, 018, 022, 133 → P2; **BR-PPL-019 quay lại P1 bản tối giản (R3-07)** | 4 P2, 1 khôi phục | HR nhập trực tiếp các thay đổi; tài liệu là file đính kèm thông thường; lựa chọn thâm niên do HR điều chỉnh; giữ một trường trình độ chuyên môn cho sổ quản lý lao động (§11.4 R2-C5) |
| T&A (8) | BR-ATT-026, 034, 043, 066, 070, 083 → P2; **BR-ATT-037 vẫn P1**; **BR-ATT-075 quay lại P1 (R3-06)** | 6 P2, 2 ở P1 | Một chính sách cho mỗi pháp nhân; làm thêm giờ chỉ theo phê duyệt trước; công tác và WFH là mã chấm công. BR-ATT-037 (admin chỉnh sửa bảng công) là phần "nhập liệu thủ công" mà R2-01 giữ ở P1 |
| Payroll (6) | BR-PAY-021, 073, 090, 112, 114 → P2; **BR-PAY-102 vẫn P1** | 5 P2, 1 được giữ | Phạm vi lương net cố định; tạm ứng qua loại kỳ lương Advance hoặc dưới dạng dữ liệu đầu vào biến đổi; chi phí theo trung tâm chi phí mặc định. BR-PAY-102 (phiếu lương PDF có mật khẩu) được giữ theo R2-17 |
| SaaS (5) | BR-SAAS-009, 013, 036, 053 → P2; **BR-SAAS-037 quay lại P1 bản tối giản (R3-08)** | 4 P2, 1 khôi phục | Bật/tắt bằng cấu hình; báo cáo số lượng nhân viên thủ công để tính phí; quy trình break-glass hai người thủ công; nhật ký truy cập của operator được xuất khi có yêu cầu |

**Khuyến nghị.** Ước lượng 260 BR bằng story point trước khi cam kết 16 tuần. Phương án B nay đã dùng hết, nên **không còn dự phòng cắt giảm phạm vi nào** ngoài phạm vi luật định. R2-C1 (hóa đơn điện tử của nhà cung cấp) đã được sử dụng ở vòng 3 (R3-01). Đòn bẩy còn lại là lùi ngày go-live.

## 5. Các mục cắt giảm đáng chú ý (M cũ → P2, không tính Tuyển dụng; danh sách R1, phần bổ sung vòng 2 ở §11)

| Lĩnh vực | Chuyển sang P2 | Giải pháp thay thế trong Phase 1 |
|---|---|---|
| Tổ chức & vị trí | BR-CORE-013/014/015 gói tái cơ cấu; BR-CORE-022/023, BR-PPL-038 kiểm soát vị trí | Chỉnh sửa tổ chức theo ngày hiệu lực; phân công theo chức danh + đơn vị tổ chức |
| Định danh | BR-CORE-039 + BR-SAAS-040 SSO (OIDC); BR-SAAS-035 impersonation | Đăng nhập nội bộ + MFA; bộ phận hỗ trợ làm việc trong phạm vi quyền được đồng ý cấp |
| Workflow | BR-CORE-055 ủy quyền, BR-CORE-057 leo thang theo SLA, BR-PPL-122 | HR chuyển giao người phê duyệt (BR-CORE-056) |
| Nghiệp vụ nhân sự | BR-PPL-060–063 checklist, BR-PPL-061 cổng thông tin trước khi nhận việc, BR-PPL-076 theo dõi sau nghỉ việc, BR-PPL-006 dòng thời gian, BR-PPL-033 kiêm nhiệm, BR-PPL-121 màn hình nhóm MSS | Checklist thủ công; phê duyệt qua hộp thư của Core |
| Kỷ luật & khen thưởng | BR-PPL-080/081 quy trình kỷ luật, BR-PPL-085 khen thưởng → lương, BR-PPL-094 đánh giá thử việc | Quy trình offline với tài liệu được tải lên; khen thưởng là dữ liệu đầu vào tính lương; kết quả thử việc qua BR-PPL-052 |
| T&A | BR-ATT-081 dashboard MSS (dữ liệu hồ sơ BHXH BR-ATT-063 nằm ở đây trong R1; quay lại P1 theo R2-07); cùng các mục S/C gắn nhãn MVP: chấm công trên web (024), nghỉ bù (050), lịch làm việc linh hoạt (012), đổi ngày (004), lịch nhóm (069), xác nhận bảng công (077/078), tự động leo thang (084), giới hạn làm thêm giờ liên pháp nhân (047), theo dõi ngày ốm hưởng BHXH (064) | Máy chấm công + Excel; giới hạn làm thêm giờ theo từng pháp nhân |
| Payroll | BR-PAY-006 IDE công thức | Kiểm tra cú pháp trong BR-PAY-005 |
| Thương mại | BR-SAAS-006 gói/phiên bản, 010 bảng giá, 011 tính theo tỷ lệ thời gian, 017 nhắc nợ, 018/019 xuất & xóa dữ liệu khi offboarding tenant | Giá hợp đồng theo module; thu tiền thủ công; offboarding được xây dựng trước khi có tenant đầu tiên rời đi |
| Nền tảng | BR-SAAS-027 trường tùy chỉnh, 047 kho dữ liệu báo cáo riêng, 068 integration hub, 082 job xóa dữ liệu theo thời hạn lưu trữ (073 so sánh chạy song song nằm ở đây trong R1; P1 theo R2-03) | Tích hợp dựa trên file; hạn mức theo từng tenant; không có dữ liệu hết hạn lưu trữ trong năm pilot |
| Mobile (vốn đã là S) | BR-SAAS-076/077/078, BR-ATT-023, BR-CORE-059, NFR-ATT-009/011, NFR-SAAS-022 | ESS web responsive |

## 6. Các yêu cầu được thay đổi hoặc thêm mới do D-09 (Oracle → import số dư đầu kỳ)

| ID | Thay đổi | Phase mới | Vị trí |
|---|---|---|---|
| BR-CORE-081 | Ánh xạ đơn vị hành chính cũ→mới nay phục vụ import số dư đầu kỳ và CCCD/CMND cũ, không phải Oracle | P1 | 01-core.md (đã sửa) |
| BR-CORE-114 | "Chế độ chuyển đổi dữ liệu Oracle" → **chế độ import số dư đầu kỳ**: nạp hàng loạt, bỏ qua workflow, ID tham chiếu bên ngoài, báo cáo đối soát | P1 | 01-core.md (đã sửa) |
| BR-CORE-110/111/112 | Nội dung không đổi; nay là P1 vì import số dư đầu kỳ dựa vào chúng | P1 | — |
| NFR-CORE-009 | Thông lượng import được nâng từ S → P1 | P1 | chỉ xếp hạng |
| NFR-PPL-007 | "Import lịch sử từ hệ thống cũ (Oracle)" → **import dữ liệu nhân viên đầu kỳ**: cá nhân, quan hệ lao động, phân công, lương, hợp đồng, người phụ thuộc, ngân hàng hiện tại, cộng với lịch sử lương cần cho việc tính bình quân trợ cấp thôi việc | P1 | 02-people.md (đã sửa) |
| BR-ATT-068 | Không đổi; đây là bút toán sổ cái dùng cho số dư phép đầu kỳ | P1 | — |
| BR-ATT-086 | Số dư phép đầu kỳ (theo loại và năm, bao gồm phép chuyển sang) và số giờ làm thêm lũy kế từ đầu năm để tính giới hạn. Không có bảng công lịch sử. | P1 | 03 (T&A BA thay đổi, 2026-10-16) |
| BR-PAY-091 | Khoản vay của nhân viên được nâng từ S → P1, chỉ trả góp không lãi, để có thể thu hồi số dư vay đầu kỳ | P1 | **Chủ sở hữu: Payroll BA** |
| BR-PAY-122 | Số dư lương đầu kỳ từ Excel: thu nhập chịu thuế lũy kế, thuế TNCN, mức lương đóng BHXH và các khoản đóng; lịch sử BHXH; 6–12 tháng lịch sử lương; các khoản vay và tạm ứng còn dư | P1 | 04 (Payroll BA thay đổi, 2026-10-16) |
| BR-SAAS-031 | Ghi chú về sandbox: không còn cần cho chuyển đổi dữ liệu | P2 | 06 (đã sửa) |
| BR-SAAS-069 | Mở rộng danh sách template: thu nhập/thuế TNCN/BHXH lũy kế theo tháng, lịch sử đóng BHXH, số dư phép, khoản vay | P1 | 06 (đã sửa) |
| BR-SAAS-071 | ETL từ Oracle → **import số dư đầu kỳ cho go-live giữa năm** | P1 | 06 (đã sửa) |
| BR-SAAS-072 | Đối soát nay kiểm tra so với **các tổng kiểm soát do khách hàng cung cấp** (bảng lương cuối cùng của hệ thống cũ, C12-TS của VSS, báo cáo phép) | P1 | 06 (đã sửa) |
| BR-SAAS-073 | Bỏ chạy song song → so sánh tùy chọn với bảng lương Excel do khách hàng cung cấp | P2 ở R1 → **P1 ở R2 (R2-03)** | 06 (đã sửa); phase theo §9 (quy tắc banner, §12) |
| **BR-SAAS-088** | **Mới:** tháng chuyển đổi go-live theo từng pháp nhân; số dư đầu kỳ bị khóa sau kỳ lương đầu tiên được phê duyệt; các điều chỉnh sau đó chỉ thực hiện qua các lô điều chỉnh có kiểm toán | P1 | 06 (đã thêm) |
| **BR-SAAS-089** | **Mới:** thu nhập/thuế TNCN hằng tháng trước go-live của từng nhân viên để tính thuế TNCN lũy kế hằng tháng, quyết toán 05/QTT và chứng từ điện tử cả năm | P1 | 06 (đã thêm) |
| NFR-SAAS-008 | Đã bỏ điều khoản về thông tin đăng nhập Oracle | P1 | 06 (đã sửa) |
| P9 (06 §3) | Quy trình chuyển đổi dữ liệu Oracle → quy trình import số dư đầu kỳ | — | 06 (đã sửa) |

Thay đổi liên quan cho D-10: BR-SAAS-012 nay định nghĩa nhân viên tính phí là người đang làm việc tại thời điểm cuối tháng, theo từng pháp nhân (06, đã sửa).

## 7. Việc cần theo dõi cho các chủ sở hữu khác (các file Lead BA không sửa)

| Chủ sở hữu | Nội dung |
|---|---|
| T&A BA (03) | BR-ATT-024 chấm công trên web → P2 (OD-9). Ghi chú của BR-ATT-025 "cũng là công cụ chuyển đổi dữ liệu" → "cũng dùng cho import số dư đầu kỳ". Cột Phase của BR-ATT-004/012/047/050/064/069/077/078/084 → P2. BR-ATT-063, BR-ATT-081 → P2. |
| Payroll BA (04) | BR-PAY-091 → P1 (tối giản, trả góp không lãi) để có thể thu hồi số dư vay đầu kỳ; BR-PAY-006 → P2. |
| Integration analyst (gap-analysis) | Trỏ lại các gap đang dẫn chiếu đến chuyển đổi dữ liệu Oracle, chạy song song hoặc BR-SAAS-071/073; kiểm tra các gap liên quan đến những mục bị hoãn ở §5. |
| Kiến trúc sư giải pháp / dữ liệu | Bỏ staging cho chuyển đổi dữ liệu; thêm lô import số dư đầu kỳ và cơ chế khóa (BR-SAAS-088); kho dữ liệu báo cáo (BR-SAAS-047) không còn cần ở P1. |
| Tất cả BA module (vòng 2) | Hoàn thành 2026-10-16: đã thêm banner phase vào 01–06 và đồng bộ cột Phase của T&A (§12). Phase chỉ được xác định tại §9. |

## 8. Các mục sponsor phải xác nhận lại (kết quả vòng 2)

| # | Mục (R1) | Kết quả (vòng 2, 2026-10-16) |
|---|---|---|
| 1 | P1 = 281 BR so với mục tiêu 180–200; Phương án B? | Phương án B được phê duyệt (R2-19) và loại bỏ tích hợp trực tuyến (R2-01) → P1 = 254; sau đồng bộ R2-02 (+7 mới, −2 chuyển đi) P1 = 259; vòng 3 (+3 khôi phục, −2 hóa đơn điện tử) **P1 = 260**. Vẫn cần ước lượng story point. |
| 2 | Không chạy song song: so sánh một tháng (BR-SAAS-073)? | **P1** (R2-03). |
| 3 | Hoãn SSO | Xác nhận P2 (R2-04). |
| 4 | Hoãn quy trình kỷ luật | Xác nhận P2 (R2-05). |
| 5 | Hoãn chấm công trên web | Xác nhận P2 (R2-06). Các địa điểm không có máy chấm công dùng import Excel. |
| 6 | Hợp đồng lao động điện tử | Xác nhận P2 trừ khi có nghị định bắt buộc (R2-10). |
| 7 | Đa tiền tệ | Xác nhận P2, không có hợp đồng bằng USD (R2-09). |
| 8 | Giới hạn làm thêm giờ năm liên pháp nhân | Xác nhận P2, chờ ý kiến pháp lý (R2-08, R2-16). |
| 9 | D-10 tính phí hai lần cho hai pháp nhân | Chưa được trả lời; D-10 giữ nguyên (§7.2). Nhân viên đang tạm hoãn hợp đồng không bị tính phí (R2-12a). |
| 10 | Khoản vay được nâng lên P1 | Không phản đối; vẫn là P1. |
| 11 | Chủ sở hữu dữ liệu số dư đầu kỳ | Khách hàng chuẩn bị, tư vấn triển khai kiểm tra, trưởng bộ phận C&B của khách hàng ký xác nhận (R2-13). |
| 12 | Kỳ lương Tết/tháng 13 (tháng 2/2027) | Kỳ lương thưởng/ngoài chu kỳ với thuế TNCN chính xác vẫn là P1; quy tắc chính sách Tết là P2; kiểm thử với dữ liệu mẫu (R2-14). |

## 9. Bảng xếp hạng đầy đủ

### 9.1 Core — [01-core.md](modules/01-core.md)

| ID | Tiêu đề ngắn | Độ ưu tiên cũ | Phase mới | Lý do | Baseline |
|---|---|---|---|---|---|
| BR-CORE-001 | Tập đoàn & 1..n pháp nhân | M | P1 | Nền tảng: tính lương đa pháp nhân | B |
| BR-CORE-002 | Thuộc tính pháp nhân (MST, mã đơn vị BHXH, vùng) | M | P1 | Mã định danh theo luật định cho kê khai BHXH/thuế TNCN | B |
| BR-CORE-003 | Kiểm tra định dạng MST | M | P1 | Định dạng theo luật định | B |
| BR-CORE-004 | Thuộc tính pháp nhân theo ngày hiệu lực | M | P1 | Luật định: kê khai đúng theo kỳ | B |
| BR-CORE-005 | Chi nhánh / địa điểm làm việc | M | P1 | Nền tảng: vùng lương tối thiểu, chi nhánh kê khai thuế TNCN (dữ liệu geo-fence là P2) | B |
| BR-CORE-006 | Bảng vùng lương tối thiểu | M | P1 | Luật định: lương tối thiểu vùng | B |
| BR-CORE-007 | Tính lại vùng + LocationRegionChanged | M | P1 | Luật định: kiểm tra lương tối thiểu | B |
| BR-CORE-008 | Quan hệ giữa các pháp nhân | C | P3 | Could; không thiết yếu cho pilot |  |
| BR-CORE-010 | Cây tổ chức phân cấp, loại đơn vị | M | P1 | Cấu trúc cốt lõi | B |
| BR-CORE-011 | Thuộc tính đơn vị tổ chức | M | P1 | Cấu trúc cốt lõi | B |
| BR-CORE-012 | Tổ chức theo ngày hiệu lực (as-of) | M | P1 | Cốt lõi: đảm bảo đúng cho hồi tố (retro) và báo cáo | B |
| BR-CORE-013 | Gói tái cơ cấu (đánh giá tác động, phê duyệt) | M | P2 | Nâng cao; P1 dùng chỉnh sửa theo ngày hiệu lực |  |
| BR-CORE-014 | Ánh xạ gộp/tách vị trí/nhân viên | M | P2 | Phụ thuộc BR-CORE-013 |  |
| BR-CORE-015 | Quyền & thông báo tái cơ cấu hồi tố | M | P2 | Phụ thuộc BR-CORE-013 |  |
| BR-CORE-016 | Sơ đồ tổ chức tương tác | S | P2 | Should; tiện ích |  |
| BR-CORE-017 | Tuyến báo cáo ma trận / chức năng | C | P3 | Could |  |
| BR-CORE-020 | Họ công việc & chức danh (cờ nặng nhọc, độc hại) | M | P1 | Cờ theo luật định quyết định chế độ phép/nghỉ hưu | B |
| BR-CORE-021 | Ngạch / bậc với khoảng tham chiếu | M | P1 | Nền tảng cho thang lương | B |
| BR-CORE-022 | Vị trí như suất định biên có ngân sách | M | P2 | Cắt giảm: P1 phân công theo chức danh + đơn vị tổ chức (vị trí là tùy chọn) |  |
| BR-CORE-023 | Thiết lập chế độ kiểm soát vị trí | M | P2 | Phụ thuộc BR-CORE-022 (P2) |  |
| BR-CORE-024 | Tình trạng chiếm giữ vị trí bao gồm tuyển dụng | S | P2 | Should; cần Tuyển dụng (P2) |  |
| BR-CORE-025 | Trung tâm chi phí & mặc định theo đơn vị | M | P1 | Cần cho xuất GL | B |
| BR-CORE-026 | Danh mục mã loại nhân viên | M | P1 | Quyết định quy tắc lương/BHXH | B |
| BR-CORE-030 | Quản lý người dùng | M | P1 | Truy cập cốt lõi | B |
| BR-CORE-031 | Quyền chức năng & vai trò | M | P1 | Truy cập cốt lõi | B |
| BR-CORE-032 | Phạm vi dữ liệu của vai trò | M | P1 | Truy cập cốt lõi; bảo mật thông tin lương | B |
| BR-CORE-033 | Phạm vi suy diễn LINE_MANAGER / SELF | M | P1 | Cốt lõi: ESS/phê duyệt không cần thao tác quản trị | B |
| BR-CORE-034 | Che dữ liệu nhạy cảm ở cấp trường | M | P1 | Luật định: dữ liệu nhạy cảm theo PDPL | B |
| BR-CORE-035 | Thực thi phạm vi phía server | M | P1 | Baseline bảo mật | B |
| BR-CORE-036 | Cấp vai trò có thời hạn kèm phê duyệt | S | P2 | Should |  |
| BR-CORE-037 | Quy tắc phân tách nhiệm vụ | S | P2 | Should |  |
| BR-CORE-038 | Đăng nhập nội bộ, chính sách mật khẩu, MFA, phiên đăng nhập | M | Removed | Bị loại — thuộc saas-platform: BR-SAAS-038 (chính sách mật khẩu), 039 (MFA), 043 (phiên đăng nhập), tất cả P1 + baseline (R2-02, 2026-10-16) |  |
| BR-CORE-039 | SSO OIDC/SAML/SCIM | M/S/C | Removed | Bị loại — thuộc saas-platform: BR-SAAS-040/041/042 (SSO P2, SCIM P3) (R2-02, 2026-10-16) |  |
| BR-CORE-040 | Ủy quyền truy cập ("act as") | C | P3 | Could |  |
| BR-CORE-041 | Truy cập hỗ trợ break-glass có ghi log | M | P1 | Baseline bảo mật/PDPL | B |
| BR-CORE-042 | API client của tenant | S | Removed | Bị loại — thuộc saas-platform: BR-SAAS-056 API client (P2) (R2-02, 2026-10-16) |  |
| BR-CORE-050 | Workflow engine chung / loại quy trình | M | P1 | Engine cốt lõi cho mọi phê duyệt | B |
| BR-CORE-051 | Định nghĩa workflow không cần code | M | P2 | Cắt theo Phương án B (R2-19): luồng phê duyệt do nhà cung cấp seed sẵn theo loại quy trình; tenant thay đổi qua bộ phận hỗ trợ (R2, 2026-10-16) |  |
| BR-CORE-052 | Bộ xác định người phê duyệt | M | P1 | Engine cốt lõi | B |
| BR-CORE-053 | Loại bước (nhánh song song là P2) | M | P2 | Cắt theo Phương án B (R2-19): engine P1 chỉ có các bước tuần tự với một người phê duyệt (R2, 2026-10-16) |  |
| BR-CORE-054 | Thao tác workflow | M | P1 | Engine cốt lõi | B |
| BR-CORE-055 | Người dùng tự ủy quyền phê duyệt | M | P2 | Cắt giảm: HR chuyển giao (056) xử lý trường hợp vắng mặt |  |
| BR-CORE-056 | Chuyển giao & người phê duyệt dự phòng | M | P1 | Cốt lõi: không để phê duyệt bị treo | B |
| BR-CORE-057 | Nhắc nhở & leo thang theo SLA | M | P2 | Tiện ích; đôn đốc thủ công trong pilot |  |
| BR-CORE-058 | Snapshot / xác định lại chuỗi người phê duyệt | M | P2 | Cắt theo Phương án B (R2-19): không xác định lại khi tái cơ cấu; HR chuyển giao (BR-CORE-056) xử lý (R2, 2026-10-16) |  |
| BR-CORE-059 | Phê duyệt từ ứng dụng di động / e-mail / liên kết Zalo | S | P2 | Mobile & các kênh là P2 (OD-9) |  |
| BR-CORE-060 | Sự kiện workflow + hộp thư Việc của tôi | M | P1 | Cốt lõi: hộp thư phê duyệt (ESS web) | B |
| BR-CORE-061 | Lịch sử phê duyệt bất biến | M | P1 | Bằng chứng kiểm toán | B |
| BR-CORE-070 | Dịch vụ thông báo (chỉ trong ứng dụng + e-mail) | M/S/C | P1 | Cốt lõi; kênh P1 = chỉ trong ứng dụng + e-mail (R2-01) | B |
| BR-CORE-071 | Template thông báo VI/EN | M | P2 | Cắt theo Phương án B (R2-19): template VI/EN do nhà cung cấp duy trì, tenant không ghi đè (R2, 2026-10-16) |  |
| BR-CORE-072 | Tùy chọn kênh của người dùng | S | P2 | Should |  |
| BR-CORE-073 | Lập lịch, bản tổng hợp, dự phòng, theo dõi | S | P2 | Should |  |
| BR-CORE-074 | Không có dữ liệu nhạy cảm dạng rõ trong thông báo | M | P1 | PDPL; phiếu lương PDF có mật khẩu là ngoại lệ đã thống nhất (R2-17) | B |
| BR-CORE-075 | Thông báo nội bộ | C | P3 | Could |  |
| BR-CORE-080 | Đơn vị hành chính (34 tỉnh, xã/phường) | M | P1 | Dữ liệu gốc theo luật định | B |
| BR-CORE-081 | Ánh xạ đơn vị hành chính cũ→mới | M | P1 | Cần cho import số dư đầu kỳ & giấy tờ định danh cũ (thay đổi theo D-09) | B |
| BR-CORE-082 | Danh mục mã toàn cục (ngân hàng, dân tộc, loại giấy tờ) | M | P1 | Dữ liệu gốc theo luật định | B |
| BR-CORE-083 | Danh mục mã tùy chỉnh của tenant | M | P2 | Cắt theo Phương án B (R2-19): danh mục mã do nhà cung cấp duy trì; giá trị của tenant do bộ phận hỗ trợ thêm vào (R2, 2026-10-16) |  |
| BR-CORE-084 | Thiết lập tenant phân cấp | M | P1 | Cấu hình cốt lõi | B |
| BR-CORE-085 | Dịch vụ đánh số | M | P1 | Đánh số hợp đồng/quyết định | B |
| BR-CORE-086 | Trường tùy chỉnh | S | P2 | Should. MoSCoW S → M trong 01-core (R2-02, PM phê duyệt 2026-10-16); phase vẫn là P2 (trường tùy chỉnh bị hoãn) |  |
| BR-CORE-087 | Phân phối dữ liệu gốc kèm thông báo thay đổi | M | P2 | Cập nhật P1 được phát hành kèm các bản release |  |
| BR-CORE-088 | Bảng tỷ giá (dữ liệu gốc) | new (R2-02, 2026-10-16) | P2 | Tính lương đa tiền tệ là P2 (R2-09); bảng chỉ cần cho trả lương bằng ngoại tệ |  |
| BR-CORE-090 | Kiểm toán thay đổi dữ liệu | M | P1 | Bằng chứng kiểm toán / thanh tra | B |
| BR-CORE-091 | Ghi log sự kiện bảo mật | M | P1 | Baseline bảo mật | B |
| BR-CORE-092 | Nhật ký kiểm toán chống giả mạo | M | P1 | Bằng chứng kiểm toán | B |
| BR-CORE-093 | Tìm kiếm & xuất nhật ký kiểm toán | M | P1 | Quyền truy cập cho kiểm toán viên / thanh tra | B |
| BR-CORE-094 | Thời hạn lưu kiểm toán cấu hình được theo loại | M | P2 | P1 dùng mức tối thiểu cố định của nền tảng |  |
| BR-CORE-100 | Dịch vụ tài liệu (tải lên, quét, lưu trữ) | M | P1 | Cốt lõi | B |
| BR-CORE-101 | Truy cập file theo phạm vi bản ghi | M | P1 | Bảo mật | B |
| BR-CORE-102 | Template tài liệu → PDF | M | P1 | Hợp đồng/quyết định | B |
| BR-CORE-103 | Adapter ký điện tử | S | P2 | Should (P1 nếu hợp đồng điện tử trở thành bắt buộc) |  |
| BR-CORE-104 | Lưu trữ/xóa theo loại thời hạn lưu trữ | S | P2 | Should |  |
| BR-CORE-110 | Framework import | M | P1 | Cần cho import số dư đầu kỳ (D-09) | B |
| BR-CORE-111 | Import bất đồng bộ, chạy thử (dry-run), file lỗi | M | P1 | Cần cho import số dư đầu kỳ (D-09) | B |
| BR-CORE-112 | Sinh template Excel | M | P1 | Cần cho import số dư đầu kỳ (D-09) | B |
| BR-CORE-113 | Framework xuất dữ liệu | M | Removed | Bị loại — thuộc saas-platform: BR-SAAS-046 framework xuất báo cáo và xuất danh sách (P1 + baseline); xuất bất đồng bộ BR-SAAS-053 (P2) (R2-02, 2026-10-16) |  |
| BR-CORE-114 | Chế độ import số dư đầu kỳ (trước đây là chế độ chuyển đổi dữ liệu Oracle) | M | P1 | Thay đổi theo D-09 | B |
| BR-CORE-115 | Xuất dữ liệu theo lịch / tích hợp đầu ra | C | P3 | Could |  |
| NFR-CORE-001 | Cô lập tenant bằng RLS | M | P1 | Baseline bảo mật | B |
| NFR-CORE-002 | Kiểm tra phạm vi ≤20 ms | M | P1 | Baseline hiệu năng | B |
| NFR-CORE-003 | Truy vấn tổ chức as-of ≤1 s | M | P1 | Baseline hiệu năng | B |
| NFR-CORE-004 | Thông lượng workflow, không mất task | M | P1 | Toàn vẹn | B |
| NFR-CORE-005 | Độ trễ thông báo | M | P1 | Phát hành phiếu lương | B |
| NFR-CORE-006 | Tính sẵn sàng của đăng nhập | M | P1 | Tính sẵn sàng | B |
| NFR-CORE-007 | Băm mật khẩu & mã hóa | M | P1 | Baseline bảo mật | B |
| NFR-CORE-008 | Đảm bảo ghi kiểm toán | M | P1 | Toàn vẹn | B |
| NFR-CORE-009 | Thông lượng import/export | S | P1 | Được nâng: khối lượng import số dư đầu kỳ (D-09) | B |
| NFR-CORE-010 | Giới hạn file & signed URL | M | P1 | Bảo mật | B |
| NFR-CORE-011 | VI/EN, tìm kiếm không phân biệt dấu | M | P1 | Bản địa hóa | B |
| NFR-CORE-012 | WCAG AA, phê duyệt trên di động | S | P2 | Should; mobile là P2 |  |
| NFR-CORE-013 | Xuất/nhập cấu hình giữa các môi trường | S | P2 | Should |  |
| NFR-CORE-014 | OWASP ASVS L2, giới hạn tần suất (rate limiting) | M | P1 | Baseline bảo mật | B |
| NFR-CORE-015 | Job theo ngày hiệu lực có tính idempotent | M | P1 | Toàn vẹn | B |

### 9.2 People — [02-people.md](modules/02-people.md)

| ID | Tiêu đề ngắn | Độ ưu tiên cũ | Phase mới | Lý do | Baseline |
|---|---|---|---|---|---|
| BR-PPL-001 | Một Person cho mỗi cá nhân (CCCD/hộ chiếu) | M | P1 | Nền tảng: định danh cho BHXH/thuế TNCN | B |
| BR-PPL-002 | Nhiều quan hệ lao động / kiêm nhiệm đồng thời | M | P1 | Làm việc kiêm nhiệm cơ bản giữa các pháp nhân trong P1 (R2-16) | B |
| BR-PPL-003 | Thuộc tính & trạng thái quan hệ lao động | M | P1 | Nền tảng | B |
| BR-PPL-004 | Quan hệ lao động chính cho BHXH & người phụ thuộc | M | P1 | Quan hệ lao động chính quyết định giảm trừ gia cảnh thuế TNCN và đơn vị đóng BHXH duy nhất (R2-16; xem §11 R2-C2) | B |
| BR-PPL-005 | Lịch sử tái tuyển dụng & lựa chọn thâm niên | M | P2 | Cắt theo Phương án B (R2-19): lựa chọn thâm niên khi tái tuyển dụng bằng điều chỉnh số dư phép của HR (BR-ATT-068) (R2, 2026-10-16) |  |
| BR-PPL-006 | Màn hình dòng thời gian của nhân viên | M | P2 | Tiện ích; lịch sử được lưu trong các bản ghi |  |
| BR-PPL-010 | Định danh & dữ liệu cá nhân | M | P1 | Luật định: sổ quản lý lao động, các trường BHXH/thuế TNCN | B |
| BR-PPL-011 | Giấy tờ định danh & ngày hết hạn | M | P1 | Mã định danh theo luật định | B |
| BR-PPL-012 | Xác thực VNeID | S | P2 | Should; connector là P2/P3 |  |
| BR-PPL-013 | Mã số thuế cá nhân (= CCCD) | M | P1 | Luật định: thuế TNCN | B |
| BR-PPL-014 | Số sổ BHXH, thẻ BHYT, nơi KCB | M | P1 | Luật định: BHXH/BHYT | B |
| BR-PPL-015 | Tài khoản ngân hàng & chia tách | M | P1 | Cần cho chi trả lương | B |
| BR-PPL-016 | Thành viên gia đình & người phụ thuộc thuế TNCN | M | P1 | Luật định: giảm trừ gia cảnh thuế TNCN | B |
| BR-PPL-017 | Phê duyệt người phụ thuộc & mẫu đăng ký | M | P1 | Luật định: đăng ký người phụ thuộc | B |
| BR-PPL-018 | Người liên hệ khẩn cấp | M | P2 | Cắt theo Phương án B (R2-19): người liên hệ khẩn cấp được giữ dưới dạng ghi chú văn bản tự do (R2, 2026-10-16) |  |
| BR-PPL-019 | Học vấn & chứng chỉ | M | P1 | Khôi phục bản tối giản theo R3-07: trường trình độ chuyên môn cho sổ quản lý lao động; học vấn/chứng chỉ đầy đủ vẫn là P2 (R3, 2026-10-16) |  |
| BR-PPL-020 | Giấy phép lao động của người lao động nước ngoài | M | P1 | Luật định: hiệu lực giấy phép lao động | B |
| BR-PPL-021 | Thuộc tính tình trạng đặc biệt | M | P1 | Luật định: quyết định việc bảo vệ về làm thêm giờ/nghỉ phép/chấm dứt hợp đồng | B |
| BR-PPL-022 | Yêu cầu thay đổi dữ liệu cá nhân qua ESS | M | P2 | Cắt theo Phương án B (R2-19): HR nhập trực tiếp thay đổi dữ liệu cá nhân; màn hình xem hồ sơ ESS vẫn giữ (BR-PPL-120) (R2, 2026-10-16) |  |
| BR-PPL-023 | Tình trạng cư trú thuế, ngày đến/rời đi | new (R2-02, 2026-10-16) | P1 | Luật định: thuế TNCN cá nhân cư trú và không cư trú (Luật Thuế TNCN; Thông tư 111/2013); hồ sơ thuế thuộc thông tin cơ bản của People (BR-SAAS-091) | B |
| BR-PPL-030 | Phân công theo ngày hiệu lực | M | P1 | Nền tảng | B |
| BR-PPL-031 | Thuộc tính phân công | M | P1 | Nền tảng cho T&A/tính lương | B |
| BR-PPL-032 | Danh mục lý do thay đổi | M | P1 | Nền tảng; quyết định việc ra quyết định nhân sự | B |
| BR-PPL-033 | Kiêm nhiệm vị trí (kiêm nhiệm) | M | P2 | Không thiết yếu cho pilot; phụ cấp qua dữ liệu đầu vào tính lương |  |
| BR-PPL-034 | Biệt phái | S | P2 | Should |  |
| BR-PPL-035 | Quy tắc tạm thời chuyển làm công việc khác (Điều 29) | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PPL-036 | Thay đổi hàng loạt | S | P2 | Should |  |
| BR-PPL-037 | Thay đổi tương lai/hồi tố có cờ retro | M | P1 | Đảm bảo đúng khi tính lương hồi tố |  |
| BR-PPL-038 | Người đương nhiệm vị trí | M | P2 | Phụ thuộc BR-CORE-022 (P2) |  |
| BR-PPL-039 | Thực thi ánh xạ tái cơ cấu khi có core.ReorgActivated | new (R2-02, 2026-10-16) | P2 | Gói tái cơ cấu là P2 (BR-CORE-013) |  |
| BR-PPL-040 | Bản ghi lương theo ngày hiệu lực | M | P1 | Nền tảng cho tính lương & mức lương đóng BHXH | B |
| BR-PPL-041 | Lương so với khung & lương tối thiểu vùng | M | P1 | Luật định: lương tối thiểu | B |
| BR-PPL-042 | Lộ trình nâng bậc | S | P2 | Should |  |
| BR-PPL-043 | Đợt xét duyệt điều chỉnh lương | C | P3 | Could |  |
| BR-PPL-044 | Hiển thị lương theo vai trò | M | P1 | Bảo mật thông tin | B |
| BR-PPL-050 | Loại hợp đồng | M | P1 | Luật định: hợp đồng lao động  (R2-02: baseline, 2026-10-16) | B |
| BR-PPL-051 | Giới hạn thời gian thử việc | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PPL-052 | Kết quả thử việc & hợp đồng | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PPL-053 | Quy tắc 36 tháng / hai hợp đồng xác định thời hạn | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PPL-054 | Theo dõi hợp đồng hết hạn | M | P1 | Luật định: rủi ro tự động chuyển loại hợp đồng |  |
| BR-PPL-055 | Kiểm tra hợp lệ phụ lục hợp đồng | M | P1 | Luật định: Điều 22 |  |
| BR-PPL-056 | Tạm hoãn hợp đồng | M | P1 | Luật định; ảnh hưởng BHXH/T&A |  |
| BR-PPL-057 | Sinh văn bản hợp đồng | M | P1 | Đầu ra HR cốt lõi |  |
| BR-PPL-058 | Hợp đồng lao động điện tử | S→M | P2 | P2 trừ khi có nghị định bắt buộc trước go-live (R2-10 đã xác nhận) |  |
| BR-PPL-059 | Tham chiếu thỏa ước lao động tập thể | C | P3 | Could |  |
| BR-PPL-060 | Template checklist | M | P2 | Không theo luật định; làm thủ công trong pilot |  |
| BR-PPL-061 | Cổng thông tin trước khi nhận việc | M | P2 | Không thiết yếu cho pilot |  |
| BR-PPL-062 | Bàn giao/thanh lý khi nghỉ việc | M | P2 | Không theo luật định; làm thủ công trong pilot |  |
| BR-PPL-063 | Dashboard & nhắc nhở checklist | M | P2 | Phụ thuộc BR-PPL-060 |  |
| BR-PPL-070 | Lý do nghỉ việc → căn cứ pháp lý | M | P1 | Luật định: điều kiện hưởng trợ cấp thôi việc |  |
| BR-PPL-071 | Tính thời hạn báo trước | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PPL-072 | Đơn xin nghỉ việc qua ESS | M | P1 | ESS web (Phase 1) |  |
| BR-PPL-073 | Chặn chấm dứt hợp đồng với đối tượng được bảo vệ | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PPL-074 | Điều kiện hưởng trợ cấp thôi việc / mất việc làm | M | P1 | Luật định: thanh toán khi nghỉ việc |  |
| BR-PPL-075 | Sự kiện chấm dứt hợp đồng & thời hạn thanh toán | M | P1 | Luật định: thanh toán trong 14/30 ngày |  |
| BR-PPL-076 | Theo dõi nghĩa vụ sau khi nghỉ việc | M | P2 | Checklist (P2); báo giảm BHXH do Payroll thực hiện |  |
| BR-PPL-077 | Hỗ trợ cắt giảm lao động hàng loạt | C | P3 | Could |  |
| BR-PPL-080 | Quy trình xử lý kỷ luật | M | P2 | Pilot xử lý kỷ luật offline + tài liệu (R2-05 đã xác nhận) |  |
| BR-PPL-081 | Thời hiệu kỷ luật & quy tắc không phạt tiền | M | P2 | Phụ thuộc BR-PPL-080 (R2-05 đã xác nhận) |  |
| BR-PPL-082 | Tạm đình chỉ công việc | S | P2 | Should |  |
| BR-PPL-083 | Bồi thường trách nhiệm vật chất trả góp | S | P2 | Should; giới hạn được thực thi bởi BR-PAY-092 |  |
| BR-PPL-084 | Xóa kỷ luật | S | P2 | Should |  |
| BR-PPL-085 | Khen thưởng → lương | M | P2 | Cắt giảm: khen thưởng nhập dưới dạng dữ liệu đầu vào biến đổi của lương |  |
| BR-PPL-090 | Chu kỳ đánh giá | S | P2 | Đánh giá thuộc Phase 2 |  |
| BR-PPL-091 | Thư viện KPI / OKR | S | P2 | Đánh giá thuộc Phase 2 |  |
| BR-PPL-092 | Đánh giá & hiệu chỉnh | S | P2 | Đánh giá thuộc Phase 2 |  |
| BR-PPL-093 | AppraisalFinalised sang tính lương | S | P2 | Đánh giá thuộc Phase 2 |  |
| BR-PPL-094 | Mẫu đánh giá thử việc | M | P2 | Cắt giảm: kết quả được ghi nhận qua BR-PPL-052 |  |
| BR-PPL-100 | Danh mục & lớp đào tạo | S | P2 | Đào tạo thuộc Phase 2 |  |
| BR-PPL-101 | Cam kết đào tạo | S | P2 | Đào tạo thuộc Phase 2 |  |
| BR-PPL-102 | Theo dõi đào tạo bắt buộc | S | P2 | Đào tạo thuộc Phase 2 |  |
| BR-PPL-103 | Thời gian đào tạo sang T&A | C | P3 | Could |  |
| BR-PPL-110 | Loại chi phí & hạn mức | S | P2 | Chi phí thuộc Phase 2 |  |
| BR-PPL-111 | Tạm ứng tiền mặt & quyết toán | S | P2 | Chi phí thuộc Phase 2 |  |
| BR-PPL-112 | Luồng hoàn ứng | S | P2 | Chi phí thuộc Phase 2 |  |
| BR-PPL-113 | Đề nghị công tác gắn với chi phí | S | P2 | Chi phí thuộc Phase 2 (công tác trong T&A vẫn là P1) |  |
| BR-PPL-120 | Hồ sơ, hợp đồng, người phụ thuộc, nghỉ việc trên ESS | M | P1 | ESS web (Phase 1) |  |
| BR-PPL-121 | Màn hình nhóm MSS & khởi tạo thay đổi | M | P2 | Cắt giảm: quản lý phê duyệt qua hộp thư Core trong P1 |  |
| BR-PPL-122 | Quản lý ủy quyền phê duyệt | M | P2 | Phụ thuộc BR-CORE-055 (P2) |  |
| BR-PPL-123 | ESS VI/EN, web responsive | M | P1 | ESS web (Phase 1) |  |
| BR-PPL-130 | Sinh quyết định nhân sự | M | P1 | Văn bản theo luật định (quyết định) |  |
| BR-PPL-131 | Giao diện quản lý phiên bản template People | M | P2 | Cắt giảm: template qua BR-CORE-102 trong P1 |  |
| BR-PPL-132 | Thư xác nhận HR theo yêu cầu | S | P2 | Should |  |
| BR-PPL-133 | Kho tài liệu nhân viên | M | P2 | Cắt theo Phương án B (R2-19): tài liệu là file đính kèm thông thường qua dịch vụ tài liệu Core (BR-CORE-100) (R2, 2026-10-16) |  |
| BR-PPL-134 | Ký số quyết định | S | P2 | Should |  |
| BR-PPL-140 | Sổ quản lý lao động | M | P1 | Luật định: Nghị định 145 |  |
| BR-PPL-141 | Báo cáo tình hình sử dụng lao động 6 tháng | M | P1 | Luật định: Nghị định 145 |  |
| BR-PPL-142 | Thông báo biến động lao động hằng tháng | M | P1 | Luật định: Luật Việc làm 2025 |  |
| BR-PPL-143 | Báo cáo về người lao động nước ngoài | S | P2 | Should |  |
| BR-PPL-144 | Khai trình sử dụng lao động lần đầu | C | P3 | Could |  |
| BR-PPL-150 | Phân loại dữ liệu cá nhân | M | P1 | Luật định: PDPL |  |
| BR-PPL-151 | Đồng ý theo từng mục đích | M | P1 | Luật định: PDPL |  |
| BR-PPL-152 | Che trường nhạy cảm & log hiển thị | M | P1 | Luật định: PDPL |  |
| BR-PPL-153 | Yêu cầu của chủ thể dữ liệu | M | P1 | Luật định: PDPL |  |
| BR-PPL-154 | Xóa dữ liệu theo thời hạn lưu trữ sau nghỉ việc | S | P2 | Should; không có dữ liệu đến hạn xóa trong năm pilot |  |
| NFR-PPL-001 | Hiệu lực theo ngày as-of / as-known | M | P1 | Toàn vẹn | B |
| NFR-PPL-002 | Outbox sự kiện giao dịch | M | P1 | Toàn vẹn | B |
| NFR-PPL-003 | Hiệu năng hồ sơ/tìm kiếm | M | P1 | Baseline hiệu năng |  |
| NFR-PPL-004 | Thao tác hàng loạt 5,000 bản ghi trong ≤10 phút | S | P2 | Phụ thuộc thay đổi hàng loạt (P2) |  |
| NFR-PPL-005 | Mã hóa ở cấp trường | M | P1 | PDPL / bảo mật | B |
| NFR-PPL-006 | Ghi log đọc/ghi dữ liệu nhạy cảm | M | P1 | PDPL |  |
| NFR-PPL-007 | Import dữ liệu nhân viên đầu kỳ (trước đây là import lịch sử Oracle) | M | P1 | Thay đổi theo D-09 | B |
| NFR-PPL-008 | Không đối chiếu chéo giữa các tenant | M | P1 | Bảo mật | B |
| NFR-PPL-009 | VI/EN, tìm kiếm có dấu | M | P1 | Bản địa hóa |  |
| NFR-PPL-010 | Tài liệu PDF/A | M | P1 | Lưu trữ |  |
| NFR-PPL-011 | ESS trên trình duyệt di động ≥360 px | M | P1 | ESS web responsive (không có app) |  |
| NFR-PPL-012 | Lưu trữ tài liệu & tạm giữ pháp lý | S | P2 | Should |  |

### 9.3 Time & Attendance — [03-time-attendance.md](modules/03-time-attendance.md)

Độ ưu tiên cũ thể hiện giá trị MoSCoW và, trong ngoặc, nhãn Phase riêng của module.

| ID | Tiêu đề ngắn | Độ ưu tiên cũ | Phase mới | Lý do | Baseline |
|---|---|---|---|---|---|
| BR-ATT-001 | Lịch ngày lễ theo pháp nhân | M (MVP) | P1 | Luật định: ngày lễ ; API đọc luôn bật (R2-C9, 2026-10-16) | API |
| BR-ATT-002 | Bộ ngày lễ VN (âm lịch) | M (MVP) | P1 | Luật định: ngày lễ ; API đọc luôn bật (R2-C9, 2026-10-16) | API |
| BR-ATT-003 | Nghỉ bù khi ngày lễ trùng ngày nghỉ hằng tuần | M (MVP) | P1 | Luật định: Điều 112(3) |  |
| BR-ATT-004 | Hoán đổi ngày / ngày làm bù | S (MVP) | P2 | Không bắt buộc với doanh nghiệp tư nhân |  |
| BR-ATT-005 | Ngày nghỉ thêm cho người lao động nước ngoài | S (MVP) | P1 | Được nâng: luật định Điều 112(2) |  |
| BR-ATT-006 | Thay đổi lịch → tính lại/hồi tố | M (MVP) | P1 | Toàn vẹn |  |
| BR-ATT-010 | Định nghĩa ca làm việc | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-011 | Mẫu lịch làm việc hằng tuần (xoay ca là P2) | M (MVP) | P1 | T&A cốt lõi ; API đọc luôn bật (R2-C9, 2026-10-16) | API |
| BR-ATT-012 | Lịch làm việc linh hoạt | S (MVP) | P2 | Không thiết yếu cho pilot |  |
| BR-ATT-013 | Ca gãy | S (P2) | P2 | Nhãn module là P2 |  |
| BR-ATT-014 | Ngày nghỉ hằng tuần theo lịch làm việc | M (MVP) | P1 | Luật định: loại làm thêm giờ, Điều 111 |  |
| BR-ATT-015 | Gán lịch làm việc theo ngày hiệu lực | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-016 | Số ngày/giờ làm việc tiêu chuẩn | M (MVP) | P1 | Đầu vào tính lương theo tỷ lệ |  |
| BR-ATT-017 | Lập kế hoạch xếp ca | S (P2) | P2 | Xếp ca nâng cao thuộc Phase 2 |  |
| BR-ATT-018 | Kiểm tra hợp lệ xếp ca | S (P2) | P2 | Xếp ca nâng cao thuộc Phase 2 |  |
| BR-ATT-019 | Đổi ca | C (P2) | P2 | Xếp ca nâng cao thuộc Phase 2 |  |
| BR-ATT-020 | Kho dữ liệu chấm công gốc bất biến | M (MVP) | P1 | T&A cốt lõi; bằng chứng thanh tra |  |
| BR-ATT-021 | Connector máy chấm công (1) + import file | M (MVP) | P2 | Connector trực tuyến là P2 (R2-01); file xuất từ phần mềm máy chấm công (CSV/Excel) được import qua template BR-ATT-025 (R2, 2026-10-16) |  |
| BR-ATT-022 | Ánh xạ đăng ký & cách ly dữ liệu | M (MVP) | P1 | Vẫn cần ánh xạ mã người dùng trên máy chấm công cho các file xuất từ máy (R2-01) |  |
| BR-ATT-023 | Chấm công trên di động bằng GPS/Wi-Fi/selfie | S (P2) | P2 | Mobile thuộc Phase 2 (OD-9) |  |
| BR-ATT-024 | Chấm công trên web | S (MVP) | P2 | Không phải địa điểm nào cũng có máy chấm công; các nơi đó dùng import Excel (R2-06 đã xác nhận) |  |
| BR-ATT-025 | Import dữ liệu chấm công/bảng công từ Excel | M (MVP) | P1 | Nguồn dữ liệu chấm công của pilot; bao gồm template file xuất từ máy chấm công (CSV/Excel) (R2-01) | B |
| BR-ATT-026 | Gộp bản ghi trùng & lệch đồng hồ | M (MVP) | P2 | Cắt theo Phương án B (R2-19): bản ghi trùng được loại trong file import / báo cáo chạy thử; không có đồng hồ máy chấm công trực tuyến (R2, 2026-10-16) |  |
| BR-ATT-027 | Không bao giờ chỉnh sửa dữ liệu chấm công gốc | M (MVP) | P1 | Bằng chứng thanh tra |  |
| BR-ATT-030 | Xác định ngày công | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-031 | Quy tắc ghép cặp & thiếu giờ vào/ra | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-032 | Số lượng theo ngày bao gồm giờ làm đêm | M (MVP) | P1 | T&A cốt lõi; đầu vào phụ cấp làm đêm |  |
| BR-ATT-033 | Trạng thái ngày & mã chấm công | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-034 | Chính sách dung sai đi muộn/về sớm | M (MVP) | P2 | Cắt theo Phương án B (R2-19): một quy tắc đi muộn/về sớm cố định cho mỗi pháp nhân (R2, 2026-10-16) |  |
| BR-ATT-035 | Thời gian nghỉ ngắn có hưởng lương theo luật định | M (MVP) | P1 | Luật định: Bộ luật Lao động |  |
| BR-ATT-036 | Yêu cầu điều chỉnh chấm công | M (MVP) | P1 | ESS web; yêu cầu điều chỉnh là một nguồn dữ liệu chấm công P1 (R2-01) |  |
| BR-ATT-037 | Admin chỉnh sửa bảng công hàng loạt | M (MVP) | P1 | Được giữ ở P1 theo R2-01 "nhập liệu thủ công" (ghi đè Phương án B): admin chỉnh sửa bảng công kèm lý do |  |
| BR-ATT-038 | Xử lý lại có tính idempotent | M (MVP) | P1 | Toàn vẹn |  |
| BR-ATT-039 | Ngày chưa làm việc / tạm hoãn | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-040 | Yêu cầu làm thêm giờ có sự đồng ý | M (MVP) | P1 | Luật định: sự đồng ý của người lao động |  |
| BR-ATT-041 | Loại làm thêm giờ (ngày/đêm/ngày nghỉ/ngày lễ) | M (MVP) | P1 | Luật định: mức lương làm thêm giờ |  |
| BR-ATT-042 | Cờ làm thêm giờ ban đêm sau làm thêm ban ngày | M (MVP) | P1 | Luật định: Nghị định 145 Điều 57 |  |
| BR-ATT-043 | Chính sách phát hiện làm thêm giờ | M (MVP) | P2 | Cắt theo Phương án B (R2-19): làm thêm giờ chỉ theo phê duyệt trước (BR-ATT-040) (R2, 2026-10-16) |  |
| BR-ATT-044 | Quy tắc làm thêm giờ được trả lương | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-045 | Giới hạn làm thêm giờ theo ngày | M (MVP) | P1 | Luật định: giới hạn làm thêm giờ |  |
| BR-ATT-046 | Giới hạn 40h/tháng / 200/300h/năm | M (MVP) | P1 | Luật định: giới hạn làm thêm giờ |  |
| BR-ATT-047 | Giới hạn làm thêm giờ năm liên pháp nhân | S (MVP) | P2 | Giới hạn theo từng pháp nhân trong P1; chờ ý kiến pháp lý (R2-08/R2-16 đã xác nhận) |  |
| BR-ATT-048 | Chặn làm thêm giờ/làm đêm với người lao động được bảo vệ | M (MVP) | P1 | Luật định: Bộ luật Lao động |  |
| BR-ATT-049 | Thông báo cơ quan có thẩm quyền về chế độ 300h | S (MVP) | P1 | Được nâng: luật định Điều 107(4) |  |
| BR-ATT-050 | Nghỉ bù thay cho trả lương làm thêm giờ | S (MVP) | P2 | Chính sách tùy chọn |  |
| BR-ATT-051 | Cảnh báo nghỉ ngơi khi làm thêm giờ liên tiếp | C (P2) | P2 | Nhãn module là P2 |  |
| BR-ATT-055 | Cấu hình loại nghỉ phép | M (MVP) | P1 | Luật định: luật về nghỉ phép |  |
| BR-ATT-056 | Các loại nghỉ phép VN được seed sẵn | M (MVP) | P1 | Luật định: luật về nghỉ phép |  |
| BR-ATT-057 | Quyền nghỉ phép năm + thâm niên | M (MVP) | P1 | Luật định: Điều 113–114 |  |
| BR-ATT-058 | Tích lũy & tính theo tỷ lệ | M (MVP) | P1 | Luật định: Nghị định 145 |  |
| BR-ATT-059 | Tính thời gian làm việc | M (MVP) | P1 | Luật định: Nghị định 145 |  |
| BR-ATT-060 | Năm phép & chuyển phép | M (MVP) | P1 | Nghỉ phép cốt lõi |  |
| BR-ATT-061 | Đơn nghỉ phép (ngày/nửa ngày/giờ) | M (MVP) | P1 | ESS web (Phase 1) |  |
| BR-ATT-062 | Kiểm tra hợp lệ & giữ chỗ nghỉ phép | M (MVP) | P1 | Nghỉ phép cốt lõi |  |
| BR-ATT-063 | Dữ liệu hồ sơ BHXH khi nghỉ ốm/thai sản | M (MVP) | P1 | Quay lại P1, bản tối giản (R2-07): người sử dụng lao động nộp hồ sơ ốm đau/thai sản cho VSS; tính lương cần cùng dữ liệu đó (R2, 2026-10-16) |  |
| BR-ATT-064 | Theo dõi số ngày nghỉ ốm hưởng BHXH | S (MVP) | P2 | Chỉ mang tính thông tin; hồ sơ BHXH là P2 |  |
| BR-ATT-065 | Nghỉ thai sản & đi làm sớm | M (MVP) | P1 | Luật định: Điều 139 |  |
| BR-ATT-066 | Hủy/rút ngắn nghỉ phép có hồi tố | M (MVP) | P2 | Cắt theo Phương án B (R2-19): HR điều chỉnh nghỉ phép qua điều chỉnh số dư và import lại bảng công (R2, 2026-10-16) |  |
| BR-ATT-067 | Phép chưa nghỉ khi chấm dứt hợp đồng | M (MVP) | P1 | Luật định: thanh toán tiền phép chưa nghỉ |  |
| BR-ATT-068 | Sổ cái số dư & điều chỉnh thủ công | M (MVP) | P1 | Số dư phép đầu kỳ (D-09) |  |
| BR-ATT-069 | Lịch nghỉ phép của nhóm | S (MVP) | P2 | Tiện ích |  |
| BR-ATT-070 | Công tác & WFH | M (MVP) | P2 | Cắt theo Phương án B (R2-19): công tác và WFH được ghi nhận dưới dạng loại nghỉ / mã chấm công (R2, 2026-10-16) |  |
| BR-ATT-071 | Chênh lệch hồi tố cho kỳ đã khóa | M (MVP) | P1 | Hợp đồng dữ liệu hồi tố với Payroll |  |
| BR-ATT-072 | Kỳ công khớp với lịch lương | M (MVP) | P1 | Bàn giao cho Payroll |  |
| BR-ATT-073 | Vòng đời kỳ công | M (MVP) | P1 | Bàn giao cho Payroll |  |
| BR-ATT-074 | Checklist ngoại lệ trước khóa kỳ | M (MVP) | P1 | Chất lượng bàn giao cho Payroll |  |
| BR-ATT-075 | Khóa kỳ theo pháp nhân × nhóm lương / khóa sớm | M (MVP) | P1 | Khôi phục theo R3-06: khóa sớm cho người nghỉ việc, cần cho thanh toán khi nghỉ việc trong 14 ngày làm việc (R3, 2026-10-16) |  |
| BR-ATT-076 | Attendance Result có phiên bản | M (MVP) | P1 | Bàn giao cho Payroll |  |
| BR-ATT-077 | Nhân viên xác nhận bảng công | C (MVP) | P2 | Tùy chọn |  |
| BR-ATT-078 | Quản lý ký duyệt bảng công | S (MVP) | P2 | Tùy chọn |  |
| BR-ATT-079 | API giữ khóa kỳ cho Payroll (mới, X-03) | M (MVP) | P1 | Toàn vẹn khóa Payroll/T&A (X-03) |  |
| BR-ATT-080 | Lịch làm việc, bảng công, số dư trên ESS | M (MVP) | P1 | ESS web (Phase 1) |  |
| BR-ATT-081 | Dashboard MSS ai đang có mặt | M (MVP) | P2 | Tiện ích; phê duyệt qua hộp thư Core |  |
| BR-ATT-082 | Thông báo T&A | M (MVP) | P1 | T&A cốt lõi |  |
| BR-ATT-083 | Gán chính sách theo ngày hiệu lực | M (MVP) | P2 | Cắt theo Phương án B (R2-19): một bộ chính sách cho mỗi pháp nhân (R2, 2026-10-16) |  |
| BR-ATT-084 | Tự động leo thang trước khóa kỳ | S (MVP) | P2 | Phụ thuộc leo thang theo SLA (P2) |  |
| BR-ATT-085 | Public API chấm công/bảng công | S (P2) | P2 | Public API là P2 |  |
| BR-ATT-086 | Import số dư phép đầu kỳ & số giờ làm thêm lũy kế (trước đây là chuyển đổi dữ liệu Oracle) | M (MVP) | P1 | Thay đổi theo D-09 (bởi T&A BA) |  |
| BR-ATT-087 | Mở lại kỳ đang bị giữ khóa (mới, X-03) | M (MVP) | P1 | Toàn vẹn khóa Payroll/T&A (X-03) |  |
| BR-ATT-088 | Khóa/mở khóa theo trạng thái lương (mới, X-03) | M (MVP) | P1 | Toàn vẹn khóa Payroll/T&A (X-03) |  |
| NFR-ATT-001 | Thông lượng nhận dữ liệu chấm công (500/s toàn nền tảng, X-05) | M | P2 | NFR tốc độ chấm công là P2 cùng connector trực tuyến (R2-01; hoãn SP-3) (R2, 2026-10-16) |  |
| NFR-ATT-002 | Độ trễ từ chấm công đến bảng công | M | P1 | Phần P1: xử lý lại hằng đêm ≤ 30 phút; độ trễ của nguồn trực tuyến không còn ý nghĩa cho đến khi có connector (R2-01) |  |
| NFR-ATT-003 | Khóa kỳ ≤15 phút | M | P1 | Tiến độ tính lương |  |
| NFR-ATT-004 | Bộ đệm offline của connector ≥7 ngày | M | P2 | Bộ đệm offline của connector là P2 (R2-01) (R2, 2026-10-16) |  |
| NFR-ATT-005 | Lưu trữ theo UTC, ngày công theo giờ địa phương | M | P1 | Toàn vẹn |  |
| NFR-ATT-006 | Tính toán xác định, có phiên bản | M | P1 | Toàn vẹn |  |
| NFR-ATT-007 | Lưu trữ hồ sơ T&A | M | P1 | Lưu trữ theo luật định |  |
| NFR-ATT-008 | Dữ liệu nhạy cảm (sinh trắc học trên máy; GPS/selfie là P2) | M | P1 | Luật định: PDPL |  |
| NFR-ATT-009 | Phát hiện vị trí giả | S | P2 | Mobile thuộc Phase 2 |  |
| NFR-ATT-010 | Cô lập tenant bao gồm máy chấm công | M | P1 | Baseline bảo mật; endpoint máy chấm công chỉ có từ P2 (R2-01) |  |
| NFR-ATT-011 | Thời gian phản hồi chấm công trên ESS | S | P2 | Chấm công trên web/di động thuộc Phase 2 |  |

### 9.4 Payroll — [04-payroll.md](modules/04-payroll.md)

| ID | Tiêu đề ngắn | Độ ưu tiên cũ | Phase mới | Lý do | Baseline |
|---|---|---|---|---|---|
| BR-PAY-001 | Danh mục thành phần lương | M | P1 | Tính lương cốt lõi | B |
| BR-PAY-002 | Cờ thuế TNCN/BHXH/công đoàn độc lập cho từng thành phần | M | P1 | Luật định: cách xử lý thuế TNCN/BHXH | B |
| BR-PAY-003 | Mức trần phụ cấp được miễn một phần | M | P1 | Luật định: miễn thuế TNCN |  |
| BR-PAY-004 | Thành phần lương theo ngày hiệu lực | M | P1 | Toàn vẹn của kỳ lương đã khóa |  |
| BR-PAY-005 | Engine công thức | M | P1 | Tính lương cốt lõi (quy tắc lương của pilot, SP-4) |  |
| BR-PAY-006 | IDE công thức (tô sáng cú pháp, chế độ thử, so sánh khác biệt) | M | P2 | Tiện ích; P1 = kiểm tra cú pháp trong BR-PAY-005 |  |
| BR-PAY-007 | Cơ cấu lương | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-008 | Nhóm lương (hằng tháng) | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-009 | Phương pháp xác định ngày công chuẩn | M | P1 | Tính lương cốt lõi; tính theo tỷ lệ |  |
| BR-PAY-010 | Tham số pháp lý theo ngày hiệu lực | M | P1 | Luật định: mọi tỷ lệ/mức trần/bậc thuế | B |
| BR-PAY-011 | Template khởi tạo cho VN | S | P2 | Should; công cụ tăng tốc |  |
| BR-PAY-012 | Quy tắc làm tròn | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-013 | Hợp đồng đa tiền tệ | S | P2 | Pilot không có hợp đồng bằng USD (R2-09 đã xác nhận) |  |
| BR-PAY-020 | GROSS và NET (quy đổi gross-up) | M | P1 | Tính lương VN cốt lõi |  |
| BR-PAY-021 | Phạm vi quy đổi net sang gross | M | P2 | Cắt theo Phương án B (R2-19): phạm vi net cố định (mọi thành phần định kỳ là net; thưởng/làm thêm giờ là gross) (R2, 2026-10-16) |  |
| BR-PAY-022 | Tính theo tỷ lệ cho người mới vào/nghỉ việc | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-023 | Giảm trừ không lương/vắng mặt | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-024 | Phân đoạn khi thay đổi giữa kỳ | M | P1 | Tính lương cốt lõi; thay đổi pháp lý giữa kỳ |  |
| BR-PAY-025 | Lương làm thêm giờ ≥ hệ số theo luật | M | P1 | Luật định: lương làm thêm giờ |  |
| BR-PAY-026 | Căn cứ tính lương làm thêm giờ & phần làm thêm được miễn thuế TNCN | M | P1 | Luật định: miễn thuế TNCN |  |
| BR-PAY-027 | Kiểm tra lương tối thiểu vùng | M | P1 | Luật định: lương tối thiểu |  |
| BR-PAY-028 | Thành phần phụ cấp & biến đổi | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-029 | Import dữ liệu đầu vào biến đổi (API là P2) | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-030 | Ngày ốm/thai sản do BHXH chi trả | M | P1 | Luật định: BHXH |  |
| BR-PAY-031 | Tiền lương ngày nghỉ phép/ngày lễ | M | P1 | Luật định: Bộ luật Lao động |  |
| BR-PAY-040 | Đóng BHXH/BHYT/BHTN/BHTNLĐ-BNN | M | P1 | Luật định: Luật BHXH 2024 |  |
| BR-PAY-041 | Tiền lương đóng BHXH & mức trần | M | P1 | Luật định: Luật BHXH 2024 |  |
| BR-PAY-042 | Mức lương đóng BHXH khai báo theo tháng | M | P1 | Luật định: quy định của VSS |  |
| BR-PAY-043 | Quy tắc không đóng khi nghỉ từ 14 ngày | M | P1 | Luật định: Luật BHXH 2024 |  |
| BR-PAY-044 | Điều kiện tham gia & miễn đóng BHXH | M | P1 | Luật định: Luật BHXH 2024 |  |
| BR-PAY-045 | Tỷ lệ BHTNLĐ-BNN theo pháp nhân | M | P1 | Luật định |  |
| BR-PAY-046 | Kinh phí & đoàn phí công đoàn | M | P1 | Luật định: Luật Công đoàn |  |
| BR-PAY-047 | Dữ liệu D02-LT / TK1-TS, đối soát C12-TS | M | P1 | File kê khai VSS để khách hàng tải lên qua I-VAN của mình (R2-01) |  |
| BR-PAY-048 | Điều chỉnh BHXH hồi tố kèm lãi | S | P2 | Should; điều chỉnh thủ công trong pilot |  |
| BR-PAY-049 | Theo dõi nợ BHXH | S | P2 | Should |  |
| BR-PAY-050 | Thuế TNCN lũy tiến cho cá nhân cư trú | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-051 | Biểu thuế TNCN theo tham số (7→5 bậc) | M | P1 | Luật định: Luật Thuế TNCN 2025 |  |
| BR-PAY-052 | Thuế TNCN lũy kế trong tháng qua nhiều kỳ lương | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-053 | Cá nhân không cư trú 20% | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-054 | Khấu trừ 10% thu nhập vãng lai | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-055 | Cam kết 08/CK-TNCN | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-056 | XML tờ khai 05/KK-TNCN | M | P1 | Luật định: kê khai thuế |  |
| BR-PAY-057 | Quyết toán thuế TNCN cuối năm | M | P1 | Luật định: 05/QTT-TNCN (dùng dữ liệu lũy kế đã import, D-09) |  |
| BR-PAY-058 | Điều kiện & ủy quyền quyết toán | M | P1 | Luật định: quyết toán thuế TNCN |  |
| BR-PAY-059 | Chứng từ khấu trừ thuế TNCN điện tử | M | P1 | Luật định; cần chữ ký số của người sử dụng lao động (xem §11 R2-C3) |  |
| BR-PAY-060 | Số tháng giảm trừ người phụ thuộc | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-061 | Kiểm tra mã số thuế | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-062 | Thuế TNCN do người sử dụng lao động trả thay & phúc lợi bằng hiện vật | M | P1 | Luật định: thuế TNCN |  |
| BR-PAY-063 | Thu nhập từ đơn vị cũ trong cùng tập đoàn | S | P2 | Should (dữ liệu lũy kế trước go-live lấy từ số dư đầu kỳ) |  |
| BR-PAY-064 | Tùy chọn tính lại thuế TNCN hồi tố | S | P2 | Should; mặc định tính thuế vào tháng chi trả |  |
| BR-PAY-065 | Báo cáo kiểm tra cư trú 183 ngày | new (R2-02, 2026-10-16) | P1 | Luật định: kiểm tra tình trạng cư trú thuế TNCN (cung cấp dữ liệu cho BR-PPL-023, BR-PAY-053) |  |
| BR-PAY-070 | Loại kỳ lương gồm tháng 13/Tết, thanh toán khi nghỉ việc | M | P1 | Kỳ lương thưởng / ngoài chu kỳ với thuế TNCN chính xác vẫn là P1; quy tắc điều kiện và tính theo tỷ lệ của Tết/tháng 13 là P2 (R2-14) |  |
| BR-PAY-071 | Vòng đời & khóa kỳ lương | M | P1 | Kiểm soát tính lương cốt lõi |  |
| BR-PAY-072 | Snapshot dữ liệu đầu vào & khả năng tái lập | M | P1 | Toàn vẹn |  |
| BR-PAY-073 | Kỳ lương cho tập con / một người / mô phỏng | M | P2 | Cắt theo Phương án B (R2-19): chỉ chạy cho toàn bộ nhân viên; kỳ lương đã tính nhưng chưa trình duyệt dùng để xem trước (R2, 2026-10-16) |  |
| BR-PAY-074 | Báo cáo ngoại lệ trước khi trình duyệt | M | P1 | Kiểm soát tính lương cốt lõi |  |
| BR-PAY-075 | Phê duyệt qua workflow Core | M | P1 | Kiểm soát tính lương cốt lõi |  |
| BR-PAY-076 | Truy lĩnh lương (retro pay) | M | P1 | Tính lương cốt lõi; thay đổi theo ngày hiệu lực |  |
| BR-PAY-077 | Kỳ lương thanh toán khi nghỉ việc | M | P1 | Thanh toán khi nghỉ việc theo luật định là P1; tháng 13 theo tỷ lệ theo chính sách là P2 (R2-14) |  |
| BR-PAY-078 | Căn cứ tính trợ cấp thôi việc (bình quân 6 tháng) | M | P1 | Luật định: trợ cấp thôi việc |  |
| BR-PAY-079 | Kỳ lương đảo ngược | S | P2 | Should |  |
| BR-PAY-080 | Quy tắc chặn khóa kỳ | S | P2 | Should |  |
| BR-PAY-081 | Tính lương đa pháp nhân | M | P1 | Mỗi pháp nhân khấu trừ thuế TNCN trên khoản chi trả của mình (R2-16) |  |
| BR-PAY-082 | Giữ khóa kỳ khi trình duyệt (mới, X-03) | M | P1 | Toàn vẹn khóa Payroll/T&A (X-03) |  |
| BR-PAY-083 | Giải phóng giữ khóa (mới, X-03) | M | P1 | Toàn vẹn khóa Payroll/T&A (X-03) |  |
| BR-PAY-084 | Cổng phê duyệt & xác nhận khóa (mới, X-03) | M | P1 | Toàn vẹn khóa Payroll/T&A (X-03) |  |
| BR-PAY-085 | Gán nhóm lương (nhân viên → nhóm lương) | new (R2-02, 2026-10-16) | P1 | Chuỗi xử lý: mọi kỳ lương đều cần liên kết nhóm lương |  |
| BR-PAY-086 | Sự kiện People ảnh hưởng đến lương | new (R2-02, 2026-10-16) | P1 | Chuỗi xử lý, P1 chỉ cho các sự kiện có nguồn phát là P1: tạm hoãn/tiếp tục hợp đồng (BR-PPL-056) và tạm thời chuyển công việc (BR-PPL-035). P2 cùng với nguồn phát của chúng: RewardApproved (BR-PPL-085), SecondmentChanged (BR-PPL-034), AppraisalFinalised (BR-PPL-093), AdvanceDeductionRequested (BR-PPL-111), hoàn trả cam kết đào tạo (BR-PPL-101) |  |
| BR-PAY-087 | Trạng thái khóa pháp nhân cho Core | new (R2-02, 2026-10-16) | P1 | Chuỗi xử lý: bảo vệ kỳ lương đã khóa khỏi các thay đổi hồi tố về pháp nhân/tổ chức (Core C-16) |  |
| BR-PAY-090 | Tạm ứng lương | M | P2 | Cắt theo Phương án B (R2-19): tạm ứng chi qua loại kỳ lương Advance / nhập dưới dạng dữ liệu đầu vào biến đổi (R2, 2026-10-16) |  |
| BR-PAY-091 | Khoản vay của nhân viên (trả góp không lãi; có lãi là P2) | S | P1 | Được nâng: số dư vay đầu kỳ (D-09) |  |
| BR-PAY-092 | Mức trần khấu trừ 30% để bồi thường thiệt hại | M | P1 | Luật định: Điều 102 |  |
| BR-PAY-093 | Thứ tự ưu tiên khấu trừ & chuyển kỳ sau | M | P1 | Tính lương cốt lõi |  |
| BR-PAY-094 | Hoàn ứng chi phí qua lương | S | P2 | Chi phí thuộc Phase 2 |  |
| BR-PAY-100 | Template phiếu lương | M | P1 | Luật định: nội dung phiếu lương (Điều 95) |  |
| BR-PAY-101 | Phiếu lương trên ESS web (ứng dụng di động là P2) | M | P1 | Phiếu lương trên ESS web (R2-17) |  |
| BR-PAY-102 | Phiếu lương qua e-mail có mật khẩu | M | P1 | Được giữ ở P1 theo R2-17 (ghi đè Phương án B): PDF có mật khẩu qua e-mail |  |
| BR-PAY-103 | Thắc mắc về phiếu lương từ ESS | C | P3 | Could |  |
| BR-PAY-104 | Chứng từ thuế TNCN, 08/CK, ủy quyền trên ESS | M | P1 | Luật định: quyết toán thuế TNCN |  |
| BR-PAY-110 | File thanh toán ngân hàng | M | P1 | Trả lương cho nhân viên của pilot |  |
| BR-PAY-111 | Chi trả chia tách/tiền mặt | S | P2 | Should |  |
| BR-PAY-112 | Kiểm soát & trạng thái file thanh toán | M | P2 | Cắt theo Phương án B (R2-19): cổng phê duyệt BR-PAY-084 vẫn áp dụng; không có hash file/import trạng thái (R2, 2026-10-16) |  |
| BR-PAY-113 | Xuất bút toán GL | M | P1 | Bàn giao cho kế toán |  |
| BR-PAY-114 | Phân bổ chi phí (mặc định; theo thời gian là P2) | M | P2 | Cắt theo Phương án B (R2-19): chi phí theo trung tâm chi phí mặc định trong file xuất GL (BR-PAY-113) (R2, 2026-10-16) |  |
| BR-PAY-115 | Trích trước thưởng/phép | C | P3 | Could |  |
| BR-PAY-120 | Phạm vi dữ liệu lương & nhóm bảo mật | M | P1 | Bảo mật thông tin |  |
| BR-PAY-121 | Kiểm toán tính lương | M | P1 | Bằng chứng kiểm toán |  |
| BR-PAY-122 | Import số dư lương đầu kỳ (trước đây là chuyển đổi lịch sử Oracle) | M | P1 | Thay đổi theo D-09 (bởi Payroll BA) |  |
| BR-PAY-123 | Lưu trữ hồ sơ lương | M | P1 | Luật định: Luật Kế toán |  |
| BR-PAY-124 | API ước tính chi phí lương | S | P2 | Bên sử dụng (Tuyển dụng) thuộc Phase 2 ; API đọc luôn bật (R2-C9, 2026-10-16) | API |
| BR-PAY-125 | Công cụ tính gross↔net | C | P3 | Could |  |
| NFR-PAY-001 | 10,000 nhân viên ≤15 phút | M | P1 | Baseline hiệu năng (SP-1) |  |
| NFR-PAY-002 | Số học dấu phẩy tĩnh xác định | M | P1 | Toàn vẹn |  |
| NFR-PAY-003 | Tái lập được ≥10 năm | M | P1 | Bằng chứng theo luật định |  |
| NFR-PAY-004 | Bảo mật dữ liệu lương | M | P1 | Bảo mật / PDPL |  |
| NFR-PAY-005 | Cô lập tenant | M | P1 | Baseline bảo mật |  |
| NFR-PAY-006 | Kiểm toán tính lương bất biến | M | P1 | Bằng chứng kiểm toán |  |
| NFR-PAY-007 | Linh hoạt theo thay đổi pháp lý bằng cấu hình | M | P1 | Sẵn sàng cho thay đổi luật định | B |
| NFR-PAY-008 | Bộ kiểm thử chuẩn (golden test pack) ≥200 kịch bản | M | P1 | Điều kiện go-live (độ chính xác theo luật định) |  |
| NFR-PAY-009 | Tính sẵn sàng của tính lương 99.5% | M | P1 | Tính sẵn sàng |  |
| NFR-PAY-010 | Tương thích file HTKK/eTax/VSS/ngân hàng | M | P1 | Kê khai theo luật định |  |
| NFR-PAY-011 | VN/EN, định dạng VND | M | P1 | Bản địa hóa |  |
| NFR-PAY-012 | Kỳ lương 500 nhân viên <30 phút thao tác | S | P2 | Should |  |
| NFR-PAY-013 | 50 pháp nhân / 10k nhân viên mỗi pháp nhân | S | P2 | Vượt quy mô pilot |  |

### 9.5 Recruitment — [05-recruitment.md](modules/05-recruitment.md)

Toàn bộ module thuộc Phase 2 (charter §6). Các mục `C` chuyển sang P3. Hợp đồng bàn giao `CandidateHired` vẫn được đóng băng từ Phase 0.

| ID | Tiêu đề ngắn | Độ ưu tiên cũ | Phase mới | Lý do | Baseline |
|---|---|---|---|---|---|
| BR-REC-001 | Kế hoạch nhân sự | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-002 | Điền sẵn kế hoạch từ People | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-003 | Chi phí ngân sách theo dòng kế hoạch | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-004 | Phê duyệt & phiên bản kế hoạch | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-005 | Tổng hợp kế hoạch so với thực tế | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-006 | Gắn cờ tổ chức/vị trí đã ngừng sử dụng | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-007 | Tạo yêu cầu tuyển dụng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-008 | Phân loại yêu cầu tuyển dụng so với kế hoạch | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-009 | Phê duyệt yêu cầu tuyển dụng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-010 | Lương trong yêu cầu tuyển dụng nằm trong khung | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-011 | Phạm vi dữ liệu yêu cầu tuyển dụng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-012 | Yêu cầu tuyển thay thế khi hủy nghỉ việc | C | P3 | Could |  |
| BR-REC-013 | Tin tuyển dụng từ yêu cầu tuyển dụng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-014 | Trang tuyển dụng mang thương hiệu | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-015 | Bảng tin tuyển dụng nội bộ | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-016 | Đăng tin lên trang tuyển dụng | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-017 | Import hồ sơ ứng tuyển từ trang tuyển dụng | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-018 | Cảnh báo nội dung phân biệt đối xử | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-019 | Tự động gỡ tin khi đóng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-020 | Một hồ sơ ứng viên, nhiều lần ứng tuyển | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-021 | Dữ liệu hồ sơ ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-022 | Phân tích CV | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-023 | Phát hiện ứng viên trùng lặp | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-024 | Nguồn ứng viên tiềm năng | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-025 | Tìm kiếm ứng viên | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-026 | Không từ chối tự động | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-027 | Template quy trình tuyển dụng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-028 | Câu hỏi sàng lọc | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-029 | Lý do từ chối/rút hồ sơ, thao tác hàng loạt | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-030 | Template trao đổi với ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-031 | Mốc thời gian theo giai đoạn | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-032 | Quyền xem của người phỏng vấn | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-033 | Lên lịch phỏng vấn | M/S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-034 | Ứng viên tự đặt lịch | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-035 | Nhắc lịch phỏng vấn | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-036 | Phiếu đánh giá (scorecard) | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-037 | Đôn đốc phiếu đánh giá | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-038 | Bài đánh giá năng lực | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-039 | Người phỏng vấn từ People | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-040 | Tạo offer | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-041 | Kiểm tra khung lương của offer | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-042 | Mô phỏng gross-up cho offer NET | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-043 | Kiểm tra pháp lý của offer | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-044 | Phê duyệt offer | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-045 | Thư mời nhận việc & chấp nhận | M/S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-046 | Phiên bản & phản hồi offer | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-047 | Giữ chỗ vị trí trống khi chấp nhận | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-048 | Checklist trước khi nhận việc | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-049 | Xác minh tài liệu trước khi nhận việc | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-050 | Bàn giao CandidateHired | M | P2 | Tuyển dụng thuộc Phase 2 (hợp đồng được đóng băng trong Phase 0) |  |
| BR-REC-051 | Chặn tuyển trùng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-052 | Xử lý HireCancelled / StartDateChanged | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-053 | Bàn giao điều chuyển nội bộ | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-054 | Cờ giấy phép lao động khi tuyển | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-055 | Nhân viên giới thiệu ứng viên | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-056 | Chương trình giới thiệu | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-057 | Thưởng giới thiệu sang lương | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-058 | Theo dõi chi phí tuyển dụng | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-059 | Sự đồng ý của ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-060 | Quyền đối với dữ liệu của ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-061 | Thời hạn lưu trữ dữ liệu ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-062 | Tạm giữ pháp lý dữ liệu ứng viên | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| BR-REC-063 | Ghi log truy cập CV/giấy tờ định danh | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-001 | Cô lập tenant cho rec | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-002 | Hiệu năng trang tuyển dụng | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-003 | Chống bot & quét file tải lên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-004 | Mã hóa tài liệu của ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-005 | Outbox bàn giao | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-006 | Độ chính xác phân tích CV | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-007 | Xử lý lỗi tích hợp an toàn | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-008 | Hiệu năng tìm kiếm ứng viên | S | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-009 | Job lưu trữ dữ liệu ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-010 | Tuyển dụng VI/EN | M | P2 | Tuyển dụng thuộc Phase 2 |  |
| NFR-REC-011 | Phiên đăng nhập cổng ứng viên | M | P2 | Tuyển dụng thuộc Phase 2 |  |

### 9.6 SaaS Platform — [06-saas-platform.md](modules/06-saas-platform.md)

| ID | Tiêu đề ngắn | Độ ưu tiên cũ | Phase mới | Lý do | Baseline |
|---|---|---|---|---|---|
| BR-SAAS-001 | Bản ghi tenant & các trạng thái vòng đời | M | P1 | SaaS cốt lõi |  |
| BR-SAAS-002 | Pháp nhân theo tenant | M | P1 | Nền tảng: đa pháp nhân |  |
| BR-SAAS-003 | Operator khởi tạo tenant ≤10 phút | M | P1 | Onboarding có hỗ trợ (lộ trình của pilot) |  |
| BR-SAAS-004 | Tự đăng ký dùng thử | S | P2 | Tự phục vụ thuộc Phase 2 |  |
| BR-SAAS-005 | Cấu hình & hết hạn dùng thử | S | P2 | Tự phục vụ thuộc Phase 2 |  |
| BR-SAAS-006 | Gói/phiên bản có quản lý phiên bản | M | P2 | Cắt giảm: P1 = đăng ký module theo tenant (BR-SAAS-007) |  |
| BR-SAAS-007 | Đăng ký module theo tenant | M | P1 | SaaS cốt lõi |  |
| BR-SAAS-008 | Dịch vụ quyền sử dụng (entitlement) | M | P1 | SaaS cốt lõi |  |
| BR-SAAS-009 | Feature flag & kill-switch | M | P2 | Cắt theo Phương án B (R2-19): bật/tắt bằng cấu hình/deploy (R2, 2026-10-16) |  |
| BR-SAAS-010 | Bảng giá, bậc giá, mã giảm giá | M | P2 | Cắt giảm: P1 = giá hợp đồng theo module |  |
| BR-SAAS-011 | Thay đổi gói tính theo tỷ lệ thời gian | M | P2 | Tự động hóa tính phí thuộc Phase 2 |  |
| BR-SAAS-012 | Nhân viên tính phí = người đang làm việc cuối tháng theo pháp nhân | M | P1 | Thay đổi theo D-10 |  |
| BR-SAAS-013 | Đo lường mức sử dụng (số lượng tính phí; các chỉ số khác là P2) | M | P2 | Cắt theo Phương án B (R2-19): báo cáo số lượng nhân viên cuối tháng thủ công theo pháp nhân để tính phí (R2, 2026-10-16) |  |
| BR-SAAS-014 | Hóa đơn thuê bao + hóa đơn điện tử VN | M | P2 | Chuyển theo R3-01: bộ phận tài chính của nhà cung cấp phát hành hóa đơn điện tử từ công cụ hóa đơn điện tử hiện có dựa trên báo cáo mức sử dụng (R3, 2026-10-16) |  |
| BR-SAAS-015 | Hóa đơn điện tử điều chỉnh/thay thế | M | P2 | Chuyển theo R3-01: bộ phận tài chính của nhà cung cấp phát hành hóa đơn điện tử từ công cụ hóa đơn điện tử hiện có dựa trên báo cáo mức sử dụng (R3, 2026-10-16) |  |
| BR-SAAS-016 | Cổng thanh toán & đối soát tự động | S | P2 | Should |  |
| BR-SAAS-017 | Nhắc nợ & tạm ngưng tự động | M | P2 | Thu tiền thủ công cho pilot |  |
| BR-SAAS-018 | Xuất toàn bộ dữ liệu khi offboarding ≤72h | M | P2 | Cần trước khi có tenant đầu tiên rời đi, không phải khi go-live pilot |  |
| BR-SAAS-019 | Xóa dữ liệu sau thời hạn lưu trữ & chứng nhận | M | P2 | Cần trước khi có tenant đầu tiên rời đi |  |
| BR-SAAS-020 | Chuyển quyền sở hữu | S | P2 | Should |  |
| BR-SAAS-021 | tenant_id + RLS + bộ lọc ứng dụng | M | P1 | Baseline bảo mật |  |
| BR-SAAS-022 | Gói dedicated | S | P2 | Should |  |
| BR-SAAS-023 | Kiểm thử cô lập chặn release | M | P1 | Baseline bảo mật |  |
| BR-SAAS-024 | Lưu trữ dữ liệu tại VN | M | P1 | Luật định: Luật An ninh mạng / PDPL |  |
| BR-SAAS-025 | Thiết lập tenant (ngôn ngữ, múi giờ, tiền tệ) | M | P1 | SaaS cốt lõi |  |
| BR-SAAS-026 | VI/EN đầy đủ | M | P1 | Bản địa hóa |  |
| BR-SAAS-027 | Trường tùy chỉnh | M | Removed | Bị loại — thuộc core: BR-CORE-086 framework trường tùy chỉnh (P2) (R2-02, 2026-10-16) |  |
| BR-SAAS-028 | Tùy chỉnh biểu mẫu & template | S | P2 | Should |  |
| BR-SAAS-029 | White-label | C | P2 | White-label thuộc Phase 2 (charter) |  |
| BR-SAAS-030 | Tên miền tùy chỉnh | C | P2 | White-label thuộc Phase 2 (charter) |  |
| BR-SAAS-031 | Tenant sandbox | S | P2 | Không cần diễn tập chuyển đổi dữ liệu (D-09); thay vào đó dùng import chỉ kiểm tra hợp lệ |  |
| BR-SAAS-032 | Back-office của operator | M | P1 | Vận hành SaaS cốt lõi |  |
| BR-SAAS-033 | Cấp quyền hỗ trợ dựa trên sự đồng ý | M | P1 | Bảo mật / PDPL |  |
| BR-SAAS-034 | Che dữ liệu trong phiên hỗ trợ | M | P1 | Bảo mật / PDPL |  |
| BR-SAAS-035 | Impersonation (xem với tư cách người dùng) | M | P2 | Cắt giảm: bộ phận hỗ trợ làm việc trong phạm vi quyền được cấp, không dùng impersonation |  |
| BR-SAAS-036 | Break-glass hai người | M | P2 | Cắt theo Phương án B (R2-19): quy trình break-glass hai người thủ công; truy cập vẫn được ghi log (BR-CORE-041) (R2, 2026-10-16) |  |
| BR-SAAS-037 | Nhật ký truy cập của operator mà tenant xem được | M | P1 | Khôi phục bản tối giản theo R3-08: nhật ký truy cập của operator mà tenant xem được (giám sát bên xử lý dữ liệu theo PDPL) (R3, 2026-10-16) |  |
| BR-SAAS-038 | Chính sách mật khẩu | M | P1 | Baseline bảo mật  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-039 | MFA | M | P1 | Baseline bảo mật  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-040 | SSO OIDC Google/Entra | M | P2 | Không cần cho pilot (R2-04 đã xác nhận) |  |
| BR-SAAS-041 | SSO SAML/OIDC chung | S | P2 | Should |  |
| BR-SAAS-042 | Cấp phát qua SCIM | C | P3 | Could |  |
| BR-SAAS-043 | Quản lý phiên đăng nhập (web; mobile là P2) | M | P1 | Baseline bảo mật  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-044 | Tài khoản người dùng đa tenant | S | P2 | Should |  |
| BR-SAAS-045 | Đăng nhập ESS & kích hoạt bằng OTP | M | P1 | ESS web; OTP kích hoạt chỉ qua e-mail trong P1 (SMS là P2, R2-01) |  |
| BR-SAAS-046 | Framework báo cáo | M | P1 | Báo cáo chuẩn & xuất dữ liệu  (R2-02: baseline, 2026-10-16) | B |
| BR-SAAS-047 | Kho dữ liệu báo cáo riêng | M | P2 | Tải của pilot thấp; hạn mức theo tenant bảo vệ OLTP |  |
| BR-SAAS-048 | Báo cáo theo lịch | S | P2 | Should |  |
| BR-SAAS-049 | Công cụ thiết kế báo cáo | S | P2 | Công cụ thiết kế báo cáo thuộc Phase 2 |  |
| BR-SAAS-050 | Dashboard | S | P2 | Phase 2 |  |
| BR-SAAS-051 | Phân tích nhân sự | S | P2 | BI thuộc Phase 2 |  |
| BR-SAAS-052 | Framework báo cáo theo luật định | M | P1 | Biểu mẫu theo luật định (BHXH/thuế TNCN/lao động) |  |
| BR-SAAS-053 | Xuất dữ liệu bất đồng bộ có kiểm toán | M | P2 | Cắt theo Phương án B (R2-19): xuất dữ liệu chạy đồng bộ; kiểm toán qua BR-CORE-090/093 (R2, 2026-10-16) |  |
| BR-SAAS-054 | Trích xuất dữ liệu cho BI | C | P3 | Could |  |
| BR-SAAS-055 | Public REST API | S | P2 | Public API thuộc Phase 2 |  |
| BR-SAAS-056 | API key / OAuth client | S | P2 | Public API thuộc Phase 2 |  |
| BR-SAAS-057 | Giới hạn tần suất API | S | P2 | Public API thuộc Phase 2 |  |
| BR-SAAS-058 | Webhook | S | P2 | Phase 2 |  |
| BR-SAAS-059 | Xuất file bút toán kế toán | M | P1 | Chỉ xuất file (Excel/CSV); đẩy qua API kế toán là P2 (R2-01) |  |
| BR-SAAS-060 | File thanh toán ngân hàng (H2H là P2) | M | P1 | Chỉ xuất file; host-to-host là P2 (R2-01) |  |
| BR-SAAS-061 | Nộp qua API I-VAN của VSS | S | P2 | P1 tạo file (BR-PAY-047) |  |
| BR-SAAS-062 | XML eTax & chứng từ thuế TNCN điện tử | M | P1 | Xuất file XML eTax; việc ký chứng từ điện tử xem §11 R2-C3 (R2-01) |  |
| BR-SAAS-063 | Ký điện tử / hợp đồng lao động điện tử | S | P2 | Ký điện tử và hợp đồng lao động điện tử là tích hợp trực tuyến (R2-01, R2-10 đã xác nhận) |  |
| BR-SAAS-064 | VNeID | C | P3 | Could; VNeID là tích hợp trực tuyến (R2-01), vẫn là P3 |  |
| BR-SAAS-065 | Nhận dữ liệu từ máy chấm công (1 connector được chứng nhận) | M | P2 | Nhận dữ liệu máy chấm công trực tuyến là P2 (R2-01); nguồn dữ liệu chấm công P1 = file xuất từ máy + import Excel (BR-ATT-025) (R2, 2026-10-16) |  |
| BR-SAAS-066 | Nhắn tin: chỉ e-mail (SMS/Zalo/push là P2) | M | P1 | Thông báo cốt lõi: chỉ e-mail; SMS/Zalo/push là P2 (R2-01) |  |
| BR-SAAS-067 | Trang tuyển dụng | C | P3 | Could; Tuyển dụng thuộc Phase 2 |  |
| BR-SAAS-068 | Integration hub | M | P2 | Tích hợp P1 dựa trên file + connector máy chấm công |  |
| BR-SAAS-069 | Template import số dư đầu kỳ & dữ liệu gốc | M | P1 | Thay đổi theo D-09 |  |
| BR-SAAS-070 | Import chỉ kiểm tra hợp lệ, idempotent, có thể đảo ngược | M | P1 | Cần cho số dư đầu kỳ (D-09) |  |
| BR-SAAS-071 | Import số dư đầu kỳ cho go-live giữa năm (trước đây là chuyển đổi dữ liệu Oracle) | M | P1 | Thay đổi theo D-09 |  |
| BR-SAAS-072 | Đối soát số dư đầu kỳ với tổng kiểm soát của khách hàng | M | P1 | Thay đổi theo D-09 |  |
| BR-SAAS-073 | So sánh bảng lương tùy chọn với bảng lương của khách hàng (trước đây là chạy song song) | M | P1 | So sánh bảng lương một tháng với bảng lương của khách hàng chuyển sang P1 (R2-03) (R2, 2026-10-16) |  |
| BR-SAAS-074 | Wizard onboarding | S | P2 | Should |  |
| BR-SAAS-075 | ESS web responsive | M | P1 | ESS web (Phase 1) |  |
| BR-SAAS-076 | Ứng dụng di động native bao gồm chấm công GPS/selfie | S | P2 | Mobile thuộc Phase 2 (X-04, OD-9) |  |
| BR-SAAS-077 | Bảo vệ phiếu lương trên di động | S | P2 | Mobile thuộc Phase 2 |  |
| BR-SAAS-078 | Chấm công offline trên di động | S | P2 | Mobile thuộc Phase 2 |  |
| BR-SAAS-079 | Danh mục dữ liệu cá nhân | M | P1 | Luật định: PDPL |  |
| BR-SAAS-080 | Quản lý sự đồng ý | M | P1 | Luật định: PDPL |  |
| BR-SAAS-081 | Công cụ xử lý yêu cầu của chủ thể dữ liệu (DSR) | M | P1 | Luật định: PDPL |  |
| BR-SAAS-082 | Job lưu trữ & xóa dữ liệu cấu hình được | M | P2 | Không có dữ liệu hết hạn lưu trữ trong năm pilot; mức tối thiểu đã được lập thành văn bản |  |
| BR-SAAS-083 | Hồ sơ DPIA | M | P1 | Luật định: PDPL |  |
| BR-SAAS-084 | Thông báo vi phạm dữ liệu trong 72h | M | P1 | Luật định: PDPL |  |
| BR-SAAS-085 | Kiểm toán bảo mật chống giả mạo | M | P1 | Baseline bảo mật |  |
| BR-SAAS-086 | ISMS theo ISO 27001 / SOC 2 | S | P2 | Should |  |
| BR-SAAS-087 | Phân loại cấp độ an toàn hệ thống thông tin | S | P1 | Được nâng: luật định (Nghị định 85), không cần xây dựng |  |
| BR-SAAS-088 | Ngày chuyển đổi go-live & khóa số dư đầu kỳ (mới) | — (new) | P1 | Bổ sung theo D-09 |  |
| BR-SAAS-089 | Thu nhập/thuế TNCN hằng tháng trước go-live để quyết toán (mới) | — (new) | P1 | Bổ sung theo D-09 |  |
| BR-SAAS-090 | Bộ lập lịch job / hàng đợi nhận biết tenant | new (R2-02, 2026-10-16) | P1 | Nền tảng: kích hoạt theo ngày hiệu lực, import, kỳ lương, phát hành phiếu lương |  |
| BR-SAAS-091 | Năng lực baseline luôn bật (entitlement) | new (R2-02, 2026-10-16) | P1 | Quy tắc baseline R2-18 trong dịch vụ entitlement | B |
| NFR-SAAS-001 | Tính sẵn sàng 99.5% | M | P1 | Tính sẵn sàng |  |
| NFR-SAAS-002 | Hiệu năng tính lương | M | P1 | Baseline hiệu năng |  |
| NFR-SAAS-003 | Hiệu năng tương tác | M | P1 | Baseline hiệu năng |  |
| NFR-SAAS-004 | Đột biến dữ liệu chấm công (máy chấm công) | M | P2 | Đột biến dữ liệu chấm công là P2 cùng connector trực tuyến (R2-01) (R2, 2026-10-16) |  |
| NFR-SAAS-005 | Khả năng mở rộng & giới hạn tenant gây tải lớn | M | P1 | Baseline cho gói dùng chung |  |
| NFR-SAAS-006 | Sao lưu & DR tại VN | M | P1 | Baseline khả năng phục hồi |  |
| NFR-SAAS-007 | Mã hóa | M | P1 | Baseline bảo mật |  |
| NFR-SAAS-008 | Secret lưu trong vault | M | P1 | Baseline bảo mật (đã bỏ nội dung về Oracle, D-09) |  |
| NFR-SAAS-009 | ASVS L2, quét lỗ hổng, kiểm thử xâm nhập | M | P1 | Baseline bảo mật |  |
| NFR-SAAS-010 | Khả năng quan sát & thời hạn lưu log | M | P1 | Lưu log theo luật định |  |
| NFR-SAAS-011 | WCAG 2.1 AA | S | P2 | Should |  |
| NFR-SAAS-012 | Hỗ trợ trình duyệt, 360 px (hệ điều hành cho app là P2) | M | P1 | ESS web responsive |  |
| NFR-SAAS-013 | Bản địa hóa đầy đủ | M | P1 | Bản địa hóa |  |
| NFR-SAAS-014 | Release không gián đoạn dịch vụ | M | P1 | An toàn trong khung thời gian tính lương |  |
| NFR-SAAS-015 | Quản trị release & toggle | M | P1 | An toàn tính toán |  |
| NFR-SAAS-016 | SLA đáp ứng thay đổi quy định | M | P1 | Sẵn sàng cho thay đổi luật định |  |
| NFR-SAAS-017 | Truy vết tính toán | M | P1 | Bằng chứng theo luật định |  |
| NFR-SAAS-018 | Chất lượng public API | S | P2 | Public API thuộc Phase 2 |  |
| NFR-SAAS-019 | Xuất toàn bộ dữ liệu tenant ≤6 h | M | P2 | Gắn với offboarding (P2) |  |
| NFR-SAAS-020 | SLA hỗ trợ | M | P1 | Hỗ trợ pilot |  |
| NFR-SAAS-021 | Năng lực cao điểm (khóa lương, mùa quyết toán thuế TNCN) | M | P1 | Cao điểm quyết toán thuế TNCN |  |
| NFR-SAAS-022 | Hiệu năng ứng dụng di động | S | P2 | Mobile thuộc Phase 2 |  |


## 10. Tái lập các con số

Không có sẵn Python, nên việc trích xuất và đếm dùng `awk` (Git Bash). Dữ liệu xếp hạng là tập hợp các bảng §9 trong file này.

```bash
cd docs/01-requirements/brd
# 1. extract every BR/NFR row of the module BRDs: ID and current priority
awk -F'|' '/^\| *\**(BR|NFR)-[A-Z]+-[0-9]+/ { id=$2; gsub(/[ *]/,"",id); p=$4; gsub(/^ +| +$/,"",p); print id"\t"p }' \
  modules/0[1-6]-*.md > /tmp/reqs.tsv
# 2. pull the ratings (ID, old priority, new phase) out of §9 of this file
awk -F'|' '/^## 9\./{s=1} /^## 10\./{s=0} s && /^\| (BR|NFR)-/ { id=$2; o=$4; n=$5; gsub(/ /,"",id); gsub(/^ +| +$/,"",o); gsub(/ /,"",n); o=(o ~ /new/)?"new":substr(o,1,1); print id"\t"o"\t"n }' \
  mvp-scope.md > /tmp/rated.tsv
# 3. coverage: IDs in the BRDs without a rating, and ratings without a BRD row (expect none)
comm -3 <(cut -f1 /tmp/reqs.tsv | sort) <(cut -f1 /tmp/rated.tsv | sort)
# 4. counts per module and type: old priority and new phase ("new" = requirement added on 2026-10-16)
#    ("Removed" rows keep their ID in the module BRDs, so step 3 still covers them; step 4 excludes them from every count)
awk -F'\t' '{split($1,x,"-"); k=x[2]" "x[1]; if($3=="Removed"){r[k]++; t[k]+=0; next} t[k]++; o[k" "$2]++; n[k" "$3]++}
  END{for(k in t) printf "%s total=%d oldM=%d S=%d C=%d new=%d | P1=%d P2=%d P3=%d removed=%d\n",
      k,t[k],o[k" M"],o[k" S"],o[k" C"],o[k" new"],n[k" P1"],n[k" P2"],n[k" P3"],r[k]}' /tmp/rated.tsv | sort
```

Bổ sung ở vòng 2: kiểm tra không khớp phase (§12) và đếm baseline (§3.4).

```bash
# 5. current phase tag per row in the module BRDs (T&A BR rows: Phase column; others: MoSCoW, overridden by a "→ P1/P2" or "Phase 2" note)
awk -F'|' '/^\| *\**(BR|NFR)-[A-Z]+-[0-9]+/ { f=FILENAME; sub(/.*\//,"",f); id=$2; gsub(/[ *]/,"",id); p=$4; gsub(/^ +| +$/,"",p); ph=""; nt="";
  if (f ~ /^03-/ && id ~ /^BR/) {ph=$5; gsub(/^ +| +$/,"",ph)} else {nt=$5; gsub(/^ +| +$/,"",nt)}
  if (ph!="") { tag=ph; imp=(ph ~ /^MVP/)?"P1":(ph ~ /^P2/?"P2":(ph ~ /^P3/?"P3":"?")) }
  else { l=substr(p,1,1); imp=(l=="M")?"P1":(l=="S"?"P2":"P3"); tag=p;
    if (nt ~ /→ *P2/) imp="P2"; else if (nt ~ /→ *P1/) imp="P1"; else if (nt ~ /^Phase 2/) imp="P2" }
  if ($3 ~ /Removed —/) imp="Removed"
  print f"\t"id"\t"tag"\t"imp }' modules/0[1-6]-*.md > /tmp/cur.tsv
# 6. mismatch check: rows whose module tag differs from §9. Only the T&A BR Phase column must match (expect 0 for 03-time-attendance BR rows); elsewhere MoSCoW is business priority (banner rule, §12)
join -t $'\t' -1 2 -2 1 <(sort -t $'\t' -k2,2 /tmp/cur.tsv) <(sort -k1,1 /tmp/rated.tsv) \
  | awk -F'\t' '$2 !~ /^05-/ && $4!=$6' | sort -t $'\t' -k2,2 -k1,1V
# 7. baseline rows per module (column 7 of the §9 tables)
awk -F'|' '/^## 9\./{s=1} /^## 10\./{s=0} s && /^\| (BR|NFR)-/ && $7 ~ /B/ {split($2,x,"-"); c[x[2]" "x[1]]++} END{for(k in c) print k, c[k]}' mvp-scope.md | sort
```

Các kiểm tra chạy ngày 2026-10-16 sau vòng 3 (và đợt đồng bộ phạm vi cuối cùng R2-02): 583 dòng trong các BRD module, 583 xếp hạng (494 BR đang hiệu lực + 84 NFR + 5 bị loại), không thiếu ID nào ở cả hai phía (đầu ra bước 3 rỗng), không có bản trùng. Các bước 1–4 và 7 cho ra các con số trong §3 và §3.4. Bước 6 cho 0 điểm không khớp đối với cột Phase BR của T&A.

**Ghi chú về cột "Độ ưu tiên cũ".** Cột này thể hiện giá trị MoSCoW trước khi xếp hạng lại ở R1. Chạy lại bước 1 trên các file hiện tại cho ra hai điểm khác biệt: BR-SAAS-073 nay là `S`, và BR-SAAS-088/089 nay đã tồn tại với giá trị `M`. Đó là lý do [BRD.md](BRD.md) §9 thể hiện SaaS là M = 57 và S = 26, trong khi §3 ở đây thể hiện các giá trị trước khi xếp hạng lại (M = 56, S = 25, 2 mới).

## 11. Các thay đổi vòng 2 (2026-10-16)

Nguồn: [decision-log.md](../../00-project-management/decision-log.md) §7, §7.1 và các diễn giải của PM trong §7.2. Mọi dòng thay đổi trong §9 đều có "(R2, 2026-10-16)" trong lý do. Các dòng không đổi phase nhưng được cập nhật lý do để ghi nhận một quyết định vòng 2 thì dẫn chiếu ID quyết định, không kèm nhãn ngày.

### 11.1 Thay đổi phase (34 dòng: 31 BR, 3 NFR)

| Quyết định | Các ID | Từ → sang |
|---|---|---|
| R2-19 Phương án B | Core: BR-CORE-051, 053, 058, 071, 083 · People: BR-PPL-005, 018, 019, 022, 133 · T&A: BR-ATT-026, 034, 043, 066, 070, 075, 083 · Payroll: BR-PAY-021, 073, 090, 112, 114 · SaaS: BR-SAAS-009, 013, 036, 037, 053 | P1 → P2 (27 BR) |
| Ngoại lệ của R2-19 | BR-PAY-102 (giữ theo R2-17), BR-ATT-037 (giữ theo R2-01 "nhập liệu thủ công") | vẫn P1 |
| R2-01 / §7.2 tích hợp trực tuyến | BR-ATT-021 (connector máy chấm công; phần import file chuyển sang BR-ATT-025), BR-SAAS-065 (nhận dữ liệu máy chấm công), NFR-ATT-001 (tốc độ chấm công), NFR-ATT-004 (bộ đệm offline), NFR-SAAS-004 (đột biến dữ liệu chấm công) | P1 → P2 (2 BR, 3 NFR) |
| R2-07 | BR-ATT-063 dữ liệu hồ sơ hưởng chế độ BHXH, bản tối giản: loại/số giấy chứng nhận, cơ sở KCB, ngày, dữ liệu về con, file đính kèm | P2 → P1 |
| R2-03 | BR-SAAS-073 so sánh bảng lương một tháng với bảng lương của khách hàng | P2 → P1 |

### 11.2 Tích hợp: Phase 1 giữ lại gì và chuyển đi gì (R2-01, §7.2)

| Giữ ở P1 (file hoặc trong sản phẩm) | Các ID | Chuyển sang P2 (tích hợp trực tuyến) | Các ID |
|---|---|---|---|
| E-mail (đăng nhập, OTP MFA, kích hoạt ESS, OTP cho nhân viên cũ theo OD-8, thông báo) và thông báo trong ứng dụng | BR-CORE-038, 070, BR-SAAS-039, 045, 066 | Zalo OA/ZNS, SMS, push | BR-CORE-070 (phần S/C), BR-SAAS-066 (phần không phải e-mail), BR-CORE-059 |
| File thanh toán ngân hàng | BR-PAY-110, BR-SAAS-060 | Host-to-host / open banking | BR-SAAS-060 (phần API) |
| File kê khai VSS (D02-LT, TK1-TS), do khách hàng tải lên qua I-VAN của mình | BR-PAY-047 | Nộp qua API I-VAN của VSS | BR-SAAS-061 |
| XML thuế TNCN cho eTax/HTKK (05/KK, 05/QTT); XML/PDF chứng từ điện tử | BR-PAY-056, 057, 059, BR-SAAS-062 | Chữ ký số / CA, VNeID | BR-CORE-103, BR-PPL-134, BR-SAAS-063, BR-PPL-012, BR-SAAS-064 (P3) |
| Bút toán GL dạng Excel/CSV | BR-PAY-113, BR-SAAS-059 | Đẩy qua API kế toán (MISA, Fast, SAP) | BR-SAAS-059 (phần API) |
| Import Excel dữ liệu chấm công và bảng công, bao gồm **template file xuất từ máy chấm công (CSV/Excel)** | BR-ATT-025, 022 (ánh xạ mã người dùng trên máy chấm công), 020 | Connector máy chấm công, bộ đệm offline, NFR về tốc độ chấm công | BR-ATT-021, BR-SAAS-065, NFR-ATT-001, 004, NFR-SAAS-004 |
| Nhập liệu thủ công và yêu cầu điều chỉnh công | BR-ATT-036, 037 | Trang tuyển dụng, public API, webhook, runtime của integration hub | BR-REC-016/017, BR-SAAS-067 (P3), BR-SAAS-055–058, BR-ATT-085, BR-SAAS-068 |

### 11.3 Các quyết định được xác nhận mà không đổi phase

| Quyết định | Các ID | Phase |
|---|---|---|
| R2-04 SSO | BR-CORE-039, BR-SAAS-040, 041 (SCIM BR-SAAS-042: P3) | P2 |
| R2-05 Kỷ luật | BR-PPL-080, 081 (082–084 vốn đã là P2) | P2 |
| R2-06 Chấm công trên web | BR-ATT-024 | P2 |
| R2-08 / R2-16 Giới hạn làm thêm giờ liên pháp nhân | BR-ATT-047 | P2 |
| R2-09 Đa tiền tệ | BR-PAY-013 | P2 |
| R2-10 Hợp đồng lao động điện tử | BR-PPL-058, BR-SAAS-063 | P2 |
| R2-14 Thưởng / Tết | BR-PAY-070 các loại kỳ lương thưởng và ngoài chu kỳ với thuế TNCN chính xác: **P1**. Quy tắc điều kiện và tính theo tỷ lệ của Tết/tháng 13 (gap-analysis GAP-067 "kế hoạch thưởng"; phần tháng 13 theo tỷ lệ trong BR-PAY-077): **P2**. Pilot trả thưởng Tết như một khoản một lần được nhập dưới dạng dữ liệu đầu vào biến đổi; kiểm thử với dữ liệu mẫu | P1 / P2 |
| R2-16 Làm việc kiêm nhiệm | BR-PPL-002, 004, BR-PAY-081: một quan hệ lao động cho mỗi pháp nhân, quan hệ lao động chính dùng cho giảm trừ gia cảnh thuế TNCN, một đơn vị đóng BHXH duy nhất, mỗi pháp nhân khấu trừ thuế TNCN trên khoản chi trả của mình | P1 |
| R2-17 Gửi phiếu lương | BR-PAY-101 (ESS web) và BR-PAY-102 (PDF có mật khẩu qua e-mail). BR-CORE-074 giữ nguyên quy tắc, với phiếu lương là ngoại lệ đã thống nhất | P1 |
| R2-18 Baseline | §3.4 và cột Baseline trong §9 | — |

### 11.4 Xung đột với tuân thủ luật định và các điểm còn mở khác

| # | Nội dung | Vì sao quan trọng | Cách xử lý đề xuất | Chủ sở hữu |
|---|---|---|---|---|
| R2-C1 | **Đã giải quyết — R3-01 (2026-10-16):** BR-SAAS-014/015 → P2; bộ phận tài chính của nhà cung cấp phát hành hóa đơn điện tử từ công cụ hóa đơn điện tử hiện có dựa trên báo cáo mức sử dụng. Nội dung ban đầu: **Sponsor quyết định.** Việc lập hóa đơn điện tử cho các hóa đơn thuê bao của chính YourHRM (BR-SAAS-014/015) cần một nhà cung cấp hóa đơn điện tử được chứng nhận, đây là một tích hợp trực tuyến. **Tạm thời giữ ở P1** vì là yêu cầu luật định. Các kiến trúc sư coi đây là P2, với bộ phận tài chính của nhà cung cấp lập hóa đơn thủ công từ báo cáo mức sử dụng theo R2-01 | Đây là nghĩa vụ của chính nhà cung cấp (Nghị định 123/2020 được sửa đổi bởi Nghị định 70/2025), không phải của tenant. Cả hai phương án đều tuân thủ nếu hóa đơn được phát hành đúng hạn | Phương án A: giữ P1 (một liên kết với nhà cung cấp). Phương án B: P2, và bộ phận tài chính của nhà cung cấp phát hành hóa đơn bằng phần mềm hóa đơn điện tử hiện có dựa trên báo cáo số lượng nhân viên cuối tháng (−2 BR P1). Khi đó tài liệu kiến trúc và file này phải thống nhất | **Sponsor**; PM + bộ phận tài chính của nhà cung cấp |
| R2-C2 | **Đã quyết định — R3-03 (2026-10-16), chờ xác nhận pháp lý:** quy tắc nhiều hợp đồng là một tham số pháp lý theo từng loại bảo hiểm (BHXH, BHYT, BHTN, BHTNLĐ-BNN) với các giá trị FIRST_CONTRACT, HIGHEST_SALARY hoặc EACH_EMPLOYER. Mặc định: BHXH, BHTN và BHYT = FIRST_CONTRACT (sponsor), BHTNLĐ-BNN = EACH_EMPLOYER, tất cả đều `[verify-legal]`. PM lưu ý rằng luật BHYT có thể yêu cầu HIGHEST_SALARY; tư vấn pháp lý xác nhận. Nội dung ban đầu: **R2-16 "một đơn vị đóng BHXH duy nhất"** | Với người có nhiều hợp đồng lao động, BHXH/BHTN theo hợp đồng đầu tiên, nhưng BHYT phải đóng theo hợp đồng có mức lương cao nhất, và BHTNLĐ-BNN phải đóng bởi **từng** người sử dụng lao động `[verify-legal]`. Quy tắc cứng "một đơn vị đóng BHXH" có thể dẫn đến khai thiếu BHYT hoặc BHTNLĐ-BNN ở pháp nhân thứ hai | Mô hình hóa việc tham gia BHXH, BHYT, BHTN và BHTNLĐ-BNN riêng biệt theo từng quan hệ lao động (People BR-PPL-004, Payroll BR-PAY-040/044). Tư vấn pháp lý xác nhận (GAP-031) | People + Payroll BA, tư vấn pháp lý |
| R2-C3 | **Đã giải quyết — R3-02 (2026-10-16):** ký điện tử vẫn là P2. Trong P1, BR-PAY-059 tạo dữ liệu chứng từ và một file PDF chưa ký; khách hàng ký và phát hành chứng từ qua công cụ của cơ quan thuế hoặc nhà cung cấp của mình (`[verify-legal]` cho năm 2027). Nội dung ban đầu: **Chứng từ khấu trừ thuế TNCN điện tử** (BR-PAY-059, BR-SAAS-062, P1) cần chữ ký số của người sử dụng lao động và, theo Nghị định 70/2025 và Thông tư 32/2025, có thể phải gửi dữ liệu cho cơ quan thuế `[verify-legal]`. Ký điện tử (BR-CORE-103, BR-SAAS-063) là P2 và không có liên kết với cơ quan thuế trong P1 | Người nghỉ việc có thể yêu cầu chứng từ bất cứ lúc nào; chứng từ cả năm đến hạn vào đầu năm 2027 | P1 tạo XML/PDF chứng từ. Tenant ký và truyền qua nhà cung cấp chứng từ điện tử/hóa đơn điện tử của mình, hoặc bổ sung một bước ký tối giản bằng token của chính tenant vào P1. Quyết định trước go-live | Payroll BA + tư vấn pháp lý |
| R2-C4 | **Đã giải quyết — R3-06 (2026-10-16):** BR-ATT-075 quay lại P1. Nội dung ban đầu: **Cắt BR-ATT-075** (khóa sớm cho một nhân viên) | Thanh toán khi nghỉ việc phải hoàn tất trong 14 ngày làm việc (Bộ luật Lao động Điều 48) và có thể rơi vào trước khi kỳ công được khóa | Dữ liệu chấm công cuối cùng của người nghỉ việc được nhập dưới dạng dữ liệu đầu vào biến đổi cho kỳ lương thanh toán khi nghỉ việc. Payroll BA xác nhận logic giữ khóa X-03 cho phép chạy kỳ lương thanh toán khi nghỉ việc mà không cần kỳ công T&A đã khóa | Payroll + T&A BA |
| R2-C5 | **Đã giải quyết — R3-07 (2026-10-16):** BR-PPL-019 quay lại P1 ở dạng tối giản (trường trình độ chuyên môn cho sổ quản lý lao động). Nội dung ban đầu: **Cắt BR-PPL-019** (học vấn & chứng chỉ) | Sổ quản lý lao động (BR-PPL-140, Nghị định 145) bao gồm trình độ chuyên môn / kỹ năng nghề | Giữ một trường "trình độ chuyên môn / kỹ năng nghề" trong BR-PPL-010 hoặc BR-PPL-140 | People BA |
| R2-C6 | **Đã giải quyết — R3-05 (2026-10-16):** HR in phiếu lương PDF và trao cho nhân viên không có e-mail. Không phát hành mã kích hoạt, và ESS không khả dụng cho những nhân viên này trong P1. Nội dung ban đầu: **Phụ thuộc vào e-mail** | Công nhân nhà máy thường không có e-mail công việc hay cá nhân. Kích hoạt ESS (BR-SAAS-045), OTP MFA, OTP cho nhân viên cũ theo OD-8 và phiếu lương qua e-mail (BR-PAY-102) đều phụ thuộc vào e-mail khi SMS đã là P2. Người sử dụng lao động vẫn phải cung cấp thông tin phiếu lương cho từng nhân viên mỗi lần trả lương (Bộ luật Lao động Điều 95(3)) | Phiếu lương PDF in giấy hoặc do HR trao tay (PDF của BR-PAY-100), mã kích hoạt dùng một lần do HR cấp; thu thập e-mail cá nhân khi tuyển dụng | Payroll + SaaS BA |
| R2-C7 | **Đã giải quyết — R3-04 (2026-10-16):** nhân viên tự đặt mật khẩu PDF trên ESS, không có mật khẩu mặc định hay mật khẩu suy diễn. Cho đến khi đặt mật khẩu, e-mail chỉ là thông báo không kèm file đính kèm. Vẫn cần DPO ký xác nhận. Nội dung ban đầu: **Phiếu lương PDF có mật khẩu** (R2-17) nới lỏng quy tắc deep-link của Core | PDPL: mật khẩu PDF phải đủ mạnh. Quy tắc ví dụ trong BR-PAY-102 ("ngày sinh + 4 số cuối giấy tờ định danh") dễ đoán | DPO xác nhận; PIN do tenant hoặc nhân viên đặt; kiểm toán việc gửi (GAP-057) `[verify-legal]` | DPO + Payroll BA |
| R2-C8 | **Đã giải quyết — R3-08 (2026-10-16):** BR-SAAS-037 quay lại P1 ở dạng tối giản (nhật ký truy cập của operator mà tenant xem được). Break-glass hai người (BR-SAAS-036) vẫn là P2. Nội dung ban đầu: **Cắt BR-SAAS-036/037** (break-glass hai người, nhật ký operator mà tenant xem được) | Giám sát của bên kiểm soát dữ liệu đối với bên xử lý dữ liệu theo PDPL | Truy cập vẫn được ghi log (BR-CORE-041, BR-SAAS-033/034); log được xuất cho tenant khi có yêu cầu; quy trình hai người thủ công được lập thành văn bản | SaaS BA + DPO |
| R2-C9 | **Đã giải quyết (đồng bộ R2-02).** Phạm vi baseline (R2-18) chưa liệt kê lịch ngày lễ và mẫu lịch làm việc | Payroll không có T&A vẫn cần ngày lễ có hưởng lương và ngày công chuẩn (BR-PAY-009, BR-PAY-031) | API đọc lịch ngày lễ và lịch làm việc (ATT-P-16, nay là M) và công cụ mô phỏng gross↔net (IF-PAY-P-15) được đánh dấu là **dịch vụ luôn bật ở mức API** (`API` trong §9; §3.4). Các tính năng T&A đầy đủ vẫn gắn với module | PM (đã đóng) |
| R2-C10 | **Cắt BR-PAY-073** (kỳ lương cho tập con / một người / mô phỏng) | G2 (kỳ lương đầu tiên được rà soát trước khi phê duyệt) và phần so sánh của R2-03 cần một kỳ lương đã tính nhưng chưa phê duyệt | Dùng vòng đời kỳ lương thông thường (đã tính, chưa trình duyệt) làm bản xem trước, và một kỳ lương so sánh bị hủy cho BR-SAAS-073 | Payroll BA |
| R2-C11 | **Đã giải quyết — R3-09 (2026-10-16):** các thuộc tính tùy chỉnh được ánh xạ sang trường chuẩn hoặc file đính kèm trong quá trình kiểm tra hợp lệ template (BR-SAAS-070/071). Nội dung ban đầu: **Trường tùy chỉnh là P2** (BR-CORE-086, MoSCoW nay là M) | Template hồ sơ nhân viên đầu kỳ có thể chứa các thuộc tính riêng của tenant mà không có chỗ lưu nếu thiếu trường tùy chỉnh | Giữ P2. Pilot ánh xạ thuộc tính của tenant sang trường chuẩn hoặc sang file đính kèm; mọi thuộc tính pilot cần phải được liệt kê trong quá trình kiểm tra hợp lệ template | PM + People BA |

Sau vòng 3, chỉ còn R2-C2 (xác nhận pháp lý về quy tắc bảo hiểm) và các điểm quy trình R2-C9/C10. Không có quyết định nào của vòng 2 hay 3 loại bỏ một đầu ra theo luật định: các đầu ra về BHXH, thuế TNCN, hợp đồng lao động, giới hạn làm thêm giờ, nghỉ phép và PDPL vẫn là P1. R2-C1 đến R2-C3 là những điểm mà "không có tích hợp trực tuyến" và một nghĩa vụ luật định gặp nhau.

### 11.5 Đồng bộ phạm vi cuối cùng R2-02 (2026-10-16)

Các BA module đã áp dụng bảng một-chủ-sở-hữu (R2-02). Việc này bổ sung 9 yêu cầu và loại 5 dòng khỏi module cũ. Chạy lại bước 3 cho ra 583 dòng module so với 574 xếp hạng. Sau đợt đồng bộ này, hai phía khớp nhau: 583 = 583.

**Yêu cầu mới**, do PM xếp hạng và được đánh dấu "new (R2-02, 2026-10-16)" trong §9:

| ID | Tiêu đề | Phase | Lý do |
|---|---|---|---|
| BR-CORE-088 | Bảng tỷ giá | P2 | Đa tiền tệ là P2 (R2-09) |
| BR-PPL-023 | Tình trạng cư trú thuế | P1, `B` | Luật định (cư trú thuế TNCN); thuộc hồ sơ thuế trong thông tin cơ bản của People |
| BR-PPL-039 | Thực thi ánh xạ tái cơ cấu | P2 | Gói tái cơ cấu là P2 |
| BR-PAY-065 | Kiểm tra cư trú 183 ngày | P1 | Luật định |
| BR-PAY-085 | Gán nhóm lương | P1 | Chuỗi xử lý |
| BR-PAY-086 | Sự kiện People ảnh hưởng đến lương | P1 (một phần) | Chuỗi xử lý, chỉ cho các nguồn phát P1: tạm hoãn/tiếp tục hợp đồng (BR-PPL-056) và tạm thời chuyển công việc (BR-PPL-035). P2 cùng với nguồn phát của chúng: khen thưởng (BR-PPL-085), biệt phái (034), đánh giá (093), tạm ứng tiền mặt (111) và cam kết đào tạo (101) |
| BR-PAY-087 | Trạng thái khóa pháp nhân | P1 | Chuỗi xử lý |
| BR-SAAS-090 | Bộ lập lịch job / hàng đợi | P1 | Nền tảng |
| BR-SAAS-091 | Baseline luôn bật | P1, `B` | R2-18 |

**Các dòng bị loại**: phase `Removed`, không tính vào mọi con số. Các ID được giữ lại và không tái sử dụng.

| ID bị loại | Phase cũ | Chủ sở hữu mới | Năng lực có còn được đáp ứng? |
|---|---|---|---|
| BR-CORE-038 đăng nhập nội bộ, chính sách mật khẩu, MFA, phiên đăng nhập | P1, `B` | saas-platform | **Có.** BR-SAAS-038 (chính sách mật khẩu), 039 (MFA) và 043 (phiên đăng nhập) đều là P1 và nay được đánh dấu `B` |
| BR-CORE-039 SSO / SCIM | P2 | saas-platform | BR-SAAS-040/041 P2, 042 P3. Không mất gì ở P1 |
| BR-CORE-042 API client của tenant | P2 | saas-platform | BR-SAAS-056 P2. Không mất gì ở P1 |
| BR-CORE-113 framework xuất dữ liệu | P1, `B` | saas-platform | **Có.** BR-SAAS-046 (xuất báo cáo và xuất danh sách) là P1 và nay được đánh dấu `B`. Xuất bất đồng bộ (BR-SAAS-053) vẫn là mục cắt theo Phương án B (P2), nên xuất dữ liệu ở P1 chạy đồng bộ |
| BR-SAAS-027 trường tùy chỉnh | P2 | core | BR-CORE-086, P2. MoSCoW đổi S → M, phase vẫn là P2 (R2-C11). Không mất gì ở P1 |

**Các thay đổi khác được PM phê duyệt**:
- **ATT-P-16** (API đọc lịch ngày lễ và lịch làm việc) nay có MoSCoW là M. Thay đổi này chỉ được ghi nhận trong T&A §7, vì đây là một interface. Đây là API luôn bật, tương tự công cụ mô phỏng gross↔net (IF-PAY-P-15, trong Payroll §7). Cả hai đều được đánh dấu `API` trong §9; xem §3.4.
- **BR-PPL-050** (bản ghi hợp đồng) được đánh dấu `B`, vì BR-SAAS-091 liệt kê "hợp đồng" trong thông tin cơ bản của People.

## 12. Đồng bộ nhãn phase cho các BRD module (thay thế ngày 2026-10-16)

Danh sách nhãn theo từng dòng trong v0.2 bị thu hồi. **Quy tắc banner phase** thay thế nó. Mỗi BRD module 01–06 nay có banner sau đây ngay sau bảng kiểm soát tài liệu:

> **Phase:** phase bàn giao của mọi yêu cầu chỉ được xác định tại [mvp-scope.md](../mvp-scope.md) §9; MoSCoW ở đây là độ ưu tiên nghiệp vụ. (2026-10-16)

Banner được đặt sau bảng thay vì bên trong bảng, để bảng vẫn hiển thị đúng.

Hệ quả:
- **§9 của file này là nguồn duy nhất xác định phase.** Giá trị MoSCoW `M` trong một BRD module không còn đồng nghĩa với P1.
- **03-time-attendance.md** giữ cột Phase, và cột đó nay được đồng bộ với §9: 9 dòng đã được cập nhật, với "MVP" nghĩa là P1. Đó là BR-ATT-021, 026, 034, 043, 063, 066, 070, 075 và 083.
- **Kiểm tra.** Chạy lại các bước 5–6 cho ra **0 điểm không khớp** đối với các dòng BR của T&A. Các dòng NFR của T&A không có cột Phase, nên banner bao trùm chúng.
- **Các module khác.** Phần đầu ra còn lại của bước 6 cho 01, 02, 04 và 06 (64 dòng) chỉ là chênh lệch giữa MoSCoW và phase. Điều này là dự kiến theo quy tắc banner và không cần xử lý.
