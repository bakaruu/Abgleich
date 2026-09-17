package dev.abgleich.adapter.in.scheduler;

import dev.abgleich.application.port.in.PurgePublishedEventsUseCase;
import java.util.Objects;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes events published longer ago than the retention period, once a day.
 *
 * <p>Not final: ShedLock wraps the scheduled method in a proxy.
 */
public class OutboxRetentionJob {

    static final String LOCK = "outbox-retention";
    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);

    private final PurgePublishedEventsUseCase purgePublishedEvents;

    public OutboxRetentionJob(PurgePublishedEventsUseCase purgePublishedEvents) {
        this.purgePublishedEvents = Objects.requireNonNull(purgePublishedEvents, "purgePublishedEvents");
    }

    @Scheduled(cron = "${abgleich.outbox.retention-cron}", zone = "UTC")
    @SchedulerLock(name = LOCK, lockAtMostFor = "PT30M")
    public void run() {
        int deleted = purgePublishedEvents.purgePublished();
        if (deleted > 0) {
            log.info("Outbox retention: {} published events deleted", deleted);
        }
    }
}
