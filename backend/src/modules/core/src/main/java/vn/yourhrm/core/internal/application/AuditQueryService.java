package vn.yourhrm.core.internal.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.infra.AuditWriter;
import vn.yourhrm.core.internal.infra.CoreJson;
import vn.yourhrm.shared.auth.api.AuthorizationService;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Audit search and chain verification (BR-CORE-092/093, US-CORE-092/093). Every search and verification is itself
 * audited. Values of columns classified {@code pii:P2} / {@code pii:P3} (the column comments of the DDL) are shown
 * only to holders of {@link CorePermissions#AUDIT_VIEW_SENSITIVE}; encrypted columns never had a value to show.
 */
@Service
public class AuditQueryService {

    /** BR-CORE-091 events that the security filter returns besides the explicit security events. */
    private static final Set<String> SECURITY_OBJECTS = Set.of(AuditLogService.SECURITY_OBJECT, "role",
            "role_permission", "role_assignment", "http_request");
    private static final Pattern PII = Pattern.compile("pii:(P[0-3])");
    private static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final int MAX_PAGE = 200;
    static final String MASKED = "<masked>";

    public record Filter(Instant from, Instant to, UUID actorId, String module, String objectType, UUID objectId,
            String action, UUID legalEntityId, String channel, String clientIp, String correlationId,
            boolean securityOnly) {
    }

    public record AuditEventView(UUID id, Instant occurredAt, LocalDateTime occurredAtLocal, String actorType,
            UUID actorId, String actorLogin, UUID onBehalfOfId, UUID operatorId, UUID supportGrantId, String action,
            String module, String objectType, UUID objectId, UUID legalEntityId, String channel, String clientIp,
            String userAgent, String correlationId, String requestId, String purpose, boolean sensitiveRead,
            Long chainSeq, Map<String, Object> change, boolean masked) {
    }

    public record Page(List<AuditEventView> items, int page, int size, boolean hasMore, Instant from, Instant to) {
    }

    /** {@code firstBrokenSeq} null = the sealed range is intact; anchors whose head hash differs are listed. */
    public record ChainStatus(long lastSealedSeq, long unsealedCount, Instant oldestUnsealedAt, Long firstBrokenSeq,
            List<String> anchorMismatches, int anchorsChecked, boolean intact) {
    }

    private final JdbcClient jdbc;
    private final AuthorizationService authorization;
    private final AuditWriter audit;
    private final CoreJson json;
    private final BusinessClock clock;
    private final Map<String, Map<String, String>> classification = new ConcurrentHashMap<>();

    public AuditQueryService(JdbcClient jdbc, AuthorizationService authorization, AuditWriter audit, CoreJson json,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** Read-write: the search itself is appended to the audit (BR-CORE-093). */
    @Transactional
    public Page search(Filter f, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE) {
            throw ApiException.unprocessable("invalid-page", "page >= 0 and 1 <= size <= " + MAX_PAGE);
        }
        Instant to = f.to() != null ? f.to() : clock.now();
        // a record's or a person's history spans its whole life; open searches are bounded to keep them indexed
        boolean pinpoint = f.objectId() != null || f.actorId() != null || f.correlationId() != null;
        Instant from = f.from() != null ? f.from() : to.minus(pinpoint ? Duration.ofDays(3660) : Duration.ofDays(30));
        if (!from.isBefore(to)) {
            throw ApiException.unprocessable("invalid-range", "from must be before to");
        }
        if (!pinpoint && Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.unprocessable("range-too-long",
                    "Without a record, user or correlation id the range is at most 366 days");
        }
        if (f.channel() != null && !Set.of("UI", "API", "IMPORT", "SYSTEM").contains(f.channel())) {
            throw ApiException.unprocessable("invalid-channel", "channel is UI, API, IMPORT or SYSTEM");
        }
        StringBuilder where = new StringBuilder("e.tenant_id = :tenant and e.occurred_at >= :from and e.occurred_at < :to");
        Map<String, Object> params = new HashMap<>();
        params.put("tenant", TenantContext.current().tenantId());
        params.put("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        params.put("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        if (f.actorId() != null) {
            where.append(" and e.actor_id = :actor");
            params.put("actor", f.actorId());
        }
        if (f.module() != null) {
            where.append(" and e.object_schema = :module");
            params.put("module", f.module());
        }
        if (f.objectType() != null) {
            where.append(" and e.object_type = :objectType");
            params.put("objectType", f.objectType());
        }
        if (f.objectId() != null) {
            where.append(" and e.object_id = :objectId");
            params.put("objectId", f.objectId());
        }
        if (f.action() != null) {
            where.append(" and e.action = :action");
            params.put("action", f.action());
        }
        if (f.legalEntityId() != null) {
            where.append(" and e.legal_entity_id = :le");
            params.put("le", f.legalEntityId());
        }
        if (f.channel() != null) {
            where.append(" and e.channel = :channel");
            params.put("channel", f.channel());
        }
        if (f.clientIp() != null) {
            where.append(" and e.client_ip = cast(:ip as inet)");
            params.put("ip", f.clientIp());
        }
        if (f.correlationId() != null) {
            where.append(" and e.correlation_id = :corr");
            params.put("corr", f.correlationId());
        }
        if (f.securityOnly()) {
            where.append(" and (e.object_type = any(:secObjects) or e.is_sensitive_read or e.operator_id is not null)");
            params.put("secObjects", SECURITY_OBJECTS.toArray(String[]::new));
        }
        params.put("limit", size + 1);
        params.put("offset", page * size);
        boolean sensitive = authorization.hasTenantWidePermission(CorePermissions.AUDIT_VIEW_SENSITIVE);
        List<AuditEventView> rows;
        try {
            rows = jdbc.sql("""
                    select e.id, e.occurred_at, e.actor_type, e.actor_id, u.login, e.on_behalf_of_id, e.operator_id,
                           e.support_grant_id, e.action, e.object_schema, e.object_type, e.object_id,
                           e.legal_entity_id, e.channel, host(e.client_ip) as ip, e.user_agent, e.correlation_id,
                           e.request_id, e.purpose, e.is_sensitive_read, e.chain_seq, e.change::text as change
                      from audit.audit_event e
                      left join core.user_account u on u.tenant_id = e.tenant_id and u.id = e.actor_id
                     where %s
                     order by e.occurred_at desc, e.id desc
                     limit :limit offset :offset""".formatted(where))
                    .params(params)
                    .query((rs, n) -> {
                        Instant at = rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
                        String schema = rs.getString("object_schema");
                        String type = rs.getString("object_type");
                        Map<String, Object> change = json.map(rs.getString("change"));
                        boolean masked = false;
                        if (!sensitive && schema != null && type != null) {
                            Map<String, Object> m = mask(schema, type, change);
                            masked = m != change;
                            change = m;
                        }
                        return new AuditEventView(rs.getObject("id", UUID.class), at,
                                LocalDateTime.ofInstant(at, BusinessClock.VIETNAM), rs.getString("actor_type"),
                                rs.getObject("actor_id", UUID.class), rs.getString("login"),
                                rs.getObject("on_behalf_of_id", UUID.class), rs.getObject("operator_id", UUID.class),
                                rs.getObject("support_grant_id", UUID.class), rs.getString("action"), schema, type,
                                rs.getObject("object_id", UUID.class), rs.getObject("legal_entity_id", UUID.class),
                                rs.getString("channel"), rs.getString("ip"), rs.getString("user_agent"),
                                rs.getString("correlation_id"), rs.getString("request_id"), rs.getString("purpose"),
                                rs.getBoolean("is_sensitive_read"), (Long) rs.getObject("chain_seq"), change, masked);
                    })
                    .list();
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            throw ApiException.unprocessable("invalid-filter", "A filter value is malformed (e.g. the client address)");
        }
        boolean hasMore = rows.size() > size;
        List<AuditEventView> items = hasMore ? rows.subList(0, size) : rows;
        Map<String, String> details = new LinkedHashMap<>();
        details.put("from", from.toString());
        details.put("to", to.toString());
        params.forEach((k, v) -> {
            if (!Set.of("tenant", "from", "to", "limit", "offset", "secObjects").contains(k)) {
                details.put(k, String.valueOf(v));
            }
        });
        details.put("securityOnly", String.valueOf(f.securityOnly()));
        details.put("results", String.valueOf(items.size()));
        details.put("sensitiveValues", String.valueOf(sensitive));
        audit.append(AuditWriter.Event.ofCurrentActor("AUDIT_SEARCH", "core", "audit_event", null, null, details,
                sensitive));
        return new Page(List.copyOf(items), page, size, hasMore, from, to);
    }

    /**
     * Chain status and verification (BR-CORE-092, US-CORE-092): recomputes the hash chain of the sealed events
     * (gaps = deleted rows, changed rows, reordering) and compares the daily anchors with the chain.
     */
    @Transactional
    public ChainStatus verify(Long fromSeq, Long toSeq) {
        UUID tenant = TenantContext.current().tenantId();
        long last = jdbc.sql("select coalesce(max(chain_seq), 0) from audit.audit_event where tenant_id = :t")
                .param("t", tenant).query(Long.class).single();
        long from = fromSeq == null ? 1 : fromSeq;
        long to = toSeq == null ? last : Math.min(toSeq, last);
        if (from < 1 || from > Math.max(to, 1)) {
            throw ApiException.unprocessable("invalid-range", "1 <= from <= to <= " + last);
        }
        // verify_chain returns null when the range is intact
        Long broken = last == 0 ? null : jdbc.sql("select audit.verify_chain(:t, :from, :to)")
                .param("t", tenant).param("from", from).param("to", to)
                .query((rs, n) -> Optional.ofNullable((Long) rs.getObject(1))).single().orElse(null);
        List<String> anchorMismatches = new ArrayList<>();
        int[] checked = {0};
        jdbc.sql("""
                select a.anchor_date, a.last_seq, (a.head_hash = e.row_hash) as ok
                  from audit.chain_anchor a
                  left join audit.audit_event e on e.tenant_id = a.tenant_id and e.chain_seq = a.last_seq
                 where a.tenant_id = :t and a.last_seq > 0
                 order by a.anchor_date""")
                .param("t", tenant)
                .query((rs, n) -> {
                    checked[0]++;
                    if (!Boolean.TRUE.equals(rs.getObject("ok"))) {
                        anchorMismatches.add(rs.getString("anchor_date") + "@" + rs.getLong("last_seq"));
                    }
                    return null;
                }).list();
        record Unsealed(long count, Instant oldest) {
        }
        Unsealed unsealed = jdbc.sql("""
                select count(*), min(occurred_at) from audit.audit_event
                 where tenant_id = :t and sealed_at is null""")
                .param("t", tenant)
                .query((rs, n) -> new Unsealed(rs.getLong(1), rs.getObject(2) == null ? null
                        : rs.getObject(2, OffsetDateTime.class).toInstant()))
                .single();
        boolean intact = broken == null && anchorMismatches.isEmpty();
        audit.append(AuditWriter.Event.ofCurrentActor("AUDIT_VERIFY", "core", "audit_event", null, null, Map.of(
                "from", String.valueOf(from), "to", String.valueOf(to), "intact", String.valueOf(intact),
                "firstBrokenSeq", broken == null ? "" : broken.toString()), false));
        return new ChainStatus(last, unsealed.count(), unsealed.oldest(), broken, List.copyOf(anchorMismatches),
                checked[0], intact);
    }

    /** Replaces the old / new values of P2 / P3 columns; returns {@code change} itself when nothing was masked. */
    private Map<String, Object> mask(String schema, String table, Map<String, Object> change) {
        Map<String, String> classes = classification.computeIfAbsent(schema + "." + table, k -> classify(schema, table));
        Map<String, Object> out = null;
        for (var e : change.entrySet()) {
            String pii = classes.get(e.getKey());
            if (("P2".equals(pii) || "P3".equals(pii)) && e.getValue() instanceof Map<?, ?>) {
                if (out == null) {
                    out = new LinkedHashMap<>(change);
                }
                out.put(e.getKey(), Map.of("old", MASKED, "new", MASKED, "pii", pii));
            }
        }
        return out == null ? change : out;
    }

    /** {@code column -> Pn} from the {@code pii:Pn} column comments (catalogue access needs no table grant). */
    private Map<String, String> classify(String schema, String table) {
        Map<String, String> out = new HashMap<>();
        jdbc.sql("""
                select a.attname, d.description
                  from pg_catalog.pg_attribute a
                  join pg_catalog.pg_class c on c.oid = a.attrelid
                  join pg_catalog.pg_namespace n on n.oid = c.relnamespace
                  join pg_catalog.pg_description d on d.objoid = c.oid and d.objsubid = a.attnum
                 where n.nspname = :s and c.relname = :t and a.attnum > 0 and not a.attisdropped""")
                .param("s", schema).param("t", table)
                .query((rs, n) -> {
                    Matcher m = PII.matcher(rs.getString(2));
                    if (m.find()) {
                        out.put(rs.getString(1), m.group(1));
                    }
                    return null;
                }).list();
        return Map.copyOf(out);
    }
}
