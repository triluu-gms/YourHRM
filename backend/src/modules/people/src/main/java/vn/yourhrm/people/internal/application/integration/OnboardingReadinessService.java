package vn.yourhrm.people.internal.application.integration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.IntegrationEvents.OnboardingReadinessChanged;
import vn.yourhrm.people.api.IntegrationEvents.ReadinessItem;
import vn.yourhrm.people.api.OnboardingReadinessSources;
import vn.yourhrm.people.api.OnboardingReadinessSources.ItemState;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Onboarding readiness of PRE_HIRE / ACTIVE employments ({@code people.OnboardingReadinessChanged}, US-PPL-082,
 * ADR-0019 {@code HireProcess}). People's own items are computed from {@code hr}: SIGNED_CONTRACT (a live contract with
 * a sign date), BANK_ACCOUNT (an ACTIVE account of the person), TAX_CODE (the person's tax code is recorded). The
 * items of other modules come from {@link OnboardingReadinessSources} (stub: UNKNOWN), from the state carried by the
 * event that triggered the evaluation, or else from the last evaluated state kept in {@code hr.onboarding_readiness}
 * (one row per employment: status, items, trigger).
 *
 * <p>The event is published, and the row written, only when the status or an item changed since the last state.
 */
@Service
public class OnboardingReadinessService {

    static final List<String> ITEMS = List.of("USER_ACCOUNT", "PAY_GROUP", "WORK_SCHEDULE", "SIGNED_CONTRACT",
            "BANK_ACCOUNT", "TAX_CODE");

    private final HrDb db;
    private final HrJson json;
    private final HrOutbox outbox;
    private final OnboardingReadinessSources sources;
    private final BusinessClock clock;

    public OnboardingReadinessService(HrDb db, HrJson json, HrOutbox outbox,
            ObjectProvider<OnboardingReadinessSources> sources, BusinessClock clock) {
        this.db = db;
        this.json = json;
        this.outbox = outbox;
        this.sources = sources.getIfAvailable(() -> OnboardingReadinessSources.NONE);
        this.clock = clock;
    }

    private record Employment(UUID id, UUID personId, UUID legalEntityId, LocalDate hireDate, String status) {
    }

    /**
     * Evaluates {@code employmentId} and publishes the readiness when it changed.
     *
     * @param known item states carried by the triggering event (e.g. PAY_GROUP → READY)
     * @return the published event, empty when nothing changed or the employment is not onboarding
     */
    @HrTransactional
    public Optional<OnboardingReadinessChanged> evaluate(UUID employmentId, String trigger, Map<String, ItemState> known) {
        UUID tenant = TenantContext.current().tenantId();
        Optional<Employment> emp = db.jdbc().sql("""
                        select id, person_id, legal_entity_id, hire_date, status from hr.employment
                         where tenant_id = :t and id = :id""")
                .param("t", tenant).param("id", employmentId)
                .query((rs, n) -> new Employment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, LocalDate.class), rs.getString(5)))
                .optional();
        if (emp.isEmpty() || !List.of("PRE_HIRE", "ACTIVE").contains(emp.get().status())) {
            return Optional.empty();
        }
        Employment e = emp.get();
        Map<String, String> last = lastState(tenant, e.id());
        Map<String, String> items = new LinkedHashMap<>();
        items.put("USER_ACCOUNT", external(sources.userAccount(e.id()), known.get("USER_ACCOUNT"), last.get("USER_ACCOUNT")));
        items.put("PAY_GROUP", external(sources.payGroup(e.id()), known.get("PAY_GROUP"), last.get("PAY_GROUP")));
        items.put("WORK_SCHEDULE", external(sources.workSchedule(e.id()), known.get("WORK_SCHEDULE"),
                last.get("WORK_SCHEDULE")));
        LocalDate on = e.hireDate().isAfter(clock.today()) ? e.hireDate() : clock.today();
        Map<String, Object> own = db.jdbc().sql("""
                        select exists (select 1 from hr.contract c where c.tenant_id = :t and c.employment_id = :e
                                         and c.status in ('ACTIVE','SUSPENDED') and c.sign_date is not null) as contract,
                               exists (select 1 from hr.bank_account b where b.tenant_id = :t and b.person_id = :p
                                         and b.status = 'ACTIVE' and b.valid_during @> cast(:d as date)) as bank,
                               exists (select 1 from hr.person p where p.tenant_id = :t and p.id = :p
                                         and p.tax_code_bidx is not null) as tax""")
                .param("t", tenant).param("e", e.id()).param("p", e.personId()).param("d", on)
                .query().singleRow();
        items.put("SIGNED_CONTRACT", Boolean.TRUE.equals(own.get("contract")) ? "READY" : "MISSING");
        items.put("BANK_ACCOUNT", Boolean.TRUE.equals(own.get("bank")) ? "READY" : "MISSING");
        items.put("TAX_CODE", Boolean.TRUE.equals(own.get("tax")) ? "READY" : "MISSING");
        String status = items.containsValue("MISSING") ? "BLOCKED" : "READY";
        Map<String, String> lastWithStatus = new LinkedHashMap<>(items);
        lastWithStatus.put("$status", status);
        if (lastWithStatus.equals(last)) {
            return Optional.empty();
        }
        List<ReadinessItem> list = new ArrayList<>();
        List<Map<String, String>> stored = new ArrayList<>();
        items.forEach((k, v) -> {
            list.add(new ReadinessItem(k, v));
            stored.add(Map.of("item", k, "state", v));
        });
        db.jdbc().sql("""
                        insert into hr.onboarding_readiness (tenant_id, employment_id, status, items, trigger_code)
                        values (:t, :e, :s, cast(:items as jsonb), :trigger)
                        on conflict (tenant_id, employment_id) do update
                           set status = excluded.status, items = excluded.items, trigger_code = excluded.trigger_code,
                               evaluated_at = now(), version = hr.onboarding_readiness.version + 1""")
                .param("t", tenant).param("e", e.id()).param("s", status).param("items", json.write(stored))
                .param("trigger", trigger).update();
        OnboardingReadinessChanged event = new OnboardingReadinessChanged(tenant, e.legalEntityId(), e.personId(),
                e.id(), e.hireDate(), status, list, trigger);
        outbox.publish(event);
        return Optional.of(event);
    }

    /** Employments of a person that are still onboarding-relevant (bank account / tax code are person-level). */
    @HrTransactional(readOnly = true)
    public List<UUID> onboardingEmploymentsOf(UUID personId) {
        return db.jdbc().sql("""
                        select id from hr.employment where tenant_id = :t and person_id = :p
                           and status in ('PRE_HIRE','ACTIVE') and hire_date >= :from""")
                .param("t", TenantContext.current().tenantId()).param("p", personId)
                .param("from", clock.today().minusDays(60)).query(UUID.class).list();
    }

    private static String external(ItemState spi, ItemState carried, String last) {
        if (carried != null) {
            return carried.name();
        }
        if (spi != null && spi != ItemState.UNKNOWN) {
            return spi.name();
        }
        return last != null ? last : ItemState.UNKNOWN.name();
    }

    /** Items and status ({@code $status}) of the last evaluated readiness of the employment. */
    private Map<String, String> lastState(UUID tenant, UUID employmentId) {
        Map<String, String> out = new LinkedHashMap<>();
        db.jdbc().sql("""
                        select status, items::text from hr.onboarding_readiness
                         where tenant_id = :t and employment_id = :e""")
                .param("t", tenant).param("e", employmentId)
                .query((rs, n) -> {
                    for (Map<String, Object> i : json.listOfMaps(rs.getString(2))) {
                        if (i.get("item") != null) {
                            out.put(i.get("item").toString(), String.valueOf(i.get("state")));
                        }
                    }
                    out.put("$status", rs.getString(1));
                    return 1;
                })
                .optional();
        return out;
    }
}
