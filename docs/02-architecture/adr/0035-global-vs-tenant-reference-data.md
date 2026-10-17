# ADR-0035: Dữ liệu tham chiếu global và dữ liệu tham chiếu tenant có thể override

- **Trạng thái:** Đã chấp thuận (2026-10-16, X-02 — ngoại lệ chỉ đọc cho dữ liệu tham chiếu global, liệt kê trong ADR-0021)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, trưởng nhóm Core
- **Liên quan:** gap-analysis §5 #28 (Core sở hữu master data global), §9 A-1 (miễn trừ RLS); NFR-CORE-001; ADR-0020, ADR-0022, ADR-0025, ADR-0036; [DESIGN.md](../../../database/postgresql/DESIGN.md) §3.5; DDL `core.code_value`, `core.v_code_value_effective`, `sys.enable_tenant_or_global_rls`

## Bối cảnh

Một số dữ liệu tham chiếu giống hệt nhau cho mọi tenant và được nền tảng phát hành: quốc gia, tiền tệ, đơn vị hành chính và bảng ánh xạ năm 2025 của chúng, ngân hàng, và các danh mục mã theo luật định. Một số danh mục có thể mở rộng hoặc override theo tenant (lý do nghỉ phép, lý do nghỉ việc, loại nhân viên). Một số danh mục được seed theo tenant từ template (role, loại nghỉ phép, thành phần lương). Mô hình phải giữ RLS đơn giản và FK an toàn với tenant.

## Quyết định

Bốn loại:

| Loại | `tenant_id` | RLS | Ví dụ | Đường ghi |
|---|---|---|---|---|
| **Global thuần tuý** | không có | không có; `SELECT` được cấp cho app role (bảng Core); `saas.legal_param_*` chỉ cho role SaaS, các module khác dùng API của Legal Parameter Service | `core.country`, `currency`, `admin_unit`, `admin_unit_mapping`, `bank`, `bank_branch`, `code_list`; `saas.legal_param_*` | Release job của nền tảng (`core.MasterDataReleased`), sao chép tới mọi cluster |
| **Global + override của tenant** | `NOT NULL`; dòng global dùng sentinel `00000000-…-0000` | đọc dòng của mình + sentinel; chỉ ghi dòng của mình (`sys.enable_tenant_or_global_rls`) | `core.code_value`, `core.exchange_rate`, `saas.retention_policy` | Quản trị viên tenant cho dòng của mình; release job cho dòng sentinel |
| **Seed từ template** | `NOT NULL`, chỉ có dòng tenant | chuẩn | `core.role` (role hệ thống), `att.leave_type`, `pay.salary_component`, bản sao lịch ngày lễ | Seed API khi khởi tạo tenant, giữ `template_version` để nâng cấp |
| **Thuộc sở hữu tenant** | `NOT NULL` | chuẩn | mọi thứ còn lại | API của module |

* Phân giải override: `DISTINCT ON (list, code) … ORDER BY (tenant_id = sentinel)`, được bọc trong `core.v_code_value_effective` (view security-invoker). Tenant có thể thêm giá trị hoặc vô hiệu hoá chúng cho riêng mình. Danh mục luật định (`is_statutory`) không thể mở rộng.
* Các module khác tham chiếu dữ liệu global **bằng mã**, không bao giờ bằng surrogate id (ADR-0022).
* **Module khác đọc trực tiếp (X-02, chấp thuận 2026-10-16):** role của module được phép `SELECT` các bảng tham chiếu của Core thuộc hai loại đầu (và dòng `saas.tenant` của chính tenant). Đây là ngoại lệ đọc duy nhất đối với quy tắc "không có grant trên schema của module khác"; nó chỉ đọc và được liệt kê trong ADR-0021, và được bảo vệ bởi `sys.cross_schema_grant_violations()` (test DB) cộng một rule ArchUnit. Các danh mục được seed và danh mục thuộc sở hữu tenant **không** được bao gồm: module khác đọc chúng qua API của chủ sở hữu hoặc projection được cấp bởi event.
* **Không FK nào được trỏ tới bảng global + tenant bằng `id`**: kiểm tra FK bỏ qua RLS, nên một tenant có thể tham chiếu tới dòng của tenant khác. Các tham chiếu như vậy dùng mã (được kiểm tra trong ứng dụng), hoặc danh mục được seed theo tenant.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| `tenant_id NULL` cho dòng global | NULL không thể là một phần của PK; policy có nhánh `IS NULL` dễ sai. |
| Sao chép toàn bộ dữ liệu tham chiếu vào mọi tenant | 500 bản sao của bảng đơn vị hành chính; các bản release (như đợt sắp xếp lại đơn vị hành chính 2025) sẽ cần 500 lần cập nhật. |
| Schema `ref` riêng | Quyền sở hữu thuộc về Core (gap §5 #28); schema riêng thêm bề mặt grant mà không có lợi ích. |

## Hệ quả

- \+ Một bản release cập nhật mọi tenant; tenant vẫn tuỳ biến được ở nơi được phép.
- − Nâng cấp template đã seed (quyền mới của role hệ thống, một thành phần lương luật định mới) cần một job "nâng cấp template" tôn trọng các thay đổi của tenant.
- − Sentinel tenant id không bao giờ được khởi tạo thành tenant thật (CHECK trên `saas.tenant`).
