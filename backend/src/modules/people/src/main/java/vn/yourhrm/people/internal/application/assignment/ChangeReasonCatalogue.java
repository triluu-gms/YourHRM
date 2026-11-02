package vn.yourhrm.people.internal.application.assignment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.Settings;
import vn.yourhrm.people.internal.application.DecisionService;
import vn.yourhrm.people.internal.domain.assignment.ChangeReason;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * The tenant's job-change reasons (BR-PPL-032, US-PPL-035): the vendor defaults of {@link ChangeReason#DEFAULTS}
 * merged with the tenant's overrides in Core setting {@code hr.assignment.change_reasons} (a tenant disables a default
 * reason or adds its own; Phase 2 reasons cannot be activated). Each reason maps to a Core process type and a document
 * kind. Storage choice: a JSON tenant setting (Core settings exist; Core code lists only hold a code and a name, not the
 * process-type / document mapping).
 */
@Component
public class ChangeReasonCatalogue {

    /** A reason as listed: invalid tenant entries are listed with their problems and cannot be used. */
    public record ReasonView(String code, String nameVi, String nameEn, String actionCode, String assignmentType,
            String processTypeCode, String decisionTypeCode, String documentKind, boolean active, boolean phase2,
            boolean tenantDefined, List<String> problems) {
    }

    private final Settings settings;

    public ChangeReasonCatalogue(Settings settings) {
        this.settings = settings;
    }

    public List<ReasonView> list(LocalDate asOf) {
        Map<String, ReasonView> out = new LinkedHashMap<>();
        Map<String, ChangeReason> merged = new LinkedHashMap<>();
        ChangeReason.DEFAULTS.forEach(r -> merged.put(r.code(), r));
        java.util.Set<String> custom = new java.util.HashSet<>();
        for (Map<String, Object> o : overrides(asOf)) {
            Object code = o.get("code");
            if (code == null) {
                continue;
            }
            ChangeReason base = merged.get(code.toString());
            if (base != null) {
                merged.put(base.code(), base.with(o));
            } else {
                merged.put(code.toString(), ChangeReason.custom(o));
                custom.add(code.toString());
            }
        }
        merged.values().forEach(r -> out.put(r.code(), view(r, custom.contains(r.code()))));
        return new ArrayList<>(out.values());
    }

    /** A usable reason, or 422 {@code invalid-change-reason}. */
    public ChangeReason require(String code, LocalDate asOf) {
        ReasonView v = list(asOf).stream().filter(r -> r.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("invalid-change-reason", "Unknown change reason " + code));
        if (v.phase2()) {
            throw ApiException.unprocessable("invalid-change-reason", "Change reason " + code
                    + " belongs to a Phase 2 feature (secondment, concurrent position, reorganisation)");
        }
        if (!v.active()) {
            throw ApiException.unprocessable("invalid-change-reason", "Change reason " + code + " is disabled");
        }
        if (!v.problems().isEmpty()) {
            throw ApiException.unprocessable("invalid-change-reason", "Change reason " + code
                    + " is misconfigured: " + String.join("; ", v.problems()));
        }
        return new ChangeReason(v.code(), v.nameVi(), v.nameEn(), v.actionCode(), v.assignmentType(),
                v.processTypeCode(), v.decisionTypeCode(), v.documentKind(), v.active(), v.phase2());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> overrides(LocalDate asOf) {
        Object value = settings.value(AssignmentCatalogues.CHANGE_REASONS, Settings.Scope.tenant(), asOf);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static ReasonView view(ChangeReason r, boolean tenantDefined) {
        return new ReasonView(r.code(), r.nameVi(), r.nameEn(), r.actionCode(), r.assignmentType(),
                r.processTypeCode(), r.decisionTypeCode(), r.documentKind(), r.active() && !r.phase2(), r.phase2(),
                tenantDefined, r.problems(DecisionService.TYPES));
    }
}
