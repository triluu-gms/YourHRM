package vn.yourhrm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.CompanyGroupProvisioning;
import vn.yourhrm.core.api.NotificationDeliveryStatus;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Attachment;
import vn.yourhrm.core.api.Notifications.Audience;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.core.api.Notifications.SendResult;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.support.IntegrationTest;
import vn.yourhrm.support.TestEmailAdapter;
import vn.yourhrm.support.TestFixtures;
import vn.yourhrm.support.TestFixtures.TestUser;
import vn.yourhrm.support.TestNotificationTemplates;
import vn.yourhrm.support.TestProcessTypes;

/** The notification centre (BR-CORE-070 in-app + e-mail, BR-CORE-074) and its workflow notifications (US-CORE-063). */
class NotificationIT extends IntegrationTest {

    @Autowired
    Notifications notifications;

    @Autowired
    NotificationDeliveryStatus deliveryStatus;

    @Autowired
    WorkflowService workflow;

    @Autowired
    CompanyGroupProvisioning provisioning;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeAll
    static void processTypes() {
        TestProcessTypes.register(ADMIN);
    }

    private static <T> T as(TestUser user, Callable<T> call) throws Exception {
        return TenantContext.callWith(new TenantContext(user.tenantId(), user.userId(), user.identityId().toString(),
                "t", "vi-VN"), call);
    }

    private static TestUser user(UUID tenant, String login, String email, String locale) {
        TestUser u = FIX.user(tenant, login);
        ADMIN.update("update core.user_account set email = ?, locale = ? where id = ?", email, locale, u.userId());
        return u;
    }

    private static String deliveryStatus(UUID notificationId, String channel) {
        return ADMIN.queryForObject("select status from core.notification_delivery where notification_id = ? and channel = ?",
                String.class, notificationId, channel);
    }

    private static SendRequest reminder(Recipients to, String dedupe) {
        return new SendRequest(TestNotificationTemplates.REMINDER, "pay", to, Map.of("period", "11/2026"),
                "pay.run", UUID.randomUUID(), "/payroll/runs/1", dedupe, null);
    }

    @Test
    void inAppAndEmailInTheRecipientsLanguageWithDeliveryStatus() throws Exception {
        UUID tenant = FIX.tenant("nt-send");
        TestUser sender = FIX.owner(tenant);
        TestUser vi = user(tenant, "an@test.local", "an@acme.vn", "vi-VN");
        TestUser en = user(tenant, "john@test.local", "john@acme.vn", "en-US");
        TestUser noMail = user(tenant, "binh@test.local", null, "vi-VN");

        SendResult result = as(sender, () -> notifications.send(new SendRequest(TestNotificationTemplates.PAYSLIP, "pay",
                Recipients.users(vi.userId(), en.userId(), noMail.userId()), Map.of("period", "11/2026"),
                "pay.payslip", UUID.randomUUID(), "/ess/payslips/2026-11", null, null)));
        assertThat(result.notificationIds()).hasSize(3);

        mvc.perform(get("/api/v1/core/notifications").with(as(en)))
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Payslip 11/2026"))
                .andExpect(jsonPath("$.items[0].deepLink").value("/ess/payslips/2026-11"));
        // e-mail went to the adapter after commit, with an absolute deep link; HTML values are escaped
        assertThat(TestEmailAdapter.sentTo("an@acme.vn")).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("Phiếu lương 11/2026");
            assertThat(m.body()).contains("http://localhost:5173/ess/payslips/2026-11");
            assertThat(m.attachment()).isNull();
        });
        String viNotification = ADMIN.queryForObject(
                "select id::text from core.notification where recipient_user_id = ?", String.class, vi.userId());
        assertThat(deliveryStatus(UUID.fromString(viNotification), "IN_APP")).isEqualTo("DELIVERED");
        assertThat(deliveryStatus(UUID.fromString(viNotification), "EMAIL")).isEqualTo("QUEUED");
        assertThat(ADMIN.queryForObject("select destination_masked from core.notification_delivery "
                + "where notification_id = ?::uuid and channel = 'EMAIL'", String.class, viNotification))
                .isEqualTo("a***@acme.vn");

        // the adapter reports back; HR sees status and failures
        UUID viDelivery = TestEmailAdapter.sentTo("an@acme.vn").getFirst().deliveryId();
        as(sender, () -> {
            deliveryStatus.report(viDelivery, "DELIVERED", "msg-1", null);
            deliveryStatus.report(TestEmailAdapter.sentTo("john@acme.vn").getFirst().deliveryId(), "BOUNCED", null,
                    "mailbox full");
            return null;
        });
        mvc.perform(get("/api/v1/core/notification-deliveries?status=BOUNCED").with(as(sender)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lastError").value("mailbox full"));
        assertThat(ADMIN.queryForObject("select status from core.notification_delivery where tenant_id = ? and channel = 'EMAIL' "
                + "and destination_masked is null", String.class, tenant)).isEqualTo("SUPPRESSED");
        mvc.perform(get("/api/v1/core/notification-deliveries").with(as(vi))).andExpect(status().isForbidden());
    }

    @Test
    void audiencesDeduplicationAndResend() throws Exception {
        UUID tenant = FIX.tenant("nt-aud");
        TestUser sender = FIX.owner(tenant);
        UUID le1 = FIX.legalEntity(tenant, "LE1");
        UUID le2 = FIX.legalEntity(tenant, "LE2");
        UUID hrRole = FIX.role(tenant, "HR_PAY", false, "core.legal_entity.view");
        TestUser hr1 = user(tenant, "hr1@test.local", "hr1@acme.vn", "vi-VN");
        FIX.assign(hr1, hrRole, "LEGAL_ENTITY", List.of(le1), true, TestFixtures.EPOCH, null);
        TestUser hr2 = user(tenant, "hr2@test.local", "hr2@acme.vn", "vi-VN");
        FIX.assign(hr2, hrRole, "LEGAL_ENTITY", List.of(le2), true, TestFixtures.EPOCH, null);

        // "the HR responsible for legal entity 1", through the authorisation service
        Recipients hrOfLe1 = new Recipients(Set.of(), List.of(Audience.role("HR_PAY",
                new AuthorizationTarget.LegalEntity(le1))));
        SendResult first = as(sender, () -> notifications.send(reminder(hrOfLe1, "close-2026-11")));
        assertThat(first.notificationIds()).hasSize(1);
        assertThat(ADMIN.queryForObject("select recipient_user_id from core.notification where id = ?", UUID.class,
                first.notificationIds().getFirst())).isEqualTo(hr1.userId());
        SendResult again = as(sender, () -> notifications.send(reminder(hrOfLe1, "close-2026-11")));
        assertThat(again.notificationIds()).isEmpty();
        assertThat(again.deduplicated()).containsExactly(hr1.userId());

        // a failed e-mail is resent from the notification's own text
        UUID delivery = TestEmailAdapter.sentTo("hr1@acme.vn").getFirst().deliveryId();
        as(sender, () -> {
            deliveryStatus.report(delivery, "FAILED", null, "SMTP 451");
            return null;
        });
        mvc.perform(post("/api/v1/core/notification-deliveries/" + delivery + "/resend").with(as(sender)))
                .andExpect(status().isNoContent());
        assertThat(TestEmailAdapter.sentTo("hr1@acme.vn")).hasSize(2);
        assertThat(TestEmailAdapter.sentTo("hr1@acme.vn").get(1).body()).contains("/payroll/runs/1");

        // a rolled-back business change sends nothing
        assertThatThrownBy(() -> as(sender, () -> new TransactionTemplate(txManager)
                .execute(s -> {
                    notifications.send(reminder(Recipients.users(hr2.userId()), null));
                    throw new IllegalStateException("module change failed");
                }))).hasMessage("module change failed");
        assertThat(TestEmailAdapter.sentTo("hr2@acme.vn")).isEmpty();
        assertThat(ADMIN.queryForObject("select count(*) from core.notification where recipient_user_id = ?",
                Integer.class, hr2.userId())).isZero();
    }

    @Test
    void sensitiveContentRulesAndThePayslipException() throws Exception {
        UUID tenant = FIX.tenant("nt-074");
        TestUser payroll = FIX.owner(tenant);
        TestUser emp = user(tenant, "emp@test.local", "emp@acme.vn", "vi-VN");
        TestUser other = user(tenant, "other@test.local", "other@acme.vn", "vi-VN");
        UUID pdf = FIX.document(tenant, "Phiếu lương 11/2026 (mã hóa)");
        Attachment protectedPdf = new Attachment(pdf, "phieu-luong-2026-11.pdf", "application/pdf", "ab12cd", true);

        // attachments only on the registered exception, encrypted with the employee's password, one recipient
        assertThatThrownBy(() -> as(payroll, () -> notifications.send(new SendRequest(TestNotificationTemplates.REMINDER,
                "pay", Recipients.users(emp.userId()), Map.of("period", "11/2026"), null, null, "/x", null, protectedPdf))))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("attachment-not-allowed");
        Attachment plain = new Attachment(pdf, "p.pdf", "application/pdf", "ab12cd", false);
        assertThatThrownBy(() -> as(payroll, () -> notifications.send(new SendRequest(TestNotificationTemplates.PAYSLIP,
                "pay", Recipients.users(emp.userId()), Map.of("period", "11/2026"), null, null, "/x", null, plain))))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("attachment-not-encrypted");
        assertThatThrownBy(() -> as(payroll, () -> notifications.send(new SendRequest(TestNotificationTemplates.PAYSLIP,
                "pay", Recipients.users(emp.userId(), other.userId()), Map.of("period", "11/2026"), null, null, "/x", null,
                protectedPdf))))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code())
                .isEqualTo("attachment-single-recipient");

        as(payroll, () -> notifications.send(new SendRequest(TestNotificationTemplates.PAYSLIP, "pay",
                Recipients.users(emp.userId()), Map.of("period", "11/2026"), "pay.payslip", UUID.randomUUID(),
                "/ess/payslips/2026-11", null, protectedPdf)));
        TestEmailAdapter.Sent mail = TestEmailAdapter.sentTo("emp@acme.vn").getFirst();
        assertThat(mail.attachment().documentId()).isEqualTo(pdf);
        assertThat(ADMIN.queryForObject("""
                select count(*) from audit.audit_event where tenant_id = ? and action = 'NOTIFICATION_ATTACHMENT_SENT'
                   and change ->> 'sha256' = 'ab12cd' and change ->> 'recipient' = ?""",
                Integer.class, tenant, emp.userId().toString())).isEqualTo(1);
        // resending a payslip e-mail is the source module's job (re-publish, audited there)
        mvc.perform(post("/api/v1/core/notification-deliveries/" + mail.deliveryId() + "/resend").with(as(payroll)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("resend-from-source"));
    }

    @Test
    void inboxReadStateIsPerUserAndStreamsOpen() throws Exception {
        UUID tenant = FIX.tenant("nt-inbox");
        TestUser sender = FIX.owner(tenant);
        TestUser a = user(tenant, "a@test.local", null, "vi-VN");
        TestUser b = user(tenant, "b@test.local", null, "vi-VN");
        SendResult sent = as(sender, () -> notifications.send(reminder(Recipients.users(a.userId(), b.userId()), null)));
        as(sender, () -> notifications.send(reminder(Recipients.users(a.userId()), null)));
        UUID aFirst = sent.notificationIds().stream().filter(id -> ADMIN.queryForObject(
                "select recipient_user_id from core.notification where id = ?", UUID.class, id).equals(a.userId()))
                .findFirst().orElseThrow();

        mvc.perform(get("/api/v1/core/notifications/unread-count").with(as(a))).andExpect(jsonPath("$.unread").value(2));
        mvc.perform(post("/api/v1/core/notifications/" + aFirst + "/read").with(as(b))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/core/notifications/" + aFirst + "/read").with(as(a))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/core/notifications?unreadOnly=true").with(as(a))).andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(post("/api/v1/core/notifications/read-all").with(as(a))).andExpect(jsonPath("$.marked").value(1));
        mvc.perform(get("/api/v1/core/notifications").with(as(a)))
                .andExpect(jsonPath("$.unread").value(0)).andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get("/api/v1/core/notifications/unread-count").with(as(b))).andExpect(jsonPath("$.unread").value(1));
        mvc.perform(get("/api/v1/core/notifications/stream").with(as(a)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());
    }

    @Test
    void workflowActivityNotifiesApproversAndRequesters() throws Exception {
        UUID tenant = FIX.tenant("nt-wf");
        TenantContext.callWith(new TenantContext(tenant, null, null, "t", null), provisioning::provision);
        UUID le = FIX.legalEntity(tenant, "LE");
        TestUser manager = user(tenant, "mgr@test.local", "mgr@acme.vn", "vi-VN");
        TestUser employee = user(tenant, "emp@test.local", "emp@acme.vn", "en-US");
        TestFixtures.OrgUnit unit = FIX.orgUnit(tenant, "U", null, le, null, TestFixtures.EPOCH, null);
        ADMIN.update("update core.org_unit_version set manager_user_id = ? where org_unit_id = ?", manager.userId(),
                unit.id());

        UUID instance = as(employee, () -> workflow.start(new StartRequest(TestProcessTypes.OVERTIME, "att.ot", UUID.randomUUID(),
                null, UUID.randomUUID().toString(), null, null, le, unit.id(), null, null, Map.of(), Map.of(), Map.of())))
                .instanceId();
        mvc.perform(get("/api/v1/core/notifications").with(as(manager)))
                .andExpect(jsonPath("$.items[0].eventCode").value("core.WorkflowTaskAssigned"))
                .andExpect(jsonPath("$.items[0].title").value("Việc cần phê duyệt: Làm thêm giờ (test)"))
                .andExpect(jsonPath("$.items[0].deepLink").value(startsWith("/workflow/tasks/")));
        String taskId = ADMIN.queryForObject("select id::text from core.workflow_task where workflow_instance_id = ?",
                String.class, instance);
        mvc.perform(post("/api/v1/core/workflow/tasks/" + taskId + "/actions").with(as(manager))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/core/notifications").with(as(employee)))
                .andExpect(jsonPath("$.items[0].eventCode").value("core.WorkflowDecided"))
                .andExpect(jsonPath("$.items[0].title").value("Overtime (test): approved"));
        assertThat(TestEmailAdapter.sentTo("emp@acme.vn")).extracting(TestEmailAdapter.Sent::subject)
                .contains("Overtime (test): approved");
    }

    @Test
    void templateRegistryAndPreview() throws Exception {
        UUID tenant = FIX.tenant("nt-tpl");
        TestUser owner = FIX.owner(tenant);
        mvc.perform(get("/api/v1/core/notification-templates").with(as(owner)))
                .andExpect(jsonPath("$[*].eventCode", hasItem("core.WorkflowTaskAssigned")))
                .andExpect(jsonPath("$[?(@.eventCode == 'pay.TEST_PAYSLIP' && @.channel == 'EMAIL')].attachmentAllowed")
                        .value(true));
        mvc.perform(post("/api/v1/core/notification-templates/preview").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventCode":"pay.TEST_PAYSLIP","channel":"EMAIL","locale":"en-US",
                                 "sample":{"period":"<b>11/2026</b>"},"deepLink":"/ess/payslips/x"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Phiếu lương <b>11/2026</b>")) // no en-US e-mail: vi-VN fallback
                .andExpect(jsonPath("$.body").value(containsString("&lt;b&gt;11/2026&lt;/b&gt;")));
        mvc.perform(post("/api/v1/core/notification-templates/preview").with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventCode\":\"pay.TEST_PAYSLIP\",\"channel\":\"IN_APP\",\"sample\":{}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("missing-placeholder-value"));
        assertThatThrownBy(() -> as(owner, () -> notifications.send(new SendRequest("pay.NOPE", "pay",
                Recipients.users(owner.userId()), Map.of(), null, null, null, null, null))))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code())
                .isEqualTo("unknown-notification-event");
    }
}
