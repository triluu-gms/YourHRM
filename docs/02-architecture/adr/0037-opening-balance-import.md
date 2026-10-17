# ADR-0037: Import số dư đầu kỳ — template Excel, staging, kiểm tra, đối soát, commit

- **Trạng thái:** Đã chấp thuận (2026-10-16, D-09)
- **Ngày:** 2026-10-16
- **Thay thế:** [ADR-0033](0033-oracle-migration-staging-reconciliation.md) (migration hệ thống Oracle cũ)
- **Người quyết định:** Data architect (bảng đích, kiểm tra hợp lệ và kiểm tra đối soát), Solution architect (pipeline import và template), trưởng nhóm Core (import engine), trưởng nhóm Payroll / T&A / People, người phụ trách dữ liệu của khách hàng pilot
- **Liên quan:** decision-log D-09 và diễn giải tại §6; architecture brief baseline 7; GAP-005 (import engine của Core); BR-CORE-114; NFR-PPL-007; ADR-0021, ADR-0022, ADR-0024, ADR-0026, ADR-0027, ADR-0028; [architecture-overview](../architecture-overview.md) §9.6; [DESIGN.md](../../../database/postgresql/DESIGN.md) §13; DDL `core.import_job`, `core.import_row`, `core.import_reconciliation` (`002_core.sql`)

## Bối cảnh

Nhà tài trợ đã quyết định (D-09) rằng **không có migration từ Oracle**: YourHRM là một hệ thống mới và pilot không chuyển lịch sử cũ sang. Một đợt go-live giữa năm vẫn cần vị thế khởi đầu chính xác:

* **dữ liệu gốc nhân viên** (cá nhân, quan hệ lao động, phân công hiện tại, hồ sơ lương hiện tại, hợp đồng, người phụ thuộc, tài khoản ngân hàng) tại thời điểm go-live;
* **thu nhập luỹ kế năm (YTD), thuế TNCN đã khấu trừ và các khoản đóng BHXH** của năm tính thuế hiện tại, vì quyết toán thuế TNCN cuối năm (05/QTT) và lương tháng 13 / thưởng Tết dựa trên cả năm;
* **lịch sử đóng BHXH** (số tháng và căn cứ đóng), là cơ sở cho tờ khai giảm BHXH, điều kiện hưởng ốm đau/thai sản và tính trợ cấp thôi việc/thất nghiệp;
* **số dư phép** (được hưởng, đã nghỉ, chuyển sang) theo loại nghỉ phép và năm;
* **khoản vay và tạm ứng còn dư nợ**.

Khách hàng có thể cung cấp các dữ liệu này từ bất kỳ hệ thống cũ hay bảng tính nào, nên giải pháp phải không phụ thuộc nguồn, lặp lại được cho mọi tenant mới (không phải dự án một lần), được kiểm tra trước khi bất cứ thứ gì đến bảng đích, và được đối soát với các số tổng do khách hàng ký xác nhận.

## Quyết định

1. **Một pipeline chung, chạy bởi import engine của Core** ở một chế độ riêng `OPENING_BALANCE` (`core.import_job.mode`). Không có database migration, không có schema `stg_raw`/`stg_clean`/`mig` và không có bảng ánh xạ khoá cũ.
2. **Template (Excel, vi/en)**, có phiên bản và do module sở hữu dữ liệu sở hữu:

   | Loại import | Module sở hữu | Đích (schema của chủ sở hữu) |
   |---|---|---|
   | `OB_EMPLOYEE_MASTER` (cá nhân, quan hệ lao động, phân công hiện tại, hồ sơ lương, hợp đồng, người phụ thuộc, tài khoản ngân hàng) | People | `hr.*` |
   | `OB_YTD_PAYROLL` (theo nhân viên × tháng của năm tính thuế hiện tại: tổng thu nhập, thu nhập chịu thuế, thuế TNCN đã khấu trừ, BHXH NLĐ/NSDLĐ, thực lĩnh) | Payroll | `pay.ytd_balance` (`source = OPENING`) |
   | `OB_SI_HISTORY` (theo nhân viên × tháng: căn cứ đóng, chế độ, số tiền NLĐ/NSDLĐ; từ sổ BHXH / VSS C12) | Payroll | `pay.si_contribution` (`status = OPENING`) |
   | `OB_LEAVE_BALANCE` (theo nhân viên × loại nghỉ phép × năm: được hưởng, đã nghỉ, chuyển sang, cuối kỳ) | T&A | `att.leave_balance_ledger` (`txn_type = OPENING`) |
   | `OB_LOAN` (theo nhân viên × khoản vay: gốc, dư nợ, kế hoạch trả góp) | Payroll | `pay.loan`, `pay.loan_schedule` |

   Mọi template đều có một **sheet kiểm soát** chứa các số tổng do khách hàng khai báo (headcount theo pháp nhân, tổng YTD tổng thu nhập/thu nhập chịu thuế/thuế TNCN theo tháng, số tháng BHXH, số ngày phép cuối kỳ, dư nợ vay).
3. **Pipeline:** tải template → tải lên (quét mã độc, sha256) → **parse vào staging** (`core.import_row`, một dòng cho mỗi dòng của sheet; toàn bộ dòng đã parse được mã hoá bằng một DEK theo từng job vì có thể chứa CCCD, mã số thuế hoặc số tài khoản ngân hàng, ADR-0026; chỉ các trường xem trước không nhạy cảm ở dạng rõ) → **kiểm tra** bởi các validator của module sở hữu (định dạng, tham chiếu, quy tắc nghiệp vụ; lỗi chặn và cảnh báo) → xem trước kèm thông báo theo từng dòng → **báo cáo đối soát** (`core.import_reconciliation`: giá trị kiểm soát so với giá trị trong staging theo từng kiểm tra và mức chi tiết, dung sai 0 VND / 0,0 ngày / số lượng khớp chính xác; chênh lệch phải được sửa hoặc được miễn trừ kèm lý do) → **khách hàng ký duyệt** (`signed_off_by/at`; một CHECK constraint cấm commit job số dư đầu kỳ khi chưa có ký duyệt) → **commit** qua `ImportHandler` của module sở hữu trong các transaction riêng của nó → **kiểm tra sau commit** (`committed_value` được tính lại từ các bảng đích).
4. **Giữ nguyên ranh giới module:** Core lưu staging và báo cáo; Core không bao giờ đọc `hr`/`att`/`pay`. Module sở hữu đọc các dòng staging qua import API in-process của Core, tính giá trị staging và giá trị đã commit trong schema của chính nó (SPI `ReconciliationProvider`) và trả về. Không thêm grant chéo schema nào (ADR-0021, X-02).
5. **Thứ tự và phụ thuộc:** `OB_EMPLOYEE_MASTER` phải được commit trước (`depends_on_job_id`); các template khác tham chiếu nhân viên bằng `employee_no` và pháp nhân bằng mã. Kiểm tra thất bại khi nhân viên được tham chiếu không tồn tại.
6. **"Số dư đầu kỳ tại ngày go-live" (thay cho "ngày biết" của lịch sử migration):**
   * mọi job số dư đầu kỳ có `as_of_date` = ngày go-live **G** (CHECK constraint);
   * dữ liệu gốc được nạp dưới dạng **version có hiệu lực tại G**; `valid_from` của nó là ngày bắt đầu thực tế từ template nếu biết, nếu không thì là G. Lịch sử trước version đó không được nạp;
   * dòng bi-temporal có `recorded_at` = thời điểm nạp và `source = 'OPENING_LOAD'`. Truy vấn "theo hiểu biết tại" cho các ngày trước thời điểm nạp trả về góc nhìn đầu kỳ; đây là một giới hạn được ghi nhận, không phải lỗi;
   * YTD và lịch sử BHXH được lưu theo từng tháng trước G và không bao giờ được YourHRM tính lại; không hỗ trợ truy lĩnh/truy thu trước G (hiệu chỉnh là các điều chỉnh thủ công trong kỳ chạy đầu tiên);
   * số dư phép là một dòng sổ `OPENING` cho mỗi nhân viên × loại × năm, ghi ngày G.
7. **Idempotency và hiệu chỉnh:** khoá nghiệp vụ (`employee_no`, + tháng / loại nghỉ phép / số khoản vay) khiến việc tải lên lại là upsert vào cùng các đích. Một job đã commit có thể rollback bằng undo log của nó cho tới khi kỳ chạy lương đầu tiên của tenant được tính; sau đó, hiệu chỉnh là các điều chỉnh thông thường có audit.
8. **Event và audit:** việc commit phát event với cờ envelope `initial_load = true`; consumer bỏ qua các tác dụng phụ (lời mời, thông báo, kích hoạt truy lĩnh/truy thu) và projection được dựng lại bằng các job đồng bộ lại. Audit dùng `app.audit_mode = 'BATCH'` (một event tóm tắt cho mỗi chunk, người thực hiện `IMPORT`).
9. **Diễn tập:** khách hàng chạy toàn bộ pipeline trong một **tenant sandbox** trước; lần nạp production lặp lại với cùng các file và phiên bản template.
10. **Lưu trữ:** các dòng staging bị xoá và DEK của job bị huỷ 30 ngày sau khi commit; header của job và báo cáo đối soát được giữ làm bằng chứng go-live trong thời hạn lưu trữ bảng lương `[verify-legal]`.

## Các phương án đã cân nhắc

| Phương án | Lý do không chọn |
|---|---|
| Giữ thiết kế staging/ánh xạ/chạy song song cho Oracle (ADR-0033) | Quyết định của nhà tài trợ D-09: không migration. Thiết kế đó cũng chỉ phục vụ một hệ thống nguồn. |
| Khởi đầu mọi tenant với số dư bằng không | Phá vỡ quyết toán thuế TNCN cuối năm, điều kiện hưởng BHXH và số ngày phép được hưởng cho bất kỳ đợt go-live nào sau tháng Một. |
| Nạp trực tiếp bằng SQL vào bảng của module | Bỏ qua kiểm tra domain, event và audit; projection bị lệch; cần quyền truy cập chéo schema. |
| Mỗi module một màn hình import, không có pipeline chung | Kiểm tra không nhất quán, không có báo cáo đối soát hay cổng ký duyệt duy nhất, trùng lặp code staging. |
| Giữ toàn bộ lịch sử trước go-live (mọi version lương/phân công) | Ít giá trị cho pilot, tốn nhiều công sức về chất lượng dữ liệu; góc nhìn đầu kỳ tại G là đủ cho bảng lương và các đầu ra theo luật định. |

## Hệ quả

- \+ Cùng một bộ template phục vụ mọi khách hàng mới; onboarding là một tính năng sản phẩm, không phải một dự án.
- \+ Cổng chất lượng cứng (kiểm tra + đối soát + ký duyệt) trước khi dữ liệu đến bảng lương.
- \+ Không có môi trường hay schema bổ sung cần bảo mật; staging nằm trong cluster của tenant dưới RLS, được mã hoá.
- − Không có lịch sử trước go-live trong YourHRM (thay đổi lương, điều chuyển). Khách hàng giữ hồ sơ cũ của họ để xử lý tranh chấp.
- − Số tiền YTD/BHXH trước go-live là đầu vào được tin cậy; lỗi phát hiện sau này cần điều chỉnh thủ công.
- − Phiên bản template phải theo kịp các thay đổi pháp lý (chế độ BHXH mới, trường thuế TNCN) giống như các biểu mẫu theo luật định.
