package vn.yourhrm.attendance.internal.application.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.AttendanceLegalParameters;
import vn.yourhrm.attendance.internal.application.leave.LeaveCatalogues.StatutoryType;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyData;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeData;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.attendance.internal.infra.timesheet.TimesheetStore;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Leave types and policies (BR-ATT-055..060). A type says who pays, the unit, the counting basis, whether a balance is
 * tracked, eligibility, required documents and SI claim data, maxima, whether it counts as working time and how it
 * maps to an attendance code and payroll category. A policy is effective-dated per type × (legal entity, employee
 * category) and holds the accrual, proration, leave year, carry-over and notice rules; for annual leave it may grant
 * more than the statutory floor, never less (BR-ATT-057). The VN statutory types are seeded per tenant (BR-ATT-056).
 */
@Service
public class LeaveTypeService {

    public record TypeInput(String code, String nameVi, String nameEn, String category, String paidBy, String minUnit,
            String countingBasis, Boolean tracksBalance, Boolean requiresSiClaimData, Boolean requiresAttachment,
            BigDecimal maxDaysPerRequest, BigDecimal maxDaysPerYear, Boolean countsAsService, String attendanceCode,
            String payrollCategory, Map<String, Object> eligibility, String status, Integer version) {
    }

    public record PolicyInput(UUID leaveTypeId, String code, UUID legalEntityId, String employeeCategoryCode,
            String accrualMethod, BigDecimal baseEntitlementDays, Integer seniorityStepYears,
            BigDecimal seniorityStepDays, String prorationRule, Integer leaveYearStartMonth,
            BigDecimal carryOverMaxDays, Integer carryOverExpiryMonths, String expiryAction,
            BigDecimal allowNegativeDays, Integer noticeDays, LocalDate validFrom, LocalDate validTo) {
    }

    /** Result of seeding: the codes created, and warnings (e.g. a legal parameter that was not defined). */
    public record SeedResult(List<String> created, List<String> existing, List<String> warnings) {
    }

    static final Set<String> ELIGIBILITY_KEYS = Set.of("gender", "min_service_months", "allow_probation",
            "employee_categories");

    private final LeaveStore store;
    private final TimesheetStore codes;
    private final AttendanceLegalParameters legal;
    private final LeaveEntitlements entitlements;
    private final AuthorizationService authz;
    private final BusinessClock clock;

    public LeaveTypeService(LeaveStore store, TimesheetStore codes, AttendanceLegalParameters legal,
            LeaveEntitlements entitlements, AuthorizationService authz, BusinessClock clock) {
        this.store = store;
        this.codes = codes;
        this.legal = legal;
        this.entitlements = entitlements;
        this.authz = authz;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------- types

    @AttTransactional(readOnly = true)
    public List<TypeRow> types() {
        return store.types();
    }

    @AttTransactional
    public TypeRow createType(TypeInput in) {
        TypeData d = validate(in, null);
        if (store.typeByCode(d.code()).isPresent()) {
            throw ApiException.conflict("leave-type-exists", "Leave type " + d.code() + " already exists");
        }
        return store.type(store.insertType(d)).orElseThrow();
    }

    @AttTransactional
    public TypeRow updateType(UUID id, TypeInput in) {
        TypeRow cur = store.type(id).orElseThrow(() -> ApiException.notFound("Leave type", id));
        TypeData d = validate(in, cur);
        if (in.version() == null || !store.updateType(id, in.version(), d)) {
            throw ApiException.conflict("version-conflict", "The leave type was changed meanwhile; reload it");
        }
        return store.type(id).orElseThrow();
    }

    /** Creates the VN statutory types the tenant does not have yet (BR-ATT-056); idempotent. */
    @AttTransactional
    public SeedResult seedStatutory() {
        List<String> created = new ArrayList<>();
        List<String> existing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        LocalDate today = clock.today();
        Map<String, Object> personal = legal.find(AttendanceLegalParameters.LEAVE_PERSONAL_DAYS, Scope.tenant(), today)
                .map(v -> v.asMap()).orElse(null);
        for (StatutoryType s : LeaveCatalogues.STATUTORY_TYPES) {
            if (store.typeByCode(s.code()).isPresent()) {
                existing.add(s.code());
                continue;
            }
            BigDecimal max = null;
            if (s.personalKey() != null) {
                Object v = personal == null ? null : personal.get(s.personalKey());
                if (v == null) {
                    warnings.add("Legal parameter " + AttendanceLegalParameters.LEAVE_PERSONAL_DAYS + "."
                            + s.personalKey() + " is not defined: " + s.code() + " has no maximum per request");
                } else {
                    max = new BigDecimal(v.toString());
                }
            }
            store.insertType(new TypeData(s.code(), s.nameVi(), s.nameEn(), s.category(), s.paidBy(), s.minUnit(),
                    s.basis(), s.tracksBalance(), s.siClaim(), s.attachment(), max, null, s.countsAsService(),
                    s.attendanceCode(), s.payrollCategory(), s.eligibility(), true, s.status()));
            created.add(s.code());
        }
        return new SeedResult(created, existing, warnings);
    }

    private TypeData validate(TypeInput in, TypeRow cur) {
        String code = cur != null ? cur.data().code() : LeaveInputs.upper(in.code());
        if (code == null || !code.matches("[A-Z0-9_]{1,30}")) {
            throw invalid("invalid-code", "The code is 1-30 letters, digits or _");
        }
        String nameVi = LeaveInputs.text(in.nameVi());
        if (nameVi == null) {
            throw invalid("name-required", "The Vietnamese name is required");
        }
        String category = oneOf("category", in.category(), LeaveRules.CATEGORIES, null);
        String paidBy = oneOf("paidBy", in.paidBy(), LeaveRules.PAID_BY, null);
        String unit = oneOf("minUnit", in.minUnit(), LeaveRules.UNITS, "DAY");
        String basis = oneOf("countingBasis", in.countingBasis(), LeaveRules.BASES, "WORKING_DAY");
        String payroll = oneOf("payrollCategory", in.payrollCategory(), LeaveRules.PAYROLL_CATEGORIES, null);
        String status = oneOf("status", in.status(), Set.of("ACTIVE", "INACTIVE"), "ACTIVE");
        String code2 = LeaveInputs.upper(in.attendanceCode());
        var ac = code2 == null ? null : codes.effectiveCodes().get(code2);
        if (ac == null || !"LEAVE".equals(ac.kind())) {
            throw invalid("invalid-attendance-code", "The attendance code must be an existing LEAVE code");
        }
        if ("CALENDAR_DAY".equals(basis) && "HOUR".equals(unit)) {
            throw invalid("invalid-unit", "Calendar-day leave is taken in days");
        }
        for (BigDecimal v : new BigDecimal[] {in.maxDaysPerRequest(), in.maxDaysPerYear()}) {
            if (v != null && v.signum() <= 0) {
                throw invalid("invalid-maximum", "Maxima are positive");
            }
        }
        Map<String, Object> elig = in.eligibility() == null ? Map.of() : in.eligibility();
        for (String k : elig.keySet()) {
            if (!ELIGIBILITY_KEYS.contains(k)) {
                throw invalid("invalid-eligibility", "Eligibility keys are " + ELIGIBILITY_KEYS);
            }
        }
        Object g = elig.get("gender");
        if (g != null && !Set.of("M", "F").contains(g.toString())) {
            throw invalid("invalid-eligibility", "Eligibility gender is M or F");
        }
        boolean tracks = in.tracksBalance() != null ? in.tracksBalance()
                : cur != null ? cur.data().tracksBalance() : false;
        return new TypeData(code, nameVi, LeaveInputs.text(in.nameEn()), category, paidBy, unit, basis, tracks,
                bool(in.requiresSiClaimData(), cur == null ? null : cur.data().requiresSiClaimData()),
                bool(in.requiresAttachment(), cur == null ? null : cur.data().requiresAttachment()),
                in.maxDaysPerRequest(), in.maxDaysPerYear(),
                in.countsAsService() != null ? in.countsAsService() : cur == null || cur.data().countsAsService(),
                code2, payroll, elig, cur != null && cur.data().statutory(), status);
    }

    // ---------------------------------------------------------------------------------------------- policies

    @AttTransactional(readOnly = true)
    public List<PolicyRow> policies(UUID leaveTypeId) {
        return store.policies(leaveTypeId);
    }

    @AttTransactional
    public PolicyRow createPolicy(PolicyInput in) {
        TypeRow type = store.type(in.leaveTypeId()).orElseThrow(() -> ApiException.notFound("Leave type",
                in.leaveTypeId()));
        if (in.legalEntityId() != null && !authz.can(AttendancePermissions.LEAVE_TYPE_UPDATE,
                new AuthorizationTarget.LegalEntity(in.legalEntityId()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                    "The legal entity is outside your data scope");
        }
        String code = LeaveInputs.upper(in.code());
        if (code == null) {
            throw invalid("code-required", "The policy code is required");
        }
        if (in.validFrom() == null) {
            throw invalid("valid-from-required", "The policy starts on validFrom");
        }
        if (in.validTo() != null && !in.validTo().isAfter(in.validFrom())) {
            throw invalid("invalid-period", "validTo is after validFrom");
        }
        String accrual = oneOf("accrualMethod", in.accrualMethod(), LeaveRules.ACCRUAL_METHODS,
                type.data().tracksBalance() ? "MONTHLY" : "NONE");
        if (!type.data().tracksBalance() && !"NONE".equals(accrual)) {
            throw invalid("accrual-without-balance", "Only balance-tracked leave types accrue");
        }
        String proration = oneOf("prorationRule", in.prorationRule(), LeaveRules.PRORATION_RULES, "ND145_HALF_MONTH");
        String expiry = oneOf("expiryAction", in.expiryAction(), LeaveRules.EXPIRY_ACTIONS, "FORFEIT");
        int startMonth = in.leaveYearStartMonth() == null ? 1 : in.leaveYearStartMonth();
        if (startMonth < 1 || startMonth > 12) {
            throw invalid("invalid-leave-year", "The leave year starts in month 1-12");
        }
        for (BigDecimal v : new BigDecimal[] {in.baseEntitlementDays(), in.carryOverMaxDays(),
                in.allowNegativeDays(), in.seniorityStepDays()}) {
            if (v != null && v.signum() < 0) {
                throw invalid("invalid-value", "Days are not negative");
            }
        }
        if (in.noticeDays() != null && in.noticeDays() < 0
                || in.carryOverExpiryMonths() != null && in.carryOverExpiryMonths() < 0
                || in.seniorityStepYears() != null && in.seniorityStepYears() < 1) {
            throw invalid("invalid-value", "Notice days and expiry months are not negative; seniority steps >= 1 year");
        }
        if (!"ANNUAL".equals(type.data().category()) && !"NONE".equals(accrual) && in.baseEntitlementDays() == null) {
            throw invalid("base-required", "An accruing policy of a non-annual type gives its base entitlement");
        }
        if ("ANNUAL".equals(type.data().category()) && in.baseEntitlementDays() != null) {
            // BR-ATT-057: more than the floor, never less
            Optional<BigDecimal> floor = entitlements.annualFloor(in.legalEntityId(), in.validFrom());
            if (floor.isPresent() && in.baseEntitlementDays().compareTo(floor.get()) < 0) {
                throw invalid("below-statutory-floor", "The base entitlement " + in.baseEntitlementDays()
                        + " is below the statutory " + floor.get() + " days");
            }
        }
        try {
            UUID id = store.insertPolicy(new PolicyData(type.id(), code, in.legalEntityId(),
                    LeaveInputs.upper(in.employeeCategoryCode()), accrual, in.baseEntitlementDays(),
                    in.seniorityStepYears(), in.seniorityStepDays(), proration, startMonth, in.carryOverMaxDays(),
                    in.carryOverExpiryMonths(), expiry,
                    in.allowNegativeDays() == null ? BigDecimal.ZERO : in.allowNegativeDays(),
                    in.noticeDays() == null ? 0 : in.noticeDays(), in.validFrom(), in.validTo(), "ACTIVE"));
            return store.policy(id).orElseThrow();
        } catch (DataIntegrityViolationException e) {
            String m = String.valueOf(e.getMostSpecificCause().getMessage());
            if (m.contains("leave_policy_code_uq")) {
                throw ApiException.conflict("policy-code-exists", "Policy " + code + " already exists");
            }
            throw ApiException.conflict("policy-overlap", "Another active policy of the type covers the same legal "
                    + "entity, category and dates; end it first");
        }
    }

    /** Ends a policy on {@code validTo} (exclusive) so a new version can start. */
    @AttTransactional
    public PolicyRow endPolicy(UUID id, int version, LocalDate validTo) {
        PolicyRow p = store.policy(id).orElseThrow(() -> ApiException.notFound("Leave policy", id));
        if (validTo == null || !validTo.isAfter(p.data().validFrom())) {
            throw invalid("invalid-period", "validTo is after the policy's validFrom");
        }
        if (!store.endPolicy(id, version, validTo)) {
            throw ApiException.conflict("version-conflict", "The policy was changed meanwhile; reload it");
        }
        return store.policy(id).orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static boolean bool(Boolean in, Boolean cur) {
        return in != null ? in : cur != null && cur;
    }

    private static String oneOf(String field, String value, Set<String> allowed, String dflt) {
        String v = LeaveInputs.upper(value);
        if (v == null) {
            if (dflt == null) {
                throw invalid(field + "-required", field + " is required (" + allowed + ")");
            }
            return dflt;
        }
        if (!allowed.contains(v)) {
            throw invalid("invalid-" + field, field + " is one of " + allowed);
        }
        return v;
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
