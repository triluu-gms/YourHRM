package vn.yourhrm.core.internal.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;

/**
 * Core's own notification events: the workflow engine's (US-CORE-063), the document service's and the import engine's.
 * Texts carry names and a deep link only, never the request's content or amounts (BR-CORE-074). Every category here
 * (WORKFLOW, SECURITY, SYSTEM) is mandatory.
 */
@Component
public class CoreNotificationTemplates implements NotificationTemplateCatalogue {

    public static final String TASK_ASSIGNED = "core.WorkflowTaskAssigned";
    public static final String REQUEST_RETURNED = "core.WorkflowReturned";
    public static final String REQUEST_DECIDED = "core.WorkflowDecided";
    /** An uploaded file failed its antivirus scan (BR-CORE-100, US-CORE-100); sent to the uploader. */
    public static final String DOCUMENT_QUARANTINED = "core.DocumentQuarantined";
    /** An import job finished a step (validated, committed, failed) (BR-CORE-111); sent to its creator. */
    public static final String IMPORT_FINISHED = "core.ImportJobFinished";

    @Override
    public String module() {
        return "core";
    }

    @Override
    public List<NotificationTemplateDefinition> templates() {
        List<NotificationTemplateDefinition> out = new ArrayList<>();
        List<Placeholder> task = List.of(new Placeholder("processName", Kind.TEXT), new Placeholder("stepName", Kind.TEXT),
                new Placeholder("requesterName", Kind.TEXT));
        add(out, "WORKFLOW", TASK_ASSIGNED, task,
                "Việc cần phê duyệt: {{processName}}", "{{requesterName}} gửi yêu cầu {{processName}} ({{stepName}}).",
                "Mở yêu cầu: {{deepLink}}",
                "Approval needed: {{processName}}", "{{requesterName}} submitted {{processName}} ({{stepName}}).",
                "Open the request: {{deepLink}}");
        List<Placeholder> request = List.of(new Placeholder("processName", Kind.TEXT));
        add(out, "WORKFLOW", REQUEST_RETURNED, request,
                "Yêu cầu được trả lại: {{processName}}", "Yêu cầu {{processName}} của bạn cần được bổ sung.",
                "Xem nhận xét của người phê duyệt: {{deepLink}}",
                "Request returned: {{processName}}", "Your {{processName}} request needs changes.",
                "See the approver's comments: {{deepLink}}");
        List<Placeholder> decided = List.of(new Placeholder("processName", Kind.TEXT),
                new Placeholder("outcome", Kind.TEXT));
        add(out, "WORKFLOW", REQUEST_DECIDED, decided,
                "Kết quả yêu cầu {{processName}}: {{outcome}}", "Yêu cầu {{processName}} của bạn: {{outcome}}.",
                "Xem chi tiết: {{deepLink}}",
                "{{processName}}: {{outcome}}", "Your {{processName}} request: {{outcome}}.",
                "View the details: {{deepLink}}");
        List<Placeholder> file = List.of(new Placeholder("title", Kind.TEXT), new Placeholder("fileName", Kind.TEXT));
        add(out, "SECURITY", DOCUMENT_QUARANTINED, file,
                "Tệp bị cách ly: {{fileName}}", "Tệp {{fileName}} ({{title}}) chứa mã độc và đã bị cách ly; tệp không được sử dụng.",
                "Xem tài liệu: {{deepLink}}",
                "File quarantined: {{fileName}}", "The file {{fileName}} ({{title}}) contains malware and was quarantined; it is not used.",
                "View the document: {{deepLink}}");
        List<Placeholder> job = List.of(new Placeholder("importType", Kind.TEXT), new Placeholder("status", Kind.TEXT),
                new Placeholder("errorCount", Kind.NUMBER));
        add(out, "SYSTEM", IMPORT_FINISHED, job,
                "Import {{importType}}: {{status}}", "Lần import {{importType}} đã chuyển sang trạng thái {{status}} ({{errorCount}} dòng lỗi).",
                "Xem kết quả: {{deepLink}}",
                "Import {{importType}}: {{status}}", "The {{importType}} import is now {{status}} ({{errorCount}} rows with errors).",
                "View the result: {{deepLink}}");
        return out;
    }

    /** IN_APP and EMAIL in vi-VN and en-US; the e-mail body is the in-app text plus the link line. */
    private static void add(List<NotificationTemplateDefinition> out, String category, String event,
            List<Placeholder> placeholders,
            String subjectVi, String bodyVi, String linkVi, String subjectEn, String bodyEn, String linkEn) {
        out.add(new NotificationTemplateDefinition(event, category, true, "IN_APP", "vi-VN", subjectVi, bodyVi, "TEXT",
                placeholders, false));
        out.add(new NotificationTemplateDefinition(event, category, true, "IN_APP", "en-US", subjectEn, bodyEn, "TEXT",
                placeholders, false));
        out.add(new NotificationTemplateDefinition(event, category, true, "EMAIL", "vi-VN", subjectVi,
                bodyVi + "\n\n" + linkVi, "TEXT", placeholders, false));
        out.add(new NotificationTemplateDefinition(event, category, true, "EMAIL", "en-US", subjectEn,
                bodyEn + "\n\n" + linkEn, "TEXT", placeholders, false));
    }
}
