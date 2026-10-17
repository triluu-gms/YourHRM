# Backlog Technical Enabler: Phase 1 (v0.1, 2026-10-16)

| Mục | Giá trị |
|---|---|
| File | `docs/01-requirements/user-stories/technical-enablers-backlog.md` |
| Chủ sở hữu | Solution architect |
| Phương pháp | [estimation-guide.md](estimation-guide.md) v0.1: SP theo Fibonacci, 1 SP ≈ 1 ngày công lập trình viên, không story nào > 13 |
| Đầu vào | [architecture-overview.md](../../02-architecture/architecture-overview.md) v0.3 (§5 tenancy, §6 định danh, §7 eventing, §9.5 job, §10 reporting, §11 frontend, §13 bảo mật, §14 vận hành, §17.3 spike) · [DESIGN.md](../../../database/postgresql/DESIGN.md) v0.3 · DDL `database/postgresql/ddl/000–003` · [mvp-scope.md](../brd/mvp-scope.md) §9 · [decision-log.md](../../00-project-management/decision-log.md) (R2-01, R2-04, R2-15, R2-17, R3-08, R3-11) |
| Tiền tố ID | Epic `EP-TECH-NN`, story `US-TECH-NNN` |

## 0. Phạm vi và quy tắc ước lượng cho file này

**Trong phạm vi.** Các công việc không hướng tới người dùng mà không backlog module nào sở hữu: repository, build và ranh giới module; walking skeleton SP-0; tenant context, hạ tầng RLS và các bộ test cách ly chéo tenant; role database, pipeline Flyway và gia cố DDL (bao gồm kiểm thử DDL 000–003 hiện có); outbox/inbox và event bus dựa trên DB; runtime job queue chung (`saas.job`) và worker; Keycloak, BFF, session và edge gateway; hạ tầng ép buộc phân quyền mà Core sử dụng; caching Valkey; envelope encryption PII, OpenBao, xoay vòng khoá và hạ tầng crypto-shredding; object storage và quét virus; luồng cấp dữ liệu reporting store; app shell frontend, design system và framework i18n; CI/CD; môi trường, IaC/GitOps và DR; observability; gia cố bảo mật và hỗ trợ pen-test; bộ khung kiểm thử hiệu năng; và các spike SP-0, SP-1 (chỉ bộ khung), SP-2, SP-4 (hỗ trợ), SP-5, SP-6, SP-7, SP-8, SP-9.

**Ngoài phạm vi (do nơi khác sở hữu, ở đây chỉ sử dụng).** Các engine sau thuộc các backlog khác:
- Runtime Legal Parameter Service: backlog SaaS.
- Formula engine YFL và payroll engine: backlog Payroll. Estimation guide §2 liệt kê "Legal Parameter Service runtime, formula engine" thuộc file này, nhưng brief của architect giao chúng cho các module sở hữu. PM phải xác nhận rằng mỗi hạng mục chỉ được ước lượng đúng một lần.
- Workflow, trung tâm thông báo, document service, import engine và audit store: backlog Core.
- Entitlement, vòng đời support-grant, channel adapter e-mail, framework xuất file, consent/DSR và framework báo cáo: backlog SaaS.
- Màn hình của module: backlog các module.

**Quy ước định cỡ cho công việc hạ tầng.** Định nghĩa SP trong guide loại trừ DevOps vì phần này nằm trong overhead +40%. Trong file này, công việc hạ tầng và nền tảng (IaC, cluster, CI/CD, DR, observability) *chính là* sản phẩm bàn giao, nên được ước lượng bằng SP theo cùng thang tham chiếu: 1 SP = 1 ngày của một kỹ sư trung cấp. **PM: khi tổng hợp, hãy tách phần DevOps ra khỏi overhead +40% cho các epic này, để công sức không bị tính hai lần.**

**Đợt (cột Ghi chú).** Cho biết khi nào mỗi story cần có cho kế hoạch 16 tuần (12 tuần xây dựng = 6 sprint hai tuần, sau đó 4 tuần SIT/UAT/pilot):
- `S0-1`: đường găng, hoàn thành trước cuối sprint 1.
- `S2-3`: sprint 2–3.
- `S4-6`: sprint 4–6.
- `SIT`: tuần 13–16.
- `GA-def`: có thể dời sau go-live pilot nhưng phải hoàn thành trước khi phát hành chính thức (general availability), có nêu lý do.

**Độ không chắc chắn.** `[high-uncertainty]` đánh dấu các story có ước lượng phụ thuộc vào kết quả spike hoặc vào nhà cung cấp tại VN (OD-1).

**Khử trùng lặp với backlog SaaS.** BR-SAAS-021, 023, 024, 090 và phần kỹ thuật của BR-SAAS-038, 039 và 043 được bàn giao ở đây. `saas-platform-backlog.md` nên tham chiếu các ID US-TECH cho những hạng mục này và chỉ ước lượng các màn hình và cấu hình mà nó sở hữu.

---

## 1. Epic

| Epic | Tên | Mục tiêu | Số story | SP |
|---|---|---|---|---|
| EP-TECH-01 | Repository, build và ranh giới module | Gradle multi-project, guard Spring Modulith + ArchUnit, shared kernel, quy ước API và event contract, môi trường dev | 9 | 42 |
| EP-TECH-02 | Walking skeleton (SP-0) | Chứng minh Java 25 + Boot 4 + Modulith + Keycloak + BFF + CNPG + RLS + bus dựa trên DB chạy end to end, deploy bằng Argo CD | 4 | 18 |
| EP-TECH-03 | Hạ tầng tenancy và bộ test cách ly | Tenant context ở mọi nơi, RLS + guard ứng dụng, định tuyến, giới hạn noisy-neighbour, test cách ly chặn release | 10 | 52 |
| EP-TECH-04 | Role database, pipeline Flyway, gia cố DDL | DDL 000–003 đã kiểm thử, Flyway theo schema, role dạng code, expand/contract, partition, pgaudit | 10 | 44 |
| EP-TECH-05 | Outbox/inbox và event bus dựa trên DB | Transactional outbox, relay, delivery job có thứ tự, khử trùng lặp inbox, DEAD/replay, nền process-manager | 8 | 35 |
| EP-TECH-06 | Runtime job queue và worker | Queue `saas.job` công bằng giữa các tenant, worker pool, cron, job theo ngày hiệu lực idempotent, tiến độ, autoscaling | 8 | 39 |
| EP-TECH-07 | Định danh, BFF và edge gateway | Keycloak HA với Organizations, MFA, step-up, xử lý session ở BFF, Envoy Gateway + WAF | 10 | 45 |
| EP-TECH-08 | Hạ tầng phân quyền và caching | Permission SPI, cache snapshot, SQL scope predicate, che trường, Valkey | 5 | 28 |
| EP-TECH-09 | Mã hoá PII và secret | OpenBao, thông tin xác thực DB động, envelope encryption, blind index, xoay vòng, crypto-shredding, che dữ liệu trong log | 9 | 48 |
| EP-TECH-10 | Object storage và an toàn file | Lưu trữ có tenant prefix, quét file tải lên, signed URL, replication/WORM, dịch vụ render | 5 | 21 |
| EP-TECH-11 | Luồng cấp dữ liệu reporting store *(có điều kiện, xem §5 R-1)* | Native logical replication với column list sang reporting store PostgreSQL, framework transformation | 6 | 28 |
| EP-TECH-12 | Nền tảng frontend | Workspace React/AntD, client sinh tự động, session BFF trong SPA, shell và registry, design system, i18n | 11 | 54 |
| EP-TECH-13 | CI/CD và chuỗi cung ứng phần mềm | Pipeline đặt tại VN, cổng chất lượng và bảo mật, image có chữ ký, release ring, deploy không downtime | 10 | 41 |
| EP-TECH-14 | Môi trường, IaC, GitOps, backup và DR | Nền tảng cloud tại VN, K8s, cluster CNPG, backup/PITR, site DR DC2 và diễn tập | 12 | 65 |
| EP-TECH-15 | Observability và vận hành | OTel + Grafana stack tại VN, telemetry gắn tenant, SLO, cảnh báo bảo mật, runbook | 7 | 33 |
| EP-TECH-16 | Gia cố bảo mật và hỗ trợ pen-test | Baseline ASVS L2, DAST, threat model, pen test và khắc phục, gia cố truy cập quản trị | 8 | 28 |
| EP-TECH-17 | Kỹ thuật hiệu năng | Bộ khung perf, tải tham chiếu, test bảng lương/batch/noisy-neighbour, scale trước cho cao điểm, tinh chỉnh | 7 | 34 |
| EP-TECH-18 | Spike SP-1, 2, 4, 5, 6, 7, 8, 9 | Trả lời các câu hỏi kiến trúc còn mở trước khi chúng chặn việc bàn giao | 10 | 49 |
| **Tổng** | | | **149** | **704** |

---

## 2. Story

### EP-TECH-01 Repository, build và ranh giới module

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-TECH-001 | EP-TECH-01 | Là developer, tôi muốn một build Gradle (Kotlin DSL) multi-project với `src/app`, `src/modules/*` và `src/shared/*`, toolchain Java 25, BOM Spring Boot 4 và một version catalogue để mọi module được build theo cùng một cách; `./gradlew build` xanh trên một bản checkout sạch; profile vai trò `api` và `worker` khởi động cùng một jar; Spotless, Checkstyle và Renovate đã được cấu hình | — | 5 | — | S0-1 |
| US-TECH-002 | EP-TECH-01 | Là architect, tôi muốn khai báo module Spring Modulith với `ApplicationModules.verify()` và tài liệu module được sinh tự động trong CI để vi phạm ranh giới module làm build thất bại; một import `internal` cố ý từ module khác làm CI thất bại; module canvas được phát hành dưới dạng build artifact | — | 3 | US-TECH-001 | S0-1; AR-3 |
| US-TECH-003 | EP-TECH-01 | Là architect, tôi muốn một bộ rule ArchUnit để các quy tắc phụ thuộc trong kiến trúc §3.3 được máy kiểm tra; các rule: chỉ các hướng module được phép, chỉ truy cập qua `api`, `shared/*` không phụ thuộc vào gì, không có `double`/`float` trong các package tiền/engine/biểu thức, truy cập DB chỉ bên trong `@Transactional`, không có `LocalDateTime.now()`/đồng hồ hệ thống ngoài `Clock`; mỗi rule có một fixture test thất bại | NFR-PAY-002 (một phần), NFR-ATT-005 (một phần) | 5 | US-TECH-002 | S0-1 |
| US-TECH-004 | EP-TECH-01 | Là developer module, tôi muốn một kernel `shared/common` (record TenantContext, id UUIDv7/ULID, `Money`/VND trên `BigDecimal` với các rounding mode tường minh, `Instant` UTC cộng các kiểu ngày công địa phương, mô hình lỗi RFC 9457, `Clock` có thể inject) để các module dùng chung một bộ quy ước; các value type được unit test 100%; định dạng VND không có phần thập phân | NFR-PAY-002 (một phần), NFR-ATT-005 (một phần), NFR-PAY-011 (một phần) | 5 | US-TECH-001 | S0-1 |
| US-TECH-005 | EP-TECH-01 | Là developer People/Payroll, tôi muốn thư viện `temporal-kit` (helper repository cho Mẫu A valid-time và Mẫu B bi-temporal, binding tham số `asOf`/`knownAt`, guard chống các kỳ chồng lấn) để ngày hiệu lực chỉ được hiện thực một lần; property-based test cho split/close/correct; các truy vấn as-of và as-known được chứng minh trên các bảng `hr` từ DDL 003 | NFR-PPL-001 (một phần) | 8 | US-TECH-004, US-TECH-024 | S0-1 (cần cho story People có ngày hiệu lực đầu tiên) |
| US-TECH-006 | EP-TECH-01 | Là bên tiêu thụ frontend hoặc API, tôi muốn các quy ước REST chung (springdoc OpenAPI 3.1 theo module, cursor pagination, `ETag`/`If-Match`, filter `Idempotency-Key`, problem details, `asOf`/`knownAt`) để mọi API module hoạt động như nhau; OpenAPI được phát hành theo mỗi build; POST idempotent khi phát lại trả về response ban đầu | NFR-SAAS-003 (một phần) | 5 | US-TECH-004 | S0-1 |
| US-TECH-007 | EP-TECH-01 | Là chuyên viên phân tích tích hợp, tôi muốn `shared/contracts` với một JSON Schema cho mỗi event trong catalogue, record Java và kiểu TS được sinh tự động, kiểm tra tương thích ngược trong CI và khung consumer-driven contract test để thay đổi event không thể làm hỏng consumer; một thay đổi phá vỡ tương thích đối với schema v1 làm CI thất bại; mỗi producer có một test Pact/SCC mẫu | NFR-PPL-002 (một phần) | 5 | US-TECH-001 | S0-1 |
| US-TECH-008 | EP-TECH-01 | Là developer, tôi muốn một môi trường local khởi chạy bằng một lệnh (PostgreSQL 17, Keycloak, Valkey, store tương thích S3, ClamAV, Gotenberg, mail catcher) với hai tenant demo đã seed để onboarding mất chưa đến nửa ngày; `docker compose up` cộng script seed cho ra hai login hoạt động được | — | 3 | US-TECH-001 | S0-1 |
| US-TECH-009 | EP-TECH-01 | Là developer, tôi muốn nền tảng kiểm thử (base class Testcontainers, quy ước `@ApplicationModuleTest`, builder fixture tenant/người dùng) để integration test dễ viết; container tái sử dụng giữ bộ test module dưới 10 phút | — | 3 | US-TECH-001 | S0-1 |

### EP-TECH-02 Walking skeleton (SP-0)

Skeleton là lát cắt mỏng đầu tiên của US-TECH-015, 034, 035, 036, 042, 050, 054 và 108. Các story đó được ước lượng đã trừ phần công việc skeleton, mà chúng sẽ gia cố.

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-TECH-010 | EP-TECH-02 | Là architect, tôi muốn một luồng request "hello-tenant" SPA → BFF → `hrm-api` → một bảng được bảo vệ bằng RLS trên CloudNativePG, với `set_config` theo từng transaction qua PgBouncer (chế độ transaction), để mô hình tenancy được chứng minh ngay ngày 1; tenant A không bao giờ thấy dòng của tenant B; một request không có tenant context trả về 0 dòng | BR-SAAS-021 (một phần), NFR-CORE-001 (một phần) | 5 | US-TECH-001, US-TECH-008 | S0-1; SP-0 |
| US-TECH-011 | EP-TECH-02 | Là architect, tôi muốn luồng event tối thiểu outbox → relay → job `bus.Deliver` → consumer có inbox để bus dựa trên DB của Phase 1 (không Kafka) được chứng minh; một event được gửi đúng một lần tới consumer chạy dưới role của module khác; bản trùng bị inbox bỏ qua | NFR-PPL-002 (một phần) | 5 | US-TECH-010 | S0-1; SP-0 |
| US-TECH-012 | EP-TECH-02 | Là architect, tôi muốn đăng nhập bằng mật khẩu + TOTP qua Keycloak Organizations và BFF, với kiểm tra JWT cục bộ trong `hrm-api`, để việc tách định danh hoạt động end to end; session cookie HttpOnly; claim `tid` điều khiển tenant context | NFR-CORE-006 (một phần) | 3 | US-TECH-010 | S0-1; SP-0 |
| US-TECH-013 | EP-TECH-02 | Là nhà tài trợ, tôi muốn skeleton được build trong CI với các guard Modulith/ArchUnit/grant X-02 và được Argo CD deploy lên một cluster tạm thời tại VN, cùng một báo cáo mức độ phù hợp của thư viện (Hibernate 7, jOOQ, Modulith 2, springdoc, db-scheduler, thư viện Keycloak trên Java 25/Boot 4), để OD-3 có bằng chứng; báo cáo liệt kê các blocker và điều kiện kích hoạt phương án dự phòng | — | 5 | US-TECH-011, US-TECH-012, US-TECH-002 | S0-1; tiêu chí thoát SP-0; đầu vào cho OD-3 (đóng sau SP-1) |

### EP-TECH-03 Hạ tầng tenancy và bộ test cách ly

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-TECH-014 | EP-TECH-03 | Là platform engineer, tôi muốn `TenantContext` được gắn bằng `ScopedValue` theo từng request và được lan truyền (Micrometer context propagation) tới virtual thread và executor để không đoạn code nào chạy mà không có tenant; công việc bắt đầu mà không có context sẽ thất bại ngay; slug của host phải khớp với claim `tid`, nếu không trả về 403 + security event | BR-SAAS-021, NFR-CORE-001, NFR-ATT-010, NFR-PAY-005 | 5 | US-TECH-004, US-TECH-010 | S0-1 |
| US-TECH-015 | EP-TECH-03 | Là platform engineer, tôi muốn một `TenantAwareDataSource` thiết lập `app.tenant_id`, `user_id`, `request_id`, `purpose`, `support_grant_id` và `mask_level` khi bắt đầu transaction và gọi `sys.assert_tenant()` để RLS luôn có context; truy cập không có transaction vào schema tenant bị từ chối; context không bao giờ được thiết lập ở mức session (kiểm tra dưới PgBouncer) | BR-SAAS-021, NFR-CORE-001 | 3 | US-TECH-010 | S0-1 |
| US-TECH-016 | EP-TECH-03 | Là cán bộ an ninh thông tin, tôi muốn một guard ứng dụng (Hibernate filter + jOOQ `VisitListener` thêm `tenant_id = ?`) làm lớp phòng thủ thứ hai để kể cả khi thiếu RLS policy cũng không rò rỉ gì; một test với RLS bị tắt vẫn chỉ trả về các dòng của tenant | BR-SAAS-021, NFR-CORE-001, NFR-PAY-005 | 5 | US-TECH-015 | S2-3 |
| US-TECH-017 | EP-TECH-03 | Là platform engineer, tôi muốn định tuyến tenant (`AbstractRoutingDataSource` theo `cluster_id` từ `saas.tenant_placement`, cache trong Caffeine + Valkey, mỗi role module mỗi cluster một Hikari pool, `@ReadOnlyRoute` tới replica) để platform cluster và pool cluster được truy cập một cách trong suốt; tenant được di chuyển trong directory sẽ được định tuyến đúng sau khi vô hiệu hoá cache; định tuyến tới replica cho báo cáo và xuất dữ liệu | NFR-SAAS-005 | 8 | US-TECH-015, US-TECH-064, US-TECH-109 | S2-3 |
| US-TECH-018 | EP-TECH-03 | Là platform engineer, tôi muốn các bảng nền tảng REPLICATED (`saas.tenant`, `tenant_placement`, `entitlement`, legal pack đã phát hành, master data global) được sao chép chỉ đọc từ platform cluster tới mọi pool cluster (`pub_platform_ref`), có giám sát độ trễ, để các module đọc chúng cục bộ; cách xử lý xung đột cho các dòng `code_value` global được quyết định cùng Data architect | NFR-SAAS-005 | 5 | US-TECH-109, US-TECH-147 | GA-def nếu pilot chạy schema platform + pool trong cùng một cluster (quyết định cùng DA); spike DESIGN §3.1; [high-uncertainty] |
| US-TECH-019 | EP-TECH-03 | Là cán bộ an ninh thông tin, tôi muốn một bộ test cách ly mức DB (hai tenant; mọi app role × mọi bảng tenant trả về 0 dòng chéo tenant và từ chối ghi chéo tenant; một kiểm tra catalogue thất bại khi một bảng thiếu `tenant_id` + RLS trừ khi nằm trong allow-list) chạy sau mỗi migration để lỗ hổng RLS không thể lọt ra production | BR-SAAS-023, NFR-CORE-001, NFR-PAY-005, NFR-ATT-010, NFR-PPL-008 (một phần) | 5 | US-TECH-024, US-TECH-026 | S2-3; chặn release |
| US-TECH-020 | EP-TECH-03 | Là cán bộ an ninh thông tin, tôi muốn một bộ test cách ly mức API tự phát hiện mọi endpoint từ các đặc tả OpenAPI và gọi chúng bằng session của tenant A với id giả mạo của tenant B để QAS-1 được kiểm thử tự động; kết quả mong đợi 403/404/rỗng; các module đăng ký fixture qua một SPI đơn giản; là cổng release trong pipeline | BR-SAAS-023, NFR-CORE-001, NFR-PAY-005, NFR-ATT-010, NFR-PPL-008 (một phần) | 8 | US-TECH-006, US-TECH-019, US-TECH-054 | S4-6; chặn release |
| US-TECH-021 | EP-TECH-03 | Là cán bộ an ninh thông tin, tôi muốn các test cách ly cho các kênh bất đồng bộ (dòng job, envelope event và context được thiết lập lại trước handler, tiền tố cache key, key của object storage, file xuất, reporting store) để dữ liệu tenant không thể đi chéo bên ngoài HTTP; mỗi kênh một negative test | BR-SAAS-023, NFR-PAY-005, NFR-ATT-010 | 5 | US-TECH-036, US-TECH-042, US-TECH-064, US-TECH-074 | S4-6 |
| US-TECH-022 | EP-TECH-03 | Là đơn vị vận hành nền tảng, tôi muốn các biện pháp kiểm soát noisy-neighbour (token bucket Bucket4j trong Valkey theo tenant và theo người dùng, mặc định theo gói dịch vụ; `statement_timeout` theo role; giới hạn số báo cáo/xuất dữ liệu đồng thời theo tenant) để một tenant không thể chiếm quá 20% pool; vượt giới hạn trả về problem details 429; giới hạn chống brute-force trên các endpoint đăng nhập | NFR-SAAS-005, NFR-CORE-014 | 5 | US-TECH-064, US-TECH-054 | S2-3 |
| US-TECH-023 | EP-TECH-03 | Là đơn vị vận hành nền tảng, tôi muốn hạ tầng context cho support-grant (token của operator không có `tid` + grant id → TenantContext; `mask_level` mặc định che toàn bộ; mọi hành động được đóng dấu grant id) để tính năng support-grant của SaaS có thể dựa vào; các hành động không có grant hợp lệ bị từ chối | BR-SAAS-033 (một phần), BR-SAAS-034 (một phần), BR-SAAS-037 (một phần) | 3 | US-TECH-014, US-TECH-063 | S4-6; vòng đời grant và UI nằm trong backlog SaaS |

### EP-TECH-04 Role database, pipeline Flyway, gia cố DDL

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-TECH-024 | EP-TECH-04 | Là Data architect, tôi muốn DDL hiện có `000_extensions_roles`, `001_saas`, `002_core`, `003_hr` được thực thi từ đầu trên PostgreSQL 17 trong Testcontainers, được lint (`squawk`) và được bao phủ bởi test cho các hàm đã có (`sys.current_tenant_id`, `enable_tenant_rls`, `create_outbox_inbox`, `cross_schema_grant_violations`, masking view fail closed, `claim_jobs`, `enqueue_event_delivery`) để lỗi được phát hiện trước khi các module xây dựng trên đó; danh sách lỗi được bàn giao cho DA; mọi script chạy sạch | NFR-CORE-001 (một phần), NFR-PAY-004 (một phần) | 8 | US-TECH-009 | S0-1; DDL dài 2.908 dòng; DDL `att`/`pay` chưa được viết (DA) |
| US-TECH-025 | EP-TECH-04 | Là developer, tôi muốn baseline DDL được chuyển thành các location Flyway theo schema (`V1__baseline.sql`, `R__` cho view/hàm/policy, mỗi schema một bảng lịch sử, thứ tự `sys → saas → core/audit → hr → att → pay`) để các module migrate độc lập; build chạy mọi migration từ đầu và bản nâng cấp từ snapshot của bản release trước | NFR-SAAS-014 (một phần) | 5 | US-TECH-024 | S0-1 |
| US-TECH-026 | EP-TECH-04 | Là platform engineer, tôi muốn các DB role (`yhrm_owner`, `migrator`, `app_*`, `relay`, `cdc`, `audit_sealer`, `reporting_ro`, `ops_ro`, `breakglass`) và grant được định nghĩa dạng code (CNPG managed role + bootstrap) để mọi môi trường có đặc quyền giống hệt nhau; guard X-02 trả về 0 dòng; không app role nào có BYPASSRLS | BR-SAAS-021 (một phần), NFR-PAY-004 (một phần) | 5 | US-TECH-024 | S0-1 |
| US-TECH-027 | EP-TECH-04 | Là SRE, tôi muốn migration được áp dụng theo từng cluster bởi một migration Job trước khi rollout (Argo CD pre-sync), theo từng đợt, với kiểm tra khi khởi động ứng dụng từ chối chạy trên schema cũ hơn mức cần thiết, để các bản release không bao giờ gặp database đang migrate dở; migration thất bại sẽ dừng rollout | NFR-SAAS-014 | 5 | US-TECH-025, US-TECH-108 | S2-3 |
| US-TECH-028 | EP-TECH-04 | Là developer, tôi muốn hỗ trợ expand/contract (rule `squawk` trong CI: `lock_timeout`, `CREATE INDEX CONCURRENTLY`, `NOT VALID` + `VALIDATE`, không đổi tên hay đổi kiểu trên bảng lớn) và một template job backfill công bằng giữa các tenant để thay đổi schema không cần downtime; lint chặn DDL không an toàn; backfill thiết lập tenant context theo từng batch | NFR-SAAS-014 | 5 | US-TECH-025, US-TECH-042 | S2-3 |
| US-TECH-029 | EP-TECH-04 | Là SRE, tôi muốn quản lý partition (`pg_partman` hoặc `sys.create_range_partitions()` trên scheduler, tạo trước 3 kỳ, partition outbox hằng ngày bị drop sau 7 ngày, inbox 90 ngày, cảnh báo khi thiếu partition tương lai, không có partition DEFAULT) để các bảng lớn không bao giờ bị lỗi insert | NFR-ATT-007 (một phần) | 3 | US-TECH-045 | S2-3 |
| US-TECH-030 | EP-TECH-04 | Là DPO, tôi muốn một kiểm tra CI phân tích `COMMENT ON COLUMN … 'pii:Px'` và thất bại khi có cột chưa phân loại, đồng thời xuất registry cột cho danh mục dữ liệu cá nhân của SaaS, để không cột dữ liệu cá nhân nào bị bỏ sót đăng ký | BR-SAAS-079 (một phần), NFR-PPL-005 (một phần) | 3 | US-TECH-025 | S2-3 |
| US-TECH-031 | EP-TECH-04 | Là cán bộ an ninh thông tin, tôi muốn baseline extension và logging (`btree_gist`, `pgcrypto`, `pg_trgm`, `unaccent`, `ltree`, `pg_stat_statements`, `pgaudit`), một tag comment JDBC `/* tenant=… */`, pgaudit trên các session của con người và break-glass, ghi log kết nối và cảnh báo khi có bất kỳ lần đăng nhập bằng role module nào từ bên ngoài pod ứng dụng (R2-15), để truy cập DB có bằng chứng | NFR-PAY-004, NFR-SAAS-010 (một phần) | 5 | US-TECH-109, US-TECH-118 | S2-3 |
| US-TECH-032 | EP-TECH-04 | Là developer module, tôi muốn một helper tìm kiếm không phân biệt dấu (wrapper `unaccent` immutable, template trigram index, helper predicate jOOQ/JPA, bộ ngữ liệu test tiếng Việt) để tìm kiếm tên hoạt động không cần dấu; "nguyen van an" tìm thấy "Nguyễn Văn An"; index được sử dụng (test EXPLAIN) | NFR-CORE-011 (một phần), NFR-PPL-009 (một phần) | 3 | US-TECH-025 | S2-3 |
| US-TECH-033 | EP-TECH-04 | Là đơn vị vận hành nền tảng, tôi muốn quy trình break-glass thủ công hai người (grant `yhrm_breakglass` tạm thời, script thu hồi ≤ 4 h, pgaudit, ghi vào nhật ký truy cập của operator) để truy cập DB khẩn cấp được kiểm soát; quy trình đã được diễn tập một lần | BR-SAAS-036 (biến thể thủ công P1), BR-SAAS-037 (một phần), NFR-SAAS-008 (một phần) | 2 | US-TECH-031 | S4-6; tự động hoá là P2 (R2-19) |

### EP-TECH-05 Outbox/inbox và event bus dựa trên DB

| ID | Epic | Story | Tham chiếu BR/NFR | SP | Phụ thuộc | Ghi chú |
|---|---|---|---|---|---|---|
| US-TECH-034 | EP-TECH-05 | Là developer module, tôi muốn một API ghi outbox (`publish(event)` trong transaction của bên gọi, đóng dấu envelope gồm tenant, pháp nhân, correlation/causation, phiên bản producer, header `initial_load`, kiểm tra schema trong test, guard từ chối các trường được đánh dấu nhạy cảm) để không có thay đổi đã commit nào tồn tại mà không có event của nó | NFR-PPL-002, NFR-CORE-004 (một phần), NFR-CORE-008 (một phần) | 5 | US-TECH-011, US-TECH-007 | S0-1 |
| US-TECH-035 | EP-TECH-05 | Là SRE, tôi muốn relay production (poller theo cluster trên kết nối trực tiếp, batch `SKIP LOCKED` 500 dòng trên mọi outbox module, `enqueue_event_delivery()` trong cùng transaction, replica không cần leader, event nền tảng được fan-out tới mọi cluster với sentinel tenant) để event được truyền tin cậy; độ trễ relay p95 < 5 s ở tải test SP-2 | NFR-PPL-002, NFR-CORE-004 (một phần) | 5 | US-TECH-034, US-TECH-042 | S2-3 |
| US-TECH-036 | EP-TECH-05 | Là developer module, tôi muốn các subscription `@EventConsumer` được biên dịch thành một registry và một worker `bus.Deliver` đọc envelope qua kết nối relay, thiết lập lại tenant context và chạy handler trong transaction, role và inbox của module consumer, theo thứ tự cho mỗi `consumer:aggregate`, để consumer idempotent và có thứ tự; payload của job chỉ là một con trỏ | NFR-PPL-002, NFR-CORE-004 (một phần), NFR-PAY-004 (một phần) | 8 | US-TECH-035 | S2-3 |
| US-TECH-037 | EP-TECH-05 | Là đơn vị vận hành nền tảng, tôi muốn retry với exponential backoff (1 s … 10 phút, 10 lần thử), trạng thái `DEAD` và một màn hình/API replay cho operator trong cửa sổ outbox 7 ngày để không event nào bị mất âm thầm; replay chạy lại một hoặc nhiều lượt gửi DEAD; hook đồng bộ lại cho các event cũ hơn | NFR-CORE-004 (một phần), NFR-PPL-002 | 5 | US-TECH-036 | S2-3 |
| US-TECH-038 | EP-TECH-05 | Là architect, tôi muốn một port `EventTransport` (`JobQueueTransport` hiện tại, stub interface `KafkaTransport` cho Phase 2) để Phase 2 không phải thay đổi code của producer hay consumer nào; ArchUnit cấm handler phụ thuộc vào transport | — | 2 | US-TECH-036 | S2-3 |
| US-TECH-039 | EP-TECH-05 | Là SRE, tôi muốn metric và housekeeping cho bus (độ trễ relay, độ trễ gửi, số DEAD, kích thước và bloat của `saas.job`, purge hằng ngày các job đã xong) kèm cảnh báo để AR-14 được nhìn thấy trước khi gây hại | NFR-SAAS-010 (một phần) | 3 | US-TECH-036, US-TECH-119 | S2-3 |
| US-TECH-040 | EP-TECH-05 | Là developer module, tôi muốn một nền `ProcessManager` trong `shared/common` (bảng trạng thái, timeout qua scheduler, bước idempotent, correlation) để các saga tuyển mới, nghỉ việc, chốt kỳ, tái cơ cấu và DSR dùng chung một cơ chế; một saga mẫu có timeout được kiểm thử | NFR-CORE-004 (một phần) | 5 | US-TECH-036, US-TECH-045 | S2-3 |
