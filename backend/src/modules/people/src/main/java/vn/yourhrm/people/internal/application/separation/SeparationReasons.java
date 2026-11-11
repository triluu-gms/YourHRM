package vn.yourhrm.people.internal.application.separation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.internal.application.PeopleLegalParameters;
import vn.yourhrm.people.internal.domain.separation.LegalGround;
import vn.yourhrm.shared.common.error.ApiException;
import vn.yourhrm.shared.common.legal.LegalParameters.Scope;

/**
 * Separation reasons (US-PPL-060, BR-PPL-070): the legal grounds ({@link LegalGround}, fixed) with their statutory
 * attributes from the legal parameter {@code separation.legal_grounds} ([verify-legal] SI/UI code, notice side,
 * severance kind), and the tenant's sub-reasons from the setting {@code hr.separation.sub_reasons}. Storage choice:
 * Core code lists cannot carry the ground of a sub-reason through Core's public API ({@code MasterData} only validates
 * a code), so sub-reasons are a tenant JSON setting (the pattern of {@code hr.assignment.change_reasons}); an entry
 * naming an unknown ground is reported as a problem and never becomes a ground.
 */
@Component
public class SeparationReasons {

    public static final Set<String> NOTICE_SIDES = Set.of("EMPLOYEE", "EMPLOYER", "NONE");
    public static final Set<String> SEVERANCE_TYPES = Set.of("NONE", "SEVERANCE_ALLOWANCE", "JOB_LOSS_ALLOWANCE");

    /** The statutory attributes of a ground on a date (from the legal pack). */
    public record GroundRule(LegalGround ground, String statutoryCode, String noticeSide, String severanceType) {
    }

    /** {@code configured=false}: the legal pack lacks the ground (it cannot be used until the pack is fixed). */
    public record GroundView(String code, String separationType, String initiatedBy, String article, String nameVi,
            String nameEn, boolean employerInitiated, String decisionType, String statutoryCode, String noticeSide,
            String severanceType, boolean configured, List<String> problems) {
    }

    public record SubReasonView(String code, String groundCode, String nameVi, String nameEn, boolean active,
            List<String> problems) {
    }

    public record Catalogue(List<GroundView> grounds, List<SubReasonView> subReasons) {
    }

    private final PeopleLegalParameters legal;
    private final Settings settings;

    public SeparationReasons(PeopleLegalParameters legal, Settings settings) {
        this.legal = legal;
        this.settings = settings;
    }

    public Catalogue catalogue(UUID legalEntityId, LocalDate asOf) {
        Map<String, Object> rules = legal.find(SeparationKeys.LEGAL_GROUNDS, scope(legalEntityId), asOf)
                .map(v -> v.asMap()).orElse(Map.of());
        List<GroundView> grounds = new ArrayList<>();
        for (LegalGround g : LegalGround.values()) {
            List<String> problems = new ArrayList<>();
            GroundRule r = parse(g, rules.get(g.code()), problems);
            grounds.add(new GroundView(g.code(), g.separationType(), g.initiatedBy(), g.article(), g.nameVi(),
                    g.nameEn(), g.employerInitiated(), g.decisionType(), r == null ? null : r.statutoryCode(),
                    r == null ? null : r.noticeSide(), r == null ? null : r.severanceType(), r != null,
                    List.copyOf(problems)));
        }
        return new Catalogue(grounds, subReasons(asOf));
    }

    /** The rule of {@code ground}, or 422 {@code legal-parameter-missing} when the pack does not define it. */
    public GroundRule rule(LegalGround ground, UUID legalEntityId, LocalDate asOf) {
        Map<String, Object> rules = legal.require(SeparationKeys.LEGAL_GROUNDS, scope(legalEntityId), asOf).asMap();
        List<String> problems = new ArrayList<>();
        GroundRule r = parse(ground, rules.get(ground.code()), problems);
        if (r == null) {
            throw ApiException.unprocessable("legal-parameter-missing", "Legal parameter "
                    + SeparationKeys.LEGAL_GROUNDS + " does not define ground " + ground.code() + ": "
                    + String.join("; ", problems), Map.of("parameter", SeparationKeys.LEGAL_GROUNDS,
                    "ground", ground.code(), "asOf", asOf.toString()));
        }
        return r;
    }

    public LegalGround requireGround(String code) {
        return LegalGround.of(code).orElseThrow(() -> ApiException.unprocessable("invalid-legal-ground",
                "Unknown legal ground " + code + "; tenants may add sub-reasons, not grounds"));
    }

    /** Validates a sub-reason code under {@code ground}; null passes (no sub-reason). */
    public String requireSubReason(String code, LegalGround ground, LocalDate asOf) {
        if (code == null || code.isBlank()) {
            return null;
        }
        SubReasonView v = subReasons(asOf).stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("invalid-separation-reason",
                        "Unknown separation sub-reason " + code));
        if (!v.problems().isEmpty()) {
            throw ApiException.unprocessable("invalid-separation-reason", "Sub-reason " + code + " is misconfigured: "
                    + String.join("; ", v.problems()));
        }
        if (!v.active()) {
            throw ApiException.unprocessable("invalid-separation-reason", "Sub-reason " + code + " is disabled");
        }
        if (!ground.code().equals(v.groundCode())) {
            throw ApiException.unprocessable("invalid-separation-reason", "Sub-reason " + code + " belongs to ground "
                    + v.groundCode() + ", not " + ground.code());
        }
        return code;
    }

    public Optional<SubReasonView> subReason(String code, LocalDate asOf) {
        return code == null ? Optional.empty()
                : subReasons(asOf).stream().filter(s -> s.code().equals(code)).findFirst();
    }

    private List<SubReasonView> subReasons(LocalDate asOf) {
        Object value = settings.value(SeparationKeys.SETTING_SUB_REASONS, Settings.Scope.tenant(), asOf);
        List<SubReasonView> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String code = str(m.get("code"));
            String ground = str(m.get("groundCode"));
            List<String> problems = new ArrayList<>();
            if (code == null) {
                problems.add("code is missing");
            }
            if (ground == null || LegalGround.of(ground).isEmpty()) {
                problems.add("groundCode " + ground + " is not a legal ground (tenants cannot add grounds)");
            }
            String nameVi = str(m.get("nameVi"));
            if (nameVi == null) {
                problems.add("nameVi is missing");
            }
            String nameEn = str(m.get("nameEn"));
            out.add(new SubReasonView(code, ground, nameVi, nameEn == null ? nameVi : nameEn,
                    !Boolean.FALSE.equals(m.get("active")), List.copyOf(problems)));
        }
        return out;
    }

    private static GroundRule parse(LegalGround g, Object raw, List<String> problems) {
        if (!(raw instanceof Map<?, ?> m)) {
            problems.add("not defined in " + SeparationKeys.LEGAL_GROUNDS);
            return null;
        }
        String code = str(m.get("statutory_code"));
        String side = str(m.get("notice_side"));
        String severance = str(m.get("severance"));
        if (code == null) {
            problems.add("statutory_code is missing");
        }
        if (side == null || !NOTICE_SIDES.contains(side)) {
            problems.add("notice_side must be one of " + NOTICE_SIDES);
        }
        if (severance == null || !SEVERANCE_TYPES.contains(severance)) {
            problems.add("severance must be one of " + SEVERANCE_TYPES);
        }
        return problems.isEmpty() ? new GroundRule(g, code, side, severance) : null;
    }

    private static Scope scope(UUID legalEntityId) {
        return new Scope(legalEntityId, null);
    }

    private static String str(Object o) {
        return o == null || o.toString().isBlank() ? null : o.toString();
    }
}
