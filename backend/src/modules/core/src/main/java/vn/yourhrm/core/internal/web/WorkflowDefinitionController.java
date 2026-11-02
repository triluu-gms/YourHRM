package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.WorkflowDefinitionService;
import vn.yourhrm.core.internal.application.WorkflowDefinitionService.Conditions;
import vn.yourhrm.core.internal.application.WorkflowDefinitionService.DefinitionView;
import vn.yourhrm.core.internal.application.WorkflowDefinitionService.ProcessTypeView;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.workflow.api.StepTemplate;

/**
 * Process-type registry and workflow definitions (BR-CORE-050, US-CORE-050/051). Support loads tenant flows here
 * without a code change; the form designer for tenants is P2 (BR-CORE-051).
 */
@RestController
@RequestMapping("/api/v1/core")
class WorkflowDefinitionController {

    private final WorkflowDefinitionService definitions;

    WorkflowDefinitionController(WorkflowDefinitionService definitions) {
        this.definitions = definitions;
    }

    record PublishDefinitionRequest(
            @NotBlank String processTypeCode,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,64}$") String code,
            @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn,
            Integer matchPriority,
            Conditions conditions,
            @NotEmpty List<@NotNull StepTemplate> steps,
            String onResolverFailure,
            @NotNull LocalDate validFrom) {
    }

    @GetMapping("/process-types")
    @RequiresPermission(value = CorePermissions.WORKFLOW_DEFINITION_VIEW, tenantWide = true)
    public List<ProcessTypeView> processTypes() {
        return definitions.processTypes();
    }

    @GetMapping("/workflow-definitions")
    @RequiresPermission(value = CorePermissions.WORKFLOW_DEFINITION_VIEW, tenantWide = true)
    public List<DefinitionView> list(@RequestParam(name = "processType", required = false) String processType) {
        return definitions.list(processType);
    }

    @GetMapping("/workflow-definitions/{definitionId}")
    @RequiresPermission(value = CorePermissions.WORKFLOW_DEFINITION_VIEW, tenantWide = true)
    public DefinitionView get(@PathVariable UUID definitionId) {
        return definitions.get(definitionId);
    }

    /** Creates the definition (by code) if needed and publishes a new version from {@code validFrom}. */
    @PostMapping("/workflow-definitions")
    @RequiresPermission(value = CorePermissions.WORKFLOW_DEFINITION_MANAGE, tenantWide = true)
    public ResponseEntity<DefinitionView> publish(@Valid @RequestBody PublishDefinitionRequest req) {
        DefinitionView published = definitions.publish(req.processTypeCode(), req.code(), req.nameVi(), req.nameEn(),
                req.matchPriority(), req.conditions() == null ? Conditions.none() : req.conditions(), req.steps(),
                req.onResolverFailure() == null ? "ESCALATE_UP" : req.onResolverFailure(), req.validFrom());
        return ResponseEntity.created(URI.create("/api/v1/core/workflow-definitions/" + published.id()))
                .body(published);
    }
}
