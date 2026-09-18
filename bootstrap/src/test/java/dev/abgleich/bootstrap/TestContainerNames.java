package dev.abgleich.bootstrap;

import java.util.UUID;

/**
 * Descriptive container names for tests, with a short suffix.
 *
 * <p>A fixed name reads well in {@code docker ps}, but it also means a container left behind by an earlier run,
 * a parallel job or a failed startup attempt blocks the next one — and Testcontainers retries startup, so the
 * second attempt would fail for a different reason than the first, hiding the real problem.
 */
final class TestContainerNames {

    private TestContainerNames() {
    }

    static String of(String what) {
        return "abgleich-test-" + what + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
