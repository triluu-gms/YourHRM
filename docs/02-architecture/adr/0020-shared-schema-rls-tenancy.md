# ADR-0020: Multi-tenancy dùng chung schema với RLS và tenant context cục bộ theo transaction

- **Trạng thái:** Đã chấp thuận (2026-10-16, baseline 4 của brief; X-07 schema `saas` được duyệt; SP-2 vẫn kiểm chứng hiệu năng RLS)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect (chủ sở hữu), Solution architect (bên sử dụng), PM
- **Liên quan:** Architecture brief B4; SaaS BRD Q2; NFR-CORE-001, NFR-PAY-005, NFR-ATT-010, NFR-REC-001, NFR-SAAS-005; ADR-0003 (tenancy ở tầng ứng dụng), ADR-0021, ADR-0035, ADR-0036; [DESIGN.md](../../../database/postgresql/DESIGN.md) §3; DDL `000_extensions_roles.sql` (`sys.enable_tenant_rls`)

## Bối cảnh

Baseline của PM chốt một pool dùng chung với `tenant_id` trên mọi dòng, PostgreSQL RLS và một lớp bảo vệ ở ứng dụng, cùng với một tier database riêng. SaaS Q2 vẫn đặt câu hỏi liệu schema-per-tenant có tốt hơn không. Tier dùng chung phải chứa ≥ 500 tenant và 200k nhân viên khi ra mắt và mở rộng được 5× (NFR-SAAS-005). Mọi module đều yêu cầu cách ly được "kiểm thử tự động" và vẫn đúng trong background job và khi xuất dữ liệu. Kết nối đi qua PgBouncer ở chế độ transaction, nên không thể tin cậy trạng thái session.

## Quyết định

1. **Dùng chung schema, dùng chung bảng.** Mọi bảng thuộc sở hữu tenant có `tenant_id uuid NOT NULL`, đứng đầu PK `(tenant_id, id)` và mọi index phụ.
2. **RLS được bật và ép buộc (forced)** trên mọi bảng thuộc sở hữu tenant, với một policy chuẩn duy nhất được áp bởi `sys.enable_tenant_rls()`:
   `FOR ALL TO yhrm_app USING (tenant_id = (SELECT sys.current_tenant_id())) WITH CHECK (same)`.
   Scalar subquery được đánh giá một lần mỗi câu lệnh (InitPlan), nên predicate vẫn tận dụng được index.
3. **Context cục bộ theo transaction.** Câu lệnh đầu tiên của mọi transaction là `set_config('app.tenant_id', …, true)` (tương đương `SET LOCAL`), cùng với `app.user_id`, `app.request_id`, `app.purpose`, `app.support_grant_id` và `app.mask_level`. `sys.current_tenant_id()` trả về `NULLIF(current_setting('app.tenant_id', true), '')::uuid`.
4. **Fail closed.** Khi không có context, predicate là NULL, nên đọc không trả về dòng nào và ghi bị từ chối. Mọi truy cập DB đều chạy trong một transaction, và các luồng code không có tenant (cron dispatcher) không bao giờ chạm vào bảng tenant.
5. **RLS chỉ dùng cho cách ly tenant.** Phạm vi dữ liệu của người dùng được ép buộc bởi scope predicate của Core trong ứng dụng (ADR-0006), dựa trên các cột phi chuẩn hoá `legal_entity_id`/`org_unit_id`.
6. **Role kỹ thuật** (outbox relay, audit sealer) nhận policy `USING (true)` tường minh cho các thao tác chúng cần, không bao giờ dùng `BYPASSRLS`. Chỉ `yhrm_owner` (migration) và role break-glass có giới hạn thời gian mới bỏ qua RLS.
7. **Test chặn release:** với mọi bảng trong schema của module, một fixture hai tenant khẳng định không có khả năng nhìn thấy chéo tenant và ghi chéo tenant bị từ chối, theo từng app role. Một kiểm tra catalogue làm build thất bại khi một bảng không có `tenant_id` + RLS và không nằm trong allow-list bảng global.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Schema-per-tenant | 500+ tenant × ~400 bảng nghĩa là khoảng 200k relation: catalogue phình to, migration chậm (mỗi release × 500 schema), và không có truy vấn nền tảng chéo tenant (headcount tính phí, tác động của legal pack). Khôi phục theo tenant tốt hơn, nhưng điều này đạt được qua logical export (DESIGN §3.7). |
| Database-per-tenant cho tất cả | Chi phí kết nối và vận hành nhân lên. Chỉ giữ cho tier Enterprise (ADR-0036). |
| Chỉ lọc ở ứng dụng (Hibernate filter / jOOQ listener) | Quên một predicate là rò rỉ dữ liệu. Được giữ làm lớp phòng thủ **thứ hai** (ADR-0003), không phải lớp duy nhất. |
| `SET app.tenant_id` mức session | Không an toàn với transaction pooling của PgBouncer: context rò rỉ sang client tiếp theo. |
| `current_user` theo tenant (DB role theo tenant) | Bùng nổ số role, không pooling được giữa các tenant, grant theo từng tenant. |

## Hệ quả

- \+ Cách ly được database ép buộc, kể cả với SQL ad-hoc của công cụ hỗ trợ và lỗi trong job.
- \+ Chỉ một schema cần migrate. Tier riêng dùng cùng DDL.
- − Mọi truy vấn đều chịu chi phí của policy predicate; SP-2 phải cho thấy overhead p95 < 10 % **[spike]**.
- − Developer phải luôn chạy bên trong `@Transactional` (kể cả read-only) với context đã được thiết lập. Rule ArchUnit và kiểm tra `sys.assert_tenant()` ép buộc điều này.
- − Partition không được bảo vệ bởi policy của bảng cha khi truy cập trực tiếp, nên app role chỉ được grant trên bảng cha.
- − Bảo trì hàng loạt chéo tenant (backfill) chạy dưới quyền migration owner và phải được review như code đặc quyền.
