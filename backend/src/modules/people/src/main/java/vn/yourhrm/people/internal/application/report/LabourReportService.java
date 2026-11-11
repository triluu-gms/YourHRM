package vn.yourhrm.people.internal.application.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.JobCatalogue;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.auth.api.AuthorizationTarget;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * The periodic statutory labour reports of a legal entity, computed from People's history ({@code hr.employment},
 * {@code hr.v_labour_movement}, contracts and qualifications as of the period dates):
 * <ul>
 *   <li>the 6-monthly labour change report (BR-PPL-141, US-PPL-069; Decree 145/2020 Art. 4, e.g. Form 01/PLI
 *       [verify-legal]): headcount at the start and end, hires and terminations by type, women, contract types and
 *       qualifications at the end; periods, due dates and form code from legal parameters;</li>
 *   <li>the monthly labour fluctuation notice to the Employment Service Centre (BR-PPL-142, US-PPL-070, high
 *       uncertainty: the form is unconfirmed): the hires and terminations of the month in a parameterised column
 *       layout ({@code report.labour_fluctuation.layout}).</li>
 * </ul>
 * Aggregates cover the whole legal entity, so the caller needs the permission at legal-entity level (404 otherwise).
 */
@Service
public class LabourReportService {

    /** Column keys of the fluctuation layout with their Vietnamese / English headers. */
    public static final Map<String, String[]> FLUCTUATION_COLUMNS = columns();

    public record Period(String code, LocalDate start, LocalDate end, LocalDate due) {
    }

    public record LabourChange(UUID legalEntityId, String periodCode, LocalDate periodStart, LocalDate periodEnd,
            LocalDate dueDate, String formCode, String titleVi, int headcountStart, int hires, int terminations,
            int headcountEnd, int femaleEnd, Map<String, Integer> hiresByType, Map<String, Integer> terminationsByType,
            Map<String, Integer> contractTypesEnd, Map<String, Integer> qualificationsEnd, Map<String, String> notes) {
    }

    /** One hire or termination. {@code nationalId} / {@code siNumber}: masked unless revealed in an export. */
    public record Movement(UUID employmentId, String employeeNo, String fullName, LocalDate birthDate,
            String genderCode, String nationalId, String siNumber, String jobTitle, String movementType,
            LocalDate movementDate, String contractTypeCode, String reasonCode, String legalGroundCode) {
    }

    public record Fluctuation(UUID legalEntityId, String month, LocalDate periodStart, LocalDate periodEnd,
            LocalDate dueDate, String formCode, String titleVi, List<String> columns, int hires, int terminations,
            boolean identityRevealed, List<Movement> rows) {
    }

    private final HrDb db;
    private final AuthorizationService authz;
    private final PeopleLegalParameters legal;
    private final PiiCipher cipher;
    private final SensitiveDataAccess sensitive;
    private final JobCatalogue jobs;

    public LabourReportService(HrDb db, AuthorizationService authz, PeopleLegalParameters legal, PiiCipher cipher,
            SensitiveDataAccess sensitive, JobCatalogue jobs) {
        this.db = db;
        this.authz = authz;
        this.legal = legal;
        this.cipher = cipher;
        this.sensitive = sensitive;
        this.jobs = jobs;
    }

    // ================================================================================================ periods

    /** A 6-monthly period of {@code year} ({@code code} e.g. JUNE / DECEMBER) from {@code report.labour_change_6m.periods}. */
    public Period sixMonthPeriod(UUID legalEntityId, int year, String code) {
        Map<String, Object> periods = legal.require(ReportKeys.CHANGE_6M_PERIODS, new Scope(legalEntityId, null),
                LocalDate.of(year, 1, 1)).asMap();
        if (code == null || !(periods.get(code) instanceof Map<?, ?> p)) {
            throw ApiException.unprocessable("invalid-report-period", "Unknown 6-monthly period " + code,
                    Map.of("allowed", List.copyOf(periods.keySet())));
        }
        try {
            LocalDate start = LocalDate.of(year + num(p, "start_year_offset", 0), num(p, "start_month", null), 1);
            LocalDate end = YearMonth.of(year + num(p, "end_year_offset", 0), num(p, "end_month", null)).atEndOfMonth();
            LocalDate due = LocalDate.of(year + num(p, "due_year_offset", 0), num(p, "due_month", null),
                    num(p, "due_day", null));
            return new Period(code, start, end, due);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("legal-parameter-invalid", ReportKeys.CHANGE_6M_PERIODS + " period "
                    + code + " is malformed: " + e.getMessage());
        }
    }

    /** The period codes of the 6-monthly report in the pack of {@code year} (e.g. JUNE, DECEMBER). */
    public List<String> sixMonthCodes(UUID legalEntityId, int year) {
        return legal.find(ReportKeys.CHANGE_6M_PERIODS, new Scope(legalEntityId, null), LocalDate.of(year, 1, 1))
                .map(v -> List.copyOf(v.asMap().keySet())).orElse(List.of());
    }

    /** The calendar month with its due date from {@code report.labour_fluctuation.due}. */
    public Period monthPeriod(UUID legalEntityId, YearMonth month) {
        Map<String, Object> due = legal.require(ReportKeys.FLUCTUATION_DUE, new Scope(legalEntityId, null),
                month.atDay(1)).asMap();
        try {
            LocalDate d = month.plusMonths(num(due, "month_offset", 1)).atDay(num(due, "day", null));
            return new Period(month.toString(), month.atDay(1), month.atEndOfMonth(), d);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("legal-parameter-invalid", ReportKeys.FLUCTUATION_DUE + " is malformed: "
                    + e.getMessage());
        }
    }

    // ================================================================================================ 6-monthly

    @HrTransactional(readOnly = true)
    public LabourChange labourChange(UUID legalEntityId, int year, String periodCode, Map<String, String> notes) {
        requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE,
                legalEntityId);
        Period p = sixMonthPeriod(legalEntityId, year, periodCode);
        Map<String, Object> form = legal.require(ReportKeys.CHANGE_6M_FORM, new Scope(legalEntityId, null), p.end())
                .asMap();
        UUID t = tenant();
        int[] counts = db.jdbc().sql("""
                        select count(*) filter (where e.hire_date < :s and (e.end_date is null or e.end_date >= :s)),
                               count(*) filter (where e.hire_date <= :e and (e.end_date is null or e.end_date > :e)),
                               count(*) filter (where e.hire_date <= :e and (e.end_date is null or e.end_date > :e)
                                                  and p.gender_code = 'F')
                          from hr.employment e join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id
                         where e.tenant_id = :t and e.legal_entity_id = :le and e.status not in ('CANCELLED','PRE_HIRE')""")
                .param("t", t).param("le", legalEntityId).param("s", p.start()).param("e", p.end())
                .query((rs, n) -> new int[] {rs.getInt(1), rs.getInt(2), rs.getInt(3)}).single();
        Map<String, Integer> hires = new TreeMap<>();
        Map<String, Integer> terminations = new TreeMap<>();
        db.jdbc().sql("""
                        select movement_type, coalesce(reason_code, 'UNSPECIFIED'), count(*) from hr.v_labour_movement
                         where tenant_id = :t and legal_entity_id = :le and movement_date between :s and :e
                         group by 1, 2""")
                .param("t", t).param("le", legalEntityId).param("s", p.start()).param("e", p.end())
                .query(rs -> {
                    ("HIRE".equals(rs.getString(1)) ? hires : terminations).put(rs.getString(2), rs.getInt(3));
                });
        Map<String, Integer> contracts = new TreeMap<>();
        Map<String, Integer> qualifications = new TreeMap<>();
        db.jdbc().sql("""
                        select coalesce(ctr.contract_type_code, 'NONE'), coalesce(q.qualification_level_code, 'NONE')
                          from hr.employment e
                          left join lateral (select c.contract_type_code from hr.contract c
                                              where c.tenant_id = e.tenant_id and c.employment_id = e.id
                                                and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                                                and c.start_date <= :e and (c.end_date is null or c.end_date >= :e)
                                              order by c.start_date desc limit 1) ctr on true
                          left join lateral (select x.qualification_level_code from hr.education_qualification x
                                              where x.tenant_id = e.tenant_id and x.person_id = e.person_id and x.is_highest
                                              limit 1) q on true
                         where e.tenant_id = :t and e.legal_entity_id = :le and e.status not in ('CANCELLED','PRE_HIRE')
                           and e.hire_date <= :e and (e.end_date is null or e.end_date > :e)""")
                .param("t", t).param("le", legalEntityId).param("e", p.end())
                .query(rs -> {
                    contracts.merge(rs.getString(1), 1, Integer::sum);
                    qualifications.merge(rs.getString(2), 1, Integer::sum);
                });
        return new LabourChange(legalEntityId, p.code(), p.start(), p.end(), p.due(), str(form.get("form_code")),
                str(form.get("title_vi")), counts[0], sum(hires), sum(terminations), counts[1], counts[2], hires,
                terminations, contracts, qualifications, notes == null ? Map.of() : Map.copyOf(notes));
    }

    byte[] labourChangeExcel(LabourChange c) {
        List<List<Object>> rows = new ArrayList<>();
        int[] i = {1};
        java.util.function.BiConsumer<String, Object[]> row = (code, v) -> {
            List<Object> r = new ArrayList<>();
            r.add(i[0]++);
            r.add(v[0]);
            r.add(v[1]);
            r.add(c.notes().getOrDefault(code, ""));
            rows.add(r);
        };
        row.accept("HEADCOUNT_START", new Object[] {"Số lao động đầu kỳ", c.headcountStart()});
        row.accept("HIRES", new Object[] {"Số lao động tăng trong kỳ", c.hires()});
        c.hiresByType().forEach((k, v) -> row.accept("HIRES_" + k, new Object[] {"  - Tăng (" + k + ")", v}));
        row.accept("TERMINATIONS", new Object[] {"Số lao động giảm trong kỳ", c.terminations()});
        c.terminationsByType().forEach((k, v) -> row.accept("TERMINATIONS_" + k,
                new Object[] {"  - Giảm (" + k + ")", v}));
        row.accept("HEADCOUNT_END", new Object[] {"Số lao động cuối kỳ", c.headcountEnd()});
        row.accept("FEMALE_END", new Object[] {"  Trong đó: lao động nữ", c.femaleEnd()});
        c.contractTypesEnd().forEach((k, v) -> row.accept("CONTRACT_" + k,
                new Object[] {"  Theo loại hợp đồng: " + k, v}));
        c.qualificationsEnd().forEach((k, v) -> row.accept("QUALIFICATION_" + k,
                new Object[] {"  Theo trình độ chuyên môn kỹ thuật: " + k, v}));
        return ExcelSheet.write("Mau " + nz(c.formCode()).replace('/', '-'), List.of(
                        nz(c.titleVi()).toUpperCase(), "Mẫu số: " + c.formCode(), "Pháp nhân: " + c.legalEntityId(),
                        "Kỳ báo cáo: " + ExcelSheet.date(c.periodStart()) + " - " + ExcelSheet.date(c.periodEnd()),
                        "Hạn nộp: " + ExcelSheet.date(c.dueDate())),
                List.of("STT", "Chỉ tiêu", "Số lượng", "Ghi chú"), rows);
    }

    /** Merge values of the Core template {@code HR_LABOUR_CHANGE_6M}. */
    Map<String, Object> labourChangeFields(LabourChange c) {
        Map<String, Object> f = new HashMap<>();
        f.put("report.form_code", nz(c.formCode()));
        f.put("report.title", nz(c.titleVi()));
        f.put("report.period", ExcelSheet.date(c.periodStart()) + " - " + ExcelSheet.date(c.periodEnd()));
        f.put("report.headcount_start", String.valueOf(c.headcountStart()));
        f.put("report.hires", String.valueOf(c.hires()));
        f.put("report.terminations", String.valueOf(c.terminations()));
        f.put("report.headcount_end", String.valueOf(c.headcountEnd()));
        f.put("report.female_end", String.valueOf(c.femaleEnd()));
        int indefinite = c.contractTypesEnd().getOrDefault("INDEFINITE", 0);
        int definite = c.contractTypesEnd().getOrDefault("DEFINITE", 0) + c.contractTypesEnd().getOrDefault("SEASONAL", 0);
        f.put("report.indefinite_end", String.valueOf(indefinite));
        f.put("report.definite_end", String.valueOf(definite));
        f.put("report.other_end", String.valueOf(c.headcountEnd() - indefinite - definite));
        f.put("report.notes", String.join("; ", c.notes().values()));
        return f;
    }

    // ================================================================================================ monthly

    @HrTransactional(readOnly = true)
    public Fluctuation fluctuation(UUID legalEntityId, YearMonth month, boolean export) {
        if (export) {
            requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_EXECUTE, null, legalEntityId);
        } else {
            requireLegalEntity(PeoplePermissions.STATUTORY_REPORT_VIEW, PeoplePermissions.STATUTORY_REPORT_EXECUTE,
                    legalEntityId);
        }
        Period p = monthPeriod(legalEntityId, month);
        Map<String, Object> layout = legal.require(ReportKeys.FLUCTUATION_LAYOUT, new Scope(legalEntityId, null),
                p.end()).asMap();
        List<String> columns = layout.get("columns") instanceof List<?> l ? l.stream().map(Object::toString).toList()
                : List.of();
        List<String> unknown = columns.stream().filter(c -> !FLUCTUATION_COLUMNS.containsKey(c)).toList();
        if (columns.isEmpty() || !unknown.isEmpty()) {
            throw ApiException.unprocessable("legal-parameter-invalid", ReportKeys.FLUCTUATION_LAYOUT
                    + " needs columns from " + FLUCTUATION_COLUMNS.keySet() + "; unknown " + unknown);
        }
        boolean reveal = export && authz.can(PeoplePermissions.PERSON_VIEW_SENSITIVE,
                new AuthorizationTarget.LegalEntity(legalEntityId));
        UUID t = tenant();
        record Raw(UUID employmentId, UUID personId, String employeeNo, String fullName, LocalDate birth, String gender,
                String idLast4, String siLast4, UUID jobTitle, String type, LocalDate date, String contract,
                String reason, String ground) {
        }
        List<Raw> raws = db.jdbc().sql("""
                        select m.employment_id, m.person_id, e.employee_no, p.full_name, p.birth_date, p.gender_code,
                               p.national_id_last4, p.si_number_last4, asg.job_title_id, m.movement_type,
                               m.movement_date, ctr.contract_type_code, m.reason_code, m.legal_ground_code
                          from hr.v_labour_movement m
                          join hr.employment e on e.tenant_id = m.tenant_id and e.id = m.employment_id
                          join hr.person p on p.tenant_id = e.tenant_id and p.id = e.person_id
                          left join lateral (select x.job_title_id from hr.assignment x
                                              where x.tenant_id = e.tenant_id and x.employment_id = e.id
                                                and x.assignment_type = 'PRIMARY' and x.superseded_at is null
                                                and x.valid_from <= m.movement_date
                                              order by x.valid_from desc limit 1) asg on true
                          left join lateral (select c.contract_type_code from hr.contract c
                                              where c.tenant_id = e.tenant_id and c.employment_id = e.id
                                                and c.status in ('ACTIVE','SUSPENDED','EXPIRED','TERMINATED')
                                                and c.start_date <= m.movement_date
                                              order by c.start_date desc limit 1) ctr on true
                         where m.tenant_id = :t and m.legal_entity_id = :le and m.movement_date between :s and :e
                         order by m.movement_date, m.movement_type, e.employee_no""")
                .param("t", t).param("le", legalEntityId).param("s", p.start()).param("e", p.end())
                .query((rs, n) -> new Raw(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10), rs.getObject(11, LocalDate.class),
                        rs.getString(12), rs.getString(13), rs.getString(14)))
                .list();
        Map<UUID, String> titles = new HashMap<>();
        List<UUID> ids = raws.stream().map(Raw::jobTitle).filter(Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            jobs.jobTitles(ids).forEach((id, jt) -> titles.put(id, jt.nameVi()));
        }
        List<Movement> rows = new ArrayList<>();
        Map<UUID, String[]> revealed = new HashMap<>();
        for (Raw r : raws) {
            String id = r.idLast4() == null ? null : "••••" + r.idLast4();
            String si = r.siLast4() == null ? null : "••••" + r.siLast4();
            if (reveal) {
                String[] clear = revealed.computeIfAbsent(r.personId(), this::identifiers);
                id = clear[0];
                si = clear[1];
            }
            rows.add(new Movement(r.employmentId(), r.employeeNo(), r.fullName(), r.birth(), r.gender(), id, si,
                    r.jobTitle() == null ? null : titles.get(r.jobTitle()), r.type(), r.date(), r.contract(),
                    r.reason(), r.ground()));
        }
        revealed.keySet().forEach(person -> sensitive.recordSensitiveRead(PeoplePermissions.PERSON_VIEW_SENSITIVE,
                "hr.person", person, legalEntityId));
        int hires = (int) rows.stream().filter(r -> "HIRE".equals(r.movementType())).count();
        return new Fluctuation(legalEntityId, month.toString(), p.start(), p.end(), p.due(), str(layout.get("form_code")),
                str(layout.get("title_vi")), columns, hires, rows.size() - hires, reveal, rows);
    }

    byte[] fluctuationExcel(Fluctuation f) {
        List<String> headers = new ArrayList<>();
        headers.add("STT");
        f.columns().forEach(c -> headers.add(FLUCTUATION_COLUMNS.get(c)[0]));
        List<List<Object>> rows = new ArrayList<>();
        int i = 1;
        for (Movement m : f.rows()) {
            List<Object> r = new ArrayList<>();
            r.add(i++);
            for (String c : f.columns()) {
                r.add(value(m, c));
            }
            rows.add(r);
        }
        return ExcelSheet.write("Bien dong lao dong", List.of(nz(f.titleVi()).toUpperCase(),
                "Mẫu số: " + nz(f.formCode()), "Pháp nhân: " + f.legalEntityId(), "Tháng: " + f.month(),
                "Hạn nộp: " + ExcelSheet.date(f.dueDate()), "Tăng: " + f.hires() + ", giảm: " + f.terminations()),
                headers, rows);
    }

    private static Object value(Movement m, String column) {
        return switch (column) {
            case "employee_no" -> m.employeeNo();
            case "full_name" -> m.fullName();
            case "birth_date" -> m.birthDate();
            case "gender" -> m.genderCode();
            case "national_id" -> m.nationalId();
            case "si_number" -> m.siNumber();
            case "job_title" -> m.jobTitle();
            case "movement_type" -> "HIRE".equals(m.movementType()) ? "Tăng" : "Giảm";
            case "movement_date" -> m.movementDate();
            case "contract_type" -> m.contractTypeCode();
            case "reason" -> m.reasonCode();
            case "legal_ground" -> m.legalGroundCode();
            default -> null;
        };
    }

    private static Map<String, String[]> columns() {
        Map<String, String[]> m = new LinkedHashMap<>();
        m.put("employee_no", new String[] {"Mã nhân viên", "Employee number"});
        m.put("full_name", new String[] {"Họ và tên", "Full name"});
        m.put("birth_date", new String[] {"Ngày sinh", "Date of birth"});
        m.put("gender", new String[] {"Giới tính", "Gender"});
        m.put("national_id", new String[] {"Số CCCD / hộ chiếu", "ID number"});
        m.put("si_number", new String[] {"Mã số BHXH", "SI number"});
        m.put("job_title", new String[] {"Vị trí việc làm", "Job title"});
        m.put("movement_type", new String[] {"Tăng / giảm", "Hire / termination"});
        m.put("movement_date", new String[] {"Ngày tăng / giảm", "Date"});
        m.put("contract_type", new String[] {"Loại hợp đồng", "Contract type"});
        m.put("reason", new String[] {"Lý do", "Reason"});
        m.put("legal_ground", new String[] {"Căn cứ chấm dứt", "Legal ground"});
        return java.util.Collections.unmodifiableMap(m);
    }

    private String[] identifiers(UUID personId) {
        UUID t = tenant();
        return db.jdbc().sql("""
                        select national_id_enc, si_number_enc, subject_key_version from hr.person
                         where tenant_id = :t and id = :p""")
                .param("t", t).param("p", personId)
                .query((rs, n) -> new String[] {
                        cipher.decrypt(Subject.person(t, personId), "national_id", rs.getBytes(1), rs.getInt(3)),
                        cipher.decrypt(Subject.person(t, personId), "si_number", rs.getBytes(2), rs.getInt(3))})
                .single();
    }

    /** Aggregates and registers cover a whole legal entity: 404 unless the permission covers it. */
    void requireLegalEntity(String permission, String alternative, UUID legalEntityId) {
        if (legalEntityId == null) {
            throw ApiException.unprocessable("legal-entity-required", "Statutory reports are kept per legal entity");
        }
        AuthorizationTarget le = new AuthorizationTarget.LegalEntity(legalEntityId);
        if (!authz.can(permission, le) && (alternative == null || !authz.can(alternative, le))) {
            throw ApiException.notFound("Legal entity", legalEntityId);
        }
    }

    private static int num(Map<?, ?> m, String key, Integer fallback) {
        Object v = m.get(key);
        if (v == null) {
            if (fallback == null) {
                throw new IllegalArgumentException(key + " is missing");
            }
            return fallback;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static int sum(Map<String, Integer> m) {
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
