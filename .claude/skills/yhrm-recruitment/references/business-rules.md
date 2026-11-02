# Recruitment: BR/NFR index

Source: `docs/01-requirements/brd/modules/05-recruitment.md` v0.2 (§4, §9). The Phase column comes from `docs/01-requirements/brd/mvp-scope.md` §9.5, which is the only authority for phase.

**Count: 63 BRs (BR-REC-001..063) + 11 NFRs (NFR-REC-001..011) = 74 requirements.**
- BR MoSCoW: 42 M (BR-REC-033 and 045 are written "M/S"; mvp-scope counts them as M), 20 S, 1 C.
- Phase: **0 P1**, 62 BR + 11 NFR are P2, 1 BR (BR-REC-012) is P3.
- No BR or NFR was removed.

MoSCoW is business priority only. Every `[verify-legal]` value is an example and must be read from the Legal Parameter Service.

## 4.1 Staffing / headcount plan
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-001 | Staffing plans per legal entity and period. Each line is org unit × position with planned HC/FTE, hires, exits and budget cost (monthly + period, VND). | M | P2 |
| BR-REC-002 | Pre-fill a new plan with actual HC/FTE per org unit × position from People as of plan start. | M | P2 |
| BR-REC-003 | Propose line cost = FTE × band midpoint (or reference point) + employer on-cost %; the planner may edit it. | S | P2 |
| BR-REC-004 | Plans are approved via Core workflow and versioned. An approved version is read-only; a change creates a new version with a reason. | M | P2 |
| BR-REC-005 | Show Budget HC, Actual HC, Open vacancies, Accepted-not-started and Variance per line and rolled up the org tree; refresh daily and on hire/termination events. | M | P2 |
| BR-REC-006 | Flag lines whose org unit or position was retired or moved in Core; remap them before the next version is approved. | S | P2 |

## 4.2 Job requisitions
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-007 | A hiring manager creates a requisition: entity, org unit, position, title, vacancies, reason (new/replacement/temporary), replaced employee, employment and contract type, location, target start, salary range, JD, hiring team. | M | P2 |
| BR-REC-008 | Validate against the approved plan line and classify as Within plan / Over plan / Unplanned. | M | P2 |
| BR-REC-009 | Approve via Core workflow, routed on classification, org unit, grade and range; the approver can approve, reject or return. | M | P2 |
| BR-REC-010 | The requisition salary range must be inside the grade band. Out-of-band needs a justification and an extra approval. | M | P2 |
| BR-REC-011 | A hiring manager creates requisitions only within their Core data scope; HR may create them for any unit. | M | P2 |
| BR-REC-012 | When the replaced employee's separation is cancelled, notify the recruiter and suggest cancelling the requisition. | C | **P3** |

## 4.3 Job openings & postings
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-013 | One or more openings per approved requisition; total opening vacancies ≤ approved vacancies. | M | P2 |
| BR-REC-014 | Branded bilingual career site per tenant (custom domain or `<tenant>.careers.yourhrm.vn`) with search/filter. | M | P2 |
| BR-REC-015 | Internal job board via ESS. Internal applicants are identified; their manager may be notified per policy. | S | P2 |
| BR-REC-016 | Publish/unpublish to TopCV, VietnamWorks, ITviec and LinkedIn (API/XML) and share on Facebook. Each posting stores channel, status, external id, URL, dates and cost. | S | P2 |
| BR-REC-017 | Import applications from boards (API, or email-inbox fallback) and tag them with the source. | S | P2 |
| BR-REC-018 | Warn on discriminatory content before publishing, using a configurable keyword list `[verify-legal]`. | S | P2 |
| BR-REC-019 | Closing, filling or holding a requisition auto-unpublishes all its active postings. | M | P2 |

## 4.4 Candidates, applications, CV parsing, talent pool
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-020 | One candidate profile per person per tenant; many applications, each with its own stage, source and history. | M | P2 |
| BR-REC-021 | Candidate profile fields as listed in the BRD. CCCD/passport is collected **only from the offer stage** (data minimisation). | M | P2 |
| BR-REC-022 | Parse CVs (PDF, DOC/DOCX, OCR images; VI/EN) with confidence indicators; the recruiter confirms before saving. | S | P2 |
| BR-REC-023 | Duplicate detection by email, phone (+84), CCCD/passport and fuzzy name + DOB, with audited merge. Also flag current/former employees, including "not eligible for rehire". | M | P2 |
| BR-REC-024 | Talent pools with tags and search, only while talent-pool consent is valid. | S | P2 |
| BR-REC-025 | Full-text and faceted search over candidates and CV content within the tenant. | S | P2 |
| BR-REC-026 | No fully automated rejection or ranking. Scores and knock-outs are advisory; a human records the rejection `[verify-legal]`. | M | P2 |

## 4.5 Pipeline & screening
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-027 | Admin-defined pipeline templates (typed, ordered stages), one per opening; the recruiter may add stages per opening. | M | P2 |
| BR-REC-028 | Screening questions with knock-out answers that **flag**, never auto-reject. | S | P2 |
| BR-REC-029 | Rejected/Withdrawn requires a configurable reason code. Bulk move, reject and email are supported. | M | P2 |
| BR-REC-030 | Bilingual tenant templates for candidate communications by email (SMS / Zalo OA optional), each logged on the timeline. | M | P2 |
| BR-REC-031 | Every stage change is timestamped with the user, for time-in-stage and time-to-hire. | M | P2 |
| BR-REC-032 | Hiring managers and interviewers see only assigned openings; salary expectations and offer data need the "compensation" permission. | M | P2 |

## 4.6 Interviews & assessments
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-033 | Schedule interviews (round, type, room, panel, duration); propose slots from Google/M365 free/busy. | M (manual) / S (calendar) | P2 |
| BR-REC-034 | Candidate self-scheduling via a secure link; confirmed slots create calendar events with a Meet/Teams/Zoom link. | S | P2 |
| BR-REC-035 | Configurable reminders (e.g. 24 h / 1 h); reschedule, cancel and no-show with history. | M | P2 |
| BR-REC-036 | Scorecard template (criteria, 1–5 rating, Strong no → Strong yes, comments). Panelists submit independently and see others' scorecards only after submitting (configurable). | M | P2 |
| BR-REC-037 | Chase overdue scorecards (SLA default 24 h after the interview). | S | P2 |
| BR-REC-038 | Record assessments (internal or third-party): score, pass mark, file, expiry. | S | P2 |
| BR-REC-039 | Interviewers are active employees (People), identified by their Core user account. | M | P2 |

## 4.7 Offers
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-040 | Offer fields: position, grade, org unit, entity, location, manager, start date, contract type/term, probation days and %, GROSS/NET basis, base salary, allowances (Payroll catalogue), bonus/13th month, benefits, expiry. | M | P2 |
| BR-REC-041 | Validate against the grade band (min/mid/max) and show the compa-ratio. Out-of-band needs a justification and extra approval. | M | P2 |
| BR-REC-042 | NET offers call the Payroll gross-up simulation (GROSS offers get the estimated NET); store the simulation parameter version on the offer. | M | P2 |
| BR-REC-043 | Validate: salary ≥ regional minimum wage; probation ≤ legal max for the job level; probation % ≥ legal minimum; no probation below the contract-length threshold `[verify-legal]`. | M | P2 |
| BR-REC-044 | Offer approval via Core workflow, routed by grade, out-of-band flag and requisition classification. | M | P2 |
| BR-REC-045 | Generate the offer letter PDF (VI/EN) from templates; accept in the portal by click-to-accept or external e-sign. | M (click-accept) / S (e-sign) | P2 |
| BR-REC-046 | Offers are versioned. The candidate can Accept, Decline (with reason) or Request change; expired offers become Expired automatically. | M | P2 |
| BR-REC-047 | Acceptance reserves the vacancy and counts as Accepted-not-started on the plan. | M | P2 |

## 4.8 Pre-boarding & hire hand-off
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-048 | Configurable portal checklist after acceptance; validate CCCD (12 digits), tax code, bank account and SI number. | M | P2 |
| BR-REC-049 | HR verifies each item (Verified / Rejected with reason). When all mandatory items are verified, the application is Ready to hire. | M | P2 |
| BR-REC-050 | On Hire, emit one `rec.CandidateHired` package to **People only**, idempotent by `hire_id`, and track the People status (Pending/Accepted/Failed). People owns the salary record; Payroll is not a receiver. Contract frozen in Phase 0; payload minimised by D7. | M | P2 |
| BR-REC-051 | Never hire the same candidate twice for one requisition. Block if People reports an active employment in the same entity, unless concurrent employment is allowed. | M | P2 |
| BR-REC-052 | Process `HireCancelled` (no-show) and `StartDateChanged` from People; reopen or keep the vacancy. | M | P2 |
| BR-REC-053 | An internal candidate's hire emits `rec.InternalTransferAccepted` to People, which applies it via its own job/salary change process. | S | P2 |
| BR-REC-054 | Foreign national → flag "Work permit required" in the hand-off. Hire is blocked until the permit status is recorded, unless HR overrides with a reason. People owns the permit `[verify-legal]`. | S | P2 |

## 4.9 Referrals
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-055 | Employees refer candidates via ESS for published or referral-only openings; the referred person gets a consent request before further processing. | S | P2 |
| BR-REC-056 | Referral programmes define eligible openings, bonus (VND), qualifying condition (hired / passed probation / N days) and exclusions. | S | P2 |
| BR-REC-057 | When the condition is met (People events), send a referral bonus instruction to Payroll for the next period. | S | P2 |

## 4.10 Cost, analytics, privacy
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| BR-REC-058 | Record recruitment costs by category per posting / opening / requisition / source, in VND. | S | P2 |
| BR-REC-059 | Explicit per-purpose consent (application, talent pool + duration, group sharing) is recorded in the **saas consent registry**. Rec holds references and calls `hasConsent()`. Nothing beyond minimal identifiers is processed without consent or a legal basis `[verify-legal]`. | M | P2 |
| BR-REC-060 | Candidates view, correct, export, withdraw consent and request deletion via the portal or a tokenised link. Requests are logged and fulfilled within the SLA `[verify-legal]`. | M | P2 |
| BR-REC-061 | Configurable retention (defaults: unsuccessful 12 months; talent pool per consent, max 24 months) `[verify-legal]`. The saas scheduler triggers the daily rec anonymise/delete handler; only anonymised statistics remain. | M | P2 |
| BR-REC-062 | Legal hold by HR/DPO suspends deletion, with a reason and review date. | S | P2 |
| BR-REC-063 | Every view, export or download of a CV or identity document is written to the Core audit log. | M | P2 |

## 9. Non-functional requirements
| ID | Rule | MoSCoW | Phase |
|---|---|---|---|
| NFR-REC-001 | Tenant isolation (tenant_id + RLS); feature gating and quotas follow the subscription. | M | P2 |
| NFR-REC-002 | Career site: p95 < 2 s on 4G, mobile-first, WCAG 2.1 AA, SEO (schema.org JobPosting, sitemap), apply without login. | M | P2 |
| NFR-REC-003 | Bot protection (rate limit, CAPTCHA); virus-scanned uploads, ≤ 10 MB, PDF/DOC/DOCX/JPG/PNG. | M | P2 |
| NFR-REC-004 | ID documents, health and judicial records are encrypted at field/file level; access needs explicit permission and is logged. | M | P2 |
| NFR-REC-005 | Hand-off via transactional outbox, at-least-once with an idempotency key; < 1 min end-to-end; retry dashboard. | M | P2 |
| NFR-REC-006 | CV parsing ≥ 90% accuracy on name/email/phone; < 10 s p95; a parser outside VN needs tenant opt-in (cross-border). | S | P2 |
| NFR-REC-007 | Job-board and calendar integrations fail gracefully: retries with backoff, notify after final failure, no lost applications. | S | P2 |
| NFR-REC-008 | Search over 500k candidates per tenant < 2 s p95. | S | P2 |
| NFR-REC-009 | The retention job deletes/anonymises within 24 h of expiry, including files and search indexes; backups expire per policy. | M | P2 |
| NFR-REC-010 | Bilingual VI/EN UI and communications; accent-insensitive Vietnamese search. | M | P2 |
| NFR-REC-011 | Candidate portal magic-link sessions expire after 30 min idle; links are single-tenant, signed and time-limited. | M | P2 |

## Compliance rules (BRD §5, not BRs, listed for cross-reference)
These are all `[verify-legal]` except C-03.
- C-01 non-discrimination
- C-02 no deposits or original papers
- C-03 information duty
- C-04 probation maximum
- C-05 probation salary %
- C-06 regional minimum wage
- C-07 minimum age
- C-08 work permit
- C-09 PDPL consent and rights
- C-10 sensitive data
- C-11 DPIA / cross-border
- C-12 breach notification within 72 h
- C-13 judicial record
- C-14 labour-usage reporting (People)

See SKILL.md §6.
