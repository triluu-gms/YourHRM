package vn.yourhrm.people.internal.infra.salary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.yourhrm.people.api.SalaryEvents.Allowance;

/** One version of {@code hr.salary_record} (bi-temporal) with its contractual allowances. Amounts are P2. */
public record SalaryRow(UUID id, UUID employmentId, String payBasis, String salaryKind, BigDecimal baseSalaryAmount,
        BigDecimal agreedNetAmount, List<String> netGuaranteedScope, BigDecimal siSalaryAmount, String currencyCode,
        String payScaleCode, String payScaleStepCode, BigDecimal probationPct, String actionCode, String reasonCode,
        UUID decisionId, UUID contractAppendixId, LocalDate validFrom, LocalDate validTo, String source,
        Instant recordedAt, Instant supersededAt, UUID supersededBy, String correctionReason,
        List<Allowance> allowances) {

    public SalaryRow {
        allowances = allowances == null ? List.of() : List.copyOf(allowances);
    }

    public SalaryRow withId(UUID newId) {
        return new SalaryRow(newId, employmentId, payBasis, salaryKind, baseSalaryAmount, agreedNetAmount,
                netGuaranteedScope, siSalaryAmount, currencyCode, payScaleCode, payScaleStepCode, probationPct,
                actionCode, reasonCode, decisionId, contractAppendixId, validFrom, validTo, source, null, null, null,
                correctionReason, allowances);
    }

    public SalaryRow withPeriod(LocalDate from, LocalDate to) {
        return new SalaryRow(id, employmentId, payBasis, salaryKind, baseSalaryAmount, agreedNetAmount,
                netGuaranteedScope, siSalaryAmount, currencyCode, payScaleCode, payScaleStepCode, probationPct,
                actionCode, reasonCode, decisionId, contractAppendixId, from, to, source, recordedAt, supersededAt,
                supersededBy, correctionReason, allowances);
    }

    public SalaryRow withDocuments(UUID decision, UUID appendix) {
        return new SalaryRow(id, employmentId, payBasis, salaryKind, baseSalaryAmount, agreedNetAmount,
                netGuaranteedScope, siSalaryAmount, currencyCode, payScaleCode, payScaleStepCode, probationPct,
                actionCode, reasonCode, decision, appendix, validFrom, validTo, source, recordedAt, supersededAt,
                supersededBy, correctionReason, allowances);
    }

    public SalaryRow withCorrectionReason(String reason) {
        return new SalaryRow(id, employmentId, payBasis, salaryKind, baseSalaryAmount, agreedNetAmount,
                netGuaranteedScope, siSalaryAmount, currencyCode, payScaleCode, payScaleStepCode, probationPct,
                actionCode, reasonCode, decisionId, contractAppendixId, validFrom, validTo, source, recordedAt,
                supersededAt, supersededBy, reason, allowances);
    }

    public boolean covers(LocalDate d) {
        return !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo));
    }
}
