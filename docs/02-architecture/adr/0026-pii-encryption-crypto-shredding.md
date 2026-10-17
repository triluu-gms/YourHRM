# ADR-0026: Phân loại PII, envelope encryption ở tầng ứng dụng và crypto-shredding

- **Trạng thái:** Đã chấp thuận (2026-10-16, X-01; danh sách P3 được mở rộng cùng ngày với mật khẩu PDF phiếu lương, R2-17, đã chấp thuận)
- **Ngày:** 2026-10-16 (sửa đổi cho X-01)
- **Người quyết định:** Data architect, Security lead, DPO, Solution architect; PM phân xử X-01 (được nhà tài trợ phê duyệt)
- **Liên quan:** gap-analysis §9 A-9; NFR-SAAS-007, NFR-PPL-005, NFR-PAY-004, NFR-REC-004/009, NFR-ATT-008; Luật BVDLCN 2025 / Nghị định 13/2023 `[verify-legal]`; ADR-0017 (OpenBao transit); [DESIGN.md](../../../database/postgresql/DESIGN.md) §9.3–9.4; DDL `saas.tenant_key`, `saas.subject_key`, các cột `*_enc/*_bidx/*_last4`

## Bối cảnh

Các NFR yêu cầu mã hoá mức trường với khoá theo tenant cho số CCCD/hộ chiếu, mã số thuế cá nhân, tài khoản ngân hàng, dữ liệu sức khoẻ, và "số tiền lương ở nơi khả thi". Bản nháp của SA và DA bất đồng về số tiền lương; PM đã phân xử (X-01): số tiền không được mã hoá theo cột. Xoá theo DSR, thời hạn lưu hồ sơ ứng viên 12 tháng và purge tenant phải xoá dữ liệu một cách kiểm chứng được, kể cả từ replica, reporting store, file xuất và backup — vốn không thể chỉnh sửa.

## Quyết định

1. **Phân loại mọi cột** thành P0–P3 với `COMMENT 'pii:Px'`. Một kiểm tra CI từ chối các cột chưa phân loại và cấp dữ liệu cho danh mục dữ liệu cá nhân (PD inventory) của SaaS.
2. **P3** (số định danh, mã số thuế, số sổ BHXH, số tài khoản ngân hàng, tôn giáo, dân tộc, sức khoẻ/khuyết tật, GPS, tham chiếu ảnh selfie, số định danh và mã số thuế của người phụ thuộc, giấy tờ tuỳ thân của ứng viên, và mật khẩu PDF phiếu lương do người lao động đặt theo R2-17 (`pay.payslip_delivery_preference.pdf_password_enc`, write-only)) được **ứng dụng mã hoá** bằng AES-256-GCM vào `<col>_enc bytea`. Tìm kiếm bằng nhau dùng blind index `<col>_bidx` (HMAC-SHA256 với khoá tenant suy ra qua HKDF, cắt còn 16 byte); hiển thị dùng `<col>_last4`.
3. **Phân cấp khoá:** tenant KEK trong OpenBao transit (tại VN; root of trust bằng phần mềm cho MVP, root dựa trên HSM trước tier Enterprise, OD-10) → **DEK theo từng chủ thể dữ liệu** (`saas.subject_key.wrapped_dek`; người lao động, ứng viên, người nhận thanh toán vãng lai, thành viên gia đình) → các cột. Header của ciphertext mang key id và phiên bản.
4. **Crypto-shredding:**
   * mức chủ thể: `wrapped_dek := NULL` (xoá theo DSR, hết hạn lưu trữ);
   * mức tenant: huỷ tenant KEK trong OpenBao.

   Dữ liệu P2 dạng plaintext trong backup hết hạn theo thời hạn lưu backup (35 ngày), điều này được ghi nhận với DPO.
5. **P2** (số tiền lương, ngày sinh, địa chỉ, thông tin liên hệ) giữ dạng plaintext trong DB, được bảo vệ bởi RLS, grant của module, mã hoá lưu trữ, masking view (`security_invoker`, `app.mask_level`), che trường ở API (ADR-0006) và audit các lần đọc nhạy cảm. **Số tiền lương không được mã hoá theo cột (quyết định X-01, 2026-10-16).** Điều này bao gồm hồ sơ lương và các khoản phụ cấp, projection hồ sơ lương, đầu vào bảng lương, dòng kết quả, số tiền trên phiếu lương, số dư luỹ kế năm (YTD), số tiền BHXH/thuế TNCN, dòng chi lương ngân hàng và bút toán GL. NFR-PAY-004 "mã hoá lương ở nơi khả thi" được trả lời là *không khả thi* và được thay bằng các biện pháp kiểm soát bù trừ ở điểm 8.
6. `pgcrypto` chỉ được dùng để băm (`digest`) và sinh byte ngẫu nhiên, không bao giờ để mã hoá dữ liệu nghiệp vụ.
7. Keyring nằm trong platform cluster (ADR-0036), nên backup của pool cluster không bao giờ chứa DEK đã bọc.
8. **Biện pháp kiểm soát bù trừ cho số tiền lương (X-01), tất cả đều bắt buộc:**
   * RLS trên mọi bảng chứa số tiền (cách ly tenant, fail closed);
   * DB role của module: chỉ `yhrm_app_hr` (hồ sơ lương) và `yhrm_app_pay` (dữ liệu bảng lương) có grant; không role module nào khác đọc được (danh sách ngoại lệ X-02 không bao gồm bất kỳ bảng số tiền nào);
   * masking view (`hr.v_salary_record_masked`, các view tương đương của payroll) ẩn số tiền trừ khi `app.mask_level` là `NONE` hoặc `PII`; mức chưa thiết lập sẽ ẩn số tiền (fail closed);
   * che trường ở API theo field profile của người dùng (`@Sensitive(category=SALARY)`, ADR-0006) và step-up cho các màn hình xem và xuất lương hàng loạt;
   * audit mọi lần đọc nhạy cảm (`is_sensitive_read`) và mọi lần xuất có chứa số tiền;
   * event chỉ mang số tiền trên các topic Payroll bị giới hạn bằng ACL (`people.SalaryChanged`);
   * reporting store: số tiền chỉ đi qua reporting service với quyền lương và phạm vi theo từng người dùng (ADR-0032).
9. **Số định danh, tài khoản ngân hàng và dữ liệu nhạy cảm đặc biệt vẫn là P3** và được mã hoá như ở điểm 2: số CCCD/hộ chiếu/CMND, mã số thuế cá nhân và của người phụ thuộc, số sổ BHXH, số tài khoản ngân hàng, tôn giáo, dân tộc, sức khoẻ/khuyết tật, và (Phase 2) toạ độ GPS và tham chiếu ảnh selfie.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| `pgcrypto` `pgp_sym_encrypt` trong SQL | Khoá đi trong văn bản SQL và xuất hiện trong log/`pg_stat_statements`; tiến trình DB nhìn thấy plaintext; không có cách xử lý khoá theo tenant gọn gàng. |
| Chỉ TDE / mã hoá volume | Bảo vệ khỏi trộm ổ đĩa, nhưng không chống được truy cập ở mức DB, replica hay file xuất. Không có crypto-shredding. |
| Chỉ có DEK theo tenant (không có mức chủ thể) | Xoá một chủ thể sẽ cần mã hoá lại hoặc xoá dòng ở mọi nơi, kể cả các bản sao đã xuất sang reporting store. |
| Mã hoá cả số tiền lương/kết quả | Phá vỡ phép tổng hợp SQL (GL, báo cáo, phân bổ chi phí, reporting store), làm chậm tính lương cho 10k nhân viên, và đẩy toàn bộ phân tích lương vào bộ nhớ ứng dụng. Bị loại theo X-01 (2026-10-16); thay vào đó là các biện pháp kiểm soát bù trừ ở điểm 8. |

## Hệ quả

- \+ Việc xoá kiểm chứng được và có hiệu lực tức thì trên mọi bản sao chứa ciphertext.
- \+ Quản trị viên database và backup không bao giờ nhìn thấy plaintext P3.
- − Không có tìm kiếm theo khoảng, một phần hay gần đúng trên các trường P3 (chỉ tìm bằng nhau).
- − Độ sẵn sàng của OpenBao nằm trên đường găng. Tenant KEK được cache trong bộ nhớ ≤ 1 h, và chế độ suy giảm sẽ che dữ liệu P3.
- − Phải vận hành các job xoay vòng khoá và mã hoá lại dạng lazy (hoàn tất trong ≤ 90 ngày).
- − Benchmark 10k lần unwrap DEK mỗi kỳ chạy lương **[spike]**. Kỳ chạy lương chỉ cần tài khoản ngân hàng (file ngân hàng) và mã số thuế (tờ khai), không cần số tiền, nên tải có giới hạn.
- − Số tiền lương đọc được bởi bất kỳ ai nắm thông tin xác thực DB của module `hr`/`pay` hoặc quyền break-glass; điều này được chấp nhận (X-01) và được giảm thiểu bằng thông tin xác thực động, pgaudit trên break-glass, và các biện pháp che/audit ở trên. DPO ghi nhận điều này trong DPIA.
- − Root of trust: root bằng phần mềm (OpenBao) cho MVP, root dựa trên HSM trước tier Enterprise (OD-10).
