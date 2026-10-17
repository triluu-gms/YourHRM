# ADR-0018: Observability với OpenTelemetry và Grafana stack tự host tại Việt Nam

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Liên quan:** NFR-SAAS-010 (log/metric/trace gắn tenant, ≥ 12/24 tháng tại VN), NFR-SAAS-001, GAP-069; [architecture-overview](../architecture-overview.md) §14.5

## Bối cảnh

Đội hỗ trợ và SRE cần log, metric và trace gắn tenant và correlation id, dashboard theo tenant, cảnh báo SLO, và giám sát nghiệp vụ (lệch khi chốt kỳ, hạn quyết toán, lỗi kiểm tra theo quy định). Log phải được lưu ≥ 12 tháng (bảo mật ≥ 24) tại Việt Nam với dữ liệu cá nhân được che. Các nhà cung cấp observability dạng SaaS sẽ đưa dữ liệu ra khỏi Việt Nam.

## Quyết định

1. **OpenTelemetry** ở mọi nơi (Java agent + Micrometer; Faro cho RUM frontend); OTel Collector dạng DaemonSet + gateway với các processor lọc PII.
2. **Backend:** Grafana **Mimir** (metric), **Loki** (log), **Tempo** (trace), dashboard Grafana và Alertmanager; lưu trữ trên object storage tại VN với retention phân tầng (hot 30 ngày, cold tới 12/24 tháng).
3. **Gắn tenant:** `tenant_id` và `correlation_id` trên log/span; metric chỉ mang tenant trên các series top-N có giới hạn để kiểm soát cardinality.
4. **SLO** với cảnh báo burn-rate nhiều cửa sổ: độ sẵn sàng API, đăng nhập (SLO nội bộ 99,9%), độ trễ p95, thời gian chạy bảng lương, độ trễ relay, độ trễ gửi event và các lượt gửi `DEAD` (bus dựa trên DB ở Phase 1; kích thước DLT từ Phase 2), độ tươi của dữ liệu sao chép sang reporting, thông lượng import; xác nhận lượt chấm công từ Phase 2 (R2-01).
5. **Giám sát nghiệp vụ** dưới dạng các kiểm tra theo lịch phát ra metric.
6. Sự kiện bảo mật cũng được chuyển tiếp tới SIEM (Wazuh hoặc rule dựa trên Grafana) **[spike]**.

## Các phương án đã cân nhắc

- **Datadog / New Relic / Grafana Cloud:** dữ liệu rời khỏi Việt Nam; bị loại.
- **ELK/OpenSearch cho log:** vận hành nặng hơn ở mức retention 12–24 tháng so với Loki trên object storage.

## Hệ quả

- + Chuẩn mở; lưu trú tại VN; một giao diện duy nhất.
- − Stack phải được vận hành và định cỡ (ước tính khối lượng log trong SP-0).
