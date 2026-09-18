package dev.abgleich.bootstrap;

import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * One Kafka broker for every Spring Boot test, created once like the PostgreSQL container.
 *
 * <p>The plain image, not {@code apache/kafka-native}: the native build died on the CI runner with exit code 1
 * before writing a single line, which is the kind of failure a GraalVM binary hits when the host's kernel or
 * seccomp profile differs from the one it was built against. A few seconds of JVM startup are worth a build that
 * does not depend on the runner. The demo deployment keeps the native image, where the smaller memory footprint
 * is what matters and the host is known.
 *
 * <p>Its output goes to the test log, so a broker that refuses to start says why instead of leaving a timeout,
 * and the name carries a suffix so a container left behind by an earlier attempt never blocks the next one.
 */
@TestConfiguration(proxyBeanMethods = false)
class KafkaTestConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.2.1")
                .withStartupTimeout(Duration.ofMinutes(2))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("abgleich-test-kafka")))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(TestContainerNames.of("kafka-application")));
    }
}
