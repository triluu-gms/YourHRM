# ADR-0022: Mỗi module một schema, không có foreign key chéo schema, projection được cấp bởi event

- **Trạng thái:** Đã chấp thuận (2026-10-16, D-01; ngoại lệ dữ liệu tham chiếu chỉ đọc X-02 theo ADR-0021)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect, PM
- **Liên quan:** Architecture brief B1/B2/B3; gap-analysis §5 (chủ sở hữu duy nhất), §9 A-1; ADR-0001 (modular monolith), ADR-0004 (event); [DESIGN.md](../../../database/postgresql/DESIGN.md) §2

## Bối cảnh

Tập schema đã được cố định: `saas`, `core`, `hr`, `att`, `pay`, `rec`. Mỗi dữ liệu có một chủ sở hữu duy nhất (gap §5). Worker tính lương và việc tiếp nhận dữ liệu chấm công phải scale out được riêng rẽ và sau này có thể chuyển sang database riêng. Một DB tenant riêng và một tenant di chuyển giữa các cluster phải mang cùng một mô hình. Câu hỏi là các dòng trong một module tham chiếu tới các dòng trong module khác như thế nào.

## Quyết định

1. Mỗi module sở hữu đúng một schema (cộng với `audit`, do Core sở hữu, và `sys`, một shared kernel không có bảng gồm các hàm và domain).
2. **Bên trong một schema**, FK là bắt buộc và dạng composite: `(tenant_id, x_id) → x(tenant_id, id)`.
3. **Giữa các schema không có FK**, kể cả không có FK tới `saas.tenant`. Tham chiếu tới entity của module khác là *mềm*: `<entity>_id uuid` với comment `-> core.position (soft)`. Chúng được kiểm tra qua API của chủ sở hữu tại thời điểm ghi và được kiểm tra hằng đêm bằng một báo cáo tham chiếu mồ côi.
4. **Master data global được tham chiếu bằng mã ổn định** (`country_code`, `bank_code`, `admin_unit_code`, `currency_code`), không bằng surrogate id.
5. Module nào cần truy cập hàng loạt dữ liệu của module khác sẽ giữ một **projection được cấp bởi event** tên là `ext_<owner>_<entity>` trong schema của chính nó. Mỗi dòng projection lưu `source_event_id`/`source_version`, có một job đồng bộ lại từ API của chủ sở hữu, và có so sánh checksum.
6. Các khoá phạm vi phi chuẩn hoá (`legal_entity_id`, `org_unit_id`) được sao chép vào các dòng theo phạm vi nhân viên để mỗi module có thể lọc theo phạm vi dữ liệu mà không cần join với Core.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| FK chéo schema chỉ tới `core` | Gắn chặt thứ tự migration và thứ tự xoá, hỏng ngay khi một module chuyển sang database khác, và cần grant `REFERENCES` giữa các module. |
| FK từ mọi bảng tới `saas.tenant` | `saas` nằm trong platform cluster (ADR-0036). Mỗi lần insert cũng sẽ lấy khoá `FOR KEY SHARE` trên một dòng nóng của mỗi tenant. |
| Bảng "kernel" dùng chung mà mọi module đều đọc (person, org) | Tái tạo lại monolith dùng chung database mà brief cấm; quyền sở hữu trở nên mơ hồ. |
| View database chéo schema | Phụ thuộc ẩn; phá vỡ mô hình role (ADR-0021). |

## Hệ quả

- \+ Các module có thể được migrate, scale và sau này tách ra một cách độc lập. DB ép buộc bản đồ module.
- \+ Khoá composite khiến tham chiếu chéo tenant là bất khả thi bên trong một schema.
- − Tính toàn vẹn giữa các module là eventual. Được giảm thiểu bằng kiểm tra của chủ sở hữu khi ghi, projection có phiên bản, kiểm tra mồ côi hằng đêm, và bắt tay phiên bản khi chốt kỳ (gap §7, ADR-0019).
- − Một số dữ liệu bị trùng lặp (projection). Điều này chấp nhận được: projection là cache có thể dựng lại, không phải bản gốc.
- − Báo cáo join nhiều module chạy trên reporting store (ADR-0032), không chạy trên OLTP.
