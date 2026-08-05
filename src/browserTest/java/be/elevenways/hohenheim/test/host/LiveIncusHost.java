package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The enrollment facts for a GENUINELY REMOTE Incus host, supplied out of band by an
 * operator in {@code ~/.config/hohenheim-livehost/incus.properties}; absent file =
 * every Incus live test skips, the {@link LiveRemoteHost} pattern exactly.
 *
 * Expected keys: {@code url} (https://host:8443), {@code fingerprint} (the sha256 hex
 * {@code incus info} prints ON THE HOST -- copied from a scan it would be decorative),
 * and {@code trust_target} ([user@]host for ssh) so a test can MINT a one-use trust
 * token the way an operator would ({@code incus config trust add}) and remove the
 * enrolled certificate afterwards. Unlike the ssh fixture no identity is supplied:
 * minting the client certificate is the PRODUCT's own job (IncusTrust), and the token
 * is how it lands on the daemon.
 */
public final class LiveIncusHost {

    /** Where an operator records the enrollment facts. */
    public static final Path CONFIG = Path.of(System.getProperty("user.home"),
        ".config", "hohenheim-livehost", "incus.properties");

    private final String url;
    private final String fingerprint;
    private final String trustTarget;

    private LiveIncusHost(Properties properties) throws IOException {
        this.url = require(properties, "url");
        this.fingerprint = require(properties, "fingerprint");
        this.trustTarget = require(properties, "trust_target");
    }

    /** The configured host, or null when no operator enrolled one on this machine. */
    public static LiveIncusHost configured() {
        if (!Files.isReadable(CONFIG)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(CONFIG)) {
            properties.load(in);
            return new LiveIncusHost(properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable " + CONFIG, e);
        }
    }

    public String url() {
        return this.url;
    }

    /** The fingerprint an operator read ON THE HOST ITSELF, never from a scan. */
    public String fingerprint() {
        return this.fingerprint;
    }

    /**
     * Enrol the host record at the BOTTOM of the trust ceremony: no pin, unconfirmed,
     * unadmitted, no client identity yet (the product mints that itself).
     */
    public Row enrol(String name) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(ServerModel.NAME, name);
        }
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.INCUS_URL, this.url);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.SSH_TARGET, null);
        row.set(ServerModel.HOST_KEY, null);
        row.set(ServerModel.HOST_KEY_FINGERPRINT, null);
        row.set(ServerModel.HOST_KEY_OFFERED, null);
        row.set(ServerModel.HOST_KEY_VERIFIED, false);
        row.set(ServerModel.PREFLIGHT_OK, false);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.IDENTITY_PRIVATE_KEY, null);
        row.set(ServerModel.IDENTITY_PUBLIC_KEY, null);
        model.save(row);
        return model.findByName(name);
    }

    /**
     * Mint a ONE-USE trust token on the host, the operator act
     * ({@code incus config trust add <name>}); the ssh lane here is the test's stand-in
     * for the host's own console, the same trust root the fingerprint came from.
     */
    public String mintTrustToken(String clientName) throws IOException {
        String output = ssh(List.of("incus", "config", "trust", "add", clientName, "--quiet"));
        String token = output.trim();
        int lastLine = token.lastIndexOf('\n');
        if (lastLine >= 0) {
            token = token.substring(lastLine + 1).trim();
        }
        if (token.isEmpty()) {
            throw new IOException("incus config trust add printed no token: " + output);
        }
        return token;
    }

    /** Remove an enrolled client certificate by its sha256-hex fingerprint (cleanup). */
    public void removeTrustEntry(String certificateFingerprint) throws IOException {
        ssh(List.of("incus", "config", "trust", "remove", certificateFingerprint));
    }

    /**
     * The FULL product enrollment ceremony against this host -- identity, pin,
     * confirm, token enrollment, preflight, admission -- with no fixture shortcut
     * writing any verdict. Call inside a {@code Db.run} scope.
     *
     * @return the enrolled client certificate's fingerprint (for trust cleanup)
     */
    public String enrollThroughProduct(String hostName, String clientName) {
        Row host = enrol(hostName);
        IncusTrust.ensureIdentity(host);
        String fingerprint = IncusTrust.fingerprintOf(
            host.get(ServerModel.IDENTITY_PUBLIC_KEY));
        IncusTrust.scanAndPin(host);
        Row pinned = Models.get(ServerModel.class).findByName(hostName);
        HostKeys.confirm(pinned);
        try {
            IncusTrust.enrollWithToken(Models.get(ServerModel.class).findByName(hostName),
                mintTrustToken(clientName));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        HostPreflight.Report report = HostPreflight.runAndStore(hostName);
        Assumptions.assumeTrue(report.passed(),
            "incus preflight did not pass: " + report.checks());
        Row ready = Models.get(ServerModel.class).findByName(hostName);
        HostAdmission.requireAdmittable(ready);
        ready.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        Models.get(ServerModel.class).save(ready);
        return fingerprint;
    }

    /** Run one shell command INSIDE the instance, over the host's own CLI. */
    public String exec(String handle, String command) throws IOException {
        return ssh(List.of("incus", "exec", handle, "--", "sh", "-c",
            "'" + command.replace("'", "'\\''") + "'")).trim();
    }

    /** Force-remove one instance over the host's own CLI (cleanup; absent is fine). */
    public void forceDelete(String handle) {
        try {
            ssh(List.of("incus", "delete", "--force", handle));
        } catch (IOException ignored) {
            // already gone
        }
    }

    /** Run one command on the HOST itself (reachability probes from outside a container). */
    public String hostCommand(String... command) throws IOException {
        return ssh(List.of(command)).trim();
    }

    /** Raw REST truth over the host's own CLI ({@code incus query <path>}). */
    public String query(String path) throws IOException {
        return ssh(List.of("incus", "query", path)).trim();
    }

    /** The daemon-side truth of one instance, read over the host's own CLI. */
    public String instanceInfoOrError(String handle) throws IOException {
        try {
            return ssh(List.of("incus", "info", handle));
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String ssh(List<String> remoteCommand) throws IOException {
        ArrayList<String> argv = new ArrayList<>(List.of("ssh",
            "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "--", this.trustTarget));
        argv.addAll(remoteCommand);
        try {
            Process process = new ProcessBuilder(argv).start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ssh to " + this.trustTarget + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("ssh " + remoteCommand + " failed (exit "
                    + process.exitValue() + "): " + stderr.trim());
            }
            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted talking to " + this.trustTarget);
        }
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException(CONFIG + " has no '" + key + "'");
        }
        return value.trim();
    }
}
