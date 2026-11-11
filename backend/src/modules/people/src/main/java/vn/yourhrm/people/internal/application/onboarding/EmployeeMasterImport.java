package vn.yourhrm.people.internal.application.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.AuditLog;
import vn.yourhrm.people.api.OnboardingEvents.InitialEmployeeHired;
import vn.yourhrm.people.internal.application.employment.EmploymentService;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService;
import vn.yourhrm.people.internal.application.person.PersonService;
import vn.yourhrm.people.internal.application.person.PersonService.CreatePerson;
import vn.yourhrm.people.internal.application.person.PersonService.Identifiers;
import vn.yourhrm.people.internal.application.person.PersonService.MatchQuery;
import vn.yourhrm.people.internal.application.person.PersonService.PersonData;
import vn.yourhrm.people.internal.domain.person.PersonRules;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore;
import vn.yourhrm.people.internal.infra.employment.EmploymentStore.Row;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;

/**
 * {@code OB_EMPLOYEE_MASTER} (US-PPL-089/090/091; NFR-PPL-007, BR-PPL-001/002/003/004): one row per employment with
 * the person's identity data (P3 identifiers as sensitive columns), the employment with original hire and seniority
 * dates, and the PIT primary flag for concurrent employees.
 *
 * <p>Checks: national id format (BR-PPL-001), the same person twice in one legal entity in the file or in the tenant
 * (one live employment per LE, BR-PPL-002), a concurrent person with two primary flags (error) or none (warning:
 * the earliest employment becomes primary), tax-code / SI-number candidates of other persons (warning), unmapped custom
 * attributes (warning, R3-09). Commit: the person is matched by the blind index of the national id (else created with
 * {@code source = OPENING_LOAD}), the employment is upserted by employee number (status ACTIVE when hired by the
 * go-live date, else PRE_HIRE activated by the daily job), no workflow and no decision; {@code people.EmployeeHired}
 * with {@code initialLoad = true} is published once, when the employment is created; insurance participation is
 * re-derived for the person.
 */
@Component
class EmployeeMasterImport extends OpeningBalanceImport {

    private final PersonService persons;
    private final EmploymentStore store;
    private final EmploymentService employments;
    private final PrimaryEmploymentService primaries;
    private final InsuranceParticipationService insurance;
    private final PiiCipher cipher;
    private final HrOutbox outbox;
    private final AuditLog audit;

    EmployeeMasterImport(OnboardingSupport support, PersonService persons, EmploymentStore store,
            EmploymentService employments, PrimaryEmploymentService primaries, InsuranceParticipationService insurance,
            PiiCipher cipher, HrOutbox outbox, AuditLog audit) {
        super(support);
        this.persons = persons;
        this.store = store;
        this.employments = employments;
        this.primaries = primaries;
        this.insurance = insurance;
        this.cipher = cipher;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Override
    public Definition definition() {
        return definition(OnboardingKeys.MASTER, "Số dư đầu kỳ - Hồ sơ nhân viên", "Opening balance - employee master",
                List.of(employeeNo(),
                        lookup("legal_entity", "Pháp nhân", "Legal entity", "LEGAL_ENTITY", true),
                        text("family_name", "Họ", "Family name", true),
                        text("middle_name", "Tên đệm", "Middle name", false),
                        text("given_name", "Tên", "Given name", true),
                        text("latin_name", "Tên theo hộ chiếu", "Name as in passport", false),
                        sensitive("birth_date", "Ngày sinh", "Date of birth", ColumnType.DATE, false, null, null),
                        text("gender", "Giới tính", "Gender", false, "Mã danh mục GENDER (MALE / FEMALE)",
                                "Code list GENDER (MALE / FEMALE)"),
                        text("nationality", "Quốc tịch", "Nationality", false, "Mã quốc gia 2 ký tự, mặc định VN",
                                "2-letter country code, default VN"),
                        text("national_id_type", "Loại giấy tờ", "ID type", true, "CCCD, CMND hoặc PASSPORT",
                                "CCCD, CMND or PASSPORT"),
                        sensitive("national_id", "Số CCCD / hộ chiếu", "ID number", ColumnType.TEXT, true,
                                "CCCD 12 chữ số; hộ chiếu kèm quốc tịch", "12-digit CCCD; passport with nationality"),
                        sensitive("tax_code", "Mã số thuế cá nhân", "Personal tax code", ColumnType.TEXT, false,
                                "Bỏ trống khi dùng số CCCD làm MST", "Leave empty when the CCCD is the tax code"),
                        sensitive("si_number", "Mã số BHXH", "SI number", ColumnType.TEXT, false, null, null),
                        sensitive("hi_card_no", "Số thẻ BHYT", "Health-insurance card no.", ColumnType.TEXT, false,
                                null, null),
                        sensitive("ethnicity", "Dân tộc", "Ethnicity", ColumnType.TEXT, false,
                                "Mã danh mục ETHNICITY", "Code list ETHNICITY"),
                        text("si_book_custody", "Nơi giữ sổ BHXH", "SI book custody", false,
                                "EMPLOYER, EMPLOYEE hoặc RETURNED", "EMPLOYER, EMPLOYEE or RETURNED"),
                        date("hire_date", "Ngày vào làm", "Hire date", true),
                        date("original_hire_date", "Ngày tuyển dụng ban đầu", "Original hire date", false),
                        date("seniority_date", "Ngày tính thâm niên", "Seniority date", false),
                        date("probation_end_date", "Ngày kết thúc thử việc", "Probation end date", false),
                        text("worker_category", "Loại người lao động", "Worker category", true),
                        text("employee_category", "Nhóm nhân viên", "Employee category", false),
                        text("working_regime", "Chế độ làm việc", "Working regime", false),
                        bool("pit_primary", "Nơi giảm trừ gia cảnh chính", "PIT primary employment",
                                "Có/Không - chỉ cho người làm việc tại nhiều pháp nhân",
                                "Yes/No - only for persons employed by several legal entities"),
                        text("custom_attributes", "Thuộc tính tùy biến", "Custom attributes", false,
                                "Dạng tên=giá trị; ... - sẽ được báo cáo để ánh xạ",
                                "As name=value; ... - reported for mapping")),
                List.of("employee_no"), List.of(),
                List.of(new ControlCheck(OnboardingKeys.CHECK_HEADCOUNT, "Số lao động đang làm việc tại ngày go-live",
                        "Active headcount at go-live")));
    }

    // --------------------------------------------------------------------------------------------------- checks

    /** Persons seen in the file: national-id key → first rows (legal entity, primary flag). */
    private record Seen(int rowNo, UUID legalEntityId, boolean pitPrimary, String employeeNo) {
    }

    @Override
    protected List<RowMessage> precheck(ImportContext ctx, ImportRow row, Map<String, Object> state) {
        List<RowMessage> out = new ArrayList<>();
        String custom = row.text("custom_attributes");
        if (custom != null) {
            for (String part : custom.split(";")) {
                String name = part.contains("=") ? part.substring(0, part.indexOf('=')).strip() : part.strip();
                if (!name.isEmpty()) {
                    out.add(OnboardingSupport.warn("custom_attributes", "unmapped-custom-attribute",
                            "Thuộc tính tùy biến '" + name + "' chưa được ánh xạ: ánh xạ vào trường chuẩn hoặc lưu "
                                    + "thành tệp đính kèm", "Custom attribute '" + name + "' is not mapped: map it to a "
                                    + "standard field or keep it as an attachment"));
                }
            }
        }
        String key;
        try {
            key = personKey(row);
        } catch (ApiException ex) {
            out.add(OnboardingSupport.error("national_id", ex));
            return out;
        }
        LocalDate hire = row.date("hire_date");
        if (row.date("original_hire_date") != null && row.date("original_hire_date").isAfter(hire)) {
            out.add(OnboardingSupport.err("original_hire_date", "invalid-original-hire-date",
                    "Ngày tuyển dụng ban đầu sau ngày vào làm", "The original hire date is after the hire date"));
        }
        if (row.date("probation_end_date") != null && row.date("probation_end_date").isBefore(hire)) {
            out.add(OnboardingSupport.err("probation_end_date", "invalid-probation-end",
                    "Ngày kết thúc thử việc trước ngày vào làm", "The probation end is before the hire date"));
        }
        @SuppressWarnings("unchecked")
        Map<String, List<Seen>> seen = (Map<String, List<Seen>>) state.computeIfAbsent("persons",
                k -> new java.util.HashMap<String, List<Seen>>());
        List<Seen> earlier = seen.computeIfAbsent(key, k -> new ArrayList<>());
        UUID le = row.id("legal_entity");
        boolean primary = Boolean.TRUE.equals(flag(row, "pit_primary"));
        for (Seen e : earlier) {
            if (e.legalEntityId().equals(le)) {
                out.add(OnboardingSupport.err("national_id", "duplicate-person-in-legal-entity",
                        "Cùng một người đã có dòng " + e.rowNo() + " (" + e.employeeNo() + ") tại pháp nhân này: một "
                                + "người chỉ có một quan hệ lao động còn hiệu lực tại mỗi pháp nhân",
                        "The same person is already on row " + e.rowNo() + " (" + e.employeeNo() + ") for this legal "
                                + "entity: one live employment per legal entity (BR-PPL-002)"));
            }
        }
        if (!earlier.isEmpty() && !OnboardingSupport.hasError(out)) {
            long flagged = earlier.stream().filter(Seen::pitPrimary).count() + (primary ? 1 : 0);
            if (flagged > 1) {
                out.add(OnboardingSupport.err("pit_primary", "pit-primary-twice",
                        "Người làm việc tại nhiều pháp nhân chỉ có một nơi giảm trừ gia cảnh chính",
                        "A concurrent employee has exactly one PIT primary employment (BR-PPL-004)"));
            } else if (flagged == 0) {
                out.add(OnboardingSupport.warn("pit_primary", "pit-primary-defaulted",
                        "Chưa chọn nơi giảm trừ gia cảnh chính: quan hệ lao động bắt đầu sớm nhất được chọn",
                        "No PIT primary employment flagged: the earliest employment is designated"));
            }
        }
        if (!OnboardingSupport.hasError(out)) {
            earlier.add(new Seen(row.rowNo(), le, primary, row.text("employee_no")));
        }
        return out;
    }

    private String personKey(ImportRow row) {
        String type = upper(row, "national_id_type");
        String n = PersonRules.nationalId(type, row.text("national_id"));
        String nat = row.text("nationality") == null ? "VN" : PersonRules.country(row.text("nationality"));
        return HexFormat.of().formatHex(cipher.blindIndex(OnboardingSupport.tenant(),
                PersonRules.nationalIdKey(type, n, nat)));
    }

    // ---------------------------------------------------------------------------------------------------- write

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        UUID le = row.id("legal_entity");
        s.requireUnlocked(le);
        LocalDate asOf = s.asOf(ctx);
        List<RowMessage> warnings = new ArrayList<>();
        String nationality = row.text("nationality") == null ? "VN" : upper(row, "nationality");
        Identifiers ids = new Identifiers(upper(row, "national_id_type"), row.text("national_id"), row.text("tax_code"),
                null, row.text("si_number"), row.text("hi_card_no"), row.text("ethnicity"), null);
        PersonData data = new PersonData(row.text("family_name"), row.text("middle_name"), row.text("given_name"),
                row.text("latin_name"), null, row.date("birth_date"), row.text("gender"), nationality, null,
                upper(row, "si_book_custody"));
        String employeeNo = row.text("employee_no").strip();
        var existingEmp = s.employment(employeeNo);

        var match = persons.match(new MatchQuery(ids.nationalIdType(), ids.nationalId(), nationality, null,
                ids.taxCode(), ids.siNumber()));
        if (existingEmp.isEmpty() && !match.warnings().isEmpty()) {
            warnings.add(OnboardingSupport.warn("tax_code", "possible-duplicate-person",
                    "Người khác trong tenant có cùng mã số thuế / mã số BHXH (" + match.warnings().getFirst().matchedOn()
                            + ")", "Another person of the tenant has the same tax code / SI number ("
                            + match.warnings().getFirst().matchedOn() + ")"));
        }
        var person = persons.createOrMatch(new CreatePerson(data, ids, OnboardingKeys.SOURCE, true));
        UUID personId = person.personId();
        store.lockPerson(personId);

        if (existingEmp.isPresent()) {
            var e = existingEmp.get();
            if (!e.personId().equals(personId) || !e.legalEntityId().equals(le)) {
                throw ApiException.conflict("employee-no-taken",
                        "Employee number " + employeeNo + " belongs to another person or legal entity");
            }
            Row r = store.find(e.id()).orElseThrow();
            if ("CANCELLED".equals(r.status()) && OnboardingKeys.SOURCE.equals(r.source())) {
                return reload(row, r, personId, le, asOf, state, warnings, dryRun);
            }
            if (!store.updateAttributes(r.id(), r.version(), row.text("worker_category"), row.text("employee_category"),
                    row.text("working_regime"), row.date("original_hire_date") != null ? row.date("original_hire_date")
                            : r.originalHireDate(), row.date("seniority_date") != null ? row.date("seniority_date")
                            : r.seniorityDate(), row.date("probation_end_date"), OnboardingSupport.actor())) {
                throw ApiException.conflict("stale-version", "Employment " + employeeNo + " changed meanwhile");
            }
            designatePrimary(personId, r.id(), row, state, asOf, warnings);
            return Written.updated("hr.employment", r.id(), warnings);
        }

        if (!person.created()) {
            warnings.add(OnboardingSupport.warn("national_id", "person-exists",
                    "Người này đã có trong tenant: dữ liệu cá nhân hiện có được giữ nguyên",
                    "The person already exists in the tenant: the existing personal data is kept"));
        }
        LocalDate hire = row.date("hire_date");
        List<Row> others = store.ofPerson(personId);
        for (Row o : others) {
            if (o.legalEntityId().equals(le) && !"CANCELLED".equals(o.status())
                    && (o.endDate() == null || !o.endDate().isBefore(hire))) {
                throw ApiException.unprocessable("same-legal-entity-employment", "The person already has an "
                        + "employment (" + o.employeeNo() + ") in this legal entity for this period (BR-PPL-002)");
            }
        }
        boolean liveElsewhere = others.stream().anyMatch(o -> !"CANCELLED".equals(o.status())
                && (o.endDate() == null || !o.endDate().isBefore(hire)));
        String hireType = liveElsewhere ? "CONCURRENT"
                : others.stream().anyMatch(o -> !"CANCELLED".equals(o.status())) ? "REHIRE" : "NEW";
        LocalDate original = row.date("original_hire_date") != null ? row.date("original_hire_date") : hire;
        LocalDate seniority = row.date("seniority_date") != null ? row.date("seniority_date") : hire;
        UUID id = Ids.newId();
        Row r = new Row(id, personId, le, employeeNo, hireType, hire, original, seniority,
                row.date("probation_end_date"), null, null, hire.isAfter(asOf) ? "PRE_HIRE" : "ACTIVE",
                row.text("worker_category"), row.text("employee_category"), row.text("working_regime"), null,
                "REHIRE".equals(hireType) ? others.getLast().id() : null, OnboardingKeys.SOURCE, 0);
        try {
            store.insert(r, OnboardingSupport.actor());
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            String m = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (m.contains("employment_no_uq")) {
                throw ApiException.conflict("employee-no-taken", "Employee number " + employeeNo + " is already used");
            }
            throw ex;
        }
        designatePrimary(personId, id, row, state, asOf, warnings);
        if (!dryRun) {
            s.tryRun("Insurance participation after opening load " + employeeNo,
                    () -> insurance.derive(personId, hire, "OPENING_BALANCE"), 0);
            publishHired(r, asOf);
        }
        return Written.created("hr.employment", id, warnings);
    }

    /**
     * An employment of an earlier load that was rolled back (CANCELLED, it keeps its employee number): loaded again
     * with the row's data, as a creation (so that a later rollback covers it too).
     */
    private Written reload(ImportRow row, Row r, UUID personId, UUID le, LocalDate asOf, Map<String, Object> state,
            List<RowMessage> warnings, boolean dryRun) {
        LocalDate hire = row.date("hire_date");
        for (Row o : store.ofPerson(personId)) {
            if (!o.id().equals(r.id()) && o.legalEntityId().equals(le) && !"CANCELLED".equals(o.status())
                    && (o.endDate() == null || !o.endDate().isBefore(hire))) {
                throw ApiException.unprocessable("same-legal-entity-employment", "The person already has an "
                        + "employment (" + o.employeeNo() + ") in this legal entity for this period (BR-PPL-002)");
            }
        }
        s.db().jdbc().sql("""
                        update hr.employment set status = :st, hire_date = :hd, original_hire_date = :ohd,
                               seniority_date = :sd, probation_end_date = :ped, worker_category_code = :wc,
                               employee_category_code = :ec, working_regime_code = :wr, version = version + 1,
                               updated_by = :a, updated_at = now()
                         where tenant_id = :t and id = :id""")
                .param("st", hire.isAfter(asOf) ? "PRE_HIRE" : "ACTIVE").param("hd", hire)
                .param("ohd", row.date("original_hire_date") != null ? row.date("original_hire_date") : hire)
                .param("sd", row.date("seniority_date") != null ? row.date("seniority_date") : hire)
                .param("ped", row.date("probation_end_date")).param("wc", row.text("worker_category"))
                .param("ec", row.text("employee_category")).param("wr", row.text("working_regime"))
                .param("a", OnboardingSupport.actor()).param("t", OnboardingSupport.tenant()).param("id", r.id())
                .update();
        Row now = store.find(r.id()).orElseThrow();
        designatePrimary(personId, r.id(), row, state, asOf, warnings);
        if (!dryRun) {
            s.tryRun("Insurance participation after opening load " + now.employeeNo(),
                    () -> insurance.derive(personId, hire, "OPENING_BALANCE"), 0);
            publishHired(now, asOf);
        }
        return Written.created("hr.employment", r.id(), warnings);
    }

    private void publishHired(Row r, LocalDate asOf) {
        String fullName = s.db().jdbc().sql("select full_name from hr.person where tenant_id = :t and id = :p")
                .param("t", OnboardingSupport.tenant()).param("p", r.personId()).query(String.class).single();
        outbox.publish(new InitialEmployeeHired(OnboardingSupport.tenant(), r.legalEntityId(), r.personId(), r.id(),
                r.hireDate(), r.hireDate().isAfter(asOf), r.hireType(), r.employeeNo(), fullName, r.hireDate(),
                r.originalHireDate(), r.seniorityDate(), r.workerCategoryCode(), r.employeeCategoryCode(),
                r.workingRegimeCode(), r.previousEmploymentId(), null, null, null, null, null, null, null,
                employments.missingData(r), true));
    }

    /**
     * With 2+ live employments on the go-live date exactly one ACTIVE designation must cover it (BR-PPL-004): the
     * flagged employment (this row, or an earlier row of the person), else the earliest hired one. Written with
     * {@code source = OPENING_LOAD}, no workflow and no event (consumers re-sync from the initial load).
     */
    private void designatePrimary(UUID personId, UUID employmentId, ImportRow row, Map<String, Object> state,
            LocalDate asOf, List<RowMessage> warnings) {
        @SuppressWarnings("unchecked")
        Map<UUID, UUID> flagged = (Map<UUID, UUID>) state.computeIfAbsent("primaryFlags",
                k -> new java.util.HashMap<UUID, UUID>());
        if (Boolean.TRUE.equals(flag(row, "pit_primary"))) {
            flagged.put(personId, employmentId);
        }
        List<Row> live = store.ofPerson(personId).stream()
                .filter(e -> !"CANCELLED".equals(e.status()) && !"TERMINATED".equals(e.status())
                        && (e.endDate() == null || !e.endDate().isBefore(asOf)))
                .toList();
        if (live.size() < 2) {
            return;
        }
        LocalDate from = live.stream().map(Row::hireDate).max(LocalDate::compareTo).orElseThrow();
        var covering = primaries.designations(personId).stream()
                .filter(d -> "ACTIVE".equals(d.status()) && !d.validFrom().isAfter(from)
                        && (d.validTo() == null || d.validTo().isAfter(from)))
                .findFirst();
        UUID chosen = flagged.getOrDefault(personId, live.getFirst().id());
        if (covering.isPresent()) {
            if (!covering.get().employmentId().equals(chosen) && flagged.containsKey(personId)) {
                warnings.add(OnboardingSupport.warn("pit_primary", "pit-primary-kept",
                        "Đã có nơi giảm trừ gia cảnh chính được chỉ định; thay đổi qua quy trình phê duyệt",
                        "A PIT primary employment is already designated; change it through the approval flow"));
            }
            return;
        }
        s.db().jdbc().sql("""
                        insert into hr.primary_employment (tenant_id, id, person_id, employment_id, purpose, valid_from,
                               status, reason_code, source, created_by, updated_by)
                        values (:t, :id, :p, :e, 'PIT_FAMILY_DEDUCTION', :vf, 'ACTIVE', 'OPENING_BALANCE',
                                'OPENING_LOAD', :a, :a)""")
                .param("t", OnboardingSupport.tenant()).param("id", Ids.newId()).param("p", personId)
                .param("e", chosen).param("vf", from).param("a", OnboardingSupport.actor()).update();
    }

    // ------------------------------------------------------------------------------------------ reconciliation

    @Override
    public List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        LocalDate asOf = s.asOf(ctx);
        long active = rows.stream().filter(r -> !r.date("hire_date").isAfter(asOf)).count();
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_HEADCOUNT, "ALL", BigDecimal.valueOf(active)));
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = rows.stream().map(CommittedRow::targetId).toList();
        long active = ids.isEmpty() ? 0 : s.write(() -> s.db().jdbc().sql("""
                        select count(*) from hr.employment where tenant_id = :t and id in (:ids) and status = 'ACTIVE'""")
                .param("t", OnboardingSupport.tenant()).param("ids", ids).query(Long.class).single());
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_HEADCOUNT, "ALL", BigDecimal.valueOf(active)));
    }

    // -------------------------------------------------------------------------------------------------- rollback

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = created(rows);
        return s.write(() -> {
            List<String> out = new ArrayList<>();
            if (s.locked(ctx.legalEntityId())) {
                out.add("Opening balances are locked (go-live)");
            }
            if (ids.isEmpty()) {
                return out;
            }
            out.addAll(s.db().jdbc().sql("""
                            select e.employee_no || ': ' || x.what from hr.employment e
                              cross join lateral (
                                select 'assignment' as what where exists (select 1 from hr.assignment a
                                       where a.tenant_id = e.tenant_id and a.employment_id = e.id)
                                union all select 'salary record' where exists (select 1 from hr.salary_record r
                                       where r.tenant_id = e.tenant_id and r.employment_id = e.id)
                                union all select 'contract' where exists (select 1 from hr.contract c
                                       where c.tenant_id = e.tenant_id and c.employment_id = e.id)
                                union all select 'dependant claim' where exists (select 1 from hr.pit_dependant d
                                       where d.tenant_id = e.tenant_id and d.employment_id = e.id)
                                union all select 'separation' where exists (select 1 from hr.separation sp
                                       where sp.tenant_id = e.tenant_id and sp.employment_id = e.id)
                                union all select 'decision' where exists (select 1 from hr.decision dc
                                       where dc.tenant_id = e.tenant_id and dc.employment_id = e.id)) x
                             where e.tenant_id = :t and e.id in (:ids)
                             order by 1""")
                    .param("t", OnboardingSupport.tenant()).param("ids", ids).query(String.class).list()
                    .stream().map(m -> "Employment " + m + " depends on it (roll back the later sheets first; "
                            + "assignments and salary records are immutable history)").toList());
            return out;
        });
    }

    /**
     * Rolls back the employments the job created by CANCELLING them (People's DB role has no DELETE on
     * {@code hr.employment} / {@code hr.person}): the CANCELLED status frees the one-employment-per-LE rule, the PIT
     * designations and insurance-participation rows are CANCELLED too. The person rows stay (no employment) and a
     * re-load of the same employee number re-activates the employment. The initial-load events already published are
     * not recalled (consumers re-sync; see the stream report).
     */
    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = created(rows);
        if (ids.isEmpty()) {
            return;
        }
        s.write(() -> {
            var j = s.db().jdbc();
            UUID t = OnboardingSupport.tenant();
            UUID a = OnboardingSupport.actor();
            j.sql("""
                            update hr.insurance_participation set status = 'CANCELLED', version = version + 1
                             where tenant_id = :t and employment_id in (:ids) and status = 'ACTIVE'""")
                    .param("t", t).param("ids", ids).update();
            j.sql("""
                            update hr.primary_employment set status = 'CANCELLED', version = version + 1
                             where tenant_id = :t and employment_id in (:ids) and status = 'ACTIVE'""")
                    .param("t", t).param("ids", ids).update();
            j.sql("""
                            update hr.employment set status = 'CANCELLED', version = version + 1, updated_by = :a,
                                   updated_at = now()
                             where tenant_id = :t and id in (:ids)""")
                    .param("a", a).param("t", t).param("ids", ids).update();
            audit.record(new AuditLog.BusinessAction("UNDO_OPENING_LOAD", "hr", "import_job", ctx.jobId(),
                    ctx.legalEntityId(), Map.of("importType", ctx.importType(), "employments",
                            String.valueOf(ids.size()))));
            return null;
        });
    }

    static List<UUID> created(List<CommittedRow> rows) {
        return rows.stream().filter(r -> "CREATED".equals(r.action())).map(CommittedRow::targetId).toList();
    }
}
