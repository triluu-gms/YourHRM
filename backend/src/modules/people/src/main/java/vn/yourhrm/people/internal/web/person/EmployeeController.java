package vn.yourhrm.people.internal.web.person;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.yourhrm.people.api.PeoplePermissions;
import vn.yourhrm.people.internal.application.person.EmployeeDirectoryService;
import vn.yourhrm.people.internal.application.person.EmployeeDirectoryService.Page;
import vn.yourhrm.people.internal.application.person.EmployeeDirectoryService.Profile;
import vn.yourhrm.people.internal.application.person.EmployeeDirectoryService.Query;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/** Employee list and profile (US-PPL-015/016); scoped, masked. */
@RestController
@RequestMapping("/api/v1/people/employees")
class EmployeeController {

    private final EmployeeDirectoryService directory;

    EmployeeController(EmployeeDirectoryService directory) {
        this.directory = directory;
    }

    /**
     * Employments in scope on {@code asOf} (default today; pre-hires starting later included), filtered and sorted by
     * Vietnamese name. {@code q} matches the name without accents ("nguyen van an") or an employee-number prefix.
     */
    @GetMapping
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public Page list(@RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "legalEntityId", required = false) UUID legalEntityId,
            @RequestParam(name = "orgUnitId", required = false) UUID orgUnitId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "workerCategoryCode", required = false) String workerCategoryCode,
            @RequestParam(name = "employeeCategoryCode", required = false) String employeeCategoryCode,
            @RequestParam(name = "asOf", required = false) LocalDate asOf,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {
        return directory.list(new Query(q, legalEntityId, orgUnitId, status, workerCategoryCode, employeeCategoryCode,
                asOf, page, size));
    }

    @GetMapping("/{employmentId}")
    @RequiresPermission(PeoplePermissions.EMPLOYEE_VIEW)
    public Profile profile(@PathVariable UUID employmentId,
            @RequestParam(name = "asOf", required = false) LocalDate asOf) {
        return directory.profile(employmentId, asOf);
    }
}
