# YourHRM

build a new HRM system on PostgreSQL database, Java Spring Boot Framework business logic, reporting system,  moderm and professional frontend covering Vietnam-specific payroll, overtime, insurance and PIT rules.

Fit database and application architecture with multi-tenant allow build SaaS Application.

## Repository layout

| Folder | Purpose | Owner |
|---|---|---|
| `docs/` | Project management, requirements, architecture, UX, testing, deployment, user guides | PM / BA / Architect |
| `database/postgresql/` | Target data model: DDL, seed data, schema tests, `DESIGN.md` | DB / Architect |
| `backend/` | API and business logic, one module per domain | Backend team |
| `frontend/` | Web UI, one module per domain | Frontend team |
| `tests/` | Cross-system tests: end-to-end, performance | QA |
| `infra/` | Docker, CI/CD pipelines, per-environment configuration | DevOps |
| `scripts/` | Developer and ops helper scripts | All |

## Functional modules

Modules are aligned with the PostgreSQL schemas, so code, docs and tests use the same names.
Sample schemas bellow:
| Module | DB schema | Scope |
|---|---|---|
| `core` | `core` | Company, org tree, positions, users / roles / permissions, workflow & approvals, notifications, settings, audit |
| `people` | `hr` | Person & employee records, assignments, contracts, onboarding / separation, appraisal, training, expenses |
| `time-attendance` | `att` | Holidays, shifts, rosters, check-ins, attendance, overtime, leave |
| `payroll` | `pay` | Salary components & structures, insurance, PIT, payroll runs, payslips, bank payments |
| `recruitment` | `rec` | Staffing plans, requisitions, job openings, applicants, interviews, offers |

## Getting started


> Never commit `.env` files. Credentials for the legacy Oracle database stay local only.
