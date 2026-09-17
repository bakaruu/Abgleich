package dev.abgleich.application.events.port.in;

/**
 * Keeps the outbox small. Published events are kept for a while to answer "was it sent, and when?", then
 * deleted. Unpublished events are never deleted, however old they are (B23).
 */
public interface PurgePublishedEventsUseCase {

    /** @return the number of deleted events */
    int purgePublished();
}
