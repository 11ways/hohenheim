package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.async.AsyncFailures;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MINA's own SFTP client as the journeys sign in with it: to Hohenheim's server on the loopback, by password or by key,
 * trusting whatever host key the test server answers with.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class SftpTestClient implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String LOOPBACK = "127.0.0.1";

    private final SshClient client;

    private SftpTestClient(@NonNull SshClient client) {
        this.client = client;
    }

    /** @return a started client offering no key of its own */
    public static @NonNull SftpTestClient start() {
        SshClient client = SshClient.setUpDefaultClient();
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setServerKeyVerifier((session, address, key) -> true);
        client.start();
        return new SftpTestClient(client);
    }

    /** @return a session signed in with the password */
    public @NonNull Connection signIn(int port, @NonNull String username, @NonNull String password) throws IOException {
        return this.connect(port, username, session -> session.addPasswordIdentity(password));
    }

    /** @return a session signed in with the key */
    public @NonNull Connection signIn(int port, @NonNull String username, @NonNull KeyPair key) throws IOException {
        return this.connect(port, username, session -> session.addPublicKeyIdentity(key));
    }

    private @NonNull Connection connect(int port, @NonNull String username, @NonNull Consumer<ClientSession> identity)
        throws IOException {
        ClientSession session = this.client.connect(username, LOOPBACK, port).verify(WAIT).getSession();
        try {
            identity.accept(session);
            session.auth().verify(WAIT);
            return new Connection(session, SftpClientFactory.instance().createSftpClient(session));
        } catch (IOException | RuntimeException failure) {
            session.close(true);
            throw failure;
        }
    }

    @Override
    public void close() {
        this.client.stop();
    }

    /** @return the SFTP status a client call failed with; a listing reports it wrapped in an unchecked exception */
    public static @NonNull SftpException refusal(@Nullable Throwable thrown) {
        SftpException refusal = thrown == null ? null : AsyncFailures.first(thrown, SftpException.class);
        assertThat(refusal).as("the call failed with an SFTP status").isNotNull();
        return refusal;
    }

    /** @return the entry names of a directory, without "." and ".." */
    public static @NonNull List<String> names(@NonNull SftpClient sftp, @NonNull String path) throws IOException {
        List<String> names = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            if (!".".equals(entry.getFilename()) && !"..".equals(entry.getFilename())) {
                names.add(entry.getFilename());
            }
        }
        return names;
    }

    public static @NonNull String readText(@NonNull SftpClient sftp, @NonNull String path) throws IOException {
        return new String(readBytes(sftp, path), StandardCharsets.UTF_8);
    }

    public static byte @NonNull [] readBytes(@NonNull SftpClient sftp, @NonNull String path) throws IOException {
        try (InputStream in = sftp.read(path)) {
            return in.readAllBytes();
        }
    }

    public static void writeBytes(@NonNull SftpClient sftp, @NonNull String path, byte @NonNull [] content)
        throws IOException {
        try (OutputStream out = sftp.write(path)) {
            out.write(content);
        }
    }

    /** One signed-in client connection with its SFTP channel. */
    public record Connection(@NonNull ClientSession session, @NonNull SftpClient sftp) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                this.sftp.close();
            } catch (IOException ended) {
                // The server already ended the session.
            }
            this.session.close(true);
        }
    }
}
