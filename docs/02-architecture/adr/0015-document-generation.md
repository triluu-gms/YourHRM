# ADR-0015: Engine sinh tài liệu; phiếu lương PDF có mật khẩu gửi qua e-mail

- **Trạng thái:** Đề xuất (sửa đổi 2026-10-16: quyết định 6 áp dụng quyết định của nhà tài trợ **R2-17** — phiếu lương PDF có mật khẩu gửi qua e-mail — đã được chấp thuận; thiết kế kỹ thuật của quyết định 6 ở trạng thái Đề xuất)
- **Ngày:** 2026-10-16
- **Liên quan:** gap-analysis GAP-007, GAP-057; decision log R2-17; NFR-PPL-010 (PDF/A, font tiếng Việt), NFR-SAAS-013, NFR-PAY-010/011, NFR-CORE-005; [architecture-overview](../architecture-overview.md) §9.3, §9.4; ADR-0005 (step-up), ADR-0026 (subject DEK), ADR-0027 (audit); DESIGN.md §7.5, §9.3

## Bối cảnh

Hệ thống sinh ra: phiếu lương (10.000 phiếu mỗi kỳ chạy, phát hành trong vòng 30 phút), các PDF và sổ sách theo quy định, quyết định nhân sự (quyết định), hợp đồng và thư mà tenant muốn chỉnh sửa trong Word, file Excel xuất tới 100k dòng, và file XML cho eTax/VSS được kiểm tra theo mẫu hiện hành. Dấu tiếng Việt phải hiển thị đúng; lưu trữ yêu cầu PDF/A. Core sở hữu document service; SaaS sở hữu lưu trữ.

Gửi phiếu lương (GAP-057): quy tắc thông báo của Core là "không có dữ liệu nhạy cảm trong nội dung tin nhắn, chỉ dùng deep link". Nhà tài trợ đã quyết định (R2-17, 2026-10-16) rằng phiếu lương được gửi qua e-mail dưới dạng **PDF có mật khẩu**; quy tắc chỉ-deep-link của Core được nới lỏng cho phiếu lương. Người lao động Việt Nam thường mong nhận phiếu lương trong hộp thư, và nhiều công nhân pilot hiếm khi đăng nhập ESS.

## Quyết định

1. **Tài liệu hệ thống khối lượng lớn:** template HTML Thymeleaf → **OpenHTMLtoPDF** → PDF/A-2b có nhúng font (Noto Sans/Serif, Be Vietnam Pro); chạy in-process trên worker.
2. **Tài liệu tenant có thể chỉnh sửa:** template DOCX có merge field (**poi-tl**) → **Gotenberg** (LibreOffice) → PDF/A; registry merge field theo loại tài liệu (các module tự đăng ký field).
3. **Excel:** Apache POI SXSSF streaming; template cho các biểu mẫu Excel theo quy định.
4. **XML:** binding sinh bằng JAXB theo từng phiên bản mẫu; kiểm tra XSD trước khi phát hành; mẫu được quản lý phiên bản trong statutory template framework (ADR-0012).
5. **Lưu trữ & truy cập:** chỉ thông qua document service của Core; quét ClamAV với file tải lên; signed URL ≤ 15 phút. Các thông báo khác ngoài phiếu lương vẫn giữ quy tắc chỉ-deep-link.
6. **Phiếu lương gửi e-mail dạng PDF có mật khẩu (R2-17):**
   - **Bản lưu trữ và bản đính kèm.** Phiếu lương chính thức vẫn là bản **PDF/A-2b** không mã hoá trong document service (PDF/A cấm mã hoá), tải được trong ESS với step-up. Đối với e-mail, job phiếu lương tạo ra một **bản sao mã hoá riêng**: cùng nội dung đã render, dùng standard security handler của PDF 2.0 **AES-256 (revision 6)**, qua Apache PDFBox 3; quyền cho phép xem và in, không cho phép chỉnh sửa. File đính kèm được tạo theo từng người nhận, chuyển cho kênh e-mail dưới dạng object tồn tại ngắn hạn dưới tenant prefix (xoá sau khi gửi, tối đa 24 h) và không bao giờ được giữ lại như một tài liệu.
   - **Quy tắc mật khẩu.** **Người lao động tự đặt mật khẩu phiếu lương trong ESS** (step-up `acr ≥ 2`; tối thiểu 8 ký tự, không trùng hoặc chứa mã nhân viên, ngày sinh, CCCD hay số điện thoại; được kiểm tra với danh sách mật khẩu phổ biến). Mật khẩu **không bao giờ được gửi qua e-mail** — không trong e-mail phiếu lương, không trong bất kỳ e-mail nào khác — và không bao giờ được hiển thị lại sau khi đã đặt. HR và operator không thể đọc hay đặt mật khẩu; họ chỉ có thể xoá nó, việc này buộc người lao động đặt mật khẩu mới. Không dùng mật khẩu mặc định hay suy ra (như ngày sinh + các số cuối CCCD), vì các giá trị đó đồng nghiệp và HR đều biết.
   - **Chưa có mật khẩu.** Cho tới khi người lao động đặt mật khẩu (hoặc nếu họ chọn chỉ dùng ESS), e-mail phiếu lương **không có file đính kèm**: chỉ mang thông báo và link ESS như trước. HR thấy danh sách "chưa có mật khẩu phiếu lương" trước khi phát hành; e-mail đầu tiên sau go-live mời người lao động đặt mật khẩu.
   - **Xử lý khoá.** Mật khẩu được lưu trong `pay.payslip_delivery_preference.pdf_password_enc` dưới dạng giá trị **P3** mã hoá bằng subject DEK của người đó (ADR-0026; OpenBao transit bọc DEK bằng tenant KEK). Trường API là write-only; giá trị không bao giờ được ghi log, không đưa vào event, payload job hay template thông báo. Job e-mail phiếu lương (Payroll, pool `general`) unwrap DEK và giải mã mật khẩu chỉ trong bộ nhớ trong thời gian mã hoá một PDF, sau đó huỷ bỏ. Owner password của PDF là ngẫu nhiên cho từng file và bị huỷ bỏ. Crypto-shredding subject (DSR, retention) cũng huỷ luôn mật khẩu.
   - **Gửi và audit.** `pay.payslip_delivery` ghi lại cho mỗi e-mail: payslip id, địa chỉ người nhận, SHA-256 của file đính kèm đã mã hoá, `password_set_at` đang hiệu lực, trạng thái gửi và bounce. Audit event (ADR-0027, không chứa giá trị bí mật): đặt / xoá mật khẩu (người thực hiện, thời điểm), mỗi e-mail phiếu lương có đính kèm đã gửi, mỗi lỗi hoặc bounce, mỗi lần gửi lại. E-mail phiếu lương được gửi tới địa chỉ người lao động đã chọn (công việc hoặc cá nhân), nên người lao động đã nghỉ việc (FORMER_EMPLOYEE) nhận phiếu lương quyết toán cuối cùng tại địa chỉ cá nhân.
   - **Thông lượng.** Mã hoá AES chỉ thêm rất ít so với render; mục tiêu 10.000 phiếu lương ≤ 30 phút (NFR-CORE-005) được kiểm chứng trong SP-5 với mã hoá bật.

## Các phương án đã cân nhắc

- **Chỉ dùng link bảo mật (khuyến nghị của PM trong GAP-057).** Quyền riêng tư tốt nhất, nhưng bị nhà tài trợ loại (R2-17) vì tính tiện dụng.
- **Mật khẩu mặc định suy ra từ dữ liệu cá nhân** (ngày sinh, các số CCCD, mã nhân viên). Phổ biến trên thị trường, nhưng đồng nghiệp và HR đoán được; bị loại.
- **Gửi mật khẩu trong e-mail thứ hai hoặc SMS.** E-mail thứ hai tới cùng hộp thư không bảo vệ gì; SMS là P3. Bị loại.
- **Chỉ lưu khoá PDF suy ra từ mật khẩu thay vì mật khẩu đã mã hoá** (tính trước hash user-key revision 6 cho từng nhân viên). Tránh phải lưu chính mật khẩu, nhưng cần một PDF security handler tuỳ biến; giữ lại như một phương án tăng cường ở Phase 2.
- **Mã hoá e-mail S/MIME hoặc PGP.** Người lao động không có chứng thư số; không khả thi.
- **JasperReports / headless Chromium / engine thương mại** cho việc sinh tài liệu nói chung: như trước — trải nghiệm soạn template, tuân thủ PDF/A hoặc chi phí license; có thể dùng Aspose/Docmosis sau này nếu độ trung thực của Gotenberg không đạt SP-5.

## Hệ quả

- + Người dùng HR chỉnh template trong Word; phiếu lương được tạo nhanh và đạt chuẩn lưu trữ; người lao động nhận phiếu lương trong hộp thư đúng như nhà tài trợ yêu cầu.
- + Mật khẩu không bao giờ đi kèm tài liệu và không ai ngoài người lao động biết nó.
- − File đính kèm phiếu lương chỉ mạnh bằng mật khẩu của người lao động; có thể đoán offline khi một e-mail bị lộ. Giảm thiểu bằng quy tắc mật khẩu và AES-256 R6 (key derivation chậm).
- − Người lao động quên mật khẩu phải đặt lại trong ESS (step-up); dự kiến tăng tải hỗ trợ trong tháng đầu.
- − Phải duy trì hai engine; độ trung thực của DOCX phức tạp qua LibreOffice cần được kiểm chứng với template pilot (SP-5).
- − Quy tắc thông báo của Core giờ có một ngoại lệ được ghi nhận (phiếu lương); BA của Core và Payroll ghi lại điều này trong BRD của họ (GAP-057).
