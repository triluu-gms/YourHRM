package vn.yourhrm.people.internal.application.separation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.api.WorkingDayCalendar;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.contract.ContractRecords;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.domain.separation.NoticePeriods;
import vn.yourhrm.people.internal.domain.separation.NoticePeriods.Category;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;

/**
 * Required notice and shortfall (BR-PPL-071, US-PPL-062): the side whose rules apply comes from the ground
 * ({@code notice_side}: Art. 35 for the employee, Art. 36 for the employer), the category from the contract in force on
 * the notice date (indefinite / definite 12-36 months / definite under 12 months / probation), the days and their basis
 * (calendar or working days) from {@code separation.notice.days}; special trades (Decree 145/2020 Art. 7) and the
 * no-notice cases (Art. 35.2 / 36.3) from their own parameters. The shortfall is the required minus the notice given
 * between the notice date and the termination date; its consequences (Art. 40 / 41) are codes from
 * {@code separation.notice.shortfall_consequences}. Working days use the {@link WorkingDayCalendar} SPI (documented
 * fallback without T&amp;A). Joins the caller's People transaction.
 */
@Component
public class NoticeCalculator {

    /** What the calculation needs. {@code specialTradeCode} / {@code exemptReasonCode} are optional. */
    public record Input(String noticeSide, UUID employmentId, UUID legalEntityId, UUID locationId, LocalDate noticeDate,
            LocalDate terminationDate, String specialTradeCode, String exemptReasonCode) {
    }

    /**
     * @param requiredDays null when no notice rule applies (side NONE)
     * @param consequences codes of the consequences of the shortfall (empty without shortfall)
     */
    public record Assessment(String side, String category, UUID contractId, String contractNo, String contractTypeCode,
            Integer requiredDays, String basis, Integer givenDays, int shortfallDays, String specialTradeCode,
            String exemptReasonCode, List<String> consequences) {

        public boolean forfeitsSeverance() {
            return consequences.contains(SeparationKeys.NO_SEVERANCE);
        }
    }

    private final PeopleLegalParameters legal;
    private final ContractRecords contracts;
    private final WorkingDayCalendar calendar;

    public NoticeCalculator(PeopleLegalParameters legal, ContractRecords contracts,
            ObjectProvider<WorkingDayCalendar> calendar) {
        this.legal = legal;
        this.contracts = contracts;
        // T&A implements the calendar; until then Monday-Saturday without holidays (documented stub)
        this.calendar = calendar.getIfAvailable(() -> WorkingDayCalendar.NONE);
    }

    public Assessment assess(Input in) {
        Scope scope = new Scope(in.legalEntityId(), in.locationId());
        if ("NONE".equals(in.noticeSide())) {
            if (in.exemptReasonCode() != null || in.specialTradeCode() != null) {
                throw ApiException.unprocessable("notice-not-applicable",
                        "The legal ground needs no notice; drop the no-notice reason / special trade");
            }
            return new Assessment("NONE", null, null, null, null, null, null, null, 0, null, null, List.of());
        }
        ContractView c = contractOn(in.employmentId(), in.noticeDate());
        int longMin = legal.require(SeparationKeys.NOTICE_LONG_MIN_MONTHS, scope, in.noticeDate()).asInt();
        Category category = c == null ? Category.NOT_APPLICABLE
                : NoticePeriods.category(c.contractTypeCode(), c.startDate(), c.endDate(), c.probationEndDate(),
                        in.noticeDate(), longMin);
        UUID contractId = c == null ? null : c.id();
        String contractNo = c == null ? null : c.contractNo();
        String type = c == null ? null : c.contractTypeCode();

        if (in.exemptReasonCode() != null) {
            Map<String, Object> exempt = legal.require(SeparationKeys.NOTICE_EXEMPT_REASONS, scope, in.noticeDate())
                    .asMap();
            Object codes = exempt.get(in.noticeSide());
            if (!(codes instanceof List<?> l) || !l.contains(in.exemptReasonCode())) {
                throw ApiException.unprocessable("invalid-notice-exemption", "No-notice reason "
                        + in.exemptReasonCode() + " is not a statutory case for the " + in.noticeSide()
                        + " side (" + SeparationKeys.NOTICE_EXEMPT_REASONS + ")",
                        Map.of("side", in.noticeSide(), "allowed", codes == null ? List.of() : codes));
            }
            return new Assessment(in.noticeSide(), category.name(), contractId, contractNo, type, 0, null,
                    given(in, NoticePeriods.CALENDAR), 0, in.specialTradeCode(), in.exemptReasonCode(), List.of());
        }
        if (category == Category.NOT_APPLICABLE) {
            // no labour contract in force (apprentice, intern, service agreement, none on file): no statutory notice
            return new Assessment(in.noticeSide(), category.name(), contractId, contractNo, type, 0, null,
                    given(in, NoticePeriods.CALENDAR), 0, null, null, List.of());
        }

        Integer required;
        String basis;
        Map<String, Object> rule = sideRule(scope, in.noticeSide(), category, in.noticeDate());
        basis = rule.get("basis") == null ? NoticePeriods.CALENDAR : rule.get("basis").toString();
        if (!NoticePeriods.CALENDAR.equals(basis) && !NoticePeriods.WORKING.equals(basis)) {
            throw ApiException.unprocessable("legal-parameter-invalid", SeparationKeys.NOTICE_DAYS + " basis must be "
                    + "CALENDAR or WORKING, got " + basis);
        }
        required = number(rule.get("days"), SeparationKeys.NOTICE_DAYS).intValue();
        if (in.specialTradeCode() != null && category != Category.PROBATION) {
            Map<String, Object> trades = legal.require(SeparationKeys.NOTICE_SPECIAL_TRADES, scope, in.noticeDate())
                    .asMap();
            if (!(trades.get(in.specialTradeCode()) instanceof Map<?, ?> trade)) {
                throw ApiException.unprocessable("invalid-special-trade", "Special trade " + in.specialTradeCode()
                        + " is not defined in " + SeparationKeys.NOTICE_SPECIAL_TRADES,
                        Map.of("allowed", List.copyOf(trades.keySet())));
            }
            if (trade.get(category.name()) instanceof Map<?, ?> t) {
                basis = NoticePeriods.CALENDAR;
                if (t.get("days") != null) {
                    required = number(t.get("days"), SeparationKeys.NOTICE_SPECIAL_TRADES).intValue();
                } else if (t.get("term_fraction") != null && c.endDate() != null) {
                    required = NoticePeriods.fractionOfTerm(c.startDate(), c.endDate(),
                            new BigDecimal(t.get("term_fraction").toString()));
                }
            }
        }
        int givenDays = given(in, basis);
        int shortfall = Math.max(0, required - givenDays);
        List<String> consequences = List.of();
        if (shortfall > 0) {
            Object list = legal.require(SeparationKeys.NOTICE_SHORTFALL_CONSEQUENCES, scope, in.noticeDate()).asMap()
                    .get(in.noticeSide());
            consequences = list instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of();
        }
        return new Assessment(in.noticeSide(), category.name(), contractId, contractNo, type, required, basis,
                givenDays, shortfall, in.specialTradeCode(), null, consequences);
    }

    private Map<String, Object> sideRule(Scope scope, String side, Category category, LocalDate on) {
        Map<String, Object> days = legal.require(SeparationKeys.NOTICE_DAYS, scope, on).asMap();
        if (days.get(side) instanceof Map<?, ?> bySide && bySide.get(category.name()) instanceof Map<?, ?> r) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rule = (Map<String, Object>) r;
            return rule;
        }
        throw ApiException.unprocessable("legal-parameter-missing", "Legal parameter " + SeparationKeys.NOTICE_DAYS
                + " has no rule for side " + side + " and category " + category,
                Map.of("parameter", SeparationKeys.NOTICE_DAYS, "side", side, "category", category.name()));
    }

    private int given(Input in, String basis) {
        return NoticePeriods.given(in.noticeDate(), in.terminationDate(), basis,
                d -> calendar.isWorkingDay(in.legalEntityId(), in.locationId(), d));
    }

    /** The contract in force (or once in force) on {@code date}: the latest one starting on or before it. */
    private ContractView contractOn(UUID employmentId, LocalDate date) {
        return contracts.liveOfEmployment(employmentId).stream()
                .filter(c -> !c.startDate().isAfter(date))
                .filter(c -> c.endDate() == null || !c.endDate().isBefore(date))
                .findFirst().orElse(null);
    }

    private static Number number(Object o, String key) {
        if (o instanceof Number n) {
            return n;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw ApiException.unprocessable("legal-parameter-invalid", key + " must hold a number of days, got " + o);
        }
    }
}
