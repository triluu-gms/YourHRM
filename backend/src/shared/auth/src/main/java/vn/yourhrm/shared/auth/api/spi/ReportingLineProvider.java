package vn.yourhrm.shared.auth.api.spi;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * SPI for the reporting line (US-CORE-036): who reports to an employment as of a date. Needed to
 * resolve the SELF-adjacent data scopes DIRECT_REPORTS and ALL_REPORTS. The data (assignments, org
 * managers) is owned by People, so People implements this SPI; Core consumes it (consumer-declared SPI,
 * ADR-0001 decision 3).
 *
 * <p>Until the People module exists, Core falls back to {@link #NONE}: no reports, i.e. DIRECT_REPORTS
 * and ALL_REPORTS grants resolve to an empty scope (fail closed).
 */
public interface ReportingLineProvider {

    /** Employments whose direct manager is {@code managerEmploymentId} on {@code asOf}. */
    Set<UUID> directReports(UUID tenantId, UUID managerEmploymentId, LocalDate asOf);

    /** All employments below {@code managerEmploymentId} (transitively) on {@code asOf}. */
    Set<UUID> allReports(UUID tenantId, UUID managerEmploymentId, LocalDate asOf);

    /**
     * STUB until People exists (US-CORE-036 depends on PPL:AssignmentChanged / PPL:assignment-lookup):
     * returns no reports for anybody.
     */
    ReportingLineProvider NONE = new ReportingLineProvider() {
        @Override
        public Set<UUID> directReports(UUID tenantId, UUID managerEmploymentId, LocalDate asOf) {
            return Set.of();
        }

        @Override
        public Set<UUID> allReports(UUID tenantId, UUID managerEmploymentId, LocalDate asOf) {
            return Set.of();
        }

        @Override
        public String toString() {
            return "ReportingLineProvider.NONE (stub until the People module provides reporting lines)";
        }
    };
}
