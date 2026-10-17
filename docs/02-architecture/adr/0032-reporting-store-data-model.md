# ADR-0032: Mô hình dữ liệu reporting store — star schema được cấp dữ liệu qua replication với cách ly tenant

- **Trạng thái:** Đề xuất (sửa đổi 2026-10-16, R2-01: luồng cấp dữ liệu Phase 1 là native logical replication, ADR-0012 Amendment 1; mô hình không đổi)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect (mô hình), Solution architect (ADR-0012 sở hữu công nghệ và pipeline)
- **Liên quan:** BR-SAAS-046/047, NFR-SAAS-003; gap-analysis §9 A-10; ADR-0006 (`report_scope`), ADR-0012, ADR-0026, ADR-0029; [DESIGN.md](../../../database/postgresql/DESIGN.md) §11

## Bối cảnh

Phân tích không được tạo tải lên OLTP vào giờ cao điểm tính lương và chấm công. Độ tươi dữ liệu phải ≤ 15 phút, và RLS cùng phạm vi dữ liệu phải được kế thừa. ADR-0012 đã chọn reporting store PostgreSQL 17 với star schema, SCD2, và `tenant_id` + RLS. Amendment 1 của nó (R2-01) cấp dữ liệu ở Phase 1 bằng native logical replication với column list (không phát hành cột P3) thay vì Debezium → Kafka; Debezium vẫn là tuỳ chọn Phase 2. Mô hình dưới đây hoạt động với cả hai luồng cấp dữ liệu. ADR này chốt mô hình và các quy tắc cách ly của nó.

## Quyết định

1. **Các lớp:**
   * `rpt_raw`: vùng đáp dữ liệu replication, một bảng cho mỗi bảng nguồn được phát hành (Phase 1: bảng subscriber của logical replication, không RLS, chỉ role transformation đọc được; tuỳ chọn Phase 2: CDC append cộng một merge view);
   * `rpt`: star schema, được dựng bởi các SQL model do scheduler chạy (kiểu dbt).
2. **Dimension:** SCD2 cho `dim_employee` (khoá theo `employment_id`), `dim_org_unit` (có path), `dim_position`, `dim_legal_entity`; SCD1 cho `dim_pay_component`, `dim_att_code`, `dim_leave_type`, `dim_location`, `dim_cost_centre`; cộng `dim_date` và `dim_pay_run`. Surrogate key là `bigint`; natural id được giữ lại.
3. **Fact:** `fact_headcount_daily` (periodic snapshot, `is_billable`), `fact_movement`, `fact_payroll_line` (nhân viên × kỳ chạy × thành phần, 10 năm), `fact_payroll_employee_month`, `fact_attendance_daily`, `fact_ot`, `fact_leave`, `fact_si_contribution`, và `fact_recruitment_funnel` (P2). Fact được partition hằng tháng.
4. **Cách ly:** `tenant_id` trên mọi dòng, đứng đầu mọi index; cùng template RLS và context cục bộ theo transaction như OLTP. Phạm vi dữ liệu đến từ `report_scope(tenant_id, user_account_id, employment_id)` join theo `employment_id`. Tenant riêng có reporting DB riêng.
5. **Quyền riêng tư:** không có P3 trong store; tên (P1) được giữ; số tiền (P2) chỉ hiển thị qua các subject area có kiểm tra quyền. Ẩn danh hoá theo DSR cập nhật `dim_employee`; purge tenant xoá theo `tenant_id`.
6. Chỉ các kỳ chạy lương đã phê duyệt và các kỳ công đã khoá hoặc đã chốt mới được đưa vào fact bảng lương và chấm công. Dữ liệu tạm tính được gắn cờ và mặc định bị loại trừ.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Báo cáo từ read replica OLTP với materialised view | Join chéo module bị cấm trên OLTP (ADR-0022); tải refresh MV đè lên các replica dùng cho failover. |
| ClickHouse làm store đầu tiên | Mạnh cho scan, nhưng ngữ nghĩa RLS và SCD2 yếu hơn và là thêm một engine phải vận hành tại VN. Vẫn là phương án scale-out nếu PostgreSQL không đạt NFR-SAAS-003 **[spike]**. |
| Data vault | Thiết kế quá mức so với danh mục báo cáo trong phạm vi. |

## Hệ quả

- \+ Một mô hình được quản trị duy nhất cho báo cáo chuẩn, trình thiết kế báo cáo (P2) và Superset.
- \+ Cách ly giống như OLTP, không có cơ chế mới.
- − Việc tiến hoá schema của các bảng được phát hành phải theo expand/contract (ADR-0031) với một bước migration `rpt_raw`, vì logical replication không mang DDL; các transformation phải chịu được cột được thêm vào.
- − `report_scope` có thể lớn (số người dùng × số nhân viên trong phạm vi). Nó được duy trì tăng dần, với lối tắt ở mức role cho các phạm vi toàn tenant **[spike]**.
