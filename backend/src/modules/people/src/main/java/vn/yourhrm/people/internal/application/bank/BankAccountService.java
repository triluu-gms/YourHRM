package vn.yourhrm.people.internal.application.bank;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.yourhrm.core.api.Notifications;
import vn.yourhrm.core.api.Notifications.Recipients;
import vn.yourhrm.core.api.Notifications.SendRequest;
import vn.yourhrm.people.api.BankEvents;
import vn.yourhrm.people.api.BankEvents.BankAccountChanged;
import vn.yourhrm.people.api.PayeeBankAccounts;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.EmployeeAccess;
import vn.yourhrm.people.internal.application.HrMasking;
import vn.yourhrm.people.internal.infra.HrDb;
import vn.yourhrm.people.internal.infra.HrOutbox;
import vn.yourhrm.people.internal.infra.HrTransactional;
import vn.yourhrm.people.internal.infra.PiiCipher;
import vn.yourhrm.people.internal.infra.PiiCipher.Sealed;
import vn.yourhrm.people.internal.infra.PiiCipher.Subject;
import vn.yourhrm.shared.auth.api.SensitiveDataAccess;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;
import vn.yourhrm.shared.workflow.api.WorkflowService;
import vn.yourhrm.shared.workflow.api.WorkflowService.StartRequest;

/**
 * Salary bank accounts of a person (BR-PPL-015, NFR-PPL-005/011; US-PPL-031/032/075). Accounts belong to the PERSON
 * (Pattern A, {@code valid_to} exclusive) and are used by every employment of the person.
 * <ul>
 *   <li>One or more accounts; a split account takes a PERCENT (≤ 100 in total on every day) or a FIXED amount, and
 *       exactly one REMAINDER account (the default salary account) receives the rest: at most one ACTIVE per day
 *       ({@code bank_account_remainder_ex}), and a split account needs a default account covering its whole period;</li>
 *   <li>account number P3: encrypted with the person's key, blind index (duplicate / shared-account checks) and last
 *       4 for display; bank and branch from Core's global bank master data;</li>
 *   <li>warnings, not blocks: holder name differs from the employee's name compared without diacritics; the same
 *       account number is registered for another person of the tenant;</li>
 *   <li>every new account is PENDING_VERIFICATION until approved through Core process type
 *       {@code hr.BANK_ACCOUNT_CHANGE} → ACTIVE (a new default account ends the previous default account the day
 *       before) and {@code people.BankAccountChanged} without the full number; rejection → REJECTED;</li>
 *   <li>ESS requests (US-PPL-075) carry the step-up evidence ({@code step_up_auth_ref}); the employee is notified by
 *       e-mail when the request is decided.</li>
 * </ul>
 */
@Service
public class BankAccountService implements PayeeBankAccounts {

    public static final List<String> SPLIT_TYPES = List.of("PERCENT", "FIXED", "REMAINDER");
    private static final Pattern ACCOUNT_NO = Pattern.compile("^[A-Z0-9]{4,34}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** PUT-style attributes of a new account. {@code currencyCode} null = VND (VND only in P1, R2-09). */
    public record Attributes(String bankCode, String branchCode, String accountNo, String holderName,
            String currencyCode, String splitType, BigDecimal splitValue, Integer priority, LocalDate validFrom,
            LocalDate validTo) {
    }

    public record BankAccountView(UUID id, UUID personId, String bankCode, String bankName, String branchCode,
            String accountNoMasked, String holderName, String currencyCode, String splitType, BigDecimal splitValue,
            int priority, LocalDate validFrom, LocalDate validTo, String status, boolean stepUpVerified,
            Instant verifiedAt, int version) {
    }

    /** A non-blocking finding (BR-PPL-015: holder name mismatch is a warning). */
    public record Warning(String code, String message) {
    }

    public record Saved(BankAccountView account, List<Warning> warnings) {
    }

    /** Unmasked account number (audited sensitive read). */
    public record AccountNumber(UUID id, String accountNo) {
    }

    private record Row(UUID id, UUID personId, String bankCode, String branchCode, String last4, String holderName,
            String currencyCode, String splitType, BigDecimal splitValue, int priority, LocalDate validFrom,
            LocalDate validTo, String status, String stepUpRef, Instant verifiedAt, int version) {

        boolean covers(LocalDate d) {
            return !validFrom.isAfter(d) && (validTo == null || validTo.isAfter(d));
        }

        boolean overlaps(LocalDate from, LocalDate to) {
            return (to == null || validFrom.isBefore(to)) && (validTo == null || validTo.isAfter(from));
        }
    }

    private final HrDb db;
    private final HrOutbox outbox;
    private final PiiCipher cipher;
    private final EmployeeAccess access;
    private final HrMasking masking;
    private final SensitiveDataAccess sensitive;
    private final WorkflowService workflow;
    private final Notifications notifications;
    private final BusinessClock clock;

    public BankAccountService(HrDb db, HrOutbox outbox, PiiCipher cipher, EmployeeAccess access, HrMasking masking,
            SensitiveDataAccess sensitive, WorkflowService workflow, Notifications notifications, BusinessClock clock) {
        this.db = db;
        this.outbox = outbox;
        this.cipher = cipher;
        this.access = access;
        this.masking = masking;
        this.sensitive = sensitive;
        this.workflow = workflow;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ================================================================================================ HR

    @HrTransactional(readOnly = true)
    public List<BankAccountView> list(UUID personId) {
        access.requirePerson(PeoplePermissions.BANK_ACCOUNT_VIEW, personId, clock.today());
        masking.apply(PeoplePermissions.BANK_ACCOUNT_VIEW);
        return views(personId);
    }

    /** The full account number: needs {@code hr.person.view_sensitive} on the person; audited (BR-CORE-034). */
    @HrTransactional(readOnly = true)
    public AccountNumber accountNumber(UUID personId, UUID id) {
        LocalDate today = clock.today();
        access.requirePerson(PeoplePermissions.BANK_ACCOUNT_VIEW, personId, today);
        try {
            access.requirePerson(PeoplePermissions.PERSON_VIEW_SENSITIVE, personId, today);
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "sensitive-not-permitted",
                    "Showing the account number needs " + PeoplePermissions.PERSON_VIEW_SENSITIVE);
        }
        String number = decrypt(personId, id);
        sensitive.recordSensitiveRead(PeoplePermissions.PERSON_VIEW_SENSITIVE, "hr.bank_account", id, null);
        return new AccountNumber(id, number);
    }

    /** HR records a new account (US-PPL-031/032): PENDING_VERIFICATION until approved. */
    @HrTransactional
    public Saved request(UUID personId, Attributes a) {
        access.requirePerson(PeoplePermissions.BANK_ACCOUNT_UPDATE, personId, clock.today());
        return doRequest(personId, a, null);
    }

    /**
     * HR ends an ACTIVE account from {@code validTo} (exclusive): e.g. stops a split. A default account cannot end
     * while split accounts continue without another default account. Publishes {@code BankAccountChanged} CLOSED.
     */
    @HrTransactional
    public BankAccountView close(UUID personId, UUID id, LocalDate validTo, int version) {
        access.requirePerson(PeoplePermissions.BANK_ACCOUNT_UPDATE, personId, clock.today());
        Row r = row(personId, id);
        if (r.version() != version) {
            throw ApiException.conflict("stale-version", "The bank account was changed by someone else; reload it");
        }
        if (!"ACTIVE".equals(r.status())) {
            throw ApiException.conflict("bank-account-not-active", "Only an active account can be ended");
        }
        if (validTo == null || !validTo.isAfter(r.validFrom()) || (r.validTo() != null && !validTo.isBefore(r.validTo()))) {
            throw ApiException.unprocessable("invalid-valid-to",
                    "The end date must lie after the start and before the current end of the account");
        }
        if ("REMAINDER".equals(r.splitType())) {
            List<Row> others = rows(personId).stream().filter(x -> !x.id().equals(id)
                    && List.of("ACTIVE", "PENDING_VERIFICATION").contains(x.status())).toList();
            for (Row split : others.stream().filter(x -> !"REMAINDER".equals(x.splitType())
                    && "ACTIVE".equals(x.status()) && x.overlaps(validTo, null)).toList()) {
                LocalDate from = split.validFrom().isAfter(validTo) ? split.validFrom() : validTo;
                if (!covered(others.stream().filter(x -> "REMAINDER".equals(x.splitType())).toList(), from,
                        split.validTo())) {
                    throw ApiException.unprocessable("default-account-required", "Split accounts continue after "
                            + validTo + "; record a new default account before ending this one");
                }
            }
        }
        db.jdbc().sql("""
                        update hr.bank_account set valid_to = :to, updated_at = now(), updated_by = :actor,
                               version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("to", validTo).param("actor", actor()).param("t", tenant()).param("id", id).param("v", version)
                .update();
        Row closed = row(personId, id);
        publish(closed, BankEvents.CLOSED, validTo, false);
        return view(personId, id);
    }

    // ================================================================================================ ESS

    @HrTransactional(readOnly = true)
    public List<BankAccountView> listOwn(UUID selfPersonId) {
        return views(selfPersonId);
    }

    /**
     * ESS bank change (US-PPL-075): the web layer verified the step-up authentication and passes its evidence; the
     * request goes into HR approval. Only today or a future start date.
     */
    @HrTransactional
    public Saved requestOwn(UUID selfPersonId, Attributes a, String stepUpRef) {
        Objects.requireNonNull(stepUpRef, "stepUpRef");
        LocalDate from = a.validFrom() == null ? clock.today() : a.validFrom();
        if (from.isBefore(clock.today())) {
            throw ApiException.unprocessable("invalid-valid-from", "A bank change cannot start in the past");
        }
        return doRequest(selfPersonId, new Attributes(a.bankCode(), a.branchCode(), a.accountNo(), a.holderName(),
                a.currencyCode(), a.splitType(), a.splitValue(), a.priority(), from, a.validTo()), stepUpRef);
    }

    // ================================================================================================ internal

    /**
     * Opening-balance import (NFR-PPL-007, ADR-0037): an ACTIVE account in the caller's People transaction, without
     * approval; same validation. Publishes {@code BankAccountChanged} ACTIVATED with {@code initialLoad}.
     */
    @HrTransactional
    public UUID createForInitialLoad(UUID personId, Attributes a) {
        Attributes n = normalised(a);
        validate(personId, n);
        UUID id = insert(personId, n, "ACTIVE", null);
        publish(row(personId, id), BankEvents.ACTIVATED, n.validFrom(), true);
        return id;
    }

    /** {@link PayeeBankAccounts} for Payroll: ACTIVE accounts valid on {@code asOf}, decrypted. */
    @Override
    @HrTransactional(readOnly = true)
    public List<PayeeBankAccount> activeAccounts(UUID personId, LocalDate asOf) {
        return rows(personId).stream().filter(r -> "ACTIVE".equals(r.status()) && r.covers(asOf))
                .sorted(Comparator.comparing((Row r) -> "REMAINDER".equals(r.splitType()))
                        .thenComparing(Row::priority))
                .map(r -> new PayeeBankAccount(r.id(), r.bankCode(), r.branchCode(), decrypt(personId, r.id()),
                        r.holderName(), r.currencyCode(), r.splitType(), r.splitValue(), r.priority()))
                .toList();
    }

    /**
     * Applies the outcome of a {@code hr.BANK_ACCOUNT_CHANGE} approval (workflow listener). Idempotent.
     *
     * @param requesterUserId the user who submitted (notified when the request came from ESS)
     */
    @HrTransactional
    public Map<String, Object> applyOutcome(UUID accountId, String outcome, UUID requesterUserId) {
        Optional<Row> found = db.jdbc().sql(SELECT + " where tenant_id = :t and id = :id for update")
                .param("t", tenant()).param("id", accountId).query(BankAccountService::map).optional();
        if (found.isEmpty() || !"PENDING_VERIFICATION".equals(found.get().status())) {
            return Map.of("bankAccountStatus", found.map(Row::status).orElse("GONE"));
        }
        Row r = found.get();
        if (!"APPROVED".equals(outcome)) {
            db.jdbc().sql("""
                            update hr.bank_account set status = 'REJECTED', updated_at = now(), updated_by = :actor,
                                   version = version + 1 where tenant_id = :t and id = :id""")
                    .param("actor", actor()).param("t", tenant()).param("id", r.id()).update();
            notifyEmployee(r, requesterUserId, BankCatalogues.EVENT_CHANGE_REJECTED);
            return Map.of("bankAccountStatus", "REJECTED");
        }
        if ("REMAINDER".equals(r.splitType())) {
            for (Row old : rows(r.personId()).stream().filter(x -> "ACTIVE".equals(x.status())
                    && "REMAINDER".equals(x.splitType()) && x.overlaps(r.validFrom(), r.validTo())).toList()) {
                if (old.validFrom().isBefore(r.validFrom())) {
                    db.jdbc().sql("""
                                    update hr.bank_account set valid_to = :to, updated_at = now(), updated_by = :actor,
                                           version = version + 1 where tenant_id = :t and id = :id""")
                            .param("to", r.validFrom()).param("actor", actor()).param("t", tenant())
                            .param("id", old.id()).update();
                } else {
                    db.jdbc().sql("""
                                    update hr.bank_account set status = 'CLOSED', updated_at = now(), updated_by = :actor,
                                           version = version + 1 where tenant_id = :t and id = :id""")
                            .param("actor", actor()).param("t", tenant()).param("id", old.id()).update();
                }
                publish(row(r.personId(), old.id()), BankEvents.CLOSED, r.validFrom(), false);
            }
        }
        db.jdbc().sql("""
                        update hr.bank_account set status = 'ACTIVE', verified_by = :actor, verified_at = now(),
                               updated_at = now(), updated_by = :actor, version = version + 1
                         where tenant_id = :t and id = :id""")
                .param("actor", actor()).param("t", tenant()).param("id", r.id()).update();
        Row active = row(r.personId(), r.id());
        publish(active, BankEvents.ACTIVATED, active.validFrom(), false);
        notifyEmployee(active, requesterUserId, BankCatalogues.EVENT_CHANGED);
        return Map.of("bankAccountStatus", "ACTIVE");
    }

    // ================================================================================================ flow

    private Saved doRequest(UUID personId, Attributes raw, String stepUpRef) {
        Attributes a = normalised(raw);
        validate(personId, a);
        List<Warning> warnings = warnings(personId, a);
        UUID id = insert(personId, a, "PENDING_VERIFICATION", stepUpRef);
        startApproval(personId, id, a, stepUpRef != null);
        return new Saved(view(personId, id), warnings);
    }

    private void startApproval(UUID personId, UUID accountId, Attributes a, boolean ess) {
        // the person's current employment drives the approver resolution (HR of its legal entity)
        Optional<UUID[]> org = db.jdbc().sql("select e.legal_entity_id, pa.org_unit_id, pa.location_id from "
                        + EmployeeAccess.EMPLOYMENT_SCOPE_JOIN + " " + """
                         where e.tenant_id = :t and e.person_id = :p
                         order by (e.status in ('PRE_HIRE','ACTIVE','SUSPENDED')) desc, e.hire_date desc limit 1""")
                .param("t", tenant()).param("p", personId).param("asOf", clock.today())
                .query((rs, n) -> new UUID[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)}).optional();
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("bankCode", a.bankCode());
        summary.put("splitType", a.splitType());
        summary.put("validFrom", a.validFrom().toString());
        summary.put("channel", ess ? "ESS" : "HR");
        workflow.start(new StartRequest(BankCatalogues.PROCESS_BANK_ACCOUNT_CHANGE, BankCatalogues.SUBJECT_TYPE,
                accountId, 0, "bank-account:" + accountId, null, personId, org.map(o -> o[0]).orElse(null),
                org.map(o -> o[1]).orElse(null), org.map(o -> o[2]).orElse(null), null, summary,
                Map.of("channel", ess ? "ESS" : "HR", "splitType", a.splitType()), Map.of()));
    }

    private Attributes normalised(Attributes a) {
        String type = a.splitType() == null ? "REMAINDER" : a.splitType().toUpperCase(Locale.ROOT);
        return new Attributes(a.bankCode() == null ? null : a.bankCode().strip(),
                a.branchCode() == null || a.branchCode().isBlank() ? null : a.branchCode().strip(),
                a.accountNo() == null || a.accountNo().isBlank() ? null : PiiCipher.normalise(a.accountNo()),
                a.holderName() == null ? null : a.holderName().strip().replaceAll("\\s+", " "),
                a.currencyCode() == null ? "VND" : a.currencyCode().toUpperCase(Locale.ROOT), type, a.splitValue(),
                a.priority() == null ? 1 : a.priority(), a.validFrom(), a.validTo());
    }

    private void validate(UUID personId, Attributes a) {
        boolean personExists = db.jdbc().sql("select exists (select 1 from hr.person where tenant_id = :t and id = :p)")
                .param("t", tenant()).param("p", personId).query(Boolean.class).single();
        if (!personExists) {
            throw ApiException.notFound("Person", personId);
        }
        if (a.bankCode() == null || !db.jdbc().sql("select exists (select 1 from core.bank where bank_code = :c and is_active)")
                .param("c", a.bankCode()).query(Boolean.class).single()) {
            throw ApiException.unprocessable("unknown-bank", "Bank " + a.bankCode() + " is not in the bank master data");
        }
        if (a.branchCode() != null && !db.jdbc().sql("""
                        select exists (select 1 from core.bank_branch where bank_code = :c and branch_code = :b and is_active)""")
                .param("c", a.bankCode()).param("b", a.branchCode()).query(Boolean.class).single()) {
            throw ApiException.unprocessable("unknown-branch", "Branch " + a.branchCode() + " of bank " + a.bankCode()
                    + " is not in the bank master data");
        }
        if (a.accountNo() == null || !ACCOUNT_NO.matcher(a.accountNo()).matches()) {
            throw ApiException.unprocessable("invalid-account-no", "The account number has an invalid format");
        }
        if (a.holderName() == null || a.holderName().isBlank() || a.holderName().length() > 200) {
            throw ApiException.unprocessable("invalid-holder-name", "The account holder name is required");
        }
        if (!"VND".equals(a.currencyCode())) {
            throw ApiException.unprocessable("currency-not-supported", "Salary accounts are in VND only (Phase 1)");
        }
        if (!SPLIT_TYPES.contains(a.splitType())) {
            throw ApiException.unprocessable("invalid-split-type", "The split type is PERCENT, FIXED or REMAINDER");
        }
        switch (a.splitType()) {
            case "REMAINDER" -> {
                if (a.splitValue() != null) {
                    throw ApiException.unprocessable("invalid-split-value", "The default account takes the remainder");
                }
            }
            case "PERCENT" -> {
                if (a.splitValue() == null || a.splitValue().signum() <= 0 || a.splitValue().compareTo(HUNDRED) > 0) {
                    throw ApiException.unprocessable("invalid-split-value", "A percentage lies in (0, 100]");
                }
            }
            default -> {
                if (a.splitValue() == null || a.splitValue().signum() <= 0) {
                    throw ApiException.unprocessable("invalid-split-value", "A fixed amount is positive");
                }
            }
        }
        if (a.priority() < 1 || a.priority() > 99) {
            throw ApiException.unprocessable("invalid-priority", "The priority lies in 1..99");
        }
        if (a.validFrom() == null || (a.validTo() != null && !a.validTo().isAfter(a.validFrom()))) {
            throw ApiException.unprocessable("invalid-period", "validFrom is required and before validTo");
        }
        List<Row> live = rows(personId).stream()
                .filter(x -> List.of("ACTIVE", "PENDING_VERIFICATION").contains(x.status())).toList();
        byte[] bidx = cipher.blindIndex(tenant(), a.accountNo());
        boolean duplicate = db.jdbc().sql("""
                        select exists (select 1 from hr.bank_account
                                        where tenant_id = :t and person_id = :p and account_no_bidx = :b
                                          and status in ('ACTIVE','PENDING_VERIFICATION')
                                          and valid_during && daterange(cast(:f as date), cast(:to as date), '[)'))""")
                .param("t", tenant()).param("p", personId).param("b", bidx).param("f", a.validFrom())
                .param("to", a.validTo()).query(Boolean.class).single();
        if (duplicate) {
            throw ApiException.conflict("bank-account-duplicate",
                    "This account is already registered (or pending) for the employee in this period");
        }
        if (!"REMAINDER".equals(a.splitType())
                && !covered(live.stream().filter(x -> "REMAINDER".equals(x.splitType())).toList(), a.validFrom(),
                        a.validTo())) {
            throw ApiException.unprocessable("default-account-required", "A split account needs a default (REMAINDER) "
                    + "account covering its whole period");
        }
        if ("PERCENT".equals(a.splitType())) {
            TreeSet<LocalDate> points = new TreeSet<>();
            points.add(a.validFrom());
            for (Row x : live) {
                if (x.overlaps(a.validFrom(), a.validTo())) {
                    if (x.validFrom().isAfter(a.validFrom())) {
                        points.add(x.validFrom());
                    }
                    if (x.validTo() != null && (a.validTo() == null || x.validTo().isBefore(a.validTo()))) {
                        points.add(x.validTo());
                    }
                }
            }
            for (LocalDate d : points) {
                BigDecimal total = a.splitValue();
                for (Row x : live) {
                    if ("PERCENT".equals(x.splitType()) && x.covers(d)) {
                        total = total.add(x.splitValue());
                    }
                }
                if (total.compareTo(HUNDRED) > 0) {
                    throw ApiException.unprocessable("split-exceeds-100", "The percentage splits exceed 100% on " + d,
                            Map.of("date", d.toString(), "totalPercent", total));
                }
            }
        }
    }

    /** Is [from, to) covered by the union of {@code accounts}' periods? */
    private static boolean covered(List<Row> accounts, LocalDate from, LocalDate to) {
        LocalDate cursor = from;
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Row r : accounts) {
                if (r.covers(cursor)) {
                    if (r.validTo() == null) {
                        return true;
                    }
                    cursor = r.validTo();
                    progress = true;
                    if (to != null && !cursor.isBefore(to)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<Warning> warnings(UUID personId, Attributes a) {
        List<Warning> out = new ArrayList<>();
        List<String> names = db.jdbc().sql("""
                        select full_name, latin_name from hr.person where tenant_id = :t and id = :p""")
                .param("t", tenant()).param("p", personId)
                .query((rs, n) -> {
                    List<String> l = new ArrayList<>();
                    l.add(rs.getString(1));
                    if (rs.getString(2) != null) {
                        l.add(rs.getString(2));
                    }
                    return l;
                }).single();
        String holder = foldName(a.holderName());
        if (names.stream().map(BankAccountService::foldName).noneMatch(holder::equals)) {
            out.add(new Warning("holder-name-mismatch",
                    "The account holder name differs from the employee's name (compared without diacritics)"));
        }
        boolean shared = db.jdbc().sql("""
                        select exists (select 1 from hr.bank_account
                                        where tenant_id = :t and person_id <> :p and account_no_bidx = :b
                                          and status in ('ACTIVE','PENDING_VERIFICATION'))""")
                .param("t", tenant()).param("p", personId).param("b", cipher.blindIndex(tenant(), a.accountNo()))
                .query(Boolean.class).single();
        if (shared) {
            out.add(new Warning("account-shared",
                    "The same account number is registered for another employee of the company"));
        }
        return out;
    }

    /** Upper case, without diacritics (đ → D), single spaces: "Nguyễn  Thị Ánh" = "NGUYEN THI ANH". */
    static String foldName(String name) {
        String s = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
    }

    private UUID insert(UUID personId, Attributes a, String status, String stepUpRef) {
        UUID id = Ids.newId();
        Sealed sealed = cipher.encrypt(Subject.person(tenant(), personId), "account_no", a.accountNo());
        try {
            db.jdbc().sql("""
                            insert into hr.bank_account (tenant_id, id, person_id, bank_code, branch_code, account_no_enc,
                                   account_no_bidx, account_no_last4, holder_name, currency_code, split_type, split_value,
                                   priority, valid_from, valid_to, status, step_up_auth_ref, verified_by, verified_at,
                                   created_by, updated_by)
                            values (:t, :id, :p, :bank, :branch, :enc, :bidx, :last4, :holder, :cur, :type, :value,
                                    :prio, :from, :to, :status, :stepUp, :verBy,
                                    case when :active then now() end, :actor, :actor)""")
                    .param("t", tenant()).param("id", id).param("p", personId).param("bank", a.bankCode())
                    .param("branch", a.branchCode()).param("enc", sealed.ciphertext())
                    .param("bidx", cipher.blindIndex(tenant(), a.accountNo()))
                    .param("last4", PiiCipher.last4(a.accountNo())).param("holder", a.holderName())
                    .param("cur", a.currencyCode()).param("type", a.splitType()).param("value", a.splitValue())
                    .param("prio", a.priority()).param("from", a.validFrom()).param("to", a.validTo())
                    .param("status", status).param("stepUp", stepUpRef)
                    .param("verBy", "ACTIVE".equals(status) ? actor() : null)
                    .param("active", "ACTIVE".equals(status)).param("actor", actor())
                    .update();
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("default-account-overlap",
                    "Another active default account covers this period");
        }
        return id;
    }

    private void publish(Row r, String subtype, LocalDate effective, boolean initialLoad) {
        outbox.publish(new BankAccountChanged(tenant(), null, r.personId(), null, effective, subtype, r.id(),
                r.bankCode(), r.branchCode(), r.last4(), r.currencyCode(), r.splitType(), r.splitValue(),
                r.priority(), r.validFrom(), r.validTo(), r.status(), initialLoad));
    }

    private void notifyEmployee(Row r, UUID requesterUserId, String eventCode) {
        if (r.stepUpRef() == null || requesterUserId == null) {
            return; // HR-entered change: Core has no user ↔ person lookup for People yet (reported)
        }
        String bankName = db.jdbc().sql("select short_name from core.bank where bank_code = :c")
                .param("c", r.bankCode()).query(String.class).optional().orElse(r.bankCode());
        notifications.send(new SendRequest(eventCode, "hr", Recipients.users(requesterUserId),
                Map.of("bankName", bankName, "validFrom", VN_DATE.format(r.validFrom())), "hr.bank_account", r.id(),
                "/ess/bank-accounts", eventCode + ":" + r.id(), null));
    }

    private String decrypt(UUID personId, UUID id) {
        return db.jdbc().sql("""
                        select b.account_no_enc, p.subject_key_version from hr.bank_account b
                          join hr.person p on p.tenant_id = b.tenant_id and p.id = b.person_id
                         where b.tenant_id = :t and b.id = :id and b.person_id = :p""")
                .param("t", tenant()).param("id", id).param("p", personId)
                .query((rs, n) -> cipher.decrypt(Subject.person(tenant(), personId), "account_no", rs.getBytes(1),
                        rs.getInt(2)))
                .optional().orElseThrow(() -> ApiException.notFound("Bank account", id));
    }

    // ================================================================================================ data

    private static final String SELECT = """
            select id, person_id, bank_code, branch_code, account_no_last4, holder_name, currency_code, split_type,
                   split_value, priority, valid_from, valid_to, status, step_up_auth_ref, verified_at, version
              from hr.bank_account""";

    private static Row map(ResultSet rs, int n) throws SQLException {
        OffsetDateTime verified = rs.getObject(15, OffsetDateTime.class);
        return new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getInt(10),
                rs.getObject(11, LocalDate.class), rs.getObject(12, LocalDate.class), rs.getString(13),
                rs.getString(14), verified == null ? null : verified.toInstant(), rs.getInt(16));
    }

    private List<Row> rows(UUID personId) {
        return db.jdbc().sql(SELECT + " where tenant_id = :t and person_id = :p order by valid_from, priority")
                .param("t", tenant()).param("p", personId).query(BankAccountService::map).list();
    }

    private Row row(UUID personId, UUID id) {
        return db.jdbc().sql(SELECT + " where tenant_id = :t and person_id = :p and id = :id")
                .param("t", tenant()).param("p", personId).param("id", id).query(BankAccountService::map).optional()
                .orElseThrow(() -> ApiException.notFound("Bank account", id));
    }

    /** Through the masking view (account number masked; ciphertext never selected). */
    private List<BankAccountView> views(UUID personId) {
        return db.jdbc().sql("""
                        select v.id, v.person_id, v.bank_code, bk.short_name, v.branch_code, v.account_no_masked,
                               v.holder_name, v.currency_code, v.split_type, v.split_value, v.priority, v.valid_from,
                               v.valid_to, v.status, b.step_up_auth_ref is not null, b.verified_at, b.version
                          from hr.v_bank_account_masked v
                          join hr.bank_account b on b.tenant_id = v.tenant_id and b.id = v.id
                          left join core.bank bk on bk.bank_code = v.bank_code
                         where v.tenant_id = :t and v.person_id = :p
                         order by v.valid_from desc, v.priority""")
                .param("t", tenant()).param("p", personId)
                .query((rs, n) -> {
                    OffsetDateTime verified = rs.getObject(16, OffsetDateTime.class);
                    return new BankAccountView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getString(8), rs.getString(9), rs.getBigDecimal(10), rs.getInt(11),
                            rs.getObject(12, LocalDate.class), rs.getObject(13, LocalDate.class), rs.getString(14),
                            rs.getBoolean(15), verified == null ? null : verified.toInstant(), rs.getInt(17));
                }).list();
    }

    private BankAccountView view(UUID personId, UUID id) {
        return views(personId).stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Bank account", id));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }

    private static UUID actor() {
        return TenantContext.current().userAccountId();
    }
}
