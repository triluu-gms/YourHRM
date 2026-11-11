package vn.yourhrm.people.internal.application.onboarding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.people.internal.application.contract.ContractService;
import vn.yourhrm.people.internal.application.contract.ContractService.InitialSource;
import vn.yourhrm.people.internal.application.contract.ContractTerms;
import vn.yourhrm.people.internal.application.employment.InsuranceParticipationService;

/**
 * {@code OB_EMP_CONTRACT} (US-PPL-089/090/091; BR-PPL-050..053): the current labour contract with its legacy number and
 * the number of consecutive definite contracts signed before go-live (BR-PPL-053 "imported prior count"). Created
 * through the contract stream's {@code createInitial} with {@code source = OPENING_LOAD} and a sign date (ACTIVE,
 * {@code ContractChanged} SIGNED with {@code initialLoad}); the same rules as on screen apply (term limits, probation,
 * overlap, consecutive definite contracts, work-permit coverage). Insurance participation is re-derived for the
 * person afterwards. Idempotent by contract number.
 */
@Component
class ContractImport extends OpeningBalanceImport {

    private final ContractService contracts;
    private final InsuranceParticipationService insurance;

    ContractImport(OnboardingSupport support, ContractService contracts, InsuranceParticipationService insurance) {
        super(support);
        this.contracts = contracts;
        this.insurance = insurance;
    }

    @Override
    public Definition definition() {
        return definition(OnboardingKeys.CONTRACT, "Số dư đầu kỳ - Hợp đồng hiện tại", "Opening balance - current contract",
                List.of(employeeNo(),
                        text("contract_no", "Số hợp đồng", "Contract number", true),
                        text("contract_type", "Loại hợp đồng", "Contract type", true,
                                "PROBATION, DEFINITE, INDEFINITE, SEASONAL ...", "PROBATION, DEFINITE, INDEFINITE, ..."),
                        date("sign_date", "Ngày ký", "Sign date", true),
                        date("start_date", "Ngày bắt đầu", "Start date", true),
                        date("end_date", "Ngày kết thúc", "End date", false),
                        integer("probation_days", "Số ngày thử việc", "Probation days", false),
                        new Column("prior_definite_count", "Số HĐ xác định thời hạn đã ký trước", "Prior definite contracts",
                                ColumnType.INTEGER, false, null, false,
                                "Số hợp đồng xác định thời hạn liên tiếp đã ký trước hợp đồng này",
                                "Consecutive definite contracts signed before this one"),
                        text("signatory_name", "Người ký", "Signatory", false),
                        text("signatory_title", "Chức vụ người ký", "Signatory's title", false),
                        text("definite_exception_code", "Ngoại lệ HĐ xác định thời hạn", "Definite-term exception",
                                false)),
                List.of("employee_no"), List.of(OnboardingKeys.ASSIGNMENT), List.of());
    }

    @Override
    protected Written write(ImportContext ctx, ImportRow row, Map<String, Object> state, boolean dryRun) {
        OnboardingSupport.Emp emp = s.requireEmployment(row.text("employee_no"));
        s.requireUnlocked(emp.legalEntityId());
        String no = row.text("contract_no").strip();
        Optional<UUID> same = s.db().jdbc().sql("""
                        select id from hr.contract where tenant_id = :t and employment_id = :e and contract_no = :no
                           and status not in ('CANCELLED','SUPERSEDED')""")
                .param("t", OnboardingSupport.tenant()).param("e", emp.id()).param("no", no).query(UUID.class)
                .optional();
        if (same.isPresent()) {
            return Written.updated("hr.contract", same.get(), List.of());
        }
        Integer prior = integer(row, "prior_definite_count");
        ContractTerms terms = new ContractTerms(upper(row, "contract_type"), row.date("start_date"),
                row.date("end_date"), integer(row, "probation_days"), row.text("signatory_name"),
                row.text("signatory_title"), null, null, null, row.text("definite_exception_code"), null);
        var created = contracts.createInitial(emp.id(), terms,
                new InitialSource(OnboardingKeys.SOURCE, prior == null ? 0 : prior, no, row.date("sign_date")));
        if (!dryRun) {
            s.tryRun("Insurance participation after opening contract " + no,
                    () -> insurance.derive(emp.personId(), emp.hireDate(), "OPENING_BALANCE"), 0);
        }
        return Written.created("hr.contract", created.id(), new ArrayList<>());
    }

    @Override
    public List<String> undoBlockers(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = EmployeeMasterImport.created(rows);
        return s.write(() -> {
            List<String> out = new ArrayList<>();
            if (s.locked(ctx.legalEntityId())) {
                out.add("Opening balances are locked (go-live)");
            }
            if (!ids.isEmpty()) {
                out.addAll(s.db().jdbc().sql("""
                                select c.contract_no from hr.contract c where c.tenant_id = :t and c.id in (:ids)
                                   and (exists (select 1 from hr.contract_appendix a where a.tenant_id = c.tenant_id
                                                   and a.contract_id = c.id)
                                     or exists (select 1 from hr.contract x where x.tenant_id = c.tenant_id
                                                   and x.previous_contract_id = c.id))""")
                        .param("t", OnboardingSupport.tenant()).param("ids", ids).query(String.class).list().stream()
                        .map(n -> "Contract " + n + " has appendices or a following contract").toList());
            }
            return out;
        });
    }

    /** The contracts the job created are CANCELLED (they keep their number; the published event is not recalled). */
    @Override
    public void undo(ImportContext ctx, List<CommittedRow> rows) {
        List<UUID> ids = EmployeeMasterImport.created(rows);
        if (ids.isEmpty()) {
            return;
        }
        s.write(() -> s.db().jdbc().sql("""
                        update hr.contract set status = 'CANCELLED', version = version + 1, updated_by = :a
                         where tenant_id = :t and id in (:ids)""")
                .param("a", OnboardingSupport.actor()).param("t", OnboardingSupport.tenant()).param("ids", ids)
                .update());
    }
}
