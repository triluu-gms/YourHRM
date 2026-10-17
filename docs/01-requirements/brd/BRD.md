# YourHRM — Tài liệu Yêu cầu Nghiệp vụ (Bản tổng)

> **Cách đọc tài liệu này.** Đây là BRD tổng hợp ở cấp sản phẩm. Tài liệu nêu *vì sao* YourHRM tồn tại, *cần đạt được gì*, *những gì thuộc và không thuộc phạm vi* theo từng giai đoạn, và *cách* các module phối hợp với nhau từ đầu đến cuối. Yêu cầu chi tiết nằm trong sáu BRD module và chỉ được tham chiếu ở đây — không bao giờ nhắc lại.

## 1. Kiểm soát tài liệu

| Hạng mục | Giá trị |
|---|---|
| Tài liệu | `docs/01-requirements/brd/BRD.md` — Tài liệu Yêu cầu Nghiệp vụ tổng |
| Phiên bản | 0.3 (R3) (bản nháp) |
| Ngày | 2026-10-16 |
| Trạng thái | Bản nháp để review — Phase 0 |
| Tác giả | Lead Business Analyst |
| Người review | Nhà tài trợ điều hành — TBD · Trưởng bộ phận HR/C&B của khách hàng pilot — TBD · PM — TBD · Solution architect — TBD · Chuyên gia tư vấn lao động/thuế VN — TBD |
| Phê duyệt | Đang chờ |
| Tài liệu nguồn | [Điều lệ dự án](../../00-project-management/project-charter.md) · [Nhật ký quyết định](../../00-project-management/decision-log.md) · [Đánh giá lại phạm vi MVP](mvp-scope.md) · [01 Core](modules/01-core.md) · [02 People](modules/02-people.md) · [03 Time & Attendance](modules/03-time-attendance.md) · [04 Payroll](modules/04-payroll.md) · [05 Recruitment](modules/05-recruitment.md) · [06 SaaS Platform](modules/06-saas-platform.md) · [Gap analysis](gap-analysis.md) |

**Quy ước** (điều lệ dự án §4): mã yêu cầu `BR-<MOD>-NNN` / `NFR-<MOD>-NNN` với `MOD ∈ CORE, PPL, ATT, PAY, REC, SAAS`; độ ưu tiên theo MoSCoW, trong đó `M` = cần cho MVP / tenant trả phí đầu tiên. Mọi giá trị pháp lý là **tham số có hiệu lực theo thời gian**, không bao giờ hard-code, và được đánh dấu `[verify-legal]` cho đến khi được chuyên gia tư vấn lao động/thuế Việt Nam xác nhận.

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| 0.1 | 2026-10-16 | Bản tổng hợp đầu tiên từ các BRD module v0.1 |
| 0.2 | 2026-10-16 | Áp dụng các quyết định của nhà tài trợ X-04, OD-9, D-09 và D-10 ([nhật ký quyết định](../../00-project-management/decision-log.md)). (1) MVP cắt còn "tính năng lõi của mỗi module". Mọi BR/NFR được đánh giá lại P1/P2/P3 trong [mvp-scope.md](mvp-scope.md); giai đoạn đó được ưu tiên hơn MoSCoW khi xác định phạm vi. (2) Không có tính năng mobile ở Phase 1: không app native, PWA, check-in GPS/selfie hay web, và không có push. Dữ liệu chấm công của pilot lấy từ máy chấm công và import Excel. ESS vẫn là web app responsive. (3) Không chuyển đổi từ Oracle hay chạy song song. Thay vào đó dùng chức năng import số dư đầu kỳ tổng quát (mẫu Excel), và tiêu chí go-live giờ dựa trên đối soát số dư đầu kỳ. (4) Nhân viên tính phí = người đang làm việc tại cuối tháng theo từng pháp nhân. Cập nhật §2–§6, §9–§11. |
| 0.3 | 2026-10-16 | Áp dụng các quyết định vòng 2 của nhà tài trợ R2-01 – R2-19 ([nhật ký quyết định](../../00-project-management/decision-log.md) §7, §7.1, §7.2; [mvp-scope.md](mvp-scope.md) v0.2 §11). (1) Áp dụng các cắt giảm mở rộng của Phương án B: 27 trên 29 BR chuyển sang P2. BR-PAY-102 (phiếu lương PDF có mật khẩu, R2-17) và BR-ATT-037 (quản trị viên nhập bảng công thủ công, R2-01) vẫn ở P1. (2) **Không có tích hợp ngoài trực tuyến ở Phase 1.** Connector máy chấm công, Zalo/SMS/push, ký số, VNeID, API kế toán và ngân hàng, trang tuyển dụng, public API/webhook và integration hub chuyển sang P2. Dữ liệu chấm công của pilot = import Excel (bao gồm mẫu cho file xuất từ thiết bị), nhập thủ công và yêu cầu điều chỉnh công. Thông báo = e-mail và trong ứng dụng. Đầu ra theo luật định và tài chính là các file xuất. (3) BR-ATT-063 (dữ liệu hồ sơ hưởng BHXH, tối thiểu) và BR-SAAS-073 (so sánh bảng lương một tháng) chuyển sang P1. (4) Quy tắc chính sách Tết → P2; kỳ tính thưởng/ngoài kỳ vẫn ở P1. Quan hệ lao động đồng thời cơ bản ở nhiều pháp nhân vẫn ở P1. (5) Định nghĩa các năng lực baseline (luôn bật) (R2-18). **P1 = 254 BR + 58 NFR** (trước là 281 + 61). Cập nhật §2, §5, §6, §8–§11. |
| 0.3 (sync) | 2026-10-16 | Đồng bộ phạm vi cuối cùng theo R2-02. 9 yêu cầu BA mới được đánh giá: 7 P1 (BR-PPL-023, BR-PAY-065, 085, 086 một phần, 087, BR-SAAS-090, 091) và 2 P2 (BR-CORE-088, BR-PPL-039). 5 dòng bị loại bỏ sau khi chuyển quyền sở hữu (BR-CORE-038, 039, 042, 113, BR-SAAS-027); năng lực P1 của chúng được xác nhận trong BR-SAAS-038/039/043/046 (P1, baseline). BR-CORE-086 giờ là MoSCoW M, vẫn ở P2. API lịch ngày lễ/lịch làm việc (ATT-P-16) và trình mô phỏng gross↔net được đánh dấu là dịch vụ API luôn bật. Giai đoạn giờ chỉ được thiết lập trong mvp-scope.md §9 (banner trong mọi BRD module). Hóa đơn điện tử của nhà cung cấp vẫn ở P1 chờ quyết định của nhà tài trợ (mvp-scope.md §11.4 R2-C1). **P1 = 259 BR + 58 NFR.** Cập nhật §9 và RK5. |
| 0.3 (R3) | 2026-10-16 | Áp dụng vòng 3 ([nhật ký quyết định](../../00-project-management/decision-log.md) §8, R3-01 – R3-11). Hóa đơn điện tử cho thuê bao của chính nhà cung cấp (BR-SAAS-014/015) chuyển sang P2: bộ phận tài chính của nhà cung cấp xuất hóa đơn từ công cụ hiện có dựa trên báo cáo sử dụng (R3-01). BR-ATT-075 khóa sớm cho người nghỉ việc (R3-06), BR-PPL-019 trường trình độ chuyên môn tối thiểu (R3-07) và BR-SAAS-037 log truy cập tối thiểu của operator (R3-08) quay lại P1. Chứng từ khấu trừ thuế TNCN điện tử ở P1 là dữ liệu cộng với một PDF chưa ký mà khách hàng tự ký và phát hành (R3-02). Quy tắc bảo hiểm cho nhiều hợp đồng là một tham số pháp lý theo từng loại bảo hiểm, mặc định theo hợp đồng đầu tiên cho BHXH/BHTN/BHYT và theo từng người sử dụng lao động cho BHTNLĐ-BNN, `[verify-legal]` (R3-03). Mật khẩu phiếu lương chỉ do nhân viên đặt (R3-04). Nhân viên không có e-mail nhận phiếu lương PDF in sẵn và không có ESS ở P1 (R3-05). Thuộc tính tùy chỉnh trong file số dư đầu kỳ được ánh xạ trong bước kiểm tra hợp lệ (R3-09). **P1 = 260 BR + 58 NFR.** Cập nhật §2, §5.1 #39, §9, §10.1, §10.3, A6, RK5 và RK12. |

---

## 2. Tóm tắt điều hành

**Vấn đề.** Các doanh nghiệp Việt Nam đang vận hành nhân sự và tiền lương trên các hệ thống on-premise cũ (như cơ sở dữ liệu Oracle HR của khách hàng pilot) hoặc trên bảng tính. Tính lương ở Việt Nam rất khó làm đúng. Nó kết hợp bảo hiểm bắt buộc (BHXH/BHYT/BHTN) với mức trần và miễn trừ, thuế TNCN lũy tiến với giảm trừ gia cảnh, phụ cấp làm thêm giờ và làm đêm, lương tối thiểu vùng, và các thay đổi pháp lý thường xuyên. Chỉ riêng giai đoạn 2025–2026 đã có Luật BHXH 2024, Luật Thuế TNCN 2025 với các mức giảm trừ và bậc thuế mới, Luật Việc làm 2025, Luật Bảo vệ dữ liệu cá nhân 2025, mức lương tối thiểu vùng mới và việc sáp nhập tỉnh. Mỗi thay đổi đồng nghĩa với việc làm lại bảng tính, rủi ro tuân thủ và khóa sổ cuối tháng chậm. Các tập đoàn có nhiều pháp nhân phải làm lặp lại công việc cho từng pháp nhân.

**Sản phẩm.** YourHRM là một nền tảng HRM SaaS multi-tenant xây dựng trên PostgreSQL và Java Spring Boot. Sản phẩm bao quát cơ cấu và quyền truy cập của doanh nghiệp (Core), vòng đời nhân viên (People), Chấm công (Time & Attendance), Tính lương theo quy định Việt Nam với BHXH và thuế TNCN, và Tuyển dụng. Một lớp nền tảng SaaS thương mại bổ sung vòng đời tenant, tính phí, định danh, báo cáo, tích hợp, import số dư đầu kỳ và ESS dạng web responsive. App mobile native và các tích hợp trực tuyến (máy chấm công, Zalo/SMS, ký số, API ngân hàng và kế toán) sẽ có ở Phase 2. Phase 1 trao đổi dữ liệu với các hệ thống bên ngoài chỉ qua xuất file và import Excel.

**Khách hàng mục tiêu.** Các tổ chức Việt Nam có khoảng 20 đến 10.000 nhân viên mỗi tenant. Bao gồm SME, doanh nghiệp quy mô vừa và các tập đoàn nhiều pháp nhân, kể cả các ngành thâm dụng lao động (sản xuất, may mặc, bán lẻ) cần ca làm việc, chấm công bằng thiết bị và chế độ làm thêm 300 giờ. Toàn bộ dữ liệu tenant được lưu trữ tại Việt Nam.

**Điểm khác biệt.**

| # | Điểm khác biệt | Ý nghĩa đối với khách hàng |
|---|---|---|
| D1 | **Tuân thủ quy định Việt Nam mặc định** | BHXH/BHYT/BHTN/BHTNLĐ-BNN theo luật định, kinh phí công đoàn, thuế TNCN (cư trú, không cư trú, vãng lai 10%), quy tắc làm thêm giờ/làm đêm, số ngày phép được hưởng, quy định về hợp đồng lao động và các đầu ra theo luật định (D02-LT, 05/KK-TNCN, 05/QTT-TNCN, chứng từ khấu trừ điện tử, báo cáo tình hình sử dụng lao động) được cung cấp sẵn cấu hình. |
| D2 | **Tham số pháp lý có hiệu lực theo thời gian** | Nhà cung cấp duy trì tập trung và quản lý phiên bản mọi tỷ lệ, mức trần, bậc thuế, giảm trừ, lương tối thiểu và ngày lễ. Mỗi kỳ được tính với bộ tham số có hiệu lực tại thời điểm đó, và truy lĩnh/truy thu được tính lại chính xác. Mỗi kết quả ghi nhận phiên bản tham số đã dùng (NFR-SAAS-016/017, BR-PAY-010). |
| D3 | **Đa pháp nhân từ thiết kế** | Một tenant có thể chứa 1..n pháp nhân, mỗi pháp nhân có MST, mã đơn vị BHXH và vùng lương tối thiểu riêng. Tenant có thể có quan hệ lao động đồng thời ở nhiều pháp nhân và báo cáo cấp tập đoàn. |
| D4 | **SaaS thực thụ** | Gói dùng chung và gói dedicated với cách ly được cưỡng chế ở DB, lưu trữ dữ liệu tại VN, phát hành không downtime, bật/tắt tính năng theo tenant, hóa đơn điện tử VN cho thuê bao (từ P2; R3-01), và import số dư đầu kỳ bằng Excel chuẩn, để mọi khách hàng có thể go-live giữa năm mà không cần dự án chuyển đổi dữ liệu. |

---

## 3. Mục tiêu kinh doanh & chỉ số thành công

Các mục tiêu được đề xuất để nhà tài trợ phê duyệt. Số liệu baseline sẽ được đo tại khách hàng pilot trong giai đoạn khảo sát.

| # | Mục tiêu | KPI | Chỉ tiêu | Nguồn / bằng chứng |
|---|---|---|---|---|
| O1 | Khóa sổ lương nhanh hơn, dự đoán được | Thời gian từ lúc khóa kỳ chấm công đến khi kỳ tính lương được phê duyệt | ≤ 3 ngày làm việc (baseline pilot TBD) | Payroll §7.3 về thời gian; timestamp trạng thái kỳ tính lương |
| O1 | | Thời gian thao tác của quản trị viên cho một kỳ lương tháng 500 nhân viên không có ngoại lệ | < 30 phút | NFR-PAY-012 |
| O1 | | Thời gian tính cho một kỳ 10.000 nhân viên | ≤ 15 phút | NFR-SAAS-002, NFR-PAY-001 |
| O2 | Độ chính xác theo luật định | Chênh lệch so với các golden scenario đã được chuyên gia tư vấn kiểm chứng (≥ 200 trường hợp) | 0 VND ở mọi bản phát hành và mọi lần thay đổi tham số | NFR-PAY-008 |
| O2 | | Đối soát số dư đầu kỳ với số tổng kiểm soát của khách hàng (thu nhập/thuế TNCN lũy kế từ đầu năm, lịch sử BHXH, phép, khoản vay) | 0 chênh lệch không giải thích được trước kỳ tính lương đầu tiên | BR-SAAS-072 |
| O2 | | File kê khai BHXH và thuế TNCN được BHXH Việt Nam/eTax chấp nhận ngay lần nộp đầu tiên | ≥ 98% | NFR-PAY-010 |
| O3 | Linh hoạt pháp lý | Bộ tham số pháp lý được công bố trước ngày hiệu lực (khi luật cho thời gian báo trước ≥ 30 ngày) | ≥ 7 ngày theo lịch trước đó; nếu không thì ≤ 10 ngày làm việc sau khi ban hành | NFR-SAAS-016 |
| O4 | Onboarding tenant nhanh | Operator khởi tạo một tenant hoạt động được | ≤ 10 phút | BR-SAAS-003 |
| O4 | | Từ ký hợp đồng đến go-live cho pilot (import số dư đầu kỳ, không chạy song song) | ≤ 16 tuần | BR-SAAS-071, 072, 088 |
| O4 | | Từ ký hợp đồng đến kỳ lương đầu tiên cho SME chuẩn (≤ 300 nhân viên, import theo mẫu) | ≤ 4 tuần (MVP có hỗ trợ), ≤ 2 tuần (wizard ở Phase 2) | BR-SAAS-069, 074 |
| O5 | Tự phục vụ cho nhân viên & quản lý | Yêu cầu nghỉ phép, làm thêm giờ và điều chỉnh công được gửi qua ESS/MSS thay vì trên giấy | ≥ 90% sau 3 tháng kể từ go-live | Báo cáo sử dụng ESS |
| O5 | | Thời gian trung vị của chu trình phê duyệt đơn nghỉ phép | ≤ 1 ngày làm việc | Báo cáo SLA workflow Core R-07 |
| O6 | Tin cậy & bảo mật | Sự cố dữ liệu xuyên tenant | 0; test cách ly chặn bản phát hành | BR-SAAS-021/023, NFR-SAAS-001 |
| O6 | | Độ khả dụng hằng tháng | ≥ 99,5% (Enterprise ≥ 99,9%) | NFR-SAAS-001 |
| O7 | Tăng trưởng thương mại | Số tenant trả phí đang hoạt động sau 12 tháng kể từ MVP GA | Pilot + TBD (nhà tài trợ đặt) | Số lượng tính phí cuối tháng (BR-SAAS-012); đo lường sử dụng tự động (BR-SAAS-013) thuộc P2 |

---

## 4. Các bên liên quan & persona

Mục §2 của các BRD module định nghĩa chi tiết các tác nhân. Bảng này hợp nhất chúng thành các persona ở cấp sản phẩm.

| Persona | Thuật ngữ tiếng Việt | Mục tiêu chính | Module chính |
|---|---|---|---|
| Quản trị viên HR của tenant | Quản trị nhân sự | Duy trì tổ chức, vị trí và hồ sơ nhân viên; xử lý hợp đồng và quyết định; lập báo cáo lao động theo luật định | Core, People |
| Chuyên viên C&B / tiền lương | Chuyên viên C&B / tiền lương | Cấu hình thành phần lương; tính lương chính xác, đúng hạn; kê khai BHXH/thuế TNCN; đối soát | Payroll, T&A |
| Trưởng bộ phận lương / C&B, kế toán trưởng | Kế toán trưởng | Phê duyệt kỳ lương và chi trả; nhận bút toán GL; đối soát các khoản phải nộp BHXH/thuế TNCN | Payroll |
| Quản lý trực tiếp | Quản lý trực tiếp | Phê duyệt nghỉ phép, làm thêm giờ và các thay đổi; rà soát bảng công của nhóm; đánh giá; tuyển dụng | Core (workflow), T&A, People, Recruitment |
| Nhân viên | Nhân viên (ESS) | Gửi yêu cầu nghỉ phép/làm thêm giờ và điều chỉnh công (check-in từ app mobile thuộc P2); xem phiếu lương và chứng từ khấu trừ thuế TNCN; cập nhật hồ sơ và người phụ thuộc; đưa ra sự đồng ý | T&A, Payroll, People, SaaS (ESS) |
| Chuyên viên tuyển dụng / quản lý tuyển dụng | Chuyên viên tuyển dụng | Đăng tin tuyển dụng; quản lý pipeline; đưa ra offer trong khung lương; bàn giao nhân sự mới mà không phải nhập lại | Recruitment |
| Ứng viên | Ứng viên | Ứng tuyển; đặt lịch phỏng vấn; chấp nhận offer; chuẩn bị trước khi nhận việc; thực hiện quyền đối với dữ liệu | Recruitment |
| Quản trị IT của tenant / chủ tenant | Quản trị hệ thống khách hàng / Chủ tài khoản | Cấu hình SSO/MFA, vai trò, tích hợp và thiết bị; quản lý thuê bao; phê duyệt truy cập hỗ trợ | Core, SaaS |
| Operator nền tảng (hỗ trợ, onboarding, tính phí, SRE) | Nhà cung cấp | Khởi tạo tenant; hướng dẫn import số dư đầu kỳ; tính phí; hỗ trợ khi được đồng ý; vận hành nền tảng | SaaS |
| Người sở hữu nội dung pháp lý | Chuyên gia pháp lý / lương | Công bố các bộ tham số pháp lý và mẫu biểu theo luật định đã được kiểm thử | SaaS, Payroll, T&A |
| Kế toán / tài chính | Kế toán | Chi tạm ứng và chi phí; import bút toán lương vào MISA/Fast/SAP; nhận hóa đơn điện tử thuê bao | Payroll, People, SaaS |
| Kiểm toán viên (nội bộ/bên ngoài), thanh tra lao động | Kiểm toán / thanh tra lao động | Bằng chứng chỉ đọc: audit trail, sổ lương, giới hạn làm thêm giờ, số ngày phép đã cấp | Core (audit), tất cả các module |
| DPO / cán bộ bảo vệ dữ liệu | Người phụ trách bảo vệ DLCN | Sổ đăng ký sự đồng ý, DSR, DPIA và xử lý vi phạm dữ liệu | SaaS, People, Recruitment |
| Nhà tài trợ điều hành | Ban điều hành | Business case, KPI, quyết định go/no-go | — |

---

## 5. Phạm vi

### 5.1 Bản đồ năng lực

**Quy tắc giai đoạn (thay đổi ở v0.2 và v0.3).** MVP = Phase 1 (P1). Giai đoạn của mỗi yêu cầu được thiết lập trong **[mvp-scope.md](mvp-scope.md)**. Việc đánh giá lại đó áp dụng các quyết định của nhà tài trợ X-04, OD-9, D-09, D-10 và vòng 2 (R2-01 – R2-19). Nó **được ưu tiên hơn** cả cách ánh xạ MoSCoW → giai đoạn cũ (M → MVP, S → P2, C → P3) lẫn nhãn giai đoạn trong các module cho đến khi các BRD module được đồng bộ (mvp-scope.md §12). Tóm lại, Phase 1 gồm:
- các tính năng lõi của mỗi module, triển khai dưới dạng **web app responsive** (quản trị + ESS)
- mọi nội dung theo luật định cho tính lương và tuân thủ lao động tại VN
- import số dư đầu kỳ
- **không có tích hợp ngoài trực tuyến**: dữ liệu ra vào bằng xuất file và import Excel, và thông báo chỉ gửi qua e-mail và trong ứng dụng (R2-01).

**Không có tính năng mobile nào ở Phase 1.** Nghĩa là không có app native, PWA, push, hay check-in bằng GPS/selfie/Wi-Fi/web. Tuyển dụng là một module P2 nói chung (điều lệ dự án §6), nhưng các hợp đồng dữ liệu của nó với Core, People và Payroll được chốt ở Phase 0. Cột Giai đoạn bên dưới tóm tắt kết quả.

**Năng lực baseline (R2-18).** Bất kể tenant mua module nào, các năng lực sau luôn được bật: Core, People cơ bản (cá nhân, quan hệ lao động, phân công, hồ sơ lương), tham số pháp lý, thang/bậc lương và danh mục thành phần lương, và import chấm công Excel. Danh sách ID nằm trong mvp-scope.md §3.4.

| # | Năng lực | Module | Giai đoạn | Chi tiết |
|---|---|---|---|---|
| 1 | Pháp nhân (MST, mã đơn vị BHXH, vùng), chi nhánh/địa điểm có geo-fence | Core | MVP (dữ liệu geo-fence chỉ dùng cho check-in ở P2) | [01 §4.1](modules/01-core.md#41-company-legal-entities-locations) |
| 2 | Cây tổ chức có hiệu lực theo thời gian, gói tái cơ cấu, vị trí, ngạch/bậc, trung tâm chi phí | Core | MVP (gói tái cơ cấu, vị trí như định biên có ngân sách, sơ đồ tổ chức tương tác: P2; quan hệ báo cáo ma trận: P3) | [01 §4.2–4.3](modules/01-core.md#42-organisation-structure) |
| 3 | Người dùng, RBAC, phạm vi dữ liệu, masking trường dữ liệu, đăng nhập nội bộ + MFA, OIDC SSO (Google/Entra) | Core / SaaS | MVP: đăng nhập nội bộ + MFA (OIDC và SAML SSO: P2; SCIM: P3) | [01 §4.4](modules/01-core.md#44-users-roles-permissions-data-scope-sso), [06 §4.D](modules/06-saas-platform.md#4d-identity--authentication) |
| 4 | Workflow & approval engine (điều kiện, ủy quyền, SLA, escalation) | Core | MVP: engine với các luồng phê duyệt tuần tự do nhà cung cấp tạo sẵn và HR phân công lại. Trình thiết kế luồng no-code, bước any-of/all-of và song song, xác định lại người phê duyệt khi tái cơ cấu, ủy quyền phê duyệt, escalation theo SLA, phê duyệt qua mobile/email/Zalo: P2 (R2-19) | [01 §4.5](modules/01-core.md#45-workflow--approval-engine) |
| 5 | Thông báo: trong ứng dụng và email | Core | MVP: trong ứng dụng + e-mail với mẫu VI/EN do nhà cung cấp duy trì (tenant override mẫu: P2, R2-19; push, Zalo ZNS, SMS: P2, R2-01) | [01 §4.6](modules/01-core.md#46-notifications) |
| 6 | Dữ liệu danh mục VN (34 tỉnh/phường xã với ánh xạ cũ→mới), danh mục mã, thiết lập, đánh số | Core | MVP (danh mục mã tùy chỉnh của tenant: P2, R2-19) | [01 §4.7](modules/01-core.md#47-settings--master-data) |
| 7 | Audit log chống giả mạo; dịch vụ tài liệu và mẫu → PDF | Core | MVP (adapter ký số P2) | [01 §4.8–4.9](modules/01-core.md#48-audit-log) |
| 8 | Framework import/export bao gồm chế độ và mẫu import số dư đầu kỳ (D-09; không chuyển đổi dữ liệu cũ) | Core / SaaS | MVP | [01 §4.10](modules/01-core.md#410-import--export), [06 §4.G](modules/06-saas-platform.md#4g-data-migration--onboarding) |
| 9 | Cá nhân và quan hệ lao động (tái tuyển dụng, đồng thời), dữ liệu cá nhân, người phụ thuộc, ngân hàng, số sổ BHXH | People | MVP, bao gồm quan hệ lao động đồng thời cơ bản ở nhiều pháp nhân: quan hệ lao động chính cho giảm trừ gia cảnh thuế TNCN, một đơn vị đóng BHXH (R2-16). Lựa chọn tính thâm niên khi tái tuyển dụng, liên hệ khẩn cấp, hồ sơ học vấn: P2 (R2-19) | [02 §4.1–4.2](modules/02-people.md#41-person--employment) |
| 10 | Phân công có hiệu lực theo thời gian, điều chuyển, thăng chức; lịch sử lương | People | MVP (biệt phái, thay đổi hàng loạt, nâng bậc: P2) | [02 §4.3–4.4](modules/02-people.md#43-assignments--organisation-placement) |
| 11 | Hợp đồng lao động, thử việc, phụ lục, tạm hoãn, quy tắc chuyển đổi | People | MVP (hợp đồng lao động điện tử P2, trở thành MVP nếu bắt buộc) | [02 §4.5](modules/02-people.md#45-labour-contracts) |
| 12 | Checklist tiếp nhận/nghỉ việc; chấm dứt, báo trước, điều kiện hưởng trợ cấp thôi việc | People | Chấm dứt, báo trước, trợ cấp thôi việc: MVP. Checklist và cổng thông tin trước khi nhận việc: P2 | [02 §4.6–4.7](modules/02-people.md#46-onboarding--offboarding) |
| 13 | Kỷ luật & khen thưởng (thủ tục theo luật định) | People | P2 (thủ tục ngoài hệ thống với tài liệu được tải lên ở MVP; nhà tài trợ xác nhận lại) | [02 §4.8](modules/02-people.md#48-discipline--rewards) |
| 14 | Đánh giá (KPI/OKR, hiệu chỉnh) | People | P2 (kết quả thử việc được ghi nhận cùng hợp đồng ở MVP) | [02 §4.9](modules/02-people.md#49-appraisal--performance) |
| 15 | Đào tạo, cam kết đào tạo | People | P2 | [02 §4.10](modules/02-people.md#410-training) |
| 16 | Đề nghị thanh toán chi phí & tạm ứng | People | P2 (chờ People Q5) | [02 §4.11](modules/02-people.md#411-expense-claims--advances) |
| 17 | ESS/MSS, quyết định & văn bản HR, sổ quản lý lao động, báo cáo lao động 6 tháng, kiểm soát bảo vệ dữ liệu cá nhân | People | MVP, web responsive (view nhóm trên MSS, văn bản HR, yêu cầu thay đổi dữ liệu cá nhân trên ESS, kho tài liệu: P2; R2-19) | [02 §4.12–4.15](modules/02-people.md#412-ess--mss) |
| 18 | Lịch ngày lễ (bộ dữ liệu VN gồm Tết âm lịch, ngày nghỉ bù) | T&A | MVP | [03 §4.1](modules/03-time-attendance.md#41-holiday-calendars) |
| 19 | Lịch làm việc và ca (cố định, 5/5,5/6 ngày, ca đêm) | T&A | MVP (ca xoay vòng, ca gãy: P2) | [03 §4.2](modules/03-time-attendance.md#42-work-schedules-and-rosters) |

| 20 | Lịch phân ca, đổi ca, kiểm tra hợp lệ lịch phân ca | T&A | P2 | [03 §4.2](modules/03-time-attendance.md#42-work-schedules-and-rosters) |
| 21 | Thu nhận dữ liệu chấm công: import Excel/file, nhập thủ công và điều chỉnh | T&A / SaaS | MVP: import Excel dữ liệu chấm công và bảng công, bao gồm **mẫu file xuất từ thiết bị (CSV/Excel)**, quản trị viên nhập thủ công, yêu cầu điều chỉnh công trên ESS. **Connector thiết bị trực tuyến, bộ đệm offline và mục tiêu tốc độ tiếp nhận dữ liệu chấm công: P2 (R2-01). Check-in qua web, PWA và mobile GPS/Wi-Fi/selfie: P2 (OD-9)** | [03 §4.3](modules/03-time-attendance.md#43-check-in-sources-and-raw-punches) |
| 22 | Xử lý bảng công hằng ngày, giải trình | T&A | MVP (chính sách dung sai đi muộn/về sớm, chính sách phát hiện làm thêm giờ, gán chính sách có hiệu lực theo thời gian cho từng nhóm: P2; một bộ chính sách cho mỗi pháp nhân, làm thêm giờ theo phê duyệt trước; R2-19) | [03 §4.4](modules/03-time-attendance.md#44-daily-timesheet-processing) |
| 23 | Làm thêm giờ: yêu cầu, phát hiện, các loại theo quy định VN, giới hạn 40/200/300 giờ | T&A | MVP | [03 §4.5](modules/03-time-attendance.md#45-overtime) |
| 24 | Loại nghỉ phép, số dư, tích lũy, thâm niên, chuyển phép, số ngày phép thanh toán bằng tiền, dữ liệu hồ sơ hưởng BHXH cho nghỉ ốm/thai sản (tối thiểu, R2-07) | T&A | MVP (hủy/rút ngắn nghỉ phép có tính lại hồi tố: P2, R2-19) | [03 §4.6](modules/03-time-attendance.md#46-leave-management) |
| 25 | Công tác / WFH (ảnh hưởng đến bảng công) | T&A | P2 dưới dạng luồng yêu cầu (R2-19); ở MVP ghi nhận bằng mã chấm công | [03 §4.7](modules/03-time-attendance.md#47-business-trips-and-remote-work) |
| 26 | Khóa kỳ, bàn giao Attendance Result, chênh lệch hồi tố | T&A | MVP (khóa sớm cho một nhân viên: P2, R2-19) | [03 §4.8, §7.3](modules/03-time-attendance.md#48-period-close-payroll-hand-off-and-retro) |
| 27 | Danh mục thành phần lương, formula engine, tham số pháp lý có hiệu lực theo thời gian | Payroll | MVP (mẫu khởi tạo VN, đa tiền tệ: P2) | [04 §4.1](modules/04-payroll.md#41-configuration-parameters--components) |
| 28 | Gross/Net, gross-up, tính theo tỷ lệ, tiền làm thêm giờ/làm đêm | Payroll | MVP | [04 §4.2](modules/04-payroll.md#42-gross--net-and-earnings-calculation) |
| 29 | BHXH/BHYT/BHTN/BHTNLĐ-BNN, kinh phí công đoàn, file D02-LT, đối chiếu C12-TS | Payroll | MVP, dưới dạng file để tải lên qua I-VAN của khách hàng (điều chỉnh BHXH hồi tố, nợ BHXH, xử lý hồ sơ hưởng chế độ BHXH, API I-VAN: P2) | [04 §4.3](modules/04-payroll.md#43-compulsory-insurance--trade-union) |
| 30 | Thuế TNCN: cư trú/không cư trú/vãng lai, 08/CK, 05/KK, 05/QTT, chứng từ điện tử | Payroll | MVP: XML thuế TNCN cho eTax/HTKK dưới dạng file xuất; chứng từ điện tử XML/PDF (phương thức ký còn mở, mvp-scope.md §11.4 R2-C3) | [04 §4.4](modules/04-payroll.md#44-personal-income-tax) |
| 31 | Kỳ tính lương tháng và ngoài kỳ (tháng 13, Tết), truy lĩnh/truy thu, quyết toán khi nghỉ việc, khóa/phê duyệt | Payroll | MVP — chỉ tần suất hằng tháng (A2). Kỳ tính thưởng/ngoài kỳ với thuế TNCN chính xác: MVP. Quy tắc điều kiện hưởng và tính theo tỷ lệ cho Tết/tháng 13, kỳ tính cho một nhóm/mô phỏng: P2 (R2-14, R2-19) | [04 §4.5](modules/04-payroll.md#45-payroll-runs-lifecycle--control) |
| 32 | Tạm ứng và khấu trừ | Payroll | MVP bao gồm khoản vay không lãi của nhân viên cho số dư đầu kỳ (lãi vay, luồng yêu cầu tạm ứng lương: P2; tạm ứng qua loại kỳ Advance) | [04 §4.6](modules/04-payroll.md#46-loans-advances--deductions) |
| 33 | Phiếu lương (ESS, PDF được bảo vệ) | Payroll | MVP: xem trên web ESS và PDF có mật khẩu qua e-mail (R2-17) | [04 §4.7](modules/04-payroll.md#47-payslips--ess) |
| 34 | File chi trả ngân hàng, xuất bút toán GL, phân bổ chi phí | Payroll / SaaS | MVP dưới dạng file xuất: file chi trả ngân hàng, bút toán GL dạng Excel, chi phí theo trung tâm chi phí mặc định (ngân hàng host-to-host, đẩy qua API kế toán, import trạng thái file chi trả, phân bổ chi phí tách nhiều trung tâm: P2) | [04 §4.8](modules/04-payroll.md#48-payment-accounting--cost-allocation) |
| 35 | Kế hoạch nhân sự, yêu cầu tuyển dụng, vị trí đang tuyển, đăng tin đa kênh | Recruitment | P2 | [05 §4.1–4.3](modules/05-recruitment.md#41-staffing--headcount-plan) |
| 36 | Ứng viên, phân tích CV, pipeline, phỏng vấn, đánh giá năng lực | Recruitment | P2 | [05 §4.4–4.6](modules/05-recruitment.md#44-candidates-applications-cv-parsing-talent-pool) |
| 37 | Offer (kiểm tra khung lương, offer NET qua gross-up), chuẩn bị trước khi nhận việc, bàn giao nhân sự mới | Recruitment | P2 (hợp đồng bàn giao được chốt ở Phase 0) | [05 §4.7–4.8](modules/05-recruitment.md#47-offers) |
| 38 | Giới thiệu ứng viên; phân tích tuyển dụng & quyền riêng tư của ứng viên | Recruitment | P2 / P3 | [05 §4.9–4.10](modules/05-recruitment.md#49-referrals) |
| 39 | Vòng đời tenant, gói dịch vụ, entitlement, feature flag, tính phí PEPM, hóa đơn điện tử VN, nhắc nợ, offboarding | SaaS | MVP: thuê bao module, giá theo hợp đồng, số lượng tính phí cuối tháng theo từng pháp nhân (D-10) từ báo cáo headcount thủ công, không xuất hóa đơn điện tử trong sản phẩm: bộ phận tài chính của nhà cung cấp phát hành hóa đơn điện tử VN từ công cụ hóa đơn điện tử riêng dựa trên báo cáo sử dụng (R3-01). Hóa đơn điện tử thuê bao (BR-SAAS-014/015), đo lường sử dụng, dịch vụ feature flag, gói dịch vụ/bảng giá, tự động nhắc nợ, xuất/xóa dữ liệu khi offboarding, tự đăng ký, cổng thanh toán: P2 | [06 §4.A](modules/06-saas-platform.md#4a-tenant-lifecycle--commercial) |
| 40 | Gói dùng chung + cách ly bằng RLS, lưu trữ tại VN, vi/en, custom field | SaaS | MVP (custom field, gói dedicated, sandbox, white-label, custom domain: P2) | [06 §4.B](modules/06-saas-platform.md#4b-tenancy-isolation-residency--customisation) |
| 41 | Back-office cho operator, truy cập hỗ trợ dựa trên sự đồng ý | SaaS | MVP (giả lập người dùng, break-glass hai người tự động, log truy cập của operator mà tenant xem được: P2; log được xuất khi có yêu cầu, R2-19) | [06 §4.C](modules/06-saas-platform.md#4c-operator-back-office--support-access) |
| 42 | Framework báo cáo, kho dữ liệu báo cáo tách riêng, framework báo cáo theo luật định | SaaS | MVP (kho dữ liệu báo cáo tách riêng, trình thiết kế báo cáo, dashboard, phân tích nhân sự: P2) | [06 §4.E](modules/06-saas-platform.md#4e-reporting--analytics) |
| 43 | Integration hub; tích hợp ngân hàng, eTax và máy chấm công | SaaS | MVP: **chỉ xuất file** (file chi trả ngân hàng, file kê khai BHXH, XML thuế TNCN cho eTax, bút toán GL dạng Excel) và import Excel. Toàn bộ tích hợp trực tuyến — connector máy chấm công, Zalo/SMS/push, ký số, VNeID, API kế toán và ngân hàng, integration hub, public API, webhook, API I-VAN của BHXH, trang tuyển dụng: P2 (R2-01) | [06 §4.F](modules/06-saas-platform.md#4f-integration) |
| 44 | ESS web responsive | SaaS | MVP (app mobile native và **toàn bộ** tính năng mobile: P2) | [06 §4.H](modules/06-saas-platform.md#4h-mobile--ess-channel) |
| 45 | Tuân thủ bảo vệ dữ liệu cá nhân: kiểm kê dữ liệu, sự đồng ý, DSR, lưu trữ, DPIA, vi phạm dữ liệu, phù hợp ISO 27001 | SaaS | MVP | [06 §4.I](modules/06-saas-platform.md#4i-compliance-privacy--security) |
| 46 | Tích hợp marketplace, trợ lý AI, mở rộng ra khu vực (ngoài VN) | — | P3 | Điều lệ dự án §6 |

### 5.2 Ngoài phạm vi (tất cả các giai đoạn trừ khi có ghi chú)

- **Chuyển đổi dữ liệu từ hệ thống cũ** (D-09, 2026-10-16). Không có chương trình chuyển đổi từ Oracle hay hệ thống cũ khác, không có vùng staging hay chạy song song. Khách hàng nạp số dư đầu kỳ qua các mẫu Excel chuẩn (năng lực 8, §6.5).
- **Kế toán sổ cái.** YourHRM xuất bút toán và không phải là hệ thống kế toán. Việc hạch toán GL cho chi phí được thực hiện bên ngoài.
- **Tích hợp ngoài trực tuyến ở MVP** (R2-01, 2026-10-16): connector máy chấm công (push/pull, bộ đệm offline), Zalo OA/ZNS, SMS, push, chữ ký điện tử/CA, VNeID, API hệ thống kế toán, API ngân hàng, trang tuyển dụng, public API và webhook, và runtime integration hub đều thuộc P2. MVP trao đổi dữ liệu bằng xuất file và import Excel, và thông báo qua e-mail và trong ứng dụng.
- **Nộp hồ sơ trực tiếp qua API tới BHXH/Tổng cục Thuế ở MVP.** MVP tạo file để tải lên qua I-VAN/VssID và eTax/HTKK. Nộp qua API T-VAN/I-VAN có ở P2.
- **Xử lý hồ sơ hưởng chế độ BHXH** (ốm đau và thai sản do BHXH chi trả, mẫu 01B-HSB): P2.
- **Chi trả ngân hàng host-to-host ở MVP.** MVP chỉ cung cấp file để tải lên.
- Tần suất trả lương nửa tháng hoặc hằng tuần ở MVP.
- Firmware máy chấm công, kiểm soát ra vào và hệ thống căng tin. Cung cấp phần cứng.
- Theo dõi thời gian theo dự án/công việc để tính phí, dự báo nhu cầu nhân lực và tối ưu phân ca tự động (ứng viên cho P3).
- Vận hành nhà cung cấp dịch vụ thẩm tra lý lịch và cổng thông tin cho agency/RPO (P3). Thanh toán phí agency và chi phí quảng cáo.
- Xếp hạng bằng AI và tự động loại ứng viên hoàn toàn (BR-REC-026).
- Quan hệ lao động đồng thời xuyên tenant. Hợp nhất định danh xuyên tenant.
- Triển khai on-premise hoặc private cloud ngoài mô hình SaaS (SaaS Q9).
- Lưu trữ hoặc xử lý dữ liệu bên ngoài Việt Nam.

---

## 6. Quy trình nghiệp vụ đầu-cuối

Mỗi bước nêu tên module sở hữu. Chi tiết quy trình ở cấp module nằm trong §3 của mỗi BRD, và các hợp đồng giao tiếp nằm trong §7 của mỗi BRD.

### 6.1 Từ tuyển dụng đến nghỉ hưu
1. **Core** — HR xây dựng pháp nhân, địa điểm, đơn vị tổ chức, vị trí và trung tâm chi phí. Vị trí mang ngân sách FTE.
2. **Recruitment** (P2) hoặc **People** (tuyển trực tiếp) — Việc tuyển mới được kích hoạt (`CandidateHired` hoặc tuyển thủ công).
3. **People** — Cá nhân được đối chiếu theo CCCD, hộ chiếu, MST hoặc số sổ BHXH. HR tạo quan hệ lao động, phân công, hợp đồng (thử việc hoặc lao động) và hồ sơ lương. Các event `EmployeeHired`, `AssignmentChanged`, `SalaryChanged` và `ContractSigned` được publish.
4. **Core** — Tài khoản người dùng được tạo tự động với role ESS, và phạm vi quản lý được suy ra.
5. **T&A** — Gán lịch làm việc, lịch ngày lễ và số ngày phép được hưởng, và ánh xạ đăng ký trên thiết bị.
6. **Payroll** — Nhân viên được đưa vào một nhóm lương. Chuẩn bị báo tăng BHXH (D02-LT) và người phụ thuộc cho thuế TNCN.
7. **People** — Các thay đổi trong quá trình làm việc (điều chuyển, thăng chức, thay đổi lương, gia hạn hoặc chuyển đổi hợp đồng, kỷ luật/khen thưởng, đánh giá) được xử lý dưới dạng bản ghi có hiệu lực theo thời gian, mỗi thay đổi tạo ra một quyết định và phụ lục. Chúng được phê duyệt qua workflow của **Core**, và event của chúng được chuyển tới T&A và Payroll.
8. **People** — Việc nghỉ hưu được đánh dấu dựa trên lộ trình tuổi nghỉ hưu theo luật định. Việc nghỉ việc theo §6.4.

### 6.2 Từ chấm công đến trả lương (chu kỳ hằng tháng; ví dụ chốt công ngày 25, ngày trả lương mùng 5)
1. **SaaS** — Bộ tham số pháp lý hiện hành (tỷ lệ, mức trần, bậc thuế, ngày lễ) đã được công bố và có hiệu lực.
2. **T&A** — Dữ liệu chấm công được import từ file xuất của phần mềm máy chấm công hoặc bảng công Excel (Phase 1; tiếp nhận trực tuyến từ thiết bị thuộc P2) và được xử lý thành bảng công hằng ngày. HR nhập thủ công những ngày còn thiếu. Nhân viên giải trình các ngoại lệ, và các yêu cầu nghỉ phép, làm thêm giờ, công tác và WFH được phê duyệt qua workflow của **Core**.
3. **People** — Các thay đổi trong kỳ (tuyển mới, chấm dứt, lương, người phụ thuộc) có hiệu lực trước ngày chốt.
4. **T&A** — Cuối kỳ, dashboard ngoại lệ được xử lý hết, quản lý xác nhận, và quản trị viên **khóa** kỳ. Attendance Result có phiên bản (kèm chênh lệch hồi tố) được publish cho Payroll (+2 ngày làm việc).
5. **Payroll** — Kỳ tính lương được mở và snapshot đầu vào được tạo. Dữ liệu đầu vào biến đổi được import. Hệ thống tính gross → tính theo tỷ lệ → làm thêm giờ → BHXH/BHYT/BHTN/KPCĐ → thuế TNCN → khấu trừ → net, với gross-up cho nhân viên hưởng lương NET (+3).
6. **Payroll** — Xử lý các ngoại lệ: dưới mức lương tối thiểu, thực lĩnh âm, thiếu tài khoản ngân hàng hoặc MST, chênh lệch vượt ngưỡng.
7. **Core** — Các bước phê duyệt chạy tuần tự: trưởng bộ phận lương → tài chính → giám đốc. Kỳ tính lương được **Phê duyệt/Khóa**, và Payroll báo cho **T&A** khóa cứng kỳ (+5).
8. **Payroll** — Tạo file ngân hàng, bút toán GL và các khoản phải nộp BHXH/KPCĐ/thuế TNCN. Vào ngày trả lương, phiếu lương được công bố lên ESS (kênh **SaaS**) cùng thông báo từ **Core**.
9. **Payroll** — Xác nhận từ ngân hàng đánh dấu kỳ tính lương là **Đã chi trả**. Tiền đóng BHXH được nộp trước cuối tháng, và tờ khai 05/KK-TNCN được nộp trước ngày 20 của tháng sau (đối với người nộp theo tháng) `[verify-legal]`.
10. **Payroll / T&A** — Các thay đổi muộn cho kỳ đã khóa được chuyển thành chênh lệch hồi tố trong kỳ tiếp theo.

### 6.3 Từ tuyển dụng đến tiếp nhận (Phase 2)
1. **Recruitment** — Kế hoạch nhân sự được điền sẵn headcount thực tế từ **People** và vị trí từ **Core**, sau đó được phê duyệt qua workflow của **Core**.
2. **Recruitment** — Yêu cầu tuyển dụng được kiểm tra với định biên còn trống theo kế hoạch và khung lương từ **Payroll**, sau đó được phê duyệt.
3. **Recruitment** — Vị trí tuyển dụng được công bố (trang tuyển dụng của công ty, bảng tin nội bộ, trang tuyển dụng bên ngoài). Hồ sơ ứng tuyển được thu nhận kèm sự đồng ý theo từng mục đích (dịch vụ quản lý sự đồng ý của **SaaS**).
4. **Recruitment** — Kiểm tra trùng lặp với ứng viên và nhân viên hiện tại/cựu nhân viên trong **People**. Ứng viên đi qua các bước sàng lọc, phỏng vấn (tích hợp lịch) và đánh giá năng lực.
5. **Recruitment** — Offer qua các bước kiểm tra pháp lý (thời gian và tiền lương thử việc, lương tối thiểu vùng từ **Core**/**Payroll**). Offer NET được mô phỏng bằng gross-up của **Payroll**. Offer được phê duyệt, sau đó ký qua ký số (**SaaS**) hoặc chấp nhận bằng một cú nhấp.
6. **Recruitment** — Cổng thông tin ứng viên xử lý việc chuẩn bị trước khi nhận việc (CCCD, MST, số sổ BHXH, ngân hàng, người phụ thuộc, tài liệu).
7. **Recruitment → People / Payroll** — Bàn giao `CandidateHired` tạo nhân viên trước khi nhận việc, hợp đồng nháp, checklist tiếp nhận và hồ sơ lương mà không cần nhập lại.
8. **People** — Checklist tiếp nhận được thực hiện. Vào ngày bắt đầu làm việc, nhân viên chuyển sang `Active` và tiếp tục theo §6.1.

### 6.4 Từ chấm dứt đến quyết toán
1. **People** — Đơn xin nghỉ việc được gửi qua ESS, hoặc HR/quản lý khởi tạo chấm dứt. Hệ thống tính thời hạn báo trước bắt buộc, kiểm tra tình trạng được bảo vệ và chuyển phê duyệt qua workflow của **Core**. Quyết định chấm dứt hợp đồng được tạo ra.
2. **People** — Checklist nghỉ việc được thực hiện (bàn giao công việc, tài sản, ngày thu hồi quyền truy cập). `TerminationScheduled` được publish, tiếp theo là `EmployeeTerminated` vào ngày làm việc cuối cùng.
3. **Core** — Người dùng bị vô hiệu hóa vào cuối ngày làm việc cuối cùng, session bị thu hồi và các phê duyệt đang chờ được phân công lại.
4. **T&A** — Xử lý bảng công cuối cùng ước tính số dư phép và cung cấp số ngày phép năm chưa nghỉ để thanh toán bằng tiền, cùng số dư âm cần thu hồi (nếu có).
5. **Payroll** — Tính quyết toán khi nghỉ việc: lương tính theo tỷ lệ, tiền thanh toán phép, trợ cấp thôi việc hoặc trợ cấp mất việc làm (điều kiện hưởng từ **People**), tiền bồi thường không báo trước, thu hồi khoản vay và tạm ứng, lương tháng 13 theo tỷ lệ, và thuế TNCN. Quyết toán được phê duyệt và chi trả trong vòng 14 ngày làm việc, có thể kéo dài đến 30 ngày `[verify-legal]`.
6. **Payroll** — Báo giảm BHXH (D02-LT) được nộp và xuất dữ liệu chốt sổ BHXH. Chứng từ khấu trừ thuế TNCN được cấp khi có yêu cầu.
7. **People** — Trả lại giấy tờ và cấp xác nhận quá trình làm việc. Cựu nhân viên giữ quyền truy cập ESS giới hạn theo chính sách, và dữ liệu được lưu giữ theo lịch lưu trữ (chính sách lưu trữ của **SaaS**).

### 6.5 Từ đăng ký tenant đến go-live (luồng pilot / có hỗ trợ, MVP)
1. **SaaS** — Hợp đồng được ký. Operator tạo tenant (pháp nhân, phiên bản sản phẩm, module, gói, vùng VN) và khởi tạo trong ≤ 10 phút với dữ liệu tham chiếu và bộ tham số pháp lý hiện hành. DPA được ký.
2. **Core** — `TenantProvisioned` tạo nhóm công ty, tài khoản chủ sở hữu, các role hệ thống, mẫu và thiết lập. Chủ sở hữu kích hoạt MFA (SSO thuộc P2).
3. **Core / T&A / Payroll** — Cấu hình: cơ cấu tổ chức, workflow, lịch ngày lễ, lịch làm việc, chính sách nghỉ phép, nhóm lương, thành phần lương và công thức, ánh xạ ngân hàng và GL, và import dữ liệu chấm công Excel theo bố cục cột của file xuất từ máy chấm công của pilot (không có connector trực tuyến ở Phase 1, R2-01).
4. **SaaS / Core** — Import số dư đầu kỳ (thay đổi ở v0.2, D-09; không chuyển đổi từ Oracle). Operator và khách hàng thống nhất tháng go-live cho từng pháp nhân (BR-SAAS-088). Khách hàng điền các mẫu Excel chuẩn (BR-SAAS-069): hồ sơ gốc nhân viên, lịch sử lương cho cơ sở tính trợ cấp thôi việc và lương bình quân, lịch sử đóng BHXH, thu nhập/bảo hiểm/thuế TNCN lũy kế từ đầu năm theo từng tháng của năm tính thuế (BR-SAAS-089), số dư phép và số giờ làm thêm lũy kế từ đầu năm, và các khoản vay, tạm ứng còn dư. Mỗi file được kiểm tra ở chế độ chỉ kiểm tra hợp lệ cho đến khi sạch lỗi, sau đó được commit ở chế độ import số dư đầu kỳ của **Core** (BR-CORE-114).
5. **SaaS / Payroll / T&A** — Số dư đầu kỳ được đối soát với số tổng kiểm soát của khách hàng: sổ lương cuối cùng của hệ thống cũ, C12-TS của BHXH, tờ khai 05/KK đã nộp, và báo cáo phép (BR-SAAS-072). Bao gồm lương lũy kế từ đầu năm, thuế TNCN lũy kế từ đầu năm, người phụ thuộc, lịch sử BHXH, số dư phép và khoản vay. Người phụ trách HR/C&B và tài chính của khách hàng ký xác nhận.
6. **Payroll** — Tháng cuối cùng trước go-live được tính lại trong YourHRM từ dữ liệu đầu vào đã import và so sánh với sổ lương của khách hàng, theo từng nhân viên (BR-SAAS-073, P1 kể từ R2-03). Sau đó kỳ tính lương thực tế đầu tiên được tính, và các báo cáo ngoại lệ và chênh lệch được rà soát cùng khách hàng trước khi kỳ được gửi phê duyệt.
7. **SaaS** — Go-live: checklist go-live được ký (xem §10). Việc phê duyệt kỳ tính lương thường kỳ đầu tiên sẽ khóa số dư đầu kỳ của pháp nhân đó (BR-SAAS-088). Bắt đầu tính phí với hóa đơn điện tử VN, đếm số người đang làm việc tại cuối tháng theo từng pháp nhân (BR-SAAS-012, D-10).
8. *(P2)* Luồng tự phục vụ: đăng ký với OTP và kiểm tra MST → dùng thử trong ≤ 2 phút → wizard onboarding → chuyển đổi sang trả phí.

### 6.6 Quyết toán thuế TNCN cuối năm
1. **SaaS** — Bộ tham số thuế TNCN cho năm tính thuế (bậc thuế, giảm trừ gia cảnh) được xác nhận. Năng lực cao điểm được lên kế hoạch cho tháng 1–3 (NFR-SAAS-021).
2. **People** — Hoàn tất đăng ký và thay đổi người phụ thuộc trong năm (ESS cộng với xác minh của HR).
3. **Payroll** — Hệ thống xác định điều kiện của từng nhân viên để ủy quyền cho người sử dụng lao động quyết toán thay, và hiển thị lý do đối với nhân viên không đủ điều kiện.
4. **Payroll / SaaS ESS** — Nhân viên đủ điều kiện gửi giấy ủy quyền (08/UQ-QTT-TNCN hoặc mẫu hiện hành).
5. **Payroll** — Thuế năm được tính lại cho các nhân viên đã ủy quyền và xác định số khấu trừ thừa hoặc thiếu. Đối với tenant go-live giữa năm, các tháng trước go-live lấy từ số dư đầu kỳ đã import (BR-SAAS-089). Quản trị viên rà soát.
6. **Payroll** — Hoàn thuế hoặc khấu trừ bổ sung được xử lý trong một kỳ tính theo chính sách của tenant.
7. **Payroll / SaaS** — Tờ khai 05/QTT-TNCN và các phụ lục 05-1/05-2/05-3 BK-QTT-TNCN được tạo dưới dạng XML eTax và nộp trước ngày cuối cùng của tháng thứ ba sau khi kết thúc năm `[verify-legal]`. Chứng từ khấu trừ điện tử được cấp cho nhân viên qua ESS hoặc email.

---

## 7. Khung pháp lý Việt Nam (tóm tắt)

Bảng này hợp nhất §5 của các BRD module. **Mọi mục đều là `[verify-legal]`** và phải được chuyên gia tư vấn lao động/thuế/bảo vệ dữ liệu Việt Nam xác nhận trước khi phê duyệt xây dựng MVP. Bản thân các giá trị chỉ nằm trong các bộ tham số có hiệu lực theo thời gian.

| # | Luật / văn bản | Chủ đề | Module bị ảnh hưởng |
|---|---|---|---|
| L1 | Bộ luật Lao động 2019 (45/2019/QH14) | Hợp đồng, thử việc, tạm thời chuyển người lao động làm công việc khác, báo trước, người lao động được bảo vệ, kỷ luật, thời giờ làm việc/nghỉ ngơi, làm thêm giờ, ngày lễ, nghỉ phép, trả lương, khấu trừ, trợ cấp thôi việc, quyết toán | People, T&A, Payroll, Recruitment `[verify-legal]` |
| L2 | Nghị định 145/2020/NĐ-CP | Hướng dẫn Bộ luật Lao động: giới hạn làm thêm giờ và chế độ 300 giờ, tiền lương làm thêm giờ/làm đêm, nghỉ phép năm, kỷ luật, sổ quản lý lao động, báo cáo tình hình sử dụng lao động | People, T&A, Payroll `[verify-legal]` |
| L3 | Nghị định 74/2024/NĐ-CP; Nghị định 293/2025/NĐ-CP (từ 2026-01-01) | Lương tối thiểu vùng (vùng I–IV), được ánh xạ lại sau khi sáp nhập tỉnh | Core, Payroll, People, Recruitment `[verify-legal]` |
| L4 | Nghị quyết 202/2025/QH15; Luật 72/2025/QH15 | 34 tỉnh, chính quyền địa phương hai cấp; dữ liệu danh mục đơn vị hành chính | Core, People `[verify-legal]` |
| L5 | Luật Bảo hiểm xã hội 2024 (41/2024/QH15, hiệu lực 2025-07-01) | Đóng BHXH, mức tham chiếu, miễn đóng 14 ngày, chế độ ốm đau/thai sản, thời hạn đăng ký và nộp tiền | Payroll, T&A, Core `[verify-legal]` |
| L6 | Luật Bảo hiểm y tế (sửa đổi 2024) | Mức đóng và mức trần BHYT | Payroll `[verify-legal]` |
| L7 | Luật Việc làm 2025 (74/2025/QH15, hiệu lực 2026-01-01) | Bảo hiểm thất nghiệp; thông báo biến động lao động; chống phân biệt đối xử | Payroll, People, Recruitment `[verify-legal]` |
| L8 | Luật An toàn, vệ sinh lao động 2015; Nghị định 58/2020/NĐ-CP | Tỷ lệ đóng BHTNLĐ-BNN (0,5%, hoặc 0,3% nếu được chấp thuận) | Payroll `[verify-legal]` |
| L9 | Luật Công đoàn 2024 | Kinh phí công đoàn 2%; đoàn phí công đoàn | Payroll `[verify-legal]` |
| L10 | Nghị định 73/2024/NĐ-CP | Lương cơ sở / mức tham chiếu cho mức trần | Payroll `[verify-legal]` |
| L11 | Luật Thuế TNCN (2007, sửa đổi) và Luật Thuế TNCN 2025; Nghị quyết 110/2025/UBTVQH15; Thông tư 111/2013/TT-BTC; Thông tư 92/2015/TT-BTC | Cư trú, biểu thuế (7 → 5 bậc), giảm trừ gia cảnh, miễn thuế, khấu trừ 10% cho lao động vãng lai, người phụ thuộc | Payroll, People `[verify-legal]` |
| L12 | Luật Quản lý thuế 38/2019/QH14; Nghị định 126/2020/NĐ-CP; Thông tư 80/2021/TT-BTC; Thông tư 86/2024/TT-BTC | Tờ khai (05/KK, 05/QTT), thời hạn, định dạng MST, CCCD làm mã số thuế cá nhân | Payroll, Core, People `[verify-legal]` |
| L13 | Nghị định 123/2020/NĐ-CP được sửa đổi bởi Nghị định 70/2025/NĐ-CP; Thông tư 78/2021, Thông tư 32/2025 | Hóa đơn điện tử (tính phí thuê bao), chứng từ khấu trừ thuế TNCN điện tử | SaaS, Payroll `[verify-legal]` |
| L14 | Quyết định 595/QĐ-BHXH và 490/QĐ-BHXH của BHXH Việt Nam (và các văn bản thay thế) | Mẫu BHXH D02-LT, TK1-TS, C12-TS; kê khai điện tử qua I-VAN | Payroll, SaaS `[verify-legal]` |
| L15 | Luật Bảo vệ dữ liệu cá nhân 2025 (91/2025/QH15, hiệu lực 2026-01-01); Nghị định 13/2023/NĐ-CP | Sự đồng ý, dữ liệu nhạy cảm, DSR, DPIA, chuyển dữ liệu xuyên biên giới, thông báo vi phạm trong 72 giờ | Tất cả các module, SaaS `[verify-legal]` |
| L16 | Luật An ninh mạng 2018 (và bản cập nhật 2025); Nghị định 53/2022/NĐ-CP | Lưu trữ dữ liệu tại VN; thời hạn lưu log | SaaS, Core `[verify-legal]` |
| L17 | Luật An toàn thông tin mạng 2015; Nghị định 85/2016/NĐ-CP | Phân loại cấp độ an toàn hệ thống thông tin | SaaS `[verify-legal]` |
| L18 | Luật Giao dịch điện tử 2023 (20/2023/QH15); Nghị định 23/2025/NĐ-CP; nghị định về hợp đồng lao động điện tử (ví dụ 337/2025/NĐ-CP) | Chữ ký điện tử, văn bản điện tử, hợp đồng lao động điện tử | Core, People, SaaS `[verify-legal]` |
| L19 | Luật Căn cước 2023; Nghị định 69/2024/NĐ-CP | Định danh điện tử VNeID | People, SaaS `[verify-legal]` |
| L20 | Luật Kế toán 2015 (88/2015/QH13); Nghị định 174/2016/NĐ-CP | Lưu trữ chứng từ kế toán ≥ 10 năm, bao gồm bảng lương | Core, Payroll, SaaS `[verify-legal]` |
| L21 | Thông tư 11/2020/TT-BLĐTBXH | Danh mục nghề, công việc nặng nhọc, độc hại (nghỉ hưu, nghỉ phép, phụ cấp) | Core, T&A, People `[verify-legal]` |
| L22 | Nghị định 135/2020/NĐ-CP | Lộ trình tuổi nghỉ hưu | People `[verify-legal]` |
| L23 | Nghị định 152/2020/NĐ-CP (đã sửa đổi); Nghị định 219/2025/NĐ-CP | Lao động nước ngoài: giấy phép lao động và chấp thuận nhu cầu sử dụng | People, Recruitment, Payroll `[verify-legal]` |
| L24 | Luật Bình đẳng giới 2006; Luật Lý lịch tư pháp 2009 | Chống phân biệt đối xử; giới hạn yêu cầu cung cấp lý lịch tư pháp | Recruitment `[verify-legal]` |
| L25 | Nghị định 30/2020/NĐ-CP; Quyết định 121-TCTK/PPCĐ của Tổng cục Thống kê | Quy chuẩn văn bản hành chính; 54 dân tộc | Core `[verify-legal]` |
| L26 | Nghị định 91/2020/NĐ-CP | Quy định về tin nhắn thương hiệu (SMS brandname) và chống tin nhắn rác | SaaS, Core `[verify-legal]` |
| L27 | Nghị định 12/2022/NĐ-CP | Xử phạt vi phạm hành chính (lao động, BHXH) — bằng chứng và lưu giữ hồ sơ | T&A, People, Payroll `[verify-legal]` |

---

## 8. Yêu cầu phi chức năng toàn sản phẩm

Các NFR toàn sản phẩm thuộc sở hữu của [06 SaaS Platform §9](modules/06-saas-platform.md#9-non-functional-requirements-product-wide). Các NFR của module (`NFR-CORE/PPL/ATT/PAY/REC`) chi tiết hóa chúng và không được mâu thuẫn với chúng.

| Lĩnh vực | Chỉ tiêu ở cấp sản phẩm (tóm tắt) | Tham chiếu |
|---|---|---|
| Độ khả dụng & bảo trì | ≥ 99,5% (≥ 99,9% với Enterprise); không bảo trì trong khoảng từ ngày 25 đến ngày 5 | NFR-SAAS-001 |
| Hiệu năng tính lương | 10.000 nhân viên ≤ 15 phút; 1 nhân viên ≤ 5 s | NFR-SAAS-002, NFR-PAY-001 |
| Hiệu năng tương tác | p95 ≤ 500 ms cho giao dịch; ≤ 3 s cho báo cáo chuẩn | NFR-SAAS-003, NFR-CORE-002/003, NFR-PPL-003 |
| Đột biến check-in (P2, cùng connector thiết bị trực tuyến, R2-01) | 2.000 event/phút mỗi tenant; 20.000/phút toàn nền tảng; bộ đệm offline ≥ 7 ngày | NFR-SAAS-004, NFR-ATT-001/004 |
| Khả năng mở rộng & tính công bằng | ≥ 500 tenant và 200.000 nhân viên khi ra mắt, dư địa 5 lần; tenant gây nhiễu bị giới hạn ở 20% pool | NFR-SAAS-005, NFR-PAY-013 |
| Backup & DR | RPO ≤ 15 phút; RTO ≤ 4 giờ (≤ 2 giờ với Enterprise); trung tâm dữ liệu thứ hai tại VN | NFR-SAAS-006 |
| Bảo mật | TLS 1.2+, AES-256, mã hóa dữ liệu nhạy cảm ở mức trường, secret trong vault, OWASP ASVS L2, kiểm thử xâm nhập | NFR-SAAS-007/008/009, NFR-CORE-007/014 |
| Cách ly tenant | `tenant_id` + PostgreSQL RLS + bộ lọc ứng dụng; test cách ly chặn bản phát hành | BR-SAAS-021/023, NFR-CORE-001, NFR-PAY-005, NFR-ATT-010 |
| Observability | Log, metric và trace gắn tag tenant; log lưu ≥ 12 tháng (bảo mật ≥ 24) tại VN | NFR-SAAS-010 |
| Bản địa hóa & UX | Đầy đủ vi/en; dấu tiếng Việt trong PDF/Excel; tìm kiếm không phân biệt dấu; responsive từ 360 px; WCAG 2.1 AA (S) | NFR-SAAS-011/012/013, NFR-CORE-011 |
| Quản lý phát hành | Phát hành không downtime; rollback ≤ 15 phút; thay đổi tính toán nằm sau toggle theo tenant | NFR-SAAS-014/015 |
| Linh hoạt pháp lý & truy vết | Bộ tham số có trước ngày hiệu lực ≥ 7 ngày; mọi kết quả lưu phiên bản bộ tham số, cấu hình và bản phát hành; tất định, tái lập được trong ≥ 10 năm | NFR-SAAS-016/017, NFR-PAY-002/003/007, NFR-ATT-006 |
| Khả năng di chuyển dữ liệu | Xuất toàn bộ dữ liệu tenant ≤ 6 giờ cho 10.000 nhân viên | NFR-SAAS-019 |
| Hỗ trợ & năng lực cao điểm | Phản hồi Sev-1 ≤ 1 giờ (Enterprise 24×7); năng lực gấp 3 lần cho khóa sổ lương và quyết toán thuế TNCN tháng 1–3 | NFR-SAAS-020/021 |
| API & mobile (P2) | API tương thích ngược, thời gian deprecation ≥ 6 tháng; app khởi động nguội ≤ 3 s | NFR-SAAS-018/022 |

---

## 9. Danh mục yêu cầu

**Quy tắc đếm.** Số liệu bao gồm các dòng được định nghĩa trong §4 (BR) và §9 (NFR) của mỗi BRD module, được đếm bằng `awk` ngày 2026-10-16 sau các chỉnh sửa v0.2 (script trong [mvp-scope.md §10](mvp-scope.md#10-reproducing-the-counts)). Năm dòng có độ ưu tiên tách đôi và được đếm theo độ ưu tiên đứng đầu: BR-CORE-039, BR-CORE-070, BR-REC-033 và BR-REC-045 được tính là M, và BR-PPL-058 ("S→M nếu bắt buộc") được tính là S. Không có yêu cầu nào dùng `W`. Các thay đổi kể từ v0.1:
- T&A và Payroll bổ sung sáu BR về close-hold theo X-03: BR-ATT-079, 087, 088 và BR-PAY-082, 083, 084.
- SaaS bổ sung BR-SAAS-088 và BR-SAAS-089 (D-09).
- BR-SAAS-073 đổi từ M sang S.
- Đồng bộ chủ sở hữu duy nhất theo R2-02 (v0.3, 2026-10-16): bổ sung 9 BR (BR-CORE-088, BR-PPL-023, 039, BR-PAY-065, 085–087, BR-SAAS-090, 091). 5 dòng giờ là "Đã loại bỏ — thuộc sở hữu của …" (BR-CORE-038, 039, 042, 113, BR-SAAS-027). ID của chúng vẫn nằm trong các BRD module, nhưng bị loại khỏi mọi số đếm bên dưới. BR-CORE-086 đổi từ S sang M.

**MoSCoW** xếp hạng yêu cầu trong một giai đoạn. **Giai đoạn** (P1/P2/P3) là quyết định về phạm vi, chỉ được thiết lập trong **[mvp-scope.md](mvp-scope.md) §9** (banner giai đoạn trong mọi BRD module). Nó được đánh giá lại theo X-04/OD-9/D-09 (v0.1) và vòng 2 bao gồm đồng bộ R2-02 (v0.2). Tài liệu đó có các quy tắc, lý do cho từng ID, các thay đổi theo D-09 và các thay đổi của vòng 2 (§11).

Trong bảng, BR M/S/C là MoSCoW hiện tại trong các BRD module, chỉ cho các dòng đang hoạt động. "Tổng" đếm các dòng BR + NFR đang hoạt động; 5 dòng đã loại bỏ đưa tổng số dòng của module lên 583.

| Module | BRD | Tổng BR | BR M | BR S | BR C | BR P1 (v0.2) | **BR P1** | BR P2 | BR P3 | Tổng NFR | NFR P1 (v0.2) | **NFR P1** | NFR P2 | Tổng |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Core | [01-core.md](modules/01-core.md) | 75 | 60 | 10 | 5 | 52 | **45** | 25 | 5 | 15 | 13 | **13** | 2 | 90 |
| People | [02-people.md](modules/02-people.md) | 95 | 67 | 23 | 5 | 50 | **47** | 43 | 5 | 12 | 10 | **10** | 2 | 107 |
| Time & Attendance | [03-time-attendance.md](modules/03-time-attendance.md) | 80 | 61 | 16 | 3 | 61 | **55** | 25 | 0 | 11 | 9 | **7** | 4 | 91 |
| Payroll | [04-payroll.md](modules/04-payroll.md) | 91 | 76 | 12 | 3 | 72 | **71** | 17 | 3 | 13 | 11 | **11** | 2 | 104 |
| Recruitment (module P2) | [05-recruitment.md](modules/05-recruitment.md) | 63 | 42 | 20 | 1 | 0 | **0** | 62 | 1 | 11 | 0 | **0** | 11 | 74 |
| SaaS Platform | [06-saas-platform.md](modules/06-saas-platform.md) | 90 | 58 | 26 | 6 | 46 | **42** | 44 | 4 | 22 | 18 | **17** | 5 | 112 |
| **Tổng** | | **494** | **364** | **107** | **23** | **281** | **260** | **216** | **18** | **84** | **61** | **58** | **26** | **578** |

**Phạm vi Phase 1: 260 BR và 58 NFR** (v0.3 (R3), sau vòng 2, vòng 3 và đồng bộ R2-02). Ở v0.1, MVP gồm 310 BR mức Must (không tính Tuyển dụng). Tính cả sáu bổ sung theo X-03, baseline đó thành 316, và việc đánh giá lại ở vòng 1 đưa P1 về 281. Vòng 2 thay đổi P1 như sau:
- 27 BR của Phương án B chuyển sang P2 (R2-19). BR-PAY-102 và BR-ATT-037 được giữ lại theo các quyết định cụ thể hơn.
- 2 BR tích hợp trực tuyến chuyển sang P2: connector thiết bị BR-ATT-021 và BR-SAAS-065 (R2-01).
- 2 BR quay lại P1: BR-ATT-063 (R2-07) và BR-SAAS-073 (R2-03).
- 7 BR mới theo R2-02 được xếp P1: cư trú thuế, kiểm tra 183 ngày, gán nhóm lương, các event của People ảnh hưởng đến lương, trạng thái khóa của pháp nhân, job scheduler và quy tắc baseline. Hai BR khác (tỷ giá, ánh xạ tái cơ cấu) thuộc P2.
- 2 dòng P1 của Core bị loại bỏ vì SaaS giờ sở hữu chúng. Đăng nhập, mật khẩu, MFA và session nằm trong BR-SAAS-038/039/043, và xuất dữ liệu nằm trong BR-SAAS-046, tất cả đều P1 và thuộc baseline.
- 3 NFR về tốc độ tiếp nhận dữ liệu chấm công và bộ đệm chuyển sang P2 (NFR-ATT-001, NFR-ATT-004, NFR-SAAS-004).
- Vòng 3 (decision-log §8): BR-ATT-075 khóa sớm cho người nghỉ việc (R3-06), BR-PPL-019 trường trình độ chuyên môn tối thiểu (R3-07) và BR-SAAS-037 log truy cập tối thiểu của operator (R3-08) quay lại P1 (+3). Hóa đơn điện tử của chính nhà cung cấp, BR-SAAS-014/015, chuyển sang P2 (R3-01, −2). Kết quả ròng: 259 → 260.

Mọi hạng mục Tuyển dụng đều thuộc Phase 2.

Mục tiêu của nhà tài trợ là khoảng 180–200 BR. **Mục tiêu đó vẫn chưa đạt được**:
- 110 BR P1 là bắt buộc theo luật (BHXH, thuế TNCN bao gồm quyết toán và cư trú, hợp đồng, chấm dứt hợp đồng, quy định về làm thêm giờ và nghỉ phép, sổ quản lý lao động và báo cáo, thời hạn quyết toán khi nghỉ việc, PDPL). Hóa đơn điện tử của chính nhà cung cấp được xử lý bên ngoài sản phẩm (R3-01).
- Khoảng 101 BR khác là chuỗi xử lý mà các đầu ra theo luật định đó phụ thuộc vào.

Phương án B giờ đã dùng hết, nên không còn dự phòng cắt giảm nào ngoài phạm vi bắt buộc theo luật. Kế hoạch phải được ước lượng bằng story point (R2-01). Đòn bẩy phạm vi cuối cùng, hóa đơn điện tử của nhà cung cấp, đã được dùng ở vòng 3 (R3-01).

---

## 10. Định nghĩa MVP — go-live tenant pilot

**Hồ sơ pilot.** Pilot là một khách hàng hiện hữu chuyển sang YourHRM như một **hệ thống mới** (D-09). Dữ liệu Oracle HR cũ của họ không được chuyển đổi. Pilot nạp số dư đầu kỳ từ hồ sơ của chính mình qua các mẫu Excel chuẩn và có thể go-live giữa năm. Pilot có thể có một hoặc nhiều pháp nhân và trả lương hằng tháng bằng VND. Danh sách ngân hàng và số lượng pháp nhân cần được xác nhận (Payroll Q9, Core Q7).

**Kênh triển khai.** Phase 1 chỉ được triển khai dưới dạng web app: quản trị cộng với web ESS/MSS responsive. **Không có tính năng mobile**: không có app native, PWA, push notification, hay check-in GPS/Wi-Fi/selfie/web (X-04, OD-9). **Không có tích hợp ngoài trực tuyến** (R2-01):
- **Dữ liệu chấm công** đến từ import Excel, bao gồm import file xuất (CSV/Excel) của phần mềm máy chấm công qua mẫu theo cột của thiết bị. Được bổ sung bằng việc quản trị viên nhập thủ công và yêu cầu điều chỉnh công trên ESS.
- **Thông báo** chỉ gửi qua e-mail và trong ứng dụng. Bao gồm OTP đăng nhập, MFA và kích hoạt, phê duyệt và thông báo phiếu lương.
- **Đầu ra theo luật định và tài chính là các file xuất** mà khách hàng tự tải lên: file chi trả ngân hàng, file kê khai BHXH (qua I-VAN của họ), XML thuế TNCN cho eTax/HTKK và bút toán GL dạng Excel.

### 10.1 Các năng lực bắt buộc
Danh sách chính xác là các dòng P1 của [mvp-scope.md](mvp-scope.md) §9. Tóm tắt:
1. **Nền tảng.** Khởi tạo có hỗ trợ, gói dùng chung với RLS, lưu trữ tại VN, vi/en, đăng nhập nội bộ với MFA (SSO thuộc P2), back-office cho operator với support grant dựa trên sự đồng ý (break-glass hai người thủ công), thuê bao module với giá theo hợp đồng, số lượng tính phí cuối tháng theo từng pháp nhân từ báo cáo headcount thủ công (D-10), framework báo cáo chuẩn và theo luật định, thông báo e-mail, backup/DR.
2. **Core.** Pháp nhân và địa điểm có vùng, tổ chức có hiệu lực theo thời gian (gói tái cơ cấu và kiểm soát định biên vị trí thuộc P2), chức danh, ngạch/bậc và trung tâm chi phí, RBAC với phạm vi dữ liệu và masking, workflow (luồng tuần tự do nhà cung cấp tạo sẵn, HR phân công lại), thông báo trong ứng dụng và e-mail với mẫu do nhà cung cấp duy trì, audit, mẫu tài liệu, và framework import với **chế độ import số dư đầu kỳ**.
3. **People.** Hồ sơ cá nhân và quan hệ lao động (bao gồm tái tuyển dụng và quan hệ lao động đồng thời cơ bản ở nhiều pháp nhân, R2-16), dữ liệu cá nhân và người phụ thuộc, phân công, lịch sử lương, hợp đồng lao động, thử việc và tạm hoãn, chấm dứt với báo trước, bảo vệ và điều kiện hưởng trợ cấp thôi việc, web ESS (xem), quyết định, sổ quản lý lao động, báo cáo lao động 6 tháng và hằng tháng, kiểm soát bảo vệ dữ liệu cá nhân. Checklist tiếp nhận/nghỉ việc, thủ tục kỷ luật, khen thưởng, đánh giá thử việc, view nhóm trên MSS, yêu cầu thay đổi trên ESS và kho tài liệu thuộc P2.
4. **T&A.** Bộ ngày lễ VN (bao gồm ngày lễ của nhân viên nước ngoài), lịch làm việc cố định theo tuần và ca đêm, **import Excel dữ liệu chấm công và bảng công bao gồm mẫu file xuất từ thiết bị**, quản trị viên nhập thủ công, yêu cầu điều chỉnh công trên ESS, bảng công, làm thêm giờ theo phê duyệt trước với các loại và giới hạn theo luật (bao gồm thông báo chế độ 300 giờ), nghỉ phép với tích lũy, thâm niên và chuyển phép, dữ liệu thai sản và hồ sơ hưởng BHXH (tối thiểu, R2-07), khóa kỳ với close hold (X-03), Attendance Result và hồi tố, cùng số dư phép đầu kỳ và số giờ làm thêm lũy kế từ đầu năm. Một bộ chính sách cho mỗi pháp nhân.
5. **Payroll.** Thành phần lương và công thức, tham số pháp lý có hiệu lực theo thời gian, Gross/Net với gross-up (phạm vi net cố định), BHXH/BHYT/BHTN/BHTNLĐ-BNN/KPCĐ, thuế TNCN (mọi chế độ, lũy kế hằng tháng, quyết toán dùng các tháng trước go-live đã import), kỳ tính lương tháng và kỳ tính thưởng/ngoài kỳ với thuế TNCN chính xác (quy tắc chính sách Tết thuộc P2, R2-14), truy lĩnh/truy thu, quyết toán khi nghỉ việc và trợ cấp thôi việc, khoản vay không lãi, phiếu lương (web ESS và PDF có mật khẩu qua e-mail, R2-17), và các file xuất: file ngân hàng cho các ngân hàng của pilot, bút toán GL dạng Excel, D02-LT, 05/KK và 05/QTT, chứng từ khấu trừ điện tử.
6. **Import số dư đầu kỳ** (thay cho "Chuyển đổi dữ liệu", D-09). Các mẫu Excel chuẩn cho hồ sơ gốc nhân viên, lịch sử lương, lịch sử đóng BHXH, thu nhập/bảo hiểm/thuế TNCN lũy kế từ đầu năm theo từng tháng, số dư phép và số giờ làm thêm lũy kế từ đầu năm, và khoản vay. Các lần chạy chỉ kiểm tra hợp lệ, đối soát với số tổng kiểm soát của khách hàng có ký xác nhận, khóa khi kỳ đầu tiên được phê duyệt, và so sánh bảng lương một tháng với sổ lương của khách hàng (BR-SAAS-069–073, 088, 089).

### 10.2 Tiêu chí nghiệm thu go-live
| # | Tiêu chí |
|---|---|
| G1 | Báo cáo đối soát số dư đầu kỳ so với số tổng kiểm soát của khách hàng (sổ lương cuối cùng của hệ thống cũ, C12-TS của BHXH, tờ khai 05/KK đã nộp trong năm, báo cáo phép) không có chênh lệch nào không giải thích được và đã được người phụ trách HR/C&B và tài chính của pilot ký xác nhận (BR-SAAS-072). *(thay đổi ở v0.2, D-09)* |
| G2 | Mọi tháng của năm tính thuế hiện tại trước go-live đã được nạp cho mọi nhân viên (BR-SAAS-089). So sánh một tháng của tháng cuối cùng trước go-live với sổ lương của khách hàng không có chênh lệch nào không giải thích được (BR-SAAS-073, R2-03). Kỳ tính lương thực tế đầu tiên đã được tính, và các báo cáo ngoại lệ và chênh lệch đã được rà soát và giải thích cùng khách hàng trước khi kỳ được gửi đi. *(thay cho tiêu chí chạy song song, D-09; thay đổi ở v0.3)* |
| G3 | Bộ test lương golden (≥ 200 kịch bản) chạy đạt, và chuyên gia tư vấn VN đã ký xác nhận bộ tham số pháp lý đang có hiệu lực (NFR-PAY-008). |
| G4 | File D02-LT, XML thuế TNCN 05/KK và các file ngân hàng của pilot được xuất ra được công cụ I-VAN, eTax/HTKK và cổng ngân hàng của khách hàng chấp nhận. Bút toán GL dạng Excel được chức năng import của phần mềm kế toán của khách hàng chấp nhận (NFR-PAY-010). *(thay đổi ở v0.3: chỉ xuất file)* |
| G8 | Luồng chấm công của pilot hoạt động đầu-cuối: file xuất từ máy chấm công (hoặc bảng công Excel) của họ được import sạch lỗi với mẫu theo cột của thiết bị, và các bản nhập thủ công cùng yêu cầu điều chỉnh công khép kín được một kỳ đầy đủ. Việc gửi e-mail OTP, thông báo và phiếu lương được bảo vệ đã được kiểm thử trên tên miền e-mail của pilot. Nhân viên không có e-mail có phương án dự phòng đã thống nhất. *(mới ở v0.3, R2-01)* |
| G5 | Bộ test cách ly chạy đạt, các phát hiện kiểm thử xâm nhập mức critical/high đã được xử lý, và DPA cùng các đầu vào cho DPIA đã được bàn giao. |
| G6 | UAT cho các quy trình §6.1, §6.2, §6.4 và §6.5 đã được ký, chỉ trên web app. §6.6 được diễn tập trên dữ liệu lũy kế từ đầu năm **đã import** nếu go-live trước cuối năm. |
| G7 | Tháng cut-over được thống nhất cho từng pháp nhân, và số dư đầu kỳ bị khóa khi kỳ tính lương thường kỳ đầu tiên được phê duyệt (BR-SAAS-088). Quyết định go/no-go được nhà tài trợ và khách hàng pilot phê duyệt. *(thay đổi ở v0.2, D-09)* |

### 10.3 Những nội dung không bắt buộc cho go-live pilot
- **Mobile và các kênh.** Toàn bộ tính năng mobile (app native, PWA, push, check-in GPS/Wi-Fi/selfie và web, phê duyệt trên mobile), kênh Zalo/SMS.
- **Tích hợp trực tuyến (R2-01).** Connector máy chấm công (push/pull, bộ đệm offline, mục tiêu tốc độ tiếp nhận), chữ ký điện tử, VNeID, API hệ thống kế toán và ngân hàng, trang tuyển dụng, public API và webhook, runtime integration hub, nộp qua API cho BHXH/eTax, ngân hàng host-to-host.
- **Tính năng People và T&A.** Tuyển dụng, đánh giá (bao gồm biểu mẫu đánh giá thử việc), đào tạo, chi phí, lịch phân ca và đổi ca, checklist tiếp nhận/nghỉ việc và cổng thông tin trước khi nhận việc, thủ tục kỷ luật, dashboard nhóm trên MSS. Từ Phương án B (R2-19): yêu cầu thay đổi dữ liệu cá nhân trên ESS, kho tài liệu, lựa chọn tính thâm niên khi tái tuyển dụng, liên hệ khẩn cấp và hồ sơ học vấn, chính sách đi muộn/về sớm và phát hiện làm thêm giờ, gán chính sách theo nhóm, hủy nghỉ phép có hồi tố, yêu cầu công tác/WFH, khóa sớm cho một nhân viên, xử lý trùng lặp/lệch đồng hồ của thiết bị.
- **Tính năng Payroll (R2-14, R2-19).** Quy tắc điều kiện hưởng và tính theo tỷ lệ cho Tết/tháng 13, phạm vi net có thể cấu hình, kỳ tính cho một nhóm/mô phỏng, luồng yêu cầu tạm ứng lương, import trạng thái file chi trả, phân bổ chi phí tách nhiều trung tâm.
- **Nền tảng.** SSO, gói tái cơ cấu và kiểm soát định biên vị trí, ủy quyền phê duyệt và escalation theo SLA, trình thiết kế workflow no-code và bước song song, tenant override mẫu và danh mục mã, custom field, trình thiết kế báo cáo và dashboard, kho dữ liệu báo cáo tách riêng, dịch vụ feature flag, dịch vụ xuất dữ liệu bất đồng bộ.
- **Thương mại.** Hóa đơn điện tử thuê bao trong sản phẩm (bộ phận tài chính của nhà cung cấp xuất hóa đơn từ công cụ hóa đơn điện tử riêng, R3-01), gói dịch vụ/bảng giá, đo lường sử dụng, tự động nhắc nợ, xuất và xóa dữ liệu khi tenant offboarding, tự đăng ký, sandbox, gói dedicated, white-label.
- **Chuyển đổi dữ liệu.** Mọi hoạt động chuyển đổi dữ liệu cũ hoặc chạy song song.

---

## 11. Giả định, ràng buộc, phụ thuộc, rủi ro

### 11.1 Giả định
| # | Giả định | Nguồn |
|---|---|---|
| A1 | Quyền sở hữu dữ liệu được phân chia theo domain. Payroll sở hữu tiền. People sở hữu mức đãi ngộ đã thỏa thuận và dữ liệu gốc về cá nhân, thuế và BHXH. T&A sở hữu số lượng thời gian. Core sở hữu cơ cấu, quyền truy cập và các engine dùng chung. | Payroll A1; People A2; T&A A2 |
| A2 | Nhà cung cấp duy trì tập trung các bộ tham số pháp lý. Tenant chỉ override ở những nơi luật cho phép, ví dụ với các quyền lợi có lợi hơn. | SaaS A7; Payroll A4 |
| A3 | Tenant là bên kiểm soát dữ liệu cá nhân và nhà cung cấp là bên xử lý theo DPA đã ký. | SaaS A2; Recruitment A-05 |
| A4 | Lưu trữ tại một nhà cung cấp cloud VN có từ hai địa điểm trở lên. Không có dữ liệu cá nhân nào được lưu tại các region của hyperscaler bên ngoài VN. | SaaS A1 |
| A5 | Pilot điền các mẫu số dư đầu kỳ từ hồ sơ của chính mình: mọi tháng của năm tính thuế hiện tại trước go-live, ít nhất sáu tháng lịch sử lương, lịch sử đóng BHXH, số dư phép và khoản vay. Pilot cũng cung cấp số tổng kiểm soát và người phụ trách dữ liệu ký xác nhận đối soát. Không có quyền truy cập Oracle và không chạy song song (thay đổi ở v0.2, D-09). | SaaS A4; Payroll A5 |
| A6 | Hóa đơn điện tử, I-VAN, chữ ký điện tử và VNeID đến từ các nhà cung cấp bên thứ ba được cấp phép. Ở Phase 1, khách hàng dùng công cụ I-VAN, eTax và ngân hàng của riêng mình để tải lên các file xuất từ YourHRM; hóa đơn điện tử của chính nhà cung cấp được phát hành từ công cụ hóa đơn điện tử hiện có của bộ phận tài chính nhà cung cấp, bên ngoài sản phẩm (R3-01); chữ ký điện tử và VNeID thuộc P2 (R2-01), nên chứng từ khấu trừ thuế TNCN điện tử được tạo dưới dạng dữ liệu + PDF chưa ký và do khách hàng ký (R3-02). | SaaS A5/A6; People A6 |
| A7 | Việc so khớp sinh trắc học diễn ra trên thiết bị. YourHRM không lưu mẫu sinh trắc học. | T&A A5 |

### 11.2 Ràng buộc
- PostgreSQL với mỗi module một schema (`core`, `hr`, `att`, `pay`, `rec`) cộng thêm `saas` cho dữ liệu nền tảng (điều lệ dự án §2, X-07), Java Spring Boot, và multi-tenant ngay từ ngày đầu.
- Phase 1 chỉ có web (quản trị + ESS responsive). Tính năng mobile thuộc Phase 2 (X-04, OD-9).
- Giá trị pháp lý không bao giờ được hard-code.
- Dữ liệu cá nhân, file dữ liệu của khách hàng (ví dụ mẫu số dư đầu kỳ) và thông tin đăng nhập không bao giờ được commit vào repository.
- Toàn bộ dữ liệu cá nhân được lưu tại Việt Nam.
- Cửa sổ bảo trì liên quan đến tính lương phải tránh khoảng từ ngày 25 đến ngày 5 hằng tháng.

### 11.3 Phụ thuộc
| # | Phụ thuộc | Cần cho |
|---|---|---|
| DP1 | Chuyên gia tư vấn lao động, thuế và bảo vệ dữ liệu VN được thuê dài hạn | Ký xác nhận `[verify-legal]`; golden test; bộ tham số |
| DP2 | Khách hàng pilot: mẫu số dư đầu kỳ đã điền đầy đủ và số tổng kiểm soát, người phụ trách ký xác nhận đối soát, danh sách ngân hàng, model/firmware máy chấm công, nhân sự tham gia UAT | Go-live MVP |
| DP3 | Nhà cung cấp cloud/trung tâm dữ liệu VN (hai địa điểm) | Lưu trữ dữ liệu trong nước, DR |
| DP4 | Nhà cung cấp hóa đơn điện tử; I-VAN/T-VAN; định dạng file của ngân hàng; CA chữ ký điện tử | Tính phí, nộp hồ sơ theo luật định, chi trả, hợp đồng điện tử |
| DP5 | File xuất mẫu từ phần mềm máy chấm công của pilot (bố cục cột, encoding, ID người dùng trên thiết bị) cho mẫu file xuất từ thiết bị; chỉ một connector được chứng nhận từ P2 (R2-01) | T&A MVP |
| DP7 | Dịch vụ e-mail tin cậy cho người dùng của pilot (khả năng gửi tới tên miền của pilot, SPF/DKIM), và địa chỉ e-mail cá nhân cho nhân viên không có e-mail công việc | Đăng nhập/OTP, thông báo, PDF phiếu lương (R2-01, R2-17) |
| DP6 | Các quyết định kiến trúc: mô hình cách ly tenant, tự xây hay nhúng workflow engine, kho dữ liệu báo cáo | Tất cả các module (xem gap analysis) |

### 11.4 Các rủi ro hàng đầu (tổng hợp)
| # | Rủi ro | Tác động | Giảm thiểu | Tham chiếu module |
|---|---|---|---|---|
| RK1 | Khối lượng thay đổi pháp lý lớn trong 2025–2026: Luật BHXH 2024, Luật Thuế TNCN 2025, Luật Việc làm 2025, Luật Bảo vệ dữ liệu cá nhân 2025, lương tối thiểu, sáp nhập tỉnh, nghị định về hợp đồng điện tử | Trả lương sai; làm lại; bị phạt | Bộ tham số có hiệu lực theo thời gian; SLA về cập nhật pháp lý; golden test; chuyên gia tư vấn được thuê dài hạn | PAY R1, SAAS R2, PPL RK1, ATT R3 |
| RK2 | Rò rỉ dữ liệu xuyên tenant | Thiệt hại nghiêm trọng về pháp lý và uy tín | RLS + bộ lọc ứng dụng; test cách ly chặn bản phát hành | SAAS R1, CORE R-08 |
| RK3 | Số dư đầu kỳ không đầy đủ hoặc sai trong file Excel của khách hàng (không chuyển đổi dữ liệu, D-09): trùng lặp, thiếu ID, địa chỉ ba cấp, thiếu các tháng trước go-live, lịch sử lũy kế từ đầu năm/BHXH | Thuế TNCN hằng tháng và quyết toán, trợ cấp thôi việc và phép bị sai; chậm go-live | Mẫu chuẩn với các lần chạy chỉ kiểm tra hợp lệ; ánh xạ địa chỉ cũ→mới; bắt buộc đủ các tháng của năm tính thuế (BR-SAAS-089); cổng đối soát với số tổng kiểm soát (BR-SAAS-072); khóa và điều chỉnh có audit (BR-SAAS-088) | SAAS R3, PAY R7, PPL RK2, CORE R-01, ATT R7 |
| RK4 | Không nhất quán về hồi tố và effective dating giữa People, T&A và Payroll | Sai sót về lương và chi phí | Event có phiên bản; hợp đồng hồi tố của Attendance Result; khóa kỳ; contract test | PAY R4, ATT R2/R4, PPL RK3/RK4, CORE R-06 |
| RK5 | Phạm vi MVP không vừa 16 tuần: P1 vẫn là 260 BR sau vòng 2–3 và đồng bộ R2-02 so với mục tiêu khoảng 180–200, vì 110 BR là bắt buộc theo luật (X-04). Phương án B đã dùng hết, nên không còn dự phòng cắt giảm | Chậm go-live pilot và chậm có tenant trả phí đầu tiên | Xếp giai đoạn trong mvp-scope.md; ước lượng story point trước khi cam kết (R2-01); không thêm hạng mục P1 mới nếu không có hoán đổi tương ứng; các đòn bẩy tiếp theo được liệt kê trong mvp-scope.md §11.4 | CORE R-04, PPL RK6, SAAS R8 |
| RK6 | Lộ dữ liệu cá nhân nhạy cảm (lương, ngân hàng, CCCD, sức khỏe; GPS/selfie chỉ từ P2) | Bị phạt theo Luật Bảo vệ dữ liệu cá nhân | Mã hóa trường dữ liệu, masking, ghi log truy cập, sự đồng ý, DPIA | PPL RK5, PAY R6, ATT R5, SAAS R7 |
| RK7 | Thay đổi định dạng/API bên ngoài (BHXH, eTax, ngân hàng, thiết bị, trang tuyển dụng) | File bị từ chối; thiếu hụt tích hợp | Mẫu xuất và import có thể cấu hình, có phiên bản (P1: ngân hàng, BHXH, eTax, GL, file xuất từ thiết bị); integration hub và quản lý phiên bản connector từ P2 | PAY R5, SAAS R6, ATT R1, REC RK-02 |
| RK8 | Vi phạm yêu cầu lưu trữ dữ liệu trong nước qua các công cụ SaaS bên thứ ba (email, giám sát, phân tích CV) | Bị xử phạt theo quy định | Kiểm kê nhà cung cấp; phương án thay thế lưu trữ tại VN; loại bỏ PII; tenant tự chọn tham gia | SAAS R4, REC RK-05 |
| RK9 | Không chạy song song trước go-live (D-09): lỗi tính toán hoặc cấu hình xuất hiện lần đầu trên phiếu lương thực tế | Kỳ lương đầu tiên bị sai; mất niềm tin của pilot | Bộ golden test (G3); so sánh một tháng với sổ lương cuối cùng của khách hàng (BR-SAAS-073, P1 kể từ R2-03); kỳ đầu tiên được rà soát trước khi gửi đi (G2) | SAAS R3, PAY R7 |
| RK10 | Dữ liệu chấm công của pilot không có nguồn trực tuyến (OD-9, R2-01). Dữ liệu thiết bị chỉ đến dưới dạng file xuất, và nhân viên không có thiết bị, như nhân viên văn phòng, hiện trường và làm việc từ xa, cần dùng Excel hoặc yêu cầu điều chỉnh công | Thiếu dữ liệu chấm công; khối lượng công việc quản trị nặng hơn khi khóa kỳ | Import bảng công Excel và mẫu file xuất từ thiết bị (BR-ATT-025); yêu cầu điều chỉnh công trên ESS (BR-ATT-036); quản trị viên nhập thủ công (BR-ATT-037); mã chấm công cho công tác/WFH | ATT R1 |
| RK11 | **Công sức chấm công thủ công và lỗi nhập liệu tại pilot** (R2-01, R2-19). File từ thiết bị được xuất và import thủ công theo lịch, và các chính sách phát hiện đi muộn/về sớm và làm thêm giờ, xử lý dữ liệu chấm công trùng lặp và khóa sớm thuộc P2. File bị muộn hoặc bị chỉnh sửa, sai cột, dữ liệu chấm công trùng lặp hoặc bị thiếu và lỗi gõ phím khi nhập thủ công sẽ ảnh hưởng đến giới hạn làm thêm giờ, số dư phép và tiền lương | Số lượng làm thêm giờ/phép và tiền lương bị sai; vi phạm giới hạn làm thêm giờ không được phát hiện kịp thời; khóa kỳ kéo dài; tranh chấp với nhân viên | Lịch import cố định (ví dụ hằng ngày hoặc hằng tuần) được thống nhất với pilot; chạy thử chỉ kiểm tra hợp lệ kèm file lỗi (BR-CORE-111) trước mỗi lần commit; mẫu theo cột của thiết bị được kiểm thử trên file xuất thực tế (G8); bắt buộc nhập lý do và lịch sử trước/sau cho các chỉnh sửa thủ công (BR-ATT-037); checklist ngoại lệ trước khi khóa kỳ (BR-ATT-074); kiểm tra giới hạn làm thêm giờ khi import; view bảng công trên ESS cho nhân viên (BR-ATT-080); ước lượng công sức của HR cùng pilot trước go-live | ATT R1, PAY R4 |
| RK12 | **Phụ thuộc vào e-mail** (R2-01, R2-17, R3-04, R3-05). Do SMS, Zalo và push thuộc P2, e-mail là kênh gửi ra duy nhất cho kích hoạt ESS và OTP MFA, OTP cho cựu nhân viên (OD-8), thông báo phê duyệt và phiếu lương có mật khẩu. Nhiều công nhân nhà máy không có e-mail, và thư có thể bị chậm, bị lọc là spam hoặc gửi nhầm địa chỉ | Nhân viên không thể kích hoạt ESS hoặc nhận phiếu lương; người phê duyệt bị khóa tài khoản; phiếu lương gửi nhầm người (sự cố PDPL) | DP7: thiết lập khả năng gửi thư (SPF/DKIM) với tên miền của pilot; thu thập và xác minh e-mail cá nhân khi tuyển dụng; nhân viên không có e-mail nhận **phiếu lương PDF in sẵn từ HR** ở mỗi lần chi trả, không có mã kích hoạt và không có quyền truy cập ESS ở P1 (R3-05; người sử dụng lao động vẫn phải cung cấp chi tiết phiếu lương cho mọi nhân viên, Bộ luật Lao động Điều 95(3)); mật khẩu phiếu lương do nhân viên tự đặt trong ESS, không có mật khẩu mặc định hay suy ra, và trước khi đặt, e-mail chỉ là thông báo (R3-04); theo dõi trạng thái gửi và gửi lại (BR-PAY-102); đưa SMS trở lại như một hạng mục sớm của P2 | SAAS R4, PAY R6, CORE R-08 |

---

## 12. Bảng thuật ngữ

| # | Tiếng Anh | Tiếng Việt | Ý nghĩa |
|---|---|---|---|
| 1 | Social insurance (SI) | Bảo hiểm xã hội (BHXH) | Bảo hiểm bắt buộc chi trả cho hưu trí và tử tuất, ốm đau và thai sản, và BHTNLĐ-BNN. Được tài trợ bằng khoản đóng của người sử dụng lao động và người lao động trên tiền lương làm căn cứ đóng BHXH. |
| 2 | Health insurance (HI) | Bảo hiểm y tế (BHYT) | Bảo hiểm sức khỏe bắt buộc được tài trợ bằng khoản đóng của người sử dụng lao động và người lao động. |
| 3 | Unemployment insurance (UI) | Bảo hiểm thất nghiệp (BHTN) | Chế độ bắt buộc, mức trần 20 × lương tối thiểu vùng. Không áp dụng cho người nước ngoài. |
| 4 | Occupational accident & disease insurance (OA-OD) | Bảo hiểm tai nạn lao động, bệnh nghề nghiệp | Thành phần BHXH chỉ do người sử dụng lao động đóng. |
| 5 | Vietnam Social Security (VSS) | Bảo hiểm xã hội Việt Nam | Cơ quan thu BHXH/BHYT/BHTN. Tiếp nhận tờ khai D02-LT và phát hành thông báo C12-TS. |
| 6 | SI unit code / SI number | Mã đơn vị BHXH / Mã số BHXH (sổ BHXH) | Mã đăng ký của người sử dụng lao động tại cơ quan BHXH, và mã số hoặc sổ BHXH trọn đời của cá nhân. |
| 7 | Trade union fee | Kinh phí công đoàn (KPCĐ) | Khoản đóng 2% của người sử dụng lao động trên tiền lương làm căn cứ đóng BHXH. Khác với đoàn phí của đoàn viên. |
| 8 | Tax code | Mã số thuế (MST) | Mã số thuế doanh nghiệp: 10 chữ số, hoặc 13 chữ số với đơn vị phụ thuộc. Cá nhân dùng số định danh cá nhân 12 chữ số. |
| 9 | Citizen identity card | Căn cước công dân / Căn cước (CCCD) | Giấy tờ tùy thân quốc gia với số định danh cá nhân 12 chữ số. Đồng thời dùng làm mã số thuế cá nhân. |
| 10 | VNeID | VNeID (định danh điện tử) | Ứng dụng định danh điện tử quốc gia và định danh điện tử mức 2. |
| 11 | Personal income tax (PIT) | Thuế thu nhập cá nhân (TNCN) | Thuế lũy tiến cho người cư trú, thuế suất cố định 20% cho người không cư trú, khấu trừ 10% đối với thu nhập vãng lai. |
| 12 | PIT year-end finalisation | Quyết toán thuế TNCN | Tính lại thuế TNCN hằng năm. Người sử dụng lao động quyết toán thay cho nhân viên đã ủy quyền (05/QTT-TNCN). |
| 13 | Family deduction | Giảm trừ gia cảnh | Khoản giảm trừ hằng tháng khỏi thu nhập tính thuế: giảm trừ bản thân cộng với một khoản cho mỗi người phụ thuộc đã đăng ký. |
| 14 | Dependant | Người phụ thuộc | Người được đăng ký để giảm trừ gia cảnh. Mỗi người phụ thuộc chỉ được một người nộp thuế đăng ký giảm trừ. |
| 15 | PIT withholding certificate | Chứng từ khấu trừ thuế TNCN | Chứng từ xác nhận số thuế đã khấu trừ, được cấp dưới dạng điện tử cho người nhận thu nhập. |
| 16 | Commitment form 08/CK-TNCN | Bản cam kết 08/CK-TNCN | Bản cam kết thu nhập năm dưới ngưỡng chịu thuế, giúp không bị khấu trừ 10% đối với thu nhập vãng lai. |
| 17 | Base salary / reference level | Lương cơ sở / Mức tham chiếu | Mức tham chiếu của Nhà nước dùng cho mức trần BHXH/BHYT (20 ×) và các chế độ khác. Theo Luật BHXH 2024, mức tham chiếu thay thế lương cơ sở. |
| 18 | Regional minimum wage | Lương tối thiểu vùng | Mức sàn theo tháng và theo giờ theo luật định cho các vùng I–IV, được xác định theo nơi làm việc. |
| 19 | Pay scale | Thang bảng lương | Bảng lương theo ngạch và bậc của người sử dụng lao động. |
| 20 | Allowance | Phụ cấp | Thành phần lương bổ sung. Mỗi khoản phụ cấp có cờ xử lý thuế TNCN và BHXH riêng. |
| 21 | Standard working days | Ngày công chuẩn | Số chia để tính theo tỷ lệ (số ngày theo lịch làm việc thực tế, cố định 26, hoặc theo ngày dương lịch). |
| 22 | Actual worked days | Ngày công thực tế | Số ngày làm việc, bao gồm các ngày nghỉ có hưởng lương, do T&A cung cấp. |
| 23 | Overtime (OT) | Làm thêm giờ | Làm việc ngoài giờ bình thường, được trả ≥ 150/200/300% cộng thêm phụ cấp làm đêm. Giới hạn 40 giờ/tháng và 200 hoặc 300 giờ/năm. |
| 24 | Annual leave | Nghỉ phép năm | 12/14/16 ngày làm việc cộng 1 ngày cho mỗi 5 năm làm việc. Số ngày chưa nghỉ được thanh toán khi chấm dứt hợp đồng. |
| 25 | Compensatory leave / TOIL | Nghỉ bù | Thời gian nghỉ được cấp thay cho tiền làm thêm giờ, hoặc khi ngày lễ trùng ngày nghỉ hằng tuần. |
| 26 | 13th-month salary / Tết bonus | Lương tháng 13 / Thưởng Tết | Khoản chi trả cuối năm theo thông lệ, được xử lý trong kỳ tính ngoài kỳ. |
| 27 | Labour contract (definite / indefinite) | Hợp đồng lao động (xác định / không xác định thời hạn) | Hợp đồng lao động. Hợp đồng xác định thời hạn tối đa 36 tháng, và sau hai hợp đồng xác định thời hạn thì hợp đồng tiếp theo phải là không xác định thời hạn. |
| 28 | Probation | Thử việc | Thời gian làm thử với độ dài tối đa theo luật định. Tiền lương phải ≥ 85% mức lương của công việc. |
| 29 | Contract appendix | Phụ lục hợp đồng | Sửa đổi các điều khoản hợp đồng. Không được thay đổi thời hạn hợp đồng. |
| 30 | HR decision | Quyết định | Văn bản chính thức được ký cho tuyển dụng, điều chuyển, thay đổi lương, kỷ luật và chấm dứt hợp đồng. |
| 31 | Severance / job-loss allowance | Trợ cấp thôi việc / Trợ cấp mất việc làm | Trợ cấp khi chấm dứt hợp đồng, 0,5 hoặc 1 tháng lương cho mỗi năm làm việc, không tính thời gian đã đóng BHTN. |
| 32 | Legal entity | Pháp nhân | Người sử dụng lao động và người nộp thuế theo pháp luật. Một tenant có thể có nhiều pháp nhân. |
| 33 | Collaborator / service contract | Cộng tác viên (CTV) / Hợp đồng dịch vụ | Quan hệ không phải lao động. Khoản chi trả bị tính thuế như thu nhập vãng lai, không thuộc diện đóng BHXH. |
| 34 | Labour management book | Sổ quản lý lao động | Sổ đăng ký nhân viên theo luật định do người sử dụng lao động lưu giữ. |
| 35 | Attendance explanation | Giải trình chấm công | Yêu cầu điều chỉnh của nhân viên khi thiếu dữ liệu chấm công hoặc đi muộn. |
| 36 | Electronic invoice | Hóa đơn điện tử | Định dạng hóa đơn điện tử bắt buộc cho việc tính phí thuê bao của nhà cung cấp. |
| 37 | Effective-dated | Hiệu lực theo thời gian | Bản ghi có ngày bắt đầu và ngày kết thúc. Hệ thống trả lời "tại ngày D" và lưu giữ lịch sử. |
| 38 | ESS / MSS | Tự phục vụ nhân viên / quản lý | Cổng tự phục vụ cho nhân viên và quản lý. |

---

## 13. Các quyết định còn mở

Chuyên viên phân tích tích hợp hợp nhất các câu hỏi mở xuyên module, xung đột quyền sở hữu và các thiếu hụt về giao tiếp trong **[gap-analysis.md](gap-analysis.md)**. Tài liệu đó đang được chuẩn bị song song và là sổ đăng ký duy nhất cho các quyết định. Các quyết định chặn MVP phải được đóng trước khi phê duyệt SRS. Các chủ đề tiêu biểu được nêu trong các BRD module:
- chủ sở hữu kho tham số pháp lý và lịch ngày lễ
- nơi lưu trữ ESS
- schema của nền tảng và mô hình cách ly tenant
- mã đơn vị BHXH theo từng chi nhánh
- các phương pháp tính ngày công chuẩn và thời điểm chốt làm thêm giờ
- ngày chuyển tiếp của Luật Thuế TNCN 2025 và mức tham chiếu
- hợp đồng lao động điện tử có bắt buộc hay không
- danh sách ngân hàng của pilot và nhu cầu SCIM
- luồng hoàn ứng chi phí
