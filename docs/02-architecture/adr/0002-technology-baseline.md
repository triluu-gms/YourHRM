# ADR-0002: Baseline công nghệ (Java 25, Spring Boot 4, PostgreSQL 17)

- **Trạng thái:** Đã chấp nhận (2026-10-16, OD-3 — Java 25 + Spring Boot 4; phương án dự phòng Java 21 + Boot 3.5 được quyết định sau spike đầu tiên). **Amendment 1 (2026-10-16, R2-01) — Đã chấp nhận (R3-11):** messaging (Kafka, Apicurio) chuyển sang Phase 2; xem ADR-0004 rev. 2
- **Ngày:** 2026-10-16
- **Liên quan:** [architecture-overview](../architecture-overview.md) §4; ADR-0001, ADR-0017

## Bối cảnh

Điều lệ dự án chốt Java + Spring Boot và PostgreSQL. Sản phẩm phải vận hành hơn 10 năm (tái lập kết quả tính lương), hoàn toàn tại Việt Nam, với các bản phát hành không downtime. Tính đến tháng 10/2026, Java 25 là bản LTS hiện hành (tháng 9/2025) và Spring Boot 4.0 (Spring Framework 7, Jakarta EE 11) đã GA từ tháng 11/2025. PostgreSQL 17 được cung cấp rộng rãi; PostgreSQL 18 đã phát hành nhưng chưa được mọi nhà cung cấp/operator tại VN hỗ trợ.

## Quyết định

| Hạng mục | Lựa chọn |
|---|---|
| Runtime | Java 25 LTS (Eclipse Temurin); virtual thread; `ScopedValue` cho tenant context |
| Framework | Spring Boot 4.0.x, Spring Modulith 2.x, Spring Security 7 |
| Persistence | Hibernate ORM 7 cho aggregate; jOOQ cho read model, I/O khối lượng lớn của payroll và báo cáo; `COPY` cho ghi khối lượng lớn; Flyway Community theo từng schema (giấy phép được chấp nhận ngày 2026-10-16, ADR-0031) |
| Cơ sở dữ liệu | PostgreSQL 17 (18 khi nhà cung cấp/operator được chọn hỗ trợ) vận hành bằng CloudNativePG; PgBouncer transaction pooling |
| Messaging | **Phase 1:** không có — transactional outbox + polling relay + job phân phối trên job queue PostgreSQL (ADR-0004 rev. 2). **Phase 2:** Apache Kafka 4.x (KRaft) qua Strimzi (hoặc dịch vụ managed của nhà cung cấp); Apicurio Registry 3 |
| Cache | Valkey 8 + Caffeine |
| Build | Gradle (Kotlin DSL), image Jib, Testcontainers |

**Phương án dự phòng (OD-3, chấp nhận ngày 2026-10-16):** Java 25 + Spring Boot 4 là quyết định chính thức. Nếu các spike đầu tiên — walking skeleton (SP-0, tương thích thư viện) và spike payroll engine (SP-1, được nêu trong quyết định của nhà tài trợ) — phát hiện vấn đề không tương thích thư viện hoặc hiệu năng mang tính chặn với Boot 4/Java 25, sẽ chuyển sang Spring Boot 3.5.x trên Java 21 LTS. Việc chuyển đổi chỉ được quyết định một lần, vào cuối SP-1, và không làm thay đổi bất kỳ ADR nào khác.

## Các phương án đã cân nhắc

- **Java 21 + Spring Boot 3.5.** An toàn và trưởng thành, nhưng hỗ trợ OSS cho Boot 3.x kết thúc sớm hơn và sẽ cần nâng cấp phiên bản lớn trong giai đoạn pilot. Giữ làm phương án dự phòng.
- **Kotlin.** Ngắn gọn, nhưng nguồn tuyển dụng backend Kotlin tại VN nhỏ hơn; không mang lại lợi ích rõ rệt cho domain này.
- **Thuần JPA** cho toàn bộ persistence. Bị loại cho các luồng xử lý khối lượng lớn của payroll và truy vấn as-of, nơi cần SQL tường minh vì hiệu năng và tính tất định.
- **Redis 8.** Điều khoản giấy phép khó dự đoán hơn đối với nhà cung cấp cloud; Valkey tương thích giao thức và dùng giấy phép BSD.

## Hệ quả

- + Thời gian hỗ trợ dài; mô hình concurrency hiện đại (virtual thread) phù hợp với tải API và worker nặng về I/O.
- + Hai công cụ persistence cần hướng dẫn rõ ràng (ghi aggregate → JPA; khối lượng lớn/read model → jOOQ).
- − Boot 4 còn mới; người dùng sớm có thể gặp thiếu hụt thư viện (giảm thiểu bằng SP-0 và phương án dự phòng).
- − Thành viên nhóm cần được đào tạo về Modulith và jOOQ.
