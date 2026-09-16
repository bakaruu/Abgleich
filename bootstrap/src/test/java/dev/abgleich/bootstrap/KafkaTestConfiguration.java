package dev.abgleich.bootstrap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/** One Kafka broker for every Spring Boot test, created once like the PostgreSQL container. */
@TestConfiguration(proxyBeanMethods = false)
class KafkaTestConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka-native:4.2.1")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("abgleich-test-kafka-application"));
    }
}
