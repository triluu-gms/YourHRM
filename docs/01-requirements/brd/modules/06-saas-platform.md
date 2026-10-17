# BRD — Nền tảng SaaS & Năng lực xuyên suốt (`SAAS`)

| Mục | Giá trị |
|---|---|
| Tài liệu | `docs/01-requirements/brd/modules/06-saas-platform.md` |
| Phiên bản | Bản nháp v0.3 — 2026-10-16 |
| Ghi chú thay đổi | v0.2 (2026-10-16), theo các quyết định của sponsor D-09, D-10, OD-9 và X-04: (1) việc chuyển đổi dữ liệu từ Oracle cũ và chạy song song được thay bằng **import số dư đầu kỳ** dùng chung (P9, thay đổi BR-SAAS-069/071/072/073, thêm BR-SAAS-088/089); (2) nhân viên tính phí = người đang làm việc vào cuối tháng theo pháp nhân (BR-SAAS-012, P4); (3) không có ứng dụng mobile, push hay check-in ở Phase 1 (§1.4, BR-SAAS-066/075/076); (4) phase của từng yêu cầu nay được xác định trong [mvp-scope.md](../mvp-scope.md), ghi đè cách ánh xạ MoSCoW → phase. v0.3 (2026-10-16): áp dụng bảng chủ sở hữu duy nhất đã được phê duyệt ([gap-analysis](../gap-analysis.md) §5, quyết định **R2-02**): SaaS sở hữu Legal Parameter Service (PR-06; #1), xác thực/SSO/MFA/phiên làm việc/API client (#10), object storage (#15), job scheduler (mới BR-SAAS-090, PR-17; #16), ESS/MSS shell (#17), framework báo cáo/xuất dữ liệu (#18), sổ đăng ký sự đồng ý, danh mục dữ liệu cá nhân, điều phối DSR và bộ lập lịch lưu trữ (#27). Core sở hữu pháp nhân (BR-SAAS-002 → bên nhận hóa đơn; #23), trường tùy chỉnh (BR-SAAS-027, bỏ PR-05; #11), import engine (BR-SAAS-069/070, PR-12 trỏ lại; #12), audit store (BR-SAAS-085, PR-14; #13), trung tâm thông báo (#14) và dữ liệu danh mục toàn cục (BR-SAAS-032 quy trình phát hành back-office; #28). Việc cấp phát người dùng phản ứng theo `core.UserStatusChanged` (CO-06; #9). R2-18: các năng lực nền tảng luôn bật (mới BR-SAAS-091). Các chỉnh sửa được đánh dấu "(changed 2026-10-16, R2-02 §5 #n)". Các cột ưu tiên không đổi (PM đồng bộ). |
| Người phụ trách | BA – Nền tảng SaaS & xuyên suốt |
| Tiền tố yêu cầu | `BR-SAAS-NNN` (nghiệp vụ), `NFR-SAAS-NNN` (phi chức năng) |
| Liên quan | Project charter; các BRD module `01-core` … `05-recruitment`; `gap-analysis.md`; `architecture-overview.md` |
| Trạng thái | Chờ PM, Kiến trúc sư giải pháp, Kiến trúc sư dữ liệu, Trưởng nhóm bảo mật, chuyên gia tư vấn pháp lý/thuế VN rà soát |

> **Phase:** phase triển khai của mọi yêu cầu chỉ được xác định trong [mvp-scope.md](../mvp-scope.md) §9; MoSCoW ở đây là mức ưu tiên nghiệp vụ. (2026-10-16)

> Quy ước (charter §4): mức ưu tiên theo MoSCoW, `M` = cần cho MVP / tenant trả phí đầu tiên. Mọi số liệu, tỷ lệ, thời hạn pháp lý hoặc tham chiếu nghị định đều được đánh dấu `[verify-legal]` cho đến khi chuyên gia tư vấn pháp lý/thuế Việt Nam xác nhận. Giá trị pháp lý là tham số có hiệu lực theo ngày, không bao giờ hard-code.

---

## 1. Mục đích & phạm vi

### 1.1 Mục đích
Xác định yêu cầu nghiệp vụ cho mọi thứ biến YourHRM thành một **sản phẩm SaaS thương mại, multi-tenant** thay vì một hệ thống nhân sự cho một khách hàng duy nhất, cùng với các năng lực xuyên suốt (định danh, báo cáo, tích hợp, import số dư đầu kỳ, kênh ESS, tuân thủ, NFR toàn sản phẩm) mà không module HR riêng lẻ nào (`core`, `people`, `time-attendance`, `payroll`, `recruitment`) sở hữu.

### 1.2 Trong phạm vi
| # | Lĩnh vực | Tóm tắt |
|---|---|---|
| A | Vòng đời tenant & thương mại | Đăng ký / dùng thử, khởi tạo, gói & phiên bản sản phẩm, feature flag, đăng ký module, định giá theo nhân viên, đo lường sử dụng, tính phí thuê bao kèm hóa đơn điện tử VN, nhắc nợ, tạm ngưng, offboarding (xuất dữ liệu + xóa) |
| B | Tenancy, cô lập & lưu trú dữ liệu | Tier dùng chung và tier riêng, cam kết cô lập tenant, lưu trú dữ liệu tại Việt Nam, cấu hình & tùy biến tenant (biểu mẫu, bản địa hóa vi/en; trường tùy chỉnh thuộc Core — R2-02 §5 #11), white-label, tên miền riêng, object storage, job scheduler (R2-02 §5 #15, #16) |
| C | Back-office của nhà vận hành | Bảng điều khiển quản trị nền tảng, truy cập hỗ trợ có sự đồng ý của tenant, quy tắc mạo danh (impersonation), audit nhà vận hành |
| D | Định danh & truy cập (xác thực) | Đăng nhập cục bộ, chính sách mật khẩu, MFA, SSO (SAML 2.0 / OIDC, Google Workspace, Microsoft Entra ID / M365), cấp phát kiểu SCIM, quản lý phiên |
| E | Báo cáo & phân tích | Danh mục báo cáo chuẩn, công cụ thiết kế báo cáo, báo cáo theo lịch, dashboard, xuất Excel/PDF, phân tích nhân sự, framework báo cáo theo luật định, tách OLTP / báo cáo |
| F | Tích hợp | REST API công khai, webhook, API key / OAuth client, kế toán (MISA, Fast, SAP), chữ ký điện tử & hợp đồng lao động điện tử, VNeID, VSS/BHXH, eTax, ngân hàng, thiết bị chấm công, Zalo / email / SMS |
| G | Import số dư đầu kỳ & onboarding | Điều phối onboarding trên **import engine của Core** (R2-02 §5 #12): mẫu import, import số dư đầu kỳ dùng chung cho go-live giữa năm (hồ sơ nhân viên gốc, thu nhập/thuế TNCN/BHXH lũy kế từ đầu năm, lịch sử đóng BHXH, số dư phép, khoản vay), kiểm tra hợp lệ, đối chiếu với tổng kiểm soát của khách hàng. Không chuyển đổi dữ liệu cũ (changed 2026-10-16, D-09) |
| H | Kênh mobile / ESS | ESS web responsive (Phase 1); ứng dụng mobile cho nhân viên (iOS/Android) gồm mọi hình thức check-in (Phase 2) (changed 2026-10-16, OD-9) |
| I | Tuân thủ & bảo mật | Luật BVDLCN 2025 / Nghị định 13/2023, sổ đăng ký sự đồng ý duy nhất, danh mục dữ liệu cá nhân, điều phối DSR, bộ lập lịch lưu trữ duy nhất (R2-02 §5 #27), DPIA, thông báo vi phạm, sẵn sàng ISO 27001 / SOC 2, mã hóa, sao lưu, sự kiện bảo mật được ghi vào audit store của Core (R2-02 §5 #13) |
| K | Legal Parameter Service | Một service cho mọi tham số pháp lý (mức lương tối thiểu + bản đồ đơn vị hành chính → vùng, tỷ lệ & mức trần BHXH/BHYT/BHTN/TNLĐ-BNN, mức tham chiếu, biểu thuế TNCN & giảm trừ, ngưỡng thu nhập vãng lai, giới hạn làm thêm giờ, khung giờ đêm, số ngày phép được hưởng, giới hạn thử việc/báo trước, lộ trình tuổi nghỉ hưu, thời hạn quyết toán, mẫu ngày lễ, quy tắc bảo hiểm nhiều hợp đồng theo từng loại bảo hiểm — changed 2026-10-16, R3-03), API đọc `getParam(key, scope, asOfDate)` (R2-02 §5 #1) |
| J | NFR toàn sản phẩm | SLA sẵn sàng, hiệu năng, khả năng mở rộng, khả năng tiếp cận, observability, quản lý phát hành, toggle theo tenant, quản lý thay đổi pháp lý |

### 1.3 Ngoài phạm vi
- Bản thân các quy tắc nghiệp vụ HR (công thức lương, tích lũy phép, quy tắc ca, quy trình tuyển dụng) — thuộc các BRD module `02`–`05`.
- Mô hình phân quyền trong tenant (vai trò, quyền, phạm vi dữ liệu theo đơn vị tổ chức), tài khoản người dùng tenant và việc cấp phát chúng, workflow engine, trung tâm thông báo, dịch vụ và store audit, dịch vụ tài liệu, import engine, framework trường tùy chỉnh, dữ liệu gốc pháp nhân và kho dữ liệu danh mục toàn cục — thuộc `core` (`01-core.md`) (changed 2026-10-16, R2-02 §5 #9, #11–#15b, #23, #28). Nền tảng cung cấp **xác thực**, **tenant context**, **quyền sử dụng (entitlement)** và các dịch vụ hạ tầng dùng chung (object storage, gửi qua kênh, scheduler, tham số pháp lý, báo cáo, dịch vụ quyền riêng tư); `core` cung cấp **phân quyền**.
- Các quy trình kế toán / ERP riêng của khách hàng ngoài hợp đồng xuất dữ liệu/tích hợp.
- Marketplace ứng dụng bên thứ ba, trợ lý AI, mở rộng khu vực (ngoài VN) — Phase 3 theo charter.
- Cung cấp phần cứng thiết bị chấm công.

### 1.4 Tóm tắt phân phase
| Phase | Năng lực nền tảng |
|---|---|
| MVP (Phase 1) | Onboarding tenant có hỗ trợ (nhà vận hành khởi tạo), đăng ký module & entitlement, tính phí theo giá hợp đồng kèm hóa đơn điện tử VN (số lượng tính phí cuối tháng theo pháp nhân), tier dùng chung với cô lập bằng RLS, lưu trú dữ liệu tại VN, vi/en, mật khẩu + MFA, báo cáo chuẩn + xuất Excel/PDF, báo cáo theo luật định cho BHXH/thuế TNCN, mẫu import và **import số dư đầu kỳ** có đối chiếu, đầu ra dạng file cho ngân hàng/GL/eTax/BHXH, một connector thiết bị chấm công được chứng nhận + import dữ liệu chấm công Excel, ESS web responsive, thông báo e-mail, mức tuân thủ BVDLCN cơ bản, sao lưu/DR. **Không có ứng dụng mobile, push hay check-in qua mobile/GPS/selfie/web** (changed 2026-10-16, D-09/OD-9/X-04) |
| Phase 2 | Tự đăng ký & dùng thử, gói/phiên bản & bảng giá, tự động hóa tính phí theo đo lường sử dụng, nhắc nợ, xuất dữ liệu & xóa khi offboarding tenant, tier riêng, sandbox, SSO OIDC/SAML, trường tùy chỉnh, công cụ thiết kế báo cáo & dashboard, phân tích nhân sự, reporting store riêng, integration hub, API công khai + webhook, connector API kế toán/ngân hàng/VSS/eTax, chữ ký điện tử, ứng dụng mobile native với mọi hình thức check-in và push, white-label & tên miền riêng |
| Phase 3 | Tích hợp marketplace, trợ lý AI, mở rộng khu vực |

---

## 2. Tác nhân & persona

| Tác nhân | Loại | Mô tả / mục tiêu |
|---|---|---|
| Khách hàng tiềm năng / Người dùng thử | Bên ngoài | Đánh giá YourHRM; muốn có bản dùng thử chạy được với dữ liệu mẫu trong vài phút |
| Chủ tài khoản tenant (Chủ tài khoản) | Tenant | Ký hợp đồng thuê bao, chịu trách nhiệm thanh toán, có thể chuyển quyền sở hữu, phê duyệt truy cập hỗ trợ và offboarding |
| Tenant Admin (Quản trị hệ thống khách hàng) | Tenant | Cấu hình công ty, module, SSO, trường tùy chỉnh, tích hợp, API client |
| Người dùng HR / C&B | Tenant | Chạy báo cáo, import dữ liệu, sử dụng đầu ra theo luật định |
| Tài chính / Kế toán | Tenant | Nhận hóa đơn thuê bao; nhận bút toán lương trong MISA/Fast/SAP |
| Nhân viên (người dùng ESS) | Tenant | Dùng ESS web responsive: phiếu lương, nghỉ phép, xem chấm công & yêu cầu điều chỉnh, hồ sơ, sự đồng ý. Ứng dụng mobile và check-in thuộc Phase 2 (changed 2026-10-16, OD-9) |
| DPO / Người phụ trách bảo vệ dữ liệu (Người phụ trách bảo vệ DLCN) | Tenant | Xử lý yêu cầu của chủ thể dữ liệu, hồ sơ sự đồng ý, đầu vào DPIA, thông báo vi phạm cho tenant (bên kiểm soát) |
| Nhà vận hành nền tảng – Hỗ trợ (L1/L2) | Nhà cung cấp | Chẩn đoán sự cố của tenant khi có sự đồng ý; không bao giờ xem dữ liệu khi chưa được cấp quyền |
| Nhà vận hành nền tảng – Tư vấn onboarding | Nhà cung cấp | Khởi tạo tenant, hướng dẫn khách hàng dùng các mẫu số dư đầu kỳ, chạy kiểm tra hợp lệ và đối chiếu (changed 2026-10-16, D-09) |
| Nhà vận hành nền tảng – Tính phí / Tài chính | Nhà cung cấp | Quản lý gói, giá, hóa đơn, nhắc nợ |
| Quản trị viên nền tảng / SRE | Nhà cung cấp | Vận hành hạ tầng, phát hành, sao lưu, ứng phó sự cố |
| Cán bộ Bảo mật & Tuân thủ của nhà cung cấp | Nhà cung cấp | Chương trình ISO 27001 / SOC 2, DPIA với vai trò bên xử lý, thông báo vi phạm cho cơ quan chức năng |
| Người phụ trách nội dung pháp lý (chuyên gia Pháp lý/Tính lương) | Nhà cung cấp | Duy trì các gói tham số pháp lý (tỷ lệ, mức trần, lương tối thiểu, bậc thuế TNCN) cho mọi tenant |
| Hệ thống bên ngoài | Hệ thống | IdP, phần mềm kế toán, ngân hàng, VSS, eTax (GDT), VNeID, CA chữ ký điện tử, nhà cung cấp hóa đơn điện tử, thiết bị chấm công, Zalo OA / SMS / cổng email |
| Đối tác tích hợp / nhà phát triển | Bên ngoài | Xây dựng dựa trên API công khai và webhook |

---

## 3. Quy trình nghiệp vụ

### P1. Đăng ký, dùng thử và chuyển đổi
1. Khách hàng tiềm năng gửi biểu mẫu đăng ký (tên công ty, mã số thuế / MST, người liên hệ, quy mô nhân viên, ngôn ngữ ưu tiên).
2. Nền tảng xác minh email (link OTP) và số điện thoại (OTP), kiểm tra định dạng MST và trùng lặp.
3. Nền tảng khởi tạo một **tenant dùng thử** trên tier dùng chung với dữ liệu mẫu và mọi module của phiên bản đã chọn, hiệu lực trong thời gian dùng thử cấu hình được (mặc định 14 ngày).
4. Email nhắc vào T-7, T-3, T-1; Chủ tài khoản tenant chọn gói, module và chu kỳ thanh toán; chấp nhận ToS, DPA (thỏa thuận xử lý dữ liệu) và thông báo quyền riêng tư.
5. Khi thanh toán lần đầu (hoặc điều khoản hóa đơn được duyệt), tenant chuyển sang **Active**; dữ liệu mẫu được xóa theo yêu cầu.
- *Ngoại lệ:* MST trùng → chuyển cho bộ phận kinh doanh (có thể là khách hàng hiện hữu); hết hạn dùng thử mà không chuyển đổi → **Expired** (chỉ đọc 30 ngày, sau đó xóa theo P8); khách hàng doanh nghiệp lớn (> 1.000 nhân viên hoặc tier riêng) → luồng có hỗ trợ P2.

### P2. Khởi tạo có hỗ trợ (doanh nghiệp lớn / thí điểm)
1. Bộ phận kinh doanh chốt hợp đồng; nhà vận hành tạo tenant trong back-office: pháp nhân, phiên bản, module, tier (dùng chung / riêng), khu vực (VN), ngày hợp đồng, số suất cam kết.
2. Nền tảng khởi tạo hạ tầng (dùng chung: bản ghi tenant + khóa RLS; riêng: bộ database / schema) qua pipeline tự động, nạp dữ liệu tham chiếu và gói tham số pháp lý hiện hành.
3. Chủ tài khoản tenant nhận link kích hoạt; thiết lập MFA; cấu hình SSO (tùy chọn).
4. Import số dư đầu kỳ (P9) chạy ở chế độ **chỉ kiểm tra** cho đến khi sạch lỗi, sau đó được commit vào tenant production trước run lương đầu tiên (changed 2026-10-16, D-09; tenant sandbox thuộc Phase 2).
- *Ngoại lệ:* một bước khởi tạo thất bại → tự động rollback và cảnh báo nhà vận hành; không đủ năng lực cho tier riêng → trạng thái **Pending Provisioning** kèm đồng hồ SLA.

### P3. Thay đổi thuê bao (nâng cấp / hạ cấp / thêm module)
1. Chủ tài khoản tenant chọn thay đổi; nền tảng hiển thị giá tính theo tỷ lệ.
2. Nâng cấp / thêm module: entitlement có hiệu lực ngay; phí tính theo tỷ lệ trên hóa đơn kế tiếp.
3. Hạ cấp / bỏ module: có hiệu lực vào cuối kỳ thanh toán hiện tại; nền tảng cảnh báo về dữ liệu sẽ chuyển sang chỉ đọc (dữ liệu không bao giờ bị xóa do hạ cấp).

### P4. Đo lường sử dụng và chu kỳ tính phí
1. Vào mỗi cuối tháng, nền tảng chụp snapshot **nhân viên tính phí**, tức là những người đang làm việc vào cuối tháng, theo pháp nhân và theo module (BR-SAAS-012). Snapshot hằng ngày chỉ được giữ để tham khảo (changed 2026-10-16, D-10).
2. Khi đóng kỳ, nền tảng tính phí = max(số suất cam kết, tổng số nhân viên tính phí cuối tháng trên các pháp nhân) × đơn giá theo module, cộng các add-on, trừ chiết khấu (changed 2026-10-16, D-10).
3. Nhà vận hành tính phí rà soát; nền tảng xuất hóa đơn điện tử VN qua nhà cung cấp hóa đơn điện tử, gửi cho Chủ tài khoản tenant / đầu mối Tài chính, ghi nhận trạng thái thanh toán.
4. Nhận được thanh toán (đối soát chuyển khoản ngân hàng / cổng thanh toán) → hóa đơn Paid.
- *Ngoại lệ:* hóa đơn sai → hóa đơn điện tử điều chỉnh hoặc thay thế theo Nghị định 123/2020 được sửa đổi bởi Nghị định 70/2025 `[verify-legal]`; không thanh toán → nhắc nợ P5.

### P5. Nhắc nợ, tạm ngưng và kích hoạt lại
1. Quá hạn D+1: nhắc nhở; D+15: banner cảnh báo cho quản trị viên; D+30: **Suspended – read-only** (vẫn đăng nhập được, không tạo run lương mới, vẫn xuất dữ liệu được); D+60: **Suspended – locked** (chỉ Chủ tài khoản tenant được đăng nhập để thanh toán/xuất dữ liệu). Số ngày cấu hình được theo hợp đồng.
2. Thanh toán → tự động kích hoạt lại trong vòng 15 phút.
- *Ngoại lệ:* việc tạm ngưng không bao giờ chặn: (a) nhân viên tải phiếu lương/chứng từ thuế TNCN của chính mình, (b) Chủ tài khoản tenant xuất dữ liệu, (c) xử lý DSR.

### P6. Truy cập hỗ trợ và mạo danh
1. Nhân viên hỗ trợ mở **yêu cầu truy cập** gắn với ticket (phạm vi: chỉ đọc / đọc-ghi; module; thời hạn ≤ 72h; lý do).
2. Tenant Admin/Chủ tài khoản phê duyệt trong ứng dụng (hoặc một quyền cấp thường trực được phê duyệt trước cho các sự cố mức nghiêm trọng 1 được nêu tên theo hợp đồng).
3. Nhân viên hỗ trợ truy cập tenant qua "phiên hỗ trợ" — hiển thị banner, mọi thao tác được audit, các trường nhạy cảm (lương, tài khoản ngân hàng, số định danh, sức khỏe) được che trừ khi quyền cấp bao gồm rõ ràng.
4. Quyền cấp tự động hết hạn; tenant nhận bản tóm tắt phiên.
- *Ngoại lệ:* truy cập khẩn cấp (break-glass) cho sự cố toàn nền tảng → hai người phê duyệt phía nhà cung cấp, thông báo cho tenant trong vòng 24h.

### P7. Cấu hình SSO / MFA
1. Tenant Admin chọn IdP (Google, Microsoft Entra ID, SAML 2.0 / OIDC chung), tải metadata lên, ánh xạ claim → người dùng (email / mã nhân viên).
2. Thử đăng nhập với một quản trị viên được chỉ định; bắt buộc SSO cho các nhóm người dùng được chọn; giữ ≥1 quản trị viên cục bộ break-glass có MFA.

### P8. Offboarding (hết hợp đồng / chấm dứt)
1. Chủ tài khoản tenant (hoặc việc hết hạn hợp đồng) kích hoạt offboarding; nền tảng hiển thị checklist và hóa đơn cuối cùng.
2. Nền tảng tạo **bản xuất dữ liệu đầy đủ** (P8a) — dữ liệu có cấu trúc (CSV/JSON + từ điển dữ liệu) và tài liệu (phiếu lương, hợp đồng, tệp đính kèm) — tải về được trong 30 ngày.
3. Tenant chuyển sang trạng thái **Terminated – retention**: không đăng nhập được, trừ quyền xuất dữ liệu của Chủ tài khoản.
4. Sau thời gian lưu giữ (mặc định 90 ngày, có thể thỏa thuận trong hợp đồng), nền tảng **xóa** dữ liệu tenant khỏi storage chính, bản sao, chỉ mục tìm kiếm, reporting store và object storage; các bản sao lưu tự hết hạn trong thời gian lưu giữ sao lưu (≤ 35 ngày) và được crypto-shred khi có khóa theo tenant.
5. Nền tảng cấp **chứng nhận xóa dữ liệu** cho Chủ tài khoản tenant.
- *Ngoại lệ:* tenant hoặc cơ quan chức năng yêu cầu lưu giữ theo pháp lý (legal hold) → tạm dừng xóa kèm lý do; dữ liệu nhà cung cấp phải giữ làm hồ sơ của chính mình (tính phí, hóa đơn, hợp đồng, audit thao tác của nhà vận hành) được lưu theo luật kế toán `[verify-legal]`.

### P9. Import số dư đầu kỳ (go-live giữa năm, mọi tenant) (changed 2026-10-16, D-09)
YourHRM được xây dựng như một hệ thống mới, nên không có chuyển đổi dữ liệu cũ, vùng staging hay chạy song song. Mỗi tenant, kể cả tenant thí điểm, nạp dữ liệu cần thiết để go-live từ các mẫu Excel chuẩn.
1. **Kế hoạch chuyển đổi.** Nhà vận hành và tenant thống nhất tháng go-live cho từng pháp nhân. Số dư đầu kỳ được xác định "tại thời điểm cuối tháng trước tháng go-live" (BR-SAAS-088).
2. **Mẫu.** Tenant tải các mẫu (BR-SAAS-069) về và điền theo thứ tự sau:
   1. đơn vị tổ chức, vị trí và trung tâm chi phí
   2. hồ sơ nhân viên gốc (cá nhân, quan hệ lao động, phân công, hợp đồng, lương, ngân hàng, người phụ thuộc)
   3. lịch sử lương cần cho trợ cấp thôi việc và các cơ sở bình quân
   4. lịch sử đóng BHXH
   5. thu nhập, bảo hiểm và thuế TNCN lũy kế theo từng tháng của năm tính thuế (BR-SAAS-089)
   6. số dư phép (theo loại, gồm phép chuyển sang và hạn sử dụng) và số giờ làm thêm lũy kế từ đầu năm
   7. khoản vay và tạm ứng còn dư nợ
3. **Kiểm tra.** Mỗi file được tải lên ở chế độ **chỉ kiểm tra**. Hệ thống kiểm tra trường bắt buộc, danh mục mã, toàn vẹn tham chiếu, trùng lặp (CCCD / MST / số BHXH) và địa chỉ cũ→mới, và báo lỗi theo dòng và cột bằng vi/en. Tenant sửa và tải lên lại cho đến khi file sạch lỗi.
4. **Commit.** Các lô được commit theo thứ tự phụ thuộc. Việc commit là idempotent (upsert theo khóa nghiệp vụ) và hoàn tác được cho đến khi số dư đầu kỳ bị khóa.
5. **Đối chiếu.** Báo cáo đối chiếu (BR-SAAS-072) so sánh các giá trị đã import với **tổng kiểm soát do tenant cung cấp**: bảng lương gần nhất của hệ thống cũ, thông báo C12-TS của VSS, các tờ khai thuế TNCN đã nộp trong năm (05/KK), và báo cáo phép. Người phụ trách HR/C&B và tài chính của tenant ký xác nhận.
6. **Go-live.** Run lương đầu tiên trong YourHRM dùng số lũy kế đầu kỳ. Việc phê duyệt run này sẽ khóa số dư đầu kỳ của pháp nhân đó (BR-SAAS-088). Các điều chỉnh sau đó chỉ được thực hiện qua các lô điều chỉnh có audit.
- *Ngoại lệ:*
  - Các dòng lỗi được đưa vào báo cáo lỗi tải về được. Mặc định không cho phép commit một phần.
  - Thiếu một tháng trước go-live sẽ chặn go-live của pháp nhân, vì quyết toán thuế TNCN cần dữ liệu cả năm.
  - Dữ liệu cá nhân trong các mẫu chỉ được tải lên qua nền tảng. Không bao giờ gửi qua e-mail và không bao giờ lưu trong source control (quy tắc README).

### P10. Triển khai thay đổi pháp lý
1. Người phụ trách nội dung pháp lý phát hiện văn bản pháp luật mới (ví dụ lương tối thiểu vùng, cơ sở trần BHXH, giảm trừ gia cảnh thuế TNCN).
2. Tạo một phiên bản **gói tham số pháp lý** với ngày hiệu lực, căn cứ pháp lý và test case; được rà soát chéo + chuyên gia tư vấn ký xác nhận.
3. Gói được phát hành lên staging; chạy hồi quy tự động trên các kịch bản tính lương tham chiếu.
4. Phát hành cho mọi tenant kèm thông báo (trong ứng dụng + email) ≥ 7 ngày trước ngày hiệu lực khi luật cho phép; tenant có thể xem trước tác động (mô phỏng trên dữ liệu của chính họ).
5. Các ghi đè ở mức tenant (ví dụ công ty trả cao hơn mức luật định) được giữ nguyên; audit ghi lại phiên bản gói mà mỗi run lương đã dùng.

### P11. Xử lý vi phạm dữ liệu cá nhân
1. Phát hiện (giám sát, báo cáo từ tenant hoặc nhân viên) → ticket sự cố, phân loại mức nghiêm trọng trong vòng 1h.
2. Ngăn chặn; đánh giá các tenant / chủ thể dữ liệu / loại dữ liệu bị ảnh hưởng.
3. Thông báo cho các tenant (bên kiểm soát) bị ảnh hưởng không chậm trễ (mục tiêu ≤ 24h kể từ khi xác nhận) kèm thông tin để họ tự thông báo.
4. Thông báo cho cơ quan có thẩm quyền (A05 – Cục An ninh mạng, Bộ Công an) trong vòng 72h khi nhà cung cấp là bên chịu trách nhiệm `[verify-legal]`.
5. Rà soát sau sự cố, hành động khắc phục, lưu giữ bằng chứng.

### P12. Yêu cầu của chủ thể dữ liệu (DSR)
1. Chủ thể dữ liệu (nhân viên, ứng viên, nhân viên cũ) gửi yêu cầu qua ESS / biểu mẫu công khai, hoặc DPO của tenant ghi nhận.
2. DPO của tenant xác minh danh tính và quyết định (tenant là bên kiểm soát; nền tảng là bên xử lý).
3. Công cụ của nền tảng thực thi: truy cập/xuất dữ liệu, chuyển yêu cầu chỉnh sửa, rút lại sự đồng ý, hạn chế xử lý, xóa/ẩn danh hóa (tuân thủ thời hạn lưu trữ luật định), phản đối.
4. Phản hồi được theo dõi theo thời hạn luật định `[verify-legal]`; audit được lưu giữ.

---

## 4. Yêu cầu nghiệp vụ

### 4.A Vòng đời tenant & thương mại

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-001 | Nền tảng phải duy trì bản ghi **tenant** với trạng thái vòng đời ∈ {Trial, Pending Provisioning, Active, Suspended-ReadOnly, Suspended-Locked, Expired, Terminated-Retention, Deleted} và ghi log mọi lần chuyển trạng thái kèm người thực hiện, lý do và thời điểm. | M | Trạng thái quyết định việc đăng nhập, lập lịch job, tính phí |
| BR-SAAS-002 | Một tenant phải chứa 1..n **pháp nhân** (pháp nhân), dùng chung một thuê bao. **Core sở hữu dữ liệu gốc pháp nhân** (MST, địa chỉ, mã đơn vị BHXH, cơ quan thuế — BR-CORE-001/002). Nền tảng chỉ giữ bản ghi **bên nhận hóa đơn** (MST người mua, tên, địa chỉ, định danh hóa đơn điện tử) và giới hạn số pháp nhân của gói; việc khởi tạo tạo pháp nhân đầu tiên qua API của Core, và việc đo lường tham chiếu `core.legal_entity.id` (changed 2026-10-16, R2-02 §5 #23). | M | Tập đoàn có công ty con |
| BR-SAAS-003 | Nhà vận hành phải khởi tạo được một tenant hoạt động đầy đủ (tier dùng chung) từ back-office trong ≤ 10 phút từ đầu đến cuối, bao gồm dữ liệu tham chiếu và gói tham số pháp lý hiện hành. | M | Luồng có hỗ trợ P2 |
| BR-SAAS-004 | Khách hàng tiềm năng phải tự đăng ký dùng thử được với xác minh OTP qua email + điện thoại, kiểm tra định dạng MST và phát hiện trùng lặp; tenant dùng thử sẵn sàng trong ≤ 2 phút với dữ liệu mẫu tùy chọn. | S | Phase 2 |
| BR-SAAS-005 | Thời gian dùng thử, phiên bản dùng thử và giới hạn số nhân viên dùng thử phải cấu hình được theo chiến dịch; khi hết hạn tenant chuyển sang chỉ đọc, sau đó bị xóa sau một khoảng thời gian cấu hình được (mặc định 30 ngày) kèm cảnh báo trước 7 ngày và 1 ngày. | S | |
| BR-SAAS-006 | Nền tảng phải định nghĩa **gói/phiên bản** (ví dụ Starter, Professional, Enterprise) là các bộ có phiên bản gồm module, tính năng, giới hạn (nhân viên, pháp nhân, dung lượng lưu trữ, số lượt gọi API) và mức hỗ trợ. Tenant hiện hữu giữ phiên bản gói của mình cho đến khi được chuyển đổi. | M | Danh mục gói do nhà vận hành tính phí sở hữu |
| BR-SAAS-007 | Mỗi module (`core` bắt buộc; `people`, `time-attendance`, `payroll`, `recruitment`) và add-on (ứng dụng mobile, phân tích nâng cao, API, tier riêng, white-label) phải đăng ký được độc lập theo tenant, có ngày bắt đầu/kết thúc, tuân theo các **năng lực nền tảng** luôn bật (BR-SAAS-091) (changed 2026-10-16, R2-18). | M | |
| BR-SAAS-008 | Một **entitlement service** trung tâm phải trả lời câu hỏi "tính năng X hiện có được bật cho tenant T (và pháp nhân E) không?"; mọi UI, API và job chạy nền của module đều phải thực thi nó. Tính năng không có quyền sử dụng bị ẩn trên UI và trả về HTTP 403 với mã `FEATURE_NOT_ENTITLED` trên API. | M | Được mọi module sử dụng (§7) |
| BR-SAAS-009 | **Feature flag** phải hỗ trợ các phạm vi: toàn cục, gói, tenant, nhóm người dùng, triển khai theo tỷ lệ phần trăm; có kill-switch; thay đổi flag được audit và có hiệu lực trong ≤ 60 s mà không cần triển khai lại. | M | Cũng dùng cho quản lý phát hành (NFR-SAAS-017) |
| BR-SAAS-010 | Định giá phải hỗ trợ đơn giá theo nhân viên mỗi tháng (PEPM) theo module, bậc theo khối lượng, phí tối thiểu hằng tháng, số suất cam kết, chiết khấu trả trước theo năm, mã khuyến mại, và ghi đè giá theo hợp đồng cụ thể. Giá bằng VND, chưa gồm VAT. | M | |
| BR-SAAS-011 | Thay đổi gói phải được tính theo tỷ lệ theo ngày; nâng cấp có hiệu lực ngay, hạ cấp vào cuối kỳ; hạ cấp không bao giờ xóa dữ liệu (dữ liệu bị ảnh hưởng chuyển sang chỉ đọc). | M | |
| BR-SAAS-012 | **Nhân viên tính phí** = một người có quan hệ lao động đang hiệu lực tại một pháp nhân vào **ngày cuối cùng của tháng tính phí**. Số lượng được đếm theo pháp nhân và theo module, nên một người làm việc tại hai pháp nhân của cùng tenant được tính hai lần. Người ở trạng thái trước khi nhận việc, đã nghỉ việc hoặc tạm hoãn không được tính phí vào ngày đó, các bản ghi chỉ giữ để lưu lịch sử cũng vậy. Với `payroll`, người được trả lương trong run thường kỳ của tháng đó tại pháp nhân cũng được tính. Chu kỳ quý và năm tính phí bằng tổng số lượng của các tháng. (changed 2026-10-16, D-10) | M | Cần nguồn dữ liệu số nhân sự cuối tháng theo pháp nhân từ `people` (CO-05). Cách xử lý cộng tác viên/CTV không có trong bảng lương: Product xác nhận |
| BR-SAAS-013 | Nền tảng phải đo lường và lưu số liệu sử dụng hằng ngày theo tenant: số nhân viên tính phí theo module, số người dùng hoạt động, dung lượng lưu trữ GB, số lượt gọi API, số tin SMS/Zalo đã gửi, số giao dịch chữ ký điện tử; Chủ tài khoản tenant và nhà vận hành xem được. | M | Chi phí chuyển tiếp (SMS, ký điện tử) được tính phí |
| BR-SAAS-014 | Nền tảng phải tạo hóa đơn thuê bao theo chu kỳ tính phí (tháng / quý / năm) và xuất **hóa đơn điện tử VN** (hóa đơn điện tử, có mã của cơ quan thuế khi bắt buộc) qua nhà cung cấp hóa đơn điện tử được chứng nhận, gồm VAT theo thuế suất hiện hành. | M | Nghị định 123/2020/ND-CP được sửa đổi bởi Nghị định 70/2025/ND-CP; Thông tư 78/2021/TT-BTC `[verify-legal]` |
| BR-SAAS-015 | Việc sửa hóa đơn phải dùng hóa đơn điện tử điều chỉnh hoặc thay thế theo quy định; hóa đơn điện tử đã xuất không bao giờ bị sửa hoặc xóa. | M | `[verify-legal]` |
| BR-SAAS-016 | Thanh toán phải được chấp nhận qua chuyển khoản ngân hàng (đối soát tự động qua tài khoản ảo / nội dung chuyển khoản) và ít nhất một cổng thanh toán nội địa (thẻ, QR/VietQR). | S | |
| BR-SAAS-017 | Nhắc nợ phải tuân theo các bước cấu hình được (nhắc nhở → cảnh báo → chỉ đọc → khóa) theo P5; việc tạm ngưng không bao giờ chặn nhân viên truy cập phiếu lương/chứng từ thuế TNCN của chính mình, Chủ tài khoản xuất dữ liệu, hoặc xử lý DSR. | M | |
| BR-SAAS-018 | Chủ tài khoản tenant phải yêu cầu offboarding được; nền tảng phải giao bản xuất đầy đủ (dữ liệu có cấu trúc dạng CSV/JSON kèm từ điển dữ liệu + mọi tài liệu) trong vòng 72h kể từ khi yêu cầu. | M | Cũng tự động khi hết hợp đồng |
| BR-SAAS-019 | Sau thời gian lưu giữ, nền tảng phải xóa toàn bộ dữ liệu tenant khỏi mọi kho (DB, bản sao, reporting store, tìm kiếm, object storage, cache, log có dữ liệu cá nhân) và cấp chứng nhận xóa; các bản sao lưu hết hạn trong thời gian lưu giữ sao lưu. | M | Legal hold có thể tạm dừng |
| BR-SAAS-020 | Việc chuyển quyền sở hữu tenant phải được Chủ tài khoản hiện tại phê duyệt (hoặc nhà cung cấp xác minh giấy tờ chứng minh thẩm quyền pháp lý nếu Chủ tài khoản không liên hệ được). | S | |

### 4.B Tenancy, cô lập, lưu trú dữ liệu & tùy biến

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-021 | Mọi dòng dữ liệu nghiệp vụ trong `core`, `hr`, `att`, `pay`, `rec` phải mang `tenant_id`; việc cô lập phải được thực thi trong database (PostgreSQL Row-Level Security) **và** ở tầng ứng dụng, để việc thiếu bộ lọc trong mã không thể trả về dữ liệu của tenant khác. | M | Phòng thủ nhiều lớp; ADR do Kiến trúc sư dữ liệu viết |
| BR-SAAS-022 | Nền tảng phải hỗ trợ hai tier: **Shared** (DB dùng chung, RLS) và **Dedicated** (database hoặc cluster riêng cho mỗi tenant, cùng code base và chu kỳ phát hành). Chuyển một tenant giữa các tier phải là quy trình do nhà vận hành thực hiện, đã được kiểm thử, với khung thời gian ngừng hoạt động ≤ 4h. | S | Dedicated = add-on Enterprise, Phase 2 |
| BR-SAAS-023 | **Kiểm thử cô lập giữa các tenant** tự động phải chạy ở mỗi lần phát hành và đánh trượt bản phát hành nếu bất kỳ API, báo cáo, bản xuất, tìm kiếm, URL file hoặc cache key nào làm lộ dữ liệu giữa các tenant. | M | |
| BR-SAAS-024 | Toàn bộ dữ liệu tenant (chính, bản sao, sao lưu, reporting store, file, log có dữ liệu cá nhân) phải được lưu trữ và xử lý trên hạ tầng đặt **tại Việt Nam**. Mọi dịch vụ ngoài VN (ví dụ gửi email, SaaS giám sát) không được nhận dữ liệu cá nhân hoặc phải có đánh giá tác động chuyển dữ liệu ra nước ngoài. | M | Luật An ninh mạng 2018 + Nghị định 53/2022/ND-CP Điều 26; quy định chuyển dữ liệu xuyên biên giới của Luật BVDLCN 2025 `[verify-legal]` |
| BR-SAAS-025 | Tenant Admin phải cấu hình được thiết lập tenant: ngôn ngữ mặc định (vi/en), múi giờ (mặc định Asia/Ho_Chi_Minh), định dạng ngày/số, tiền tệ (mặc định VND), năm tài chính, ngày đầu tuần, logo; ghi đè ngôn ngữ theo từng người dùng. | M | |
| BR-SAAS-026 | Toàn bộ UI, thông báo, báo cáo chuẩn, phiếu lương và email hệ thống phải có bằng **tiếng Việt và tiếng Anh**; mọi chuỗi hiển thị cho người dùng được tách ra ngoài mã; dấu tiếng Việt hiển thị đúng trên UI, PDF và Excel; sắp xếp theo quy tắc tiếng Việt và tìm kiếm không phân biệt dấu. | M | |
| BR-SAAS-027 | Đã bỏ — thuộc core (R2-02). Framework trường tùy chỉnh (kiểu, thực thể, kiểm tra hợp lệ, quyền và phạm vi dữ liệu ở mức trường, đưa vào báo cáo/import/API/xuất dữ liệu) là BR-CORE-086; các framework báo cáo, API và xuất dữ liệu của nền tảng hiển thị các trường tùy chỉnh của Core (changed 2026-10-16, R2-02 §5 #11). | M | Giữ ID; không tái sử dụng. |
| BR-SAAS-028 | Tenant Admin phải tùy biến được các biểu mẫu chuẩn (thứ tự trường, ẩn/hiện, nhãn vi/en, các phần) và mẫu tài liệu (hợp đồng, quyết định, phiếu lương, thư) mà không cần viết mã; thay đổi có phiên bản và hoàn tác được. | S | |
| BR-SAAS-029 | **White-label**: tenant (Enterprise) hoặc đại lý có thể đặt logo, chủ đề màu, tên sản phẩm trên UI/email/mobile, tên miền người gửi email (đã xác minh SPF/DKIM). | C | Phase 2 |
| BR-SAAS-030 | **Tên miền riêng**: tenant có thể ánh xạ `hr.customer.vn` tới tenant của mình với chứng chỉ TLS do nền tảng quản lý; URL tenant mặc định `<slug>.yourhrm.vn`. | C | Phase 2 |
| BR-SAAS-031 | Tenant có thể yêu cầu một tenant **sandbox** (bản sao của production, tùy chọn che dữ liệu cá nhân) để thử cấu hình, import và đào tạo; sandbox không tính vào số nhân viên tính phí. | S | Phase 2. Không còn cần cho go-live: số dư đầu kỳ dùng import ở chế độ chỉ kiểm tra (changed 2026-10-16, D-09) |

### 4.C Back-office của nhà vận hành & truy cập hỗ trợ

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-032 | Nền tảng phải cung cấp back-office cho nhà vận hành (tách biệt với UI tenant, realm xác thực riêng, bắt buộc MFA, IP allow-list) để quản lý tenant, gói, tính phí, khởi tạo, feature flag, gói tham số pháp lý, tình trạng nền tảng, và **quy trình phát hành dữ liệu danh mục toàn cục** (đơn vị hành chính và ánh xạ cũ→mới, ngân hàng, quốc tịch…): nhà vận hành chỉnh sửa một bản phát hành, công bố nó, và back-office nạp nó dưới dạng bản phát hành có phiên bản vào **kho dữ liệu danh mục toàn cục của Core** qua admin API của Core, API này phát hành `core.MasterDataReleased` (changed 2026-10-16, R2-02 §5 #28). | M | Core sở hữu kho và API (BR-CORE-080/087) |
| BR-SAAS-033 | Nhà vận hành **mặc định không có quyền truy cập** dữ liệu nghiệp vụ của tenant. Việc truy cập cần một **quyền cấp hỗ trợ** có thời hạn (≤ 72h) do Tenant Admin/Chủ tài khoản phê duyệt, giới hạn theo module và chỉ đọc/đọc-ghi. | M | P6 |
| BR-SAAS-034 | Trong phiên hỗ trợ, các trường nhạy cảm (số tiền lương, tài khoản ngân hàng, số định danh/CCCD, MST cá nhân, dữ liệu sức khỏe và y tế BHXH, người phụ thuộc) phải được che trừ khi quyền cấp bao gồm rõ ràng "dữ liệu nhạy cảm". | M | Dữ liệu cá nhân nhạy cảm theo quy định BVDLCN `[verify-legal]` |
| BR-SAAS-035 | **Mạo danh** ("xem dưới vai trò người dùng") chỉ được phép trong một quyền cấp đang hiệu lực, phải hiển thị banner thường trực cho nhà vận hành, phải được ghi vào audit log của tenant dưới cả hai danh tính (nhà vận hành + người dùng bị mạo danh), và phải chặn: phê duyệt workflow, đổi mật khẩu/MFA, phát hành lương/chi trả, và ký tài liệu. | M | |
| BR-SAAS-036 | Truy cập khẩn cấp (break-glass) cho sự cố toàn nền tảng phải cần hai người phê duyệt phía nhà cung cấp, giới hạn 4h, và được thông báo cho tenant bị ảnh hưởng trong vòng 24h. | M | |
| BR-SAAS-037 | Tenant Admin phải xem được log mọi lần nhà vận hành truy cập tenant của mình (ai, khi nào, lý do, ticket, đã xem/thay đổi gì) và xuất được log đó. | M | |

### 4.D Định danh & xác thực

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-038 | Xác thực cục bộ với **chính sách mật khẩu** cấu hình được theo tenant: độ dài tối thiểu (mặc định 10, tối thiểu 8), độ phức tạp, lịch sử (mặc định 5), thời hạn tối đa (tùy chọn), khóa sau N lần thất bại (mặc định 5 / 15 phút), kiểm tra mật khẩu đã bị lộ. | M | |
| BR-SAAS-039 | **MFA** qua ứng dụng xác thực TOTP và OTP qua email (OTP qua SMS tùy chọn, có tính phí); bắt buộc được theo tenant/vai trò; bắt buộc với Chủ tài khoản tenant, Tenant Admin, người duyệt lương, và mọi nhà vận hành. | M | |
| BR-SAAS-040 | **SSO** với Google Workspace và Microsoft Entra ID (M365) qua OIDC. | M | MVP |
| BR-SAAS-041 | **SSO** với bất kỳ IdP SAML 2.0 hoặc OIDC nào, cấu hình được theo tenant, có ánh xạ claim (email / UPN / mã nhân viên), liên kết JIT với người dùng hiện có, và tùy chọn bắt buộc "chỉ SSO" theo nhóm người dùng. | S | Phase 2 |
| BR-SAAS-042 | Cấp phát người dùng từ IdP qua SCIM 2.0 (tạo/vô hiệu hóa người dùng, đề xuất ánh xạ nhóm → vai trò; việc gán vai trò vẫn ở `core`). | C | |
| BR-SAAS-043 | Quản lý phiên: thời gian chờ không hoạt động cấu hình được (mặc định 30 phút trên web, 30 ngày trên mobile có mở khóa sinh trắc học), thời hạn tuyệt đối, giới hạn số phiên đồng thời, "đăng xuất khỏi mọi thiết bị", quản trị viên buộc đăng xuất. | M | |
| BR-SAAS-044 | Một người có thể có tài khoản người dùng ở nhiều tenant (ví dụ chuyên gia tư vấn, dịch vụ dùng chung của tập đoàn) với bộ chuyển tenant; danh tính không bao giờ được gộp giữa các tenant nếu người dùng không chủ động liên kết. | S | |
| BR-SAAS-045 | Đăng nhập ESS của nhân viên phải hỗ trợ tên đăng nhập = mã nhân viên / email công việc / số điện thoại di động, kích hoạt lần đầu qua OTP; không có mật khẩu mặc định dùng chung. **Phase 1 (changed 2026-10-16, R3-05):** OTP kích hoạt chỉ được gửi qua e-mail (SMS thuộc P2), nên nhân viên cần một địa chỉ e-mail công việc hoặc cá nhân đã xác minh để dùng ESS. Nhân viên không có e-mail **không có quyền truy cập ESS ở Phase 1**: không cấp mã kích hoạt, và HR trao cho họ phiếu lương PDF bản in (Payroll BR-PAY-102). | M | |

### 4.E Báo cáo & phân tích

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-046 | Nền tảng phải cung cấp **framework báo cáo** duy nhất cho mọi module: danh mục báo cáo, tham số, row-level security kế thừa từ phạm vi dữ liệu của `core` (Core scope predicate, P-10), che dữ liệu ở mức trường với các trường nhạy cảm, xuất ra Excel (.xlsx), PDF và CSV — bao gồm **xuất dữ liệu từ màn hình danh sách** của mọi module (trước đây là BR-CORE-113) (changed 2026-10-16, R2-02 §5 #18). | M | Mỗi module định nghĩa các báo cáo chuẩn của mình (§8 của từng BRD) |
| BR-SAAS-047 | Các truy vấn báo cáo trên khối lượng lớn phải chạy trên một **reporting store riêng** (read replica hoặc kho phân tích) để tải báo cáo không làm giảm hiệu năng giao dịch OLTP (run lương, check-in). Độ trễ dữ liệu của reporting store ≤ 15 phút; báo cáo hiển thị dấu thời gian "dữ liệu tính đến". | M | Báo cáo theo luật định/chốt lương có thể cần đọc thời gian thực → chạy trên OLTP có giới hạn tải |
| BR-SAAS-048 | **Báo cáo theo lịch**: người dùng có thể lên lịch bất kỳ báo cáo nào họ được phép chạy (hằng ngày/hằng tuần/hằng tháng/khi chốt lương) và gửi tới hộp thư trong ứng dụng, email (tệp đính kèm có mật khẩu với báo cáo nhạy cảm) hoặc SFTP. | S | |
| BR-SAAS-049 | **Công cụ thiết kế báo cáo** (tự phục vụ): Tenant Admin/chuyên viên phân tích HR xây dựng báo cáo dạng bảng, pivot và biểu đồ từ một semantic layer được quản trị (các trường đặt tên theo nghiệp vụ, gồm cả trường tùy chỉnh), lưu, chia sẻ theo vai trò. | S | Phase 2 |
| BR-SAAS-050 | **Dashboard**: dashboard theo vai trò (CEO, trưởng phòng nhân sự, quản lý trực tiếp, C&B) với các ô KPI và drill-down; tenant có thể tạo/sửa dashboard từ các báo cáo của công cụ thiết kế. | S | Phase 2 |
| BR-SAAS-051 | Gói **phân tích nhân sự**: xu hướng số nhân sự & FTE, tuyển mới/nghỉ việc, tỷ lệ nghỉ việc & giữ chân, vắng mặt, làm thêm giờ so với giới hạn luật định, chi phí lao động theo tổ chức/trung tâm chi phí, phân bố giới tính/độ tuổi, thời gian tuyển dụng, chênh lệch lương giữa các tháng. | S | Add-on phân tích nâng cao |
| BR-SAAS-052 | **Framework báo cáo theo luật định**: các mẫu có phiên bản (bố cục + ánh xạ) cho biểu mẫu nhà nước, được cập nhật qua quy trình thay đổi pháp lý (P10) độc lập với các bản phát hành ứng dụng; đầu ra ở định dạng chính thức (XML/Excel/PDF) mà cổng tiếp nhận yêu cầu. | M | Mẫu do các BRD module sở hữu (biểu mẫu BHXH trong `payroll`, báo cáo lao động trong `people`) |
| BR-SAAS-053 | Các bản xuất lớn (> 10.000 dòng hoặc > 30 s) phải chạy bất đồng bộ kèm thông báo khi hoàn tất; file hết hạn sau 7 ngày; mọi bản xuất đều được audit (ai, báo cáo nào, tham số, số dòng). | M | Kiểm soát rò rỉ dữ liệu |
| BR-SAAS-054 | Tenant gói Enterprise có thể nhận **bản trích xuất dữ liệu** được quản trị (tăng dần hằng ngày, tùy chọn bút danh hóa) vào công cụ BI của riêng họ (Power BI, v.v.) qua endpoint bảo mật. | C | |

### 4.F Tích hợp

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-055 | **REST API công khai** (có phiên bản, tài liệu OpenAPI 3, JSON, phân trang, idempotency key cho POST, mô hình lỗi chuẩn) bao phủ tối thiểu: đơn vị tổ chức, vị trí, nhân viên, phân công, hợp đồng, bản ghi chấm công, đơn nghỉ phép/số dư phép, kết quả lương (chỉ đọc), ứng viên. | S | Phase 2; API nội bộ tuân theo cùng tiêu chuẩn từ MVP |
| BR-SAAS-056 | API client: Tenant Admin có thể tạo **API key** (có phạm vi, có hạn, thu hồi được) và client **OAuth 2.0 client-credentials** với phạm vi theo module & đọc/ghi; secret chỉ hiển thị một lần; xem được số liệu sử dụng theo từng client. | S | |
| BR-SAAS-057 | Giới hạn tần suất API theo tenant và theo client tùy theo gói (ví dụ số request/phút, hạn mức hằng ngày), trả về HTTP 429 kèm retry-after. | S | |
| BR-SAAS-058 | **Webhook**: tenant đăng ký nhận sự kiện (employee.created/updated/terminated, contract.signed, leave.approved, attendance.closed, payroll.approved, payroll.paid, applicant.hired…); payload ký HMAC, thử lại với exponential backoff ≥ 24h, màn hình dead-letter, phát lại. | S | Danh mục sự kiện từ các module (§7) |
| BR-SAAS-059 | **Tích hợp kế toán**: xuất bút toán lương (tài khoản GL, trung tâm chi phí, pháp nhân, nợ/có) theo ánh xạ cấu hình được cho MISA (AMIS / SME), Fast Accounting và SAP (IDoc/CSV/BAPI qua middleware); xuất file ở MVP, đẩy qua API ở Phase 2. | M | Xuất file là M; đẩy qua API là S |
| BR-SAAS-060 | **Tích hợp chi trả ngân hàng**: tạo file chi lương theo định dạng của các ngân hàng lớn tại VN (tối thiểu Vietcombank, BIDV, VietinBank, Techcombank, MB, ACB) và, khi có, API host-to-host / open-banking có phản hồi trạng thái chi trả. | M | Xuất file M; API S |
| BR-SAAS-061 | **Tích hợp VSS / BHXH**: tạo file khai báo BHXH (tăng/giảm/điều chỉnh) theo định dạng được các nhà cung cấp dịch vụ giao dịch điện tử của VSS (I-VAN) chấp nhận và, khi có, nộp qua API của nhà cung cấp và nhận kết quả. | S | Luật BHXH 2024 (41/2024/QH15) và thủ tục của VSS `[verify-legal]` |
| BR-SAAS-062 | **Tích hợp eTax**: tạo tờ khai khấu trừ thuế TNCN (tháng/quý, quyết toán năm) theo định dạng XML HTKK/eTax; phát hành **chứng từ khấu trừ thuế TNCN điện tử** (chứng từ khấu trừ thuế TNCN điện tử) cho nhân viên. Phase 1: chỉ dữ liệu chứng từ và một PDF chưa ký; khách hàng ký và phát hành qua công cụ của cơ quan thuế hoặc nhà cung cấp của mình (Payroll BR-PAY-059; changed 2026-10-16, R3-02). | M | Nghị định 123/2020 được sửa đổi bởi Nghị định 70/2025; Thông tư 80/2021 `[verify-legal]` |
| BR-SAAS-063 | **Connector chữ ký điện tử & hợp đồng lao động điện tử** (integration hub; điều phối ký và trạng thái tài liệu đã ký thuộc Core, BR-CORE-103 — R2-02 §5 #15b): tích hợp với các nhà cung cấp chữ ký số/CA được cấp phép (ký từ xa) để ký hợp đồng lao động, phụ lục, quyết định và xác nhận phiếu lương; lưu PDF đã ký kèm bằng chứng xác thực chữ ký; hỗ trợ nộp hợp đồng lao động điện tử lên hệ thống hợp đồng lao động điện tử quốc gia khi bắt buộc. | S | Luật Giao dịch điện tử 2023; Bộ luật Lao động 2019 Điều 14; nghị định hướng dẫn về hợp đồng lao động điện tử `[verify-legal]` |
| BR-SAAS-064 | **VNeID / định danh quốc gia**: xác minh dữ liệu định danh của nhân viên (số CCCD, họ tên, ngày sinh) với VNeID/cơ sở dữ liệu quốc gia về dân cư qua kênh được ủy quyền, có sự đồng ý của nhân viên; tùy chọn cho phép đăng nhập ESS bằng VNeID khi được mở cho ứng dụng tư nhân. | C | Nghị định 69/2024/ND-CP về định danh điện tử `[verify-legal]` |
| BR-SAAS-065 | **Thiết bị chấm công**: tiếp nhận dữ liệu chấm công từ thiết bị sinh trắc học/khuôn mặt/thẻ (tối thiểu ZKTeco, Hikvision, Suprema) qua cloud push hoặc agent connector on-prem; lưu đệm dữ liệu chấm công khi offline; danh mục thiết bị theo tenant/địa điểm. | M | Bên tiêu thụ là `time-attendance`. Phase 1: một connector được chứng nhận + import Excel/file là các nguồn dữ liệu chấm công duy nhất (changed 2026-10-16, OD-9) |
| BR-SAAS-066 | **Kênh nhắn tin**: gửi thông báo qua email (mang thương hiệu tenant), SMS brandname, Zalo OA / ZNS, và push trên mobile; thông tin xác thực nhà cung cấp theo tenant hoặc mặc định của nền tảng; theo dõi trạng thái gửi; mẫu bằng vi/en. | M | Email thuộc Phase 1. Push, SMS và Zalo thuộc Phase 2 (changed 2026-10-16, OD-9) |
| BR-SAAS-067 | **Trang tuyển dụng** (recruitment): đăng tin tuyển dụng và import ứng viên từ các trang tuyển dụng lớn tại VN (ví dụ TopCV, VietnamWorks, CareerViet) và LinkedIn khi API cho phép. | C | Bên tiêu thụ là `recruitment` |
| BR-SAAS-068 | Mọi tích hợp phải dùng chung một **integration hub**: cấu hình connector theo tenant, kho thông tin xác thực được mã hóa, log thực thi kèm payload (che dữ liệu cá nhân), thử lại, cảnh báo cho Tenant Admin khi thất bại. | M | |

### 4.G Import số dư đầu kỳ & onboarding (changed 2026-10-16, D-09)

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-069 | **Mẫu import** tải về được (Excel, tiêu đề vi/en, có nhúng danh mục mã & hướng dẫn) cho mọi dữ liệu gốc và số dư đầu kỳ. Các mẫu: đơn vị tổ chức, vị trí, trung tâm chi phí, **hồ sơ nhân viên gốc** (cá nhân, quan hệ lao động, phân công, hợp đồng, lương, tài khoản ngân hàng, người phụ thuộc), lịch sử lương (≥ 6 tháng cho trợ cấp thôi việc/các cơ sở bình quân), **lịch sử đóng BHXH** (tháng, mức đóng BHXH, đơn vị đóng, phục vụ đối chiếu C12-TS và thời gian làm việc tính trợ cấp thôi việc), **thu nhập / bảo hiểm / thuế TNCN lũy kế theo tháng** (BR-SAAS-089), **số dư phép** (theo loại và năm phép, phép chuyển sang, hạn sử dụng) và số giờ làm thêm lũy kế từ đầu năm, **khoản vay và tạm ứng còn dư nợ** (dư nợ, số trả mỗi kỳ, tháng khấu trừ kế tiếp), ứng viên (Phase 2). (changed 2026-10-16, D-09) Mỗi mẫu là một **import type được đăng ký trên import engine của Core** (BR-CORE-110) và do module sở hữu dữ liệu tương ứng quản lý: People (hồ sơ nhân viên gốc, lịch sử lương), Payroll (thu nhập/thuế TNCN/BHXH lũy kế, lịch sử đóng BHXH, khoản vay), T&A (số dư phép, giờ làm thêm lũy kế), Core (tổ chức, vị trí, trung tâm chi phí); nền tảng công bố danh mục mẫu và vận hành quy trình onboarding P9 (changed 2026-10-16, R2-02 §5 #12). | M | Cùng một bộ mẫu cho mọi tenant; không có ETL riêng cho khách hàng |
| BR-SAAS-070 | Yêu cầu đối với **import engine của Core** (BR-CORE-110/111; changed 2026-10-16, R2-02 §5 #12): việc import phải chạy ở chế độ **chỉ kiểm tra** trước, báo mọi lỗi theo dòng/cột bằng vi/en, hỗ trợ chính sách commit một phần (mặc định tất cả hoặc không), idempotent (upsert theo khóa nghiệp vụ), và hoàn tác được (rollback một lô trong vòng 24h nếu chưa có giao dịch phụ thuộc). | M | |
| BR-SAAS-071 | **Import số dư đầu kỳ cho go-live giữa năm** (thay thế việc chuyển đổi dữ liệu từ Oracle cũ). Mọi tenant phải nạp được trạng thái đầu kỳ từ các mẫu BR-SAAS-069 qua import engine của Core (chế độ số dư đầu kỳ BR-CORE-114: nạp hàng loạt, bỏ qua workflow, giữ ID tham chiếu bên ngoài để truy vết), theo thứ tự phụ thuộc của P9, để run lương đầu tiên, thuế TNCN lũy kế hằng tháng, khai báo BHXH, tích lũy phép và thu hồi khoản vay tiếp tục đúng từ tháng go-live. Không truy cập database cũ, không pipeline ETL hay vùng staging. (changed 2026-10-16, D-09) **Thuộc tính tùy chỉnh** (changed 2026-10-16, R3-09): vì trường tùy chỉnh thuộc Phase 2 (BR-CORE-086), mọi cột riêng của tenant trong file số dư đầu kỳ được ánh xạ, trong quá trình kiểm tra mẫu, tới một trường chuẩn hoặc tới một tài liệu đính kèm trên hồ sơ nhân viên. Việc ánh xạ được thống nhất với khách hàng và ghi lại trong báo cáo kiểm tra; các cột chưa được ánh xạ sẽ chặn commit. | M | P9 |
| BR-SAAS-072 | Báo cáo **đối chiếu số dư đầu kỳ** so sánh các giá trị đã import với **tổng kiểm soát do tenant cung cấp** (bảng lương gần nhất của hệ thống cũ, thông báo C12-TS của VSS, 05/KK-TNCN đã nộp, báo cáo phép), theo từng nhân viên và tổng cộng, cho: tổng thu nhập gross và net lũy kế từ đầu năm, thu nhập tính thuế TNCN lũy kế, thuế TNCN đã khấu trừ lũy kế, người phụ thuộc đã đăng ký, mức lương đóng BHXH/BHYT/BHTN và số tháng đã đóng, số ngày phép được hưởng/đã nghỉ/còn lại (gồm phép chuyển sang), khoản vay/tạm ứng còn dư nợ. Go-live yêu cầu không còn chênh lệch chưa giải trình và có ký xác nhận của người phụ trách HR/C&B và tài chính của tenant. (changed 2026-10-16, D-09) | M | Quan trọng để quyết toán thuế TNCN năm chính xác |
| BR-SAAS-073 | *Tùy chọn* **so sánh bảng lương**: tenant có thể import bảng lương gần nhất của hệ thống cũ (Excel, theo nhân viên và khoản mục) và tính lại tháng đó trong YourHRM từ dữ liệu đầu vào đã import, kèm báo cáo chênh lệch theo nhân viên và khoản mục. Việc này thay thế yêu cầu bắt buộc chạy song song. (changed 2026-10-16, D-09) | S | Phase 2 theo mvp-scope.md; sponsor cần xác nhận lại có cần làm bước kiểm tra an toàn trước go-live hay không |
| BR-SAAS-074 | Trình hướng dẫn onboarding cho tenant mới: công ty & pháp nhân → cơ cấu tổ chức → chính sách (lịch làm việc, nghỉ phép, chu kỳ lương) → import nhân viên → mời người dùng → checklist go-live với % tiến độ. | S | |
| BR-SAAS-088 | **Chuyển đổi go-live và khóa số dư đầu kỳ.** Mỗi pháp nhân có một tháng go-live, và số dư đầu kỳ của pháp nhân được xác định "tại thời điểm cuối tháng trước". Khi run lương thường kỳ đầu tiên của pháp nhân được phê duyệt, số dư đầu kỳ bị khóa. Sau khi khóa, chỉ có thể thay đổi qua một **lô điều chỉnh** có audit kèm lý do. Lô này cập nhật các sổ lũy kế từ đầu năm, lịch sử BHXH, phép và khoản vay, và được Payroll và T&A tiếp nhận như dữ liệu đầu vào truy lĩnh hoặc điều chỉnh. (new 2026-10-16, D-09) | M | Ngăn việc âm thầm thay đổi lịch sử thuế TNCN/BHXH sau go-live |
| BR-SAAS-089 | **Lịch sử năm tính thuế trước go-live cho thuế TNCN.** Với go-live sau tháng 1, việc import phải ghi nhận, theo từng nhân viên, pháp nhân và từng tháng của năm tính thuế trước go-live: thu nhập chịu thuế, thu nhập miễn thuế, bảo hiểm bắt buộc đã trừ, giảm trừ gia cảnh đã áp dụng, và thuế TNCN đã khấu trừ. Payroll dùng các tháng này cho thuế TNCN lũy kế hằng tháng (BR-PAY-052), quyết toán cuối năm và các phụ lục 05/QTT-TNCN (BR-PAY-057), và chứng từ khấu trừ điện tử cả năm (BR-PAY-059). Nếu thiếu bất kỳ tháng nào, việc import chặn go-live của pháp nhân đó. (new 2026-10-16, D-09) | M | Khi không chuyển đổi dữ liệu, đây là nguồn duy nhất cho các tháng trước go-live phục vụ quyết toán |
| BR-SAAS-090 | **Job scheduler / queue.** Nền tảng phải cung cấp một job scheduler và queue nhận biết tenant cho mọi module: job theo lịch và theo yêu cầu (Core kích hoạt theo ngày hiệu lực lúc 00:00 ICT, bộ đếm SLA, import; T&A xử lý dữ liệu chấm công, tích lũy, xử lý lại hằng đêm; Payroll chạy run, công bố phiếu lương, đối chiếu; People thay đổi hàng loạt; Recruitment lưu giữ dữ liệu), hạn mức theo tenant (NFR-SAAS-005), tạm ngưng hoặc giới hạn tải theo trạng thái tenant (BR-SAAS-001), thử lại với idempotency key, và API tiến độ / trạng thái. (new 2026-10-16, R2-02 §5 #16) | M | Được Core C-24, T&A ATT-C-11, Payroll IF-PAY-C-32 (PR-17) sử dụng. Phase theo mvp-scope.md (PM đồng bộ). |
| BR-SAAS-091 | **Năng lực nền tảng luôn bật.** Bất kể tenant đăng ký module nào, entitlement service phải luôn bật: **Core**; **People cơ bản** (cá nhân, quan hệ lao động, phân công, hợp đồng, bản ghi lương, người phụ thuộc, tài khoản ngân hàng, hồ sơ thuế/BHXH); **tham số pháp lý** (Legal Parameter Service, gồm mẫu ngày lễ quốc gia); **thang bảng lương / khung lương theo ngạch** và danh mục thành phần (Payroll IF-PAY-P-02); và **import chấm công Excel** tạo ra Attendance Result chuẩn (BR-ATT-025). Các năng lực này không được tính phí như module riêng. Ma trận phụ thuộc (module → phụ thuộc nền tảng và phụ thuộc tùy chọn) được công bố trong danh mục gói. (new 2026-10-16, R2-18) | M | Quyết định R2-18 (GAP-060). |

### 4.H Kênh mobile / ESS

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-075 | **ESS web responsive** (trình duyệt mobile) cho: xem/cập nhật hồ sơ (có phê duyệt), phiếu lương & chứng từ thuế TNCN, đơn nghỉ phép/số dư phép, xem chấm công & yêu cầu điều chỉnh, hộp thư phê duyệt cho quản lý, tài liệu, thông báo, quản lý sự đồng ý. | M | Kênh ESS của Phase 1. Không check-in từ ESS ở Phase 1: dữ liệu chấm công thí điểm lấy từ thiết bị chấm công và import Excel (changed 2026-10-16, OD-9) |
| BR-SAAS-076 | **Ứng dụng mobile native** (iOS & Android, một ứng dụng multi-tenant duy nhất; tenant được xác định khi đăng nhập qua mã công ty hoặc tên miền email) cung cấp các tính năng ESS cùng check-in GPS/Wi-Fi/QR có geofence, selfie (có sự đồng ý), push notification, mở khóa sinh trắc học. | S | Phase 2, gồm **mọi** hình thức check-in qua mobile, GPS, Wi-Fi, QR, selfie và PWA (changed 2026-10-16, OD-9/X-04); bản build white-label tùy chọn C |
| BR-SAAS-077 | Phiếu lương trên mobile phải yêu cầu xác thực lại (PIN/sinh trắc học) và không được cache dạng không mã hóa trên thiết bị; hạn chế chụp màn hình bằng cảnh báo (Android FLAG_SECURE tùy chọn theo tenant). | S | |
| BR-SAAS-078 | Ứng dụng mobile phải hỗ trợ ghi nhận check-in offline với thời gian thiết bị tin cậy và đồng bộ sau, được gắn cờ offline cho các quy tắc T&A. | S | |

### 4.I Tuân thủ, quyền riêng tư & bảo mật

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| BR-SAAS-079 | Nền tảng phải duy trì **danh mục dữ liệu cá nhân** (bản đồ dữ liệu) duy nhất cho mọi module (R2-02 §5 #27): với mỗi thực thể/trường — loại (cơ bản / nhạy cảm), mục đích, căn cứ pháp lý, thời hạn lưu trữ, module sở hữu, (các) bên xử lý. Các module đăng ký trường của mình; danh mục xuất được để làm DPIA. | M | Luật BVDLCN 2025 (Luật 91/2025/QH15) & nghị định hướng dẫn; Nghị định 13/2023/ND-CP `[verify-legal]` |
| BR-SAAS-080 | **Quản lý sự đồng ý** — sổ đăng ký sự đồng ý duy nhất cho nhân viên, người sắp nhận việc, nhân viên cũ và ứng viên (chủ thể = person id hoặc candidate id); People và Recruitment chỉ giữ tham chiếu (changed 2026-10-16, R2-02 §5 #27): nội dung đồng ý/thông báo có phiên bản theo mục đích (ví dụ check-in sinh trắc học, selfie, GPS, tiếp thị, chia sẻ với công ty bảo hiểm), ghi nhận sự đồng ý kèm thời điểm/kênh/phiên bản, rút lại bất kỳ lúc nào trên ESS, và API để các module kiểm tra sự đồng ý trước khi xử lý. | M | |
| BR-SAAS-081 | **Công cụ DSR** theo P12: log yêu cầu, bước xác minh danh tính, theo dõi thời hạn kèm nhắc nhở, xuất dữ liệu cá nhân của một chủ thể dữ liệu trên mọi module bằng một thao tác, xóa/ẩn danh hóa tuân thủ thời hạn lưu trữ luật định, bằng chứng hoàn thành. | M | Thời hạn `[verify-legal]` |
| BR-SAAS-082 | **Chính sách lưu trữ** (bộ lập lịch lưu trữ duy nhất cho mọi module; các lớp lưu trữ tài liệu của Core là khóa tham chiếu vào nó — R2-02 §5 #27) cấu hình được theo loại dữ liệu, với mức tối thiểu luật định được thực thi như mức sàn (ví dụ hồ sơ lương/kế toán, hồ sơ BHXH, hợp đồng lao động); job xóa/ẩn danh hóa tự động có báo cáo; ứng viên không được tuyển bị xóa sau khoảng thời gian cấu hình được trừ khi đồng ý cho lưu giữ. | M | Thời hạn lưu trữ theo Luật Kế toán 2015; các thời hạn khác `[verify-legal]` |
| BR-SAAS-083 | Nhà cung cấp phải duy trì **hồ sơ DPIA** của mình (với vai trò bên xử lý) và cung cấp cho mỗi tenant một mẫu + đầu vào từ nền tảng cho hồ sơ DPIA của chính tenant; cập nhật khi có thay đổi trọng yếu. | M | `[verify-legal]` |
| BR-SAAS-084 | **Thông báo vi phạm** theo P11: thông báo cho các tenant bị ảnh hưởng ≤ 24h sau khi xác nhận; thông báo cho cơ quan chức năng trong vòng **72h** khi bắt buộc; lưu giữ mẫu và bằng chứng. | M | `[verify-legal]` |
| BR-SAAS-085 | **Audit**: nền tảng phải **ghi** các sự kiện bảo mật của mình (đăng nhập, MFA, cấu hình SSO, API key) và sự kiện truy cập của nhà vận hành vào **dịch vụ và store audit của Core** (audit store tenant duy nhất, chỉ ghi thêm, hash chain — BR-CORE-090–094, P-27); chỉ các sự kiện của nhà vận hành ở mức nền tảng không gắn với tenant mới được giữ trong `saas.audit_event`. Mức sàn lưu trữ theo loại được đặt trong Core (bảo mật ≥ 2 năm; lương/kế toán ≥ 10 năm `[verify-legal]`); Tenant Admin tìm kiếm được cho tenant của mình (changed 2026-10-16, R2-02 §5 #13). | M | |
| BR-SAAS-086 | Nhà cung cấp phải vận hành ISMS phù hợp với **ISO/IEC 27001:2022** và các kiểm soát ánh xạ được tới **SOC 2 Type II** (bảo mật, tính sẵn sàng, tính bảo mật thông tin), tự động hóa thu thập bằng chứng khi có thể, mục tiêu đạt chứng nhận trong vòng 18 tháng kể từ GA. | S | |
| BR-SAAS-087 | Nhà cung cấp phải đạt/duy trì việc phân loại cấp độ an toàn hệ thống thông tin cần thiết cho nền tảng theo quy định an toàn thông tin của VN. | S | Nghị định 85/2016/ND-CP `[verify-legal]` |

---

## 5. Quy định tuân thủ của Việt Nam

| # | Quy định | Áp dụng cho | Căn cứ pháp lý `[verify-legal]` |
|---|---|---|---|
| C1 | Doanh nghiệp trong nước cung cấp dịch vụ trực tuyến có thu thập dữ liệu cá nhân của người dùng tại VN phải lưu trữ dữ liệu đó tại Việt Nam; áp dụng thời hạn lưu trữ tối thiểu với một số dữ liệu/log. YourHRM lưu trữ toàn bộ dữ liệu tenant tại các trung tâm dữ liệu ở VN. | BR-SAAS-024 | Luật An ninh mạng 2018 (24/2018/QH14) Điều 26; Nghị định 53/2022/ND-CP Điều 26–27; kiểm tra thay đổi theo Luật An ninh mạng 2025 mới |
| C2 | Xử lý dữ liệu cá nhân cần có căn cứ pháp lý (sự đồng ý hoặc ngoại lệ theo luật, ví dụ thực hiện hợp đồng lao động, nghĩa vụ luật định); dữ liệu nhạy cảm (sức khỏe, sinh trắc học, tài chính, vị trí, v.v.) cần xử lý chặt chẽ hơn. | BR-SAAS-034, 079, 080 | Luật Bảo vệ dữ liệu cá nhân 2025 (91/2025/QH15, hiệu lực 01/01/2026); nghị định hướng dẫn; Nghị định 13/2023/ND-CP cho các vấn đề chuyển tiếp |
| C3 | Nghĩa vụ của bên kiểm soát/bên xử lý: DPA giữa tenant (bên kiểm soát) và nhà cung cấp (bên xử lý); hồ sơ đánh giá tác động; đánh giá tác động chuyển dữ liệu xuyên biên giới cho mọi việc chuyển ra nước ngoài. | BR-SAAS-024, 083 | Luật BVDLCN 2025 |
| C4 | Vi phạm dữ liệu cá nhân phải được thông báo cho cơ quan có thẩm quyền (Bộ Công an – A05) trong vòng 72h kể từ khi phát hiện. | BR-SAAS-084 | Luật BVDLCN 2025; Nghị định 13/2023 Điều 23 |
| C5 | Quyền của chủ thể dữ liệu (truy cập, chỉnh sửa, xóa, hạn chế, phản đối, rút lại sự đồng ý) được xử lý trong thời hạn luật định. | BR-SAAS-081 | Luật BVDLCN 2025 |
| C6 | Hóa đơn điện tử cho bán dịch vụ: nội dung, đánh số, thời điểm lập, mã của cơ quan thuế, điều chỉnh qua hóa đơn điều chỉnh/thay thế; lưu trữ. | BR-SAAS-014, 015 | Nghị định 123/2020/ND-CP; Nghị định 70/2025/ND-CP; Thông tư 78/2021/TT-BTC; Thông tư 32/2025/TT-BTC |
| C7 | Chứng từ khấu trừ thuế TNCN điện tử do người sử dụng lao động cấp cho người lao động. | BR-SAAS-062 | Nghị định 123/2020 Điều 30–33 được sửa đổi bởi Nghị định 70/2025 |
| C8 | Khai thuế điện tử theo định dạng XML quy định. | BR-SAAS-062 | Luật Quản lý thuế 38/2019/QH14; Thông tư 80/2021/TT-BTC (và các văn bản sửa đổi) |
| C9 | Khai báo điện tử BHXH/BHYT/BHTN qua VSS và các nhà cung cấp I-VAN được cấp phép. | BR-SAAS-061 | Luật BHXH 2024 (41/2024/QH15, hiệu lực 01/07/2025); các quyết định về thủ tục của VSS |
| C10 | Hợp đồng lao động điện tử có giá trị pháp lý như hợp đồng bằng văn bản; yêu cầu đối với chữ ký điện tử và nền tảng hợp đồng điện tử. | BR-SAAS-063 | Bộ luật Lao động 2019 Điều 14; Luật Giao dịch điện tử 2023 (20/2023/QH15); Nghị định về chữ ký số (23/2025/ND-CP); nghị định về hợp đồng lao động điện tử |
| C11 | Định danh điện tử qua VNeID; bên thứ ba chỉ được sử dụng cơ sở dữ liệu quốc gia về dân cư qua các kênh được ủy quyền. | BR-SAAS-064 | Nghị định 69/2024/ND-CP; Luật Căn cước 2023 |
| C12 | Chứng từ kế toán (gồm hồ sơ lương và hóa đơn thuê bao) được lưu trữ theo luật kế toán (thông thường ≥ 10 năm với chứng từ dùng để ghi sổ). | BR-SAAS-019, 082 | Luật Kế toán 2015 (88/2015/QH13); Nghị định 174/2016/ND-CP |
| C13 | Hệ thống thông tin xử lý dữ liệu cá nhân phải được phân loại và bảo vệ theo cấp độ an toàn. | BR-SAAS-087 | Luật An toàn thông tin mạng 2015; Nghị định 85/2016/ND-CP |
| C14 | SMS brandname / tin nhắn quảng cáo cần đăng ký và có sự đồng ý của người nhận; tin nhắn HR mang tính giao dịch không được chứa nội dung quảng cáo. | BR-SAAS-066 | Nghị định 91/2020/ND-CP về chống tin nhắn rác `[verify-legal]` |

---

## 6. Thực thể nghiệp vụ chính

> Các thực thể nền tảng nằm trong một schema cấp nền tảng (đề xuất `saas`, xem Q1) và **không** được giới hạn theo tenant bằng RLS, trừ những thực thể được đánh dấu (T).

| Thực thể | Mục đích | Thuộc tính chính |
|---|---|---|
| Tenant | Tổ chức khách hàng đăng ký thuê bao | tenant_id, slug, tên, trạng thái, tier (dùng chung/riêng), khu vực (VN), locale mặc định, múi giờ, ngày tạo/kích hoạt/chấm dứt, vị trí kho dữ liệu, tham chiếu khóa mã hóa |
| Bên nhận hóa đơn (trước đây là "Legal Entity") | Bên nhận hóa đơn; bản thân các pháp nhân được quản lý gốc trong `core.legal_entity` (changed 2026-10-16, R2-02 §5 #23) | id, tenant_id, MST người mua, tên, địa chỉ, định danh hóa đơn điện tử, các id pháp nhân core được liên kết, trạng thái |
| Gói / Phiên bản (có phiên bản) | Gói thương mại | mã, phiên bản, module, tính năng, giới hạn, tham chiếu bảng giá, hiệu lực từ/đến |
| Bảng giá | Giá theo module/add-on | tiền tệ, đơn vị (PEPM/cố định/theo sử dụng), bậc, phí tối thiểu, loại VAT |
| Thuê bao | Hợp đồng của tenant | tenant_id, phiên bản gói, chu kỳ tính phí, bắt đầu/kết thúc, số suất cam kết, chiết khấu, tự động gia hạn, trạng thái |
| Dòng thuê bao | Dòng module/add-on | subscription_id, module/add-on, cơ sở số lượng, bắt đầu/kết thúc, ghi đè giá |
| Entitlement | Khả năng sử dụng tính năng đã được xác định | tenant_id, legal_entity_id?, feature_key, đã bật, giá trị giới hạn, nguồn (gói/add-on/ghi đè), hiệu lực từ/đến |
| Feature Flag | Kiểm soát phát hành / thử nghiệm | khóa, loại (release/ops/permission/experiment), quy tắc theo phạm vi, mặc định, người phụ trách, ngày hết hạn |
| Bản ghi sử dụng | Đo lường | tenant_id, chỉ số, chiều (module, pháp nhân), ngày, số lượng, nguồn |
| Hóa đơn / Dòng hóa đơn | Tính phí thuê bao | số, mã hóa đơn điện tử & mã tra cứu, ngày lập, kỳ, các dòng, VAT, tổng VND, trạng thái, liên kết thay thế/điều chỉnh |
| Thanh toán | Tất toán | invoice_id, phương thức, tham chiếu, số tiền, ngày, trạng thái đối soát |
| Quyền cấp hỗ trợ | Truy cập của nhà vận hành dựa trên sự đồng ý | tenant_id, nhà vận hành, ticket, phạm vi, cờ nhạy cảm, approved_by, hiệu lực từ/đến, trạng thái |
| Phiên nhà vận hành | Log truy cập của nhà vận hành | grant_id, nhà vận hành, người dùng bị mạo danh, bắt đầu/kết thúc, tham chiếu thao tác |
| Cấu hình Identity Provider (T) | Thiết lập SSO | giao thức, metadata/issuer, client id, chứng chỉ, ánh xạ claim, nhóm bắt buộc |
| Thông tin xác thực người dùng / Yếu tố MFA (T) | Xác thực | user_id, loại yếu tố, tham chiếu secret, lần dùng gần nhất, hash mã khôi phục |
| API Client (T) | API key / OAuth client | client_id, tên, phạm vi, hash secret, hạn dùng, người sở hữu, gói giới hạn tần suất, lần dùng gần nhất |
| Đăng ký webhook (T) | Gửi sự kiện | sự kiện, URL, secret, trạng thái, log gửi |
| Cấu hình connector tích hợp (T) | Integration hub | loại connector (MISA, ngân hàng X, VSS I-VAN…), tham chiếu thông tin xác thực, ánh xạ, lịch, trạng thái |
| Định nghĩa trường tùy chỉnh (T) | Đã bỏ — thuộc core (R2-02): `core.custom_field_def` (changed 2026-10-16, R2-02 §5 #11) | — |
| Định nghĩa / Lịch báo cáo (T) | Báo cáo | nguồn (chuẩn/công cụ thiết kế), người sở hữu, tham số, chia sẻ, lịch, kênh gửi |
| Gói tham số pháp lý | Giá trị luật định cho mọi tenant — dữ liệu gốc của Legal Parameter Service (R2-02 §5 #1) | phiên bản, ngày hiệu lực, tham số (khóa có kiểu, giá trị / bảng như bậc thuế và bản đồ đơn vị hành chính → vùng, đơn vị, cờ "luật cho phép ghi đè"), căn cứ pháp lý, phê duyệt, ngày phát hành |
| Ghi đè tham số pháp lý (T) | Ghi đè theo tenant / pháp nhân khi luật cho phép (R2-02 §5 #1) | khóa, phạm vi (tenant / pháp nhân), giá trị, hiệu lực từ/đến, minh chứng, phê duyệt |
| Job theo lịch (T) | Job scheduler / queue (new 2026-10-16, R2-02 §5 #16) | tenant_id, module, loại job, lịch, trạng thái, tiến độ, số lần thử, idempotency key |
| Mẫu theo luật định | Bố cục/ánh xạ biểu mẫu nhà nước | mã biểu mẫu, phiên bản, ngày hiệu lực, định dạng đầu ra, ánh xạ |
| Lô import (T) | Tham chiếu tới `import_job` của Core cho một lô số dư đầu kỳ (bản thân lô nằm trong import engine của Core — R2-02 §5 #12) | id import_job của core, mẫu, tham chiếu chuyển đổi, chế độ (validate/commit/adjustment), trạng thái |
| Chuyển đổi số dư đầu kỳ (T) | Thời điểm go-live theo pháp nhân (BR-SAAS-088) | pháp nhân, tháng go-live, ngày chốt số liệu, trạng thái (open/locked), run lương thực hiện khóa, các lô điều chỉnh (new 2026-10-16, D-09) |
| Lần chạy đối chiếu (T) | Kiểm tra số dư đầu kỳ | phạm vi, tổng kiểm soát do tenant cung cấp, tổng đã import, chênh lệch, ký xác nhận (changed 2026-10-16, D-09) |
| Bản ghi sự đồng ý (T) | Sự đồng ý theo BVDLCN | chủ thể dữ liệu, mục đích, phiên bản thông báo, thời điểm đồng ý/rút lại, kênh |
| Yêu cầu của chủ thể dữ liệu (T) | DSR | người yêu cầu, loại, ngày nhận, hạn xử lý, xác minh, trạng thái, bằng chứng |
| Chính sách lưu trữ (T) | Vòng đời dữ liệu | loại dữ liệu, thời hạn lưu trữ, mức sàn luật định, hành động (xóa/ẩn danh hóa), lần chạy gần nhất |
| Sự cố bảo mật / Vi phạm | Quản lý sự cố | mức nghiêm trọng, các tenant bị ảnh hưởng, loại dữ liệu, dòng thời gian, các thông báo đã gửi |
| Sự kiện audit (`saas.audit_event`) | Chỉ các sự kiện của nhà vận hành ở mức nền tảng không gắn với tenant; mọi sự kiện audit của tenant được đưa vào audit store của Core (changed 2026-10-16, R2-02 §5 #13) | người thực hiện (nhà vận hành), thao tác, đối tượng, hash trước/sau, thời điểm, IP, chain hash |

---

## 7. Giao diện liên module

### 7.1 Cung cấp (nền tảng → module)

| # | Nội dung | Tới (các) module | Thời điểm / cơ chế |
|---|---|---|---|
| PR-01 | **Tenant context** (tenant_id, pháp nhân, locale, múi giờ) trên mọi request, job và sự kiện; biến phiên DB cho RLS | core, people, time-attendance, payroll, recruitment | Mọi request / job (đồng bộ) |
| PR-02 | **Kiểm tra entitlement & feature flag** (module, tính năng, giới hạn) | mọi module | Mỗi lần render UI, gọi API, chạy job theo lịch |
| PR-03 | **Danh tính đã xác thực** (user id, phương thức xác thực, mức MFA, SSO claim) và phiên; xác thực tăng cường theo yêu cầu cho thao tác nhạy cảm (duyệt lương, phát hành file ngân hàng) | core (ánh xạ danh tính → vai trò/quyền), mọi module | Đăng nhập / mỗi request |
| PR-04 | **Thiết lập tenant & bản địa hóa** (resource bundle vi/en, định dạng, lịch, tiền tệ) | mọi module | Runtime |
| PR-05 | Đã bỏ — thuộc core (R2-02). Framework trường tùy chỉnh là Core P-19 / BR-CORE-086 (changed 2026-10-16, R2-02 §5 #11) | — | — |
| PR-06 | **Legal Parameter Service** (chủ sở hữu duy nhất): các giá trị luật định có hiệu lực theo ngày — mức lương tối thiểu và bản đồ đơn vị hành chính → vùng, tỷ lệ & mức trần BHXH/BHYT/BHTN/TNLĐ-BNN, mức tham chiếu, bậc thuế TNCN & giảm trừ, ngưỡng khấu trừ 10% thu nhập vãng lai, giới hạn làm thêm giờ, khung giờ đêm, số ngày phép được hưởng, giới hạn thử việc/báo trước, lộ trình tuổi nghỉ hưu, thời hạn quyết toán, mẫu ngày lễ quốc gia, và tham số pháp lý `insurance.multi_contract_rule` cho từng loại bảo hiểm (BHXH, BHYT, BHTN, TNLĐ-BNN), với giá trị `FIRST_CONTRACT` hoặc `HIGHEST_SALARY` hoặc `EACH_EMPLOYER` (Legal Parameter Service, 06 PR-06). Mặc định: BHXH, BHTN và BHYT = `FIRST_CONTRACT` (quyết định của sponsor R3-03), TNLĐ-BNN = `EACH_EMPLOYER`; tất cả `[verify-legal]` chờ chuyên gia tư vấn lao động (changed 2026-10-16, R3-03); API đọc `getParam(key, scope, asOfDate)` trả về giá trị + phiên bản gói; `saas.LegalParameterPackPublished`; ghi đè theo tenant/pháp nhân chỉ khi được gắn cờ "luật cho phép". Bên tiêu thụ chỉ có quyền đọc | payroll, time-attendance, people, recruitment, core (xác định vùng) | Khi phát hành + truy vấn đồng bộ theo ngày hiệu lực (changed 2026-10-16, R2-02 §5 #1) |
| PR-07 | **Framework mẫu theo luật định** (bố cục/định dạng đầu ra của biểu mẫu nhà nước có phiên bản) | payroll (BHXH, thuế TNCN), people (báo cáo lao động), time-attendance (báo cáo làm thêm giờ) | Khi phát hành |
| PR-08 | **Framework báo cáo & reporting store** (danh mục, mọi bản xuất gồm xuất từ màn hình danh sách, Excel/PDF/CSV, lập lịch, xuất bất đồng bộ, che dữ liệu, framework mẫu theo luật định); đồng thời nhận dữ liệu số nhân sự từ People (OUT-34) và kết quả lương / chi phí thực tế từ Payroll (IF-PAY-P-03/16) | mọi module | Module đăng ký báo cáo; runtime (changed 2026-10-16, R2-02 §5 #18) |
| PR-09 | **Integration hub & connector**: xuất bút toán kế toán, file/API chi trả ngân hàng, VSS I-VAN, eTax/chứng từ thuế TNCN điện tử, connector chữ ký điện tử (do Core điều phối), VNeID, thiết bị chấm công, trang tuyển dụng. Phase 1 chỉ giữ e-mail và xuất file; connector trực tiếp thuộc Phase 2 (R2-01) | payroll (kế toán, ngân hàng, eTax, VSS), core (điều phối chữ ký điện tử), people (VNeID), time-attendance (thiết bị), recruitment (trang tuyển dụng) | Khi module kích hoạt / theo lịch (changed 2026-10-16, R2-02 §5 #15b) |
| PR-10 | **Channel delivery adapter** (email SMTP/DKIM; push, SMS, Zalo/ZNS ở Phase 2) kèm tài khoản nhà cung cấp và trạng thái gửi | core (trung tâm thông báo soạn và áp dụng phương án dự phòng; nền tảng gửi); các module chỉ gọi Core | Theo sự kiện (changed 2026-10-16, R2-02 §5 #14) |
| PR-11 | **API gateway công khai & gửi webhook** (xác thực, giới hạn tần suất, quản lý phiên bản, phân phối sự kiện) | mọi module công bố tài nguyên/sự kiện qua nó | Runtime |
| PR-12 | **Onboarding số dư đầu kỳ** dựa trên **import engine của Core** (BR-CORE-110–114): danh mục mẫu, kế hoạch chuyển đổi và khóa (BR-SAAS-088), lô điều chỉnh, lần chạy đối chiếu (BR-SAAS-072). Bản thân engine (validate/commit, báo cáo lỗi, rollback) là Core P-22; mỗi mẫu là một import type do module của nó sở hữu | people (nhân viên, hợp đồng), time-attendance (số dư phép, giờ làm thêm lũy kế), payroll (thu nhập/thuế TNCN lũy kế hằng tháng, lịch sử BHXH, khoản vay), recruitment (ứng viên, P2), core (tổ chức, vị trí, người dùng) | Onboarding / go-live / theo nhu cầu (changed 2026-10-16, D-09; R2-02 §5 #12) |
| PR-13 | **Dịch vụ quyền riêng tư** (chủ sở hữu duy nhất): sổ đăng ký sự đồng ý và API kiểm tra, danh mục dữ liệu cá nhân, điều phối DSR (yêu cầu xuất/ẩn danh hóa tới từng module), bộ lập lịch lưu trữ với mức sàn luật định | mọi module | DSR / theo lịch (changed 2026-10-16, R2-02 §5 #27) |
| PR-14 | Đã bỏ — thuộc core (R2-02). Audit store chống giả mạo là Core P-20; nền tảng ghi các sự kiện định danh và nhà vận hành vào đó (Core P-27) (changed 2026-10-16, R2-02 §5 #13) | — | — |
| PR-15 | **Shell mobile/ESS** (container ứng dụng, xác thực, push, đồng bộ offline, ghi nhận check-in) | people, time-attendance, payroll, core (phê duyệt) | Runtime. Phase 2; Phase 1 chỉ cung cấp ESS web responsive (changed 2026-10-16, OD-9) |
| PR-16 | **Hạ tầng object storage** (lưu trú tại VN, prefix theo tenant, mã hóa/KMS, quét virus, URL ký có thời hạn ngắn) phía sau dịch vụ tài liệu của Core; các module chỉ gọi Core P-21 | core (dịch vụ tài liệu) | Runtime (changed 2026-10-16, R2-02 §5 #15) |
| PR-17 | **Job scheduler / queue** (BR-SAAS-090) | core, people, time-attendance, payroll, recruitment | Runtime (new 2026-10-16, R2-02 §5 #16) |
| PR-18 | **Entitlement nền tảng** luôn bật (BR-SAAS-091) và ma trận phụ thuộc module | mọi module | Runtime (new 2026-10-16, R2-18) |

### 7.2 Tiêu thụ (module → nền tảng)

| # | Nội dung | Từ module | Thời điểm / cơ chế |
|---|---|---|---|
| CO-01 | Người dùng, vai trò, mô hình quyền & phạm vi dữ liệu (để thực thi phân quyền trong báo cáo, phạm vi API, che dữ liệu khi cấp quyền hỗ trợ) | core | Runtime |
| CO-02 | Pháp nhân (`core.LegalEntityChanged`, API tạo pháp nhân dùng khi khởi tạo), cây tổ chức, trung tâm chi phí (liên kết bên nhận hóa đơn, bộ lọc báo cáo, ánh xạ kế toán) | core | Khi thay đổi (sự kiện) (changed 2026-10-16, R2-02 §5 #23) |
| CO-03 | Yêu cầu gửi thông báo từ trung tâm thông báo của Core (để gửi qua các kênh và trả về trạng thái); API ghi audit của audit store Core (để ghi sự kiện định danh và nhà vận hành) | core | Theo sự kiện (changed 2026-10-16, R2-02 §5 #13, #14) |
| CO-04 | Quyết định workflow/phê duyệt cho các quy trình nền tảng (duyệt quyền cấp hỗ trợ, duyệt DSR, duyệt commit import) | core | Theo sự kiện |
| CO-05 | **Số người đang làm việc theo pháp nhân vào cuối tháng** (số nhân viên tính phí, BR-SAAS-012) | people | Snapshot cuối tháng (hằng ngày để tham khảo) (changed 2026-10-16, D-10) |
| CO-06 | `core.UserStatusChanged` (ACTIVE / FORMER_EMPLOYEE / DEACTIVATED) cho thông tin xác thực, thu hồi phiên và SCIM; sự kiện vòng đời People chỉ dùng cho webhook | core (trạng thái người dùng); people (webhook) | Theo sự kiện (changed 2026-10-16, R2-02 §5 #9) |
| CO-07 | Đăng ký trường dữ liệu cá nhân và handler xuất/ẩn danh hóa theo chủ thể | people, time-attendance, payroll, recruitment, core | Thời điểm thiết kế + runtime khi xử lý DSR |
| CO-08 | Hợp đồng/quyết định cần ký điện tử; bên tiêu thụ callback kết quả ký | people | Theo yêu cầu |
| CO-09 | Ánh xạ chấm công/thiết bị (nhân viên ↔ id đăng ký trên thiết bị), kết quả chấp nhận dữ liệu chấm công | time-attendance | Khi đồng bộ thiết bị |
| CO-10 | Kết quả lương đã duyệt: bút toán lương (các dòng GL), lệnh chi trả ngân hàng, dữ liệu khấu trừ thuế TNCN, dữ liệu khai báo BHXH | payroll | Khi duyệt lương / phát hành chi trả |
| CO-11 | Khối lượng run lương (số nhân viên đã xử lý) để giám sát hiệu năng và đo lường module payroll | payroll | Theo từng run |
| CO-12 | Tin tuyển dụng cần đăng, hồ sơ ứng viên & sự đồng ý của ứng viên | recruitment | Khi đăng tin / khi ứng tuyển |
| CO-13 | Báo cáo chuẩn do module định nghĩa, ánh xạ mẫu theo luật định, schema sự kiện webhook và mẫu import | mọi module | Thời điểm thiết kế (đăng ký theo từng bản phát hành) |
| CO-14 | Quy tắc lưu trữ của module (mức sàn luật định theo loại dữ liệu) | people, payroll, time-attendance, recruitment, core | Thời điểm thiết kế |
| CO-15 | Admin API cho dữ liệu danh mục toàn cục (nạp bản phát hành có phiên bản, `core.MasterDataReleased`) | core | Khi nhà vận hành công bố (new 2026-10-16, R2-02 §5 #28) |
| CO-16 | Tài khoản người dùng tenant và ánh xạ phạm vi API → quyền (để xác định một danh tính ứng với người dùng tenant nào) | core | Runtime (new 2026-10-16, R2-02 §5 #10) |

---

## 8. Báo cáo & phân tích

### 8.1 Báo cáo nền tảng/nhà vận hành (back-office)
| Báo cáo | Đối tượng | Nội dung |
|---|---|---|
| Danh mục tenant | Ban quản lý nhà cung cấp | Tenant theo trạng thái, gói, tier, MRR/ARR, nhân viên tính phí, tỷ lệ rời bỏ, tỷ lệ chuyển đổi dùng thử |
| Sử dụng & đo lường | Tính phí | Theo tenant theo chỉ số theo ngày; cảnh báo vượt giới hạn |
| Tính phí & tuổi nợ phải thu | Tính phí | Hóa đơn đã xuất, đã thanh toán, nhóm quá hạn, bước nhắc nợ |
| Báo cáo truy cập của nhà vận hành | Bảo mật | Quyền cấp, phiên, mạo danh, sự kiện break-glass |
| Báo cáo SLA nền tảng | Ban quản lý nhà cung cấp, tenant (Enterprise) | Tính sẵn sàng hằng tháng, sự cố, kết quả kiểm thử RPO/RTO, phân vị hiệu năng |
| Mức áp dụng gói pháp lý | Chuyên gia pháp lý | Các phiên bản gói đã phát hành, số tenant trên mỗi phiên bản, số run lương theo gói |
| Tình trạng tích hợp | Hỗ trợ | Lỗi connector, dead letter của webhook, tỷ lệ lỗi API theo tenant |

### 8.2 Báo cáo nền tảng dành cho tenant
| Báo cáo | Đối tượng | Nội dung |
|---|---|---|
| Thuê bao & hóa đơn | Chủ tài khoản tenant / Tài chính | Gói hiện tại, module, xu hướng nhân viên tính phí, hóa đơn & link hóa đơn điện tử |
| Bảo mật & hoạt động đăng nhập | Tenant Admin | Đăng nhập, thất bại, mức áp dụng MFA, sử dụng SSO, tài khoản bị khóa |
| Log truy cập của nhà vận hành | Tenant Admin | BR-SAAS-037 |
| Log xuất & truy cập dữ liệu | Tenant Admin / DPO | Các bản xuất, các lần chạy báo cáo lớn, sử dụng API client |
| Sổ đăng ký quyền riêng tư | DPO | Sự đồng ý theo mục đích, DSR và trạng thái SLA, kết quả job lưu trữ |
| Đối chiếu số dư đầu kỳ | HR / Tài chính / tư vấn | Đầu ra BR-SAAS-072 kèm ký xác nhận (changed 2026-10-16, D-09) |

### 8.3 Năng lực framework (được mọi báo cáo của module sử dụng)
Danh mục, tham số, RLS & che dữ liệu, xuất Excel/PDF/CSV với font tiếng Việt, lập lịch, xuất bất đồng bộ, công cụ thiết kế, dashboard, phân tích nhân sự (BR-SAAS-046 … 054).

---

## 9. Yêu cầu phi chức năng (toàn sản phẩm)

| ID | Yêu cầu | Ưu tiên | Ghi chú |
|---|---|---|---|
| NFR-SAAS-001 | **Tính sẵn sàng**: thời gian hoạt động hằng tháng trên production ≥ 99,5% (Starter/Professional) và ≥ 99,9% (Enterprise/tier riêng), không tính bảo trì đã thông báo ≤ 4h/tháng được lên lịch trong khung 22:00–05:00 ICT và không bao giờ trong khung ngày lương 25–5 nếu tenant không đồng ý. | M | Service credit được định nghĩa trong SLA |
| NFR-SAAS-002 | **Hiệu năng tính lương**: một run lương hằng tháng cho 10.000 nhân viên (≈ 60 khoản mục mỗi người, gồm BHXH/thuế TNCN) hoàn tất trong ≤ 15 phút; 1.000 nhân viên trong ≤ 2 phút; tính lại một nhân viên ≤ 5 s. | M | Đo trên bộ dữ liệu tham chiếu trong môi trường perf |
| NFR-SAAS-003 | **Hiệu năng tương tác**: thời gian phản hồi máy chủ ở phân vị 95 ≤ 500 ms cho màn hình/API giao dịch và ≤ 3 s cho báo cáo chuẩn đến 5.000 dòng, dưới tải tham chiếu (NFR-SAAS-005). | M | |
| NFR-SAAS-004 | **Đột biến check-in**: duy trì 2.000 sự kiện check-in/phút mỗi tenant và 20.000/phút trên toàn nền tảng (đỉnh khi bắt đầu ca) với thời gian xác nhận p95 ≤ 1 s. | M | Nguồn ở Phase 1: chỉ connector thiết bị và import file (changed 2026-10-16, OD-9) |
| NFR-SAAS-005 | **Khả năng mở rộng**: tier dùng chung hỗ trợ ≥ 500 tenant và tổng ≥ 200.000 nhân viên khi ra mắt, mở rộng theo chiều ngang (node ứng dụng stateless, các bảng lớn được partition theo tenant/kỳ) tới 5× mà không cần thiết kế lại kiến trúc; một tenant gây tải lớn không thể dùng > 20% năng lực của pool dùng chung (hạn mức theo tenant với job, báo cáo, API). | M | |
| NFR-SAAS-006 | **Sao lưu & DR**: RPO ≤ 15 phút (PITR), RTO ≤ 4h (dùng chung) / ≤ 2h (Enterprise); sao lưu toàn bộ hằng ngày + lưu trữ WAL liên tục; bản sao lưu được mã hóa, giữ 35 ngày, lưu tại trung tâm dữ liệu thứ hai ở VN; kiểm thử khôi phục hằng quý; có thể khôi phục theo thời điểm cho từng tenant vào sandbox. | M | |
| NFR-SAAS-007 | **Mã hóa**: TLS 1.2+ khi truyền (kể cả lưu lượng giữa các service nội bộ); AES-256 khi lưu trữ cho DB, sao lưu, file; mã hóa ở tầng ứng dụng cho các trường đặc biệt nhạy cảm (tài khoản ngân hàng, CCCD, MST cá nhân, số BHXH, dữ liệu sức khỏe — số tiền lương KHÔNG mã hóa ở mức cột nhưng được bảo vệ bằng RLS, DB role theo module, view che dữ liệu và audit truy cập, changed 2026-10-16, X-01) với khóa trong KMS/HSM; khóa theo tenant cho tier riêng và được hỗ trợ cho tier dùng chung (crypto-shredding). | M | |
| NFR-SAAS-008 | **Secret**: không có thông tin xác thực trong mã hoặc repo; secret nằm trong vault, xoay vòng ≤ 90 ngày; file dữ liệu khách hàng (ví dụ các mẫu số dư đầu kỳ) không bao giờ được commit vào repository (changed 2026-10-16, D-09). | M | |
| NFR-SAAS-009 | **Bảo mật ứng dụng**: kiểm soát OWASP ASVS L2; quét SAST/DAST/dependency trong CI, chặn các phát hiện nghiêm trọng; kiểm thử xâm nhập bởi bên thứ ba hằng năm + sau bản phát hành lớn; SLA khắc phục lỗ hổng: nghiêm trọng 7 ngày, cao 30 ngày. | M | |
| NFR-SAAS-010 | **Observability**: log tập trung (che dữ liệu cá nhân), metric và distributed trace gắn tenant_id và correlation id; dashboard theo tenant cho bộ phận hỗ trợ; cảnh báo theo SLO; log được giữ ≥ 12 tháng (log bảo mật ≥ 24 tháng) tại VN. | M | Lưu trữ log theo Nghị định 53/2022 `[verify-legal]` |
| NFR-SAAS-011 | **Khả năng tiếp cận**: UI web và mobile đáp ứng WCAG 2.1 AA cho ESS và các luồng HR cốt lõi; điều hướng bằng bàn phím; nhãn cho trình đọc màn hình bằng vi/en. | S | |
| NFR-SAAS-012 | **Hỗ trợ trình duyệt/thiết bị**: 2 phiên bản mới nhất của Chrome, Edge, Safari, Firefox; iOS ≥ 16, Android ≥ 10; responsive xuống đến độ rộng 360 px. | M | |
| NFR-SAAS-013 | **Khả năng sử dụng/i18n**: mọi màn hình được bản địa hóa đầy đủ vi/en, không có UI lẫn lộn ngôn ngữ; font tiếng Việt hiển thị đúng trong bản xuất PDF/Excel. | M | |
| NFR-SAAS-014 | **Phát hành không gián đoạn**: triển khai rolling/blue-green; migration DB tương thích ngược (expand/contract); không có thời gian ngừng theo kế hoạch cho các bản phát hành thường kỳ; nhịp phát hành ≥ 2 tuần một lần; rollback ≤ 15 phút. | M | |
| NFR-SAAS-015 | **Quản trị phát hành**: release note bằng vi/en được công bố ≥ 3 ngày làm việc trước khi phát hành; thay đổi tính năng ảnh hưởng đến phép tính được ẩn sau toggle theo tenant cho đến khi tenant chủ động bật hoặc đến ngày đã thông báo; vòng thí điểm/người dùng sớm trước khi triển khai rộng. | M | Dùng BR-SAAS-009 |
| NFR-SAAS-016 | **SLA thay đổi pháp lý**: một văn bản pháp luật mới thay đổi giá trị luật định có sẵn dưới dạng gói tham số pháp lý đã phát hành, đã kiểm thử ≥ 7 ngày dương lịch trước ngày hiệu lực khi văn bản được ban hành trước ≥ 30 ngày; nếu không thì trong vòng 10 ngày làm việc kể từ ngày ban hành, có hỗ trợ tính lại hồi tố. | M | P10 |
| NFR-SAAS-017 | **Truy vết phép tính**: mọi kết quả lương/chấm công lưu phiên bản gói tham số pháp lý, phiên bản cấu hình và phiên bản phát hành mã đã dùng, để mọi kết quả trong quá khứ đều tái lập được. | M | |
| NFR-SAAS-018 | **Chất lượng API**: API công khai tương thích ngược trong một major version; thông báo ngừng hỗ trợ ≥ 6 tháng; độ trễ p95 ≤ 300 ms cho GET một tài nguyên. | S | |
| NFR-SAAS-019 | **Khả năng di chuyển dữ liệu**: định dạng xuất toàn bộ tenant được tài liệu hóa và ổn định; xuất một tenant 10.000 nhân viên hoàn tất trong ≤ 6 giờ. | M | |
| NFR-SAAS-020 | **SLA hỗ trợ**: phản hồi mức nghiêm trọng 1 ≤ 1h 24×7 (Enterprise) / trong giờ hành chính (các gói khác); hỗ trợ tiếng Việt và tiếng Anh. | M | |
| NFR-SAAS-021 | **Năng lực trong mùa cao điểm**: năng lực cho chốt lương (ngày 25–5) và quyết toán thuế TNCN năm (tháng 1–3) được hoạch định ở mức 3× tải trung bình; kiểm thử tải trước mỗi mùa cao điểm. | M | |
| NFR-SAAS-022 | **Hiệu năng ứng dụng mobile**: khởi động nguội ≤ 3 s trên Android tầm trung; gửi check-in ≤ 2 s trên 4G; dung lượng ứng dụng ≤ 60 MB. | S | |

---

## 10. Giả định, câu hỏi mở, rủi ro

### 10.1 Giả định
- A1. Hạ tầng được đặt tại một nhà cung cấp cloud/trung tâm dữ liệu ở Việt Nam (ví dụ Viettel IDC, VNPT, FPT, CMC) có ít nhất hai availability zone/địa điểm tại VN; không dùng các region của hyperscaler ngoài VN cho dữ liệu cá nhân.
- A2. Tenant là **bên kiểm soát** dữ liệu cá nhân; nhà cung cấp là **bên xử lý** theo DPA đã ký. Nhà cung cấp chỉ là bên kiểm soát đối với dữ liệu liên hệ khách hàng và dữ liệu tính phí của chính mình.
- A3. Phân quyền (vai trò, quyền, phạm vi dữ liệu), tài khoản người dùng tenant, audit store, import engine, framework trường tùy chỉnh, pháp nhân và dữ liệu danh mục toàn cục nằm trong `core`; nền tảng sử dụng chúng và không nhân bản chúng (changed 2026-10-16, R2-02 §5).
- A4. Khách hàng thí điểm sẽ tự điền các mẫu số dư đầu kỳ từ hồ sơ của mình và cung cấp tổng kiểm soát cùng người phụ trách dữ liệu để ký xác nhận đối chiếu. Không có kế hoạch truy cập Oracle và không chạy song song (changed 2026-10-16, D-09).
- A5. Việc xuất hóa đơn điện tử dùng một nhà cung cấp bên thứ ba được chứng nhận (ví dụ VNPT, Viettel, MISA meInvoice, BKAV) thay vì hệ thống hóa đơn điện tử tự xây dựng.
- A6. Việc nộp hồ sơ VSS đi qua một nhà cung cấp I-VAN được cấp phép; không giả định có API VSS trực tiếp cho bên thứ ba.
- A7. Các gói tham số pháp lý được nhà cung cấp duy trì tập trung; tenant chỉ được ghi đè khi luật cho phép (ví dụ phúc lợi có lợi hơn).

### 10.2 Câu hỏi mở
| # | Câu hỏi | Người phụ trách |
|---|---|---|
| Q1 | ~~Các thực thể nền tảng nằm ở đâu?~~ **Đã đóng 2026-10-16 (X-07, GAP-014):** một schema `saas` mới; charter đã được sửa đổi. | Kiến trúc sư dữ liệu / PM |
| Q2 | Cô lập mặc định cho tier dùng chung: một DB với RLS trên mọi bảng, hay schema-per-tenant? Ảnh hưởng đến công cụ import, reporting store và khôi phục theo tenant. | Kiến trúc sư giải pháp (ADR) |
| Q3 | Công nghệ reporting store: PostgreSQL read replica + materialised view, hay DB phân tích riêng (ví dụ ClickHouse tại VN)? | Kiến trúc sư giải pháp |
| Q4 | ~~Định nghĩa nhân viên tính phí~~ — **đã quyết định 2026-10-16 (D-10)**: người đang làm việc vào cuối tháng theo pháp nhân (BR-SAAS-012). Vẫn còn mở: cách xử lý lao động thời vụ / cộng tác viên (cộng tác viên) không có trong bảng lương. | Product / Kinh doanh |
| Q5 | Nghị định hướng dẫn Luật BVDLCN 2025 có thay đổi thời hạn DSR, việc nộp DPIA và nội dung thông báo vi phạm so với Nghị định 13/2023 không? | Chuyên gia tư vấn pháp lý VN |
| Q6 | Nền tảng hợp đồng lao động điện tử quốc gia có bắt buộc với các tenant của chúng ta không, từ ngày nào, và giao diện tích hợp nào được công bố? | Chuyên gia tư vấn pháp lý VN / Chuyên viên phân tích tích hợp |
| Q7 | Tích hợp VNeID: nhà cung cấp SaaS tư nhân có được truy cập không, và theo giấy phép nào? | Chuyên viên phân tích tích hợp |
| Q8 | Những ngân hàng nào bắt buộc cho khách hàng thí điểm và họ có cung cấp API host-to-host không? | Chuyên viên phân tích tích hợp |
| Q9 | Khách hàng doanh nghiệp lớn có yêu cầu triển khai on-premise hoặc private cloud (ngoài SaaS) không? Hiện nằm ngoài phạm vi. | PM / Kinh doanh |
| Q10 | Thời gian lưu giữ dữ liệu tenant sau khi chấm dứt (mặc định 90 ngày) — có được khách hàng thí điểm chấp nhận và nhất quán với nghĩa vụ lưu trữ chứng từ kế toán do tenant nắm giữ không? | PM / Pháp lý |
| Q11 | Ngày mục tiêu đạt chứng nhận ISO 27001 và liệu SOC 2 có thực sự cần cho thị trường VN không. | Trưởng nhóm bảo mật |

### 10.3 Rủi ro
| # | Rủi ro | Tác động | Giảm thiểu |
|---|---|---|---|
| R1 | Lộ dữ liệu giữa các tenant do thiếu bộ lọc tenant | Nghiêm trọng (pháp lý, uy tín) | RLS thực thi trong DB + bộ lọc ở ứng dụng + kiểm thử cô lập tự động (BR-SAAS-021/023) |
| R2 | Thay đổi pháp lý ban hành với thời gian báo trước ngắn hoặc có hiệu lực hồi tố | Cao: tính lương sai cho mọi tenant | Gói tham số, SLA thay đổi pháp lý, tính lại hồi tố, chuyên gia tư vấn thường trực (P10, NFR-SAAS-016) |
| R3 | Số dư lũy kế đầu kỳ được import sai hoặc thiếu từ file Excel của khách hàng (không chuyển đổi dữ liệu và không chạy song song, D-09) | Cao: thuế TNCN hằng tháng và quyết toán năm sai, tranh chấp lịch sử BHXH, số dư phép sai | Mẫu chuẩn có kiểm tra hợp lệ; bắt buộc đủ các tháng của năm tính thuế (BR-SAAS-089); đối chiếu với tổng kiểm soát của tenant có ký xác nhận (BR-SAAS-072); khóa và điều chỉnh có audit (BR-SAAS-088); tùy chọn so sánh bảng lương (BR-SAAS-073) (changed 2026-10-16, D-09) |
| R4 | Vi phạm lưu trú dữ liệu qua SaaS bên thứ ba (email, giám sát, theo dõi lỗi) | Cao: bị xử phạt theo quy định | Danh mục nhà cung cấp, phương án thay thế lưu trữ tại VN, lọc bỏ PII, đánh giá tác động chuyển dữ liệu |
| R5 | Tải báo cáo làm giảm hiệu năng tính lương/check-in | Trung bình | Reporting store riêng, hạn mức theo tenant (BR-SAAS-047, NFR-SAAS-005) |
| R6 | Phụ thuộc vào các bên tích hợp bên ngoài (I-VAN, hóa đơn điện tử, chữ ký điện tử, ngân hàng) thay đổi định dạng/API | Trung bình | Lớp trừu tượng integration hub, quản lý phiên bản connector, giám sát |
| R7 | Nhà vận hành lạm dụng quyền truy cập hỗ trợ | Cao | Quyền cấp dựa trên sự đồng ý, che dữ liệu, giới hạn mạo danh, log hiển thị cho tenant (BR-SAAS-033…037) |
| R8 | Phình phạm vi: các năng lực nền tảng bị đẩy vào MVP | Trung bình: chậm có tenant trả phí đầu tiên | MoSCoW chặt chẽ; Phase 2 cho tự phục vụ, công cụ thiết kế, ứng dụng native, API |
| R9 | Quy định hướng dẫn Luật BVDLCN 2025 vẫn đang thay đổi | Trung bình | Theo dõi pháp lý, cấu hình được sự đồng ý/lưu trữ, các mục gắn cờ `[verify-legal]` được rà soát trước MVP |
