package dev.abgleich.adapter.in.sftp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;

/**
 * A real SFTP server on a random local port, serving a temporary folder. Tests write into the folder directly
 * to create exactly the situations a bank upload produces, such as a file that is still being written.
 */
public final class EmbeddedSftpServer implements AutoCloseable {

    public static final String USER = "bank";
    public static final String PASSWORD = "sftp-test-only";

    private final SshServer server;
    private final Path root;
    private final List<String> offeredPublicKeys;

    private EmbeddedSftpServer(SshServer server, Path root, List<String> offeredPublicKeys) {
        this.server = server;
        this.root = root;
        this.offeredPublicKeys = offeredPublicKeys;
    }

    public static EmbeddedSftpServer start() {
        try {
            Path root = Files.createTempDirectory("abgleich-sftp-");
            Files.createDirectories(root.resolve("inbox"));
            SshServer server = SshServer.setUpDefaultServer();
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
            server.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
            List<String> offered = new CopyOnWriteArrayList<>();
            server.setPublickeyAuthenticator((user, key, session) -> {
                offered.add(KeyUtils.getFingerPrint(key));
                return false;
            });
            server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
            server.setFileSystemFactory(new VirtualFileSystemFactory(root));
            server.start();
            return new EmbeddedSftpServer(server, root, offered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int port() {
        return server.getPort();
    }

    /** The local folder behind the remote {@code /}. */
    public Path root() {
        return root;
    }

    public Path inbox() {
        return root.resolve("inbox");
    }

    /** Fingerprints of the public keys clients tried to log in with; the server accepts only the password. */
    public List<String> offeredPublicKeys() {
        return List.copyOf(offeredPublicKeys);
    }

    /** Sessions that trust the generated host key; production configuration checks known hosts. */
    public DefaultSftpSessionFactory sessionFactory() {
        DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory(false);
        factory.setHost("127.0.0.1");
        factory.setPort(port());
        factory.setUser(USER);
        factory.setPassword(PASSWORD);
        factory.setAllowUnknownKeys(true);
        return factory;
    }

    /** Empties the inbox, processed and error folders. */
    public void clear() {
        for (String folder : List.of("inbox", "processed", "error")) {
            Path path = root.resolve(folder);
            if (Files.isDirectory(path)) {
                try (var files = Files.list(path)) {
                    for (Path file : files.toList()) {
                        Files.delete(file);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            server.stop(true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
