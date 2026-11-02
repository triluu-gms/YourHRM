package vn.yourhrm.people.internal.application.person;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.domain.person.PersonRules;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * Phones and e-mails of a person (BR-PPL-010, US-PPL-006): format-checked, one primary value per contact type
 * (setting a new primary demotes the previous one). The primary PERSONAL_EMAIL is the address for the former-employee
 * OTP sign-in (OD-8): the separation stream reads it with {@link #primaryPersonalEmail}. Contact values are P2
 * (work contacts P1); writes are row-audited.
 */
@Service
public class PersonContactService {

    public record ContactData(String contactType, String value, boolean primary) {
    }

    /** {@code usableForFormerEmployeeLogin}: the primary personal e-mail (OD-8). */
    public record ContactView(UUID id, String contactType, String value, boolean primary, boolean verified,
            boolean usableForFormerEmployeeLogin, int version) {
    }

    private final HrDb db;
    private final PersonSupport support;

    public PersonContactService(HrDb db, PersonSupport support) {
        this.db = db;
        this.support = support;
    }

    @HrTransactional(readOnly = true)
    public List<ContactView> list(UUID personId) {
        support.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, personId);
        return db.jdbc().sql("""
                        select * from hr.person_contact where tenant_id = :t and person_id = :p
                         order by contact_type, is_primary desc, created_at""")
                .param("t", support.tenant()).param("p", personId).query((rs, n) -> view(rs)).list();
    }

    @HrTransactional
    public ContactView add(UUID personId, ContactData c) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        support.lockPerson(personId);
        String value = PersonRules.contactValue(c.contactType(), c.value());
        UUID t = support.tenant();
        boolean hasPrimary = db.jdbc().sql("""
                        select exists (select 1 from hr.person_contact where tenant_id = :t and person_id = :p
                                          and contact_type = :type and is_primary)""")
                .param("t", t).param("p", personId).param("type", c.contactType()).query(Boolean.class).single();
        // the first value of a type is its primary
        boolean primary = c.primary() || !hasPrimary;
        if (primary) {
            demote(personId, c.contactType(), null);
        }
        UUID id = Ids.newId();
        db.jdbc().sql("""
                        insert into hr.person_contact (tenant_id, id, person_id, contact_type, contact_value, is_primary,
                               created_by, updated_by)
                        values (:t, :id, :p, :type, :v, :primary, :a, :a)""")
                .param("t", t).param("id", id).param("p", personId).param("type", c.contactType()).param("v", value)
                .param("primary", primary).param("a", support.actor()).update();
        return find(id).orElseThrow();
    }

    @HrTransactional
    public ContactView change(UUID personId, UUID contactId, int version, String value, boolean primary) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        ContactView current = owned(personId, contactId);
        String v = PersonRules.contactValue(current.contactType(), value);
        if (primary && !current.primary()) {
            demote(personId, current.contactType(), contactId);
        }
        if (!primary && current.primary()) {
            throw ApiException.unprocessable("primary-contact-required", "Make another " + current.contactType()
                    + " primary instead of removing the flag");
        }
        int updated = db.jdbc().sql("""
                        update hr.person_contact set contact_value = :v, is_primary = :primary,
                               is_verified = is_verified and contact_value = :v, version = version + 1, updated_by = :a
                         where tenant_id = :t and id = :id and version = :ver""")
                .param("v", v).param("primary", primary).param("a", support.actor()).param("t", support.tenant())
                .param("id", contactId).param("ver", version).update();
        PersonSupport.requireUpdated(updated, "Contact", contactId);
        return find(contactId).orElseThrow();
    }

    /** Removes a contact; removing the primary one promotes the oldest remaining value of that type. */
    @HrTransactional
    public void remove(UUID personId, UUID contactId) {
        support.requirePerson(PeoplePermissions.PERSON_UPDATE, personId);
        ContactView current = owned(personId, contactId);
        db.jdbc().sql("delete from hr.person_contact where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", contactId).update();
        if (current.primary()) {
            db.jdbc().sql("""
                            update hr.person_contact set is_primary = true, version = version + 1, updated_by = :a
                             where tenant_id = :t and id = (select id from hr.person_contact where tenant_id = :t
                                                              and person_id = :p and contact_type = :type
                                                            order by created_at limit 1)""")
                    .param("a", support.actor()).param("t", support.tenant()).param("p", personId)
                    .param("type", current.contactType()).update();
        }
    }

    /** OD-8: the primary personal e-mail of the person (former-employee OTP login), if any. No permission check. */
    @HrTransactional(readOnly = true)
    public Optional<String> primaryPersonalEmail(UUID personId) {
        return db.jdbc().sql("""
                        select contact_value from hr.person_contact where tenant_id = :t and person_id = :p
                           and contact_type = 'PERSONAL_EMAIL' and is_primary""")
                .param("t", support.tenant()).param("p", personId).query(String.class).optional();
    }

    private void demote(UUID personId, String type, UUID except) {
        db.jdbc().sql("""
                        update hr.person_contact set is_primary = false, version = version + 1, updated_by = :a
                         where tenant_id = :t and person_id = :p and contact_type = :type and is_primary
                           and (cast(:x as uuid) is null or id <> :x)""")
                .param("a", support.actor()).param("t", support.tenant()).param("p", personId).param("type", type)
                .param("x", except).update();
    }

    private ContactView owned(UUID personId, UUID contactId) {
        return db.jdbc().sql("select * from hr.person_contact where tenant_id = :t and id = :id and person_id = :p")
                .param("t", support.tenant()).param("id", contactId).param("p", personId)
                .query((rs, n) -> view(rs)).optional().orElseThrow(() -> ApiException.notFound("Contact", contactId));
    }

    private Optional<ContactView> find(UUID id) {
        return db.jdbc().sql("select * from hr.person_contact where tenant_id = :t and id = :id")
                .param("t", support.tenant()).param("id", id).query((rs, n) -> view(rs)).optional();
    }

    private static ContactView view(ResultSet rs) throws SQLException {
        String type = rs.getString("contact_type");
        boolean primary = rs.getBoolean("is_primary");
        return new ContactView(rs.getObject("id", UUID.class), type, rs.getString("contact_value"), primary,
                rs.getBoolean("is_verified"), primary && "PERSONAL_EMAIL".equals(type), rs.getInt("version"));
    }
}
