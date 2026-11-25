package vn.yourhrm.attendance.internal.application.period;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;

/**
 * T&amp;A's notification of the close protocol: the release request of a held period (BR-ATT-087), sent to the holders
 * of {@code att.period.hold} for the period's legal entity. Placeholders are dates only; the period is behind the link.
 */
@Component
public class PeriodNotificationTemplates implements NotificationTemplateCatalogue {

    public static final String RELEASE_REQUESTED = "att.PeriodReleaseRequested";

    private static final Placeholder START = new Placeholder("periodStart", Kind.DATE);
    private static final Placeholder END = new Placeholder("periodEnd", Kind.DATE);

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        String subjectVi = "Yêu cầu giải phóng kỳ chấm công {{periodStart}} - {{periodEnd}}";
        String bodyVi = "Quản trị chấm công cần mở lại kỳ chấm công {{periodStart}} - {{periodEnd}} đang được kỳ lương "
                + "giữ. Vui lòng giải phóng (release) kỳ chấm công trong kỳ lương; kỳ sẽ được mở lại tự động.";
        String subjectEn = "Release requested for attendance period {{periodStart}} - {{periodEnd}}";
        String bodyEn = "The T&A administrator needs to reopen the attendance period {{periodStart}} - {{periodEnd}}, "
                + "which a payroll run holds. Please release the hold in the payroll run; the period then reopens "
                + "automatically.";
        List<Placeholder> ph = List.of(START, END);
        out.add(new NotificationTemplateDefinition(RELEASE_REQUESTED, "WORKFLOW", false, "IN_APP", "vi-VN", subjectVi,
                bodyVi, "TEXT", ph, false));
        out.add(new NotificationTemplateDefinition(RELEASE_REQUESTED, "WORKFLOW", false, "IN_APP", "en-US", subjectEn,
                bodyEn, "TEXT", ph, false));
        out.add(new NotificationTemplateDefinition(RELEASE_REQUESTED, "WORKFLOW", false, "EMAIL", "vi-VN", subjectVi,
                bodyVi + " {{deepLink}}", "TEXT", ph, false));
        out.add(new NotificationTemplateDefinition(RELEASE_REQUESTED, "WORKFLOW", false, "EMAIL", "en-US", subjectEn,
                bodyEn + " {{deepLink}}", "TEXT", ph, false));
        return out;
    }
}
