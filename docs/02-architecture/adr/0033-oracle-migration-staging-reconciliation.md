# ADR-0033: Migration hệ thống Oracle cũ — các lớp staging, bảng ánh xạ khoá, chất lượng dữ liệu và đối soát

- **Trạng thái:** Được thay thế bởi [ADR-0037](0037-opening-balance-import.md) (2026-10-16, D-09: không migration từ Oracle; YourHRM được xây dựng như một hệ thống mới)
- **Ngày:** 2026-10-16

> **Đã được thay thế.** Chỉ giữ lại để lưu hồ sơ. Nhà tài trợ đã quyết định (D-09) rằng dữ liệu Oracle của pilot không được migration. Các lớp `stg_raw`/`stg_clean`/`mig`, bảng ánh xạ khoá, bảng ánh xạ mã cũ và chạy song song dưới đây **không được xây dựng**. Vị thế khởi đầu khi go-live được nạp qua chức năng import số dư đầu kỳ chung (ADR-0037). Các quy tắc chất lượng dữ liệu và kiểm tra đối soát trong ADR này đã được chuyển sang ADR-0037 dưới dạng không phụ thuộc nguồn.
- **Người quyết định:** Data architect (ánh xạ, staging, thiết kế đối soát), Solution architect (quy trình và công cụ), trưởng nhóm migration SaaS, người phụ trách dữ liệu của khách hàng pilot
- **Liên quan:** BRD O4, A5, RK3; SaaS BRD P9, BR-SAAS-071–073, R3; NFR-PPL-007; gap-analysis §5 #12/#12b; README (thông tin xác thực Oracle chỉ lưu cục bộ); [DESIGN.md](../../../database/postgresql/DESIGN.md) §13

## Bối cảnh

Pilot migration từ một database HR Oracle trong vòng ≤ 16 tuần, với ≥ 1 (mục tiêu 2) kỳ lương chạy song song. Các vấn đề chất lượng dữ liệu dự kiến: trùng lặp, thiếu số định danh, địa chỉ ba cấp cũ, lịch sử YTD và BHXH không đáng tin cậy. Số dư đầu kỳ sai sẽ làm hỏng quyết toán thuế TNCN năm và trợ cấp thôi việc. Chạy lại phải idempotent. Dữ liệu và thông tin xác thực phải nằm tại VN và ngoài repository.

## Quyết định

1. Một **database migration** riêng tại VN với `tenant_id` + RLS:
   * `stg_raw`: bản sao dạng text 1:1 của các bảng Oracle cộng `batch_id`, `source_rowid`, `row_hash`, được trích xuất tại cùng một SCN nhất quán;
   * `stg_clean`: đã định kiểu và chuẩn hoá (tiếng Việt NFC, định dạng số định danh, tách họ tên, địa chỉ được ánh xạ qua `core.admin_unit_mapping`);
   * `mig`: `key_map`, `code_map`, `dq_rule`, `dq_issue`, `recon_run`, `recon_result`, `legacy_payroll_result`, `parallel_diff`.
2. **Idempotency:** `mig.key_map(source_system, source_table, source_key → target_id)` gán vĩnh viễn một UUIDv7 cho mỗi khoá cũ, nên các lần nạp là upsert. Trích xuất delta dùng `row_hash`.
3. **Kênh nạp:** import engine của Core ở chế độ migration → API của module. Outbox event được gắn cờ `migration=true` và bị relay chặn, và projection được đồng bộ lại sau đó.
4. **Lịch sử:**
   * lịch sử valid-time được nạp nguyên trạng;
   * `recorded_at` bi-temporal = timestamp cut-over với `source='MIGRATION'`;
   * số dư đầu kỳ vào `pay.ytd_balance`, `pay.si_contribution` (`MIGRATED`), `att.leave_balance_ledger` (`MIGRATED`) và `pay.loan`.
5. **Quy tắc chất lượng dữ liệu** (chặn trừ khi được miễn trừ và ký duyệt): trùng người (blind index CCCD, hoặc họ tên + ngày sinh + số điện thoại), CCCD hoặc mã số thuế không hợp lệ, khoảng thời gian chồng lấn, lương dưới mức lương tối thiểu, căn cứ BHXH vượt mức trần, người phụ thuộc bị khai hai lần, ngân hàng hoặc đơn vị hành chính chưa được ánh xạ, và YTD không nhất quán.
6. **Cổng đối soát** trước production:
   * headcount theo pháp nhân/tổ chức/trạng thái/loại, khớp chính xác;
   * số bản ghi và hash tổng theo từng entity, khớp chính xác;
   * YTD tổng thu nhập/thu nhập chịu thuế/thuế TNCN/thực lĩnh theo nhân viên và tháng, 0 VND;
   * tổng thuế TNCN năm, 0 VND;
   * số tháng và số tiền lịch sử BHXH, khớp chính xác;
   * số dư phép, 0,0 ngày;
   * khoản vay, 0 VND.

   Khách hàng ký duyệt trong `reconciliation_run` của SaaS.
7. **Chạy song song:** kết quả của hệ thống cũ được import hằng tháng. YourHRM chạy `PROVISIONAL` trên cùng đầu vào, và `mig.parallel_diff` so sánh theo nhân viên × thành phần. Chấp nhận = thực lĩnh, thuế TNCN và BHXH bằng nhau tới từng VND, hoặc mọi chênh lệch đều được giải thích và ký duyệt.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| ETL trực tiếp Oracle → đích không qua staging | Không phát lại được, không có bằng chứng DQ, không có vết đối soát; không đáp ứng yêu cầu ký duyệt. |
| Nạp thẳng vào bảng đích bằng SQL | Bỏ qua kiểm tra domain, event và audit; projection sẽ lệch. |
| UUIDv5 tất định từ khoá cũ thay vì bảng ánh xạ khoá | Không sắp theo thời gian (tính cục bộ của index). Xung đột giữa các hệ thống nguồn vẫn cần kỷ luật namespace, và bảng ánh xạ còn ghi lại nguồn gốc dữ liệu. |
| Tái dựng transaction time của hệ thống cũ | Nguồn hiếm khi có lịch sử thay đổi đáng tin cậy; độ chính xác giả tạo. |

## Hệ quả

- \+ Migration lặp lại được, kiểm toán được với cổng chất lượng cứng. Cũng tái sử dụng được cho các khách hàng sau (các lớp `stg`/`mig` chung).
- − Database migration là một môi trường bổ sung cần bảo mật (tại VN, mã hoá, ghi log truy cập, huỷ sau go-live cộng thời hạn lưu đã thoả thuận với khách hàng).
- − Truy vấn "theo hiểu biết tại" trước cut-over trả về góc nhìn tại cut-over (D-09).
