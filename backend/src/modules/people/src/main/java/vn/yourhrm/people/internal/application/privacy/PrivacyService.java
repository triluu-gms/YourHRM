package vn.yourhrm.people.internal.application.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.core.api.DocumentGeneration;
import vn.yourhrm.core.api.DocumentGeneration.GenerateRequest;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonEvents.PersonalDataChanged;
import vn.yourhrm.people.api.PrivacyRequests;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrJson;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.common.crypto.SubjectKeys;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * People's DSR handlers (BR-PPL-153, US-PPL-088, OUT-33; Decree 13/2023 Art. 14-16, PDPL 2025 {@code [verify-legal]}).
 *
 * <p><b>Export</b>: every row of every {@code hr} base table that refers to the person ({@code person_id}) or to one
 * of the person's employments ({@code employment_id}), plus the allowances of the salary records and the service
 * periods of the separations, discovered from the catalogue so that a new table is included automatically. Encrypted
 * columns of the person's own data are decrypted into the package; blind indexes are dropped; family members' P3
 * values (third parties) stay masked. The salary rows are read from the table, not the masking view: the package is
 * the subject's own data, released under the privileged {@code hr.dsr.execute} with a sensitive-read audit entry.
 * The PDF (Core template {@code HR_DSR_EXPORT}) lists the same sections without P3 values, since it is stored in
 * Core's document service.
 *
 * <p><b>Anonymisation</b>: refused while an employment is live (the contract is the lawful basis). The labour, SI and
 * tax records must be kept for {@value #LP_RETENTION_YEARS} years after the last employment ended
 * ({@code [verify-legal]}, legal parameter, fail closed). During that period it is RESTRICTED: optional data is
 * deleted (contacts, emergency contacts, non-permanent addresses, consent-based special statuses, bank accounts never
 * used for pay, family members without a PIT claim, whose subject keys are destroyed too), the bank accounts pay was
 * made to are kept CLOSED with the number scrubbed (payment evidence of the retained payroll records), religion and
 * the other consent-based person fields are cleared. Afterwards it is FULL: every optional table (addresses, identity
 * documents, education, family members and their claims, bank accounts, special statuses, work permits) is deleted,
 * the identity is replaced by a pseudonym and the person's subject key destroyed; the labour records without a
 * DELETE grant (person, employment, assignment, contract, salary, decision, separation) stay as anonymous statistics.
 * Both set {@code hr.person.is_anonymised}. Every step is audited.
 */
@Service
public class PrivacyService implements PrivacyRequests {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    /** Years the labour / SI / tax records are kept after the last employment ended {@code [verify-legal]}. */
    public static final String LP_RETENTION_YEARS = "privacy.retention.labour_records_years";
    public static final String TEMPLATE = "HR_DSR_EXPORT";
    public static final String DOCUMENT_TYPE = "HR_DSR_EXPORT";
    public static final String OWNER = "hr.dsr_export";
    static final String ANONYMOUS = "Đã ẩn danh";

    /** Tables left out of the export: technical (outbox / inbox). */
    private static final Set<String> EXCLUDED = Set.of("outbox_event", "inbox_event");
    /** Third-party data inside the person's file: P3 stays masked. */
    private static final Set<String> THIRD_PARTY = Set.of("family_member", "emergency_contact");
    /** Optional special statuses that rest on consent (BR-PPL-151). */
    private static final List<String> CONSENT_STATUSES = List.of("PARTY_MEMBER", "YOUTH_UNION_MEMBER");

    private final HrDb db;
    private final HrJson json;
    private final PiiCipher cipher;
    private final EmployeeAccess access;
    private final PeopleLegalParameters legal;
    private final DocumentGeneration generation;
    private final ObjectProvider<SubjectKeys> subjectKeys;
    private final SensitiveDataAccess sensitive;
    private final HrOutbox outbox;
    private final AuditLog audit;
    private final BusinessClock clock;

    public PrivacyService(HrDb db, HrJson json, PiiCipher cipher, EmployeeAccess access, PeopleLegalParameters legal,
            DocumentGeneration generation, ObjectProvider<SubjectKeys> subjectKeys, SensitiveDataAccess sensitive,
            HrOutbox outbox, AuditLog audit, BusinessClock clock) {
        this.db = db;
        this.json = json;
        this.cipher = cipher;
        this.access = access;
        this.legal = legal;
        this.generation = generation;
        this.subjectKeys = subjectKeys;
        this.sensitive = sensitive;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    // ================================================================================================== export

    @Override
    @HrTransactional
    public ExportPackage exportPersonData(UUID personId, String requestRef) {
        access.requirePerson(PeoplePermissions.DSR_EXECUTE, personId, clock.today());
        step("DSR_EXPORT_STARTED", personId, requestRef, Map.of());
        sensitive.recordSensitiveRead(PeoplePermissions.DSR_EXECUTE, "person", personId, null);
        List<String> warnings = new ArrayList<>();
        Map<String, List<Map<String, Object>>> data = collect(personId, true, warnings);
        step("DSR_EXPORT_COLLECTED", personId, requestRef, Map.of("sections", String.valueOf(data.size()),
                "rows", String.valueOf(data.values().stream().mapToInt(List::size).sum())));
        UUID pdf = pdf(personId, requestRef, collect(personId, false, new ArrayList<>()), warnings);
        step("DSR_EXPORT_COMPLETED", personId, requestRef, pdf == null ? Map.of("pdf", "none")
                : Map.of("pdfDocumentId", pdf.toString()));
        return new ExportPackage(personId, requestRef, Instant.now(), data, pdf, warnings);
    }

    /** The person's rows per table; {@code reveal}: decrypt the person's own P3 values, else drop them. */
    private Map<String, List<Map<String, Object>>> collect(UUID personId, boolean reveal, List<String> warnings) {
        UUID t = tenant();
        Integer kv = db.jdbc().sql("select subject_key_version from hr.person where tenant_id = :t and id = :p")
                .param("t", t).param("p", personId).query(Integer.class).optional()
                .orElseThrow(() -> ApiException.notFound("Person", personId));
        Map<String, String> tables = new LinkedHashMap<>();
        db.jdbc().sql("""
                        select c.table_name, string_agg(c.column_name, ',') from information_schema.columns c
                          join information_schema.tables x on x.table_schema = c.table_schema
                               and x.table_name = c.table_name and x.table_type = 'BASE TABLE'
                         where c.table_schema = 'hr'
                           and c.column_name in ('person_id','employment_id','salary_record_id','separation_id')
                         group by c.table_name order by c.table_name""")
                .query((rs, n) -> tables.put(rs.getString(1), rs.getString(2))).list();
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("person", rows("select to_jsonb(x)::text from hr.person x where x.tenant_id = :t and x.id = :p",
                personId));
        tables.forEach((table, cols) -> {
            if (EXCLUDED.contains(table)) {
                return;
            }
            Set<String> c = Set.of(cols.split(","));
            String where;
            if (c.contains("person_id")) {
                where = "x.person_id = :p";
            } else if (c.contains("employment_id")) {
                where = "x.employment_id in (select id from hr.employment where tenant_id = :t and person_id = :p)";
            } else if (c.contains("salary_record_id")) {
                where = "x.salary_record_id in (select s.id from hr.salary_record s join hr.employment e on "
                        + "e.tenant_id = s.tenant_id and e.id = s.employment_id where s.tenant_id = :t and e.person_id = :p)";
            } else {
                where = "x.separation_id in (select s.id from hr.separation s join hr.employment e on "
                        + "e.tenant_id = s.tenant_id and e.id = s.employment_id where s.tenant_id = :t and e.person_id = :p)";
            }
            List<Map<String, Object>> rows = rows("select to_jsonb(x)::text from hr." + table + " x where x.tenant_id = :t and "
                    + where, personId);
            if (!rows.isEmpty()) {
                out.put(table, rows);
            }
        });
        out.forEach((table, rows) -> rows.forEach(r -> clean(table, r, personId, kv, reveal, warnings)));
        return out;
    }

    private List<Map<String, Object>> rows(String sql, UUID personId) {
        return db.jdbc().sql(sql).param("t", tenant()).param("p", personId).query(String.class).list().stream()
                .map(this::toMap).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String text) {
        return new LinkedHashMap<String, Object>((Map<String, Object>) json.read(text, Map.class));
    }

    private void clean(String table, Map<String, Object> row, UUID personId, int personKv, boolean reveal,
            List<String> warnings) {
        row.remove("tenant_id");
        List<String> keys = new ArrayList<>(row.keySet());
        for (String k : keys) {
            if (k.endsWith("_bidx")) {
                row.remove(k);
            } else if (k.endsWith("_enc")) {
                Object v = row.remove(k);
                if (!reveal || THIRD_PARTY.contains(table) || v == null) {
                    continue;
                }
                String column = k.substring(0, k.length() - 4);
                int kv = row.get("subject_key_version") instanceof Number n ? n.intValue() : personKv;
                try {
                    byte[] sealed = HexFormat.of().parseHex(v.toString().substring(2));
                    row.put(column, cipher.decrypt(Subject.person(tenant(), personId), column, sealed, kv));
                } catch (RuntimeException ex) {
                    row.put(column, null);
                    String w = table + "." + column + " cannot be decrypted (subject key destroyed)";
                    if (!warnings.contains(w)) {
                        warnings.add(w);
                    }
                }
            }
        }
    }

    private UUID pdf(UUID personId, String requestRef, Map<String, List<Map<String, Object>>> data,
            List<String> warnings) {
        UUID t = tenant();
        Optional<UUID> le = db.jdbc().sql("""
                        select legal_entity_id from hr.employment where tenant_id = :t and person_id = :p
                         order by hire_date desc limit 1""")
                .param("t", t).param("p", personId).query(UUID.class).optional();
        if (le.isEmpty()) {
            warnings.add("No PDF: the person has no employment (no issuing legal entity)");
            return null;
        }
        Map<String, Object> person = data.get("person").getFirst();
        StringBuilder text = new StringBuilder();
        data.forEach((table, rows) -> {
            text.append("== ").append(table).append(" (").append(rows.size()).append(")\n");
            rows.forEach(r -> text.append(r.entrySet().stream().filter(e -> e.getValue() != null)
                    .map(e -> e.getKey() + ": " + e.getValue()).reduce((a, b) -> a + "; " + b).orElse("")).append('\n'));
        });
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("person.full_name", String.valueOf(person.get("full_name")));
        f.put("request.ref", requestRef == null ? "" : requestRef);
        f.put("export.date", clock.today().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        f.put("export.sections", String.join(", ", data.keySet()));
        f.put("export.content", text.toString());
        try {
            return generation.generate(new GenerateRequest(TEMPLATE, le.get(), personId, personId,
                    "Dữ liệu cá nhân - " + person.get("full_name"), clock.today(), f)).documentId();
        } catch (RuntimeException ex) {
            log.info("DSR export PDF not generated: {}", ex.getMessage());
            warnings.add("No PDF: template " + TEMPLATE + " is not available (" + ex.getMessage() + ")");
            return null;
        }
    }

    // ============================================================================================ anonymisation

    @Override
    @HrTransactional
    public AnonymisationConfirmation anonymise(UUID personId, String requestRef) {
        LocalDate today = clock.today();
        access.requirePerson(PeoplePermissions.DSR_EXECUTE, personId, today);
        UUID t = tenant();
        var j = db.jdbc();
        j.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "hr.person." + personId)
                .query((rs, n) -> 1).single();
        List<String> live = j.sql("""
                        select employee_no from hr.employment where tenant_id = :t and person_id = :p
                           and status in ('PRE_HIRE','ACTIVE','SUSPENDED')""")
                .param("t", t).param("p", personId).query(String.class).list();
        if (!live.isEmpty()) {
            throw ApiException.unprocessable("employment-live", "The person has a live employment " + live
                    + ": the labour contract is the lawful basis of the processing; anonymise after it ended");
        }
        step("DSR_ANONYMISE_STARTED", personId, requestRef, Map.of());
        LocalDate lastEnd = j.sql("""
                        select max(coalesce(end_date, hire_date)) from hr.employment where tenant_id = :t
                           and person_id = :p and status <> 'CANCELLED'""")
                .param("t", t).param("p", personId).query(LocalDate.class).optional().orElse(null);
        LocalDate retainedUntil = null;
        if (lastEnd != null) {
            int years = legal.require(LP_RETENTION_YEARS, Scope.tenant(), today).asInt();
            retainedUntil = lastEnd.plusYears(years);
        }
        boolean full = retainedUntil == null || !retainedUntil.isAfter(today);
        List<String> cleared = new ArrayList<>();
        List<String> retained = new ArrayList<>();
        List<UUID> shredMembers = new ArrayList<>();

        // optional data, erased in both modes
        clear(personId, cleared, "person_contact", j.sql("delete from hr.person_contact where tenant_id = :t and person_id = :p"));
        clear(personId, cleared, "emergency_contact",
                j.sql("delete from hr.emergency_contact where tenant_id = :t and person_id = :p"));
        if (full) {
            clear(personId, cleared, "bank_account", j.sql("delete from hr.bank_account where tenant_id = :t and person_id = :p"));
        } else {
            // accounts never used for pay go; those pay was made to stay as scrubbed evidence of the retained payroll
            clear(personId, cleared, "bank_account (never used for pay)", j.sql("""
                    delete from hr.bank_account where tenant_id = :t and person_id = :p
                       and status in ('PENDING_VERIFICATION','REJECTED')"""));
            clear(personId, cleared, "bank_account (closed, number scrubbed)", j.sql("""
                    update hr.bank_account set status = 'CLOSED', holder_name = '***', account_no_enc = decode('', 'hex'),
                           account_no_bidx = decode(md5(id::text), 'hex'), account_no_last4 = '****',
                           step_up_auth_ref = null, version = version + 1
                     where tenant_id = :t and person_id = :p"""));
        }
        clear(personId, cleared, "person_address" + (full ? "" : " (except PERMANENT)"), j.sql("""
                delete from hr.person_address where tenant_id = :t and person_id = :p"""
                + (full ? "" : " and address_type <> 'PERMANENT'")));
        clear(personId, cleared, full ? "special_status" : "special_status (consent-based)", j.sql("""
                delete from hr.special_status where tenant_id = :t and person_id = :p"""
                + (full ? "" : " and status_type in (:types)")).param("types", CONSENT_STATUSES));
        j.sql("""
                        update hr.person set religion_enc = null, preferred_name = null, photo_document_id = null,
                               version = version + 1 where tenant_id = :t and id = :p""")
                .param("t", t).param("p", personId).update();
        cleared.add("person.religion, preferred_name, photo");
        String memberFilter = full ? "" : """
                 and not exists (select 1 from hr.pit_dependant d where d.tenant_id = m.tenant_id
                                    and d.family_member_id = m.id)""";
        shredMembers.addAll(j.sql("select m.id from hr.family_member m where m.tenant_id = :t and m.person_id = :p "
                        + memberFilter)
                .param("t", t).param("p", personId).query(UUID.class).list());
        if (!shredMembers.isEmpty()) {
            j.sql("delete from hr.pit_dependant where tenant_id = :t and family_member_id in (:ids)")
                    .param("t", t).param("ids", shredMembers).update();
            j.sql("delete from hr.family_member where tenant_id = :t and id in (:ids)")
                    .param("t", t).param("ids", shredMembers).update();
            cleared.add("family_member (" + shredMembers.size() + (full ? ")" : " without PIT claim)"));
        }
        step("DSR_OPTIONAL_DATA_CLEARED", personId, requestRef, Map.of("mode", full ? "FULL" : "RESTRICTED"));

        if (full) {
            clear(personId, cleared, "person_identity_doc", j.sql("""
                    delete from hr.person_identity_doc where tenant_id = :t and person_id = :p"""));
            clear(personId, cleared, "education_qualification", j.sql("""
                    delete from hr.education_qualification where tenant_id = :t and person_id = :p"""));
            clear(personId, cleared, "work_permit", j.sql("""
                    delete from hr.work_permit where tenant_id = :t and person_id = :p"""));
            String pseudonym = "ANON-" + personId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            j.sql("""
                            update hr.person set family_name = :anon, middle_name = null, given_name = :pseudo,
                                   full_name = :anon || ' ' || :pseudo, latin_name = null, birth_date = null,
                                   national_id_type = null, national_id_enc = null, national_id_bidx = null,
                                   national_id_last4 = null, tax_code_enc = null, tax_code_bidx = null,
                                   tax_code_last4 = null, si_number_enc = null, si_number_bidx = null,
                                   si_number_last4 = null, hi_card_no_enc = null, hi_card_no_last4 = null,
                                   ethnicity_enc = null, hi_facility_code = null, si_book_custody = null
                             where tenant_id = :t and id = :p""")
                    .param("anon", ANONYMOUS).param("pseudo", pseudonym).param("t", t).param("p", personId).update();
            cleared.add("person identity (names, birth date, national id, tax code, SI no., HI card, ethnicity)");
            retained.add("employment, assignment, contract, salary, decision, separation history (anonymous)");
        } else {
            retained.addAll(List.of("person identity and identifiers (labour book, SI, PIT)", "permanent address",
                    "employment, assignment, contract, salary, decision, separation, insurance participation",
                    "family members with PIT dependant claims", "education (labour book)", "identity documents",
                    "bank accounts pay was made to (closed, number scrubbed)", "special statuses (labour law)",
                    "work permits"));
        }
        j.sql("update hr.person set is_anonymised = true, anonymised_at = now() where tenant_id = :t and id = :p")
                .param("t", t).param("p", personId).update();
        outbox.publish(new PersonalDataChanged(t, personId, today, full
                ? List.of("family_name", "given_name", "full_name", "birth_date", "national_id", "tax_code", "si_number")
                : List.of("contact", "address", "bank_account"), null, null, null, "PERSON_ANONYMISED"));

        // crypto-shredding last: irreversible, so only once every People write of this transaction succeeded
        int shredded = 0;
        SubjectKeys keys = subjectKeys.getIfAvailable();
        if (keys != null) {
            for (UUID m : shredMembers) {
                keys.destroy(t, SubjectKeys.FAMILY_MEMBER, m);
                shredded++;
            }
            if (full) {
                keys.destroy(t, SubjectKeys.PERSON, personId);
                shredded++;
            }
        }
        step("DSR_CRYPTO_SHREDDED", personId, requestRef, Map.of("subjects", String.valueOf(shredded)));
        AnonymisationConfirmation c = new AnonymisationConfirmation(personId, requestRef, full ? "FULL" : "RESTRICTED",
                full ? null : retainedUntil, cleared, retained, shredded, Instant.now());
        step("DSR_ANONYMISE_COMPLETED", personId, requestRef, Map.of("mode", c.mode(),
                "retainedUntil", String.valueOf(c.retainedUntil())));
        return c;
    }

    private void clear(UUID personId, List<String> cleared, String what, org.springframework.jdbc.core.simple.JdbcClient.StatementSpec sql) {
        int n = sql.param("t", tenant()).param("p", personId).update();
        if (n > 0) {
            cleared.add(what + " (" + n + ")");
        }
    }

    private void step(String action, UUID personId, String requestRef, Map<String, String> details) {
        Map<String, String> d = new LinkedHashMap<>(details);
        if (requestRef != null) {
            d.put("requestRef", requestRef);
        }
        audit.record(new BusinessAction(action, "hr", "person", personId, null, d));
    }
}
