# ADR-0021: Mô hình role và đặc quyền database

- **Trạng thái:** Đã chấp thuận (2026-10-16, X-02 — với ngoại lệ dữ liệu tham chiếu chỉ đọc liệt kê bên dưới; **danh sách ngoại lệ đầy đủ, bao gồm các dòng override của tenant trong `core.code_value`/`core.exchange_rate` và các grant hạ tầng trên `saas.job` và `audit.audit_event`, được chấp thuận trong R2-11**). Grant cho relay trên `saas.enqueue_event_delivery()` (R2-01, event bus Phase 1): Đề xuất cùng với ADR-0004 rev. 2
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect, Security lead
- **Liên quan:** Architecture brief B3 (các module không bao giờ chạm vào bảng của nhau); SA overview §4 "không có SQL chéo schema"; BR-SAAS-033/036; NFR-SAAS-008; ADR-0017 (thông tin xác thực động OpenBao); ADR-0020, ADR-0022; [DESIGN.md](../../../database/postgresql/DESIGN.md) §2.3, §3.4; DDL `000_extensions_roles.sql`

## Bối cảnh

Modular monolith không được để module X đọc bảng của module Y. Operator không được có quyền truy cập mặc định vào dữ liệu tenant (BR-SAAS-033). Truy cập break-glass cần hai người phê duyệt và giới hạn 4 giờ (BR-SAAS-036). Migration cần sở hữu các object và chạy backfill chéo tenant. Outbox relay, audit sealer và reporting CDC cần quyền truy cập chéo tenant với phạm vi hẹp.

## Quyết định

| Role | Login | BYPASSRLS | Mục đích / grant |
|---|---|---|---|
| `yhrm_owner` | không | có | Sở hữu mọi object; chỉ migrator dùng qua `SET ROLE` |
| `yhrm_migrator` | có | không | Flyway trong CI/CD; thành viên của `yhrm_owner` |
| `yhrm_app` | không | **không** | Group role là đích của mọi RLS policy |
| `yhrm_app_{saas,core,hr,att,pay,rec}` | có | không | Mỗi module một role, thành viên của `yhrm_app`. DML **chỉ trên schema của chính nó**; `INSERT` trên `audit.audit_event`; `SELECT` trên các bảng tham chiếu **global** của Core; `INSERT` trên `saas.job` |
| `yhrm_platform` | có | không | Back-office của operator: các bảng nền tảng trong `saas`, **không có grant trên schema của module** |
| `yhrm_relay` | có | không | Outbox polling relay: `SELECT`, `UPDATE (published_at)` trên mọi `outbox_event` qua policy `USING (true)`; Phase 1: `EXECUTE` trên hàm SECURITY DEFINER `saas.enqueue_event_delivery()` (chèn các delivery job chỉ chứa con trỏ, ADR-0004 rev. 2) |
| `yhrm_cdc` | có | không | `REPLICATION`; chỉ dùng cho luồng cấp dữ liệu reporting store (Phase 1 dùng native logical replication với column list, tuỳ chọn Phase 2 là Debezium; ADR-0012 Amendment 1) |
| `yhrm_audit_sealer` | có | không | `UPDATE` chỉ trên các cột seal của audit |
| `yhrm_reporting_ro` | có | không | Đọc có ràng buộc RLS trên masking view cho báo cáo theo quy định thời gian thực |
| `yhrm_ops_ro` | có | không | `pg_monitor`; không có dữ liệu bảng |
| `yhrm_breakglass` | không | có | Cấp cho một DBA được chỉ định trong ≤ 4 h khi có phê duyệt hai người; pgaudit ghi log mọi câu lệnh |

- Mật khẩu không bao giờ nằm trong DDL. Login role nhận thông tin xác thực động từ OpenBao (ADR-0017).
- Mỗi module dùng Hikari pool và login role riêng, nên DB ép buộc ranh giới module ngay cả bên trong một JVM.
- `PUBLIC` mất quyền `CREATE` trên `public`; các procedure dùng cho migration không thể được `PUBLIC` thực thi.
- Công việc chéo tenant mà ứng dụng cần (nhận job) đi qua **hàm `SECURITY DEFINER`** do `yhrm_owner` sở hữu với `search_path` cố định (ví dụ `saas.claim_jobs`), không qua các policy rộng.

### Danh sách ngoại lệ chéo schema (X-02, chấp thuận 2026-10-16; danh sách được xác nhận trong R2-11)

App role của một module **không có grant trên schema của module khác**, ngoại trừ chính xác các ngoại lệ sau. Bất cứ thứ gì không có trong danh sách đều làm build thất bại.

| Schema.object | Đặc quyền | Cấp cho | Lý do |
|---|---|---|---|
| `core.country`, `core.currency`, `core.admin_unit`, `core.admin_unit_mapping`, `core.bank`, `core.bank_branch`, `core.code_list` | `SELECT` | `yhrm_app` (mọi module) | Dữ liệu tham chiếu global thuần tuý (X-02), bất biến giữa các bản release nền tảng |
| `core.code_value`, `core.v_code_value_effective`, `core.exchange_rate` | `SELECT` | `yhrm_app` | Dòng global + override tham chiếu riêng của tenant (RLS: dòng của mình + dòng sentinel); dữ liệu tham chiếu chỉ đọc (X-02) |
| `saas.tenant` | `SELECT` | `yhrm_app` | Chỉ dòng của chính tenant (policy `tenant_self_read`; locale, múi giờ, trạng thái) — tham chiếu global của SaaS (X-02) |
| `saas.job` | `SELECT`, `INSERT` | `yhrm_app` | Hạ tầng nền tảng, không phải đọc dữ liệu: enqueue job trong transaction (ADR-0011) |
| `audit.audit_event` | `INSERT` | `yhrm_app` | Hạ tầng nền tảng: audit chỉ-ghi-thêm trong transaction nghiệp vụ (ADR-0027) |

Không có trong danh sách (do đó bị cấm): mọi `UPDATE`/`DELETE` chéo schema, mọi thao tác đọc dữ liệu tenant của module khác, số tiền lương hay bảng lương, và các bảng tham số pháp lý (`saas.legal_param_*` chỉ được các module khác đọc qua API của Legal Parameter Service).

**Lớp bảo vệ (cả hai đều chặn release):**
1. **Test DB:** `SELECT count(*) FROM sys.cross_schema_grant_violations()` phải trả về 0 sau migration (hàm trong `002_core.sql`; đọc `pg_class.relacl` và so sánh với allow-list ở trên).
2. **Rule ArchUnit / jOOQ:** SQL hoặc class jOOQ sinh ra của module X chỉ được tham chiếu schema Y đối với các object ở trên; allow-list của rule được sinh từ cùng danh sách này.

Thêm một mục cần sửa đổi ADR này và ADR-0035.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Một application role cho toàn bộ monolith | Ranh giới module chỉ còn là quy ước, và một lỗi SQL injection duy nhất sẽ làm lộ mọi schema. |
| **Schema** theo module nhưng dùng chung role | Giống như trên. |
| Cấp cho operator quyền đọc DB với RLS | Bỏ qua phạm vi support-grant và che dữ liệu trong ứng dụng (BR-SAAS-034); operator làm việc qua ứng dụng. |
| `BYPASSRLS` cho relay/sealer | Đặc quyền quá mức; các policy hẹp đạt được cùng kết quả. |

## Hệ quả

- \+ Ranh giới module và least privilege được ép buộc bằng grant và kiểm thử được trong CI.
- − Mỗi instance ứng dụng có sáu connection pool. PgBouncer giữ số server connection trong giới hạn (`default_pool_size` theo cặp db/user).
- − Lời gọi chéo module trong cùng process không bao giờ dùng chung một DB transaction, đây là chủ đích (nhất quán qua outbox và saga, ADR-0019).
- − Ngoại lệ: role của module đọc trực tiếp các bảng tham chiếu global của Core/SaaS. Được chấp thuận là X-02 (2026-10-16) với điều kiện danh sách ngoại lệ ở trên được nêu tường minh và được bảo vệ bởi test DB và rule ArchUnit.
