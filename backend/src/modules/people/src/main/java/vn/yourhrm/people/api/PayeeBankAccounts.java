package vn.yourhrm.people.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read API of salary bank accounts for Payroll's bank file (US-PPL-032: "Payroll reads the account number through the
 * API"; IF-PAY-C bank file). Returns the person's ACTIVE accounts valid on {@code asOf} with the decrypted account
 * number. A system interface: the calling module authorises its own operation (payroll run) and must never log,
 * publish or persist the number outside its encrypted bank-file pipeline. Runs in the caller's tenant context.
 */
public interface PayeeBankAccounts {

    /** Ordered: split accounts by priority, the REMAINDER (default) account last. */
    List<PayeeBankAccount> activeAccounts(UUID personId, LocalDate asOf);

    record PayeeBankAccount(UUID bankAccountId, String bankCode, String branchCode, String accountNo,
            String holderName, String currencyCode, String splitType, BigDecimal splitValue, int priority) {
    }
}
