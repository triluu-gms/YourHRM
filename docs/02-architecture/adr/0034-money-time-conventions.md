# ADR-0034: Quy ước về tiền, làm tròn, ngày tháng và múi giờ

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, trưởng nhóm Payroll, Solution architect
- **Liên quan:** NFR-PAY-002 (số thực dấu chấm tĩnh, tính tất định chính xác tới VND), NFR-PAY-011, NFR-ATT-005, NFR-CORE-011/015, H-07 (hợp đồng USD); ADR-0009; [DESIGN.md](../../../database/postgresql/DESIGN.md) §4.6–4.7; DDL domain `sys.money_amount`, `sys.rate`, `sys.quantity`, `sys.month_date`

## Bối cảnh

Bảng lương phải chính xác tới từng VND và tất định. VND không có đơn vị lẻ, nhưng hợp đồng của người nước ngoài có thể bằng USD, và các tỷ lệ BHXH như 8 % hay 1,5 % cùng tỷ giá cần số thập phân chính xác. Chấm công trải qua các ca qua nửa đêm và thiết bị ở nhiều múi giờ, trong khi ngày công và ngày hiệu lực là ngày nghiệp vụ theo giờ địa phương.

## Quyết định

1. **Tiền:** `numeric(19,4)` (`sys.money_amount`) cộng `currency_code char(3)` trên dòng hoặc header của nó. Tỷ lệ dùng `numeric(12,8)` (`sys.rate`); số lượng (giờ, ngày) dùng `numeric(12,4)`. Cấm dùng kiểu số thực dấu phẩy động cho giá trị nghiệp vụ.
2. **Làm tròn là cấu hình:** mỗi thành phần lương có `rounding_mode` (`HALF_UP/DOWN/UP/HALF_EVEN`) và `rounding_unit` (1/10/100/1000). Mặc định theo luật định lấy từ legal pack (`ROUNDING.*`) `[verify-legal]`. Giá trị phải trả và giá trị kê khai được lưu **đã làm tròn**; dòng kết quả còn giữ `amount_unrounded numeric(24,8)`.
3. **Thời điểm** là `timestamptz`, session và server chạy theo UTC. **Ngày nghiệp vụ** là `date` theo lịch địa phương của địa điểm hoặc pháp nhân. Năm-tháng là ngày đầu tiên của tháng (`sys.month_date`). Giờ ca là `time` cộng `crosses_midnight`. Dữ liệu chấm công giữ `punch_at` (UTC), `local_ts` của thiết bị và `tz_name`.
4. **Khoảng** là nửa mở `[from, to)` cho ngày hiệu lực (ADR-0024). Ngày kết thúc nghiệp vụ dạng bao gồm (ngày kết thúc hợp đồng, ngày chấm dứt) được hiển thị đúng như vậy và được chuyển đổi trong các khoảng sinh tự động.
5. Văn bản là UTF-8 với collation mặc định ICU; collation ICU `vi-VN` được dùng để sắp xếp tên. Tìm kiếm không phân biệt dấu dùng `sys.search_key()` (`unaccent` + `đ→d`) với trigram index.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Số tiền VND kiểu `bigint` | Không biểu diễn được cent USD hay độ chính xác trung gian; sẽ cần biểu diễn thứ hai cho ngoại tệ. |
| Kiểu `money` của PostgreSQL | Phụ thuộc locale và cố định 2 chữ số thập phân; không chuyển đổi được sang `BigDecimal` của Java. |
| `timestamp without time zone` cho dữ liệu chấm công | Mơ hồ giữa các thiết bị và các quốc gia áp dụng giờ mùa hè (người nước ngoài đi công tác); khó chuẩn hoá. |
| Làm tròn cố định theo loại thành phần | Quy tắc làm tròn khác nhau theo chính sách tenant và biểu mẫu luật định; NFR-PAY-007 yêu cầu thay đổi được mà không cần release. |

## Hệ quả

- \+ Phép tính chính xác, chuyển đổi được (`BigDecimal` ↔ `numeric`) và có vết audit làm tròn tường minh.
- − Phép tính `numeric` trong các hàm tổng hợp SQL chậm hơn số nguyên. Điều này không đáng kể so với I/O ở khối lượng của chúng ta.
- − Developer không bao giờ được suy ra ngày nghiệp vụ từ `now()` mà không có múi giờ; lint rule và code review ép buộc điều này.
