# ADR-0027: Audit log chống giả mạo với niêm phong hash-chain bất đồng bộ

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Security lead, Solution architect
- **Liên quan:** gap-analysis §5 #13; NFR-CORE-008, NFR-PPL-006, NFR-PAY-006, BR-SAAS-037; SA overview §audit; [DESIGN.md](../../../database/postgresql/DESIGN.md) §9.2; DDL `002_core.sql` (`audit.*`)

## Bối cảnh

Việc ghi audit phải là một phần của transaction nghiệp vụ (hoặc được đảm bảo qua outbox), có tính chống giả mạo (tamper-evident), bao phủ các lần đọc nhạy cảm, và truy vấn được (một bản ghi trong một năm trong ≤ 3 s). Thời hạn lưu kéo dài tới 10 năm đối với các thao tác liên quan đến bảng lương. Một hash chain theo tenant đơn giản được tính bên trong mỗi transaction nghiệp vụ sẽ tuần tự hoá mọi thao tác ghi của một tenant trên đầu chuỗi, điều không thể chấp nhận vào giờ cao điểm tính lương và chấm công.

## Quyết định

1. `audit.audit_event`: một schema `audit` riêng (do Core sở hữu), partition theo khoảng hằng tháng, PK `(tenant_id, id, occurred_at)`, RLS. Bảng chứa người thực hiện, thực hiện thay mặt cho, operator, support grant, hành động, đối tượng, mục đích, request/correlation id, IP và một **diff đã được che** (`*_enc`/`*_bidx` chỉ xuất hiện dưới dạng `"<encrypted:changed>"`).
2. **Ghi** diễn ra trong transaction nghiệp vụ: được service ghi tường minh cho các hành động nghiệp vụ và lần đọc nhạy cảm, và bởi trigger chung `audit.tg_row_audit` trên các bảng cấu hình và dữ liệu gốc HR. Bảng nằm trong cùng database đóng vai trò outbox của chính nó. Nạp hàng loạt (import số dư đầu kỳ, ADR-0037) có thể chuyển sang event tóm tắt theo từng batch (`app.audit_mode = 'BATCH'`, người thực hiện `IMPORT`).
3. **Niêm phong là bất đồng bộ.** `audit.seal_batch(tenant)` (role `yhrm_audit_sealer`, chạy mỗi vài giây) khoá `audit.chain_head`, lấy các dòng chưa niêm phong cũ hơn 2 s theo thứ tự id, và ghi `chain_seq`, `prev_hash` và `row_hash = sha256(prev_hash ‖ canonical(row))`. Thứ tự chuỗi là thứ tự niêm phong.
4. **Neo (anchoring):** đầu chuỗi hằng ngày của mỗi tenant được ghi vào `audit.chain_anchor` và vào object storage WORM tại DC2. `audit.verify_chain()` phát hiện chỉnh sửa, xoá (khuyết sequence) và đổi thứ tự.
5. **Bất biến:** app role chỉ có `INSERT`; sealer chỉ có thể update các cột niêm phong (column grant cộng guard trigger); không ai có `DELETE`. Partition được detach và lưu trữ sang Parquet WORM sau 24 tháng; các nhóm liên quan đến bảng lương được giữ 10 năm.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Chuỗi đồng bộ theo từng transaction | Tuần tự hoá thao tác ghi theo tenant; tính lương và thay đổi HR hàng loạt sẽ xếp hàng chờ trên một row lock. |
| Hash chain theo từng dòng không có sequence | Không phát hiện được việc xoá các dòng mới nhất hoặc đổi thứ tự. |
| Sổ cái bất biến bên ngoài (ví dụ dịch vụ kiểu QLDB) | Không có ở các nhà cung cấp đặt tại VN; thêm một phụ thuộc trên đường ghi. |
| Chỉ audit trong log ứng dụng | Không truy vấn được theo bản ghi, không có tính transaction, và thời hạn lưu log khác. |

## Hệ quả

- \+ Không tuần tự hoá thao tác ghi. Giả mạo bên trong DB có thể phát hiện được, và giả mạo các điểm neo còn cần quyền truy cập storage WORM.
- − Có một khoảng thời gian vài giây chứa các dòng chưa niêm phong (khả năng phát hiện giả mạo bắt đầu từ lúc niêm phong). Điều này chấp nhận được và được giám sát bằng cảnh báo tuổi của dòng chưa niêm phong.
- − DDL smoke test đã xác nhận việc niêm phong hoạt động và một dòng bị chỉnh sửa được phát hiện tại số sequence của nó.
- − Khối lượng audit lớn (~40 event mỗi nhân viên mỗi tháng), nên phải vận hành partition, lưu trữ và index truy vấn.
