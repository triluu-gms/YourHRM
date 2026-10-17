# ADR-0029: Snapshot bảng lương, kết quả bất biến và lưu trữ 10 năm

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect (ADR-0009 sở hữu việc điều phối kỳ chạy), trưởng nhóm Payroll
- **Liên quan:** NFR-PAY-001/002/003/006, NFR-SAAS-017, BR-PAY-071/072/123; gap §7; ADR-0009, ADR-0019, ADR-0025, ADR-0030; [DESIGN.md](../../../database/postgresql/DESIGN.md) §7.5, §8

## Bối cảnh

Mọi kỳ chạy đã phê duyệt phải tính lại được từ snapshot của nó với kết quả giống hệt trong ≥ 10 năm. Kết quả vào khoảng 72 triệu dòng mỗi tháng ở kịch bản định cỡ 1 triệu nhân viên. Kỳ chạy đã phê duyệt là chỉ đọc, trừ khi được mở khoá có đặc quyền với phê duyệt lần hai. ADR-0009 niêm phong snapshot trước khi tính toán và ghi kết quả theo từng chunk bằng `COPY`.

## Quyết định

1. **Snapshot cấu hình kỳ chạy** (`pay.run_config_snapshot`, khử trùng lặp theo `config_sha256`): phiên bản thành phần lương và công thức (văn bản biểu thức và hash), cấu trúc, quy tắc làm tròn, và **các giá trị pháp lý đã phân giải** kèm `legal_pack_id` + `override_version`.
2. **Snapshot đầu vào kỳ chạy** (`pay.run_input_snapshot`, một bản cho mỗi kỳ chạy × nhân viên): `payload jsonb` (TOAST lz4) chứa mọi đầu vào cùng phiên bản nguồn của nó (id hồ sơ lương và `recorded_at`, phiên bản và checksum kết quả chấm công, đầu vào biến đổi, người phụ thuộc theo tháng, YTD, khoản vay, phân chia tài khoản ngân hàng), cộng `payload_sha256`.
3. **Header kỳ chạy** đóng dấu `code_version` (engine kernel), `config_version`, `legal_pack_id`, `override_version`, các phiên bản chấm công đã sử dụng và watermark People.
4. **Kết quả:** `run_employee` (tổng, `result_sha256`) và `result_line` (thành phần × phân đoạn, `amount` đã làm tròn, `amount_unrounded`, chịu thuế, căn cứ BHXH, `retro_of_period`), partition theo khoảng **hằng tháng theo `period_month`**.
5. **Bất biến:** một trigger chặn thay đổi trên snapshot và kết quả khi kỳ chạy đã `APPROVED`. `UNLOCKED` lưu trữ kết quả trước đó vào `pay.run_result_history` trước khi tính lại.
6. **Lưu trữ:** 36 partition dòng hằng tháng được giữ nóng. Các tháng cũ hơn được nén vào `pay.result_archive` (một dòng cho mỗi kỳ chạy × nhân viên, các dòng dưới dạng JSON nén, nhỏ hơn khoảng 8×), và các partition dòng bị drop. Header, snapshot, phiếu lương, BHXH/thuế TNCN và tờ khai được giữ online trong 10 năm. Reporting store giữ 10 năm `fact_payroll_line`.
7. **Diễn tập tái tạo:** hằng tháng, một mẫu 0,5 % các kỳ chạy của năm trước được chạy lại qua đúng phiên bản kernel đã đóng dấu, và so sánh hash.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Tính lại từ bảng đang hoạt động "theo hiểu biết tại" | Phụ thuộc vào 10 năm lịch sử bi-temporal được giữ hoàn hảo ở mọi module đầu vào và vào các bảng cấu hình không thay đổi; dễ đổ vỡ. |
| Kết quả chỉ ở dạng tài liệu JSON theo từng nhân viên | GL, phân bổ chi phí, tờ khai và báo cáo cần SQL trên các dòng. Tài liệu chỉ được dùng cho tầng lưu trữ. |
| Giữ nóng toàn bộ 10 năm dòng kết quả | Khoảng 8,6 tỷ dòng; chi phí bảo trì index và vacuum tăng mà không có giá trị truy vấn (phân tích chạy trên reporting store). |

## Hệ quả

- \+ Việc tái tạo độc lập với các thay đổi sau này ở People, T&A, Core và legal pack.
- \+ Dữ liệu nóng có giới hạn, và partition giúp lưu trữ rẻ.
- − Dung lượng snapshot khoảng 110 GB/năm ở kịch bản định cỡ; tỷ lệ nén cần được kiểm chứng **[spike]**.
- − Truy vấn trên kết quả cũ hơn 36 tháng dùng bảng lưu trữ (chậm hơn), điều này chấp nhận được cho kiểm toán và tranh chấp.
- − Engine kernel và container image của chúng phải được giữ trong 10 năm (ADR-0009 §7).
