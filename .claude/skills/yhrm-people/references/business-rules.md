# People BR / NFR index (BR-PPL, NFR-PPL)

Source: `docs/01-requirements/brd/modules/02-people.md` v0.2 §4 and §9 (Vietnamese). Phase comes from
`docs/01-requirements/brd/mvp-scope.md` §9.2, which is the only phase authority. MoSCoW is business priority only.
`[VL]` = the BRD marks the rule `[verify-legal]`: every number in it is an example and must be a legal parameter.

**Count: 95 BR + 12 NFR = 107 rows.** By phase: P1 = 47 BR + 10 NFR, P2 = 43 BR + 2 NFR, P3 = 5 BR. This matches
mvp-scope §3.1 (People 95 BR, 47 P1) and the backlog (57 P1 requirements).

Read the BRD row before you implement a BR. These one-liners are an index, not the spec.

## 4.1 Person & employment
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-001 | Exactly one Person per real individual per tenant, keyed by the 12-digit national ID (CCCD), or passport + nationality for foreigners. Check duplicates on create and import; merges are audited. | M | P1 |
| BR-PPL-002 | A Person may have many Employments over time (rehire) and at the same time in different legal entities of the tenant. At most one active Employment per legal entity on any date. | M | P1 |
| BR-PPL-003 | Every Employment has a legal entity, an employee no. (unique in the tenant, pattern set per LE), hire, original-hire and seniority dates, a status, a worker group and an employment type. Collaborators on service contracts are included. | M | P1 |
| BR-PPL-004 | Concurrent employment: designate a date-effective primary employment for `PIT_FAMILY_DEDUCTION` only (exactly one per person per day). Insurance participation is per employment × type (SI/HI/UI/OA_OD), derived from `insurance.multi_contract_rule` [VL]. Never claim the same dependant or self-deduction twice. Emit OUT-36 / OUT-37. | M | P1 |
| BR-PPL-005 | On rehire, show prior employment history, the leaving reason and the rehire-eligible flag. HR chooses whether prior service counts towards seniority. | M | P2 |
| BR-PPL-006 | Immutable employee timeline of all HR events with effective date, entry date, actor and source document. It is a read model built from events. | M | P2 |

## 4.2 Personal information
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-010 | Identity data: name (with and without diacritics), date of birth, gender, nationality, ethnicity, religion (optional, sensitive), hometown, and permanent and current addresses on the post-2025 two-level admin units. Legacy three-level addresses are kept for import and old documents. [VL] | M | P1 |
| BR-PPL-011 | Identity documents (CCCD, passport, work permit, temporary residence card) with number, issue date and place, expiry and scan. Alert before expiry (configurable days). | M | P1 |
| BR-PPL-012 | VNeID identity verification (level-2 QR/NFC) through the platform connector. Record its status, method and time. | S | P2 |
| BR-PPL-013 | Personal tax code. Default it to the 12-digit personal ID where the law treats them as equal; keep the legacy 10-digit MST as history. [VL] | M | P1 |
| BR-PPL-014 | SI book no. (mã số BHXH), health-insurance card no., initial KCB facility, and SI-book custody status. | M | P1 |
| BR-PPL-015 | One or more date-effective bank accounts with a pay split (amount or %). Exactly one default salary account. Warn when the holder name ≠ the employee name. | M | P1 |
| BR-PPL-016 | Family members. Flag PIT dependants (người phụ thuộc) with start and end month and evidence, validated against eligibility rules; thresholds are parameters. [VL] | M | P1 |
| BR-PPL-017 | Registering or changing a dependant needs HR approval and emits `DependantChanged`. Generate the registration form/list (07/ĐK-NPT-TNCN or the current form). [VL] | M | P1 |
| BR-PPL-018 | Emergency contacts (minimum 1 per active employee, configurable). | M | P2 |
| BR-PPL-019 | Education, certificates and practising licences with expiry alerts; safety-training certificate flag. P1 builds only the minimal qualification field for the labour book (R3-07). | M | P1 (minimal) |
| BR-PPL-020 | Foreign-worker data: work permit or exemption, validity, title on permit, LE. Block an assignment end beyond permit validity unless renewed. Alert 45/60 days before expiry (configurable). Decree 219/2025 [VL] | M | P1 |
| BR-PPL-021 | Special-status attributes (pregnant/child < 12 months, disability, minor, elderly, union member, party member optional, hazardous-job class). They feed T&A and protection rules downstream. | M | P1 |
| BR-PPL-022 | ESS change requests for personal data: configured fields update directly, others need HR approval with evidence. Previous values are kept. | M | P2 |
| BR-PPL-023 | Date-effective tax residency (resident/non-resident) with basis, VN entry/exit dates and optional days-in-VN per year. HR approves; published in `PersonalDataChanged`. People does NOT compute the 183-day test: Payroll proposes (BR-PAY-065). [VL] | new | P1 |

## 4.3 Assignment & org placement
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-030 | Assignments are date-effective with full history. Never overwrite history rows in place; record corrections as corrections with a reason. | M | P1 |
| BR-PPL-031 | An assignment holds LE, org unit, position, job, grade, line manager (by employee or by position), location, cost centre (split %), contractual working regime, FTE and type. It has **no pay group** (Payroll owns it) and **no work schedule** (T&A owns it). | M | P1 |
| BR-PPL-032 | Catalogue of change reasons. The reason decides the approval steps and the required documents. | M | P1 |
| BR-PPL-033 | Concurrent positions (kiêm nhiệm) within one employment, each with its own manager, FTE and optional allowance. One line is flagged primary. | M | P2 |
| BR-PPL-034 | Secondment (biệt phái) to another LE (home/host/paying LE, cost recharge, dates) without terminating the home employment. | S | P2 |
| BR-PPL-035 | Art. 29 temporary transfer: cumulative days per calendar year over the limit need written consent; minimum notice; wage-protection flags go to Payroll. Example values (60 working days, 3 days notice, ≥85%, 30 days) [VL] | M | P1 |
| BR-PPL-036 | Mass change (reorg): apply one change at one effective date to selected employees and generate the individual decisions. | S | P2 |
| BR-PPL-037 | Future-dated and retroactive changes are allowed. A retro change that touches a closed pay period sets the retro flag on the emitted event. | M | P1 |
| BR-PPL-038 | Position incumbency, and vacancy/over-establishment against Core's position headcount. | M | P2 |
| BR-PPL-039 | On `core.ReorgActivated`, apply the package mapping (new assignment rows, mass decisions, `AssignmentChanged` with reason `reorg`), then emit `people.ReorgMappingCompleted` with a fix list. Idempotent per package × employee. | new | P2 |

## 4.4 Salary history (agreed compensation)
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-040 | People is the system of record for agreed pay: date-effective salary records per employment (scale/grade/step, GROSS/NET basis, base salary, agreed net (required if NET), net-guaranteed scope, SI salary, contractual allowances by Payroll component code, currency, probation %). Create or change them **only** via an approved decision or contract appendix. Each one emits `SalaryChanged`. | M | P1 |
| BR-PPL-041 | Validate against the Payroll grade band (warn) and the regional minimum wage for the work location at the effective date (block). NET records are checked on the gross from Payroll's simulator. [VL] | M | P1 |
| BR-PPL-042 | Step-increase schedule: compute the next eligible date, including discipline delays; produce a due list. | S | P2 |
| BR-PPL-043 | Salary review cycle (budget per org unit, manager proposals, mass `SalaryChanged`). | C | P3 |
| BR-PPL-044 | Viewing or changing salary records needs the `compensation` permission. Every view and every change is written to Core audit (who, when, record, purpose). | M | P1 |

## 4.5 Labour contracts
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-050 | Contract types: probation, definite (≤36 months), indefinite, seasonal/short (<1 month), apprenticeship, internship, service/collaborator (not a labour contract), and appendix. [VL] | M | P1 |
| BR-PPL-051 | Probation: maximum length by job level, once per job, none for contracts < 1 month, probation pay ≥ 85%. Example limits [VL] | M | P1 |
| BR-PPL-052 | Record the probation result and notify the employee before probation ends; alert N days ahead; a pass leads to a labour contract. [VL] | M | P1 |
| BR-PPL-053 | A definite contract is ≤ 36 months. Count consecutive definite contracts per employment; after two, the next one must be indefinite, except for legal exceptions (elderly, foreigners, SOE managers, union term, …). [VL] | M | P1 |
| BR-PPL-054 | Expiry monitor with configurable alerts (e.g. 45/30/15 days). Working > 30 days past expiry without a new contract raises the auto-conversion flag. [VL] | M | P1 |
| BR-PPL-055 | An appendix records changed terms with an effective date. It must not change the contract term. [VL] | M | P1 |
| BR-PPL-056 | Statutory contract suspension with dates. Employment status becomes `Suspended`; emit `ContractSuspended` / `ContractResumed`. [VL] | M | P1 |
| BR-PPL-057 | Contract documents come from bilingual tenant templates, are numbered by LE sequence, and are signed by an authorised signatory. | M | P1 |
| BR-PPL-058 | E-labour contract through the platform e-sign provider with status tracking and signed-file evidence; national-platform filing if mandated. [VL] | S→M | P2 |
| BR-PPL-059 | Collective labour agreement (TƯLĐTT) reference per LE. | C | P3 |

## 4.6 Onboarding & offboarding
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-060 | Configurable checklist templates per LE, employee group and event. | M | P2 |
| BR-PPL-061 | Pre-hire portal: invitees complete their data, upload documents and give consents before day one. | M | P2 |
| BR-PPL-062 | Offboarding: handover record, assets to return, access-revocation date, exit interview, clearance sign-off. | M | P2 |
| BR-PPL-063 | Checklist progress and overdue-task dashboard; reminders through Core notifications. | M | P2 |

## 4.7 Separation
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-070 | Separation reasons map to a legal ground, and each ground carries a statutory code used for SI/UI and reports. | M | P1 |
| BR-PPL-071 | Compute the required notice from contract type and reason, including special trades and no-notice cases. Show the shortfall and the compensation consequence. [VL] | M | P1 |
| BR-PPL-072 | ESS resignation (proposed LWD, reason). Manager and HR approve. Withdrawal is allowed before approval, and after approval only with consent. | M | P1 |
| BR-PPL-073 | Block employer-initiated termination or dismissal of protected employees unless a lawful, documented exception exists. [VL] | M | P1 |
| BR-PPL-074 | Determine eligibility for severance (trợ cấp thôi việc) or job-loss allowance (trợ cấp mất việc làm) and supply service periods and already-paid periods. **Payroll computes** service time and amount; People displays the result and does not recompute it. [VL] | M | P1 |
| BR-PPL-075 | On approval emit `TerminationScheduled`; on LWD+1 emit `EmployeeTerminated` with the final-settlement trigger. Track the settlement deadline with alerts. [VL] | M | P1 |
| BR-PPL-076 | Post-separation obligations: SI book closure/return, original documents, work certificate, PIT certificate request, rehire flag, ESS read-only period. | M | P2 |
| BR-PPL-077 | Mass redundancy support (Art. 42–44): labour-use plan, union consultation evidence, notice to the provincial authority. | C | P3 |

## 4.8 Discipline & rewards
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-080 | Discipline case file (violation, rule breached, evidence, meeting with union rep, minutes, decision, sanction, duration, expungement date). [VL] | M | P2 |
| BR-PPL-081 | Enforce the limitation period (e.g. 6/12 months) and forbid fines or pay cuts instead of discipline. [VL] | M | P2 |
| BR-PPL-082 | Suspension during investigation (maximum days, pay advance %) sent to Payroll. [VL] | S | P2 |
| BR-PPL-083 | Material liability: compensation amount and instalments, with the monthly deduction capped (e.g. ≤30%) and sent to Payroll. [VL] | S | P2 |
| BR-PPL-084 | Auto-expunge discipline after the statutory period without repeat offence; keep the record, marked expunged. [VL] | S | P2 |
| BR-PPL-085 | Rewards (type, level, amount, tax flag, decision). Approved cash rewards are published to Payroll for the target period. | M | P2 |

## 4.9 Appraisal / performance
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-090 | Appraisal cycle configuration (period, population, template, weights, rating scale, deadlines). | S | P2 |
| BR-PPL-091 | KPI library and cascading goals; OKR progress reviews. | S | P2 |
| BR-PPL-092 | Self and manager review, optional 360, calibration; the final rating locks after confirmation. | S | P2 |
| BR-PPL-093 | Emit `AppraisalFinalised` (rating, score, period) to Payroll and salary review. Monthly KPI score is open (Q4). | S | P2 |
| BR-PPL-094 | Probation appraisal form linked to the probation result (BR-PPL-052). | M | P2 |

## 4.10 Training
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-100 | Course catalogue, sessions, enrolment, attendance and results; issuing certificates updates BR-PPL-019. | S | P2 |
| BR-PPL-101 | Training bond: on early leaving, compute the pro-rata repayment and send it to the final settlement. [VL] | S | P2 |
| BR-PPL-102 | Mandatory training (e.g. OSH groups) with expiry and compliance reporting. [VL] | S | P2 |
| BR-PPL-103 | Training time on workdays can be sent to T&A as paid training time. | C | P3 |

## 4.11 Expenses & advances
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-110 | Expense types with policy limits, required receipts and e-invoice VAT fields. | S | P2 |
| BR-PPL-111 | Cash advance → approval → paid; settle claims against the advance; balance due to or from the employee. | S | P2 |
| BR-PPL-112 | Approved reimbursements go, per expense type, to Payroll (`ExpenseReimbursementApproved`) or to a finance export. Overdue advances are deducted via `AdvanceDeductionRequested` with consent. | S | P2 |
| BR-PPL-113 | Business trips are **owned by T&A**. People consumes `att.BusinessTripApproved` to prefill and validate trip expenses. People has no trip request of its own. | S | P2 |

## 4.12 ESS / MSS
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-120 | People ESS pages (profile, assignment, contract, salary history if policy allows, documents, timeline, dependants request, bank change with step-up, resignation, …) hosted in the SaaS ESS/MSS shell. | M | P1 |
| BR-PPL-121 | MSS: team list and org chart, pending approvals, initiating changes, probation results, appraisals. | M | P2 |
| BR-PPL-122 | Manager approval delegation through Core's delegation. | M | P2 |
| BR-PPL-123 | ESS/MSS in Vietnamese and English, mobile-responsive. | M | P1 |

## 4.13 Documents, decisions & letters
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-130 | Generate decisions (hire, probation result, transfer, promotion, salary change, suspension, termination, …) with a number per LE sequence per year, date, signatory, effective date and legal basis. | M | P1 |
| BR-PPL-131 | Template management UI (editable, merge fields, versioned, bilingual). In P1 templates go through BR-CORE-102. | M | P2 |
| BR-PPL-132 | On-demand HR letters (employment and income confirmation, visa support), requested via ESS with approval. | S | P2 |
| BR-PPL-133 | Employee document repository with categories, retention, confidentiality and versions. | M | P2 |
| BR-PPL-134 | Digital signing of decisions and letters through platform e-sign. | S | P2 |

## 4.14 Statutory reporting & labour book
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-140 | Electronic labour management book (sổ quản lý lao động) per LE with the statutory fields, inspection-ready and exportable. Decree 145/2020 [VL] | M | P1 |
| BR-PPL-141 | 6-monthly labour change report per LE (e.g. Form 01/PLI) with deadline alerts and a submission log. [VL] | M | P1 |
| BR-PPL-142 | Monthly labour fluctuation notice to the Employment Service Centre (hires and terminations) with deadline alerts. [VL] | M | P1 |
| BR-PPL-143 | Foreign-worker statutory reports. [VL] | S | P2 |
| BR-PPL-144 | Initial labour-use declaration within 30 days of the LE starting operations. [VL] | C | P3 |

## 4.15 Personal data protection
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| BR-PPL-150 | Classify every personal-data field as basic or **sensitive** in the data dictionary. [VL] | M | P1 |
| BR-PPL-151 | Per-purpose consent lives in the **SaaS consent registry**. People keeps only references and calls `hasConsent()` before optional sensitive processing; lawful-basis processing needs no consent. | M | P1 |
| BR-PPL-152 | Sensitive fields are masked by default in the UI and in exports. Unmasking needs a permission and is logged. | M | P1 |
| BR-PPL-153 | DSRs are run by SaaS DSR orchestration. People supplies the export and anonymisation handlers (OUT-33). [VL] for the response deadline | M | P1 |
| BR-PPL-154 | Retention after separation through the SaaS retention scheduler. People registers its categories and runs the anonymise/delete callbacks; legal hold is respected. | S | P2 |

## 9. NFR
| ID | Rule (one line) | MoSCoW | Phase |
|---|---|---|---|
| NFR-PPL-001 | Every employee query supports "as of date X" and "as known on date Y" for assignment, salary and contract. | M | P1 |
| NFR-PPL-002 | Transactional outbox: no HR change commits without its event. At-least-once delivery, idempotent `event_id`. | M | P1 |
| NFR-PPL-003 | Profile page ≤ 2 s p95 and list search ≤ 1 s p95 at 10,000 employees. | M | P1 |
| NFR-PPL-004 | Bulk operations: 5,000 employees in ≤ 10 min, async, with progress and per-row errors. | S | P2 |
| NFR-PPL-005 | Field-level encryption of sensitive fields (IDs, bank, health, religion, ethnicity); masked in logs and non-prod copies. | M | P1 |
| NFR-PPL-006 | Log every sensitive read and every write with actor, purpose and before/after. | M | P1 |
| NFR-PPL-007 | Opening-balance employee import from standard Excel templates (person, employment, assignment, contract with prior definite count, salary plus the history needed for severance, dependants, bank). Validated, reconciled, idempotent re-run. No legacy DB migration. | M | P1 |
| NFR-PPL-008 | Strict tenant isolation: never match persons across tenants. | M | P1 |
| NFR-PPL-009 | Bilingual VI/EN UI, Vietnamese collation, accent-insensitive name search. | M | P1 |
| NFR-PPL-010 | Generated documents in PDF/A with correct Vietnamese fonts. | M | P1 |
| NFR-PPL-011 | ESS usable on mobile browsers ≥ 360 px, with step-up auth for bank changes. | M | P1 |
| NFR-PPL-012 | Configurable document retention and legal hold per category; verifiable deletion. | S | P2 |
