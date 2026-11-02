package vn.yourhrm.people.internal.application.contract;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;

/** Tenant settings of the labour-contract stream (BR-PPL-052/053/054, US-PPL-059: offsets configurable per tenant). */
@Component
public class ContractSettings implements SettingCatalogue {

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(
                new SettingDefinition(ContractKeys.SETTING_EXPIRY_ALERT_DAYS, "JSON", List.of(), List.of(45, 30, 15),
                        Set.of("TENANT", "LEGAL_ENTITY"), false, false,
                        "Các mốc cảnh báo hợp đồng sắp hết hạn (số ngày trước ngày kết thúc)",
                        "Contract expiry alert offsets (days before the end date)"),
                new SettingDefinition(ContractKeys.SETTING_PROBATION_ALERT_DAYS, "INT", List.of(), 7,
                        Set.of("TENANT", "LEGAL_ENTITY"), false, false,
                        "Số ngày trước khi hết thử việc để nhắc ghi nhận kết quả",
                        "Days before the probation end to remind about the result"),
                new SettingDefinition(ContractKeys.SETTING_DEFINITE_EXTRA_EXCEPTIONS, "JSON", List.of(), List.of(),
                        Set.of("TENANT"), false, false,
                        "Các trường hợp ngoại lệ bổ sung cho quy tắc hai hợp đồng xác định thời hạn",
                        "Additional exceptions to the two-consecutive-definite-contracts rule"),
                new SettingDefinition(ContractKeys.SETTING_PROBATION_LEVEL_BY_GRADE, "JSON", List.of(), Map.of(),
                        Set.of("TENANT"), false, false,
                        "Cấp thử việc theo mã ngạch/bậc (ví dụ {\"M1\":\"ENTERPRISE_MANAGER\"})",
                        "Probation level per grade code (e.g. {\"M1\":\"ENTERPRISE_MANAGER\"})"));
    }
}
