# ADR-0014: BFF cho trình duyệt, Envoy Gateway ở biên, public API và webhook

- **Trạng thái:** Đề xuất (sửa đổi 2026-10-16, R2-01: public API, webhook và route device-ingest thuộc Phase 2; Phase 1 = edge + BFF + API nội bộ)
- **Ngày:** 2026-10-16
- **Liên quan:** ADR-0005; decision log R2-01 / §7.2; SaaS BRD §4.F (public API, webhook P2); NFR-SAAS-018, NFR-CORE-014; [architecture-overview](../architecture-overview.md) §6.2, §12

## Bối cảnh

SPA xử lý dữ liệu cá nhân và dữ liệu lương có độ nhạy cảm cao. Hướng dẫn OAuth hiện hành cho ứng dụng trình duyệt khuyến nghị giữ token ngoài JavaScript (mẫu BFF / token-handler). Nền tảng cần TLS termination, WAF, rate limiting và định tuyến cho trình duyệt, ứng dụng di động Phase 2, agent thiết bị (mTLS) và một public API P2 với OAuth2 client credentials và webhook. Controller ingress-nginx cộng đồng đã ngừng phát triển, nên cần một chuẩn edge mới.

## Quyết định

0. **Phân chia Phase (R2-01).** **Phase 1** không có bên tiêu thụ API bên ngoài: edge chỉ phục vụ trình duyệt (admin + ESS web thông qua BFF) và các deep link trong e-mail. Public API, webhook, API client, developer portal và route device-ingest (`ingest.yourhrm.vn`, mTLS) thuộc **Phase 2**. API nội bộ vẫn được thiết kế theo quy ước public (quyết định 3) để tập con public của Phase 2 chỉ là việc tuyển chọn, không phải viết lại.
1. **Edge: Envoy Gateway** (Kubernetes Gateway API) với WAF OWASP CRS (WAF của nhà cung cấp hoặc Coraza), TLS 1.2+/1.3, rate limiting toàn cục, route cho ứng dụng (Phase 1) và cho public API cùng ingest với mTLS (Phase 2).
2. **`hrm-bff`** (Spring Cloud Gateway server WebMVC + Spring Security OAuth2 client): Authorization Code + PKCE với Keycloak; token lưu phía server (Valkey, mã hoá); cookie HttpOnly `Secure` `SameSite=Strict`; chống CSRF; chuyển tiếp access token tới `hrm-api`; xử lý chuyển hướng step-up.
3. **Quy ước API:** OpenAPI 3.1 theo từng module, lỗi theo RFC 9457, cursor pagination, ETag/If-Match, `Idempotency-Key`, tham số `asOf`/`knownAt`.
4. **Public API (P2):** tập con được tuyển chọn `/public/v1`; OAuth2 client credentials; scope được Core ánh xạ sang quyền + phạm vi dữ liệu; hạn mức theo client; tương thích ngược trong cùng major; thời hạn deprecation ≥ 6 tháng. Chỉ dùng sản phẩm API management chuyên dụng (APISIX/Kong) nếu cần developer portal có tính phí.
5. **Webhook (P2):** các sự kiện được tuyển chọn, tối thiểu hoá PII; chữ ký HMAC-SHA256 kèm timestamp; retry trong 24 h; nhật ký gửi và replay; tự động vô hiệu hoá; xoay vòng secret.

## Các phương án đã cân nhắc

- **SPA giữ token trong bộ nhớ (public client):** đơn giản hơn, nhưng token bị lộ trước XSS; xử lý refresh token trong trình duyệt yếu hơn. Bị loại vì dữ liệu nhạy cảm.
- **Kong / APISIX làm edge chính:** đủ năng lực; nhiều thành phần hơn mức cần cho MVP.
- **GraphQL:** truy vấn linh hoạt, nhưng làm phức tạp việc che trường (field-level masking), caching và rate limiting; chọn REST.

## Hệ quả

- + Token không bao giờ đến JavaScript của trình duyệt; chính sách edge nhất quán.
- − Thêm một thành phần deploy (`hrm-bff`) trên đường xử lý request; phải HA và độ trễ thấp.
- − Hạ tầng gửi webhook phải xây ở P2 (tái sử dụng integration worker và, từ Phase 2, Kafka, ADR-0004 rev. 2; payload webhook là các sự kiện catalogue được tuyển chọn).
