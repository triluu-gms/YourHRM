package vn.yourhrm.core.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.yourhrm.core.api.CoreEvents.UserStatusChanged;
import vn.yourhrm.core.internal.domain.UserAccount;
import vn.yourhrm.core.internal.domain.UserStatus;
import vn.yourhrm.core.internal.infra.UserAccountRepository;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.id.Ids;
import vn.yourhrm.shared.common.tenancy.TenantContext;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Tenant user accounts (US-CORE-030): admin-created users, profile maintenance, activate / deactivate.
 * Event-driven provisioning from People (US-CORE-031) is deferred. Every write stamps created_by /
 * updated_by with the acting user; the DDL row-audit trigger records the change in audit.audit_event.
 */
@Service
public class UserService {

    private final UserAccountRepository users;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;

    public UserService(UserAccountRepository users, JdbcClient jdbc, ApplicationEventPublisher events,
            BusinessClock clock) {
        this.users = users;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    public record UserView(UUID id, String login, String email, String mobile, UUID employmentId, UUID personId,
            UUID identityId, String locale, UserStatus status, Instant expiresAt, Instant accessRevokedAt,
            Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy, int version) {

        static UserView of(UserAccount u) {
            return new UserView(u.getId(), u.getLogin(), u.getEmail(), u.getMobile(), u.getEmploymentId(),
                    u.getPersonId(), u.getIdentityId(), u.getLocale(), u.getStatus(), u.getExpiresAt(),
                    u.getAccessRevokedAt(), u.getCreatedAt(), u.getCreatedBy(), u.getUpdatedAt(), u.getUpdatedBy(),
                    u.getVersion() == null ? 0 : u.getVersion());
        }
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    public record Profile(String email, String mobile, String locale, UUID identityId, UUID employmentId,
            UUID personId, Instant expiresAt) {
    }

    @Transactional(readOnly = true)
    public Page<UserView> search(String query, UserStatus status, int page, int size) {
        UUID tenant = TenantContext.current().tenantId();
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        String st = status == null ? null : status.name();
        String where = """
                 where u.tenant_id = :t
                   and (cast(:q as text) is null or lower(u.login) like :q or lower(coalesce(u.email, '')) like :q)
                   and (cast(:st as text) is null or u.status = :st)""";
        long total = jdbc.sql("select count(*) from core.user_account u " + where)
                .param("t", tenant).param("q", q).param("st", st)
                .query(Long.class).single();
        List<UUID> ids = jdbc.sql("select u.id from core.user_account u " + where
                        + " order by lower(u.login) limit :limit offset :offset")
                .param("t", tenant).param("q", q).param("st", st)
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> rs.getObject(1, UUID.class)).list();
        List<UserView> items = ids.stream()
                .map(id -> users.findByTenantIdAndId(tenant, id).map(UserView::of).orElseThrow())
                .toList();
        return new Page<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public UserView get(UUID userId) {
        return UserView.of(load(userId));
    }

    @Transactional
    public UserView create(String login, Profile profile) {
        TenantContext ctx = TenantContext.current();
        if (users.existsLogin(ctx.tenantId(), login)) {
            throw ApiException.conflict("login-taken", "Login " + login + " is already used in this tenant");
        }
        checkIdentityFree(ctx.tenantId(), profile.identityId(), null);
        Instant now = clock.now();
        UserAccount user = UserAccount.create(ctx.tenantId(), Ids.newId(), login,
                profile.locale() == null ? "vi-VN" : profile.locale(), ctx.userAccountId(), now);
        user.updateProfile(profile.email(), profile.mobile(), user.getLocale(), profile.identityId(),
                profile.employmentId(), profile.personId(), profile.expiresAt(), ctx.userAccountId(), now);
        users.saveAndFlush(user);
        publish(user);
        return UserView.of(user);
    }

    @Transactional
    public UserView update(UUID userId, Profile profile, int expectedVersion) {
        TenantContext ctx = TenantContext.current();
        UserAccount user = load(userId);
        checkVersion(user, expectedVersion);
        checkIdentityFree(ctx.tenantId(), profile.identityId(), userId);
        user.updateProfile(profile.email(), profile.mobile(),
                profile.locale() == null ? user.getLocale() : profile.locale(), profile.identityId(),
                profile.employmentId(), profile.personId(), profile.expiresAt(), ctx.userAccountId(), clock.now());
        users.saveAndFlush(user);
        publish(user);
        return UserView.of(user);
    }

    @Transactional
    public UserView activate(UUID userId) {
        TenantContext ctx = TenantContext.current();
        UserAccount user = load(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            user.activate(ctx.userAccountId(), clock.now());
            users.saveAndFlush(user);
            publish(user);
        }
        return UserView.of(user);
    }

    @Transactional
    public UserView deactivate(UUID userId) {
        TenantContext ctx = TenantContext.current();
        if (userId.equals(ctx.userAccountId())) {
            throw ApiException.conflict("self-deactivation", "You cannot deactivate your own account");
        }
        UserAccount user = load(userId);
        if (user.getStatus() != UserStatus.DEACTIVATED) {
            user.deactivate(ctx.userAccountId(), clock.now());
            users.saveAndFlush(user);
            publish(user);
        }
        return UserView.of(user);
    }

    private void publish(UserAccount user) {
        // core.UserStatusChanged (AC of US-CORE-030). In-process only; outbox transport is TECH:outbox (deferred).
        events.publishEvent(new UserStatusChanged(user.getTenantId(), user.getId(), user.getStatus().name(),
                user.getIdentityId() == null ? null : user.getIdentityId().toString()));
    }

    private void checkIdentityFree(UUID tenant, UUID identityId, UUID self) {
        if (identityId == null) {
            return;
        }
        users.findByTenantIdAndIdentityId(tenant, identityId)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw ApiException.conflict("identity-linked",
                            "The identity is already linked to another user of this tenant");
                });
    }

    private UserAccount load(UUID userId) {
        UUID tenant = TenantContext.current().tenantId();
        return users.findByTenantIdAndId(tenant, userId).orElseThrow(() -> ApiException.notFound("User", userId));
    }

    private static void checkVersion(UserAccount user, int expected) {
        if (user.getVersion() != expected) {
            throw ApiException.conflict("stale-version", "The user was changed (version " + user.getVersion()
                    + "); reload and retry");
        }
    }
}
