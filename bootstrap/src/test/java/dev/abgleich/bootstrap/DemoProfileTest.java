package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

/** The settings the public demo runs with, read from the files it is started with. */
class DemoProfileTest {

    @Test
    void B43_demo_data_is_purged_nightly() throws IOException {
        CronExpression reset = CronExpression.parse(defaults("abgleich.demo.reset-cron"));
        LocalDateTime first = reset.next(LocalDateTime.of(2026, 11, 20, 12, 0));
        LocalDateTime second = reset.next(first);

        assertThat(Duration.between(first, second)).as("every day, so nothing lives longer than 24 h").hasHours(24);
        assertThat(first.getHour()).as("at night (UTC)").isBetween(0, 5);
        assertThat(demo("abgleich.demo.enabled")).isEqualTo("true");
    }

    @Test
    void B44_the_demo_limits_changes_and_offers_no_file_channels() throws IOException {
        assertThat(demo("abgleich.rate-limit.enabled")).isEqualTo("true");
        assertThat(demo("abgleich.sftp.enabled")).isEqualTo("false");
        assertThat(demo("abgleich.bank-api.enabled")).isEqualTo("false");
        assertThat(demo("management.server.port")).as("metrics not on the public port").isEqualTo("8081");
        assertThat(defaults("abgleich.demo.max-stored-data")).isEqualTo("500MB");
    }

    private static String demo(String key) throws IOException {
        return value("application-demo.yml", key);
    }

    private static String defaults(String key) throws IOException {
        return value("application.yml", key);
    }

    private static String value(String file, String key) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return sources.stream().map(source -> source.getProperty(key)).filter(value -> value != null)
                .map(Object::toString).findFirst().orElseThrow(() -> new AssertionError(key + " missing in " + file));
    }
}
