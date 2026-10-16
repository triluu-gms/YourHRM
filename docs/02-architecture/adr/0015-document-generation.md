# ADR-0015: Document generation engines

- **Status:** Proposed
- **Date:** 2026-10-16
- **Related:** gap-analysis GAP-007, GAP-057; NFR-PPL-010 (PDF/A, Vietnamese fonts), NFR-SAAS-013, NFR-PAY-010/011, NFR-CORE-005; [architecture-overview](../architecture-overview.md) §9.4

## Context

The system generates: payslips (10,000 per run, published within 30 min), statutory PDFs and registers, HR decisions (quyết định), contracts and letters that tenants want to edit in Word, Excel exports up to 100k rows, and XML files for eTax/VSS validated against current templates. Vietnamese diacritics must render correctly; archives require PDF/A. Core owns the document service; SaaS owns storage.

## Decision

1. **System documents at volume:** Thymeleaf HTML templates → **OpenHTMLtoPDF** → PDF/A-2b with embedded fonts (Noto Sans/Serif, Be Vietnam Pro); runs in-process on workers.
2. **Tenant-editable documents:** DOCX templates with merge fields (**poi-tl**) → **Gotenberg** (LibreOffice) → PDF/A; merge-field registry per document type (modules register fields).
3. **Excel:** Apache POI SXSSF streaming; templates for statutory Excel forms.
4. **XML:** JAXB-generated bindings per template version; XSD validation before release; templates versioned in the statutory template framework (ADR-0012).
5. **Storage & access:** through the Core document service only; ClamAV scan for uploads; signed URLs ≤ 15 min; payslip e-mail = deep link by default; password-protected PDF attachment as tenant opt-in.

## Alternatives considered

- **JasperReports:** powerful but authoring requires developer tools; poor fit for HR-editable templates.
- **Headless Chromium for HTML→PDF:** best CSS fidelity, but heavier per document and harder PDF/A compliance.
- **Commercial (Aspose, Docmosis):** high fidelity DOCX; licence cost; possible later if Gotenberg fidelity fails SP-5.

## Consequences

- + HR users edit templates in Word; payslips are fast and archival-grade.
- − Two engines to maintain; fidelity of complex DOCX via LibreOffice to be verified with pilot templates (SP-5).
