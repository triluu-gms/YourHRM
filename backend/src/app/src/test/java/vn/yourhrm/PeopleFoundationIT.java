package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.people.api.PeopleEvent;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.PeopleFixtures;
import vn.yourhrm.support.PeopleFixtures.Employee;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.OrgUnit;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestLegalParameters;

/**
 * People foundation (US-PPL-001, NFR-PPL-002/005/008): the module's own pool runs as yhrm_app_hr under RLS, the
 * permission catalogue is registered, events commit with the change, P3 values round-trip through the field cipher,
 * and employment scope follows the primary assignment.
 */
class PeopleFoundationIT extends IntegrationTest {

    static final PeopleFixtures PPL = new PeopleFixtures(ADMIN, FIX);
    static final LocalDate D = LocalDate.of(2026, 1, 5);

    @Autowired
    HrDb db;
    @Autowired
    HrOutbox outbox;
    @Autowired
    PiiCipher cipher;
    @Autowired
    EmployeeAccess access;
    @Autowired
    PeopleLegalParameters legal;
    @Autowired
    DecisionService decisions;
    @Autowired
    @Qualifier(HrDb.TX)
    PlatformTransactionManager hrTx;

    record Probe(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId, LocalDate effectiveDate)
            implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.FoundationProbed";
        }
    }

    static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", null), call);
    }

    <T> T inHrTx(TestUser user, TransactionTemplateCallback<T> body) throws Exception {
        return as(user, () -> new TransactionTemplate(hrTx).execute(s -> body.run()));
    }

    interface TransactionTemplateCallback<T> {
        T run();
    }

    @Test
    void peoplePermissionsAreRegistered() {
        assertThat(PPL.allHrPermissions()).contains(PeoplePermissions.EMPLOYEE_VIEW,
                PeoplePermissions.COMPENSATION_VIEW, PeoplePermissions.ESS_REQUEST);
    }

    @Test
    void thePeoplePoolConnectsAsTheHrRoleAndSeesOnlyItsTenant() throws Exception {
        UUID tenantA = FIX.tenant("ppl-a");
        UUID tenantB = FIX.tenant("ppl-b");
        UUID personA = PPL.person(tenantA, "Nguyễn", "An", null);
        PPL.person(tenantB, "Trần", "Bình", null);
        TestUser hrA = PPL.hrAdmin(tenantA);

        String role = inHrTx(hrA, () -> db.jdbc().sql("select current_user").query(String.class).single());
        assertThat(role).isEqualTo("yhrm_app_hr");
        // Cross-tenant negative test (US-PPL-001 AC, NFR-PPL-008): RLS hides tenant B's persons.
        List<UUID> visible = inHrTx(hrA, () -> db.jdbc().sql("select id from hr.person").query(UUID.class).list());
        assertThat(visible).containsExactly(personA);
        // Outside a People transaction there is no tenant context on the connection: nothing is visible.
        assertThat(db.jdbc().sql("select count(*) from hr.person").query(Long.class).single()).isZero();
    }

    @Test
    void eventsCommitAndRollBackWithTheChange() throws Exception {
        UUID tenant = FIX.tenant("ppl-outbox");
        UUID le = FIX.legalEntity(tenant, "LE1");
        UUID person = PPL.person(tenant, "Lê", "Chi", null);
        TestUser hr = PPL.hrAdmin(tenant);

        UUID eventId = inHrTx(hr, () -> outbox.publish(new Probe(tenant, le, person, null, D)));
        assertThat(ADMIN.queryForObject("select event_type from hr.outbox_event where id = ?", String.class, eventId))
                .isEqualTo("people.FoundationProbed");
        assertThat(ADMIN.queryForObject("select aggregate_type from hr.outbox_event where id = ?", String.class, eventId))
                .isEqualTo("person");

        assertThatThrownBy(() -> as(hr, () -> new TransactionTemplate(hrTx).execute(s -> {
            UUID id = outbox.publish(new Probe(tenant, le, person, null, D));
            throw new IllegalStateException("rollback " + id);
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(ADMIN.queryForObject("select count(*) from hr.outbox_event where tenant_id = ?", Long.class, tenant))
                .isEqualTo(1L);
    }

    @Test
    void p3ValuesRoundTripAndBlindIndexesMatchNormalisedInput() throws Exception {
        UUID tenant = FIX.tenant("ppl-pii");
        UUID other = FIX.tenant("ppl-pii2");
        UUID person = UUID.randomUUID();
        Subject subject = Subject.person(tenant, person);

        PiiCipher.Sealed sealed = cipher.encrypt(subject, "national_id", "001090012345");
        assertThat(new String(sealed.ciphertext())).doesNotContain("001090012345");
        assertThat(cipher.decrypt(subject, "national_id", sealed.ciphertext(), sealed.keyVersion()))
                .isEqualTo("001090012345");
        // Bound to subject and column: moving the value fails.
        assertThatThrownBy(() -> cipher.decrypt(subject, "tax_code", sealed.ciphertext(), sealed.keyVersion()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cipher.blindIndex(tenant, "001 090-012345")).isEqualTo(cipher.blindIndex(tenant, "001090012345"));
        assertThat(cipher.blindIndex(tenant, "001090012345")).isNotEqualTo(cipher.blindIndex(other, "001090012345"));
        assertThat(PiiCipher.last4("001090012345")).isEqualTo("2345");
    }

    @Test
    void employmentScopeFollowsThePrimaryAssignment() throws Exception {
        UUID tenant = FIX.tenant("ppl-scope");
        UUID le = FIX.legalEntity(tenant, "LE1");
        OrgUnit sales = FIX.orgUnit(tenant, "SALES", null, le, null, TestFixtures.EPOCH, null);
        OrgUnit ops = FIX.orgUnit(tenant, "OPS", null, le, null, TestFixtures.EPOCH, null);
        Employee inSales = PPL.employee(tenant, le, sales, "Phạm", "Dũng", D);
        Employee inOps = PPL.employee(tenant, le, ops, "Võ", "Em", D);
        TestUser salesHr = PPL.userWith(tenant, "ORG_UNIT", List.of(sales.id()), PeoplePermissions.EMPLOYEE_VIEW);

        assertThat(as(salesHr, () -> access.can(PeoplePermissions.EMPLOYEE_VIEW, inSales.employmentId(), D))).isTrue();
        assertThat(as(salesHr, () -> access.can(PeoplePermissions.EMPLOYEE_VIEW, inOps.employmentId(), D))).isFalse();
        assertThatThrownBy(() -> as(salesHr, () -> {
            access.requirePerson(PeoplePermissions.EMPLOYEE_VIEW, inOps.personId(), D);
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("not found");

        List<UUID> listed = inHrTx(salesHr, () -> {
            var scope = access.scope(PeoplePermissions.EMPLOYEE_VIEW, D, "s");
            return db.jdbc().sql("select e.id from " + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + " where " + scope.sql())
                    .param("asOf", D).params(scope.params()).query(UUID.class).list();
        });
        assertThat(listed).containsExactly(inSales.employmentId());
    }

    @Test
    void missingLegalParametersFailClosed() throws Exception {
        UUID tenant = FIX.tenant("ppl-legal");
        TestUser hr = PPL.hrAdmin(tenant);
        TestLegalParameters.define(tenant, "contract.definite.max_months", 36);

        assertThat(as(hr, () -> legal.require("contract.definite.max_months", Scope.tenant(), D).asInt())).isEqualTo(36);
        assertThatThrownBy(() -> as(hr, () -> legal.require("no.such.parameter", Scope.tenant(), D)))
                .isInstanceOf(ApiException.class).hasMessageContaining("no.such.parameter");
    }

    @Test
    void decisionsAreNumberedPerLegalEntityAndKeepTheirNumberWhenCancelled() throws Exception {
        UUID tenant = FIX.tenant("ppl-dec");
        UUID le = FIX.legalEntity(tenant, "HN");
        OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        Employee emp = PPL.employee(tenant, le, unit, "Đỗ", "Giang", D);
        TestUser hr = PPL.hrAdmin(tenant);
        PPL.numberSeries(tenant);

        var first = as(hr, () -> decisions.issue(new DecisionService.Issue(le, "SALARY_CHANGE", emp.employmentId(), D,
                "Nguyễn Văn Giám", "Giám đốc", null)));
        var second = as(hr, () -> decisions.issue(new DecisionService.Issue(le, "TRANSFER", emp.employmentId(), D,
                null, null, null)));
        assertThat(first.decisionNo()).isEqualTo("001/2026/QĐ-HN");
        assertThat(second.decisionNo()).isEqualTo("002/2026/QĐ-HN");
        assertThat(first.status()).isEqualTo("APPROVED");

        var cancelled = as(hr, () -> decisions.cancel(first.id(), first.version()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.decisionNo()).isEqualTo("001/2026/QĐ-HN");
        assertThatThrownBy(() -> as(hr, () -> decisions.issue(new DecisionService.Issue(le, "NO_SUCH_TYPE",
                emp.employmentId(), D, null, null, null)))).isInstanceOf(ApiException.class);
    }
}
