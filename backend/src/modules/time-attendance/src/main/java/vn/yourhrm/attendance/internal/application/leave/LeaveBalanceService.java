package vn.yourhrm.attendance.internal.application.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.api.LeaveEncashmentFlagged;
import vn.yourhrm.attendance.api.LeaveSettlements;
import vn.yourhrm.attendance.internal.application.timesheet.LockedDayChangeSink;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules;
import vn.yourhrm.attendance.internal.domain.leave.LeaveRules.YearEndSplit;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.attendance.internal.infra.AttOutbox;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.EmploymentFacts;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.LedgerRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.NewTxn;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyData;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.PolicyRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.RequestRow;
import vn.yourhrm.attendance.internal.infra.leave.LeaveStore.TypeRow;
import vn.yourhrm.attendance.internal.infra.punch.PunchStore;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Leave balances (BR-ATT-058, 060, 067, 068). The balance of an employment × type × leave year is the sum of its ledger
 * rows, never a stored number; PENDING requests reserve days on top (BR-ATT-062). Every movement is a ledger row:
 * <ul>
 *   <li><b>accrual</b> (monthly, or granted up front for the year / prorated), with the ND145 half-month rule and the
 *       seniority steps; idempotent per employment × type × month;</li>
 *   <li><b>year end</b>: carry over up to the policy's maximum with an expiry date; the remainder expires, is encashed
 *       or is flagged by policy; carried days unused at their expiry follow the same policy action (taken days
 *       consume carried days first). Encashed and flagged days are handed to Payroll (ATT-P-06):
 *       {@code att.LeaveEncashmentFlagged}, and encashed days as LEAVE_ENCASH_DAYS of the Attendance Result;</li>
 *   <li><b>adjustments</b> by HR with a mandatory reason;</li>
 *   <li><b>termination settlement</b>: the year's entitlement prorated to the last working day, the days still owed
 *       (encashed) or taken in advance (to recover), published to Payroll / People through {@link LeaveSettlements}.</li>
 * </ul>
 * Runs process every employment in its own transaction, so one failure does not stop the run. The scheduler that
 * triggers them monthly / yearly is SaaS's (not built yet): they are endpoints for now.
 */
@Service
public class LeaveBalanceService implements LeaveSettlements {

    private static final Logger log = LoggerFactory.getLogger(LeaveBalanceService.class);

    public record BalanceView(UUID leaveTypeId, String leaveTypeCode, int leaveYear, BigDecimal opening,
            BigDecimal accrued, BigDecimal carried, BigDecimal taken, BigDecimal adjusted, BigDecimal expired,
            BigDecimal encashed, BigDecimal balance, BigDecimal pending, BigDecimal available, LocalDate yearStart,
            LocalDate yearEnd) {
    }

    public record AdjustInput(UUID leaveTypeId, Integer leaveYear, BigDecimal quantity, String reason) {
    }

    public record RunSummary(String run, int employments, int written, int skipped, List<String> flagged,
            Map<UUID, String> failures) {
    }

    private final LeaveStore store;
    private final PunchStore punches;
    private final LeaveEntitlements entitlements;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TransactionTemplate perEmployment;
    private final AttOutbox outbox;
    private final ObjectProvider<LockedDayChangeSink> closedDaySinks;

    public LeaveBalanceService(LeaveStore store, PunchStore punches, LeaveEntitlements entitlements,
            AuthorizationService authz, AuditLog audit, BusinessClock clock,
            @Qualifier(AttDb.TX) PlatformTransactionManager attTm, AttOutbox outbox,
            ObjectProvider<LockedDayChangeSink> closedDaySinks) {
        this.outbox = outbox;
        this.closedDaySinks = closedDaySinks;
        this.store = store;
        this.punches = punches;
        this.entitlements = entitlements;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
        this.perEmployment = new TransactionTemplate(attTm);
        this.perEmployment.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------- balances

    /** Balance of the leave year minus the days PENDING requests of the type reserve in it (except one request). */
    public BigDecimal available(UUID employmentId, UUID leaveTypeId, int leaveYear, int startMonth, UUID exceptId) {
        return store.balance(employmentId, leaveTypeId, leaveYear).subtract(pending(employmentId, leaveTypeId,
                leaveYear, startMonth, exceptId));
    }

    private BigDecimal pending(UUID employmentId, UUID leaveTypeId, int leaveYear, int startMonth, UUID exceptId) {
        return store.pending(employmentId, leaveTypeId, exceptId).stream()
                .filter(r -> LeaveRules.leaveYearOf(r.startDate(), startMonth) == leaveYear)
                .map(RequestRow::durationDays).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Balances of the balance-tracked types of the employment for the leave year containing {@code asOf}. */
    @AttTransactional(readOnly = true)
    public List<BalanceView> balances(UUID employmentId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        requireEmployee(AttendancePermissions.LEAVE_BALANCE_VIEW, employmentId, d);
        return balancesOf(employmentId, d);
    }

    /**
     * {@link #balances} without the permission check: for callers that checked access themselves (ESS: the caller's
     * own employment, BR-ATT-080).
     */
    @AttTransactional(readOnly = true)
    public List<BalanceView> balancesOf(UUID employmentId, LocalDate asOf) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        EmploymentFacts facts = store.employment(employmentId, d)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        List<BalanceView> out = new ArrayList<>();
        for (TypeRow t : store.types()) {
            if (!t.data().tracksBalance() || !"ACTIVE".equals(t.data().status())) {
                continue;
            }
            int sm = startMonth(t, facts, d);
            int year = LeaveRules.leaveYearOf(d, sm);
            out.add(view(employmentId, t, year, sm));
        }
        return out;
    }

    private BalanceView view(UUID employmentId, TypeRow t, int year, int sm) {
        Map<String, BigDecimal> s = store.sums(employmentId, t.id(), year);
        BigDecimal balance = s.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = pending(employmentId, t.id(), year, sm, null);
        return new BalanceView(t.id(), t.data().code(), year, z(s.get("OPENING")), z(s.get("ACCRUE")),
                z(s.get("CARRY")), z(s.get("TAKE")).add(z(s.get("CANCEL"))).negate(), z(s.get("ADJUST")),
                z(s.get("EXPIRE")).negate(), z(s.get("ENCASH")).negate(), balance, pending,
                balance.subtract(pending), LeaveRules.yearStart(year, sm), LeaveRules.yearEnd(year, sm));
    }

    @AttTransactional(readOnly = true)
    public List<LedgerRow> ledger(UUID employmentId, UUID leaveTypeId, Integer leaveYear) {
        requireEmployee(AttendancePermissions.LEAVE_BALANCE_VIEW, employmentId, clock.today());
        return store.ledger(employmentId, leaveTypeId, leaveYear);
    }

    /** A manual movement with a mandatory reason (BR-ATT-068). */
    @AttTransactional
    public LedgerRow adjust(UUID employmentId, AdjustInput in) {
        String reason = LeaveInputs.text(in.reason());
        if (reason == null) {
            throw invalid("reason-required", "A reason is required for a balance adjustment (BR-ATT-068)");
        }
        if (in.quantity() == null || in.quantity().signum() == 0 || in.quantity().scale() > 2
                && in.quantity().stripTrailingZeros().scale() > 2) {
            throw invalid("invalid-quantity", "The quantity is a non-zero number of days (2 decimals)");
        }
        TypeRow t = store.type(in.leaveTypeId()).filter(x -> x.data().tracksBalance())
                .orElseThrow(() -> invalid("unknown-leave-type", "The leave type does not track a balance"));
        LocalDate today = clock.today();
        var where = requireEmployee(AttendancePermissions.LEAVE_BALANCE_ADJUST, employmentId, today);
        EmploymentFacts facts = store.employment(employmentId, today)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        int year = in.leaveYear() != null ? in.leaveYear() : LeaveRules.leaveYearOf(today, startMonth(t, facts, today));
        store.insertTxn(new NewTxn(employmentId, where.legalEntityId(), where.orgUnitId(), t.id(), t.data().code(),
                year, "ADJUST", in.quantity(), today, null, null, null, "ADMIN", null, reason, null, "APP"));
        audit.record(new BusinessAction("ADJUST", "att", "leave_balance", employmentId, where.legalEntityId(),
                Map.of("leaveType", t.data().code(), "leaveYear", String.valueOf(year), "quantity",
                        in.quantity().toPlainString())));
        List<LedgerRow> rows = store.ledger(employmentId, t.id(), year);
        return rows.getLast();
    }

    // ---------------------------------------------------------------------------------------------- accrual

    /**
     * Accrual of {@code month} (BR-ATT-058): MONTHLY policies accrue the month when it earns leave (the month must be
     * over); ANNUAL_FRONT / PRORATA grant the year's (prorated) entitlement in the first month of the leave year or
     * of the employment. Idempotent per employment × type × month.
     */
    public RunSummary runAccrual(UUID legalEntityId, YearMonth month) {
        requireRunScope(legalEntityId);
        return accrualRun(legalEntityId, month);
    }

    /**
     * {@link #runAccrual} for every legal entity of the current tenant, without a user permission check: the T&amp;A
     * nightly job (system actor) only.
     */
    public RunSummary systemAccrual(YearMonth month) {
        return accrualRun(null, month);
    }

    private RunSummary accrualRun(UUID legalEntityId, YearMonth month) {
        if (month == null || month.isAfter(YearMonth.from(clock.today()))) {
            throw invalid("invalid-month", "Give a month that has started");
        }
        boolean monthOver = month.atEndOfMonth().isBefore(clock.today());
        List<UUID> emps = perEmployment.execute(s -> store.employments(legalEntityId, month.atDay(1),
                month.atEndOfMonth()));
        int[] written = {0};
        int[] skipped = {0};
        Map<UUID, String> failures = new LinkedHashMap<>();
        for (UUID e : emps) {
            each(e, failures, id -> {
                for (TypeRow t : store.types()) {
                    if (!t.data().tracksBalance() || !"ACTIVE".equals(t.data().status())) {
                        continue;
                    }
                    if (accrue(id, t, month, monthOver)) {
                        written[0]++;
                    } else {
                        skipped[0]++;
                    }
                }
            });
        }
        return new RunSummary("ACCRUAL " + month, emps.size(), written[0], skipped[0], List.of(), failures);
    }

    private boolean accrue(UUID employmentId, TypeRow t, YearMonth month, boolean monthOver) {
        LocalDate monthEnd = month.atEndOfMonth();
        EmploymentFacts f = store.employment(employmentId, monthEnd).orElse(null);
        if (f == null) {
            return false;
        }
        LocalDate ref = f.lastDay() != null && f.lastDay().isBefore(monthEnd) ? f.lastDay() : monthEnd;
        PolicyData p = policy(t, f, ref).orElse(null);
        if (p == null || "NONE".equals(p.accrualMethod())) {
            return false;
        }
        int sm = p.leaveYearStartMonth();
        int year = LeaveRules.leaveYearOf(month.atDay(1), sm);
        LocalDate ys = LeaveRules.yearStart(year, sm);
        LocalDate ye = LeaveRules.yearEnd(year, sm);
        Optional<LedgerRow> opening = store.opening(employmentId, t.id(), year);
        LocalDate countFrom = opening.map(o -> o.txnDate().withDayOfMonth(1)).filter(d -> d.isAfter(ys)).orElse(ys);
        if (month.atDay(1).isBefore(countFrom)) {
            return false;
        }
        BigDecimal amount;
        if ("MONTHLY".equals(p.accrualMethod())) {
            if (!monthOver || !entitlements.counts(f, p.prorationRule(), ys, month)) {
                return false;
            }
            int k = entitlements.countedMonths(f, p.prorationRule(), ys, countFrom, monthEnd);
            amount = LeaveRules.monthlyStep(entitlements.annual(t, p, f, ref), k);
        } else {
            // up-front grant: in the first month of the leave year, or of the employment within it
            LocalDate firstEmployed = f.hireDate().isAfter(countFrom) ? f.hireDate() : countFrom;
            if (!YearMonth.from(firstEmployed).equals(month) || opening.isPresent()) {
                return false;
            }
            LocalDate until = "PRORATA".equals(p.accrualMethod()) && f.lastDay() != null && f.lastDay().isBefore(ye)
                    ? f.lastDay() : ye;
            int months = entitlements.countedMonths(f, p.prorationRule(), ys, firstEmployed, until);
            amount = LeaveRules.share(entitlements.annual(t, p, f, firstEmployed), months);
        }
        if (amount.signum() <= 0) {
            return false;
        }
        var where = punches.placement(employmentId, ref).orElse(null);
        return store.insertTxn(new NewTxn(employmentId, f.legalEntityId(), where == null ? null : where.orgUnitId(),
                t.id(), t.data().code(), year, "ACCRUE", amount, ref, month.atDay(1), null, null, "ACCRUAL_RUN", null,
                null, null, "SYSTEM"));
    }

    // --------------------------------------------------------------------------------------------- year end

    /**
     * Year end of leave year {@code year} (BR-ATT-060): per employment and balance-tracked type, the balance is carried
     * to the next year up to the policy's maximum (with its expiry date; a negative balance is carried as debt) and the
     * remainder expires, is encashed or is flagged. The leave year must be over; idempotent.
     */
    public RunSummary runYearEnd(UUID legalEntityId, int year) {
        requireRunScope(legalEntityId);
        return yearEndRun(legalEntityId, year);
    }

    /** {@link #runYearEnd} for the whole tenant without a permission check (nightly job only). */
    public RunSummary systemYearEnd(int year) {
        return yearEndRun(null, year);
    }

    private RunSummary yearEndRun(UUID legalEntityId, int year) {
        LocalDate today = clock.today();
        int written = 0;
        int skipped = 0;
        int employments = 0;
        List<String> flagged = new ArrayList<>();
        Map<UUID, String> failures = new LinkedHashMap<>();
        List<TypeRow> types = perEmployment.execute(s -> store.types());
        for (TypeRow t : types) {
            if (!t.data().tracksBalance()) {
                continue;
            }
            List<UUID> emps = perEmployment.execute(s -> store.employmentsWithLedger(t.id(), year, legalEntityId));
            employments += emps.size();
            for (UUID e : emps) {
                int[] w = {0};
                each(e, failures, id -> w[0] += yearEnd(id, t, year, today, flagged));
                if (w[0] > 0) {
                    written += w[0];
                } else {
                    skipped++;
                }
            }
        }
        return new RunSummary("YEAR_END " + year, employments, written, skipped, flagged, failures);
    }

    private int yearEnd(UUID employmentId, TypeRow t, int year, LocalDate today, List<String> flagged) {
        EmploymentFacts f = store.employment(employmentId, today).orElse(null);
        if (f == null) {
            return 0;
        }
        int sm0 = startMonth(t, f, LeaveRules.yearEnd(year, 1));
        LocalDate ye = LeaveRules.yearEnd(year, sm0);
        PolicyData p = policy(t, f, ye).orElse(null);
        int sm = p == null ? 1 : p.leaveYearStartMonth();
        ye = LeaveRules.yearEnd(year, sm);
        if (!ye.isBefore(today) || store.yearEndDone(employmentId, t.id(), year)) {
            return 0;
        }
        BigDecimal balance = store.balance(employmentId, t.id(), year);
        YearEndSplit split = LeaveRules.yearEnd(balance, p == null ? null : p.carryOverMaxDays());
        LocalDate nextStart = LeaveRules.yearStart(year + 1, sm);
        int n = 0;
        if (split.carried().signum() != 0) {
            LocalDate expires = split.carried().signum() > 0 && p != null && p.carryOverExpiryMonths() != null
                    && p.carryOverExpiryMonths() > 0 ? nextStart.plusMonths(p.carryOverExpiryMonths()) : null;
            store.insertTxn(txn(f, t, year, "CARRY", split.carried().negate(), ye, null, "CARRY_RUN", null,
                    "Carried to " + (year + 1)));
            // the incoming row has its own ref type: CARRY_RUN marks the year whose end was processed
            store.insertTxn(txn(f, t, year + 1, "CARRY", split.carried(), nextStart, expires, "CARRY_IN", null,
                    "Carried from " + year));
            n += 2;
        }
        if (split.remainder().signum() > 0) {
            String action = p == null ? "FORFEIT" : p.expiryAction();
            switch (action) {
                case "ENCASH" -> {
                    n += handOver(f, t, year, "YEAR_END", "ENCASH", split.remainder(), ye, "CARRY_RUN", null,
                            "Unused days above the carry-over maximum, paid out (ATT-P-06)");
                }
                case "FLAG" -> {
                    n += handOver(f, t, year, "YEAR_END", "FLAG", split.remainder(), ye, "CARRY_RUN", null,
                            "Unused days above the carry-over maximum, flagged for payout (ATT-P-06)");
                    flagged.add(employmentId + " " + t.data().code() + " " + year + ": "
                            + split.remainder() + " days above the carry-over maximum");
                }
                default -> {
                    store.insertTxn(txn(f, t, year, "EXPIRE", split.remainder().negate(), ye, null, "CARRY_RUN", null,
                            "Unused days above the carry-over maximum, forfeited"));
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Expires carried days still unused at their expiry date (BR-ATT-060), up to {@code asOf}: days taken in the new
     * year before the expiry consume the carried days first. Idempotent per carry row.
     */
    public RunSummary expireCarried(UUID legalEntityId, LocalDate asOf) {
        requireRunScope(legalEntityId);
        return expiryRun(legalEntityId, asOf);
    }

    /** {@link #expireCarried} for the whole tenant without a permission check (nightly job only). */
    public RunSummary systemExpireCarried(LocalDate asOf) {
        return expiryRun(null, asOf);
    }

    private RunSummary expiryRun(UUID legalEntityId, LocalDate asOf) {
        LocalDate d = asOf == null || asOf.isAfter(clock.today()) ? clock.today() : asOf;
        List<LedgerRow> rows = perEmployment.execute(s -> store.carriesToExpire(d, legalEntityId));
        int[] written = {0};
        List<String> flagged = new ArrayList<>();
        Map<UUID, String> failures = new LinkedHashMap<>();
        for (LedgerRow c : rows) {
            each(c.employmentId(), failures, id -> {
                BigDecimal taken = store.takenBefore(id, c.leaveTypeId(), c.leaveYear(), c.expiresOn());
                BigDecimal rest = LeaveRules.carriedToExpire(c.quantity(), taken);
                if (rest.signum() > 0) {
                    EmploymentFacts f = store.employment(id, c.expiresOn()).orElseThrow();
                    TypeRow t = store.type(c.leaveTypeId()).orElseThrow();
                    // the expiry action of the policy in force at the expiry (ATT-P-06), FORFEIT without a policy
                    String action = policy(t, f, c.expiresOn()).map(PolicyData::expiryAction).orElse("FORFEIT");
                    String why = "Carried days not taken by " + c.expiresOn();
                    switch (action) {
                        case "ENCASH" -> written[0] += handOver(f, t, c.leaveYear(), "CARRY_EXPIRY", "ENCASH", rest,
                                c.expiresOn(), "CARRY_EXPIRY", c.id(), why + ", paid out (ATT-P-06)");
                        case "FLAG" -> {
                            written[0] += handOver(f, t, c.leaveYear(), "CARRY_EXPIRY", "FLAG", rest, c.expiresOn(),
                                    "CARRY_EXPIRY", c.id(), why + ", flagged for payout (ATT-P-06)");
                            flagged.add(id + " " + t.data().code() + " " + c.leaveYear() + ": " + rest
                                    + " carried days expired on " + c.expiresOn());
                        }
                        default -> {
                            if (store.insertTxn(txn(f, t, c.leaveYear(), "EXPIRE", rest.negate(), c.expiresOn(),
                                    null, "CARRY_EXPIRY", c.id(), why))) {
                                written[0]++;
                            }
                        }
                    }
                }
            });
        }
        return new RunSummary("CARRY_EXPIRY " + d, rows.size(), written[0], rows.size() - written[0], flagged,
                failures);
    }

    /**
     * Hands leftover days to Payroll (ATT-P-06): ENCASH writes the ENCASH ledger row (the days then travel as
     * LEAVE_ENCASH_DAYS in the Attendance Result of the period covering {@code date}); FLAG writes a zero-quantity
     * ADJUST marker (the days stay on the balance for a decision; the marker makes the run idempotent). Both publish
     * {@code att.LeaveEncashmentFlagged} in the same transaction. When the period covering {@code date} is already
     * CLOSED / LOCKED, an ENCASH records a retro request so the next close carries it as a delta (BR-ATT-071).
     *
     * @return the number of ledger rows written (0 when an idempotency index refused it)
     */
    private int handOver(EmploymentFacts f, TypeRow t, int year, String run, String outcome, BigDecimal days,
            LocalDate date, String refType, UUID refId, String reason) {
        boolean encash = "ENCASH".equals(outcome);
        Optional<UUID> id = store.insertTxnId(txn(f, t, year, encash ? "ENCASH" : "ADJUST",
                encash ? days.negate() : BigDecimal.ZERO, date, null, refType, refId, reason));
        if (id.isEmpty()) {
            return 0;
        }
        boolean retro = false;
        if (encash) {
            Optional<UUID> closed = store.closedPeriodOf(f.employmentId(), date);
            if (closed.isPresent()) {
                retro = true;
                closedDaySinks.orderedStream().forEach(s -> s.lockedDayChanged(f.employmentId(), closed.get(), date));
            }
        }
        outbox.publish(new LeaveEncashmentFlagged(TenantContext.current().tenantId(), f.legalEntityId(),
                f.employmentId(), t.id(), t.data().code(), year, run, outcome, days, date, id.get(), retro));
        audit.record(new BusinessAction(encash ? "LEAVE_ENCASH" : "LEAVE_FLAG", "att", "leave_balance",
                f.employmentId(), f.legalEntityId(), Map.of("leaveType", t.data().code(), "leaveYear",
                        String.valueOf(year), "run", run, "days", days.toPlainString(), "date", date.toString())));
        return 1;
    }

    // ------------------------------------------------------------------------------------------- settlement

    /** {@inheritDoc} In-process API (no permission check). */
    @Override
    @AttTransactional(readOnly = true)
    public Settlement settlement(UUID employmentId) {
        return compute(employmentId);
    }

    /** The settlement for an HR user in scope. */
    @AttTransactional(readOnly = true)
    public Settlement settlementInScope(UUID employmentId) {
        requireEmployee(AttendancePermissions.LEAVE_BALANCE_VIEW, employmentId, clock.today());
        return compute(employmentId);
    }

    /**
     * Writes the settlement (BR-ATT-067): the proration difference as an ADJUST row and the days owed as ENCASH, both
     * referring to TERMINATION; days taken in advance stay as a negative balance for Payroll to recover. Needs the last
     * working day; idempotent per leave type and year.
     */
    @AttTransactional
    public Settlement finalise(UUID employmentId) {
        LocalDate today = clock.today();
        requireEmployee(AttendancePermissions.LEAVE_BALANCE_ADJUST, employmentId, today);
        EmploymentFacts f = store.employment(employmentId, today)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (f.lastDay() == null) {
            throw ApiException.conflict("no-last-working-day", "The employment has no last working day yet");
        }
        Settlement s = compute(employmentId);
        for (Line l : s.lines()) {
            if (l.finalised()) {
                continue;
            }
            TypeRow t = store.type(l.leaveTypeId()).orElseThrow();
            BigDecimal delta = prorationDelta(f, t, l.leaveYear());
            store.insertTxn(txn(f, t, l.leaveYear(), "ADJUST", delta, f.lastDay(), null, "TERMINATION", null,
                    "Termination settlement: entitlement prorated to " + f.lastDay() + " (BR-ATT-067)"));
            if (l.remaining().signum() > 0) {
                store.insertTxn(txn(f, t, l.leaveYear(), "ENCASH", l.remaining().negate(), f.lastDay(), null,
                        "TERMINATION", null, "Unused days paid out on termination"));
            }
        }
        audit.record(new BusinessAction("SETTLE", "att", "leave_balance", employmentId, f.legalEntityId(),
                Map.of("lastWorkingDate", f.lastDay().toString())));
        return compute(employmentId);
    }

    private Settlement compute(UUID employmentId) {
        LocalDate today = clock.today();
        EmploymentFacts f = store.employment(employmentId, today)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        LocalDate last = f.lastDay() != null ? f.lastDay() : today;
        List<Line> lines = new ArrayList<>();
        for (TypeRow t : store.types()) {
            if (!t.data().tracksBalance()) {
                continue;
            }
            int sm = startMonth(t, f, last);
            int year = LeaveRules.leaveYearOf(last, sm);
            Map<String, BigDecimal> sums = store.sums(employmentId, t.id(), year);
            if (sums.isEmpty() && policy(t, f, last).map(p -> "NONE".equals(p.accrualMethod())).orElse(true)) {
                continue;
            }
            boolean finalised = store.hasRef(employmentId, t.id(), year, "TERMINATION");
            BigDecimal total = sums.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            // after finalisation the termination ENCASH row zeroed the owed days: report what was settled. Days paid
            // out earlier by a carry-expiry run (ATT-P-06) are not part of it: they went to Payroll in their period.
            BigDecimal remaining = finalised
                    ? total.subtract(store.sumOf(employmentId, t.id(), year, "ENCASH", "TERMINATION"))
                    : total.add(prorationDelta(f, t, year));
            BigDecimal taken = z(sums.get("TAKE")).add(z(sums.get("CANCEL"))).negate();
            lines.add(new Line(t.id(), t.data().code(), year, remaining.add(taken), taken, remaining, finalised));
        }
        return new Settlement(employmentId, f.lastDay(), lines);
    }

    /** Entitlement prorated to the last day minus what was accrued in the year (0 without an accrual policy). */
    private BigDecimal prorationDelta(EmploymentFacts f, TypeRow t, int year) {
        LocalDate last = f.lastDay() != null ? f.lastDay() : clock.today();
        PolicyData p = policy(t, f, last).orElse(null);
        if (p == null || "NONE".equals(p.accrualMethod())) {
            return BigDecimal.ZERO;
        }
        LocalDate ys = LeaveRules.yearStart(year, p.leaveYearStartMonth());
        LocalDate countFrom = store.opening(f.employmentId(), t.id(), year)
                .map(o -> o.txnDate().withDayOfMonth(1)).filter(d -> d.isAfter(ys)).orElse(ys);
        LocalDate from = f.hireDate().isAfter(countFrom) ? f.hireDate() : countFrom;
        if (last.isBefore(from)) {
            return BigDecimal.ZERO;
        }
        int months = entitlements.countedMonths(f, p.prorationRule(), ys, from, last);
        BigDecimal due = LeaveRules.share(entitlements.annual(t, p, f, last), months);
        return due.subtract(z(store.sums(f.employmentId(), t.id(), year).get("ACCRUE")));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private NewTxn txn(EmploymentFacts f, TypeRow t, int year, String type, BigDecimal q, LocalDate date,
            LocalDate expires, String refType, UUID refId, String reason) {
        var where = punches.placement(f.employmentId(), date).orElse(null);
        return new NewTxn(f.employmentId(), f.legalEntityId(), where == null ? null : where.orgUnitId(), t.id(),
                t.data().code(), year, type, q, date, null, expires, null, refType, refId, reason, null, "SYSTEM");
    }

    private Optional<PolicyData> policy(TypeRow t, EmploymentFacts f, LocalDate date) {
        return store.policyFor(t.id(), f.legalEntityId(), f.employeeCategoryCode(), date).map(PolicyRow::data);
    }

    private int startMonth(TypeRow t, EmploymentFacts f, LocalDate date) {
        return policy(t, f, date).map(PolicyData::leaveYearStartMonth).orElse(1);
    }

    private void each(UUID employmentId, Map<UUID, String> failures, Consumer<UUID> work) {
        try {
            perEmployment.executeWithoutResult(s -> work.accept(employmentId));
        } catch (ApiException e) {
            failures.put(employmentId, e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Leave run for employment {} failed: {}", employmentId, e.toString());
            failures.put(employmentId, e.getClass().getSimpleName());
        }
    }

    private void requireRunScope(UUID legalEntityId) {
        boolean ok = legalEntityId != null
                ? authz.can(AttendancePermissions.LEAVE_BALANCE_ADJUST, new AuthorizationTarget.LegalEntity(legalEntityId))
                : authz.scopeFor(AttendancePermissions.LEAVE_BALANCE_ADJUST, clock.today()).tenantWide();
        if (!ok) {
            throw new ApiException(HttpStatus.FORBIDDEN, "legal-entity-not-in-scope",
                    legalEntityId == null ? "A run for every legal entity needs tenant-wide scope"
                            : "The legal entity is outside your data scope");
        }
    }

    private PunchStore.Placement requireEmployee(String permission, UUID employmentId, LocalDate date) {
        PunchStore.Placement p = punches.placement(employmentId, date)
                .orElseThrow(() -> ApiException.notFound("Employment", employmentId));
        if (!authz.can(permission, new AuthorizationTarget.Employee(employmentId, p.legalEntityId(), p.orgUnitId(),
                p.locationId()), date)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "employment-not-in-scope",
                    "Employment " + employmentId + " is outside your data scope");
        }
        return p;
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static ApiException invalid(String code, String message) {
        return ApiException.unprocessable(code, message);
    }
}
