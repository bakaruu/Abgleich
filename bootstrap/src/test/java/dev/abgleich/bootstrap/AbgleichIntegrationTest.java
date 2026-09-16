package dev.abgleich.bootstrap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The whole application on a random port against PostgreSQL, Kafka, an SFTP server and the mock bank. Every
 * test class uses exactly this, so Spring caches one context and the named test containers are created once
 * per test run.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "abgleich.scheduling.enabled=false",
        "abgleich.sftp.enabled=true",
        "abgleich.sftp.host=127.0.0.1",
        "abgleich.sftp.user=bank",
        "abgleich.sftp.allow-unknown-keys=true",
        "abgleich.bank-api.enabled=true",
        "abgleich.bank-api.lock-at-least-for=PT0S",
        "abgleich.kafka.partitions=1"})
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, ChannelServersTestConfiguration.class})
@interface AbgleichIntegrationTest {
}
