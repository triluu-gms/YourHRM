package vn.yourhrm.people.internal.application.family;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.MasterData;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.auth.api.MaskLevel;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Family members of an employee-person (BR-PPL-016, US-PPL-027): relationship from Core's {@code RELATIONSHIP} code
 * list, name, birth date, national id and tax code (P3, encrypted with the family member's OWN subject key so they can
 * be crypto-shredded independently, blind index + last 4 for duplicate checks and masked display), income status and
 * disability / deceased flags, which feed the PIT dependant eligibility rules (US-PPL-028).
 *
 * <p>Not stored (no DDL column, v0.5): occupation and contact details of the family member (reported to the lead);
 * student status is captured per dependant claim (reason {@code CHILD_STUDENT} + evidence).
 *
 * <p>Identifier updates follow "null = keep, blank = clear" because masked screens never hold the plain value.
 */
@Service
public class FamilyMemberService {

    static final String RELATIONSHIP_LIST = "RELATIONSHIP";
    private static final Pattern NATIONAL_ID = Pattern.compile("^[A-Z0-9]{6,20}$");
    private static final Pattern TAX_CODE = Pattern.compile("^[0-9]{10}([0-9]{2,3})?$");

    /** Attributes of a family member (PUT-style). */
    public record Attributes(String relationshipCode, String fullName, LocalDate birthDate, String nationalId,
            String taxCode, Boolean hasIncomeAboveThreshold, BigDecimal averageMonthlyIncome, boolean disabled,
            boolean deceased) {
    }

    /**
     * A family member as shown to the caller. P3 identifiers are always masked; with a masking level other than NONE
     * the birth date is reduced to the year and the (health-derived) disability flag is hidden.
     */
    public record FamilyMemberView(UUID id, UUID personId, String relationshipCode, String fullName,
            LocalDate birthDate, Integer birthYear, String nationalIdMasked, String taxCodeMasked,
            Boolean hasIncomeAboveThreshold, Boolean disabled, boolean deceased, boolean masked, int version) {
    }

    /** Unmasked identifiers (audited sensitive read). */
    public record Identifiers(UUID id, String nationalId, String taxCode) {
    }

    /** What the dependant rules read. */
    record Member(UUID id, UUID personId, String relationshipCode, String fullName, LocalDate birthDate,
            Boolean hasIncomeAboveThreshold, boolean disabled, boolean deceased, byte[] nationalIdBidx, int version,
            String nationalIdLast4, String taxCodeLast4) {
    }

    private final HrDb db;
    private final PiiCipher cipher;
    private final EmployeeAccess access;
    private final HrMasking masking;
    private final MasterData masterData;
    private final PeopleLegalParameters legal;
    private final SensitiveDataAccess sensitive;
    private final BusinessClock clock;

    public FamilyMemberService(HrDb db, PiiCipher cipher, EmployeeAccess access, HrMasking masking,
            MasterData masterData, PeopleLegalParameters legal, SensitiveDataAccess sensitive, BusinessClock clock) {
        this.db = db;
        this.cipher = cipher;
        this.access = access;
        this.masking = masking;
        this.masterData = masterData;
        this.legal = legal;
        this.sensitive = sensitive;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------ HR

    @HrTransactional(readOnly = true)
    public List<FamilyMemberView> list(UUID personId) {
        access.requirePerson(PeoplePermissions.DEPENDANT_VIEW, personId, clock.today());
        MaskLevel level = masking.apply(PeoplePermissions.DEPENDANT_VIEW);
        return rows(personId).stream().map(m -> view(m, level != MaskLevel.NONE)).toList();
    }

    @HrTransactional(readOnly = true)
    public FamilyMemberView get(UUID personId, UUID id) {
        access.requirePerson(PeoplePermissions.DEPENDANT_VIEW, personId, clock.today());
        MaskLevel level = masking.apply(PeoplePermissions.DEPENDANT_VIEW);
        return view(load(personId, id), level != MaskLevel.NONE);
    }

    @HrTransactional
    public FamilyMemberView create(UUID personId, Attributes a) {
        access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        return view(load(personId, insert(personId, a)), false);
    }

    @HrTransactional
    public FamilyMemberView update(UUID personId, UUID id, Attributes a, int version) {
        access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        Member current = load(personId, id);
        if (current.version() != version) {
            throw ApiException.conflict("stale-version", "The family member was changed by someone else; reload it");
        }
        validate(a);
        UUID tenant = TenantContext.current().tenantId();
        Subject subject = Subject.familyMember(tenant, id);
        Boolean income = incomeFlag(a);
        StringBuilder sql = new StringBuilder("""
                update hr.family_member
                   set relationship_code = :rel, full_name = :name, birth_date = :birth,
                       has_income_above_threshold = :income, is_disabled = :disabled, is_deceased = :deceased,
                       updated_at = now(), updated_by = :actor, version = version + 1""");
        var stmt = new java.util.LinkedHashMap<String, Object>();
        String nid = normalised(a.nationalId());
        if (a.nationalId() != null) {
            byte[] bidx = nid == null ? null : cipher.blindIndex(tenant, nid);
            if (bidx != null) {
                checkDuplicate(personId, id, bidx);
            }
            Sealed s = cipher.encrypt(subject, "national_id", nid);
            sql.append(", national_id_enc = :nidEnc, national_id_bidx = :nidBidx, national_id_last4 = :nidLast4");
            stmt.put("nidEnc", s == null ? null : s.ciphertext());
            stmt.put("nidBidx", bidx);
            stmt.put("nidLast4", PiiCipher.last4(nid));
            if (s != null) {
                sql.append(", subject_key_version = :kv");
                stmt.put("kv", s.keyVersion());
            }
        }
        if (a.taxCode() != null) {
            String tax = normalised(a.taxCode());
            Sealed s = cipher.encrypt(subject, "tax_code", tax);
            sql.append(", tax_code_enc = :taxEnc, tax_code_bidx = :taxBidx, tax_code_last4 = :taxLast4");
            stmt.put("taxEnc", s == null ? null : s.ciphertext());
            stmt.put("taxBidx", cipher.blindIndex(tenant, tax));
            stmt.put("taxLast4", PiiCipher.last4(tax));
            if (s != null && !stmt.containsKey("kv")) {
                sql.append(", subject_key_version = :kv");
                stmt.put("kv", s.keyVersion());
            }
        }
        sql.append(" where tenant_id = :t and id = :id and version = :v");
        int n = db.jdbc().sql(sql.toString()).params(stmt)
                .param("rel", a.relationshipCode()).param("name", a.fullName().strip()).param("birth", a.birthDate())
                .param("income", income).param("disabled", a.disabled()).param("deceased", a.deceased())
                .param("actor", TenantContext.current().userAccountId())
                .param("t", tenant).param("id", id).param("v", version)
                .update();
        if (n == 0) {
            throw ApiException.conflict("stale-version", "The family member was changed by someone else; reload it");
        }
        return view(load(personId, id), false);
    }

    @HrTransactional
    public void delete(UUID personId, UUID id, int version) {
        access.requirePerson(PeoplePermissions.DEPENDANT_UPDATE, personId, clock.today());
        Member m = load(personId, id);
        if (m.version() != version) {
            throw ApiException.conflict("stale-version", "The family member was changed by someone else; reload it");
        }
        UUID tenant = TenantContext.current().tenantId();
        boolean claimed = db.jdbc().sql("""
                        select exists (select 1 from hr.pit_dependant
                                        where tenant_id = :t and family_member_id = :id and status <> 'REJECTED')""")
                .param("t", tenant).param("id", id).query(Boolean.class).single();
        boolean contact = db.jdbc().sql("""
                        select exists (select 1 from hr.emergency_contact where tenant_id = :t and family_member_id = :id)""")
                .param("t", tenant).param("id", id).query(Boolean.class).single();
        if (claimed || contact) {
            throw ApiException.conflict("family-member-in-use",
                    "The family member has PIT dependant claims or is an emergency contact; end the claim instead");
        }
        db.jdbc().sql("delete from hr.pit_dependant where tenant_id = :t and family_member_id = :id")
                .param("t", tenant).param("id", id).update();
        db.jdbc().sql("delete from hr.family_member where tenant_id = :t and id = :id")
                .param("t", tenant).param("id", id).update();
    }

    /** The unmasked identifiers: needs {@code hr.person.view_sensitive} on the person; audited (BR-CORE-034). */
    @HrTransactional(readOnly = true)
    public Identifiers identifiers(UUID personId, UUID id) {
        LocalDate today = clock.today();
        access.requirePerson(PeoplePermissions.DEPENDANT_VIEW, personId, today);
        if (!canViewSensitive(personId, today)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "sensitive-not-permitted",
                    "Showing identifiers needs " + PeoplePermissions.PERSON_VIEW_SENSITIVE);
        }
        load(personId, id);
        UUID tenant = TenantContext.current().tenantId();
        Identifiers out = db.jdbc().sql("""
                        select national_id_enc, tax_code_enc, subject_key_version
                          from hr.family_member where tenant_id = :t and id = :id""")
                .param("t", tenant).param("id", id)
                .query((rs, n) -> {
                    Subject s = Subject.familyMember(tenant, id);
                    int kv = rs.getInt(3);
                    return new Identifiers(id, cipher.decrypt(s, "national_id", rs.getBytes(1), kv),
                            cipher.decrypt(s, "tax_code", rs.getBytes(2), kv));
                }).single();
        sensitive.recordSensitiveRead(PeoplePermissions.PERSON_VIEW_SENSITIVE, "hr.family_member", id, null);
        return out;
    }

    boolean canViewSensitive(UUID personId, LocalDate asOf) {
        try {
            access.requirePerson(PeoplePermissions.PERSON_VIEW_SENSITIVE, personId, asOf);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ ESS

    /** The employee's own family (US-PPL-074): identifiers masked, the rest shown (it is their own data). */
    @HrTransactional(readOnly = true)
    public List<FamilyMemberView> listOwn(UUID selfPersonId) {
        return rows(selfPersonId).stream().map(m -> view(m, false)).toList();
    }

    /** ESS: the employee adds a family member before requesting a dependant registration (US-PPL-074). */
    @HrTransactional
    public FamilyMemberView createOwn(UUID selfPersonId, Attributes a) {
        return view(load(selfPersonId, insert(selfPersonId, a)), false);
    }

    // ------------------------------------------------------------------------------------------------ internal

    /**
     * Opening-balance import (NFR-PPL-007, ADR-0037): creates a family member in the caller's People transaction
     * without permission checks (the import type authorised the run). Same validation as the UI.
     */
    @HrTransactional
    public UUID createForInitialLoad(UUID personId, Attributes a) {
        return insert(personId, a);
    }

    /** The member of {@code personId}, or 404. */
    @HrTransactional(readOnly = true)
    Member load(UUID personId, UUID id) {
        return db.jdbc().sql(SELECT + " where tenant_id = :t and person_id = :p and id = :id")
                .param("t", TenantContext.current().tenantId()).param("p", personId).param("id", id)
                .query(FamilyMemberService::member).optional()
                .orElseThrow(() -> ApiException.notFound("Family member", id));
    }

    /** The member by id alone (any person of the tenant), or 404. */
    @HrTransactional(readOnly = true)
    Member load(UUID id) {
        return db.jdbc().sql(SELECT + " where tenant_id = :t and id = :id")
                .param("t", TenantContext.current().tenantId()).param("id", id)
                .query(FamilyMemberService::member).optional()
                .orElseThrow(() -> ApiException.notFound("Family member", id));
    }

    private static final String SELECT = """
            select id, person_id, relationship_code, full_name, birth_date, has_income_above_threshold, is_disabled,
                   is_deceased, national_id_bidx, version, national_id_last4, tax_code_last4
              from hr.family_member""";

    private List<Member> rows(UUID personId) {
        return db.jdbc().sql(SELECT + " where tenant_id = :t and person_id = :p order by birth_date nulls last, full_name")
                .param("t", TenantContext.current().tenantId()).param("p", personId)
                .query(FamilyMemberService::member).list();
    }

    private static Member member(ResultSet rs, int n) throws SQLException {
        return new Member(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), (Boolean) rs.getObject(6), rs.getBoolean(7), rs.getBoolean(8),
                rs.getBytes(9), rs.getInt(10), rs.getString(11), rs.getString(12));
    }

    private FamilyMemberView view(Member m, boolean masked) {
        return new FamilyMemberView(m.id(), m.personId(), m.relationshipCode(), m.fullName(),
                masked ? null : m.birthDate(), m.birthDate() == null ? null : m.birthDate().getYear(),
                m.nationalIdLast4() == null ? null : "••••" + m.nationalIdLast4(),
                m.taxCodeLast4() == null ? null : "••••" + m.taxCodeLast4(),
                m.hasIncomeAboveThreshold(), masked ? null : m.disabled(), m.deceased(), masked, m.version());
    }

    private UUID insert(UUID personId, Attributes a) {
        validate(a);
        UUID tenant = TenantContext.current().tenantId();
        boolean personExists = db.jdbc().sql("select exists (select 1 from hr.person where tenant_id = :t and id = :p)")
                .param("t", tenant).param("p", personId).query(Boolean.class).single();
        if (!personExists) {
            throw ApiException.notFound("Person", personId);
        }
        UUID id = Ids.newId();
        Subject subject = Subject.familyMember(tenant, id);
        String nid = normalised(a.nationalId());
        String tax = normalised(a.taxCode());
        byte[] nidBidx = cipher.blindIndex(tenant, nid);
        if (nidBidx != null) {
            checkDuplicate(personId, null, nidBidx);
        }
        Sealed nidSealed = cipher.encrypt(subject, "national_id", nid);
        Sealed taxSealed = cipher.encrypt(subject, "tax_code", tax);
        int keyVersion = nidSealed != null ? nidSealed.keyVersion() : taxSealed != null ? taxSealed.keyVersion() : 1;
        db.jdbc().sql("""
                        insert into hr.family_member (tenant_id, id, person_id, relationship_code, full_name, birth_date,
                               national_id_enc, national_id_bidx, national_id_last4, tax_code_enc, tax_code_bidx,
                               tax_code_last4, has_income_above_threshold, is_disabled, is_deceased, subject_key_version,
                               created_by, updated_by)
                        values (:t, :id, :p, :rel, :name, :birth, :nidEnc, :nidBidx, :nidLast4, :taxEnc, :taxBidx,
                                :taxLast4, :income, :disabled, :deceased, :kv, :actor, :actor)""")
                .param("t", tenant).param("id", id).param("p", personId).param("rel", a.relationshipCode())
                .param("name", a.fullName().strip()).param("birth", a.birthDate())
                .param("nidEnc", nidSealed == null ? null : nidSealed.ciphertext()).param("nidBidx", nidBidx)
                .param("nidLast4", PiiCipher.last4(nid))
                .param("taxEnc", taxSealed == null ? null : taxSealed.ciphertext())
                .param("taxBidx", cipher.blindIndex(tenant, tax)).param("taxLast4", PiiCipher.last4(tax))
                .param("income", incomeFlag(a)).param("disabled", a.disabled()).param("deceased", a.deceased())
                .param("kv", keyVersion).param("actor", TenantContext.current().userAccountId())
                .update();
        return id;
    }

    private void validate(Attributes a) {
        LocalDate today = clock.today();
        if (a.relationshipCode() == null || !masterData.isValidCode(RELATIONSHIP_LIST, a.relationshipCode(), today)) {
            throw ApiException.unprocessable("invalid-relationship",
                    "Relationship " + a.relationshipCode() + " is not an active value of code list " + RELATIONSHIP_LIST);
        }
        if (a.fullName() == null || a.fullName().isBlank()) {
            throw ApiException.unprocessable("invalid-name", "The full name is required");
        }
        if (a.birthDate() != null && a.birthDate().isAfter(today)) {
            throw ApiException.unprocessable("invalid-birth-date", "The birth date lies in the future");
        }
        String nid = normalised(a.nationalId());
        if (nid != null && !NATIONAL_ID.matcher(nid).matches()) {
            throw ApiException.unprocessable("invalid-national-id", "The identity number has an invalid format");
        }
        String tax = normalised(a.taxCode());
        if (tax != null && !TAX_CODE.matcher(tax).matches()) {
            throw ApiException.unprocessable("invalid-tax-code", "A personal tax code has 10, 12 or 13 digits");
        }
        if (a.averageMonthlyIncome() != null && a.averageMonthlyIncome().signum() < 0) {
            throw ApiException.unprocessable("invalid-income", "The average monthly income cannot be negative");
        }
    }

    /** The stated flag, or derived from the stated average income and the threshold parameter (not stored). */
    private Boolean incomeFlag(Attributes a) {
        if (a.averageMonthlyIncome() == null) {
            return a.hasIncomeAboveThreshold();
        }
        BigDecimal threshold = legal.require(FamilyLegalKeys.INCOME_MONTHLY_THRESHOLD, Scope.tenant(), clock.today())
                .asDecimal();
        return a.averageMonthlyIncome().compareTo(threshold) > 0;
    }

    private void checkDuplicate(UUID personId, UUID self, byte[] bidx) {
        boolean dup = db.jdbc().sql("""
                        select exists (select 1 from hr.family_member
                                        where tenant_id = :t and person_id = :p and national_id_bidx = :b
                                          and (cast(:self as uuid) is null or id <> :self))""")
                .param("t", TenantContext.current().tenantId()).param("p", personId).param("b", bidx)
                .param("self", self).query(Boolean.class).single();
        if (dup) {
            throw ApiException.conflict("family-member-duplicate",
                    "A family member with this identity number is already recorded for the employee");
        }
    }

    /** Normal form of an identifier; blank = null. */
    static String normalised(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return PiiCipher.normalise(value);
    }
}
