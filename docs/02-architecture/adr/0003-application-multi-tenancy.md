# ADR-0003: Multi-tenancy ở tầng ứng dụng — xác định tenant, lan truyền context, routing, quota

- **Trạng thái:** Đã chấp nhận (2026-10-16, baseline 4 của bản định hướng — pool chung + RLS + app guard, gói dedicated, lưu trữ tại VN; X-07 schema `saas`). Quyết định 5 được cập nhật ngày 2026-10-16 theo tập baseline đã được phê duyệt trong **R2-18** (đã chấp nhận)
- **Ngày:** 2026-10-16
- **Liên quan:** Bản định hướng kiến trúc B4; gap-analysis GAP-016, GAP-060, §9 A-1/A-7; [architecture-overview](../architecture-overview.md) §5; cách ly ở mức dữ liệu trong kiến trúc dữ liệu (DESIGN.md, ADR 0020–0039)

## Bối cảnh

Baseline là pool chung (`tenant_id` + PostgreSQL RLS + application guard) cùng cơ sở dữ liệu dedicated cho gói Enterprise được chọn qua tenant routing. NFR-SAAS-005 yêu cầu ≥ 500 tenant, 200k nhân viên, tăng trưởng 5 lần và giới hạn noisy-tenant ở mức 20% năng lực của pool. Tenant context phải được giữ nguyên qua HTTP, xử lý bất đồng bộ, job, event, cache và object storage. Thuê bao module được bán riêng, nhưng khi chạy các module lại phụ thuộc vào năng lực tham chiếu của nhau (GAP-060).

## Quyết định

1. **Xác định tenant:** claim `tid` của access token đã được xác thực là nguồn có thẩm quyền duy nhất cho lưu lượng của người dùng; tên host (`{slug}.yourhrm.vn`, custom domain P2/P3) chỉ là gợi ý đăng nhập và phải khớp với claim. Lưu lượng từ thiết bị (Phase 2, connector trực tuyến) xác định tenant từ credential của thiết bị (mTLS); ở Phase 1, dữ liệu thiết bị chỉ đến dưới dạng file import trong phiên làm việc của người dùng. Job và event mang `tenant_id` trong bản ghi được lưu. Operator chỉ thao tác trên một tenant khi có support grant giới hạn thời gian.
2. **Context:** một `TenantContext` bất biến được bind bằng `ScopedValue`, lan truyền sang virtual thread/executor bằng Micrometer context propagation; code không có context sẽ fail fast. Mọi transaction thực thi `SET LOCAL app.tenant_id` (và user / support grant) cho RLS; một Hibernate filter / jOOQ listener thêm điều kiện `tenant_id` làm lớp phòng thủ thứ hai. Cache key và object key được gắn tiền tố tenant bởi các wrapper từ chối dữ liệu tenant không có tiền tố.
3. **Routing:** `saas.tenant_placement` ánh xạ tenant → cluster (pool-NN hoặc dedicated). Một `AbstractRoutingDataSource` chọn cluster cho mỗi transaction; một tenant không bao giờ trải trên nhiều cluster. Mỗi pool cluster chứa khoảng 100k nhân viên **[spike]**. Việc di chuyển giữa các cluster dùng filtered logical replication, ngoài khoảng ngày 25–5.
4. **Entitlement & flag:** entitlement (`saas`) kiểm soát tính năng và giao diện qua aspect `@Entitled`; OpenFeature với provider tự phát triển phục vụ các flag phát hành, vận hành và hành vi tính toán theo tenant/ring.
5. **Năng lực baseline luôn bật (R2-18, chấp nhận ngày 2026-10-16; GAP-060):** có sẵn cho mọi tenant bất kể mua module nào — **Core** (tổ chức, pháp nhân, người dùng/vai trò, workflow, thông báo, tài liệu, import engine), **People cơ bản** (cá nhân, quan hệ lao động, phân công, hồ sơ lương; API đọc + import + giao diện hồ sơ gốc), **bộ tham số pháp lý** (LPS API), **thang/bậc lương** (gap §5 #7; API + quản trị danh mục), và **import chấm công Excel** tạo ra Attendance Result `CLOSED` chuẩn để giao thức khóa kỳ không thay đổi đối với tenant dùng Payroll mà không có T&A.
   - **Thiết kế entitlement:** tập baseline là bảng nền tảng `saas.baseline_capability` (feature key `baseline.core`, `baseline.people.master`, `baseline.legal_params`, `baseline.pay.scales_bands`, `baseline.att.excel_import`), được phát hành bởi release job và chỉ thay đổi theo quyết định sản phẩm. Entitlement resolver ghi một dòng entitlement `source = BASELINE` cho mỗi tenant và key; DB chỉ cho phép dòng như vậy dưới dạng grant được bật trên toàn tenant, và ràng buộc exclusion một-dòng-cho-mỗi-tính-năng-mỗi-kỳ ngăn plan hoặc override vô hiệu hóa nó (DESIGN.md §7.1). Các kiểm tra `` và response `/me/capabilities` xử lý các key này như mọi key khác, nên không có luồng code nào xử lý đặc biệt cho "baseline".
   - **Dịch vụ tham chiếu runtime** mà các module khác gọi không bao giờ bị kiểm soát bởi entitlement (chỉ giao diện và tính năng mới bị): lịch ngày lễ quốc gia và mẫu lịch làm việc mặc định qua T&A calendar API / SPI `WorkingCalendarProvider` của Core, và API mô phỏng gross↔net mà People dùng cho lương NET. Các dịch vụ này chỉ được cung cấp dưới dạng API cho tenant không có T&A hoặc Payroll. (Chúng không nằm trong danh sách R2-18 dưới dạng năng lực có thể bán; PM xác nhận chúng không cần liệt kê riêng.)
   - Danh mục plan công bố ma trận phụ thuộc (ví dụ Payroll yêu cầu hồ sơ gốc People + import chấm công).
6. **Kiểm soát noisy-neighbour:** token bucket API theo tenant (Bucket4j/Valkey), job queue chia sẻ công bằng với giới hạn 20% slot (ADR-0011), statement timeout theo role, giới hạn số báo cáo chạy đồng thời, chia sẻ công bằng theo tenant trên hàng đợi phân phối `events` (Kafka key theo tenant từ Phase 2, ADR-0004 rev. 2), giới hạn tốc độ thông báo theo tenant.

## Các phương án đã cân nhắc

- **Schema-per-tenant** trong pool: bị loại — 500+ schema × 6 module nhân bội số migration và làm phình catalog; RLS cho mức cách ly tương đương với plan dùng chung.
- **Database-per-tenant cho tất cả:** bị loại vì chi phí và vận hành ở quy mô SME; giữ lại cho Enterprise.
- **Tenant lấy từ header/path của request:** bị loại làm nguồn có thẩm quyền (có thể giả mạo); chỉ dùng làm gợi ý.
- **Context bằng ThreadLocal:** bị loại để dùng `ScopedValue`, an toàn với virtual thread và không thể rò rỉ qua các thread trong pool.

## Hệ quả

- + Phòng thủ nhiều lớp (token → app guard → RLS); hành vi đồng nhất cho tenant pool và dedicated.
- + Tính công bằng có thể đo lường và cưỡng chế được.
- − Mọi luồng truy cập dữ liệu phải chạy bên trong transaction có context; JDBC thô bên ngoài framework bị ArchUnit cấm.
- − Thư mục routing là thành phần then chốt: được cache hai tầng và nhân bản theo từng cluster.
- − Năng lực baseline nghĩa là một phần code của module chạy cho cả tenant không mua module đó; giá bán phải phản ánh điều này (PM).
