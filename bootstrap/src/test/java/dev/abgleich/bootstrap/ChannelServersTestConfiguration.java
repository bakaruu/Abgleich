package dev.abgleich.bootstrap;

import dev.abgleich.adapter.in.sftp.EmbeddedSftpServer;
import dev.abgleich.mockbank.MockBank;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The outside systems of the SFTP and bank API channels: an SFTP server and the mock bank, both on random local
 * ports, with the application configured to use them. Scheduling is off in tests; tests trigger polls and
 * downloads themselves.
 */
@TestConfiguration(proxyBeanMethods = false)
class ChannelServersTestConfiguration {

    static final String BANK_TOKEN = "bank-api-test-only";

    @Bean(destroyMethod = "close")
    EmbeddedSftpServer embeddedSftpServer() {
        return EmbeddedSftpServer.start();
    }

    @Bean(destroyMethod = "close")
    MockBank mockBank() {
        return MockBank.start(0, BANK_TOKEN);
    }

    @Bean
    DynamicPropertyRegistrar channelServerProperties(EmbeddedSftpServer sftp, MockBank bank) {
        return registry -> {
            registry.add("abgleich.sftp.port", sftp::port);
            registry.add("abgleich.sftp.password", () -> EmbeddedSftpServer.PASSWORD);
            registry.add("abgleich.bank-api.base-url", bank::baseUrl);
            registry.add("abgleich.bank-api.token", () -> BANK_TOKEN);
        };
    }
}
