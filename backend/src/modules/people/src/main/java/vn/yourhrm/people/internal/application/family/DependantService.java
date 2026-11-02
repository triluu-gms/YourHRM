package vn.yourhrm.people.internal.application.family;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.FamilyEvents;
import vn.yourhrm.people.api.FamilyEvents.DependantChanged;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.family.DependantEligibility.Violation;
import vn.yourhrm.people.internal.application.family.FamilyMemberService.Member;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * PIT dependants (người phụ thuộc, BR-PPL-016/017, BR-PPL-004; US-PPL-024/028/029/074).
 *
 * <p>A claim ({@code hr.pit_dependant}) registers a family member as dependant of the employee for a month range
 * ({@code from_month}..{@code to_month} inclusive, open = null) through the CLAIMING employment, which must be the
 * person's PIT primary employment ({@code hr.primary_employment}, purpose PIT_FAMILY_DEDUCTION) in every month of the
 * claim; without a designation on the reference day the claiming employment must be the person's only live employment
 * then. The reference day of a month is its last day (clamped into the employment's period in its first / last month).
 * A dependant is claimed by one taxpayer per month in the tenant: the claim constraint per family member
 * ({@code pit_dependant_claim_ex}) plus a check across family members of other employees with the same identity
 * number (blind index).
 *
 * <p>Lifecycle (approval through Core process type {@code hr.DEPENDANT_CHANGE}, US-PPL-029):
 * <ul>
 *   <li>register: the claim is inserted PENDING with its evidence; approval → APPROVED and
 *       {@code people.DependantChanged} REGISTERED; rejection / withdrawal → REJECTED;</li>
 *   <li>end: the request is stored as a DRAFT row of the same family member and first month carrying the proposed
 *       end month (DRAFT rows are outside the claim constraint; the DDL has no other place for a pending change);
 *       approval → the claim's {@code to_month} is set, status ENDED, {@code DependantChanged} ENDED, the request row
 *       is removed; rejection → the request row is removed;</li>
 *   <li>transfer ({@link #transferToNewPrimary}): when the PIT primary employment changes, active claims move to the new
 *       primary employment from the given month (split into an ended part and a new APPROVED row) →
 *       {@code DependantChanged} TRANSFERRED. No approval: the primary designation itself was approved.</li>
 * </ul>
 */
@Service
public class DependantService {

    static final List<String> LIVE_EMPLOYMENT = List.of("PRE_HIRE", "ACTIVE", "SUSPENDED");

    /** A registration request; {@code employmentId} null = the claimant the rules determine. Months: first days. */
    public record Registration(UUID familyMemberId, UUID employmentId, LocalDate fromMonth, LocalDate toMonth,
            String reasonCode, List<UUID> evidenceDocumentIds, String taxRegistrationRef) {

        public Registration {
            evidenceDocumentIds = evidenceDocumentIds == null ? List.of() : List.copyOf(evidenceDocumentIds);
        }
    }

    /** Result of the eligibility / claim checks, shown before submitting (US-PPL-074). */
    public record EligibilityResult(boolean eligible, UUID claimEmploymentId, List<Map<String, Object>> violations) {
    }

    /**
     * A claim. {@code pendingEndMonth}: an end request awaiting approval. {@code status}: PENDING, APPROVED, REJECTED,
     * ENDED.
     */
    public record DependantView(UUID id, UUID personId, UUID familyMemberId, String familyMemberName,
            String relationshipCode, UUID employmentId, LocalDate fromMonth, LocalDate toMonth, String reasonCode,
            String status, LocalDate pendingEndMonth, UUID pendingEndRequestId, String taxRegistrationRef,
            LocalDate registeredAt, Instant approvedAt, List<UUID> evidenceDocumentIds, int version) {
    }

    private record Row(UUID id, UUID familyMemberId, UUID employmentId, LocalDate fromMonth, LocalDate toMonth,
            String reasonCode, String status, String taxRegistrationRef, LocalDate registeredAt, Instant approvedAt,
            int version, UUID personId, String memberName, String relationshipCode) {
    }

    private record Employment(UUID id, UUID legalEntityId, String status, LocalDate hireDate, LocalDate endDate) {
    }

    private final HrDb db;
    private final HrOutbox outbox;
    private final EmployeeAccess access;
    private final FamilyMemberService members;
    private final DependantEligibility eligibility;
    private final Documents documents;
    private final WorkflowService workflow;
    private final BusinessClock clock;

    public DependantService(HrDb db, HrOutbox outbox, EmployeeAccess access, FamilyMemberService members,
            DependantEligibility eligibility, Documents documents, WorkflowService workflow, BusinessClock clock) {
        this.db = db;
        this.outbox = outbox;
        this.access = access;
        this.members = members;
        this.eligibility = eligibility;
        this.documents = documents;
        this.workflow = workflow;
        this.clock = clock;
    }

    // ================================================================================================ HR

    @HrTransactional(readOnly = true)
    public List<DependantView> list(UUID personId) {
        access.requirePerson(PeoplePermissions.DEPENDANT_VIEW, personId, clock.today());
        return views(personId);
    }

    @HrTransactional(readOnly = true)
    public EligibilityResult validate(UUID personId, Registration r) {
        access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        return evaluate(personId, r, null);
    }

    @HrTransactional
    public DependantView register(UUID personId, Registration r) {
        access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        return doRegister(personId, r, null);
    }

    @HrTransactional
    public DependantView requestEnd(UUID personId, UUID dependantId, LocalDate toMonth, int version,
            List<UUID> evidenceDocumentIds) {
        access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        return doRequestEnd(personId, dependantId, toMonth, version, evidenceDocumentIds);
    }

    // ================================================================================================ ESS

    /** The employee's own claims (US-PPL-074); the person comes from the SELF scope, never from the request. */
    @HrTransactional(readOnly = true)
    public List<DependantView> listOwn(UUID selfPersonId) {
        return views(selfPersonId);
    }

    @HrTransactional(readOnly = true)
    public EligibilityResult validateOwn(UUID selfPersonId, Registration r) {
        return evaluate(selfPersonId, withoutEmployment(r), null);
    }

    /** ESS request (US-PPL-074): goes into the same HR approval as an HR registration (US-PPL-029). */
    @HrTransactional
    public DependantView registerOwn(UUID selfPersonId, Registration r) {
        return doRegister(selfPersonId, withoutEmployment(r), null);
    }

    @HrTransactional
    public DependantView requestEndOwn(UUID selfPersonId, UUID dependantId, LocalDate toMonth, int version,
            List<UUID> evidenceDocumentIds) {
        return doRequestEnd(selfPersonId, dependantId, toMonth, version, evidenceDocumentIds);
    }

    private static Registration withoutEmployment(Registration r) {
        return new Registration(r.familyMemberId(), null, r.fromMonth(), r.toMonth(), r.reasonCode(),
                r.evidenceDocumentIds(), null);
    }

    // ================================================================================================ internal API

    /**
     * US-PPL-024: the person's PIT primary employment changes to {@code newEmploymentId} from {@code fromMonth}; every
     * APPROVED / ENDED claim of the person through another employment that covers {@code fromMonth} or later moves to
     * the new employment from that month (a claim starting earlier is split: the old row ends the month before, a new
     * APPROVED row continues). Publishes {@code DependantChanged} TRANSFERRED per moved claim. Runs in the caller's
     * People transaction (called by the primary-employment flow, which authorised and approved the change). Pending
     * registrations and end requests are left as they are (they are re-checked on approval by the approver).
     *
     * @return the claims now held by the new employment
     */
    @HrTransactional
    public List<DependantView> transferToNewPrimary(UUID personId, UUID newEmploymentId, LocalDate fromMonth) {
        LocalDate from = firstOfMonth(fromMonth);
        UUID tenant = TenantContext.current().tenantId();
        Employment target = employments(personId).stream().filter(e -> e.id().equals(newEmploymentId)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("employment-not-of-person",
                        "Employment " + newEmploymentId + " does not belong to person " + personId));
        List<Row> moving = db.jdbc().sql(ROW_SELECT + """
                         where d.tenant_id = :t and f.person_id = :p and d.status in ('APPROVED','ENDED')
                           and d.employment_id <> :emp and (d.to_month is null or d.to_month >= :from)
                         order by d.from_month""")
                .param("t", tenant).param("p", personId).param("emp", newEmploymentId).param("from", from)
                .query(DependantService::row).list();
        List<UUID> moved = new ArrayList<>();
        for (Row old : moving) {
            Employment previous = employment(old.employmentId());
            UUID newId;
            if (!old.fromMonth().isBefore(from)) {
                db.jdbc().sql("""
                                update hr.pit_dependant set employment_id = :emp, updated_at = now(), updated_by = :actor,
                                       version = version + 1
                                 where tenant_id = :t and id = :id""")
                        .param("emp", newEmploymentId).param("actor", actor()).param("t", tenant).param("id", old.id())
                        .update();
                newId = old.id();
            } else {
                db.jdbc().sql("""
                                update hr.pit_dependant set to_month = :to, updated_at = now(), updated_by = :actor,
                                       version = version + 1
                                 where tenant_id = :t and id = :id""")
                        .param("to", from.minusMonths(1)).param("actor", actor()).param("t", tenant)
                        .param("id", old.id()).update();
                newId = Ids.newId();
                db.jdbc().sql("""
                                insert into hr.pit_dependant (tenant_id, id, family_member_id, employment_id, from_month,
                                       to_month, dependant_reason_code, status, tax_registration_ref, registered_at,
                                       approved_by, approved_at, created_by, updated_by)
                                values (:t, :id, :fm, :emp, :from, :to, :reason, :status, :ref, :reg, :actor, now(),
                                        :actor, :actor)""")
                        .param("t", tenant).param("id", newId).param("fm", old.familyMemberId())
                        .param("emp", newEmploymentId).param("from", from).param("to", old.toMonth())
                        .param("reason", old.reasonCode()).param("status", old.status())
                        .param("ref", old.taxRegistrationRef()).param("reg", clock.today()).param("actor", actor())
                        .update();
                copyEvidence(old.id(), newId, personId, newEmploymentId);
            }
            moved.add(newId);
            outbox.publish(new DependantChanged(tenant, target.legalEntityId(), personId, newEmploymentId, from,
                    FamilyEvents.TRANSFERRED, newId, old.familyMemberId(), old.relationshipCode(), old.reasonCode(),
                    old.fromMonth().isBefore(from) ? from : old.fromMonth(), old.toMonth(), previous.id(),
                    previous.legalEntityId(), newId.equals(old.id()) ? null : old.id(), false));
        }
        return views(personId).stream().filter(v -> moved.contains(v.id())).toList();
    }

    /**
     * Opening-balance import (NFR-PPL-007, ADR-0037): an APPROVED claim with its registered months, in the caller's
     * People transaction, without approval and without the eligibility rules (history is loaded as it was declared);
     * the one-claimant rule still applies. Publishes {@code DependantChanged} REGISTERED with {@code initialLoad}.
     */
    @HrTransactional
    public UUID createForInitialLoad(UUID familyMemberId, UUID employmentId, LocalDate fromMonth, LocalDate toMonth,
            String reasonCode, String taxRegistrationRef, LocalDate registeredAt) {
        Member m = members.load(familyMemberId);
        Employment e = employment(employmentId);
        LocalDate from = firstOfMonth(fromMonth);
        LocalDate to = toMonth == null ? null : firstOfMonth(toMonth);
        if (!DependantEligibility.REASONS.contains(reasonCode)) {
            throw ApiException.unprocessable("invalid-reason", "Unknown dependant reason " + reasonCode);
        }
        List<Violation> claims = claimConflicts(m, from, to, null);
        if (!claims.isEmpty()) {
            throw ApiException.conflict("dependant-already-claimed", claims.getFirst().message());
        }
        UUID id = Ids.newId();
        insert(id, m.id(), e.id(), from, to, reasonCode, "APPROVED", taxRegistrationRef,
                registeredAt == null ? clock.today() : registeredAt);
        outbox.publish(new DependantChanged(TenantContext.current().tenantId(), e.legalEntityId(), m.personId(),
                e.id(), from, FamilyEvents.REGISTERED, id, m.id(), m.relationshipCode(), reasonCode, from, to, null,
                null, null, true));
        return id;
    }

    /**
     * Applies the outcome of a {@code hr.DEPENDANT_CHANGE} approval (called by the workflow listener). Idempotent: a
     * row no longer awaiting a decision is left alone.
     *
     * @return a short result for the callback acknowledgement
     */
    @HrTransactional
    public Map<String, Object> applyOutcome(UUID subjectId, String outcome) {
        UUID tenant = TenantContext.current().tenantId();
        Optional<Row> found = db.jdbc().sql(ROW_SELECT + " where d.tenant_id = :t and d.id = :id for update of d")
                .param("t", tenant).param("id", subjectId).query(DependantService::row).optional();
        if (found.isEmpty()) {
            return Map.of("dependantStatus", "GONE");
        }
        Row r = found.get();
        boolean approved = "APPROVED".equals(outcome);
        if ("PENDING".equals(r.status())) {
            if (approved) {
                db.jdbc().sql("""
                                update hr.pit_dependant set status = 'APPROVED', approved_by = :actor, approved_at = now(),
                                       registered_at = coalesce(registered_at, :today), updated_at = now(),
                                       updated_by = :actor, version = version + 1
                                 where tenant_id = :t and id = :id""")
                        .param("actor", actor()).param("today", clock.today()).param("t", tenant).param("id", r.id())
                        .update();
                Employment e = employment(r.employmentId());
                outbox.publish(new DependantChanged(tenant, e.legalEntityId(), r.personId(), e.id(), r.fromMonth(),
                        FamilyEvents.REGISTERED, r.id(), r.familyMemberId(), r.relationshipCode(), r.reasonCode(),
                        r.fromMonth(), r.toMonth(), null, null, null, false));
                return Map.of("dependantStatus", "APPROVED");
            }
            db.jdbc().sql("""
                            update hr.pit_dependant set status = 'REJECTED', updated_at = now(), updated_by = :actor,
                                   version = version + 1
                             where tenant_id = :t and id = :id""")
                    .param("actor", actor()).param("t", tenant).param("id", r.id()).update();
            return Map.of("dependantStatus", "REJECTED");
        }
        if ("DRAFT".equals(r.status())) {
            Optional<Row> original = db.jdbc().sql(ROW_SELECT + """
                             where d.tenant_id = :t and d.family_member_id = :fm and d.from_month = :from
                               and d.status in ('APPROVED','ENDED') for update of d""")
                    .param("t", tenant).param("fm", r.familyMemberId()).param("from", r.fromMonth())
                    .query(DependantService::row).optional();
            String result = "END_REJECTED";
            if (approved && original.isPresent()) {
                Row o = original.get();
                db.jdbc().sql("""
                                update hr.pit_dependant set to_month = :to, status = 'ENDED', updated_at = now(),
                                       updated_by = :actor, version = version + 1
                                 where tenant_id = :t and id = :id""")
                        .param("to", r.toMonth()).param("actor", actor()).param("t", tenant).param("id", o.id())
                        .update();
                db.jdbc().sql("""
                                update hr.hr_document set source_id = :orig, updated_at = now()
                                 where tenant_id = :t and source_entity = 'hr.pit_dependant' and source_id = :id""")
                        .param("orig", o.id()).param("t", tenant).param("id", r.id()).update();
                Employment e = employment(o.employmentId());
                outbox.publish(new DependantChanged(tenant, e.legalEntityId(), o.personId(), e.id(),
                        r.toMonth().plusMonths(1), FamilyEvents.ENDED, o.id(), o.familyMemberId(), o.relationshipCode(),
                        o.reasonCode(), o.fromMonth(), r.toMonth(), null, null, null, false));
                result = "ENDED";
            } else {
                db.jdbc().sql("""
                                update hr.hr_document set status = 'WITHDRAWN', updated_at = now()
                                 where tenant_id = :t and source_entity = 'hr.pit_dependant' and source_id = :id""")
                        .param("t", tenant).param("id", r.id()).update();
            }
            db.jdbc().sql("delete from hr.pit_dependant where tenant_id = :t and id = :id")
                    .param("t", tenant).param("id", r.id()).update();
            return Map.of("dependantStatus", result);
        }
        return Map.of("dependantStatus", r.status());
    }

    // ================================================================================================ flows

    private DependantView doRegister(UUID personId, Registration r, UUID ignored) {
        EligibilityResult result = evaluate(personId, r, null);
        if (!result.eligible()) {
            throw ApiException.unprocessable("dependant-ineligible",
                    "The family member cannot be registered as a dependant for these months: "
                            + result.violations().getFirst().get("message"),
                    Map.of("violations", result.violations()));
        }
        Member m = members.load(personId, r.familyMemberId());
        if (r.evidenceDocumentIds().isEmpty()) {
            throw ApiException.unprocessable("evidence-required",
                    "A dependant registration needs at least one evidence document");
        }
        checkEvidence(m.id(), r.evidenceDocumentIds());
        UUID id = Ids.newId();
        LocalDate from = firstOfMonth(r.fromMonth());
        LocalDate to = r.toMonth() == null ? null : firstOfMonth(r.toMonth());
        insert(id, m.id(), result.claimEmploymentId(), from, to, r.reasonCode(), "PENDING", r.taxRegistrationRef(),
                null);
        linkEvidence(id, personId, result.claimEmploymentId(), r.evidenceDocumentIds());
        startApproval(id, personId, result.claimEmploymentId(), "REGISTER", m, r.reasonCode(), from, to);
        return view(personId, id);
    }

    private DependantView doRequestEnd(UUID personId, UUID dependantId, LocalDate toMonth, int version,
            List<UUID> evidenceDocumentIds) {
        Row claim = row(personId, dependantId);
        if (claim.version() != version) {
            throw ApiException.conflict("stale-version", "The dependant was changed by someone else; reload it");
        }
        if (!List.of("APPROVED", "ENDED").contains(claim.status())) {
            throw ApiException.conflict("dependant-not-approved", "Only an approved dependant can be ended");
        }
        if (toMonth == null) {
            throw ApiException.unprocessable("end-month-required", "The last month of the deduction is required");
        }
        LocalDate to = firstOfMonth(toMonth);
        if (to.isBefore(claim.fromMonth())) {
            throw ApiException.unprocessable("invalid-end-month", "The end month lies before the first month");
        }
        if (claim.toMonth() != null && !to.isBefore(claim.toMonth())) {
            throw ApiException.unprocessable("invalid-end-month",
                    "The claim already ends in " + YearMonth.from(claim.toMonth()) + "; an end request can only shorten it");
        }
        UUID tenant = TenantContext.current().tenantId();
        boolean pending = db.jdbc().sql("""
                        select exists (select 1 from hr.pit_dependant where tenant_id = :t and family_member_id = :fm
                                          and from_month = :from and status = 'DRAFT')""")
                .param("t", tenant).param("fm", claim.familyMemberId()).param("from", claim.fromMonth())
                .query(Boolean.class).single();
        if (pending) {
            throw ApiException.conflict("end-request-pending", "An end request of this dependant awaits approval");
        }
        List<UUID> evidence = evidenceDocumentIds == null ? List.of() : evidenceDocumentIds;
        checkEvidence(claim.familyMemberId(), evidence);
        UUID requestId = Ids.newId();
        insert(requestId, claim.familyMemberId(), claim.employmentId(), claim.fromMonth(), to, claim.reasonCode(),
                "DRAFT", claim.taxRegistrationRef(), null);
        linkEvidence(requestId, personId, claim.employmentId(), evidence);
        Member m = members.load(personId, claim.familyMemberId());
        startApproval(requestId, personId, claim.employmentId(), "END", m, claim.reasonCode(), claim.fromMonth(), to);
        return view(personId, dependantId);
    }

    private void startApproval(UUID subjectId, UUID personId, UUID employmentId, String action, Member m,
            String reason, LocalDate from, LocalDate to) {
        UUID tenant = TenantContext.current().tenantId();
        var org = db.jdbc().sql("select e.legal_entity_id, pa.org_unit_id, pa.location_id from "
                        + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + " where e.tenant_id = :t and e.id = :id")
                .param("t", tenant).param("id", employmentId).param("asOf", clock.today())
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)}).single();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("action", action);
        summary.put("relationshipCode", m.relationshipCode());
        summary.put("reasonCode", reason);
        summary.put("fromMonth", YearMonth.from(from).toString());
        if (to != null) {
            summary.put("toMonth", YearMonth.from(to).toString());
        }
        workflow.start(new StartRequest(FamilyCatalogues.PROCESS_DEPENDANT_CHANGE, FamilyCatalogues.SUBJECT_TYPE,
                subjectId, 0, "dependant:" + subjectId, null, personId, org[0], org[1], org[2], null, summary,
                Map.of("action", action, "reasonCode", reason), Map.of()));
        // A definition whose steps are all skipped approves at once: the listener has then already applied the outcome
        // in this transaction (the event is published synchronously).
    }

    // ================================================================================================ rules

    /** Eligibility (US-PPL-028) plus the claimant and one-claimant rules (US-PPL-024/028). */
    private EligibilityResult evaluate(UUID personId, Registration r, UUID excludeRow) {
        if (r.familyMemberId() == null || r.fromMonth() == null || r.reasonCode() == null) {
            throw ApiException.unprocessable("invalid-registration",
                    "familyMemberId, fromMonth and reasonCode are required");
        }
        Member m = members.load(personId, r.familyMemberId());
        LocalDate from = firstOfMonth(r.fromMonth());
        LocalDate to = r.toMonth() == null ? null : firstOfMonth(r.toMonth());
        List<Violation> out = new ArrayList<>();
        if (to != null && to.isBefore(from)) {
            out.add(new Violation("invalid-months", "The end month lies before the first month", null));
            return result(null, out);
        }
        out.addAll(eligibility.check(m, r.reasonCode(), from, to));
        UUID claimant = r.employmentId();
        List<Employment> employments = employments(personId);
        if (claimant == null) {
            claimant = defaultClaimant(personId, employments, from).orElse(null);
        }
        if (claimant == null) {
            out.add(new Violation("primary-employment-required", "The employee has several employments and no PIT "
                    + "primary employment for " + YearMonth.from(from) + "; designate one first", from));
        } else {
            out.addAll(claimantViolations(personId, employments, claimant, from, to));
        }
        out.addAll(claimConflicts(m, from, to, excludeRow));
        return result(claimant, out);
    }

    private static EligibilityResult result(UUID claimant, List<Violation> violations) {
        return new EligibilityResult(violations.isEmpty(), claimant,
                violations.stream().map(Violation::toMap).toList());
    }

    /** The primary designation on the reference day of {@code from}, else the only live employment then. */
    private Optional<UUID> defaultClaimant(UUID personId, List<Employment> employments, LocalDate from) {
        LocalDate day = from.plusMonths(1).minusDays(1);
        Optional<UUID> primary = primaryOn(personId, day);
        if (primary.isPresent()) {
            return primary;
        }
        List<Employment> live = employments.stream().filter(e -> liveOn(e, day) || liveInMonth(e, from)).toList();
        if (live.size() == 1) {
            return Optional.of(live.getFirst().id());
        }
        List<Employment> current = employments.stream().filter(e -> LIVE_EMPLOYMENT.contains(e.status())).toList();
        return live.isEmpty() && current.size() == 1 ? Optional.of(current.getFirst().id()) : Optional.empty();
    }

    private List<Violation> claimantViolations(UUID personId, List<Employment> employments, UUID claimant,
            LocalDate from, LocalDate to) {
        Optional<Employment> found = employments.stream().filter(e -> e.id().equals(claimant)).findFirst();
        if (found.isEmpty()) {
            return List.of(new Violation("employment-not-of-person",
                    "The claiming employment does not belong to the employee", null));
        }
        Employment e = found.get();
        if (!LIVE_EMPLOYMENT.contains(e.status())) {
            return List.of(new Violation("employment-not-live", "The claiming employment is " + e.status(), null));
        }
        LocalDate thisMonth = firstOfMonth(clock.today());
        LocalDate last = to != null ? to : (from.isAfter(thisMonth) ? from : thisMonth);
        for (LocalDate m = from; !m.isAfter(last); m = m.plusMonths(1)) {
            if (!liveInMonth(e, m)) {
                return List.of(new Violation("outside-employment",
                        "The claiming employment does not exist in " + YearMonth.from(m), m));
            }
            LocalDate day = referenceDay(e, m);
            Optional<UUID> primary = primaryOn(personId, day);
            if (primary.isPresent()) {
                if (!primary.get().equals(claimant)) {
                    return List.of(new Violation("not-primary-employment", "In " + YearMonth.from(m)
                            + " another employment is the PIT primary employment; dependants are claimed only there", m));
                }
            } else {
                long others = employments.stream().filter(x -> !x.id().equals(claimant) && liveOn(x, day)).count();
                if (others > 0) {
                    return List.of(new Violation("primary-employment-required", "In " + YearMonth.from(m)
                            + " the employee has several employments and no PIT primary employment", m));
                }
            }
        }
        return List.of();
    }

    /** One claimant per dependant per month in the tenant (pit_dependant_claim_ex and identity-number match). */
    private List<Violation> claimConflicts(Member m, LocalDate from, LocalDate to, UUID excludeRow) {
        UUID tenant = TenantContext.current().tenantId();
        LocalDate toExclusive = to == null ? null : to.plusMonths(1);
        List<Violation> out = new ArrayList<>();
        List<LocalDate> own = db.jdbc().sql("""
                        select d.from_month from hr.pit_dependant d
                         where d.tenant_id = :t and d.family_member_id = :fm and d.status in ('PENDING','APPROVED','ENDED')
                           and (cast(:ex as uuid) is null or d.id <> :ex)
                           and d.month_range && daterange(cast(:from as date), cast(:to as date), '[)')""")
                .param("t", tenant).param("fm", m.id()).param("ex", excludeRow).param("from", from)
                .param("to", toExclusive).query(LocalDate.class).list();
        if (!own.isEmpty()) {
            out.add(new Violation("already-claimed", "The family member is already registered (or pending) as a "
                    + "dependant for some of these months", own.getFirst()));
        }
        if (m.nationalIdBidx() != null) {
            List<LocalDate> others = db.jdbc().sql("""
                            select d.from_month from hr.pit_dependant d
                              join hr.family_member f on f.tenant_id = d.tenant_id and f.id = d.family_member_id
                             where d.tenant_id = :t and f.national_id_bidx = :b and f.id <> :fm
                               and d.status in ('PENDING','APPROVED','ENDED')
                               and d.month_range && daterange(cast(:from as date), cast(:to as date), '[)')""")
                    .param("t", tenant).param("b", m.nationalIdBidx()).param("fm", m.id()).param("from", from)
                    .param("to", toExclusive).query(LocalDate.class).list();
            if (!others.isEmpty()) {
                out.add(new Violation("claimed-by-another-taxpayer", "This person is already claimed as a dependant "
                        + "by another employee of the company for some of these months", others.getFirst()));
            }
        }
        return out;
    }

    private Optional<UUID> primaryOn(UUID personId, LocalDate day) {
        return db.jdbc().sql("""
                        select employment_id from hr.primary_employment
                         where tenant_id = :t and person_id = :p and purpose = 'PIT_FAMILY_DEDUCTION'
                           and status = 'ACTIVE' and valid_during @> cast(:d as date)""")
                .param("t", TenantContext.current().tenantId()).param("p", personId).param("d", day)
                .query(UUID.class).optional();
    }

    private static boolean liveOn(Employment e, LocalDate day) {
        return !"CANCELLED".equals(e.status()) && !e.hireDate().isAfter(day)
                && (e.endDate() == null || !e.endDate().isBefore(day));
    }

    private static boolean liveInMonth(Employment e, LocalDate month) {
        LocalDate end = month.plusMonths(1).minusDays(1);
        return !"CANCELLED".equals(e.status()) && !e.hireDate().isAfter(end)
                && (e.endDate() == null || !e.endDate().isBefore(month));
    }

    /** Last day of the month, clamped into the employment's period. */
    private static LocalDate referenceDay(Employment e, LocalDate month) {
        LocalDate day = month.plusMonths(1).minusDays(1);
        if (e.endDate() != null && e.endDate().isBefore(day)) {
            day = e.endDate();
        }
        return day.isBefore(e.hireDate()) ? e.hireDate() : day;
    }

    // ================================================================================================ evidence

    private void checkEvidence(UUID familyMemberId, List<UUID> documentIds) {
        for (UUID id : documentIds) {
            Documents.DocumentView d;
            try {
                d = documents.get(id);
            } catch (ApiException e) {
                throw ApiException.unprocessable("invalid-evidence", "Evidence document " + id + " is not available");
            }
            if (!FamilyCatalogues.OWNER_FAMILY_MEMBER.equals(d.ownerEntityType())
                    || !familyMemberId.equals(d.ownerEntityId())) {
                throw ApiException.unprocessable("invalid-evidence",
                        "Evidence document " + id + " does not belong to this family member");
            }
        }
    }

    private void linkEvidence(UUID dependantId, UUID personId, UUID employmentId, List<UUID> documentIds) {
        for (UUID doc : documentIds) {
            db.jdbc().sql("""
                            insert into hr.hr_document (tenant_id, id, person_id, employment_id, category_code, document_id,
                                   confidentiality, source_entity, source_id, created_by, updated_by)
                            values (:t, :id, :p, :e, :cat, :doc, 'CONFIDENTIAL', 'hr.pit_dependant', :src, :a, :a)""")
                    .param("t", TenantContext.current().tenantId()).param("id", Ids.newId()).param("p", personId)
                    .param("e", employmentId).param("cat", FamilyCatalogues.EVIDENCE_CATEGORY).param("doc", doc)
                    .param("src", dependantId).param("a", actor()).update();
        }
    }

    private void copyEvidence(UUID fromDependant, UUID toDependant, UUID personId, UUID employmentId) {
        linkEvidence(toDependant, personId, employmentId, evidence(fromDependant));
    }

    private List<UUID> evidence(UUID dependantId) {
        return db.jdbc().sql("""
                        select document_id from hr.hr_document
                         where tenant_id = :t and source_entity = 'hr.pit_dependant' and source_id = :id
                           and status = 'ACTIVE' order by created_at""")
                .param("t", TenantContext.current().tenantId()).param("id", dependantId).query(UUID.class).list();
    }

    // ================================================================================================ data

    private static final String ROW_SELECT = """
            select d.id, d.family_member_id, d.employment_id, d.from_month, d.to_month, d.dependant_reason_code,
                   d.status, d.tax_registration_ref, d.registered_at, d.approved_at, d.version, f.person_id,
                   f.full_name, f.relationship_code
              from hr.pit_dependant d
              join hr.family_member f on f.tenant_id = d.tenant_id and f.id = d.family_member_id
            """;

    private static Row row(ResultSet rs, int n) throws SQLException {
        OffsetDateTime approved = rs.getObject(10, OffsetDateTime.class);
        return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getObject(9, LocalDate.class), approved == null ? null : approved.toInstant(),
                rs.getInt(11), rs.getObject(12, UUID.class), rs.getString(13), rs.getString(14));
    }

    private Row row(UUID personId, UUID id) {
        return db.jdbc().sql(ROW_SELECT + " where d.tenant_id = :t and d.id = :id and f.person_id = :p and d.status <> 'DRAFT'")
                .param("t", TenantContext.current().tenantId()).param("id", id).param("p", personId)
                .query(DependantService::row).optional()
                .orElseThrow(() -> ApiException.notFound("Dependant", id));
    }

    private List<DependantView> views(UUID personId) {
        List<Row> rows = db.jdbc().sql(ROW_SELECT + " where d.tenant_id = :t and f.person_id = :p order by d.from_month, f.full_name")
                .param("t", TenantContext.current().tenantId()).param("p", personId)
                .query(DependantService::row).list();
        List<DependantView> out = new ArrayList<>();
        for (Row r : rows) {
            if ("DRAFT".equals(r.status())) {
                continue;
            }
            Optional<Row> endRequest = rows.stream().filter(x -> "DRAFT".equals(x.status())
                    && x.familyMemberId().equals(r.familyMemberId()) && x.fromMonth().equals(r.fromMonth())
                    && List.of("APPROVED", "ENDED").contains(r.status())).findFirst();
            out.add(new DependantView(r.id(), r.personId(), r.familyMemberId(), r.memberName(), r.relationshipCode(),
                    r.employmentId(), r.fromMonth(), r.toMonth(), r.reasonCode(), r.status(),
                    endRequest.map(Row::toMonth).orElse(null), endRequest.map(Row::id).orElse(null),
                    r.taxRegistrationRef(), r.registeredAt(), r.approvedAt(), evidence(r.id()), r.version()));
        }
        return out;
    }

    private DependantView view(UUID personId, UUID id) {
        return views(personId).stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Dependant", id));
    }

    private void insert(UUID id, UUID familyMemberId, UUID employmentId, LocalDate from, LocalDate to, String reason,
            String status, String taxRegistrationRef, LocalDate registeredAt) {
        boolean approved = "APPROVED".equals(status);
        try {
            db.jdbc().sql("""
                            insert into hr.pit_dependant (tenant_id, id, family_member_id, employment_id, from_month,
                                   to_month, dependant_reason_code, status, tax_registration_ref, registered_at,
                                   approved_by, approved_at, created_by, updated_by)
                            values (:t, :id, :fm, :emp, :from, :to, :reason, :status, :ref, :reg, :appBy,
                                    case when :approved then now() end, :actor, :actor)""")
                    .param("t", TenantContext.current().tenantId()).param("id", id).param("fm", familyMemberId)
                    .param("emp", employmentId).param("from", from).param("to", to).param("reason", reason)
                    .param("status", status).param("ref", taxRegistrationRef).param("reg", registeredAt)
                    .param("appBy", approved ? actor() : null).param("approved", approved).param("actor", actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("dependant-already-claimed",
                    "The family member is already claimed as a dependant for some of these months");
        }
    }

    private List<Employment> employments(UUID personId) {
        return db.jdbc().sql("""
                        select id, legal_entity_id, status, hire_date, end_date from hr.employment
                         where tenant_id = :t and person_id = :p""")
                .param("t", TenantContext.current().tenantId()).param("p", personId)
                .query((rs, n) -> new Employment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class)))
                .list();
    }

    private Employment employment(UUID id) {
        return db.jdbc().sql("""
                        select id, legal_entity_id, status, hire_date, end_date from hr.employment
                         where tenant_id = :t and id = :id""")
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query((rs, n) -> new Employment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Employment", id));
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }

    static LocalDate firstOfMonth(LocalDate d) {
        return d.withDayOfMonth(1);
    }
}
