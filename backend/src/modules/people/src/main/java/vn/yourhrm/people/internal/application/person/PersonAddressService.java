package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.MasterData;
import vn.yourhrm.core.api.MasterData.AdminUnit;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonEvents.PersonalDataChanged;
import vn.yourhrm.people.internal.domain.person.PersonRules;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Person addresses (BR-PPL-010, US-PPL-005; Pattern A per address type, {@code valid_to} EXCLUSIVE): permanent,
 * current, temporary, home town, birth place. Vietnamese addresses use the two-level administrative units (province +
 * commune) valid on the address's start date in Core master data, the commune inside the province. History is never
 * overwritten: a new address from a date closes the open one; corrections keep the period. Addresses supplied in the
 * pre-2025 three-level format (opening-balance import) keep their legacy codes and text and are read-only here.
 * Changes publish {@code people.PersonalDataChanged} ({@code address.<TYPE>}).
 */
@Service
public class PersonAddressService {

    public static final Set<String> TYPES = Set.of("PERMANENT", "CURRENT", "TEMPORARY", "HOMETOWN", "BIRTH_PLACE");

    /** The address attributes (PUT semantics). */
    public record AddressData(String countryCode, String provinceCode, String communeCode, String addressLine) {
    }

    public record AddressView(UUID id, String addressType, String countryCode, String provinceCode, String communeCode,
            String addressLine, String legacyProvinceCode, String legacyDistrictCode, String legacyCommuneCode,
            String legacyAddressText, String mappingStatus, boolean readOnly, LocalDate validFrom, LocalDate validTo,
            String source, int version) {
    }

    private final HrDb db;
    private final PersonSupport support;
    private final MasterData masterData;
    private final HrOutbox outbox;

    public PersonAddressService(HrDb db, PersonSupport support, MasterData masterData, HrOutbox outbox) {
        this.db = db;
        this.support = support;
        this.masterData = masterData;
        this.outbox = outbox;
    }

    /** All addresses (history) or those valid on {@code asOf}. */
    @HrTransactional(readOnly = true)
    public List<AddressView> list(UUID personId, LocalDate asOf) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql("""
                        select * from hr.person_address where tenant_id = :t and person_id = :p
                           and (cast(:d as date) is null or valid_during @> cast(:d as date))
                         order by address_type, valid_from""")
                .param("t", support.tenant()).param("p", personId).param("d", asOf)
                .query((rs, n) -> view(rs)).list();
    }

    /**
     * A new address of {@code type} from {@code validFrom}: the address open on that date is closed the day before
     * (valid_to = validFrom). An address that starts on or after {@code validFrom} must be corrected instead.
     */
    @HrTransactional
    public AddressView add(UUID personId, String type, LocalDate validFrom, AddressData a) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        support.lockPerson(personId);
        if (!TYPES.contains(type)) {
            throw ApiException.unprocessable("invalid-address-type", "Address type must be one of " + TYPES);
        }
        if (validFrom == null) {
            throw ApiException.unprocessable("invalid-period", "validFrom is required");
        }
        AddressData v = validate(a, validFrom);
        UUID t = support.tenant();
        boolean later = db.jdbc().sql("""
                        select exists (select 1 from hr.person_address where tenant_id = :t and person_id = :p
                                          and address_type = :type and valid_from >= :d)""")
                .param("t", t).param("p", personId).param("type", type).param("d", validFrom)
                .query(Boolean.class).single();
        if (later) {
            throw ApiException.unprocessable("address-history-conflict", "An address of type " + type + " starts on or "
                    + "after " + validFrom + "; correct that address instead of adding one before it");
        }
        db.jdbc().sql("""
                        update hr.person_address set valid_to = :d, version = version + 1, updated_by = :a
                         where tenant_id = :t and person_id = :p and address_type = :type
                           and valid_during @> cast(:d as date)""")
                .param("d", validFrom).param("a", support.actor()).param("t", t).param("p", personId)
                .param("type", type).update();
        UUID id = Ids.newId();
        try {
            db.jdbc().sql("""
                            insert into hr.person_address (tenant_id, id, person_id, address_type, country_code,
                                   province_code, commune_code, address_line, valid_from, created_by, updated_by)
                            values (:t, :id, :p, :type, :c, :prov, :com, :line, :from, :a, :a)""")
                    .param("t", t).param("id", id).param("p", personId).param("type", type)
                    .param("c", v.countryCode()).param("prov", v.provinceCode()).param("com", v.communeCode())
                    .param("line", v.addressLine()).param("from", validFrom).param("a", support.actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("address-overlap", "The address period overlaps another " + type + " address");
        }
        publish(personId, type, validFrom);
        return find(id).orElseThrow();
    }

    /** Corrects an address in place (same period); legacy (imported three-level) addresses are read-only. */
    @HrTransactional
    public AddressView correct(UUID personId, UUID addressId, int version, AddressData a) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        AddressView current = find(addressId).filter(x -> belongs(x, personId))
                .orElseThrow(() -> ApiException.notFound("Address", addressId));
        if (current.readOnly()) {
            throw ApiException.unprocessable("legacy-address-read-only", "An address kept in the pre-2025 three-level "
                    + "format is read-only; add a new address in the two-level format instead");
        }
        AddressData v = validate(a, current.validFrom());
        int updated = db.jdbc().sql("""
                        update hr.person_address set country_code = :c, province_code = :prov, commune_code = :com,
                               address_line = :line, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id and version = :v""")
                .param("c", v.countryCode()).param("prov", v.provinceCode()).param("com", v.communeCode())
                .param("line", v.addressLine()).param("a", support.actor()).param("t", support.tenant())
                .param("id", addressId).param("v", version).update();
        PersonSupport.requireUpdated(updated, "Address", addressId);
        publish(personId, current.addressType(), current.validFrom());
        return find(addressId).orElseThrow();
    }

    /** Ends an address (valid_to EXCLUSIVE), e.g. a temporary residence that ended. */
    @HrTransactional
    public AddressView end(UUID personId, UUID addressId, int version, LocalDate validTo) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        AddressView current = find(addressId).filter(x -> belongs(x, personId))
                .orElseThrow(() -> ApiException.notFound("Address", addressId));
        if (validTo == null || !validTo.isAfter(current.validFrom())) {
            throw ApiException.unprocessable("invalid-period", "validTo (exclusive) must be after validFrom");
        }
        int updated = db.jdbc().sql("""
                        update hr.person_address set valid_to = :to, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id and version = :v""")
                .param("to", validTo).param("a", support.actor()).param("t", support.tenant()).param("id", addressId)
                .param("v", version).update();
        PersonSupport.requireUpdated(updated, "Address", addressId);
        publish(personId, current.addressType(), validTo);
        return find(addressId).orElseThrow();
    }

    private boolean belongs(AddressView a, UUID personId) {
        return db.jdbc().sql("select person_id from hr.person_address where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", a.id()).query(UUID.class).single().equals(personId);
    }

    /** Two-level units valid on {@code on}: commune inside province (US-PPL-005 AC). */
    private AddressData validate(AddressData a, LocalDate on) {
        if (a == null) {
            throw ApiException.unprocessable("invalid-address", "Address data is required");
        }
        String country = PersonRules.country(a.countryCode() == null ? "VN" : a.countryCode());
        String line = a.addressLine() == null || a.addressLine().isBlank() ? null : a.addressLine().trim();
        if (!"VN".equals(country)) {
            if (line == null) {
                throw ApiException.unprocessable("invalid-address", "A foreign address needs the address line");
            }
            return new AddressData(country, null, null, line);
        }
        if (a.provinceCode() == null || a.communeCode() == null) {
            throw ApiException.unprocessable("invalid-address", "A Vietnamese address needs the province and the commune");
        }
        AdminUnit province = unit(a.provinceCode(), "PROVINCE", on);
        AdminUnit commune = unit(a.communeCode(), "COMMUNE", on);
        if (!province.code().equals(commune.parentCode())) {
            throw ApiException.unprocessable("invalid-admin-unit", "Commune " + commune.code() + " is not in province "
                    + province.code());
        }
        return new AddressData(country, province.code(), commune.code(), line);
    }

    private AdminUnit unit(String code, String level, LocalDate on) {
        Optional<AdminUnit> u = masterData.adminUnit(code.trim());
        if (u.isEmpty() || !level.equals(u.get().level())) {
            throw ApiException.unprocessable("unknown-admin-unit", "No " + level.toLowerCase() + " has code " + code);
        }
        AdminUnit x = u.get();
        if (x.validFrom().isAfter(on) || (x.validTo() != null && !x.validTo().isAfter(on))) {
            throw ApiException.unprocessable("admin-unit-not-in-effect", "Administrative unit " + code
                    + " is not valid on " + on + " (use the two-level units in effect since the 2025 restructure)");
        }
        return x;
    }

    private void publish(UUID personId, String type, LocalDate effective) {
        outbox.publish(new PersonalDataChanged(support.tenant(), personId, effective, List.of("address." + type), null,
                null, null, "ADDRESS_CHANGED"));
    }

    private Optional<AddressView> find(UUID id) {
        return db.jdbc().sql("select * from hr.person_address where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).optional();
    }

    private static AddressView view(ResultSet rs) throws SQLException {
        String mapping = rs.getString("mapping_status");
        boolean legacy = rs.getString("legacy_province_code") != null || rs.getString("legacy_address_text") != null;
        return new AddressView(rs.getObject("id", UUID.class), rs.getString("address_type"),
                rs.getString("country_code"), rs.getString("province_code"), rs.getString("commune_code"),
                rs.getString("address_line"), rs.getString("legacy_province_code"),
                rs.getString("legacy_district_code"), rs.getString("legacy_commune_code"),
                rs.getString("legacy_address_text"), mapping, legacy,
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getString("source"), rs.getInt("version"));
    }
}
