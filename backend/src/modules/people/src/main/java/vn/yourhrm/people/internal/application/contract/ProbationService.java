package vn.yourhrm.people.internal.application.contract;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vn.yourhrm.people.api.ContractEvents.ProbationCompleted;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.application.contract.ContractRecords.ContractView;
import vn.yourhrm.people.internal.domain.contract.ContractType;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Probation result (BR-PPL-052, US-PPL-049; Labour Code Art. 27 [verify-legal]): recorded, by the last probation day
 * at the latest, with a PROBATION_RESULT decision, and published as {@code people.ProbationCompleted}.
 * <ul>
 *   <li>PASS after a probation agreement: the labour contract terms given with the result become a DRAFT contract
 *       starting the day after the probation (signed later); PASS of a probation clause: the contract simply
 *       continues.</li>
 *   <li>FAIL: the event is the hook of the separation flow (US-PPL-061), which opens the separation.</li>
 * </ul>
 * The DDL has no result column on {@code hr.contract}: the result is the decision plus the outbox event (read back by
 * {@link ContractRecords}).
 */
@Service
public class ProbationService {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";

    /** {@code nextContract}: PASS of a probation agreement only, the labour contract to draft. */
    public record Result(String result, LocalDate resultDate, String signatoryName, String signatoryTitle,
            ContractTerms nextContract) {
    }

    public record Recorded(ContractView contract, UUID decisionId, String decisionNo, ContractView nextContract) {
    }

    private final ContractRecords records;
    private final ContractService contracts;
    private final DecisionService decisions;
    private final HrOutbox outbox;
    private final BusinessClock clock;

    public ProbationService(ContractRecords records, ContractService contracts, DecisionService decisions,
            HrOutbox outbox, BusinessClock clock) {
        this.records = records;
        this.contracts = contracts;
        this.decisions = decisions;
        this.outbox = outbox;
        this.clock = clock;
    }

    @HrTransactional
    public Recorded record(UUID contractId, Result r) {
        ContractView c = records.require(contractId);
        contracts.requireScope(PeoplePermissions.CONTRACT_UPDATE, c);
        if (!PASS.equals(r.result()) && !FAIL.equals(r.result())) {
            throw ApiException.unprocessable("invalid-probation-result", "The result is PASS or FAIL");
        }
        if (!c.hasProbation()) {
            throw ApiException.unprocessable("no-probation", "Contract " + c.contractNo() + " has no probation");
        }
        if (!"ACTIVE".equals(c.status())) {
            throw ApiException.unprocessable("invalid-status-transition", "The probation result is recorded on a "
                    + "signed contract in force; " + c.contractNo() + " is " + c.status());
        }
        if (c.probationResult() != null) {
            throw ApiException.conflict("probation-result-recorded", "The probation result of " + c.contractNo()
                    + " is already recorded (" + c.probationResult() + ")");
        }
        LocalDate date = r.resultDate() != null ? r.resultDate() : clock.today();
        if (date.isAfter(c.probationEndDate())) {
            throw ApiException.unprocessable("probation-result-late", "The result must be recorded and notified by "
                    + "the end of the probation (" + c.probationEndDate() + ", BR-PPL-052)");
        }
        if (date.isBefore(c.startDate())) {
            throw ApiException.unprocessable("invalid-result-date", "The result date is before the probation start");
        }
        if (FAIL.equals(r.result()) && r.nextContract() != null) {
            throw ApiException.unprocessable("invalid-next-contract", "No next contract after a failed probation");
        }
        boolean agreement = c.type() == ContractType.PROBATION;
        if (PASS.equals(r.result()) && !agreement && r.nextContract() != null) {
            throw ApiException.unprocessable("invalid-next-contract",
                    "A probation clause passes into its own contract; no next contract is drafted");
        }
        if (PASS.equals(r.result()) && agreement && r.nextContract() == null) {
            throw ApiException.unprocessable("next-contract-required",
                    "A passed probation agreement leads to a labour contract: give its terms (BR-PPL-052)");
        }
        // Core calls first (ADR-0021): the decision number is drawn in Core's own transaction.
        DecisionService.Decision decision = decisions.issue(new DecisionService.Issue(c.legalEntityId(),
                "PROBATION_RESULT", c.employmentId(), c.probationEndDate().plusDays(1), r.signatoryName(),
                r.signatoryTitle(), null));
        ContractView next = null;
        if (PASS.equals(r.result()) && agreement) {
            next = contracts.afterProbation(c, r.nextContract());
        }
        outbox.publish(new ProbationCompleted(TenantContext.current().tenantId(), c.legalEntityId(), c.personId(),
                c.employmentId(), c.probationEndDate().plusDays(1), c.id(), c.contractNo(), r.result(), date,
                c.probationEndDate(), decision.id(), next == null ? null : next.id()));
        return new Recorded(records.require(contractId), decision.id(), decision.decisionNo(), next);
    }
}
