# ADR-0013: Frontend — React, TypeScript, Ant Design; no micro-frontends; responsive ESS web in Phase 1, React Native in Phase 2

- **Status:** Accepted (2026-10-16, OD-2; Phase-1 scope per OD-9)
- **Date:** 2026-10-16
- **Related:** decision-log OD-2, OD-9, X-04; gap-analysis GAP-026 (ESS shell); NFR-SAAS-011/012/013/022, NFR-CORE-011, NFR-REC-002; [architecture-overview](../architecture-overview.md) §11

## Context

The product needs a data-dense admin UI (HR, C&B, managers, tenant admins), a responsive ESS/MSS (≥ 360 px) where each module owns its pages inside a SaaS-owned shell, full vi/en localisation, WCAG 2.1 AA, and in Phase 2 a native mobile app (GPS/Wi-Fi/selfie check-in, push) and an SEO-friendly career site. One frontend team works on one release train.

Sponsor decisions (2026-10-16): React + TypeScript + Ant Design (OD-2). **No mobile features in Phase 1** (OD-9, X-04): no PWA, no web/GPS/selfie check-in; Phase-1 attendance comes from time devices and Excel import.

## Decision

1. **React 19 + TypeScript 5**, Vite, TanStack Query + TanStack Router, React Hook Form + Zod, react-i18next (ICU).
2. **Ant Design 5** themed with YourHRM tokens, wrapped by our own components in `frontend/src/shared/components` (DataTable, EffectiveDatedForm, OrgTreePicker, MoneyInput VND, MaskedField, ApprovalTimeline…); Storybook + visual regression + axe checks.
3. **No micro-frontends.** One codebase with two shells/entry points (Admin web, ESS/MSS web); domain folders mirror backend modules and are lazy-loaded; module boundaries enforced by lint rules; ESS pages registered per module in a shell registry.
4. **API client generated** from OpenAPI 3.1 (orval).
5. **Phase 1 ESS/MSS = responsive web only** (≥ 360 px, works in mobile browsers): no service worker/installable PWA, no offline cache of personal data, **no check-in from the browser**.
6. **Phase 2 — all mobile features:** React Native (Expo) app reusing the API client, i18n bundles and tokens, with GPS/Wi-Fi/selfie check-in, push and biometric unlock. Whether a PWA is also offered is decided in Phase 2.
7. **Career site (Phase 2):** SSR app (React Router 7 framework mode).

## Alternatives considered

- **Angular 20 + PrimeNG/Material:** strong for enterprise forms and opinionated structure; viable. Rejected by OD-2 (team/hiring fit and React Native reuse).
- **Vue 3:** good DX; smaller enterprise component ecosystem for dense tables in vi.
- **Micro-frontends (Module Federation):** independent deployability not needed with one team; adds runtime complexity and inconsistent UX.
- **Flutter for mobile:** excellent UI, but a second language and no code sharing with web.
- **MUI / shadcn:** MUI's data grid advanced features are commercial; shadcn needs more in-house components for enterprise tables.
- **PWA web check-in in Phase 1:** rejected by OD-9 (all mobile features move to Phase 2).

## Consequences

- + Large VN talent pool; rich components with vi_VN locale; code sharing web ↔ mobile in Phase 2.
- + Smaller Phase-1 scope: no offline/PWA security work, no GPS/selfie privacy (DPIA) work before Phase 2.
- − Ant Design's look must be themed to avoid a generic appearance; bundle size managed by tree-shaking and route splitting.
- − Pilot employees cannot clock in from a phone in Phase 1; sites without time devices must use Excel attendance import.
