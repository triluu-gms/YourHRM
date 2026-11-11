package vn.yourhrm.people.internal.application.separation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.yourhrm.core.api.DocumentTemplateDefinition.MergeField;
import vn.yourhrm.people.internal.application.DecisionService.Decision;
import vn.yourhrm.people.internal.application.decision.DecisionMergeFields;
import vn.yourhrm.people.internal.domain.separation.LegalGround;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Merge fields of the termination decisions (US-PPL-061, BR-PPL-130): TERMINATION and RETIREMENT, from the separation
 * that the decision was issued for. No personal data beyond the common employee fields; no amounts (severance is
 * Payroll's).
 */
@Configuration(proxyBeanMethods = false)
public class SeparationDecisionFields {

    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final Map<String, String[]> SEVERANCE = Map.of(
            "NONE", new String[] {"Không thuộc trường hợp hưởng trợ cấp thôi việc / mất việc làm",
                    "No severance or job-loss allowance"},
            "SEVERANCE_ALLOWANCE", new String[] {"Trợ cấp thôi việc theo Điều 46 Bộ luật Lao động",
                    "Severance allowance (Labour Code Art. 46)"},
            "JOB_LOSS_ALLOWANCE", new String[] {"Trợ cấp mất việc làm theo Điều 47 Bộ luật Lao động",
                    "Job-loss allowance (Labour Code Art. 47)"});

    @Bean
    DecisionMergeFields terminationDecisionFields(HrDb db) {
        return fields("TERMINATION", db);
    }

    @Bean
    DecisionMergeFields retirementDecisionFields(HrDb db) {
        return fields("RETIREMENT", db);
    }

    private static DecisionMergeFields fields(String type, HrDb db) {
        return new DecisionMergeFields() {
            @Override
            public String decisionType() {
                return type;
            }

            @Override
            public List<MergeField> fields() {
                return List.of(
                        MergeField.text("separation.ground_vi", "Căn cứ chấm dứt (tiếng Việt)", "Legal ground (Vietnamese)",
                                "Người lao động đơn phương chấm dứt hợp đồng (xin nghỉ việc)"),
                        MergeField.text("separation.ground_en", "Căn cứ chấm dứt (tiếng Anh)", "Legal ground (English)",
                                "Employee resignation"),
                        MergeField.text("separation.article", "Điều luật", "Legal article", "BLLĐ 2019 Điều 35"),
                        MergeField.text("separation.notice_date", "Ngày báo trước", "Notice date", "01/02/2026"),
                        MergeField.text("separation.last_working_date", "Ngày làm việc cuối cùng", "Last working day",
                                "15/03/2026"),
                        MergeField.text("separation.termination_date", "Ngày chấm dứt hợp đồng", "Termination date",
                                "15/03/2026"),
                        MergeField.text("separation.severance_vi", "Trợ cấp (tiếng Việt)", "Allowance (Vietnamese)",
                                "Trợ cấp thôi việc theo Điều 46 Bộ luật Lao động"),
                        MergeField.text("separation.severance_en", "Trợ cấp (tiếng Anh)", "Allowance (English)",
                                "Severance allowance (Labour Code Art. 46)"));
            }

            @Override
            public Map<String, Object> values(Decision d) {
                Map<String, Object> v = new HashMap<>();
                fields().forEach(f -> v.put(f.key(), ""));
                db.jdbc().sql("""
                                select legal_ground_code, notice_date, last_working_date, termination_date, severance_type
                                  from hr.separation where tenant_id = :t and decision_id = :d""")
                        .param("t", TenantContext.current().tenantId()).param("d", d.id())
                        .query(rs -> {
                            LegalGround.of(rs.getString(1)).ifPresent(g -> {
                                v.put("separation.ground_vi", g.nameVi());
                                v.put("separation.ground_en", g.nameEn());
                                v.put("separation.article", g.article());
                            });
                            v.put("separation.notice_date", date(rs.getObject(2, LocalDate.class)));
                            v.put("separation.last_working_date", date(rs.getObject(3, LocalDate.class)));
                            v.put("separation.termination_date", date(rs.getObject(4, LocalDate.class)));
                            String[] sev = SEVERANCE.getOrDefault(rs.getString(5), new String[] {"", ""});
                            v.put("separation.severance_vi", sev[0]);
                            v.put("separation.severance_en", sev[1]);
                        });
                return v;
            }
        };
    }

    private static String date(LocalDate d) {
        return d == null ? "" : VN.format(d);
    }
}
