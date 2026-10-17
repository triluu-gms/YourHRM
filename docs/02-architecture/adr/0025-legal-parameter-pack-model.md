# ADR-0025: Mô hình dữ liệu legal parameter pack và đóng dấu phiên bản

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Người quyết định:** Data architect, Solution architect (ADR-0010 sở hữu API và caching), người phụ trách nội dung pháp quy
- **Liên quan:** Architecture brief B6; gap-analysis §5 #1, §9 A-6; NFR-PAY-003/007, NFR-SAAS-016/017; ADR-0010; [DESIGN.md](../../../database/postgresql/DESIGN.md) §6; DDL `001_saas.sql` (`saas.legal_param_*`)

## Bối cảnh

Mọi giá trị theo luật định (lương tối thiểu vùng và bảng ánh xạ đơn vị hành chính → vùng, tỷ lệ và mức trần BHXH/BHYT/BHTN, biểu thuế và các khoản giảm trừ thuế TNCN, giới hạn làm thêm giờ, số ngày phép được hưởng, lộ trình tuổi nghỉ hưu …) phải có ngày hiệu lực, có phiên bản, chỉ được tenant override ở nơi luật cho phép, được đóng dấu trên mọi kết quả, và tái tạo được trong 10 năm. Luật mới có thể có hiệu lực hồi tố. Một cấu trúc thuế TNCN mới không được đòi hỏi thay đổi schema (NFR-PAY-007). BRD Payroll đặt các bảng này trong `pay`; gap analysis đã chuyển quyền sở hữu sang SaaS.

## Quyết định

1. Các bảng nằm trong `saas` và là **global thuần tuý** (không có `tenant_id`), được sao chép chỉ đọc tới mọi cluster (ADR-0036):
   * `legal_param_definition` chứa khoá có kiểu: `value_type` ∈ `SCALAR/TABLE/MAP/CALENDAR_TEMPLATE` (ADR-0010), `scalar_type`, đơn vị, JSON schema, `override_rule` ∈ `NONE/ANY/FLOOR/CAP`, và `override_scope`.
   * `legal_param_pack` có `pack_version` tăng đơn điệu và vòng đời `DRAFT → IN_TEST → APPROVED → PUBLISHED → SUPERSEDED` (+ `WITHDRAWN`), kèm `content_sha256`.
   * `legal_param_value` (pack, khoá, `[valid_from, valid_to)`, `value_num` | `value_text` | `value_json`, căn cứ pháp lý) có exclusion constraint theo pack và khoá.
   * `legal_param_table_row` chứa các bảng ánh xạ lớn.
2. **Một pack là một snapshot luỹ kế, bất biến**: nó chứa mọi khoá cùng toàn bộ lịch sử hiệu lực. Một trigger cấm mọi thay đổi nội dung khi pack đã rời `DRAFT/IN_TEST`. Tra cứu = `(pack_id, key, valid_during @> date)`.
3. **Override của tenant** nằm trong `legal_param_override_set` (tenant, `override_version` tăng đơn điệu, phê duyệt qua workflow) và `legal_param_override` (tuỳ chọn theo pháp nhân hoặc địa điểm). `FLOOR`/`CAP` được kiểm tra so với giá trị luật định cho cùng khoảng ngày, khi kích hoạt và lại một lần nữa mỗi khi phát hành pack mới.
4. **Đóng dấu:** mọi version kết quả chấm công, kỳ chạy lương và snapshot, mô phỏng offer và tính toán điều kiện hưởng đều lưu `legal_pack_id` + `override_version`. Kỳ chạy lương còn đóng băng **các giá trị đã phân giải** trong snapshot cấu hình của kỳ chạy (ADR-0029).
5. **Luật hồi tố:** một pack sau có thể thay đổi các giá trị ở ngày quá khứ. Kết quả đã phê duyệt giữ nguyên dấu của mình. Event phát hành liệt kê các khoá và ngày bị thay đổi, để Payroll và T&A có thể tạo các hạng mục công việc truy lĩnh/truy thu (ADR-0010 §6).

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Delta pack (mỗi pack chỉ chứa thay đổi) | Phân giải cần phát lại một chuỗi; việc tái tạo phụ thuộc vào việc mọi pack trước đó còn nguyên vẹn; khó diff và kiểm thử hơn. Snapshot đầy đủ có kích thước nhỏ (hàng trăm khoá). |
| Một dòng EAV chung cho mỗi giá trị, không có pack | Không có đơn vị phát hành nguyên tử, không có đơn vị để chuyên gia ký duyệt, không có dấu rõ ràng. |
| Bảng có kiểu theo từng nhóm tham số (`pit_bracket`, `si_rate` …) | Mỗi cấu trúc pháp lý mới đều cần thay đổi schema và release (vi phạm NFR-PAY-007). |
| Tenant giữ bản sao của pack | Override âm thầm lệch khỏi luật; không có nguồn luật định duy nhất. |

## Hệ quả

- \+ Một dấu (pack + override version) xác định đầy đủ các đầu vào theo luật định. Các pack cũ vẫn truy vấn được trong 10 năm.
- \+ Giá trị có cấu trúc được kiểm tra bằng JSON schema hấp thụ được các hình dạng pháp lý mới mà không cần DDL.
- − Kiểu của giá trị JSON được ứng dụng ép buộc (JSON schema), không phải bởi kiểu của PostgreSQL.
- − Kiểm tra FLOOR/CAP cần ngữ nghĩa so sánh được theo từng khoá; khoá có giá trị dạng cấu trúc mặc định là `NONE` cho tới khi có comparator được định nghĩa.
