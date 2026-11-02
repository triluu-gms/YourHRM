package vn.yourhrm.people.internal.web.bank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.bank.BankAccountService;
import vn.yourhrm.people.internal.application.bank.BankAccountService.AccountNumber;
import vn.yourhrm.people.internal.application.bank.BankAccountService.Attributes;
import vn.yourhrm.people.internal.application.bank.BankAccountService.BankAccountView;
import vn.yourhrm.people.internal.application.bank.BankAccountService.Saved;
import vn.yourhrm.people.internal.application.family.SelfServiceScope;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Salary bank accounts (BR-PPL-015; US-PPL-031/032 HR side, US-PPL-075 ESS side). New accounts are approved through
 * {@code hr.BANK_ACCOUNT_CHANGE}; the full number is shown only with {@code hr.person.view_sensitive} (audited).
 */
@RestController
@RequestMapping("/api/v1/people")
class BankAccountController {

    private final BankAccountService accounts;
    private final SelfServiceScope self;
    private final StepUpVerifier stepUp;

    BankAccountController(BankAccountService accounts, SelfServiceScope self, StepUpVerifier stepUp) {
        this.accounts = accounts;
        this.self = self;
        this.stepUp = stepUp;
    }

    /** {@code splitType} PERCENT | FIXED | REMAINDER (default account; {@code splitValue} empty). */
    record BankAccountRequest(
            @NotBlank @Size(max = 16) String bankCode,
            @Size(max = 32) String branchCode,
            @NotBlank @Size(max = 40) String accountNo,
            @NotBlank @Size(max = 200) String holderName,
            String currencyCode,
            String splitType,
            BigDecimal splitValue,
            Integer priority,
            LocalDate validFrom,
            LocalDate validTo) {

        Attributes toAttributes() {
            return new Attributes(bankCode, branchCode, accountNo, holderName, currencyCode, splitType, splitValue,
                    priority, validFrom, validTo);
        }
    }

    record CloseRequest(@NotNull LocalDate validTo, @NotNull Integer version) {
    }

    // ------------------------------------------------------------------------------------------- HR

    @GetMapping("/persons/{personId}/bank-accounts")
    @RequiresPermission(PeoplePermissions.BANK_ACCOUNT_VIEW)
    public List<BankAccountView> list(@PathVariable UUID personId) {
        return accounts.list(personId);
    }

    @GetMapping("/persons/{personId}/bank-accounts/{id}/account-number")
    @RequiresPermission(PeoplePermissions.BANK_ACCOUNT_VIEW)
    public AccountNumber accountNumber(@PathVariable UUID personId, @PathVariable UUID id) {
        return accounts.accountNumber(personId, id);
    }

    @PostMapping("/persons/{personId}/bank-accounts")
    @RequiresPermission(PeoplePermissions.BANK_ACCOUNT_UPDATE)
    public ResponseEntity<Saved> create(@PathVariable UUID personId, @Valid @RequestBody BankAccountRequest req) {
        if (req.validFrom() == null) {
            throw vn.yourhrm.shared.common.error.ApiException.unprocessable("invalid-period", "validFrom is required");
        }
        Saved saved = accounts.request(personId, req.toAttributes());
        return ResponseEntity.created(URI.create("/api/v1/people/persons/" + personId + "/bank-accounts/"
                + saved.account().id())).body(saved);
    }

    @PostMapping("/persons/{personId}/bank-accounts/{id}/close")
    @RequiresPermission(PeoplePermissions.BANK_ACCOUNT_UPDATE)
    public BankAccountView close(@PathVariable UUID personId, @PathVariable UUID id,
            @Valid @RequestBody CloseRequest req) {
        return accounts.close(personId, id, req.validTo(), req.version());
    }

    // ------------------------------------------------------------------------------------------- ESS

    @GetMapping("/me/bank-accounts")
    @RequiresPermission(PeoplePermissions.ESS_VIEW)
    public List<BankAccountView> mine() {
        return accounts.listOwn(self.require(PeoplePermissions.ESS_VIEW).personId());
    }

    /** ESS bank change with step-up authentication; HR approval follows (US-PPL-075). */
    @PostMapping("/me/bank-accounts")
    @RequiresPermission(PeoplePermissions.ESS_REQUEST)
    public ResponseEntity<Saved> requestChange(@Valid @RequestBody BankAccountRequest req) {
        String evidence = stepUp.require();
        UUID person = self.require(PeoplePermissions.ESS_REQUEST).personId();
        return ResponseEntity.status(201).body(accounts.requestOwn(person, req.toAttributes(), evidence));
    }
}
