package vn.yourhrm.attendance.internal.infra.jobs;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.yourhrm.attendance.internal.infra.AttDb;
import vn.yourhrm.shared.common.tenancy.PlatformContext;
import vn.yourhrm.shared.common.tenancy.TenantContext;

/**
 * SQL of T&amp;A's nightly job. The tenant list and the partition maintenance are not tenant work: they run as platform
 * work ({@link PlatformContext}, empty tenant context) through the SECURITY DEFINER functions
 * {@code att.job_tenants()} and {@code att.maintain_partitions(int)} (DDL v0.9). Everything else runs inside the
 * caller's T&amp;A transaction with a bound tenant.
 */
@Component
public class AttendanceJobStore {

    private final AttDb db;
    private final TransactionTemplate tx;

    public AttendanceJobStore(AttDb db, @Qualifier(AttDb.TX) PlatformTransactionManager attTm) {
        this.db = db;
        this.tx = new TransactionTemplate(attTm);
    }

    /** Tenant ids with T&amp;A data (platform work). */
    public List<UUID> tenants() {
        return platform("att-job-tenants", () -> db.jdbc().sql("select * from att.job_tenants()")
                .query(UUID.class).list());
    }

    /** Creates the missing monthly partitions up to {@code monthsAhead}; returns how many were created. */
    public int maintainPartitions(int monthsAhead) {
        return platform("att-partitions", () -> db.jdbc().sql("select att.maintain_partitions(:n)")
                .param("n", monthsAhead).query(Integer.class).single());
    }

    /**
     * Employments projected into T&amp;A that are employed on some day of {@code [from, to]} (current tenant; own
     * read transaction).
     */
    public List<UUID> employmentsBetween(LocalDate from, LocalDate to) {
        return tx.execute(s -> db.jdbc().sql("""
                        select employment_id from att.ext_hr_employment
                         where tenant_id = :t and status <> 'CANCELLED' and hire_date <= :to
                           and (termination_date is null or termination_date >= :f)
                         order by employment_id""")
                .param("t", TenantContext.current().tenantId()).param("f", from).param("to", to)
                .query(UUID.class).list());
    }

    /** Start of the earliest OPEN / IN_REVIEW period of the current tenant (own read transaction). */
    public Optional<LocalDate> earliestOpenPeriodStart() {
        return tx.execute(s -> db.jdbc().sql("""
                        select min(period_start) from att.attendance_period
                         where tenant_id = :t and status in ('OPEN', 'IN_REVIEW')""")
                .param("t", TenantContext.current().tenantId()).query(LocalDate.class).list().stream()
                .filter(java.util.Objects::nonNull).findFirst());
    }

    private <T> T platform(String purpose, java.util.function.Supplier<T> work) {
        try {
            return PlatformContext.call(purpose, () -> tx.execute(s -> work.get()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
