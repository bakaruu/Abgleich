package dev.abgleich.adapter.in.scheduler;

import dev.abgleich.application.example.port.in.ResetDemoUseCase;
import dev.abgleich.application.example.port.in.ResetDemoUseCase.DemoReset;
import java.util.Objects;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Public demo only. Every night all data is deleted and the example loaded again, so nothing a visitor stored
 * lives longer than a day (B43). Between two nights, data over the disk quota triggers the same reset (B44).
 * Logs contain sizes and counts, never what visitors uploaded.
 *
 * <p>Not final: ShedLock wraps the scheduled methods in a proxy.
 */
public class DemoResetJob {

    static final String NIGHTLY_LOCK = "demo-nightly-reset";
    static final String QUOTA_LOCK = "demo-quota-check";
    private static final Logger log = LoggerFactory.getLogger(DemoResetJob.class);

    private final ResetDemoUseCase resetDemo;
    private final long maxBytes;

    public DemoResetJob(ResetDemoUseCase resetDemo, long maxBytes) {
        this.resetDemo = Objects.requireNonNull(resetDemo, "resetDemo");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Scheduled(cron = "${abgleich.demo.reset-cron}", zone = "UTC")
    @SchedulerLock(name = NIGHTLY_LOCK, lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void resetNightly() {
        CorrelatedRun.withId("demo-reset", () -> {
            DemoReset reset = resetDemo.reset();
            log.info("Demo reset: {} MB deleted, {} example invoices loaded", reset.bytesBefore() / (1024 * 1024),
                    reset.seeded().invoicesRegistered());
        });
    }

    @Scheduled(fixedDelayString = "${abgleich.demo.quota-check-interval}",
            initialDelayString = "${abgleich.demo.quota-check-interval}")
    @SchedulerLock(name = QUOTA_LOCK, lockAtMostFor = "PT30M")
    public void resetIfOverQuota() {
        CorrelatedRun.withId("demo-quota", () -> {
            resetDemo.resetIfLargerThan(maxBytes).ifPresent(reset -> log.warn(
                    "Demo data reached {} MB, over the quota of {} MB: reset", reset.bytesBefore() / (1024 * 1024),
                    maxBytes / (1024 * 1024)));
        });
    }
}
