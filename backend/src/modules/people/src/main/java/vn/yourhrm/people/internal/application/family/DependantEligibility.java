package vn.yourhrm.people.internal.application.family;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.family.FamilyMemberService.Member;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;

/**
 * PIT dependant eligibility (BR-PPL-016, US-PPL-028, C17; Circular 111/2013 Art. 9 as summarised by the BRD,
 * {@code [verify-legal]}). Evaluated for EVERY month of the claim with the legal parameters in effect in that month:
 * <ul>
 *   <li>the relationship must belong to a category of {@link FamilyLegalKeys#RELATIONSHIP_CATEGORIES} matching the
 *       claimed reason (CHILD_* reasons need CHILD; SPOUSE, PARENT, OTHER_SUPPORTED their category);</li>
 *   <li>CHILD_UNDER_AGE: born, and under {@link FamilyLegalKeys#CHILD_MAX_AGE} (the month of that birthday still
 *       counts); the claim needs an end month no later than that month;</li>
 *   <li>CHILD_STUDENT (from that age, studying: proven by the evidence), CHILD_DISABLED (disabled, unable to work),
 *       SPOUSE, PARENT, OTHER_SUPPORTED: no income above {@link FamilyLegalKeys#INCOME_MONTHLY_THRESHOLD} (the
 *       recorded income status must be known);</li>
 *   <li>a deceased family member cannot be registered.</li>
 * </ul>
 * An open-ended claim is evaluated for its first month and, for an age-limited reason, refused without an end month.
 */
@Component
public class DependantEligibility {

    public static final Set<String> REASONS = Set.of("CHILD_UNDER_AGE", "CHILD_STUDENT", "CHILD_DISABLED", "SPOUSE",
            "PARENT", "OTHER_SUPPORTED");

    /** One failed rule; {@code month} is the first month it fails in, when month-specific. */
    public record Violation(String code, String message, LocalDate month) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("message", message);
            if (month != null) {
                m.put("month", month.toString());
            }
            return m;
        }
    }

    private final PeopleLegalParameters legal;

    public DependantEligibility(PeopleLegalParameters legal) {
        this.legal = legal;
    }

    /** The violated rules (empty = eligible). {@code fromMonth}/{@code toMonth} are first days of months. */
    public List<Violation> check(Member m, String reason, LocalDate fromMonth, LocalDate toMonth) {
        List<Violation> out = new ArrayList<>();
        if (!REASONS.contains(reason)) {
            out.add(new Violation("invalid-reason", "Unknown dependant reason " + reason, null));
            return out;
        }
        if (m.deceased()) {
            out.add(new Violation("deceased", "A deceased family member cannot be registered as a dependant", null));
            return out;
        }
        YearMonth first = YearMonth.from(fromMonth);
        YearMonth last = toMonth == null ? first : YearMonth.from(toMonth);
        String requiredCategory = reason.startsWith("CHILD_") ? "CHILD" : "OTHER_SUPPORTED".equals(reason) ? "OTHER"
                : reason;
        for (YearMonth ym = first; !ym.isAfter(last); ym = ym.plusMonths(1)) {
            LocalDate month = ym.atDay(1);
            Violation v = monthViolation(m, reason, requiredCategory, ym, month);
            if (v != null) {
                out.add(v);
                return out;
            }
        }
        if ("CHILD_UNDER_AGE".equals(reason) && m.birthDate() != null) {
            int maxAge = legal.require(FamilyLegalKeys.CHILD_MAX_AGE, Scope.tenant(), fromMonth).asInt();
            YearMonth lastEligible = YearMonth.from(m.birthDate().plusYears(maxAge));
            if (toMonth == null) {
                out.add(new Violation("end-month-required", "A child under " + maxAge
                        + " is a dependant until " + lastEligible + " at the latest; give that end month",
                        lastEligible.atDay(1)));
            }
        }
        return out;
    }

    private Violation monthViolation(Member m, String reason, String requiredCategory, YearMonth ym, LocalDate month) {
        Map<String, Object> categories = legal.require(FamilyLegalKeys.RELATIONSHIP_CATEGORIES, Scope.tenant(), month)
                .asMap();
        Object codes = categories.get(requiredCategory);
        if (!(codes instanceof List<?> list) || !list.contains(m.relationshipCode())) {
            return new Violation("relationship-not-eligible", "Relationship " + m.relationshipCode()
                    + " does not allow the dependant reason " + reason, month);
        }
        switch (reason) {
            case "CHILD_UNDER_AGE" -> {
                if (m.birthDate() == null) {
                    return new Violation("birth-date-required", "The child's birth date is required", null);
                }
                if (ym.isBefore(YearMonth.from(m.birthDate()))) {
                    return new Violation("before-birth", "The claim starts before the child's birth", month);
                }
                int maxAge = legal.require(FamilyLegalKeys.CHILD_MAX_AGE, Scope.tenant(), month).asInt();
                if (ym.isAfter(YearMonth.from(m.birthDate().plusYears(maxAge)))) {
                    return new Violation("child-over-age", "The child is " + maxAge
                            + " or older in " + ym + "; register as student or disabled if applicable", month);
                }
            }
            case "CHILD_DISABLED" -> {
                if (!m.disabled()) {
                    return new Violation("not-disabled", "The family member is not recorded as disabled", null);
                }
                Violation income = income(m, month);
                if (income != null) {
                    return income;
                }
            }
            default -> {
                Violation income = income(m, month);
                if (income != null) {
                    return income;
                }
            }
        }
        return null;
    }

    private Violation income(Member m, LocalDate month) {
        // the threshold must exist for the month even though only the recorded status is compared (fail closed)
        legal.require(FamilyLegalKeys.INCOME_MONTHLY_THRESHOLD, Scope.tenant(), month);
        if (m.hasIncomeAboveThreshold() == null) {
            return new Violation("income-status-required", "The family member's income status is not recorded", null);
        }
        if (m.hasIncomeAboveThreshold()) {
            return new Violation("income-above-threshold",
                    "The family member has an average monthly income above the threshold", month);
        }
        return null;
    }
}
