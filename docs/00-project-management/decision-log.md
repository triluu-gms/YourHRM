# Nhật ký quyết định & RAID — Phase 0 (2026-10-16)

Tổng hợp các quyết định còn mở phát sinh từ BRD, gap analysis và các bản nháp kiến trúc. Mỗi quyết định cần có người phụ trách và thời hạn trước khi chốt backlog MVP.

**Tình trạng 2026-10-16:** vòng 1–3 (§1–§2, §7, §8) đã được quyết định và **áp dụng** vào điều lệ dự án, các BRD (BRD v0.3 R3, gap analysis v1.2, mvp-scope v0.3), architecture overview v0.3, DESIGN.md v0.3 và các ADR. Phase 1 = **260 BR + 58 NFR**. Không còn gap mở nào chặn MVP. Các hạng mục còn mở: thẩm định pháp lý/DPO (§3) và xác nhận pháp lý cho R3-03. Diễn giải của PM: §6 và §7.2.

## 1. Các xung đột cấp PM phát hiện khi review chéo
| ID | Xung đột | Nguồn | Khuyến nghị của PM | Người quyết định | Quyết định |
|---|---|---|---|---|---|
| X-01 | **Có mã hóa số tiền lương không?** Solution architect liệt kê là có mã hóa; data architect khuyến nghị không, vì làm hỏng phép tổng SQL, GL, báo cáo và tốc độ tính lương. | architecture-overview.md; DESIGN.md §15 D-02/D-11; ADR-0026 | Theo data architect: không mã hóa cột cho số tiền. Bảo vệ bằng RLS, role theo module, masking view và audit truy cập. Chỉ mã hóa định danh và tài khoản ngân hàng. | Architect + DPO | Đồng ý |
| X-02 | **Role của module đọc các bảng tham chiếu toàn cục của Core** vi phạm quy tắc "không grant trên schema khác" của solution architect. | DESIGN.md D-12; ADR-0021/0035; ADR-0001 | Chấp nhận ngoại lệ chỉ đọc, chỉ cho dữ liệu tham chiếu toàn cục, được liệt kê trong test ArchUnit/DB. | Architect | chấp nhận |
| X-03 | **Khóa kỳ:** solution architect thay đổi gap-analysis §7 để thêm cơ chế *hold* của payroll trên phiên bản chấm công. | ADR-0019; gap-analysis §7, GAP-027 | Chấp nhận, vì nó loại bỏ deadlock khóa. BA T&A và Payroll cập nhật BRD. | BA T&A + Payroll | Chấp nhận |
| X-04 | **Phạm vi MVP:** 310 BR bắt buộc (không tính Tuyển dụng) so với mốc go-live pilot 16 tuần. | BRD §9–10 | Rủi ro cao. Đánh giá lại MVP chỉ giữ phần "thiết yếu cho pilot". Dự kiến cắt còn khoảng 180–200 BR, hoãn đánh giá, đào tạo, chi phí, trình thiết kế báo cáo và white-label. | Nhà tài trợ + PM | Đồng ý, cắt bớt tính năng và chuyển toàn bộ tính năng app mobile sang giai đoạn sau. Phase 1 chỉ gồm tính năng lõi của mỗi module. |
| X-05 | **Năng lực tiếp nhận dữ liệu chấm công:** T&A muốn 200/s mỗi vùng, SaaS ~333/s, còn solution architect đặt 500/s toàn nền tảng và 50/s mỗi tenant. | GAP-058; architecture-overview | Dùng con số của solution architect, sẽ xác nhận trong spike SP-3. | BA T&A | chấp nhận |
| X-06 | Các event mới chưa có trong danh mục v1: `att.AttendancePeriodHoldGranted/Released`, `core.ReorgPackageStatusChanged`, `people.OnboardingReadinessChanged`. | architecture-overview | Bổ sung vào gap-analysis §6 (danh mục v1.1). | Chuyên viên phân tích tích hợp | Đồng ý bổ sung vào gap-analysis §6 (danh mục v1.1) |
| X-07 | **Điều chỉnh điều lệ dự án:** thêm schema `saas`. | GAP-014 | Phê duyệt. | Nhà tài trợ | Phê duyệt |

## 2. Quyết định của nhà tài trợ / nghiệp vụ
| ID | Quyết định cần đưa ra | Nguồn | Quyết định |
|---|---|---|---|
| OD-1 | Nhà cung cấp cloud tại VN cho production và DR (Viettel IDC / FPT / VNG / CMC). Phải hỗ trợ các extension PG, logical replication và KMS (xem thêm D-07). | Solution arch. §17.2, Data arch. D-07 | Thực hiện đánh giá trong 2 tuần (SP-7), sau đó quyết định. |
| OD-2 | Framework frontend: React + TS + Ant Design, hoặc Angular. Phụ thuộc vào kỹ năng của nhóm. | ADR-0013 | React + TS + Ant Design |
| OD-3 | Java 25 + Spring Boot 4, hoặc phương án dự phòng Java 21 + Boot 3.5. Quyết định sau spike SP-1. | ADR-0002 | Java 25, dự phòng bằng 21 sau spike đầu tiên |
| OD-4 | Chấp nhận workflow engine tự phát triển, không có trình thiết kế BPMN. | ADR-0007 | Chấp nhận |
| OD-5 | BI cho Phase 2: Superset, hoặc một công cụ thương mại. | ADR-0012 | Superset |
| OD-6 | Mục tiêu chi phí hạ tầng trên mỗi nhân viên mỗi tháng, và giá sàn cho gói Enterprise dedicated. | Solution arch. | tính sau, hiện chưa cần. |
| OD-8 | Cựu nhân viên đăng nhập bằng OTP qua e-mail, nghĩa là phải thu thập e-mail cá nhân khi nghỉ việc (GAP-036). | ADR-0005 | Chấp nhận |
| OD-9 | Check-in qua web PWA cho pilot; app native ở Phase 2. | Solution arch. | Thực hiện ở phase 2. |
| OD-10 | Software key root cho MVP; HSM trước khi có gói Enterprise. | Solution arch. | Chấp nhận |
| D-01 | Chấp nhận tính toàn vẹn sau cùng (eventual integrity) giữa các module (không có foreign key xuyên schema). | ADR-0022 | Chấp nhận |
| D-03 | Ngưỡng lưu trữ archive: dữ liệu chấm công 25 tháng, bảng công 37, dòng lương 36. Khôi phục trong khoảng 2 ngày làm việc. | ADR-0030 | chấp nhận |
| D-09 | Lịch sử Oracle được chuyển đổi hiển thị ngày cut-over là ngày "được biết". | ADR-0033 | Không cần chuyển đổi dữ liệu, chúng ta sẽ xây dựng hệ thống mới. |
| D-10 | Định nghĩa nhân viên tính phí. | DESIGN.md §15 | đếm số người đang làm việc tại thời điểm cuối tháng, theo từng pháp nhân. |
| Flyway | Điều khoản giấy phép của bản Community. | ADR-0031 | Có, tôi nghĩ giấy phép Community là đủ |

## 3. Thẩm định pháp lý / DPO (chặn go-live tính lương)
- Mọi con số `[verify-legal]` trong các BRD: tỷ lệ và mức trần theo Luật BHXH 2024, lương tối thiểu vùng năm 2026, biểu thuế TNCN và các khoản giảm trừ năm 2026, giới hạn và tỷ lệ làm thêm giờ.
- D-04: thời hạn lưu trữ, bao gồm "dữ liệu chấm công lưu 10 năm sau khi chấm dứt lao động".
- D-05: bản backup giữ dữ liệu của người đã bị xóa tối đa 35 ngày. Cần tuyên bố của DPO theo PDPL 2025.
- PDPL 2025 / Nghị định 13/2023: DPIA cho sinh trắc học, GPS và selfie (T&A) và cho dữ liệu ứng viên (Tuyển dụng).

## 4. Các spike trước khi phê duyệt kiến trúc
| Spike | Mục tiêu | Phụ trách |
|---|---|---|
| SP-1 | Payroll engine: 10k nhân viên ≤ 15 phút (mục tiêu 5) | Solution arch. |
| SP-2 | Hiệu năng RLS + PgBouncer transaction mode với 100k nhân viên mỗi cluster | Data arch. |
| SP-3 | Tiếp nhận dữ liệu chấm công ở mức 500/s, có bộ đệm offline trên thiết bị | Solution arch. |
| SP-4 | Mức độ ngôn ngữ công thức bao phủ các chính sách lương của pilot (từ tài liệu quy chế lương, không phải từ Oracle) | BA Payroll + Solution arch. |
| SP-7 | Đánh giá cloud VN (2 tuần) | DevOps |
| — | Chi phí key-unwrap cho 10k người mỗi kỳ tính lương | Data arch. |

## 5. Các rủi ro hàng đầu
1. Phạm vi MVP so với tiến độ 16 tuần (X-04).
2. Độ chính xác của tham số pháp lý: các thay đổi về thuế TNCN và BHXH năm 2026 chưa được thẩm định.
3. Số dư đầu kỳ: go-live giữa năm cần nạp chính xác lương/thuế TNCN lũy kế từ đầu năm, lịch sử BHXH và số ngày phép còn lại từ các mẫu Excel (không chuyển đổi từ Oracle, D-09).
4. Kỳ tính thưởng Tết đầu tiên của pilot (tháng 2/2027) cần chính sách Tết/lương tháng 13, một lỗ hổng phạm vi trong gap-analysis §8.
5. Tự xây dựng các engine (workflow, ngôn ngữ công thức, scheduler) gây áp lực lên năng lực của nhóm.

## 6. Diễn giải của PM về các quyết định (2026-10-16)
| Quyết định | Diễn giải được áp dụng | Cần xác nhận? |
|---|---|---|
| D-09 Không chuyển đổi dữ liệu | Không có chương trình chuyển đổi từ Oracle, không staging, không chạy song song. Thay bằng chức năng **import số dư đầu kỳ** tổng quát (mẫu Excel): hồ sơ gốc nhân viên, thu nhập/thuế TNCN/BHXH lũy kế từ đầu năm, lịch sử đóng BHXH, số ngày phép còn lại, khoản vay — cần cho go-live giữa năm và quyết toán thuế TNCN cuối năm. | Nhà tài trợ |
| X-04 + OD-9 | Phase 1 = tính năng lõi của mỗi module, triển khai dưới dạng web app responsive (web quản trị + ESS). Toàn bộ tính năng mobile (app native, check-in PWA/GPS/selfie) chuyển sang Phase 2. Dữ liệu chấm công của pilot lấy từ máy chấm công và import Excel. | — |
| D-10 | Đơn vị tính phí = người đang làm việc tại cuối tháng **theo từng pháp nhân**; một người làm việc cho hai pháp nhân của cùng một tenant được tính hai lần. | Nhà tài trợ |

## 7. Vòng 2 — các quyết định cần đưa ra (phát sinh ngày 2026-10-16 sau khi áp dụng vòng 1)
| ID | Quyết định cần đưa ra | Nguồn | Khuyến nghị của PM | Quyết định |
|---|---|---|---|---|
| R2-01 | **Quy mô MVP.** Rà soát lại phạm vi cho ra **281 BR P1**, không phải 180–200: 107 BR là bắt buộc theo luật và 121 BR là chuỗi cần thiết để tạo ra các đầu ra đó. Phương án B cắt thêm 29 BR (→ 252), mỗi BR có cách xử lý thay thế. | [mvp-scope.md](../01-requirements/brd/mvp-scope.md) | Chấp nhận 281 làm phạm vi và giữ Phương án B làm dự phòng cắt giảm. **Ước lượng bằng story point trước khi cam kết 16 tuần**, vì 281 BR trong 16 tuần là rủi ro cao. | Bỏ tích hợp ngoài với máy chấm công, zalo, để giảm phạm vi. |
| R2-02 | **Phê duyệt bảng chủ sở hữu duy nhất** (gap-analysis §5) là bản cuối cùng. Việc này đóng phần lớn trong số 29 gap mở đang chặn MVP. | gap-analysis §3, §5 | Phê duyệt toàn bộ. | Đồng ý |
| R2-03 | **Không chạy song song** theo D-09. Có thêm chức năng so sánh bảng lương một tháng với kết quả tính lương hiện tại của khách hàng (BR-SAAS-073) vào P1 không? Khoảng 1 tuần công. | mvp-scope §8; BRD RK9 | Có, P1. | đồng ý |
| R2-04 | SSO (Google / Microsoft 365) hoãn sang P2. Pilot dùng username Keycloak + MFA. | BR-CORE-039, BR-SAAS-040 | Chấp nhận, trừ khi pilot yêu cầu SSO. | Pilot không cần SSO. SSO có thể chuyển sang giai đoạn sau |
| R2-05 | Quy trình kỷ luật hoãn sang P2. Pilot xử lý sa thải ngoài hệ thống. | BR-PPL-080/081 | Chấp nhận. | Đồng ý |
| R2-06 | Check-in qua web trên máy tính (giới hạn IP) hoãn sang P2 cùng OD-9. Nhân viên không có thiết bị dùng import Excel hoặc yêu cầu điều chỉnh công. | BR-ATT-024 | Chấp nhận nếu mọi địa điểm pilot đều có máy chấm công; nếu không thì giữ ở P1 (nhỏ). | Đồng ý chuyển sang P2, không phải nơi nào cũng có máy chấm công, có thể dùng import Excel. |
| R2-07 | Dữ liệu hồ sơ hưởng chế độ BHXH cho nghỉ ốm/thai sản (giấy chứng nhận, cơ sở y tế, thông tin con) hoãn sang P2. | BR-ATT-063 | **Giữ ở P1 (tối thiểu).** Người sử dụng lao động phải nộp hồ sơ ốm đau/thai sản cho BHXH Việt Nam, và payroll cần cùng dữ liệu này. | Đồng ý |
| R2-08 | Giới hạn làm thêm giờ theo năm xuyên pháp nhân vẫn ở P2, chờ tư vấn pháp lý. | BR-ATT-047; GAP-031 | Chấp nhận; hỏi chuyên gia tư vấn lao động. | Đồng ý |
| R2-09 | Đa tiền tệ (hợp đồng USD cho người nước ngoài) vẫn ở P2. | mvp-scope §8 | Chấp nhận, nếu pilot không có hợp đồng USD. Vui lòng xác nhận. | Đồng ý, không cần đa tiền tệ |
| R2-10 | Hợp đồng lao động điện tử vẫn ở P2, trừ khi có nghị định bắt buộc trước go-live. | People BRD | Chấp nhận. | Đồng ý, hợp đồng lao động điện tử hiện có thể giữ ở P2 |
| R2-11 | Danh sách ngoại lệ X-02 còn bao gồm các dòng override của tenant trong `core.code_value` / `core.exchange_rate`, cùng hai quyền ghi kỹ thuật: insert vào `saas.job` và append vào `audit.audit_event`. | ADR-0021; DESIGN.md §2.3 | Chấp nhận, coi như thuộc phạm vi X-02. | Đồng ý |
| R2-12 | Chi tiết D-10: (a) nhân viên `SUSPENDED` có tính phí không? (b) xác nhận một người làm việc cho 2 pháp nhân bị tính phí hai lần. | DESIGN.md §11; BR-SAAS-012 | (a) Không tính phí. (b) Tùy anh/chị quyết; tính phí một lần cho mỗi người trên mỗi tenant thân thiện hơn. |  (a) Không tính phí |
| R2-13 | Số dư đầu kỳ: ai chuẩn bị file Excel và ai ký biên bản đối soát? Dữ liệu phải bắt đầu từ ngày 1 tháng 1 của năm tính thuế. | BR-SAAS-088/089 | Khách hàng chuẩn bị, tư vấn triển khai của chúng ta kiểm tra, trưởng bộ phận C&B của khách hàng ký. | Đúng, khách hàng chuẩn bị |
| R2-14 | Chính sách thưởng Tết / lương tháng 13 cho pilot (kỳ chạy đầu tiên tháng 2/2027). | gap-analysis §8 | Cần đầu vào nghiệp vụ từ pilot. | Không cần khi chạy pilot, chỉ cần đưa một ít dữ liệu mẫu để kiểm tra chức năng hoạt động là đủ. |
| R2-15 | DPO ghi nhận trong DPIA: người nắm thông tin đăng nhập DB `hr`/`pay` có thể đọc được số tiền lương dạng plaintext (hệ quả của X-01). | Arch. AR-13 | Chấp nhận; giảm thiểu bằng secrets vault, không cho con người truy cập thông tin đăng nhập của ứng dụng, audit truy cập DB. | Chấp nhận |

### 7.1 Các quyết định tiếp theo của vòng 2 (2026-10-16)
| ID | Quyết định cần đưa ra | Nguồn | Khuyến nghị của PM | Quyết định |
|---|---|---|---|---|
| R2-16 | GAP-031: hỗ trợ một người làm việc cho hai pháp nhân của cùng một tenant trong Phase 1? | gap-analysis GAP-031 | Hỗ trợ cơ bản: giảm trừ gia cảnh thuế TNCN áp dụng một lần (nơi làm việc chính), BHXH qua một người sử dụng lao động; giới hạn làm thêm giờ xuyên pháp nhân ở P2 (R2-08). | Đồng ý |
| R2-17 | GAP-057: gửi phiếu lương qua e-mail. | gap-analysis GAP-057; quy tắc deep-link của Core | Mặc định gửi link bảo mật, PDF là tùy chọn. | **PDF có mật khẩu** (nhà tài trợ). Quy tắc chỉ dùng deep-link của Core được nới lỏng cho phiếu lương. |
| R2-18 | GAP-060: các năng lực nền tảng luôn được bật, bất kể tenant mua module nào. | gap-analysis GAP-060 | Core, People cơ bản, tham số pháp lý, thang/bậc lương (§5 #7), import chấm công Excel. | Đồng ý |
| R2-19 | Phương án B (cắt thêm 29 BR không bắt buộc theo luật, 281 → ~252) và toàn bộ tích hợp ngoài trực tuyến → P2 (R2-01). | mvp-scope §6 | — | Đồng ý (nhà tài trợ) |

### 7.2 Diễn giải của PM về vòng 2 (xác nhận hoặc điều chỉnh)
| Hạng mục | Diễn giải được áp dụng |
|---|---|
| R2-01 tích hợp | **P2:** connector trực tuyến với máy chấm công, bộ đệm offline và mục tiêu 500/s (X-05, SP-3 hoãn lại); Zalo OA/ZNS; SMS; ký số; VNeID; API kế toán; trang tuyển dụng; webhook công khai. **P1 giữ lại:** e-mail (đăng nhập, MFA, OTP theo OD-8, thông báo), thông báo trong ứng dụng, và **xuất file** để khách hàng tự tải lên: file chi trả ngân hàng, file kê khai bảo hiểm cho BHXH Việt Nam, XML thuế TNCN cho eTax, bút toán GL dạng Excel. **Import file xuất từ phần mềm máy chấm công (CSV/Excel) vẫn ở P1**: là import Excel theo bố cục cột của thiết bị, không phải tích hợp trực tuyến. |
| R2-12 (b) | Chưa có câu trả lời → giữ nguyên D-10: tính phí một lần cho mỗi pháp nhân. |
| R2-13 | Khách hàng chuẩn bị các file số dư đầu kỳ; tư vấn triển khai của chúng ta kiểm tra; trưởng bộ phận C&B của khách hàng ký biên bản đối soát. |
| R2-14 | Không có engine chính sách Tết ở P1. Kỳ tính thưởng / tính lương ngoài kỳ với thuế TNCN chính xác vẫn ở **P1**, để có thể chi trả thưởng Tết thực tế như một khoản một lần. Quy tắc điều kiện hưởng và tính theo tỷ lệ → P2. Kiểm thử bằng dữ liệu mẫu. |
| R2-07 | BR-ATT-063 (dữ liệu hồ sơ hưởng chế độ BHXH) chuyển lại **P1**, ở mức tối thiểu. |
| R2-03 | BR-SAAS-073 (so sánh bảng lương một tháng) chuyển sang **P1**. |

## 8. Vòng 3 — các quyết định cần đưa ra (phát sinh ngày 2026-10-16 sau khi áp dụng vòng 2)
| ID | Quyết định cần đưa ra | Nguồn | Khuyến nghị của PM | Quyết định |
|---|---|---|---|---|
| R3-01 | **Hóa đơn điện tử cho việc thu phí thuê bao của chính YourHRM** (BR-SAAS-014/015) cần API của nhà cung cấp hóa đơn điện tử được cấp phép, là tích hợp trực tuyến duy nhất còn lại ở P1. | mvp-scope §11.4 R2-C1; arch. điểm mở 1 | **P2.** Bộ phận tài chính của nhà cung cấp phát hành hóa đơn từ công cụ hóa đơn điện tử hiện có, dựa trên báo cáo sử dụng. Pilot khó có khả năng được tính phí qua sản phẩm. | chuyển sang P2 |
| R3-02 | **Chứng từ khấu trừ thuế TNCN điện tử** (BR-PAY-059) cần chữ ký số của người sử dụng lao động và có thể phải gửi dữ liệu cho cơ quan thuế (Nghị định 70/2025). Ký số ở P2. | R2-C3; arch. điểm 7 | P1 = dữ liệu và chứng từ PDF. Khách hàng ký và phát hành qua công cụ của cơ quan thuế hoặc nhà cung cấp hiện có của họ. **Chuyên gia tư vấn pháp lý phải xác nhận cho năm 2027.** | Ký số ở P2 |
| R3-03 | **Bảo hiểm cho một người có hợp đồng ở hai pháp nhân:** BHXH/BHTN theo hợp đồng đầu tiên, BHYT theo mức lương cao nhất, BHTNLĐ-BNN do từng người sử dụng lao động đóng. | R2-C2; GAP-031 | Triển khai tham gia theo từng loại bảo hiểm, theo từng quan hệ lao động (đã được thiết kế). **Chuyên gia tư vấn pháp lý xác nhận quy tắc.** | Quy tắc là theo hợp đồng đầu tiên, không theo lương cao nhất. |
| R3-04 | **Mật khẩu PDF phiếu lương:** nhân viên tự đặt trong ESS, không có mật khẩu mặc định hay mật khẩu suy ra. Cho đến khi được đặt, e-mail chỉ là thông báo không kèm file đính kèm. | R2-C7; BR-PAY-102; ADR-0015 | Chấp nhận. **DPO phê duyệt.** | Chấp nhận |
| R3-05 | **Nhân viên không có e-mail** không nhận được OTP hay phiếu lương, nhưng chi tiết phiếu lương phải đến được mọi nhân viên (Bộ luật Lao động Điều 95(3)). | R2-C6; BRD RK12 | Phương án dự phòng: HR in phiếu lương và cấp mã kích hoạt dùng một lần cho ESS. | In PDF rồi đưa cho nhân viên. |
| R3-06 | **Quyết toán cho người nghỉ việc trong vòng 14 ngày làm việc** mà không cần khóa sớm chấm công của một nhân viên (BR-ATT-075 bị cắt theo Phương án B). | R2-C4 | Khôi phục BR-ATT-075 vào P1 (nhỏ), vì cách xử lý thay thế (nhập payroll thủ công) dễ sai đối với một thời hạn theo luật. | Đồng ý |
| R3-07 | **Sổ quản lý lao động** vẫn cần trường trình độ chuyên môn (BR-PPL-019 bị cắt theo Phương án B). | R2-C5 | Khôi phục trường trình độ chuyên môn tối thiểu vào P1 (bắt buộc theo luật). | Đồng ý |
| R3-08 | **Nhật ký truy cập của operator / break-glass** bị cắt theo Phương án B, nhưng PDPL yêu cầu giám sát bên xử lý dữ liệu. | R2-C8 | Khôi phục ghi log tối thiểu việc operator truy cập dữ liệu tenant ở P1. | Đồng ý |
| R3-09 | **Custom field ở P2:** các thuộc tính riêng của tenant trong file nhân viên số dư đầu kỳ không có chỗ lưu. | R2-C11 | Ánh xạ chúng vào trường chuẩn hoặc file đính kèm trong bước kiểm tra mẫu. | Đồng ý |
| R3-10 | **GAP-049:** tỷ lệ BHTNLĐ-BNN và tần suất kê khai thuế TNCN nằm trên pháp nhân của Core (PM brief), không nằm trong thiết lập Payroll. | gap-analysis v1.2 | Chấp nhận. | Đồng ý |
| R3-11 | **Đơn giản hóa công nghệ cho Phase 1:** không dùng Kafka (event bus dựa trên DB, ADR-0004 rev. 2), không có ingest role (điều chỉnh ADR-0001), báo cáo được cấp dữ liệu bằng logical replication native (điều chỉnh ADR-0012). Kafka được bổ sung ở P2. | architecture-overview v0.3 §7.2 | Chấp nhận: tiết kiệm nhiều tuần công vận hành trong kế hoạch 16 tuần. | Đồng ý |

### 8.1 Ghi chú khi áp dụng vòng 3 (2026-10-16)
- **Lưu ý pháp lý R3-03 (PM):** nhà tài trợ quyết định bảo hiểm theo hợp đồng đầu tiên. PM cho rằng Luật Bảo hiểm y tế yêu cầu đóng BHYT theo hợp đồng có **mức lương cao nhất** khi một người có nhiều hợp đồng lao động. Vì vậy quy tắc được xây dựng dưới dạng tham số pháp lý `insurance.multi_contract_rule`, thiết lập theo từng loại bảo hiểm (FIRST_CONTRACT | HIGHEST_SALARY | EACH_EMPLOYER). Giá trị mặc định là BHXH/BHTN/BHYT = FIRST_CONTRACT (nhà tài trợ) và BHTNLĐ-BNN = EACH_EMPLOYER, tất cả đều `[verify-legal]`. Nếu chuyên gia tư vấn xác nhận quy tắc lương cao nhất cho BHYT, chỉ cần thay đổi tham số.
- **R3-02:** ở P1, chứng từ khấu trừ thuế TNCN là dữ liệu cộng với một file PDF chưa ký. Khách hàng ký và phát hành qua công cụ của cơ quan thuế hoặc nhà cung cấp riêng của họ. `[verify-legal]` cho năm 2027.
- **R3-05:** nhân viên không có e-mail nhận phiếu lương PDF in sẵn từ HR, không có mã kích hoạt và không có quyền truy cập ESS ở P1.
- **R3-11:** ADR-0004 rev. 2 và Amendment 1 của ADR-0001/0002/0012 được chuyển sang trạng thái Accepted.

## 9. Vòng 4 — các quyết định lập kế hoạch (phát sinh ngày 2026-10-16 sau khi ước lượng)
Bằng chứng: [backlog-summary.md](../01-requirements/user-stories/backlog-summary.md). Phase 1 = 608 story, ≈ 3.130 SP, ≈ 195 người-tháng. Kế hoạch 16 tuần sẽ cần khoảng 75 developer.

| ID | Quyết định cần đưa ra | Khuyến nghị của PM | Quyết định |
|---|---|---|---|
| R4-01 | **Đặt lại baseline tiến độ.** Mốc go-live Phase 1 trong 16 tuần là không khả thi (vượt năng lực khoảng 4–5 lần). | Chấp nhận đặt lại baseline: hoàn thành toàn bộ Phase 1 trong ≈ 13–14 tháng với ~16 developer (≈ 22 FTE), và kỳ tính lương thực tế đầu tiên trong ≈ 9–10 tháng thông qua release train (R4-02). | _Mở_ |
| R4-02 | **Release train theo lịch pháp lý:** R1 kỳ tính lương thực tế đầu tiên → R2 trong vòng ~3 tháng (truy lĩnh/truy thu, thưởng/tính lương ngoài kỳ trước Tết, các trường hợp biên của quyết toán khi nghỉ việc, báo cáo lao động 6 tháng) → R3 trước hạn quyết toán thuế TNCN cuối năm (quyết toán 05/QTT, chứng từ, tự đăng ký sử dụng và tính phí). | Chấp nhận, sau đó yêu cầu các BA gắn nhãn R1/R2/R3 cho từng story để ước lượng chính xác R1 (PM ước tính ~1.700–1.900 SP). | _Mở_ |
| R4-03 | **Quy mô nhóm và ngân sách:** có thể cấp kinh phí hoặc bố trí bao nhiêu developer? | 16 developer (≈ 6 Payroll, 3 Core, 3 People/T&A, 4 nền tảng/frontend) + QA, BA, UX, DevOps, PM ≈ 22 FTE. | _Mở_ |
| R4-04 | **Các đòn bẩy giảm công sức** (§6 của bản tóm tắt, ~200–275 SP): nền tảng pilot đơn giản hóa (single tenant, DR bằng backup/restore), hoãn kho dữ liệu báo cáo, hoãn mẫu DOCX theo tenant. | Chấp nhận, trừ thông tin đăng nhập DB tĩnh (xung đột với R2-15). | _Mở_ |
| R4-05 | **Mở lại OD-4:** dùng BPMN engine mã nguồn mở (ví dụ Flowable) thay cho workflow engine tự phát triển, tiết kiệm ~30–40 SP và một story 13 SP [high-uncertainty]. | Thực hiện spike so sánh 3 ngày trong sprint 0, sau đó quyết định. | _Mở_ |
| R4-06 | **Hiệu chỉnh velocity:** các mốc thời gian chỉ là ước tính lập kế hoạch cho đến khi đo được sprint 1–2. | Chỉ cam kết mốc R1 sau sprint 2. | _Mở_ |
| R4-07 | **Chuyên gia tư vấn lương/thuế VN:** 4–6 tuần công tư vấn cho các golden scenario và quy tắc `[verify-legal]`; cũng cần cho R3-02/R3-03. | Thuê trước sprint 1. | _Mở_ |

## 10. Vòng 5 — quyết định review DDL (D1–D11, nhà tài trợ đồng ý theo khuyến nghị ngày 2026-10-21)
Bằng chứng: [DESIGN.md](../../database/postgresql/DESIGN.md) §15.1, DDL v0.4 trong [database/postgresql/ddl/](../../database/postgresql/ddl/).

| ID | Vấn đề | Quyết định (2026-10-21) |
|---|---|---|
| D1 | Hai module cùng thực thi close hold | `att.attendance_result_hold` là nguồn duy nhất; `pay.run_attendance_version` chỉ là bản phản chiếu (bỏ unique một hold/kỳ). |
| D2 | Kết quả lương đóng băng từ `SUBMITTED` | Chấp nhận (chặt hơn `APPROVED`). |
| D3 | Lần chạy lương không có `CANCELLED` | Thêm `CANCELLED` (chỉ từ DRAFT/CALCULATED/REJECTED, có lý do, giải phóng hold). |
| D4 | Nguồn cấp reporting store chưa nối | Thêm `007_publications.sql` (column list loại cột P3, policy đọc cho `yhrm_cdc`) và bảng landing `att`/`pay` trong `100_rpt.sql`. |
| D5 | T&A thiếu ngày vào làm/nghỉ việc, lao động được bảo vệ | Thêm projection `att.ext_hr_employment`. |
| D6 | Masking view và `yhrm_reporting_ro` | Thu hồi grant view cho `yhrm_reporting_ro`; báo cáo đi qua reporting store. |
| D7 | Payload `rec.CandidateHired` chứa P3 | Payload tối giản; People lấy dữ liệu P3 qua API Recruitment theo `hire_id` (Phase 2). |
| D8 | Liên hệ ứng viên nâng lên P3 | Chấp nhận; DPO xác nhận. |
| D9 | Purge tenant bị chặn bởi trigger append-only | Cơ chế bypass có kiểm soát: role `yhrm_purger` + `sys.purge_bypass(tenant)` (chỉ DELETE, tenant ở trạng thái TERMINATING). |
| D10 | `pay.ext_att_result` lệch với `att` | Đồng bộ scope `PERIOD`/`EMPLOYMENT` + `result_kind`, unique theo `att_result_id`. |
| D11 | `reporting/100_rpt.sql` lạc hậu | Sửa theo DDL v0.4/v0.5 (bỏ override vùng lương, loại bảo hiểm `OA_OD`/`TU`, `source`, `COMPARISON`/`CANCELLED`). |
