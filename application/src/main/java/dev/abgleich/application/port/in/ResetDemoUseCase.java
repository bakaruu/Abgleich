package dev.abgleich.application.port.in;

import dev.abgleich.application.port.in.ExampleDataUseCase.ExampleLoaded;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the public demo clean. Visitors may upload files that are not synthetic, whatever the banner says, so
 * nothing they store lives longer than one day (B43), and the stored data never outgrows its quota (B44).
 */
public interface ResetDemoUseCase {

    /** Deletes all data and loads the example again, so the next visitor finds a working demo. */
    DemoReset reset();

    /** Resets only if the stored data takes more than {@code maxBytes}. */
    Optional<DemoReset> resetIfLargerThan(long maxBytes);

    /** @param bytesBefore bytes the data occupied before the reset */
    record DemoReset(long bytesBefore, ExampleLoaded seeded) {
        public DemoReset {
            Objects.requireNonNull(seeded, "seeded");
        }
    }
}
