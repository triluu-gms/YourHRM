# ADR-0005: Keycloak làm identity provider; SaaS xác thực, Core phân quyền

- **Trạng thái:** Đã chấp nhận (2026-10-16, baseline 1 của bản định hướng "SaaS xác thực, Core phân quyền"; OD-8 OTP qua e-mail cho cựu nhân viên; **R2-04: SSO chuyển sang Phase 2** — Phase 1 = username/password Keycloak + MFA bằng TOTP hoặc OTP qua e-mail)
- **Ngày:** 2026-10-16
- **Liên quan:** Bản định hướng kiến trúc B1; gap-analysis GAP-002, GAP-003, GAP-016, GAP-035, GAP-036, §9 A-5; NFR-CORE-006/007; [architecture-overview](../architecture-overview.md) §6

## Bối cảnh

Xác thực (credential, MFA, SSO, session, API client) do nền tảng SaaS sở hữu; phân quyền (tài khoản người dùng, vai trò, phạm vi dữ liệu, masking) do Core sở hữu. **Phase 1 (R2-04, 2026-10-16): username + password Keycloak với MFA bằng TOTP (ứng dụng authenticator) hoặc mã dùng một lần qua e-mail; không có SSO ở pilot.** OIDC SSO (Google, Microsoft Entra ID) và SAML chuyển sang Phase 2; SCIM vẫn ở P3. Một thể nhân có thể thuộc nhiều tenant (GAP-016). Cựu nhân viên giữ quyền truy cập giới hạn mà không cần SSO của tenant (GAP-036). Ứng viên cần truy cập không mật khẩu (P2). Toàn bộ dữ liệu định danh phải lưu tại Việt Nam; SLO khả dụng của đăng nhập là 99,9% và việc kiểm tra token không được gọi IdP cho mỗi request.

## Quyết định

1. **Keycloak 26.x**, tự host trong các cluster tại VN với cơ sở dữ liệu PostgreSQL riêng được nhân bản.
2. **Một realm `yourhrm`** sử dụng **Keycloak Organizations**: mỗi tenant một Organization; một Keycloak user là định danh toàn cục; tư cách thành viên Organization liên kết user với tenant; identity-provider brokering theo từng Organization (OIDC và SAML, **Phase 2**, R2-04). Gói Enterprise dedicated có thể có realm riêng.
   - **Xác thực ở Phase 1 (R2-04):** username (e-mail công việc hoặc cá nhân) + password theo password policy do tenant cấu hình (Keycloak), bảo vệ chống brute-force, và **MFA bằng TOTP hoặc OTP qua e-mail** (authenticator OTP qua e-mail của Keycloak, có sẵn hoặc một extension đã được thẩm định **[SP-8]**); MFA bắt buộc đối với các role HR, C&B, tài chính, quản trị tenant và operator, và do tenant cấu hình đối với người dùng ESS. WebAuthn/passkey thuộc Phase 2 cùng SSO. Đặt lại mật khẩu và OTP qua e-mail dùng e-mail relay lưu trữ tại VN; OTP qua SMS thuộc P3.
3. **Token:** JWT access token sống ngắn (5 phút) với `sub`, `tid`, `org`, `acr`, `amr`, `sid`; **không có role/quyền trong token**. Resource server kiểm tra cục bộ bằng JWKS được cache.
4. **Step-up** qua các mức ACR (`1` password/SSO, `2` MFA, `3` MFA trong vòng 5 phút) được yêu cầu bằng `acr_values`; các thao tác nhạy cảm đòi hỏi `acr ≥ 2` hoặc `3`.
5. **Trình duyệt** dùng BFF (ADR-0014); app mobile (Phase 2, OD-9) dùng AppAuth + PKCE với xoay vòng refresh token; API client (public API, Phase 2, ADR-0014) dùng client credentials, do SaaS tạo cho từng tenant và được Core ánh xạ sang quyền.
6. **Core** sở hữu `user_account` (theo phạm vi tenant) với `identity_id`; Core phát `core.UserStatusChanged`; SaaS phản ứng bằng cách bật/tắt tư cách thành viên Organization, thu hồi session, và (P3) SCIM.
7. **Cựu nhân viên:** trạng thái `FORMER_EMPLOYEE` gỡ liên kết SSO (Phase 2, khi đã có SSO) và bật đăng nhập bằng OTP qua e-mail trong khoảng thời gian do tenant đặt (mặc định 24 tháng); được chấp nhận theo OD-8, do đó e-mail cá nhân được thu thập khi nghỉ việc (GAP-036). OTP qua SMS thuộc P3.
8. **Ứng viên (P2):** magic link không mật khẩu qua token ký số dùng một lần hoặc một realm riêng cho chủ thể bên ngoài **[spike]**.

## Các phương án đã cân nhắc

- **IdP dạng hosted (Auth0, Entra External ID, Cognito):** dữ liệu định danh nằm ngoài Việt Nam; bị loại vì yêu cầu lưu trữ dữ liệu trong nước.
- **Mỗi tenant một realm:** tách biệt mạnh, nhưng 500+ realm làm giảm hiệu năng quản trị Keycloak và gây phức tạp cho người dùng thuộc nhiều tenant; chỉ giữ cho Enterprise.
- **Zitadel / Ory:** mạnh và hỗ trợ multi-tenant native, nhưng nguồn kỹ năng trong nước ít hơn và ít tham chiếu về SAML brokering hơn; cân nhắc lại nếu Organizations tỏ ra không đủ trong SP-8.
- **Spring Authorization Server (IdP tự xây):** quá nhiều code quan trọng về bảo mật phải tự sở hữu.
- **Role trong token:** bị cũ cho đến khi hết hạn và phình to với người dùng có nhiều phạm vi; bị loại.

## Hệ quả

- + Đăng nhập, MFA và (Phase 2) SSO theo chuẩn mà không phải tự xây IdP; lưu trữ tại VN.
- − Phase 1 không có SSO: người dùng của pilot phải giữ thêm một mật khẩu; giảm thiểu bằng tự đặt lại mật khẩu qua e-mail và TOTP/OTP qua e-mail. Bổ sung SSO ở Phase 2 là việc cấu hình theo từng Organization (identity brokering), không phải thay đổi mô hình.
- + Ranh giới rõ ràng: SaaS không bao giờ quyết định "được làm gì", Core không bao giờ xử lý credential.
- − Keycloak trở thành tier-0: HA (≥ 3 replica), DB của nó nằm trong phạm vi DR, kỷ luật nâng cấp.
- − Độ trưởng thành của tính năng Organizations phải được kiểm chứng (SP-8).
- − Mọi request cần tra cứu phân quyền của Core (có cache; ADR-0006).
