package dev.abgleich.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ResetDemoUseCase.DemoReset;
import dev.abgleich.application.port.out.DemoDataPort;
import dev.abgleich.application.port.out.ExampleFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DemoResetServiceTest {

    private final List<String> calls = new ArrayList<>();
    private long storedBytes = 5_000;

    private final DemoDataPort data = new DemoDataPort() {
        @Override
        public void deleteAllData() {
            calls.add("delete");
            storedBytes = 0;
        }

        @Override
        public long storedBytes() {
            return storedBytes;
        }
    };

    private final ExampleDataUseCase examples = new ExampleDataUseCase() {
        @Override
        public ExampleLoaded load() {
            calls.add("seed");
            return new ExampleLoaded(16, 0, List.of());
        }

        @Override
        public ExampleLoaded load(String country) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ExampleFile> files() {
            return List.of();
        }

        @Override
        public Optional<ExampleFile> file(String name) {
            return Optional.empty();
        }
    };

    private final DemoResetService service = new DemoResetService(data, examples);

    @Test
    void B43_reset_deletes_everything_before_seeding_the_example_again() {
        DemoReset reset = service.reset();

        assertThat(calls).containsExactly("delete", "seed");
        assertThat(reset.bytesBefore()).isEqualTo(5_000);
        assertThat(reset.seeded().invoicesRegistered()).isEqualTo(16);
    }

    @Test
    void B44_data_over_the_quota_is_reset_and_data_within_it_is_left_alone() {
        assertThat(service.resetIfLargerThan(10_000)).isEmpty();
        assertThat(calls).isEmpty();

        assertThat(service.resetIfLargerThan(4_999)).isPresent();
        assertThat(calls).containsExactly("delete", "seed");
        assertThatThrownBy(() -> service.resetIfLargerThan(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
