package vn.yourhrm.people.internal.application.assignment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.core.api.MinWageRegions;
import vn.yourhrm.people.api.AssignmentEvents.CostShare;
import vn.yourhrm.people.api.OrgStructureLookup;
import vn.yourhrm.people.internal.application.assignment.AssignmentService.Attributes;
import vn.yourhrm.people.internal.application.assignment.EmploymentFacts.Employment;
import vn.yourhrm.people.internal.domain.assignment.CostSplit;
import vn.yourhrm.people.internal.domain.assignment.ManagerResolution;
import vn.yourhrm.people.internal.infra.assignment.AssignmentStore;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Validates the soft references of an assignment version against their owners on the effective date (BR-PPL-031,
 * US-PPL-033/034): job title, grade and cost centres through Core's {@link JobCatalogue}, the work location through
 * {@link MinWageRegions} (a location without a version on that date does not exist then), org unit and position
 * through {@link OrgStructureLookup} when Core provides it, and the line manager (named, or derived from the manager
 * position's incumbent).
 */
@Component
public class AssignmentReferences {

    /** The attributes as they will be stored: manager and primary cost centre resolved, FTE defaulted. */
    public record Resolved(Attributes attributes, boolean managerDerived, List<Warning> warnings) {
    }

    private final JobCatalogue jobs;
    private final MinWageRegions regions;
    private final OrgStructureLookup org;
    private final AssignmentStore store;
    private final EmploymentFacts employments;

    public AssignmentReferences(JobCatalogue jobs, MinWageRegions regions, ObjectProvider<OrgStructureLookup> org,
            AssignmentStore store, EmploymentFacts employments) {
        this.jobs = jobs;
        this.regions = regions;
        // Core does not publish an org / position lookup yet: documented stub (references unvalidated).
        this.org = org.getIfAvailable(() -> OrgStructureLookup.NONE);
        this.store = store;
        this.employments = employments;
    }

    public Resolved resolve(Employment emp, Attributes a, LocalDate date) {
        List<Warning> warnings = new ArrayList<>();
        UUID le = emp.legalEntityId();
        if (a.orgUnitId() == null) {
            throw ApiException.unprocessable("org-unit-required", "An assignment needs an org unit");
        }
        BigDecimal fte = a.fte() == null ? BigDecimal.ONE : a.fte();
        if (fte.signum() <= 0 || fte.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.unprocessable("invalid-fte", "FTE must be greater than 0 and at most 1");
        }
        if (org.available()) {
            var unit = org.orgUnit(a.orgUnitId(), date).orElseThrow(() -> ApiException.unprocessable(
                    "unknown-org-unit", "Org unit " + a.orgUnitId() + " does not exist on " + date));
            if (unit.legalEntityId() != null && !unit.legalEntityId().equals(le)) {
                throw ApiException.unprocessable("org-unit-other-legal-entity", "Org unit " + a.orgUnitId()
                        + " belongs to another legal entity than the employment");
            }
            if (a.positionId() != null) {
                var pos = org.position(a.positionId(), date).orElseThrow(() -> ApiException.unprocessable(
                        "unknown-position", "Position " + a.positionId() + " does not exist on " + date));
                if (pos.legalEntityId() != null && !pos.legalEntityId().equals(le)) {
                    throw ApiException.unprocessable("position-other-legal-entity", "Position " + a.positionId()
                            + " belongs to another legal entity than the employment");
                }
                if (pos.jobTitleId() != null && a.jobTitleId() != null && !pos.jobTitleId().equals(a.jobTitleId())) {
                    warnings.add(new Warning("job-title-differs-from-position",
                            "The job title differs from the position's job title"));
                }
            }
        } else if (a.positionId() != null) {
            warnings.add(new Warning("position-unvalidated",
                    "Positions cannot be validated yet (Core position lookup not available)"));
        }
        if (a.jobTitleId() != null) {
            var title = jobs.jobTitles(List.of(a.jobTitleId())).get(a.jobTitleId());
            if (title == null || !title.active()) {
                throw ApiException.unprocessable("unknown-job-title", "Job title " + a.jobTitleId()
                        + " does not exist or is inactive");
            }
        }
        if (a.gradeId() != null && !jobs.grades(List.of(a.gradeId()), date).containsKey(a.gradeId())) {
            throw ApiException.unprocessable("unknown-grade", "Grade " + a.gradeId() + " is not valid on " + date);
        }
        if (a.locationId() != null) {
            var loc = regions.forLocation(a.locationId(), date).orElseThrow(() -> ApiException.unprocessable(
                    "unknown-location", "Work location " + a.locationId() + " does not exist on " + date));
            if (loc.legalEntityId() != null && !loc.legalEntityId().equals(le)) {
                throw ApiException.unprocessable("location-other-legal-entity", "Work location " + a.locationId()
                        + " belongs to another legal entity than the employment");
            }
        }
        List<CostShare> split = a.costSplit() == null ? List.of() : a.costSplit();
        CostSplit.problem(split).ifPresent(p -> {
            throw ApiException.unprocessable("invalid-cost-split", "Cost-centre split: " + p);
        });
        UUID costCentre = a.costCentreId();
        if (!split.isEmpty()) {
            if (costCentre == null) {
                costCentre = CostSplit.primary(split);
            } else {
                UUID cc = costCentre;
                if (split.stream().noneMatch(s -> s.costCentreId().equals(cc))) {
                    throw ApiException.unprocessable("invalid-cost-split",
                            "The primary cost centre must be one of the split's cost centres");
                }
            }
        }
        Set<UUID> centres = new LinkedHashSet<>();
        if (costCentre != null) {
            centres.add(costCentre);
        }
        split.forEach(s -> centres.add(s.costCentreId()));
        if (!centres.isEmpty()) {
            Map<UUID, JobCatalogue.CostCentre> found = jobs.costCentres(centres, date);
            for (UUID id : centres) {
                JobCatalogue.CostCentre c = found.get(id);
                if (c == null) {
                    throw ApiException.unprocessable("unknown-cost-centre", "Cost centre " + id
                            + " is not valid on " + date);
                }
                if (c.legalEntityId() != null && !c.legalEntityId().equals(le)) {
                    throw ApiException.unprocessable("cost-centre-other-legal-entity", "Cost centre " + c.code()
                            + " belongs to another legal entity");
                }
            }
        }
        UUID manager = a.managerEmploymentId();
        boolean derived = false;
        if (manager != null) {
            if (manager.equals(emp.id())) {
                throw ApiException.unprocessable("invalid-manager", "An employee cannot be their own line manager");
            }
            var m = employments.find(manager).orElseThrow(() -> ApiException.unprocessable("invalid-manager",
                    "Line manager employment " + a.managerEmploymentId() + " does not exist"));
            if ("CANCELLED".equals(m.status()) || !m.covers(date)) {
                throw ApiException.unprocessable("invalid-manager", "Line manager employment " + m.employeeNo()
                        + " is not employed on " + date);
            }
        } else if (a.positionId() != null && org.available()) {
            Optional<UUID> found = ManagerResolution.fromPosition(a.positionId(),
                    p -> org.position(p, date).map(OrgStructureLookup.PositionRef::reportsToPositionId),
                    p -> store.incumbent(p, date), emp.id());
            if (found.isPresent()) {
                manager = found.get();
                derived = true;
            } else {
                warnings.add(new Warning("manager-not-derived",
                        "No incumbent found up the position hierarchy; the assignment has no line manager"));
            }
        }
        Attributes resolved = new Attributes(a.orgUnitId(), a.positionId(), a.jobTitleId(), a.gradeId(), manager,
                a.locationId(), costCentre, split, fte);
        return new Resolved(resolved, derived, warnings);
    }
}
