package vn.yourhrm.people.internal.web.bank;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.internal.application.bank.BankCatalogues;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * Minimal step-up check of ESS bank changes (NFR-PPL-011, US-PPL-075). The SaaS ESS shell performs the step-up
 * (Keycloak re-authentication with a higher level of assurance) and the resulting access token carries it; neither
 * {@code TenantContext} nor Core exposes it, so the validated JWT of the request is read directly:
 * <ul>
 *   <li>{@code acr} must be one of the tenant setting {@link BankCatalogues#SETTING_STEP_UP_ACR} (or {@code amr} must
 *       contain one of them);</li>
 *   <li>{@code auth_time} must be at most {@link BankCatalogues#SETTING_STEP_UP_MAX_AGE} seconds old.</li>
 * </ul>
 * Otherwise 403 {@code step-up-required} (the shell then re-authenticates and retries). The evidence kept in
 * {@code hr.bank_account.step_up_auth_ref} is {@code acr}, {@code auth_time} and the token / session id, no PII.
 */
@Component
class StepUpVerifier {

    private final Settings settings;
    private final BusinessClock clock;

    StepUpVerifier(Settings settings, BusinessClock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** The step-up evidence of the current request, or 403 {@code step-up-required}. */
    String require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw stepUp("No bearer token");
        }
        Jwt jwt = token.getToken();
        Object configured = settings.value(BankCatalogues.SETTING_STEP_UP_ACR, Settings.Scope.tenant(), clock.today());
        List<String> accepted = configured instanceof Collection<?> c ? c.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(configured));
        long maxAge = settings.longValue(BankCatalogues.SETTING_STEP_UP_MAX_AGE, Settings.Scope.tenant(), clock.today());
        String acr = jwt.getClaimAsString("acr");
        List<String> amr = jwt.hasClaim("amr") ? jwt.getClaimAsStringList("amr") : List.of();
        String level = acr != null && accepted.contains(acr) ? acr
                : amr.stream().filter(accepted::contains).findFirst().orElse(null);
        if (level == null) {
            throw stepUp("The session is not step-up authenticated");
        }
        Instant authTime = instant(jwt.getClaims().get("auth_time"));
        if (authTime == null || authTime.isBefore(clock.now().minusSeconds(maxAge))) {
            throw stepUp("The step-up authentication is older than " + maxAge + " seconds");
        }
        String session = jwt.getId() != null ? jwt.getId() : jwt.getClaimAsString("sid");
        return "acr=" + level + ";auth_time=" + authTime.getEpochSecond() + (session == null ? "" : ";jti=" + session);
    }

    private static Instant instant(Object claim) {
        if (claim instanceof Instant i) {
            return i;
        }
        if (claim instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (claim instanceof String s && s.matches("\\d+")) {
            return Instant.ofEpochSecond(Long.parseLong(s));
        }
        return null;
    }

    private static ApiException stepUp(String why) {
        return new ApiException(HttpStatus.FORBIDDEN, "step-up-required",
                why + "; re-authenticate with step-up before changing the bank account",
                Map.of("reason", why));
    }
}
