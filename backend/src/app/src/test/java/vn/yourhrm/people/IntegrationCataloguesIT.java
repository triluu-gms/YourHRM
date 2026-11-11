package vn.yourhrm.people;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.core.api.DocumentTemplateCatalogue;
import vn.yourhrm.core.api.DocumentTemplateDefinition;
import vn.yourhrm.core.api.DocumentTypeCatalogue;
import vn.yourhrm.core.api.DocumentTypeDefinition;
import vn.yourhrm.core.api.NotificationTemplateCatalogue;
import vn.yourhrm.core.api.NotificationTemplateDefinition;
import vn.yourhrm.core.api.NotificationTemplateDefinition.Placeholder.Kind;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.shared.workflow.api.ProcessTypeCatalogue;
import vn.yourhrm.shared.workflow.api.ProcessTypeDefinition;
import vn.yourhrm.shared.workflow.api.StepTemplate;

/**
 * VI / EN labels of everything People registers in Core (US-PPL-078, BR-PPL-123, NFR-PPL-011) and the content rule of
 * People's task cards in Core's inbox (US-PPL-076): every notification template exists in Vietnamese and English per
 * event code and channel and carries no sensitive / money placeholder; every document template (and merge field),
 * document type, process type (and default step) and setting has a Vietnamese and an English label; no process type
 * declares a pay amount in its summary schema and no request summary People sent to Core contains one (Core shows the
 * summary to every approver as is, so it can never depend on the approver's compensation permission).
 */
class IntegrationCataloguesIT extends EmploymentTestSupport {

    /**
     * Templates of other streams that lack a language today (reported to the lead by the integration stream; remove
     * an entry once the owning stream adds the missing locale). Any NEW gap fails the test.
     */
    static final Set<String> KNOWN_GAPS = Set.of("hr.IdentityDocumentExpiring/EMAIL", "hr.WorkPermitExpiring/EMAIL");

    static final Pattern AMOUNT = Pattern.compile("(?i).*(amount|salary|wage|allowance|luong|grossup|netpay).*");

    @Autowired
    List<NotificationTemplateCatalogue> notifications;
    @Autowired
    List<DocumentTemplateCatalogue> documentTemplates;
    @Autowired
    List<DocumentTypeCatalogue> documentTypes;
    @Autowired
    List<ProcessTypeCatalogue> processTypes;
    @Autowired
    List<SettingCatalogue> settings;

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Test
    void everyPeopleNotificationTemplateExistsInVietnameseAndEnglishWithoutSensitivePlaceholders() {
        Map<String, Set<String>> languages = new HashMap<>();
        List<String> problems = new ArrayList<>();
        int n = 0;
        for (NotificationTemplateCatalogue c : notifications) {
            if (!"hr".equals(c.module())) {
                continue;
            }
            for (NotificationTemplateDefinition t : c.templates()) {
                n++;
                languages.computeIfAbsent(t.eventCode() + "/" + t.channel(), k -> new TreeSet<>())
                        .add(t.locale().substring(0, 2).toLowerCase(Locale.ROOT));
                if (blank(t.body()) || ("EMAIL".equals(t.channel()) && blank(t.subject()))) {
                    problems.add(t.eventCode() + "/" + t.channel() + "/" + t.locale() + ": empty text");
                }
                t.placeholders().stream().filter(p -> p.kind() == Kind.SENSITIVE || p.kind() == Kind.MONEY)
                        .forEach(p -> problems.add(t.eventCode() + ": placeholder " + p.name() + " is " + p.kind()));
            }
        }
        languages.forEach((k, v) -> {
            if (!v.containsAll(Set.of("vi", "en")) && !KNOWN_GAPS.contains(k)) {
                problems.add(k + ": languages " + v);
            }
        });
        assertThat(n).isPositive();
        assertThat(problems).isEmpty();
    }

    @Test
    void everyPeopleTemplateTypeProcessAndSettingHasBothLabels() {
        List<String> problems = new ArrayList<>();
        for (DocumentTemplateCatalogue c : documentTemplates) {
            if (!"hr".equals(c.module())) {
                continue;
            }
            for (DocumentTemplateDefinition t : c.templates()) {
                if (blank(t.nameVi()) || blank(t.nameEn())) {
                    problems.add("document template " + t.code());
                }
                t.mergeFields().stream().filter(f -> blank(f.labelVi()) || blank(f.labelEn()))
                        .forEach(f -> problems.add("merge field " + t.code() + "." + f.key()));
            }
        }
        for (DocumentTypeCatalogue c : documentTypes) {
            if (!"hr".equals(c.module())) {
                continue;
            }
            for (DocumentTypeDefinition t : c.documentTypes()) {
                if (blank(t.nameVi()) || blank(t.nameEn())) {
                    problems.add("document type " + t.code());
                }
            }
        }
        int types = 0;
        for (ProcessTypeCatalogue c : processTypes) {
            if (!"hr".equals(c.module())) {
                continue;
            }
            for (ProcessTypeDefinition p : c.processTypes()) {
                types++;
                if (blank(p.nameVi()) || blank(p.nameEn())) {
                    problems.add("process type " + p.code());
                }
                for (StepTemplate s : p.defaultSteps()) {
                    if (blank(s.nameVi()) || blank(s.nameEn())) {
                        problems.add("step " + p.code() + "." + s.stepKey());
                    }
                }
                if (p.summarySchema().get("properties") instanceof Map<?, ?> props) {
                    props.keySet().stream().map(String::valueOf).filter(k -> AMOUNT.matcher(k).matches())
                            .forEach(k -> problems.add("summary of " + p.code() + " declares pay field " + k));
                }
            }
        }
        for (SettingCatalogue c : settings) {
            if (!"hr".equals(c.module())) {
                continue;
            }
            for (SettingDefinition s : c.settings()) {
                if (blank(s.descriptionVi()) || blank(s.descriptionEn())) {
                    problems.add("setting " + s.key());
                }
            }
        }
        assertThat(types).isGreaterThanOrEqualTo(8);
        assertThat(problems).isEmpty();
    }

    @Test
    void peopleTaskSummariesInCoresInboxNeverCarrySalary() throws Exception {
        World w = employmentWorld("int-cards");
        UUID id = hire(w, hireBody(newPerson("001095000901"), w.le(), w.unit().id(), w.location(),
                TODAY.plusDays(5), 12_345_678));
        List<Map<String, Object>> summaries = ADMIN.queryForList("""
                select process_type_code, request_summary::text as summary from core.workflow_instance
                 where process_type_code like 'hr.%'""");
        assertThat(summaries).isNotEmpty();
        assertThat(ADMIN.queryForObject("select request_summary::text from core.workflow_instance where subject_id = ?",
                String.class, id)).contains("employeeNo").doesNotContain("12345678");
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> s : summaries) {
            Map<String, Object> m = json(String.valueOf(s.get("summary")));
            m.keySet().stream().filter(k -> AMOUNT.matcher(k).matches())
                    .forEach(k -> problems.add(s.get("process_type_code") + ": " + k));
        }
        assertThat(problems).isEmpty();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String s) throws Exception {
        return new tools.jackson.databind.json.JsonMapper().readValue(s, Map.class);
    }
}
