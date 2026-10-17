# Backlog People: Phase 1 (v0.1, 2026-10-16)

| Mục | Giá trị |
|---|---|
| Chủ sở hữu | BA – People |
| Phạm vi | Toàn bộ BR-PPL và NFR-PPL mức **P1** trong [mvp-scope.md](../brd/mvp-scope.md) §9.2: **47 BR + 10 NFR = 57 yêu cầu** |
| Nguồn | [02-people.md](../brd/modules/02-people.md) v0.2; [decision-log.md](../../00-project-management/decision-log.md) vòng 1–3; [estimation-guide.md](estimation-guide.md) v0.1 |
| Ước lượng | SP theo dãy Fibonacci, đối chiếu thang tham chiếu tại §4 của hướng dẫn ước lượng (1 SP ≈ 1 ngày công developer, chưa tính +40% chi phí phát sinh khi bàn giao) |

**Quyền sở hữu engine.** Core xây dựng workflow engine, thông báo, dịch vụ tài liệu và mẫu PDF, đánh số, import engine, kho audit và phân quyền (vai trò, phạm vi dữ liệu, che dữ liệu theo trường). SaaS xây dựng khung ESS/MSS, xác thực tăng cường (step-up authentication), Legal Parameter Service, sổ đăng ký sự đồng ý (consent registry), danh mục dữ liệu cá nhân (PD inventory) và điều phối DSR. Các technical enabler xây dựng outbox/event bus, tenancy/RLS, các helper effective-dating trong `shared/common`, dịch vụ khóa mã hóa trường và job queue. Các story ở đây **chỉ tính phần việc của People**: các thực thể, quy tắc, màn hình của People, cùng việc đăng ký/cấu hình các engine nói trên (process type, mẫu, quyền, loại import, trường dữ liệu cá nhân, event schema).

**Các quyết định đã áp dụng.** Không có ứng dụng mobile (ESS = web responsive, R5/OD-9); không có chữ ký điện tử hay hợp đồng lao động điện tử (R2-10, BR-PPL-058 P2), nên hợp đồng và quyết định được ký trên giấy và tải bản scan lên; không có VNeID (BR-PPL-012 P2); không có trang MSS (BR-PPL-121 P2): quản lý phê duyệt qua inbox của Core; không có yêu cầu thay đổi dữ liệu cá nhân qua ESS (BR-PPL-022 P2): HR nhập thay đổi, ngoại trừ người phụ thuộc và tài khoản ngân hàng, vốn được BR-PPL-120 giữ lại dưới dạng yêu cầu ESS; không có thay đổi hàng loạt hay ánh xạ tái cơ cấu (BR-PPL-036/039 P2); chỉ dùng VND (R2-09); kiêm nhiệm giữa các pháp nhân của cùng một tenant là mức cơ bản P1 (R2-16); bảo hiểm theo từng loại là tham số pháp lý `insurance.multi_contract_rule` với BHXH/BHTN/BHYT = FIRST_CONTRACT, TNLĐ-BNN = EACH_EMPLOYER (R3-03, `[verify-legal]`); import số dư đầu kỳ thay thế cho migration (D-09), khách hàng tự chuẩn bị file (R2-13), thuộc tính tùy biến được ánh xạ vào trường chuẩn hoặc tệp đính kèm (R3-09); trường trình độ tối thiểu cho sổ quản lý lao động (R3-07); người lao động đã nghỉ việc đăng nhập bằng OTP qua e-mail (OD-8); số tiền lương không mã hóa ở mức cột (X-01).

**Ký hiệu "Phụ thuộc".** Backlog Core/SaaS chưa đánh số story, nên phụ thuộc vào chúng được ghi bằng yêu cầu P1 cung cấp engine đó (ví dụ `BR-CORE-050`); technical enabler được ghi là `TECH:<enabler>` (tên lấy từ estimation-guide §2). Thay bằng ID `US-CORE-…`, `US-SAAS-…`, `US-TECH-…` khi PM hợp nhất.

Persona: **HR** = Quản trị viên HR (C&B / HR Ops); **C&B** = người dùng HR có quyền `compensation`; **Employee** = người dùng ESS; **Former employee** = người lao động đã nghỉ việc; **DPO**; **Payroll/T&A** = module sử dụng dữ liệu (persona hệ thống); **Implementation consultant** = tư vấn triển khai go-live của chúng ta.

---

## 1. Epic

| Epic ID | Tên | Mục tiêu | Số story | SP |
|---|---|---|---|---|
| EP-PPL-01 | Cá nhân & dữ liệu cá nhân | Mỗi cá nhân chỉ có một bản ghi person trong mỗi tenant, với dữ liệu định danh, thuế, BHXH, cư trú và tình trạng đặc biệt mà mọi báo cáo theo luật định đều cần | 16 | 65 |
| EP-PPL-02 | Quan hệ lao động & kiêm nhiệm | Quan hệ lao động (employment) theo từng pháp nhân, tuyển dụng/kích hoạt, kiêm nhiệm với employment chính theo mục đích và tham gia bảo hiểm theo từng loại | 10 | 53 |
| EP-PPL-03 | Gia đình, người phụ thuộc & tài khoản ngân hàng | Người phụ thuộc giảm trừ thuế TNCN đăng ký một lần và được phê duyệt; tài khoản nhận lương có chia tách | 6 | 29 |
| EP-PPL-04 | Phân công theo ngày hiệu lực | Lịch sử phân công đầy đủ, thay đổi công việc có phê duyệt, thay đổi tương lai/hồi tố, quy tắc điều chuyển tạm thời | 8 | 40 |
| EP-PPL-05 | Hồ sơ lương (lương thỏa thuận) | People là system of record cho lương thỏa thuận GROSS/NET, chỉ thay đổi bằng quyết định/phụ lục, theo quyền `compensation` | 6 | 29 |
| EP-PPL-06 | Hợp đồng lao động & phụ lục | Vòng đời hợp đồng theo Bộ luật Lao động: loại hợp đồng, thử việc, quy tắc hai hợp đồng xác định thời hạn, hết hạn, phụ lục, tạm hoãn, tài liệu | 10 | 48 |
| EP-PPL-07 | Quyết định nhân sự & thông báo People | Quyết định có đánh số cho mọi thay đổi nhân sự P1; mẫu cảnh báo của People | 3 | 13 |
| EP-PPL-08 | Chấm dứt HĐLĐ & kích hoạt quyết toán cuối cùng | Chấm dứt hợp pháp: lý do, báo trước, bảo vệ, điều kiện trợ cấp thôi việc, sự kiện gửi Payroll | 8 | 40 |
| EP-PPL-09 | Sổ quản lý lao động & báo cáo lao động theo luật định | Sổ quản lý lao động và báo cáo 6 tháng theo Nghị định 145, thông báo biến động lao động hằng tháng | 4 | 27 |
| EP-PPL-10 | Trang web ESS cho People | Tự phục vụ của nhân viên trong khung SaaS: hồ sơ, quan hệ lao động, người phụ thuộc, ngân hàng, nghỉ việc | 7 | 26 |
| EP-PPL-11 | Sự kiện nhân viên & read API | Sự kiện People có giao dịch, có phiên bản và read API nhân viên theo ngày as-of cho các module khác | 4 | 23 |
| EP-PPL-12 | PDPL cho dữ liệu People | Phân loại, tham chiếu sự đồng ý, che dữ liệu, mã hóa, ghi log truy cập, bộ xử lý DSR | 6 | 32 |
| EP-PPL-13 | Import số dư đầu kỳ hồ sơ nhân viên | Mẫu Excel, kiểm tra hợp lệ, nạp idempotent và đối soát cho go-live giữa năm | 5 | 31 |
| **Tổng** | | | **93** | **456** |

---

## 2. Story

### EP-PPL-01 Cá nhân & dữ liệu cá nhân

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-001 | EP-PPL-01 | Là HR, tôi muốn có nền tảng module People (schema `hr`, RLS trên mọi bảng, quyền chức năng và phạm vi dữ liệu của People được đăng ký trong Core) để mọi tính năng People được cô lập theo tenant và kiểm tra quyền ngay từ ngày đầu. AC: mọi bảng `hr` có `tenant_id` + RLS policy và một kiểm thử âm tính chéo tenant; quyền/phạm vi People (pháp nhân, đơn vị tổ chức, SELF) hiển thị trong màn hình quản trị vai trò của Core; không tồn tại đường tra cứu person chéo tenant | NFR-PPL-008 | 5 | TECH:walking-skeleton, TECH:tenancy-RLS, BR-CORE-031, BR-CORE-032, BR-CORE-033 | Chỉ đăng ký; việc thực thi phạm vi thuộc Core (BR-CORE-035) |
| US-PPL-002 | EP-PPL-01 | Là HR, tôi muốn tạo và duy trì một person (họ tên có dấu + dạng không dấu được sinh tự động, ngày sinh, giới tính, quốc tịch, dân tộc, tôn giáo, quê quán) để dữ liệu định danh cho BHXH/thuế TNCN và sổ quản lý lao động chỉ cần nhập một lần. AC: danh mục tra cứu từ dữ liệu tham chiếu của Core; tôn giáo/dân tộc được đánh dấu nhạy cảm; thay đổi được ghi audit | BR-PPL-010, NFR-PPL-008 | 5 | US-PPL-001, BR-CORE-090 | |
| US-PPL-003 | EP-PPL-01 | Là HR, tôi muốn khi tạo mới hệ thống phát hiện person đã tồn tại theo CCCD (12 số), hoặc hộ chiếu + quốc tịch, mã số thuế hoặc mã số BHXH để một tenant không bao giờ có hai person cho cùng một cá nhân. AC: chặn cứng khi trùng CCCD/hộ chiếu+quốc tịch; cảnh báo kèm danh sách ứng viên khi trùng mã số thuế/BHXH; việc đối khớp không bao giờ vượt qua ranh giới tenant | BR-PPL-001, NFR-PPL-008 | 3 | US-PPL-002, US-PPL-086 | Đối khớp trên ID đã mã hóa dùng blind index của US-PPL-086 |
| US-PPL-004 | EP-PPL-01 | Là HR, tôi muốn gộp hai person trùng lặp để các employment, người phụ thuộc, tài liệu và tài khoản ngân hàng được quy về một person. AC: xem trước những gì sẽ được chuyển; trỏ lại các dòng con của mọi bảng People; lý do gộp và dữ liệu trước/sau được ghi vào audit; publish `PersonalDataChanged` + sự kiện gộp | BR-PPL-001 | 5 | US-PPL-003, US-PPL-080 | Hiếm gặp nhưng cần sau khi import số dư đầu kỳ (RK2) |
| US-PPL-005 | EP-PPL-01 | Là HR, tôi muốn địa chỉ thường trú và địa chỉ hiện tại theo ngày hiệu lực trên đơn vị hành chính hai cấp sau 2025 (tỉnh/xã), vẫn giữ văn bản địa chỉ 3 cấp cũ, để tài liệu và báo cáo in đúng địa chỉ tại bất kỳ ngày nào. AC: không ghi đè, giữ lịch sử; địa chỉ cũ lưu chỉ đọc từ import; địa chỉ được kiểm tra theo đơn vị hành chính của Core tại ngày hiệu lực | BR-PPL-010 | 5 | US-PPL-002, BR-CORE (dữ liệu tham chiếu) | `[verify-legal]` quy tắc chuyển đổi đơn vị hành chính |
| US-PPL-006 | EP-PPL-01 | Là HR, tôi muốn có số điện thoại và e-mail (công việc và cá nhân, mỗi loại một giá trị chính) trên person để thông báo và đăng nhập OTP của người đã nghỉ việc đến được với họ. AC: kiểm tra định dạng; e-mail cá nhân được đánh dấu để dùng cho OD-8 | BR-PPL-010 | 2 | US-PPL-002 | |
| US-PPL-007 | EP-PPL-01 | Là HR, tôi muốn có giấy tờ tùy thân (CCCD/căn cước, hộ chiếu, giấy phép lao động, thẻ tạm trú) với số, ngày/nơi cấp, ngày hết hạn và bản scan để các mã định danh theo luật định có bằng chứng và được gia hạn đúng hạn. AC: bản scan lưu qua dịch vụ tài liệu của Core; job chạy hằng đêm phát cảnh báo hết hạn theo khoảng thời gian tenant cấu hình; báo cáo danh sách sắp hết hạn | BR-PPL-011 | 5 | US-PPL-002, BR-CORE-100, BR-CORE-101, US-PPL-059 | |
| US-PPL-008 | EP-PPL-01 | Là HR, tôi muốn mã số thuế cá nhân mặc định là số định danh cá nhân 12 số, MST 10 số cũ được giữ làm lịch sử, để hồ sơ khai thuế TNCN dùng đúng mã định danh. AC: áp dụng mặc định khi luật coi hai mã là tương đương; MST cũ được giữ kèm ngày hiệu lực; kiểm tra định dạng | BR-PPL-013 | 3 | US-PPL-002 | `[verify-legal]` Thông tư 86/2024 |
| US-PPL-009 | EP-PPL-01 | Là HR, tôi muốn có mã số BHXH, số thẻ BHYT, nơi đăng ký khám chữa bệnh ban đầu và tình trạng lưu giữ sổ BHXH để Payroll có thể kê khai bảo hiểm và HR theo dõi được sổ. AC: kiểm tra mã số BHXH 10 số; tình trạng lưu giữ đang giữ/đã trả kèm ngày; cơ sở khám chữa bệnh lấy từ danh mục tham chiếu | BR-PPL-014 | 3 | US-PPL-002 | |
| US-PPL-010 | EP-PPL-01 | Là HR, tôi muốn có một trường trình độ tối thiểu (trình độ học vấn, chuyên ngành) trên person để sổ quản lý lao động có cột trình độ. AC: trình độ lấy từ danh mục tham chiếu của Core; hiển thị trong hồ sơ và sổ quản lý lao động | BR-PPL-019 | 1 | US-PPL-002 | R3-07 mức tối thiểu; học vấn/chứng chỉ đầy đủ là P2 |
| US-PPL-011 | EP-PPL-01 | Là HR, tôi muốn có hồ sơ giấy phép lao động / miễn giấy phép (số, loại, thời hạn, công việc trên giấy phép, pháp nhân) cho người lao động nước ngoài để không ai làm việc khi không có giấy phép hợp lệ. AC: chặn phân công hoặc ngày kết thúc hợp đồng xác định thời hạn vượt quá thời hạn giấy phép trừ khi đã ghi nhận gia hạn; cảnh báo trước 45/60 ngày (có thể cấu hình) | BR-PPL-020 | 5 | US-PPL-002, US-PPL-033, US-PPL-047, US-PPL-059 | `[verify-legal]` Nghị định 219/2025 |
| US-PPL-012 | EP-PPL-01 | Là HR, tôi muốn có các tình trạng đặc biệt theo ngày hiệu lực (mang thai, nuôi con dưới 12 tháng, khuyết tật, chưa thành niên, cao tuổi, đoàn viên công đoàn, công việc nặng nhọc/độc hại) để T&A, Payroll và cơ chế bảo vệ khi chấm dứt áp dụng đúng chế độ. AC: ngày từ/đến, tệp bằng chứng đính kèm; chưa thành niên/cao tuổi suy ra từ ngày sinh với tham số độ tuổi theo luật; publish `SpecialStatusChanged` | BR-PPL-021 | 5 | US-PPL-002, US-PPL-080 | Tình trạng liên quan sức khỏe là nhạy cảm (US-PPL-083) |
| US-PPL-013 | EP-PPL-01 | Là HR, tôi muốn có tình trạng cư trú thuế theo ngày hiệu lực (cư trú/không cư trú) kèm căn cứ, ngày đến, ngày đi và tùy chọn số ngày ở VN mỗi năm để Payroll áp dụng đúng phương pháp tính thuế TNCN. AC: không có giai đoạn chồng lấn; thay đổi phải qua HR phê duyệt (đã đăng ký process type workflow của Core); thay đổi đã duyệt được publish trong `PersonalDataChanged` | BR-PPL-023 | 5 | US-PPL-002, BR-CORE-050, US-PPL-080 | `[verify-legal]` Thông tư 111/2013; People không tính phép thử 183 ngày |
| US-PPL-014 | EP-PPL-01 | Là HR, tôi muốn xem xét các đề xuất cư trú 183 ngày của Payroll và phê duyệt hoặc từ chối để thay đổi cư trú luôn được HR phê duyệt. AC: tiếp nhận đề xuất từ payroll (IN-32) vào một danh sách xem xét; phê duyệt tạo bản ghi cư trú của US-PPL-013 với nguồn = đề xuất từ payroll; từ chối thì lưu lý do | BR-PPL-023 | 3 | US-PPL-013, US-PPL-082 | Phụ thuộc vào việc bàn giao BR-PAY-065 |
| US-PPL-015 | EP-PPL-01 | Là HR, tôi muốn có danh sách nhân viên với tìm kiếm tên không phân biệt dấu, bộ lọc (pháp nhân, đơn vị tổ chức, trạng thái, phân loại) và ngày as-of để tìm bất kỳ ai thật nhanh. AC: "nguyen van an" tìm ra "Nguyễn Văn An"; sắp xếp theo collation tiếng Việt; p95 ≤ 1 s với 10.000 nhân viên trong kiểm thử tải; xuất Excel qua chức năng xuất danh sách của SaaS có che dữ liệu | NFR-PPL-003, NFR-PPL-009 | 5 | US-PPL-002, US-PPL-033, BR-SAAS-046 | Dùng cột không dấu + trigram index |
| US-PPL-016 | EP-PPL-01 | Là HR, tôi muốn có hồ sơ nhân viên dạng tab (person, employment, phân công, hợp đồng, lương nếu được phép, người phụ thuộc, ngân hàng, tài liệu) để xem một nhân viên tại một nơi. AC: p95 ≤ 2 s với 10.000 nhân viên; trường nhạy cảm được che mặc định; tab bị ẩn nếu không có quyền | NFR-PPL-003, BR-PPL-010 | 5 | US-PPL-002, US-PPL-017, US-PPL-085 | |

### EP-PPL-02 Quan hệ lao động & kiêm nhiệm

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-017 | EP-PPL-02 | Là HR, tôi muốn có một employment cho mỗi pháp nhân (mã nhân viên theo mẫu riêng của từng pháp nhân, ngày tuyển dụng, ngày tuyển dụng ban đầu và ngày tính thâm niên, trạng thái, phân loại người lao động, loại hình lao động) để mọi quan hệ làm việc được ghi nhận như luật và Payroll yêu cầu. AC: mã nhân viên duy nhất trong tenant qua cơ chế đánh số của Core; máy trạng thái Pre-hire → Active → Notice/Suspended → Terminated với các chuyển trạng thái có kiểm soát; cho phép loại cộng tác viên/dịch vụ | BR-PPL-003 | 5 | US-PPL-002, BR-CORE-085 | Q7 (cộng tác viên là một employment) giả định là có |
| US-PPL-018 | EP-PPL-02 | Là HR, tôi muốn có wizard tuyển dụng trực tiếp (đối khớp person → employment → phân công đầu tiên → hợp đồng → hồ sơ lương) để một nhân viên mới được hoàn tất trong một luồng có hướng dẫn. AC: đối khớp person theo US-PPL-003 đưa ra các lựa chọn mới / tái tuyển dụng dưới dạng employment mới / kiêm nhiệm; toàn bộ việc tuyển dụng được lưu trong một giao dịch; kiểm tra giới hạn license (IN-23); thiếu dữ liệu bắt buộc thì employment bị đánh dấu "chưa hoàn tất" | BR-PPL-001, BR-PPL-002, BR-PPL-003 | 8 | US-PPL-003, US-PPL-017, US-PPL-033, US-PPL-041, US-PPL-047 | Lựa chọn thâm niên khi tái tuyển dụng là P2 (BR-PPL-005); bàn giao từ tuyển dụng là P2 cùng Recruitment |
| US-PPL-019 | EP-PPL-02 | Là HR, tôi muốn phê duyệt tuyển dụng được cấu hình như một process type workflow của Core và employment được kích hoạt vào ngày tuyển dụng để `EmployeeHired` chỉ được publish cho các trường hợp tuyển dụng đã duyệt và đã có hiệu lực. AC: đăng ký process type + chuỗi phê duyệt mặc định; job kích hoạt hằng ngày đặt Active và publish `EmployeeHired` (cờ xem trước khi phê duyệt với ngày tương lai) | BR-PPL-003 | 3 | US-PPL-018, BR-CORE-050, BR-CORE-054, US-PPL-080 | Chỉ đăng ký/cấu hình |
| US-PPL-020 | EP-PPL-02 | Là HR, tôi muốn hủy một pre-hire trước ngày bắt đầu để những người không đến nhận việc không bao giờ đi vào T&A và Payroll. AC: chỉ áp dụng trạng thái Pre-hire; bắt buộc nhập lý do; publish `HireCancelled`; đóng các bản ghi con | BR-PPL-003 | 2 | US-PPL-019 | |
| US-PPL-021 | EP-PPL-02 | Là HR, tôi muốn tuyển một nhân viên hiện có vào một pháp nhân khác của tenant dưới dạng kiêm nhiệm để một người có thể làm việc cho hai pháp nhân (R2-16). AC: tại mọi thời điểm, mỗi pháp nhân có tối đa một employment còn hiệu lực (DB exclusion constraint); hire_type CONCURRENT trong `EmployeeHired`; thao tác trên cùng pháp nhân được chuyển hướng sang thay đổi phân công | BR-PPL-002 | 5 | US-PPL-018 | |
| US-PPL-022 | EP-PPL-02 | Là HR, tôi muốn chỉ định employment chính theo ngày hiệu lực cho từng mục đích (PIT_FAMILY_DEDUCTION, SOCIAL_INSURANCE) để giảm trừ gia cảnh và BHXH được áp dụng qua đúng một employment. AC: tại mọi thời điểm mỗi person có đúng một employment chính cho mỗi mục đích (tự đặt khi chỉ có một employment, bắt buộc chọn khi kiêm nhiệm); các mục đích có thể khác nhau; thay đổi được phê duyệt và publish `PrimaryEmploymentChanged`; chấm dứt employment chính buộc phải chỉ định lại | BR-PPL-004 | 8 | US-PPL-021, BR-CORE-050, US-PPL-080 | Bất biến được thực thi trong DB và service; RK7 |
| US-PPL-023 | EP-PPL-02 | Là Payroll, tôi muốn có thông tin tham gia bảo hiểm theo từng loại (BHXH, BHYT, BHTN, TNLĐ-BNN) cho từng employment, theo ngày hiệu lực và suy ra từ tham số pháp lý `insurance.multi_contract_rule` (FIRST_CONTRACT / HIGHEST_SALARY / EACH_EMPLOYER) để mỗi pháp nhân chỉ kê khai phần bảo hiểm mình có nghĩa vụ. AC: tính lại thông tin tham gia khi tuyển dụng, chấm dứt, thay đổi hợp đồng và lương và khi có gói tham số mới; HR có thể ghi nhận miễn trừ kèm lý do; kết quả đi kèm `PrimaryEmploymentChanged`; đổi quy tắc BHYT sang HIGHEST_SALARY chỉ cần đổi tham số | BR-PPL-004 | 13 | US-PPL-022, US-PPL-041, TECH:legal-parameter-service | **[high-uncertainty]** `[verify-legal]` cơ sở 8 +1 bậc: sponsor (R3-03) và PM bất đồng về BHYT; HIGHEST_SALARY cần so sánh lương giữa các pháp nhân và quy tắc khi bằng nhau |
| US-PPL-024 | EP-PPL-02 | Là HR, tôi muốn hệ thống ngăn việc một người phụ thuộc hoặc giảm trừ bản thân được kê qua hai employment để giảm trừ gia cảnh chỉ được áp dụng một lần. AC: employment kê khai người phụ thuộc phải là employment chính cho thuế TNCN ở mọi tháng của kỳ kê khai; khi employment chính thay đổi, người phụ thuộc đang hiệu lực chuyển sang employment chính mới kèm `DependantChanged` (transferred) | BR-PPL-004, BR-PPL-016 | 5 | US-PPL-022, US-PPL-028 | |
| US-PPL-025 | EP-PPL-02 | Là HR, tôi muốn có báo cáo ngoại lệ kiêm nhiệm (không có employment chính, employment chính chồng lấn, thiếu hoặc trùng tham gia bảo hiểm) để lỗi được sửa trước khi tính lương. AC: theo pháp nhân và ngày; có liên kết tới màn hình sửa | BR-PPL-004 | 2 | US-PPL-022, US-PPL-023 | |
| US-PPL-026 | EP-PPL-02 | Là nền tảng SaaS, tôi muốn có số lượng nhân viên đang làm việc hằng ngày theo pháp nhân để tính phí và giới hạn gói dùng dữ liệu People (D-10). AC: đếm Active tại cuối tháng theo pháp nhân, loại trừ Suspended (R2-12); publish sang SaaS (OUT-32) | BR-PPL-003 | 2 | US-PPL-017, US-PPL-079 | |

### EP-PPL-03 Gia đình, người phụ thuộc & tài khoản ngân hàng

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-027 | EP-PPL-03 | Là HR, tôi muốn có thành viên gia đình (quan hệ, họ tên, ngày sinh, số giấy tờ, mã số thuế, nghề nghiệp, tình trạng thu nhập, liên hệ) để có thể xây dựng người phụ thuộc và sổ quản lý lao động từ đó. AC: CRUD có kiểm tra hợp lệ; số giấy tờ được mã hóa và che | BR-PPL-016 | 3 | US-PPL-002, US-PPL-086 | |
| US-PPL-028 | EP-PPL-03 | Là HR, tôi muốn đăng ký một thành viên gia đình là người phụ thuộc giảm trừ thuế TNCN với tháng bắt đầu/kết thúc giảm trừ và giấy tờ chứng minh, được kiểm tra theo quy tắc đủ điều kiện, để chỉ những người phụ thuộc đủ điều kiện mới được giảm thuế TNCN. AC: quy tắc cho con < 18 tuổi, con từ 18 tuổi đang đi học hoặc khuyết tật không có thu nhập, vợ/chồng/cha mẹ không có thu nhập trên ngưỡng, dùng tham số pháp lý tại tháng đó; mỗi người phụ thuộc chỉ một người nộp thuế trong tenant; không đủ điều kiện = chặn kèm lý do | BR-PPL-016 | 8 | US-PPL-027, US-PPL-022, TECH:legal-parameter-service | `[verify-legal]` ngưỡng là tham số; bộ quy tắc theo Thông tư 111 |
| US-PPL-029 | EP-PPL-03 | Là HR, tôi muốn việc đăng ký và thay đổi người phụ thuộc được phê duyệt qua workflow của Core để mọi thay đổi đều được kiểm tra và Payroll được thông báo. AC: đăng ký process type; phê duyệt publish `DependantChanged` (registered/ended/transferred) kèm employment kê khai và tháng từ/đến | BR-PPL-017 | 3 | US-PPL-028, BR-CORE-050, US-PPL-080 | |
| US-PPL-030 | EP-PPL-03 | Là HR, tôi muốn sinh tờ khai/danh sách đăng ký người phụ thuộc (mẫu 07/ĐK-NPT-TNCN hoặc mẫu hiện hành) theo pháp nhân và kỳ để nộp cho cơ quan thuế. AC: liệt kê người phụ thuộc mới/kết thúc trong kỳ; Excel + PDF qua mẫu của Core; font tiếng Việt hiển thị đúng | BR-PPL-017 | 8 | US-PPL-029, BR-CORE-102 | **[high-uncertainty]** `[verify-legal]` cơ sở 5 +1 bậc: phiên bản và bố cục mẫu hiện hành chưa được xác nhận |
| US-PPL-031 | EP-PPL-03 | Là HR, tôi muốn có một hoặc nhiều tài khoản ngân hàng theo ngày hiệu lực với chia tách thanh toán (số tiền hoặc %) và đúng một tài khoản nhận lương mặc định để lương được trả chính xác. AC: tổng chia tách bằng 100% / phần còn lại vào tài khoản mặc định; cảnh báo khi tên chủ tài khoản khác tên nhân viên (so sánh không dấu); số tài khoản được mã hóa và che; ngân hàng lấy từ danh mục của Core | BR-PPL-015, NFR-PPL-005 | 5 | US-PPL-002, US-PPL-086 | |
| US-PPL-032 | EP-PPL-03 | Là C&B, tôi muốn thay đổi tài khoản ngân hàng được phê duyệt và publish để Payroll trả lương vào tài khoản đã xác minh. AC: đăng ký process type phê duyệt; `BankAccountChanged` không chứa số tài khoản đầy đủ (payroll đọc qua API) | BR-PPL-015 | 2 | US-PPL-031, BR-CORE-050, US-PPL-080 | |

### EP-PPL-04 Phân công theo ngày hiệu lực

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-033 | EP-PPL-04 | Là HR, tôi muốn có các phân công theo ngày hiệu lực (pháp nhân, đơn vị tổ chức, vị trí, công việc, ngạch/bậc, quản lý trực tiếp, nơi làm việc, chế độ thời giờ làm việc theo hợp đồng, FTE, loại phân công) với lịch sử đầy đủ để mọi module biết nhân viên thuộc đâu tại bất kỳ ngày nào. AC: dòng mới đặt ngày kết thúc cho dòng trước, không bao giờ ghi đè; không có khoảng trống hay chồng lấn trong mỗi employment; không có trường pay group hay lịch làm việc | BR-PPL-030, BR-PPL-031 | 5 | US-PPL-017, BR-CORE (tổ chức, vị trí, công việc, ngạch/bậc, nơi làm việc) | Ví dụ tham chiếu "lịch sử phân công công việc" |
| US-PPL-034 | EP-PPL-04 | Là HR, tôi muốn có chia tách % theo trung tâm chi phí và quản lý trực tiếp được chỉ định đích danh hoặc suy ra từ vị trí quản lý để phân bổ chi phí và định tuyến phê duyệt chính xác. AC: tổng chia tách bằng 100%; quản lý theo vị trí được xác định là người đương nhiệm tại ngày đó; vị trí quản lý trống thì chuyển lên cấp kế tiếp | BR-PPL-031 | 3 | US-PPL-033 | |
| US-PPL-035 | EP-PPL-04 | Là HR, tôi muốn có danh mục lý do thay đổi (điều chuyển, thăng chức, giáng chức, điều chuyển tạm thời, chức danh, quản lý, nơi làm việc, thay đổi trung tâm chi phí) trong đó mỗi lý do quy định phê duyệt cần có và tài liệu (quyết định/phụ lục) để thay đổi tuân theo chính sách. AC: danh mục có sẵn dữ liệu mặc định, tenant có thể vô hiệu hóa/thêm mới; ánh xạ lý do → process type + loại tài liệu | BR-PPL-032 | 3 | US-PPL-001 | Lý do biệt phái, kiêm nhiệm chức vụ và tái cơ cấu vẫn ở trạng thái không hoạt động (P2) |
| US-PPL-036 | EP-PPL-04 | Là HR, tôi muốn yêu cầu thay đổi công việc với ngày hiệu lực và lý do, đi qua phê duyệt rồi tạo phân công mới và quyết định tương ứng, để thay đổi được kiểm soát và có tài liệu. AC: đăng ký process type workflow của Core theo nhóm lý do; khi phê duyệt sẽ tạo dòng phân công, quyết định (US-PPL-057) và, khi điều khoản thay đổi, phụ lục (US-PPL-053); publish `AssignmentChanged` | BR-PPL-030, BR-PPL-032 | 5 | US-PPL-033, US-PPL-035, US-PPL-057, BR-CORE-050 | |
| US-PPL-037 | EP-PPL-04 | Là Payroll, tôi muốn truy vấn được phân công, hồ sơ lương và hợp đồng "tại ngày X" và "theo những gì đã biết vào ngày Y", với việc đính chính được ghi nhận tách biệt khỏi thay đổi, để có thể tái hiện lịch sử. AC: đính chính giữ lại phiên bản bị thay thế kèm lý do và known-from/known-to; truy vấn as-known trả về những gì hệ thống biết tại Y; áp dụng cho phân công, lương, hợp đồng | NFR-PPL-001, BR-PPL-030 | 8 | US-PPL-033, US-PPL-041, US-PPL-047, TECH:walking-skeleton (các helper effective-dating) | Bitemporal trên ba aggregate |
| US-PPL-038 | EP-PPL-04 | Là HR, tôi muốn các thay đổi có ngày tương lai được giữ lại đến ngày hiệu lực và có thể hủy trước đó để thay đổi đã lên kế hoạch có hiệu lực đúng hạn. AC: job hằng ngày áp dụng các thay đổi đến hạn và publish sự kiện; thay đổi tương lai có thể hủy kèm `AssignmentChangeCancelled`; các thay đổi tương lai chồng lấn được xử lý theo thứ tự | BR-PPL-037 | 5 | US-PPL-036, TECH:job-queue | |
| US-PPL-039 | EP-PPL-04 | Là Payroll, tôi muốn có cờ hồi tố trên các sự kiện có ngày hiệu lực rơi vào kỳ lương đã khóa để có thể chạy đính chính hồi tố. AC: dùng trạng thái kỳ đã khóa của Payroll theo pháp nhân; cờ + kỳ bị ảnh hưởng sớm nhất trong `AssignmentChanged`, `SalaryChanged`, các sự kiện hợp đồng và chấm dứt | BR-PPL-037 | 3 | US-PPL-080, US-PPL-082 | |
| US-PPL-040 | EP-PPL-04 | Là HR, tôi muốn điều chuyển tạm thời (Điều 29) được kiểm tra số ngày làm việc cộng dồn trong năm dương lịch, thời hạn báo trước và bảo vệ tiền lương để việc điều chuyển luôn hợp pháp. AC: vượt 60 ngày làm việc/năm thì bắt buộc đính kèm văn bản đồng ý của người lao động; kiểm tra báo trước ≥ 3 ngày làm việc; cờ bảo vệ tiền lương (≥85%, ≥ lương tối thiểu vùng, giữ lương cũ trong 30 ngày làm việc) được gửi trong `TemporaryTransferStarted/Ended` | BR-PPL-035 | 8 | US-PPL-036, TECH:legal-parameter-service | `[verify-legal]` giá trị là tham số; ngày làm việc lấy từ lịch nghỉ lễ của Core/T&A |

### EP-PPL-05 Hồ sơ lương (lương thỏa thuận)

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-041 | EP-PPL-05 | Là C&B, tôi muốn có hồ sơ lương theo ngày hiệu lực, có phiên bản, cho từng employment (thang lương/ngạch/bậc, cơ sở trả lương GROSS/NET, lương cơ bản, lương net thỏa thuận bắt buộc với NET, phạm vi thành phần được bảo đảm net, lương đóng BHXH, phụ cấp theo hợp đồng theo mã thành phần lương, VND, tần suất trả lương, % lương thử việc) để People là system of record cho lương thỏa thuận. AC: mã thành phần và thang lương tra cứu từ danh mục của Payroll (IN-17); bản ghi NET không có lương net thỏa thuận bị từ chối; mỗi phiên bản giữ liên kết tới quyết định/phụ lục | BR-PPL-040 | 8 | US-PPL-017, BR-PAY (thang lương & danh mục thành phần lương) | Thực thể theo ngày hiệu lực (5) + quy tắc phạm vi GROSS/NET |
| US-PPL-042 | EP-PPL-05 | Là C&B, tôi muốn hồ sơ lương chỉ được tạo hoặc thay đổi thông qua quyết định hoặc phụ lục hợp đồng đã phê duyệt để không có thay đổi lương nào thiếu văn bản pháp lý. AC: không có API hay màn hình sửa trực tiếp; đăng ký process type thay đổi lương; phê duyệt tạo phiên bản + quyết định/phụ lục và publish `SalaryChanged` (có số tiền, chỉ handler của Payroll đọc) | BR-PPL-040 | 5 | US-PPL-041, US-PPL-053, US-PPL-057, BR-CORE-050, US-PPL-080 | Import số dư đầu kỳ là nguồn ghi duy nhất khác (US-PPL-091) |
| US-PPL-043 | EP-PPL-05 | Là C&B, tôi muốn mỗi mức lương được kiểm tra theo khung min/max của ngạch/bậc và lương tối thiểu vùng của nơi làm việc tại ngày hiệu lực để không ai bị trả thấp hơn mức tối thiểu theo luật. AC: dưới mức tối thiểu = chặn, ngoài khung = cảnh báo kèm lý do; lương thử việc cũng được kiểm tra; bản ghi NET được kiểm tra trên số tiền đã quy đổi gross từ API mô phỏng của Payroll | BR-PPL-041 | 8 | US-PPL-041, US-PPL-034, TECH:legal-parameter-service, BR-PAY (bộ mô phỏng gross-up) | **[high-uncertainty]** `[verify-legal]` cơ sở 5 +1 bậc: lương tối thiểu vùng 2026 chưa được xác nhận; API mô phỏng gross-up chưa được Payroll đặc tả |
| US-PPL-044 | EP-PPL-05 | Là C&B, tôi muốn việc xem hoặc thay đổi lương yêu cầu quyền `compensation` để lương được giữ bảo mật. AC: quyền được đăng ký trong danh mục của Core; được thực thi trên API, màn hình, xuất danh sách, read API và ESS; quản lý chỉ thấy lương nếu được cấp quyền | BR-PPL-044 | 3 | US-PPL-041, BR-CORE-031, BR-CORE-034 | |
| US-PPL-045 | EP-PPL-05 | Là DPO, tôi muốn mọi lần xem và mọi thay đổi hồ sơ lương được ghi vào audit log của Core (ai, khi nào, bản ghi nào, mục đích) để có thể truy vết việc truy cập dữ liệu lương. AC: lần xem danh sách và xem chi tiết đều được ghi log; mục đích được ghi nhận ở lần xem đầu tiên trong mỗi phiên; audit có thể tìm theo nhân viên | BR-PPL-044, NFR-PPL-006 | 3 | US-PPL-044, BR-CORE-090, BR-CORE-093 | |
| US-PPL-046 | EP-PPL-05 | Là C&B, tôi muốn có màn hình lịch sử lương (diễn biến lương) kèm tài liệu liên kết để có thể giải thích mọi thay đổi. AC: các phiên bản theo thứ tự thời gian, bao gồm cả đính chính; liên kết tới PDF quyết định/phụ lục; kiểm soát theo quyền | BR-PPL-040 | 2 | US-PPL-042 | |

### EP-PPL-06 Hợp đồng lao động & phụ lục

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-047 | EP-PPL-06 | Là HR, tôi muốn có hợp đồng thuộc mọi loại theo luật định (thỏa thuận thử việc, xác định thời hạn ≤ 36 tháng, không xác định thời hạn, theo mùa vụ < 1 tháng, học nghề, thực tập, dịch vụ/cộng tác viên) với số, ngày ký, ngày bắt đầu/kết thúc, người ký và số thứ tự để hợp đồng lao động được ghi nhận đúng như Bộ luật Lao động quy định. AC: hợp đồng xác định thời hạn bắt buộc có ngày kết thúc và ≤ 36 tháng; bộ đếm hợp đồng xác định thời hạn thứ n cho mỗi employment; số hợp đồng lấy từ cơ chế đánh số của Core theo pháp nhân | BR-PPL-050 | 5 | US-PPL-017, BR-CORE-085 | `[verify-legal]` Điều 20, 24 |
| US-PPL-048 | EP-PPL-06 | Là HR, tôi muốn thử việc được kiểm tra hợp lệ (thời gian tối đa theo cấp bậc công việc, chỉ một lần cho mỗi công việc, không áp dụng cho hợp đồng < 1 tháng, lương thử việc ≥ 85%) để thử việc hợp pháp. AC: giới hạn lấy từ tham số pháp lý theo cấp bậc công việc; chặn thử việc lần hai cho cùng công việc; % thử việc được đối chiếu với hồ sơ lương | BR-PPL-051 | 5 | US-PPL-047, US-PPL-041, TECH:legal-parameter-service | `[verify-legal]` Điều 24–27 |
| US-PPL-049 | EP-PPL-06 | Là HR, tôi muốn kết quả thử việc được ghi nhận và thông báo trước khi kết thúc thử việc, với cảnh báo trước N ngày, để kết quả hợp pháp và đạt thử việc dẫn tới hợp đồng lao động. AC: cảnh báo tới HR và quản lý; đạt → bản nháp hợp đồng lao động (hoặc tiếp tục đối với điều khoản thử việc trong hợp đồng); không đạt → hồ sơ chấm dứt với lý do không đạt thử việc; `ProbationStarted/Completed` | BR-PPL-052 | 5 | US-PPL-047, US-PPL-059, US-PPL-061 | Biểu mẫu đánh giá thử việc là P2 |
| US-PPL-050 | EP-PPL-06 | Là HR, tôi muốn hệ thống yêu cầu hợp đồng không xác định thời hạn sau hai hợp đồng xác định thời hạn liên tiếp, trừ các ngoại lệ theo luật, để không bao giờ vi phạm Điều 20. AC: đếm số hợp đồng xác định thời hạn liên tiếp, bao gồm số đếm trước đó đã import; chặn hợp đồng xác định thời hạn thứ ba trừ khi áp dụng ngoại lệ (người cao tuổi, người lao động nước ngoài, người quản lý doanh nghiệp nhà nước, nhiệm kỳ cán bộ công đoàn, các trường hợp khác được cấu hình); lưu lý do ngoại lệ | BR-PPL-053 | 8 | US-PPL-047, US-PPL-011, US-PPL-012 | **[high-uncertainty]** `[verify-legal]` cơ sở 5 +1 bậc: danh sách ngoại lệ còn mở ("v.v.") và tương tác với thời hạn giấy phép lao động |
| US-PPL-051 | EP-PPL-06 | Là HR, tôi muốn theo dõi hợp đồng hết hạn với cảnh báo theo các mốc có thể cấu hình và cờ tự động chuyển đổi để không hợp đồng nào âm thầm trở thành không xác định thời hạn. AC: cảnh báo ví dụ trước 45/30/15 ngày tới HR và quản lý; `ContractExpiring`; tiếp tục làm việc > 30 ngày sau khi hết hạn mà không có hợp đồng mới thì đánh dấu tự động chuyển đổi; danh sách hợp đồng sắp hết hạn và thử việc sắp kết thúc | BR-PPL-054 | 5 | US-PPL-047, US-PPL-059, TECH:job-queue | `[verify-legal]` Điều 20.2 |
| US-PPL-052 | EP-PPL-06 | Là HR, tôi muốn gia hạn, chuyển đổi hoặc chấm dứt một hợp đồng sắp hết hạn ngay từ danh sách hết hạn để bước tiếp theo chỉ là một thao tác. AC: gia hạn tạo hợp đồng kế tiếp có kiểm tra quy tắc hai hợp đồng xác định thời hạn; chuyển đổi tạo hợp đồng không xác định thời hạn; chấm dứt mở một hồ sơ chấm dứt (hết hạn hợp đồng); `ContractRenewed/Converted` | BR-PPL-053, BR-PPL-054 | 3 | US-PPL-050, US-PPL-051, US-PPL-061 | |
| US-PPL-053 | EP-PPL-06 | Là HR, tôi muốn có phụ lục hợp đồng cho thay đổi lương, vị trí, nơi làm việc và thời giờ làm việc với ngày hiệu lực để thay đổi điều khoản có tài liệu. AC: phụ lục không được thay đổi thời hạn hợp đồng (kiểm tra hợp lệ); phụ lục đã duyệt tạo phiên bản lương/phân công liên kết; `ContractAppendixSigned` | BR-PPL-055 | 5 | US-PPL-047, US-PPL-055 | `[verify-legal]` Điều 22 |
| US-PPL-054 | EP-PPL-06 | Là HR, tôi muốn tạm hoãn và tiếp tục thực hiện hợp đồng vì các lý do theo luật định để lương, BHXH và chấm công dừng đúng cách. AC: danh mục lý do, từ/đến; trạng thái Suspended; cảnh báo quay lại làm việc trong vòng 15 ngày; `ContractSuspended/Resumed` | BR-PPL-056 | 5 | US-PPL-047, US-PPL-017, US-PPL-080 | `[verify-legal]` Điều 30–31 |
| US-PPL-055 | EP-PPL-06 | Là HR, tôi muốn tài liệu hợp đồng và phụ lục được sinh từ mẫu song ngữ VN/EN của tenant, được đánh số theo pháp nhân và do người có thẩm quyền ký, để hợp đồng giấy được in ra chính xác. AC: data provider merge-field của People cho hợp đồng/phụ lục; mẫu mặc định được đăng ký với Core; người ký được chọn từ danh sách ủy quyền của pháp nhân; PDF/A với font tiếng Việt | BR-PPL-057, NFR-PPL-010 | 5 | US-PPL-047, BR-CORE-102, BR-CORE-085 | UI quản lý phiên bản mẫu thuộc Core (BR-PPL-131 P2) |
| US-PPL-056 | EP-PPL-06 | Là HR, tôi muốn ghi nhận chữ ký trên giấy (ngày ký, tải lên bản scan đã ký) để hợp đồng có hiệu lực mà không cần chữ ký điện tử. AC: trạng thái Draft → Signed; bản scan đã ký lưu qua dịch vụ tài liệu của Core; publish `ContractSigned` | BR-PPL-057 | 2 | US-PPL-055, BR-CORE-100 | Chữ ký điện tử là P2 (R2-10) |

### EP-PPL-07 Quyết định nhân sự & thông báo People

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-057 | EP-PPL-07 | Là HR, tôi muốn có bản ghi quyết định với số theo pháp nhân theo năm, ngày, người ký, ngày hiệu lực, văn bản căn cứ pháp lý và employment liên quan, liên kết tới thay đổi mà quyết định ban hành, để mọi thay đổi nhân sự đều có văn bản theo luật định. AC: số lấy từ cơ chế đánh số của Core (theo pháp nhân/năm); một quyết định cho mỗi thay đổi; hủy/ban hành lại vẫn giữ dấu vết số; tải lên bản scan đã ký | BR-PPL-130 | 5 | US-PPL-017, BR-CORE-085, BR-CORE-100 | |
| US-PPL-058 | EP-PPL-07 | Là HR, tôi muốn có sẵn các mẫu quyết định song ngữ cho các loại quyết định P1 (tuyển dụng/bổ nhiệm, kết quả thử việc, điều chuyển, thăng chức/giáng chức, thay đổi lương, tạm hoãn, chấm dứt, nghỉ hưu) để quyết định được sinh tự động chứ không phải gõ tay. AC: mỗi loại có một merge-field provider; PDF/A hiển thị tiếng Việt chính xác; mẫu được đăng ký với Core | BR-PPL-130, NFR-PPL-010 | 5 | US-PPL-057, BR-CORE-102 | Quyết định khen thưởng, kỷ luật, biệt phái, kiêm nhiệm chức vụ đi theo các tính năng P2 tương ứng |
| US-PPL-059 | EP-PPL-07 | Là HR, tôi muốn các mẫu cảnh báo và quy tắc người nhận của People (giấy tờ/giấy phép hết hạn, kết thúc thử việc, hợp đồng hết hạn, hạn quyết toán, hạn báo cáo theo luật định) được đăng ký với cơ chế thông báo của Core để cảnh báo nhất quán và song ngữ. AC: mẫu trong ứng dụng + e-mail VI/EN; không có giá trị nhạy cảm trong nội dung thông báo; các mốc thời gian cấu hình được theo tenant | BR-PPL-011, BR-PPL-020, BR-PPL-052, BR-PPL-054, BR-PPL-075, BR-PPL-141, BR-PPL-142 | 3 | BR-CORE-070, BR-CORE-074 | Trigger nằm trong các story sở hữu tương ứng |

### EP-PPL-08 Chấm dứt HĐLĐ & kích hoạt quyết toán cuối cùng

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-060 | EP-PPL-08 | Là HR, tôi muốn các lý do chấm dứt được ánh xạ tới căn cứ pháp lý, mỗi lý do có mã BHXH/BHTN theo luật định, để căn cứ pháp lý quyết định việc báo trước, trợ cấp thôi việc và báo cáo. AC: có sẵn các lý do (hết hạn, thỏa thuận, người lao động xin nghỉ, Điều 36, Điều 42–43, sa thải, không đạt thử việc, nghỉ hưu, chết, mất tích, chấm dứt hoạt động); tenant có thể thêm lý do con, không được thêm căn cứ pháp lý | BR-PPL-070 | 3 | US-PPL-001 | `[verify-legal]` các mã |
| US-PPL-061 | EP-PPL-08 | Là HR, tôi muốn có hồ sơ chấm dứt (lý do, căn cứ pháp lý, ngày báo trước, ngày làm việc cuối cùng (LWD), ngày chấm dứt, ngày thu hồi quyền truy cập) với phê duyệt và quyết định chấm dứt để việc chấm dứt được kiểm soát. AC: đăng ký process type; trạng thái employment là Notice khi được phê duyệt; access_revocation_date mặc định là LWD 23:59; sinh quyết định chấm dứt | BR-PPL-070, BR-PPL-075 | 5 | US-PPL-060, US-PPL-058, BR-CORE-050 | |
| US-PPL-062 | EP-PPL-08 | Là HR, tôi muốn thời hạn báo trước bắt buộc được tính theo loại hợp đồng và lý do, bao gồm ngành nghề đặc thù và các trường hợp không cần báo trước, hiển thị số ngày thiếu và hệ quả bồi thường, để cả chúng tôi lẫn người lao động tuân thủ luật. AC: phía người lao động và phía người sử dụng lao động (Điều 35, 36); mặc định 45 / 30 / 3 ngày làm việc lấy từ tham số pháp lý; số ngày thiếu được đưa vào `TerminationScheduled` | BR-PPL-071 | 8 | US-PPL-061, TECH:legal-parameter-service | `[verify-legal]` ngành nghề đặc thù theo Nghị định 145 |
| US-PPL-063 | EP-PPL-08 | Là HR, tôi muốn việc người sử dụng lao động chấm dứt hoặc sa thải bị chặn đối với người lao động được bảo vệ (mang thai, đang nghỉ thai sản, nuôi con < 12 tháng, đang điều trị ốm đau) trừ khi có ngoại lệ hợp pháp được lập hồ sơ, để tránh chấm dứt trái luật. AC: dùng tình trạng đặc biệt (US-PPL-012) và các giai đoạn nghỉ của T&A (IN-14); ngoại lệ cần căn cứ + tệp đính kèm và phê duyệt của HR Manager | BR-PPL-073 | 5 | US-PPL-012, US-PPL-061, US-PPL-082 | `[verify-legal]` Điều 37, 122, 137 |
| US-PPL-064 | EP-PPL-08 | Là Payroll, tôi muốn được cung cấp điều kiện hưởng trợ cấp thôi việc hoặc mất việc làm, các giai đoạn làm việc (bao gồm thời gian làm việc trước đó được công nhận) và các giai đoạn đã được chi trả, kèm căn cứ pháp lý, để có thể tính số tiền. AC: điều kiện hưởng suy ra từ căn cứ pháp lý; các giai đoạn bao gồm lịch sử đã import (US-PPL-092); kết quả của Payroll (IN-19) hiển thị chỉ đọc trên hồ sơ, không bao giờ tính lại | BR-PPL-074 | 8 | US-PPL-061, US-PPL-082, US-PPL-092 | `[verify-legal]` Điều 46–47 |
| US-PPL-065 | EP-PPL-08 | Là nhân viên, tôi muốn nộp đơn nghỉ việc trên ESS (LWD đề xuất, lý do, ghi chú) và rút lại đơn để việc nghỉ việc đơn giản và được ghi nhận. AC: trang ESS trong khung SaaS; quản lý rồi HR phê duyệt qua inbox của Core; rút đơn tự do trước khi được duyệt, sau khi duyệt chỉ khi HR đồng ý → `TerminationCancelled` | BR-PPL-072 | 5 | US-PPL-061, US-PPL-062, BR-SAAS-075, BR-CORE-060 | |
| US-PPL-066 | EP-PPL-08 | Là Payroll, tôi muốn có `TerminationScheduled` khi phê duyệt và `EmployeeTerminated` kèm trigger quyết toán cuối cùng vào ngày LWD + 1 để quyết toán cuối cùng bắt đầu đúng hạn. AC: job hằng ngày đặt Terminated; trigger mang theo LWD, căn cứ pháp lý, mã BHXH/BHTN, điều kiện hưởng, các giai đoạn, số ngày báo trước còn thiếu; hạn quyết toán lấy từ đồng hồ của Payroll (`pay.FinalSettlementStatusChanged`) được hiển thị kèm cảnh báo | BR-PPL-075 | 5 | US-PPL-061, US-PPL-064, US-PPL-080, US-PPL-082 | `[verify-legal]` Điều 48 |
| US-PPL-067 | EP-PPL-08 | Là HR, tôi muốn ghi nhận e-mail cá nhân của người nghỉ việc trên hồ sơ chấm dứt để người đã nghỉ việc có thể đăng nhập bằng OTP (OD-8). AC: bắt buộc trừ khi HR ghi nhận "không có e-mail"; được chuyển cho Core để cấp vai trò FORMER_EMPLOYEE | BR-PPL-075 | 1 | US-PPL-061, US-PPL-006 | |

### EP-PPL-09 Sổ quản lý lao động & báo cáo lao động theo luật định

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-068 | EP-PPL-09 | Là HR, tôi muốn có sổ quản lý lao động điện tử theo từng pháp nhân với các trường theo quy định, xem được tại bất kỳ ngày nào và xuất được, để luôn sẵn sàng cho thanh tra. AC: các trường theo Điều 3 Nghị định 145 (định danh, trình độ, hợp đồng, lương, BHXH, đào tạo, chỗ giữ cho kỷ luật, chấm dứt); xuất Excel + PDF; phản ánh mọi thay đổi theo ngày hiệu lực | BR-PPL-140 | 8 | US-PPL-010, US-PPL-033, US-PPL-041, US-PPL-047, US-PPL-061 | `[verify-legal]` báo cáo xuất theo luật định (8) |
| US-PPL-069 | EP-PPL-09 | Là HR, tôi muốn có báo cáo tình hình thay đổi lao động 6 tháng (Mẫu 01/PLI) theo pháp nhân cho kỳ tháng 6 và tháng 12 để nộp trước hạn. AC: số liệu theo phân loại từ lịch sử People; HR xem xét và điều chỉnh ghi chú; xuất theo bố cục quy định; cảnh báo hạn nộp (ví dụ trước ngày 5/6 / 5/12) | BR-PPL-141 | 8 | US-PPL-068, US-PPL-071 | `[verify-legal]` báo cáo xuất theo luật định |
| US-PPL-070 | EP-PPL-09 | Là HR, tôi muốn có thông báo biến động lao động hằng tháng gửi Trung tâm Dịch vụ việc làm, liệt kê các trường hợp tuyển dụng và chấm dứt, để đáp ứng nghĩa vụ BHTN. AC: theo pháp nhân theo tháng; xuất theo mẫu quy định; cảnh báo hạn nộp | BR-PPL-142 | 8 | US-PPL-066, US-PPL-071 | **[high-uncertainty]** `[verify-legal]` cơ sở 5 +1 bậc: nghị định hướng dẫn Luật Việc làm 2025 và biểu mẫu chưa được ban hành/xác nhận |
| US-PPL-071 | EP-PPL-09 | Là HR, tôi muốn có sổ đăng ký báo cáo theo luật định (loại, pháp nhân, kỳ, file đã sinh, thời điểm nộp, người nộp) để việc nộp báo cáo được ghi nhận. AC: sinh lại vẫn giữ các phiên bản; đánh dấu đã nộp kèm ngày và tải lên giấy biên nhận | BR-PPL-141, BR-PPL-142 | 3 | US-PPL-001, BR-CORE-100 | |

### EP-PPL-10 Trang web ESS cho People

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-072 | EP-PPL-10 | Là nhân viên, tôi muốn có trang "Hồ sơ của tôi" (dữ liệu cá nhân, giấy tờ được che, địa chỉ, liên hệ, trình độ, cư trú thuế) trong khung ESS để có thể kiểm tra dữ liệu của mình. AC: chỉ phạm vi SELF; chỉ đọc (yêu cầu thay đổi là P2); dùng được ở 360 px | BR-PPL-120, BR-PPL-123, NFR-PPL-011 | 5 | US-PPL-002, BR-SAAS-075, US-PPL-085 | |
| US-PPL-073 | EP-PPL-10 | Là nhân viên, tôi muốn có trang "Quá trình làm việc của tôi" (phân công, hợp đồng, quyết định để tải xuống, lịch sử lương nếu chính sách tenant cho phép) để có được các tài liệu của mình. AC: tài liệu qua signed URL của Core; tab lương bị ẩn trừ khi bật cờ chính sách | BR-PPL-120 | 5 | US-PPL-033, US-PPL-047, US-PPL-057, BR-CORE-101 | |
| US-PPL-074 | EP-PPL-10 | Là nhân viên, tôi muốn xem gia đình và người phụ thuộc của mình và yêu cầu thêm mới hoặc kết thúc người phụ thuộc kèm giấy tờ để giảm trừ thuế TNCN của tôi luôn được cập nhật. AC: yêu cầu đi vào quy trình HR phê duyệt của US-PPL-029; trạng thái hiển thị được; lỗi điều kiện hưởng được hiển thị trước khi gửi | BR-PPL-120, BR-PPL-017 | 5 | US-PPL-029, BR-SAAS-075 | |
| US-PPL-075 | EP-PPL-10 | Là nhân viên, tôi muốn yêu cầu thay đổi tài khoản ngân hàng với xác thực tăng cường để chỉ tôi mới có thể chuyển hướng lương của mình. AC: step-up qua khung SaaS trước khi gửi; HR phê duyệt (US-PPL-032); gửi e-mail thông báo cho nhân viên khi có thay đổi | BR-PPL-120, NFR-PPL-011 | 3 | US-PPL-032, BR-SAAS-075 | |
| US-PPL-076 | EP-PPL-10 | Là quản lý trực tiếp hoặc người phê duyệt HR, tôi muốn có các thẻ tác vụ People (tuyển dụng, thay đổi công việc, thay đổi lương, người phụ thuộc, ngân hàng, nghỉ việc, chấm dứt) trong inbox của Core để có thể phê duyệt với đầy đủ thông tin trước mắt. AC: tóm tắt + liên kết theo process type; giá trị lương chỉ hiển thị khi có quyền `compensation` | BR-PPL-120, BR-PPL-072, BR-PPL-017 | 3 | BR-CORE-060, US-PPL-036, US-PPL-065 | Trang MSS là P2 (BR-PPL-121) |
| US-PPL-077 | EP-PPL-10 | Là người đã nghỉ việc, tôi muốn có quyền truy cập ESS chỉ đọc vào hợp đồng và quyết định của mình để có thể tải xuống sau khi nghỉ (OD-8). AC: vai trò FORMER_EMPLOYEE chỉ thấy tài liệu của chính mình trong khoảng thời gian được cấu hình | BR-PPL-120 | 2 | US-PPL-073, US-PPL-067 | |
| US-PPL-078 | EP-PPL-10 | Là nhân viên, tôi muốn mọi trang ESS của People có tiếng Việt và tiếng Anh và dùng được trên trình duyệt điện thoại 360 px để có thể dùng ở bất cứ đâu. AC: mọi nhãn có trong file resource VI/EN; kiểm tra responsive ở 360/768/1280 px; tên được hiển thị và tìm kiếm có dấu | BR-PPL-123, NFR-PPL-011, NFR-PPL-009 | 3 | US-PPL-072, US-PPL-073, US-PPL-074, US-PPL-075, US-PPL-065 | |

### EP-PPL-11 Sự kiện nhân viên & read API

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-079 | EP-PPL-11 | Là Payroll/T&A, tôi muốn mọi thay đổi People được commit cùng sự kiện của nó trong cùng một giao dịch qua outbox để không thay đổi nhân sự nào bị mất. AC: `hr.outbox_event` được nối với relay của TECH; JSON Schema có phiên bản cho các sự kiện People được kiểm tra trong CI; `event_id` idempotent; kiểm thử chứng minh rollback hủy cả hai | NFR-PPL-002 | 5 | TECH:outbox-event-bus | Engine thuộc TECH; chỉ phần nối và schema của People |
| US-PPL-080 | EP-PPL-11 | Là Payroll/T&A/Core, tôi muốn có các sự kiện vòng đời People (EmployeeHired, HireCancelled, AssignmentChanged, AssignmentChangeCancelled, SalaryChanged, Contract*, Probation*, ContractSuspended/Resumed, TemporaryTransfer*, TerminationScheduled, EmployeeTerminated, TerminationCancelled, PersonalDataChanged, BankAccountChanged, DependantChanged, SpecialStatusChanged, PrimaryEmploymentChanged) với envelope chuẩn để các consumer phản ứng với thay đổi nhân sự. AC: payload builder với dữ liệu tối thiểu hóa (không có CCCD, số tài khoản ngân hàng đầy đủ; số tiền chỉ có trong SalaryChanged); contract test với consumer Payroll và T&A; có trường cờ hồi tố | NFR-PPL-002, BR-PPL-004, BR-PPL-075 | 8 | US-PPL-079 | ~20 loại sự kiện |
| US-PPL-081 | EP-PPL-11 | Là Payroll/T&A, tôi muốn có read API hồ sơ nhân viên (hiện tại và theo ngày as-of / as-known) cho person, employment, phân công, tóm tắt hợp đồng và tóm tắt lương (kiểm soát theo quyền) để consumer lấy giá trị nhạy cảm theo quyền của chính mình. AC: tham số as-of và as-known; lương cần quyền `compensation`; p95 ≤ 300 ms cho một nhân viên; truy vấn theo lô theo pháp nhân cho các lần chạy lương | NFR-PPL-001 | 5 | US-PPL-037, US-PPL-044 | OUT-03 |
| US-PPL-082 | EP-PPL-11 | Là HR, tôi muốn People tiêu thụ sự kiện của các module khác (core.UserStatusChanged, pay.PayGroupAssigned, phân lịch làm việc của T&A, các giai đoạn nghỉ phép/thai sản của T&A, pay.FinalSettlementStatusChanged, kỳ lương đã khóa, đề xuất 183 ngày) một cách idempotent để kiểm tra bảo vệ, cờ hồi tố và mức sẵn sàng onboarding dùng dữ liệu hiện hành. AC: inbox có khóa theo consumer + event_id; publish `people.OnboardingReadinessChanged`; dead-letter hiển thị cho đội vận hành | BR-PPL-073, BR-PPL-075 | 5 | US-PPL-079, TECH:outbox-event-bus | |

### EP-PPL-12 PDPL cho dữ liệu People

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-083 | EP-PPL-12 | Là DPO, tôi muốn mọi trường People được phân loại là cơ bản hoặc nhạy cảm và được đăng ký trong danh mục dữ liệu cá nhân của SaaS để từ điển dữ liệu đầy đủ. AC: phân loại cho mọi cột cá nhân trong `hr` (sức khỏe, dân tộc, tôn giáo, công đoàn, ngân hàng = nhạy cảm); kiểm tra CI thất bại khi có cột mới chưa phân loại | BR-PPL-150 | 3 | BR-SAAS-079 | `[verify-legal]` Nghị định 13/2023, PDPL 2025 |
| US-PPL-084 | EP-PPL-12 | Là DPO, tôi muốn People kiểm tra tham chiếu sự đồng ý (`hasConsent()`) trước khi xử lý dữ liệu nhạy cảm tùy chọn và ghi nhận cơ sở pháp lý trong trường hợp còn lại để việc xử lý là hợp pháp. AC: tôn giáo, Đảng/Đoàn và các trường sức khỏe tùy chọn bị ẩn/chặn nếu không có sự đồng ý; cơ sở pháp lý (hợp đồng/nghĩa vụ pháp lý) được ghi nhận cho các trường bắt buộc; `saas.ConsentChanged` khi rút lại sự đồng ý sẽ ẩn dữ liệu tùy chọn | BR-PPL-151 | 5 | BR-SAAS-080, US-PPL-083 | Tái sử dụng sự đồng ý từ tuyển dụng là P2 cùng Recruitment |
| US-PPL-085 | EP-PPL-12 | Là HR, tôi muốn các trường nhạy cảm được che mặc định trên màn hình và file xuất, với thao tác hiện dữ liệu cần có quyền và mục đích và được ghi log, để dữ liệu nhạy cảm chỉ hiển thị khi cần. AC: quy tắc che dữ liệu theo trường của Core được cấu hình cho mọi trường People nhạy cảm; thao tác hiện ghi log ai/khi nào/trường/mục đích; file xuất được che trừ khi người xuất có quyền hiện dữ liệu | BR-PPL-152, NFR-PPL-006 | 5 | BR-CORE-034, BR-CORE-090, US-PPL-083 | |
| US-PPL-086 | EP-PPL-12 | Là DPO, tôi muốn số giấy tờ, tài khoản ngân hàng, sức khỏe, tôn giáo và dân tộc được mã hóa ở mức trường với blind index để đối khớp, và được che trong log và các bản sao phi production, để rò rỉ cơ sở dữ liệu không làm lộ chúng. AC: mã hóa qua dịch vụ khóa của TECH; blind index hỗ trợ kiểm tra trùng CCCD/hộ chiếu và tìm kiếm chính xác; kiểm thử che dữ liệu trong log; script che dữ liệu cho bản sao phi production; số tiền lương không mã hóa (X-01) | NFR-PPL-005 | 8 | TECH:security (mã hóa trường/KMS) | **[high-uncertainty]** chi phí key-unwrap cho 10k person mỗi lần chạy lương chưa được đo (decision log §4) |
| US-PPL-087 | EP-PPL-12 | Là DPO, tôi muốn mọi lần đọc dữ liệu People nhạy cảm và mọi lần ghi được log kèm người thực hiện, mục đích và dữ liệu trước/sau (với thao tác ghi) để việc truy cập có trách nhiệm giải trình. AC: thao tác ghi được audit cho mọi aggregate People qua audit của Core; thao tác đọc nhạy cảm được log (bao gồm read API); việc ghi audit được bảo đảm trong giao dịch nghiệp vụ | NFR-PPL-006 | 3 | BR-CORE-090, BR-CORE-092 | |
| US-PPL-088 | EP-PPL-12 | Là DPO, tôi muốn có các handler xuất và ẩn danh hóa của People cho yêu cầu của chủ thể dữ liệu để một DSR mở trong SaaS có thể được đáp ứng cho dữ liệu People. AC: gói xuất (JSON + PDF dễ đọc) gồm toàn bộ dữ liệu People của một person; handler ẩn danh hóa tuân thủ thời hạn lưu trữ tối thiểu theo luật (giữ hồ sơ lao động/BHXH/thuế, xóa dữ liệu tùy chọn); xác nhận hoàn tất gửi về SaaS (OUT-33) | BR-PPL-153 | 8 | BR-SAAS-081, US-PPL-083 | `[verify-legal]` thời hạn lưu trữ tối thiểu và hạn chót (D-04, Q9) |

### EP-PPL-13 Import số dư đầu kỳ hồ sơ nhân viên

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-PPL-089 | EP-PPL-13 | Là tư vấn triển khai, tôi muốn có mẫu Excel import số dư đầu kỳ hồ sơ nhân viên (các sheet: person, liên hệ/địa chỉ, employment với ngày tuyển dụng ban đầu và ngày tính thâm niên, phân công hiện tại, hợp đồng hiện tại với số hợp đồng xác định thời hạn trước đó, lương hiện tại, lịch sử lương, gia đình/người phụ thuộc với tháng đăng ký, tài khoản ngân hàng) được đăng ký với import engine của Core để khách hàng có thể chuẩn bị file (R2-13). AC: mẫu do Core sinh từ định nghĩa loại import của People, tiêu đề VI/EN và từ điển cột; danh mục tra cứu được liệt kê trên một sheet tham chiếu; có chế độ chỉ kiểm tra hợp lệ | NFR-PPL-007 | 5 | BR-CORE-110, BR-CORE-112, BR-CORE-114, BR-SAAS-069 | Ví dụ tham chiếu "mẫu import Excel" |
| US-PPL-090 | EP-PPL-13 | Là tư vấn triển khai, tôi muốn có kiểm tra hợp lệ theo dòng và chéo giữa các sheet (tra cứu tổ chức/vị trí/đơn vị hành chính/ngân hàng, địa chỉ 3 cấp cũ, person trùng trong file và trong tenant, một employment còn hiệu lực cho mỗi pháp nhân, quy tắc hợp đồng và lương, employment chính cho nhân viên kiêm nhiệm) để dữ liệu sai bị từ chối trước khi nạp. AC: file lỗi theo từng dòng; thuộc tính tùy biến chưa ánh xạ được báo cáo để ánh xạ vào trường chuẩn hoặc tệp đính kèm (R3-09); cùng quy tắc nghiệp vụ như trên màn hình, phân biệt cảnh báo và lỗi | NFR-PPL-007, BR-PPL-001 | 8 | US-PPL-089, US-PPL-003, BR-CORE-111 | Tái sử dụng validator của US-PPL-028/043/048/050 |
| US-PPL-091 | EP-PPL-13 | Là tư vấn triển khai, tôi muốn có thao tác nạp idempotent tạo person, employment, phân công theo ngày hiệu lực, hợp đồng, hồ sơ lương, người phụ thuộc và tài khoản ngân hàng kèm nguồn gốc import để chạy lại không bao giờ gây trùng lặp. AC: upsert theo khóa tự nhiên (CCCD, mã nhân viên); hồ sơ lương được ghi với nguồn = số dư đầu kỳ thay vì quyết định; sự kiện đầu kỳ được publish một lần kèm cờ `opening_balance`; có thể đảo ngược theo lô | NFR-PPL-007 | 8 | US-PPL-090, US-PPL-041, US-PPL-080, BR-SAAS-070 | |
| US-PPL-092 | EP-PPL-13 | Là Payroll, tôi muốn import các giai đoạn làm việc trước đây, thời gian công tác được công nhận, các giai đoạn đã chi trả trợ cấp thôi việc và lịch sử lương cần cho trợ cấp thôi việc và các cơ sở tính bình quân để người nghỉ việc sau go-live được quyết toán chính xác. AC: các dòng lịch sử lưu dưới dạng bản ghi theo ngày hiệu lực đã đóng; không phát sự kiện cho lịch sử; hiển thị trên dòng thời gian nhân viên của hồ sơ chấm dứt | NFR-PPL-007, BR-PPL-074 | 5 | US-PPL-091 | |
| US-PPL-093 | EP-PPL-13 | Là trưởng nhóm C&B của khách hàng, tôi muốn có báo cáo đối soát và chất lượng dữ liệu (số lượng theo pháp nhân, headcount đang làm việc, tổng lương, người phụ thuộc, thiếu mã số thuế/mã số BHXH/ngân hàng) so với tổng kiểm soát của khách hàng để có thể ký xác nhận việc nạp. AC: cung cấp dữ liệu cho đối soát BR-SAAS-072; chênh lệch liệt kê theo từng nhân viên; xuất được để ký xác nhận | NFR-PPL-007 | 5 | US-PPL-091, BR-SAAS-072 | Báo cáo R20 |

---

## 3. Kiểm tra độ bao phủ (57 yêu cầu P1)

| P1 ID | Được bao phủ bởi |
|---|---|
| BR-PPL-001 | US-PPL-003, US-PPL-004, US-PPL-018, US-PPL-090 |
| BR-PPL-002 | US-PPL-018, US-PPL-021 |
| BR-PPL-003 | US-PPL-017, US-PPL-018, US-PPL-019, US-PPL-020, US-PPL-026 |
| BR-PPL-004 | US-PPL-022, US-PPL-023, US-PPL-024, US-PPL-025, US-PPL-080 |
| BR-PPL-010 | US-PPL-002, US-PPL-005, US-PPL-006, US-PPL-016 |
| BR-PPL-011 | US-PPL-007, US-PPL-059 |
| BR-PPL-013 | US-PPL-008 |
| BR-PPL-014 | US-PPL-009 |
| BR-PPL-015 | US-PPL-031, US-PPL-032 |
| BR-PPL-016 | US-PPL-024, US-PPL-027, US-PPL-028 |
| BR-PPL-017 | US-PPL-029, US-PPL-030, US-PPL-074, US-PPL-076 |
| BR-PPL-019 | US-PPL-010 (tối thiểu, R3-07) |
| BR-PPL-020 | US-PPL-011, US-PPL-059 |
| BR-PPL-021 | US-PPL-012 |
| BR-PPL-023 | US-PPL-013, US-PPL-014 |
| BR-PPL-030 | US-PPL-033, US-PPL-036, US-PPL-037 |
| BR-PPL-031 | US-PPL-033, US-PPL-034 |
| BR-PPL-032 | US-PPL-035, US-PPL-036 |
| BR-PPL-035 | US-PPL-040 |
| BR-PPL-037 | US-PPL-038, US-PPL-039 |
| BR-PPL-040 | US-PPL-041, US-PPL-042, US-PPL-046 |
| BR-PPL-041 | US-PPL-043 |
| BR-PPL-044 | US-PPL-044, US-PPL-045 |
| BR-PPL-050 | US-PPL-047 |
| BR-PPL-051 | US-PPL-048 |
| BR-PPL-052 | US-PPL-049, US-PPL-059 |
| BR-PPL-053 | US-PPL-050, US-PPL-052 |
| BR-PPL-054 | US-PPL-051, US-PPL-052, US-PPL-059 |
| BR-PPL-055 | US-PPL-053 |
| BR-PPL-056 | US-PPL-054 |
| BR-PPL-057 | US-PPL-055, US-PPL-056 |
| BR-PPL-070 | US-PPL-060, US-PPL-061 |
| BR-PPL-071 | US-PPL-062 |
| BR-PPL-072 | US-PPL-065, US-PPL-076 |
| BR-PPL-073 | US-PPL-063, US-PPL-082 |
| BR-PPL-074 | US-PPL-064, US-PPL-092 |
| BR-PPL-075 | US-PPL-059, US-PPL-061, US-PPL-066, US-PPL-067, US-PPL-080, US-PPL-082 |
| BR-PPL-120 | US-PPL-072, US-PPL-073, US-PPL-074, US-PPL-075, US-PPL-076, US-PPL-077 |
| BR-PPL-123 | US-PPL-072, US-PPL-078 |
| BR-PPL-130 | US-PPL-057, US-PPL-058 |
| BR-PPL-140 | US-PPL-068 |
| BR-PPL-141 | US-PPL-059, US-PPL-069, US-PPL-071 |
| BR-PPL-142 | US-PPL-059, US-PPL-070, US-PPL-071 |
| BR-PPL-150 | US-PPL-083 |
| BR-PPL-151 | US-PPL-084 |
| BR-PPL-152 | US-PPL-085 |
| BR-PPL-153 | US-PPL-088 |
| NFR-PPL-001 | US-PPL-037, US-PPL-081 |
| NFR-PPL-002 | US-PPL-079, US-PPL-080 |
| NFR-PPL-003 | US-PPL-015, US-PPL-016 |
| NFR-PPL-005 | US-PPL-031, US-PPL-086 |
| NFR-PPL-006 | US-PPL-045, US-PPL-085, US-PPL-087 |
| NFR-PPL-007 | US-PPL-089, US-PPL-090, US-PPL-091, US-PPL-092, US-PPL-093 |
| NFR-PPL-008 | US-PPL-001, US-PPL-002, US-PPL-003 |
| NFR-PPL-009 | US-PPL-015, US-PPL-078 |
| NFR-PPL-010 | US-PPL-055, US-PPL-058 |
| NFR-PPL-011 | US-PPL-072, US-PPL-075, US-PPL-078 |

**Kết quả:** 57 trên 57 yêu cầu P1 được bao phủ (47 BR + 10 NFR); không có yêu cầu nào bị bỏ sót. Không story nào bàn giao yêu cầu P2/P3; các hạng mục P2 có liên quan ở phần rìa (thâm niên khi tái tuyển dụng BR-PPL-005, yêu cầu thay đổi dữ liệu qua ESS BR-PPL-022, MSS BR-PPL-121, hợp đồng điện tử BR-PPL-058, UI quản lý mẫu BR-PPL-131, thay đổi hàng loạt NFR-PPL-004) được loại trừ rõ ràng trong ghi chú của story.

Kiểm tra (Git Bash, từ thư mục này):
```bash
awk -F'|' '/^### 9.2/{s=1} /^### 9.3/{s=0} s && /^\| (BR|NFR)-/ && $5 ~ /P1/ {id=$2; gsub(/ /,"",id); print id}' ../brd/mvp-scope.md | sort > /tmp/p1.txt
awk -F'|' '/^\| US-PPL-/ {print $5}' people-backlog.md | grep -oE '(BR|NFR)-PPL-[0-9]+' | sort -u > /tmp/cov.txt
comm -23 /tmp/p1.txt /tmp/cov.txt   # expect empty (uncovered P1)
comm -13 /tmp/p1.txt /tmp/cov.txt   # expect empty (non-P1 refs)
```

---

## 4. Tổng hợp và ghi chú rủi ro

### 4.1 Story point

| Epic | Số story | SP |
|---|---|---|
| EP-PPL-01 Cá nhân & dữ liệu cá nhân | 16 | 65 |
| EP-PPL-02 Quan hệ lao động & kiêm nhiệm | 10 | 53 |
| EP-PPL-03 Gia đình, người phụ thuộc & tài khoản ngân hàng | 6 | 29 |
| EP-PPL-04 Phân công theo ngày hiệu lực | 8 | 40 |
| EP-PPL-05 Hồ sơ lương | 6 | 29 |
| EP-PPL-06 Hợp đồng lao động & phụ lục | 10 | 48 |
| EP-PPL-07 Quyết định nhân sự & thông báo People | 3 | 13 |
| EP-PPL-08 Chấm dứt HĐLĐ & kích hoạt quyết toán cuối cùng | 8 | 40 |
| EP-PPL-09 Sổ quản lý lao động & báo cáo lao động theo luật định | 4 | 27 |
| EP-PPL-10 Trang web ESS cho People | 7 | 26 |
| EP-PPL-11 Sự kiện nhân viên & read API | 4 | 23 |
| EP-PPL-12 PDPL cho dữ liệu People | 6 | 32 |
| EP-PPL-13 Import số dư đầu kỳ hồ sơ nhân viên | 5 | 31 |
| **Tổng** | **93** | **456** |

Cơ cấu điểm: 1 SP × 2, 2 SP × 8, 3 SP × 21, 5 SP × 42, 8 SP × 19, 13 SP × 1.

**Góc nhìn năng lực (hướng dẫn §6, dành cho PM).** 456 SP ≈ 456 ngày công developer chưa tính chi phí phát sinh (≈ 640 khi cộng +40%). Với 7 SP mỗi developer mỗi sprint qua 6 sprint xây dựng (42 SP mỗi developer), riêng People cần **≈ 11 developer** cho khung thời gian xây dựng 12 tuần, và đó là chưa tính đến thời điểm các engine Core mà People phụ thuộc sẵn sàng. Điều này xác nhận rủi ro 1 trong decision-log (X-04): hoặc People được cấp năng lực đó, hoặc phải xem xét lại phạm vi ngoài chuỗi nghiệp vụ theo luật định (các ứng viên được liệt kê ở §4.3).

### 4.2 Ghi chú rủi ro: các story `[high-uncertainty]`

| Story | SP | Lý do |
|---|---|---|
| US-PPL-023 Tham gia bảo hiểm theo từng loại cho mỗi employment | 13 | Quy tắc của sponsor (tất cả FIRST_CONTRACT, R3-03) mâu thuẫn với cách PM hiểu Luật BHYT (HIGHEST_SALARY); phương án HIGHEST_SALARY cần so sánh lương giữa các pháp nhân, quy tắc khi bằng nhau và tính lại khi có mọi thay đổi lương. Tư vấn pháp lý phải xác nhận trước khi bắt đầu sprint. |
| US-PPL-043 Lương so với khung và lương tối thiểu vùng (bao gồm quy đổi gross cho NET) | 8 | Lương tối thiểu vùng 2026 `[verify-legal]`; kiểm tra NET phụ thuộc vào API mô phỏng gross-up của Payroll chưa được đặc tả. |
| US-PPL-050 Quy tắc hai hợp đồng xác định thời hạn / 36 tháng kèm ngoại lệ | 8 | Danh sách ngoại lệ theo luật định còn mở ("v.v.") và tương tác với thời hạn giấy phép của người lao động nước ngoài và người lao động cao tuổi. |
| US-PPL-070 Thông báo biến động lao động hằng tháng | 8 | Nghị định hướng dẫn Luật Việc làm 2025 và biểu mẫu thông báo chưa được xác nhận; bố cục có thể thay đổi. |
| US-PPL-030 Tờ khai đăng ký người phụ thuộc 07/ĐK-NPT-TNCN | 8 | Phiên bản mẫu hiện hành và việc nộp dạng danh sách hay tờ khai riêng lẻ chưa được xác nhận. |
| US-PPL-086 Mã hóa mức trường với blind index | 8 | Chi phí key-unwrap cho 10k person mỗi lần chạy lương chưa được đo (spike trong decision log §4); có thể làm thay đổi thiết kế read API. |

Các story `[verify-legal]` khác (quy tắc trong BRD đủ rõ, được ước lượng không cộng +1 bậc, ước lượng lại nếu tư vấn pháp lý thay đổi quy tắc): US-PPL-005, 008, 011, 028, 040, 047, 048, 051, 053, 054, 060, 062, 063, 064, 066, 068, 069, 083, 088.

### 4.3 Ghi chú phụ thuộc và phạm vi
- **Thời điểm có engine Core.** 41 story phụ thuộc vào một engine của Core hoặc SaaS (workflow, đánh số, mẫu tài liệu, import, che dữ liệu, khung ESS). Công việc People ở sprint 1–2 nên là phần nền tảng không phụ thuộc engine: US-PPL-001/002/003/017/033/041/047 và phần nối outbox US-PPL-079.
- **Hợp đồng giao tiếp với Payroll.** US-PPL-014, 039, 041, 043, 064, 066 cần API/sự kiện của Payroll (đề xuất 183 ngày, kỳ đã khóa, danh mục thành phần lương, bộ mô phỏng gross-up, kết quả trợ cấp thôi việc, đồng hồ quyết toán); thống nhất schema trong sprint 1 (RK4).
- **Dự phòng cắt giảm phạm vi nếu thiếu năng lực** (không thuộc luật định hoặc có cách xử lý thay thế; PM/sponsor quyết định): US-PPL-004 gộp person (sửa thủ công có hỗ trợ DB), US-PPL-014 (HR nhập trực tiếp thay đổi cư trú), US-PPL-025 (báo cáo ngoại lệ), US-PPL-034 quản lý theo vị trí (chỉ dùng quản lý đích danh), US-PPL-077 ESS cho người đã nghỉ việc (HR gửi tài liệu qua e-mail). Tổng cộng ≈ 15 SP.
