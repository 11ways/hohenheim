package be.elevenways.hohenheim.test.host;

import be.elevenways.protoblast.common.time.Backoff;
import be.elevenways.protoblast.common.time.Retry;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.server.process.SubprocessException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** Fresh ports tried before a lost bind race is reported as a failure. */
    private static final int BIND_ATTEMPTS = 5;

    /** Probes of this server's port: every 50 ms, 200 at most, again only after an I/O failure. */
    private static final Retry PORT_PROBES = Retry.of(Backoff.fixed(Duration.ofMillis(50)), 200)
        .judgedBy(failure -> failure instanceof IOException ? Retry.Verdict.AGAIN : Retry.Verdict.FINAL);

    private final Path directory;
    private final int port;
    private RunningProcess process;

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

    /**
     * Start a server on a free loopback port.
     *
     * AIDEV-NOTE: a free port can only be LEARNED by binding port 0 and closing it, and
     * sshd has no way to inherit that bound socket, so another process can take the port in
     * between. That race is survived by retrying on a fresh port whenever sshd itself
     * reports the bind lost, never by hoping: see {@link #spawn} for how a lost bind is told
     * apart from a server that is up.
     */
    public static Sshd start(Path directory) throws IOException {
        Files.createDirectories(directory);
        PortTaken lost = null;
        for (int attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
            int port;
            try (ServerSocket probe = new ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            try {
                return startOn(directory, port);
            } catch (PortTaken taken) {
                lost = taken;
            }
        }
        throw new IOException("sshd lost the bind race " + BIND_ATTEMPTS + " times in a row", lost);
    }

    private static Sshd startOn(Path directory, int port) throws IOException {
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
        // bound after the master is gone; the replacement master then loses the
        // bind while the port still ACCEPTS nothing, which reaches the test as an
        // empty-stderr "unreachable" instead of the failure it is measuring. The stop
        // takes the whole tree now, so this wait normally passes on its first probe.
        //
        // AIDEV-NOTE: -e sends sshd's log into the merged output, and the server is up only
        // once THAT process says it listens on OUR port. A bare TCP connect used to be the
        // readiness check, and it succeeds just as well against whichever process took the
        // port after the probe closed it; sshd then dies with "Address already in use"
        // and the test talks to a stranger. A lost bind is reported as PortTaken so
        // start() can retry on a fresh port.
        waitUntilClosed();
        SshdLog log = new SshdLog("Server listening on 127.0.0.1 port " + this.port);
        RunningProcess started;
        try {
            started = Subprocess.of("/usr/sbin/sshd", "-D", "-e",
                    "-f", this.directory.resolve("sshd_config").toString())
                .mergeStderr()
                .stdoutTo(log)
                .start();
        } catch (SubprocessException refused) {
            throw new IOException(refused.getMessage(), refused);
        }
        this.process = started;
        try {
            CompletableFuture.anyOf(log.listening, started.completion().toFuture()).get(10, TimeUnit.SECONDS);
        } catch (TimeoutException silent) {
            this.stop();
            throw new IOException("sshd neither listened on 127.0.0.1:" + this.port + " nor exited in 10 s: "
                + log.text(), silent);
        } catch (InterruptedException e) {
            this.stop();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for sshd", e);
        } catch (ExecutionException e) {
            this.stop();
            throw new IOException("sshd's start could not be observed", e.getCause());
        }
        if (!started.isAlive()) {
            started.await();
            this.process = null;
            String output = log.text();
            if (output.contains("Address already in use") || output.contains("Cannot bind")) {
                throw new PortTaken("port " + this.port + " was taken before sshd bound it: "
                    + output.trim());
            }
            throw new IOException("sshd exited immediately: " + output);
        }
        waitUntilListening();
    }

    /**
     * sshd's merged output: a bounded tail for failure messages and the moment its listening line arrived.
     *
     * AIDEV-NOTE: bytes are kept as ISO-8859-1 chars, one per byte, so a needle split across two writes still
     * matches; text() decodes the tail as UTF-8 again.
     */
    private static final class SshdLog extends OutputStream {

        private static final int TAIL_LIMIT = 64 * 1024;

        private final String needle;
        private final StringBuilder tail = new StringBuilder();
        final CompletableFuture<Void> listening = new CompletableFuture<>();

        SshdLog(String needle) {
            this.needle = needle;
        }

        @Override
        public void write(int b) {
            this.write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            int from = Math.max(0, this.tail.length() - this.needle.length());
            this.tail.append(new String(bytes, offset, length, StandardCharsets.ISO_8859_1));
            if (!this.listening.isDone() && this.tail.indexOf(this.needle, from) >= 0) {
                this.listening.complete(null);
            }
            if (this.tail.length() > TAIL_LIMIT) {
                this.tail.delete(0, this.tail.length() - TAIL_LIMIT);
            }
        }

        synchronized String text() {
            return new String(this.tail.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        }
    }

    /** sshd reported that another process holds its port. */
    private static final class PortTaken extends IOException {
        PortTaken(String message) {
            super(message);
        }
    }

    private void waitUntilClosed() throws IOException {
        PORT_PROBES.run(() -> {
            try (Socket socket = new Socket("127.0.0.1", this.port)) {
                if (!socket.isConnected()) {
                    return null;
                }
            } catch (IOException expected) {
                return null;   // nothing is bound any more
            }
            throw new PortTaken("port " + this.port + " never became free");
        });
    }

    private void waitUntilListening() throws IOException {
        try {
            PORT_PROBES.run(() -> {
                try (Socket socket = new Socket("127.0.0.1", this.port)) {
                    if (socket.isConnected()) {
                        return null;
                    }
                }
                throw new IOException("the connect reached nothing");
            });
        } catch (IOException last) {
            throw new IOException("sshd never listened on 127.0.0.1:" + this.port, last);
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
        this.process.close();
        this.process = null;
    }

    private static String run(List<String> argv) {
        try {
            return Subprocess.of(argv).mergeStderr().timeout(Duration.ofSeconds(30)).runChecked().stdout().text();
        } catch (IOException e) {
            throw new IllegalStateException("could not run " + argv, e);
        }
    }
}
