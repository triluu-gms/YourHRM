# YourHRM — Kiến trúc dữ liệu (v0.3)

| Mục | Giá trị |
|---|---|
| Phiên bản | **0.3 — 2026-10-16** (đã áp dụng các quyết định vòng 2 R2-01, R2-11, R2-12, R2-15, R2-16, R2-17, R2-18 trong [nhật ký quyết định](../../docs/00-project-management/decision-log.md) §7–§7.2; v0.2 áp dụng vòng 1; v0.1 là bản nháp để review) |
| Trạng thái | Baseline cho SRS và sprint 1. Các quyết định được ghi nhận thành ADR-0020 … ADR-0037; trạng thái từng ADR ở §16 |
| Tác giả | Data Architect |
| Đầu vào | [Architecture brief](../../docs/02-architecture/architecture-brief.md), [nhật ký quyết định](../../docs/00-project-management/decision-log.md), [BRD](../../docs/01-requirements/brd/BRD.md), [gap analysis](../../docs/01-requirements/brd/gap-analysis.md) §5/§6/§7/§9, BRD của các module §6–§7 |
| Mục tiêu | PostgreSQL 17 trên CloudNativePG (SA ADR-0002/0017); DDL giữ tương thích với 16+. Java 25 / Spring Boot 4 (OD-3; phương án dự phòng Boot 3.5 / Java 21 sẽ quyết định sau SP-1), Hibernate + jOOQ, modular monolith |
| Đồng bộ với | SA ADR-0002 (stack), 0003 (tenancy runtime, `saas.tenant_placement`, baseline capabilities), 0004 rev. 2 (outbox + polling relay; bus dựa trên DB ở Phase 1, Kafka ở Phase 2), 0006 (scope predicates, `report_scope`), 0009 (snapshot-first runs), 0010 (Legal Parameter Service), 0011 (job queue), 0012 (reporting), 0017 (CNPG, OpenBao, DR), 0019 (close hold, đã chấp nhận X-03) |
| DDL | [`ddl/`](ddl/) — `000_extensions_roles.sql`, `001_saas.sql`, `002_core.sql`, `003_hr.sql`. **v0.1** đã được smoke test trên PostgreSQL 16.4 (PGlite; pgcrypto/unaccent được stub): mọi file đều load được, đọc và ghi chéo tenant bị chặn, không có context = không có dòng nào, exclusion constraint, bi-temporal guard và phát hiện giả mạo audit hoạt động đúng thiết kế. **Các thay đổi của v0.2 CHƯA được test lại** (vòng này không có PostgreSQL/Python; chỉ đọc soát kỹ): `core.import_job`/`import_row`/`import_reconciliation` và `sys.cross_schema_grant_violations()` trong `002`, `hr.v_salary_record_masked` và `source = 'OPENING_LOAD'` trong `003`, audit actor `IMPORT`, comment trong `000`/`001`. **Các thay đổi của v0.3 cũng CHƯA được test** (chưa cài PostgreSQL và Python; chỉ đọc soát): `saas.job.ordering_key`, ordered lanes của `saas.claim_jobs()`, `saas.enqueue_event_delivery()`, entitlement source `BASELINE` và `saas.baseline_capability` trong `001`; `hr.primary_employment` thay cho `hr.employment.is_primary`, `employment_person_le_ex` trong `003`; comment về role trong `000`. Lưu ý: các helper tenancy nằm trong `000` (schema `sys`), không phải `002`, vì `001_saas` cần đến chúng. |

**Những thay đổi trong v0.3 (vòng 2, nhật ký quyết định §7–§7.2).** R2-01: Phase 1 **không có tích hợp ngoài trực tiếp (live)** — không có device connector hay tiếp nhận dữ liệu chấm công trực tiếp (`att.raw_punch` chỉ nhận lượt chấm công từ file xuất từ máy chấm công được import, §7.4), không có Kafka ở Phase 1 (outbox relay đẩy vào bus dựa trên DB trên `saas.job`, §9.1; SA ADR-0004 rev. 2), và reporting store được cấp dữ liệu bằng native logical replication (§11). R2-11: danh sách ngoại lệ X-02, bao gồm các dòng override của tenant trong `core.code_value`/`core.exchange_rate` và hai grant hạ tầng, được **chấp nhận** (§2.3). R2-12: các employment ở trạng thái `SUSPENDED` **không tính phí** (§11). R2-15: số tiền lương dạng plaintext có thể đọc được bằng DB credential của `hr`/`pay` — chấp nhận kèm biện pháp giảm thiểu (§15.2). R2-16: làm việc đồng thời tại nhiều pháp nhân — `hr.primary_employment` với quy tắc "mỗi người tối đa một employment chính cho mỗi mục đích trong mỗi khoảng thời gian" (§7.3). R2-17: mật khẩu PDF phiếu lương (`pay.payslip_delivery_preference`, P3, §7.5, §9.3). R2-18: các capability nền tảng luôn bật được ghi thành entitlement `BASELINE` (§7.1).

**Những thay đổi trong v0.2.** X-01: số tiền lương giữ dạng plaintext (P2) kèm biện pháp kiểm soát bù trừ; định danh, tài khoản ngân hàng và dữ liệu nhạy cảm đặc biệt vẫn được mã hóa (§9.3, ADR-0026). X-02: ngoại lệ đọc dữ liệu tham chiếu chỉ-đọc được liệt kê tường minh và được bảo vệ bằng một DB test và một rule ArchUnit (§2.3, ADR-0021). D-09: **không migrate từ Oracle**; §13 nay là **import số dư đầu kỳ** tổng quát (ADR-0037, thay thế ADR-0033) và các schema `stg_*`/`mig` bị loại bỏ. D-10: định nghĩa nhân viên tính phí (§11). X-05: tốc độ chấm công (§10.1). OD-9: không chấm công qua mobile ở Phase 1 (§7.4). D-01, D-03, Flyway Community: đã chấp nhận (§15).

Các mục đánh dấu **[spike]** cần chứng minh kỹ thuật trước khi quyết định chính thức. Các mục đánh dấu **[verify-legal]** theo quy ước của BRD.
Solution Architect (SA) chịu trách nhiệm container view, công nghệ bus, outbox relay, identity và payroll engine (ADR-0001 … 0019). Tài liệu này chịu trách nhiệm các bảng nằm bên dưới chúng.

---

## 1. Nguyên tắc

1. **Mỗi dữ liệu có một chủ sở hữu duy nhất** (gap-analysis §5). Một bảng nằm trong schema của module sở hữu dữ liệu đó. Các module khác giữ một *projection được cấp dữ liệu qua event* hoặc gọi API của module sở hữu. Chúng không bao giờ đọc bảng của module sở hữu.
2. **Cô lập được thực thi bởi database.** Mọi dòng thuộc tenant đều mang `tenant_id`. Row-level security (RLS) của PostgreSQL fail closed khi chưa thiết lập tenant. Guard ở tầng ứng dụng là lớp thứ hai, không phải lớp duy nhất.
3. **Thời gian là cột hạng nhất.** Hiệu lực nghiệp vụ dùng `valid_from`/`valid_to` với exclusion constraint. Lương, phân công và tham số pháp lý còn lưu cả transaction time, để hệ thống trả lời được câu hỏi "tại thời điểm X, theo hiểu biết vào ngày Y".
4. **Phép tính có thể tái lập.** Kết quả tính lương và chấm công là bất biến sau khi được duyệt. Chúng lưu các đầu vào, phiên bản legal pack, phiên bản cấu hình và phiên bản code đã sử dụng, và có thể tái lập trong 10 năm.
5. **Định danh, tài khoản ngân hàng và dữ liệu nhạy cảm đặc biệt được ứng dụng mã hóa bằng khóa riêng cho từng tenant**, nên hủy khóa là xóa dữ liệu (crypto-shredding), kể cả các bản sao trong replica, reporting store và file xuất. **Số tiền lương không được mã hóa ở mức cột** (X-01); chúng được bảo vệ bằng RLS, role theo module, masking view và audit truy cập.
6. **Schema tiến hóa không downtime.** Thay đổi là các migration expand/contract chỉ tiến (forward-only), và mọi database (shared pool hay dedicated) có cùng một schema.

---

## 2. Bản đồ schema và quyền sở hữu

### 2.1 Các schema

| Schema | Module sở hữu (code) | Nội dung | RLS |
|---|---|---|---|
| `sys` | Platform shared kernel | **Không có bảng.** Function, domain và trigger function: `uuid_v7()`, getter cho tenant context, helper RLS, helper partition, guard bất biến, `f_unaccent()` | n/a |
| `saas` | SaaS platform | Tenant, gói dịch vụ, subscription, entitlement, flag, usage, hóa đơn, support grant, identity (credential, MFA, cấu hình IdP, API client), **legal parameter pack**, registry về consent/DSR/lưu trữ, registry khóa | Hỗn hợp: dòng platform (không RLS, chỉ role platform) và dòng tenant `(T)` có RLS |
| `core` | Core | Master data toàn cục (quốc gia, tiền tệ, đơn vị hành chính + mapping, ngân hàng, danh mục mã), pháp nhân, địa điểm, cây tổ chức, kiến trúc công việc, danh mục ngạch bậc, vị trí, trung tâm chi phí, user/role/data scope, workflow, thông báo, tài liệu, thiết lập, custom field, job import/export, tỷ giá | Có (bảng toàn cục chỉ-đọc với ứng dụng và không có `tenant_id`) |
| `audit` | Core (component `audit`) | `audit_event` (partitioned, hash-chained), chain head và anchor | Có; grant chỉ cho phép append |
| `hr` | People | Person, employment, assignment, hồ sơ lương, hợp đồng, quyết định, gia đình/người phụ thuộc, tài khoản ngân hàng, nghỉ việc, metadata tài liệu, đánh giá/đào tạo (P2) | Có |
| `att` | Time & Attendance | Lịch ngày lễ, ca, mẫu ca, gán lịch làm việc, roster, thiết bị, lượt chấm công, bảng công ngày, OT, nghỉ phép, công tác, kỳ chấm công và các phiên bản kết quả | Có |
| `pay` | Payroll | Nhóm lương, lịch và kỳ lương, danh mục thành phần lương, công thức, cấu trúc lương, thang/khung lương, projection hồ sơ lương, đầu vào, lần chạy, snapshot, kết quả, BHXH/thuế TNCN, phiếu lương, file ngân hàng, GL, khoản vay, quyết toán khi nghỉ việc | Có |
| `rec` | Recruitment (P2) | Kế hoạch định biên, yêu cầu tuyển dụng, vị trí tuyển, tin đăng, ứng viên, hồ sơ ứng tuyển, phỏng vấn, offer, bàn giao | Có |

Các schema `stg_*`/`mig` của v0.1 (database migrate từ Oracle) đã **bị loại bỏ** (D-09: không migrate từ Oracle). Số dư đầu kỳ khi go-live được đưa vào vùng staging trong các bảng import tổng quát của Core `core.import_job`/`import_row`/`import_reconciliation` (§13, ADR-0037), trong chính cluster của tenant và chịu RLS.

Mỗi schema của module còn chứa `outbox_event` và `inbox_message` riêng (§9.1) và bảng lịch sử Flyway riêng (§12).
Reporting store là một **database riêng biệt** (§11).

**Vì sao `audit` là schema riêng** dù Core sở hữu nó: nó cần grant riêng (chỉ INSERT cho mọi role module, UPDATE chỉ cho sealer), vòng đời partition/lưu trữ riêng, và mọi module đều ghi vào nó bên trong transaction nghiệp vụ của mình (NFR-CORE-008). Đây là schema **duy nhất** mà mọi role module được phép ghi.

**Vì sao `sys` không có bảng:** function và domain là code, không phải dữ liệu, và mọi schema đều cần chúng. Không có bảng nghĩa là không ai "sở hữu dữ liệu" ở đó.

### 2.2 Quy tắc giữa các schema (ADR-0022)

| Quy tắc | Quyết định |
|---|---|
| Foreign key **bên trong** một schema | Bắt buộc, và là khóa ghép: `(tenant_id, parent_id) → parent(tenant_id, id)`. Do đó một dòng không bao giờ tham chiếu được đến dòng của tenant khác. |
| Foreign key **giữa** các schema | **Không có, không ngoại lệ** — không tới `core`, kể cả tới `saas.tenant`. |
| Tham chiếu đến entity của module khác | Lưu ID của nó dưới dạng *soft reference* (`position_id uuid`, comment cột `-> core.position (soft)`). Ứng dụng kiểm tra nó qua API của module sở hữu tại thời điểm ghi. Tính toàn vẹn cuối cùng (eventual integrity) đến từ event của module sở hữu cộng với job kiểm tra tham chiếu mồ côi hằng đêm. |
| Tham chiếu master data **toàn cục** | Dùng **mã nghiệp vụ ổn định**, không dùng surrogate (`country_code char(2)`, `currency_code char(3)`, `admin_unit_code text`, `bank_code text`). Mã giữ nguyên qua các lần sao chép môi trường, DB dedicated, chuyển tenant và import. |
| Đọc dữ liệu hàng loạt của module khác | Qua một **projection được cấp dữ liệu qua event** trong schema của bên tiêu thụ, đặt tên `ext_<owner>_<entity>` (ví dụ `pay.ext_hr_salary`, `att.ext_core_location`). Mỗi dòng lưu `source_event_id` và `source_version`. Projection có thể dựng lại từ API của module sở hữu (job "re-sync"). |
| `tenant_id` | Không phải foreign key. Tính hợp lệ của nó được đảm bảo bởi RLS context, provisioning và job purge tenant. Một FK trỏ tới một dòng tenant "nóng" duy nhất còn khiến mọi lệnh insert phải lấy lock `FOR KEY SHARE` (áp lực multixact). |

**Lý do.** (a) PM baseline 3: các module không bao giờ chạm vào bảng của nhau. FK giữa các schema sẽ ràng buộc migration, thứ tự deploy và thứ tự xóa của chúng với nhau. (b) Khi đó role DB theo module (§3.4) có thể thực thi ranh giới bằng grant. (c) Payroll worker và tiếp nhận chấm công sau này có thể chuyển sang database riêng mà không phải viết lại schema. (d) Cái giá: toàn vẹn giữa các module trở thành eventual. Điều này được giảm thiểu bằng projection mang phiên bản, báo cáo đối soát và cơ chế bắt tay phiên bản khi khóa kỳ (gap §7).

### 2.3 Ma trận truy cập module → schema (DB grant)

| DB role | saas | core | audit | hr | att | pay | rec |
|---|---|---|---|---|---|---|---|
| `yhrm_app_saas` | RW | R (ref)¹ | INSERT | – | – | – | – |
| `yhrm_app_core` | R (ref)¹ + job² | RW | INSERT, SELECT | – | – | – | – |
| `yhrm_app_hr` | R (ref)¹ + job² | R (ref)¹ | INSERT | RW | – | – | – |
| `yhrm_app_att` | R (ref)¹ + job² | R (ref)¹ | INSERT | – | RW | – | – |
| `yhrm_app_pay` | R (ref)¹ + job² | R (ref)¹ | INSERT | – | – | RW | – |
| `yhrm_app_rec` | R (ref)¹ + job² | R (ref)¹ | INSERT | – | – | – | RW |

¹ **Ngoại lệ chỉ-đọc X-02 (chấp nhận 2026-10-16; toàn bộ danh sách dưới đây, bao gồm các dòng override của tenant trong `core.code_value`/`core.exchange_rate` và hai grant hạ tầng ở chú thích ², đã được chấp nhận là thuộc phạm vi X-02 tại R2-11)** — là những lần đọc giữa các schema duy nhất, tất cả đều là `SELECT`, được cấp cho group role `yhrm_app`:

| Đối tượng | Nội dung | Vì sao an toàn |
|---|---|---|
| `core.country`, `core.currency`, `core.admin_unit`, `core.admin_unit_mapping`, `core.bank`, `core.bank_branch`, `core.code_list` | Dữ liệu tham chiếu toàn cục thuần túy | Bất biến giữa các bản phát hành platform (`core.MasterDataReleased`), không có dữ liệu tenant |
| `core.code_value`, `core.v_code_value_effective`, `core.exchange_rate` | Dòng toàn cục + các override tham chiếu của chính tenant | RLS chỉ trả về dòng của chính tenant + dòng sentinel; dữ liệu tham chiếu, chỉ đọc |
| `saas.tenant` | Dòng của chính tenant (locale, múi giờ, trạng thái) | Policy `tenant_self_read` |

² Hạ tầng platform, không phải đọc dữ liệu (chấp nhận tại R2-11): `SELECT, INSERT` trên `saas.job` (enqueue trong transaction, ADR-0011) và `INSERT` trên `audit.audit_event` (ADR-0027). Các job phân phối event-bus ở Phase 1 trong `saas.job` chỉ mang con trỏ tới dòng outbox, không bao giờ mang payload nghiệp vụ (§9.1), nên grant này không làm lộ số tiền giữa các module.

Không bao gồm: các bảng tham số pháp lý (module khác dùng API của Legal Parameter Service; chỉ `yhrm_app_saas` đọc chúng), dữ liệu tenant của bất kỳ module nào khác, bất kỳ bảng số tiền nào. **Guard (chặn release):** (a) DB test `SELECT count(*) FROM sys.cross_schema_grant_violations()` = 0 (function trong `002_core.sql`, đọc `pg_class.relacl` và so sánh với allow-list này); (b) rule ArchUnit/jOOQ chỉ cho phép module X tham chiếu schema Y đối với các đối tượng này. Thêm mục mới cần sửa đổi ADR-0021.

---

## 3. Multi-tenancy ở tầng dữ liệu (ADR-0020, ADR-0021, ADR-0035, ADR-0036)

### 3.1 Mô hình

* **Shared pool (mặc định):** một PostgreSQL cluster cho mỗi region pool. Mọi tenant dùng chung các bảng, mọi bảng thuộc tenant có `tenant_id uuid NOT NULL`, và RLS được bật và **forced**.
* **Dedicated tier (Enterprise):** một database riêng (và thường là một cluster riêng) với **DDL giống hệt từng byte**, bao gồm `tenant_id` và RLS. Định tuyến tenant lấy từ `saas.tenant_placement` (SA ADR-0003). Giữ RLS trong DB chỉ có một tenant tốn rất ít, giữ được một luồng code duy nhất và là phòng thủ nhiều lớp (ADR-0036).
* **Platform cluster và placement class** (đồng bộ với SA overview: "schema `saas` nằm trong một platform cluster"). Mọi database có **cùng DDL**, gồm tất cả các schema. Điểm khác nhau là nơi mỗi bảng được coi là *nguồn chuẩn (authoritative)*:

  | Class | Nguồn chuẩn tại | Bản sao | Bảng |
  |---|---|---|---|
  | PLATFORM | platform cluster | không có | `saas.plan*`, `subscription*`, `invoice*`, `support_grant`, `tenant_key`, `subject_key`, `consent_record`, `data_subject_request`, `archive_manifest`, `platform_audit_event`, soạn thảo legal pack |
  | REPLICATED | platform cluster | logical replication chỉ-đọc tới mọi pool/dedicated cluster (`pub_platform_ref`) | `saas.tenant`, `tenant_placement`, `entitlement`, `legal_param_*` (chỉ các pack đã publish), `retention_policy`, master data toàn cục của Core (`core.country`, `admin_unit`, `bank`, các dòng `code_value` toàn cục) |
  | LOCAL | từng cluster | không có | các schema module `core`/`audit`/`hr`/`att`/`pay`/`rec`, `saas.job` (queue; enqueue trong transaction nghiệp vụ, ADR-0011), mọi `outbox_event`/`inbox_message` |

  Giữ keyring (`subject_key`) trong platform cluster còn có nghĩa là bản backup của pool cluster không bao giờ chứa DEK đã wrap. **[spike: logical replication các bảng REPLICATED so với release job đẩy dữ liệu; xử lý xung đột cho các dòng `code_value` toàn cục]**

### 3.2 Tenant context cho mỗi transaction

```sql
-- issued by the application as the FIRST statement of every transaction
SELECT set_config('app.tenant_id', :tenantId, true),   -- true = transaction-local (SET LOCAL)
       set_config('app.user_id',   :userId,   true),
       set_config('app.request_id',:requestId,true),
       set_config('app.purpose',   :purpose,  true),    -- e.g. PAYROLL_RUN, ESS, SUPPORT_GRANT
       set_config('app.support_grant_id', :grantId, true),  -- operator sessions only (SA ADR-0003)
       set_config('app.mask_level', :maskLevel, true);      -- NONE | PII | PII_AND_PAY (masking views, §9.4)
```

* `sys.current_tenant_id()` trả về `NULLIF(current_setting('app.tenant_id', true), '')::uuid`. `NULLIF` là quan trọng: sau khi một `SET LOCAL` kết thúc, session trong pool vẫn giữ placeholder ở dạng `''`, không phải NULL.
* Policy so sánh `tenant_id = (SELECT sys.current_tenant_id())`. Scalar subquery trở thành một InitPlan được tính **một lần cho mỗi câu lệnh**, nên phép so sánh vẫn tận dụng được index.
* **Fail closed:** khi không có tenant, phép so sánh cho kết quả NULL, nên truy vấn không trả về dòng nào và mọi lệnh insert đều thất bại ở `WITH CHECK`.
* Spring: một `TenantAwareDataSource` / `TransactionSynchronization` chạy `set_config` ngay sau `BEGIN`. **Mọi** truy cập DB phải chạy bên trong `@Transactional` (kể cả read-only). Một rule ArchUnit cộng với kiểm tra lúc runtime (`sys.assert_tenant()` trong một hook kiểu `connectionInitSql` cho mỗi transaction) thực thi điều này. Context không bao giờ được thiết lập ở mức session.
* **Background job** (payroll worker, scheduler) thiết lập context theo từng tenant cho mỗi transaction. Job liên tenant lặp qua từng tenant. Chúng không bao giờ dùng role BYPASSRLS.

### 3.3 Connection pooling

* **PgBouncer ở transaction mode** đặt trước mỗi cluster. `SET LOCAL`/`set_config(..., true)` an toàn vì context mất đi cùng transaction.
* Bị cấm: `SET` ở mức session, session advisory lock (dùng `pg_advisory_xact_lock`), `LISTEN/NOTIFY` qua PgBouncer (relay dùng kết nối trực tiếp), và cursor `WITH HOLD`.
* Server-side prepared statement: PgBouncer ≥ 1.21 với `max_prepared_statements > 0` **[spike: pgJDBC + chế độ prepared-statement của PgBouncer 1.21+ dưới tải]**.
* Có một Hikari pool cho mỗi role module (sáu pool). Tổng số kết nối server bị giới hạn bởi `default_pool_size` của PgBouncer cho mỗi cặp (db, user). Payroll worker có PgBouncer pool riêng, nên một lần chạy lương không thể làm cạn tài nguyên của OLTP (NFR-SAAS-005).

### 3.4 Database role

| Role | Login | BYPASSRLS | Mục đích |
|---|---|---|---|
| `yhrm_owner` | không | **có** | Sở hữu mọi đối tượng. Chỉ được migrator dùng qua `SET ROLE`. BYPASSRLS cho phép backfill liên tenant trong migration. |
| `yhrm_migrator` | có | không (kế thừa qua `SET ROLE`) | Flyway. Thành viên của `yhrm_owner`. Chỉ CI/CD pipeline sử dụng. |
| `yhrm_app` | không | **không** | Group role mà mọi RLS policy nhắm tới. |
| `yhrm_app_{saas,core,hr,att,pay,rec}` | có | không | Mỗi module một login role, là thành viên của `yhrm_app`. Grant theo §2.3. |
| `yhrm_platform` | có | không | Back-office của operator. Có quyền DML trên các bảng platform trong `saas`, và **không** có grant nào trên các schema nghiệp vụ của tenant. Truy cập hỗ trợ của operator đi qua ứng dụng dưới một support grant (BR-SAAS-033) và được che dữ liệu tại đó. |
| `yhrm_relay` | có | không | Outbox polling relay (SA ADR-0004 rev. 2). Có thể `SELECT` và cập nhật `published_at` trên các bảng outbox (policy `TO yhrm_relay USING (true)`). Phase 1: `EXECUTE` trên `saas.enqueue_event_delivery()` (SECURITY DEFINER) để phân phối event thành các delivery job; Phase 2: publish lên Kafka thay vào đó. Không phải role module, nên nằm ngoài danh sách X-02. |
| `yhrm_cdc` | có | không | `REPLICATION`. **Chỉ dùng để cấp dữ liệu cho reporting store** (sửa đổi SA ADR-0012): Phase 1 dùng native logical replication với publication liệt kê cột (cột P3 không bao giờ được publish); Debezium trên standby là phương án cho Phase 2. |
| `yhrm_audit_sealer` | có | không | Niêm phong (seal) hash chain của audit (chỉ UPDATE các cột seal). |
| `yhrm_reporting_ro` | có | không | Role chỉ-đọc cho số ít báo cáo pháp định thời gian thực trên OLTP (ngoại lệ BR-SAAS-047), RLS vẫn áp dụng. |
| `yhrm_ops_ro` | có | không | `pg_monitor`, `pg_read_all_stats`. Không có dữ liệu bảng. |
| `yhrm_breakglass` | không | **có** | Cấp tạm thời cho một DBA có danh tính khi được hai người phê duyệt (BR-SAAS-036), pgaudit ghi log mọi câu lệnh. Tự động thu hồi sau ≤ 4 h. |

### 3.5 Phân loại bảng và mẫu RLS policy

| Class | `tenant_id` | Policy (áp dụng bởi `sys.enable_tenant_rls()`) | Ví dụ |
|---|---|---|---|
| **Thuộc tenant** | `NOT NULL` | `FOR ALL TO yhrm_app USING (tenant_id = cur) WITH CHECK (tenant_id = cur)` | gần như mọi bảng |
| **Toàn cục + tenant override được** | `NOT NULL`; dòng toàn cục dùng sentinel `sys.global_tenant_id()` = `00000000-0000-0000-0000-000000000000` | `SELECT` với `tenant_id IN (cur, global)`; chỉ ghi khi `tenant_id = cur` | `core.code_value`, bản sao template ngày lễ trong `att`, template `pay.salary_component` |
| **Toàn cục thuần túy** | không có | Không RLS. `SELECT` cấp cho các app role đối với các bảng Core (danh sách X-02, §2.3); `saas.legal_param_*` chỉ cho `yhrm_app_saas` (các module khác dùng LPS API); chỉ `yhrm_owner`/release job được ghi | `core.country`, `core.currency`, `core.admin_unit`, `core.bank`, `saas.legal_param_*` (các pack đã publish) |
| **Chỉ platform** | không có hoặc tùy chọn | Không grant cho app role của tenant | `saas.plan`, `saas.feature_flag`, `saas.invoice` (UI tenant đọc qua SaaS API) |

Việc phân giải override cho class toàn cục + tenant dùng `DISTINCT ON (list_code, value_code) … ORDER BY (tenant_id = sys.global_tenant_id())`, nên dòng của tenant che dòng toàn cục có cùng mã. Tenant có thể thêm hoặc vô hiệu hóa giá trị nhưng không thể sửa dòng toàn cục.

### 3.6 Index cho tenancy

* PK của mọi bảng thuộc tenant là `(tenant_id, id)`. Mọi index phụ **bắt đầu bằng `tenant_id`**, trừ khi đó là index kỹ thuật duy nhất toàn cục (ví dụ `outbox_event(published_at)`).
* Tính duy nhất của khóa nghiệp vụ là theo tenant: `UNIQUE (tenant_id, code)`, hoặc `(tenant_id, legal_entity_id, code)` khi cần.
* Các bảng lớn được partition theo khoảng thời gian, không theo hash tenant (§10). Tenant đủ lớn để cần partition riêng sẽ chuyển sang dedicated tier.

### 3.7 Xuất dữ liệu, offboarding và xóa tenant

| Bước | Cơ chế |
|---|---|
| Xuất dữ liệu (NFR-SAAS-019) | Một export job chạy theo từng module dưới app role của module với tenant context đã thiết lập: `COPY (SELECT …) TO STDOUT` dưới dạng NDJSON có phiên bản cho mỗi entity, cộng với tài liệu từ object storage. Các trường mã hóa được ứng dụng giải mã vào file xuất, bản thân file xuất được mã hóa bằng khóa hoặc mật khẩu do khách hàng cung cấp. Mục tiêu: 10k nhân viên ≤ 6 h. |
| Chấm dứt | `saas.tenant.state = TERMINATING`. Đăng nhập bị chặn và dữ liệu ở trạng thái chỉ-đọc trong 90 ngày (SaaS Q10). |
| Purge | (1) **Crypto-shred:** lên lịch cho KMS xóa KEK của tenant (§9.3). Từ thời điểm đó mọi cột mã hóa trong primary, replica, reporting store, file xuất và **backup** đều không đọc được. (2) Xóa dòng theo lô cho từng schema (`DELETE … WHERE tenant_id = $1` theo khối 10k dựa trên khoảng PK, có điều tiết). (3) Xóa các dòng của tenant trong reporting store và prefix object storage. (4) Ghi chứng nhận purge vào platform audit, kèm số dòng của từng bảng. Dữ liệu plaintext trong backup hết hạn theo thời hạn lưu backup (35 ngày), điều này được thông báo bằng văn bản cho DPO. |
| Dedicated tier | Drop database sau khi xuất dữ liệu và hủy KEK. |
| Khôi phục theo tenant (NFR-SAAS-006) | Khôi phục PITR của cluster vào một instance tạm, sau đó sao chép các dòng của tenant vào một sandbox tenant qua định dạng export/import (không bao giờ `pg_restore` vào shared pool). |

---

## 4. Khóa và quy ước (ADR-0023, ADR-0034)

### 4.1 Khóa chính

* **Surrogate `id uuid`, UUIDv7**, do ứng dụng sinh (có thứ tự theo thời gian, nên insert vào B-tree luôn dồn về bên phải và độ phình index gần với bigint). Nó duy nhất toàn cục, an toàn để lộ ra trong API và event, có thể gộp giữa DB shared và dedicated, và biết trước khi insert (payload outbox, import idempotent). Hàm dự phòng trong SQL `sys.uuid_v7()` là giá trị mặc định của cột. Trên PostgreSQL 18 có thể thay bằng hàm có sẵn `uuidv7()`.
* `tenant_id uuid` (cũng là UUIDv7).
* **PK `(tenant_id, id)`** trên các bảng thuộc tenant. Điều này cho phép dùng FK ghép và giữ mọi entry index của một tenant nằm liền nhau. API vẫn định danh dòng chỉ bằng `id` (duy nhất toàn cục), và khi tra cứu thì bổ sung `tenant_id` từ context.
* `bigint` chỉ được dùng ở nơi thứ tự không có khoảng trống là mục đích chính: `audit.audit_event.chain_seq` và `saas.legal_param_pack.pack_version`. Lượt chấm công và dòng kết quả cũng dùng UUIDv7, để một quy tắc áp dụng ở mọi nơi.
* **Khóa nghiệp vụ** (`employee_no`, `code`, `number` của hợp đồng) là các unique constraint riêng và có thể sửa theo quy trình quản trị.

### 4.2 Đặt tên

| Đối tượng | Quy ước | Ví dụ |
|---|---|---|
| Schema, bảng, cột | `snake_case`, danh từ số ít, ASCII, tiếng Anh | `hr.salary_record` |
| Cột PK / FK | `id` / `<entity>_id` | `employment_id` |
| Soft reference giữa các schema | `<entity>_id` với comment `-> core.position (soft)` | `position_id` |
| Tham chiếu mã toàn cục | `<thing>_code` | `country_code`, `bank_code` |
| Boolean | `is_` / `has_` | `is_retro` |
| Thời điểm / ngày | `_at` (`timestamptz`) / `_date`, `valid_from`, `valid_to` (`date`) | `approved_at` |
| Tiền / số lượng / tỷ lệ | `_amount` + `currency_code`, `_qty`, `_rate` | `base_salary_amount` |
| Mã hóa / blind index / gợi ý hiển thị | `_enc` (`bytea`), `_bidx` (`bytea`), `_last4` (`text`) | `national_id_enc` |
| Constraint | `<table>_pk`, `<table>_<col>_fk`, `_uq`, `_ck`, `_ex` (exclusion) | `assignment_period_ex` |
| Index | `<table>_<cols>_ix` | `employment_person_ix` |
| Projection của module khác | `ext_<owner>_<entity>` | `pay.ext_hr_salary` |
| View | `v_<name>`; masking view `v_<table>_masked` | `hr.v_person_masked` |

### 4.3 Cột chuẩn

| Cột | Kiểu | Quy tắc |
|---|---|---|
| `tenant_id` | `uuid NOT NULL` | Bất biến. Trigger chặn mọi thay đổi. |
| `id` | `uuid NOT NULL DEFAULT sys.uuid_v7()` | |
| `created_at`, `created_by` | `timestamptz NOT NULL DEFAULT now()`, `uuid` | `created_by` = `core.user_account.id`, id của một system principal, hoặc id của API client (soft reference) |
| `updated_at`, `updated_by` | như trên | Trigger `sys.tg_touch()` thiết lập `updated_at`. Ứng dụng thiết lập `updated_by`. |
| `version` | `integer NOT NULL DEFAULT 0` | Optimistic lock (JPA `@Version`). Không dùng trên bảng append-only. |

Bảng append-only (lượt chấm công, outbox, audit, dòng kết quả, snapshot) chỉ mang `created_at`/`created_by`.

### 4.4 Chính sách xóa

* **Không dùng soft delete chung chung** (không dùng cờ `deleted_at`). Mỗi vòng đời được mô hình hóa tường minh: `status` (`DRAFT/ACTIVE/INACTIVE/CANCELLED`), ngày hiệu lực (`valid_to`), hoặc thay thế (supersession, ở các bảng bi-temporal).
* Hard delete chỉ được phép với các dòng `DRAFT` chưa từng có hiệu lực và với các dòng kỹ thuật (outbox, inbox, session hết hạn).
* Bản ghi có thời hạn lưu trữ theo luật (hợp đồng, bảng lương, BHXH/thuế TNCN, chấm công) chỉ được xóa bởi **retention scheduler** (do SaaS sở hữu): ẩn danh hóa, crypto-shred, rồi xóa, có bằng chứng trong audit log.

### 4.5 Kiểu liệt kê (enumeration)

| Loại | Lưu trữ | Lý do |
|---|---|---|
| State machine của hệ thống và tập kỹ thuật đóng (`status`, `run_type`, `assignment_type`) | `text` + `CHECK (col IN (...))` | Có thể thêm giá trị bằng `NOT VALID` + `VALIDATE` mà không cần `ALTER TYPE` nặng lock. Dễ đọc trong CDC và báo cáo. **Không dùng kiểu `ENUM` của PostgreSQL.** |
| Phân loại theo luật định (loại hợp đồng, lý do giảm BHXH, tình trạng cư trú thuế TNCN, quan hệ, dân tộc) | Danh mục mã toàn cục `core.code_value` (toàn cục thuần túy, có phiên bản) | Thuộc quy trình phát hành của platform. Mã được tham chiếu dạng text. |
| Danh sách tenant cấu hình được (lý do nghỉ phép, loại nhân viên, lý do nghỉ việc) | `core.code_value` toàn cục + tenant override | Tenant mở rộng mà không cần thay đổi schema. |
| Cấu hình module có hành vi (loại nghỉ phép, thành phần lương, mã chấm công) | Một bảng riêng trong module sở hữu | Nó có thuộc tính và quy tắc. |

### 4.6 Tiền, số lượng, làm tròn

* Các domain trong `sys`: `sys.money_amount = numeric(19,4)`, `sys.rate = numeric(12,8)` (8% BHXH vẫn chính xác, và hỗ trợ tỷ giá), `sys.quantity = numeric(12,4)` (giờ, ngày, nửa ngày).
* Mọi cột tiền có một cột đi kèm `currency_code char(3)` (mặc định `VND`), hoặc dòng kế thừa loại tiền từ header của nó (ví dụ `result_line` từ `payroll_run`).
* **VND không có đơn vị lẻ.** Các giá trị *phải trả hoặc phải kê khai* (lương thực nhận, thuế TNCN khấu trừ, đóng BHXH, dòng ngân hàng, tờ khai) được lưu **đã làm tròn tới 1 VND** trong `numeric(19,4)` (giữ scale cho hợp đồng USD và giá trị trung gian). Quy tắc làm tròn là cấu hình, không phải code: `pay.salary_component.rounding_mode` (`HALF_UP`, `DOWN`, `UP`, `HALF_EVEN`) và `rounding_unit` (1, 10, 100, 1000). Giá trị mặc định theo luật lấy từ legal pack (`ROUNDING.PIT`, `ROUNDING.SI`) **[verify-legal]**.
* Dòng kết quả giữ cả `amount` (đã làm tròn, giá trị chuẩn) và `amount_unrounded numeric(24,8)` để truy vết và đối soát từng đồng lẻ.
* Java dùng `BigDecimal` với `MathContext` tường minh. Cấm dùng số dấu phẩy động (NFR-PAY-002).

### 4.7 Ngày, giờ, múi giờ

* DB server và mọi session chạy với `TimeZone = 'UTC'`. Mọi thời điểm là `timestamptz`.
* **Ngày nghiệp vụ là `date`** theo lịch *địa phương* của địa điểm hoặc pháp nhân: ngày công, ngày hiệu lực, ngày của kỳ lương. Chúng không bao giờ được suy ra trong SQL từ `now()` mà không có múi giờ tường minh. Job kích hoạt chạy lúc 00:00 `Asia/Ho_Chi_Minh` (NFR-CORE-015).
* Giờ ca dùng `time` cộng cờ `crosses_midnight`. Lượt chấm công lưu `punch_at timestamptz` (chuẩn hóa về UTC), `local_ts timestamp` (giờ trên đồng hồ thiết bị, như khi nhận) và `tz_name text` (NFR-ATT-005).
* Kỳ năm-tháng được lưu là ngày đầu tháng (`date`, với `CHECK (date_trunc('month', m) = m)`), không lưu dạng chuỗi.
* Văn bản: encoding database UTF-8, collation ICU `und-x-icu` làm mặc định, và một collation ICU `vi-VN` dùng để sắp xếp tên. Tìm kiếm không phân biệt dấu dùng `sys.f_unaccent(lower(x))` với index GIN `pg_trgm` (NFR-CORE-011, NFR-PPL-009).

---

## 5. Ngày hiệu lực và bi-temporality (ADR-0024)

### 5.1 Pattern A — chỉ valid time (phần lớn master data)

```
valid_from  date NOT NULL,
valid_to    date NULL,                 -- exclusive; NULL = open-ended
valid_during daterange GENERATED ALWAYS AS (daterange(valid_from, valid_to, '[)')) STORED,
CONSTRAINT x_period_ck CHECK (valid_to IS NULL OR valid_to > valid_from),
CONSTRAINT x_period_ex EXCLUDE USING gist (tenant_id WITH =, <entity>_id WITH =, valid_during WITH &&)
```

* Các biên là **nửa mở `[valid_from, valid_to)`**. "Ngày kết thúc" hiển thị trên UI là `valid_to - 1`. Cách này tránh lỗi lệch một đơn vị kinh điển khi các khoảng liền kề nhau.
* Exclusion constraint cần `btree_gist` (cho phép so sánh bằng trên `uuid`) và đảm bảo không có chồng lấn. Khoảng trống được phép trừ khi entity quy định khác; "không có khoảng trống" do service kiểm tra.
* Cấu trúc: một **anchor table** giữ định danh và các thuộc tính bất biến (`core.org_unit`: `id`, `code`), và một **version table** giữ các thuộc tính theo ngày hiệu lực (`core.org_unit_version`). Việc sửa một phiên bản là các lệnh update thông thường được audit log ghi lại, như vậy là đủ cho master data cốt lõi.
* Dùng cho: pháp nhân, địa điểm, đơn vị tổ chức, vị trí, công việc, ngạch bậc, trung tâm chi phí, gán lịch làm việc, gán nhóm lương, tài khoản ngân hàng, đăng ký người phụ thuộc, tình trạng đặc biệt, địa chỉ.

### 5.2 Pattern B — bi-temporal (lương, phân công, tham số pháp lý; NFR-PPL-001, BR-PAY-072)

Pattern A cộng thêm transaction time trên cùng bảng:

```
recorded_at    timestamptz NOT NULL DEFAULT now(),   -- when this version became known
superseded_at  timestamptz NULL,                     -- when it stopped being the current knowledge
superseded_by  uuid NULL,                            -- the correcting row (soft, same table)
correction_reason text NULL,
EXCLUDE USING gist (tenant_id WITH =, <key> WITH =, valid_during WITH &&) WHERE (superseded_at IS NULL)
```

* **Dòng không bao giờ được update tại chỗ**, ngoại trừ việc đóng dấu `superseded_at`/`superseded_by`. Trigger `sys.tg_bitemporal_guard()` từ chối mọi thay đổi khác, và `DELETE` cũng bị từ chối. (Cột generated dạng stored có giá trị NULL trong `NEW` bên trong BEFORE trigger, nên guard bỏ qua `valid_during`. Điều này được phát hiện trong smoke test.)
* Transaction time là **timestamp của transaction** (`now()`), nên một lần thay thế và dòng thay thế của nó được tạo trong cùng một transaction sẽ có cùng thời điểm. "Theo hiểu biết vào ngày" có độ chi tiết theo từng transaction, đó là ngữ nghĩa mong muốn.
* **Thay đổi cho tương lai** (lương mới từ 1/1): đóng dòng hiện tại bằng cách thay thế nó và insert một bản sao với `valid_to = 2027-01-01`, rồi insert dòng mới. Tất cả diễn ra trong một transaction.
* **Điều chỉnh hồi tố** (lương tháng 3 bị sai): thay thế các dòng bị ảnh hưởng và insert các dòng đã sửa với cùng khoảng hiệu lực hoặc khoảng mới. Payroll phát hiện thay đổi vì `recorded_at` muộn hơn watermark của lần chạy, từ đó tạo ra các khoản điều chỉnh hồi tố (retro item) (gap §7.2 bước 7 và 13).
* Truy vấn:

```sql
-- as of business date :d, current knowledge
SELECT * FROM hr.salary_record
 WHERE tenant_id = :t AND employment_id = :e AND superseded_at IS NULL AND valid_during @> :d::date;

-- as of business date :d, as known on :k  (payroll reproduction, audit)
SELECT * FROM hr.salary_record
 WHERE tenant_id = :t AND employment_id = :e
   AND recorded_at <= :k AND (superseded_at IS NULL OR superseded_at > :k)
   AND valid_during @> :d::date;

-- all segments overlapping a pay period (proration)
SELECT * FROM hr.salary_record
 WHERE tenant_id = :t AND employment_id = :e AND superseded_at IS NULL
   AND valid_during && daterange(:p_start, :p_end, '[]');
```

* Index: một B-tree partial `(tenant_id, employment_id, valid_from) WHERE superseded_at IS NULL` cho luồng nóng, cộng với index GiST của exclusion constraint (cũng phục vụ `@>`/`&&`). Truy vấn toàn bộ lịch sử dùng `(tenant_id, employment_id, recorded_at)`.
* Định danh xuyên suốt các phiên bản: mỗi dòng phiên bản có `id` riêng (được snapshot và audit tham chiếu). Định danh nghiệp vụ ổn định là `employment_id` đối với lương, và `assignment_key` đối với phân công (một employment có thể giữ nhiều phân công đồng thời).
* Một thư viện Java dùng chung (`temporal-kit`) cài đặt các thao tác supersede/split/merge, nên service không bao giờ tự viết tay các lệnh update này.

### 5.3 Cơ cấu tổ chức theo thời điểm và data scope

`core.org_unit_version` lưu `parent_id` và một `path ltree` được vật chất hóa cho mỗi phiên bản. Cây tổ chức tại một thời điểm là `WHERE valid_during @> :d`. Một cây con là `path <@ :root_path`, được hỗ trợ bởi index GiST trên `path`. Path chỉ thay đổi khi một đợt tái cơ cấu kích hoạt phiên bản mới, và Core tính lại các phiên bản của các đơn vị con trong cùng gói (`reorg_package`). Mục tiêu: NFR-CORE-003 (2,000 đơn vị ≤ 1 s).

Data scope (SA chịu trách nhiệm thực thi): mọi dòng gắn với nhân viên trong `hr`, `att`, `pay` mang `legal_entity_id` và `org_unit_id` được phi chuẩn hóa (tại ngày của dòng, lấy từ phân công). Core phân giải scope của user thành một tập id đơn vị tổ chức (có cache, từ `core.org_unit_version.path`). Các module áp dụng `org_unit_id = ANY(:scopeIds)` trên các index `(tenant_id, org_unit_id, ...)`. RLS **chỉ** thực thi tenant, không thực thi data scope: scope phụ thuộc vào user và thay đổi theo ủy quyền, nên đưa nó vào RLS sẽ phá vỡ plan caching và ngân sách ≤ 20 ms (NFR-CORE-002).

---

## 6. Legal parameter pack (ADR-0025)

Chủ sở hữu: SaaS (gap §5 #1). Các entity `legal_param_set`/`pit_schedule` của BRD Payroll chuyển về đây. Payroll, T&A, People, Core và Rec đọc qua API của Legal Parameter Service (SA chịu trách nhiệm API và caching), tại một ngày hiệu lực.

```mermaid
erDiagram
  LEGAL_PARAM_DEFINITION ||--o{ LEGAL_PARAM_VALUE : "typed by"
  LEGAL_PARAM_PACK ||--o{ LEGAL_PARAM_VALUE : contains
  LEGAL_PARAM_VALUE ||--o{ LEGAL_PARAM_TABLE_ROW : "rows (maps)"
  LEGAL_PARAM_DEFINITION ||--o{ LEGAL_PARAM_OVERRIDE : "overridable by"
  LEGAL_PARAM_OVERRIDE_SET ||--o{ LEGAL_PARAM_OVERRIDE : groups
  LEGAL_PARAM_DEFINITION {
    text param_key PK "SI.EE.RATE, MIN_WAGE.REGION_I, PIT.BRACKETS"
    text value_type "SCALAR TABLE MAP CALENDAR_TEMPLATE"
    text scalar_type "NUMBER INT MONEY RATE BOOL TEXT DATE"
    text unit
    jsonb json_schema
    text override_rule "NONE ANY FLOOR CAP"
    text override_scope "TENANT LEGAL_ENTITY"
  }
  LEGAL_PARAM_PACK {
    uuid id PK
    text pack_code "VN"
    bigint pack_version "monotonic"
    text status "DRAFT IN_TEST APPROVED PUBLISHED SUPERSEDED WITHDRAWN"
    timestamptz published_at
    text content_sha256
  }
  LEGAL_PARAM_VALUE {
    uuid id PK
    uuid pack_id FK
    text param_key FK
    date valid_from
    date valid_to
    numeric value_num
    jsonb value_json
    text legal_ref
  }
  LEGAL_PARAM_TABLE_ROW {
    uuid value_id FK
    text row_key "admin_unit_code"
    jsonb row_value "region"
  }
  LEGAL_PARAM_OVERRIDE_SET {
    uuid tenant_id
    int override_version
    text status
  }
  LEGAL_PARAM_OVERRIDE {
    uuid tenant_id
    uuid override_set_id FK
    uuid legal_entity_id "nullable"
    text param_key
    date valid_from
    numeric value_num
    jsonb value_json
  }
```

Quy tắc:

1. **Một pack là một snapshot lũy kế, bất biến.** Pack phiên bản *N* chứa **mọi** tham số kèm **toàn bộ lịch sử ngày hiệu lực** của nó: vài trăm khóa × vài khoảng thời gian, tức là nhỏ. "Giá trị của khóa K vào ngày D theo pack N" là một lần tra cứu duy nhất trong N. Các pack cũ vẫn truy vấn được mãi mãi, giúp tái lập được trong 10 năm mà không cần phát lại các delta. Trigger chặn mọi thay đổi khi pack đã ở trạng thái `PUBLISHED`.
2. Hình dạng giá trị: giá trị vô hướng trong `value_num`; giá trị có cấu trúc (biểu thuế TNCN lũy tiến, hệ số OT theo loại, lộ trình nghỉ hưu) trong `value_json`, được kiểm tra theo `definition.json_schema`. Do đó một cấu trúc biểu thuế TNCN mới **không cần thay đổi schema** (NFR-PAY-007). Các bảng ánh xạ lớn (đơn vị hành chính → vùng lương tối thiểu, khoảng 3,300 xã/phường sau đợt sắp xếp năm 2025) nằm trong `legal_param_table_row`.
3. **Publish** (vòng đời theo SA ADR-0010: `DRAFT → IN_TEST → APPROVED → PUBLISHED → SUPERSEDED`, cộng với `WITHDRAWN`): các pack đã publish tạo thành một thứ tự toàn phần theo `pack_code`. Chính xác một pack là *hiện hành*, và publish pack mới sẽ chuyển pack trước sang `SUPERSEDED` (nội dung không đổi, vẫn dùng để tái lập). Event `saas.LegalParameterPackPublished` (phiên bản pack, các khóa và ngày thay đổi) điều khiển việc vô hiệu hóa cache và phát hiện retro.
4. **Luật có hiệu lực hồi tố** (NFR-SAAS-016): pack sau có thể thay đổi giá trị cho các ngày trong quá khứ. Các kết quả đã duyệt giữ nguyên pack đã đóng dấu. Payroll so sánh pack đã đóng dấu với pack hiện hành cho các khóa và ngày bị ảnh hưởng, và đề xuất retro.
5. **Tenant override** chỉ được phép khi `override_rule <> NONE`, ở mức cụ thể nhất mà định nghĩa cho phép (`TENANT`, `LEGAL_ENTITY` hoặc `LOCATION`; thứ tự phân giải GLOBAL → TENANT → LEGAL_ENTITY → LOCATION, ADR-0010):
   * `FLOOR`: giá trị của tenant phải ≥ giá trị luật định (ví dụ số ngày phép, mức lương tối thiểu dùng trong thang lương).
   * `CAP`: giá trị của tenant phải ≤ giá trị luật định (ví dụ trần OT nội bộ).
   * `ANY`: tự do lựa chọn (ví dụ phương pháp tính ngày công chuẩn mặc định).

   Việc kiểm tra chạy so với giá trị luật định **cho cùng các ngày** tại thời điểm kích hoạt, và chạy lại khi một pack mới được publish; vi phạm sẽ phát cảnh báo cho tenant admin và payroll SME. Các override được gom vào một `override_set` của tenant với `override_version` tăng đơn điệu, được phê duyệt qua workflow của Core và có audit. Các set đã kích hoạt là bất biến, và một thay đổi sẽ tạo ra phiên bản set mới.
6. **Đóng dấu phiên bản:** mọi phiên bản kết quả chấm công, lần chạy lương, snapshot đầu vào của lần chạy, mô phỏng offer (`rec.offer.sim_param_version`) và phép tính điều kiện hưởng của People đều lưu `legal_pack_id` + `override_version`. Lần chạy lương còn lưu `config_version` và `code_version` (NFR-SAAS-017).
7. DB dedicated nhận các pack đã publish qua replication. Pack là bảng toàn cục thuần túy không có `tenant_id`; override thuộc tenant.

Thứ tự phân giải tại thời điểm tính toán là: override của tenant theo pháp nhân → override của tenant → giá trị trong pack. Tập giá trị đã phân giải cho một lần chạy được đóng băng vào run config snapshot (§7.3).

---

## 7. Mô hình entity chính theo schema

Các sơ đồ chỉ thể hiện các bảng và cột chính. Mọi bảng thuộc tenant còn có `tenant_id` trong PK và các cột chuẩn (§4.3). Các quan hệ nét đứt (`..`) là **soft reference giữa các schema** (không có FK). DDL đã có cho `saas`, `core` và `hr` (xem `ddl/`); `att`, `pay` và `rec` được đặc tả ở đây và sẽ có DDL ở vòng lặp tiếp theo.

### 7.1 `saas`

```mermaid
erDiagram
  TENANT ||--o{ SUBSCRIPTION : has
  PLAN ||--o{ PLAN_VERSION : versions
  PLAN_VERSION ||--o{ SUBSCRIPTION : "sold as"
  SUBSCRIPTION ||--o{ SUBSCRIPTION_ITEM : lines
  TENANT ||--o{ ENTITLEMENT : resolved
  TENANT ||--o{ INVOICE : billed
  INVOICE ||--o{ INVOICE_LINE : lines
  TENANT ||--o{ SUPPORT_GRANT : grants
  TENANT ||--o{ TENANT_KEY : "KEK refs"
  TENANT ||--o{ SUBJECT_KEY : "per-subject DEKs"
  TENANT ||--o{ USER_CREDENTIAL : "auth (T)"
  TENANT ||--o{ CONSENT_RECORD : "(T)"
  TENANT ||--o{ DATA_SUBJECT_REQUEST : "(T)"
  TENANT {
    uuid id PK
    text slug UK
    text state "PROVISIONING TRIAL ACTIVE SUSPENDED TERMINATING TERMINATED PURGED"
    text tier "SHARED DEDICATED"
    text time_zone_default "Asia/Ho_Chi_Minh"
    text kms_key_ref
    text time_zone
  }
  PLAN_VERSION {
    uuid id PK
    jsonb modules
    jsonb features
    jsonb limits
    date valid_from
  }
  SUBSCRIPTION {
    uuid tenant_id
    uuid id PK
    uuid plan_version_id FK
    text billing_cycle
    date start_date
    int committed_seats
    text status
  }
  ENTITLEMENT {
    uuid tenant_id
    uuid legal_entity_id "nullable soft core"
    text feature_key
    bool is_enabled
    numeric limit_value
    text source "BASELINE PLAN ADDON OVERRIDE TRIAL"
    date valid_from
  }
  INVOICE {
    uuid tenant_id
    uuid id PK
    text invoice_no
    text einvoice_code
    numeric total_amount
    text status
  }
  SUBJECT_KEY {
    uuid tenant_id
    text subject_type "PERSON CANDIDATE CASUAL_PAYEE"
    uuid subject_id
    int key_version
    bytea wrapped_dek "NULL after shredding"
    timestamptz destroyed_at
  }
```

Ngoài ra trong `saas`: `price_book`, `feature_flag`, `usage_record` (partition theo tháng), `payment`, `operator_session`, `identity_provider_config (T)` (SSO, **Phase 2**, R2-04), `mfa_factor (T)` (TOTP và OTP qua e-mail ở Phase 1), `api_client (T)` (public API, **Phase 2**), `webhook_subscription (T)` (**Phase 2**, R2-01), `retention_policy (T)`, `security_incident`, cộng với các bảng tham số pháp lý (§6). (Các bảng `import_batch`/`reconciliation_run` của v0.1 dành cho chương trình migrate từ Oracle đã bị bỏ; việc import khi go-live và đối soát của nó nằm trong `core.import_*`, §13.)

`saas.billing_party (T)` chỉ giữ thông tin định danh xuất hóa đơn của các pháp nhân thuộc tenant, khóa theo `legal_entity_id` (soft reference). Master data pháp nhân vẫn nằm ở Core (gap §5 #23).

**Capability nền tảng luôn bật (R2-18, GAP-060; SA ADR-0003 quyết định 5).** `saas.baseline_capability` (bảng platform, được replicate) liệt kê các feature key mà mọi tenant đều có bất kể đã mua gì: `baseline.core` (Core), `baseline.people.master` (People cơ bản: person, employment, assignment, hồ sơ lương), `baseline.legal_params` (legal parameter pack qua LPS API), `baseline.pay.scales_bands` (thang/khung lương, gap §5 #7) và `baseline.att.excel_import` (import chấm công từ Excel, tạo ra một Attendance Result chuẩn). Entitlement resolver ghi một dòng `saas.entitlement` với `source = 'BASELINE'` cho mỗi tenant và mỗi key. `entitlement_baseline_ck` chỉ cho phép dòng BASELINE dưới dạng grant được bật trên toàn tenant, và `entitlement_period_ex` (một dòng cho mỗi tenant × feature × pháp nhân × khoảng thời gian) khi đó ngăn mọi dòng `OVERRIDE`/`PLAN` vô hiệu hóa nó trong cùng khoảng thời gian. Subscription của module thêm các dòng `PLAN`/`ADDON` cho đầy đủ tính năng của module.

### 7.2 `core`

```mermaid
erDiagram
  LEGAL_ENTITY ||--o{ LEGAL_ENTITY_VERSION : "effective-dated"
  LEGAL_ENTITY ||--o{ LEGAL_ENTITY_BANK_ACCOUNT : pays_from
  LEGAL_ENTITY ||--o{ LOCATION : has
  LOCATION ||--o{ LOCATION_VERSION : "effective-dated"
  ORG_UNIT ||--o{ ORG_UNIT_VERSION : "effective-dated"
  ORG_UNIT_VERSION }o--|| ORG_UNIT : "parent_id"
  JOB_FAMILY ||--o{ JOB_TITLE : groups
  GRADE_STRUCTURE ||--o{ GRADE : levels
  POSITION ||--o{ POSITION_VERSION : "effective-dated"
  POSITION_VERSION }o--|| ORG_UNIT : in
  POSITION_VERSION }o--|| JOB_TITLE : of
  COST_CENTRE }o--|| LEGAL_ENTITY : owned_by
  USER_ACCOUNT ||--o{ ROLE_ASSIGNMENT : holds
  ROLE ||--o{ ROLE_ASSIGNMENT : granted
  ROLE ||--o{ ROLE_PERMISSION : includes
  WORKFLOW_DEFINITION ||--o{ WORKFLOW_INSTANCE : runs
  WORKFLOW_INSTANCE ||--o{ WORKFLOW_TASK : tasks
  DOCUMENT ||--o{ DOCUMENT_VERSION : versions
  LEGAL_ENTITY_VERSION {
    uuid legal_entity_id FK
    date valid_from
    date valid_to
    text legal_name_vi
    text tax_code
    text si_unit_code
    text admin_unit_code "global code"
    text min_wage_region
  }
  ORG_UNIT_VERSION {
    uuid org_unit_id FK
    uuid parent_id FK
    ltree path
    uuid legal_entity_id FK
    uuid location_id FK
    uuid cost_centre_id FK
    text status
    date valid_from
  }
  POSITION_VERSION {
    uuid position_id FK
    uuid org_unit_id FK
    uuid job_title_id FK
    uuid reports_to_position_id
    numeric fte_budget
    text status "PROPOSED ACTIVE FROZEN CLOSED"
    date valid_from
  }
  ROLE_ASSIGNMENT {
    uuid user_id FK
    uuid role_id FK
    text scope_type "TENANT LE ORG_SUBTREE SELF DIRECT_REPORTS"
    uuid_array scope_ids
    date valid_from
  }
  USER_ACCOUNT {
    uuid id PK
    text login
    uuid employment_id "soft hr"
    text status "ACTIVE FORMER_EMPLOYEE DEACTIVATED"
  }
```

Toàn cục (không có `tenant_id`): `country`, `currency`, `admin_unit` (với `valid_from/valid_to`, `replaced_by_code`), `admin_unit_mapping` (cũ→mới, `MERGE/SPLIT/RENAME`), `bank`, `bank_branch`, `code_list`/`code_value` (dòng toàn cục dùng sentinel tenant, xem §3.5), `exchange_rate` (tỷ giá tham chiếu toàn cục VCB/SBV; tenant có thể thêm dòng riêng).

Ngoài ra trong `core`: `company_group`, `employee_category`, `reorg_package`/`reorg_change`/`reorg_mapping`, `permission`, `sod_rule`, `approval_delegation`, `notification_template`/`notification`/`notification_delivery`, `setting_definition`/`setting_value`, `sequence_counter`, `custom_field_def`/`custom_field_value` (bảng giá trị khóa theo `(target_schema, target_table, target_id)`), `import_job`/`import_row`/`import_reconciliation` (DDL trong `002`, §13; các loại import do module đăng ký trong code), `export_job`.

### 7.3 `hr`

```mermaid
erDiagram
  PERSON ||--o{ EMPLOYMENT : "engaged as"
  PERSON ||--o{ PRIMARY_EMPLOYMENT : "designates (per purpose)"
  EMPLOYMENT ||--o{ PRIMARY_EMPLOYMENT : "is primary for"
  PERSON ||--o{ PERSON_IDENTITY_DOC : holds
  PERSON ||--o{ PERSON_ADDRESS : lives
  PERSON ||--o{ FAMILY_MEMBER : has
  PERSON ||--o{ BANK_ACCOUNT : owns
  FAMILY_MEMBER ||--o{ PIT_DEPENDANT : "registered as"
  EMPLOYMENT ||--o{ PIT_DEPENDANT : claims
  EMPLOYMENT ||--o{ ASSIGNMENT : "bi-temporal"
  EMPLOYMENT ||--o{ SALARY_RECORD : "bi-temporal"
  SALARY_RECORD ||--o{ SALARY_RECORD_ALLOWANCE : lines
  EMPLOYMENT ||--o{ CONTRACT : signs
  CONTRACT ||--o{ CONTRACT_APPENDIX : amends
  EMPLOYMENT ||--o{ DECISION : subject_of
  EMPLOYMENT ||--o| SEPARATION : ends
  EMPLOYMENT ||--o{ SPECIAL_STATUS : flags
  PERSON {
    uuid id PK
    text full_name
    date birth_date
    text gender_code
    text nationality_code
    bytea national_id_enc
    bytea national_id_bidx
    bytea tax_code_enc
    bytea si_number_enc
    bytea religion_code_enc
  }
  EMPLOYMENT {
    uuid id PK
    uuid person_id FK
    uuid legal_entity_id "soft core"
    text employee_no
    date hire_date
    date seniority_date
    date end_date
    text status "PRE_HIRE ACTIVE SUSPENDED TERMINATED CANCELLED"
    text worker_category
  }
  PRIMARY_EMPLOYMENT {
    uuid id PK
    uuid person_id FK
    uuid employment_id FK
    text purpose "PIT_FAMILY_DEDUCTION SOCIAL_INSURANCE"
    date valid_from
    date valid_to
    text status "ACTIVE CANCELLED"
  }
  ASSIGNMENT {
    uuid id PK
    uuid assignment_key
    uuid employment_id FK
    text assignment_type "PRIMARY CONCURRENT SECONDMENT TEMP_TRANSFER"
    uuid position_id "soft core"
    uuid org_unit_id "soft core"
    uuid manager_employment_id
    numeric fte
    date valid_from
    timestamptz recorded_at
    timestamptz superseded_at
  }
  SALARY_RECORD {
    uuid id PK
    uuid employment_id FK
    text pay_basis "GROSS NET"
    numeric base_salary_amount
    numeric agreed_net_amount
    numeric si_salary_amount
    text pay_scale_code "soft pay"
    numeric probation_pct
    date valid_from
    timestamptz recorded_at
    timestamptz superseded_at
  }
  CONTRACT {
    uuid id PK
    uuid employment_id FK
    text contract_type_code
    text contract_no
    int definite_seq
    date start_date
    date end_date
    text status
  }
  BANK_ACCOUNT {
    uuid id PK
    uuid person_id FK
    text bank_code "global"
    bytea account_no_enc
    text account_no_last4
    text split_type
    date valid_from
  }
  PIT_DEPENDANT {
    uuid family_member_id FK
    uuid employment_id FK
    date from_month
    date to_month
    text status
  }
```

`hr.separation` (DDL ở vòng lặp tiếp theo): `employment_id`, `separation_type`, `reason_code`, `legal_ground_code`, `notice_date`, `required_notice_days`, `last_working_date`, `termination_date`, `access_revocation_date`, `severance_type`, `is_protected`, `status`, `workflow_instance_id`. Ngoài ra: `person_contact`, `emergency_contact`, `education`, `certificate`, `work_permit`, `checklist*`, `discipline_case`, `reward`, đánh giá và đào tạo (P2), và `hr_document` (chỉ metadata; file nằm trong document service của Core).

Tài khoản ngân hàng thuộc về **person**, không thuộc employment, nên một tài khoản có thể phục vụ nhiều employment đồng thời. Payroll chọn một tài khoản qua `employment_id` trên `pay.ext_hr_bank_split`.

**Làm việc đồng thời tại nhiều pháp nhân (R2-16, GAP-031, BR-PPL-002/004; Phase 1, mức cơ bản).** Mỗi cá nhân thực có một `hr.person` trong mỗi tenant; mỗi quan hệ làm việc với một pháp nhân là một `hr.employment`. Mô hình dữ liệu hỗ trợ như sau:

| Quy tắc | Nơi thực thi |
|---|---|
| Tại một thời điểm, một người có tối đa một employment đang hiệu lực cho mỗi pháp nhân (làm thêm việc trong cùng pháp nhân = một assignment `CONCURRENT`) | `employment_person_le_ex` (exclusion constraint, `003_hr.sql`) |
| **Mỗi người tối đa một employment chính cho mỗi mục đích trong mỗi khoảng thời gian** — các mục đích `PIT_FAMILY_DEDUCTION` (đơn vị sử dụng lao động áp dụng giảm trừ bản thân và người phụ thuộc) và `SOCIAL_INSURANCE` (đơn vị sử dụng lao động mà qua đó đóng BHXH, quy tắc nhiều hợp đồng của Luật BHXH 2024 `[verify-legal]`) | `hr.primary_employment` + `primary_employment_ex`; FK ghép `(tenant_id, person_id, employment_id)` đảm bảo employment được chỉ định thuộc về chính người đó |
| Employment chính có thể thay đổi trong khi cả hai employment vẫn tiếp tục (ví dụ employment chính kết thúc, hoặc nhân viên đăng ký lại thuế TNCN) | `hr.primary_employment` là valid-time (Pattern A, §5.1): đóng dòng cũ, mở dòng mới; lịch sử được giữ lại |
| Người chỉ có một employment đang hiệu lực thì không cần dòng nào; có từ hai trở lên thì cả hai mục đích phải được chỉ định cho mọi ngày | People service khi tuyển dụng/chấm dứt + job kiểm tra nhất quán hằng đêm (quy tắc liên dòng, không phải constraint) |
| Người phụ thuộc chỉ được đăng ký giảm trừ qua employment chính về thuế TNCN | `pit_dependant_claim_ex` (mỗi người phụ thuộc một lần đăng ký mỗi tháng) + quy tắc của People service trên `pit_dependant.employment_id` |
| Mỗi pháp nhân khấu trừ thuế TNCN trên khoản chi trả của chính mình; chỉ employment chính về thuế TNCN áp dụng giảm trừ gia cảnh; chỉ employment chính về BHXH kê khai BHXH | Payroll (đọc thông tin chỉ định từ event/API của People vào `pay.employee_pay_profile`) |
| Trần OT năm tính gộp giữa các pháp nhân | **Phase 2** (R2-08); trần OT ở Phase 1 tính theo từng employment |

Cờ boolean `hr.employment.is_primary` của v0.2 (một cờ cho toàn bộ khoảng thời gian employment, với exclusion trên `employment_period`) bị rút lại vì nó không thể biểu diễn việc đổi employment chính trong khi cả hai employment vẫn tiếp tục. Event: các thay đổi chỉ định do People publish; tên event (đề xuất `people.PrimaryEmploymentChanged`) cần được Integration analyst bổ sung vào danh mục gap §6.

### 7.4 `att`

```mermaid
erDiagram
  HOLIDAY_CALENDAR ||--o{ HOLIDAY_DATE : contains
  SHIFT_DEFINITION ||--o{ SCHEDULE_PATTERN_DAY : used_in
  SCHEDULE_PATTERN ||--o{ SCHEDULE_PATTERN_DAY : cycle
  SCHEDULE_PATTERN ||--o{ SCHEDULE_ASSIGNMENT : assigned
  ROSTER ||--o{ ROSTER_LINE : plans
  DEVICE ||--o{ RAW_PUNCH : captures
  DAILY_TIMESHEET ||--o{ TIMESHEET_CORRECTION : corrected_by
  OVERTIME_REQUEST ||--o{ OVERTIME_RESULT : yields
  LEAVE_TYPE ||--o{ LEAVE_REQUEST : of
  LEAVE_TYPE ||--o{ LEAVE_BALANCE_LEDGER : balances
  ATTENDANCE_PERIOD ||--o{ ATTENDANCE_RESULT : "versions v1 v2"
  ATTENDANCE_RESULT ||--o{ ATTENDANCE_RESULT_LINE : lines
  RAW_PUNCH {
    uuid id
    date work_date_hint "partition key"
    timestamptz punch_at
    timestamp local_ts
    text source "IMPORT (P1); DEVICE MOBILE WEB (P2)"
    uuid device_id
    text enrol_id
    uuid employment_id
    bytea geo_enc "P2 only"
    text validation_status
    text dedup_key UK
  }
  DAILY_TIMESHEET {
    uuid employment_id
    date work_date "partition key"
    uuid shift_id
    text day_status
    numeric worked_hours
    int late_min
    numeric night_hours
    int ot_candidate_min
    numeric day_value
    int calc_version
    uuid legal_pack_id
  }
  LEAVE_BALANCE_LEDGER {
    uuid employment_id
    text leave_type_code
    int leave_year
    text txn_type "ACCRUE TAKE CANCEL CARRY EXPIRE ENCASH ADJUST OPENING"
    numeric quantity
    uuid ref_id
  }
  ATTENDANCE_PERIOD {
    uuid legal_entity_id
    uuid pay_group_id "soft pay"
    date period_start
    date period_end
    text status "OPEN IN_REVIEW CLOSED LOCKED"
    int current_version
    int locked_version
  }
  ATTENDANCE_RESULT {
    uuid period_id FK
    int result_version
    text status "PUBLISHED WITHDRAWN LOCKED"
    text checksum_sha256
    uuid legal_pack_id
    int override_version
    timestamptz published_at
  }
  ATTENDANCE_RESULT_LINE {
    uuid result_id FK
    uuid employment_id
    uuid assignment_id
    text category_code
    numeric quantity
    text unit
    bool is_retro
    uuid original_period_id
  }
```

Quy tắc chính:

* `raw_punch` là append-only, được range-partition theo `work_date_hint` (theo tháng), và loại trùng bằng `UNIQUE (tenant_id, dedup_key, work_date_hint)` với `dedup_key = hash(device_id|enrol_id|punch_at)` (NFR-ATT-001). Việc tiếp nhận là một batch `INSERT … ON CONFLICT DO NOTHING`: ở Phase 1 từ bước commit import của một file xuất từ máy chấm công (mỗi chunk của import job là một chunk, `import_job_id` được giữ trên dòng), ở Phase 2 còn từ consumer của punch stream.
* `daily_timesheet` có thể thay đổi cho đến khi kỳ của nó ở trạng thái LOCKED. Nó được partition theo tháng theo `work_date` với `fillfactor = 80` để tận dụng HOT update. Nó được tính lại thay vì sửa, và `calc_version` tăng lên sau mỗi lần tính lại.
* **Kết quả chấm công là bất biến.** Khóa lại kỳ sẽ tạo `result_version + 1`, và phiên bản trước chuyển sang `WITHDRAWN`.
* **Close hold** (SA ADR-0019): `att.attendance_result_hold (tenant_id, id, period_id, result_version, payroll_run_id, held_at, released_at, status ACTIVE/RELEASED/CONSUMED)` với partial unique index `(tenant_id, period_id) WHERE status = 'ACTIVE'`. Payroll giành hold khi submit qua T&A API chạy in-process. Thao tác mở lại kỳ kiểm tra hold ACTIVE trong cùng transaction (`SELECT … FOR UPDATE` trên dòng kỳ) và bị từ chối khi đang có hold. Khi được duyệt, hold chuyển sang `CONSUMED`, và kỳ chuyển sang LOCKED bằng `UPDATE att.attendance_period SET status='LOCKED', locked_version=:v WHERE id=:p AND current_version=:v AND status='CLOSED'`. Nhờ có hold nên lệnh này không thể ảnh hưởng tới 0 dòng, nhưng predicate vẫn được giữ làm guard.
* Các delta hồi tố sau khi LOCKED là các dòng có `is_retro = true` và `original_period_id`, nằm trong kết quả của kỳ OPEN tiếp theo (hoặc một kết quả off-cycle cho người nghỉ việc).
* `ot_cap_ledger` (theo employment × tháng/năm) được cập nhật trong cùng transaction với việc duyệt OT, với `SELECT … FOR UPDATE` trên dòng ledger, giúp tuần tự hóa việc kiểm tra trần theo từng nhân viên.
* **Nguồn chấm công ở Phase 1 (OD-9, R2-01): chỉ import file.** Ở Phase 1 không có device connector trực tiếp hay endpoint tiếp nhận. Lượt chấm công đến dưới dạng các dòng `IMPORT` từ **template file xuất từ máy chấm công** (CSV/Excel xuất từ phần mềm máy chấm công của khách hàng, ánh xạ theo bố cục cột riêng của từng nhà cung cấp: enrol id, ngày-giờ, id thiết bị/terminal, cờ vào/ra nếu có) thông qua import engine của Core; `dedup_key` dùng terminal id trong file, nên import lại một file bị chồng lấn cũng không gây hại. Chấm công tổng hợp (số ngày/giờ theo nhân viên) dùng luồng import chấm công Excel riêng, tạo trực tiếp một Attendance Result (capability nền tảng, R2-18). Nguồn `DEVICE` (connector trực tiếp, Phase 2), `MOBILE`/`WEB`, `geo_enc`, tham chiếu ảnh selfie và `geofence` thuộc Phase 2; CHECK trên `source` trong DDL Phase 1 chỉ liệt kê `IMPORT` và sẽ được mở rộng (expand migration) ở Phase 2. `att.device` vẫn có ở Phase 1 làm registry các terminal và ánh xạ enrolment dùng cho việc import file.
* Các dòng `leave_balance_ledger` có `txn_type = 'OPENING'` là số dư khi go-live được nạp bởi `OB_LEAVE_BALANCE` (ADR-0037), mỗi employment × loại nghỉ phép × năm một dòng, ghi ngày là ngày go-live.
* Ngoài ra: `device`, `geofence` (P2), `enrolment_mapping`, `business_trip`, `remote_work_request`, `attendance_code`, `leave_policy`, `ext_core_location`, `ext_hr_assignment` (projection), `ext_pay_pay_group_assignment`.

### 7.5 `pay`

```mermaid
erDiagram
  PAY_GROUP ||--o{ PAY_PERIOD : calendar
  PAY_GROUP ||--o{ PAY_GROUP_ASSIGNMENT : members
  SALARY_COMPONENT ||--o{ COMPONENT_VERSION : versions
  FORMULA ||--o{ FORMULA_VERSION : versions
  SALARY_STRUCTURE ||--o{ STRUCTURE_LINE : bundles
  PAY_PERIOD ||--o{ PAYROLL_RUN : runs
  PAYROLL_RUN ||--|| RUN_CONFIG_SNAPSHOT : "frozen config"
  PAYROLL_RUN ||--o{ RUN_EMPLOYEE : population
  RUN_EMPLOYEE ||--|| RUN_INPUT_SNAPSHOT : "frozen inputs"
  RUN_EMPLOYEE ||--o{ RESULT_LINE : "partitioned"
  RUN_EMPLOYEE ||--o| PAYSLIP : publishes
  RUN_EMPLOYEE ||--o{ SI_CONTRIBUTION : contributes
  RUN_EMPLOYEE ||--o{ PIT_WITHHOLDING : withholds
  SI_DECLARATION ||--o{ SI_DECLARATION_LINE : lines
  PIT_DECLARATION ||--o{ PIT_DECLARATION_LINE : lines
  PAYROLL_RUN ||--o{ BANK_PAYMENT_FILE : pays
  BANK_PAYMENT_FILE ||--o{ BANK_PAYMENT_LINE : lines
  PAYROLL_RUN ||--o{ GL_JOURNAL : posts
  PAYROLL_RUN {
    uuid id PK
    uuid pay_period_id FK
    text run_type "REGULAR OFFCYCLE FINAL_SETTLEMENT BONUS CORRECTION"
    text calc_mode "PROVISIONAL FINAL"
    text status "DRAFT CALCULATED SUBMITTED APPROVED PAID CLOSED REJECTED UNLOCKED REVERSED"
    bool is_stale
    uuid legal_pack_id
    int override_version
    text config_version
    text code_version
    jsonb attendance_versions "period_id to version"
    bigint people_watermark
    timestamptz approved_at
  }
  RUN_EMPLOYEE {
    uuid run_id FK
    uuid employment_id
    uuid legal_entity_id
    uuid org_unit_id
    text status
    numeric gross_amount
    numeric net_amount
    text result_sha256
  }
  RUN_INPUT_SNAPSHOT {
    uuid run_employee_id FK
    jsonb payload "lz4 compressed"
    text payload_sha256
    jsonb source_versions
  }
  RESULT_LINE {
    uuid run_employee_id
    date period_month "partition key"
    text component_code
    text segment_key
    numeric qty
    numeric rate
    numeric amount
    numeric amount_unrounded
    numeric taxable_amount
    numeric si_basis_amount
    date retro_of_period
  }
  SI_CONTRIBUTION {
    uuid employment_id
    date contribution_month
    numeric basis_amount
    numeric ee_si_amount
    numeric er_si_amount
    text status "CONTRIBUTE EXEMPT OPENING"
  }
```

Ngoài ra trong `pay`: `pay_scale`/`pay_scale_step`/`grade_band` (sở hữu tại đây, gap §5 #7), `employee_pay_profile` (projection của People + các thiết lập chỉ dành cho payroll), `ext_hr_salary`, `ext_hr_bank_split`, `ext_hr_dependant_count`, `ext_att_result` (con trỏ tới các phiên bản kết quả đã sử dụng), `variable_input`/`input_batch`, `retro_item`, `loan`/`loan_schedule`, `deduction_order`, `final_settlement`, `casual_payee`/`casual_payment`, `pit_commitment`, `pit_finalisation_auth`, `pit_certificate`, `payslip_delivery`, `payslip_delivery_preference` (bên dưới), `bank_file_template`, `gl_mapping`, `cost_allocation`, `ytd_balance` (theo employment × năm tính thuế × tháng × mã số dư; các dòng có `source = 'OPENING'` là các tháng trước go-live của năm tính thuế hiện tại được nạp bởi `OB_YTD_PAYROLL`, ADR-0037, và không bao giờ được tính lại).

**Gửi phiếu lương qua e-mail dưới dạng PDF có mật khẩu (R2-17, GAP-057; SA ADR-0015).** `pay.payslip_delivery_preference (tenant_id, id, person_id, channel ESS_ONLY|EMAIL_PDF, email_target WORK|PERSONAL, pdf_password_enc bytea, password_set_at, password_set_by, subject_key_version, status)` — mỗi người một dòng (một mật khẩu cho mọi employment của người đó). `pdf_password_enc` là **P3**: được mã hóa bằng subject DEK của người đó (§9.3), chỉ-ghi qua API (không bao giờ trả về, không bao giờ ghi log, không bao giờ có trong event hay payload của job), chỉ được giải mã trong bộ nhớ bởi job gửi phiếu lương qua e-mail. Mật khẩu do **nhân viên tự đặt trong ESS** với step-up (`acr ≥ 2`); HR và operator chỉ có thể xóa nó (buộc "đặt mật khẩu mới"), không bao giờ đọc hay đặt nó. Khi không có mật khẩu, e-mail không có file đính kèm, chỉ có thông báo và đường link ESS. `pay.payslip_delivery` ghi lại mỗi lần gửi (id phiếu lương, SHA-256 của file đính kèm đã mã hóa, người nhận, trạng thái đã gửi/bị trả về, `password_set_at` đã dùng); bản thân file PDF mã hóa là tạm thời (được tạo cho mỗi e-mail, chuyển cho kênh e-mail dưới dạng một object tồn tại ngắn trong prefix của tenant và bị xóa sau khi gửi, tối đa 24 h), trong khi phiếu lương lưu trữ vẫn là file PDF/A không mã hóa trong document service (PDF/A không cho phép mã hóa). Crypto-shred subject DEK cũng hủy luôn mật khẩu. Audit: đặt/xóa mật khẩu, mọi e-mail có file đính kèm, mọi lỗi (`audit.audit_event`, không chứa giá trị).

### 7.6 `rec` (Phase 2)

```mermaid
erDiagram
  STAFFING_PLAN ||--o{ STAFFING_PLAN_LINE : lines
  STAFFING_PLAN_LINE ||--o{ REQUISITION : funds
  REQUISITION ||--o{ JOB_OPENING : opens
  JOB_OPENING ||--o{ JOB_POSTING : published_on
  CANDIDATE ||--o{ APPLICATION : applies
  JOB_OPENING ||--o{ APPLICATION : receives
  APPLICATION ||--o{ STAGE_HISTORY : moves
  APPLICATION ||--o{ INTERVIEW : schedules
  INTERVIEW ||--o{ SCORECARD : feedback
  APPLICATION ||--o{ OFFER : "versions"
  APPLICATION ||--o| HIRE_HANDOFF : "hand-off"
  CANDIDATE {
    uuid id PK
    text full_name
    bytea email_enc
    bytea email_bidx
    bytea phone_enc
    bytea id_doc_enc
    uuid employee_person_id "soft hr"
    text status "ACTIVE ANONYMISED"
    date retention_until
  }
  REQUISITION {
    uuid id PK
    uuid position_id "soft core"
    uuid org_unit_id "soft core"
    int vacancies
    int filled_count
    text status
  }
  OFFER {
    uuid application_id FK
    int offer_version
    text salary_basis "GROSS NET"
    numeric base_salary_amount
    uuid sim_legal_pack_id
    text status
    date expires_at
  }
  HIRE_HANDOFF {
    uuid id PK
    uuid application_id FK
    int payload_version
    text people_status
    uuid employment_id "soft hr"
  }
```

PII của ứng viên dùng subject key riêng cho từng ứng viên, nên việc hết hạn lưu trữ 12 tháng là thao tác hủy khóa cộng với ẩn danh hóa (NFR-REC-009). Không có việc so khớp ứng viên giữa các tenant.

---

## 8. Lưu trữ dữ liệu lương: bất biến, tái lập, lưu trữ dài hạn (ADR-0029)

| Vấn đề | Thiết kế |
|---|---|
| Đầu vào | Tại `INPUT_CUTOFF`/khi tính toán, mọi đầu vào của mọi nhân viên được **đóng băng** vào `pay.run_input_snapshot.payload` (`jsonb`, TOAST `lz4`). Payload chứa hồ sơ lương, các đoạn lương kèm id bản ghi và `recorded_at`, các đoạn phân công, các dòng kết quả chấm công (kỳ, phiên bản, checksum), đầu vào biến đổi, khoản vay, số người phụ thuộc theo tháng, số dư lũy kế từ đầu năm (YTD), phân bổ tài khoản ngân hàng, tình trạng cư trú, và lịch sử BHXH cần cho việc áp trần. `payload_sha256` được tính trên JSON chuẩn hóa. |
| Cấu hình | `pay.run_config_snapshot` (mỗi lần chạy một bản) giữ các phiên bản thành phần lương đã phân giải, phiên bản công thức (văn bản biểu thức và hash), các dòng cấu trúc lương, quy tắc làm tròn, và **tập giá trị pháp lý đã phân giải** cho kỳ (pack id + override version + chính các giá trị đó). Các cấu hình giống hệt nhau giữa các lần chạy được loại trùng theo `config_sha256`, và lần chạy tham chiếu tới nó. |
| Code | `payroll_run.code_version` = release tag của payroll engine cộng với phiên bản formula-sandbox. Các bản release được giữ dưới dạng container image trong 10 năm **[spike: chính sách lưu giữ/ký quỹ image]**. |
| Kết quả | `run_employee` (header, tổng, `result_sha256`) và `result_line` (mỗi thành phần × đoạn một dòng). |
| Luồng ghi (SA ADR-0009) | Khi lần chạy đang ở `SNAPSHOTTING/CALCULATING`, các chunk worker ghi `run_employee` và `result_line` bằng `COPY` (xóa-và-ghi-lại idempotent theo từng chunk, khóa theo `run_id` + số thứ tự chunk). Snapshot được ghi một lần, trước khi bắt đầu tính toán. |
| Tính bất biến | Trigger `pay.tg_run_immutable` chặn INSERT/UPDATE/DELETE trên snapshot, run_employee và result_line khi lần chạy đã ở `APPROVED` hoặc trạng thái sau đó. Đường duy nhất được phép là `UNLOCKED` (đặc quyền, cần phê duyệt lần hai), lưu kết quả trước đó vào `pay.run_result_history` (cùng cấu trúc, khóa theo `unlock_seq`) trước khi tính lại. Không có gì đã duyệt bị mất. |
| Tái lập | Service "replay" nạp một config snapshot và một input snapshot, chạy engine phiên bản `code_version`, và so sánh hash với `result_sha256`. Một job định kỳ hằng tháng replay mẫu 0.5 % các lần chạy của năm trước (phát hiện sai lệch bằng golden-file, NFR-PAY-003/008). |
| Partitioning | `result_line` được range-partition theo `period_month` (theo tháng). PK `(tenant_id, id, period_month)`; index `(tenant_id, run_employee_id)` trên mỗi partition. |
| Khối lượng | 1M nhân viên × ~60 dòng × ~1.2 lần chạy mỗi tháng ≈ **72M dòng/tháng**, khoảng 0.86B dòng/năm, với ~150 B/dòng kể cả index ≈ 11 GB/tháng. |
| Lưu trữ (10 năm, BR-PAY-123) | Hot: 36 partition tháng online. Warm (tháng 37–120): mỗi tháng được **nén gọn** vào `pay.result_archive` (mỗi `run_employee` một dòng, `lines jsonb` nén lz4, nhỏ hơn khoảng 8×), sau đó partition dòng được detach và drop. Phiếu lương, tờ khai, snapshot, header lần chạy và `si_contribution`/`pit_withholding` được giữ online đủ 10 năm (khối lượng thấp). Sau 10 năm cộng thời điểm kết thúc employment, retention scheduler sẽ purge hoặc ẩn danh hóa **[verify-legal]**. |
| Báo cáo | `fact_payroll_line` trong reporting store giữ đủ 10 năm ở dạng cột (§11), nên phân tích không bao giờ chạm vào kho lưu trữ. |

`si_contribution` (mỗi employment × tháng × chế độ một dòng) là master **lịch sử đóng BHXH** (gap §5 #20). Nó cũng chứa các tháng trước go-live được nạp bởi template `OB_SI_HISTORY` (từ sổ BHXH của nhân viên / bảng C12 của VSS) với `status = 'OPENING'` và `import_job_id` (soft reference tới `core.import_job`), ADR-0037.

---

## 9. Outbox/inbox, audit, bảo vệ PII

### 9.1 Transactional outbox và inbox (ADR-0028)

Mỗi schema của module có cùng một cặp bảng (template DDL trong `002_core.sql`, procedure `sys.create_outbox_inbox(schema)`):

| Bảng | Cột chính | Ghi chú |
|---|---|---|
| `<schema>.outbox_event` | `id` (= `event_id` của envelope, UUIDv7), `tenant_id`, `legal_entity_id`, `aggregate_type`, `aggregate_id`, `event_type`, `schema_version`, `occurred_at`, `effective_date`, `correlation_id`, `causation_id`, `is_retro`, `person_id`, `employment_id`, `payload jsonb`, `headers jsonb`, `created_at`, `published_at` | Envelope như trong gap §6. Được insert trong **cùng transaction** với thay đổi nghiệp vụ (NFR-PPL-002). Range-partition **theo ngày** theo `created_at`. Partition cũ hơn 7 ngày bị drop, nên không có `DELETE` và không có vacuum churn. |
| `<schema>.inbox_message` | PK `(consumer, event_id)`, `tenant_id`, `event_type`, `received_at`, `processed_at`, `status`, `attempts`, `last_error` | Tiêu thụ idempotent: handler insert dòng inbox và áp dụng hiệu ứng của nó trong một transaction, và bản trùng sẽ vướng PK. Partition theo tháng, lưu 90 ngày. |

* Relay (SA ADR-0004 rev. 2): một **polling relay** trong ứng dụng chạy ở `hrm-worker` thực thi `SELECT … WHERE published_at IS NULL ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 500` (partial index `outbox_event_unpublished_ix`) qua kết nối trực tiếp (không qua PgBouncer) và thiết lập `published_at`. **Phase 1 (không có Kafka, R2-01):** trong cùng transaction nó gọi `saas.enqueue_event_delivery()`, hàm này insert một dòng `saas.job` cho mỗi consumer đã đăng ký (queue `events`, type `bus.Deliver`, `idempotency_key = consumer:event_id`, `ordering_key = consumer:aggregate_id`). `saas.claim_jobs()` chạy tối đa một job chưa kết thúc cho mỗi ordering key, cũ nhất trước, nên mỗi consumer thấy các event của một aggregate theo đúng thứ tự; retry, backoff, `DEAD` (trạng thái dead-letter ở Phase 1), replay bởi operator và chia sẻ công bằng giữa các tenant đến từ job framework. Payload của job chỉ là một con trỏ (cluster nguồn, schema, `event_id`, `created_at`); delivery worker đọc event qua kết nối relay và chạy handler của consumer trong transaction của module tiêu thụ với role và inbox riêng của nó. Các platform event của platform cluster (ví dụ `saas.LegalParameterPackPublished`) được relay enqueue trên từng pool cluster với sentinel tenant. **Phase 2:** relay publish lên Kafka với key `tenant_id:aggregate_id` thay vào đó; bảng và code consumer không đổi. Một delivery ở trạng thái `DEAD` phải được replay trong cửa sổ 7 ngày của outbox, nếu không consumer sẽ re-sync từ API của module sở hữu. Các dòng đã publish được giữ 7 ngày để có thể publish lại sau DR, khớp với việc partition theo ngày bị drop sau 7 ngày. Debezium **không** nằm trên luồng nghiệp vụ. Cờ `initial_load` của envelope (import số dư đầu kỳ, ADR-0037; trước đây là `migration`) được truyền trong `headers`.
* Role relay `yhrm_relay` đọc mọi tenant qua một policy riêng `USING (true)`. Các role ứng dụng chỉ thấy tenant của mình.
* PII trong payload được tối thiểu hóa (id cộng với *tên* các trường thay đổi). Các giá trị nhạy cảm (tài khoản ngân hàng, số giấy tờ định danh) không bao giờ nằm trong payload; consumer gọi API của module sở hữu (gap §6 `PersonalDataChanged` "minimised").

### 9.2 Audit log (ADR-0027)

* `audit.audit_event`: range partition theo tháng trên `occurred_at`; PK `(tenant_id, id, occurred_at)`. Các cột: loại/id của actor, `on_behalf_of_id`, `support_grant_id`, `operator_id`, hành động, schema/loại/id của đối tượng, `legal_entity_id`, `purpose`, `request_id`, `correlation_id`, `client_ip`, `user_agent`, `change jsonb` (diff trước/sau đã che), `is_sensitive_read`, cộng với các cột seal `chain_seq`, `prev_hash`, `row_hash`, `sealed_at`.
* **Ghi** diễn ra trong transaction nghiệp vụ: tường minh bởi service (ngữ nghĩa nghiệp vụ, các lần đọc nhạy cảm) và bởi trigger dòng chung `audit.tg_row_audit` trên các bảng cấu hình và HR. Cột mã hóa chỉ xuất hiện dưới dạng `"<encrypted:changed>"`.
* **Bằng chứng chống giả mạo:** việc seal là **bất đồng bộ** để tránh tuần tự hóa mọi transaction nghiệp vụ trên chain head của từng tenant. `audit.seal_batch(tenant)`, do `yhrm_audit_sealer` chạy vài giây một lần, lock `audit.chain_head(tenant)`, lấy các dòng chưa seal cũ hơn 2 s theo thứ tự `id`, và gán `chain_seq = last + 1` và `row_hash = sha256(prev_hash || canonical(row))`. Thứ tự chuỗi là thứ tự seal. Một dòng commit muộn đơn giản sẽ được seal muộn hơn.
* **Anchoring:** hằng ngày, chain head của mỗi tenant được ghi vào `audit.chain_anchor` và vào một bucket WORM (object-lock) ở data centre thứ hai tại VN. `audit.verify_chain(tenant, from_seq, to_seq)` tính lại hash. Mọi giả mạo trong DB đều phá vỡ hoặc chuỗi hoặc anchor.
* Grant: module chỉ có `INSERT`; sealer chỉ có thể cập nhật các cột seal (grant ở mức cột); không ai có `DELETE`. Partition được lưu trữ (detach → Parquet trong WORM storage) sau 24 tháng hot và giữ trong 10 năm (các hành động liên quan đến lương). Mục tiêu truy vấn: 1 năm cho một bản ghi ≤ 3 s (NFR-CORE-008), qua index `(tenant_id, object_type, object_id, occurred_at)` trên mỗi partition.
* Các event chỉ thuộc platform (operator đăng nhập, thay đổi gói) đi vào `saas.platform_audit_event` với cùng cơ chế chuỗi, dùng sentinel tenant.

### 9.3 Phân loại và mã hóa PII (ADR-0026)

| Class | Ví dụ | Bảo vệ |
|---|---|---|
| **P3 — định danh, tài khoản ngân hàng, dữ liệu nhạy cảm đặc biệt** | Số CCCD/hộ chiếu, MST cá nhân, số sổ BHXH, số tài khoản ngân hàng, tôn giáo, dân tộc, dữ liệu sức khỏe/y tế, khuyết tật, tọa độ GPS và tham chiếu ảnh selfie (chỉ Phase 2, OD-9), số định danh và MST của thành viên gia đình, giấy tờ định danh của ứng viên, các dòng import ở staging (`core.import_row.payload_enc`), mật khẩu PDF phiếu lương do nhân viên đặt (`pay.payslip_delivery_preference.pdf_password_enc`, R2-17) | **Envelope encryption ở tầng ứng dụng** (AES-256-GCM) trong `_enc bytea`. Blind index `_bidx` (HMAC-SHA256 với blind-index key của tenant, cắt còn 16 byte) ở nơi cần tra cứu bằng. Gợi ý hiển thị `_last4` ở nơi UI che dữ liệu. Truy cập được ghi log như một lần đọc nhạy cảm. |
| **P2 — thông tin cá nhân / lương bảo mật** | Số tiền lương (hồ sơ lương, dòng kết quả, phiếu lương), ngày sinh, địa chỉ, điện thoại, e-mail cá nhân, tên người phụ thuộc, kỷ luật | Plaintext trong DB: được bảo vệ bởi RLS + grant theo module + mã hóa lưu trữ (AES-256 ở mức volume/tương đương TDE) + **masking view** + phân quyền trường trong Core + audit. **Số tiền lương là P2 và không được mã hóa ở mức cột (quyết định X-01, 2026-10-16; đóng D-02/D-11).** Các biện pháp kiểm soát bù trừ, tất cả đều bắt buộc: RLS; chỉ `yhrm_app_hr`/`yhrm_app_pay` có grant trên các bảng số tiền (không bảng nào nằm trong danh sách X-02); masking view ẩn số tiền trừ khi `app.mask_level` ∈ (`NONE`, `PII`), fail closed khi chưa thiết lập (`hr.v_salary_record_masked`, các view tương đương bên payroll); che trường ở API; step-up cho view/xuất dữ liệu hàng loạt; audit đọc nhạy cảm; `people.SalaryChanged` chỉ được gửi tới consumer Payroll (bus job ở Phase 1 không mang payload; topic hạn chế bằng ACL ở Phase 2); credential động của OpenBao, không ai có quyền truy cập thủ công vào app credential, và pgaudit trên các DB session (R2-15); số tiền trong reporting store chỉ truy cập được qua reporting service (§11). |
| **P1 — nội bộ** | Họ tên, mã nhân viên, tổ chức, vị trí, e-mail công việc | RLS + grant. |
| **P0 — công khai/tham chiếu** | Master data toàn cục | không có |

Phân loại được đăng ký cho từng cột trong danh mục dữ liệu cá nhân (PD inventory) của SaaS (gap §5 #27) qua `COMMENT ON COLUMN … IS 'pii:P3 …'`. Một kiểm tra CI phân tích các comment và báo lỗi khi một cột mới không có phân loại.

**Phân cấp khóa:**

```
OpenBao transit (in VN; software root for the MVP, HSM-backed before the Enterprise tier, OD-10; SA ADR-0017) ─ tenant KEK (one per tenant, per purpose, rotatable; ref in saas.tenant_key)
   └─ wraps ─ subject DEK (one per data subject: person / candidate / casual payee; saas.subject_key.wrapped_dek)
          └─ encrypts ─ every P3 column of that subject (ciphertext header = key id + key version + nonce)
   └─ derives ─ tenant blind-index key (HKDF, never stored in plaintext)
   └─ wraps ─ import-job DEK (core.import_job.wrapped_dek) ─ encrypts staged rows; destroyed 30 days after commit
```

* **Tinh chỉnh so với SA ADR-0017:** SA mô tả DEK theo tenant được wrap bởi KEK của tenant. Thiết kế này thêm một cấp, **DEK theo từng chủ thể dữ liệu (per-subject DEK)**, để có thể crypto-shred một người hoặc một ứng viên mà không phải mã hóa lại toàn bộ tenant. Cấp KEK không đổi.
* **pgcrypto so với mã hóa ở tầng ứng dụng:** chọn tầng ứng dụng. Với pgcrypto, khóa sẽ đi trong văn bản SQL và xuất hiện trong `pg_stat_statements`/log, tiến trình DB sẽ thấy plaintext, và KMS theo tenant rất khó áp dụng. Envelope encryption ở tầng ứng dụng giữ khóa ngoài database và khiến crypto-shredding có ý nghĩa thực sự. `pgcrypto` vẫn được dùng cho `digest()` (hash của audit) và `gen_random_bytes()`.
* Chi phí: lần chạy lương chỉ giải mã những gì cần (tài khoản ngân hàng khi tạo file ngân hàng, MST cho tờ khai). Unwrap subject DEK là AES cục bộ với KEK của tenant đã cache, khoảng 10k lần unwrap trong < 1 s **[spike: benchmark với KMS được chọn + cache TTL]**.
* **Crypto-shredding:**
  * Mức chủ thể (xóa theo DSR, hết hạn lưu trữ ứng viên, hết thời hạn lưu trữ 10 năm): đặt `wrapped_dek = NULL`, `destroyed_at = now()`. Ciphertext trong DB, replica, reporting store và file xuất lập tức không đọc được. Backup vẫn chứa DEK đã wrap và hết hạn trong vòng 35 ngày, điều này được thông báo bằng văn bản cho DPO.
  * Mức tenant: lên lịch cho KMS hủy KEK của tenant. Mọi dữ liệu P3 của tenant, kể cả backup, trở nên không đọc được.
* Xoay vòng khóa: xoay vòng KEK sẽ wrap lại các DEK (chi phí thấp). Xoay vòng DEK mã hóa lại các cột của chủ thể theo kiểu lazy khi ghi, và một background job hoàn tất trong vòng 90 ngày.
* Tìm kiếm trên giá trị mã hóa chỉ hỗ trợ so khớp bằng (blind index). Không hỗ trợ tìm kiếm một phần hay tìm gần đúng trên số CCCD hoặc số tài khoản ngân hàng, điều này được chấp nhận theo thiết kế.

### 9.4 Masking view

* Các view `<schema>.v_<table>_masked` hiển thị gợi ý `_last4`, đặt NULL cho các cột `_enc` và làm tròn hoặc ẩn số tiền P2 theo cờ session `app.mask_level` (`NONE`, `PII`, `PII_AND_PAY`) được thiết lập cho mỗi transaction từ phân quyền trường của user hoặc từ support grant (BR-SAAS-034). Đây là các view với `security_barrier = true` và `security_invoker = true` (PG15+), nên RLS vẫn áp dụng cho bên gọi. Vì số tiền lương không được mã hóa (X-01), các view lương fail closed: `app.mask_level` chưa thiết lập sẽ ẩn số tiền (`hr.v_salary_record_masked` trong `003_hr.sql`; các view `pay` theo cùng template), trong khi các view PII mặc định là `PII`.
* Các bản sao ngoài production (sandbox từ production, BR-SAAS-031) dùng **masking pipeline**: các cột P3 được mã hóa lại bằng khóa của sandbox tenant sau khi thay thế bằng dữ liệu giả có tính xác định, và tên cùng địa chỉ P2 được thay bằng tên tiếng Việt tổng hợp. Không có gì từ khóa production đến được môi trường ngoài production.

---

## 10. Partitioning, khối lượng, index, vacuum, lưu trữ (ADR-0030)

### 10.1 Ước tính khối lượng (kịch bản định cỡ: 500 tenant, 1,000,000 nhân viên; mục tiêu khi ra mắt là 200k = 1/5)

| Bảng | Yếu tố quyết định | Dòng / tháng | Dòng / năm | Dung lượng / năm (gồm idx) | Partitioning | Thời gian lưu hot trong OLTP |
|---|---|---|---|---|---|---|
| `att.raw_punch` | 1M × 4 lượt chấm công × 26 ngày | 104M | 1.25B | ~360 GB | range theo tháng trên `work_date_hint` | 25 tháng, sau đó lưu trữ (Parquet + manifest), khôi phục được trong ~2 ngày làm việc (ngưỡng đã chấp nhận, D-03) |
| `att.daily_timesheet` | 1M × 30 ngày | 30M | 360M | ~150 GB | range theo tháng trên `work_date` | 37 tháng, sau đó lưu trữ dạng nén gọn |
| `att.attendance_result_line` | 1M × ~15 loại × 1.3 phiên bản | 20M | 240M | ~50 GB | range theo tháng trên `period_start` | 10 năm (dòng hẹp) **[spike]** |
| `att.leave_balance_ledger` | ~3 giao dịch / nhân viên / tháng | 3M | 36M | ~8 GB | không có (index trên tenant, employment) | 10 năm |
| `pay.result_line` | 1M × 60 × 1.2 | 72M | 864M | ~130 GB | range theo tháng trên `period_month` | 36 tháng, sau đó `result_archive` |
| `pay.run_input_snapshot` | 1M × 1.2 × ~8 KB đã nén | 1.2M | 14M | ~110 GB | range theo năm trên `period_month` | 10 năm |
| `audit.audit_event` | ~40 event / nhân viên / tháng | 40M | 480M | ~250 GB | range theo tháng | 24 tháng hot, 10 năm lưu trữ |
| `*.outbox_event` | ~2× số lần ghi nghiệp vụ | ~60M | — | giữ ≤ 7 ngày | range theo ngày | 7 ngày |
| `saas.usage_record` | theo tenant × chỉ số × ngày | 0.5M | 6M | nhỏ | theo tháng | 25 tháng |

Tốc độ ghi đỉnh: các con số chấm công của X-05 (**duy trì 500/s toàn platform và 50/s mỗi tenant**, đột biến 1,000/s; đáp ứng 20,000/phút ≈ 333/s của NFR-SAAS-004) là mục tiêu của **Phase 2**, cùng với device connector trực tiếp và SP-3 (bị hoãn theo R2-01). Thiết kế bảng (append-only, partition theo tháng, batch `ON CONFLICT DO NOTHING`) được giữ nguyên để Phase 2 không cần mô hình hóa lại. Lượt chấm công ở Phase 1 chỉ đến từ các file xuất từ máy chấm công được import: một file của một địa điểm 2,000 nhân viên trong một tháng có khoảng 250k dòng, được import job commit theo từng chunk, nằm thoải mái trong khả năng insert theo batch. Payroll ghi 10k nhân viên × 60 dòng = 600k dòng trong ≤ 15 phút mỗi lần chạy, dùng batch insert kiểu `COPY` từ các worker (JDBC `reWriteBatchedInserts=true`).

Trên toàn platform, 1M nhân viên nghĩa là khoảng 1.1 TB/năm dữ liệu hot mới. Theo SA ADR-0003, **mỗi pool cluster chứa khoảng 100k nhân viên**, nên kịch bản định cỡ là khoảng 10 pool cluster (khi ra mắt: 2). Mỗi pool cluster tương ứng khoảng 110 GB/năm dữ liệu hot và đỉnh khoảng 100 lượt chấm công/s (một cluster 100k nhân viên vẫn có thể nhận toàn bộ đỉnh 500/s của platform khi đổi ca trong vùng; consumer theo batch sẽ hấp thụ). Một CNPG primary (16 vCPU / 128 GB, NVMe) cộng một sync standby là thoải mái ở quy mô đó. Mở rộng bằng cách **thêm pool cluster (nhóm tenant)**, được định tuyến giống dedicated tier, không bao giờ bằng cách chia bảng qua nhiều cluster. **[spike: SP-2 hiệu năng RLS và load test trên bộ dữ liệu tham chiếu trước khi go-live pilot]**

### 10.2 Quy tắc

* Quản lý partition: `pg_partman` 5.x nếu dịch vụ managed cho phép; nếu không thì dùng `sys.create_range_partitions()` đi kèm, do SaaS scheduler chạy. Partition được tạo trước 3 kỳ. Không có partition `DEFAULT` trên các bảng hot, vì dòng nằm ngoài khoảng phải báo lỗi rõ ràng. Dữ liệu số dư đầu kỳ (ADR-0037) chỉ đi vào các bảng không partition (`pay.ytd_balance`, `pay.si_contribution`, `att.leave_balance_ledger`, `pay.loan`), nên một lần go-live không bao giờ cần partition lùi ngày.
* Index trên bảng partition được tạo trên bảng cha (lan truyền xuống). Index mới trên bảng lớn được build cho từng partition bằng `CONCURRENTLY`, rồi attach.
* **Autovacuum:** các bảng append-only (`raw_punch`, `audit_event`, `result_line`) đặt `autovacuum_vacuum_insert_scale_factor = 0.05` và freeze mạnh khi partition đã đóng (`VACUUM (FREEZE)` khi đóng tháng). Các bảng update nhiều (`daily_timesheet`, `employee_pay_profile`, `ot_cap_ledger`) dùng `fillfactor = 80`, `autovacuum_vacuum_scale_factor = 0.02`, `autovacuum_analyze_scale_factor = 0.01`. Thiết lập toàn cục: `autovacuum_max_workers = 6`, `maintenance_work_mem = 2GB`.
* Các partition đã đóng cũ hơn cửa sổ hot được detach (`DETACH PARTITION … CONCURRENTLY`), xuất ra Parquet (thư mục phân theo tenant, mã hóa bằng KEK của tenant, trong object storage tại VN có object lock) và drop. Bảng manifest `saas.archive_manifest` ghi lại URI, số dòng và checksum. Khôi phục bằng cách attach lại vào một schema tạm khi có yêu cầu (DSR, audit, tranh chấp).
* Chính sách index: mọi FK đều có index. Danh sách dùng `(tenant_id, <filter>, <sort>)`. Partial index cho các dòng "hiện hành" (`WHERE superseded_at IS NULL`, `WHERE status = 'ACTIVE'`). GIN trigram trên tên đã bỏ dấu. Không tạo index chỉ trên các cờ có số lượng giá trị phân biệt thấp.

---

## 11. Mô hình dữ liệu báo cáo (ADR-0032)

Công nghệ (SA ADR-0012, sửa đổi cho Phase 1 theo R2-01): một **reporting store PostgreSQL 17 riêng biệt** tại VN. **Phase 1:** được cấp dữ liệu bằng **native logical replication** — mỗi schema module một publication trên mỗi OLTP cluster, với **column list** (PG 15+) loại bỏ mọi cột P3 `_enc`/`_bidx` và chỉ gồm các bảng mà star schema cần; reporting DB subscribe (role `yhrm_cdc`, slot trên standby nếu nhà cung cấp hỗ trợ standby decoding của PG 16+ **[spike SP-6]**) vào `rpt_raw`. Không có Kafka, không có Kafka Connect. Thay đổi DDL trên các bảng đã publish tuân theo cùng quy tắc expand/contract cộng thêm một bước migration cho `rpt_raw` (logical replication không mang DDL). `rpt_raw` là vùng tiếp nhận không có RLS (apply worker ghi với quyền subscription owner), chỉ role transformation đọc được; user và reporting service chỉ đọc `rpt`, nơi có `tenant_id` + RLS. **Phương án Phase 2:** Debezium trên standby → Kafka `cdc.*` → loader, nếu cần streaming transform hoặc columnar engine. Độ tươi ≤ 15 phút (thông thường ≤ 2 phút; BR-SAAS-047). Superset (P2) kết nối với RLS. Các bảng dimensional nằm trong `rpt`, các bảng replicate thô trong `rpt_raw`, và các phép biến đổi được viết dưới dạng SQL model (kiểu dbt) do scheduler chạy. Nếu khối lượng sau 12 tháng vượt quá mức mà PostgreSQL thông thường phục vụ được trong 3 s (NFR-SAAS-003), cùng star schema đó sẽ được triển khai trên một columnar engine **[spike: Citus columnar hoặc ClickHouse host tại VN, với fact_payroll_line 1B dòng]**.

```mermaid
erDiagram
  DIM_DATE ||--o{ FACT_HEADCOUNT_DAILY : on
  DIM_EMPLOYEE ||--o{ FACT_HEADCOUNT_DAILY : counts
  DIM_ORG_UNIT ||--o{ FACT_HEADCOUNT_DAILY : in
  DIM_LEGAL_ENTITY ||--o{ FACT_HEADCOUNT_DAILY : in
  DIM_EMPLOYEE ||--o{ FACT_MOVEMENT : moves
  DIM_EMPLOYEE ||--o{ FACT_PAYROLL_LINE : paid
  DIM_PAY_COMPONENT ||--o{ FACT_PAYROLL_LINE : component
  DIM_PAY_RUN ||--o{ FACT_PAYROLL_LINE : run
  DIM_EMPLOYEE ||--o{ FACT_ATTENDANCE_DAILY : worked
  DIM_ATT_CODE ||--o{ FACT_ATTENDANCE_DAILY : code
  DIM_EMPLOYEE ||--o{ FACT_LEAVE : takes
  FACT_HEADCOUNT_DAILY {
    uuid tenant_id
    int date_key
    bigint employee_sk
    bigint org_unit_sk
    bigint legal_entity_sk
    bigint position_sk
    numeric fte
    text employment_status
    bool is_billable
  }
  FACT_PAYROLL_LINE {
    uuid tenant_id
    int period_key
    bigint employee_sk
    bigint component_sk
    bigint run_sk
    bigint cost_centre_sk
    numeric amount
    numeric taxable_amount
    numeric si_basis_amount
  }
  FACT_ATTENDANCE_DAILY {
    uuid tenant_id
    int date_key
    bigint employee_sk
    bigint att_code_sk
    numeric worked_hours
    numeric ot_hours
    int late_min
  }
  DIM_EMPLOYEE {
    bigint employee_sk PK
    uuid tenant_id
    uuid employment_id
    text employee_no
    text full_name
    text gender_code
    int birth_year
    date scd_valid_from
    date scd_valid_to
  }
```

* Fact: `fact_headcount_daily` (snapshot định kỳ), `fact_movement` (tuyển dụng, nghỉ việc, điều chuyển, thăng chức, kèm lý do), `fact_payroll_line` và `fact_payroll_employee_month` (tổng hợp), `fact_attendance_daily`, `fact_ot`, `fact_leave`, `fact_si_contribution`, `fact_recruitment_funnel` (P2).
* Dimension: SCD2 cho `dim_employee`, `dim_org_unit` (có `path`), `dim_position`, `dim_legal_entity`; SCD1 cho `dim_pay_component`, `dim_att_code`, `dim_leave_type`, `dim_location`, `dim_cost_centre`; cộng với `dim_date` và `dim_pay_run`.
* **Cô lập tenant:** `tenant_id` có mặt trên mọi fact và dimension và là cột đứng đầu của mọi index và của partitioning (fact được partition theo tháng). Cùng template RLS và context `app.tenant_id` được áp dụng, và reporting service thiết lập context giống hệt OLTP. **Data scope** lấy từ bảng `report_scope(tenant_id, user_account_id, employment_id)` được duy trì từ các snapshot phân quyền của Core (SA ADR-0006/0012). Mọi truy vấn fact đều join với nó theo `employment_id` (index `(tenant_id, user_account_id, employment_id)`), và `dim_employee` mang `employment_id` để phục vụ phép join đó. Tenant ở dedicated tier có reporting DB riêng.
* **PII:** không có dữ liệu P3 trong reporting store (không có CCCD, MST hay tài khoản ngân hàng). Họ tên được giữ (P1). Số tiền là P2 và chỉ hiển thị qua reporting service có kiểm tra quyền xem lương. Crypto-shredding: bộ ẩn danh hóa DSR cập nhật các thuộc tính tên trong `dim_employee`, và purge tenant xóa theo `tenant_id`.
* **Tính phí (D-10, quyết định 2026-10-16):** đơn vị tính phí là **một người đang làm việc vào cuối tháng, theo từng pháp nhân**. `is_billable` vào ngày cuối tháng chỉ đúng với employment có trạng thái `ACTIVE` vào ngày đó. **`SUSPENDED` không tính phí (R2-12a, quyết định 2026-10-16)**; `PRE_HIRE`, `CANCELLED` hay `TERMINATED` có `end_date` trước ngày cuối tháng cũng không. Một người có một employment `ACTIVE` và một employment `SUSPENDED` trong cùng pháp nhân được tính phí một lần cho pháp nhân đó. Job usage hằng tháng đếm `DISTINCT (legal_entity_id, person_id)` có `is_billable` vào ngày đó, nên các phân công đồng thời trong một pháp nhân chỉ tính một lần, và một người làm việc cho hai pháp nhân của tenant được tính hai lần (D-10 giữ nguyên; R2-12b chưa được trả lời, nhật ký quyết định §7.2). Kết quả được ghi vào `saas.usage_record` (chỉ số `BILLABLE_PERSON`, theo tenant × pháp nhân × tháng) và được đóng băng khi hóa đơn đã phát hành; các trường hợp tuyển dụng/chấm dứt lùi ngày sau đó sẽ điều chỉnh vào hóa đơn kế tiếp, không bao giờ điều chỉnh hóa đơn đã phát hành.

---

## 12. Công cụ migration schema (ADR-0031)

* **Flyway Community** (SQL-first; giấy phép Community được chấp nhận 2026-10-16, đóng D-08), do CI/CD pipeline chạy dưới `yhrm_migrator` với `initSql = SET ROLE yhrm_owner`. Liquibase đã được cân nhắc (xem ADR); SQL-first phù hợp với một sản phẩm chỉ dùng PostgreSQL và sử dụng nhiều RLS, partitioning, exclusion constraint và function.
* Bố cục: `backend/<module>/src/main/resources/db/migration/<schema>/V<yyyymmddHHMM>__<desc>.sql` cộng với `R__<object>.sql` (repeatable migration cho view, function và policy). Có **một bảng lịch sử Flyway cho mỗi schema** (`<schema>.flyway_schema_history`), nên các module migrate độc lập với nhau. Thứ tự: `sys` → `saas` → `core`/`audit` → `hr` → `att` → `pay` → `rec`. Các file trong `database/postgresql/ddl/` là baseline thiết kế và trở thành `V1__baseline.sql` của từng module.
* **Forward-only:** không có down migration. Rollback = deploy phiên bản ứng dụng trước đó, điều này khả thi nhờ expand/contract.
* **Expand/contract** (NFR-SAAS-014): release N thêm mới (cột nullable, bảng mới, ghi kép); một backfill job chạy theo batch cho từng tenant với `set_config`; release N+1 chuyển luồng đọc; release N+2 drop cấu trúc cũ. Các rule lint (được thực thi bởi một SQL linter trong CI, `squawk`): `SET lock_timeout = '3s'` và `statement_timeout` trong mỗi migration; `CREATE INDEX CONCURRENTLY` (với `executeInTransaction=false`); `ADD CONSTRAINT … NOT VALID` rồi `VALIDATE CONSTRAINT`; không `ALTER COLUMN TYPE` trên bảng lớn; không đổi tên cột (thay vào đó: thêm + sao chép + drop); `NOT NULL` qua `CHECK (… IS NOT NULL) NOT VALID` + validate.
* **Fleet:** migration orchestrator áp dụng cùng một artifact cho các shared pool và cho mọi DB dedicated theo từng vòng (nội bộ → pilot → shared → dedicated). Database từ chối các phiên bản ứng dụng yêu cầu phiên bản schema cao hơn phiên bản đã áp dụng (kiểm tra khi khởi động dựa trên lịch sử Flyway).
* **Seed dữ liệu theo tenant là dữ liệu, không phải schema.** Provisioning (BR-SAAS-003, ≤ 10 phút) gọi các seed API của module để tạo các override danh mục mã của tenant, role mặc định, template workflow, loại nghỉ phép, template danh mục thành phần lương, và lịch ngày lễ sao chép từ template quốc gia. Dữ liệu tham chiếu toàn cục được phát hành bởi **release job** (`core.MasterDataReleased`, legal pack). Nó không bao giờ nằm trong Flyway, ngoại trừ các mã cấu trúc ban đầu mà constraint cần.
* Kiểm thử: Testcontainers PostgreSQL 16 chạy mọi migration từ đầu, cộng với nâng cấp từ snapshot của bản release gần nhất. **Bộ test RLS** tạo hai tenant và khẳng định rằng mỗi app role thấy 0 dòng chéo tenant trên mọi bảng (NFR-CORE-001). Một kiểm tra catalogue làm build thất bại khi bất kỳ bảng nào trong schema module thiếu `tenant_id` + RLS (trừ khi nằm trong allow-list). **Guard grant X-02** `sys.cross_schema_grant_violations()` phải trả về 0 dòng sau mỗi lần chạy migration (§2.3).

---

## 13. Import số dư đầu kỳ (ADR-0037; thay thế việc migrate từ Oracle, D-09)

**Không migrate từ Oracle** (D-09): YourHRM được xây dựng như một hệ thống mới và không chuyển lịch sử nào từ hệ thống cũ. Thiết kế v0.1 (migration database, các schema `stg_raw`/`stg_clean`/`mig`, bảng ánh xạ khóa và mã cũ, trích xuất nhất quán theo SCN, chạy song song) bị rút lại; ADR-0033 được thay thế bởi ADR-0037. Điều mà một lần go-live giữa năm vẫn cần là một **vị thế khởi đầu** chính xác, được nạp bằng một luồng import tổng quát, không phụ thuộc nguồn, mà mọi tenant mới đều dùng.

Chủ sở hữu: import engine của Core (GAP-005, BR-CORE-114) ở chế độ `OPENING_BALANCE`; mỗi template do module sở hữu dữ liệu chịu trách nhiệm, kiểm tra và commit. File của khách hàng lưu tại VN (object storage, prefix của tenant); nhà cung cấp không trích xuất gì từ hệ thống của khách hàng.

### 13.1 Pipeline và staging

```mermaid
flowchart LR
  X[Excel templates vi/en<br/>+ control sheet] -->|upload, malware scan, sha256| J[core.import_job<br/>mode OPENING_BALANCE, as_of_date G]
  J --> R[core.import_row<br/>payload_enc per-job DEK, preview]
  R --> V[Owner-module validators<br/>errors / warnings per row]
  V --> Q[core.import_reconciliation<br/>control vs staged]
  Q -->|all MATCHED or WAIVED + customer sign-off| C[Commit via owner ImportHandler<br/>hr / att / pay, chunked]
  C --> P[Post-commit check<br/>committed vs control]
```

| Bảng (DDL `002_core.sql`) | Mục đích | Quy tắc chính |
|---|---|---|
| `core.import_job` | Một lần upload của một template | `mode` (`STANDARD`/`OPENING_BALANCE`), `owner_module`, `template_version`, `as_of_date` (bắt buộc với số dư đầu kỳ), `tax_year`, `control_totals jsonb` (do khách hàng khai báo), `wrapped_dek` (DEK theo job), `depends_on_job_id` (master nhân viên trước), các cột ký xác nhận. CHECK: một job `OPENING_BALANCE` không thể sang `SIGNED_OFF`/`COMMITTING`/`COMMITTED` khi chưa có `signed_off_at` |
| `core.import_row` | Staging, mỗi dòng trong sheet một dòng | Toàn bộ dòng đã parse được mã hóa (`payload_enc`, AES-256-GCM, DEK của job) vì nó có thể chứa CCCD, MST hoặc số tài khoản ngân hàng; `preview jsonb` chỉ chứa các trường không nhạy cảm; `business_key` (employee_no, + tháng / loại nghỉ phép / số khoản vay) điều khiển upsert idempotent; các dòng trùng trong một file là lỗi kiểm tra dữ liệu, không phải lỗi constraint; `target_entity`/`target_id` sau khi commit |
| `core.import_reconciliation` | Báo cáo đối soát | Mỗi phép kiểm tra × mức chi tiết một dòng: `control_value`, `staged_value`, `committed_value`, `tolerance`, `status` (`MATCHED`/`MISMATCH`/`WAIVED`; miễn trừ cần có lý do và người thực hiện) |

Ranh giới module: Core không bao giờ đọc `hr`/`att`/`pay`. Module sở hữu đọc các dòng staging qua import API in-process của Core, tính giá trị staging và giá trị đã commit trong schema của chính nó (SPI `ReconciliationProvider`) rồi trả về. Không thêm grant giữa các schema nào (§2.3).

Lưu trữ: các dòng staging bị xóa và DEK của job bị hủy 30 ngày sau khi commit (`purge_due_at`); header của job và báo cáo đối soát được giữ làm bằng chứng go-live trong suốt thời hạn lưu trữ dữ liệu lương **[verify-legal]**.

### 13.2 Template và đích

| Loại import | Chủ sở hữu | Nội dung | Dòng đích |
|---|---|---|---|
| `OB_EMPLOYEE_MASTER` | People | Person (gồm CCCD, MST, số sổ BHXH), employment, phân công hiện tại, hồ sơ lương hiện tại (GROSS/NET), hợp đồng hiện tại, người phụ thuộc, tài khoản ngân hàng | `hr.*` với `source = 'OPENING_LOAD'` |
| `OB_YTD_PAYROLL` | Payroll | Theo nhân viên × tháng của năm tính thuế hiện tại trước G: tổng thu nhập, thu nhập chịu thuế, thuế TNCN đã khấu trừ, BHXH phần NLĐ/NSDLĐ, giảm trừ gia cảnh đã dùng, thực nhận | `pay.ytd_balance` (`source = 'OPENING'`) |
| `OB_SI_HISTORY` | Payroll | Theo nhân viên × tháng: mức đóng BHXH, chế độ, số tiền phần NLĐ/NSDLĐ, trạng thái đóng (sổ BHXH / VSS C12) | `pay.si_contribution` (`status = 'OPENING'`) |
| `OB_LEAVE_BALANCE` | T&A | Theo nhân viên × loại nghỉ phép × năm: số ngày được hưởng, đã nghỉ, chuyển sang, số dư cuối | `att.leave_balance_ledger` (`txn_type = 'OPENING'`) |
| `OB_LOAN` | Payroll | Theo nhân viên × khoản vay/tạm ứng: gốc, dư nợ, số kỳ trả còn lại | `pay.loan`, `pay.loan_schedule` |

Thứ tự: `OB_EMPLOYEE_MASTER` phải được commit trước; các template khác tham chiếu nhân viên theo `employee_no` và pháp nhân theo mã (nếu không thì kiểm tra dữ liệu thất bại). Địa chỉ cung cấp theo định dạng ba cấp trước năm 2025 được ánh xạ qua `core.admin_unit_mapping`.

### 13.3 "Số dư đầu kỳ tại ngày go-live" (thay thế "ngày đã biết" của lịch sử được migrate)

* Mọi job số dư đầu kỳ mang `as_of_date` = ngày go-live **G**.
* Master data được nạp dưới dạng **phiên bản có hiệu lực tại G**. `valid_from` là ngày bắt đầu thực tế từ template khi khách hàng biết (ngày vào làm, ngày hiệu lực của mức lương hiện tại), nếu không thì là G. Các phiên bản trước đó không được nạp.
* Các dòng bi-temporal (`hr.assignment`, `hr.salary_record`) có `recorded_at` = thời điểm nạp và `source = 'OPENING_LOAD'`. "Tại X, theo hiểu biết vào ngày Y" với Y trước thời điểm nạp sẽ trả về góc nhìn đầu kỳ; đây là một hạn chế được ghi nhận bằng văn bản.
* Lịch sử YTD và BHXH trước G là các đầu vào hằng tháng được tin cậy: không bao giờ tính lại, không bao giờ là đối tượng của retro. Lỗi phát hiện về sau được sửa bằng các điều chỉnh thủ công trong một lần chạy thường kỳ (có audit).
* Kỳ lương đầu tiên sau G dùng `pay.ytd_balance` (`OPENING`) + kết quả của chính nó cho thuế TNCN, nên quyết toán cuối năm 05/QTT và phép tính lương tháng 13/thưởng Tết thấy được toàn bộ năm tính thuế.
* Một job đã commit có thể được rollback bằng undo log của nó cho đến khi lần chạy lương đầu tiên của tenant được tính; sau đó chỉ có thể điều chỉnh.
* Event từ bước commit mang `initial_load = true` (consumer bỏ qua lời mời, thông báo và phát hiện retro; projection được re-sync); audit chạy ở chế độ `BATCH` với actor `IMPORT`.
* Diễn tập: toàn bộ pipeline chạy trước trong một **sandbox tenant** với cùng các file và phiên bản template.

### 13.4 Quy tắc kiểm tra dữ liệu (ví dụ; chặn trừ khi được miễn trừ)

Trùng người trong file hoặc trong tenant (cùng blind index CCCD, hoặc cùng họ tên + ngày sinh + điện thoại); thiếu hoặc sai CCCD (12 chữ số) hay MST (10/12 chữ số, hoặc CCCD dùng làm MST từ 2025 `[verify-legal]`); ngày vào làm sau ngày chấm dứt; hợp đồng chồng lấn; lương thấp hơn mức lương tối thiểu vùng tại G; mức đóng BHXH vượt trần của tháng; người phụ thuộc được hai nhân viên đăng ký; mã ngân hàng không có trong `core.bank`; đơn vị hành chính chưa được ánh xạ; số dư phép cuối kỳ thấp hơn −N ngày; dòng YTD hằng tháng cho các tháng từ G trở đi; số tiền BHXH phần NLĐ trong YTD khác với tổng số tiền phần NLĐ trong `OB_SI_HISTORY` cho cùng các tháng (kiểm tra chéo giữa các template); dư nợ khoản vay lớn hơn gốc.

### 13.5 Kiểm tra đối soát (cổng chặn trước khi commit; chạy lại sau khi commit)

| Kiểm tra | Mức chi tiết | Dung sai |
|---|---|---|
| Headcount | Pháp nhân × trạng thái × loại nhân viên, tại G | chính xác |
| Số dòng | theo template và sheet (số dòng = đã commit + bị từ chối + được miễn trừ) | chính xác |
| Lương YTD | nhân viên × tháng: tổng thu nhập, thu nhập chịu thuế, thuế TNCN đã khấu trừ, thực nhận; và tổng theo pháp nhân so với sheet kiểm soát | 0 VND |
| Thuế TNCN | nhân viên × năm tính thuế: thu nhập chịu thuế, giảm trừ gia cảnh (người phụ thuộc × số tháng), thuế đã khấu trừ | 0 VND |
| Lịch sử BHXH | nhân viên × tháng: mức đóng, phần NLĐ/NSDLĐ theo chế độ; số tháng đóng lũy kế | chính xác |
| Số dư nghỉ phép | nhân viên × loại nghỉ phép × năm: được hưởng, đã nghỉ, chuyển sang, số dư cuối | 0.0 ngày |
| Khoản vay/tạm ứng | nhân viên × khoản vay: dư nợ gốc | 0 VND |

Kết quả được ghi vào `core.import_reconciliation`; khách hàng ký xác nhận trong UI import (`import_job.signed_off_by/at`). Sau khi commit, module sở hữu tính lại `committed_value` từ các bảng đích của nó; mọi chênh lệch sẽ chặn lần chạy lương đầu tiên của tenant cho đến khi được giải quyết.

---

## 14. Backup, PITR, vận hành

* CloudNativePG với Barman Cloud (SA ADR-0017): base backup hằng ngày + lưu trữ WAL liên tục tới object storage của DC2 (`archive_timeout` 5 phút), cộng với một CNPG replica cluster ở DC2 có độ trễ vài giây, nên RPO ≤ 15 phút. Backup được mã hóa và giữ 35 ngày. Kiểm thử khôi phục chạy hằng quý (NFR-SAAS-006). Dedicated tier dùng cùng bộ công cụ với repository riêng.
* HA: 1 synchronous standby trong cùng DC (`synchronous_commit = remote_write` cho pool chứa payroll), cộng 1 asynchronous standby ở DC dự phòng (DR).
* Extension bắt buộc: `btree_gist`, `pgcrypto`, `pg_trgm`, `unaccent`, `ltree`, `pg_stat_statements`, `pgaudit`, và tùy chọn `pg_partman`. **[spike: xác nhận khả dụng trên dịch vụ managed PostgreSQL của nhà cung cấp cloud VN được chọn, hoặc tự quản lý trên VM/Kubernetes với CloudNativePG]**.
* Giám sát theo tenant: `pg_stat_statements` với comment tag `app.tenant_id` (prefix `/* tenant=… */` do JDBC interceptor thêm vào) để phát hiện tenant gây ồn (noisy tenant).

---

## 15. Quyết định, câu hỏi mở và rủi ro

### 15.1 Các quyết định kiến trúc dữ liệu (trạng thái 2026-10-16)

| # | Câu hỏi | Quyết định | Trạng thái |
|---|---|---|---|
| D-01 | Cái giá của việc **không có FK giữa các schema** (toàn vẹn eventual giữa các module, job đối soát) | Chấp nhận: không có FK giữa các schema (ADR-0022) | **Đã quyết định** (D-01) |
| D-02 | Phạm vi mã hóa: chỉ P3, hay cả số tiền lương | Chỉ P3; số tiền ở dạng plaintext kèm biện pháp kiểm soát bù trừ (§9.3, ADR-0026) | **Đã quyết định** (X-01) |
| D-03 | Cửa sổ lưu hot (lượt chấm công 25 tháng, bảng công 37, dòng kết quả 36), lưu trữ, khôi phục trong ~2 ngày làm việc | Chấp nhận (ADR-0030) | **Đã quyết định** (D-03) |
| D-04 | Thời hạn lưu trữ theo luật cho từng loại dữ liệu (10 năm cho dữ liệu lương; chấm công "10 năm sau khi chấm dứt employment") | Tư vấn pháp lý VN xác nhận `[verify-legal]` | Mở — pháp lý (nhật ký quyết định §3) |
| D-05 | Backup chứa dữ liệu P2 dạng plaintext trong ≤ 35 ngày sau khi xóa theo DSR | Chấp nhận và ghi nhận bằng văn bản; tuyên bố của DPO theo PDPL 2025 | Mở — DPO (nhật ký quyết định §3) |
| D-06 | Engine của reporting store (PostgreSQL hay columnar) | PostgreSQL trước; SP-6 | Mở — Sprint 3 |
| D-07 | Managed PostgreSQL tại VN hay tự quản lý (extension, logical replication, pg_partman, KMS) | Quyết định cùng với nhà cung cấp sau đợt đánh giá SP-7 kéo dài 2 tuần | Mở — theo sau OD-1 |
| D-08 | Phiên bản giấy phép Flyway | **Flyway Community** (ADR-0031) | **Đã quyết định** (nhật ký quyết định "Flyway") |
| D-09 | v0.1: lịch sử migrate từ Oracle và "ngày đã biết" của nó | **Không migrate từ Oracle.** Thay bằng import số dư đầu kỳ tổng quát; lịch sử trước go-live là góc nhìn đầu kỳ tại ngày go-live (§13, ADR-0037) | **Đã quyết định** (D-09); ADR-0033 bị thay thế |
| D-10 | Định nghĩa nhân viên tính phí | Người đang làm việc vào cuối tháng **theo từng pháp nhân**; một người ở hai pháp nhân được tính hai lần; `SUSPENDED` không tính phí (§11) | **Đã quyết định** (D-10; R2-12a) |
| D-11 | Xung đột SA/DA về mã hóa số tiền lương | Đã giải quyết: SA overview v0.2 không còn liệt kê số tiền lương là dữ liệu được mã hóa | **Đã đóng** (X-01) |
| D-12 | Role module đọc các bảng tham chiếu toàn cục của Core | Chấp nhận ngoại lệ chỉ-đọc, danh sách tường minh và guard DB/ArchUnit (§2.3, ADR-0021) | **Đã quyết định** (X-02); toàn bộ danh sách gồm các dòng override và grant hạ tầng được chấp nhận (R2-11) |
| D-13 | Làm việc đồng thời tại nhiều pháp nhân: mô hình cờ employment chính | `hr.primary_employment` theo ngày hiệu lực cho từng mục đích (giảm trừ gia cảnh thuế TNCN, BHXH), mỗi người tối đa một cho mỗi mục đích trong mỗi khoảng thời gian; thay thế `hr.employment.is_primary` (§7.3) | **Đã quyết định** (R2-16, hỗ trợ cơ bản ở Phase 1); quy tắc nhiều hợp đồng về BHXH `[verify-legal]` |
| D-14 | Cơ chế truyền event ở Phase 1 | Không có Kafka ở Phase 1: outbox + polling relay + delivery job trên `saas.job` với ordered lanes (§9.1); Kafka được bổ sung ở Phase 2 phía sau cùng outbox đó (SA ADR-0004 rev. 2) | Đề xuất (quyết định của SA, R2-01) |
| D-15 | Nguồn cấp dữ liệu cho reporting store ở Phase 1 | Native logical replication với column list thay cho Debezium → Kafka (§11; sửa đổi ADR-0012, ADR-0032) | Đề xuất (R2-01); SP-6 xác nhận standby decoding trên nhà cung cấp được chọn |
| D-16 | Lưu trữ mật khẩu PDF phiếu lương | Mật khẩu do nhân viên đặt, P3, mã hóa bằng subject DEK, chỉ-ghi (§7.5) | **Đã quyết định** (R2-17) |

Các quyết định liên quan được đưa ra ở nơi khác: X-03 close hold (§7.4, `att.attendance_result_hold`), X-05 năng lực tiếp nhận chấm công (§10.1), X-07 schema `saas`, OD-9 không chấm công qua mobile ở Phase 1 (§7.4), OD-10 key root bằng phần mềm cho MVP (§9.3).

### 15.2 Rủi ro

| Rủi ro | Tác động | Giảm thiểu |
|---|---|---|
| Thiếu `set_config` ở một luồng code nào đó (batch job, async listener) | Kết quả rỗng hoặc ghi thất bại (fail-closed), lỗi khó hiểu | Rule ArchUnit, gọi `sys.assert_tenant()` trong transaction template, integration test cho từng job |
| Plan RLS bị suy giảm trên các bảng partition lớn | Truy vấn chậm vào giờ cao điểm | Dạng InitPlan của policy, `tenant_id` đứng đầu mọi index, cổng `EXPLAIN` trong perf test |
| Độ phức tạp bi-temporal trong service | Hỏng dữ liệu trong lịch sử lương/phân công | Chỉ dùng thư viện `temporal-kit`, guard trigger, property-based test |
| Projection được cấp qua event bị lệch so với module sở hữu | Đầu vào tính lương sai | Phiên bản và watermark cho mỗi projection, so sánh checksum hằng đêm, job re-sync, cổng chặn tại INPUT_CUTOFF |
| KMS gặp sự cố | Không giải mã được, nên file ngân hàng, tờ khai và trang hồ sơ ESS bị lỗi | Cache KEK của tenant với TTL (ví dụ 1 h) chỉ trong bộ nhớ, KMS HA tại VN, chế độ suy giảm có che dữ liệu |
| Khối lượng vượt quá một primary (> 1M nhân viên hoặc tenant lớn) | Hiệu năng | Sharding pool theo nhóm tenant cộng với dedicated tier; cơ chế định tuyến đã có sẵn |
| Chất lượng dữ liệu số dư đầu kỳ (file Excel của khách hàng) | Sai quyết toán thuế TNCN, điều kiện hưởng BHXH hoặc phép; chậm go-live | Quy tắc kiểm tra chặn, kiểm tra chéo giữa các template, cổng đối soát có ký xác nhận, diễn tập trên sandbox (§13) |
| Số tiền lương ở dạng plaintext (X-01; SA AR-13) | Bị lộ qua DB credential hoặc break-glass | **Chấp nhận kèm biện pháp giảm thiểu (R2-15, 2026-10-16), được DPO ghi nhận trong DPIA:** (1) secrets vault — credential PostgreSQL động của OpenBao, TTL ngắn, xoay vòng ≤ 90 ngày, không có gì trong repo hay image; (2) **không ai có quyền truy cập thủ công vào credential của ứng dụng** — login role của module chỉ được cấp cho workload qua Kubernetes auth, không operator hay developer nào lấy được, con người chỉ dùng `yhrm_breakglass` (hai người phê duyệt, ≤ 4 h); (3) **audit truy cập DB** — pgaudit trên mọi session của con người và break-glass, ghi log kết nối cho mọi role, cảnh báo khi role module đăng nhập từ bên ngoài các app pod; cộng với role module chỉ cho `hr`/`pay`, masking view fail closed, audit đọc nhạy cảm |
| Thông lượng của bus dựa trên DB (D-14) | Relay bị trễ hoặc `saas.job` bị phình nếu khối lượng event tăng nhanh hơn kế hoạch | Claim theo batch, partial index, `fillfactor 80`, job đã xong được purge hằng ngày; SLO độ trễ relay p95 < 5 s; chuyển sang Kafka (Phase 2) khi chạm ngưỡng độ trễ hoặc khối lượng (SA ADR-0004 rev. 2) |
| Allow-list X-02 phình dần | Ranh giới module bị bào mòn qua các grant kiểu "chỉ thêm một cái nữa thôi" | `sys.cross_schema_grant_violations()` trong CI, rule ArchUnit, bắt buộc sửa đổi ADR |

---

## 16. Danh mục ADR (kiến trúc dữ liệu)

| ADR | Tiêu đề | Trạng thái |
|---|---|---|
| [0020](../../docs/02-architecture/adr/0020-shared-schema-rls-tenancy.md) | Multi-tenancy dùng chung schema với RLS và tenant context cục bộ theo transaction | Đã chấp nhận (2026-10-16, brief baseline 4; X-07) |
| [0021](../../docs/02-architecture/adr/0021-database-roles-and-privileges.md) | Database role và mô hình phân quyền | Đã chấp nhận (2026-10-16, X-02; danh sách ngoại lệ được chấp nhận tại R2-11; bổ sung grant cho relay trên `saas.enqueue_event_delivery()`, Đề xuất) |
| [0022](../../docs/02-architecture/adr/0022-schema-per-module-no-cross-schema-fk.md) | Mỗi module một schema; không có foreign key giữa các schema; projection | Đã chấp nhận (2026-10-16, D-01) |
| [0023](../../docs/02-architecture/adr/0023-primary-key-strategy-uuidv7.md) | Chiến lược khóa chính: UUIDv7 và khóa tenant ghép | Đề xuất |
| [0024](../../docs/02-architecture/adr/0024-effective-dating-and-bitemporality.md) | Ngày hiệu lực và bi-temporality một phần | Đề xuất |
| [0025](../../docs/02-architecture/adr/0025-legal-parameter-pack-model.md) | Mô hình dữ liệu legal parameter pack và đóng dấu phiên bản | Đề xuất |
| [0026](../../docs/02-architecture/adr/0026-pii-encryption-crypto-shredding.md) | Phân loại PII, envelope encryption, crypto-shredding | Đã chấp nhận (2026-10-16, X-01) |
| [0027](../../docs/02-architecture/adr/0027-tamper-evident-audit-log.md) | Audit log chống giả mạo với cơ chế seal hash chain bất đồng bộ | Đề xuất |
| [0028](../../docs/02-architecture/adr/0028-outbox-inbox-tables.md) | Bảng transactional outbox và inbox theo từng module (delivery job ở Phase 1) | Đề xuất (rev. 2026-10-16, R2-01) |
| [0029](../../docs/02-architecture/adr/0029-payroll-result-storage.md) | Snapshot tính lương, kết quả bất biến và lưu trữ 10 năm | Đề xuất |
| [0030](../../docs/02-architecture/adr/0030-partitioning-and-archiving.md) | Partitioning, vòng đời dữ liệu và lưu trữ | Đã chấp nhận (2026-10-16, D-03) |
| [0031](../../docs/02-architecture/adr/0031-schema-migration-flyway.md) | Công cụ migration schema: Flyway, expand/contract chỉ tiến | Đã chấp nhận (2026-10-16, Flyway Community) |
| [0032](../../docs/02-architecture/adr/0032-reporting-store-data-model.md) | Reporting store: star schema được cấp dữ liệu qua replication với cô lập tenant | Đề xuất (rev. 2026-10-16, R2-01: native logical replication ở Phase 1) |
| [0033](../../docs/02-architecture/adr/0033-oracle-migration-staging-reconciliation.md) | Migrate từ hệ thống Oracle cũ: staging, bảng ánh xạ khóa, đối soát | Bị thay thế bởi ADR-0037 (D-09) |
| [0034](../../docs/02-architecture/adr/0034-money-time-conventions.md) | Quy ước về tiền, làm tròn, ngày và múi giờ | Đề xuất |
| [0035](../../docs/02-architecture/adr/0035-global-vs-tenant-reference-data.md) | Dữ liệu tham chiếu toàn cục so với dữ liệu tenant override được | Đã chấp nhận (2026-10-16, X-02) |
| [0036](../../docs/02-architecture/adr/0036-dedicated-database-tier.md) | Platform cluster, pool cluster và dedicated tier với schema giống hệt nhau (placement class, job queue) | Đề xuất |
| [0037](../../docs/02-architecture/adr/0037-opening-balance-import.md) | Import số dư đầu kỳ: template Excel, staging, kiểm tra dữ liệu, đối soát, commit | Đã chấp nhận (2026-10-16, D-09) |
