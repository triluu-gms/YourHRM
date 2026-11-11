package vn.yourhrm.people.internal.application.integration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.people.api.ContractEvents;
import vn.yourhrm.people.api.ContractEvents.ContractChanged;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.assignment.AssignmentService;
import vn.yourhrm.people.internal.application.contract.ContractAppendixService;
import vn.yourhrm.people.internal.application.contract.ContractAppendixService.AppendixView;
import vn.yourhrm.people.internal.application.salary.SalaryService;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Integration fix F4 (BR-PPL-055, US-PPL-053): when a contract appendix is signed ({@code people.ContractChanged}
 * subtype APPENDIX, published in-process inside the signing transaction) its pay and assignment terms are applied in
 * that same transaction:
 * <ul>
 *   <li>{@code salary} / {@code allowances} → a new salary-record version from the appendix effective date through
 *       {@link SalaryService#applyFromAppendix} ({@code contract_appendix_id}, no decision), with the salary stream's
 *       validations: a violation (e.g. below the regional minimum wage) fails the signature with its 422.
 *       {@code salary} is either a number (the new base salary) or an object with any of {@code payBasis},
 *       {@code salaryKind}, {@code baseSalaryAmount} (alias {@code base}), {@code agreedNetAmount} (alias
 *       {@code agreedNet}), {@code netGuaranteedScope}, {@code siSalaryAmount} (alias {@code siSalary}),
 *       {@code payScaleCode}, {@code payScaleStepCode}, {@code probationPct}; terms not given are carried over.
 *       {@code allowances} is the full new list of {@code {componentCode, amount, inSiSalary}}. With no salary record
 *       in force to amend the pay terms are not applied (audited as {@code APPENDIX_PAY_TERMS_NOT_APPLIED}).</li>
 *   <li>{@code orgUnitId} / {@code positionId} / {@code jobTitleId} / {@code locationId} (ids) → a new PRIMARY
 *       assignment version through {@link AssignmentService#applyFromAppendix}. Free-text terms
 *       ({@code workLocation}, {@code workingTime}, {@code other}) stay on the appendix only.</li>
 * </ul>
 */
@Component
class AppendixTermsListener {

    private static final Logger log = LoggerFactory.getLogger(AppendixTermsListener.class);

    private final ContractAppendixService appendices;
    private final SalaryService salaries;
    private final AssignmentService assignments;
    private final AuditLog audit;

    AppendixTermsListener(ContractAppendixService appendices, SalaryService salaries, AssignmentService assignments,
            AuditLog audit) {
        this.appendices = appendices;
        this.salaries = salaries;
        this.assignments = assignments;
        this.audit = audit;
    }

    @EventListener
    void on(ContractChanged e) {
        if (!ContractEvents.APPENDIX.equals(e.subtype()) || e.appendixId() == null || e.initialLoad()) {
            return;
        }
        AppendixView a = appendices.find(e.appendixId()).orElse(null);
        if (a == null) {
            return;
        }
        Map<String, Object> c = a.changes();
        if (c.containsKey("orgUnitId") || c.containsKey("positionId") || c.containsKey("jobTitleId")
                || c.containsKey("locationId")) {
            assignments.applyFromAppendix(a.employmentId(), a.id(), a.effectiveDate(), uuid(c, "orgUnitId"),
                    uuid(c, "positionId"), uuid(c, "jobTitleId"), uuid(c, "locationId"));
        }
        if (c.containsKey("salary") || c.containsKey("allowances")) {
            var applied = salaries.applyFromAppendix(a.employmentId(), a.id(), a.effectiveDate(),
                    in -> amend(in, c.get("salary"), c.get("allowances")));
            if (applied.isEmpty()) {
                log.warn("Appendix {} carries pay terms but employment has no salary record on {}", a.appendixNo(),
                        a.effectiveDate());
                audit.record(new BusinessAction("APPENDIX_PAY_TERMS_NOT_APPLIED", "hr", "contract_appendix", a.id(),
                        a.legalEntityId(), Map.of("reason", "NO_SALARY_RECORD", "effectiveDate",
                                a.effectiveDate().toString())));
            }
        }
    }

    /** The amended terms: values given in the appendix replace those in force. Package-visible for unit tests. */
    static SalaryTerms amend(SalaryTerms in, Object salary, Object allowances) {
        String payBasis = in.payBasis();
        String salaryKind = in.salaryKind();
        BigDecimal base = in.baseSalaryAmount();
        BigDecimal agreedNet = in.agreedNetAmount();
        List<String> scope = in.netGuaranteedScope();
        BigDecimal si = in.siSalaryAmount();
        String scale = in.payScaleCode();
        String step = in.payScaleStepCode();
        BigDecimal probation = in.probationPct();
        List<Allowance> list = in.allowances();
        if (salary instanceof Number n) {
            base = decimal(n, "salary");
        } else if (salary instanceof Map<?, ?> m) {
            payBasis = str(m, "payBasis", payBasis);
            salaryKind = str(m, "salaryKind", salaryKind);
            base = dec(m, "baseSalaryAmount", dec(m, "base", base));
            agreedNet = dec(m, "agreedNetAmount", dec(m, "agreedNet", agreedNet));
            si = dec(m, "siSalaryAmount", dec(m, "siSalary", si));
            scale = str(m, "payScaleCode", scale);
            step = str(m, "payScaleStepCode", step);
            probation = dec(m, "probationPct", probation);
            if (m.get("netGuaranteedScope") instanceof List<?> l) {
                scope = l.stream().map(String::valueOf).toList();
            }
        } else if (salary != null) {
            throw invalid("salary");
        }
        if (allowances instanceof List<?> l) {
            List<Allowance> out = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m) || m.get("componentCode") == null || m.get("amount") == null) {
                    throw invalid("allowances");
                }
                out.add(new Allowance(m.get("componentCode").toString(), dec(m, "amount", null),
                        Boolean.TRUE.equals(m.get("inSiSalary"))));
            }
            list = out;
        } else if (allowances != null) {
            throw invalid("allowances");
        }
        return new SalaryTerms(payBasis, salaryKind, base, agreedNet, scope, si, in.currencyCode(), scale, step,
                probation, list);
    }

    private static UUID uuid(Map<String, Object> c, String key) {
        Object v = c.get(key);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException ex) {
            throw invalid(key);
        }
    }

    private static String str(Map<?, ?> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : v.toString();
    }

    private static BigDecimal dec(Map<?, ?> m, String key, BigDecimal fallback) {
        Object v = m.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return decimal(n, key);
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException ex) {
            throw invalid(key);
        }
    }

    private static BigDecimal decimal(Number n, String key) {
        try {
            return new BigDecimal(n.toString());
        } catch (NumberFormatException ex) {
            throw invalid(key);
        }
    }

    private static ApiException invalid(String key) {
        return ApiException.unprocessable("invalid-appendix-change", "Appendix term " + key
                + " cannot be applied: unexpected format", Map.of("fields", List.of(key)));
    }
}
