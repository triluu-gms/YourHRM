package vn.yourhrm.attendance.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import vn.yourhrm.attendance.api.AttendanceJobs;
import vn.yourhrm.attendance.api.PayrollInbound;
import vn.yourhrm.support.IntegrationTest;

/**
 * Base of the T&amp;A performance tests (tag {@code perf}: excluded from {@code test}, run by {@code perfTest}).
 * Budgets are defined for 10,000 employees (BRD 03 §9) and scaled linearly to {@code -Dperf.employees}; with
 * {@code -Dperf.enforce=false} the timings are only reported. Every measurement is printed and appended to
 * {@code -Dperf.reportFile} (default {@code build/perf/att-perf.csv}).
 */
@Tag("perf")
public abstract class PerfTestBase extends IntegrationTest {

    /** The NFR budgets are stated for a tenant of this size. */
    public static final int REFERENCE_EMPLOYEES = 10_000;

    @Autowired
    protected PayrollInbound payroll;

    @Autowired
    protected AttendanceJobs jobs;

    protected PerfTenantGenerator.Tenant seed(String slug) throws Exception {
        PerfTenantGenerator.Config config = PerfTenantGenerator.Config.fromSystemProperties();
        PerfTenantGenerator.Tenant t = new PerfTenantGenerator(ADMIN, FIX, mvc, IntegrationTest::as, payroll, jobs)
                .generate(slug, config);
        log("seed", slug, Duration.ofMillis(t.seedMillis()), null, t.counts().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" ")));
        return t;
    }

    /** The budget of {@code reference} (for 10,000 employees) scaled linearly to {@code employees}. */
    protected static Duration scaled(Duration reference, long employees) {
        return Duration.ofMillis(Math.max(1, reference.toMillis() * employees / REFERENCE_EMPLOYEES));
    }

    protected static boolean enforce() {
        return !"false".equalsIgnoreCase(System.getProperty("perf.enforce", "true"));
    }

    /** Asserts {@code measured <= budget} unless {@code -Dperf.enforce=false}. */
    protected static void assertWithin(String nfr, Duration measured, Duration budget) {
        if (enforce()) {
            assertThat(measured).as("%s: %s measured, budget %s", nfr, measured, budget).isLessThanOrEqualTo(budget);
        }
    }

    protected static Duration since(long nanoStart) {
        return Duration.ofNanos(System.nanoTime() - nanoStart);
    }

    /** Prints one measurement and appends it to the CSV report. */
    protected static void log(String nfr, String step, Duration measured, Duration budget, String details) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("at", Instant.now().toString());
        row.put("nfr", nfr);
        row.put("step", step);
        row.put("employees", System.getProperty("perf.employees", "1000"));
        row.put("measured_ms", String.valueOf(measured.toMillis()));
        row.put("budget_ms", budget == null ? "" : String.valueOf(budget.toMillis()));
        row.put("details", details == null ? "" : details.replace(',', ';'));
        System.out.printf("[perf] %-12s %-28s %10d ms%s  %s%n", nfr, step, measured.toMillis(),
                budget == null ? "" : " (budget " + budget.toMillis() + " ms)", row.get("details"));
        Path file = Path.of(System.getProperty("perf.reportFile", "build/perf/att-perf.csv"));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (!Files.exists(file)) {
                Files.writeString(file, String.join(",", row.keySet()) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }
            Files.writeString(file, String.join(",", row.values()) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[perf] could not write " + file + ": " + e);
        }
    }
}
