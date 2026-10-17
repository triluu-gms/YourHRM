# ADR-0010: Legal Parameter Service — API và caching

- **Trạng thái:** Đề xuất
- **Ngày:** 2026-10-16
- **Liên quan:** Bản định hướng kiến trúc B6; gap-analysis GAP-001, GAP-023, GAP-040, §9 A-6; NFR-SAAS-016/017, NFR-PAY-007; bảng và quản lý phiên bản trong kiến trúc dữ liệu (DESIGN.md); [architecture-overview](../architecture-overview.md) §9.1

## Bối cảnh

Nền tảng SaaS sở hữu một Legal Parameter Service (LPS) duy nhất cho mọi giá trị theo luật định: lương tối thiểu vùng và bản đồ đơn vị hành chính→vùng, tỷ lệ và mức trần BHXH/BHYT/BHTN/BHTNLĐ-BNN, mức tham chiếu, biểu thuế và giảm trừ thuế TNCN, giới hạn làm thêm giờ, khung giờ làm đêm, số ngày phép được hưởng, giới hạn thử việc/báo trước, lộ trình tuổi nghỉ hưu, thời hạn quyết toán, mẫu ngày lễ. Payroll, T&A, People, Core và Recruitment đọc đồng bộ theo ngày hiệu lực, chỉ có override theo tenant/pháp nhân ở những nơi luật cho phép. Mọi kết quả ghi nhận phiên bản của bộ tham số. Các bộ tham số phải công bố được mà không cần phát hành code hay thay đổi schema.

## Quyết định

1. **Key có kiểu:** `SCALAR`, `TABLE` (ví dụ biểu thuế lũy tiến), `MAP` (ví dụ đơn vị hành chính → vùng), `CALENDAR_TEMPLATE`; mỗi key khai báo kiểu, đơn vị, quyền override và chủ sở hữu.
2. **Bộ tham số là bất biến sau khi công bố.** Vòng đời `DRAFT → IN_TEST → APPROVED → PUBLISHED → SUPERSEDED`; việc công bố yêu cầu golden payroll pack chạy đạt và chuyên gia tư vấn phê duyệt; việc chỉnh sửa tạo ra phiên bản mới.
3. **API in-process** (`saas-platform.api.LegalParameters`): `get(key, scope, asOf)`, `resolveSet(keys, scope, periodStart, periodEnd)` (các phân đoạn cho thay đổi giữa kỳ), `currentPack(asOf)`, `changesSince(version)`; bản REST tương ứng cho các console. Mọi câu trả lời đều mang `packVersion` và `overrideVersion`.
4. **Xác định phạm vi:** GLOBAL → TENANT → LEGAL_ENTITY → REGION/LOCATION, chỉ cho các key được gắn cờ cho phép override; override có phiên bản và được audit.
5. **Caching:** bộ tham số được cache theo phiên bản vô thời hạn (Caffeine); chỉ mục "ngày → phiên bản" và override của tenant bị vô hiệu hóa khi có `saas.LegalParameterPackPublished` / event override qua Valkey pub/sub tới mọi pod.
6. **Kích hoạt truy lĩnh/truy thu:** công bố với ngày hiệu lực trong quá khứ sẽ liệt kê các kỳ bị ảnh hưởng; Payroll/T&A tạo các hạng mục công việc retro cho tenant có kỳ đã phê duyệt trong khoảng đó.
7. Bên tiêu thụ không bao giờ sao chép tham số vào bảng của mình, ngoại trừ snapshot của payroll/T&A ghi lại giá trị đã xác định kèm phiên bản.

## Các phương án đã cân nhắc

- **Tham số lưu trong từng module:** vấn đề "năm nơi lưu" của GAP-001; bị loại.
- **LPS là microservice riêng qua HTTP:** độ trễ không cần thiết bên trong monolith; API được thiết kế để có thể tách ra.
- **Config server / công cụ feature flag cho giá trị pháp lý:** thiếu effective dating, bảng và audit.

## Hệ quả

- + Một nguồn sự thật duy nhất, có hiệu lực theo thời gian, audit được; đọc dưới một mili giây.
- + Tái lập kết quả tính lương nhờ các phiên bản được ghi nhận và bản sao trong snapshot.
- − Công cụ cho người sở hữu nội dung (trình chỉnh sửa, so sánh khác biệt, chạy thử, phê duyệt) phải được xây trong operator console.
- − Quản trị override đòi hỏi ý kiến pháp lý cho từng key (`[verify-legal]`).
