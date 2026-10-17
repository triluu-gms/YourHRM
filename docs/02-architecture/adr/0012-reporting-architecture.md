# ADR-0012: Báo cáo — CDC sang kho dữ liệu báo cáo PostgreSQL, Superset, framework mẫu báo cáo theo luật định

- **Trạng thái:** Đã chấp nhận (2026-10-16, OD-5 — Superset cho Phase 2). **Amendment 1 (2026-10-16, R2-01) — Đã chấp nhận (R3-11):** quyết định 2 (nguồn cấp dữ liệu cho kho báo cáo) dùng logical replication native của PostgreSQL ở Phase 1 thay cho Debezium → Kafka
- **Ngày:** 2026-10-16
- **Liên quan:** Bản định hướng kiến trúc B2 (kho dữ liệu báo cáo tách riêng); gap-analysis GAP-010, §9 A-10; SaaS BRD §4.E; NFR-SAAS-003; mô hình dữ liệu báo cáo trong kiến trúc dữ liệu (DESIGN.md); [architecture-overview](../architecture-overview.md) §10

## Bối cảnh

Nền tảng SaaS sở hữu framework báo cáo, kho dữ liệu báo cáo tách riêng và mọi chức năng xuất dữ liệu; Core cung cấp scope predicate và masking. MVP cần các báo cáo chuẩn và đầu ra theo luật định; P2 bổ sung trình thiết kế báo cáo, dashboard và phân tích nhân sự. Báo cáo không được làm suy giảm OLTP trong các đợt cao điểm tính lương, phải cách ly tenant kể cả trong BI, phải tuân thủ phạm vi dữ liệu và masking, và phải theo kịp các thao tác xóa (DSR/lưu trữ). Dữ liệu phải lưu tại Việt Nam.

## Quyết định

1. **Ba nhóm:** danh sách vận hành lấy từ read replica OLTP qua API của module; **đầu ra theo luật định** do module sở hữu tạo từ dữ liệu đã phê duyệt, có phiên bản; **phân tích** lấy từ kho dữ liệu báo cáo.
2. **Pipeline (Amendment 1, Proposed):** **Phase 1** — logical replication native của PostgreSQL: mỗi schema module trên mỗi OLTP cluster một publication với **column list** loại trừ mọi cột P3, được DB báo cáo subscribe vào landing schema `rpt_raw` (slot đặt trên standby nếu có standby decoding của PG 16+, nếu không thì trên primary với `max_slot_wal_keep_size` có giới hạn) → các job chuyển đổi SQL (worker pool `reporting`) → **kho dữ liệu báo cáo PostgreSQL 17** `rpt` (star schema, SCD2, `tenant_id` + RLS trên mọi bảng). Không có Kafka hay Kafka Connect ở Phase 1 (ADR-0004 rev. 2). **Phương án Phase 2** — Debezium trên standby → Kafka `cdc.*` → loader, nếu chuyển đổi dạng streaming hoặc engine dạng cột (SP-6) cần đến; star schema không thay đổi. Mục tiêu độ mới dữ liệu ≤ 15 phút (thông thường ≤ 2 phút).
3. **Phạm vi trong báo cáo:** một bảng `report_scope(user_account_id, employment_id)` được duy trì từ các snapshot phân quyền của Core; mọi truy vấn báo cáo và dataset BI đều join với bảng này.
4. **BI (P2): Apache Superset**, tự host, không bao giờ được public trực tiếp; nhúng với guest token theo từng user mang mệnh đề RLS; kết nối DB với RLS và thiết lập tenant cho từng truy vấn.
5. **Dữ liệu nhạy cảm** vẫn được mã hóa/token hóa trong kho, ngoại trừ các vùng chủ đề tổng hợp đã được phê duyệt, được bảo vệ bằng quyền và audit.
6. **Framework mẫu báo cáo theo luật định:** các artifact biểu mẫu có phiên bản (mẫu XSD/XLSX, ánh xạ, kiểm tra hợp lệ, ngày hiệu lực) được công bố giống như bộ tham số pháp lý; các module triển khai `StatutoryDataProvider` cho từng biểu mẫu.
7. **Xuất dữ liệu:** bất đồng bộ, giới hạn theo quota của tenant, hết hạn sau 7 ngày, áp dụng phạm vi và masking.

## Các phương án đã cân nhắc

- **Truy vấn trực tiếp OLTP cho phân tích:** cạnh tranh tài nguyên với cao điểm tính lương; join xuyên schema vi phạm ranh giới module.
- **ClickHouse làm kho:** vượt trội cho tổng hợp lớn; thêm một SQL dialect thứ hai và mô hình RLS yếu hơn. Giữ làm phương án P2 sau SP-6.
- **BI thương mại (Power BI, Tableau, Looker):** dịch vụ cloud nằm ngoài VN hoặc giấy phép on-prem đắt đỏ; khó kiểm soát RLS khi nhúng.
- **Metabase OSS:** đơn giản hơn nhưng RLS/nhúng yếu hơn ở bản mã nguồn mở.

## Hệ quả

- + OLTP được bảo vệ; một SQL dialect; cách ly được cưỡng chế ngay trong cơ sở dữ liệu kể cả với BI.
- + Các thao tác xóa được lan truyền qua CDC.
- − Logical replication không mang DDL: mọi migration của một bảng được publish đều có một bước `rpt_raw` (kiểm tra trong CI), và replication slot phải được giám sát (độ trễ, WAL được giữ lại). Chỉ dùng Debezium/Kafka Connect nếu chọn phương án Phase 2.
- − Dữ liệu báo cáo có tính nhất quán sau cùng; các màn hình cần dữ liệu hiện tại dùng OLTP.
