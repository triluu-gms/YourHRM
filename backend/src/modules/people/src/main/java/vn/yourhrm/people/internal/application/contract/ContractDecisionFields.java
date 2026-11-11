package vn.yourhrm.people.internal.application.contract;

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
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * Merge fields of the decision types issued by the contract stream (US-PPL-058): PROBATION_RESULT and
 * CONTRACT_SUSPENSION.
 */
@Configuration(proxyBeanMethods = false)
public class ContractDecisionFields {

    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static final Map<String, String[]> REASONS = Map.of(
            "MILITARY_SERVICE", new String[] {"Thực hiện nghĩa vụ quân sự, nghĩa vụ tham gia Dân quân tự vệ",
                    "Military or militia service"},
            "DETENTION", new String[] {"Bị tạm giữ, tạm giam", "Held in custody or detention"},
            "MATERNITY_EXTENDED", new String[] {"Nghỉ thêm sau thời gian nghỉ thai sản theo thỏa thuận",
                    "Agreed leave after maternity leave"},
            "ELECTED_OFFICE", new String[] {"Được bầu làm nhiệm vụ chuyên trách", "Elected to a full-time office"},
            "AGREEMENT", new String[] {"Theo thỏa thuận của hai bên", "By agreement of both parties"},
            "OTHER", new String[] {"Trường hợp khác theo quy định", "Other statutory case"});

    @Bean
    DecisionMergeFields probationResultDecisionFields(HrDb db) {
        return new DecisionMergeFields() {
            @Override
            public String decisionType() {
                return "PROBATION_RESULT";
            }

            @Override
            public List<MergeField> fields() {
                return List.of(
                        MergeField.text("contract.no", "Số hợp đồng / thỏa thuận thử việc", "Contract number",
                                "001/2026/HĐTV-HQ"),
                        MergeField.text("probation.end_date", "Ngày kết thúc thử việc", "Probation end date", "31/03/2026"),
                        MergeField.text("probation.result_vi", "Kết quả thử việc (tiếng Việt)", "Result (Vietnamese)",
                                "Đạt"),
                        MergeField.text("probation.result_en", "Kết quả thử việc (tiếng Anh)", "Result (English)",
                                "Passed"));
            }

            @Override
            public Map<String, Object> values(Decision d) {
                Map<String, Object> v = new HashMap<>(Map.of("contract.no", "", "probation.end_date", "",
                        "probation.result_vi", "", "probation.result_en", ""));
                // the contract this decision recorded, else (re-issued decision) the employment's latest result
                db.jdbc().sql("select c.contract_no, " + ContractExpiryService.PROBATION_END + """
                                       , c.probation_result
                                  from hr.contract c
                                 where c.tenant_id = :t and c.employment_id = :e and c.probation_result is not null
                                 order by (c.probation_result_decision_id = :d) desc, c.probation_result_date desc,
                                          c.updated_at desc limit 1""")
                        .param("t", TenantContext.current().tenantId()).param("e", d.employmentId())
                        .param("d", d.id())
                        .query(rs -> {
                            v.put("contract.no", nz(rs.getString(1)));
                            LocalDate end = rs.getObject(2, LocalDate.class);
                            v.put("probation.end_date", end == null ? "" : VN.format(end));
                            boolean pass = ProbationService.PASS.equals(rs.getString(3));
                            v.put("probation.result_vi", pass ? "Đạt" : "Không đạt");
                            v.put("probation.result_en", pass ? "Passed" : "Not passed");
                        });
                return v;
            }
        };
    }

    @Bean
    DecisionMergeFields suspensionDecisionFields(HrDb db) {
        return new DecisionMergeFields() {
            @Override
            public String decisionType() {
                return "CONTRACT_SUSPENSION";
            }

            @Override
            public List<MergeField> fields() {
                return List.of(
                        MergeField.text("contract.no", "Số hợp đồng", "Contract number", "001/2026/HĐLĐ-HQ"),
                        MergeField.text("suspension.reason_vi", "Lý do tạm hoãn (tiếng Việt)", "Reason (Vietnamese)",
                                "Thực hiện nghĩa vụ quân sự"),
                        MergeField.text("suspension.reason_en", "Lý do tạm hoãn (tiếng Anh)", "Reason (English)",
                                "Military service"),
                        MergeField.text("suspension.from", "Tạm hoãn từ ngày", "Suspended from", "01/03/2026"),
                        MergeField.text("suspension.to", "Tạm hoãn đến ngày", "Suspended until", "28/02/2028"));
            }

            @Override
            public Map<String, Object> values(Decision d) {
                Map<String, Object> v = new HashMap<>(Map.of("contract.no", "", "suspension.reason_vi", "",
                        "suspension.reason_en", "", "suspension.from", "", "suspension.to", ""));
                db.jdbc().sql("""
                                select c.contract_no, s.reason_code, s.valid_from, s.valid_to
                                  from hr.contract_suspension s
                                  join hr.contract c on c.tenant_id = s.tenant_id and c.id = s.contract_id
                                 where s.tenant_id = :t and s.decision_id = :d""")
                        .param("t", TenantContext.current().tenantId()).param("d", d.id())
                        .query(rs -> {
                            v.put("contract.no", nz(rs.getString(1)));
                            String[] reason = REASONS.getOrDefault(rs.getString(2),
                                    new String[] {rs.getString(2), rs.getString(2)});
                            v.put("suspension.reason_vi", reason[0]);
                            v.put("suspension.reason_en", reason[1]);
                            v.put("suspension.from", VN.format(rs.getObject(3, LocalDate.class)));
                            LocalDate to = rs.getObject(4, LocalDate.class);
                            v.put("suspension.to", to == null ? "" : VN.format(to.minusDays(1)));
                        });
                return v;
            }
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

}
