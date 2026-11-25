package vn.yourhrm.attendance.internal.application.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.yourhrm.attendance.api.ScheduleAssignmentChanged;
import vn.yourhrm.attendance.internal.domain.schedule.DateRange;
import vn.yourhrm.attendance.internal.domain.schedule.RuleSegments;
import vn.yourhrm.attendance.internal.domain.schedule.RuleSegments.Segment;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore;
import vn.yourhrm.attendance.internal.infra.holiday.HolidayCalendarStore.PeriodRef;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.AssignmentRow;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.Classification;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.LineVersion;
import vn.yourhrm.attendance.internal.infra.schedule.ScheduleAssignmentStore.RuleRow;
import vn.yourhrm.people.api.IntegrationInbox;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Regenerates the rule-based schedule assignments ({@code source = RULE}) of one assignment line from the current
 * rules, the line's People versions, its manual rows and the employment period (BR-ATT-015). Deterministic and
 * idempotent: rows that are still right are kept, rows no longer produced are cancelled (kept as history), new
 * segments are inserted. Runs in the caller's T&amp;A transaction.
 *
 * <ul>
 *   <li>When T&amp;A knows the employment ({@code att.ext_hr_employment}), rule schedules are clipped to
 *       {@code [hire date, termination date]} (BR-ATT-039: no schedule outside the employment). A cancelled hire
 *       drops every schedule of the line, manual ones included (People {@code HireCancelled}).</li>
 *   <li>Every change writes {@code att.ScheduleAssignmentChanged} for the changed days in the same transaction
 *       (recalculation trigger of BR-ATT-038), flagged retro when it touches a CLOSED / LOCKED period.</li>
 *   <li>After commit, People's onboarding readiness item WORK_SCHEDULE is told whether the employment has a
 *       schedule (IN-30, US-ATT-010); a failure there is logged and never undoes the schedule change.</li>
 * </ul>
 */
@Component
class ScheduleGenerator {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGenerator.class);

    private final ScheduleAssignmentStore store;
    private final HolidayCalendarStore periods;
    private final AttOutbox outbox;
    private final ObjectProvider<IntegrationInbox> peopleInbox;
    private final BusinessClock clock;

    ScheduleGenerator(ScheduleAssignmentStore store, HolidayCalendarStore periods, AttOutbox outbox,
            ObjectProvider<IntegrationInbox> peopleInbox, BusinessClock clock) {
        this.store = store;
        this.periods = periods;
        this.outbox = outbox;
        this.peopleInbox = peopleInbox;
        this.clock = clock;
    }

    /** @return true when a schedule assignment of the line was added or cancelled */
    boolean regenerate(UUID assignmentKey, String cause) {
        return regenerate(assignmentKey, store.activeRules(), cause, List.of());
    }

    boolean regenerate(UUID assignmentKey, List<RuleRow> activeRules, String cause) {
        return regenerate(assignmentKey, activeRules, cause, List.of());
    }

    /**
     * @param alreadyChanged ranges the caller changed itself before (e.g. a manual row), reported in the same event
     */
    boolean regenerate(UUID assignmentKey, List<RuleRow> activeRules, String cause, List<DateRange> alreadyChanged) {
        List<LineVersion> versions = store.lineVersions(assignmentKey);
        List<AssignmentRow> existing = store.activeAssignmentsOfKey(assignmentKey);
        UUID employmentId = !versions.isEmpty() ? versions.getFirst().employmentId()
                : existing.isEmpty() ? null : existing.getFirst().employmentId();
        Optional<EmploymentFacts> employment = employmentId == null ? Optional.empty()
                : store.employmentFacts(employmentId);
        boolean cancelledHire = employment.map(e -> "CANCELLED".equals(e.status())).orElse(false);

        List<DateRange> changed = new ArrayList<>(alreadyChanged);
        List<Segment> desired;
        if (cancelledHire) {
            desired = List.of();
            for (AssignmentRow a : existing) {
                store.cancelAssignment(a.id());
                changed.add(range(a));
            }
            existing = List.of();
        } else {
            List<DateRange> manual = existing.stream().filter(a -> !"RULE".equals(a.source()))
                    .map(ScheduleGenerator::range).toList();
            List<Classification> classes = employmentId == null ? List.of() : store.classifications(employmentId);
            desired = clip(RuleSegments.generate(split(versions, classes),
                    activeRules.stream().map(ScheduleGenerator::rule).toList(), manual), employment);
        }

        Set<Segment> wanted = new HashSet<>(desired);
        Set<Segment> kept = new HashSet<>();
        for (AssignmentRow a : existing) {
            if (!"RULE".equals(a.source())) {
                continue;
            }
            Segment s = new Segment(a.ruleId(), a.patternId(), range(a));
            if (wanted.contains(s) && kept.add(s)) {
                continue;
            }
            store.cancelAssignment(a.id());
            changed.add(s.validity());
        }
        for (Segment s : desired) {
            if (!kept.contains(s)) {
                store.insertAssignment(employmentId, assignmentKey, s.patternId(), s.ruleId(), s.validity().from(),
                        s.validity().to(), "RULE");
                changed.add(s.validity());
            }
        }
        if (changed.isEmpty() || employmentId == null) {
            return false;
        }
        publish(employmentId, assignmentKey, versions, employment, cause, changed);
        notifyReadinessAfterCommit(employmentId);
        return true;
    }

    private void publish(UUID employmentId, UUID assignmentKey, List<LineVersion> versions,
            Optional<EmploymentFacts> employment, String cause, List<DateRange> changed) {
        LocalDate from = changed.stream().map(DateRange::from).min(LocalDate::compareTo).orElseThrow();
        boolean open = changed.stream().anyMatch(r -> r.to() == null);
        LocalDate to = open ? null
                : changed.stream().map(DateRange::to).max(LocalDate::compareTo).orElseThrow().minusDays(1);
        UUID le = !versions.isEmpty() ? versions.getLast().legalEntityId()
                : employment.map(EmploymentFacts::legalEntityId).orElse(null);
        List<UUID> frozen = le == null ? List.of()
                : periods.frozenPeriods(List.of(le), from, to).stream().map(PeriodRef::id).toList();
        LocalDate probe = from.isAfter(clock.today()) ? from : clock.today();
        boolean scheduled = store.activeAssignmentsOfKey(assignmentKey).stream().anyMatch(a -> a.covers(probe));
        outbox.publish(new ScheduleAssignmentChanged(TenantContext.current().tenantId(), le, employmentId,
                assignmentKey, cause, from, to, scheduled, frozen, !frozen.isEmpty()));
    }

    /** Rule segments limited to the employment period, when T&amp;A knows it. */
    private static List<Segment> clip(List<Segment> segments, Optional<EmploymentFacts> employment) {
        if (employment.isEmpty() || employment.get().hireDate() == null) {
            return segments;
        }
        EmploymentFacts e = employment.get();
        LocalDate end = e.terminationDate() == null ? null : e.terminationDate().plusDays(1);
        if (end != null && !end.isAfter(e.hireDate())) {
            return List.of();
        }
        DateRange window = new DateRange(e.hireDate(), end);
        List<Segment> out = new ArrayList<>();
        for (Segment s : segments) {
            s.validity().intersect(window).ifPresent(r -> out.add(new Segment(s.ruleId(), s.patternId(), r)));
        }
        return out;
    }

    /** Tells People (after commit) whether the employment has a schedule on today or, if later, its first day. */
    void notifyReadinessAfterCommit(UUID employmentId) {
        List<LineVersion> lines = store.lineVersionsOfEmployment(employmentId);
        LocalDate first = lines.stream().map(LineVersion::validFrom).min(LocalDate::compareTo).orElse(clock.today());
        LocalDate probe = first.isAfter(clock.today()) ? first : clock.today();
        boolean assigned = store.assignmentsOfEmployment(employmentId, false).stream().anyMatch(a -> a.covers(probe));
        IntegrationInbox inbox = peopleInbox.getIfAvailable();
        if (inbox == null) {
            return;
        }
        Runnable call = () -> {
            try {
                inbox.workScheduleAssigned(Ids.newId(), employmentId, assigned);
            } catch (RuntimeException e) {
                log.warn("WORK_SCHEDULE readiness not delivered to People for an employment: {}",
                        e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    call.run();
                }
            });
        } else {
            call.run();
        }
    }

    private static DateRange range(AssignmentRow a) {
        return new DateRange(a.validFrom(), a.validTo());
    }

    /**
     * D3: the line versions cut where the employment's dated employee category / working regime changes; each part
     * carries the values in force on its days ({@code att.ext_hr_employment_classification}), else the version's own.
     */
    static List<RuleSegments.Version> split(List<LineVersion> versions, List<Classification> classes) {
        List<RuleSegments.Version> out = new ArrayList<>();
        for (LineVersion v : versions) {
            DateRange range = new DateRange(v.validFrom(), v.validTo());
            java.util.TreeSet<LocalDate> cuts = new java.util.TreeSet<>();
            cuts.add(v.validFrom());
            for (Classification c : classes) {
                if (range.contains(c.validFrom())) {
                    cuts.add(c.validFrom());
                }
                if (c.validTo() != null && range.contains(c.validTo())) {
                    cuts.add(c.validTo());
                }
            }
            List<LocalDate> starts = new ArrayList<>(cuts);
            for (int i = 0; i < starts.size(); i++) {
                LocalDate start = starts.get(i);
                LocalDate end = i + 1 < starts.size() ? starts.get(i + 1) : v.validTo();
                Optional<Classification> c = classes.stream().filter(x -> x.covers(start)).findFirst();
                out.add(new RuleSegments.Version(new DateRange(start, end), v.legalEntityId(), v.orgUnitId(),
                        v.locationId(), v.jobTitleId(),
                        c.map(Classification::employeeCategoryCode).orElse(v.employeeCategoryCode()),
                        c.map(Classification::workingRegimeCode).orElse(v.workingRegimeCode())));
            }
        }
        return out;
    }

    private static RuleSegments.Rule rule(RuleRow r) {
        var d = r.data();
        return new RuleSegments.Rule(r.id(), d.code(), d.priority(), d.legalEntityId(), d.orgUnitId(),
                d.locationId(), d.jobTitleId(), d.employeeCategoryCode(), d.workingRegimeCode(), d.patternId(),
                new DateRange(d.validFrom(), d.validTo()));
    }

    /** Lines whose rows {@code rules} can affect (their legal entities, or all when one has none). */
    List<UUID> linesAffectedBy(List<RuleRow> rules) {
        Set<UUID> les = new HashSet<>();
        for (RuleRow r : rules) {
            if (r.data().legalEntityId() == null) {
                return store.lineKeys(null);
            }
            les.add(r.data().legalEntityId());
        }
        List<UUID> out = new ArrayList<>();
        for (UUID le : les) {
            out.addAll(store.lineKeys(le));
        }
        return out.stream().filter(Objects::nonNull).distinct().toList();
    }
}
