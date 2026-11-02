package vn.yourhrm.people.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Events of the labour-contract stream (EP-PPL-06; catalogue gap-analysis §6). Payloads never carry P3 values nor
 * salary amounts (an appendix publishes only the names of the changed terms; amounts travel in
 * {@code people.SalaryChanged}).
 */
public final class ContractEvents {

    private ContractEvents() {
    }

    /** Subtypes of {@code people.ContractChanged} (OUT-08, OUT-11). */
    public static final String SIGNED = "SIGNED";
    public static final String RENEWED = "RENEWED";
    public static final String CONVERTED = "CONVERTED";
    public static final String APPENDIX = "APPENDIX";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String RESUMED = "RESUMED";

    /**
     * {@code people.ContractChanged} (OUT-08/OUT-11): a contract became effective by signature (SIGNED, or RENEWED /
     * CONVERTED when it follows a definite contract), an appendix was signed (APPENDIX), or the contract was
     * suspended / resumed. {@code effectiveDate} is the contract start, the appendix effective date, the first
     * suspended day or the return date.
     *
     * @param definiteSeq            n-th consecutive definite contract (BR-PPL-053), null for other types
     * @param definiteExceptionCode  the legal exception that allowed a third definite contract, if any
     * @param changedTerms           APPENDIX only: names of the amended terms (no values)
     */
    public record ContractChanged(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, String subtype, UUID contractId, String contractNo, String contractTypeCode,
            LocalDate startDate, LocalDate endDate, Integer definiteSeq, UUID previousContractId,
            String definiteExceptionCode, UUID appendixId, String appendixNo, List<String> changedTerms,
            UUID suspensionId, String suspensionReasonCode, LocalDate suspendedUntil, boolean initialLoad)
            implements PeopleEvent {

        public ContractChanged {
            changedTerms = changedTerms == null ? List.of() : List.copyOf(changedTerms);
        }

        @Override
        public String eventType() {
            return "people.ContractChanged";
        }
    }

    /**
     * {@code people.ProbationCompleted} (OUT-09, BR-PPL-052): the probation result was recorded. A FAIL is the hook
     * of the separation flow (it listens to this event in-process and opens a separation, US-PPL-061).
     *
     * @param result PASS or FAIL
     */
    public record ProbationCompleted(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID contractId, String contractNo, String result, LocalDate resultDate,
            LocalDate probationEndDate, UUID decisionId, UUID nextContractId) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.ProbationCompleted";
        }
    }

    /**
     * {@code people.ContractExpiring} (OUT-10, BR-PPL-054; not yet in the catalogue §6): published once per contract
     * and alert offset by the expiry monitor, and once more ({@code autoConversionDue}) when the employee keeps
     * working more than {@code contract.definite.auto_convert_after_days} days after the end without a new contract.
     * {@code effectiveDate} is the contract end date.
     */
    public record ContractExpiring(UUID tenantId, UUID legalEntityId, UUID personId, UUID employmentId,
            LocalDate effectiveDate, UUID contractId, String contractNo, String contractTypeCode, LocalDate endDate,
            int daysBefore, boolean autoConversionDue) implements PeopleEvent {

        @Override
        public String eventType() {
            return "people.ContractExpiring";
        }
    }
}
