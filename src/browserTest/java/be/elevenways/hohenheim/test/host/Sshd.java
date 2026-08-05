package be.elevenways.hohenheim.test.host;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A throwaway OpenSSH server on loopback whose host key a test can replace and whose
 * authorized_keys a test owns entirely -- the real binary, so nothing about host-key
 * verification or public-key authentication is stubbed.
 *
 * AIDEV-NOTE: it runs as the TEST user and can therefore only accept logins as that
 * same user, which is exactly what the pinned lane needs to reach a real shell. The
 * only simulated thing is the REASON a host key changes ({@link #regenerateHostKey}
 * makes a new one where an attacker would have substituted it); the ssh client cannot
 * tell those apart, which is the point.
 */
public final class Sshd {

    private final Path directory;
    private final int port;
    private Process process;

    private Sshd(Path directory, int port) {
        this.directory = directory;
        this.port = port;
    }

    /** Whether this machine has the binaries every ssh-lane test needs. */
    public static boolean available() {
        return Files.isExecutable(Path.of("/usr/sbin/sshd"))
            && Files.isExecutable(Path.of("/usr/bin/ssh"))
            && Files.isExecutable(Path.of("/usr/bin/ssh-keygen"))
            && Files.isExecutable(Path.of("/usr/bin/ssh-keyscan"));
    }

    public static Sshd start(Path directory) throws IOException {
        Files.createDirectories(directory);
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Path authorizedKeys = directory.resolve("authorized_keys");
        Files.writeString(authorizedKeys, "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("sshd_config"), "Port " + port + "\n"
            + "ListenAddress 127.0.0.1\n"
            + "HostKey " + directory.resolve("host_key") + "\n"
            + "StrictModes no\n"
            + "UsePAM no\n"
            + "PermitRootLogin no\n"
            + "PasswordAuthentication no\n"
            + "KbdInteractiveAuthentication no\n"
            + "AuthorizedKeysFile " + authorizedKeys + "\n", StandardCharsets.UTF_8);
        Sshd sshd = new Sshd(directory, port);
        sshd.writeHostKey();
        sshd.spawn();
        return sshd;
    }

    public int port() {
        return this.port;
    }

    /** The {@code [user@]host:port} target a host record addresses this server by. */
    public String target() {
        return System.getProperty("user.name") + "@127.0.0.1:" + this.port;
    }

    /** Let one public key line log in; replaces whatever was authorized before. */
    public void authorize(String publicKeyLine) throws IOException {
        Files.writeString(this.directory.resolve("authorized_keys"),
            publicKeyLine.trim() + "\n", StandardCharsets.UTF_8);
    }

    private void spawn() throws IOException {
        // AIDEV-NOTE: wait for the port to go QUIET first. sshd's per-connection
        // children inherit the listening socket, so a lingering child keeps the port
        // bound after the master is killed; the replacement master then loses the
        // bind while the port still ACCEPTS nothing, which reaches the test as an
        // empty-stderr "unreachable" instead of the failure it is measuring.
        waitUntilClosed();
        this.process = new ProcessBuilder("/usr/sbin/sshd", "-D",
            "-f", this.directory.resolve("sshd_config").toString())
            .redirectErrorStream(true)
            .redirectOutput(this.directory.resolve("sshd.log").toFile())
            .start();
        waitUntilListening();
        if (!this.process.isAlive()) {
            throw new IOException("sshd exited immediately: "
                + Files.readString(this.directory.resolve("sshd.log"), StandardCharsets.UTF_8));
        }
    }

    private void waitUntilClosed() throws IOException {
        for (int attempt = 0; attempt < 200; attempt++) {
            try (Socket socket = new Socket("127.0.0.1", this.port)) {
                if (!socket.isConnected()) {
                    return;
                }
            } catch (IOException expected) {
                return;   // nothing is bound any more
            }
            sleep();
        }
        throw new IOException("port " + this.port + " never became free");
    }

    private void waitUntilListening() throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            try (Socket socket = new Socket("127.0.0.1", this.port)) {
                if (socket.isConnected()) {
                    return;
                }
            } catch (IOException e) {
                last = e;
            }
            sleep();
        }
        throw new IOException("sshd never listened on 127.0.0.1:" + this.port, last);
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeHostKey() throws IOException {
        Path key = this.directory.resolve("host_key");
        Files.deleteIfExists(key);
        Files.deleteIfExists(this.directory.resolve("host_key.pub"));
        run(List.of("/usr/bin/ssh-keygen", "-q", "-t", "ed25519", "-N", "",
            "-f", key.toString()));
    }

    /** Replace the running server's host key -- what a substituted host looks like. */
    public void regenerateHostKey() {
        try {
            stop();
            writeHostKey();
            spawn();
        } catch (IOException e) {
            throw new IllegalStateException("could not rotate the sshd host key", e);
        }
    }

    /** The fingerprint ssh-keygen itself reports for the CURRENT host key. */
    public String hostKeyFingerprint() {
        String output = run(List.of("/usr/bin/ssh-keygen", "-l", "-f",
            this.directory.resolve("host_key.pub").toString()));
        for (String token : output.trim().split("\\s+")) {
            if (token.startsWith("SHA256:")) {
                return token;
            }
        }
        throw new IllegalStateException("ssh-keygen -l printed no fingerprint: " + output);
    }

    public void stop() {
        if (this.process == null) {
            return;
        }
        this.process.destroyForcibly();
        try {
            this.process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        this.process = null;
    }

    private static String run(List<String> argv) {
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("could not run " + argv, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + argv, e);
        }
    }
}
