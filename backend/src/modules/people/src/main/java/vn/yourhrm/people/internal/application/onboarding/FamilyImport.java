package vn.yourhrm.people.internal.application.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.employment.PrimaryEmploymentService;
import vn.yourhrm.people.internal.application.family.DependantService;
import vn.yourhrm.people.internal.application.family.FamilyMemberService;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * {@code OB_EMP_FAMILY} (US-PPL-089/090/091; BR-PPL-060..062): family members and, when registered, the PIT dependant
 * claim with its registered months. Members go through the family stream's {@code createForInitialLoad} (same
 * validation as the UI: relationship code list, identifier formats); claims through
 * {@code DependantService.createForInitialLoad} (APPROVED without workflow, the one-claimant rule applies,
 * {@code DependantChanged} REGISTERED with {@code initialLoad}). A concurrent employee's claim must be on the PIT
 * primary employment of its first month (BR-PPL-004). Idempotent: a member with the same relationship, name and birth
 * date is reused, an existing claim of the member from the same month is kept. Claims carry
 * {@code source = 'OPENING_LOAD'}, which lets the 24-hour rollback remove exactly what the load registered.
 */
@Component
class FamilyImport extends OpeningBalanceImport {

    private final FamilyMemberService members;
    private final DependantService dependants;
    private final PrimaryEmploymentService primaries;

    FamilyImport(OnboardingSupport support, FamilyMemberService members, DependantService dependants,
            PrimaryEmploymentService primaries) {
        super(support);
        this.members = members;
        this.dependants = dependants;
        this.primaries = primaries;
    }

    @Override
    public Definition definition() {
        return definition(OnboardingKeys.FAMILY, "Số dư đầu kỳ - Gia đình và người phụ thuộc",
                "Opening balance - family and dependants",
                List.of(employeeNo(),
                        text("member_ref", "Số thứ tự thành viên", "Member no.", true, "1, 2, 3 ... trong mỗi nhân viên",
                                "1, 2, 3 ... per employee"),
                        text("relationship", "Quan hệ", "Relationship", true, "Mã danh mục RELATIONSHIP",
                                "Code list RELATIONSHIP"),
                        sensitive("full_name", "Họ và tên", "Full name", ColumnType.TEXT, true, null, null),
                        sensitive("birth_date", "Ngày sinh", "Date of birth", ColumnType.DATE, false, null, null),
                        sensitive("national_id", "Số CCCD", "ID number", ColumnType.TEXT, false, null, null),
                        sensitive("tax_code", "Mã số thuế NPT", "Dependant tax code", ColumnType.TEXT, false, null,
                                null),
                        bool("income_above_threshold", "Có thu nhập vượt ngưỡng", "Income above threshold", null, null),
                        bool("disabled", "Khuyết tật", "Disabled", null, null),
                        bool("deceased", "Đã mất", "Deceased", null, null),
                        date("dependant_from", "Giảm trừ từ tháng", "Dependant from month", false),
                        date("dependant_to", "Giảm trừ đến tháng", "Dependant to month", false),
                        text("dependant_reason", "Lý do người phụ thuộc", "Dependant reason", false),
                        text("tax_registration_ref", "Số đăng ký NPT", "Tax registration ref.", false),
                        date("registered_at", "Ngày đăng ký", "Registered on", false)),
                List.of("employee_no", "member_ref"), List.of(OnboardingKeys.MASTER),
                List.of(new ControlCheck(OnboardingKeys.CHECK_DEPENDANTS, "Số người phụ thuộc đã đăng ký",
                        "Registered dependants")));
    }

    @Override
    protected List<RowMessage> precheck(ImportContext ctx, ImportRow row, Map<String, Object> state) {
        if (row.date("dependant_from") != null && row.text("dependant_reason") == null) {
            return List.of(OnboardingSupport.err("dependant_reason", "required",
                    "Lý do người phụ thuộc: bắt buộc khi có tháng giảm trừ",
                    "Dependant reason: required with a dependant month"));
        }
        return List.of();
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        var j = s.db().jdbc();
        UUID t = OnboardingSupport.tenant();
        LocalDate birth = row.date("birth_date");
        Optional<UUID> existing = j.sql("""
                        select id from hr.family_member where tenant_id = :t and person_id = :p
                           and relationship_code = :r and lower(full_name) = lower(:n)
                           and birth_date is not distinct from cast(:b as date) order by created_at limit 1""")
                .param("t", t).param("p", emp.personId()).param("r", upper(row, "relationship"))
                .param("n", row.text("full_name").strip()).param("b", birth).query(UUID.class).optional();
        boolean created = existing.isEmpty();
        UUID memberId = existing.orElseGet(() -> members.createForInitialLoad(emp.personId(),
                new FamilyMemberService.Attributes(upper(row, "relationship"), row.text("full_name").strip(), birth,
                        row.text("national_id"), row.text("tax_code"), flag(row, "income_above_threshold"),
                        (BigDecimal) null, Boolean.TRUE.equals(flag(row, "disabled")),
                        Boolean.TRUE.equals(flag(row, "deceased")))));
        LocalDate from = row.date("dependant_from");
        UUID claimId = null;
        if (from != null) {
            LocalDate month = from.withDayOfMonth(1);
            boolean claimed = j.sql("""
                            select exists (select 1 from hr.pit_dependant where tenant_id = :t and family_member_id = :m
                                              and employment_id = :e and from_month = :f
                                              and status in ('PENDING','APPROVED','ENDED'))""")
                    .param("t", t).param("m", memberId).param("e", emp.id()).param("f", month)
                    .query(Boolean.class).single();
            if (!claimed) {
                var primary = primaries.primaryOn(emp.personId(), month);
                if (primary.liveEmployments() > 1 && !emp.id().equals(primary.primaryEmploymentId())) {
                    throw ApiException.unprocessable("not-primary-employment", "A concurrent employee claims "
                            + "dependants on the PIT primary employment only (BR-PPL-004)");
                }
                claimId = dependants.createForInitialLoad(memberId, emp.id(), month, row.date("dependant_to"),
                        upper(row, "dependant_reason"), row.text("tax_registration_ref"), row.date("registered_at"));
            }
        }
        if (created) {
            return Written.created(MEMBER, memberId, new ArrayList<>());
        }
        // a claim registered on a member that already existed: the row's own creation is the claim
        return claimId != null ? Written.created(CLAIM, claimId, new ArrayList<>())
                : Written.updated(MEMBER, memberId, new ArrayList<>());
    }

    @Override
    public List<ReconciliationValue> stagedTotals(ImportContext ctx, List<ImportRow> rows) {
        long n = rows.stream().filter(r -> r.date("dependant_from") != null).count();
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_DEPENDANTS, "ALL", BigDecimal.valueOf(n)));
    }

    @Override
    public List<ReconciliationValue> committedTotals(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> members = ids(rows, MEMBER, null);
        List<UUID> claims = ids(rows, CLAIM, null);
        long n = members.isEmpty() && claims.isEmpty() ? 0 : s.write(() -> s.db().jdbc().sql("""
                        select count(distinct d.id) from hr.pit_dependant d
                         where d.tenant_id = :t and d.status = 'APPROVED'
                           and (d.family_member_id in (:members) or d.id in (:claims))""")
                .param("t", OnboardingSupport.tenant()).param("members", orNone(members))
                .param("claims", orNone(claims)).query(Long.class).single());
        return List.of(new ReconciliationValue(OnboardingKeys.CHECK_DEPENDANTS, "ALL", BigDecimal.valueOf(n)));
    }

    // -------------------------------------------------------------------------------------------------- rollback

    /**
     * The members the job created can be removed unless something entered in the app now refers to them: a dependant
     * claim not registered by the load ({@code source <> 'OPENING_LOAD'}, e.g. a transfer or an ESS request) or an
     * emergency contact.
     */
    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> members = ids(rows, MEMBER, "CREATED");
        return s.write(() -> {
            List<String> out = new ArrayList<>();
            if (s.locked(ctx.legalEntityId())) {
                out.add("Opening balances are locked (go-live)");
            }
            if (members.isEmpty()) {
                return out;
            }
            out.addAll(s.db().jdbc().sql("""
                            select count(*) || ' imported family member(s) have ' || x.what
                                   || ' entered in the app; remove it first' from hr.family_member m
                              cross join lateral (
                                select 'a dependant claim' as what where exists (select 1 from hr.pit_dependant d
                                       where d.tenant_id = m.tenant_id and d.family_member_id = m.id
                                         and d.source <> :source)
                                union all select 'an emergency contact' where exists (select 1 from hr.emergency_contact c
                                       where c.tenant_id = m.tenant_id and c.family_member_id = m.id)) x
                             where m.tenant_id = :t and m.id in (:ids)
                             group by x.what order by 1""")
                    .param("source", DependantService.SOURCE_OPENING_LOAD).param("t", OnboardingSupport.tenant())
                    .param("ids", members).query(String.class).list());
            return out;
        });
    }

    /**
     * Deletes the dependant claims the job registered ({@code source = 'OPENING_LOAD'}: those of the members it
     * created and the claims it added to existing members), then the members it created. Claims and members that
     * existed before the job, or were entered in the app, stay. The initial-load {@code DependantChanged} events are
     * not recalled (as for the other opening-balance sheets).
     */
    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> members = ids(rows, MEMBER, "CREATED");
        List<UUID> claims = ids(rows, CLAIM, "CREATED");
        if (members.isEmpty() && claims.isEmpty()) {
            return;
        }
        s.write(() -> {
            var j = s.db().jdbc();
            UUID t = OnboardingSupport.tenant();
            j.sql("""
                            delete from hr.pit_dependant where tenant_id = :t and source = :source
                               and (family_member_id in (:members) or id in (:claims))""")
                    .param("t", t).param("source", DependantService.SOURCE_OPENING_LOAD)
                    .param("members", orNone(members)).param("claims", orNone(claims)).update();
            if (!members.isEmpty()) {
                j.sql("delete from hr.family_member where tenant_id = :t and id in (:ids)")
                        .param("t", t).param("ids", members).update();
            }
            return null;
        });
    }

    private static final String MEMBER = "hr.family_member";
    private static final String CLAIM = "hr.pit_dependant";

    /** Target ids of the rows on {@code entity}, with {@code action} (any action when null). */
    private static List<UUID> ids(List<CommittedRow> rows, String entity, String action) {
        return rows.stream().filter(r -> entity.equals(r.targetEntity())
                        && (action == null || action.equals(r.action())))
                .map(CommittedRow::targetId).distinct().toList();
    }

    /** A never-matching id for an empty {@code in (...)} list. */
    private static List<UUID> orNone(List<UUID> ids) {
        return ids.isEmpty() ? List.of(new UUID(0, 0)) : ids;
    }
}
