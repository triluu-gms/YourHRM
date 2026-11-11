# YourHRM frontend

Admin web + ESS web in one React codebase (ADR-0013): React 19, TypeScript 5, Vite, Ant Design 5, TanStack Query +
TanStack Router, react-i18next (ICU), Vitest + Testing Library + MSW. The SPA runs behind the BFF (ADR-0014):
same-origin calls, HttpOnly cookie, never a token in JavaScript.

## Run

```powershell
cd frontend
npm install
copy .env.example .env          # adjust YHRM_API_URL / Keycloak if needed
npm run dev                     # http://localhost:5173
```

The dev server emulates `hrm-bff` (`dev/bff-plugin.ts`): the login form posts to `/bff/login`, which exchanges the
credentials with Keycloak (realm `yourhrm`, client `yourhrm-web`, direct access grant) and keeps the token in the dev
server's memory; `/api/**` is proxied to the backend (`YHRM_API_URL`, default `http://localhost:8080`) with the
`Authorization` header added. Without a Keycloak realm you can set `YHRM_DEV_TOKEN` to a JWT the backend accepts.
The backend needs a tenant and a `core.user_account` whose `identity_id` equals the token's `sub`, and a role
assignment that grants the Core permissions (see `backend/` and `infra/docker/README.md`).

Other scripts: `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`.

## Structure

```
src/app/          providers (Query, AntD theme/locale, i18n), router composition, App
src/shared/       api client (RFC 9457 problems, Page<T>), auth (/me, guards, PermissionGate), routing base,
                  layouts (AdminLayout, LoginPage), components (DataTable, FormDrawer, HistoryDrawer, AsOfPicker,
                  MaskedField, MoneyInput, StatusTag, PageHeader…), i18n (common ns), navigation (menu registry)
src/modules/<m>/  one folder per backend module; `index.ts` exports the module's shell registry + routes
src/modules/core/ permissions.ts, menu.ts, i18n/, areas/<area>/{api.ts,queries.ts,pages,routes.tsx,i18n}
src/test/         Vitest setup, MSW server + fixtures, renderPage()
dev/              dev BFF Vite plugin
```

Rules (enforced by `eslint-plugin-boundaries`): a module imports only `shared` and itself; `shared` never imports a
module; only `app` wires modules together. A module plugs into the shell through its registry (menu entries,
header widgets) and by exporting routes created with `getParentRoute: () => adminRoute` (`@/shared/routing`).

## Conventions

- API types mirror the backend records 1:1 (`backend/src/modules/core/.../web/*Controller.java` and the
  `*Service` records). Lists are `Page<T>` (`items,page,size,total`, 0-based page). Errors are `ApiProblem`
  with `code` (`stale-version`, `validation-failed`, …) and are shown with `<ProblemAlert>` / `useFeedback()`.
- Every PUT carries `version` (optimistic locking). Effective-dated records (ADR-0024): list "as of" a date
  (`<AsOfPicker>`), detail shows the current version, `<HistoryDrawer>` the versions, "New version" creates one.
- Permissions: `requirePermission(...)` in a route's `beforeLoad`, `<PermissionGate anyOf=…>` around actions.
  Codes live in `modules/core/permissions.ts`. The backend remains authoritative.
- Dates `dd/MM/yyyy`, instants in Asia/Ho_Chi_Minh, VND without decimals (`shared/format.ts`).
- i18n: vi default, en; `common` namespace for shared words, one namespace per area (`core-users`, …) registered
  with `registerNamespace`. Never hard-code UI text.
- Forms use Ant Design `Form` (validation rules + server field errors); Zod is used for route search params.
  This is a pragmatic deviation from ADR-0013's React Hook Form; revisit if forms outgrow AntD Form.
- Tests: `renderPage(<Page/>)` from `src/test/render.tsx` with MSW handlers (`src/test/server.ts`); the `/me`
  fixture is a tenant owner with every Core permission (`meWith(...)` for a reduced set).

## Not yet

- Generated client from OpenAPI (orval) — the backend has no springdoc yet; types are hand-written.
- ESS shell, Storybook, Playwright, axe checks.
