# Sample data (local development only)

Creates a realistic data set through the backend REST API, so every frontend screen built so far has data to show.
Records go through the real services, approval workflows, audit and events. The data set has 3 legal entities,
15 org units, 18 employees (plus one concurrent employment), contracts in every state, decisions, family members and
PIT dependants, bank accounts, separations, statutory reports and open approval tasks.

## Run

```powershell
./scripts/dev-reset.ps1 -Force            # DELETES local Docker volumes; reloads DDL, realm, dev tenant, reference data
# (re)start the backend with the dev profile:
cd backend; $env:SPRING_PROFILES_ACTIVE='dev'; ./gradlew.bat :app:bootRun
node scripts/sample-data/seed.mjs         # ~3 min; prints ✓ / ✗ per step
```

`node scripts/sample-data/seed.mjs core` seeds Core only. The Core part can be re-run safely, because it looks up
records by code. The People part creates new records, so for a second clean set run `dev-reset.ps1` again.

## Logins (Keycloak realm `yourhrm`; frontend http://localhost:5173)

| Login | Password | Who | Use it to test |
|---|---|---|---|
| `admin` | `admin_dev` | Tenant owner (all permissions) | All admin screens; submitted most requests |
| `hr.admin` | `Hr@12345` | HR Admin (system role), employee Phạm Thị Thu | Approval inbox: hire, salary, temporary transfer, primary employment, dependants |
| `hr.viewer` | `Hr@12345` | Custom role HR_VIEWER (view only, PII masked, HQ only) | Permission gates, masking, data scope |
| `manager.it` | `Mgr@12345` | Line manager of the Technology division (Trần Văn Hùng) | Line-manager inbox (bank account step 1), team views |
| `manager.sales` | `Mgr@12345` | Line manager of Sales (Lê Thị Mai) | Resignation approval (step 1) |
| `employee` | `employee_dev` | ESS: Nguyễn Văn An | Self-service profile, family, dependants, bank account |
| `employee2` | `Emp@12345` | ESS: Hoàng Thị Bình | Self-service resignation (pending) |

## What each screen should show

* **Employer / structure**
  * 3 legal entities: HQ, MFG and SGN. Their address versions start on 2025-07-01, so the min-wage regions are I/II.
  * 6 locations, one of them a remote warehouse in region III.
  * Org tree with divisions and teams; cost centres; 6 employee categories.
  * Job families, grades G1–G6 and 11 job titles, including a hazardous one (Welder, category IV).
* **Access**
  * System roles HR_ADMIN, ESS, LINE_MANAGER …, plus the custom roles HR_VIEWER, PAYROLL_PREP and ESS_HR.
  * 7 users with role assignments.
* **Workflow**
  * Definition BANK_2STEP: line manager, then HR.
  * Open tasks for hr.admin, manager.it and manager.sales.
* **Employees**: 18 hires dated 2025-07 to 2026-11; Quang Minh's hire is still PENDING.
* **Contracts**
  * Probation ending within 30 days: Đỗ Minh Quân.
  * Definite contracts expiring within 60 days: Bình, Kiên and John Smith.
  * Long's contract was renewed; Lan's contract is suspended.
  * Nga passed probation and moved to a definite contract.
  * An has a signed appendix.
  * Kiên has a contract-expiry separation in DRAFT.
* **Changes**
  * An was promoted on 2026-07-01, with his salary set by the signed appendix.
  * Đức was transferred to QA; Tùng's cost centre changed and he got a salary increase.
  * Long's temporary transfer is pending; Hùng's salary review is pending.
  * Hải's decision was signed, then reissued.
* **Compliance**
  * John Smith is a foreign worker:
    * passport (with scan) and a work visa expiring within 45 days;
    * work permit expiring together with his contract;
    * tax residency NON_RESIDENT;
    * a residency proposal from Payroll, inserted with SQL because it arrives by integration and no API creates it.
  * Thu's passport expires within 40 days.
  * Hạnh is pregnant (a protected employee); Tùng is a union member.
* **Family / PIT**: approved dependants for Hạnh (child) and An (father). Pending: Tùng's second child, and An's child submitted through ESS.
* **Bank accounts**: every employee has one, approved through the two steps. An's is pending with manager.it.
* **Separations**
  * Đức: MUTUAL_AGREEMENT, SCHEDULED, with recognised prior service.
  * Bình: ESS resignation, pending.
  * Kiên: contract-expiry draft.
* **Concurrent employment**: Cao Thị Ngọc also works for MFG at 50 %; her change of PIT primary employment is pending.
* **Statutory reports**: last month's labour fluctuation report for HQ is submitted with a receipt; the June 2026 labour-change report is generated.

## Dev-only backend stand-ins (profile `dev`, `backend/src/app/src/main/java/vn/yourhrm/dev`)

The SaaS platform module does not exist yet. Without these stand-ins People refuses hires, PII and uploads:

| Class | Replaces | Notes |
|---|---|---|
| `DevLegalParameters` | Legal Parameter Service | Values in `dev/legal-parameters.json`: the integration tests' example values, all `[verify-legal]` |
| `DevKeys` | Keyring / key service | Keys are derived from a dev secret, so they survive restarts. Offers NO protection |
| `DevObjectStorage`, `DevStorageController` | Object storage + malware scan | Files go to `~/.yourhrm/dev-storage`; every upload is reported CLEAN; signed links are served under `/api/v1/dev/storage` |
| `DevReleaseJob` | Release job (catalogue upsert) | Runs as `yhrm_platform` at start-up |
| `DevTenantProvisioner` | `TenantProvisioned` consumer | Creates system roles and default types/categories for the dev tenant |

## Known limits of the local stack

* **The scheduled People jobs never run.** Nothing calls `runDaily`: there is no `@Scheduled` or HTTP trigger yet. As a result:
  * future-dated hires stay PRE_HIRE;
  * contracts never become EXPIRED;
  * SCHEDULED separations never become COMPLETED.

  The sample data therefore uses dates up to today.
* **ESS bank-account changes fail locally with `403 step-up-required`.** The dev BFF logs in with Keycloak's password
  grant, whose tokens carry no `auth_time`. Test bank changes from the HR side.
* **E-mail notifications are not sent.** No `EmailDeliveryAdapter` exists yet; in-app notifications work.
* **Document generation is unavailable.** The `/document` and `/pdf` actions need Core document templates, which are not seeded.
