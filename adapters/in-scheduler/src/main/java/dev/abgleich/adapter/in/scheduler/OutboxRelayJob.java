package dev.abgleich.adapter.in.scheduler;

import dev.abgleich.application.events.port.in.PublishEventsUseCase;
import dev.abgleich.application.events.port.in.PublishEventsUseCase.PublishRun;
import java.util.Objects;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Publishes outbox events every few seconds (B23). One instance relays at a time (B26), which also keeps
 * the events in the order they were stored.
 *
 * <p>Not final: ShedLock wraps the scheduled method in a proxy.
 */
public class OutboxRelayJob {

    static final String LOCK = "outbox-relay";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final PublishEventsUseCase publishEvents;

    public OutboxRelayJob(PublishEventsUseCase publishEvents) {
        this.publishEvents = Objects.requireNonNull(publishEvents, "publishEvents");
    }

    @Scheduled(fixedDelayString = "${abgleich.outbox.relay-interval}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "${abgleich.outbox.lock-at-most-for:PT5M}")
    public void run() {
        CorrelatedRun.withId("outbox-relay", () -> {
            PublishRun run = publishEvents.publishPending();
            if (run.failed() > 0) {
                log.warn("Outbox relay: {} published, publishing failed, {} events pending", run.published(),
                        run.stillPending());
            } else if (run.published() > 0) {
                log.info("Outbox relay: {} published, {} events pending", run.published(), run.stillPending());
            }
        });
    }
}
