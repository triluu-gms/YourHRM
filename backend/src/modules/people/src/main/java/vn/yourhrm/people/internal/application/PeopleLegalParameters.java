package vn.yourhrm.people.internal.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;
import vn.yourhrm.shared.common.legal.LegalParameters.Value;

/**
 * People's access to the Legal Parameter Service (IN-10, ADR-0010): every statutory value (probation limits, notice
 * periods, definite-contract maximum, dependant thresholds, insurance multi-contract rule ...) is read here, never
 * hard-coded. A missing parameter refuses the operation (fail closed). All example values in the BRD are
 * {@code [verify-legal]}.
 */
@Component
public class PeopleLegalParameters {

    private final ObjectProvider<LegalParameters> legalParameters;

    public PeopleLegalParameters(ObjectProvider<LegalParameters> legalParameters) {
        this.legalParameters = legalParameters;
    }

    public Optional<Value> find(String key, Scope scope, LocalDate asOf) {
        LegalParameters lps = legalParameters.getIfAvailable();
        return lps == null ? Optional.empty() : lps.value(key, scope, asOf);
    }

    /** The value in effect, or 422 {@code legal-parameter-missing}. */
    public Value require(String key, Scope scope, LocalDate asOf) {
        return find(key, scope, asOf).orElseThrow(() -> ApiException.unprocessable("legal-parameter-missing",
                "Legal parameter " + key + " is not defined for " + asOf + "; the operation cannot be checked",
                Map.of("parameter", key, "asOf", asOf.toString())));
    }
}
