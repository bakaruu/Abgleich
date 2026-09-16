package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.in.sftp.SftpFolders;
import dev.abgleich.adapter.in.sftp.SftpStatementWatcher;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import java.time.Clock;
import java.time.Duration;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;

/**
 * The SFTP statement drop (B25), off unless {@code abgleich.sftp.enabled=true}. The server's host key is checked
 * against a known hosts file; accepting unknown keys is only for local containers that create a new key on
 * every start.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "abgleich.sftp.enabled", havingValue = "true")
class SftpConfiguration {

    @Bean
    DefaultSftpSessionFactory sftpSessionFactory(
            @Value("${abgleich.sftp.host}") String host,
            @Value("${abgleich.sftp.port}") int port,
            @Value("${abgleich.sftp.user}") String user,
            @Value("${abgleich.sftp.password:}") String password,
            @Value("${abgleich.sftp.private-key:}") Resource privateKey,
            @Value("${abgleich.sftp.known-hosts:}") Resource knownHosts,
            @Value("${abgleich.sftp.allow-unknown-keys:false}") boolean allowUnknownKeys,
            @Value("${abgleich.sftp.timeout:PT30S}") Duration timeout) {
        DefaultSftpSessionFactory sessions = new DefaultSftpSessionFactory(false);
        sessions.setHost(host);
        sessions.setPort(port);
        sessions.setUser(user);
        if (!password.isEmpty()) {
            sessions.setPassword(password);
        }
        if (privateKey != null && privateKey.exists()) {
            sessions.setPrivateKey(privateKey);
        } else {
            // Without this, the SSH client offers every key it finds in the user's ~/.ssh folder to the server.
            sessions.setSshClientConfigurer(client -> client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER));
        }
        if (knownHosts != null && knownHosts.exists()) {
            sessions.setKnownHostsResource(knownHosts);
        }
        sessions.setAllowUnknownKeys(allowUnknownKeys);
        sessions.setTimeout((int) timeout.toMillis());
        return sessions;
    }

    @Bean
    SftpStatementWatcher sftpStatementWatcher(DefaultSftpSessionFactory sessions,
            @Value("${abgleich.sftp.root}") String root,
            @Value("${abgleich.sftp.max-files-per-poll}") int maxFilesPerPoll,
            ProcessStatementUseCase processStatement, Clock clock) {
        return new SftpStatementWatcher(sessions, SftpFolders.under(root), processStatement, clock, maxFilesPerPoll);
    }
}
