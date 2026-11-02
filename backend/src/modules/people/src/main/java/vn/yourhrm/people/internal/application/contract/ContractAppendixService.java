package vn.yourhrm.people.internal.application.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Documents;
import vn.yourhrm.people.api.ContractEvents;
import vn.yourhrm.people.api.ContractEvents.ContractChanged;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Contract appendices (phụ lục, BR-PPL-055, US-PPL-053; Labour Code Art. 22 [verify-legal]): amended terms with an
 * effective date, never the contract term. Created DRAFT, signed on paper (scan in Core, {@code ContractChanged}
 * APPENDIX) or cancelled.
 *
 * <p>The terms are stored as given in {@code hr.contract_appendix.changes}; applying them (a new salary-record or
 * assignment version linked by {@code contract_appendix_id}) is done by the salary / assignment streams, which read
 * signed appendices through {@link #find}, {@link #ofContract} and {@link #signedEffectiveOn}.
 *
 * <p>Change keys: {@code salary} and {@code allowances} (pay terms, P2: shown only with {@code hr.compensation.view}),
 * {@code jobTitleId}, {@code positionId}, {@code orgUnitId}, {@code locationId} / {@code workLocation},
 * {@code workingTime} and {@code other}. Term keys (start / end date, type, duration) are refused.
 */
@Service
public class ContractAppendixService {

    /** Amendable terms (BR-PPL-055: pay, position, place of work, working time, other agreed terms). */
    public static final Set<String> CHANGE_KEYS = Set.of("salary", "allowances", "jobTitleId", "positionId",
            "orgUnitId", "locationId", "workLocation", "workingTime", "other");
    /** Pay terms (P2): masked for users without {@code hr.compensation.view}. */
    public static final Set<String> PAY_KEYS = Set.of("salary", "allowances");
    /** The contract term may not be changed by an appendix (Art. 22.2). */
    static final Set<String> TERM_KEYS = Set.of("startDate", "endDate", "contractTypeCode", "term", "duration",
            "contractType", "start_date", "end_date");

    public record AppendixView(UUID id, UUID contractId, UUID employmentId, UUID legalEntityId, String contractNo,
            String appendixNo, LocalDate effectiveDate, Map<String, Object> changes, boolean payTermsMasked,
            UUID documentId, String status, Instant createdAt, int version) {
    }

    private final HrDb db;
    private final HrJson json;
    private final ContractRecords records;
    private final ContractService contracts;
    private final EmployeeAccess access;
    private final Documents documents;
    private final HrOutbox outbox;
    private final BusinessClock clock;

    public ContractAppendixService(HrDb db, HrJson json, ContractRecords records, ContractService contracts,
            EmployeeAccess access, Documents documents, HrOutbox outbox, BusinessClock clock) {
        this.db = db;
        this.json = json;
        this.records = records;
        this.contracts = contracts;
        this.access = access;
        this.documents = documents;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------ reads for the salary / assignment streams

    /** An appendix with its full terms (no permission check: for People services applying the terms). */
    @HrTransactional(readOnly = true)
    public Optional<AppendixView> find(UUID appendixId) {
        return query(" where a.tenant_id = :t and a.id = :id", Map.of("id", appendixId), false).stream().findFirst();
    }

    /** All appendices of a contract, by number (no permission check). */
    @HrTransactional(readOnly = true)
    public List<AppendixView> ofContract(UUID contractId) {
        return query(" where a.tenant_id = :t and a.contract_id = :c order by a.appendix_no", Map.of("c", contractId),
                false);
    }

    /** SIGNED appendices of an employment effective on {@code date} or earlier, by effective date (no permission check). */
    @HrTransactional(readOnly = true)
    public List<AppendixView> signedEffectiveOn(UUID employmentId, LocalDate date) {
        return query("""
                 where a.tenant_id = :t and c.employment_id = :e and a.status = 'SIGNED' and a.effective_date <= :d
                 order by a.effective_date, a.appendix_no""", Map.of("e", employmentId, "d", date), false);
    }

    // ------------------------------------------------------------------------------------------- REST use cases

    /** Appendices of a contract for the UI; pay terms are masked without {@code hr.compensation.view}. */
    @HrTransactional(readOnly = true)
    public List<AppendixView> list(UUID contractId) {
        ContractView c = records.require(contractId);
        contracts.requireScope(PeoplePermissions.CONTRACT_VIEW, c);
        return query(" where a.tenant_id = :t and a.contract_id = :c order by a.appendix_no", Map.of("c", contractId),
                !access.can(PeoplePermissions.COMPENSATION_VIEW, c.employmentId(), clock.today()));
    }

    @HrTransactional(readOnly = true)
    public AppendixView get(UUID appendixId) {
        AppendixView a = find(appendixId).orElseThrow(() -> ApiException.notFound("Appendix", appendixId));
        access.require(PeoplePermissions.CONTRACT_VIEW, a.employmentId(), clock.today());
        return access.can(PeoplePermissions.COMPENSATION_VIEW, a.employmentId(), clock.today()) ? a : masked(a);
    }

    /** A DRAFT appendix numbered {@code <contract no>/PLnn}. Pay terms need {@code hr.compensation.update}. */
    @HrTransactional
    public AppendixView create(UUID contractId, LocalDate effectiveDate, Map<String, Object> changes) {
        ContractView c = records.require(contractId);
        contracts.requireScope(PeoplePermissions.CONTRACT_UPDATE, c);
        if (!c.type().labourContract()) {
            throw ApiException.unprocessable("appendix-not-allowed", "A " + c.contractTypeCode()
                    + " contract has no appendices");
        }
        if (!List.of("ACTIVE", "SUSPENDED").contains(c.status())) {
            throw ApiException.unprocessable("contract-not-signed", "Contract " + c.contractNo() + " is "
                    + c.status() + "; an appendix amends a signed contract in force");
        }
        if (effectiveDate == null || effectiveDate.isBefore(c.startDate())
                || (c.endDate() != null && effectiveDate.isAfter(c.endDate()))) {
            throw ApiException.unprocessable("appendix-outside-contract",
                    "The effective date must lie within the contract term");
        }
        validate(changes);
        if (changes.keySet().stream().anyMatch(PAY_KEYS::contains)) {
            access.require(PeoplePermissions.COMPENSATION_UPDATE, c.employmentId(), clock.today());
        }
        int n = db.jdbc().sql("select count(*) from hr.contract_appendix where tenant_id = :t and contract_id = :c")
                .param("t", tenant()).param("c", contractId).query(Integer.class).single() + 1;
        String number = c.contractNo() + "/PL" + String.format("%02d", n);
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into hr.contract_appendix (tenant_id, id, contract_id, appendix_no, effective_date,
                                   changes, status, created_by, updated_by)
                            values (:t, :id, :c, :no, :eff, cast(:changes as jsonb), 'DRAFT', :actor, :actor)""")
                    .param("t", tenant()).param("id", id).param("c", contractId).param("no", number)
                    .param("eff", effectiveDate).param("changes", json.write(changes)).param("actor", actor())
                    .update();
        } catch (DataIntegrityViolationException ex) {
            throw ApiException.conflict("appendix-number-taken", "Appendix " + number + " exists; retry");
        }
        return get(id);
    }

    /** Paper signature of the appendix (sign date is the upload): SIGNED + {@code ContractChanged} APPENDIX. */
    @HrTransactional
    public AppendixView sign(UUID appendixId, String fileName, String mediaType, byte[] scan, int expectedVersion) {
        AppendixView a = find(appendixId).orElseThrow(() -> ApiException.notFound("Appendix", appendixId));
        access.require(PeoplePermissions.CONTRACT_UPDATE, a.employmentId(), clock.today());
        ContractView c = records.require(a.contractId());
        if (!"DRAFT".equals(a.status()) && !"PENDING_SIGNATURE".equals(a.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Appendix " + a.appendixNo() + " is "
                    + a.status());
        }
        if (!List.of("ACTIVE", "SUSPENDED").contains(c.status())) {
            throw ApiException.unprocessable("contract-not-signed", "Contract " + c.contractNo() + " is "
                    + c.status());
        }
        if (a.version() != expectedVersion) {
            throw stale(a);
        }
        Documents.DocumentView doc = documents.upload(new Documents.Upload(ContractDocuments.APPENDIX_DOC, a.id(),
                c.legalEntityId(), c.personId(), "Phụ lục " + a.appendixNo(), null, fileName, mediaType, scan));
        int updated = db.jdbc().sql("""
                        update hr.contract_appendix set status = 'SIGNED', document_id = :doc, version = version + 1,
                               updated_by = :actor
                         where tenant_id = :t and id = :id and version = :v and status in ('DRAFT','PENDING_SIGNATURE')""")
                .param("doc", doc.id()).param("actor", actor()).param("t", tenant()).param("id", appendixId)
                .param("v", expectedVersion).update();
        if (updated == 0) {
            throw stale(a);
        }
        outbox.publish(new ContractChanged(tenant(), c.legalEntityId(), c.personId(), c.employmentId(),
                a.effectiveDate(), ContractEvents.APPENDIX, c.id(), c.contractNo(), c.contractTypeCode(),
                c.startDate(), c.endDate(), c.definiteSeq(), c.previousContractId(), null, a.id(), a.appendixNo(),
                new ArrayList<>(new java.util.TreeSet<>(a.changes().keySet())), null, null, null, false));
        return get(appendixId);
    }

    @HrTransactional
    public AppendixView cancel(UUID appendixId, int expectedVersion) {
        AppendixView a = find(appendixId).orElseThrow(() -> ApiException.notFound("Appendix", appendixId));
        access.require(PeoplePermissions.CONTRACT_UPDATE, a.employmentId(), clock.today());
        if (!"DRAFT".equals(a.status()) && !"PENDING_SIGNATURE".equals(a.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "Only an unsigned appendix can be cancelled");
        }
        int updated = db.jdbc().sql("""
                        update hr.contract_appendix set status = 'CANCELLED', version = version + 1, updated_by = :actor
                         where tenant_id = :t and id = :id and version = :v and status in ('DRAFT','PENDING_SIGNATURE')""")
                .param("actor", actor()).param("t", tenant()).param("id", appendixId).param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw stale(a);
        }
        return get(appendixId);
    }

    static void validate(Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw ApiException.unprocessable("appendix-without-changes", "An appendix amends at least one term");
        }
        List<String> term = changes.keySet().stream().filter(TERM_KEYS::contains).toList();
        if (!term.isEmpty()) {
            throw ApiException.unprocessable("appendix-changes-term", "An appendix may not change the contract term "
                    + "(BR-PPL-055); renew or convert the contract instead", Map.of("fields", term));
        }
        List<String> unknown = changes.keySet().stream().filter(k -> !CHANGE_KEYS.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw ApiException.unprocessable("invalid-appendix-change", "Unknown amended terms",
                    Map.of("fields", unknown, "allowed", CHANGE_KEYS.stream().sorted().toList()));
        }
    }

    private List<AppendixView> query(String where, Map<String, Object> params, boolean maskPay) {
        var spec = db.jdbc().sql("""
                        select a.id, a.contract_id, c.employment_id, e.legal_entity_id, c.contract_no, a.appendix_no,
                               a.effective_date, a.changes::text, a.document_id, a.status, a.created_at, a.version
                          from hr.contract_appendix a
                          join hr.contract c on c.tenant_id = a.tenant_id and c.id = a.contract_id
                          join hr.employment e on e.tenant_id = c.tenant_id and e.id = c.employment_id""" + where)
                .param("t", tenant());
        for (var p : params.entrySet()) {
            spec = spec.param(p.getKey(), p.getValue());
        }
        List<AppendixView> out = spec.query((rs, n) -> new AppendixView(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                rs.getString(6), rs.getObject(7, LocalDate.class), json.map(rs.getString(8)), false,
                rs.getObject(9, UUID.class), rs.getString(10), rs.getObject(11, OffsetDateTime.class).toInstant(),
                rs.getInt(12))).list();
        return maskPay ? out.stream().map(ContractAppendixService::masked).toList() : out;
    }

    private static AppendixView masked(AppendixView a) {
        if (a.changes().keySet().stream().noneMatch(PAY_KEYS::contains)) {
            return a;
        }
        Map<String, Object> visible = new LinkedHashMap<>(a.changes());
        PAY_KEYS.forEach(k -> visible.computeIfPresent(k, (key, v) -> "***"));
        return new AppendixView(a.id(), a.contractId(), a.employmentId(), a.legalEntityId(), a.contractNo(),
                a.appendixNo(), a.effectiveDate(), visible, true, a.documentId(), a.status(), a.createdAt(),
                a.version());
    }

    private static ApiException stale(AppendixView a) {
        return ApiException.conflict("stale-version", "Appendix " + a.appendixNo() + " was changed (version "
                + a.version() + ", status " + a.status() + "); reload and retry");
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
