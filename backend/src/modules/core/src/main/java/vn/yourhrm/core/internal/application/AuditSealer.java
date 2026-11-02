package vn.yourhrm.core.internal.application;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seals the tenant audit chains and writes the daily anchors (BR-CORE-092, ADR-0027 decisions 3-4). Runs on a
 * connection of the role {@code yhrm_audit_sealer}, the only role allowed to write the seal columns and the chain
 * head; the application role can only INSERT events and never UPDATE or DELETE them. {@link AuditSealerScheduling}
 * runs it every few seconds when a sealer connection is configured.
 *
 * <p>The copy of each anchor to WORM object storage in the second VN data centre is the SaaS storage's job
 * ({@code chain_anchor.exported_uri}); it is not done here.
 */
@Component
public class AuditSealer {

    private static final Logger log = LoggerFactory.getLogger(AuditSealer.class);

    /** Seals the pending events of every tenant (older than 2 s, in id order); returns the number sealed. */
    public int sealPending(JdbcTemplate sealer) {
        List<UUID> tenants = sealer.queryForList("""
                select distinct tenant_id from audit.audit_event
                 where sealed_at is null and occurred_at < now() - interval '2 seconds'""", UUID.class);
        int sealed = 0;
        for (UUID t : tenants) {
            try {
                Integer n = sealer.queryForObject("select audit.seal_batch(?)", Integer.class, t);
                sealed += n == null ? 0 : n;
            } catch (RuntimeException ex) {
                log.error("Sealing the audit chain of tenant {} failed; retried on the next run", t, ex);
            }
        }
        return sealed;
    }

    /** Records each tenant's chain head of {@code day} as its anchor (idempotent per tenant and day). */
    public int anchor(JdbcTemplate sealer, LocalDate day) {
        return sealer.update("""
                insert into audit.chain_anchor (tenant_id, anchor_date, last_seq, head_hash)
                select tenant_id, ?, last_seq, last_hash from audit.chain_head where last_seq > 0
                on conflict (tenant_id, anchor_date) do nothing""", Date.valueOf(day));
    }
}
