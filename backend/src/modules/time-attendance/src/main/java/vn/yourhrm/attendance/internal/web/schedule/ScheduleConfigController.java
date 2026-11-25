package vn.yourhrm.attendance.internal.web.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.schedule.SchedulePatternService;
import vn.yourhrm.attendance.internal.application.schedule.SchedulePatternService.DayInput;
import vn.yourhrm.attendance.internal.application.schedule.SchedulePatternService.PatternInput;
import vn.yourhrm.attendance.internal.application.schedule.SchedulePatternService.PatternResult;
import vn.yourhrm.attendance.internal.application.schedule.SchedulePatternService.PatternView;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleRuleService;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleRuleService.RuleInput;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleRuleService.RuleResult;
import vn.yourhrm.attendance.internal.application.schedule.ScheduleRuleService.RuleView;
import vn.yourhrm.attendance.internal.application.schedule.ShiftService;
import vn.yourhrm.attendance.internal.application.schedule.ShiftService.BreakInput;
import vn.yourhrm.attendance.internal.application.schedule.ShiftService.ShiftInput;
import vn.yourhrm.attendance.internal.application.schedule.ShiftService.ShiftResult;
import vn.yourhrm.attendance.internal.application.schedule.ShiftService.ShiftView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Shifts, weekly schedule patterns and schedule assignment rules (BRD 03 §4.2: BR-ATT-010/011/014/015; US-ATT-008/
 * 009/010). Times are local {@code HH:mm}; end dates are exclusive. Create / update responses carry the statutory
 * {@code warnings} ({@code [verify-legal]}).
 */
@RestController
@RequestMapping("/api/v1/attendance")
class ScheduleConfigController {

    private final ShiftService shifts;
    private final SchedulePatternService patterns;
    private final ScheduleRuleService rules;

    ScheduleConfigController(ShiftService shifts, SchedulePatternService patterns, ScheduleRuleService rules) {
        this.shifts = shifts;
        this.patterns = patterns;
        this.rules = rules;
    }

    record BreakRequest(LocalTime start, LocalTime end, Integer durationMin, Boolean paid, Boolean flexible) {
        BreakInput toInput() {
            return new BreakInput(start, end, durationMin, Boolean.TRUE.equals(paid), Boolean.TRUE.equals(flexible));
        }
    }

    record ShiftRequest(@Size(max = 40) String code, @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn, @NotNull LocalTime startTime, @NotNull LocalTime endTime,
            Integer graceInMin, Integer graceOutMin, @Size(max = 40) String roundingRuleCode,
            Integer punchWindowBeforeMin, Integer punchWindowAfterMin, Integer minMinutesHalfDay,
            Integer minMinutesFullDay, BigDecimal stdHours, BigDecimal dayValue, LocalTime nightWindowStart,
            LocalTime nightWindowEnd, @Size(max = 40) String shiftGroupCode, List<@Valid BreakRequest> breaks,
            String status, Integer version) {

        ShiftInput toInput() {
            return new ShiftInput(code, nameVi, nameEn, startTime, endTime, graceInMin, graceOutMin, roundingRuleCode,
                    punchWindowBeforeMin, punchWindowAfterMin, minMinutesHalfDay, minMinutesFullDay, stdHours,
                    dayValue, nightWindowStart, nightWindowEnd, shiftGroupCode,
                    breaks == null ? List.of() : breaks.stream().map(BreakRequest::toInput).toList());
        }
    }

    record DayRequest(@NotNull Integer dayIndex, @NotBlank String dayKind, UUID shiftId, String shiftCode) {
    }

    record PatternRequest(@Size(max = 40) String code, @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn, Integer cycleLengthDays, @NotNull LocalDate anchorDate,
            @NotNull List<@Valid DayRequest> days, String status, Integer version) {

        PatternInput toInput() {
            return new PatternInput(code, nameVi, nameEn, cycleLengthDays, anchorDate, days.stream()
                    .map(d -> new DayInput(d.dayIndex(), d.dayKind(), d.shiftId(), d.shiftCode())).toList());
        }
    }

    record RuleRequest(@Size(max = 40) String code, Integer priority, UUID legalEntityId, UUID orgUnitId,
            UUID locationId, UUID jobTitleId, @Size(max = 40) String employeeCategoryCode,
            @Size(max = 40) String workingRegimeCode, @NotNull UUID patternId, @NotNull LocalDate validFrom,
            LocalDate validTo, String status, Integer version) {

        RuleInput toInput() {
            return new RuleInput(code, priority, legalEntityId, orgUnitId, locationId, jobTitleId,
                    employeeCategoryCode, workingRegimeCode, patternId, validFrom, validTo);
        }
    }

    record RegenerateRequest(UUID legalEntityId) {
    }

    record RegenerateResult(int linesChanged) {
    }

    // ------------------------------------------------------------------------------------------- shifts

    @GetMapping("/shifts")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_VIEW)
    public List<ShiftView> listShifts() {
        return shifts.list();
    }

    @GetMapping("/shifts/{id}")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_VIEW)
    public ShiftView getShift(@PathVariable UUID id) {
        return shifts.get(id);
    }

    @PostMapping("/shifts")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public ResponseEntity<ShiftResult> createShift(@Valid @RequestBody ShiftRequest req) {
        ShiftResult r = shifts.create(req.toInput());
        return ResponseEntity.created(URI.create("/api/v1/attendance/shifts/" + r.shift().id())).body(r);
    }

    @PutMapping("/shifts/{id}")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public ShiftResult updateShift(@PathVariable UUID id, @Valid @RequestBody ShiftRequest req) {
        return shifts.update(id, req.toInput(), req.status(), requireVersion(req.version()));
    }

    // ------------------------------------------------------------------------------------------- patterns

    @GetMapping("/schedule-patterns")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_VIEW)
    public List<PatternView> listPatterns() {
        return patterns.list();
    }

    @GetMapping("/schedule-patterns/{id}")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_VIEW)
    public PatternView getPattern(@PathVariable UUID id) {
        return patterns.get(id);
    }

    @PostMapping("/schedule-patterns")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public ResponseEntity<PatternResult> createPattern(@Valid @RequestBody PatternRequest req) {
        PatternResult r = patterns.create(req.toInput());
        return ResponseEntity.created(URI.create("/api/v1/attendance/schedule-patterns/" + r.pattern().id()))
                .body(r);
    }

    @PutMapping("/schedule-patterns/{id}")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public PatternResult updatePattern(@PathVariable UUID id, @Valid @RequestBody PatternRequest req) {
        return patterns.update(id, req.toInput(), req.status(), requireVersion(req.version()));
    }

    // ------------------------------------------------------------------------------------------- rules

    @GetMapping("/schedule-rules")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_VIEW)
    public List<RuleView> listRules() {
        return rules.list();
    }

    @GetMapping("/schedule-rules/{id}")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_VIEW)
    public RuleView getRule(@PathVariable UUID id) {
        return rules.get(id);
    }

    /** Saving a rule regenerates the affected rule-based schedules; {@code linesChanged} tells how many changed. */
    @PostMapping("/schedule-rules")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public ResponseEntity<RuleResult> createRule(@Valid @RequestBody RuleRequest req) {
        RuleResult r = rules.create(req.toInput());
        return ResponseEntity.created(URI.create("/api/v1/attendance/schedule-rules/" + r.rule().id())).body(r);
    }

    @PutMapping("/schedule-rules/{id}")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public RuleResult updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest req) {
        return rules.update(id, req.toInput(), req.status(), requireVersion(req.version()));
    }

    /** Re-evaluates the rules for every assignment line of a legal entity (all when none is given). */
    @PostMapping("/schedule-rules/regenerate")
    @RequiresPermission(AttendancePermissions.SCHEDULE_CONFIG_UPDATE)
    public RegenerateResult regenerate(@RequestBody(required = false) RegenerateRequest req,
            @RequestParam(required = false) UUID legalEntityId) {
        UUID le = legalEntityId != null ? legalEntityId : req == null ? null : req.legalEntityId();
        return new RegenerateResult(rules.regenerateAll(le));
    }

    private static int requireVersion(Integer version) {
        if (version == null) {
            throw vn.yourhrm.shared.common.error.ApiException.unprocessable("version-required",
                    "The version read is required to update");
        }
        return version;
    }
}
