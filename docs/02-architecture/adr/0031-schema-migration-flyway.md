# ADR-0031: Công cụ migration schema — Flyway, forward-only expand/contract

- **Trạng thái:** Đã chấp thuận (2026-10-16, license Flyway Community)
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect, DevOps
- **Liên quan:** NFR-SAAS-014 (không downtime, rollback ≤ 15 phút), NFR-CORE-013; ADR-0002 (Flyway theo schema), ADR-0036 (đội database); [DESIGN.md](../../../database/postgresql/DESIGN.md) §12

## Bối cảnh

Sản phẩm chỉ dùng PostgreSQL và dựa vào các tính năng mà ORM và changelog trừu tượng mô hình hoá kém: RLS policy, exclusion constraint, partitioning, generated column, hàm `SECURITY DEFINER`. Release được phát hành ít nhất hai tuần một lần mà không có downtime theo kế hoạch, tới các pool cluster dùng chung và tới mọi database riêng. Các module sở hữu schema của mình và nên migrate độc lập.

## Quyết định

1. **Flyway** (bản Community, 11.x), ưu tiên SQL. Một location và **một bảng lịch sử cho mỗi schema** (`<schema>.flyway_schema_history`). Thứ tự chạy: `sys` → `saas` → `core`/`audit` → `hr` → `att` → `pay` → `rec`.
2. Đặt tên: `V<yyyymmddHHMM>__<desc>.sql` (gắn timestamp để tránh xung đột merge) và `R__<object>.sql` cho view, hàm và policy. Các file `database/postgresql/ddl/*.sql` trở thành `V1__baseline` của từng schema.
3. Thực thi dưới `yhrm_migrator` với `initSql = SET ROLE yhrm_owner; SET lock_timeout = '3s'`. CI chạy linter **squawk**.
4. **Forward-only expand/contract:**
   * N: thêm cột nullable, bảng, ghi kép;
   * một job backfill chạy theo từng tenant (fair-share, ADR-0011);
   * N+1: chuyển luồng đọc;
   * N+2: drop.

   Quy tắc: index tạo `CONCURRENTLY` (`executeInTransaction=false`), constraint `NOT VALID` rồi `VALIDATE`, không đổi tên, không `ALTER TYPE` trên bảng lớn, `NOT NULL` thông qua một `CHECK` đã được validate.
5. **Triển khai cho toàn bộ đội database** theo các vòng (nội bộ → pilot → pool dùng chung → database riêng), do một migration orchestrator điều khiển. Khi khởi động, ứng dụng kiểm tra phiên bản schema yêu cầu với bảng lịch sử.
6. **Seed dữ liệu tenant không phải là migration:** quá trình khởi tạo tenant gọi seed API của các module. Master data global và legal pack được phát hành qua release job.
7. Kiểm thử: Testcontainers chạy mọi migration từ đầu và từ bản release trước; bộ test cách ly RLS và kiểm tra catalogue là chặn release.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Liquibase | Lớp trừu tượng changelog ít giá trị cho một sản phẩm chỉ một loại database; DDL đặc thù PostgreSQL rốt cuộc vẫn nằm trong các change set `sql` thô; khối rollback hiếm khi đúng với thay đổi dữ liệu. Hợp lệ, nhưng không có lợi thế ở đây. |
| Hibernate `ddl-auto` | Không chấp nhận được cho production; không kiểm soát được lock hay thứ tự. |
| Một bảng lịch sử duy nhất cho mọi schema | Gắn chặt release train của các module; migration thất bại của một module chặn các module khác. |
| Down migration | Phá huỷ dữ liệu và không kiểm thử được ở quy mô lớn. Rollback = phiên bản ứng dụng trước đó trên schema đã expand. |

## Hệ quả

- \+ SQL thuần có thể được DBA review và dùng trực tiếp mọi tính năng của PostgreSQL.
- − Không dùng các tính năng Flyway Teams/Enterprise (undo, báo cáo dry-run). Bản Community là đủ và đã được chấp thuận ngày 2026-10-16 (decision-log "Flyway", D-08).
- − Expand/contract kéo dài một thay đổi phá vỡ tương thích qua ba bản release. Các đội phải theo dõi các bước contract (lint rule cộng một backlog item).
