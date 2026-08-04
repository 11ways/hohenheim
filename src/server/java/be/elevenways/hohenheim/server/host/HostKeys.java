package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * THE ssh trust and credential seam: every {@code ssh} argv the product ever builds
 * comes from here, verified against a known_hosts file Hohenheim MATERIALISES from
 * stored configuration on each use. {@link #sshArgv} is the inventoried-host lane
 * (host record, per-host client identity, {@code IdentitiesOnly=yes});
 * {@link #pinnedArgv} is the same trust half for a free-form target an operator typed,
 * which is what the backup lane addresses.
 *
 * AIDEV-NOTE: the trust store is the DATABASE, the file is a rendering of it. It is
 * rewritten before every connection precisely so an out-of-band edit of the file
 * cannot change what Hohenheim enforces -- the previous shape leaned on the OS
 * user's ambient {@code ~/.ssh/known_hosts}, which nothing in the product could read,
 * display or defend.
 *
 * AIDEV-NOTE: {@code HostKeyAlias} makes both lookup and verification key on a
 * constant we own ({@code hohenheim-host-<id>}) instead of the target spelling, so
 * re-addressing a host (new DNS name, new port) never silently drops the pin, and no
 * {@code [host]:port} known_hosts formatting subtlety can turn a mismatch into a miss.
 */
public final class HostKeys {

    /** Host key types we are willing to pin, best first. */
    private static final List<String> PREFERRED_TYPES = List.of(
        "ssh-ed25519", "ecdsa-sha2-nistp521", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp256",
        "ssh-rsa");

    private static final long SSH_KEYGEN_TIMEOUT_SECONDS = 20;
    private static final long SSH_KEYSCAN_TIMEOUT_SECONDS = 20;

    /** What a rescan concluded about the key the host currently offers. */
    public enum ScanOutcome {
        /** The host had no pin; this scan established one (UNVERIFIED). */
        PINNED,
        /** The host offered exactly the pinned key. */
        UNCHANGED,
        /** The host offered a DIFFERENT key; nothing was re-pinned and the host is quarantined. */
        MISMATCH
    }

    /** One scanned host key in {@code known_hosts} spelling plus its operator-facing digest. */
    public record Offer(@NonNull String keyLine, @NonNull String fingerprint) {
    }

    /** The outcome of {@link #scanAndPin}; {@code previous} is set only on a mismatch. */
    public record ScanResult(@NonNull ScanOutcome outcome, @NonNull String fingerprint,
                             @Nullable String previous) {
    }

    /** An ssh target split into what the ssh client actually takes. */
    public record Target(@NonNull String destination, @NonNull String host, int port) {
    }

    /**
     * Refusal to connect at all, because the host has no pin or no client identity.
     * Fail-closed: there is no "just this once" path back to ambient trust.
     */
    public static final class HostTrustException extends IllegalStateException {

        /** {@link HostProbe.FailureKind} this refusal classifies as. */
        public final transient HostProbe.FailureKind kind;

        HostTrustException(HostProbe.FailureKind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }

    private HostKeys() {
    }

    // -- connecting -----------------------------------------------------------

    /**
     * THE ssh argv for a remote host. The {@code --} terminator stops ssh from parsing
     * the target as an option; the target validator refuses a leading dash for the same
     * reason and both must stay.
     *
     * @throws HostTrustException when the host is unpinned or has no client identity
     */
    public static @NonNull List<String> sshArgv(@NonNull Row server,
                                                @NonNull List<String> remoteCommand) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        String pinned = server.get(ServerModel.HOST_KEY);
        if (pinned == null || pinned.isBlank()) {
            throw new HostTrustException(HostProbe.FailureKind.NOT_PINNED,
                "Host '" + name + "' has no pinned SSH host key; scan and confirm it first");
        }
        String privateKey = server.get(ServerModel.IDENTITY_PRIVATE_KEY);
        if (privateKey == null || privateKey.isBlank()) {
            throw new HostTrustException(HostProbe.FailureKind.NO_IDENTITY,
                "Host '" + name + "' has no Hohenheim client key; rotate the SSH key and"
                    + " install the public half on the host");
        }
        Path identity = writeIdentity(server, privateKey);

        List<String> argv = pinnedArgv(alias(server), pinned,
            server.get(ServerModel.SSH_TARGET), List.of(
                "-o", "IdentitiesOnly=yes",
                "-i", identity.toString(),
                "-o", "ConnectTimeout=10"));
        argv.addAll(remoteCommand);
        return argv;
    }

    /**
     * THE pinned ssh argv for a target that is NOT an inventoried host record -- the
     * backup lane's destination is a free-form {@code [user@]host[:port]} an operator
     * typed, not a {@link ServerModel} row -- ending in the {@code --} terminator and
     * the destination, so a caller appends its remote command and nothing else.
     *
     * The pin is MANDATORY here for the same reason it is on a host record: the
     * alternative is the OS user's ambient {@code known_hosts}, which is silent
     * trust-on-first-use that nothing in the product can display or defend.
     *
     * @param alias the {@code HostKeyAlias} both lookup and verification key on
     * @param pinnedKeyLine a known_hosts key line ({@code <type> <base64>})
     * @param rawTarget {@code [user@]host[:port]}
     * @param extraOptions caller options (identity, timeouts) placed before the terminator
     */
    public static @NonNull List<String> pinnedArgv(@NonNull String alias,
                                                   @NonNull String pinnedKeyLine,
                                                   @Nullable String rawTarget,
                                                   @NonNull List<String> extraOptions) {
        Target target = parseTarget(rawTarget);
        Path knownHosts = writeKnownHosts(alias, pinnedKeyLine);
        List<String> argv = new ArrayList<>(List.of("ssh",
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=yes",
            "-o", "UserKnownHostsFile=" + knownHosts,
            "-o", "GlobalKnownHostsFile=/dev/null",
            "-o", "HostKeyAlias=" + alias));
        argv.addAll(extraOptions);
        if (target.port() > 0) {
            argv.add("-p");
            argv.add(String.valueOf(target.port()));
        }
        argv.add("--");
        argv.add(target.destination());
        return argv;
    }

    /**
     * Split {@code [user@]host[:port]} into an ssh destination plus a real {@code -p}
     * port. A bracketless IPv6 literal keeps every colon (no port), which is why the
     * single-colon test exists.
     */
    public static @NonNull Target parseTarget(@Nullable String raw) {
        String value = raw != null ? raw.trim() : "";
        String user = "";
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            user = value.substring(0, at + 1);
            value = value.substring(at + 1);
        }
        int port = 0;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                String rest = value.substring(close + 1);
                value = value.substring(1, close);
                if (rest.startsWith(":")) {
                    port = parsePort(rest.substring(1));
                }
            }
        } else {
            int colon = value.lastIndexOf(':');
            if (colon > 0 && value.indexOf(':') == colon) {
                port = parsePort(value.substring(colon + 1));
                value = value.substring(0, colon);
            }
        }
        return new Target(user + value, value, port);
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            return port > 0 && port < 65536 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // -- the trust ceremony ---------------------------------------------------

    /**
     * Scan the key the host currently offers and decide what it means: a first pin
     * (UNVERIFIED -- an operator still has to confirm the fingerprint out of band), an
     * unchanged pin, or a MISMATCH, which re-pins NOTHING and quarantines the host.
     *
     * @throws Violations when no acceptable key could be scanned
     */
    public static @NonNull ScanResult scanAndPin(@NonNull Row server) {
        Offer offer = scan(server);
        ServerModel model = Models.get(ServerModel.class);
        String pinned = server.get(ServerModel.HOST_KEY);
        String name = String.valueOf((Object) server.get(ServerModel.NAME));

        if (pinned == null || pinned.isBlank()) {
            ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_pinned", () -> {
                server.set(ServerModel.HOST_KEY, offer.keyLine());
                server.set(ServerModel.HOST_KEY_FINGERPRINT, offer.fingerprint());
                server.set(ServerModel.HOST_KEY_PINNED_AT, Instant.now());
                server.set(ServerModel.HOST_KEY_VERIFIED, false);
                server.set(ServerModel.HOST_KEY_OFFERED, null);
                model.save(server);
            });
            Blast.slog("hohenheim.host.key_pinned", Map.of(
                "server", name, "fingerprint", offer.fingerprint()));
            return new ScanResult(ScanOutcome.PINNED, offer.fingerprint(), null);
        }

        if (pinned.trim().equals(offer.keyLine())) {
            if (server.get(ServerModel.HOST_KEY_OFFERED) != null) {
                server.set(ServerModel.HOST_KEY_OFFERED, null);
                model.save(server);
            }
            return new ScanResult(ScanOutcome.UNCHANGED, offer.fingerprint(), null);
        }

        String previous = server.get(ServerModel.HOST_KEY_FINGERPRINT);
        server.set(ServerModel.HOST_KEY_OFFERED, offer.keyLine());
        model.save(server);
        // The SAME funnel a failed connection uses: typed kind, quarantine, no re-pin.
        HostProbe.recordFailure(name, HostProbe.Outcome.failure(
            HostProbe.FailureKind.HOST_KEY_CHANGED,
            "Host key changed: pinned " + previous + ", offered " + offer.fingerprint()));
        return new ScanResult(ScanOutcome.MISMATCH, offer.fingerprint(), previous);
    }

    /**
     * Record the operator's out-of-band confirmation of the pinned fingerprint. Nothing
     * else in the product may set this flag: an unverified pin becoming verified without
     * a human comparing digests would make the whole ceremony decorative.
     */
    public static void confirm(@NonNull Row server) {
        String pinned = server.get(ServerModel.HOST_KEY);
        if (pinned == null || pinned.isBlank()) {
            throw Violations.ofForm(violation("host_key_not_pinned"));
        }
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_verified", () -> {
            server.set(ServerModel.HOST_KEY_VERIFIED, true);
            Models.get(ServerModel.class).save(server);
        });
        Blast.slog("hohenheim.host.key_verified", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "fingerprint", String.valueOf((Object) server.get(ServerModel.HOST_KEY_FINGERPRINT))));
    }

    /**
     * Replace the pin with the key the host was last seen OFFERING -- the explicit
     * operator act a mismatch demands. The new pin is UNVERIFIED and the preflight
     * verdict is dropped, so the host walks the whole ceremony again before it can be
     * admitted; recovery is never a side effect of reconnecting.
     */
    public static void repin(@NonNull Row server) {
        String offered = server.get(ServerModel.HOST_KEY_OFFERED);
        if (offered == null || offered.isBlank()) {
            throw Violations.ofForm(violation("host_key_no_offer"));
        }
        String previous = server.get(ServerModel.HOST_KEY_FINGERPRINT);
        String fingerprint = fingerprintOf(offered);
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_repinned", () -> {
            server.set(ServerModel.HOST_KEY, offered);
            server.set(ServerModel.HOST_KEY_FINGERPRINT, fingerprint);
            server.set(ServerModel.HOST_KEY_PINNED_AT, Instant.now());
            server.set(ServerModel.HOST_KEY_VERIFIED, false);
            server.set(ServerModel.HOST_KEY_OFFERED, null);
            server.set(ServerModel.PREFLIGHT_OK, false);
            server.set(ServerModel.LAST_ERROR_KIND, null);
            server.set(ServerModel.LAST_ERROR, null);
            Models.get(ServerModel.class).save(server);
        });
        Blast.slog("hohenheim.host.key_repinned", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "previous", String.valueOf(previous), "fingerprint", fingerprint));
    }

    /** The digest {@code ssh-keygen -lf} prints for a public key line. */
    public static @NonNull String fingerprintOf(@NonNull String keyLine) {
        String blob = keyLineOf(keyLine).split(" ")[1];
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Base64.getDecoder().decode(blob));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Ask the host which keys it offers, WITHOUT trusting any of them: ssh-keyscan
     * performs no authentication and this result is only ever compared or shown.
     *
     * @throws Violations {@code host_key_scan_failed} when nothing usable came back
     */
    public static @NonNull Offer scan(@NonNull Row server) {
        Target target = parseTarget(server.get(ServerModel.SSH_TARGET));
        List<String> argv = new ArrayList<>(List.of("ssh-keyscan", "-T", "10"));
        if (target.port() > 0) {
            argv.add("-p");
            argv.add(String.valueOf(target.port()));
        }
        argv.add("--");
        argv.add(target.host());
        NftRunner.Result result = NftRunner.Sudo.execute(argv, null, SSH_KEYSCAN_TIMEOUT_SECONDS);

        Offer best = null;
        int bestRank = Integer.MAX_VALUE;
        for (String line : result.stdout().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            // ssh-keyscan prints "<host> <type> <base64>"; tolerate a bare "<type> <base64>".
            String type = parts.length >= 3 ? parts[1] : parts.length == 2 ? parts[0] : null;
            String blob = parts.length >= 3 ? parts[2] : parts.length == 2 ? parts[1] : null;
            if (type == null || blob == null || !blob.startsWith("AAAA")) {
                continue;
            }
            int rank = PREFERRED_TYPES.indexOf(type);
            if (rank >= 0 && rank < bestRank) {
                bestRank = rank;
                String keyLine = type + " " + blob;
                best = new Offer(keyLine, fingerprintOf(keyLine));
            }
        }
        if (best == null) {
            throw Violations.ofForm(violation("host_key_scan_failed")
                .withArg("target", String.valueOf((Object) server.get(ServerModel.SSH_TARGET)))
                .withArg("detail", result.failureText().isEmpty()
                    ? "no host keys offered" : result.failureText()));
        }
        return best;
    }

    // -- the per-host client identity -----------------------------------------

    /** Generate the host's client keypair if it has none; returns true when one was made. */
    public static boolean ensureIdentity(@NonNull Row server) {
        String existing = server.get(ServerModel.IDENTITY_PRIVATE_KEY);
        if (existing != null && !existing.isBlank()) {
            return false;
        }
        rotateIdentity(server);
        return true;
    }

    /**
     * Mint a FRESH client keypair for this host. The old key stops working the moment
     * this returns, which is the point of rotation -- the operator installs the new
     * public half on the remote's authorized_keys.
     */
    public static void rotateIdentity(@NonNull Row server) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        Path directory = null;
        try {
            directory = Files.createTempDirectory("hohenheim-hostkey");
            Path key = directory.resolve("id_ed25519");
            NftRunner.Result result = NftRunner.Sudo.execute(List.of("ssh-keygen",
                "-q", "-t", "ed25519", "-N", "", "-C", "hohenheim-" + name,
                "-f", key.toString()), null, SSH_KEYGEN_TIMEOUT_SECONDS);
            if (!result.ok() || !Files.exists(key)) {
                throw Violations.ofForm(violation("identity_generation_failed")
                    .withArg("detail", result.failureText()));
            }
            String privateKey = Files.readString(key, StandardCharsets.UTF_8);
            String publicKey = Files.readString(directory.resolve("id_ed25519.pub"),
                StandardCharsets.UTF_8).trim();
            ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_identity_rotated", () -> {
                server.set(ServerModel.IDENTITY_PRIVATE_KEY, privateKey);
                server.set(ServerModel.IDENTITY_PUBLIC_KEY, publicKey);
                Models.get(ServerModel.class).save(server);
            });
            Blast.slog("hohenheim.host.identity_rotated", Map.of("server", name));
        } catch (IOException e) {
            throw Violations.ofForm(violation("identity_generation_failed")
                .withArg("detail", String.valueOf(e.getMessage())));
        } finally {
            deleteRecursively(directory);
        }
    }

    // -- the materialised trust store -----------------------------------------

    /** The known_hosts key we control, independent of how the host is addressed. */
    static @NonNull String alias(@NonNull Row server) {
        return "hohenheim-host-" + server.get(ServerModel.ID);
    }

    /** Hohenheim's OWN ssh store, never the OS user's ~/.ssh. */
    static @NonNull Path storeDirectory() {
        Path directory = Path.of(HohenheimSettings.VALUES
            .getValue(HohenheimSettings.Storage.DATA_PATH)).resolve("host-ssh");
        try {
            Files.createDirectories(directory);
            trySetPermissions(directory, "rwx------");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the host ssh store at "
                + directory + ": " + e.getMessage(), e);
        }
        return directory;
    }

    /**
     * Reduce any public-key spelling to the {@code <type> <base64>} pair a known_hosts
     * line takes after its host field. Operators paste whole {@code ssh-keyscan} lines
     * (which carry a hostname) and whole {@code .pub} files (which carry a comment);
     * both must land on the same two tokens, or the alias we prepend would produce a
     * three-field line ssh silently never matches.
     *
     * @throws IllegalArgumentException when the text holds no key blob
     */
    public static @NonNull String keyLineOf(@NonNull String text) {
        String[] tokens = text.trim().split("\\s+");
        for (int index = 1; index < tokens.length; index++) {
            if (tokens[index].length() > 32 && tokens[index].startsWith("AAAA")) {
                return tokens[index - 1] + " " + tokens[index];
            }
        }
        throw new IllegalArgumentException("Not a public key line: " + text);
    }

    private static Path writeKnownHosts(String alias, String pinnedKey) {
        Path file = storeDirectory().resolve("known_hosts-" + alias);
        return writeFile(file, alias + " " + keyLineOf(pinnedKey) + "\n", "rw-------");
    }

    private static Path writeIdentity(Row server, String privateKey) {
        Path file = storeDirectory().resolve("id-" + server.get(ServerModel.ID));
        String body = privateKey.endsWith("\n") ? privateKey : privateKey + "\n";
        return writeFile(file, body, "rw-------");
    }

    /** Rewrite from the database unless the file already says exactly this. */
    private static Path writeFile(Path file, String content, String permissions) {
        try {
            if (!Files.exists(file) || !content.equals(Files.readString(file, StandardCharsets.UTF_8))) {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
            trySetPermissions(file, permissions);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private static void trySetPermissions(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX filesystem; the content is still authoritative.
        }
    }

    private static void deleteRecursively(@Nullable Path directory) {
        if (directory == null) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Best effort: a temp directory that survives holds a key we already replaced.
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
