package vn.yourhrm.core.internal.web;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.core.api.MasterData.AdminUnit;
import vn.yourhrm.core.api.MasterData.Resolution;
import vn.yourhrm.core.internal.application.MasterDataService;
import vn.yourhrm.core.internal.application.MasterDataService.BankBranchView;
import vn.yourhrm.core.internal.application.MasterDataService.BankView;
import vn.yourhrm.core.internal.application.MasterDataService.CodeListView;
import vn.yourhrm.core.internal.application.MasterDataService.CodeValueView;
import vn.yourhrm.core.internal.application.MasterDataService.CountryView;
import vn.yourhrm.core.internal.application.MasterDataService.CurrencyView;
import vn.yourhrm.core.internal.application.MasterDataService.FormattedAddress;
import vn.yourhrm.core.internal.application.MasterDataService.ReleaseView;
import vn.yourhrm.shared.auth.api.PublicEndpoint;
import vn.yourhrm.shared.common.error.ApiException;

/**
 * Global master data (BR-CORE-080..082): reference data every tenant user reads in pickers and forms; tenants never
 * write it (releases come from the SaaS back-office).
 */
@RestController
@RequestMapping("/api/v1/core/master-data")
class MasterDataController {

    private static final String REASON = "Global reference data, read-only and identical for every tenant user";

    private final MasterDataService masterData;

    MasterDataController(MasterDataService masterData) {
        this.masterData = masterData;
    }

    /** Provinces (default) or the children of {@code parent}, valid on {@code asOf}. */
    @GetMapping("/admin-units")
    @PublicEndpoint(reason = REASON)
    public List<AdminUnit> adminUnits(@RequestParam(name = "parent", required = false) String parent,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return masterData.adminUnits(parent, level, asOf);
    }

    @GetMapping("/admin-units/search")
    @PublicEndpoint(reason = REASON)
    public List<AdminUnit> search(@RequestParam("q") String q,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return masterData.searchAdminUnits(q, level, asOf, Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/admin-units/{code}")
    @PublicEndpoint(reason = REASON)
    public AdminUnit adminUnit(@PathVariable String code) {
        return masterData.adminUnit(code).orElseThrow(() -> ApiException.notFound("Administrative unit", code));
    }

    /** Old → new resolution (BR-CORE-081); a split returns several candidates to choose from. */
    @GetMapping("/admin-units/{code}/resolve")
    @PublicEndpoint(reason = REASON)
    public Resolution resolve(@PathVariable String code,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return masterData.resolveAdminUnit(code, asOf);
    }

    /** The address line in the OLD (as recorded) or NEW (successor on {@code asOf}) form. */
    @GetMapping("/admin-units/{code}/address")
    @PublicEndpoint(reason = REASON)
    public FormattedAddress address(@PathVariable String code,
            @RequestParam(name = "form", defaultValue = "NEW") String form,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return masterData.format(code, "OLD".equalsIgnoreCase(form) ? "OLD" : "NEW", asOf);
    }

    @GetMapping("/code-lists")
    @PublicEndpoint(reason = REASON)
    public List<CodeListView> codeLists() {
        return masterData.codeLists();
    }

    @GetMapping("/code-lists/{listCode}/values")
    @PublicEndpoint(reason = REASON)
    public List<CodeValueView> codeValues(@PathVariable String listCode,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return masterData.codeValues(listCode, asOf, q, includeInactive);
    }

    @GetMapping("/countries")
    @PublicEndpoint(reason = REASON)
    public List<CountryView> countries(@RequestParam(name = "q", required = false) String q) {
        return masterData.countries(q);
    }

    @GetMapping("/currencies")
    @PublicEndpoint(reason = REASON)
    public List<CurrencyView> currencies() {
        return masterData.currencies();
    }

    @GetMapping("/banks")
    @PublicEndpoint(reason = REASON)
    public List<BankView> banks(@RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return masterData.banks(q, includeInactive);
    }

    @GetMapping("/banks/{bankCode}/branches")
    @PublicEndpoint(reason = REASON)
    public List<BankBranchView> branches(@PathVariable String bankCode,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return masterData.bankBranches(bankCode, q, includeInactive);
    }

    @GetMapping("/releases")
    @PublicEndpoint(reason = REASON)
    public List<ReleaseView> releases() {
        return masterData.releases();
    }
}
