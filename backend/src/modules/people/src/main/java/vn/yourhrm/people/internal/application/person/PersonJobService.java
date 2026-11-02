package vn.yourhrm.people.internal.application.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.api.PersonJobs;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;

/**
 * The person stream's daily monitors (US-PPL-007, US-PPL-011, US-PPL-059 share), run per tenant by the scheduler:
 * documents and permits past their end become EXPIRED, and HR users responsible for the employee (holders of
 * {@code hr.person.update} / {@code hr.work_permit.update} whose scope covers the employment) are notified on the day
 * the configured offset is reached. Notifications carry the employee number, the document type and the date only.
 */
@Service
public class PersonJobService implements PersonJobs {

    private final HrDb db;
    private final PersonSupport support;
    private final Notifications notifications;
    private final Settings settings;

    public PersonJobService(HrDb db, PersonSupport support, Notifications notifications, Settings settings) {
        this.db = db;
        this.support = support;
        this.notifications = notifications;
        this.settings = settings;
    }

    @Override
    @HrTransactional
    public int identityDocumentExpiryAlerts(LocalDate today) {
        UUID t = support.tenant();
        db.jdbc().sql("""
                        update hr.person_identity_doc set status = 'EXPIRED', version = version + 1
                         where tenant_id = :t and status = 'ACTIVE' and expiry_date < :today""")
                .param("t", t).param("today", today).update();
        long offset = settings.longValue(PersonKeys.SETTING_ID_DOC_ALERT_DAYS, Settings.Scope.tenant(), today);
        LocalDate expiry = today.plusDays(offset);
        record Doc(UUID id, UUID personId, String type) {
        }
        List<Doc> docs = db.jdbc().sql("""
                        select id, person_id, doc_type from hr.person_identity_doc
                         where tenant_id = :t and status = 'ACTIVE' and expiry_date = :exp order by id""")
                .param("t", t).param("exp", expiry)
                .query((rs, n) -> new Doc(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .list();
        int sent = 0;
        for (Doc d : docs) {
            sent += send(PersonKeys.EVENT_ID_DOC_EXPIRING, PeoplePermissions.PERSON_UPDATE, d.personId(), null,
                    d.type(), expiry, offset, "hr.person_identity_doc", d.id(),
                    "/people/persons/" + d.personId() + "/identity-documents", "hr-iddoc:" + d.id() + ":" + expiry);
        }
        return sent;
    }

    @Override
    @HrTransactional
    public int workPermitExpiryAlerts(LocalDate today) {
        UUID t = support.tenant();
        db.jdbc().sql("""
                        update hr.work_permit set status = 'EXPIRED', version = version + 1
                         where tenant_id = :t and status = 'ACTIVE' and valid_to <= :today""")
                .param("t", t).param("today", today).update();
        record Permit(UUID id, UUID personId, UUID employmentId, UUID legalEntityId, String type, LocalDate lastDay) {
        }
        List<Permit> permits = db.jdbc().sql("""
                        select w.id, w.person_id, w.employment_id, e.legal_entity_id, w.permit_type, w.valid_to - 1
                          from hr.work_permit w
                          join hr.employment e on e.tenant_id = w.tenant_id and e.id = w.employment_id
                         where w.tenant_id = :t and w.status = 'ACTIVE' and w.valid_to > :today
                           and not exists (select 1 from hr.work_permit r where r.tenant_id = w.tenant_id
                                              and r.renewal_of_id = w.id and r.status in ('APPLIED','ACTIVE'))
                         order by w.id""")
                .param("t", t).param("today", today)
                .query((rs, n) -> new Permit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5),
                        rs.getObject(6, LocalDate.class)))
                .list();
        int sent = 0;
        for (Permit p : permits) {
            Settings.Scope scope = new Settings.Scope(p.legalEntityId(), null);
            List<Long> offsets = new ArrayList<>(List.of(
                    settings.longValue(PersonKeys.SETTING_WP_ALERT_DAYS_FIRST, scope, today),
                    settings.longValue(PersonKeys.SETTING_WP_ALERT_DAYS_SECOND, scope, today)));
            for (long offset : offsets) {
                if (p.lastDay().equals(today.plusDays(offset))) {
                    sent += send(PersonKeys.EVENT_WORK_PERMIT_EXPIRING, PeoplePermissions.WORK_PERMIT_UPDATE,
                            p.personId(), p.employmentId(), p.type(), p.lastDay(), offset, "hr.work_permit", p.id(),
                            "/people/persons/" + p.personId() + "/work-permits", "hr-wp:" + p.id() + ":" + offset);
                }
            }
        }
        return sent;
    }

    private int send(String event, String permission, UUID personId, UUID employmentId, String documentType,
            LocalDate expiry, long daysLeft, String refType, UUID refId, String deepLink, String dedupeKey) {
        List<AuthorizationTarget> targets = employmentId != null
                ? support.employeeTargets(personId, support.today()).stream()
                        .filter(x -> x instanceof AuthorizationTarget.Employee e && e.employmentId().equals(employmentId))
                        .toList()
                : support.employeeTargets(personId, support.today());
        if (targets.isEmpty()) {
            targets = List.of(AuthorizationTarget.tenant());
        }
        String employeeNo = db.jdbc().sql("""
                        select employee_no from hr.employment where tenant_id = :t and person_id = :p
                           and (cast(:e as uuid) is null or id = :e) and status <> 'CANCELLED'
                         order by hire_date desc limit 1""")
                .param("t", support.tenant()).param("p", personId).param("e", employmentId).query(String.class)
                .optional().orElse("-");
        var result = notifications.send(new SendRequest(event, "hr",
                new Recipients(null, targets.stream().map(x -> Audience.permission(permission, x)).toList()),
                Map.of("employeeNo", employeeNo, "documentType", documentType, "expiryDate", expiry.toString(),
                        "daysLeft", daysLeft),
                refType, refId, deepLink, dedupeKey, null));
        return result.notificationIds().isEmpty() ? 0 : 1;
    }
}
