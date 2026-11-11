package vn.yourhrm.people.internal.application.ess;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;

/**
 * Keys and tenant settings of the People ESS pages (EP-PPL-10). {@link #SALARY_HISTORY_VISIBLE}: the "my salary"
 * tab of US-PPL-073 is hidden unless the tenant (or legal entity) turns it on. The former-employee access window is
 * Core's {@link #FORMER_EMPLOYEE_ACCESS_DAYS} (OD-8, US-PPL-077), read here, never redefined.
 */
@Component
public class EssKeys implements SettingCatalogue {

    /** BOOL, default false (US-PPL-073 AC: salary tab hidden unless the policy flag is on). */
    public static final String SALARY_HISTORY_VISIBLE = "hr.ess.salary_history_visible";
    /** Core setting (INT, days after the termination date); registered by Core. */
    public static final String FORMER_EMPLOYEE_ACCESS_DAYS = "core.former_employee_access_days";

    @Override
    public String module() {
        return "hr";
    }

    @Override
    public List<SettingDefinition> settings() {
        return List.of(new SettingDefinition(SALARY_HISTORY_VISIBLE, "BOOL", List.of(), false,
                Set.of("TENANT", "LEGAL_ENTITY"), false, false,
                "Cho phép nhân viên xem lịch sử lương của mình trên ESS",
                "Let employees see their own salary history in ESS"));
    }
}
