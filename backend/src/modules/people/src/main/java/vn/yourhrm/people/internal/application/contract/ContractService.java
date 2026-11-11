package vn.yourhrm.people.internal.application.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.core.api.Numbering;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.api.ContractEvents;
import vn.yourhrm.people.api.ContractEvents.ContractChanged;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.assignment.RetroFlags;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.application.contract.ContractRecords.Employment;
import vn.yourhrm.people.internal.domain.contract.ContractType;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Labour contracts (EP-PPL-06; BR-PPL-050/051/053/054/057, US-PPL-047/048/050/052/056): every statutory type,
 * numbered from Core's {@code CONTRACT_NO} series per legal entity, never overlapping within an employment
 * ({@code contract_period_ex}), with the consecutive-definite counter {@code definite_seq} per employment.
 *
 * <p>Lifecycle: created DRAFT (a renewal / conversion / post-probation contract links {@code previous_contract_id});
 * the paper signature (sign date + signed scan in Core's document service) makes it ACTIVE and publishes
 * {@code people.ContractChanged} SIGNED, RENEWED or CONVERTED; a draft can be CANCELLED (its number is kept); the
 * daily monitor marks ACTIVE contracts past their end EXPIRED. The DDL has no SIGNED status: a signed contract is
 * ACTIVE with a sign date.
 *
 * <p>Foreign workers (BR-PPL-020): a contract may not run beyond the validity of the employment's ACTIVE work permit
 * unless a renewal of that permit is recorded (APPLIED / ACTIVE row with {@code renewal_of_id}); read directly from
 * {@code hr.work_permit}, whose service belongs to the person stream.
 */
@Service
public class ContractService {

    /** Where an initial contract comes from ({@code source} of the hire / opening-balance import, ADR-0037). */
    public record InitialSource(String source, int priorDefiniteCount, String legacyContractNo, LocalDate signDate) {

        public static final Set<String> SOURCES = Set.of("APP", "OPENING_LOAD", "IMPORT", "REC_HANDOFF");

        public static InitialSource app() {
            return new InitialSource("APP", 0, null, null);
        }

        public InitialSource {
            if (!SOURCES.contains(source)) {
                throw ApiException.unprocessable("invalid-source", "Unknown source " + source);
            }
            if (priorDefiniteCount < 0) {
                throw ApiException.unprocessable("invalid-prior-definite-count", "The prior count cannot be negative");
            }
        }

        boolean imported() {
            return !"APP".equals(source) && signDate != null;
        }
    }

    private final HrDb db;
    private final ContractRecords records;
    private final ProbationRules probation;
    private final PeopleLegalParameters legal;
    private final Settings settings;
    private final Numbering numbering;
    private final Documents documents;
    private final AuditLog audit;
    private final HrOutbox outbox;
    private final EmployeeAccess access;
    private final BusinessClock clock;
    /** US-PPL-080 (integration stream): retro flag of {@code ContractChanged}. */
    private final RetroFlags retro;

    public ContractService(HrDb db, ContractRecords records, ProbationRules probation, PeopleLegalParameters legal,
            Settings settings, Numbering numbering, Documents documents, AuditLog audit, HrOutbox outbox,
            EmployeeAccess access, BusinessClock clock, RetroFlags retro) {
        this.retro = retro;
        this.db = db;
        this.records = records;
        this.probation = probation;
        this.legal = legal;
        this.settings = settings;
        this.numbering = numbering;
        this.documents = documents;
        this.audit = audit;
        this.outbox = outbox;
        this.access = access;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ queries

    /** All contracts of an employment, oldest first (US-PPL-047, read API OUT-03). */
    @HrTransactional(readOnly = true)
    public List<ContractView> list(UUID employmentId) {
        access.require(PeoplePermissions.CONTRACT_VIEW, employmentId, clock.today());
        return records.ofEmployment(employmentId);
    }

    @HrTransactional(readOnly = true)
    public ContractView get(UUID contractId) {
        ContractView c = records.require(contractId);
        requireScope(PeoplePermissions.CONTRACT_VIEW, c);
        return c;
    }

    /**
     * The contract in force on {@code asOf} (NFR-PPL-001 as-of). {@code hr.contract} is Pattern A without transaction
     * time: {@code asKnownAt} only drops contracts recorded after that instant; later status or term changes of the
     * remaining rows are not reconstructed (the row audit in Core holds them).
     */
    @HrTransactional(readOnly = true)
    public Optional<ContractView> asOf(UUID employmentId, LocalDate asOf, Instant asKnownAt) {
        LocalDate date = asOf == null ? clock.today() : asOf;
        access.require(PeoplePermissions.CONTRACT_VIEW, employmentId, date);
        return records.ofEmployment(employmentId).stream()
                .filter(c -> ContractRecords.LIVE.contains(c.status()))
                .filter(c -> asKnownAt == null || !c.createdAt().isAfter(asKnownAt))
                .filter(c -> !c.startDate().isAfter(date) && (c.endDate() == null || !c.endDate().isBefore(date)))
                .reduce((a, b) -> b);
    }

    // ------------------------------------------------------------------------------------------------ create

    /** A new DRAFT contract entered by HR (US-PPL-047). */
    @HrTransactional
    public ContractView create(UUID employmentId, ContractTerms terms) {
        access.require(PeoplePermissions.CONTRACT_CREATE, employmentId, clock.today());
        return insert(records.requireEmployment(employmentId), terms, null, InitialSource.app());
    }

    /**
     * The first contract of an employment, for the hire and opening-balance streams: runs in the caller's People
     * transaction without permission checks (the caller authorised the hire / import). With source APP it is a
     * DRAFT signed later ({@link #markSigned}); an imported contract with a sign date is created ACTIVE and publishes
     * {@code ContractChanged} SIGNED ({@code initialLoad} for OPENING_LOAD). {@code priorDefiniteCount} is the number
     * of consecutive definite contracts signed before go-live (BR-PPL-053 "imported prior count"); on a probation
     * agreement it is carried in {@code definite_seq} so that the next definite contract continues the count.
     */
    @HrTransactional
    public ContractView createInitial(UUID employmentId, ContractTerms terms, InitialSource source) {
        Employment emp = records.requireEmployment(employmentId);
        if (!records.liveOfEmployment(employmentId).isEmpty()) {
            throw ApiException.conflict("contract-exists", "The employment already has a contract");
        }
        return insert(emp, terms, null, source);
    }

    /**
     * Renewal from the expiry list (US-PPL-052): the next DEFINITE contract, starting the day after the end of
     * {@code contractId} unless another start is given; the consecutive-definite rule applies.
     */
    @HrTransactional
    public ContractView renew(UUID contractId, ContractTerms terms) {
        ContractView prev = records.require(contractId);
        requireScope(PeoplePermissions.CONTRACT_CREATE, prev);
        requireFollowUp(prev);
        LocalDate start = terms.startDate() != null ? terms.startDate() : prev.endDate().plusDays(1);
        return insert(records.requireEmployment(prev.employmentId()),
                terms.withTerm(ContractType.DEFINITE.name(), start, terms.endDate()), prev.id(), InitialSource.app());
    }

    /** Conversion from the expiry list (US-PPL-052): an INDEFINITE contract following {@code contractId}. */
    @HrTransactional
    public ContractView convert(UUID contractId, ContractTerms terms) {
        ContractView prev = records.require(contractId);
        requireScope(PeoplePermissions.CONTRACT_CREATE, prev);
        requireFollowUp(prev);
        LocalDate start = terms.startDate() != null ? terms.startDate() : prev.endDate().plusDays(1);
        return insert(records.requireEmployment(prev.employmentId()),
                terms.withTerm(ContractType.INDEFINITE.name(), start, null), prev.id(), InitialSource.app());
    }

    /**
     * The labour contract following a passed probation (US-PPL-049), created in the probation-result transaction.
     * No permission check: {@link ProbationService} checked it.
     */
    public ContractView afterProbation(ContractView probationContract, ContractTerms terms) {
        LocalDate start = terms.startDate() != null ? terms.startDate() : probationContract.endDate().plusDays(1);
        return insert(records.requireEmployment(probationContract.employmentId()),
                terms.withTerm(terms.contractTypeCode(), start, terms.endDate()), probationContract.id(),
                InitialSource.app());
    }

    private void requireFollowUp(ContractView prev) {
        if (!prev.type().countsAsDefinite() || prev.endDate() == null) {
            throw ApiException.unprocessable("not-renewable", "Only a definite-term contract can be renewed or converted");
        }
        if (!List.of("ACTIVE", "SUSPENDED", "EXPIRED").contains(prev.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Contract " + prev.contractNo() + " is "
                    + prev.status());
        }
        boolean followed = db.jdbc().sql("""
                        select exists (select 1 from hr.contract
                                        where tenant_id = :t and employment_id = :e and status not in ('CANCELLED','SUPERSEDED')
                                          and (previous_contract_id = :id or start_date > :end))""")
                .param("t", tenant()).param("e", prev.employmentId()).param("id", prev.id()).param("end", prev.endDate())
                .query(Boolean.class).single();
        if (followed) {
            throw ApiException.conflict("contract-already-followed", "Contract " + prev.contractNo()
                    + " already has a following contract (draft or signed)");
        }
    }

    private ContractView insert(Employment emp, ContractTerms t, UUID previousId, InitialSource source) {
        if (emp.closed()) {
            throw ApiException.unprocessable("employment-closed", "The employment is " + emp.status());
        }
        ContractType type = ContractType.of(t.contractTypeCode()).orElseThrow(() -> ApiException.unprocessable(
                "invalid-contract-type", "Unknown contract type " + t.contractTypeCode(),
                Map.of("allowed", List.of(ContractType.values()).stream().map(Enum::name).toList())));
        LocalDate start = t.startDate();
        LocalDate end = t.endDate();
        if (start == null) {
            throw ApiException.unprocessable("start-date-required", "The start date is required");
        }
        if (start.isBefore(emp.hireDate())) {
            throw ApiException.unprocessable("contract-before-hire", "The contract starts before the hire date "
                    + emp.hireDate());
        }
        if (type == ContractType.INDEFINITE && end != null) {
            throw ApiException.unprocessable("indefinite-has-no-end", "An indefinite contract has no end date");
        }
        if (type.requiresEndDate() && end == null) {
            throw ApiException.unprocessable("end-date-required", "A " + type + " contract requires an end date");
        }
        if (end != null && end.isBefore(start)) {
            throw ApiException.unprocessable("invalid-period", "The end date is before the start date");
        }
        if (type == ContractType.DEFINITE) {
            int max = legal.require(ContractKeys.DEFINITE_MAX_MONTHS, ProbationRules.scope(emp), start).asInt();
            if (!end.isBefore(start.plusMonths(max))) {
                throw ApiException.unprocessable("definite-term-too-long", "A definite contract lasts at most " + max
                        + " months", Map.of("maxMonths", max));
            }
        }
        if (type == ContractType.SEASONAL && !probation.shortTerm(emp, start, end)) {
            throw ApiException.unprocessable("seasonal-term-too-long",
                    "A seasonal / short-term contract must be shorter than the legal threshold; use DEFINITE");
        }
        if (overlapsLive(emp.id(), start, end)) {
            throw ApiException.unprocessable("contract-overlap",
                    "The period overlaps another contract of the employment");
        }
        ProbationRules.Probation prob = probation.check(emp, type, t);
        Integer seq = definiteSeq(emp, type, start, previousId == null ? source.priorDefiniteCount() : 0, t);
        checkWorkPermit(emp.id(), end);

        String number = source.legacyContractNo() != null ? source.legacyContractNo() : nextNumber(emp, start);
        boolean active = source.imported();
        UUID id = Ids.newId();
        // the exception is stored (and audited) only when it actually allowed a further definite contract
        boolean exception = seq != null && t.definiteExceptionCode() != null && exceeds(emp, start, seq);
        if (exception) {
            Map<String, String> details = new HashMap<>();
            details.put("exception", t.definiteExceptionCode());
            details.put("definiteSeq", String.valueOf(seq));
            details.put("contractNo", number);
            if (t.definiteExceptionNote() != null) {
                details.put("note", t.definiteExceptionNote());
            }
            audit.record(new AuditLog.BusinessAction("APPLY_EXCEPTION", "hr", "contract", id, emp.legalEntityId(),
                    details));
        }
        try {
            db.jdbc().sql("""
                            insert into hr.contract (tenant_id, id, employment_id, contract_no, contract_type_code,
                                   definite_seq, sign_date, start_date, end_date, probation_days, probation_level_code,
                                   signatory_name, signatory_title, previous_contract_id, definite_exception_code,
                                   definite_exception_note, source, status, created_by, updated_by)
                            values (:t, :id, :e, :no, :type, :seq, :sd, :start, :end, :pd, :pl, :sn, :st, :prev,
                                    :exc, :excNote, :source, :status, :actor, :actor)""")
                    .param("t", tenant()).param("id", id).param("e", emp.id()).param("no", number)
                    .param("type", type.name()).param("seq", seq).param("sd", active ? source.signDate() : null)
                    .param("start", start).param("end", end).param("pd", prob == null ? null : prob.days())
                    .param("pl", prob == null ? null : prob.levelCode())
                    .param("sn", t.signatoryName()).param("st", t.signatoryTitle()).param("prev", previousId)
                    .param("exc", exception ? t.definiteExceptionCode() : null)
                    .param("excNote", exception ? t.definiteExceptionNote() : null).param("source", source.source())
                    .param("status", active ? "ACTIVE" : "DRAFT").param("actor", actor())
                    .update();
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, number);
        }
        ContractView created = records.require(id);
        if (active) {
            publish(created, ContractEvents.SIGNED, "OPENING_LOAD".equals(source.source()));
        }
        return created;
    }

    private String nextNumber(Employment emp, LocalDate onDate) {
        try {
            return numbering.next(ContractKeys.CONTRACT_SEQUENCE, emp.legalEntityId(), onDate);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ApiException.unprocessable("numbering-not-configured", "Number series "
                    + ContractKeys.CONTRACT_SEQUENCE + " is not configured for this legal entity",
                    Map.of("sequence", ContractKeys.CONTRACT_SEQUENCE));
        }
    }

    private boolean overlapsLive(UUID employmentId, LocalDate start, LocalDate end) {
        return db.jdbc().sql("""
                        select exists (select 1 from hr.contract
                                        where tenant_id = :t and employment_id = :e
                                          and status not in ('DRAFT','CANCELLED','SUPERSEDED')
                                          and contract_period && daterange(cast(:s as date), cast(:end as date), '[]'))""")
                .param("t", tenant()).param("e", employmentId).param("s", start).param("end", end)
                .query(Boolean.class).single();
    }

    // ------------------------------------------------------------------------------- consecutive definite (053)

    /**
     * The n-th consecutive definite contract (BR-PPL-053, US-PPL-050): continues the count of the latest earlier
     * definite contract of the employment (a probation agreement in between does not break it, an indefinite or
     * other contract does), else starts after {@code priorCount} imported ones. Beyond
     * {@code contract.definite.max_consecutive} the contract must be indefinite unless a legal exception applies
     * ({@code contract.definite.consecutive_exceptions} or the tenant's extra list).
     */
    private Integer definiteSeq(Employment emp, ContractType type, LocalDate start, int priorCount, ContractTerms t) {
        int base = priorCount;
        for (ContractView c : records.liveOfEmployment(emp.id())) {
            if (!c.startDate().isBefore(start)) {
                continue;
            }
            if (c.type() == ContractType.PROBATION) {
                if (c.definiteSeq() != null) {
                    base = c.definiteSeq();
                    break;
                }
                continue;
            }
            base = c.type().countsAsDefinite() && c.definiteSeq() != null ? c.definiteSeq() : 0;
            break;
        }
        if (!type.countsAsDefinite()) {
            return type == ContractType.PROBATION && base > 0 ? base : null;
        }
        int seq = base + 1;
        if (exceeds(emp, start, seq)) {
            String code = t.definiteExceptionCode();
            if (code == null || code.isBlank()) {
                throw ApiException.unprocessable("indefinite-contract-required", "This would be definite contract no. "
                        + seq + " in a row: the next contract must be indefinite unless a legal exception applies",
                        Map.of("definiteSeq", seq, "allowedExceptions", exceptions(emp, start)));
            }
            if (!exceptions(emp, start).contains(code)) {
                throw ApiException.unprocessable("invalid-definite-exception", "Unknown exception " + code,
                        Map.of("allowedExceptions", exceptions(emp, start)));
            }
        }
        return seq;
    }

    private boolean exceeds(Employment emp, LocalDate start, int seq) {
        return seq > legal.require(ContractKeys.DEFINITE_MAX_CONSECUTIVE, ProbationRules.scope(emp), start).asInt();
    }

    private List<String> exceptions(Employment emp, LocalDate start) {
        Set<String> out = new LinkedHashSet<>();
        legal.require(ContractKeys.DEFINITE_EXCEPTIONS, ProbationRules.scope(emp), start).asList()
                .forEach(v -> out.add(String.valueOf(v)));
        Object extra = settings.value(ContractKeys.SETTING_DEFINITE_EXTRA_EXCEPTIONS,
                new Settings.Scope(emp.legalEntityId(), null), start);
        if (extra instanceof List<?> l) {
            l.forEach(v -> out.add(String.valueOf(v)));
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------------------- work permit (BR-PPL-020)

    /** Blocks a contract running beyond the employment's ACTIVE work permit unless its renewal is recorded. */
    void checkWorkPermit(UUID employmentId, LocalDate endInclusive) {
        record Permit(UUID id, LocalDate validTo) {
        }
        Optional<Permit> latest = db.jdbc().sql("""
                        select id, valid_to from hr.work_permit
                         where tenant_id = :t and employment_id = :e and status = 'ACTIVE'
                         order by valid_from desc limit 1""")
                .param("t", tenant()).param("e", employmentId)
                .query((rs, n) -> new Permit(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class)))
                .optional();
        if (latest.isEmpty() || latest.get().validTo() == null) {
            return;
        }
        LocalDate validTo = latest.get().validTo();   // exclusive
        if (endInclusive != null && endInclusive.isBefore(validTo)) {
            return;
        }
        boolean renewal = db.jdbc().sql("""
                        select exists (select 1 from hr.work_permit
                                        where tenant_id = :t and renewal_of_id = :id and status in ('APPLIED','ACTIVE'))""")
                .param("t", tenant()).param("id", latest.get().id()).query(Boolean.class).single();
        if (!renewal) {
            throw ApiException.unprocessable("contract-beyond-work-permit", "The contract runs beyond the work permit "
                    + "valid until " + validTo.minusDays(1) + " and no renewal is recorded (BR-PPL-020)",
                    Map.of("permitValidUntil", validTo.minusDays(1).toString()));
        }
    }

    // ------------------------------------------------------------------------------------------- signing (056)

    /**
     * Records the paper signature (US-PPL-056, R2-10): the signed scan goes to Core's document service (type
     * {@link ContractDocuments#CONTRACT_DOC}), the contract becomes ACTIVE, and {@code ContractChanged} is published
     * (RENEWED / CONVERTED when it follows a definite contract, else SIGNED).
     */
    @HrTransactional
    public ContractView sign(UUID contractId, LocalDate signDate, String fileName, String mediaType, byte[] scan,
            int expectedVersion) {
        ContractView c = records.require(contractId);
        requireScope(PeoplePermissions.CONTRACT_UPDATE, c);
        checkSignable(c, signDate, expectedVersion);
        Documents.DocumentView doc = documents.upload(new Documents.Upload(ContractDocuments.CONTRACT_DOC, c.id(),
                c.legalEntityId(), c.personId(), "HĐLĐ " + c.contractNo(), null, fileName, mediaType, scan));
        return activate(c, signDate, doc.id(), expectedVersion);
    }

    /**
     * Marks a DRAFT contract signed with a scan already stored in Core (the hire stream after its approval). No
     * permission check: the caller authorised its flow.
     */
    @HrTransactional
    public ContractView markSigned(UUID contractId, LocalDate signDate, UUID documentId, int expectedVersion) {
        ContractView c = records.require(contractId);
        checkSignable(c, signDate, expectedVersion);
        return activate(c, signDate, documentId, expectedVersion);
    }

    private void checkSignable(ContractView c, LocalDate signDate, int expectedVersion) {
        if (!"DRAFT".equals(c.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Contract " + c.contractNo() + " is "
                    + c.status() + "; only a draft can be signed");
        }
        if (c.version() != expectedVersion) {
            throw stale(c);
        }
        if (signDate == null || signDate.isAfter(clock.today())) {
            throw ApiException.unprocessable("invalid-sign-date", "The sign date is required and cannot be in the future");
        }
        checkWorkPermit(c.employmentId(), c.endDate());
    }

    private ContractView activate(ContractView c, LocalDate signDate, UUID documentId, int expectedVersion) {
        int updated;
        try {
            updated = db.jdbc().sql("""
                            update hr.contract set status = 'ACTIVE', sign_date = :sd, document_id = :doc,
                                   version = version + 1, updated_by = :actor
                             where tenant_id = :t and id = :id and version = :v and status = 'DRAFT'""")
                    .param("sd", signDate).param("doc", documentId).param("actor", actor())
                    .param("t", tenant()).param("id", c.id()).param("v", expectedVersion)
                    .update();
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, c.contractNo());
        }
        if (updated == 0) {
            throw stale(c);
        }
        ContractView signed = records.require(c.id());
        String subtype = ContractEvents.SIGNED;
        if (c.previousContractId() != null) {
            ContractView prev = records.require(c.previousContractId());
            if (prev.type().countsAsDefinite()) {
                subtype = signed.type() == ContractType.INDEFINITE ? ContractEvents.CONVERTED : ContractEvents.RENEWED;
            }
        }
        publish(signed, subtype, false);
        return signed;
    }

    /** Cancels a DRAFT contract; it keeps its number (the series has no holes to explain). */
    @HrTransactional
    public ContractView cancel(UUID contractId, int expectedVersion) {
        ContractView c = records.require(contractId);
        requireScope(PeoplePermissions.CONTRACT_UPDATE, c);
        if (!"DRAFT".equals(c.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Only a draft contract can be cancelled; "
                    + c.contractNo() + " is " + c.status() + " (ending a signed contract is a separation)");
        }
        int updated = db.jdbc().sql("""
                        update hr.contract set status = 'CANCELLED', version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :id and version = :v and status = 'DRAFT'""")
                .param("actor", actor()).param("t", tenant()).param("id", contractId).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw stale(c);
        }
        return records.require(contractId);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** {@code definiteExceptionCode} comes from the stored exception ({@code hr.contract.definite_exception_code}). */
    void publish(ContractView c, String subtype, boolean initialLoad) {
        outbox.publish(flagRetro(new ContractChanged(tenant(), c.legalEntityId(), c.personId(), c.employmentId(),
                c.startDate(), subtype, c.id(), c.contractNo(), c.contractTypeCode(), c.startDate(), c.endDate(),
                c.definiteSeq(), c.previousContractId(), c.definiteExceptionCode(), null, null, List.of(), null, null,
                null, initialLoad)));
    }

    /**
     * US-PPL-039/080 (added by the integration stream): sets {@code retro} when the effective date falls into a locked
     * pay period of the legal entity ({@code PayPeriodLocks}); opening-load events are never retro.
     */
    public ContractChanged flagRetro(ContractChanged e) {
        if (e.initialLoad()) {
            return e;
        }
        RetroFlags.Retro r = retro.of(e.legalEntityId(), e.effectiveDate());
        return e.withRetro(r.retro(), r.earliestAffectedPeriod());
    }

    public void requireScope(String permission, ContractView c) {
        access.require(permission, c.employmentId(), clock.today());
    }

    static ApiException stale(ContractView c) {
        return ApiException.conflict("stale-version", "Contract " + c.contractNo() + " was changed (version "
                + c.version() + ", status " + c.status() + "); reload and retry");
    }

    private static ApiException translate(DataIntegrityViolationException ex, String number) {
        String msg = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (msg.contains("contract_period_ex")) {
            return ApiException.unprocessable("contract-overlap", "The period overlaps another contract of the employment");
        }
        if (msg.contains("contract_no_uq")) {
            return ApiException.conflict("contract-number-taken", "Contract number " + number + " is already used");
        }
        return ApiException.unprocessable("invalid-contract", "The contract was refused by the database");
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
