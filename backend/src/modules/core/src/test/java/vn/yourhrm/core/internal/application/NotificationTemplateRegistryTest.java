package vn.yourhrm.core.internal.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;

/** Template guardrails checked at start-up (US-CORE-070, BR-CORE-074). */
class NotificationTemplateRegistryTest {

    private static NotificationTemplateDefinition template(String channel, String body, List<Placeholder> placeholders,
            boolean attachment) {
        return new NotificationTemplateDefinition("pay.X", "PAYSLIP", true, channel, "vi-VN", "Tiêu đề", body, "TEXT",
                placeholders, attachment);
    }

    @Test
    void aWellFormedTemplatePasses() {
        assertThatCode(() -> NotificationTemplateRegistry.validate("pay",
                template("EMAIL", "Kỳ {{period}}: {{deepLink}}", List.of(new Placeholder("period", Kind.TEXT)), true)))
                .doesNotThrowAnyException();
    }

    @Test
    void undeclaredPlaceholdersFailTheRegistration() {
        assertThatThrownBy(() -> NotificationTemplateRegistry.validate("pay",
                template("IN_APP", "Kỳ {{period}} {{employeeName}}", List.of(new Placeholder("period", Kind.TEXT)),
                        false)))
                .hasMessageContaining("{{employeeName}} is not declared");
    }

    @Test
    void amountsAndSensitiveDataAreNeverAllowedInNotificationText() {
        assertThatThrownBy(() -> NotificationTemplateRegistry.validate("pay",
                template("EMAIL", "Lương {{netPay}}", List.of(new Placeholder("netPay", Kind.MONEY)), false)))
                .hasMessageContaining("BR-CORE-074");
        assertThatThrownBy(() -> NotificationTemplateRegistry.validate("pay",
                template("IN_APP", "CCCD {{idNo}}", List.of(new Placeholder("idNo", Kind.SENSITIVE)), false)))
                .hasMessageContaining("BR-CORE-074");
    }

    @Test
    void channelsLocalesModulesAndAttachmentsAreChecked() {
        assertThatThrownBy(() -> NotificationTemplateRegistry.validate("pay", template("SMS", "x", List.of(), false)))
                .hasMessageContaining("IN_APP or EMAIL");
        assertThatThrownBy(() -> NotificationTemplateRegistry.validate("att", template("IN_APP", "x", List.of(), false)))
                .hasMessageContaining("must start with att.");
        assertThatThrownBy(() -> NotificationTemplateRegistry.validate("pay", template("IN_APP", "x", List.of(), true)))
                .hasMessageContaining("attachments exist only on e-mail");
    }
}
