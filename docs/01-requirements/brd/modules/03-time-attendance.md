# YourHRM — BRD Module 03: Chấm công & Thời gian làm việc (`att`)

| Hạng mục | Giá trị |
|---|---|
| Module | `time-attendance` — DB schema `att` — tiền tố yêu cầu `BR-ATT` / `NFR-ATT` |
| Phiên bản | Bản nháp v0.3, 2026-10-16 |
| Chủ sở hữu | BA – Time & Attendance |
| BRD liên quan | 01-core, 02-people, 04-payroll, 05-recruitment, 06-saas-platform |
| Trạng thái | Chờ review bởi PM, BA Payroll, BA People, Integration analyst |
| Ghi chú thay đổi | v0.2 (2026-10-16): việc chốt kỳ được đổi sang giao thức **payroll close hold** (quyết định X-03, ADR-0019, [gap-analysis](../gap-analysis.md) §7 v1.1) — P8, BR-ATT-073, mới BR-ATT-079/087/088, §6 AttendancePeriod/AttendancePeriodHold, §7.1 ATT-C-19/21, §7.2 ATT-P-01/04, mới ATT-P-17/18, §7.3 quy tắc hồi tố. Chuyển đổi dữ liệu Oracle được thay bằng **import số dư đầu kỳ** chung (quyết định D-09) — BR-ATT-025/068/086, Q6, R7. Các chỉnh sửa được đánh dấu "(thay đổi 2026-10-16, X-03/D-09)". v0.3 (2026-10-16): áp dụng bảng chủ sở hữu duy nhất đã phê duyệt ([gap-analysis](../gap-analysis.md) §5, quyết định **R2-02**): phương pháp ngày công chuẩn thuộc Payroll (BR-ATT-016; #25); nhóm lương lấy từ `pay.PayGroupAssigned` và việc gán lịch làm việc do T&A sở hữu, không phải một trường của People (ATT-C-14, BR-ATT-015, P2; #25/#26); tham số pháp lý từ SaaS Legal Parameter Service (ATT-C-03; #1); job scheduler từ SaaS, tài liệu và audit từ Core (ATT-C-11; #13/#15b/#16); T&A là chủ sở hữu duy nhất của lịch ngày lễ và công tác (ATT-P-16, ATT-P-08; #2/#6); số năm đóng BHXH từ Payroll (ATT-C-20; #20). R2-16/R2-08: giới hạn làm thêm giờ theo pháp nhân ở Phase 1 đối với nhân viên kiêm nhiệm (ghi chú BR-ATT-047, Q4). R2-18: import chấm công từ Excel là năng lực baseline (BR-ATT-025). Các chỉnh sửa được đánh dấu "(thay đổi 2026-10-16, R2-02 §5 #n)" hoặc kèm mã quyết định. Cột độ ưu tiên và giai đoạn không thay đổi (PM đồng bộ). |

> **Giai đoạn:** giai đoạn triển khai của mọi yêu cầu chỉ được xác định trong [mvp-scope.md](../mvp-scope.md) §9; MoSCoW ở đây là độ ưu tiên nghiệp vụ. (2026-10-16)

Quy ước theo Project Charter §4: độ ưu tiên MoSCoW (`M` = MVP / tenant trả phí đầu tiên), mọi số liệu pháp lý là tham số có hiệu lực theo ngày và được đánh dấu `[verify-legal]` cho đến khi được chuyên gia tư vấn lao động Việt Nam xác nhận. Cột giai đoạn: `MVP`, `P2` (Phase 2), `P3`.

---

## 1. Mục đích & phạm vi

### 1.1 Mục đích
Time & Attendance (Chấm công, Nghỉ phép, Làm thêm giờ) ghi nhận **thời gian mỗi nhân viên phải làm việc, thời gian thực tế đã làm việc và lý do vắng mặt**, áp dụng các quy định của pháp luật lao động Việt Nam lên dữ liệu đó, và bàn giao một **kết quả chấm công tháng đã chốt, có thể audit** cho Payroll. Đây là nguồn dữ liệu chuẩn duy nhất về ngày công, vắng mặt có lương/không lương, giờ làm thêm theo loại pháp lý, giờ làm đêm và số dư phép.

### 1.2 Trong phạm vi
| # | Năng lực | Giai đoạn |
|---|---|---|
| S1 | Lịch ngày lễ theo tenant / pháp nhân: ngày lễ Việt Nam bao gồm ngày Tết (âm lịch), ngày nghỉ bù, hoán đổi ngày làm việc theo thông báo của Chính phủ, ngày nghỉ của công ty | MVP |
| S2 | Lịch làm việc: hành chính cố định, tuần 5 ngày, 6 ngày và 5.5 ngày, ca làm việc, ca gãy, ca đêm, ca qua nửa đêm, linh hoạt / giờ lõi, mô hình xoay ca | MVP (xoay ca/ca gãy: P2) |
| S3 | Phân ca: lập kế hoạch, công bố, đổi ca, so sánh phân ca với thực tế | P2 (gán mô hình ca cơ bản: MVP) |
| S4 | Nguồn chấm công: máy chấm công vân tay / khuôn mặt (ZKTeco, Hikvision …), mobile app với GPS geofence / Wi-Fi SSID / selfie, check-in trên web, import tệp, nhập thủ công | MVP (máy chấm công qua import/connector, web, import); mobile & connector push: P2 |
| S5 | Xử lý dữ liệu chấm công thô thành bảng công ngày: ghép cặp lượt chấm, đi muộn / về sớm, thiếu lượt chấm, vắng mặt, yêu cầu điều chỉnh công | MVP |
| S6 | Làm thêm giờ: yêu cầu được duyệt trước, làm thêm được phát hiện, loại làm thêm (ngày thường / ngày nghỉ hằng tuần / ngày lễ / ban đêm / làm thêm ban đêm), giới hạn pháp lý (ngày, tháng, năm 200h/300h), cảnh báo và chặn | MVP |
| S7 | Nghỉ phép: các loại (phép năm + thâm niên, ốm đau (BHXH chi trả), thai sản/nghỉ khi vợ sinh, kết hôn, tang, không lương, nghỉ bù, khác), yêu cầu, số dư, tích lũy, chuyển sang năm sau, hết hạn, thanh toán khi nghỉ việc | MVP |
| S8 | Công tác và yêu cầu làm việc từ xa / WFH cùng tác động lên bảng công | MVP (yêu cầu + tác động lên bảng công), P2 (check-in từ xa có xác minh vị trí) |
| S9 | Rà soát, chốt, khóa bảng công tháng, bàn giao cho payroll, điều chỉnh hồi tố sau khi khóa | MVP |
| S10 | Màn hình tự phục vụ ESS / MSS, báo cáo và dashboard T&A | MVP |

### 1.3 Ngoài phạm vi
- Tiền: tiền làm thêm giờ, số tiền thanh toán phép, phụ cấp, khấu trừ được **Payroll tính** (`pay`). T&A chỉ cung cấp số lượng (giờ, ngày, số lần).
- Hồ sơ hưởng chế độ BHXH (ốm đau, thai sản do quỹ BHXH chi trả) — Payroll/Insurance (`pay`) chuẩn bị hồ sơ BHXH; T&A cung cấp dữ kiện vắng mặt.
- Chi phí công tác / công tác phí — People (`hr`, chi phí).
- Workflow engine, thông báo, cây tổ chức, người dùng/vai trò — Core.
- Quản lý firmware thiết bị, logic kiểm soát ra vào (cửa), hệ thống nhà ăn.
- Theo dõi thời gian theo dự án / công việc (timesheet để tính phí) — ngoài phạm vi; có thể ở P3.
- Dự báo nhu cầu nhân lực và tối ưu phân ca tự động — P3.

---

## 2. Tác nhân & persona

| Tác nhân | Mô tả | Mục tiêu chính trong T&A |
|---|---|---|
| Nhân viên (ESS) | Mọi nhân viên, văn phòng hoặc nhà máy | Check in/out, xem bảng công của mình, gửi yêu cầu nghỉ phép / làm thêm / điều chỉnh / công tác / WFH, xem số dư |
| Quản lý trực tiếp (MSS) | Quản lý trực tiếp hoặc quản lý chức năng theo phân công trong People | Phê duyệt yêu cầu, rà soát bảng công của nhóm, xem ai vắng mặt hôm nay |
| Trưởng ca / người lập phân ca | Nhà máy, bán lẻ, khách sạn - nhà hàng | Lập và công bố phân ca, xử lý đổi ca, bù chỗ trống |
| Quản trị viên T&A (HR / C&B) | Theo từng pháp nhân | Cấu hình ngày lễ, lịch làm việc, quy tắc, chính sách nghỉ phép; xử lý ngoại lệ; chốt kỳ |
| Chuyên viên tính lương | C&B | Sử dụng bảng công đã chốt; yêu cầu mở lại / điều chỉnh hồi tố |
| Quản trị viên tenant | Trưởng nhóm IT/HR phía khách hàng | Cấu hình thiết bị, geofence, thông tin xác thực tích hợp, cấu hình tính năng |
| Thiết bị chấm công / connector | ZKTeco, Hikvision, các thiết bị đầu cuối khác; mobile app | Đẩy hoặc gửi dữ liệu chấm công thô |
| Scheduler hệ thống | Job chạy nền | Xử lý dữ liệu chấm công, tích lũy, chuyển phép, nhắc nhở tự động chốt kỳ |
| Kiểm toán viên / thanh tra lao động (gián tiếp) | Thanh tra nội bộ hoặc của Bộ LĐTBXH / Sở Nội vụ | Bằng chứng về giới hạn làm thêm giờ, thời gian nghỉ ngơi, phép đã cấp |
| SaaS operator | Nhóm nền tảng | Duy trì các pack ngày lễ/tham số pháp lý mặc định của VN, giám sát connector |

---

## 3. Quy trình nghiệp vụ

### P1 — Thiết lập lịch ngày lễ hằng năm
1. Nền tảng công bố **pack ngày lễ Việt Nam** mặc định cho năm N (ngày dương lịch và âm lịch được quy đổi sang dương lịch; ngày Tết; ngày Giỗ Tổ Hùng Vương).
2. Khi Chính phủ công bố hoán đổi ngày làm việc / lịch nghỉ Tết cho khu vực công, nền tảng cập nhật pack ở trạng thái "đã công bố" (mang tính tham khảo cho khu vực tư nhân).
3. Quản trị viên T&A của từng pháp nhân rà soát, chấp nhận hoặc điều chỉnh (ví dụ chọn ngày nghỉ thêm dịp Quốc khánh, thêm ngày nghỉ của công ty, áp dụng hoán đổi ngày tùy chọn kèm ngày làm bù).
4. Hệ thống tính **ngày nghỉ bù** khi ngày lễ trùng với ngày nghỉ hằng tuần của nhân viên (theo lịch làm việc, không theo ngày trong tuần của lịch).
5. Lịch được công bố; thay đổi sau khi kỳ đã khóa sẽ sinh điều chỉnh hồi tố (P9).
- Ngoại lệ: luật bổ sung ngày lễ giữa năm → ngày lễ mới có hiệu lực theo ngày, tính lại các kỳ đang mở, hồi tố cho các kỳ đã khóa.

### P2 — Gán lịch làm việc và phân ca
1. Quản trị viên định nghĩa ca làm việc (bắt đầu, kết thúc, nghỉ giữa ca, phần trùng với khung giờ đêm, thời gian ân hạn, làm tròn) và mô hình lịch (theo tuần hoặc chu kỳ N ngày).
2. T&A gán mô hình lịch cho từng phân công theo quy tắc (đơn vị tổ chức, địa điểm, công việc, nhóm nhân viên) hoặc thủ công; People chỉ giữ chế độ thời giờ làm việc theo hợp đồng (toàn thời gian/bán thời gian, giờ/tuần), quy tắc có thể sử dụng thông tin này (thay đổi 2026-10-16, R2-02 §5 #26).
3. Với nhóm nhân viên làm theo phân ca, người lập phân ca lập phân ca cho một kỳ, kiểm tra hợp lệ (quy tắc nghỉ ngơi, hạn chế làm đêm đối với lao động được bảo vệ, 12h giữa các ca), công bố; nhân viên được thông báo.
4. Nhân viên yêu cầu đổi ca; trưởng ca phê duyệt; phân ca được cập nhật.
- Ngoại lệ: phân ca chưa được công bố trước hạn chốt → dùng mô hình mặc định và gắn cờ.

### P3 — Thu nhận và xử lý dữ liệu chấm công
1. Lượt chấm công đến từ thiết bị (connector pull/push hoặc tệp), mobile (GPS/Wi-Fi/selfie), web hoặc import.
2. Mỗi lượt chấm được lưu bất biến dưới dạng dữ liệu thô kèm nguồn, thiết bị, thời điểm (giờ địa phương của thiết bị và UTC) và kết quả kiểm tra (trong geofence, Wi-Fi được nhận diện, điểm khớp khuôn mặt).
3. Lượt chấm được ánh xạ tới nhân viên qua ánh xạ mã đăng ký → nhân viên, có hiệu lực tại ngày đó.
4. Job xử lý (gần thời gian thực và hằng đêm) gán lượt chấm vào một **ngày công** (dựa trên ca, xử lý ca qua nửa đêm), ghép cặp vào/ra, tính thời gian làm việc, số phút đi muộn/về sớm, số phút làm đêm, ứng viên làm thêm giờ.
5. Bảng công ngày được tạo/cập nhật với trạng thái: Bình thường / Đi muộn / Về sớm / Thiếu lượt chấm / Vắng mặt / Nghỉ phép / Ngày lễ / Ngày nghỉ / Công tác / WFH.
- Ngoại lệ: mã đăng ký không xác định → hàng đợi cách ly; lượt chấm trùng trong vòng X phút → gộp lại; đồng hồ thiết bị bị lệch → gắn cờ.

### P4 — Ngoại lệ chấm công & điều chỉnh
1. Nhân viên thấy các ngoại lệ trong ESS (thiếu lượt chấm, đi muộn) và gửi **yêu cầu điều chỉnh** (giải trình) kèm lý do và tệp đính kèm tùy chọn.
2. Quản lý phê duyệt qua workflow của Core; bảng công ngày được tính lại; giá trị gốc được giữ trong lịch sử.
3. Quản trị viên có thể điều chỉnh hàng loạt với lý do bắt buộc.
- Ngoại lệ: yêu cầu sau hạn chốt kỳ → được chuyển thành điều chỉnh hồi tố (P9).

### P5 — Làm thêm giờ
1. **Duyệt trước**: nhân viên hoặc quản lý gửi yêu cầu làm thêm (ngày, số giờ dự kiến, lý do, loại làm thêm được suy ra). Hệ thống kiểm tra giới hạn pháp lý và quy tắc đối với lao động được bảo vệ → cảnh báo hoặc chặn. Ghi nhận sự đồng ý của nhân viên. Phê duyệt qua workflow.
2. **Phát hiện**: quá trình xử lý phát hiện thời gian làm việc vượt giờ theo lịch (vượt ngưỡng). Tùy chính sách: (a) bỏ qua trừ khi đã được duyệt trước, (b) tạo dưới dạng "làm thêm chờ xác nhận" cho quản lý, (c) tự động phê duyệt tới N phút.
3. Làm thêm đã duyệt được đối chiếu với lượt chấm thực tế: **giờ làm thêm được trả = min(đã duyệt, thực tế)** trừ khi chính sách quy định khác; chia theo loại dựa trên các khoảng thời gian (ngày/đêm, ngày thường/ngày nghỉ/ngày lễ).
4. Nhân viên có thể chọn **nghỉ bù (TOIL)** thay vì nhận tiền khi chính sách cho phép → số dư nghỉ bù được cộng thay vì trả tiền làm thêm.
- Ngoại lệ: sắp vượt giới hạn → chặn (giới hạn cứng) hoặc yêu cầu phê duyệt cấp HR (giới hạn mềm, cấu hình được); chế độ 300h chỉ cho các pháp nhân được đánh dấu là đủ điều kiện và đã đăng ký.

### P6 — Yêu cầu nghỉ phép
1. Nhân viên chọn loại nghỉ, ngày (cả ngày, nửa ngày sáng/chiều, hoặc theo giờ nếu loại nghỉ cho phép); hệ thống hiển thị số dư, số ngày làm việc theo lịch (không tính ngày nghỉ/ngày lễ trừ khi loại nghỉ tính theo ngày lịch, ví dụ thai sản).
2. Kiểm tra hợp lệ: số dư, điều kiện (giới tính, thâm niên, thử việc), thời hạn báo trước, yêu cầu chồng lấn, giấy tờ bắt buộc (ví dụ giấy chứng nhận nghỉ việc hưởng BHXH cho nghỉ ốm).
3. Phê duyệt qua workflow; số dư được tạm giữ khi gửi và được trừ khi phê duyệt; lịch và bảng công được cập nhật.
4. Hủy / hủy một phần sau khi phê duyệt sẽ hoàn lại số dư; nếu thuộc kỳ đã khóa → hồi tố.

### P7 — Tích lũy phép, chuyển phép và cuối năm
1. Job tích lũy hằng tháng (hoặc cấp trước theo năm) cấp phép năm theo chính sách và thâm niên; tính theo tỷ lệ cho người mới vào/nghỉ việc.
2. Phép thâm niên (+1 ngày mỗi 5 năm) được cấp vào ngày kỷ niệm hoặc đầu năm theo chính sách.
3. Cuối năm phép: chuyển sang năm sau tối đa theo mức cấu hình; đặt ngày hết hạn (ví dụ 31 tháng 3); hủy / thanh toán phần còn lại theo chính sách.
4. Quản trị viên có thể điều chỉnh số dư kèm lý do (audit).

### P8 — Chốt bảng công tháng và bàn giao cho payroll
1. Cuối kỳ, hệ thống chạy xử lý cuối cùng; dashboard ngoại lệ liệt kê các mục chưa xử lý (thiếu lượt chấm, yêu cầu đang chờ, làm thêm chưa duyệt).
2. Quản lý xác nhận bảng công của nhóm (bước tùy chọn); nhân viên có thể xác nhận bảng công của mình (tùy chọn, cấu hình được).
3. Quản trị viên T&A **chốt** kỳ theo nhóm lương / pháp nhân → trạng thái `CLOSED`; snapshot dữ liệu (**Attendance Result**) được đánh phiên bản và công bố cho Payroll (`att.AttendancePeriodClosed`, phiên bản n).
4. Payroll tính lương trên phiên bản n. Khi Payroll **trình** kỳ lương để phê duyệt, Payroll lấy một **close hold** trên phiên bản n (API đồng bộ của T&A, idempotent theo run id + phiên bản) → `att.AttendancePeriodHoldGranted`. Kỳ vẫn ở trạng thái `CLOSED` nhưng đang *bị giữ*.
5. Kỳ lương **được phê duyệt** → T&A chuyển phiên bản đang bị giữ `CLOSED → LOCKED` (cứng) → `att.AttendancePeriodLocked`. Vì phiên bản đang bị giữ nên việc khóa không thể thất bại.
6. Kỳ lương **bị từ chối, rút lại hoặc hủy** → Payroll giải phóng hold → `att.AttendancePeriodHoldReleased`; kỳ trở lại là một kỳ `CLOSED` thông thường.
7. Mở lại `CLOSED → OPEN` được phép cho quản trị viên T&A kèm lý do **chỉ khi không có hold nào đang hiệu lực**. Nếu kỳ đang bị giữ, T&A từ chối việc mở lại và tạo yêu cầu giải phóng tới quản trị viên payroll (công việc của Core); Payroll giải phóng hold (kỳ lương trở về `CALCULATED`, được đánh dấu stale), sau đó việc mở lại được thực hiện và lần chốt lại công bố phiên bản n+1.
8. `LOCKED → CLOSED` chỉ xảy ra khi Payroll thực hiện **mở khóa** đặc quyền đối với kỳ lương đã duyệt (phê duyệt lần hai). (thay đổi 2026-10-16, X-03)

### P9 — Điều chỉnh hồi tố sau khi khóa
1. Mọi thay đổi chạm tới một kỳ `Locked` (điều chỉnh muộn đã duyệt, nghỉ phép muộn, thay đổi ngày lễ, nghỉ phép bị hủy) **không** làm thay đổi snapshot đã khóa.
2. Hệ thống tính một **bản ghi chênh lệch** (kỳ gốc, khoản mục, loại, số lượng +/-) và gắn vào Attendance Result của kỳ mở kế tiếp dưới dạng các dòng hồi tố.
3. Payroll trả/thu hồi phần chênh lệch trong kỳ lương kế tiếp.

### P10 — Tuyển dụng, điều chuyển, nghỉ việc trong kỳ
1. Nhận việc giữa kỳ: bảng công bắt đầu từ ngày nhận việc; các ngày chưa làm việc là `Not employed` (không phải vắng mặt).
2. Điều chuyển (tổ chức, pháp nhân, lịch làm việc, nhóm lương): bảng công được **phân đoạn theo phân công**; mỗi đoạn mang lịch làm việc và trung tâm chi phí riêng.
3. Nghỉ việc: khi nhận thông báo ngày làm việc cuối cùng, hệ thống dự tính số dư phép tại ngày cuối, chặn nghỉ phép sau ngày đó và sau khi xử lý cuối cùng cung cấp **số ngày phép năm chưa nghỉ để thanh toán** và số dư âm (nếu có) để thu hồi.

### P11 — Công tác và làm việc từ xa
1. Nhân viên gửi yêu cầu công tác (ngày, nơi đến, mục đích, chi tiết theo nửa ngày) hoặc yêu cầu WFH; quản lý phê duyệt.
2. Bảng công đánh dấu các ngày đó là đã làm việc (`Business trip` / `WFH`) mà không cần lượt chấm (cấu hình được: yêu cầu check-in trên mobile khi WFH).
3. Ngày công tác và nơi đến (trong nước/nước ngoài) được cung cấp cho People (chi phí/công tác phí). Làm thêm giờ khi đi công tác chỉ được tính nếu được duyệt riêng.

---

## 4. Yêu cầu nghiệp vụ

### 4.1 Lịch ngày lễ
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-001 | Hệ thống phải duy trì lịch ngày lễ theo tenant với việc gán theo pháp nhân (và tùy chọn theo địa điểm), mỗi lịch có các mục có hiệu lực theo ngày cho ngày lễ, ngày nghỉ bù, ngày nghỉ của công ty và hoán đổi ngày làm việc. | M | MVP | Pháp nhân lấy từ Core |
| BR-ATT-002 | Hệ thống phải cung cấp pack ngày lễ Việt Nam mặc định do nền tảng duy trì theo từng năm, bao gồm các ngày lễ âm lịch (Tết, Giỗ Tổ Hùng Vương) được quy đổi sang dương lịch, tenant kế thừa và có thể ghi đè. | M | MVP | Pack do SaaS platform công bố |
| BR-ATT-003 | Khi ngày lễ trùng với ngày nghỉ hằng tuần của nhân viên theo **lịch làm việc của chính nhân viên đó**, hệ thống phải sinh ngày nghỉ bù vào ngày làm việc kế tiếp (quy tắc cấu hình được theo pháp nhân). | M | MVP | Bộ luật Lao động Điều 112(3) [verify-legal] |
| BR-ATT-004 | Hệ thống phải hỗ trợ hoán đổi ngày (dời một ngày làm việc để nối với ngày lễ) kèm ngày làm bù tương ứng, áp dụng theo pháp nhân hoặc nhóm nhân viên. | S | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-005 | Hệ thống phải hỗ trợ ngày nghỉ bổ sung cho lao động nước ngoài (1 ngày Tết cổ truyền + 1 ngày Quốc khánh của nước họ) dựa trên quốc tịch từ People. | S | MVP | Điều 112(2) [verify-legal] |
| BR-ATT-006 | Mọi thay đổi lịch ngày lễ ảnh hưởng tới kỳ đang mở phải kích hoạt tính lại; ảnh hưởng tới kỳ đã khóa phải kích hoạt chênh lệch hồi tố (BR-ATT-071). | M | MVP | |

### 4.2 Lịch làm việc và phân ca
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-010 | Quản trị viên phải định nghĩa được ca làm việc: giờ bắt đầu/kết thúc, cờ qua nửa đêm, thời gian nghỉ có lương/không lương (cố định hoặc linh hoạt), giờ lõi, số phút ân hạn cho đi muộn/về sớm, quy tắc làm tròn, số phút tối thiểu để tính nửa/cả ngày công, số giờ chuẩn được ghi nhận. | M | MVP | |
| BR-ATT-011 | Quản trị viên phải định nghĩa được mô hình lịch: theo tuần (5 ngày, 6 ngày, 5.5 ngày với nửa ngày thứ Bảy, thứ Bảy xen kẽ) và chu kỳ xoay ca N ngày với ngày neo. | M | MVP (xoay ca: P2) | |
| BR-ATT-012 | Hệ thống phải hỗ trợ lịch linh hoạt: số giờ yêu cầu mỗi ngày/tuần với giờ lõi tùy chọn, trong đó đi muộn chỉ được tính so với giờ lõi. | S | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-013 | Hệ thống phải hỗ trợ ca gãy (hai hoặc nhiều khối làm việc mỗi ngày) và ca đêm qua nửa đêm, gán mọi lượt chấm vào ngày công của ca. | S | P2 | |
| BR-ATT-014 | Mỗi mô hình lịch phải định nghĩa **(các) ngày nghỉ hằng tuần** của nhân viên, dùng để phân loại làm thêm giờ và quy tắc nghỉ bù ngày lễ; ngày nghỉ có thể khác Chủ nhật. | M | MVP | Điều 111 |
| BR-ATT-015 | Hệ thống phải sở hữu **việc gán lịch làm việc**: gán lịch có hiệu lực theo ngày cho từng phân công của nhân viên dựa trên quy tắc (đơn vị tổ chức, địa điểm, công việc, nhóm nhân viên, chế độ theo hợp đồng của People) hoặc thủ công; People không cung cấp lịch mặc định (thay đổi 2026-10-16, R2-02 §5 #26). | M | MVP | Trạng thái được đưa vào mức sẵn sàng onboarding của People |
| BR-ATT-016 | Hệ thống phải tính **số ngày công chuẩn và số giờ chuẩn theo nhân viên theo kỳ** (ngày công chuẩn) từ lịch làm việc, lịch ngày lễ và các ngày làm việc theo hợp đồng, sử dụng **phương pháp của nhóm lương của nhân viên** nhận được trong lịch lương (`pay.PayCalendarPublished.std_day_method`, ATT-C-18). T&A không có cấu hình phương pháp riêng; phương pháp do Payroll sở hữu (BR-PAY-009) (thay đổi 2026-10-16, R2-02 §5 #25). | M | MVP | Đầu vào quan trọng của payroll |
| BR-ATT-017 | Người lập phân ca phải tạo, sao chép, kiểm tra hợp lệ và công bố phân ca theo đơn vị tổ chức/địa điểm cho một kỳ; nhân viên được thông báo khi công bố và khi thay đổi. | S | P2 | |
| BR-ATT-018 | Kiểm tra hợp lệ phân ca phải gắn cờ: nghỉ giữa các ca < 12h, không có 24h nghỉ hằng tuần (hoặc trung bình < 4 ngày/tháng), ca đêm đối với lao động được bảo vệ, số giờ phân ca vượt giờ làm việc bình thường theo luật (8h/ngày hoặc 10h/ngày theo chế độ tuần, 48h/tuần). | S | P2 | Điều 105,110,111,137 [verify-legal] |
| BR-ATT-019 | Nhân viên phải yêu cầu được đổi ca với đồng nghiệp (cùng nhóm năng lực); cả hai bên và trưởng ca phê duyệt. | C | P2 | |

### 4.3 Nguồn chấm công và dữ liệu chấm công thô
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-020 | Hệ thống phải lưu bất biến mọi lượt chấm thô với: tenant, mã đăng ký, nhân viên (đã xác định), thời điểm (UTC + giờ địa phương + múi giờ thiết bị), loại nguồn, ID thiết bị/ứng dụng, hướng (vào/ra/không xác định), phương thức xác minh, dữ liệu vị trí/Wi-Fi, tham chiếu ảnh, thời điểm nhận và trạng thái kiểm tra. | M | MVP | |
| BR-ATT-021 | Hệ thống phải tích hợp với máy chấm công vân tay/khuôn mặt (ZKTeco, Hikvision là mục tiêu đầu tiên) qua (a) connector agent cài on-premise kéo/đẩy log qua HTTPS và (b) import tệp xuất (CSV/Excel/DAT). | M | P2 (thay đổi 2026-10-16, R2-01: connector trực tiếp ở P2; import tệp qua BR-ATT-025) | Sổ đăng ký thiết bị theo tenant |
| BR-ATT-022 | Hệ thống phải duy trì ánh xạ mã đăng ký (ID người dùng trên thiết bị ↔ nhân viên) có hiệu lực theo ngày, và cách ly các lượt chấm có ánh xạ không xác định hoặc đã hết hạn để quản trị viên xử lý. | M | MVP | |
| BR-ATT-023 | Check-in trên mobile phải kiểm tra vị trí so với geofence (lat/long + bán kính) theo địa điểm và/hoặc Wi-Fi SSID/BSSID được phép, tùy chọn kèm selfie + liveness; lượt chấm ngoài vùng được chấp nhận ở trạng thái "cần rà soát" hoặc bị từ chối theo chính sách. | S | P2 | Tọa độ địa điểm lấy từ Core |
| BR-ATT-024 | Check-in trên web phải được cho phép theo nhóm nhân viên và tùy chọn giới hạn theo dải IP. | S | P2 (thay đổi 2026-10-16, X-04/OD-9) | |
| BR-ATT-025 | Quản trị viên phải import được lượt chấm hoặc toàn bộ bảng công ngày từ mẫu Excel kèm báo cáo kiểm tra và dry-run. | M | MVP | Đồng thời là import Attendance Result cho tenant dùng Payroll mà không dùng T&A (GAP-060); không chuyển đổi dữ liệu cũ (thay đổi 2026-10-16, D-09). **Import chấm công từ Excel** là **năng lực baseline**, luôn bật bất kể tenant mua module nào (R2-18); nó tạo ra một phiên bản Attendance Result `CLOSED` chuẩn để giao thức close-hold được áp dụng không thay đổi. Import tệp xuất từ phần mềm máy chấm công (CSV/Excel có bố cục cột theo thiết bị) dùng cùng chức năng import này (R2-01) (thay đổi 2026-10-16, R2-18) |
| BR-ATT-026 | Các lượt chấm trùng lặp trong một khoảng thời gian cấu hình được (mặc định 1 phút) của cùng một nhân viên phải được gộp lại; phát hiện đồng hồ thiết bị lệch > N phút phải phát cảnh báo. | M | P2 (thay đổi 2026-10-16, R2-19) | |
| BR-ATT-027 | Người dùng không bao giờ được sửa hoặc xóa lượt chấm thô; điều chỉnh là các bản ghi riêng liên kết với bảng công ngày. | M | MVP | Bằng chứng cho audit / thanh tra |

### 4.4 Xử lý bảng công ngày
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-030 | Hệ thống phải gán lượt chấm vào ngày công theo khung giờ ca dự kiến (với dung sai trước/sau cấu hình được), hỗ trợ ca qua nửa đêm. | M | MVP | |
| BR-ATT-031 | Quy tắc ghép cặp phải cấu hình được: vào đầu tiên/ra cuối cùng, tất cả các cặp, hoặc theo hướng; lượt chấm không ghép cặp được tạo ra ngoại lệ `Missing in`/`Missing out`. | M | MVP | |
| BR-ATT-032 | Với mỗi ngày của nhân viên, hệ thống phải tính: số giờ theo lịch, số giờ làm việc, số giờ nghỉ có lương, số phút đi muộn, số phút về sớm, số giờ vắng mặt, số giờ làm đêm (tham số khung giờ 22:00–06:00), số phút ứng viên làm thêm, giá trị ngày công (0 / 0.5 / 1 hoặc số thập phân). | M | MVP | Khung giờ đêm [verify-legal] |
| BR-ATT-033 | Mỗi ngày phải có trạng thái ngày và một hoặc nhiều **ký hiệu chấm công** (ví dụ X = đi làm, P = phép năm, Ô = ốm, TS = thai sản, L = lễ, CT = công tác, WFH, KL = không lương, V = vắng không phép), cấu hình được theo tenant kèm ánh xạ sang nhóm của payroll. | M | MVP | Ký hiệu quen thuộc với người dùng VN |
| BR-ATT-034 | Dung sai và hình thức xử lý đi muộn/về sớm phải theo chính sách (ví dụ ân hạn 5 phút, sau đó trừ theo phút, theo khối 15/30 phút, hoặc tính là vắng nửa ngày); T&A xuất số phút và số lần, không xuất tiền. | M | P2 (thay đổi 2026-10-16, R2-19) | |
| BR-ATT-035 | Các khoảng thời gian nghỉ ngắn có hưởng lương theo luật phải được tính là thời gian làm việc: lao động nữ trong thời gian hành kinh (30 phút/ngày, 3 ngày/tháng), nuôi con dưới 12 tháng tuổi (60 phút/ngày), lao động nữ mang thai từ tháng thứ 7 (hoặc tháng thứ 6 ở vùng sâu vùng xa) được làm ít hơn 1h mỗi ngày. | M | MVP | Điều 137, Nghị định 145 Điều 80 [verify-legal]; cần dữ liệu từ People |
| BR-ATT-036 | Nhân viên phải gửi được yêu cầu điều chỉnh chấm công (thiếu lượt chấm, sai giờ, quên check-in, làm việc tại khách hàng) kèm lý do và tệp đính kèm; phê duyệt qua workflow của Core; giới hạn N yêu cầu mỗi tháng có thể cấu hình. | M | MVP | |
| BR-ATT-037 | Quản trị viên phải sửa hàng loạt được bảng công ngày với lý do bắt buộc, giữ giá trị trước/sau trong lịch sử. | M | MVP | |
| BR-ATT-038 | Việc xử lý phải chạy lại được cho bất kỳ khoảng ngày đang mở/tập nhân viên nào và có tính idempotent; việc tính lại được kích hoạt khi có lượt chấm đến, yêu cầu được duyệt, thay đổi lịch làm việc/ngày lễ/phân công. | M | MVP | |
| BR-ATT-039 | Các ngày nằm ngoài thời gian làm việc của nhân viên (trước ngày nhận việc, sau ngày nghỉ việc, trong thời gian tạm hoãn hợp đồng) phải được đánh dấu `Not employed` / `Suspended`, khác với vắng mặt. | M | MVP | Ngày lấy từ People |

### 4.5 Làm thêm giờ
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-040 | Nhân viên hoặc quản lý (thay mặt một hoặc nhiều nhân viên) phải gửi được yêu cầu làm thêm giờ với ngày, giờ bắt đầu/kết thúc dự kiến, lý do và cờ đồng ý của nhân viên; phê duyệt qua workflow của Core với số cấp cấu hình được. | M | MVP | Sự đồng ý: Điều 107(2) |
| BR-ATT-041 | Hệ thống phải phân loại mỗi phút làm thêm vào đúng một loại: **OT-WD** ban ngày ngày thường, **OT-RD** ban ngày ngày nghỉ hằng tuần, **OT-PH** ban ngày ngày lễ / ngày nghỉ có hưởng lương, **OT-WD-N**, **OT-RD-N**, **OT-PH-N** (làm thêm ban đêm), và ghi nhận riêng **NW** làm việc ban đêm trong giờ bình thường (không phải làm thêm). | M | MVP | Tỷ lệ là tham số của Payroll |
| BR-ATT-042 | Với làm thêm ban đêm, hệ thống phải gắn cờ nhân viên có làm thêm ban ngày trong cùng ngày trước khi làm thêm ban đêm hay không, vì cơ sở tính phụ trội làm thêm ban đêm khác nhau. | M | MVP | Nghị định 145 Điều 57(3) [verify-legal] |
| BR-ATT-043 | Chính sách phát hiện làm thêm giờ phải cấu hình được theo nhóm nhân viên: (a) chỉ duyệt trước, (b) làm thêm được phát hiện cần quản lý xác nhận, (c) tự động phê duyệt tới N phút; khối làm thêm tối thiểu và làm tròn (ví dụ 15/30 phút) cấu hình được. | M | P2 (thay đổi 2026-10-16, R2-19) | |
| BR-ATT-044 | Giờ làm thêm được trả mỗi ngày = min(làm thêm đã duyệt, làm thêm thực tế từ lượt chấm) theo mặc định; chính sách có thể cho phép "đã duyệt = được trả" đối với nhân viên được miễn chấm công. | M | MVP | |
| BR-ATT-045 | Hệ thống phải thực thi **giới hạn ngày**: giờ bình thường + làm thêm ≤ 12h/ngày (ngày thường); vào ngày nghỉ/ngày lễ làm thêm ≤ 12h; cấu hình được là cảnh báo hoặc chặn. | M | MVP | Nghị định 145 Điều 60 [verify-legal] |
| BR-ATT-046 | Hệ thống phải thực thi **giới hạn tháng** (40h) và **giới hạn năm** (mặc định 200h; 300h cho các pháp nhân/nhóm nhân viên được đánh dấu thuộc ngành nghề đủ điều kiện), tính cả làm thêm đã duyệt + đang chờ; cảnh báo tại các ngưỡng cấu hình được (ví dụ 80%) và chặn ở 100% trừ khi có ghi đè được ủy quyền kèm lý do. | M | MVP | Điều 107(2b,3); Nghị định 145 Điều 61 [verify-legal]; giới hạn có hiệu lực theo ngày (tham khảo mức tạm thời 60h/tháng năm 2022) |
| BR-ATT-047 | Giới hạn làm thêm năm phải được đo theo năm dương lịch trên mọi phân công và pháp nhân của cùng một nhân viên trong tenant **và** theo từng pháp nhân, tùy cấu hình. | S | P2 (thay đổi 2026-10-16, X-04) | Q4. Ở Phase 1 hỗ trợ kiêm nhiệm ở nhiều pháp nhân (R2-16), nhưng giới hạn tháng và năm chỉ được đo **theo từng pháp nhân**; việc đo theo từng người trên nhiều pháp nhân chờ ý kiến pháp lý (R2-08) (thay đổi 2026-10-16, R2-16) |
| BR-ATT-048 | Hệ thống phải chặn làm thêm giờ và phân ca đêm đối với lao động được bảo vệ: dưới 18 tuổi (trừ danh mục được phép), mang thai từ tháng thứ 7 (tháng thứ 6 ở vùng sâu vùng xa), đang nuôi con dưới 12 tháng tuổi — trừ khi nhân viên đã có sự đồng ý được ghi nhận ở những trường hợp pháp luật cho phép. | M | MVP | Điều 137, 146 [verify-legal] |
| BR-ATT-049 | Khi áp dụng chế độ 300h, hệ thống phải nhắc quản trị viên thông báo cho cơ quan quản lý lao động cấp tỉnh và ghi nhận số tham chiếu thông báo. | S | MVP | Điều 107(4) [verify-legal] |
| BR-ATT-050 | Nhân viên phải chọn được, khi chính sách cho phép, nghỉ bù (TOIL) thay vì nhận tiền làm thêm cho từng yêu cầu làm thêm; giờ TOIL được cộng vào số dư nghỉ bù theo tỷ lệ 1:1 (cấu hình được) và bị loại khỏi đầu ra làm thêm được trả. | S | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-051 | Hệ thống phải cảnh báo khi nhân viên làm thêm nhiều ngày liên tiếp mà không được nghỉ bù theo quy định của pháp luật. | C | P2 | Nghị định 145 Điều 62 [verify-legal] |

### 4.6 Quản lý nghỉ phép
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-055 | Quản trị viên phải cấu hình được loại nghỉ với: bên chi trả (người sử dụng lao động / quỹ BHXH / không lương), đơn vị (ngày, nửa ngày, giờ), cơ sở tính (ngày làm việc hay ngày lịch), cờ theo dõi số dư, điều kiện (giới tính, thâm niên, thử việc, nhóm nhân viên), giấy tờ bắt buộc, tối đa mỗi yêu cầu/năm, có được tính vào thời gian làm việc để tính phép năm hay không, ánh xạ nhóm payroll. | M | MVP | |
| BR-ATT-056 | Các loại nghỉ VN được khởi tạo sẵn: Phép năm; Nghỉ việc riêng có lương — bản thân kết hôn (3 ngày), con kết hôn (1 ngày), cha mẹ đẻ/cha mẹ vợ hoặc chồng/vợ hoặc chồng/con chết (3 ngày); Nghỉ việc riêng không lương — ông bà/anh chị em ruột chết, cha mẹ/anh chị em ruột kết hôn (1 ngày); Ốm đau (BHXH); Chăm con ốm (BHXH); Thai sản (BHXH); Nghỉ khi vợ sinh (BHXH); Khám thai (BHXH); Sẩy thai/phá thai (BHXH); Điều trị tai nạn lao động/bệnh nghề nghiệp; Nghỉ không lương theo thỏa thuận; Nghỉ bù (TOIL); Công tác và WFH (ký hiệu không phải nghỉ). | M | MVP | Điều 115, Luật BHXH [verify-legal] |
| BR-ATT-057 | Số ngày phép năm mặc định là 12 ngày làm việc/năm, 14 ngày cho người chưa thành niên / người khuyết tật / làm nghề nặng nhọc, độc hại, 16 ngày cho nghề đặc biệt nặng nhọc, độc hại, cộng thêm 1 ngày cho mỗi 5 năm làm việc đủ; tenant có thể cấp nhiều hơn nhưng không ít hơn. | M | MVP | Điều 113, 114 [verify-legal]; cờ công việc lấy từ People |
| BR-ATT-058 | Phương pháp tích lũy: tích lũy hằng tháng, cấp trước theo năm, hoặc cấp theo tỷ lệ; tính theo tỷ lệ cho người mới vào/nghỉ việc theo Nghị định 145 (tháng được tính nếu nhân viên làm việc ≥ một nửa số ngày làm việc của tháng). | M | MVP | Nghị định 145 Điều 66 [verify-legal] |
| BR-ATT-059 | Thời gian làm việc để tính ngày phép thâm niên và tính theo tỷ lệ phải bao gồm các khoảng thời gian được pháp luật tính (ví dụ nghỉ ốm tới giới hạn cộng dồn theo luật, nghỉ thai sản, nghỉ không lương theo thỏa thuận tới 1 tháng/năm) và loại trừ các khoảng khác, theo tham số có hiệu lực theo ngày. | M | MVP | Nghị định 145 Điều 65 [verify-legal] |
| BR-ATT-060 | Năm phép phải cấu hình được (mặc định năm dương lịch); chuyển phép sang năm sau với số ngày tối đa và ngày hết hạn; số dư hết hạn bị hủy hoặc được gắn cờ để thanh toán theo chính sách. | M | MVP | |
| BR-ATT-061 | Nhân viên phải yêu cầu được nghỉ theo ngày, nửa ngày (sáng/chiều) hoặc giờ tùy loại nghỉ cho phép; hệ thống tính thời lượng từ lịch làm việc (không tính ngày nghỉ và ngày lễ với loại nghỉ tính theo ngày làm việc; tính cả với loại nghỉ tính theo ngày lịch như thai sản). | M | MVP | |
| BR-ATT-062 | Khi gửi, hệ thống phải kiểm tra số dư (tùy chọn cho phép số dư âm tới N ngày), chồng lấn với yêu cầu/chuyến công tác khác, thời hạn báo trước, điều kiện và tệp đính kèm bắt buộc; số dư được tạm giữ khi đang chờ. | M | MVP | |
| BR-ATT-063 | Yêu cầu nghỉ ốm, chăm con ốm và thai sản phải ghi nhận dữ liệu cần cho hồ sơ BHXH: loại và số giấy chứng nhận, cơ sở khám chữa bệnh cấp, nhóm chẩn đoán, ngày sinh/mã định danh của con, ngày sinh con, số con được sinh, cờ sinh mổ và tệp đính kèm. | M | MVP (thay đổi 2026-10-16, R2-07, tối thiểu) | Cần cho Payroll/Insurance; Luật BHXH 2024 [verify-legal] |
| BR-ATT-064 | Hệ thống phải theo dõi số ngày nghỉ ốm hưởng BHXH đã dùng trong năm dương lịch so với mức hưởng BHXH (ví dụ 30/40/60 ngày làm việc theo số năm đóng; cao hơn với nghề nặng nhọc) dưới dạng thông tin/cảnh báo, không chặn cứng. | S | P2 (thay đổi 2026-10-16, X-04) | Số năm đóng lấy từ Payroll [verify-legal] |
| BR-ATT-065 | Nghỉ thai sản (mặc định 6 tháng; +1 tháng cho mỗi con thêm) phải hỗ trợ đi làm sớm sau thời gian tối thiểu theo luật, kèm thông báo và tác động lên bảng công. | M | MVP | Điều 139 [verify-legal] |
| BR-ATT-066 | Nghỉ phép đã duyệt có thể bị hủy hoặc rút ngắn bởi nhân viên (trước ngày bắt đầu) hoặc bởi HR (bất kỳ lúc nào) kèm lý do; số dư được hoàn lại; nếu ngày nghỉ nằm trong kỳ đã khóa thì tạo chênh lệch hồi tố. | M | P2 (thay đổi 2026-10-16, R2-19) | |
| BR-ATT-067 | Khi nghỉ việc, hệ thống phải tính số ngày phép năm chưa nghỉ (đã tích lũy − đã nghỉ, bao gồm phép chuyển sang chưa hết hạn và tính theo tỷ lệ tới ngày làm việc cuối cùng) và số dư âm, rồi công bố cho Payroll và People (thủ tục nghỉ việc). | M | MVP | Điều 113(3) [verify-legal] |
| BR-ATT-068 | Quản trị viên phải điều chỉnh thủ công được số dư (số dư đầu kỳ được nạp bằng import số dư đầu kỳ (thay đổi 2026-10-16, D-09), điều chỉnh, cấp thêm) kèm lý do; mọi biến động số dư là một giao dịch sổ cái (tích lũy, nghỉ, hủy, chuyển, hết hạn, thanh toán, điều chỉnh). | M | MVP | |
| BR-ATT-069 | Lịch nghỉ của nhóm cho quản lý và nhân viên (tuân theo phạm vi dữ liệu) phải hiển thị các vắng mặt đã duyệt/đang chờ và làm nổi bật các trường hợp dưới mức nhân sự tối thiểu theo đơn vị tổ chức. | S | P2 (thay đổi 2026-10-16, X-04) | |

### 4.7 Công tác và làm việc từ xa
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-070 | Nhân viên phải gửi được yêu cầu công tác (ngày, nửa ngày, nơi đến trong nước/nước ngoài, mục đích, dự án/trung tâm chi phí) và ngày WFH; phê duyệt qua workflow của Core; ngày đã duyệt được tính là đã làm việc và không cần lượt chấm trừ khi chính sách yêu cầu check-in trên mobile. T&A là **chủ sở hữu duy nhất của công tác**; các chuyến công tác được duyệt, thay đổi và hủy phát `att.BusinessTripApproved` (ATT-P-08) cho chi phí/công tác phí của People (thay đổi 2026-10-16, R2-02 §5 #6). | M | P2 (thay đổi 2026-10-16, R2-19) | Chi phí do People xử lý |

### 4.8 Chốt kỳ, bàn giao cho payroll và hồi tố
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-071 | Thay đổi ảnh hưởng tới kỳ `Locked` không bao giờ được làm thay đổi snapshot đã khóa; hệ thống phải sinh các dòng chênh lệch hồi tố (kỳ gốc, nhân viên, đoạn phân công, loại, số lượng chênh lệch, lý do, chứng từ nguồn) gắn vào Attendance Result của kỳ mở kế tiếp. | M | MVP | Cốt lõi của hồi tố lương |
| BR-ATT-072 | Kỳ chấm công phải khớp với lịch lương của Payroll theo nhóm lương (tháng dương lịch hoặc ngày chốt tùy chỉnh, ví dụ ngày 26 → ngày 25); T&A phải hỗ trợ ngày chốt làm thêm riêng nếu nhóm lương định nghĩa. | M | MVP | Lịch lương do Payroll sở hữu |
| BR-ATT-073 | Vòng đời kỳ: `OPEN` → `IN_REVIEW` → `CLOSED` (Attendance Result có phiên bản được công bố cho Payroll; có thể bị *giữ* bởi một kỳ lương đã trình) → `LOCKED` (phiên bản đang bị giữ được sử dụng bởi một kỳ lương **APPROVED**). Mở lại `CLOSED`→`OPEN` cần quản trị viên T&A + lý do, chỉ được phép khi không có close hold nào đang hiệu lực (BR-ATT-079), và phát `att.AttendancePeriodReopened`. `LOCKED`→`CLOSED` chỉ có thể thực hiện thông qua mở khóa của Payroll (BR-ATT-088); ngoài ra `LOCKED` là trạng thái cuối và các thay đổi sau đó trở thành chênh lệch hồi tố (BR-ATT-071). (thay đổi 2026-10-16, X-03) | M | MVP | Trạng thái thống nhất, gap-analysis §7.1 |
| BR-ATT-074 | Trước khi chốt, hệ thống phải hiển thị danh sách kiểm tra ngoại lệ (thiếu lượt chấm, vắng mặt không có ký hiệu, yêu cầu nghỉ/làm thêm/điều chỉnh đang chờ, lượt chấm bị cách ly, vượt giới hạn) và chỉ cho phép chốt khi các ngoại lệ chặn đã được xử lý hoặc được miễn một cách tường minh. | M | MVP | |
| BR-ATT-075 | Phải chốt được theo pháp nhân × nhóm lương, và chốt sớm cho từng nhân viên (ví dụ trường hợp nghỉ việc cần thanh toán lương cuối trước khi kết thúc kỳ). | M | MVP (thay đổi 2026-10-16, R3-06) | |
| BR-ATT-076 | **Attendance Result** được công bố phải chứa, theo nhân viên × đoạn phân công × kỳ, các số lượng liệt kê ở §7.3, có phiên bản (mỗi lần chốt lại tạo một phiên bản mới; Payroll được thông báo phiên bản nào là hiện hành). | M | MVP | |
| BR-ATT-077 | Nhân viên xác nhận bảng công tháng (ESS) một cách tùy chọn trong khoảng thời gian cấu hình được; khiếu nại tạo ra yêu cầu điều chỉnh. | C | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-078 | Quản lý ký xác nhận bảng công theo nhóm trước khi HR chốt (cấu hình được). | S | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-079 | **Close hold.** T&A phải cung cấp một API đồng bộ, idempotent để Payroll *lấy* và *giải phóng* hold trên một phiên bản Attendance Result cụ thể (khóa: tenant, pháp nhân, nhóm lương, kỳ, phiên bản kết quả, run id). Việc lấy hold chỉ thành công nếu kỳ ở trạng thái `CLOSED` và phiên bản là hiện hành; ngược lại sẽ bị từ chối (không có hold mồ côi). Khi hold đang hiệu lực, T&A từ chối mở lại và mọi thay đổi tại chỗ đối với phiên bản đó. Hold không bao giờ hết hạn theo thời gian; chỉ các hành động của Payroll mới giải phóng hold (từ chối, rút lại, hủy, yêu cầu giải phóng, mở khóa). Mỗi lần cấp/giải phóng phát `att.AttendancePeriodHoldGranted` / `att.AttendancePeriodHoldReleased`. (mới 2026-10-16, X-03) | M | MVP | ADR-0019; ATT-P-17/18 |
| BR-ATT-087 | **Mở lại kỳ đang bị giữ.** Yêu cầu mở lại của quản trị viên T&A đối với kỳ đang bị giữ không được thực hiện trực tiếp: hệ thống phải hiển thị kỳ lương đang giữ, tạo công việc yêu cầu giải phóng cho quản trị viên payroll (workflow / thông báo của Core), và tự động thực hiện việc mở lại khi hold được giải phóng. (mới 2026-10-16, X-03) | M | MVP | Kỳ lương trở về `CALCULATED` với cờ stale |
| BR-ATT-088 | **Khóa và mở khóa.** Khi nhận `pay.PayrollRunStatusChanged(APPROVED)`, T&A phải khóa phiên bản đang bị giữ (`CLOSED → LOCKED`, idempotent, phát `att.AttendancePeriodLocked`); không bao giờ được xảy ra lệch phiên bản và, nếu phát hiện, được xử lý như một sự cố đối chiếu, không phải từ chối. Khi nhận `pay.PayrollRunStatusChanged(UNLOCKED)`, T&A phải đưa kỳ về `LOCKED → CLOSED` (cùng phiên bản, hold được giải phóng) — đường ngược duy nhất. Việc **đảo ngược** kỳ lương không ảnh hưởng tới T&A. Một job đối chiếu hằng đêm so sánh trạng thái kỳ lương và kỳ chấm công và cảnh báo khi không khớp. (mới 2026-10-16, X-03) | M | MVP | ADR-0019 |

### 4.9 Tự phục vụ, thông báo và cấu hình
| ID | Yêu cầu | Ưu tiên | Giai đoạn | Ghi chú |
|---|---|---|---|---|
| BR-ATT-080 | ESS phải hiển thị lịch làm việc, lượt chấm, bảng công ngày, ngoại lệ, số dư (kèm sổ cái) và trạng thái yêu cầu của nhân viên, bằng tiếng Việt và tiếng Anh. | M | MVP | |
| BR-ATT-081 | MSS phải hiển thị cho nhóm "ai có mặt / đi muộn / vắng mặt / nghỉ phép hôm nay", phê duyệt đang chờ, làm thêm của nhóm so với giới hạn. | M | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-082 | Thông báo qua Core: yêu cầu được gửi/được duyệt/bị từ chối, nhắc thiếu lượt chấm (sáng hôm sau), ngưỡng giới hạn làm thêm, công bố phân ca, nhắc số dư sắp hết hạn (ví dụ 30 ngày trước), nhắc chốt kỳ. | M | MVP | |
| BR-ATT-083 | Mọi chính sách T&A (ân hạn, làm tròn, chính sách làm thêm, chính sách nghỉ phép) phải có hiệu lực theo ngày và gán được theo pháp nhân, nhóm nhân viên, đơn vị tổ chức hoặc cá nhân với thứ tự ưu tiên xác định. | M | P2 (thay đổi 2026-10-16, R2-19) | |
| BR-ATT-084 | Ủy quyền phê duyệt và trạng thái vắng mặt của người phê duyệt do workflow của Core xử lý; T&A phải tự động leo thang các yêu cầu đang chờ quá N ngày trước khi chốt kỳ. | S | P2 (thay đổi 2026-10-16, X-04) | |
| BR-ATT-085 | Public API phải cho phép hệ thống bên ngoài đẩy lượt chấm và đọc bảng công ngày/số dư (thông tin xác thực API theo phạm vi tenant). | S | P2 | |
| BR-ATT-086 | Import số dư đầu kỳ: nạp số dư phép đầu kỳ (theo loại nghỉ và năm phép, bao gồm phép chuyển sang và ngày hết hạn) và số giờ làm thêm lũy kế từ đầu năm (cho giới hạn tháng/năm) theo nhân viên từ các mẫu Excel số dư đầu kỳ chung (import engine của Core), kèm báo cáo kiểm tra, dry-run và ký xác nhận đối chiếu. Không chuyển đổi dữ liệu Oracle cũ và không nạp bảng công lịch sử. (thay đổi 2026-10-16, D-09) | M | MVP | Giờ làm thêm lũy kế từ đầu năm là thiết yếu cho giới hạn |

Tổng số yêu cầu nghiệp vụ: 77.

---

## 5. Quy định tuân thủ Việt Nam

Mọi giá trị là **tham số có hiệu lực theo ngày** (cấu hình Core / legal pack của nền tảng), không bao giờ hard-code. Mọi số liệu đều `[verify-legal]`.

| # | Quy định | Giá trị (mặc định) | Căn cứ pháp lý | Yêu cầu |
|---|---|---|---|---|
| VN-01 | Thời giờ làm việc bình thường | ≤ 8h/ngày và ≤ 48h/tuần; nếu theo tuần: ≤ 10h/ngày, ≤ 48h/tuần | Bộ luật Lao động 2019 (BLLĐ) Điều 105 [verify-legal] | 016, 018 |
| VN-02 | Khung giờ làm việc ban đêm | 22:00 – 06:00 | BLLĐ Điều 106 [verify-legal] | 032, 041 |
| VN-03 | Nghỉ giữa ca | ≥ 30 phút (ban ngày), ≥ 45 phút (ban đêm) khi làm việc liên tục ≥ 6h; được tính vào thời giờ làm việc | BLLĐ Điều 109 [verify-legal] | 010 |
| VN-04 | Nghỉ chuyển ca | ≥ 12h | BLLĐ Điều 110 [verify-legal] | 018 |
| VN-05 | Nghỉ hằng tuần | ≥ 24 giờ liên tục/tuần; nếu không thể, trung bình ≥ 4 ngày/tháng | BLLĐ Điều 111 [verify-legal] | 014, 018 |
| VN-06 | Ngày lễ (11 ngày) | Tết Dương lịch 1/1 (1 ngày); Tết Âm lịch (5 ngày); Giỗ Tổ Hùng Vương 10/3 âm lịch (1 ngày); 30/4 (1 ngày); 1/5 (1 ngày); 2/9 + 1 ngày liền kề (2 ngày). Người nước ngoài +2 ngày. Ngày lễ trùng ngày nghỉ hằng tuần → nghỉ bù vào ngày làm việc kế tiếp | BLLĐ Điều 112 [verify-legal]; theo dõi ngày lễ mới được bổ sung bằng nghị quyết của Quốc hội | 001–005 |
| VN-07 | Sự đồng ý và giới hạn làm thêm giờ | Sự đồng ý của người lao động; giờ bình thường + làm thêm ≤ 12h/ngày; ≤ 40h/tháng; ≤ 200h/năm; ≤ 300h/năm cho các ngành nghề được liệt kê (dệt, may, da giày, điện tử, chế biến nông lâm thủy sản, điện, viễn thông, cấp nước, lọc dầu…, cùng các trường hợp cấp bách) | BLLĐ Điều 107; Nghị định 145/2020 Điều 60–62 [verify-legal] | 040, 045–047 |
| VN-08 | Thông báo chế độ 300h | Thông báo bằng văn bản cho cơ quan quản lý lao động cấp tỉnh | BLLĐ Điều 107(4); Nghị định 145 Điều 62 [verify-legal] | 049 |
| VN-09 | Tỷ lệ tiền lương làm thêm giờ (cho Payroll; T&A cung cấp loại) | Ngày thường ≥ 150%; ngày nghỉ hằng tuần ≥ 200%; ngày lễ / ngày nghỉ có hưởng lương ≥ 300% (chưa kể tiền lương ngày lễ đối với người hưởng lương tháng) | BLLĐ Điều 98(1); Nghị định 145 Điều 55 [verify-legal] | 041 |
| VN-10 | Phụ cấp làm việc ban đêm | ≥ +30% tiền lương ngày làm việc bình thường | BLLĐ Điều 98(2); Nghị định 145 Điều 56 [verify-legal] | 041 |
| VN-11 | Làm thêm giờ ban đêm | Tỷ lệ làm thêm + 30% + 20% × tiền lương làm việc ban ngày của ngày đó (lương ngày bình thường nếu trước đó không làm thêm ban ngày; lương làm thêm ban ngày nếu trước đó có làm thêm ban ngày) — ví dụ 200%/210% ngày thường, 270% ngày nghỉ, 390% ngày lễ | BLLĐ Điều 98(3); Nghị định 145 Điều 57 [verify-legal] | 041, 042 |
| VN-12 | Nghỉ hằng năm | 12 / 14 / 16 ngày làm việc; +1 ngày cho mỗi 5 năm làm việc; tính theo tỷ lệ; phép chưa nghỉ được thanh toán khi nghỉ việc | BLLĐ Điều 113–114; Nghị định 145 Điều 65–67 [verify-legal] | 057–059, 067 |
| VN-13 | Nghỉ việc riêng | Có lương: bản thân kết hôn 3 ngày, con kết hôn 1 ngày, cha mẹ đẻ/cha mẹ vợ hoặc chồng/vợ hoặc chồng/con chết 3 ngày. Không lương: ông bà/anh chị em ruột chết 1 ngày, cha mẹ/anh chị em ruột kết hôn 1 ngày | BLLĐ Điều 115 [verify-legal] | 056 |
| VN-14 | Nghỉ ốm (BHXH chi trả) | 30 / 40 / 60 ngày làm việc/năm theo số năm đóng BHXH (<15, 15–<30, ≥30); 40/50/70 với nghề nặng nhọc, độc hại; bệnh dài ngày tới 180 ngày; chăm con ốm 20 ngày (<3 tuổi) / 15 ngày (3–<7 tuổi). Trợ cấp do quỹ BHXH chi trả, không phải tiền lương | Luật BHXH 2024 (Luật 41/2024/QH15, hiệu lực 1/7/2025) [verify-legal] | 063, 064 |
| VN-15 | Thai sản / nghỉ khi vợ sinh | Thai sản 6 tháng (+1 tháng cho mỗi con thêm); nghỉ khi vợ sinh 5/7/10/14 ngày làm việc; 5 lần khám thai | BLLĐ Điều 139; Luật BHXH 2024 [verify-legal] | 056, 063, 065 |
| VN-16 | Lao động được bảo vệ | Không làm thêm giờ/làm đêm/đi công tác xa đối với lao động nữ mang thai từ tháng thứ 7 (tháng thứ 6 ở vùng sâu vùng xa) hoặc nuôi con dưới 12 tháng tuổi trừ khi đồng ý; giảm 1h làm việc mỗi ngày từ tháng thứ 7; 30 phút/ngày trong thời gian hành kinh (3 ngày/tháng), 60 phút/ngày khi nuôi con dưới 12 tháng tuổi, được tính là thời gian có hưởng lương | BLLĐ Điều 137; Nghị định 145 Điều 80 [verify-legal] | 035, 048 |
| VN-17 | Người chưa thành niên (< 18) | Tối đa 8h/ngày, 40h/tuần (15–<18 tuổi); làm thêm/làm đêm chỉ với các công việc được phép | BLLĐ Điều 146 [verify-legal] | 048 |
| VN-18 | Ngưỡng miễn đóng BHXH | Người lao động không làm việc và không hưởng tiền lương từ 14 ngày làm việc trở lên trong tháng → không đóng BHXH/BHYT/BHTN tháng đó (trừ quy định về thai sản) | Quyết định 595/QĐ-BHXH / Luật BHXH 2024 [verify-legal] | §7.3 mục 10 |
| VN-19 | Thuế TNCN đối với làm thêm giờ | Phần tiền lương làm thêm/làm đêm cao hơn tiền lương bình thường được miễn thuế TNCN → Payroll cần số giờ theo từng loại | Luật Thuế TNCN; Thông tư 111/2013 Điều 3 [verify-legal] | §7.3 |
| VN-20 | Lưu giữ hồ sơ | Hồ sơ chấm công và làm thêm giờ được lưu giữ và sẵn sàng phục vụ thanh tra | BLLĐ; Nghị định 12/2022 (xử phạt) [verify-legal] | NFR-ATT-007 |

---

## 6. Thực thể nghiệp vụ chính (schema `att`)

| Thực thể | Mục đích | Thuộc tính chính |
|---|---|---|
| HolidayCalendar | Lịch có tên được gán cho pháp nhân/địa điểm | tenant_id, code, legal_entity_ids, source (pack của nền tảng / tùy chỉnh), year |
| HolidayDate | Một mục ngày lễ/hoán đổi ngày | calendar_id, date, type (public / substitute / company / swap_off / swap_work), holiday_code, lunar_ref, paid_flag, eligibility (all / foreigners), effective_from |
| ShiftDefinition | Mẫu thời gian cho một ngày làm việc | code, start, end, cross_midnight, breaks[], core_hours, grace_in/out, rounding, std_hours, day_value, night_window_override |
| SchedulePattern | Chu kỳ ca theo tuần hoặc N ngày | code, type (weekly/rotating/flex), cycle_length, anchor_date, days[] (ca hoặc ngày nghỉ), weekly_rest_days, std_hours_week |
| ScheduleAssignment | Nhân viên/phân công ↔ mô hình lịch (do T&A sở hữu, R2-02 §5 #26) | employee_id, assignment_id, pattern_id, valid_from/to, source (rule / manual) |
| Roster / RosterLine | Ca dự kiến theo ngày của nhân viên | org_unit, period, status (draft/published), employee_id, date, shift_id, version |
| Device / Connector | Điểm thu nhận chấm công | tenant_id, vendor, model, serial, location_id, time_zone, connector_type, last_sync, status |
| Geofence / WifiZone | Vùng kiểm tra cho mobile | location_id, lat, long, radius_m, ssid/bssid |
| EnrolmentMapping | Người dùng thiết bị ↔ nhân viên | device_or_group, enrol_id, employee_id, valid_from/to |
| RawPunch | Bản ghi lượt chấm bất biến | punch_ts_utc, local_ts, source, device_id, enrol_id, employee_id, direction, verify_method, geo, photo_ref, validation_status, import_batch_id |
| DailyTimesheet | Ngày công của nhân viên đã tính | employee_id, assignment_id, work_date, shift_id, status, codes[], scheduled_h, worked_h, late_min, early_min, night_h, ot_candidate_min, day_value, locked_flag, calc_version |
| TimesheetCorrection | Điều chỉnh đã duyệt / chỉnh sửa của quản trị viên | daily_timesheet_id, field, old, new, reason, request_id |
| OvertimeRequest | Làm thêm dự kiến hoặc được phát hiện | employee_id, date, planned_from/to, consent_flag, source (pre/detected), comp_choice (pay/TOIL), status, workflow_instance_id |
| OvertimeResult | Làm thêm được trả theo ngày theo loại | employee_id, work_date, category (OT-WD…OT-PH-N), minutes, preceded_by_day_ot, request_id |
| OtCapLedger | Tổng lũy kế cho giới hạn | employee_id, legal_entity_id, month, year, approved_min, pending_min, regime (200/300) |
| LeaveType / LeavePolicy | Cấu hình nghỉ phép | code, paid_by, unit, counting_basis, quy tắc mức hưởng, phương pháp tích lũy, tối đa chuyển phép/hết hạn, eligibility, payroll_category |
| LeaveRequest | Đơn nghỉ phép | employee_id, type, from/to, các phần nửa ngày, hours, duration_days, status, dữ liệu hồ sơ BHXH, tệp đính kèm |
| LeaveBalanceLedger | Mọi biến động số dư | employee_id, type, leave_year, txn_type (accrue/take/cancel/carry/expire/encash/adjust), quantity, ref |
| BusinessTrip / RemoteWorkRequest | Yêu cầu có mặt không phải nghỉ phép | employee_id, dates, half-days, destination, domestic/overseas, cost_centre, status |
| AttendancePeriod | Kỳ theo nhóm lương | pay_group_id, legal_entity_id, period_start/end, ot_cutoff, std_day_method (sao chép từ lịch lương, R2-02 §5 #25), status (OPEN/IN_REVIEW/CLOSED/LOCKED), current_version, locked_version |
| PayGroupMembership | Bản sao chỉ đọc liên kết nhân viên → nhóm lương của Payroll (R2-02 §5 #25) | employment_id, pay_group_id, valid_from/to, source event id |
| AttendancePeriodHold | Close hold của Payroll trên một phiên bản kết quả (thay đổi 2026-10-16, X-03) | period_id, result_version, run_id, status (ACTIVE/RELEASED/CONSUMED), granted_at, released_at, release_reason (REJECTED/WITHDRAWN/CANCELLED/REOPEN_REQUESTED/UNLOCKED) |
| AttendanceResult / AttendanceResultLine | Snapshot được công bố cho Payroll | period_id, version, employee_id, assignment_segment, category_code, quantity, unit, is_retro, original_period_id |
| AttendanceCode | Ký hiệu ngày và ánh xạ sang payroll | code, label_vi/en, payroll_category, counts_as_worked, paid_by |

---

## 7. Interface liên module

ID interface (`ATT-C-nn` sử dụng, `ATT-P-nn` cung cấp) dùng để truy vết trong gap register. "Khi nào" = tác nhân kích hoạt/thời điểm.

### 7.1 Sử dụng

| ID | Nội dung | Từ module | Khi nào | Dùng cho | Ưu tiên |
|---|---|---|---|---|---|
| ATT-C-01 | Ngữ cảnh tenant, cấu hình tenant, múi giờ, ngôn ngữ mặc định | saas-platform | Khởi tạo tenant; mọi request | Cô lập dữ liệu, quy đổi giờ địa phương | M |
| ATT-C-02 | Quyền lợi theo gói/tính năng (check-in trên mobile, connector thiết bị, phân ca, số thiết bị tối đa) | saas-platform | Khởi tạo, thay đổi gói | Kiểm soát tính năng | M |
| ATT-C-03 | **Pack tham số pháp lý VN** mặc định: mẫu ngày lễ quốc gia theo năm (bao gồm quy đổi âm lịch), giới hạn làm thêm giờ, khung giờ đêm, mức hưởng phép, quy tắc lao động được bảo vệ, danh sách ngành nghề 300h — đọc qua `getParam(key, scope, asOfDate)`; `saas.LegalParameterPackPublished`; mỗi kết quả lưu phiên bản pack | saas-platform (Legal Parameter Service) | Hằng năm + khi luật thay đổi | BR-ATT-002, 045–048, 057 (thay đổi 2026-10-16, R2-02 §5 #1) | M |
| ATT-C-04 | Gateway connector thiết bị: đăng ký endpoint/agent bảo mật, thông tin xác thực API, giới hạn tần suất | saas-platform | Khi đưa thiết bị vào sử dụng; liên tục | BR-ATT-021, 085 | M |
| ATT-C-05 | Khung mobile app & kênh push notification | saas-platform | P2 | Check-in trên mobile, nhắc nhở | S |
| ATT-C-06 | Pháp nhân (công ty, MST), cây đơn vị tổ chức kèm người quản lý, trung tâm chi phí | core | Khi thay đổi (sự kiện) + tra cứu | Gán lịch, phạm vi kỳ, báo cáo, xác định người phê duyệt | M |
| ATT-C-07 | Địa điểm làm việc kèm địa chỉ và **tọa độ địa lý**, múi giờ | core | Khi thay đổi | Vị trí thiết bị, geofence, ngày lễ theo địa điểm | M |
| ATT-C-08 | Người dùng, vai trò, quyền và **phạm vi dữ liệu** (ai xem được nhân viên nào) | core | Mỗi request | Truy cập ESS/MSS/quản trị | M |
| ATT-C-09 | **Workflow engine**: loại yêu cầu, chuỗi phê duyệt (quản lý trực tiếp, n cấp, theo số tiền/số giờ), ủy quyền, leo thang, callback khi có quyết định cuối cùng | core | Khi gửi / khi quyết định | Nghỉ phép, làm thêm, điều chỉnh, công tác, WFH, đổi ca, mở lại kỳ | M |
| ATT-C-10 | Dịch vụ thông báo (email, trong ứng dụng, push) và mẫu | core | Theo sự kiện | BR-ATT-082 | M |
| ATT-C-11 | Kho cấu hình có hiệu lực theo ngày, dịch vụ audit, dịch vụ tài liệu (tệp đính kèm) từ **core**; **job scheduler / queue** (nhận biết tenant, hạn mức, API tiến độ) từ **saas-platform** | core; saas-platform (scheduler) | Liên tục | Chính sách, audit trail, tệp đính kèm, job hằng đêm (thay đổi 2026-10-16, R2-02 §5 #13, #15b, #16) | M |
| ATT-C-12 | Master nhân viên: employee ID, họ tên, mã nhân viên, giới tính, ngày sinh, quốc tịch | people | Khi tuyển dụng/thay đổi (sự kiện) | Điều kiện, ngày lễ cho người nước ngoài, quy tắc người chưa thành niên | M |
| ATT-C-13 | Các mốc thời gian làm việc: ngày nhận việc, **ngày bắt đầu tính thâm niên/thời gian làm việc** (cho phép năm), ngày kết thúc thử việc, **ngày làm việc cuối cùng & ngày chấm dứt**, các khoảng tạm hoãn hợp đồng | people | Khi tuyển dụng, thay đổi, nghỉ việc (sự kiện, bao gồm có hiệu lực tương lai) | BR-ATT-039, 057–059, 067 | M |
| ATT-C-14 | **Phân công** (có hiệu lực theo ngày): pháp nhân, đơn vị tổ chức, vị trí/công việc, quản lý trực tiếp, địa điểm, nhóm/loại nhân viên, chế độ theo hợp đồng (toàn thời gian/bán thời gian, giờ/tuần) & FTE, trung tâm chi phí. Không có nhóm lương và không có lịch mặc định: nhóm lương lấy từ Payroll (ATT-C-24) và lịch do T&A gán (BR-ATT-015) | people | Khi thay đổi (sự kiện; hỗ trợ có hiệu lực tương lai) | Phân đoạn, quy tắc lịch, người phê duyệt (thay đổi 2026-10-16, R2-02 §5 #25, #26) | M |
| ATT-C-15 | Thuộc tính công việc: cờ nặng nhọc/độc hại và đặc biệt nặng nhọc/độc hại; cờ miễn chấm công | people | Khi thay đổi | Phép năm 14/16 ngày, miễn trừ làm thêm | M |
| ATT-C-16 | Dữ kiện về lao động được bảo vệ: mang thai (ngày dự sinh / tháng thai), ngày sinh của con (< 12 tháng), tình trạng khuyết tật, làm việc tại vùng sâu vùng xa/vùng khó khăn | people | Khi thay đổi (nhân viên khai báo, HR xác minh) | BR-ATT-035, 048, 057 | M |
| ATT-C-17 | Sự kiện nghỉ việc (đơn nghỉ việc được chấp nhận / sa thải) kèm ngày làm việc cuối cùng | people | Khi được quyết định (trước ngày cuối) | Dự tính phép, chặn nghỉ phép trong tương lai, chốt sớm (BR-ATT-075) | M |
| ATT-C-18 | **Lịch lương** `pay.PayCalendarPublished`: nhóm lương, kỳ, ngày chốt (chấm công và làm thêm), hạn chốt T&A, ngày thanh toán, **phương pháp ngày công chuẩn** | payroll | Thiết lập hằng năm + thay đổi | BR-ATT-072 khớp kỳ; BR-ATT-016 phương pháp STD_DAYS (thay đổi 2026-10-16, R2-02 §5 #25) | M |
| ATT-C-19 | **Trạng thái kỳ lương** `pay.PayrollRunStatusChanged` theo nhóm lương/kỳ kèm các phiên bản chấm công đã sử dụng: `APPROVED` → khóa phiên bản đang bị giữ; `UNLOCKED` → `LOCKED → CLOSED`; `REJECTED`/rút lại → hold đã được Payroll giải phóng; `REVERSED` → T&A không thay đổi (thay đổi 2026-10-16, X-03) | payroll | Mỗi lần chuyển trạng thái kỳ lương (sự kiện) | BR-ATT-073, 088 | M |
| ATT-C-20 | Lịch sử đóng BHXH: tổng số năm đóng BHXH theo nhân viên, bao gồm các tháng trước go-live từ import số dư đầu kỳ (API số năm đóng, IF-PAY-P-08; `pay.SiContributionPosted`) | payroll (insurance) | Hằng tháng / theo yêu cầu | BR-ATT-064 mức hưởng nghỉ ốm (thay đổi 2026-10-16, R2-02 §5 #20) | S |
| ATT-C-21 | Xác nhận đã sử dụng của Payroll = **lệnh close-hold** `acquireHold` / `releaseHold(reason)` trên một phiên bản kết quả (gọi vào ATT-P-17), cùng `versions consumed` trong `pay.PayrollRunStatusChanged` (thay đổi 2026-10-16, X-03) | payroll | Lấy hold khi trình kỳ lương; giải phóng khi từ chối, rút lại, hủy, yêu cầu giải phóng, mở khóa | BR-ATT-079, 087 — chặn mở lại và phát hiện stale | M |
| ATT-C-22 | Chi phí theo giờ ước tính theo nhân viên (hoặc theo khung) | payroll | Khi có yêu cầu làm thêm (P2) | Xem trước chi phí làm thêm cho người phê duyệt | C |
| ATT-C-23 | Ứng viên đã nhận offer kèm ngày bắt đầu dự kiến (trước khi nhận việc) | recruitment (qua people) | Khi offer được chấp nhận | Đăng ký trước trên thiết bị / gán trước lịch trước ngày đầu tiên | C |
| ATT-C-25 | `people.PrimaryEmploymentChanged` (person_id, employment_id, purpose, valid_from/to) | people | Khi thay đổi (sự kiện) | Thông tin cho nhân viên kiêm nhiệm; Phase 1 tính giới hạn làm thêm theo từng quan hệ lao động / pháp nhân nên không làm thay đổi cách tính giới hạn (R2-08) (mới 2026-10-16, R2-16) | S |
| ATT-C-24 | `pay.PayGroupAssigned` (quan hệ lao động, nhóm lương, ngày hiệu lực) — Payroll sở hữu liên kết nhân viên → nhóm lương | payroll | Khi gán theo quy tắc hoặc thủ công (RT) | Phạm vi kỳ, phân đoạn theo nhóm lương, chốt theo pháp nhân × nhóm lương (mới 2026-10-16, R2-02 §5 #25) | M |

### 7.2 Cung cấp

| ID | Nội dung | Tới module | Khi nào | Ưu tiên |
|---|---|---|---|---|
| ATT-P-01 | **Attendance Result** (snapshot có phiên bản theo nhân viên × đoạn phân công × kỳ; nội dung ở §7.3); được thông báo bằng `att.AttendancePeriodClosed(version, checksum)` | payroll | Khi chốt kỳ (và mỗi lần chốt lại, và khi mở khóa với cùng phiên bản); chốt sớm cho từng người nghỉ việc | M |
| ATT-P-02 | **Dòng chênh lệch hồi tố** cho các kỳ đã khóa (kỳ gốc, loại, số lượng ±, lý do) | payroll | Được đưa vào Attendance Result của kỳ mở kế tiếp | M |
| ATT-P-03 | **Thanh toán / thu hồi phép khi nghỉ việc**: số ngày phép năm chưa nghỉ (bao gồm phép chuyển sang), số ngày số dư âm, năm phép | payroll, people | Khi xử lý cuối cùng cho người nghỉ việc | M |
| ATT-P-04 | Thông báo kỳ stale `att.AttendancePeriodReopened` (kỳ `CLOSED` được mở lại → phiên bản hiện hành bị rút; chỉ có thể khi không có hold nào đang hiệu lực) (thay đổi 2026-10-16, X-03) | payroll | Khi mở lại | M |
| ATT-P-05 | **Dữ kiện vắng mặt hưởng BHXH**: nghỉ ốm / chăm con ốm / thai sản / nghỉ khi vợ sinh / khám thai / sẩy thai kèm ngày, số ngày làm việc, dữ liệu giấy chứng nhận và tệp đính kèm; nghỉ do tai nạn lao động | payroll (insurance) | Khi phê duyệt + trong Attendance Result | M |
| ATT-P-06 | Phép năm hết hạn được gắn cờ để thanh toán (nếu chính sách thanh toán khi hết hạn) | payroll | Khi phép chuyển sang hết hạn | S |
| ATT-P-07 | Ngày bắt đầu nghỉ thai sản / ngày đi làm lại dự kiến và thực tế; các khoảng nghỉ không lương dài (≥ 14 ngày làm việc) | people, payroll | Khi phê duyệt / thay đổi | M |
| ATT-P-08 | Ngày công tác đã duyệt (ngày, nửa ngày, nơi đến trong nước/nước ngoài, trung tâm chi phí) | people (chi phí / công tác phí) | Khi phê duyệt và khi thay đổi/hủy | M |
| ATT-P-09 | Số dư phép và các yêu cầu đang chờ tại ngày làm việc cuối cùng (cho danh sách kiểm tra thủ tục nghỉ việc) | people | Khi có sự kiện nghỉ việc, theo yêu cầu | M |
| ATT-P-10 | Thống kê chấm công theo nhân viên (tỷ lệ vắng mặt, số lần đi muộn, số ngày không lương, số giờ làm thêm) | people (đánh giá, đánh giá thử việc) | Theo yêu cầu / cuối kỳ | C |
| ATT-P-11 | Định nghĩa loại yêu cầu workflow & payload nghiệp vụ: LEAVE, OT, ATT_CORRECTION, BUSINESS_TRIP, WFH, SHIFT_SWAP, PERIOD_REOPEN, BALANCE_ADJUST | core | Khi deploy module; theo từng yêu cầu | M |
| ATT-P-12 | Sự kiện nghiệp vụ cho thông báo và audit (trạng thái yêu cầu, ngưỡng giới hạn, trạng thái kỳ, thiết bị offline) | core | Theo sự kiện | M |
| ATT-P-13 | Lịch sẵn sàng / vắng mặt của nhân viên (nghỉ phép, công tác) | recruitment (lên lịch phỏng vấn), core (dashboard tổ chức) | Theo yêu cầu | C |
| ATT-P-14 | Chỉ số sử dụng theo tenant: thiết bị hoạt động, connector, số lượt chấm/tháng, số người dùng check-in trên mobile | saas-platform | Hằng ngày | S |
| ATT-P-15 | Trạng thái hoạt động của connector theo thiết bị (lần đồng bộ cuối, lỗi) | saas-platform (giám sát vận hành) | Liên tục | S |
| ATT-P-16 | API đọc: bảng công ngày, số dư, **lịch ngày lễ** và **mô hình lịch làm việc** (API lịch `att` — T&A là chủ sở hữu duy nhất của lịch ngày lễ theo tenant/pháp nhân/địa điểm; kèm `att.HolidayCalendarChanged`) cho SLA của Core, Payroll (ngày công chuẩn, lương ngày lễ, ngày thanh toán) và Recruitment. API đọc lịch ngày lễ và lịch làm việc là **dịch vụ baseline ở mức API, luôn bật** bất kể tenant mua module nào (R2-18, mvp-scope.md §11.4 R2-C9) | core, payroll, people, recruitment | Theo yêu cầu | M (thay đổi 2026-10-16, R2-02 §5 #2; PM đã phê duyệt) |
| ATT-P-17 | **Close-hold API** (đồng bộ, idempotent theo run id + phiên bản): `acquireHold(period, version, runId)` → được cấp / bị từ chối (kỳ không ở `CLOSED` hoặc phiên bản không phải hiện hành); `releaseHold(period, runId, reason)`; `getHold(period)` (mới 2026-10-16, X-03) | payroll | Khi trình / giải phóng kỳ lương | M |
| ATT-P-18 | Sự kiện chốt kỳ `att.AttendancePeriodHoldGranted`, `att.AttendancePeriodHoldReleased`, `att.AttendancePeriodLocked` (catalogue v1.1, gap-analysis §6) (mới 2026-10-16, X-03) | payroll, core (trạng thái kỳ, thông báo, audit) | Khi cấp / giải phóng hold / khóa | M |

### 7.3 Hợp đồng dữ liệu bàn giao cho payroll (Attendance Result)

**Mức chi tiết:** một kết quả cho mỗi *nhân viên × đoạn phân công × kỳ chấm công*. Một đoạn mới bắt đầu mỗi khi pháp nhân, nhóm lương, trung tâm chi phí, lịch làm việc, nhóm nhân viên hoặc FTE thay đổi, hoặc khi nhận việc/nghỉ việc. Payroll tính lương theo tỷ lệ cho từng đoạn.

**Ranh giới kỳ:** được xác định theo lịch lương của Payroll (ATT-C-18). Ngày công = ngày bắt đầu ca (ca qua nửa đêm thuộc về ngày bắt đầu). Làm thêm giờ có thể dùng ngày chốt làm thêm riêng (ví dụ làm thêm từ ngày 21→20 được trả cùng tháng M); kết quả nêu rõ cả khoảng ngày chấm công và khoảng ngày làm thêm.

**Đơn vị:** ngày dạng số thập phân (2 chữ số thập phân, giá trị điển hình 0.5 / 1); giờ dạng số giờ thập phân (2 chữ số thập phân) sau khi áp dụng quy tắc làm tròn; số lần là số nguyên; phút chỉ dùng cho đi muộn.

| # | Khoản mục / mã loại | Đơn vị | Mô tả |
|---|---|---|---|
| 1 | `STD_DAYS`, `STD_HOURS` | ngày / giờ | Số ngày/giờ công chuẩn của đoạn (phương pháp theo BR-ATT-016), kèm giá trị cả kỳ để tính theo tỷ lệ |
| 2 | `WORKED_DAYS` | ngày | Số ngày thực tế làm việc bao gồm các khoảng nghỉ ngắn có hưởng lương theo luật, công tác, WFH |
| 3 | `PAID_LEAVE_DAYS.<type>` | ngày | Nghỉ do người sử dụng lao động trả lương theo loại: phép năm, nghỉ việc riêng có lương (kết hôn, tang), nghỉ bù (TOIL), nghỉ có lương riêng của công ty |
| 4 | `HOLIDAY_DAYS` | ngày | Ngày lễ + ngày nghỉ bù nằm trong đoạn (do người sử dụng lao động trả lương) |
| 5 | `SI_LEAVE_DAYS.<type>` | ngày (ngày làm việc; ngày lịch đối với thai sản) | Ốm đau, chăm con ốm, thai sản, nghỉ khi vợ sinh, khám thai, sẩy thai — không do người sử dụng lao động trả |
| 6 | `UNPAID_DAYS.<type>` | ngày | Nghỉ việc riêng không lương, nghỉ không lương theo thỏa thuận, vắng không phép (`V`), tạm hoãn |
| 7 | `NOT_EMPLOYED_DAYS` | ngày | Các ngày trong kỳ nằm ngoài thời gian làm việc |
| 8 | `OT_HOURS.<cat>` | giờ | Giờ làm thêm được trả theo loại OT-WD, OT-RD, OT-PH, OT-WD-N, OT-RD-N, OT-PH-N, kèm cờ phụ cho làm thêm ban đêm có làm thêm ban ngày trước đó (VN-11). Không bao gồm giờ làm thêm đã chuyển thành TOIL |
| 9 | `NIGHT_HOURS` | giờ | Làm việc ban đêm trong giờ bình thường (không phải làm thêm) |
| 10 | `SI_NONWORK_DAYS` | ngày | Số ngày làm việc không làm việc và không hưởng lương (không lương + BHXH chi trả, trừ thai sản theo quy định) — dùng cho kiểm tra miễn đóng BHXH ≥14 ngày (VN-18) |
| 11 | `LATE_MIN`, `EARLY_MIN`, `LATE_COUNT`, `EARLY_COUNT`, `MISSING_PUNCH_COUNT` | phút / số lần | Cho khấu trừ theo chính sách hoặc thưởng chuyên cần |
| 12 | `SHIFT_COUNT.<shift_group>` | số lần | Số ca đã làm theo nhóm ca (ví dụ ca đêm, ca Chủ nhật) cho phụ cấp ca |
| 13 | `MEAL_ELIGIBLE_DAYS` | ngày | Số ngày đủ điều kiện hưởng phụ cấp ăn ca/chuyên cần theo quy tắc của tenant |
| 14 | `TRIP_DAYS.<domestic/overseas>`, `WFH_DAYS` | ngày | Cho phụ cấp (bản thân công tác phí thuộc People/chi phí) |
| 15 | `LEAVE_ENCASH_DAYS`, `LEAVE_RECOVER_DAYS` | ngày | Quyết toán khi nghỉ việc (ATT-P-03) |
| 16 | `RETRO.<item>` | theo khoản mục | Dòng chênh lệch: original_period_id, khoản mục, số lượng ±, lý do, tham chiếu nguồn (ATT-P-02) |
| 17 | Header | — | period id, phiên bản, trạng thái, khoảng ngày chấm công, khoảng ngày làm thêm, generated_at, closed_by, checksum |

**Quy tắc hồi tố (thay đổi 2026-10-16, X-03):** (a) kỳ `CLOSED` không bị giữ và không ở trạng thái `LOCKED` được điều chỉnh tại chỗ bằng cách mở lại và chốt lại (phiên bản mới, ATT-P-04); kỳ đang bị giữ phải được Payroll giải phóng trước (BR-ATT-087); kỳ lương quyết toán cuối cùng hoặc kỳ lương điều chỉnh giữ phiên bản kết quả cá nhân / ngoài chu kỳ theo cùng cách; (b) kỳ `Locked` không bao giờ bị thay đổi — chênh lệch được đưa vào kỳ mở kế tiếp; (c) nếu nhân viên đã nghỉ việc và không còn kỳ mở nào cho họ, chênh lệch được công bố dưới dạng Attendance Result ngoài chu kỳ cho kỳ lương ngoài chu kỳ của Payroll; (d) dòng hồi tố phải tham chiếu tới đoạn gốc để Payroll có thể dùng tỷ lệ của kỳ gốc.

---

## 8. Báo cáo & phân tích

| # | Báo cáo | Người dùng | Ưu tiên |
|---|---|---|---|
| R-01 | Lưới bảng công tháng (bảng chấm công) theo đơn vị tổ chức: ngày × ký hiệu, tổng theo loại — in/xuất Excel theo bố cục chuẩn VN, có ô ký | HR, quản lý | M |
| R-02 | Ngoại lệ chấm công hằng ngày (đi muộn, về sớm, thiếu lượt chấm, vắng mặt) | Quản lý, HR | M |
| R-03 | Log lượt chấm thô theo thiết bị/nhân viên kèm nguồn và trạng thái kiểm tra | HR, kiểm toán viên | M |
| R-04 | Tổng hợp làm thêm giờ theo nhân viên: tháng, lũy kế từ đầu năm, % so với giới hạn 40h / 200h / 300h, theo loại | HR, quản lý | M |
| R-05 | Log vượt giới hạn làm thêm và ghi đè (ai phê duyệt vượt giới hạn, lý do) | HR, kiểm toán viên | M |
| R-06 | Báo cáo số dư phép (đầu kỳ, tích lũy, đã nghỉ, chuyển sang, hết hạn, cuối kỳ) tại ngày | HR, nhân viên (của mình) | M |
| R-07 | Nghỉ phép đã nghỉ theo loại/tổ chức/kỳ; sổ theo dõi nghỉ hưởng BHXH cho hồ sơ bảo hiểm | HR, C&B | M |
| R-08 | Tình trạng chốt kỳ theo pháp nhân / nhóm lương, kèm các ngoại lệ còn tồn | HR, payroll | M |
| R-09 | Báo cáo điều chỉnh hồi tố (theo kỳ gốc) | Payroll, tài chính | M |
| R-10 | Phân ca so với thực tế, thiếu hụt nhân sự, lịch sử đổi ca | Trưởng ca | S (P2) |
| R-11 | Tình trạng thiết bị / trạng thái đồng bộ connector | Quản trị viên tenant | S |
| R-12 | Xu hướng vắng mặt và đi muộn, yếu tố chi phí làm thêm (dashboard) | Ban lãnh đạo | C (P2) |

---

## 9. Yêu cầu phi chức năng (riêng cho module)

| ID | Yêu cầu | Ưu tiên |
|---|---|---|
| NFR-ATT-001 | Tiếp nhận lượt chấm: ≥ 200 lượt chấm/giây mỗi vùng lúc cao điểm (giờ đổi ca của nhà máy lớn, ví dụ 10,000 nhân viên trong 15 phút); không mất lượt chấm; gửi at-least-once với loại bỏ trùng lặp idempotent theo (device, enrol_id, timestamp). | M |
| NFR-ATT-002 | Độ trễ từ lượt chấm tới bảng công ≤ 5 phút đối với nguồn online; xử lý lại toàn bộ hằng đêm cho tenant 10,000 nhân viên ≤ 30 phút. | M |
| NFR-ATT-003 | Chốt kỳ (xử lý cuối cùng + snapshot) cho 10,000 nhân viên ≤ 15 phút. | M |
| NFR-ATT-004 | Connector on-premise phải lưu đệm lượt chấm khi offline ≥ 7 ngày và gửi lại khi kết nối lại; xử lý múi giờ và lệch đồng hồ của thiết bị. | M |
| NFR-ATT-005 | Mọi thời điểm được lưu theo UTC kèm múi giờ của tenant/địa điểm; ngày của bảng công là ngày công theo giờ địa phương. | M |
| NFR-ATT-006 | Việc tính toán phải tất định và tái lập được: với cùng đầu vào và phiên bản tham số, xử lý lại cho kết quả giống hệt; mỗi kết quả ghi lại phiên bản quy tắc/tham số đã dùng. | M |
| NFR-ATT-007 | Lưu trữ: lượt chấm thô, bảng công, hồ sơ làm thêm và nghỉ phép ≥ thời hạn theo luật/chính sách của tenant (mặc định 10 năm sau khi chấm dứt quan hệ lao động [verify-legal]); snapshot đã khóa là bất biến. | M |
| NFR-ATT-008 | Dữ liệu cá nhân: tọa độ GPS, ảnh selfie và mẫu sinh trắc học là dữ liệu cá nhân nhạy cảm theo Nghị định 13/2023 (và Luật Bảo vệ dữ liệu cá nhân 2025) — cần sự đồng ý rõ ràng của nhân viên, giới hạn mục đích, mã hóa khi lưu trữ, thời hạn lưu ảnh cấu hình được (mặc định 90 ngày); mẫu sinh trắc học **không** được lưu trong YourHRM. [verify-legal] | M |
| NFR-ATT-009 | Check-in trên mobile phải phát hiện vị trí giả mạo / thiết bị đã root khi hệ điều hành cho phép và gắn cờ các lượt chấm đó. | S |
| NFR-ATT-010 | Cô lập tenant cho mọi dữ liệu `att` và endpoint thiết bị; credential của thiết bị chỉ được ghi lượt chấm vào đúng tenant của nó. | M |
| NFR-ATT-011 | Thời gian phản hồi check-in ESS (web/mobile) ≤ 2 s ở p95. | S |

---

## 10. Giả định, câu hỏi mở, rủi ro

### 10.1 Giả định
- A1. Payroll sở hữu nhóm lương và lịch lương; T&A khớp kỳ theo lịch lương (ATT-C-18).
- A2. T&A không bao giờ tính tiền; mọi tỷ lệ (150/200/300%, +30%, +20%) nằm ở Payroll dưới dạng tham số có hiệu lực theo ngày.
- A3. Yêu cầu công tác do T&A sở hữu (tác động chấm công); công tác phí và đề nghị thanh toán chi phí do People sở hữu.
- A4. Thay đổi phân công trong People được công bố dưới dạng sự kiện có hiệu lực theo ngày, bao gồm thay đổi có hiệu lực tương lai và hồi tố.
- A5. Việc so khớp sinh trắc học diễn ra trên thiết bị; YourHRM chỉ nhận sự kiện lượt chấm.
- A6. Một nhân viên có thể có nhiều phân công đồng thời trong một tenant, bao gồm quan hệ lao động kiêm nhiệm ở nhiều pháp nhân (Phase 1, R2-16); ở Phase 1 giới hạn làm thêm được theo dõi theo từng pháp nhân, giới hạn theo từng người trên nhiều pháp nhân thuộc Phase 2 (R2-08, Q4) (thay đổi 2026-10-16, R2-16).
- A7. Payroll sở hữu nhóm lương, lịch lương và phương pháp ngày công chuẩn; T&A sở hữu lịch ngày lễ, việc gán lịch làm việc và công tác (R2-02 §5 #2, #6, #25, #26).

### 10.2 Câu hỏi mở
| # | Câu hỏi | Người phụ trách |
|---|---|---|
| Q1 | ~~Ai sở hữu pack tham số pháp lý VN cấp nền tảng?~~ **Đã đóng 2026-10-16 (R2-02 §5 #1):** Legal Parameter Service của saas-platform; SLA cập nhật theo NFR-SAAS-016. | PM, SaaS BA, Core BA |
| Q2 | ~~Phương pháp ngày công chuẩn: những phương pháp nào?~~ **Đã đóng 2026-10-16 (R2-02 §5 #25):** Payroll sở hữu phương pháp trên nhóm lương (BR-PAY-009: cố định, số ngày theo lịch, ngày lịch, số giờ cố định); T&A tính STD_DAYS/STD_HOURS với phương pháp lấy từ lịch lương. | ATT BA, PAY BA |
| Q3 | Làm thêm giờ được trả cùng kỳ với chấm công hay theo ngày chốt riêng (phổ biến ở nhà máy)? Payroll có hỗ trợ hai khoảng ngày trong một kỳ lương không? | PAY BA |
| Q4 | Giới hạn làm thêm năm trên nhiều pháp nhân của một tenant (tập đoàn) — theo từng pháp nhân (góc nhìn pháp lý) hay theo từng người (góc nhìn an toàn)? **Hoãn 2026-10-16 (R2-08, R2-16):** Phase 1 đo theo từng pháp nhân; việc đo trên nhiều pháp nhân thuộc Phase 2, chờ chuyên gia tư vấn lao động. | Chuyên gia tư vấn pháp lý |
| Q5 | Ai ghi nhận dữ liệu mang thai / ngày sinh của con — People (hồ sơ HR) hay T&A (nhân viên khai báo)? Hệ quả về sự đồng ý và quyền riêng tư. | PPL BA |
| Q6 | ~~Số năm đóng BHXH cho mức hưởng nghỉ ốm?~~ **Đã đóng 2026-10-16 (R2-02 §5 #20):** Payroll sở hữu toàn bộ lịch sử BHXH; các tháng trước go-live lấy từ import số dư đầu kỳ (mẫu lịch sử đóng BHXH); T&A đọc API số năm đóng (ATT-C-20, IF-PAY-P-08). | PAY BA |
| Q7 | Cơ sở thanh toán phép: Payroll cần cơ sở tiền lương (BLLĐ Điều 113 / Nghị định 145 Điều 67: tiền lương bình quân của 6 tháng liền kề trước đó) — T&A chỉ cung cấp số ngày; cần xác nhận. | PAY BA |
| Q8 | Các ngày lễ mới được bổ sung bởi văn bản pháp luật gần đây (ví dụ bất kỳ nghị quyết nào của Quốc hội năm 2025–2026) — xác nhận danh sách cho pack 2027. | Chuyên gia tư vấn pháp lý |
| Q9 | T&A có nên hỗ trợ số giờ được trả của nhân viên hưởng lương theo giờ / bán thời gian như một khoản mục riêng (`PAYABLE_HOURS`) không? | PAY BA |
| Q10 | Recruitment: có cần đăng ký trước khi nhận việc (ATT-C-23) hay chỉ qua People khi hồ sơ nhân viên đã được tạo? | REC BA, PPL BA |
| Q11 | Thời hạn hiệu lực của nghỉ bù (TOIL) và thanh toán khi hết hạn — trả theo tỷ lệ làm thêm giờ khi hết hạn? | Chính sách HR / pháp lý |

### 10.3 Rủi ro
| # | Rủi ro | Tác động | Giảm thiểu |
|---|---|---|---|
| R1 | Sự đa dạng thiết bị (firmware ZKTeco/Hikvision, phiên bản SDK, giao thức push) làm chậm MVP | Cao | MVP = import tệp + một connector được chứng nhận; danh sách chứng nhận theo model |
| R2 | Thay đổi phân công/nghỉ việc hồi tố muộn từ People sau khi khóa gây sai sót tính lương | Cao | Cơ chế hồi tố bắt buộc (BR-ATT-071), thông báo đầu vào stale, báo cáo đối chiếu R-09 |
| R3 | Thay đổi pháp luật (giới hạn, ngày lễ, chuyển tiếp Luật BHXH 2024) trong quá trình xây dựng | Trung bình | Tham số có hiệu lực theo ngày; quy trình cập nhật legal pack (Q1) |
| R4 | Định nghĩa loại không khớp giữa T&A và Payroll (ví dụ làm thêm ngày lễ so với làm thêm ngày nghỉ có hưởng lương, cơ sở tính làm thêm ban đêm) | Cao | Danh mục mã loại dùng chung ở §7.3, đồng sở hữu; contract test giữa các module |
| R5 | Không tuân thủ quy định về quyền riêng tư đối với dữ liệu GPS/selfie | Cao | NFR-ATT-008, DPIA trước khi ra mắt mobile ở P2 |
| R6 | Tải lượt chấm cao điểm vào giờ đổi ca của nhà máy | Trung bình | Tiếp nhận dựa trên hàng đợi, kiểm thử hiệu năng NFR-ATT-001 |
| R7 | Import số dư đầu kỳ cho số dư phép và giờ làm thêm lũy kế từ đầu năm không đầy đủ hoặc sai → sai giới hạn/số dư trong năm đầu (thay đổi 2026-10-16, D-09) | Trung bình | BR-ATT-086 kèm ký xác nhận đối chiếu bởi HR của tenant thí điểm |
| R8 | Kỳ đang bị giữ chặn một điều chỉnh T&A khẩn cấp cho đến khi Payroll giải phóng hold (thay đổi 2026-10-16, X-03) | Thấp | Công việc yêu cầu giải phóng (BR-ATT-087); điều chỉnh sau khi khóa được đưa vào chênh lệch hồi tố (BR-ATT-071) |
