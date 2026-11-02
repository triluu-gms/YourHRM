package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.CorePermissions;
import vn.yourhrm.core.internal.application.CompanyGroupService;
import vn.yourhrm.core.internal.application.CompanyGroupService.CompanyGroupView;
import vn.yourhrm.core.internal.application.CompanyGroupService.Profile;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** The tenant's company group profile (BR-CORE-001, US-CORE-001): one per tenant, created at provisioning. */
@RestController
@RequestMapping("/api/v1/core/company-group")
class CompanyGroupController {

    private final CompanyGroupService groups;

    CompanyGroupController(CompanyGroupService groups) {
        this.groups = groups;
    }

    record UpdateCompanyGroupRequest(
            @NotBlank @Size(max = 200) String name,
            UUID logoDocumentId,
            @NotBlank String defaultLocale,
            @NotBlank String timeZone,
            @NotBlank @Size(min = 3, max = 3) String currencyCode,
            @NotNull Integer version) {
    }

    @GetMapping
    @RequiresPermission(CorePermissions.COMPANY_GROUP_VIEW)
    public CompanyGroupView get() {
        return groups.get();
    }

    @PutMapping
    @RequiresPermission(value = CorePermissions.COMPANY_GROUP_UPDATE, tenantWide = true)
    public CompanyGroupView update(@Valid @RequestBody UpdateCompanyGroupRequest req) {
        return groups.update(new Profile(req.name(), req.logoDocumentId(), req.defaultLocale(), req.timeZone(),
                req.currencyCode()), req.version());
    }
}
