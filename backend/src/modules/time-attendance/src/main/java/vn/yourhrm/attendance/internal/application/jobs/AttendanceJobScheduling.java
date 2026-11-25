package vn.yourhrm.attendance.internal.application.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import vn.yourhrm.shared.common.time.BusinessClock;

/**
 * T&amp;A's stand-in for the platform job scheduler (SaaS, not built yet), like Core's audit sealer: when
 * {@code yhrm.attendance.jobs.enabled=true}, partitions + the nightly run of every tenant at 01:30 and the reminders
 * at 07:00, Vietnam time (cron overridable). One application instance should enable it; the runs are idempotent, so a
 * second instance only repeats work. The integration tests switch it off and call {@link NightlyJobService} directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "yhrm.attendance.jobs.enabled", havingValue = "true")
class AttendanceJobScheduling {

    private static final Logger log = LoggerFactory.getLogger(AttendanceJobScheduling.class);

    private final NightlyJobService jobs;
    private final BusinessClock clock;

    AttendanceJobScheduling(NightlyJobService jobs, BusinessClock clock) {
        this.jobs = jobs;
        this.clock = clock;
        log.info("T&A job scheduler enabled (nightly run and reminders)");
    }

    @Scheduled(cron = "${yhrm.attendance.jobs.nightly-cron:0 30 1 * * *}", zone = "Asia/Ho_Chi_Minh")
    void nightly() {
        jobs.runAllTenants(clock.today());
    }

    @Scheduled(cron = "${yhrm.attendance.jobs.reminder-cron:0 0 7 * * *}", zone = "Asia/Ho_Chi_Minh")
    void reminders() {
        jobs.remindAllTenants(clock.today());
    }
}
