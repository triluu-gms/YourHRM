package vn.yourhrm.people.internal.application.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.MasterData;
import vn.yourhrm.core.api.MasterData.Resolution;
import vn.yourhrm.people.internal.domain.person.PersonRules;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * {@code OB_EMP_CONTACT} (US-PPL-089/090; BR-PPL-010/011): addresses on the post-2025 two-level admin units and personal
 * phone / e-mail, one row per employee and record type. Legacy three-level codes (pre-2025 province / district /
 * commune) are resolved through Core's admin-unit resolver: a unit with exactly one successor is mapped (the legacy codes
 * are kept as evidence, {@code mapping_status = MAPPED}); an ambiguous split or an unknown code is an error (an UNMAPPED
 * address blocks the commit, DESIGN §13.4). Upsert: an address loaded earlier is corrected in place, an address entered
 * in the app is kept (warning); a contact with the same value is not duplicated. No events (personal-data events are
 * not needed for the initial load: consumers re-sync).
 */
@Component
class ContactImport extends OpeningBalanceImport {

    static final Set<String> ADDRESS_TYPES = Set.of("PERMANENT", "CURRENT", "TEMPORARY", "HOMETOWN", "BIRTH_PLACE");
    static final Set<String> CONTACT_TYPES = Set.of("MOBILE", "HOME_PHONE", "PERSONAL_EMAIL", "OTHER");

    private final MasterData masterData;

    ContactImport(OnboardingSupport support, MasterData masterData) {
        super(support);
        this.masterData = masterData;
    }

    @Override
    public Definition definition() {
        return definition(OnboardingKeys.CONTACT, "Số dư đầu kỳ - Liên hệ và địa chỉ",
                "Opening balance - contacts and addresses",
                List.of(employeeNo(),
                        text("record_type", "Loại", "Type", true,
                                "PERMANENT, CURRENT, TEMPORARY, HOMETOWN, BIRTH_PLACE (địa chỉ); MOBILE, HOME_PHONE, "
                                        + "PERSONAL_EMAIL, OTHER (liên hệ)",
                                "PERMANENT, CURRENT, TEMPORARY, HOMETOWN, BIRTH_PLACE (address); MOBILE, HOME_PHONE, "
                                        + "PERSONAL_EMAIL, OTHER (contact)"),
                        text("country", "Quốc gia", "Country", false, "Mặc định VN", "Default VN"),
                        text("province_code", "Mã tỉnh/thành (2025)", "Province code (2025)", false),
                        text("commune_code", "Mã xã/phường (2025)", "Commune code (2025)", false),
                        sensitive("address_line", "Số nhà, đường, thôn", "Street address", ColumnType.TEXT, false,
                                null, null),
                        text("legacy_province_code", "Mã tỉnh cũ (trước 2025)", "Legacy province code", false),
                        text("legacy_district_code", "Mã huyện cũ", "Legacy district code", false),
                        text("legacy_commune_code", "Mã xã cũ", "Legacy commune code", false),
                        sensitive("legacy_address_text", "Địa chỉ cũ (nguyên văn)", "Legacy address text",
                                ColumnType.TEXT, false, null, null),
                        sensitive("value", "Số điện thoại / email", "Phone / e-mail", ColumnType.TEXT, false,
                                "Cho loại liên hệ", "For contact types"),
                        date("valid_from", "Hiệu lực từ", "Valid from", false)),
                List.of("employee_no", "record_type"), List.of(OnboardingKeys.MASTER), List.of());
    }

    private record Unit(String province, String commune, String mapping) {
    }

    @Override
    protected List<RowMessage> precheck(ImportContext ctx, ImportRow row, Map<String, Object> state) {
        String type = upper(row, "record_type");
        if (!ADDRESS_TYPES.contains(type) && !CONTACT_TYPES.contains(type)) {
            return List.of(OnboardingSupport.err("record_type", "invalid-record-type", "Loại không hợp lệ: " + type,
                    "Unknown record type " + type));
        }
        if (CONTACT_TYPES.contains(type) && row.text("value") == null) {
            return List.of(OnboardingSupport.err("value", "required", "Giá trị liên hệ: bắt buộc nhập",
                    "Contact value: required"));
        }
        return List.of();
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        String type = upper(row, "record_type");
        return ADDRESS_TYPES.contains(type) ? address(ctx, row, emp, type) : contact(row, emp, type);
    }

    private Written contact(ImportRow row, OnboardingSupport.Emp emp, String type) {
        String contactType = "OTHER".equals(type) ? "OTHER" : type;
        String value = PersonRules.contactValue(contactType, row.text("value"));
        var j = s.db().jdbc();
        UUID t = OnboardingSupport.tenant();
        Optional<UUID> same = j.sql("""
                        select id from hr.person_contact where tenant_id = :t and person_id = :p and contact_type = :ct
                           and lower(contact_value) = lower(:v)""")
                .param("t", t).param("p", emp.personId()).param("ct", contactType).param("v", value)
                .query(UUID.class).optional();
        if (same.isPresent()) {
            return Written.updated("hr.person_contact", same.get(), List.of());
        }
        boolean hasPrimary = j.sql("""
                        select exists (select 1 from hr.person_contact where tenant_id = :t and person_id = :p
                                          and contact_type = :ct and is_primary)""")
                .param("t", t).param("p", emp.personId()).param("ct", contactType).query(Boolean.class).single();
        UUID id = Ids.newId();
        j.sql("""
                        insert into hr.person_contact (tenant_id, id, person_id, contact_type, contact_value, is_primary,
                               source, created_by, updated_by)
                        values (:t, :id, :p, :ct, :v, :primary, 'OPENING_LOAD', :a, :a)""")
                .param("t", t).param("id", id).param("p", emp.personId()).param("ct", contactType).param("v", value)
                .param("primary", !hasPrimary).param("a", OnboardingSupport.actor()).update();
        return Written.created("hr.person_contact", id, List.of());
    }

    private Written address(ImportContext ctx, ImportRow row, OnboardingSupport.Emp emp, String type) {
        String country = row.text("country") == null ? "VN" : PersonRules.country(row.text("country"));
        LocalDate from = row.date("valid_from") != null ? row.date("valid_from") : s.asOf(ctx);
        Unit unit = "VN".equals(country) ? resolve(row, from) : new Unit(null, null, "NOT_NEEDED");
        var j = s.db().jdbc();
        UUID t = OnboardingSupport.tenant();
        record Existing(UUID id, String source) {
        }
        Optional<Existing> existing = j.sql("""
                        select id, source from hr.person_address where tenant_id = :t and person_id = :p
                           and address_type = :type and (valid_to is null or valid_to > :d)
                         order by valid_from desc limit 1""")
                .param("t", t).param("p", emp.personId()).param("type", type).param("d", from)
                .query((rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2))).optional();
        if (existing.isPresent() && !OnboardingKeys.SOURCE.equals(existing.get().source())) {
            return Written.updated("hr.person_address", existing.get().id(), List.of(OnboardingSupport.warn(
                    "record_type", "address-kept", "Địa chỉ " + type + " đã được nhập trên ứng dụng: giữ nguyên",
                    "A " + type + " address was entered in the app: it is kept")));
        }
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("t", t);
        p.put("p", emp.personId());
        p.put("type", type);
        p.put("c", country);
        p.put("prov", unit.province());
        p.put("com", unit.commune());
        p.put("line", row.text("address_line"));
        p.put("lp", row.text("legacy_province_code"));
        p.put("ld", row.text("legacy_district_code"));
        p.put("lc", row.text("legacy_commune_code"));
        p.put("lt", row.text("legacy_address_text"));
        p.put("ms", unit.mapping());
        p.put("from", from);
        p.put("a", OnboardingSupport.actor());
        if (existing.isPresent()) {
            p.put("id", existing.get().id());
            j.sql("""
                            update hr.person_address set country_code = :c, province_code = :prov, commune_code = :com,
                                   address_line = :line, legacy_province_code = :lp, legacy_district_code = :ld,
                                   legacy_commune_code = :lc, legacy_address_text = :lt, mapping_status = :ms,
                                   version = version + 1, updated_by = :a
                             where tenant_id = :t and id = :id""")
                    .params(p).update();
            return Written.updated("hr.person_address", existing.get().id(), List.of());
        }
        UUID id = Ids.newId();
        p.put("id", id);
        j.sql("""
                        insert into hr.person_address (tenant_id, id, person_id, address_type, country_code,
                               province_code, commune_code, address_line, legacy_province_code, legacy_district_code,
                               legacy_commune_code, legacy_address_text, mapping_status, valid_from, source,
                               created_by, updated_by)
                        values (:t, :id, :p, :type, :c, :prov, :com, :line, :lp, :ld, :lc, :lt, :ms, :from,
                                'OPENING_LOAD', :a, :a)""")
                .params(p).update();
        return Written.created("hr.person_address", id, List.of());
    }

    /** Current codes as given, or the single successor of the legacy codes (BR-PPL-010, BR-CORE-081). */
    private Unit resolve(ImportRow row, LocalDate on) {
        boolean legacy = row.text("province_code") == null && row.text("commune_code") == null;
        String communeIn = legacy ? row.text("legacy_commune_code") : row.text("commune_code");
        String provinceIn = legacy ? row.text("legacy_province_code") : row.text("province_code");
        if (communeIn == null && provinceIn == null) {
            throw ApiException.unprocessable("admin-unit-required",
                    "A Vietnamese address needs a province / commune code (2025 or legacy three-level codes)");
        }
        String province = null;
        String commune = null;
        boolean mapped = false;
        if (communeIn != null) {
            Resolution r = single(communeIn, on);
            commune = r.candidates().getFirst().code();
            province = r.candidates().getFirst().parentCode();
            mapped = !"CURRENT".equals(r.status());
        }
        if (provinceIn != null) {
            Resolution r = single(provinceIn, on);
            String p = r.candidates().getFirst().code();
            mapped |= !"CURRENT".equals(r.status());
            if (province != null && !province.equals(p)) {
                throw ApiException.unprocessable("commune-not-in-province",
                        "Commune " + communeIn + " does not belong to province " + provinceIn);
            }
            province = p;
        }
        return new Unit(province, commune, legacy || mapped ? "MAPPED" : "NOT_NEEDED");
    }

    private Resolution single(String code, LocalDate on) {
        Resolution r = masterData.resolveAdminUnit(code.strip(), on);
        if (("CURRENT".equals(r.status()) || "MAPPED".equals(r.status())) && r.candidates().size() == 1) {
            return r;
        }
        throw ApiException.unprocessable("admin-unit-unmapped", "Administrative unit " + code + " cannot be mapped to "
                + "the current units (" + r.status() + (r.candidates().isEmpty() ? "" : ", candidates "
                + r.candidates().stream().map(MasterData.AdminUnit::code).toList()) + "): enter the 2025 code");
    }

    // -------------------------------------------------------------------------------------------------- rollback

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        List<String> out = new ArrayList<>();
        if (s.write(() -> s.locked(ctx.legalEntityId()))) {
            out.add("Opening balances are locked (go-live)");
        }
        return out;
    }

    /**
     * Removes the personal contacts and the addresses the job created (DDL v0.6 grants DELETE on
     * {@code hr.person_address}; only rows still marked {@code source = 'OPENING_LOAD'}). Rows that existed before the
     * job stay.
     */
    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> contacts = created(rows, "hr.person_contact");
        List<UUID> addresses = created(rows, "hr.person_address");
        if (contacts.isEmpty() && addresses.isEmpty()) {
            return;
        }
        s.write(() -> {
            if (!contacts.isEmpty()) {
                s.db().jdbc().sql("delete from hr.person_contact where tenant_id = :t and id in (:ids)")
                        .param("t", OnboardingSupport.tenant()).param("ids", contacts).update();
            }
            if (!addresses.isEmpty()) {
                s.db().jdbc().sql("""
                                delete from hr.person_address where tenant_id = :t and id in (:ids)
                                   and source = :source""")
                        .param("t", OnboardingSupport.tenant()).param("ids", addresses)
                        .param("source", OnboardingKeys.SOURCE).update();
            }
            return null;
        });
    }

    private static List<UUID> created(List<CommittedRow> rows, String entity) {
        return rows.stream().filter(r -> "CREATED".equals(r.action()) && entity.equals(r.targetEntity()))
                .map(CommittedRow::targetId).toList();
    }
}
