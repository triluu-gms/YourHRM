# ADR-0008: Ngôn ngữ công thức tính lương (YFL) trên ANTLR và BigDecimal

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Liên quan:** Payroll BRD §4.1 (danh mục thành phần lương, công thức), NFR-PAY-002/003/007/008; [architecture-overview](../architecture-overview.md) §8.3

## Bối cảnh

Người dùng C&B cấu hình thành phần lương bằng công thức tham chiếu đến các phần tử khác, số lượng chấm công, dữ liệu People, tham số pháp lý và giá trị lũy kế từ đầu năm. Công thức chạy bên trong một JVM multi-tenant, nên phải được sandbox. Kết quả phải chính xác đến đồng (số thập phân dấu phẩy cố định), tất định và tái lập được trong 10 năm. 10.000 nhân viên × ~60 phần tử phải được tính xong trong thời gian dư dả dưới 15 phút. Cùng loại biểu thức này cũng cần cho điều kiện workflow và quy tắc điều kiện hưởng.

## Quyết định

Định nghĩa **YourHRM Formula Language (YFL)**, một ngôn ngữ biểu thức giống Excel, được triển khai trong `backend/src/shared/expression`:

- Ngữ pháp bằng **ANTLR 4**; parse → AST có kiểu → kiểm tra kiểu → trích xuất phụ thuộc → cây evaluator đã biên dịch (closure), được cache theo phiên bản cấu hình.
- Kiểu: Decimal (`BigDecimal`, `MathContext` 34 chữ số), Integer, Boolean, Date, Text, Table. Không dùng số dấu phẩy động ở bất kỳ đâu.
- Chỉ có các hàm thuần trong danh sách cho phép (`IF`, `AND`, `MIN`, `MAX`, `ROUND(x,n,mode)`, `BRACKET`, `PRORATE`, `PARAM`, `INPUT`, `ELEMENT`, `YTD`, các hàm ngày…); không có vòng lặp, không đệ quy, không I/O, không reflection, có ngân sách số node/độ sâu.
- Các phần tử của một nhóm lương tạo thành một DAG; chu trình bị từ chối ngay khi cấu hình.
- Các bộ hàm theo domain được đăng ký theo từng host: payroll, điều kiện workflow, quy tắc điều kiện hưởng.
- Hàm mới do nhà cung cấp bổ sung qua các bản phát hành (kèm golden test), không bao giờ do tenant bổ sung.

## Các phương án đã cân nhắc

- **SpEL / MVEL / OGNL:** mạnh nhưng làm lộ đồ thị object Java; sandbox khó và trong lịch sử thường bị rò rỉ; ngữ nghĩa số thập phân không thuận tiện.
- **Groovy (sandbox) / GraalJS / Lua:** ngôn ngữ đa dụng; khó ngăn thoát sandbox và khó kiểm soát tài nguyên; số trong JS là dấu phẩy động.
- **Google CEL (cel-java):** an toàn và nhanh, nhưng không có kiểu decimal native và không giống Excel đối với người dùng C&B.
- **Exp4j/EvalEx:** EvalEx hỗ trợ `BigDecimal` nhưng thiếu bảng có kiểu, đồ thị phụ thuộc và cấu trúc mở rộng; có thể dùng để khởi đầu nhanh nhưng sẽ sớm không đáp ứng được.
- **Drools/DMN:** nặng nề; DMN FEEL có ngữ nghĩa decimal nhưng xa lạ với người dùng C&B.

## Hệ quả

- + An toàn từ thiết kế, tất định, người dùng C&B đọc được, tái sử dụng được giữa các module.
- + Nhanh: dự kiến < 50 µs cho mỗi lần đánh giá phần tử (kiểm chứng trong SP-1).
- − Chúng ta tự sở hữu một ngôn ngữ: ngữ pháp, thông báo lỗi (vi/en), hỗ trợ trình soạn thảo (autocomplete, kiểm tra hợp lệ) ở frontend.
- − Công thức của pilot phải được chuyển đổi; SP-4 đo mức độ bao phủ (mục tiêu ≥ 95% mà không cần hàm mới).
