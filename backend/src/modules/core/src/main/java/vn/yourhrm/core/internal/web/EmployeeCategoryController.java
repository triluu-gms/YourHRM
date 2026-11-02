package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.EmployeeCategoryService;
import vn.yourhrm.core.internal.application.EmployeeCategoryService.EmployeeCategoryView;
import vn.yourhrm.core.internal.application.EmployeeCategoryService.Flags;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Employee categories (BR-CORE-026, US-CORE-023). Tenant-wide code list. */
@RestController
@RequestMapping("/api/v1/core/employee-categories")
class EmployeeCategoryController {

    private final EmployeeCategoryService categories;

    EmployeeCategoryController(EmployeeCategoryService categories) {
        this.categories = categories;
    }

    record CreateCategoryRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String code,
            @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn,
            @NotNull Flags flags,
            Integer sortOrder) {
    }

    record UpdateCategoryRequest(
            @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn,
            @NotNull Flags flags,
            @NotNull Integer sortOrder,
            @NotNull Boolean active,
            @NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(CorePermissions.EMPLOYEE_CATEGORY_VIEW)
    public List<EmployeeCategoryView> list(@RequestParam(name = "includeInactive", defaultValue = "false") boolean all) {
        return categories.list(all);
    }

    @PostMapping
    @RequiresPermission(value = CorePermissions.EMPLOYEE_CATEGORY_UPDATE, tenantWide = true)
    public ResponseEntity<EmployeeCategoryView> create(@Valid @RequestBody CreateCategoryRequest req) {
        EmployeeCategoryView created = categories.create(req.code(), req.nameVi(), req.nameEn(), req.flags(),
                req.sortOrder());
        return ResponseEntity.created(URI.create("/api/v1/core/employee-categories/" + created.id())).body(created);
    }

    @PutMapping("/{categoryId}")
    @RequiresPermission(value = CorePermissions.EMPLOYEE_CATEGORY_UPDATE, tenantWide = true)
    public EmployeeCategoryView update(@PathVariable UUID categoryId, @Valid @RequestBody UpdateCategoryRequest req) {
        return categories.update(categoryId, req.nameVi(), req.nameEn(), req.flags(), req.sortOrder(), req.active(),
                req.version());
    }
}
