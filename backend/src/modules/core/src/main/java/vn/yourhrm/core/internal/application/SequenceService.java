package vn.yourhrm.core.internal.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.core.api.Numbering;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * The numbering service (BR-CORE-085, US-CORE-086). Counters live in {@code core.sequence_counter} and are taken by
 * {@code core.next_sequence_value} (INSERT .. ON CONFLICT DO UPDATE): in a gap-free series the counter row stays
 * locked until the business transaction ends, so concurrent callers queue, never collide, and a rollback returns the
 * number. Non-gap-free series take the number in a short transaction of their own.
 *
 * <p>Pattern tokens: {@code {SEQ:n}} (exactly once; zero-padded to n digits), {@code {YYYY}}, {@code {YY}},
 * {@code {MM}}, {@code {LE}} (legal entity code); everything else is literal text.
 */
@Service
public class SequenceService implements Numbering {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z]+)(?::(\\d+))?}");
    private static final List<String> TOKENS = List.of("SEQ", "YYYY", "YY", "MM", "LE");

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final TransactionTemplate ownTx;

    public SequenceService(JdbcClient jdbc, BusinessClock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownTx = new TransactionTemplate(transactionManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record SequenceView(UUID id, String code, String name, String scopeLevel, String pattern, String resetRule,
            boolean gapFree, long startValue, int incrementBy, String status, int version) {
    }

    public record SequenceCommand(String name, String scopeLevel, String pattern, String resetRule, Boolean gapFree,
            Long startValue, Integer incrementBy, String status) {
    }

    // ------------------------------------------------------------------------------------ numbering

    @Override
    @Transactional
    public String next(String sequenceCode, UUID legalEntityId, LocalDate onDate) {
        SequenceView s = active(sequenceCode);
        LocalDate date = onDate == null ? clock.today() : onDate;
        String leCode = legalEntityCode(s, legalEntityId);
        long value = s.gapFree() ? take(s, legalEntityId, date) : ownTx.execute(t -> take(s, legalEntityId, date));
        return format(s.pattern(), value, date, leCode);
    }

    /** The number the next call would produce, without consuming it (admin preview, US-CORE-086). */
    @Transactional(readOnly = true)
    public String preview(String sequenceCode, UUID legalEntityId, LocalDate onDate) {
        SequenceView s = active(sequenceCode);
        LocalDate date = onDate == null ? clock.today() : onDate;
        String leCode = legalEntityCode(s, legalEntityId);
        Long current = jdbc.sql("""
                        select current_value from core.sequence_counter
                         where tenant_id = :t and sequence_definition_id = :d
                           and legal_entity_id is not distinct from :le and period_key = :p""")
                .param("t", tenant()).param("d", s.id())
                .param("le", "LEGAL_ENTITY".equals(s.scopeLevel()) ? legalEntityId : null)
                .param("p", period(s.resetRule(), date))
                .query(Long.class).optional().orElse(null);
        long next = current == null ? s.startValue() : current + s.incrementBy();
        return format(s.pattern(), next, date, leCode);
    }

    private long take(SequenceView s, UUID legalEntityId, LocalDate date) {
        return jdbc.sql("select core.next_sequence_value(:c, :le, :d)")
                .param("c", s.code()).param("le", legalEntityId).param("d", date).query(Long.class).single();
    }

    static String format(String pattern, long value, LocalDate date, String leCode) {
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String text = switch (m.group(1)) {
                case "SEQ" -> {
                    String digits = Long.toString(value);
                    int width = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                    yield digits.length() >= width ? digits : "0".repeat(width - digits.length()) + digits;
                }
                case "YYYY" -> String.format("%04d", date.getYear());
                case "YY" -> String.format("%02d", date.getYear() % 100);
                case "MM" -> String.format("%02d", date.getMonthValue());
                case "LE" -> leCode;
                default -> throw new IllegalStateException("Unknown token " + m.group());
            };
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String period(String reset, LocalDate date) {
        return switch (reset) {
            case "YEARLY" -> String.format("%04d", date.getYear());
            case "MONTHLY" -> String.format("%04d-%02d", date.getYear(), date.getMonthValue());
            default -> "-";
        };
    }

    private String legalEntityCode(SequenceView s, UUID legalEntityId) {
        boolean needsLe = "LEGAL_ENTITY".equals(s.scopeLevel()) || s.pattern().contains("{LE}");
        if (!needsLe) {
            return null;
        }
        if (legalEntityId == null) {
            throw ApiException.unprocessable("legal-entity-required", "Series " + s.code() + " needs a legal entity");
        }
        return jdbc.sql("select code from core.legal_entity where tenant_id = :t and id = :id")
                .param("t", tenant()).param("id", legalEntityId).query(String.class).optional()
                .orElseThrow(() -> ApiException.unprocessable("unknown-legal-entity",
                        "Legal entity " + legalEntityId + " not found"));
    }

    // ------------------------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public List<SequenceView> list() {
        return jdbc.sql(SELECT + " where tenant_id = :t order by code").param("t", tenant())
                .query((rs, n) -> view(rs)).list();
    }

    @Transactional
    public SequenceView create(String code, SequenceCommand c) {
        UUID tenant = tenant();
        if (find(code).isPresent()) {
            throw ApiException.conflict("sequence-code-taken", "Series " + code + " already exists");
        }
        validate(c);
        jdbc.sql("""
                        insert into core.sequence_definition (tenant_id, id, code, name, scope_level, pattern, reset_rule,
                               is_gap_free, start_value, increment_by, created_by, updated_by)
                        values (:t, :id, :code, :name, :scope, :pattern, :reset, :gap, :start, :inc, :a, :a)""")
                .param("t", tenant).param("id", Ids.newId()).param("code", code).param("name", c.name())
                .param("scope", c.scopeLevel() == null ? "TENANT" : c.scopeLevel()).param("pattern", c.pattern())
                .param("reset", c.resetRule() == null ? "NEVER" : c.resetRule())
                .param("gap", c.gapFree() == null || c.gapFree())
                .param("start", c.startValue() == null ? 1L : c.startValue())
                .param("inc", c.incrementBy() == null ? 1 : c.incrementBy())
                .param("a", TenantContext.current().userAccountId())
                .update();
        return find(code).orElseThrow();
    }

    /** Name, pattern, reset rule, gap-free mode and status can change; code, scope and start value cannot. */
    @Transactional
    public SequenceView update(String code, SequenceCommand c, int expectedVersion) {
        SequenceView s = find(code).orElseThrow(() -> ApiException.notFound("Number series", code));
        validate(new SequenceCommand(c.name(), s.scopeLevel(), c.pattern(), c.resetRule(), c.gapFree(), null,
                c.incrementBy(), c.status()));
        int updated = jdbc.sql("""
                        update core.sequence_definition
                           set name = :name, pattern = :pattern, reset_rule = :reset, is_gap_free = :gap,
                               increment_by = :inc, status = :status, updated_by = :a, version = version + 1
                         where tenant_id = :t and id = :id and version = :v""")
                .param("name", c.name()).param("pattern", c.pattern()).param("reset", c.resetRule())
                .param("gap", c.gapFree() == null || c.gapFree())
                .param("inc", c.incrementBy() == null ? s.incrementBy() : c.incrementBy())
                .param("status", c.status() == null ? s.status() : c.status())
                .param("a", TenantContext.current().userAccountId()).param("t", tenant()).param("id", s.id())
                .param("v", expectedVersion)
                .update();
        if (updated == 0) {
            throw ApiException.conflict("stale-version", "Series " + code + " was changed; reload and retry");
        }
        return find(code).orElseThrow();
    }

    static void validate(SequenceCommand c) {
        if (c.name() == null || c.name().isBlank() || c.pattern() == null) {
            throw ApiException.unprocessable("invalid-sequence", "A name and a pattern are required");
        }
        Matcher m = TOKEN.matcher(c.pattern());
        int seq = 0;
        while (m.find()) {
            if (!TOKENS.contains(m.group(1))) {
                throw ApiException.unprocessable("invalid-pattern", "Unknown token " + m.group()
                        + "; use {SEQ:n}, {YYYY}, {YY}, {MM}, {LE}");
            }
            if ("SEQ".equals(m.group(1))) {
                seq++;
                if (m.group(2) == null || Integer.parseInt(m.group(2)) < 1 || Integer.parseInt(m.group(2)) > 12) {
                    throw ApiException.unprocessable("invalid-pattern", "{SEQ:n} needs a width between 1 and 12");
                }
            }
        }
        if (seq != 1) {
            throw ApiException.unprocessable("invalid-pattern", "The pattern needs exactly one {SEQ:n}");
        }
        if (c.scopeLevel() != null && !List.of("TENANT", "LEGAL_ENTITY").contains(c.scopeLevel())) {
            throw ApiException.unprocessable("invalid-sequence", "Scope must be TENANT or LEGAL_ENTITY");
        }
        if (c.resetRule() != null && !List.of("NEVER", "YEARLY", "MONTHLY").contains(c.resetRule())) {
            throw ApiException.unprocessable("invalid-sequence", "Reset rule must be NEVER, YEARLY or MONTHLY");
        }
        if ((c.incrementBy() != null && c.incrementBy() < 1) || (c.startValue() != null && c.startValue() < 0)) {
            throw ApiException.unprocessable("invalid-sequence", "Increment must be positive and start non-negative");
        }
    }

    private SequenceView active(String code) {
        SequenceView s = find(code).orElseThrow(() -> ApiException.unprocessable("unknown-sequence",
                "Number series " + code + " is not defined"));
        if (!"ACTIVE".equals(s.status())) {
            throw ApiException.unprocessable("unknown-sequence", "Number series " + code + " is inactive");
        }
        return s;
    }

    private Optional<SequenceView> find(String code) {
        return jdbc.sql(SELECT + " where tenant_id = :t and code = :c").param("t", tenant()).param("c", code)
                .query((rs, n) -> view(rs)).optional();
    }

    private static final String SELECT = """
            select id, code, name, scope_level, pattern, reset_rule, is_gap_free, start_value, increment_by, status,
                   version from core.sequence_definition""";

    private static SequenceView view(ResultSet rs) throws SQLException {
        return new SequenceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getLong(8), rs.getInt(9), rs.getString(10),
                rs.getInt(11));
    }

    private static UUID tenant() {
        return TenantContext.current().tenantId();
    }
}
