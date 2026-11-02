package vn.yourhrm.people.internal.application.salary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.MinWageRegions;
import vn.yourhrm.people.api.CompensationCatalogue;
import vn.yourhrm.people.api.GrossUpSimulator;
import vn.yourhrm.people.api.SalaryEvents.Allowance;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts.Employment;
import vn.yourhrm.people.internal.application.assignment.Warning;
import vn.yourhrm.people.internal.application.salary.SalaryService.SalaryTerms;
import vn.yourhrm.people.internal.infra.assignment.AssignmentRow;
import vn.yourhrm.people.internal.infra.assignment.AssignmentStore;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;

/**
 * Rules of an agreed-pay version (BR-PPL-040/041, US-PPL-041/043). Structure: GROSS / NET, the agreed net required
 * for NET, the net-guaranteed scope only for NET, VND only (R2-09), Payroll's component and pay-scale codes. Business
 * checks: below the regional minimum wage of the work location on the effective date (or of the legal entity when the
 * assignment has no location) BLOCKS, probation pay included; outside Payroll's pay band WARNS. A NET record is checked
 * on the gross from Payroll's simulator (fallback documented in {@link GrossUpSimulator}).
 */
@Component
public class SalaryRules {

    static final Set<String> BASES = Set.of("GROSS", "NET");
    static final Set<String> KINDS = Set.of("MONTHLY", "DAILY", "HOURLY", "PIECE");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PeopleLegalParameters legal;
    private final MinWageRegions regions;
    private final CompensationCatalogue catalogue;
    private final GrossUpSimulator simulator;
    private final AssignmentStore assignments;

    public SalaryRules(PeopleLegalParameters legal, MinWageRegions regions,
            ObjectProvider<CompensationCatalogue> catalogue, ObjectProvider<GrossUpSimulator> simulator,
            AssignmentStore assignments) {
        this.legal = legal;
        this.regions = regions;
        // Payroll implements both; until then: any code accepted / no band, and no gross-up (documented stubs).
        this.catalogue = catalogue.getIfAvailable(() -> CompensationCatalogue.NONE);
        this.simulator = simulator.getIfAvailable(() -> GrossUpSimulator.NONE);
        this.assignments = assignments;
    }

    /** Normalises defaults (MONTHLY, VND) and checks the record's structure; 422 on the first problem. */
    public SalaryTerms structure(SalaryTerms t, LocalDate asOf) {
        String basis = t.payBasis() == null ? "GROSS" : t.payBasis();
        String kind = t.salaryKind() == null ? "MONTHLY" : t.salaryKind();
        String currency = t.currencyCode() == null ? "VND" : t.currencyCode();
        if (!BASES.contains(basis)) {
            throw ApiException.unprocessable("invalid-pay-basis", "payBasis must be GROSS or NET");
        }
        if (!KINDS.contains(kind)) {
            throw ApiException.unprocessable("invalid-salary-kind", "salaryKind must be one of " + KINDS);
        }
        if (!"VND".equals(currency)) {
            throw ApiException.unprocessable("currency-not-supported", "Agreed pay is in VND in Phase 1 (R2-09)");
        }
        if (t.baseSalaryAmount() == null || t.baseSalaryAmount().signum() <= 0) {
            throw ApiException.unprocessable("base-salary-required", "The base salary must be greater than 0");
        }
        if ("NET".equals(basis) && t.agreedNetAmount() == null) {
            throw ApiException.unprocessable("agreed-net-required", "A NET salary record needs the agreed net amount");
        }
        if (!"NET".equals(basis) && t.agreedNetAmount() != null) {
            throw ApiException.unprocessable("agreed-net-only-for-net", "The agreed net amount is for NET records only");
        }
        if (!"NET".equals(basis) && t.netGuaranteedScope() != null && !t.netGuaranteedScope().isEmpty()) {
            throw ApiException.unprocessable("net-scope-only-for-net",
                    "The net-guaranteed scope is for NET records only");
        }
        for (BigDecimal amount : new BigDecimal[] {t.agreedNetAmount(), t.siSalaryAmount()}) {
            if (amount != null && amount.signum() < 0) {
                throw ApiException.unprocessable("invalid-amount", "Amounts cannot be negative");
            }
        }
        if (t.probationPct() != null && (t.probationPct().signum() <= 0 || t.probationPct().compareTo(HUNDRED) > 0)) {
            throw ApiException.unprocessable("invalid-probation-pct", "probationPct must be greater than 0 and at most 100");
        }
        if (t.payScaleStepCode() != null && t.payScaleCode() == null) {
            throw ApiException.unprocessable("pay-scale-required", "A pay-scale step needs its pay scale");
        }
        if (t.payScaleCode() != null && !catalogue.isPayScale(t.payScaleCode(), t.payScaleStepCode(), asOf)) {
            throw ApiException.unprocessable("unknown-pay-scale", "Pay scale " + t.payScaleCode()
                    + (t.payScaleStepCode() == null ? "" : " step " + t.payScaleStepCode()) + " is not defined");
        }
        List<Allowance> allowances = t.allowances() == null ? List.of() : t.allowances();
        Set<String> codes = new HashSet<>();
        for (Allowance a : allowances) {
            if (a.componentCode() == null || a.componentCode().isBlank() || a.amount() == null
                    || a.amount().signum() < 0) {
                throw ApiException.unprocessable("invalid-allowance",
                        "Every allowance needs a component code and an amount of at least 0");
            }
            if (!codes.add(a.componentCode())) {
                throw ApiException.unprocessable("invalid-allowance", "Allowance " + a.componentCode()
                        + " appears twice");
            }
            if (!catalogue.isAllowanceComponent(a.componentCode(), asOf)) {
                throw ApiException.unprocessable("unknown-component", "Salary component " + a.componentCode()
                        + " is not an allowance component of Payroll");
            }
        }
        List<String> scope = "NET".equals(basis) ? t.netGuaranteedScope() : null;
        return new SalaryTerms(basis, kind, t.baseSalaryAmount(), t.agreedNetAmount(), scope, t.siSalaryAmount(),
                currency, t.payScaleCode(), t.payScaleStepCode(), t.probationPct(), allowances);
    }

    /** Minimum-wage block and band warning on {@code date}; returns the warnings. */
    public List<Warning> business(Employment emp, SalaryTerms t, LocalDate date) {
        List<Warning> warnings = new ArrayList<>();
        AssignmentRow primary = assignments.primaryOn(emp.id(), date).orElse(null);
        UUID location = primary == null ? null : primary.locationId();
        BigDecimal gross = t.baseSalaryAmount();
        if ("NET".equals(t.payBasis())) {
            var simulated = simulator.grossForNet(emp.id(), t.agreedNetAmount(),
                    t.netGuaranteedScope() == null ? List.of() : t.netGuaranteedScope(), date);
            if (simulated.isPresent()) {
                gross = simulated.get();
            } else {
                warnings.add(new Warning("gross-up-unavailable", "Payroll's gross-up simulator is not available: the "
                        + "minimum wage was checked on the contractual base salary instead of the simulated gross"));
            }
        }
        String key = switch (t.salaryKind()) {
            case "MONTHLY" -> SalaryLegalKeys.MIN_WAGE_MONTHLY;
            case "HOURLY" -> SalaryLegalKeys.MIN_WAGE_HOURLY;
            default -> null;
        };
        Scope scope = new Scope(emp.legalEntityId(), location);
        if (key == null) {
            warnings.add(new Warning("minimum-wage-not-checked", "The minimum wage is not checked for "
                    + t.salaryKind() + " pay"));
        } else {
            var resolution = (location != null ? regions.forLocation(location, date)
                    : regions.forLegalEntity(emp.legalEntityId(), date)).orElse(null);
            if (resolution == null || resolution.region() == null) {
                throw ApiException.unprocessable("min-wage-region-unknown", "The minimum-wage region of the work "
                        + "location on " + date + " cannot be determined; the salary cannot be checked");
            }
            Map<String, Object> table = legal.require(key, scope, date).asMap();
            Object min = table.get(resolution.region().name());
            if (min == null) {
                throw ApiException.unprocessable("legal-parameter-missing", "Legal parameter " + key
                        + " has no value for region " + resolution.region());
            }
            BigDecimal minimum = new BigDecimal(min.toString());
            if (gross.compareTo(minimum) < 0) {
                throw ApiException.unprocessable("below-minimum-wage", "The salary is below the regional minimum wage"
                        + " of region " + resolution.region() + " on " + date,
                        Map.of("region", resolution.region().name()));
            }
            if (t.probationPct() != null) {
                BigDecimal floor = legal.require(SalaryLegalKeys.PROBATION_MIN_PCT, scope, date).asDecimal();
                if (t.probationPct().compareTo(floor) < 0) {
                    throw ApiException.unprocessable("probation-pct-below-floor", "Probation pay must be at least "
                            + floor.stripTrailingZeros().toPlainString() + " % of the salary");
                }
                BigDecimal probationPay = gross.multiply(t.probationPct()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                if (probationPay.compareTo(minimum) < 0) {
                    throw ApiException.unprocessable("probation-below-minimum-wage", "Probation pay is below the"
                            + " regional minimum wage of region " + resolution.region() + " on " + date,
                            Map.of("region", resolution.region().name()));
                }
            }
        }
        UUID grade = primary == null ? null : primary.gradeId();
        catalogue.band(t.payScaleCode(), t.payScaleStepCode(), grade, date).ifPresent(band -> {
            if (band.min() != null && t.baseSalaryAmount().compareTo(band.min()) < 0) {
                warnings.add(new Warning("outside-pay-band", "The base salary is below the pay band minimum ("
                        + band.source() + ")"));
            } else if (band.max() != null && t.baseSalaryAmount().compareTo(band.max()) > 0) {
                warnings.add(new Warning("outside-pay-band", "The base salary is above the pay band maximum ("
                        + band.source() + ")"));
            }
        });
        return warnings;
    }
}
