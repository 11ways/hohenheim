package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The enrollment facts for a GENUINELY REMOTE Docker host, supplied out of band by an
 * operator in {@code ~/.config/hohenheim-livehost/livehost.properties}; when that file
 * is absent every live-remote test skips, exactly like the Docker-socket assumption.
 *
 * AIDEV-NOTE: the fixture supplies ONE thing the product would otherwise mint itself --
 * the per-host client PRIVATE key -- because installing a freshly generated public half
 * into a real host's authorized_keys is an operator act no unattended test can perform.
 * Everything else (scan, pin, confirm, admit, connect, mismatch, re-pin) runs the
 * product's own code against the real machine. The recorded {@code fingerprint} MUST be
 * read on the host's own console; a fingerprint copied out of a scan would make the
 * confirmation step decorative, which is precisely what the ceremony exists to prevent.
 */
public final class LiveRemoteHost {

    /** Where an operator records the enrollment facts. */
    public static final Path CONFIG = Path.of(System.getProperty("user.home"),
        ".config", "hohenheim-livehost", "livehost.properties");

    private final String target;
    private final String identityPrivateKey;
    private final String fingerprint;
    private final String backupPath;

    private LiveRemoteHost(Properties properties) throws IOException {
        this.target = require(properties, "target");
        this.fingerprint = require(properties, "fingerprint");
        this.backupPath = require(properties, "backup_path");
        Path identity = Path.of(require(properties, "identity"));
        this.identityPrivateKey = Files.readString(identity, StandardCharsets.UTF_8);
    }

    /** The configured host, or null when no operator enrolled one on this machine. */
    public static LiveRemoteHost configured() {
        if (!Files.isReadable(CONFIG)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(CONFIG)) {
            properties.load(in);
            return new LiveRemoteHost(properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable " + CONFIG, e);
        }
    }

    /** {@code [user@]host[:port]} as the product's own target parser takes it. */
    public String target() {
        return this.target;
    }

    /** The fingerprint an operator read ON THE HOST ITSELF, never from a scan. */
    public String fingerprint() {
        return this.fingerprint;
    }

    /** An absolute remote directory the live backup test may own entirely. */
    public String backupPath() {
        return this.backupPath;
    }

    /**
     * Enrol the host and give it the operator-installed client identity, leaving it at
     * the BOTTOM of the trust ceremony: no pin, unconfirmed, unadmitted.
     */
    public Row enrol(String name) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(ServerModel.NAME, name);
        }
        row.set(ServerModel.MODE, ServerModel.MODE_SSH);
        row.set(ServerModel.SSH_TARGET, this.target);
        row.set(ServerModel.HOST_KEY, null);
        row.set(ServerModel.HOST_KEY_FINGERPRINT, null);
        row.set(ServerModel.HOST_KEY_OFFERED, null);
        row.set(ServerModel.HOST_KEY_VERIFIED, false);
        row.set(ServerModel.PREFLIGHT_OK, false);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.IDENTITY_PRIVATE_KEY, this.identityPrivateKey);
        model.save(row);
        return model.findByName(name);
    }

    /**
     * The host's current key line, ACCEPTED only if its digest equals the fingerprint an
     * operator read out of band -- the ceremony a free-form target (the backup lane) has
     * no host record to walk, expressed as an assertion instead of a form.
     *
     * @throws IllegalStateException when the scan disagrees with the recorded fingerprint
     */
    public String scanVerifiedKeyLine() throws IOException {
        try {
            Process scan = new ProcessBuilder("ssh-keyscan", "-T", "10", "--",
                HostKeys.parseTarget(this.target).host()).start();
            String output = new String(scan.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
            scan.waitFor(30, TimeUnit.SECONDS);
            for (String line : output.split("\n")) {
                if (!line.startsWith("#") && line.contains("ssh-ed25519 ")) {
                    String keyLine = HostKeys.keyLineOf(line);
                    String scanned = HostKeys.fingerprintOf(keyLine);
                    if (!scanned.equals(this.fingerprint)) {
                        throw new IllegalStateException("Scanned " + scanned + " but the"
                            + " out-of-band fingerprint is " + this.fingerprint);
                    }
                    return keyLine;
                }
            }
            throw new IOException("ssh-keyscan offered no ed25519 key for " + this.target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted scanning " + this.target);
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
