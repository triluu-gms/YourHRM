package vn.yourhrm.attendance.internal.application.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.attendance.api.AttendancePermissions;
import vn.yourhrm.attendance.internal.application.report.EmployeeNames.Name;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures;
import vn.yourhrm.attendance.internal.domain.report.ReportFigures.LeaveMovements;
import vn.yourhrm.attendance.internal.infra.AttTransactional;
import vn.yourhrm.attendance.internal.infra.report.ReportStore;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.BalanceSums;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.SiRow;
import vn.yourhrm.attendance.internal.infra.report.ReportStore.TakenRow;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.core.api.AuditLog.BusinessAction;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.ScopePredicate;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Leave reports of BRD 03 §8: R-06 the leave balance as of a date (opening, carried, accrued, taken, adjusted,
 * expired, paid out, closing) folded from {@code att.leave_balance_ledger} (balance = ledger sum, BR-ATT-068) with its
 * Excel export; R-07 the approved leave taken per month × org unit × type, and the register of SI-paid leave (sổ
 * theo dõi nghỉ hưởng BHXH) with the claim data recorded on the requests. The register shows P3 identifiers only as
 * their last 4 characters (nothing is decrypted), needs {@code att.leave_request.view_si_data} and is audited.
 */
@Service
public class LeaveReports {

    static final int MAX_RANGE_DAYS = 366;
    static final int MAX_EXPORT_ROWS = 20000;

    public record BalanceRow(UUID employmentId, String employeeNo, String fullName, UUID legalEntityId,
            UUID orgUnitId, UUID leaveTypeId, String leaveTypeCode, String leaveTypeName, int leaveYear,
            LeaveMovements balance) {
    }

    public record BalanceReport(LocalDate asOf, ReportPage<BalanceRow> page) {
    }

    public record TakenLine(LocalDate month, UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId,
            String leaveTypeCode, String leaveTypeName, String leaveCategory, String paidBy, long requests,
            long employees, BigDecimal days, BigDecimal hours) {
    }

    public record SiLine(UUID requestId, UUID employmentId, String employeeNo, String fullName, UUID legalEntityId,
            UUID orgUnitId, String leaveTypeCode, String leaveTypeName, String leaveCategory, LocalDate startDate,
            LocalDate endDate, LocalDate actualReturnDate, BigDecimal durationDays, String certificateType,
            String certificateNoMasked, LocalDate certificateDate, String treatmentType, Boolean longTermIllness,
            LocalDate childBirthDate, String childIdMasked, LocalDate confinementDate, Integer childrenBornCount,
            Boolean caesarean, boolean claimDataRecorded) {
    }

    private final ReportStore store;
    private final EmployeeNames names;
    private final AuthorizationService authz;
    private final AuditLog audit;
    private final BusinessClock clock;

    public LeaveReports(ReportStore store, EmployeeNames names, AuthorizationService authz, AuditLog audit,
            BusinessClock clock) {
        this.store = store;
        this.names = names;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- R-06 balance

    @AttTransactional(readOnly = true)
    public BalanceReport balances(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, UUID employmentId,
            LocalDate asOf, String lang, Integer limit, Integer offset) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.LEAVE_BALANCE_VIEW, clock.today());
        List<BalanceSums> rows = store.leaveBalances(legalEntityId, orgUnitId, leaveTypeId, employmentId, d, scope,
                lim + 1, off);
        ReportPage<BalanceSums> page = ReportPage.of(rows, lim, off);
        return new BalanceReport(d, new ReportPage<>(balanceRows(page.items(), d, l), lim, off, page.hasMore()));
    }

    @AttTransactional(readOnly = true)
    public ReportExport balancesExcel(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, LocalDate asOf, String lang,
            String unitLabel) {
        LocalDate d = asOf == null ? clock.today() : asOf;
        String l = ReportLabels.lang(lang);
        ScopePredicate scope = authz.scopeFor(AttendancePermissions.LEAVE_BALANCE_VIEW, clock.today());
        List<BalanceRow> rows = new ArrayList<>();
        int chunk = 1000;
        for (int off = 0; ; off += chunk) {
            List<BalanceSums> page = store.leaveBalances(legalEntityId, orgUnitId, leaveTypeId, null, d, scope, chunk,
                    off);
            rows.addAll(balanceRows(page, d, l));
            if (page.size() < chunk) {
                break;
            }
            if (rows.size() >= MAX_EXPORT_ROWS) {
                throw ApiException.unprocessable("export-too-large", "More than " + MAX_EXPORT_ROWS
                        + " balances: export per legal entity, org unit or leave type");
            }
        }
        List<String> keys = List.of("opening", "carriedIn", "accrued", "taken", "adjusted", "carriedOut", "expired",
                "encashed", "closing");
        List<String> h = new ArrayList<>(List.of(ReportLabels.text("no", l), ReportLabels.text("employeeNo", l),
                ReportLabels.text("fullName", l), ReportLabels.text("leaveType", l), ReportLabels.text("leaveYear",
                        l)));
        keys.forEach(k -> h.add(ReportLabels.text(k, l)));
        List<List<Object>> data = new ArrayList<>();
        int no = 1;
        for (BalanceRow r : rows) {
            LeaveMovements b = r.balance();
            List<Object> v = new ArrayList<>();
            v.add(no++);
            v.add(r.employeeNo() == null ? "" : r.employeeNo());
            v.add(r.fullName() == null ? r.employmentId().toString() : r.fullName());
            v.add(r.leaveTypeName());
            v.add(r.leaveYear());
            v.addAll(List.of(b.opening(), b.carriedIn(), b.accrued(), b.taken(), b.adjusted(), b.carriedOut(),
                    b.expired(), b.encashed(), b.closing()));
            data.add(v);
        }
        String unit = ReportLabels.text("unit", l) + ": "
                + (unitLabel == null || unitLabel.isBlank() ? "...................................." : unitLabel);
        byte[] body = ReportWorkbook.write(new ReportWorkbook.Sheet(ReportLabels.text("R06", l),
                List.of(ReportLabels.text("R06", l), ReportLabels.text("asOf", l) + " " + ReportWorkbook.date(d),
                        unit),
                List.of(h), data, null, List.of(), TimesheetReports.signatures(l), TimesheetReports.hints(l),
                ReportLabels.text("dateLine", l), 0, 0));
        audit.record(new BusinessAction("EXPORT", "att", "leave_balance_report", null, legalEntityId,
                Map.of("asOf", d.toString(), "rows", String.valueOf(rows.size()))));
        return new ReportExport("so-du-phep_" + d + ".xlsx", ReportWorkbook.MEDIA_TYPE, body);
    }

    private List<BalanceRow> balanceRows(List<BalanceSums> sums, LocalDate asOf, String lang) {
        Map<UUID, Name> who = names.of(sums.stream().map(BalanceSums::employmentId).distinct().toList(), asOf);
        return sums.stream().map(s -> {
            Name n = who.getOrDefault(s.employmentId(), Name.NONE);
            return new BalanceRow(s.employmentId(), n.employeeNo(), n.fullName(), s.legalEntityId(), s.orgUnitId(),
                    s.leaveTypeId(), s.leaveTypeCode(), ReportLabels.pick(s.leaveTypeNameVi(), s.leaveTypeNameEn(),
                            lang), s.leaveYear(), ReportFigures.leaveMovements(s.sums(), s.carryIn(), s.carryOut()));
        }).toList();
    }

    // ------------------------------------------------------------------------------------------- R-07 taken

    /** Approved leave by month of its start date (a request is counted once, in the month it starts). */
    @AttTransactional(readOnly = true)
    public ReportPage<TakenLine> taken(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, LocalDate from,
            LocalDate to, String lang, Integer limit, Integer offset) {
        ReportSupport.range(from, to, MAX_RANGE_DAYS);
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        List<TakenRow> rows = store.leaveTaken(legalEntityId, orgUnitId, leaveTypeId, from, to,
                authz.scopeFor(AttendancePermissions.LEAVE_REQUEST_VIEW, clock.today()), lim + 1, off);
        return ReportPage.of(rows, lim, off).map(r -> new TakenLine(r.month(), r.legalEntityId(), r.orgUnitId(),
                r.leaveTypeId(), r.leaveTypeCode(), ReportLabels.pick(r.leaveTypeNameVi(), r.leaveTypeNameEn(), l),
                r.leaveCategory(), r.paidBy(), r.requests(), r.employees(), r.days(), r.hours()));
    }

    /** The SI-paid leave register; audited read of SI claim data (BR-ATT-063, ND13/2023). */
    @AttTransactional(readOnly = true)
    public ReportPage<SiLine> siRegister(UUID legalEntityId, UUID orgUnitId, UUID leaveTypeId, LocalDate from,
            LocalDate to, String lang, Integer limit, Integer offset) {
        ReportSupport.range(from, to, MAX_RANGE_DAYS);
        String l = ReportLabels.lang(lang);
        int lim = ReportPage.limit(limit);
        int off = ReportPage.offset(offset);
        List<SiRow> rows = store.siRegister(legalEntityId, orgUnitId, leaveTypeId, from, to,
                authz.scopeFor(AttendancePermissions.LEAVE_SI_DATA_VIEW, clock.today()), lim + 1, off);
        ReportPage<SiRow> page = ReportPage.of(rows, lim, off);
        Map<UUID, Name> who = names.of(page.items().stream().map(SiRow::employmentId).distinct().toList(), to);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("report", "R-07-SI");
        details.put("from", from.toString());
        details.put("to", to.toString());
        details.put("rows", String.valueOf(page.items().size()));
        audit.record(new BusinessAction("VIEW", "att", "leave_request_si_register", null, legalEntityId, details));
        return page.map(r -> {
            Name n = who.getOrDefault(r.employmentId(), Name.NONE);
            return new SiLine(r.requestId(), r.employmentId(), n.employeeNo(), n.fullName(), r.legalEntityId(),
                    r.orgUnitId(), r.leaveTypeCode(), ReportLabels.pick(r.leaveTypeNameVi(), r.leaveTypeNameEn(), l),
                    r.leaveCategory(), r.startDate(), r.endDate(), r.actualReturnDate(), r.durationDays(),
                    r.certificateType(), masked(r.certificateNoLast4()), r.certificateDate(), r.treatmentType(),
                    r.longTermIllness(), r.childBirthDate(), masked(r.childIdLast4()), r.confinementDate(),
                    r.childrenBornCount(), r.caesarean(), r.claimDataRecorded());
        });
    }

    static String masked(String last4) {
        return last4 == null || last4.isBlank() ? null : "••••" + last4;
    }
}
