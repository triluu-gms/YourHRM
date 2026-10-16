# ADR-0015: Document generation engines; password-protected payslip PDF by e-mail

- **Status:** Proposed (rev. 2026-10-16: decision 6 applies the sponsor decision **R2-17** — password-protected payslip PDF by e-mail — which is accepted; the technical design of decision 6 is Proposed)
- **Date:** 2026-10-16
- **Related:** gap-analysis GAP-007, GAP-057; decision log R2-17; NFR-PPL-010 (PDF/A, Vietnamese fonts), NFR-SAAS-013, NFR-PAY-010/011, NFR-CORE-005; [architecture-overview](../architecture-overview.md) §9.3, §9.4; ADR-0005 (step-up), ADR-0026 (subject DEKs), ADR-0027 (audit); DESIGN.md §7.5, §9.3

## Context

The system generates: payslips (10,000 per run, published within 30 min), statutory PDFs and registers, HR decisions (quyết định), contracts and letters that tenants want to edit in Word, Excel exports up to 100k rows, and XML files for eTax/VSS validated against current templates. Vietnamese diacritics must render correctly; archives require PDF/A. Core owns the document service; SaaS owns storage.

Payslip delivery (GAP-057): Core's notification rule is "no sensitive data in message bodies, deep links only". The sponsor decided (R2-17, 2026-10-16) that payslips are sent by e-mail as a **password-protected PDF**; Core's deep-link-only rule is relaxed for payslips. Vietnamese employees commonly expect the payslip in their mailbox, and many pilot workers rarely log in to ESS.

## Decision

1. **System documents at volume:** Thymeleaf HTML templates → **OpenHTMLtoPDF** → PDF/A-2b with embedded fonts (Noto Sans/Serif, Be Vietnam Pro); runs in-process on workers.
2. **Tenant-editable documents:** DOCX templates with merge fields (**poi-tl**) → **Gotenberg** (LibreOffice) → PDF/A; merge-field registry per document type (modules register fields).
3. **Excel:** Apache POI SXSSF streaming; templates for statutory Excel forms.
4. **XML:** JAXB-generated bindings per template version; XSD validation before release; templates versioned in the statutory template framework (ADR-0012).
5. **Storage & access:** through the Core document service only; ClamAV scan for uploads; signed URLs ≤ 15 min. Notifications other than payslips keep the deep-link-only rule.
6. **Payslip e-mail as password-protected PDF (R2-17):**
   - **Archive vs attachment.** The payslip of record stays the unencrypted **PDF/A-2b** in the document service (PDF/A forbids encryption), downloadable in ESS with step-up. For e-mail, the payslip job produces a **separate encrypted copy**: same rendered content, PDF 2.0 standard security handler **AES-256 (revision 6)**, via Apache PDFBox 3; permissions allow view and print, disallow modification. The attachment is built per recipient, handed to the e-mail channel as a short-lived object under the tenant prefix (deleted after sending, at most 24 h) and never kept as a document.
   - **Password rule.** The **employee sets the payslip password in ESS** (step-up `acr ≥ 2`; minimum 8 characters, not equal to or containing the employee number, date of birth, CCCD or phone; checked against a common-password list). The password is **never sent by e-mail** — not in the payslip e-mail, not in any other e-mail — and never shown again after it is set. HR and operators cannot read or set it; they can only clear it, which forces the employee to set a new one. No default or derived password (such as date of birth + last digits of CCCD) is used, because those values are known to colleagues and to HR.
   - **No password yet.** Until the employee has set a password (or if they choose ESS only), the payslip e-mail has **no attachment**: it carries the notification and the ESS link, as before. HR sees a "no payslip password" list before publication; the first e-mail after go-live invites employees to set one.
   - **Key handling.** The password is stored in `pay.payslip_delivery_preference.pdf_password_enc` as a **P3** value encrypted with the person's subject DEK (ADR-0026; OpenBao transit wraps the DEK under the tenant KEK). The API field is write-only; the value is never logged, never put in events, job payloads or notification templates. The payslip e-mail job (Payroll, `general` pool) unwraps the DEK and decrypts the password in memory only for the duration of one PDF encryption, then discards it. The PDF owner password is random per file and discarded. Crypto-shredding the subject (DSR, retention) also destroys the password.
   - **Delivery and audit.** `pay.payslip_delivery` records per e-mail: payslip id, recipient address, SHA-256 of the encrypted attachment, the `password_set_at` in force, send and bounce status. Audit events (ADR-0027, no secret values): password set / cleared (actor, time), each payslip e-mail with attachment sent, each failure or bounce, each resend. Payslip e-mails go to the address the employee chose (work or personal), so former employees (FORMER_EMPLOYEE) receive final-settlement payslips at their personal address.
   - **Throughput.** AES encryption adds little to rendering; the 10,000 payslips ≤ 30 min target (NFR-CORE-005) is verified in SP-5 with encryption on.

## Alternatives considered

- **Secure link only (PM recommendation in GAP-057).** Best privacy, but rejected by the sponsor (R2-17) for usability.
- **Default password derived from personal data** (date of birth, CCCD digits, employee number). Common in the market, but guessable by colleagues and HR; rejected.
- **Password sent in a second e-mail or SMS.** A second e-mail to the same mailbox gives no protection; SMS is P3. Rejected.
- **Storing only a password-derived PDF key instead of the encrypted password** (pre-computing the revision-6 user-key hash per employee). Avoids storing the password itself, but needs a custom PDF security handler; kept as a Phase-2 hardening option.
- **S/MIME or PGP e-mail encryption.** Employees have no certificates; not practical.
- **JasperReports / headless Chromium / commercial engines** for generation in general: as before — authoring UX, PDF/A compliance or licence cost; Aspose/Docmosis possible later if Gotenberg fidelity fails SP-5.

## Consequences

- + HR users edit templates in Word; payslips are fast and archival-grade; employees get payslips in the mailbox as the sponsor asked.
- + The password never travels with the document and nobody but the employee knows it.
- − A payslip attachment is only as strong as the employee's password; offline guessing is possible once a mail is leaked. Mitigated by the password rule and AES-256 R6 (slow key derivation).
- − Employees who forget the password must reset it in ESS (step-up); support load expected in the first month.
- − Two engines to maintain; fidelity of complex DOCX via LibreOffice to be verified with pilot templates (SP-5).
- − Core's notification rule now has a documented exception (payslips); the Core and Payroll BAs record it in their BRDs (GAP-057).
