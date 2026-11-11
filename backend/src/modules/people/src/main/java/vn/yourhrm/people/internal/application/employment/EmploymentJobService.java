package vn.yourhrm.people.internal.application.employment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.EmploymentEvents.HeadcountSnapshotTaken;
import vn.yourhrm.people.api.EmploymentJobs;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.assignment.ProcessedMarkers;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.legal.LegalParameterPackPublished;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The employment stream's daily job and legal-pack hook ({@link EmploymentJobs}), idempotent per tenant and day:
 * activation of approved pre-hires (US-PPL-019), end of PIT designations of ended employments (US-PPL-022),
 * re-derivation of insurance participation after an employment ended (US-PPL-023) and the headcount snapshot per
 * legal entity (US-PPL-026, OUT-32).
 */
@Service
public class EmploymentJobService implements EmploymentJobs {

    private final HrDb db;
    private final HireService hires;
    private final PrimaryEmploymentService primaries;
    private final InsuranceParticipationService insurance;
    private final Savepoints savepoints;
    private final ProcessedMarkers markers;
    private final HrOutbox outbox;
    private final BusinessClock clock;

    public EmploymentJobService(HrDb db, HireService hires, PrimaryEmploymentService primaries,
            InsuranceParticipationService insurance, Savepoints savepoints, ProcessedMarkers markers,
            HrOutbox outbox, BusinessClock clock) {
        this.db = db;
        this.hires = hires;
        this.primaries = primaries;
        this.insurance = insurance;
        this.savepoints = savepoints;
        this.markers = markers;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @HrTransactional
    public DailySummary runDaily(LocalDate today) {
        int activated = 0;
        List<UUID> blocked = new ArrayList<>();
        for (Row r : hires.dueActivations(today)) {
            if (hires.activate(r)) {
                activated++;
            } else {
                blocked.add(r.id());
            }
        }
        int ended = primaries.closeEndedPrimaries();
        int rederived = savepoints.tryRun("Insurance participation of ended employments", insurance::rederiveEnded, 0);
        int snapshots = takeHeadcountSnapshot(today);
        return new DailySummary(activated, List.copyOf(blocked), ended, rederived, snapshots);
    }

    /**
     * One snapshot per legal entity with employments: ACTIVE ones live on {@code date} (SUSPENDED excluded from the
     * billable count, R2-12; a person employed by two legal entities counts in each). The stored status is the
     * current one, so the job takes the snapshot of the day it runs (a back-dated run uses today's statuses).
     */
    @Override
    @HrTransactional
    public int takeHeadcountSnapshot(LocalDate date) {
        UUID tenant = TenantContext.current().tenantId();
        List<Map<String, Object>> rows = db.jdbc().sql("""
                        select legal_entity_id,
                               count(*) filter (where status = 'ACTIVE' and hire_date <= :d
                                                  and (end_date is null or end_date >= :d)) as active,
                               count(*) filter (where status = 'SUSPENDED' and hire_date <= :d
                                                  and (end_date is null or end_date >= :d)) as suspended,
                               count(*) filter (where status = 'PRE_HIRE') as pre_hire
                          from hr.employment where tenant_id = :t and status <> 'CANCELLED'
                         group by legal_entity_id order by legal_entity_id""")
                .param("t", tenant).param("d", date).query().listOfRows();
        boolean monthEnd = date.plusDays(1).getDayOfMonth() == 1;
        int n = 0;
        for (Map<String, Object> r : rows) {
            UUID le = (UUID) r.get("legal_entity_id");
            UUID key = UUID.nameUUIDFromBytes((le + "|" + date).getBytes(StandardCharsets.UTF_8));
            markers.lock(EmploymentKeys.HEADCOUNT_MARKER, key);
            if (markers.exists(EmploymentKeys.HEADCOUNT_MARKER, key)) {
                continue;
            }
            outbox.publish(new HeadcountSnapshotTaken(tenant, le, date, monthEnd,
                    ((Number) r.get("active")).intValue(), ((Number) r.get("suspended")).intValue(),
                    ((Number) r.get("pre_hire")).intValue()), "legal_entity", le);
            markers.mark(EmploymentKeys.HEADCOUNT_MARKER, key, "people.HeadcountSnapshotTaken", "PROCESSED");
            n++;
        }
        return n;
    }

    @Override
    @HrTransactional
    public int onLegalPackPublished(LegalParameterPackPublished event) {
        if (!event.changedKeys().contains(EmploymentKeys.INSURANCE_MULTI_CONTRACT_RULE)) {
            return 0;
        }
        LocalDate from = event.changedFrom().stream().min(LocalDate::compareTo).orElse(clock.today());
        return insurance.rederiveAll(from, "LEGAL_PACK_PUBLISHED");
    }
}
