package vn.yourhrm.attendance.internal.application.schedule;

import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.code;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.invalid;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.stale;
import static vn.yourhrm.attendance.internal.application.schedule.ScheduleInputs.upperOrNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.RuleData;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.RuleRow;
import vn.yourhrm.attendance.internal.infra.schedule.ShiftPatternStore;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Schedule assignment rules (BR-ATT-015, US-ATT-010): criteria on the People assignment (legal entity, org unit,
 * location, job title, employee category, contractual working regime; null = any) mapped to a pattern for a validity
 * period, evaluated by {@code priority} (lower first, then code). Saving a rule regenerates the rule-based schedules of
 * the affected assignment lines in the same transaction, so the schedules always match the rules.
 *
 * <p>A rule restricted to a legal entity needs the permission's scope on it; a rule without one applies tenant-wide and
 * needs a TENANT-scoped grant.
 */
@Service
public class ScheduleRuleService {

    public record RuleInput(String code, Integer priority, UUID legalEntityId, UUID orgUnitId, UUID locationId,
            UUID jobTitleId, String employeeCategoryCode, String workingRegimeCode, UUID patternId,
            LocalDate validFrom, LocalDate validTo) {
    }

    public record RuleView(UUID id, String code, int priority, UUID legalEntityId, UUID orgUnitId, UUID locationId,
            UUID jobTitleId, String employeeCategoryCode, String workingRegimeCode, UUID patternId,
            LocalDate validFrom, LocalDate validTo, String status, int version) {

        static RuleView of(RuleRow r) {
            RuleData d = r.data();
            return new RuleView(r.id(), d.code(), d.priority(), d.legalEntityId(), d.orgUnitId(), d.locationId(),
                    d.jobTitleId(), d.employeeCategoryCode(), d.workingRegimeCode(), d.patternId(), d.validFrom(),
                    d.validTo(), r.status(), r.version());
        }
    }

    /** A saved rule and how many assignment lines got a different schedule. */
    public record RuleResult(RuleView rule, int linesChanged) {
    }

    private final ScheduleAssignmentStore store;
    private final ShiftPatternStore patterns;
    private final ScheduleGenerator generator;
    private final AuthorizationService authz;

    public ScheduleRuleService(ScheduleAssignmentStore store, ShiftPatternStore patterns,
            ScheduleGenerator generator, AuthorizationService authz) {
        this.store = store;
        this.patterns = patterns;
        this.generator = generator;
        this.authz = authz;
    }

    @AttTransactional(readOnly = true)
    public List<RuleView> list() {
        return store.rules().stream().map(RuleView::of).toList();
    }

    @AttTransactional(readOnly = true)
    public RuleView get(UUID id) {
        return RuleView.of(load(id));
    }

    @AttTransactional
    public RuleResult create(RuleInput in) {
        RuleData d = validate(in, code(in.code(), "rule"));
        requireScope(d.legalEntityId());
        UUID id = store.insertRule(d);
        RuleRow saved = load(id);
        return new RuleResult(RuleView.of(saved), regenerate(List.of(saved)));
    }

    @AttTransactional
    public RuleResult update(UUID id, RuleInput in, String status, int version) {
        RuleRow current = load(id);
        RuleData d = validate(in, current.data().code());
        requireScope(current.data().legalEntityId());
        requireScope(d.legalEntityId());
        String st = ScheduleInputs.status(status, current.status());
        if (!store.updateRule(id, d, st, version)) {
            throw stale("schedule rule");
        }
        RuleRow saved = load(id);
        return new RuleResult(RuleView.of(saved), regenerate(List.of(current, saved)));
    }

    /** Regenerates every assignment line of the legal entity (all when null), e.g. after a bulk projection load. */
    @AttTransactional
    public int regenerateAll(UUID legalEntityId) {
        requireScope(legalEntityId);
        List<RuleRow> rules = store.activeRules();
        int changed = 0;
        for (UUID key : store.lineKeys(legalEntityId)) {
            if (generator.regenerate(key, rules, "RULE")) {
                changed++;
            }
        }
        return changed;
    }

    private int regenerate(List<RuleRow> touched) {
        List<RuleRow> rules = store.activeRules();
        int changed = 0;
        for (UUID key : generator.linesAffectedBy(touched)) {
            if (generator.regenerate(key, rules, "RULE")) {
                changed++;
            }
        }
        return changed;
    }

    private RuleData validate(RuleInput in, String code) {
        int priority = in.priority() == null ? 100 : in.priority();
        if (priority < 0 || priority > 9999) {
            throw invalid("invalid-priority", "The priority lies between 0 and 9999 (lower is evaluated first)");
        }
        if (in.patternId() == null) {
            throw invalid("pattern-required", "The schedule pattern is required");
        }
        var pattern = patterns.pattern(in.patternId())
                .orElseThrow(() -> invalid("unknown-pattern", "Schedule pattern " + in.patternId() + " not found"));
        if (!"ACTIVE".equals(pattern.status())) {
            throw invalid("pattern-inactive", "Pattern " + pattern.data().code() + " is inactive");
        }
        if (in.validFrom() == null) {
            throw invalid("valid-from-required", "The start date is required");
        }
        if (in.validTo() != null && !in.validTo().isAfter(in.validFrom())) {
            throw invalid("invalid-period", "The end date (exclusive) must be after the start date");
        }
        if (in.orgUnitId() != null && in.legalEntityId() == null) {
            throw invalid("legal-entity-required", "A rule on an org unit also names the unit's legal entity");
        }
        return new RuleData(code, priority, in.legalEntityId(), in.orgUnitId(), in.locationId(), in.jobTitleId(),
                upperOrNull(in.employeeCategoryCode()), upperOrNull(in.workingRegimeCode()), in.patternId(),
                in.validFrom(), in.validTo());
    }

    private void requireScope(UUID legalEntityId) {
        boolean ok = legalEntityId == null
                ? authz.hasTenantWidePermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
                : authz.can(AttendancePermissions.SCHEDULE_CONFIG_UPDATE,
                        new AuthorizationTarget.LegalEntity(legalEntityId));
        if (!ok) {
            throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope", legalEntityId == null
                    ? "A rule for every legal entity needs a tenant-wide grant"
                    : "Schedule rules of legal entity " + legalEntityId + " are outside your data scope");
        }
    }

    private RuleRow load(UUID id) {
        return store.rule(id).orElseThrow(() -> ApiException.notFound("Schedule rule", id));
    }
}
