# Backlog SaaS Platform: Phase 1 (v0.1, 2026-10-16)

| Mục | Giá trị |
|---|---|
| Chủ sở hữu | BA – SaaS Platform & các hạng mục xuyên suốt |
| Phạm vi | Toàn bộ `BR-SAAS` và `NFR-SAAS` mức P1 trong [mvp-scope.md](../brd/mvp-scope.md) §9.6 (42 BR + 17 NFR), lấy từ [06-saas-platform.md](../brd/modules/06-saas-platform.md) v0.3 |
| Phương pháp | [estimation-guide.md](estimation-guide.md) v0.1 (SP theo dãy Fibonacci đối chiếu thang tham chiếu §4; 1 SP ≈ 1 ngày công developer, chưa tính +40% chi phí phát sinh) |
| Các quyết định đã áp dụng | D-09 (import số dư đầu kỳ, không migration), D-10/R2-12 (tính phí = person đang làm việc tại cuối tháng theo pháp nhân; người tạm hoãn không tính phí), R2-01/R2-19 (không có tích hợp trực tuyến; cắt feature flag, xuất bất đồng bộ, metering, break-glass), R2-02 (bảng single-owner), R2-03 (so sánh bảng lương ở P1), R2-04 (không SSO), R2-18 (baseline luôn bật), R3-01 (không có hóa đơn điện tử ở P1), R3-03 (quy tắc bảo hiểm đa hợp đồng là tham số pháp lý), R3-05 (không có ESS nếu không có e-mail), R3-08 (log truy cập của operator ở P1), R3-09 (thuộc tính tùy biến được ánh xạ khi kiểm tra hợp lệ), OD-8 (OTP qua e-mail cho người đã nghỉ việc) |

## 0. Phân chia với các backlog khác

Backlog này chứa **các tính năng nền tảng hướng đến sản phẩm**: những gì tenant, nhân viên, operator và người phụ trách nội dung pháp lý nhìn thấy và sử dụng.

| Không thuộc backlog này | Nằm ở đâu |
|---|---|
| Triển khai Keycloak, thiết lập realm/Organization, hệ thống token/BFF; tenancy context, RLS, bộ kiểm thử cô lập; outbox/event bus; runtime và worker của job queue; runtime Legal Parameter Service (`get`/`resolveSet`, caching, sự kiện); object storage; hosting SMTP relay; CI/CD, môi trường, observability, backup/DR, mã hóa, secrets, kiểm thử bảo mật và hiệu năng | `technical-enablers-backlog.md` (`US-TECH-…`) |
| Workflow engine, trung tâm thông báo, import engine (validate/commit/rollback), kho audit, dịch vụ tài liệu, chính sách che dữ liệu và predicate phạm vi dữ liệu, kho dữ liệu danh mục toàn cục và admin API | `core-backlog.md` (`US-CORE-…`) |
| Nội dung các mẫu số dư đầu kỳ (loại import và handler), biểu mẫu theo luật định (D02-LT, 05/KK, 05/QTT, báo cáo lao động), bố cục file ngân hàng và bút toán GL, mật khẩu PDF phiếu lương | Backlog của module sở hữu (People, T&A, Payroll), theo hướng dẫn §4 |

Các story ở đây chỉ *đăng ký vào* hoặc *cấu hình* các engine đó. **Phụ thuộc** ghi tên enabler theo năng lực (`TECH: …`, `CORE: …`) khi ID story của backlog kia chưa được công bố tại thời điểm viết file này; PM sẽ liên kết ID khi hợp nhất.

## 1. Epic

| Epic ID | Tên | Mục tiêu | Số story | SP |
|---|---|---|---|---|
| EP-SAAS-01 | Vòng đời & khởi tạo tenant | Operator khởi tạo một tenant pilot hoạt động được trong ≤ 10 phút và quản lý trạng thái vòng đời cũng như thiết lập của tenant | 6 | 27 |
| EP-SAAS-02 | Thuê bao, quyền sử dụng (entitlement) & số lượng tính phí | Module được bán theo tenant, các năng lực baseline luôn bật, bộ phận tài chính của nhà cung cấp nhận số lượng tính phí cuối tháng theo pháp nhân (không có hóa đơn điện tử ở P1) | 6 | 32 |
| EP-SAAS-03 | Back-office của operator & truy cập hỗ trợ | Operator làm việc trên một console riêng, chỉ tiếp cận dữ liệu tenant qua quyền truy cập do tenant phê duyệt, có che dữ liệu, có ghi log, và phát hành dữ liệu danh mục toàn cục | 8 | 34 |
| EP-SAAS-04 | Định danh & truy cập (theo góc nhìn người dùng) | Đăng nhập, chính sách mật khẩu, MFA (TOTP / OTP qua e-mail), phiên, kích hoạt ESS và đăng nhập của người đã nghỉ việc | 9 | 39 |
| EP-SAAS-05 | Legal Parameter Service: tính năng nghiệp vụ | Người phụ trách nội dung duy trì, phê duyệt và công bố các gói tham số pháp lý; tenant xem các giá trị đang có hiệu lực và yêu cầu ghi đè được phép | 8 | 39 |
| EP-SAAS-06 | Quyền riêng tư & tuân thủ (PDPL) | Danh mục dữ liệu cá nhân, sổ đăng ký sự đồng ý, xử lý DSR, đầu vào DPIA và xử lý sự cố rò rỉ cho tenant với vai trò bên kiểm soát và nhà cung cấp với vai trò bên xử lý | 8 | 37 |
| EP-SAAS-07 | Framework báo cáo, xuất dữ liệu & mẫu theo luật định | Một khung báo cáo và một đường xuất dữ liệu cho mọi module, mẫu theo luật định có phiên bản, và trung tâm file đầu ra cho file ngân hàng/GL/eTax | 7 | 39 |
| EP-SAAS-08 | Khung web ESS, bản địa hóa & kênh e-mail | Khung web ESS responsive, vi/en đầy đủ, và gửi thông báo của Core qua e-mail | 6 | 22 |
| EP-SAAS-09 | Chương trình go-live bằng số dư đầu kỳ | Mỗi pháp nhân go-live giữa năm từ số dư đầu kỳ đã kiểm tra hợp lệ, đã đối soát, đã ký xác nhận và đã khóa, kèm tùy chọn so sánh bảng lương một tháng | 12 | 60 |
| EP-SAAS-10 | Tính năng vận hành nền tảng | Theo dõi job, bật/tắt tính toán theo tenant và release ring, release note và thông báo bảo trì, điểm tiếp nhận hỗ trợ, hạn mức tenant | 5 | 13 |
| **Tổng** | | | **75** | **342** |

## 2. Story

### EP-SAAS-01 Vòng đời & khởi tạo tenant

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-001 | EP-SAAS-01 | Là platform operator, tôi muốn có bản ghi tenant với các trạng thái vòng đời để đăng nhập, job và tính phí tuân theo tình trạng hợp đồng của tenant. Các trạng thái Trial…Deleted theo BR, chỉ cho phép các chuyển trạng thái hợp lệ; mọi chuyển trạng thái được ghi log kèm người thực hiện, lý do, thời điểm; P1 dùng Pending Provisioning, Active, Suspended-ReadOnly, Suspended-Locked (đặt thủ công; không có nhắc nợ) | BR-SAAS-001 | 5 | TECH: tenancy context | Máy trạng thái có lịch sử = 5. Trạng thái Trial/Expired/Terminated tồn tại nhưng không có tự động hóa ở P1 (BR-SAAS-004/005/017/018 là P2) |
| US-SAAS-002 | EP-SAAS-01 | Là platform operator, tôi muốn trạng thái tenant được thực thi trên mọi module để tenant bị tạm ngưng không thể chạy công việc mới nhưng nhân viên vẫn giữ quyền truy cập theo luật định. Read-only chặn thao tác ghi với mã `TENANT_READ_ONLY`; Locked chỉ cho Tenant Owner đăng nhập; allow-list luôn mở: phiếu lương/chứng từ khấu trừ thuế TNCN của chính mình, xuất dữ liệu của Owner, DSR; job của tenant bị khóa được tạm dừng | BR-SAAS-001, BR-SAAS-090 | 5 | US-SAAS-001; TECH: job queue runtime | Guard xuyên module kèm allow-list |
| US-SAAS-003 | EP-SAAS-01 | Là billing operator, tôi muốn có bên nhận hóa đơn cho mỗi tenant liên kết với các pháp nhân trong Core để hóa đơn đến đúng người mua trong khi Core vẫn là nguồn chính về pháp nhân. CRUD MST người mua, tên, địa chỉ, thông tin định danh hóa đơn điện tử; liên kết tới `core.legal_entity.id`; giới hạn gói về số pháp nhân được thực thi khi Core tạo pháp nhân | BR-SAAS-002 | 3 | US-SAAS-001; CORE: legal entity API | Một thực thể CRUD |
| US-SAAS-004 | EP-SAAS-01 | Là tư vấn onboarding, tôi muốn khởi tạo từ back-office một tenant shared-tier hoạt động đầy đủ để khách hàng mới có thể bắt đầu trong vòng 10 phút. Wizard: tenant, bên nhận hóa đơn, pháp nhân đầu tiên (qua Core API), module, ngày hợp đồng, số seat cam kết, liên hệ owner; các bước tự động: bản ghi tenant + RLS context, Keycloak organisation và lời mời owner, seed dữ liệu tham chiếu, gắn với gói pháp lý hiện hành, entitlement baseline; hiển thị tiến độ, tự động rollback và cảnh báo operator khi bất kỳ bước nào thất bại, toàn trình ≤ 10 phút | BR-SAAS-003 | 8 | US-SAAS-001, 003, 007, 010; TECH: Keycloak, tenancy/RLS, job queue; CORE: legal entity API, master data | `[high-uncertainty]`: điều phối nhiều service kèm bù trừ khi lỗi; mục tiêu 10 phút phụ thuộc vào thời gian của Keycloak và seed |
| US-SAAS-005 | EP-SAAS-01 | Là Tenant Owner, tôi muốn có link kích hoạt để đặt mật khẩu và đăng ký MFA để có thể tiếp quản tenant mới một cách an toàn. Link dùng một lần, có hạn (mặc định 72 h), operator có thể gửi lại; bắt buộc đăng ký MFA trước lần truy cập đầu tiên; việc kích hoạt được ghi log | BR-SAAS-003, BR-SAAS-039 | 3 | US-SAAS-004, 023 | |
| US-SAAS-006 | EP-SAAS-01 | Là Tenant Admin, tôi muốn thiết lập mặc định cho tenant để mọi người dùng thấy ngôn ngữ, múi giờ và định dạng của chúng tôi. Ngôn ngữ mặc định vi/en, múi giờ (mặc định Asia/Ho_Chi_Minh), định dạng ngày/số, tiền tệ (VND), năm tài chính, ngày bắt đầu tuần, logo; người dùng có thể ghi đè ngôn ngữ; thay đổi được audit | BR-SAAS-025 | 3 | US-SAAS-001, 055 | Một thực thể thiết lập |

### EP-SAAS-02 Thuê bao, quyền sử dụng (entitlement) & số lượng tính phí

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-007 | EP-SAAS-02 | Là billing operator, tôi muốn ghi nhận thuê bao module của từng tenant để mỗi module có thể được bán và chấm dứt độc lập. Thuê bao gồm các mục theo module (`core` bắt buộc; people, time-attendance, payroll) với ngày bắt đầu/kết thúc, giá hợp đồng theo module và số seat cam kết; kiểm tra ma trận phụ thuộc (ví dụ Payroll cần dữ liệu gốc People + import chấm công); chấm dứt một module khiến dữ liệu của nó thành chỉ đọc, không bao giờ bị xóa | BR-SAAS-007 | 5 | US-SAAS-001 | Theo ngày hiệu lực = 5. Gói/phiên bản và bảng giá là P2 (BR-SAAS-006/010) |
| US-SAAS-008 | EP-SAAS-02 | Là module developer, tôi muốn có một entitlement resolver và API kiểm tra duy nhất để mọi UI, API và job đặt cùng một câu hỏi "tính năng X có đang bật cho tenant T (và pháp nhân E) hay không?". Resolve các dòng PLAN/ADDON/OVERRIDE/BASELINE vào `saas.entitlement`; `@Entitled` trên các điểm vào API và job trả về HTTP 403 `FEATURE_NOT_ENTITLED`; `/me/capabilities`; `saas.EntitlementsChanged` làm mất hiệu lực cache | BR-SAAS-008 | 8 | US-SAAS-007; TECH: event bus, walking skeleton | Rule engine nền tảng được mọi module sử dụng |
| US-SAAS-009 | EP-SAAS-02 | Là người dùng tenant, tôi muốn các tính năng chưa mua được ẩn đi để sản phẩm chỉ hiển thị những gì chúng tôi dùng. Menu và route guard được điều khiển bởi `/me/capabilities`; URL trực tiếp tới trang không có entitlement hiển thị trang "chưa đăng ký"; dữ liệu của module đã chấm dứt được hiển thị chỉ đọc | BR-SAAS-008, BR-SAAS-007 | 3 | US-SAAS-008 | |
| US-SAAS-010 | EP-SAAS-02 | Là product owner, tôi muốn các năng lực baseline luôn bật để mọi cấu hình tenant đều hoạt động bất kể mua module nào. `saas.baseline_capability` (Core, People cơ bản, tham số pháp lý, thang/khung lương + danh mục thành phần lương, import chấm công Excel) ghi các dòng BASELINE cho mọi tenant mà không gói hay override nào có thể tắt; ma trận phụ thuộc được công bố trong danh mục gói; ma trận kiểm thử tự động cho "Payroll không có T&A" và "People không có Payroll" | BR-SAAS-091 | 5 | US-SAAS-008 | Kiểm thử cấu hình chặn release |
| US-SAAS-011 | EP-SAAS-02 | Là billing operator, tôi muốn nền tảng chốt số nhân viên tính phí vào mỗi cuối tháng để việc tính phí tuân theo quyết định D-10. Đếm theo pháp nhân và module số person có employment đang hiệu lực vào ngày cuối tháng; loại trừ pre-hire, đã chấm dứt, tạm hoãn và bản ghi chỉ có lịch sử; một person ở hai pháp nhân được đếm hai lần; với payroll, person được trả lương trong kỳ lương thường của tháng đó cũng được đếm; giữ snapshot hằng ngày để tham khảo; theo quý/năm = tổng các lần đếm hằng tháng | BR-SAAS-012 | 8 | US-SAAS-007; People: luồng dữ liệu person đang làm việc cuối tháng (CO-05); TECH: job queue | Quy tắc phức tạp với nhiều trường hợp. `[high-uncertainty]`: cách xử lý cộng tác viên (CTV) không có trong bảng lương vẫn còn mở (BRD Q4) |
| US-SAAS-012 | EP-SAAS-02 | Là bộ phận tài chính của nhà cung cấp, tôi muốn có báo cáo sử dụng cuối tháng theo tenant để có thể xuất hóa đơn từ công cụ hóa đơn điện tử của chúng tôi. Theo tenant, pháp nhân và module: số lượng tính phí, số seat cam kết, max(cam kết, tính phí) × giá hợp đồng; xuất Excel; Tenant Owner xem xu hướng số lượng tính phí của mình | BR-SAAS-012 | 3 | US-SAAS-011, 048 | Không có hóa đơn điện tử, thanh toán hay nhắc nợ ở P1 (R3-01; BR-SAAS-013/014/015/017 là P2) |

### EP-SAAS-03 Back-office của operator & truy cập hỗ trợ

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-013 | EP-SAAS-03 | Là platform operator, tôi muốn có back-office tách biệt khỏi UI của tenant để công việc của nhà cung cấp không bao giờ lẫn với phiên của tenant. Realm operator riêng với MFA bắt buộc và IP allow-list; vai trò operator (hỗ trợ, onboarding, billing, người phụ trách nội dung pháp lý, SRE) với menu theo vai trò; danh sách danh mục tenant (trạng thái, module, pháp nhân, số lượng tính phí) | BR-SAAS-032 | 5 | TECH: Keycloak operator realm | Hạ tầng realm thuộc TECH; story này là console, mô hình vai trò và danh mục tenant |
| US-SAAS-014 | EP-SAAS-03 | Là platform operator, tôi muốn chuẩn bị một bản phát hành dữ liệu danh mục toàn cục để mọi tenant nhận đơn vị hành chính, ngân hàng hoặc quốc tịch mới trong một thay đổi có kiểm soát. Bản phát hành nháp bằng cách chỉnh sửa hoặc tải lên Excel (đơn vị hành chính kèm ánh xạ cũ→mới, ngân hàng, quốc tịch); kiểm tra hợp lệ và so sánh khác biệt với bản phát hành hiện hành; ngày hiệu lực và nội dung thông báo thay đổi vi/en | BR-SAAS-032 | 5 | US-SAAS-013; CORE: global master data store and admin API | Mẫu import Excel = 5 |
| US-SAAS-015 | EP-SAAS-03 | Là platform operator, tôi muốn bản phát hành được xem xét và công bố để thay đổi dữ liệu danh mục được kiểm tra bốn mắt và có thể truy vết. Operator thứ hai xem xét; công bố sẽ nạp bản phát hành có phiên bản qua Core admin API (`core.MasterDataReleased`); lịch sử phát hành; nạp lỗi được báo cáo, không có gì được công bố một phần | BR-SAAS-032 | 3 | US-SAAS-014; CORE: master data admin API | Core sở hữu kho dữ liệu và báo cáo tham chiếu tenant (BR-CORE-080/087) |
| US-SAAS-016 | EP-SAAS-03 | Là nhân viên hỗ trợ, tôi muốn yêu cầu tenant cấp quyền hỗ trợ để chỉ chẩn đoán sự cố khi có sự đồng ý của khách hàng. Yêu cầu kèm ticket, module, chỉ đọc/đọc-ghi, cờ dữ liệu nhạy cảm, thời hạn ≤ 72 h, lý do; Tenant Admin/Owner phê duyệt hoặc từ chối ngay trong ứng dụng; quyền tự hết hạn hoặc có thể bị thu hồi; tóm tắt phiên được gửi cho tenant khi hết hạn | BR-SAAS-033 | 5 | US-SAAS-013; CORE: workflow, notifications | Workflow phê duyệt = 5 |
| US-SAAS-017 | EP-SAAS-03 | Là Tenant Admin, tôi muốn operator bị chặn truy cập dữ liệu của chúng tôi khi không có quyền đang hiệu lực để nhà cung cấp không có quyền truy cập mặc định. Token operator không có tenant context trừ khi có quyền đang hiệu lực; grant id được mang trong context; banner hiển thị liên tục; quyền chỉ đọc từ chối thao tác ghi; mọi hành động được ghi vào audit của tenant kèm operator và grant | BR-SAAS-033, BR-SAAS-085 | 5 | US-SAAS-016; TECH: tenancy context; CORE: audit store | Thực thi BR-CORE-041 từ phía nền tảng |
| US-SAAS-018 | EP-SAAS-03 | Là DPO, tôi muốn các trường nhạy cảm được che trong phiên hỗ trợ để operator không thấy dữ liệu lương, ngân hàng hay giấy tờ tùy thân khi chưa có sự đồng ý rõ ràng. Số tiền lương, tài khoản ngân hàng, CCCD, mã số thuế cá nhân, dữ liệu sức khỏe/y tế BHXH và người phụ thuộc được che trên màn hình, API, báo cáo và file xuất trừ khi quyền bao gồm "dữ liệu nhạy cảm"; việc che được kiểm thử theo từng module | BR-SAAS-034 | 5 | US-SAAS-017, 047; CORE: masking policy | Dùng chính sách che dữ liệu của Core; story này bổ sung chế độ theo quyền hỗ trợ |
| US-SAAS-019 | EP-SAAS-03 | Là Tenant Admin, tôi muốn xem và xuất log mọi lần operator truy cập tenant của chúng tôi để có thể giám sát bên xử lý như PDPL yêu cầu. Ai, khi nào, vì sao, ticket, phạm vi quyền, đã xem hoặc thay đổi gì; bộ lọc; xuất Excel | BR-SAAS-037 | 3 | US-SAAS-017, 048 | Dạng tối thiểu được khôi phục theo R3-08. Break-glass hai người (BR-SAAS-036) là quy trình thủ công ở P1 |
| US-SAAS-020 | EP-SAAS-03 | Là security officer, tôi muốn các sự kiện operator cấp nền tảng được lưu dạng chống giả mạo để hành động của operator không gắn với tenant cũng có trách nhiệm giải trình. `saas.audit_event` với hash chain cho các sự kiện operator không gắn tenant (đăng nhập, tạo tenant, công bố gói, phát hành dữ liệu danh mục); báo cáo truy cập của operator trong back-office | BR-SAAS-085 | 3 | US-SAAS-013 | Sự kiện của tenant đi vào kho audit của Core |

### EP-SAAS-04 Định danh & truy cập (theo góc nhìn người dùng)

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-021 | EP-SAAS-04 | Là người dùng, tôi muốn có trang đăng nhập vi/en rõ ràng để có thể đăng nhập bằng mã nhân viên hoặc e-mail. Đăng nhập xác định theo tenant (mã công ty hoặc URL slug); tên đăng nhập = mã nhân viên hoặc e-mail công việc; quên mật khẩu qua link e-mail; thông báo khóa tài khoản và mật khẩu hết hạn bằng vi/en; giao diện mang thương hiệu sản phẩm | BR-SAAS-038, BR-SAAS-045 | 3 | TECH: Keycloak | Cấu hình luồng đăng nhập và giao diện; thiết lập Keycloak thuộc TECH |
| US-SAAS-022 | EP-SAAS-04 | Là Tenant Admin, tôi muốn cấu hình chính sách mật khẩu để đáp ứng quy định bảo mật của chúng tôi. Độ dài tối thiểu (mặc định 10, tối thiểu 8), độ phức tạp, lịch sử (mặc định 5), tùy chọn tuổi tối đa, khóa sau N lần sai (mặc định 5 / 15 phút), kiểm tra mật khẩu đã bị lộ theo danh sách offline; chính sách được đẩy sang identity provider; thay đổi được audit | BR-SAAS-038 | 5 | US-SAAS-021; TECH: Keycloak | Kiểm tra danh sách bị lộ và đồng bộ chính sách |
| US-SAAS-023 | EP-SAAS-04 | Là người dùng, tôi muốn đăng ký và sử dụng MFA bằng ứng dụng xác thực hoặc mã qua e-mail để tài khoản của tôi được bảo vệ. Đăng ký TOTP bằng QR, OTP qua e-mail là phương án thay thế; mã khôi phục; Tenant Admin có thể đặt lại MFA của người dùng (có audit, step-up) | BR-SAAS-039 | 5 | US-SAAS-021, 058 | OTP qua SMS là P2 |
| US-SAAS-024 | EP-SAAS-04 | Là Tenant Admin, tôi muốn MFA được bắt buộc theo vai trò để người dùng đặc quyền không thể bỏ qua. Bắt buộc với Tenant Owner, Tenant Admin, HR/C&B, tài chính, người phê duyệt bảng lương và mọi operator; thiết lập tenant cho người dùng ESS; step-up (`acr ≥ 2`) với các thao tác nhạy cảm (phê duyệt bảng lương, tải file ngân hàng, mật khẩu phiếu lương) | BR-SAAS-039 | 5 | US-SAAS-023; CORE: roles | |
| US-SAAS-025 | EP-SAAS-04 | Là Tenant Admin, tôi muốn có quy tắc phiên và đăng xuất cưỡng bức để phiên bị bỏ quên hoặc bị đánh cắp không còn mở. Thời gian chờ không hoạt động (mặc định 30 phút trên web), thời gian tối đa tuyệt đối, giới hạn số phiên đồng thời; người dùng "đăng xuất khỏi mọi thiết bị"; admin đăng xuất cưỡng bức một người dùng | BR-SAAS-043 | 5 | US-SAAS-021; TECH: Keycloak, BFF | Phiên mobile là P2 |
| US-SAAS-026 | EP-SAAS-04 | Là Tenant Admin, tôi muốn quyền đăng nhập tuân theo trạng thái người dùng trong Core để người nghỉ việc và người dùng bị vô hiệu hóa mất quyền truy cập đúng hạn. Tiêu thụ `core.UserStatusChanged`: DEACTIVATED vô hiệu hóa thông tin đăng nhập và thu hồi phiên; FORMER_EMPLOYEE thu hồi phiên và chuyển đăng nhập sang OTP qua e-mail; tái tuyển dụng khôi phục đăng nhập bình thường | BR-SAAS-043, BR-SAAS-045 | 3 | US-SAAS-025; TECH: event bus; CORE: user status | OD-8 |
| US-SAAS-027 | EP-SAAS-04 | Là người đã nghỉ việc, tôi muốn đăng nhập bằng mã dùng một lần gửi tới e-mail cá nhân để có thể tải phiếu lương và chứng từ khấu trừ thuế TNCN sau khi nghỉ. OTP qua e-mail không cần mật khẩu, gửi tới e-mail cá nhân được ghi nhận khi offboarding; chỉ có quyền truy cập giới hạn của người đã nghỉ việc; quyền truy cập kết thúc khi bị vô hiệu hóa (thiết lập tenant, mặc định 24 tháng); không có e-mail → không đăng nhập, HR cung cấp tài liệu | BR-SAAS-045 | 5 | US-SAAS-026, 058; People: e-mail cá nhân khi offboarding | OD-8 đã được chấp nhận; OTP qua SMS là P3 |
| US-SAAS-028 | EP-SAAS-04 | Là người dùng HR, tôi muốn kích hoạt tài khoản ESS hàng loạt để nhân viên có thể đăng nhập mà không cần mật khẩu mặc định dùng chung. Chỉ gửi kích hoạt cho nhân viên có e-mail công việc hoặc cá nhân đã xác minh; lần đăng nhập đầu bằng OTP qua e-mail rồi tự đặt mật khẩu; mã có hạn và có thể gửi lại; báo cáo nhân viên không có e-mail sẽ nhận phiếu lương in thay thế | BR-SAAS-045 | 5 | US-SAAS-021, 058; CORE: user accounts | R3-05: không có mã kích hoạt và không có ESS cho nhân viên không có e-mail |
| US-SAAS-029 | EP-SAAS-04 | Là Tenant Admin, tôi muốn có báo cáo bảo mật và hoạt động đăng nhập để phát hiện tấn công và theo dõi mức độ sử dụng MFA. Sự kiện bảo mật (đăng nhập thành công/thất bại, khóa tài khoản, đăng ký/đặt lại MFA, đổi mật khẩu, thu hồi phiên, thay đổi chính sách) được ghi vào kho audit của Core; báo cáo đăng nhập, thất bại, tài khoản bị khóa, mức độ sử dụng MFA, có thể xuất | BR-SAAS-085 | 3 | US-SAAS-022–025, 046; CORE: audit store | |

### EP-SAAS-05 Legal Parameter Service: tính năng nghiệp vụ

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-030 | EP-SAAS-05 | Là người phụ trách nội dung pháp lý, tôi muốn có danh mục các khóa tham số có kiểu để mọi giá trị theo luật định có một định nghĩa duy nhất được mọi module sử dụng. Các kiểu khóa SCALAR, TABLE, MAP, CALENDAR_TEMPLATE; đơn vị, căn cứ pháp lý, module sở hữu, cờ "luật cho phép ghi đè"; module đăng ký khóa; khóa mới không cần thay đổi schema | BR-SAAS-032, NFR-SAAS-016 (hỗ trợ BR-PAY-010, NFR-PAY-007) | 5 | US-SAAS-013; TECH: LPS runtime | |
| US-SAAS-031 | EP-SAAS-05 | Là người phụ trách nội dung pháp lý, tôi muốn chỉnh sửa một phiên bản gói nháp để có thể nhập văn bản pháp luật mới mà không cần release. Bản nháp có ngày hiệu lực và căn cứ pháp lý; trình soạn giá trị scalar và bảng (ví dụ biểu thuế TNCN lũy tiến); tải lên Excel cho MAP (đơn vị hành chính → vùng lương tối thiểu) và mẫu ngày nghỉ lễ quốc gia; so sánh khác biệt với gói trước | BR-SAAS-032, NFR-SAAS-016 (hỗ trợ BR-PAY-010, BR-CORE-006) | 8 | US-SAAS-030 | Nhiều trình soạn cộng với import Excel có kiểm tra hợp lệ |
| US-SAAS-032 | EP-SAAS-05 | Là người phụ trách nội dung pháp lý, tôi muốn các gói phải qua kiểm thử và hai lần phê duyệt trước khi công bố để không có giá trị chưa kiểm tra nào đến được bảng lương. Vòng đời DRAFT → IN_TEST (đính kèm kết quả chạy golden scenario) → APPROVED (người phụ trách nội dung + tư vấn pháp lý) → PUBLISHED (bất biến) → SUPERSEDED; đính chính chỉ bằng phiên bản mới; việc công bố phát ra `saas.LegalParameterPackPublished` | NFR-SAAS-016 (hỗ trợ BR-PAY-010) | 5 | US-SAAS-031; TECH: LPS runtime, golden test harness | Workflow phê duyệt = 5 |
| US-SAAS-033 | EP-SAAS-05 | Là Tenant Admin, tôi muốn được thông báo về gói mới và tác động của chúng để có thể chuẩn bị cho bảng lương. Thông báo trong ứng dụng và qua e-mail tới mọi tenant ≥ 7 ngày trước ngày hiệu lực, hoặc được đánh dấu "báo trước ngắn"; với ngày hiệu lực trong quá khứ, danh sách các kỳ bị ảnh hưởng được chuyển cho Payroll/T&A để tính hồi tố; báo cáo áp dụng gói (tenant và lần chạy theo phiên bản gói) | NFR-SAAS-016, NFR-SAAS-017 | 5 | US-SAAS-032; CORE: notifications | Bản thân việc tính lại hồi tố thuộc Payroll/T&A |
| US-SAAS-034 | EP-SAAS-05 | Là Tenant Admin, tôi muốn yêu cầu ghi đè một tham số được phép để quy định có lợi hơn của công ty được áp dụng một cách hợp pháp. Chỉ các khóa được đánh dấu "luật cho phép"; phạm vi tenant hoặc pháp nhân; giá trị, thời hạn hiệu lực, tệp bằng chứng đính kèm; phê duyệt qua workflow của Core; có phiên bản và audit; tenant xem giá trị hiệu lực (gói + ghi đè) theo ngày | BR-SAAS-032 (hỗ trợ BR-PAY-010, BR-CORE-006) | 5 | US-SAAS-030; CORE: workflow, documents | |
| US-SAAS-035 | EP-SAAS-05 | Là người phụ trách nội dung pháp lý, tôi muốn quy tắc bảo hiểm đa hợp đồng là một tham số theo từng loại bảo hiểm để quy tắc thay đổi mà không cần sửa code nếu tư vấn kết luận khác. `insurance.multi_contract_rule` cho BHXH, BHYT, BHTN, TNLĐ-BNN với các giá trị FIRST_CONTRACT, HIGHEST_SALARY, EACH_EMPLOYER; mặc định BHXH/BHTN/BHYT = FIRST_CONTRACT, TNLĐ-BNN = EACH_EMPLOYER; hiển thị lịch sử thay đổi | NFR-SAAS-016 (hỗ trợ BR-PAY-010; quyết định R3-03) | 3 | US-SAAS-031 | 2 SP + 1 bậc cho `[verify-legal]` (quy tắc lương cao nhất cho BHYT còn nghi vấn, decision-log §8.1). `[high-uncertainty]`. Việc tính toán thuộc Payroll |
| US-SAAS-036 | EP-SAAS-05 | Là người phụ trách nội dung pháp lý, tôi muốn gói ban đầu được nạp và ký xác nhận để kỳ lương đầu tiên của pilot dùng các giá trị đã kiểm định. Giá trị 2026 (và 2027 nếu đã biết): lương tối thiểu vùng và bản đồ đơn vị hành chính → vùng, mức tham chiếu, tỷ lệ và mức trần BHXH/BHYT/BHTN/TNLĐ-BNN, biểu thuế TNCN và các khoản giảm trừ, ngưỡng thu nhập vãng lai, giới hạn làm thêm giờ, khung giờ làm đêm, số ngày phép, giới hạn thử việc/báo trước, lộ trình tuổi nghỉ hưu, hạn quyết toán, mẫu ngày nghỉ lễ; golden scenario; tư vấn ký xác nhận | NFR-SAAS-016 (hỗ trợ BR-PAY-010, NFR-PAY-007) | 5 | US-SAAS-032 | 3 SP + 1 bậc cho `[verify-legal]` (giá trị thuế TNCN/BHXH 2026 chưa được kiểm định, decision-log §5). `[high-uncertainty]` |
| US-SAAS-037 | EP-SAAS-05 | Là người dùng C&B, tôi muốn xem một kết quả đã dùng những giá trị tham số nào để mọi bảng lương trong quá khứ đều có thể giải thích và tái hiện được. Từ phiên bản gói, ghi đè, cấu hình và release được đóng dấu trên kết quả bảng lương hoặc chấm công, hiển thị các giá trị đang có hiệu lực; xuất phục vụ audit | NFR-SAAS-017 | 3 | US-SAAS-032; Payroll/T&A: đóng dấu phiên bản trên kết quả | Việc đóng dấu nằm ở Payroll/T&A và TECH |

### EP-SAAS-06 Quyền riêng tư & tuân thủ (PDPL)

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-038 | EP-SAAS-06 | Là DPO, tôi muốn có một danh mục dữ liệu cá nhân duy nhất cho mọi module để biết chúng tôi đang lưu giữ gì, vì sao và ở đâu. Module đăng ký trường: phân loại (cơ bản/nhạy cảm), mục đích, cơ sở pháp lý, thời hạn lưu trữ và mức tối thiểu theo luật, module sở hữu, bên xử lý và địa điểm hosting của họ; màn hình cho DPO và xuất Excel | BR-SAAS-079, BR-SAAS-024 | 5 | US-SAAS-013 | Địa điểm của bên xử lý phụ hỗ trợ bằng chứng lưu trữ dữ liệu trong nước; bản thân hosting thuộc TECH |
| US-SAAS-039 | EP-SAAS-06 | Là DPO, tôi muốn thời hạn lưu trữ và mức tối thiểu theo luật của mỗi loại dữ liệu được ghi nhận để các mức tối thiểu đã lập hồ sơ được nhìn thấy trước khi có job xóa dữ liệu. Lớp lưu trữ theo loại dữ liệu (các lớp lưu trữ tài liệu của Core là khóa); mức tối thiểu theo luật; chỉ đọc với tenant; không xóa dữ liệu ở P1 | BR-SAAS-079 | 2 | US-SAAS-038 | Job xóa/ẩn danh hóa thuộc BR-SAAS-082 (P2: không có dữ liệu hết hạn lưu trữ trong năm pilot) |
| US-SAAS-040 | EP-SAAS-06 | Là DPO, tôi muốn có nội dung sự đồng ý và thông báo có phiên bản theo từng mục đích để mỗi sự đồng ý tham chiếu đúng văn bản mà người đó đã xem. Mục đích (mặc định của nền tảng + mục đích của tenant), nội dung vi/en, phiên bản với ngày hiệu lực; phiên bản mới có thể yêu cầu đồng ý lại | BR-SAAS-080 | 5 | US-SAAS-038 | Thực thể có phiên bản = 5 |
| US-SAAS-041 | EP-SAAS-06 | Là nhân viên, tôi muốn đưa ra và rút lại sự đồng ý trên ESS để kiểm soát các mục đích sử dụng tùy chọn đối với dữ liệu của mình. Ghi nhận kèm thời điểm, kênh và phiên bản nội dung; rút lại bất cứ lúc nào; API `hasConsent(subject, purpose, asOf)` cho các module; báo cáo sổ đăng ký sự đồng ý cho DPO | BR-SAAS-080, BR-SAAS-075 | 5 | US-SAAS-040, 053 | People/Recruitment chỉ giữ tham chiếu |
| US-SAAS-042 | EP-SAAS-06 | Là DPO, tôi muốn ghi nhận và theo dõi yêu cầu của chủ thể dữ liệu để mỗi yêu cầu được trả lời đúng hạn kèm bằng chứng. Yêu cầu từ ESS hoặc do DPO nhập; các loại truy cập, chỉnh sửa, xóa, hạn chế, phản đối, rút lại sự đồng ý; bước xác minh danh tính; hạn chót lấy từ tham số pháp lý kèm nhắc nhở; bằng chứng hoàn tất | BR-SAAS-081 | 5 | US-SAAS-053; CORE: workflow, notifications | Hạn chót `[verify-legal]` (BRD Q5); được giữ dưới dạng tham số nên không cộng thêm bậc. `[high-uncertainty]` chỉ ghi nhận cho giá trị hạn chót |
| US-SAAS-043 | EP-SAAS-06 | Là DPO, tôi muốn xuất dữ liệu và ẩn danh hóa xuyên module bằng một cú nhấp để một DSR được thực hiện đầy đủ. Xuất dữ liệu của một chủ thể từ handler đã đăng ký của mọi module vào một kho lưu trữ; yêu cầu ẩn danh hóa được phân phát tới các module, tuân thủ thời hạn lưu trữ theo luật định kèm báo cáo legal hold; trạng thái theo từng module | BR-SAAS-081 | 8 | US-SAAS-042, 038; mọi module: DSR handler (CO-07) | `[high-uncertainty]`: phụ thuộc vào việc mọi module bàn giao handler; mức lưu trữ tối thiểu tương tác với các tuyên bố của DPO trong D-04/D-05 |
| US-SAAS-044 | EP-SAAS-06 | Là DPO của tenant, tôi muốn có mẫu DPIA được điền sẵn đầu vào từ nền tảng để có thể nhanh chóng lập hồ sơ DPIA của chúng tôi. Sinh tài liệu gồm danh mục dữ liệu cá nhân, bên xử lý, biện pháp bảo mật và tuyên bố lưu trữ dữ liệu trong nước; DPIA của chính nhà cung cấp với vai trò bên xử lý được lưu dưới dạng tài liệu | BR-SAAS-083 | 2 | US-SAAS-038; CORE: documents | Nội dung hồ sơ là công việc của Security/DPO, không phải xây dựng |
| US-SAAS-045 | EP-SAAS-06 | Là security officer của nhà cung cấp, tôi muốn có sổ đăng ký sự cố rò rỉ kèm mẫu thông báo để tenant và cơ quan chức năng được thông báo trong thời hạn. Sự cố: mức độ nghiêm trọng, tenant bị ảnh hưởng, loại dữ liệu, dòng thời gian; e-mail thông báo tenant từ mẫu (mục tiêu ≤ 24 h kể từ khi xác nhận); checklist và đồng hồ đếm ngược thông báo cơ quan chức năng trong 72 h; lưu giữ bằng chứng | BR-SAAS-084 | 5 | US-SAAS-013, 058 | Nội dung thông báo `[verify-legal]` (BRD Q5); chỉ thay đổi nội dung mẫu nên không cộng thêm bậc |

### EP-SAAS-07 Framework báo cáo, xuất dữ liệu & mẫu theo luật định

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-046 | EP-SAAS-07 | Là người dùng HR, tôi muốn có một danh mục báo cáo duy nhất cho mọi module để tìm và chạy mọi báo cáo chuẩn theo cùng một cách. Module đăng ký báo cáo (mã, tên vi/en, tham số, quyền, nguồn dữ liệu); danh mục theo module và vai trò; form tham số, xem trước và chạy; dấu thời gian "dữ liệu tại thời điểm" | BR-SAAS-046 | 8 | TECH: walking skeleton; CORE: permissions | Framework dùng chung; báo cáo của module được tính trong backlog của module |
| US-SAAS-047 | EP-SAAS-07 | Là DPO, tôi muốn báo cáo và file xuất tuân thủ phạm vi dữ liệu và che dữ liệu để không ai xuất được dữ liệu mà họ không thể thấy trên màn hình. Predicate phạm vi của Core áp dụng cho mọi báo cáo và file xuất; che dữ liệu mức trường cho các trường nhạy cảm; mọi lần xuất được audit (ai, báo cáo, tham số, số dòng) | BR-SAAS-046 | 5 | US-SAAS-046; CORE: scope predicate, masking, audit store | |
| US-SAAS-048 | EP-SAAS-07 | Là người dùng HR, tôi muốn báo cáo ở dạng Excel, PDF và CSV với tiếng Việt hiển thị đúng để có thể lưu trữ và chia sẻ. .xlsx streaming, PDF nhúng font tiếng Việt, CSV UTF-8; đồng bộ với giới hạn số dòng và thông báo rõ ràng khi vượt quá; dấu tiếng Việt chính xác ở cả ba định dạng | BR-SAAS-046, BR-SAAS-026, NFR-SAAS-013 | 5 | US-SAAS-046 | Xuất bất đồng bộ là BR-SAAS-053 (P2) |
| US-SAAS-049 | EP-SAAS-07 | Là người dùng, tôi muốn xuất bất kỳ màn hình danh sách nào để có thể làm việc với dữ liệu trong Excel. Nút xuất trên mọi lưới dữ liệu của module với bộ lọc hiện tại và các cột đang hiển thị, Excel/CSV, áp dụng phạm vi và che dữ liệu | BR-SAAS-046 | 3 | US-SAAS-047, 048 | Trước đây là BR-CORE-113 |
| US-SAAS-050 | EP-SAAS-07 | Là người phụ trách nội dung pháp lý, tôi muốn có mẫu theo luật định có phiên bản để phiên bản biểu mẫu mới của nhà nước được công bố mà không cần release ứng dụng. Mẫu: mã biểu mẫu, phiên bản, hiệu lực từ/đến, định dạng đầu ra (XML/Excel/PDF), ánh xạ trường, XSD; công bố qua quy trình phê duyệt gói; phiên bản được chọn theo kỳ báo cáo | BR-SAAS-052 | 8 | US-SAAS-032; TECH: document generation | Bản thân các biểu mẫu (D02-LT, 05/KK, 05/QTT, báo cáo lao động) được tính trong Payroll/People |
| US-SAAS-051 | EP-SAAS-07 | Là người dùng C&B, tôi muốn file theo luật định được kiểm tra hợp lệ trước khi tải xuống để cổng thông tin chấp nhận ngay lần đầu. XML được kiểm tra theo XSD của mẫu; Excel/PDF được điền từ ánh xạ; báo cáo lỗi theo trường; file lưu dưới dạng tài liệu kèm checksum; sinh lại vẫn giữ lịch sử | BR-SAAS-052, BR-SAAS-062 | 5 | US-SAAS-050; CORE: documents | |
| US-SAAS-052 | EP-SAAS-07 | Là người dùng C&B hoặc tài chính, tôi muốn có trung tâm file đầu ra để có thể tải file thanh toán ngân hàng, bút toán GL và file XML thuế TNCN và theo dõi những gì tôi đã tải lên. Liệt kê các file do Payroll sinh ra (file thanh toán ngân hàng, bút toán GL Excel/CSV, XML 05/KK và 05/QTT, PDF chứng từ khấu trừ thuế TNCN điện tử chưa ký); việc tải xuống được audit, step-up với file ngân hàng; đánh dấu "đã tải lên ngân hàng/eTax/kế toán" kèm ngày | BR-SAAS-059, BR-SAAS-060, BR-SAAS-062 | 5 | US-SAAS-024, 051; Payroll: sinh file (BR-PAY-056/059/110/113) | Bố cục và nội dung file thuộc Payroll; API trực tuyến là P2 (R2-01) |

### EP-SAAS-08 Khung web ESS, bản địa hóa & kênh e-mail

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-053 | EP-SAAS-08 | Là nhân viên, tôi muốn có cổng web ESS responsive để có thể dùng từ trình duyệt trên điện thoại. Bố cục từ 360 px; menu và route registry nơi các module gắn trang của mình (hồ sơ, phiếu lương/chứng từ khấu trừ thuế TNCN, nghỉ phép, chấm công và điều chỉnh, phê duyệt, tài liệu, thông báo, sự đồng ý); các mục được kiểm soát theo entitlement và quyền; không có check-in | BR-SAAS-075, NFR-SAAS-012 | 5 | US-SAAS-009, 055 | Trang ESS của module được tính trong backlog của module |
| US-SAAS-054 | EP-SAAS-08 | Là nhân viên, tôi muốn có trang tài khoản để tự quản lý thiết lập bảo mật của mình. Đổi mật khẩu, phương thức MFA, ngôn ngữ, các phiên đang hoạt động kèm "đăng xuất khỏi mọi thiết bị" | BR-SAAS-075, BR-SAAS-039, BR-SAAS-043 | 3 | US-SAAS-023, 025, 053 | Trang mật khẩu PDF phiếu lương thuộc Payroll (R3-04) |
| US-SAAS-055 | EP-SAAS-08 | Là người dùng, tôi muốn toàn bộ sản phẩm bằng tiếng Việt hoặc tiếng Anh để không bao giờ thấy ngôn ngữ lẫn lộn. Mọi chuỗi được tách ra ngoài (ICU message) với kiểm tra lúc build để phát hiện khóa thiếu; định dạng theo locale (VND với dấu "." phân cách hàng nghìn, ngày); các helper sắp xếp theo tiếng Việt và tìm kiếm không phân biệt dấu cho mọi module | BR-SAAS-026, NFR-SAAS-013 | 5 | TECH: walking skeleton | |
| US-SAAS-056 | EP-SAAS-08 | Là BA, tôi muốn xuất và nhập các gói bản dịch để nội dung vi/en được rà soát bên ngoài code. Xuất/nhập gói theo module; mẫu e-mail hệ thống vi/en | BR-SAAS-026, NFR-SAAS-013 | 2 | US-SAAS-055 | |
| US-SAAS-057 | EP-SAAS-08 | Là người dùng trên trình duyệt cũ, tôi muốn được thông báo rằng trình duyệt không được hỗ trợ để biết vì sao một trang hoạt động không đúng. Danh sách hỗ trợ: 2 phiên bản mới nhất của Chrome, Edge, Safari, Firefox; thông báo với các trình duyệt khác; breakpoint responsive trong thư viện component | NFR-SAAS-012 | 2 | US-SAAS-053 | Chạy kiểm thử đa trình duyệt thuộc TECH/QA |
| US-SAAS-058 | EP-SAAS-08 | Là Tenant Admin, tôi muốn e-mail hệ thống được gửi tin cậy dưới tên của chúng tôi để nhân viên tin tưởng và nhận được. Yêu cầu thông báo của Core được gửi qua e-mail với tên người gửi của tenant; trạng thái đã gửi, bị trả lại và thất bại được trả về Core; giới hạn tốc độ theo tenant; chỉ cho phép tệp đính kèm với ngoại lệ PDF phiếu lương | BR-SAAS-066 | 5 | TECH: SMTP relay (DKIM/SPF/DMARC), job queue; CORE: notification centre | SMS, Zalo và push là P2 (R2-01) |

### EP-SAAS-09 Chương trình go-live bằng số dư đầu kỳ

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-059 | EP-SAAS-09 | Là người dùng HR của tenant, tôi muốn có một danh mục mẫu import duy nhất để biết cần điền gì và theo thứ tự nào. Danh mục các mẫu do Core/People/Payroll/T&A đăng ký trên import engine của Core; tải xuống vi/en kèm danh sách mã và hướng dẫn; thứ tự P9; trạng thái theo mẫu và pháp nhân | BR-SAAS-069 | 3 | CORE: import engine; các loại import của module | Nội dung mẫu được ước lượng trong module sở hữu (hướng dẫn §4) |
| US-SAAS-060 | EP-SAAS-09 | Là tư vấn onboarding, tôi muốn có kế hoạch cut-over theo pháp nhân để số dư đầu kỳ được ghi ngày nhất quán. Tháng go-live theo pháp nhân; ngày chốt số liệu = cuối tháng trước; hiển thị ngày bắt đầu năm tính thuế; trạng thái mở/khóa; checklist go-live | BR-SAAS-088, BR-SAAS-071 | 5 | US-SAAS-001; CORE: legal entities | |
| US-SAAS-061 | EP-SAAS-09 | Là tư vấn onboarding, tôi muốn có bảng điều khiển onboarding thực thi thứ tự nạp để không file nào được commit trước những gì nó phụ thuộc. Chỉ kiểm tra hợp lệ cho đến khi sạch lỗi, sau đó commit sau khi được duyệt, theo thứ tự phụ thuộc P9; các lô liên kết với import job của Core; cho phép rollback khi pháp nhân còn mở; giữ các ID tham chiếu bên ngoài | BR-SAAS-070, BR-SAAS-071 | 5 | US-SAAS-059, 060; CORE: import engine (BR-CORE-110/114) | Engine (validate/commit/rollback, báo cáo lỗi) thuộc Core |
| US-SAAS-062 | EP-SAAS-09 | Là tư vấn onboarding, tôi muốn các cột không xác định được ánh xạ trong quá trình kiểm tra hợp lệ để thuộc tính đặc thù của tenant không bị mất khi không có custom field. Phát hiện cột không xác định; ánh xạ vào trường chuẩn hoặc tệp tài liệu đính kèm; ánh xạ được ghi trong báo cáo kiểm tra hợp lệ; cột chưa ánh xạ chặn việc commit | BR-SAAS-071 | 5 | US-SAAS-061 | R3-09 |
| US-SAAS-063 | EP-SAAS-09 | Là người dùng C&B, tôi muốn go-live bị chặn nếu thiếu bất kỳ tháng nào trước go-live để quyết toán thuế TNCN có đủ cả năm tính thuế. Theo nhân viên và pháp nhân, mỗi tháng từ tháng 1 đến tháng trước go-live phải có thu nhập chịu thuế, thu nhập miễn thuế, bảo hiểm đã khấu trừ, giảm trừ gia cảnh và thuế TNCN đã khấu trừ; liệt kê các tháng thiếu; chặn go-live cho pháp nhân đó; go-live vào tháng 1 thì không cần | BR-SAAS-089 | 5 | US-SAAS-060; Payroll: loại import lũy kế đầu năm (YTD) | |
| US-SAAS-064 | EP-SAAS-09 | Là người phụ trách tài chính của tenant, tôi muốn nhập tổng kiểm soát của chúng tôi để số dư đã import được đối chiếu với sổ sách của chính chúng tôi. Mẫu Excel và màn hình nhập tổng từ bảng lương cuối cùng của hệ thống cũ, VSS C12-TS, 05/KK đã nộp và báo cáo nghỉ phép; theo pháp nhân và tùy chọn theo nhân viên | BR-SAAS-072 | 5 | US-SAAS-060; CORE: import engine | Mẫu import Excel = 5 |
| US-SAAS-065 | EP-SAAS-09 | Là tư vấn onboarding, tôi muốn có báo cáo đối soát giá trị đã import so với tổng kiểm soát để mọi chênh lệch được phát hiện trước go-live. Theo nhân viên và tổng: lũy kế gross và net, thu nhập tính thuế TNCN, thuế TNCN đã khấu trừ, người phụ thuộc, lương đóng BHXH/BHYT/BHTN và số tháng, phép được hưởng/đã nghỉ/còn lại bao gồm phép chuyển sang, khoản vay/tạm ứng; mỗi chênh lệch cần có giải thích; điều kiện qua cổng = không còn chênh lệch chưa giải thích | BR-SAAS-072 | 8 | US-SAAS-061, 064; People/Payroll/T&A: sổ cái đã import | `[high-uncertainty]`: quy tắc đối khớp theo từng chỉ số (làm tròn, ranh giới tháng, phép chuyển sang) chưa được đặc tả |
| US-SAAS-066 | EP-SAAS-09 | Là trưởng nhóm C&B của tenant, tôi muốn ký xác nhận đối soát để go-live dựa trên sự phê duyệt của chúng tôi. Người phụ trách HR/C&B và tài chính ký xác nhận qua workflow của Core sau khi tư vấn đã kiểm tra; ký xác nhận gắn cố định với một lần chạy đối soát; mức sẵn sàng go-live theo pháp nhân | BR-SAAS-072, BR-SAAS-088 | 3 | US-SAAS-065; CORE: workflow | R2-13 |
| US-SAAS-067 | EP-SAAS-09 | Là người dùng C&B, tôi muốn số dư đầu kỳ bị khóa khi bảng lương thường đầu tiên được phê duyệt để lịch sử thuế TNCN/BHXH không thể thay đổi âm thầm. Khóa khi phê duyệt lần chạy thường đầu tiên của pháp nhân; các lần import số dư đầu kỳ sau đó bị từ chối; trạng thái khóa hiển thị trên kế hoạch cut-over | BR-SAAS-088 | 3 | US-SAAS-060; Payroll: sự kiện lần chạy được phê duyệt | |
| US-SAAS-068 | EP-SAAS-09 | Là người dùng C&B, tôi muốn có các lô điều chỉnh có audit sau khi khóa để lỗi thực sự có thể được sửa một cách truy vết được. Lô điều chỉnh kèm lý do và phê duyệt; cập nhật sổ cái lũy kế, lịch sử BHXH, nghỉ phép và khoản vay qua handler của module; sự kiện được Payroll và T&A tiếp nhận làm đầu vào hồi tố/điều chỉnh | BR-SAAS-088 | 5 | US-SAAS-067; CORE: import engine, workflow; handler điều chỉnh của module | Workflow phê duyệt = 5 |
| US-SAAS-069 | EP-SAAS-09 | Là người dùng C&B, tôi muốn import bảng lương cuối cùng của hệ thống cũ để YourHRM có thể được so sánh với nó. Excel theo nhân viên và khoản lương; ánh xạ khoản lương cũ sang thành phần YourHRM; báo cáo kiểm tra hợp lệ | BR-SAAS-073 | 5 | US-SAAS-061; CORE: import engine | R2-03 |
| US-SAAS-070 | EP-SAAS-09 | Là trưởng nhóm C&B, tôi muốn so sánh bảng lương một tháng để tin tưởng hệ thống mới trước lần chạy thật đầu tiên. Lần chạy so sánh cho tháng đó từ dữ liệu đầu vào đã import (được tính, không bao giờ được phê duyệt, sau đó hủy bỏ); báo cáo chênh lệch theo nhân viên và khoản lương kèm dung sai; ghi chú cho từng chênh lệch; xuất Excel | BR-SAAS-073 | 8 | US-SAAS-069; Payroll: vòng đời lần chạy (R2-C10) | `[high-uncertainty]`: cần payroll engine đầy đủ và một tháng dữ liệu đầu vào; ánh xạ khoản lương phụ thuộc bố cục bảng lương của pilot |

### EP-SAAS-10 Tính năng vận hành nền tảng

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-SAAS-071 | EP-SAAS-10 | Là Tenant Admin hoặc operator, tôi muốn có màn hình theo dõi job để xem tiến độ của import, lần chạy lương và công bố. Danh sách job (loại, trạng thái, tiến độ, số lần thử, lỗi); hủy hoặc thử lại khi loại job cho phép; job bị tạm dừng do trạng thái tenant được hiển thị đúng như vậy | BR-SAAS-090 | 3 | TECH: job queue runtime | Runtime scheduler/queue thuộc TECH |
| US-SAAS-072 | EP-SAAS-10 | Là platform operator, tôi muốn các thay đổi tính toán nằm sau toggle theo tenant và release ring để tenant pilot chủ động tham gia trước mọi tenant khác. Tenant được gán vào ring (pilot, early, general); toggle hành vi tính toán theo tenant với tùy chọn opt-in hoặc ngày đã thông báo, đặt bằng cấu hình; có audit; tenant xem các toggle ảnh hưởng tới mình | NFR-SAAS-015 | 3 | US-SAAS-013 | Feature-flag service (BR-SAAS-009) là P2; P1 = toggle cấu hình (R2-19) |
| US-SAAS-073 | EP-SAAS-10 | Là Tenant Admin, tôi muốn nhận release note và thông báo bảo trì trước để lên kế hoạch phù hợp. Release note vi/en được công bố ≥ 3 ngày làm việc trước release; banner bảo trì trong ứng dụng; cảnh báo khi khung bảo trì nằm ngoài 22:00–05:00 ICT hoặc trong giai đoạn tính lương (ngày 25–5) | NFR-SAAS-015, NFR-SAAS-001 | 3 | US-SAAS-013 | |
| US-SAAS-074 | EP-SAAS-10 | Là Tenant Admin, tôi muốn gửi yêu cầu hỗ trợ ngay trong ứng dụng để nhà cung cấp có được ngữ cảnh và đồng hồ SLA bắt đầu chạy. Form với mức độ nghiêm trọng, module, mô tả, ngôn ngữ vi/en; đính kèm tenant context; ticket id dùng lại được trong quyền hỗ trợ | NFR-SAAS-020, BR-SAAS-033 | 2 | US-SAAS-016 | Bản thân SLA là một quy trình dịch vụ |
| US-SAAS-075 | EP-SAAS-10 | Là SRE, tôi muốn có thiết lập hạn mức theo tenant trong back-office để một tenant gây ồn có thể bị giới hạn mà không cần deploy. Giới hạn theo tenant về job đồng thời, lần chạy báo cáo và file xuất, mặc định theo mức trần 20% của pool; thay đổi được audit | NFR-SAAS-005 | 2 | US-SAAS-013; TECH: job queue fair-share | Việc thực thi thuộc TECH |

## 3. Kiểm tra độ bao phủ (BR-SAAS và NFR-SAAS mức P1)

42 BR P1 + 17 NFR P1 từ mvp-scope §9.6. "TECH" nghĩa là yêu cầu được bàn giao bởi `technical-enablers-backlog.md`; ID sẽ được liên kết khi PM hợp nhất.

### 3.1 Yêu cầu nghiệp vụ

| P1 ID | Tiêu đề ngắn | Được bao phủ bởi |
|---|---|---|
| BR-SAAS-001 | Bản ghi & vòng đời tenant | US-SAAS-001, 002 |
| BR-SAAS-002 | Pháp nhân / bên nhận hóa đơn | US-SAAS-003 |
| BR-SAAS-003 | Operator khởi tạo ≤ 10 phút | US-SAAS-004, 005 |
| BR-SAAS-007 | Thuê bao module | US-SAAS-007, 009 |
| BR-SAAS-008 | Entitlement service | US-SAAS-008, 009 |
| BR-SAAS-012 | Số nhân viên tính phí | US-SAAS-011, 012 |
| BR-SAAS-021 | tenant_id + RLS + bộ lọc ứng dụng | **TECH** (tenancy/RLS). Hạ tầng nền tảng, ngoài backlog này |
| BR-SAAS-023 | Kiểm thử cô lập chặn release | **TECH** (bộ kiểm thử cô lập trong CI) |
| BR-SAAS-024 | Lưu trữ dữ liệu tại VN | **TECH** (hosting tại VN, không gửi PII cho dịch vụ nước ngoài); US-SAAS-038 ghi nhận địa điểm của bên xử lý |
| BR-SAAS-025 | Thiết lập tenant | US-SAAS-006 |
| BR-SAAS-026 | vi/en đầy đủ | US-SAAS-055, 056, 048 |
| BR-SAAS-032 | Back-office của operator (bao gồm gói pháp lý, phát hành dữ liệu danh mục) | US-SAAS-013, 014, 015, 030, 031, 034 |
| BR-SAAS-033 | Quyền hỗ trợ | US-SAAS-016, 017, 074 |
| BR-SAAS-034 | Che dữ liệu trong phiên hỗ trợ | US-SAAS-018 |
| BR-SAAS-037 | Log truy cập của operator hiển thị cho tenant | US-SAAS-019 |
| BR-SAAS-038 | Chính sách mật khẩu | US-SAAS-021, 022 |
| BR-SAAS-039 | MFA | US-SAAS-005, 023, 024, 054 |
| BR-SAAS-043 | Quản lý phiên | US-SAAS-025, 026, 054 |
| BR-SAAS-045 | Đăng nhập ESS & kích hoạt bằng OTP (bao gồm người đã nghỉ việc theo OD-8) | US-SAAS-021, 026, 027, 028 |
| BR-SAAS-046 | Framework báo cáo & xuất danh sách | US-SAAS-046, 047, 048, 049 |
| BR-SAAS-052 | Framework báo cáo theo luật định | US-SAAS-050, 051 |
| BR-SAAS-059 | Xuất file bút toán kế toán | US-SAAS-052 (nội dung: Payroll BR-PAY-113) |
| BR-SAAS-060 | File thanh toán ngân hàng | US-SAAS-052 (nội dung: Payroll BR-PAY-110) |
| BR-SAAS-062 | XML eTax & chứng từ khấu trừ thuế TNCN điện tử (PDF chưa ký) | US-SAAS-051, 052 (nội dung: Payroll BR-PAY-056/057/059) |
| BR-SAAS-066 | Nhắn tin: e-mail | US-SAAS-058 (hosting SMTP relay: TECH) |
| BR-SAAS-069 | Danh mục mẫu import | US-SAAS-059 (mẫu: module sở hữu) |
| BR-SAAS-070 | Import chỉ kiểm tra hợp lệ, idempotent, đảo ngược được | US-SAAS-061 (engine: Core BR-CORE-110/111) |
| BR-SAAS-071 | Import số dư đầu kỳ | US-SAAS-060, 061, 062 |
| BR-SAAS-072 | Đối soát số dư đầu kỳ | US-SAAS-064, 065, 066 |
| BR-SAAS-073 | So sánh bảng lương một tháng | US-SAAS-069, 070 |
| BR-SAAS-075 | Web ESS responsive | US-SAAS-053, 054, 041 |
| BR-SAAS-079 | Danh mục dữ liệu cá nhân | US-SAAS-038, 039 |
| BR-SAAS-080 | Quản lý sự đồng ý | US-SAAS-040, 041 |
| BR-SAAS-081 | Công cụ DSR | US-SAAS-042, 043 |
| BR-SAAS-083 | Hồ sơ DPIA | US-SAAS-044 (việc soạn hồ sơ là công việc của Security/DPO) |
| BR-SAAS-084 | Thông báo sự cố rò rỉ | US-SAAS-045 |
| BR-SAAS-085 | Audit bảo mật (ghi vào kho của Core) | US-SAAS-017, 020, 029 (kho: Core BR-CORE-090–094) |
| BR-SAAS-087 | Phân loại cấp độ an toàn (Nghị định 85) | **Không có story: không phải xây dựng.** Được xếp P1 với nhãn "theo luật định, không phải xây dựng"; đây là hồ sơ tuân thủ của nhà cung cấp dành cho Security lead/PM, với đầu vào từ US-SAAS-038 và công việc bảo mật của TECH |
| BR-SAAS-088 | Cut-over & khóa go-live | US-SAAS-060, 066, 067, 068 |
| BR-SAAS-089 | Các tháng thuế TNCN trước go-live | US-SAAS-063 |
| BR-SAAS-090 | Job scheduler / queue | **TECH** (runtime); US-SAAS-071 (UI theo dõi), US-SAAS-002 (tạm dừng theo trạng thái tenant) |
| BR-SAAS-091 | Năng lực baseline luôn bật | US-SAAS-010 |

Kết quả: **42 / 42 BR P1 đã được liệt kê**: 38 được bao phủ bởi các story ở đây (BR-SAAS-024 và 090 một phần còn do TECH), 3 chỉ do TECH bàn giao (021, 023, cộng phần hosting của 024), và 1 không phải xây dựng (087). Không BR P1 nào bị bỏ sót.

### 3.2 Yêu cầu phi chức năng

| P1 ID | Tiêu đề ngắn | Được bao phủ bởi |
|---|---|---|
| NFR-SAAS-001 | Tính sẵn sàng 99.5% | **TECH**; US-SAAS-073 (thông báo bảo trì và kiểm soát khung bảo trì) |
| NFR-SAAS-002 | Hiệu năng tính lương | **TECH** (kiểm thử hiệu năng, spike SP-1) cùng với payroll engine |
| NFR-SAAS-003 | Hiệu năng tương tác | **TECH** |
| NFR-SAAS-005 | Khả năng mở rộng & giới hạn tenant gây ồn | **TECH** (queue fair-share, hạn mức); US-SAAS-075 (thiết lập hạn mức) |
| NFR-SAAS-006 | Backup & DR tại VN | **TECH** |
| NFR-SAAS-007 | Mã hóa | **TECH** |
| NFR-SAAS-008 | Secrets trong vault | **TECH** |
| NFR-SAAS-009 | ASVS L2, quét lỗ hổng, pen test | **TECH** |
| NFR-SAAS-010 | Observability & thời hạn lưu log | **TECH** |
| NFR-SAAS-012 | Hỗ trợ trình duyệt, 360 px | US-SAAS-053, 057 (chạy kiểm thử đa trình duyệt: TECH/QA) |
| NFR-SAAS-013 | Bản địa hóa đầy đủ | US-SAAS-055, 056, 048 |
| NFR-SAAS-014 | Release không downtime | **TECH** |
| NFR-SAAS-015 | Quản trị release & toggle | US-SAAS-072, 073 (release pipeline: TECH) |
| NFR-SAAS-016 | SLA thay đổi quy định pháp luật | US-SAAS-030, 031, 032, 033, 035, 036 (LPS runtime: TECH) |
| NFR-SAAS-017 | Khả năng truy vết tính toán | US-SAAS-033, 037 (đóng dấu phiên bản: Payroll/T&A và TECH) |
| NFR-SAAS-020 | SLA hỗ trợ | US-SAAS-074 (bản thân SLA là một quy trình dịch vụ) |
| NFR-SAAS-021 | Năng lực cao điểm | **TECH** (kiểm thử tải trước mỗi đợt cao điểm) |

Kết quả: **17 / 17 NFR P1 đã được liệt kê.** Các NFR mang tính tính năng được bao phủ ở đây: 012, 013, 015, 016, 017, 020 (và một phần của 001 và 005). **Dự kiến nằm trong backlog TECH:** NFR-SAAS-001, 002, 003, 005, 006, 007, 008, 009, 010, 014, 021 (và phần pipeline hoặc kiểm thử của 012, 015, 017).

### 3.3 Yêu cầu của các module khác được hỗ trợ tại đây
BR-PAY-010 và NFR-PAY-007 (tham số pháp lý, baseline) và BR-CORE-006 (bản đồ vùng) được quản trị và kiểm soát trong EP-SAAS-05; runtime của chúng thuộc TECH. Payroll và Core chỉ tính phần đăng ký khóa. BR-CORE-041 (truy cập hỗ trợ) được thực thi từ phía nền tảng trong US-SAAS-017. BR-CORE-080/087 (dữ liệu danh mục toàn cục) có UI phát hành trong US-SAAS-014/015.

## 4. Tổng hợp và ghi chú rủi ro

| Epic | Số story | SP |
|---|---|---|
| EP-SAAS-01 Vòng đời & khởi tạo tenant | 6 | 27 |
| EP-SAAS-02 Thuê bao, entitlement & số lượng tính phí | 6 | 32 |
| EP-SAAS-03 Back-office của operator & truy cập hỗ trợ | 8 | 34 |
| EP-SAAS-04 Định danh & truy cập | 9 | 39 |
| EP-SAAS-05 Legal Parameter Service: tính năng nghiệp vụ | 8 | 39 |
| EP-SAAS-06 Quyền riêng tư & tuân thủ | 8 | 37 |
| EP-SAAS-07 Báo cáo, xuất dữ liệu & mẫu theo luật định | 7 | 39 |
| EP-SAAS-08 Khung ESS, bản địa hóa & e-mail | 6 | 22 |
| EP-SAAS-09 Chương trình go-live bằng số dư đầu kỳ | 12 | 60 |
| EP-SAAS-10 Tính năng vận hành nền tảng | 5 | 13 |
| **Tổng** | **75** | **342** |

Góc nhìn kế hoạch (hướng dẫn §6): 342 SP ≈ 342 ngày công developer, ≈ 479 ngày khi cộng +40% chi phí phát sinh; ≈ 49 developer-sprint với 7 SP mỗi sprint, tức khoảng 8 developer trong 6 sprint xây dựng chỉ riêng cho backlog này.

### Ghi chú rủi ro (`[high-uncertainty]`)
| Story | SP | Lý do không chắc chắn |
|---|---|---|
| US-SAAS-070 Báo cáo chênh lệch so sánh bảng lương | 8 | Cần payroll engine đầy đủ và một tháng dữ liệu đầu vào đã import hoàn chỉnh; ánh xạ khoản lương phụ thuộc bố cục bảng lương của pilot; có thể cần tách nhỏ khi đã biết bố cục |
| US-SAAS-043 Xuất/ẩn danh hóa DSR xuyên module | 8 | Phụ thuộc vào việc mọi module bàn giao DSR handler; mức lưu trữ tối thiểu và tuyên bố về backup (D-04, D-05) chưa được DPO kiểm định |
| US-SAAS-065 Báo cáo đối soát số dư đầu kỳ | 8 | Quy tắc đối khớp và dung sai theo từng chỉ số (làm tròn, ranh giới tháng, phép chuyển sang) chưa được đặc tả; nằm trên đường găng của go-live |
| US-SAAS-036 Nội dung gói pháp lý ban đầu | 5 | `[verify-legal]`: giá trị thuế TNCN và BHXH 2026 chưa được kiểm định (decision-log §3, §5) |
| US-SAAS-004 Khởi tạo ≤ 10 phút | 8 | Điều phối Keycloak, tenancy, Core và seed kèm rollback; mục tiêu thời gian chưa được đo |
| US-SAAS-035 Quy tắc bảo hiểm đa hợp đồng | 3 | `[verify-legal]`: PM nghi ngờ quy tắc BHYT do sponsor chọn (decision-log §8.1) |
| US-SAAS-011 Số lượng tính phí | 8 | Cách xử lý cộng tác viên (CTV) không có trong bảng lương vẫn còn mở (BRD Q4) |

Ghi chú khác:
- EP-SAAS-09 (60 SP) nằm trên đường găng của go-live và phụ thuộc vào loại import của mọi module; lên kế hoạch từ sprint 3, sau import engine của Core.
- Hạn chót DSR (US-SAAS-042) và nội dung thông báo sự cố rò rỉ (US-SAAS-045) là `[verify-legal]` nhưng được giữ dưới dạng tham số hoặc nội dung mẫu, nên không cộng thêm bậc.
- Dự phòng cắt giảm phạm vi nếu cần: US-SAAS-073 (chỉ thông báo qua e-mail), US-SAAS-056, US-SAAS-057, US-SAAS-075 và báo cáo áp dụng gói trong US-SAAS-033 (khoảng 8 SP).
