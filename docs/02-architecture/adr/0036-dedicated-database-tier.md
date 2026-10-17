# ADR-0036: Platform cluster, pool cluster và tier database riêng với schema giống hệt nhau

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect, PM (các gói thương mại)
- **Liên quan:** Architecture brief B4; NFR-SAAS-001/005/006/019; ADR-0003 (`saas.tenant_placement`, ~100k nhân viên mỗi pool cluster), ADR-0011 (vị trí đặt job queue), ADR-0017, ADR-0020, ADR-0026; [DESIGN.md](../../../database/postgresql/DESIGN.md) §3.1, §3.7

## Bối cảnh

SA overview đặt schema `saas` trong một **platform cluster**, với các pool cluster cho tenant dùng chung và một database riêng cho tenant Enterprise, được chọn bởi `saas.tenant_placement`. Khách hàng Enterprise kỳ vọng tách biệt vật lý, khôi phục riêng và SLA 99,9 %. Tenant có thể di chuyển giữa các cluster. ADR-0011 để lại việc đặt các bảng job queue cho data architect.

## Quyết định

1. **Một DDL ở mọi nơi.** Mọi database (platform, pool, riêng) được tạo từ cùng các migration, với mọi schema, `tenant_id` và RLS. Một DB riêng chỉ đơn giản là một cluster có một tenant.
2. **Loại vị trí đặt** quyết định nơi mỗi bảng là nguồn gốc chính thức:
   * **PLATFORM** (chỉ ở platform cluster): gói dịch vụ, subscription, hoá đơn, support grant, keyring (`tenant_key`, `subject_key`), registry đồng ý/DSR, soạn legal pack, audit nền tảng.
   * **REPLICATED** (soạn ở platform cluster, có bản sao logical replication chỉ đọc ở mọi pool cluster và cluster riêng): `saas.tenant`, `tenant_placement`, `entitlement`, `legal_param_*` đã phát hành, `retention_policy`, và master data global của Core.
   * **LOCAL** (mỗi cluster): mọi schema module, `audit`, mọi outbox/inbox, và **`saas.job`**. Job queue là theo cluster, nên job được enqueue trong transaction nghiệp vụ; việc nhận job đi qua `saas.claim_jobs()` với hạn mức fair-share theo tenant.
3. **Di chuyển** (pool → pool, pool → riêng) dùng định dạng export/import tenant hoặc logical replication có lọc theo `tenant_id`, ngoài khoảng ngày 25–5. Id UUIDv7 khiến việc này không có xung đột (ADR-0023).
4. **Khôi phục theo tenant** cho tenant dùng chung = PITR cluster vào một instance tạm, sau đó export tenant → import vào sandbox. Tenant riêng khôi phục nguyên bản.
5. **Reporting:** tenant riêng có reporting database riêng; tenant pool dùng chung một database cho mỗi vùng pool (ADR-0032).

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Tier riêng với schema rút gọn (không có `tenant_id`/RLS) | Hai luồng code và hai bộ migration; tenant được di chuyển sẽ cần viết lại dữ liệu. |
| `saas` trong mọi pool cluster như một master đầy đủ | Registry multi-master, tính phí bị chia giữa các cluster, và bản sao keyring trong mọi backup. |
| Job queue toàn cục trong platform cluster | Enqueue sẽ không nguyên tử với thay đổi nghiệp vụ (cần 2PC hoặc một chặng outbox); độ trễ liên cluster trên mọi job. |

## Hệ quả

- \+ Một schema, một bộ test, và tenant có thể di chuyển.
- \+ Backup của pool không bao giờ chứa keyring, điều này củng cố crypto-shredding (ADR-0026).
- − Logical replication của các bảng REPLICATED phải được vận hành và giám sát (cảnh báo độ trễ); đẩy qua release job là phương án dự phòng **[spike]**.
- − Các dòng code-value global được sao chép vào cluster không được xung đột với dòng của tenant (sentinel tenant ngăn điều này).
- − Job worker kết nối tới mọi cluster mà chúng phục vụ; hạn mức fair-share là theo cluster, không phải toàn nền tảng.
