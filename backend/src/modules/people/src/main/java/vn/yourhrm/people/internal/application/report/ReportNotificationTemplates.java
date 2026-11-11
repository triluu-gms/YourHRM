package vn.yourhrm.people.internal.application.report;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;

/**
 * Deadline alert of the statutory labour reports (BR-PPL-141/142, US-PPL-069/070): in-app + e-mail, VI and EN, to the
 * holders of {@code hr.statutory_report.execute} of the legal entity. No personal data in the placeholders.
 */
@Component
public class ReportNotificationTemplates implements NotificationTemplateCatalogue {

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<Placeholder> p = List.of(new Placeholder("reportVi", Kind.TEXT), new Placeholder("reportEn", Kind.TEXT),
                new Placeholder("period", Kind.TEXT), new Placeholder("dueDate", Kind.DATE),
                new Placeholder("daysLeft", Kind.NUMBER));
        String subjectVi = "Sắp đến hạn nộp {{reportVi}}";
        String bodyVi = "{{reportVi}} kỳ {{period}} phải nộp trước ngày {{dueDate}} (còn {{daysLeft}} ngày) và chưa được "
                + "đánh dấu đã nộp.";
        String subjectEn = "{{reportEn}} due soon";
        String bodyEn = "The {{reportEn}} for {{period}} is due by {{dueDate}} ({{daysLeft}} days left) and has not been "
                + "marked as submitted.";
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        out.add(new NotificationTemplateDefinition(ReportKeys.EVENT_REPORT_DUE, "REMINDER", false, "IN_APP", "vi-VN",
                subjectVi, bodyVi, "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(ReportKeys.EVENT_REPORT_DUE, "REMINDER", false, "IN_APP", "en-US",
                subjectEn, bodyEn, "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(ReportKeys.EVENT_REPORT_DUE, "REMINDER", false, "EMAIL", "vi-VN",
                subjectVi, bodyVi + " {{deepLink}}", "TEXT", p, false));
        out.add(new NotificationTemplateDefinition(ReportKeys.EVENT_REPORT_DUE, "REMINDER", false, "EMAIL", "en-US",
                subjectEn, bodyEn + " {{deepLink}}", "TEXT", p, false));
        return out;
    }
}
