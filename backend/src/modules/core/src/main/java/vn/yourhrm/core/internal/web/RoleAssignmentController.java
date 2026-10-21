package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.RoleAssignmentService;
import vn.yourhrm.core.internal.application.RoleAssignmentService.AssignmentView;
import vn.yourhrm.core.internal.application.RoleAssignmentService.GrantCommand;
import vn.yourhrm.shared.auth.api.RequiresPermission;
import vn.yourhrm.shared.auth.api.ScopeType;

/** Role assignments with data scope and validity per user (US-CORE-035). */
@RestController
@RequestMapping("/api/v1/core/users/{userId}/role-assignments")
class RoleAssignmentController {

    private final RoleAssignmentService assignments;

    RoleAssignmentController(RoleAssignmentService assignments) {
        this.assignments = assignments;
    }

    /**
     * @param validTo exclusive end date ({@code [validFrom, validTo)}); {@code null} = open-ended
     */
    record GrantRequest(@NotNull UUID roleId, @NotNull ScopeType scopeType, Set<UUID> scopeIds,
            Boolean includeSubtree, LocalDate validFrom, LocalDate validTo) {
    }

    @GetMapping
    @RequiresPermission(value = CorePermissions.ROLE_ASSIGNMENT_VIEW, tenantWide = true)
    public List<AssignmentView> list(@PathVariable UUID userId,
            @RequestParam(name = "includeHistory", defaultValue = "false") boolean includeHistory) {
        return assignments.list(userId, includeHistory);
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.ROLE_ASSIGNMENT_CREATE, tenantWide = true)
    public ResponseEntity<AssignmentView> grant(@PathVariable UUID userId, @Valid @RequestBody GrantRequest req) {
        AssignmentView v = assignments.grant(userId, new GrantCommand(req.roleId(), req.scopeType(), req.scopeIds(),
                req.includeSubtree(), req.validFrom(), req.validTo()));
        return ResponseEntity.created(URI.create("/api/v1/core/users/" + userId + "/role-assignments/" + v.id()))
                .body(v);
    }

    @DeleteMapping("/{assignmentId}")
    @RequiresPermission(value = CorePermissions.ROLE_ASSIGNMENT_DELETE, tenantWide = true)
    public ResponseEntity<Void> revoke(@PathVariable UUID userId, @PathVariable UUID assignmentId) {
        assignments.revoke(userId, assignmentId);
        return ResponseEntity.noContent().build();
    }
}
