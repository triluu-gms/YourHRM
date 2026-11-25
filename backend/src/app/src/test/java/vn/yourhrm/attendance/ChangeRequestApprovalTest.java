package vn.yourhrm.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import vn.yourhrm.attendance.api.AttendanceResults.Hold;
import vn.yourhrm.attendance.api.LeaveSettlements;
import vn.yourhrm.attendance.internal.application.approval.ApprovalCatalogues;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService;
import vn.yourhrm.attendance.internal.application.leave.LeaveBalanceService.AdjustInput;
import vn.yourhrm.attendance.internal.application.leave.LeaveEntitlements;
import vn.yourhrm.attendance.internal.application.period.PeriodService;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.approval.ChangeRequestStore;
import vn.yourhrm.attendance.internal.infra.approval.ChangeRequestStore.ChangeRequest;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.NewTxn;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeData;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore;
import vn.yourhrm.attendance.internal.infra.period.AttendancePeriodStore.Period;
import vn.yourhrm.attendance.internal.infra.period.AttendanceResultStore;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartResult;

/**
 * ATT-P-11: {@code att.PERIOD_REOPEN} and {@code att.BALANCE_ADJUST} approvals. Without the setting the action runs at
 * once (unchanged behaviour); with it the request goes to Core's workflow and the action runs on the final approval,
 * the close-hold release request (BR-ATT-087) coming after the approval.
 */
class ChangeRequestApprovalTest {

    static final UUID TENANT = UUID.randomUUID();
    static final UUID HR = UUID.randomUUID();
    static final UUID REQUESTER = UUID.randomUUID();
    static final UUID LE = UUID.randomUUID();
    static final UUID PERIOD = UUID.randomUUID();
    static final UUID REQUEST = UUID.randomUUID();
    static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    AttendancePeriodStore periods;
    AttendanceResultStore results;
    ChangeRequestStore changes;
    ApprovalCatalogues approvals;
    WorkflowService workflow;
    AuthorizationService authz;
    BusinessClock clock;
    PeriodService service;

    static Period period(String status) {
        return new Period(PERIOD, LE, UUID.randomUUID(), null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                null, null, "SCHEDULE_DAYS", null, null, status, 1, null, null, null, null, null, null, null, null,
                null, null, 3);
    }

    static Hold hold() {
        return new Hold(UUID.randomUUID(), PERIOD, UUID.randomUUID(), 1, "PERIOD", null, UUID.randomUUID(), "ACTIVE",
                Instant.now(), null, null, null);
    }

    static ChangeRequest reopenRequest(String status) {
        return new ChangeRequest(REQUEST, ChangeRequestStore.PERIOD_REOPEN, LE, null, PERIOD, null, null, null, null,
                "late sick leave", status, UUID.randomUUID(), null, null, null, null, Instant.now(), REQUESTER, 0);
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        periods = mock(AttendancePeriodStore.class);
        results = mock(AttendanceResultStore.class);
        changes = mock(ChangeRequestStore.class);
        approvals = mock(ApprovalCatalogues.class);
        workflow = mock(WorkflowService.class);
        authz = mock(AuthorizationService.class);
        clock = mock(BusinessClock.class);
        when(clock.today()).thenReturn(TODAY);
        when(authz.can(anyString(), any())).thenReturn(true);
        when(authz.can(anyString(), any(), any())).thenReturn(true);
        // result lines (package-private PeriodLines) are not used by the reopen path
        service = new PeriodService(periods, results, null, mock(PunchStore.class),
                mock(AttOutbox.class), authz, mock(AuditLog.class), mock(Notifications.class), clock,
                (ObjectProvider<LeaveSettlements>) mock(ObjectProvider.class), changes, approvals, workflow);
        when(periods.forUpdate(PERIOD)).thenReturn(Optional.of(period("CLOSED")));
        when(periods.find(PERIOD)).thenReturn(Optional.of(period("CLOSED")));
    }

    static <T> T as(UUID user, java.util.concurrent.Callable<T> op) {
        try {
            return TenantContext.callWith(new TenantContext(TENANT, user, "test", null, null), op);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------- reopen

    @Test
    void withoutTheSettingAReopenRunsAtOnce() {
        var o = as(HR, () -> service.reopen(PERIOD, "late sick leave"));
        assertThat(o.outcome()).isEqualTo("REOPENED");
        assertThat(o.approvalRequestId()).isNull();
        verify(periods).reopen(PERIOD, "late sick leave", HR);
        verify(workflow, never()).start(any());
    }

    @Test
    void withTheSettingAReopenWaitsForTheWorkflow() {
        when(approvals.required(ApprovalCatalogues.SETTING_REOPEN_APPROVAL, LE)).thenReturn(true);
        when(changes.insertReopen(PERIOD, LE, "late sick leave")).thenReturn(REQUEST);
        when(workflow.start(any())).thenReturn(new StartResult(UUID.randomUUID(), "PENDING", true));

        var o = as(HR, () -> service.reopen(PERIOD, "  late sick leave "));

        assertThat(o.outcome()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(o.approvalRequestId()).isEqualTo(REQUEST);
        ArgumentCaptor<StartRequest> start = ArgumentCaptor.forClass(StartRequest.class);
        verify(workflow).start(start.capture());
        assertThat(start.getValue().processTypeCode()).isEqualTo(ApprovalCatalogues.PROCESS_PERIOD_REOPEN);
        assertThat(start.getValue().subjectType()).isEqualTo(ApprovalCatalogues.SUBJECT_CHANGE_REQUEST);
        assertThat(start.getValue().subjectId()).isEqualTo(REQUEST);
        assertThat(start.getValue().requestSummary()).containsEntry("heldByPayroll", false);
        verify(periods, never()).reopen(any(), any(), any());
        verify(periods, never()).requestReopen(any(), any(), any());
    }

    @Test
    void aSecondReopenWhileOneIsPendingReturnsThePendingRequest() {
        when(approvals.required(ApprovalCatalogues.SETTING_REOPEN_APPROVAL, LE)).thenReturn(true);
        when(changes.pendingReopen(PERIOD)).thenReturn(Optional.of(reopenRequest("PENDING")));
        var o = as(HR, () -> service.reopen(PERIOD, "again"));
        assertThat(o.outcome()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(o.approvalRequestId()).isEqualTo(REQUEST);
        verify(workflow, never()).start(any());
        verify(changes, never()).insertReopen(any(), any(), any());
    }

    @Test
    void theApprovalReopensOnBehalfOfTheRequester() {
        when(changes.find(REQUEST)).thenReturn(Optional.of(reopenRequest("PENDING")));
        Map<String, Object> r = as(HR, () -> service.applyReopenOutcome(REQUEST, "APPROVED", HR));
        assertThat(r).containsEntry("status", "APPLIED").containsEntry("outcome", "REOPENED");
        verify(periods).reopen(PERIOD, "late sick leave", REQUESTER);
        verify(changes).decide(REQUEST, "APPLIED", HR, "REOPENED", null);
    }

    @Test
    void anApprovedReopenOfAHeldPeriodFilesTheReleaseRequest() {
        when(changes.find(REQUEST)).thenReturn(Optional.of(reopenRequest("PENDING")));
        when(results.activeHold(PERIOD, null)).thenReturn(Optional.of(hold()));
        Map<String, Object> r = as(HR, () -> service.applyReopenOutcome(REQUEST, "APPROVED", HR));
        assertThat(r).containsEntry("outcome", "RELEASE_REQUESTED");
        verify(periods).requestReopen(PERIOD, "late sick leave", REQUESTER);
        verify(periods, never()).reopen(any(), any(), any());
        verify(changes).decide(REQUEST, "APPLIED", HR, "RELEASE_REQUESTED", null);
    }

    @Test
    void aRejectionOrAPeriodNoLongerClosedChangesNothing() {
        when(changes.find(REQUEST)).thenReturn(Optional.of(reopenRequest("PENDING")));
        assertThat(as(HR, () -> service.applyReopenOutcome(REQUEST, "REJECTED", HR)))
                .containsEntry("status", "REJECTED");
        verify(changes).decide(REQUEST, "REJECTED", HR, null, null);

        when(periods.forUpdate(PERIOD)).thenReturn(Optional.of(period("LOCKED")));
        assertThat(as(HR, () -> service.applyReopenOutcome(REQUEST, "APPROVED", HR)))
                .containsEntry("status", "NOT_APPLIED");
        verify(changes).decide(REQUEST, "NOT_APPLIED", HR, "period-locked", null);
        verify(periods, never()).reopen(any(), any(), any());
    }

    @Test
    void anOutcomeIsAppliedOnce() {
        when(changes.find(REQUEST)).thenReturn(Optional.of(reopenRequest("APPLIED")));
        assertThat(as(HR, () -> service.applyReopenOutcome(REQUEST, "APPROVED", HR)))
                .containsEntry("status", "APPLIED");
        verify(periods, never()).reopen(any(), any(), any());
        verify(changes, never()).decide(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------- balance adjust

    static final UUID EMP = UUID.randomUUID();
    static final UUID TYPE = UUID.randomUUID();

    LeaveStore leave;
    PunchStore punches;
    LeaveBalanceService balances;

    void leaveSetUp() {
        leave = mock(LeaveStore.class);
        punches = mock(PunchStore.class);
        balances = new LeaveBalanceService(leave, punches, mock(LeaveEntitlements.class), authz, mock(AuditLog.class),
                clock, mock(PlatformTransactionManager.class), mock(AttOutbox.class), mock(ObjectProvider.class),
                changes, approvals, workflow);
        TypeData data = new TypeData("ANNUAL", "Phép năm", "Annual", "ANNUAL", "EMPLOYER", "HALF_DAY", "WORKING_DAY",
                true, false, false, null, null, true, "P", "PAID_LEAVE_DAYS", Map.of(), true, "ACTIVE");
        when(leave.type(TYPE)).thenReturn(Optional.of(new TypeRow(TYPE, data, 0)));
        when(punches.placement(EMP, TODAY)).thenReturn(Optional.of(new PunchStore.Placement(LE, null, null)));
        when(leave.employment(EMP, TODAY)).thenReturn(Optional.of(new EmploymentFacts(EMP, UUID.randomUUID(), LE,
                LocalDate.of(2020, 1, 1), null, null, null, null, "ACTIVE", null)));
        when(leave.ledger(EMP, TYPE, 2026)).thenReturn(List.of(new LedgerRow(UUID.randomUUID(), EMP, TYPE, "ANNUAL",
                2026, "ADJUST", new BigDecimal("1.5"), "DAY", TODAY, null, null, null, null, null, "fix", "ADMIN", null,
                Instant.now())));
    }

    @Test
    void withoutTheSettingAnAdjustmentIsWrittenAtOnce() {
        leaveSetUp();
        var o = as(HR, () -> balances.adjustOrRequest(EMP, new AdjustInput(TYPE, 2026, new BigDecimal("1.5"), "fix")));
        assertThat(o.outcome()).isEqualTo("ADJUSTED");
        assertThat(o.row()).isNotNull();
        verify(leave).insertTxn(any(NewTxn.class));
        verify(workflow, never()).start(any());
    }

    @Test
    void withTheSettingAnAdjustmentIsWrittenOnApprovalOnly() {
        leaveSetUp();
        when(approvals.required(ApprovalCatalogues.SETTING_ADJUST_APPROVAL, LE)).thenReturn(true);
        when(changes.insertAdjust(eq(EMP), eq(LE), any(), eq(TYPE), eq(2026), any(), eq("fix"))).thenReturn(REQUEST);
        when(workflow.start(any())).thenReturn(new StartResult(UUID.randomUUID(), "PENDING", true));

        var o = as(HR, () -> balances.adjustOrRequest(EMP, new AdjustInput(TYPE, 2026, new BigDecimal("1.5"), "fix")));
        assertThat(o.outcome()).isEqualTo("APPROVAL_REQUESTED");
        assertThat(o.approvalRequestId()).isEqualTo(REQUEST);
        verify(leave, never()).insertTxn(any());
        ArgumentCaptor<StartRequest> start = ArgumentCaptor.forClass(StartRequest.class);
        verify(workflow).start(start.capture());
        assertThat(start.getValue().processTypeCode()).isEqualTo(ApprovalCatalogues.PROCESS_BALANCE_ADJUST);

        when(changes.find(REQUEST)).thenReturn(Optional.of(new ChangeRequest(REQUEST,
                ChangeRequestStore.BALANCE_ADJUST, LE, null, null, EMP, TYPE, 2026, new BigDecimal("1.5"), "fix",
                "PENDING", null, null, null, null, null, Instant.now(), REQUESTER, 0)));
        Map<String, Object> r = as(HR, () -> balances.applyAdjustOutcome(REQUEST, "APPROVED", HR));
        assertThat(r).containsEntry("status", "APPLIED");
        ArgumentCaptor<NewTxn> txn = ArgumentCaptor.forClass(NewTxn.class);
        verify(leave).insertTxn(txn.capture());
        assertThat(txn.getValue().txnType()).isEqualTo("ADJUST");
        assertThat(txn.getValue().quantity()).isEqualByComparingTo("1.5");
        assertThat(txn.getValue().reason()).isEqualTo("fix");
        verify(changes).decide(eq(REQUEST), eq("APPLIED"), eq(HR), eq("ADJUSTED"), any());
    }

    @Test
    void aRejectedAdjustmentWritesNothing() {
        leaveSetUp();
        when(changes.find(REQUEST)).thenReturn(Optional.of(new ChangeRequest(REQUEST,
                ChangeRequestStore.BALANCE_ADJUST, LE, null, null, EMP, TYPE, 2026, new BigDecimal("-2"), "fix",
                "PENDING", null, null, null, null, null, Instant.now(), REQUESTER, 0)));
        assertThat(as(HR, () -> balances.applyAdjustOutcome(REQUEST, "REJECTED", HR)))
                .containsEntry("status", "REJECTED");
        verify(leave, never()).insertTxn(any());
    }

    // ------------------------------------------------------------------------------------------ catalogue

    @Test
    void bothProcessTypesAndSettingsAreRegisteredWithApprovalOffByDefault() {
        ApprovalCatalogues c = new ApprovalCatalogues(mock(vn.yourhrm.core.api.Settings.class), clock);
        assertThat(c.processTypes()).extracting(d -> d.code())
                .containsExactly("att.PERIOD_REOPEN", "att.BALANCE_ADJUST");
        assertThat(c.processTypes()).allSatisfy(d -> assertThat(d.defaultSteps()).isNotEmpty());
        assertThat(c.settings()).allSatisfy(s -> assertThat(s.defaultValue()).isEqualTo(false));
        assertThat(c.required(ApprovalCatalogues.SETTING_REOPEN_APPROVAL, LE)).isFalse();
    }
}
