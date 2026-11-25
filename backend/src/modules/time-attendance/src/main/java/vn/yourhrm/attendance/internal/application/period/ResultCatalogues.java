package vn.yourhrm.attendance.internal.application.period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.domain.period.ResultLines.MealRule;
import vn.yourhrm.core.api.SettingCatalogue;
import vn.yourhrm.core.api.SettingDefinition;
import vn.yourhrm.core.api.Settings;

/**
 * The tenant rule of {@code MEAL_ELIGIBLE_DAYS} (BRD §7.3 item 13, "theo quy tắc của tenant"), as Core settings per
 * legal entity, effective-dated like the other T&amp;A policy settings (one policy set per legal entity in P1,
 * BR-ATT-083 is P2): the minimum worked hours of an eligible day, and whether business-trip and WFH days are eligible
 * whatever their worked hours. The rule in force on the period's last day applies to the whole period. Allowance
 * amounts stay in Payroll: T&amp;A only counts days.
 */
@Component
public class ResultCatalogues implements SettingCatalogue {

    /** Minimum worked hours of a day eligible for the meal / attendance allowance; default 4 h. */
    public static final String SETTING_MEAL_MIN_HOURS = "att.meal_min_worked_hours";
    /** Business-trip days (attendance codes of kind TRIP) are eligible; default true. */
    public static final String SETTING_MEAL_TRIP_DAYS = "att.meal_trip_days_eligible";
    /** WFH days (attendance codes of kind WFH) are eligible; default true. */
    public static final String SETTING_MEAL_WFH_DAYS = "att.meal_wfh_days_eligible";

    public static final BigDecimal DEFAULT_MEAL_MIN_HOURS = new BigDecimal("4");

    @Override
    public String module() {
        return "att";
    }

    @Override
    public List<SettingDefinition> settings() {
        Set<String> levels = Set.of("TENANT", "LEGAL_ENTITY");
        return List.of(
                new SettingDefinition(SETTING_MEAL_MIN_HOURS, "DECIMAL", List.of(), DEFAULT_MEAL_MIN_HOURS, levels,
                        true, false,
                        "Số giờ làm việc tối thiểu trong ngày để được tính ngày hưởng phụ cấp ăn ca / chuyên cần",
                        "Minimum worked hours of a day eligible for the meal / attendance allowance"),
                new SettingDefinition(SETTING_MEAL_TRIP_DAYS, "BOOL", List.of(), true, levels, true, false,
                        "Ngày đi công tác được tính ngày hưởng phụ cấp ăn ca / chuyên cần",
                        "Business-trip days are eligible for the meal / attendance allowance"),
                new SettingDefinition(SETTING_MEAL_WFH_DAYS, "BOOL", List.of(), true, levels, true, false,
                        "Ngày làm việc tại nhà được tính ngày hưởng phụ cấp ăn ca / chuyên cần",
                        "Work-from-home days are eligible for the meal / attendance allowance"));
    }

    /** The rule of the legal entity on {@code asOf}; a negative minimum is read as 0. */
    public static MealRule mealRule(Settings settings, UUID legalEntityId, LocalDate asOf) {
        Settings.Scope scope = new Settings.Scope(legalEntityId, null);
        Object min = settings.value(SETTING_MEAL_MIN_HOURS, scope, asOf);
        BigDecimal hours = min instanceof Number n ? new BigDecimal(n.toString()) : DEFAULT_MEAL_MIN_HOURS;
        if (hours.signum() < 0) {
            hours = BigDecimal.ZERO;
        }
        return new MealRule(hours, settings.booleanValue(SETTING_MEAL_TRIP_DAYS, scope, asOf),
                settings.booleanValue(SETTING_MEAL_WFH_DAYS, scope, asOf));
    }
}
