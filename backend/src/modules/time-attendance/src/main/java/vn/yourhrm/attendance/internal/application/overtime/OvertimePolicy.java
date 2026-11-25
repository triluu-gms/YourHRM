package vn.yourhrm.attendance.internal.application.overtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeClassifier.NightWindow;
import vn.yourhrm.attendance.internal.domain.overtime.OvertimeRules.ProtectedRule;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.legal.LegalParameters.Value;

/**
 * The limits and policy that apply to overtime of one legal entity on one date: statutory values from the Legal
 * Parameter Service ([verify-legal], key names are T&amp;A proposals) and the tenant's settings. A missing statutory
 * value refuses the operation (fail closed, ADR-0010); it is never replaced by a constant.
 */
@Component
public class OvertimePolicy {

    public static final String STANDARD = "STANDARD_200";
    public static final String EXTENDED = "EXTENDED_300";

    /**
     * @param regimeRequested the regime set for the legal entity
     * @param regime          the regime applied: EXTENDED_300 only once the notice reference is recorded (BR-ATT-049)
     * @param yearCapMin      the annual cap of the applied regime
     */
    public record Limits(int maxDayTotalMin, int maxDayOffMin, int monthCapMin, int yearCapMin,
            String regimeRequested, String regime, String noticeRef, int warnPercent, boolean blockDaily,
            boolean paidAsApproved, NightWindow night, Map<String, ProtectedRule> protectedRules, UUID packId) {

        public boolean noticeMissing() {
            return EXTENDED.equals(regimeRequested) && noticeRef == null;
        }
    }

    private final AttendanceLegalParameters legal;
    private final Settings settings;

    public OvertimePolicy(AttendanceLegalParameters legal, Settings settings) {
        this.legal = legal;
        this.settings = settings;
    }

    public Limits limits(UUID legalEntityId, UUID locationId, LocalDate date) {
        Scope scope = new Scope(legalEntityId, locationId);
        Value dayTotal = required(AttendanceLegalParameters.OT_MAX_DAY_TOTAL_HOURS, scope, date);
        int maxDayTotal = minutes(dayTotal);
        int maxDayOff = minutes(required(AttendanceLegalParameters.OT_MAX_DAY_OFF_HOURS, scope, date));
        int month = minutes(required(AttendanceLegalParameters.OT_MAX_MONTH_HOURS, scope, date));
        Settings.Scope s = new Settings.Scope(legalEntityId, null);
        String requested = settings.stringValue(OvertimeCatalogues.SETTING_ANNUAL_REGIME, s, date);
        String notice = settings.stringValue(OvertimeCatalogues.SETTING_REGIME_NOTICE_REF, s, date);
        notice = notice == null || notice.isBlank() ? null : notice.strip();
        String regime = EXTENDED.equals(requested) && notice != null ? EXTENDED : STANDARD;
        int year = minutes(required(EXTENDED.equals(regime) ? AttendanceLegalParameters.OT_MAX_YEAR_EXTENDED_HOURS
                : AttendanceLegalParameters.OT_MAX_YEAR_HOURS, scope, date));
        NightWindow night = new NightWindow(time(required(AttendanceLegalParameters.NIGHT_START, scope, date)),
                time(required(AttendanceLegalParameters.NIGHT_END, scope, date)));
        Map<String, ProtectedRule> rules = protectedRules(
                required(AttendanceLegalParameters.OT_PROTECTED_RULES, scope, date));
        int warn = (int) settings.longValue(OvertimeCatalogues.SETTING_CAP_WARN_PERCENT, s, date);
        boolean block = "BLOCK".equals(settings.stringValue(OvertimeCatalogues.SETTING_DAILY_CAP_MODE, s, date));
        boolean paidAsApproved = settings.booleanValue(OvertimeCatalogues.SETTING_PAID_AS_APPROVED, s, date);
        return new Limits(maxDayTotal, maxDayOff, month, year, requested, regime, notice, warn, block,
                paidAsApproved, night, rules, dayTotal.packId());
    }

    /** The night window of the legal pack, or the shift's override when it has one (BR-ATT-032). */
    public static NightWindow night(Limits limits, LocalTime shiftStart, LocalTime shiftEnd) {
        return shiftStart != null && shiftEnd != null ? new NightWindow(shiftStart, shiftEnd) : limits.night();
    }

    private Value required(String key, Scope scope, LocalDate date) {
        return legal.find(key, scope, date).orElseThrow(() -> new ApiException(
                org.springframework.http.HttpStatus.CONFLICT, "legal-parameter-missing",
                "The legal parameter " + key + " is not defined on " + date + "; overtime cannot be checked",
                Map.of("parameter", key)));
    }

    private static int minutes(Value v) {
        return v.asDecimal().multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static LocalTime time(Value v) {
        return LocalTime.parse(v.asString().strip());
    }

    private static Map<String, ProtectedRule> protectedRules(Value v) {
        Map<String, ProtectedRule> out = new HashMap<>();
        Object raw = v.value();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, r) -> out.put(String.valueOf(k).toUpperCase(Locale.ROOT),
                    ProtectedRule.valueOf(String.valueOf(r).toUpperCase(Locale.ROOT))));
        } else if (raw instanceof List<?>) {
            throw new IllegalStateException(AttendanceLegalParameters.OT_PROTECTED_RULES + " must be an object");
        }
        return out;
    }
}
