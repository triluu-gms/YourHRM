# ADR-0013: Frontend — React, TypeScript, Ant Design; không dùng micro-frontend; ESS web responsive ở Phase 1, React Native ở Phase 2

- **Trạng thái:** Đã chấp thuận (2026-10-16, OD-2; phạm vi Phase 1 theo OD-9)
- **Ngày:** 2026-10-16
- **Liên quan:** decision-log OD-2, OD-9, X-04; gap-analysis GAP-026 (ESS shell); NFR-SAAS-011/012/013/022, NFR-CORE-011, NFR-REC-002; [architecture-overview](../architecture-overview.md) §11

## Bối cảnh

Sản phẩm cần một giao diện quản trị dày đặc dữ liệu (HR, C&B, quản lý, quản trị viên tenant), một ESS/MSS responsive (≥ 360 px) trong đó mỗi module sở hữu các trang của mình bên trong một shell do SaaS sở hữu, bản địa hoá đầy đủ vi/en, đạt WCAG 2.1 AA, và ở Phase 2 là một ứng dụng di động native (chấm công qua GPS/Wi-Fi/selfie, push) cùng một trang tuyển dụng thân thiện SEO. Một đội frontend làm việc trên một release train.

Quyết định của nhà tài trợ (2026-10-16): React + TypeScript + Ant Design (OD-2). **Không có tính năng di động ở Phase 1** (OD-9, X-04): không PWA, không chấm công qua web/GPS/selfie; dữ liệu chấm công Phase 1 lấy từ máy chấm công và import Excel.

## Quyết định

1. **React 19 + TypeScript 5**, Vite, TanStack Query + TanStack Router, React Hook Form + Zod, react-i18next (ICU).
2. **Ant Design 5** được tuỳ biến theme bằng token của YourHRM, bọc bởi các component riêng trong `frontend/src/shared/components` (DataTable, EffectiveDatedForm, OrgTreePicker, MoneyInput VND, MaskedField, ApprovalTimeline…); Storybook + kiểm thử hồi quy giao diện + kiểm tra axe.
3. **Không dùng micro-frontend.** Một codebase với hai shell/entry point (Admin web, ESS/MSS web); các thư mục domain phản chiếu module backend và được lazy-load; ranh giới module được ép buộc bằng lint rule; các trang ESS được đăng ký theo module trong một shell registry.
4. **API client được sinh tự động** từ OpenAPI 3.1 (orval).
5. **ESS/MSS Phase 1 = chỉ web responsive** (≥ 360 px, chạy trên trình duyệt di động): không service worker/PWA cài đặt được, không cache offline dữ liệu cá nhân, **không chấm công từ trình duyệt**.
6. **Phase 2 — toàn bộ tính năng di động:** ứng dụng React Native (Expo) tái sử dụng API client, gói i18n và token, có chấm công GPS/Wi-Fi/selfie, push và mở khoá sinh trắc học. Việc có cung cấp thêm PWA hay không sẽ được quyết định ở Phase 2.
7. **Trang tuyển dụng (Phase 2):** ứng dụng SSR (React Router 7 framework mode).

## Các phương án đã cân nhắc

- **Angular 20 + PrimeNG/Material:** mạnh cho form doanh nghiệp và có cấu trúc rõ ràng; khả thi. Bị loại theo OD-2 (phù hợp đội ngũ/tuyển dụng và tái sử dụng với React Native).
- **Vue 3:** DX tốt; hệ sinh thái component doanh nghiệp cho bảng dữ liệu dày đặc bằng tiếng Việt nhỏ hơn.
- **Micro-frontend (Module Federation):** không cần khả năng deploy độc lập khi chỉ có một đội; tăng độ phức tạp runtime và UX thiếu nhất quán.
- **Flutter cho di động:** UI xuất sắc, nhưng thêm một ngôn ngữ thứ hai và không chia sẻ code với web.
- **MUI / shadcn:** các tính năng nâng cao của data grid MUI là thương mại; shadcn cần tự xây nhiều component hơn cho bảng doanh nghiệp.
- **Chấm công web qua PWA ở Phase 1:** bị loại theo OD-9 (mọi tính năng di động chuyển sang Phase 2).

## Hệ quả

- + Nguồn nhân lực tại VN dồi dào; component phong phú có locale vi_VN; chia sẻ code web ↔ mobile ở Phase 2.
- + Phạm vi Phase 1 nhỏ hơn: không có công việc bảo mật offline/PWA, không có công việc quyền riêng tư GPS/selfie (DPIA) trước Phase 2.
- − Giao diện Ant Design phải được tuỳ biến theme để tránh vẻ ngoài chung chung; kích thước bundle được kiểm soát bằng tree-shaking và tách route.
- − Nhân viên pilot không thể chấm công từ điện thoại ở Phase 1; các địa điểm không có máy chấm công phải dùng import chấm công bằng Excel.
