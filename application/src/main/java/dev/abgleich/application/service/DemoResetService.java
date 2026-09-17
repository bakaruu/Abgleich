package dev.abgleich.application.service;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ResetDemoUseCase;
import dev.abgleich.application.port.out.DemoDataPort;
import java.util.Objects;
import java.util.Optional;

public final class DemoResetService implements ResetDemoUseCase {

    private final DemoDataPort data;
    private final ExampleDataUseCase examples;

    public DemoResetService(DemoDataPort data, ExampleDataUseCase examples) {
        this.data = Objects.requireNonNull(data, "data");
        this.examples = Objects.requireNonNull(examples, "examples");
    }

    @Override
    public DemoReset reset() {
        long bytesBefore = data.storedBytes();
        data.deleteAllData();
        return new DemoReset(bytesBefore, examples.load());
    }

    @Override
    public Optional<DemoReset> resetIfLargerThan(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        return data.storedBytes() > maxBytes ? Optional.of(reset()) : Optional.empty();
    }
}
