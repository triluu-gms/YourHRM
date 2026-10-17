# Backlog Giai đoạn 1: Tổng hợp ước lượng & Kiểm tra năng lực (v0.1, 2026-10-16)

Dành cho: sponsor và PM. Phương pháp: [estimation-guide.md](estimation-guide.md). Phạm vi: 260 BR + 58 NFR được xếp hạng P1 trong [mvp-scope.md](../brd/mvp-scope.md) v0.3.

## 1. Điểm chính
- **Giai đoạn 1 = 608 user story, ≈ 3,130 story point (SP) ≈ 4,300 ngày công ≈ 195 tháng công (~16 năm công).**
- **Kế hoạch 16 tuần không khả thi cho toàn bộ Giai đoạn 1.** Để xây dựng trong 12 tuần sẽ cần khoảng 75 developer, điều mà không đội nào có thể tiếp nhận nổi.
- **Kế hoạch thực tế:** với khoảng 16 developer (tổng cộng ≈ 22 FTE), Giai đoạn 1 hoàn thành trong **khoảng 13–14 tháng**. **Kỳ lương chạy thật đầu tiên có thể đạt được trong khoảng 9–10 tháng** nếu phạm vi được chia giai đoạn theo lịch nghĩa vụ pháp định (§5).
- Các con số này dựa trên một giả định lập kế hoạch (1 SP ≈ 1 ngày công developer, 7 SP mỗi developer mỗi sprint). **Hiệu chỉnh lại sau sprint 1–2.**

## 2. Tổng backlog (tính lại từ các bảng story)
| Backlog | Số story | SP | Story 13 SP | Epic lớn nhất |
|---|---|---|---|---|
| [Core](core-backlog.md) | 86 | 454 | 1 | Workflow engine 80, Người dùng & phân quyền 73, Import engine 53 |
| [People](people-backlog.md) | 93 | 456 | 1 | Cá nhân & dữ liệu cá nhân 65, Quan hệ lao động 53, Hợp đồng 48 |
| [Time & Attendance](time-attendance-backlog.md) | 84 | 458 | 0 | Khóa kỳ & chuyển dữ liệu sang lương 83, Xử lý hằng ngày 63, OT 55 |
| [Payroll](payroll-backlog.md) | 121 | 709 | 9 | Thuế TNCN 107, Điều phối kỳ chạy lương 105, Gross-to-net 77, Bảo hiểm 71 |
| [SaaS platform](saas-platform-backlog.md) | 75 | 342 | 0 | Go-live với số dư đầu kỳ 60, Định danh 39, Tham số pháp lý 39 |
| [Technical enablers](technical-enablers-backlog.md) | 149 | 704 | 0 | Môi trường/DR 65, Nền tảng frontend 54, Tenancy/RLS 52 |
| **Tổng** | **608** | **3,123** | 11 | |
| Khoảng trống phát hiện khi tổng hợp: API đọc as-of của Legal Parameter Service có cache và xử lý override (cần bổ sung vào backlog SaaS) | +1 | +8 | | |
| **Tổng sau điều chỉnh** | **609** | **3,131** | | |

- **Độ bao phủ:** mọi BR và NFR P1 đều được ánh xạ tới ít nhất một story. Ngoại lệ:
  - BR-SAAS-087 (phân loại cấp độ an toàn hệ thống thông tin theo Nghị định 85) là hồ sơ tuân thủ, không phải công việc xây dựng, nên không có story.
  - Các job xóa dữ liệu theo thời hạn lưu trữ thuộc P2.
- **Không có story nào trên 13 SP.** Phân bố point trên tất cả backlog: 1 SP ×3, 2 ×36, 3 ×136, 5 ×293, 8 ×129, 13 ×11.
- **Khoảng 60 story mang nhãn `[high-uncertainty]`,** chủ yếu do một quy định pháp lý chưa được xác nhận hoặc chưa có kết quả spike.

## 3. Công sức kèm overhead triển khai
| Hạng mục | SP (ngày công dev) | Overhead | Ngày công |
|---|---|---|---|
| Backlog các module (gồm +8 phần khoảng trống) | 2,427 | +40% (QA, BA, UX, DevOps, PM) | 3,398 |
| Technical enablers | 704 | +25% (công việc DevOps đã nằm trong SP) | 880 |
| **Tổng** | **3,131** | | **≈ 4,280 (≈ 195 tháng công)** |

Chưa bao gồm:
- **4–6 tuần công tư vấn** từ một chuyên gia tư vấn lương/thuế Việt Nam để viết và kiểm chứng ≥200 kịch bản lương chuẩn (golden) và xác nhận các quy tắc `[verify-legal]`.
- DPO và rà soát pháp lý.
- Công sức của chính khách hàng pilot: file số dư đầu kỳ, UAT.

## 4. Các kịch bản năng lực
Giả định 7 SP mỗi developer mỗi sprint 2 tuần (hệ số tập trung 0.7), cộng 4–6 tuần cho kiểm thử hệ thống, UAT và chuyển đổi sau khi xây dựng.

| Số developer (tổng FTE ≈ ×1.4) | SP mỗi sprint | Số sprint xây dựng | Số tuần xây dựng | **Tổng thời gian đến go-live Giai đoạn 1** |
|---|---|---|---|---|
| 8 (≈ 11) | 56 | 56 | 112 | ≈ 116 tuần (~27 tháng) |
| 12 (≈ 17) | 84 | 37 | 75 | ≈ 79 tuần (~18 tháng) |
| **16 (≈ 22)** | **112** | **28** | **56** | **≈ 62 tuần (~14 tháng)** |
| 20 (≈ 28) | 140 | 22 | 45 | ≈ 51 tuần (~12 tháng) |
| 75 | 525 | 6 | 12 | 16 tuần: **không thực tế** |
| *Độ nhạy: 16 dev với 10 SP/dev/sprint* | 160 | 20 | 39 | *≈ 45 tuần (~10.5 tháng)* |

**Đường găng giới hạn mức độ hiệu quả khi tăng quy mô đội.** Payroll có 709 SP và phần lớn phải xây dựng tuần tự (ngôn ngữ công thức → dữ liệu đầu vào → gross-to-net → bảo hiểm → thuế TNCN → các file). Khoảng 6 developer có thể làm việc hiệu quả trên phần này, nên riêng Payroll cần khoảng 17 sprint (34 tuần). Nó cũng không thể bắt đầu thực sự cho đến khi nền tảng Core, People và TECH hoàn thành (khoảng sprint 3). Điều đó đặt ra **mức sàn khoảng 10–11 tháng** cho toàn bộ Giai đoạn 1, dù đội lớn đến đâu.

## 5. Cách đưa kỳ lương đầu tiên lên sớm hơn: chia giai đoạn theo lịch nghĩa vụ pháp định
Không phải mọi yêu cầu P1 đều cần có vào ngày chạy lương thật đầu tiên. Lộ trình phát hành đề xuất:

| Bản phát hành | Nội dung (ví dụ) | Cần có trước |
|---|---|---|
| **R1: Kỳ lương chạy thật đầu tiên** | Danh mục Core, phân quyền, workflow (cơ bản), import engine; hồ sơ People, hợp đồng, hồ sơ lương; lịch ngày lễ T&A, nghỉ phép, OT, nhập từ Excel và file thiết bị, khóa kỳ và tạm giữ; Payroll gross-to-net hằng tháng, BHXH/BHYT/BHTN, khấu trừ thuế TNCN hằng tháng, phiếu lương (PDF), file ngân hàng, file khai báo bảo hiểm hằng tháng; nhập số dư đầu kỳ; một tenant pilot duy nhất | Go-live pilot |
| **R2: trong vòng ~3 tháng sau R1** | Truy lĩnh/truy thu qua các kỳ đã khóa, kỳ chạy ngoài chu kỳ/thưởng (trước Tết tiếp theo), quyết toán khi nghỉ việc và các trường hợp biên của trợ cấp thôi việc, khoản vay, kiểm tra 183 ngày, hoàn thiện tờ khai thuế TNCN 05/KK tháng/quý, báo cáo lao động (6 tháng), hoàn thiện back-office cho operator | Trước báo cáo 6 tháng đầu tiên và thưởng Tết tiếp theo |
| **R3: trước khi kết thúc Q1 của năm tính thuế tiếp theo** | Quyết toán thuế TNCN cuối năm (05/QTT và các phụ lục), tính lại cả năm, chứng từ khấu trừ thuế, chuyển phép năm sang năm sau và thanh toán tiền phép, tenant tự đăng ký (self-service provisioning) và tính cước | Hạn quyết toán thuế TNCN |

Để xác định chính xác quy mô R1, mỗi story cần một nhãn R1/R2/R3. Đó là bước tiếp theo được khuyến nghị: một **lát cắt R1 ước lượng ~1,700–1,900 SP** tương ứng **≈ 9–10 tháng** với 16 developer. Đây là ước lượng của PM cho đến khi các story được gắn nhãn.

## 6. Các đòn bẩy công sức (tiết kiệm nhỏ hơn, nhưng đáng áp dụng)
| Đòn bẩy | Mức tiết kiệm ước tính | Đánh đổi |
|---|---|---|
| Pilot trên nền tảng đơn giản hóa: tenant đơn do ops khởi tạo, không định tuyến theo tier dedicated, DR dạng backup/restore thay vì warm standby, thông tin đăng nhập DB tĩnh rồi xoay vòng sau | 120–180 SP | Phải hoàn thành trước khi tenant thứ hai được onboard. Thông tin đăng nhập tĩnh mâu thuẫn với R2-15 và cần DPO đồng ý. |
| Hoãn kho dữ liệu báo cáo riêng (báo cáo của pilot chạy trên read replica) | ~33 SP | Tải báo cáo chạy trên replica |
| Mở lại OD-4: dùng một BPMN engine mã nguồn mở (ví dụ Flowable) thay cho workflow engine tự xây dựng | ~30–40 SP | Thêm một phụ thuộc, nhưng đội phải xây dựng ít hơn |
| Hoãn mẫu DOCX theo tenant của Core, phê duyệt hàng loạt, console workflow | ~21 SP | Dùng mẫu do nhà cung cấp duy trì trong giai đoạn pilot |
| **Tổng** | **~200–275 SP (6–9%)** | Tự bản thân chưa đủ; tiến độ vẫn phải thay đổi |

## 7. Các việc cần theo dõi sau tổng hợp (PM)
- **Phụ thuộc giữa các backlog** vẫn dùng placeholder (`TECH:rls`, `CORE-WF`, …). Chúng sẽ được ánh xạ sang ID story thực khi lập kế hoạch sprint.
- **Đã thực hiện khi tổng hợp:**
  - Đã điều chỉnh §2 của hướng dẫn ước lượng: formula engine và điều phối kỳ chạy lương nằm trong Payroll, Legal Parameter Service nằm trong SaaS. Mỗi phần chỉ được ước lượng một lần.
  - Đã thống nhất NFR-SAAS-007 với X-01: số tiền lương không được mã hóa ở mức cột.
- **Các quyết định TECH cần đưa vào kế hoạch phát hành:**
  - Cần có lựa chọn cloud tại VN của sponsor (OD-1) trước khi kết thúc sprint 1.
  - Spike SP-2 (hiệu năng RLS) phải hoàn thành sớm, vì nó ảnh hưởng đến việc truy cập dữ liệu của mọi module.
- **Hiệu chỉnh:** đo velocity thực tế trong sprint 1–2, sau đó tính lại §4 và chốt các mốc thời gian.
