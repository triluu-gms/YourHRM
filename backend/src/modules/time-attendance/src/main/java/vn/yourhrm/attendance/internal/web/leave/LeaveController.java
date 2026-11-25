package vn.yourhrm.attendance.internal.web.leave;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
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
import vn.yourhrm.attendance.api.LeaveSettlements.Settlement;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService.AdjustInput;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService.BalanceView;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService.RunSummary;
import vn.yourhrm.attendance.internal.application.leave.LeaveRequestService;
import vn.yourhrm.attendance.internal.application.leave.LeaveRequestService.Preview;
import vn.yourhrm.attendance.internal.application.leave.LeaveRequestService.RequestInput;
import vn.yourhrm.attendance.internal.application.leave.LeaveRequestService.SiClaimInput;
import vn.yourhrm.attendance.internal.application.leave.LeaveRequestService.SiClaimView;
import vn.yourhrm.attendance.internal.application.leave.LeaveTypeService;
import vn.yourhrm.attendance.internal.application.leave.LeaveTypeService.PolicyInput;
import vn.yourhrm.attendance.internal.application.leave.LeaveTypeService.SeedResult;
import vn.yourhrm.attendance.internal.application.leave.LeaveTypeService.TypeInput;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.RequestRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Leave management (BRD 03 §4.6: BR-ATT-055..063, 065, 067, 068): leave types and policies, leave requests through
 * Core's workflow, balances and the ledger, HR adjustments, the accrual / year-end / carry-expiry runs and the leaver
 * settlement. Opening balances are loaded through Core's import engine ({@code OB_LEAVE_BALANCE}).
 */
@RestController
@RequestMapping("/api/v1/attendance")
class LeaveController {

    private final LeaveTypeService types;
    private final LeaveRequestService requests;
    private final LeaveBalanceService balances;

    LeaveController(LeaveTypeService types, LeaveRequestService requests, LeaveBalanceService balances) {
        this.types = types;
        this.requests = requests;
        this.balances = balances;
    }

    record EndPolicyRequest(@NotNull Integer version, @NotNull LocalDate validTo) {
    }

    record LeaveRequestBody(UUID leaveTypeId, String leaveTypeCode, @NotNull LocalDate startDate, LocalDate endDate,
            String startPart, String endPart, LocalTime startTime, LocalTime endTime, String reason,
            List<UUID> attachmentDocumentIds, SiClaimInput siClaim) {

        RequestInput input() {
            return new RequestInput(leaveTypeId, leaveTypeCode, startDate, endDate, startPart, endPart, startTime,
                    endTime, reason, attachmentDocumentIds, siClaim);
        }
    }

    record WithdrawRequest(String reason) {
    }

    record EarlyReturnRequest(@NotNull LocalDate returnDate, List<UUID> attachmentDocumentIds) {
    }

    record AdjustRequest(@NotNull UUID leaveTypeId, Integer leaveYear, @NotNull BigDecimal quantity, String reason) {
    }

    record AccrualRequest(UUID legalEntityId, @NotNull YearMonth month) {
    }

    record YearEndRequest(UUID legalEntityId, @NotNull Integer year) {
    }

    record ExpiryRequest(UUID legalEntityId, LocalDate asOf) {
    }

    // ------------------------------------------------------------------------------------------ types

    @GetMapping("/leave-types")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_VIEW)
    public List<TypeRow> types() {
        return types.types();
    }

    @PostMapping("/leave-types")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_UPDATE)
    public ResponseEntity<TypeRow> createType(@RequestBody TypeInput in) {
        return ResponseEntity.status(201).body(types.createType(in));
    }

    @PutMapping("/leave-types/{id}")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_UPDATE)
    public TypeRow updateType(@PathVariable UUID id, @RequestBody TypeInput in) {
        return types.updateType(id, in);
    }

    /** Creates the VN statutory leave types the tenant does not have yet (BR-ATT-056). */
    @PostMapping("/leave-types/statutory")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_UPDATE)
    public SeedResult seedStatutory() {
        return types.seedStatutory();
    }

    @GetMapping("/leave-policies")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_VIEW)
    public List<PolicyRow> policies(@RequestParam(required = false) UUID leaveTypeId) {
        return types.policies(leaveTypeId);
    }

    @PostMapping("/leave-policies")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_UPDATE)
    public ResponseEntity<PolicyRow> createPolicy(@RequestBody PolicyInput in) {
        return ResponseEntity.status(201).body(types.createPolicy(in));
    }

    @PostMapping("/leave-policies/{id}/end")
    @RequiresPermission(AttendancePermissions.LEAVE_TYPE_UPDATE)
    public PolicyRow endPolicy(@PathVariable UUID id, @RequestBody EndPolicyRequest req) {
        return types.endPolicy(id, req.version(), req.validTo());
    }

    // --------------------------------------------------------------------------------------- requests

    @PostMapping("/employments/{employmentId}/leave-requests/preview")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_CREATE)
    public Preview preview(@PathVariable UUID employmentId, @RequestBody LeaveRequestBody req) {
        return requests.preview(employmentId, req.input());
    }

    @PostMapping("/employments/{employmentId}/leave-requests")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_CREATE)
    public ResponseEntity<RequestRow> request(@PathVariable UUID employmentId, @RequestBody LeaveRequestBody req) {
        return ResponseEntity.status(201).body(requests.request(employmentId, req.input()));
    }

    @GetMapping("/employments/{employmentId}/leave-requests")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_VIEW)
    public List<RequestRow> ofEmployment(@PathVariable UUID employmentId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        return requests.ofEmployment(employmentId, from, to);
    }

    @GetMapping("/leave-requests")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_VIEW)
    public List<RequestRow> list(@RequestParam(required = false) UUID legalEntityId,
            @RequestParam(required = false) UUID orgUnitId, @RequestParam(required = false) UUID leaveTypeId,
            @RequestParam(required = false) String status,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "500") int limit, @RequestParam(defaultValue = "0") int offset) {
        return requests.list(legalEntityId, orgUnitId, leaveTypeId, status, from, to, limit, offset);
    }

    @GetMapping("/leave-requests/{id}")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_VIEW)
    public RequestRow get(@PathVariable UUID id) {
        return requests.get(id);
    }

    @PostMapping("/leave-requests/{id}/withdraw")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_CREATE)
    public RequestRow withdraw(@PathVariable UUID id, @RequestBody(required = false) WithdrawRequest req) {
        return requests.withdraw(id, req == null ? null : req.reason());
    }

    @PostMapping("/leave-requests/{id}/early-return")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_MANAGE)
    public RequestRow earlyReturn(@PathVariable UUID id, @RequestBody EarlyReturnRequest req) {
        return requests.earlyReturn(id, req.returnDate(), req.attachmentDocumentIds());
    }

    @PutMapping("/leave-requests/{id}/si-claim")
    @RequiresPermission(AttendancePermissions.LEAVE_REQUEST_MANAGE)
    public RequestRow updateSiClaim(@PathVariable UUID id, @RequestBody SiClaimInput in) {
        return requests.updateSiClaim(id, in);
    }

    /** P3 health data: audited read. */
    @GetMapping("/leave-requests/{id}/si-claim")
    @RequiresPermission(AttendancePermissions.LEAVE_SI_DATA_VIEW)
    public SiClaimView siClaim(@PathVariable UUID id) {
        return requests.siClaim(id);
    }

    // --------------------------------------------------------------------------------------- balances

    @GetMapping("/employments/{employmentId}/leave-balances")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_VIEW)
    public List<BalanceView> balances(@PathVariable UUID employmentId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf) {
        return balances.balances(employmentId, asOf);
    }

    @GetMapping("/employments/{employmentId}/leave-ledger")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_VIEW)
    public List<LedgerRow> ledger(@PathVariable UUID employmentId, @RequestParam(required = false) UUID leaveTypeId,
            @RequestParam(required = false) Integer leaveYear) {
        return balances.ledger(employmentId, leaveTypeId, leaveYear);
    }

    /**
     * 201 with the ADJUST ledger row; 202 with {@code {outcome: APPROVAL_REQUESTED, approvalRequestId}} when the legal
     * entity requires an approval ({@code att.leave_adjust_approval}, workflow {@code att.BALANCE_ADJUST}).
     */
    @PostMapping("/employments/{employmentId}/leave-adjustments")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_ADJUST)
    public ResponseEntity<?> adjust(@PathVariable UUID employmentId, @RequestBody AdjustRequest req) {
        var o = balances.adjustOrRequest(employmentId,
                new AdjustInput(req.leaveTypeId(), req.leaveYear(), req.quantity(), req.reason()));
        if (o.row() != null) {
            return ResponseEntity.status(201).body(o.row());
        }
        return ResponseEntity.status(202).body(o);
    }

    @PostMapping("/leave-balances/accrual")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_ADJUST)
    public RunSummary accrual(@RequestBody AccrualRequest req) {
        return balances.runAccrual(req.legalEntityId(), req.month());
    }

    @PostMapping("/leave-balances/year-end")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_ADJUST)
    public RunSummary yearEnd(@RequestBody YearEndRequest req) {
        return balances.runYearEnd(req.legalEntityId(), req.year());
    }

    @PostMapping("/leave-balances/carry-expiry")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_ADJUST)
    public RunSummary carryExpiry(@RequestBody ExpiryRequest req) {
        return balances.expireCarried(req.legalEntityId(), req.asOf());
    }

    @GetMapping("/employments/{employmentId}/leave-settlement")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_VIEW)
    public Settlement settlement(@PathVariable UUID employmentId) {
        return balances.settlementInScope(employmentId);
    }

    @PostMapping("/employments/{employmentId}/leave-settlement")
    @RequiresPermission(AttendancePermissions.LEAVE_BALANCE_ADJUST)
    public Settlement finalise(@PathVariable UUID employmentId) {
        return balances.finalise(employmentId);
    }
}
