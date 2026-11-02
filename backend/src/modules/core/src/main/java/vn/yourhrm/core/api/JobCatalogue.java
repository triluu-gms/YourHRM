package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk lookups of Core's job catalogue for other modules (US-CORE-024, interfaces P-05/P-07/P-08): job titles,
 * grades, cost centres and employee categories, so People / T&A / Payroll / Recruitment can validate their soft
 * references and display them. Evaluated in the current {@code TenantContext}; no user permission check. Ids that
 * do not exist (or, for dated entries, are not valid on {@code asOf}) are simply absent from the result.
 * Changes are signalled by {@code core.JobCatalogueChanged}.
 */
public interface JobCatalogue {

    record JobTitle(UUID id, String code, String nameVi, String nameEn, UUID jobFamilyId, String occupationCode,
            String hazardousCategory, boolean active) {

        public boolean hazardous() {
            return hazardousCategory != null;
        }
    }

    /** No pay data: salary bands per grade are Payroll's (IF-PAY-P-02). */
    record Grade(UUID id, UUID gradeStructureId, String code, String name, int rankOrder) {
    }

    record CostCentre(UUID id, UUID legalEntityId, String code, String name, String glSegment) {
    }

    record EmployeeCategory(UUID id, String code, String nameVi, boolean probation, boolean seasonal, boolean intern,
            boolean expat, boolean collaborator, boolean countsInHeadcount, boolean active) {
    }

    Map<UUID, JobTitle> jobTitles(Collection<UUID> ids);

    /** Active grades valid on {@code asOf}. */
    Map<UUID, Grade> grades(Collection<UUID> ids, LocalDate asOf);

    /** Cost centres valid on {@code asOf}. */
    Map<UUID, CostCentre> costCentres(Collection<UUID> ids, LocalDate asOf);

    Map<UUID, EmployeeCategory> employeeCategories(Collection<UUID> ids);
}
