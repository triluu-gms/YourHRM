package vn.yourhrm.people.internal.infra.assignment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;

/**
 * One version of {@code hr.assignment} (bi-temporal, DESIGN §5.2). {@code costSplit} is empty for "100 % on the
 * primary cost centre". Transaction-time columns are read-only here: the store sets them.
 */
public record AssignmentRow(UUID id, UUID assignmentKey, UUID employmentId, String assignmentType, UUID legalEntityId,
        UUID payingLegalEntityId, UUID positionId, UUID orgUnitId, UUID jobTitleId, UUID gradeId,
        UUID managerEmploymentId, UUID locationId, UUID costCentreId, List<CostShare> costSplit, BigDecimal fte,
        String actionCode, String reasonCode, UUID decisionId, LocalDate validFrom, LocalDate validTo, String source,
        Instant recordedAt, Instant supersededAt, UUID supersededBy, String correctionReason) {

    public AssignmentRow {
        costSplit = costSplit == null ? List.of() : List.copyOf(costSplit);
    }

    public AssignmentRow withId(UUID newId) {
        return new AssignmentRow(newId, assignmentKey, employmentId, assignmentType, legalEntityId,
                payingLegalEntityId, positionId, orgUnitId, jobTitleId, gradeId, managerEmploymentId, locationId,
                costCentreId, costSplit, fte, actionCode, reasonCode, decisionId, validFrom, validTo, source, null,
                null, null, correctionReason);
    }

    public AssignmentRow withPeriod(LocalDate from, LocalDate to) {
        return new AssignmentRow(id, assignmentKey, employmentId, assignmentType, legalEntityId, payingLegalEntityId,
                positionId, orgUnitId, jobTitleId, gradeId, managerEmploymentId, locationId, costCentreId, costSplit,
                fte, actionCode, reasonCode, decisionId, from, to, source, recordedAt, supersededAt, supersededBy,
                correctionReason);
    }

    public AssignmentRow withDecision(UUID decision) {
        return new AssignmentRow(id, assignmentKey, employmentId, assignmentType, legalEntityId, payingLegalEntityId,
                positionId, orgUnitId, jobTitleId, gradeId, managerEmploymentId, locationId, costCentreId, costSplit,
                fte, actionCode, reasonCode, decision, validFrom, validTo, source, recordedAt, supersededAt,
                supersededBy, correctionReason);
    }

    public AssignmentRow withCorrectionReason(String reason) {
        return new AssignmentRow(id, assignmentKey, employmentId, assignmentType, legalEntityId, payingLegalEntityId,
                positionId, orgUnitId, jobTitleId, gradeId, managerEmploymentId, locationId, costCentreId, costSplit,
                fte, actionCode, reasonCode, decisionId, validFrom, validTo, source, recordedAt, supersededAt,
                supersededBy, reason);
    }

    public boolean covers(LocalDate d) {
        return !d.isBefore(validFrom) && (validTo == null || d.isBefore(validTo));
    }
}
