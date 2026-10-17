# ADR-0016: Integration hub và mẫu connector — Phase 1 = chỉ framework xuất file

- **Trạng thái:** Đề xuất (sửa đổi 2026-10-16, decision log R2-01: không có tích hợp trực tiếp với bên ngoài ở Phase 1; runtime của hub chuyển sang Phase 2)
- **Ngày:** 2026-10-16
- **Liên quan:** SaaS BRD §4.F; gap-analysis GAP-008, GAP-009, GAP-052, GAP-058; decision log R2-01, R2-19, §7.2; NFR-PAY-010, NFR-REC-007, NFR-ATT-001/004; BRD RK7; [architecture-overview](../architecture-overview.md) §7.5; ADR-0004 rev. 2, ADR-0012 (statutory template framework), ADR-0015

## Bối cảnh

YourHRM sẽ tích hợp với ngân hàng, eTax/T-VAN, VSS/I-VAN, MISA/Fast/SAP, CA chữ ký số, VNeID, máy chấm công, Zalo ZNS/SMS/push, trang tuyển dụng, lịch và bộ phân tích CV. Định dạng và API thay đổi thường xuyên (RK7); thông tin xác thực theo từng tenant; lỗi phải được retry không mất dữ liệu và phải hiển thị được.

Quyết định R2-01 (2026-10-16, được xác nhận bởi R2-19 và cách diễn giải của PM trong decision log §7.2) loại bỏ **mọi tích hợp trực tiếp với bên ngoài khỏi Phase 1**. Phase 1 chỉ giữ lại:

- **E-mail** (đăng nhập, MFA, OTP cho người đã nghỉ việc, thông báo, phiếu lương PDF) và **thông báo trong ứng dụng** — qua channel adapter của SaaS, không qua hub;
- **File xuất mà khách hàng tải về và tải lên** cổng ngân hàng, VSS hoặc thuế: file chi lương ngân hàng, file kê khai BHXH (D02-LT và liên quan), XML thuế TNCN cho eTax (05/KK, 05/QTT), bút toán GL dạng Excel;
- **Import Excel / CSV**, bao gồm **file xuất từ phần mềm máy chấm công** (mẫu dữ liệu chấm), thông qua import engine của Core.

## Quyết định

1. **Phase 1 — chỉ framework xuất file (trong `saas-platform/integration`):**
   - Một **registry mẫu định dạng**: layout ngân hàng (CSV/XLSX/XML theo từng ngân hàng pilot, tuỳ chọn không dấu), layout file VSS, XSD eTax, mẫu ánh xạ GL — các artifact có phiên bản với ngày hiệu lực, phát hành không cần release (NFR-PAY-010) và dùng chung với statutory template framework (ADR-0012).
   - **Port interface theo từng đầu ra** (`BankPaymentFileWriter`, `SiDeclarationFileWriter`, `PitDeclarationXmlWriter`, `GlJournalWriter`): module sở hữu (Payroll) cung cấp dữ liệu, writer render và **kiểm tra** (XSD, checksum, tổng kiểm soát) trước khi file được phát hành cho người dùng.
   - File được sinh dưới dạng job trên worker pool `general`, lưu qua document service của Core (tenant prefix, mã hoá khi lưu, signed URL tải xuống ≤ 15 phút, step-up cho file ngân hàng), và mỗi lần sinh và tải xuống đều được audit kèm phiên bản mẫu đã dùng.
   - **Không có lời gọi mạng ra ngoài**, không có thông tin xác thực nhà cung cấp theo tenant, không có callback từ ngoài vào ở Phase 1. Worker pool `integration` không được deploy.
   - File đầu vào (file xuất từ thiết bị, VSS C12 nếu dùng) là các loại import của Core do module nhận sở hữu, không phải connector của hub.
2. **Phase 2 — runtime integration hub** (giữ nguyên thiết kế từ revision 1, kích hoạt theo từng connector):
   - Hub là nơi duy nhất giao tiếp với nhà cung cấp bên ngoài (ngoại trừ IdP brokering của Keycloak), chạy trên worker pool `integration`.
   - **Connector = anti-corruption adapter** đứng sau một port theo từng năng lực (`TaxDeclarationSubmitter`, `SiDeclarationSubmitter`, `BankH2H`, `ESignProvider`, `MessageChannel`, `DeviceConnector`, `AccountingPush`, `JobBoardPublisher`…), được quản lý phiên bản độc lập. Các file writer của Phase 1 được tái sử dụng làm payload builder cho các API connector.
   - Lời gọi ra ngoài qua job với circuit breaker Resilience4j, timeout, retry theo cấp số nhân, trạng thái dead và replay bởi operator; callback từ ngoài vào qua webhook có chữ ký vào hub, được chuyển thành domain event.
   - Thông tin xác thực theo tenant trong OpenBao; lưu trữ request/response có che PII trong 90 ngày.
   - **Máy chấm công:** connector agent on-prem (đệm ≥ 7 ngày) → `hrm-ingest` (mTLS) → Kafka (ADR-0004 rev. 2); T&A sở hữu registry thiết bị, SaaS sở hữu transport và thông tin xác thực; mục tiêu công suất X-05 (500 lượt chấm/s toàn nền tảng, 50/s mỗi tenant) được xác nhận bởi SP-3.
   - Connector Phase 2: connector máy chấm công + bộ đệm offline, Zalo OA/ZNS, SMS brandname (P3), chữ ký số / hợp đồng lao động điện tử, VNeID, API kế toán (MISA, Fast, SAP), bank H2H, API T-VAN/I-VAN, trang tuyển dụng, lịch, bộ phân tích CV, **public webhook** (ADR-0014).
3. **Lưu trú dữ liệu:** mặc định chỉ dùng nhà cung cấp đặt tại VN; bên xử lý ngoài VN cần tenant opt-in và một bản ghi DPIA. Áp dụng từ Phase 2 (Phase 1 chỉ gửi dữ liệu tới e-mail relay tại VN).

## Các phương án đã cân nhắc

- **Vẫn xây runtime hub ở Phase 1** (connector dựa trên job, circuit breaker, credential vault theo tenant) để Phase 2 chỉ cần thêm adapter. Bị loại: không có bên tiêu thụ ở Phase 1, tốn khoảng 3–4 tuần-lập-trình-viên mà kế hoạch 16 tuần cần cho việc khác; việc tách port/adapter ở quyết định 1 giữ cho bước Phase 2 nhỏ.
- **iPaaS / ESB (MuleSoft, WSO2, Camel K):** nặng; iPaaS trên cloud vi phạm yêu cầu lưu trú dữ liệu. Các component Apache Camel vẫn có thể dùng bên trong adapter Phase 2.
- **Code tích hợp bên trong từng module:** trùng lặp template, xử lý thông tin xác thực, retry và giám sát.

## Hệ quả

- + Phase 1 không có bề mặt tích hợp ra ngoài: ít secret hơn, không có sự cố của bên thứ ba trên luồng tính lương, review bảo mật gọn hơn.
- + Các đầu ra theo quy định cần cho go-live (ngân hàng, VSS, thuế TNCN, GL) vẫn được hệ thống sinh và kiểm tra; thay đổi định dạng vẫn không cần release.
- − Khách hàng phải tự tải file lên cổng ngân hàng, VSS và eTax ở Phase 1 (bước thủ công, được mô tả trong runbook; hệ thống ghi nhận "đã tải file" nhưng không xác nhận được việc nộp).
- − Dữ liệu chấm công từ máy chấm công phụ thuộc vào việc khách hàng xuất file từ phần mềm thiết bị (tần suất thoả thuận với pilot; tối thiểu mỗi kỳ lương một lần, lý tưởng là hằng ngày để rà soát ngoại lệ).
- − Đội hub sẽ là nút thắt ở Phase 2 nếu thiếu người; connector nên được phát triển cùng BA của module.
