package dev.abgleich.adapter.in.scheduler;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Gives one scheduled run its own id, so its log lines can be told apart from the run before it and from the
 * requests happening at the same time. Web requests get theirs from the incoming header instead; the key is the
 * same in both, and each entry point sets it for itself because adapters share no code (B40).
 */
final class CorrelatedRun {

    static final String MDC_KEY = "correlation.id";

    private CorrelatedRun() {
    }

    static void withId(String job, Runnable body) {
        MDC.put(MDC_KEY, job + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        try {
            body.run();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
