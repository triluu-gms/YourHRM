package vn.yourhrm.people.internal.application.employment;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.EmploymentEvents.EmploymentChanged;
import vn.yourhrm.people.internal.application.assignment.RetroFlags;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.ClassificationRow;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Dated employee category and working regime of an employment (PM decision D3, 2026-11-19;
 * {@code hr.employment_classification}, Pattern A).
 * <ul>
 *   <li>A change from date D ends the version in force on D (or replaces it when it starts on D); the new version runs
 *       until the next later version, or open. Every change is a new version and gets one
 *       {@code people.EmploymentChanged}: when saved if D has come, otherwise from the daily job on D.</li>
 *   <li>{@code hr.employment.employee_category_code / working_regime_code} hold the values in force today (on the hire
 *       date for a pre-hire), so every current-state reader keeps working.</li>
 *   <li>An employment without versions (data written before v0.7) gets a baseline version from its hire date with
 *       its current values before the first change, so the days before the change keep their values.</li>
 * </ul>
 */
@Service
public class EmploymentClassificationService {

    /** Daily-job marker consumer (hr.inbox_message), event = the version id. */
    static final String DUE_MARKER = "hr.employment_classification.due";

    /** The two values in force on a date. */
    public record Values(String employeeCategoryCode, String workingRegimeCode) {
    }

    private final EmploymentStore store;
    private final HrOutbox outbox;
    private final RetroFlags retro;
    private final ProcessedMarkers markers;
    private final BusinessClock clock;

    public EmploymentClassificationService(EmploymentStore store, HrOutbox outbox, RetroFlags retro,
            ProcessedMarkers markers, BusinessClock clock) {
        this.store = store;
        this.outbox = outbox;
        this.retro = retro;
        this.markers = markers;
        this.clock = clock;
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    /** The values in force on {@code d}; the employment's own columns when it has no version covering it. */
    public Values valuesOn(Row employment, LocalDate d) {
        return valuesOn(employment, store.classifications(employment.id()), d);
    }

    /** As {@link #valuesOn(Row, LocalDate)} by id; both null when the employment is unknown. */
    public Values valuesOn(UUID employmentId, LocalDate d) {
        return store.find(employmentId).map(r -> valuesOn(r, d)).orElse(new Values(null, null));
    }

    private static Values valuesOn(Row employment, List<ClassificationRow> versions, LocalDate d) {
        return versions.stream().filter(v -> v.covers(d)).findFirst()
                .map(v -> new Values(v.employeeCategoryCode(), v.workingRegimeCode()))
                .orElse(new Values(employment.employeeCategoryCode(), employment.workingRegimeCode()));
    }

    /** The day whose values {@code hr.employment} holds: today, or the hire date while the hire lies ahead. */
    private LocalDate currentDay(Row employment) {
        LocalDate today = clock.today();
        return today.isBefore(employment.hireDate()) ? employment.hireDate() : today;
    }

    /** ACTIVE versions, oldest first. */
    @HrTransactional(readOnly = true)
    public List<ClassificationRow> history(UUID employmentId) {
        return store.classifications(employmentId);
    }

    /**
     * Records a change from {@code from} (inside the employment period). Returns the new version, or empty when the
     * values in force on {@code from} are already these. The caller has authorised the change and holds the
     * employment row; {@code hr.employment}'s current values are set by the caller (see {@link #currentValues}).
     */
    public Optional<ClassificationRow> change(Row employment, LocalDate from, String employeeCategory,
            String workingRegime) {
        if (from.isBefore(employment.hireDate())) {
            throw ApiException.unprocessable("classification-before-hire",
                    "The employee category / working regime cannot change before the hire date "
                            + employment.hireDate());
        }
        if (employment.endDate() != null && from.isAfter(employment.endDate())) {
            throw ApiException.unprocessable("classification-after-end",
                    "The employee category / working regime cannot change after the employment end date "
                            + employment.endDate());
        }
        List<ClassificationRow> versions = store.classifications(employment.id());
        if (versions.isEmpty()) {
            store.insertClassification(employment.id(), employment.employeeCategoryCode(),
                    employment.workingRegimeCode(), employment.hireDate(), null,
                    EmploymentStore.classificationSource(employment.source()), actor());
            versions = store.classifications(employment.id());
        }
        Values before = valuesOn(employment, versions, from);
        if (Objects.equals(before.employeeCategoryCode(), employeeCategory)
                && Objects.equals(before.workingRegimeCode(), workingRegime)) {
            return Optional.empty();
        }
        Optional<ClassificationRow> covering = versions.stream().filter(v -> v.covers(from)).findFirst();
        LocalDate to;
        if (covering.isPresent()) {
            to = covering.get().validTo();
            if (covering.get().validFrom().equals(from)) {
                store.cancelClassification(covering.get().id(), actor());
            } else {
                store.endClassification(covering.get().id(), from, actor());
            }
        } else {
            to = versions.stream().map(ClassificationRow::validFrom).filter(d -> d.isAfter(from))
                    .min(LocalDate::compareTo).orElse(null);
        }
        UUID id = store.insertClassification(employment.id(), employeeCategory, workingRegime, from, to, "APP",
                actor());
        ClassificationRow created = store.classification(id).orElseThrow();
        if (!from.isAfter(clock.today())) {
            publish(employment, created, before);
            markers.mark(DUE_MARKER, id, "people.EmploymentChanged", "PROCESSED");
        }
        return Optional.of(created);
    }

    /** The values {@code hr.employment} must hold now (after a change was recorded). */
    public Values currentValues(Row employment) {
        return valuesOn(employment, currentDay(employment));
    }

    /**
     * Opening load: the whole history becomes one version from the hire date with these values (earlier versions are
     * CANCELLED). No event: the load publishes {@code EmployeeHired} with {@code initialLoad = true}.
     */
    public void replaceForLoad(Row employment, String employeeCategory, String workingRegime, String source) {
        store.cancelClassifications(List.of(employment.id()), actor());
        store.insertClassification(employment.id(), employeeCategory, workingRegime, employment.hireDate(), null,
                source, actor());
    }

    /** Opening-load rollback: the versions of the cancelled employments are CANCELLED too. */
    public void cancelForLoad(List<UUID> employmentIds) {
        store.cancelClassifications(employmentIds, actor());
    }

    /**
     * Daily job (with the assignment and salary streams): versions recorded before their date whose date has come in
     * the look-back window get their {@code EmploymentChanged} now, and {@code hr.employment} takes their values.
     * Idempotent through markers.
     */
    public int publishDueChanges(LocalDate today, int lookbackDays) {
        int published = 0;
        for (ClassificationRow v : store.dueClassifications(today.minusDays(lookbackDays), today)) {
            markers.lock(DUE_MARKER, v.id());
            if (markers.exists(DUE_MARKER, v.id())) {
                continue;
            }
            Row employment = store.find(v.employmentId()).orElseThrow();
            if ("CANCELLED".equals(employment.status())) {
                markers.mark(DUE_MARKER, v.id(), "people.EmploymentChanged", "SKIPPED");
                continue;
            }
            List<ClassificationRow> versions = store.classifications(employment.id());
            Values before = versions.stream().filter(x -> v.validFrom().equals(x.validTo())).findFirst()
                    .map(x -> new Values(x.employeeCategoryCode(), x.workingRegimeCode()))
                    .orElse(new Values(null, null));
            publish(employment, v, before);
            Values now = valuesOn(employment, versions, today.isBefore(employment.hireDate())
                    ? employment.hireDate() : today);
            store.setCurrentClassification(employment.id(), now.employeeCategoryCode(), now.workingRegimeCode(),
                    actor());
            markers.mark(DUE_MARKER, v.id(), "people.EmploymentChanged", "PROCESSED");
            published++;
        }
        return published;
    }

    private void publish(Row employment, ClassificationRow v, Values before) {
        RetroFlags.Retro r = retro.of(employment.legalEntityId(), v.validFrom());
        outbox.publish(new EmploymentChanged(TenantContext.current().tenantId(), employment.legalEntityId(),
                employment.personId(), employment.id(), v.validFrom(), v.id(), v.employeeCategoryCode(),
                v.workingRegimeCode(), before.employeeCategoryCode(), before.workingRegimeCode(), v.validFrom(),
                v.validTo(), r.retro(), r.earliestAffectedPeriod()));
    }
}
