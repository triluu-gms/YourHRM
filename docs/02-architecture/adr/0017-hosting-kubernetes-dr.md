# ADR-0017: Hosting tại Việt Nam trên Kubernetes; quản lý secret và khoá; khôi phục thảm hoạ

- **Trạng thái:** Đề xuất (sửa đổi 2026-10-16, R2-01: Strimzi/Kafka và MirrorMaker 2 chuyển sang Phase 2; R2-15: không có con người nào truy cập thông tin xác thực DB của ứng dụng)
- **Ngày:** 2026-10-16
- **Liên quan:** BRD A4, C-2, DP3; NFR-SAAS-001/006/007/008/014; backup/PITR và phân cấp khoá mã hoá trong kiến trúc dữ liệu (DESIGN.md); [architecture-overview](../architecture-overview.md) §13, §14

## Bối cảnh

Mọi dữ liệu cá nhân — bao gồm log, backup, chỉ mục tìm kiếm và công cụ hỗ trợ — phải nằm tại Việt Nam. Nền tảng cần độ sẵn sàng ≥ 99,5% (99,9% với Enterprise), RPO ≤ 15 phút, RTO ≤ 4 h (≤ 2 h với Enterprise) với trung tâm dữ liệu thứ hai tại VN, release không downtime, secret được xoay vòng ≤ 90 ngày, và mã hoá mức trường với khoá theo tenant trong KMS/HSM. Các nhà cung cấp ứng viên: Viettel IDC/Viettel Cloud, FPT Cloud, VNG Cloud, CMC Cloud; hyperscaler chỉ khi có region đặt tại VN và sub-processor lưu trú tại VN.

## Quyết định

1. **Kubernetes** (do nhà cung cấp quản lý nếu đủ trưởng thành, tuân thủ CNCF) tại DC1 (chính) và DC2 (DR), tách cluster prod và non-prod; node pool cho app, worker, data, platform.
2. **Operator để đảm bảo tính di động:** CloudNativePG (PostgreSQL), Strimzi (Kafka, **Phase 2**, ADR-0004 rev. 2), Valkey operator/Helm, Argo CD (GitOps), cert-manager, KEDA, Kyverno, Cilium (network policy + mã hoá trong suốt).
3. **Secret & khoá: OpenBao** (tương thích Vault) với Kubernetes auth, thông tin xác thực DB động, transit engine cho envelope encryption (KEK theo tenant; DEK được bọc); auto-unseal bằng KMS/HSM của nhà cung cấp nếu có; root of trust bằng phần mềm cho MVP và root dựa trên HSM trước khi có gói Enterprise (OD-10, chấp thuận 2026-10-16); chọn nhà cung cấp sau đợt đánh giá SP-7 kéo dài 2 tuần (OD-1). **Không có con người nào truy cập thông tin xác thực của ứng dụng (R2-15, đã chấp thuận):** login role DB của module (`yhrm_app_*`), role relay, sealer và CDC chỉ nhận thông tin xác thực động qua OpenBao Kubernetes auth gắn với service account của chúng; không có policy OpenBao nào cho phép operator hay developer đọc chúng; con người chỉ truy cập database qua `yhrm_breakglass` (phê duyệt hai người, ≤ 4 h, pgaudit trên mọi câu lệnh, ghi lại phiên), và mọi lần đăng nhập bằng role của module từ bên ngoài pod ứng dụng đều phát cảnh báo.
4. **DR:** CNPG replica cluster tại DC2 + lưu trữ WAL vào object storage DC2 (`archive_timeout` 5 phút) + base backup hằng ngày (35 ngày); Kafka MirrorMaker 2 cùng tái phát hành outbox (Phase 2; ở Phase 1 outbox và job gửi event là các dòng trong database và failover cùng database); sao chép object storage giữa hai site; Argo CD tại DC2; chuyển DNS bằng script; diễn tập hằng quý. Enterprise: hot standby với ứng dụng được scale sẵn.
5. **Chọn nhà cung cấp** qua SP-7 (chấm điểm: độ trưởng thành K8s managed, hai DC cách nhau ≥ 50 km, sao chép S3, KMS/HSM, mạng, chứng chỉ tuân thủ, giá, hỗ trợ); cùng một nhà cung cấp cho DC1/DC2 ở MVP; IaC bằng OpenTofu để vẫn có thể dùng nhà cung cấp thứ hai.

## Các phương án đã cân nhắc

- **VM không dùng Kubernetes:** đơn giản hơn cho đội nhỏ, nhưng rollout không downtime, autoscaling cho giờ cao điểm và worker theo pool sẽ phải tự xây.
- **PostgreSQL/Kafka do nhà cung cấp quản lý:** chấp nhận được nếu có PITR, logical decoding trên standby, replica liên DC và lưu trú tại VN; operator vẫn là mặc định để đảm bảo tính di động.
- **HashiCorp Vault:** license BSL; OpenBao là bản fork mở với API tương thích.
- **Hyperscaler ngoài VN:** vi phạm yêu cầu lưu trú dữ liệu; bị loại.

## Hệ quả

- + Di chuyển được giữa các nhà cung cấp VN; GitOps giúp dựng lại DR có thể lặp lại.
- − Vận hành các data operator đòi hỏi kỹ năng SRE; cần một platform engineer từ sprint 1.
- − Chi phí cho một site DR warm; giảm thiểu bằng cách scale ứng dụng ở DC2 về gần 0.
