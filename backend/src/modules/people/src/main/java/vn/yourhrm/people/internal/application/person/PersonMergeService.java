package vn.yourhrm.people.internal.application.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonEvents.PersonalDataChanged;
import vn.yourhrm.people.api.PersonEvents.PersonsMerged;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Merging duplicate persons (BR-PPL-001, US-PPL-004; needed after opening-balance imports, RK2). The duplicate's
 * child rows in EVERY {@code hr} table with a {@code person_id} column (employments, documents, addresses, contacts,
 * dependants, bank accounts, statuses, permits ...) are re-pointed to the surviving person in one statement, so the
 * composite foreign keys (person, employment) are checked once at its end. P3 values encrypted under the duplicate's
 * subject key are re-encrypted under the survivor's ({@code <column>} without {@code _enc} is the AAD column name).
 * Identifiers missing on the survivor are taken over; different identifiers block. Overlaps the constraints forbid
 * (two live employments in one legal entity, overlapping address periods ...) block with the constraint named.
 *
 * <p>The preview runs the same merge and rolls it back. The duplicate row stays (no DELETE grant on hr.person) with
 * its identifiers removed and {@code is_anonymised = true}; the before/after data is in the row audit, the reason in
 * a MERGE business audit. Publishes {@code people.PersonsMerged} and {@code people.PersonalDataChanged}.
 */
@Service
public class PersonMergeService {

    /** {@code rowsToMove}: per {@code hr} table; {@code identifiersTaken}: P3 fields copied to the survivor. */
    public record MergePreview(UUID personId, UUID duplicatePersonId, Map<String, Integer> rowsToMove,
            List<String> identifiersTaken, List<String> adjustments, List<String> conflicts, boolean mergeable,
            boolean merged) {
    }

    /** Person-subject P3 identifier columns of hr.person ({@code <name>_enc}), with their blind index if any. */
    private static final String[][] IDENTIFIERS = {{"national_id", "national_id_bidx"}, {"tax_code", "tax_code_bidx"},
            {"si_number", "si_number_bidx"}, {"hi_card_no", null}, {"ethnicity", null}, {"religion", null}};
    /** Tables whose P3 columns are under another subject's key (not re-encrypted). */
    private static final List<String> OWN_SUBJECT_TABLES = List.of("family_member");

    private final HrDb db;
    private final PersonSupport support;
    private final PiiCipher cipher;
    private final HrOutbox outbox;
    private final AuditLog audit;

    public PersonMergeService(HrDb db, PersonSupport support, PiiCipher cipher, HrOutbox outbox, AuditLog audit) {
        this.db = db;
        this.support = support;
        this.cipher = cipher;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Dry run: everything {@link #merge} would do, rolled back. */
    @HrTransactional
    public MergePreview preview(UUID personId, UUID duplicateId) {
        try {
            return run(personId, duplicateId, null, null);
        } finally {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }

    @HrTransactional
    public MergePreview merge(UUID personId, UUID duplicateId, int version, String reason) {
        MergePreview r = run(personId, duplicateId, version, reason);
        if (!r.mergeable()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "merge-conflict",
                    "The persons cannot be merged: " + String.join("; ", r.conflicts()), Map.of("conflicts", r.conflicts()));
        }
        return r;
    }

    private MergePreview run(UUID survivor, UUID duplicate, Integer version, String reason) {
        if (survivor.equals(duplicate)) {
            throw ApiException.unprocessable("same-person", "A person cannot be merged into itself");
        }
        support.requirePerson(PeoplePermissions.PERSON_MERGE, survivor);
        support.requirePerson(PeoplePermissions.PERSON_MERGE, duplicate);
        UUID t = support.tenant();
        // lock both rows in a fixed order
        for (UUID id : survivor.compareTo(duplicate) < 0 ? List.of(survivor, duplicate) : List.of(duplicate, survivor)) {
            support.lockPerson(id);
        }
        boolean dupMerged = db.jdbc().sql("select is_anonymised from hr.person where tenant_id = :t and id = :id")
                .param("t", t).param("id", duplicate).query(Boolean.class).single();
        if (dupMerged) {
            throw ApiException.unprocessable("person-anonymised", "The duplicate person was already merged or anonymised");
        }
        if (version != null) {
            int current = db.jdbc().sql("select version from hr.person where tenant_id = :t and id = :id")
                    .param("t", t).param("id", survivor).query(Integer.class).single();
            if (current != version) {
                throw ApiException.conflict("stale-version", "Person " + survivor + " was changed meanwhile; reload and retry");
            }
        }
        List<String> conflicts = new ArrayList<>();
        List<String> adjustments = new ArrayList<>();
        Subject from = Subject.person(t, duplicate);
        Subject to = Subject.person(t, survivor);

        // 1. identifiers on hr.person
        Map<String, Object> s = personRow(survivor);
        Map<String, Object> d = personRow(duplicate);
        int sKv = ((Number) s.get("subject_key_version")).intValue();
        int dKv = ((Number) d.get("subject_key_version")).intValue();
        List<String> taken = new ArrayList<>();
        Map<String, Object> survivorSets = new LinkedHashMap<>();
        for (String[] f : IDENTIFIERS) {
            byte[] dEnc = (byte[]) d.get(f[0] + "_enc");
            byte[] sEnc = (byte[]) s.get(f[0] + "_enc");
            if (dEnc == null) {
                continue;
            }
            String dValue = cipher.decrypt(from, f[0], dEnc, dKv);
            if (sEnc == null) {
                taken.add(f[0]);
                Sealed sealed = cipher.encrypt(to, f[0], dValue);
                survivorSets.put(f[0], sealed);
                sKv = sealed.keyVersion();
            } else if (!dValue.equals(cipher.decrypt(to, f[0], sEnc, sKv))) {
                if (f[1] != null) {
                    conflicts.add("identifier-conflict: " + f[0] + " differs between the persons");
                } else {
                    adjustments.add(f[0] + ": the surviving person's value is kept");
                }
            }
        }
        if (!conflicts.isEmpty()) {
            return new MergePreview(survivor, duplicate, Map.of(), taken, adjustments, conflicts, false, false);
        }
        // clear the duplicate first (unique national_id_bidx), then complete the survivor
        db.jdbc().sql("""
                        update hr.person set national_id_type = null, national_id_enc = null, national_id_bidx = null,
                               national_id_last4 = null, tax_code_enc = null, tax_code_bidx = null, tax_code_last4 = null,
                               si_number_enc = null, si_number_bidx = null, si_number_last4 = null, hi_card_no_enc = null,
                               hi_card_no_last4 = null, ethnicity_enc = null, religion_enc = null, is_anonymised = true,
                               anonymised_at = now(), version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id""")
                .param("a", support.actor()).param("t", t).param("id", duplicate).update();
        for (Map.Entry<String, Object> e : survivorSets.entrySet()) {
            String col = e.getKey();
            Sealed sealed = (Sealed) e.getValue();
            String extra = switch (col) {
                case "national_id" -> ", national_id_type = :type, national_id_bidx = :bidx, national_id_last4 = :l4";
                case "tax_code", "si_number" -> ", " + col + "_bidx = :bidx, " + col + "_last4 = :l4";
                case "hi_card_no" -> ", hi_card_no_last4 = :l4";
                default -> "";
            };
            db.jdbc().sql("update hr.person set " + col + "_enc = :enc" + extra + " where tenant_id = :t and id = :id")
                    .param("enc", sealed.ciphertext()).param("type", d.get("national_id_type"))
                    .param("bidx", col.equals("national_id") ? d.get("national_id_bidx") : d.get(col + "_bidx"))
                    .param("l4", d.get(col + "_last4")).param("t", t).param("id", survivor).update();
        }
        db.jdbc().sql("""
                        update hr.person set subject_key_version = :kv, version = version + 1, updated_by = :a,
                               verification_status = case when :taken then 'UNVERIFIED' else verification_status end
                         where tenant_id = :t and id = :id""")
                .param("kv", sKv).param("taken", !taken.isEmpty()).param("a", support.actor()).param("t", t)
                .param("id", survivor).update();

        // 2. resolve one-per-person flags on the duplicate's rows
        adjust(adjustments, "person_contact: duplicate primary contacts demoted", """
                update hr.person_contact c set is_primary = false
                 where c.tenant_id = :t and c.person_id = :d and c.is_primary
                   and exists (select 1 from hr.person_contact x where x.tenant_id = c.tenant_id and x.person_id = :s
                                  and x.contact_type = c.contact_type and x.is_primary)""", t, survivor, duplicate);
        adjust(adjustments, "education_qualification: duplicate 'highest' flag removed", """
                update hr.education_qualification q set is_highest = false
                 where q.tenant_id = :t and q.person_id = :d and q.is_highest
                   and exists (select 1 from hr.education_qualification x where x.tenant_id = q.tenant_id
                                  and x.person_id = :s and x.is_highest)""", t, survivor, duplicate);
        adjust(adjustments, "person_identity_doc: documents held by both persons superseded on the duplicate", """
                update hr.person_identity_doc i set status = 'SUPERSEDED'
                 where i.tenant_id = :t and i.person_id = :d and i.status = 'ACTIVE'
                   and exists (select 1 from hr.person_identity_doc x where x.tenant_id = i.tenant_id and x.person_id = :s
                                  and x.doc_type = i.doc_type and x.issuing_country_code = i.issuing_country_code
                                  and x.doc_no_bidx = i.doc_no_bidx and x.status = 'ACTIVE')""", t, survivor, duplicate);

        // 3. re-encrypt child P3 values under the survivor's key, then re-point every child row in one statement
        List<String> tables = childTables();
        Map<String, Integer> moved = new LinkedHashMap<>();
        for (String table : tables) {
            int n = db.jdbc().sql("select count(*) from hr." + table + " where tenant_id = :t and person_id = :d")
                    .param("t", t).param("d", duplicate).query(Integer.class).single();
            if (n > 0) {
                moved.put(table, n);
                if (!OWN_SUBJECT_TABLES.contains(table)) {
                    reEncrypt(table, from, to, duplicate);
                }
            }
        }
        if (!moved.isEmpty()) {
            StringBuilder sql = new StringBuilder("with ");
            int i = 0;
            for (String table : moved.keySet()) {
                sql.append(i++ == 0 ? "" : ", ").append("u_").append(table).append(" as (update hr.").append(table)
                        .append(" set person_id = :s where tenant_id = :t and person_id = :d returning 1)");
            }
            sql.append(" select ").append(String.join(" + ", moved.keySet().stream()
                    .map(x -> "(select count(*) from u_" + x + ")").toList()));
            try {
                db.jdbc().sql(sql.toString()).param("s", survivor).param("t", t).param("d", duplicate)
                        .query(Long.class).single();
            } catch (DataIntegrityViolationException e) {
                conflicts.add("constraint: " + constraintOf(e));
                return new MergePreview(survivor, duplicate, moved, taken, adjustments, conflicts, false, false);
            }
        }
        if (reason == null) {
            return new MergePreview(survivor, duplicate, moved, taken, adjustments, conflicts, true, false);
        }

        // 4. events and business audit
        LocalDate today = support.today();
        outbox.publish(new PersonsMerged(t, survivor, duplicate, today, moved));
        List<String> changed = new ArrayList<>(taken);
        changed.add("merged_person");
        outbox.publish(new PersonalDataChanged(t, survivor, today, changed, null, null, null, "PERSONS_MERGED"));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("mergedPersonId", duplicate.toString());
        details.put("reason", reason);
        details.put("movedRows", moved.toString());
        details.put("identifiersTaken", String.join(",", taken));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                audit.record(new AuditLog.BusinessAction("MERGE", "hr", "person", survivor, null, details));
            }
        });
        return new MergePreview(survivor, duplicate, moved, taken, adjustments, conflicts, true, true);
    }

    private void adjust(List<String> adjustments, String what, String sql, UUID t, UUID s, UUID d) {
        int n = db.jdbc().sql(sql).param("t", t).param("s", s).param("d", d).update();
        if (n > 0) {
            adjustments.add(what + " (" + n + ")");
        }
    }

    /** Every hr base table with a person_id column, except the person itself and the outbox / inbox (and their partitions: the event history is never rewritten). */
    private List<String> childTables() {
        return db.jdbc().sql("""
                        select c.table_name from information_schema.columns c
                          join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
                         where c.table_schema = 'hr' and c.column_name = 'person_id' and t.table_type = 'BASE TABLE'
                           and c.table_name <> 'person' and c.table_name !~ '^(outbox_event|inbox_message)'
                         order by c.table_name""")
                .query(String.class).list();
    }

    /** Re-encrypts the {@code *_enc} columns of the duplicate's rows in {@code table} under the survivor's key. */
    private void reEncrypt(String table, Subject from, Subject to, UUID duplicate) {
        List<String> encColumns = db.jdbc().sql("""
                        select column_name from information_schema.columns
                         where table_schema = 'hr' and table_name = :tbl and column_name like '%\\_enc' order by 1""")
                .param("tbl", table).query(String.class).list();
        if (encColumns.isEmpty()) {
            return;
        }
        boolean hasKv = db.jdbc().sql("""
                        select exists (select 1 from information_schema.columns where table_schema = 'hr'
                                          and table_name = :tbl and column_name = 'subject_key_version')""")
                .param("tbl", table).query(Boolean.class).single();
        UUID t = from.tenantId();
        db.jdbc().sql("select * from hr." + table + " where tenant_id = :t and person_id = :d")
                .param("t", t).param("d", duplicate)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("kv", hasKv ? rs.getInt("subject_key_version") : 1);
                    for (String c : encColumns) {
                        row.put(c, rs.getBytes(c));
                    }
                    return row;
                })
                .list()
                .forEach(row -> {
                    int kv = (Integer) row.get("kv");
                    List<String> sets = new ArrayList<>();
                    Map<String, Object> p = new LinkedHashMap<>();
                    int newKv = kv;
                    for (String c : encColumns) {
                        byte[] enc = (byte[]) row.get(c);
                        if (enc == null) {
                            continue;
                        }
                        String column = c.substring(0, c.length() - "_enc".length());
                        String plain;
                        try {
                            plain = cipher.decrypt(from, column, enc, kv);
                        } catch (IllegalStateException e) {
                            throw ApiException.unprocessable("merge-reencryption-failed", "A value of hr." + table
                                    + "." + c + " cannot be re-encrypted for the surviving person");
                        }
                        Sealed sealed = cipher.encrypt(to, column, plain);
                        newKv = sealed.keyVersion();
                        sets.add(c + " = :" + c);
                        p.put(c, sealed.ciphertext());
                    }
                    if (sets.isEmpty()) {
                        return;
                    }
                    if (hasKv) {
                        sets.add("subject_key_version = :kv");
                        p.put("kv", newKv);
                    }
                    db.jdbc().sql("update hr." + table + " set " + String.join(", ", sets)
                                    + " where tenant_id = :t and id = :id")
                            .params(p).param("t", t).param("id", row.get("id")).update();
                });
    }

    private Map<String, Object> personRow(UUID id) {
        return db.jdbc().sql("select * from hr.person where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        m.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                    }
                    return m;
                }).single();
    }

    private static final java.util.regex.Pattern CONSTRAINT = java.util.regex.Pattern.compile("constraint \"([^\"]+)\"");

    private static String constraintOf(DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        java.util.regex.Matcher m = CONSTRAINT.matcher(msg);
        return m.find() ? m.group(1) : "unknown";
    }
}
