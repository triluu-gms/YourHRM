package vn.yourhrm.people.internal.application.assignment;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.AssignmentEvents.WageProtection;
import vn.yourhrm.people.api.WorkingDayCalendar;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts.Employment;
import vn.yourhrm.people.internal.domain.assignment.WorkingDays;
import vn.yourhrm.people.internal.infra.assignment.AssignmentRow;
import vn.yourhrm.people.internal.infra.assignment.AssignmentStore;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;

/**
 * Art. 29 temporary transfer (Labour Code 2019; BR-PPL-035, US-PPL-040), all limits from legal parameters
 * ([verify-legal]): the cumulative working days of temporary transfers in a calendar year above the limit need the
 * employee's written consent (a Core document); the employee is told at least N working days before the start; the
 * wage-protection terms go to Payroll in {@code people.TemporaryTransferChanged}. Working days come from T&amp;A's
 * calendar ({@link WorkingDayCalendar}, documented fallback when T&amp;A is absent).
 */
@Component
public class TemporaryTransferPolicy {

    public record Check(int workingDaysInStartYear, int maxWorkingDaysPerYear, int noticeWorkingDays,
            boolean consentRequired, WageProtection wageProtection) {
    }

    private final PeopleLegalParameters legal;
    private final WorkingDayCalendar calendar;
    private final AssignmentStore store;
    private final Documents documents;

    public TemporaryTransferPolicy(PeopleLegalParameters legal, ObjectProvider<WorkingDayCalendar> calendar,
            AssignmentStore store, Documents documents) {
        this.legal = legal;
        // T&A implements the calendar; until then Monday-Saturday without holidays (documented stub).
        this.calendar = calendar.getIfAvailable(() -> WorkingDayCalendar.NONE);
        this.store = store;
        this.documents = documents;
    }

    /**
     * @param endInclusive the last day of the transfer
     * @param noticeDate   when the employee was told (defaults to today at the caller)
     */
    public Check check(Employment emp, UUID locationId, LocalDate start, LocalDate endInclusive, LocalDate noticeDate,
            UUID consentDocumentId) {
        if (endInclusive == null) {
            throw ApiException.unprocessable("temp-transfer-end-required",
                    "A temporary transfer needs its last day (endDate)");
        }
        if (endInclusive.isBefore(start)) {
            throw ApiException.unprocessable("invalid-period", "endDate is before the effective date");
        }
        if (!emp.covers(endInclusive)) {
            throw ApiException.unprocessable("outside-employment", "The transfer ends after the employment");
        }
        Scope scope = new Scope(emp.legalEntityId(), locationId);
        int max = legal.require(AssignmentLegalKeys.TEMP_TRANSFER_MAX_DAYS, scope, start).asInt();
        int notice = legal.require(AssignmentLegalKeys.TEMP_TRANSFER_NOTICE_DAYS, scope, start).asInt();
        var floor = legal.require(AssignmentLegalKeys.TEMP_TRANSFER_WAGE_FLOOR_PCT, scope, start).asDecimal();
        int keepDays = legal.require(AssignmentLegalKeys.TEMP_TRANSFER_PREVIOUS_WAGE_DAYS, scope, start).asInt();
        Predicate<LocalDate> working = d -> calendar.isWorkingDay(emp.legalEntityId(), locationId, d);

        int given = WorkingDays.noticeDays(noticeDate, start, working);
        if (given < notice) {
            throw ApiException.unprocessable("temp-transfer-notice-too-short", "The employee must be told at least "
                    + notice + " working days before a temporary transfer starts; " + given + " given",
                    Map.of("requiredWorkingDays", notice, "givenWorkingDays", given));
        }
        LocalDate endExclusive = endInclusive.plusDays(1);
        boolean consentRequired = false;
        int startYearTotal = 0;
        for (int year = start.getYear(); year <= endInclusive.getYear(); year++) {
            int total = WorkingDays.countInYear(start, endExclusive, year, working);
            for (AssignmentRow r : store.current(emp.id())) {
                if ("TEMP_TRANSFER".equals(r.assignmentType())) {
                    LocalDate to = r.validTo() == null ? LocalDate.of(year + 1, 1, 1) : r.validTo();
                    total += WorkingDays.countInYear(r.validFrom(), to, year, working);
                }
            }
            if (year == start.getYear()) {
                startYearTotal = total;
            }
            if (total > max) {
                consentRequired = true;
            }
        }
        if (consentRequired) {
            if (consentDocumentId == null) {
                throw ApiException.unprocessable("temp-transfer-consent-required", "Temporary transfers would total "
                        + startYearTotal + " working days in the year, more than " + max
                        + "; the employee's written consent (consentDocumentId) is required",
                        Map.of("maxWorkingDaysPerYear", max, "workingDaysInYear", startYearTotal));
            }
            try {
                documents.get(consentDocumentId);
            } catch (ApiException ex) {
                throw ApiException.unprocessable("unknown-consent-document", "Consent document "
                        + consentDocumentId + " does not exist or is not visible to you");
            }
        }
        return new Check(startYearTotal, max, notice, consentRequired, new WageProtection(keepDays, floor, true));
    }
}
