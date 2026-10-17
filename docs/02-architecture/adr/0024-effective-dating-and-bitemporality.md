# ADR-0024: Ngày hiệu lực và bi-temporality một phần

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect, trưởng nhóm People và Payroll
- **Liên quan:** gap-analysis §9 A-3; NFR-PPL-001 ("tại ngày X, theo hiểu biết tại ngày Y"), NFR-CORE-003 (cây tổ chức tại một ngày), BR-PAY-072/076 (truy lĩnh/truy thu); ADR-0009, ADR-0025; [DESIGN.md](../../../database/postgresql/DESIGN.md) §5; DDL `sys.tg_bitemporal_guard`, `hr.assignment`, `hr.salary_record`

## Bối cảnh

Hầu hết dữ liệu gốc HR đều có ngày hiệu lực. People phải trả lời được "tại ngày X, theo hiểu biết tại ngày Y" cho phân công, lương và hợp đồng. Payroll phải phát hiện các thay đổi hồi tố sau một kỳ chạy (truy lĩnh/truy thu) và tái tạo các kỳ chạy trong 10 năm. Core phải trả về cây tổ chức tại bất kỳ ngày nào trong ≤ 1 s. Cần một mẫu thống nhất, được ép buộc trong DB và có thư viện dùng chung trong ứng dụng.

## Quyết định

1. **Mẫu A (valid time)** cho hầu hết dữ liệu gốc: `valid_from date`, `valid_to date` (không bao gồm, NULL = mở), một cột sinh `valid_during daterange '[)'`, và một `EXCLUDE USING gist (tenant_id WITH =, <key> WITH =, valid_during WITH &&)` (`btree_gist`). Cấu trúc gồm một bảng anchor (định danh) cộng một bảng version (thuộc tính). Các hiệu chỉnh là update tại chỗ và được audit log ghi lại.
2. **Mẫu B (bi-temporal)** cho `hr.assignment`, `hr.salary_record` và các tham số pháp lý (pack cung cấp transaction time theo phiên bản, ADR-0025). Là Mẫu A cộng thêm `recorded_at`, `superseded_at`, `superseded_by` và `correction_reason`. Exclusion constraint áp dụng `WHERE superseded_at IS NULL`. Các dòng **không bao giờ được update tại chỗ**; trigger `sys.tg_bitemporal_guard()` chỉ cho phép đóng dấu `superseded_*` và cấm `DELETE`.
3. **Quy ước truy vấn:**
   * hiểu biết hiện tại: `superseded_at IS NULL AND valid_during @> :d`;
   * theo hiểu biết tại `:k`: `recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k)`;
   * giao nhau theo kỳ: `valid_during && daterange(:start, :end, '[]')`.

   Mỗi quy ước có một partial B-tree index cho đường truy vấn nóng.
4. Phát hiện hồi tố: Payroll so sánh `recorded_at` với watermark People của kỳ chạy.
5. Cây tổ chức: `core.org_unit_version.path ltree` theo từng version với GiST index; cây con = `path <@ :root`.
6. Mọi thao tác ghi theo thời gian đều đi qua thư viện Java dùng chung `temporal-kit` (supersede, split, merge, close). Update theo thời gian viết tay sẽ bị loại khi code review.
7. Hợp đồng, quan hệ lao động và người phụ thuộc có ngày kết thúc nghiệp vụ *bao gồm* dùng cột sinh `daterange(start, end, '[]')`, được PostgreSQL chuẩn hoá thành `[)`.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Bảng `_history` riêng được trigger điền dữ liệu | Hai hình dạng dữ liệu phải truy vấn, và "theo hiểu biết tại" cần union. Khó biểu diễn hiệu chỉnh. |
| Bi-temporality đầy đủ ở mọi nơi | Tăng gấp đôi độ phức tạp và khối lượng cho dữ liệu không ai truy vấn "theo hiểu biết tại" (địa điểm, chức danh); audit log đã bao phủ các dữ liệu đó. |
| Bảng temporal SQL:2011 / extension `temporal_tables` | Không có sẵn trong PostgreSQL 16/17; extension không còn được bảo trì và mặc định không có trên image CNPG. |
| `valid_to` bao gồm | Các khoảng liên tiếp cần phép tính `+1 day` ở khắp nơi; lỗi lệch một đơn vị. |

## Hệ quả

- \+ Chồng lấn là bất khả thi theo thiết kế. Tái tạo bảng lương và truy lĩnh/truy thu có nguồn chính xác.
- \+ Một mẫu, một thư viện, một bộ cách viết truy vấn.
- − Bảng bi-temporal tăng theo mỗi lần hiệu chỉnh; khối lượng nhỏ (lịch sử lương/phân công).
- − Cột sinh dạng stored là NULL trong `NEW` bên trong BEFORE trigger, nên guard loại trừ `valid_during` (phát hiện trong DDL smoke test).
- − Không nạp lịch sử trước go-live (D-09, không migration từ Oracle). Dữ liệu đầu kỳ là version có hiệu lực tại ngày go-live G, với `recorded_at` = thời điểm nạp và `source = 'OPENING_LOAD'`; truy vấn "theo hiểu biết tại" trước thời điểm nạp trả về góc nhìn đầu kỳ (ADR-0037).
