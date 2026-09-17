package dev.abgleich.bootstrap.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed window per client: at most {@code maxRequests} per {@code window}. Enough for a demo on one instance;
 * the count lives in memory, so it restarts with the application and is not shared between instances.
 *
 * <p>The number of tracked clients is bounded, so a flood of different addresses cannot exhaust memory. When
 * the table is full even after dropping expired windows, new clients are refused until windows expire: the
 * demo stays up and writable for the clients already known.
 */
public final class RequestRateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final int maxTrackedClients;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RequestRateLimiter(int maxRequests, Duration window, Clock clock, int maxTrackedClients) {
        if (maxRequests < 1 || maxTrackedClients < 1) {
            throw new IllegalArgumentException("maxRequests and maxTrackedClients must be at least 1");
        }
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxTrackedClients = maxTrackedClients;
    }

    public Decision tryAcquire(String client) {
        Objects.requireNonNull(client, "client");
        Instant now = clock.instant();
        if (!windows.containsKey(client) && windows.size() >= maxTrackedClients) {
            windows.values().removeIf(tracked -> tracked.hasEnded(now, window));
            if (windows.size() >= maxTrackedClients) {
                return new Decision(false, window.toSeconds());
            }
        }
        Window current = windows.compute(client, (key, tracked) -> tracked == null || tracked.hasEnded(now, window)
                ? new Window(now, 1)
                : new Window(tracked.start(), tracked.requests() + 1));
        if (current.requests() <= maxRequests) {
            return new Decision(true, 0);
        }
        long secondsLeft = Duration.between(now, current.start().plus(window)).toSeconds();
        return new Decision(false, Math.max(1, secondsLeft));
    }

    /** @param retryAfterSeconds when a refused client may try again */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(Instant start, int requests) {
        boolean hasEnded(Instant now, Duration length) {
            return !now.isBefore(start.plus(length));
        }
    }
}
