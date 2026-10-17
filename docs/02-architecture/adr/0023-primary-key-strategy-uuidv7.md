# ADR-0023: Chiến lược primary key — id UUIDv7 và khoá tenant dạng composite

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect
- **Liên quan:** ADR-0020, ADR-0022, ADR-0033 (bảng ánh xạ khoá khi migration), ADR-0036 (tier riêng); [DESIGN.md](../../../database/postgresql/DESIGN.md) §4.1; DDL `sys.uuid_v7()`

## Bối cảnh

Id xuất hiện trong API, event (`event_id`, id của aggregate), payload outbox được ghi trước khi commit, import hàng loạt (import số dư đầu kỳ, ADR-0037), và khi di chuyển tenant giữa các pool cluster và tier riêng. Các bảng lớn (dữ liệu chấm công, dòng kết quả, audit) cần index thân thiện với insert. Cả RLS và FK composite đều có lợi khi có `tenant_id` trong khoá.

## Quyết định

1. Surrogate key `id uuid`, **UUIDv7** (sắp theo thời gian), do ứng dụng sinh. Giá trị mặc định của cột `sys.uuid_v7()` là phương án dự phòng phía SQL; trên PostgreSQL 18 được thay bằng hàm có sẵn `uuidv7()`.
2. `tenant_id uuid` (UUIDv7). Giá trị sentinel `00000000-0000-0000-0000-000000000000` đánh dấu các dòng global trong bảng global + tenant (ADR-0035).
3. Bảng thuộc sở hữu tenant dùng **PK `(tenant_id, id)`**. Bảng partition nối thêm partition key (`(tenant_id, id, occurred_at)`).
4. API và event chỉ để lộ `id`; giá trị này duy nhất toàn cục.
5. Khoá nghiệp vụ (`employee_no`, `code`, `contract_no`) là các ràng buộc `UNIQUE (tenant_id, …)` riêng và có thể chỉnh sửa.
6. `bigint` chỉ được dùng ở nơi mà thứ tự liên tục không khuyết là mục đích chính: `audit_event.chain_seq`, `legal_param_pack.pack_version`, `override_version`.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| `bigint` identity | Sequence là theo database, nên id bị trùng khi một tenant di chuyển giữa các cluster hoặc sang tier riêng. Có thể đoán được (enumeration), không thể sinh trước khi insert cho outbox và import idempotent, và cần xoay xở hi/lo khi Hibernate batching. |
| UUIDv4 | Insert ngẫu nhiên làm phân mảnh index B-tree trên các bảng khối lượng lớn (dữ liệu chấm công, dòng kết quả), gây write amplification đo được. |
| ULID / Snowflake (bigint) | ULID cần kiểu text hoặc kiểu tuỳ biến. Snowflake cần điều phối worker-id. UUIDv7 là chuẩn (RFC 9562) và có sẵn trong PG18. |
| PK chỉ `(id)` với `tenant_id` là cột thường | Không có FK composite, nên tham chiếu tới parent chéo tenant trở nên khả thi, và mất tính cục bộ của index đứng đầu bằng tenant. |

## Hệ quả

- \+ Id có thể gộp và di chuyển giữa các cluster, an toàn khi để lộ, và biết trước khi insert.
- \+ FK composite khiến tham chiếu chéo tenant là bất khả thi.
- − Khoá dài 32 byte trên bảng tenant so với 8 byte của bigint, tốn thêm khoảng 10–15 % dung lượng index trên các bảng lớn nhất. Chấp nhận.
- − UUIDv7 nhúng thời điểm tạo (mili giây). Đây không phải bí mật, nhưng không được dùng làm ngày nghiệp vụ.
