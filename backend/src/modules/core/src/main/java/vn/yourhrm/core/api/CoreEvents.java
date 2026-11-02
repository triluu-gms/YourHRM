package vn.yourhrm.core.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-process domain events of Core (published with Spring's event publisher, after-commit listeners).
 * Transport through the transactional outbox ({@code core.outbox_event}) is deferred to TECH:outbox.
 */
public final class CoreEvents {

    private CoreEvents() {
    }

    /** {@code core.RoleAssignmentChanged}: a user's role assignments changed (grant / revoke). */
    public record RoleAssignmentChanged(UUID tenantId, UUID userAccountId, UUID assignmentId, String change) {
    }

    /** {@code core.RoleChanged}: a role or its permission matrix changed (affects every holder). */
    public record RoleChanged(UUID tenantId, UUID roleId, String change) {
    }

    /**
     * {@code core.LegalEntityChanged}: a legal entity was created, its master data changed from
     * {@code effectiveFrom} (BR-CORE-002..004), or its status changed ({@code effectiveFrom} = today).
     */
    public record LegalEntityChanged(UUID tenantId, UUID legalEntityId, String change, LocalDate effectiveFrom) {
    }

    /** {@code core.LocationChanged}: a location was created or changed from {@code effectiveFrom} (BR-CORE-005). */
    public record LocationChanged(UUID tenantId, UUID locationId, UUID legalEntityId, String change,
            LocalDate effectiveFrom) {
    }

    /**
     * {@code core.LocationChanged}, subtype {@code LocationRegionChanged} (BR-CORE-007): the minimum-wage region of a
     * location changes from {@code effectiveFrom}, because its address changed or a new legal pack remapped it.
     * {@code previousRegion} is null for a new location; regions are "I".."IV" or null when not determinable.
     */
    public record LocationRegionChanged(UUID tenantId, UUID locationId, UUID legalEntityId, LocalDate effectiveFrom,
            String previousRegion, String region, long packVersion, String cause) {
    }

    /** The default minimum-wage region of a legal entity (its registered address) changes from {@code effectiveFrom}. */
    public record LegalEntityRegionChanged(UUID tenantId, UUID legalEntityId, LocalDate effectiveFrom,
            String previousRegion, String region, long packVersion, String cause) {
    }

    /**
     * {@code core.OrgUnitChanged}: an org unit was created or changed from {@code effectiveFrom} (BR-CORE-012).
     * {@code movedUnits} lists the units whose ancestry changed (the unit itself and its descendants after a move).
     */
    public record OrgUnitChanged(UUID tenantId, UUID orgUnitId, String change, LocalDate effectiveFrom,
            Set<UUID> movedUnits) {
    }

    /**
     * {@code core.JobCatalogueChanged} (BR-CORE-020/021/025/026, US-CORE-024): an entry of the job catalogue changed.
     * {@code entityType} is JOB_FAMILY, JOB_TITLE, GRADE_STRUCTURE, GRADE, COST_CENTRE or EMPLOYEE_CATEGORY;
     * {@code change} is CREATED, UPDATED or, for job titles, HAZARD_CHANGED when the hazardous category changed.
     */
    public record JobCatalogueChanged(UUID tenantId, String entityType, UUID entityId, String change) {
    }

    /**
     * {@code core.MasterDataReleased} (BR-CORE-080/087): a back-office release of global master data was loaded.
     * Platform-wide (every tenant sees the same reference data); consumers refresh caches and pickers.
     */
    public record MasterDataReleased(long releaseNo, List<String> datasets, LocalDate effectiveDate) {

        public MasterDataReleased {
            datasets = List.copyOf(datasets);
        }
    }

    /**
     * {@code core.ImportCommitted} (BR-CORE-110/114): a job's rows were written by its owner module. Consumers of an
     * opening-balance load ({@code initialLoad}) skip side effects (invitations, notifications, retro triggers) and
     * rebuild projections (ADR-0037 §8).
     */
    public record ImportCommitted(UUID tenantId, UUID jobId, String importType, String ownerModule, String mode,
            boolean initialLoad, int committedRows) {
    }

    /** {@code core.ImportRolledBack} (BR-CORE-110): a committed job was undone within its 24-hour window. */
    public record ImportRolledBack(UUID tenantId, UUID jobId, String importType, String ownerModule) {
    }

    /** {@code core.UserStatusChanged}: user created / activated / deactivated / identity relinked. */
    public record UserStatusChanged(UUID tenantId, UUID userAccountId, String status, String identityId) {
    }
}
