package vn.yourhrm.core.internal.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
import vn.yourhrm.core.internal.application.GradeService;
import vn.yourhrm.core.internal.application.GradeService.GradeStructureView;
import vn.yourhrm.core.internal.application.GradeService.GradeView;
import vn.yourhrm.core.internal.application.JobTitleService;
import vn.yourhrm.core.internal.application.JobTitleService.JobFamilyView;
import vn.yourhrm.core.internal.application.JobTitleService.JobTitleAttributes;
import vn.yourhrm.core.internal.application.JobTitleService.JobTitleView;
import vn.yourhrm.shared.auth.api.RequiresPermission;

/**
 * Job architecture (BR-CORE-020/021, US-CORE-020/021): job families, job titles, grade structures and grades.
 * Tenant-wide catalogues: maintained with a TENANT-scoped {@code core.job_catalogue.update} grant.
 */
@RestController
@RequestMapping("/api/v1/core")
class JobCatalogueController {

    private static final String CODE = "^[A-Za-z0-9_.-]{1,32}$";

    private final JobTitleService jobs;
    private final GradeService grades;

    JobCatalogueController(JobTitleService jobs, GradeService grades) {
        this.jobs = jobs;
        this.grades = grades;
    }

    record CreateFamilyRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn) {
    }

    record UpdateFamilyRequest(@NotBlank @Size(max = 200) String nameVi, @Size(max = 200) String nameEn,
            @NotNull Boolean active) {
    }

    record JobTitleRequest(
            UUID jobFamilyId,
            @NotBlank @Size(max = 200) String nameVi,
            @Size(max = 200) String nameEn,
            @Size(max = 4000) String description,
            UUID jdDocumentId,
            UUID defaultGradeMinId,
            UUID defaultGradeMaxId,
            @Size(max = 32) String occupationCode,
            @Size(max = 64) String hazardousCategory,
            Boolean active) {

        JobTitleAttributes toAttributes() {
            return new JobTitleAttributes(jobFamilyId, nameVi, nameEn, description, jdDocumentId, defaultGradeMinId,
                    defaultGradeMaxId, occupationCode, hazardousCategory, active == null || active);
        }
    }

    record CreateJobTitleRequest(@NotBlank @Pattern(regexp = CODE) String code,
            @NotNull @Valid JobTitleRequest attributes) {
    }

    record UpdateJobTitleRequest(@NotNull @Valid JobTitleRequest attributes, @NotNull Integer version) {
    }

    record CreateStructureRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String name) {
    }

    record RenameStructureRequest(@NotBlank @Size(max = 200) String name) {
    }

    record CreateGradeRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 200) String name,
            @NotNull Integer rankOrder, LocalDate validFrom, LocalDate validTo) {
    }

    record UpdateGradeRequest(@NotBlank @Size(max = 200) String name, @NotNull Integer rankOrder, LocalDate validTo,
            @NotNull Boolean active) {
    }

    // ------------------------------------------------------------------------------------ job families

    @GetMapping("/job-families")
    @RequiresPermission(CorePermissions.JOB_CATALOGUE_VIEW)
    public List<JobFamilyView> families(@RequestParam(name = "includeInactive", defaultValue = "false") boolean all) {
        return jobs.families(all);
    }

    @PostMapping("/job-families")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public ResponseEntity<JobFamilyView> createFamily(@Valid @RequestBody CreateFamilyRequest req) {
        JobFamilyView created = jobs.createFamily(req.code(), req.nameVi(), req.nameEn());
        return ResponseEntity.created(URI.create("/api/v1/core/job-families/" + created.id())).body(created);
    }

    @PutMapping("/job-families/{familyId}")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public JobFamilyView updateFamily(@PathVariable UUID familyId, @Valid @RequestBody UpdateFamilyRequest req) {
        return jobs.updateFamily(familyId, req.nameVi(), req.nameEn(), req.active());
    }

    // ------------------------------------------------------------------------------------ job titles

    @GetMapping("/job-titles")
    @RequiresPermission(CorePermissions.JOB_CATALOGUE_VIEW)
    public List<JobTitleView> titles(@RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "jobFamilyId", required = false) UUID familyId,
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean all) {
        return jobs.titles(q, familyId, all);
    }

    @GetMapping("/job-titles/{titleId}")
    @RequiresPermission(CorePermissions.JOB_CATALOGUE_VIEW)
    public JobTitleView title(@PathVariable UUID titleId) {
        return jobs.title(titleId);
    }

    @PostMapping("/job-titles")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public ResponseEntity<JobTitleView> createTitle(@Valid @RequestBody CreateJobTitleRequest req) {
        JobTitleView created = jobs.createTitle(req.code(), req.attributes().toAttributes());
        return ResponseEntity.created(URI.create("/api/v1/core/job-titles/" + created.id())).body(created);
    }

    @PutMapping("/job-titles/{titleId}")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public JobTitleView updateTitle(@PathVariable UUID titleId, @Valid @RequestBody UpdateJobTitleRequest req) {
        return jobs.updateTitle(titleId, req.attributes().toAttributes(), req.version());
    }

    // ------------------------------------------------------------------------------------ grades

    @GetMapping("/grade-structures")
    @RequiresPermission(CorePermissions.JOB_CATALOGUE_VIEW)
    public List<GradeStructureView> structures() {
        return grades.structures();
    }

    @PostMapping("/grade-structures")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public ResponseEntity<GradeStructureView> createStructure(@Valid @RequestBody CreateStructureRequest req) {
        GradeStructureView created = grades.createStructure(req.code(), req.name());
        return ResponseEntity.created(URI.create("/api/v1/core/grade-structures/" + created.id())).body(created);
    }

    @PutMapping("/grade-structures/{structureId}")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public GradeStructureView renameStructure(@PathVariable UUID structureId,
            @Valid @RequestBody RenameStructureRequest req) {
        return grades.renameStructure(structureId, req.name());
    }

    /** Grades of a structure in rank order, valid on {@code asOf} (or every period with {@code allDates=true}). */
    @GetMapping("/grade-structures/{structureId}/grades")
    @RequiresPermission(CorePermissions.JOB_CATALOGUE_VIEW)
    public List<GradeView> grades(@PathVariable UUID structureId,
            @RequestParam(name = "asOf", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(name = "allDates", defaultValue = "false") boolean allDates) {
        return grades.grades(structureId, asOf, allDates);
    }

    @PostMapping("/grade-structures/{structureId}/grades")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public ResponseEntity<GradeView> createGrade(@PathVariable UUID structureId,
            @Valid @RequestBody CreateGradeRequest req) {
        GradeView created = grades.createGrade(structureId, req.code(), req.name(), req.rankOrder(), req.validFrom(),
                req.validTo());
        return ResponseEntity.created(URI.create("/api/v1/core/grades/" + created.id())).body(created);
    }

    @PutMapping("/grades/{gradeId}")
    @RequiresPermission(value = CorePermissions.JOB_CATALOGUE_UPDATE, tenantWide = true)
    public GradeView updateGrade(@PathVariable UUID gradeId, @Valid @RequestBody UpdateGradeRequest req) {
        return grades.updateGrade(gradeId, req.name(), req.rankOrder(), req.validTo(), req.active());
    }
}
