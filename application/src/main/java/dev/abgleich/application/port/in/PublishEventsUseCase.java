package dev.abgleich.application.port.in;

/**
 * Publishes the events waiting in the outbox (B23). Safe to run at any time: an event is marked published
 * only after the broker acknowledged it, so a crash in between publishes it again rather than never.
 */
public interface PublishEventsUseCase {

    PublishRun publishPending();

    /**
     * @param published events the broker acknowledged
     * @param failed 1 when an event could not be published; the run stops there so events keep their order
     * @param stillPending events left in the outbox after the run
     */
    record PublishRun(int published, int failed, long stillPending) {
    }
}
