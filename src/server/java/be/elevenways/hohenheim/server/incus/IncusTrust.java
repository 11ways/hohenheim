package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostPins;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

/**
 * The TLS trust ceremony of an Incus host, the exact twin of {@link HostKeys}: a scan
 * captures the certificate the daemon OFFERS (trusting nothing), pinning is
 * unverified until an operator compares the fingerprint against
 * {@code incus info}'s own {@code certificate_fingerprint}, a mismatch quarantines,
 * and the per-host client identity is a self-signed certificate the daemon is taught
 * to trust -- by an operator installing it, or by {@link #enrollWithToken}.
 *
 * AIDEV-NOTE: this enrollment IS the node-identity story (see ServerModel.RUNTIME).
 * The client certificate is minted per HOST record, mirroring the per-host ssh
 * identity: revoking one host's trust entry touches nothing else.
 *
 * AIDEV-NOTE: every read and write here goes through {@link HostTrustSlot#INCUS_TLS}.
 * Before M074 this ceremony wrote the SAME columns as the ssh one, which is why an Incus
 * host could not also hold an ssh admin lane and why the backup lane needed a runtime
 * check to keep a PEM out of the known_hosts writer.
 */
public final class IncusTrust {

    private static final long OPENSSL_TIMEOUT_SECONDS = 20;

    private IncusTrust() {
    }

    // -- the ceremony ---------------------------------------------------------

    /**
     * Capture the certificate the daemon currently offers and run the shared pin state
     * machine ({@link HostPins}): first scan pins UNVERIFIED, a different offer
     * quarantines and re-pins nothing.
     *
     * @throws Violations {@code host_key_scan_failed} when no TLS endpoint answered
     */
    public static HostKeys.@NonNull ScanResult scanAndPin(@NonNull Row server) {
        IncusEndpoint endpoint = IncusEndpoint.of(server);
        if (!endpoint.https()) {
            throw Violations.ofForm(violation("host_key_scan_failed")
                .withArg("target", endpoint.describe())
                .withArg("detail", "a unix-socket Incus host has no certificate to pin"));
        }
        X509Certificate offered;
        String pem;
        try {
            offered = IncusTls.scanServerCertificate(endpoint.host(), endpoint.port(), 10_000);
            pem = IncusTls.toPem(offered);
        } catch (IOException e) {
            throw Violations.ofForm(violation("host_key_scan_failed")
                .withArg("target", endpoint.describe())
                .withArg("detail", String.valueOf(e.getMessage())));
        }
        return HostPins.apply(server, HostTrustSlot.INCUS_TLS, pem,
            IncusTls.fingerprintOf(offered));
    }

    /** The digest of a pinned/offered certificate PEM ({@code incus info}'s hex spelling). */
    public static @NonNull String fingerprintOf(@NonNull String pem) {
        try {
            return IncusTls.fingerprintOf(IncusTls.parseCertificate(pem));
        } catch (IOException e) {
            throw new IllegalArgumentException("not a certificate PEM: " + e.getMessage(), e);
        }
    }

    /**
     * Record the operator's out-of-band comparison of the pinned certificate fingerprint
     * against what {@code incus info} reports ON the host.
     */
    public static void confirm(@NonNull Row server) {
        HostPins.confirm(server, HostTrustSlot.INCUS_TLS);
    }

    /** Adopt the offered certificate; lands unverified, unpreflighted (see HostPins). */
    public static void repin(@NonNull Row server) {
        HostPins.repin(server, HostTrustSlot.INCUS_TLS, IncusTrust::fingerprintOf);
    }

    // -- the per-host client identity -----------------------------------------

    /** Mint the host's client certificate if it has none; returns true when one was made. */
    public static boolean ensureIdentity(@NonNull Row server) {
        String existing = server.get(HostTrustSlot.INCUS_TLS.clientPrivate());
        if (existing != null && !existing.isBlank()) {
            return false;
        }
        rotateIdentity(server);
        return true;
    }

    /**
     * Mint a FRESH client certificate + key for this host (openssl, EC P-256,
     * self-signed, 10 years). The daemon stops trusting the old one only when its
     * trust entry is removed there -- rotation here is the client half.
     */
    public static void rotateIdentity(@NonNull Row server) {
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        Path directory = null;
        try {
            directory = Files.createTempDirectory("hohenheim-incus-identity");
            Path key = directory.resolve("client.key");
            Path cert = directory.resolve("client.crt");
            NftRunner.Result result = NftRunner.Sudo.execute(List.of("openssl", "req",
                "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
                "-sha384", "-days", "3650", "-nodes",
                "-subj", "/CN=" + ControllerScope.scoped(name),
                "-keyout", key.toString(), "-out", cert.toString()),
                null, OPENSSL_TIMEOUT_SECONDS);
            if (!result.ok() || !Files.exists(key) || !Files.exists(cert)) {
                throw Violations.ofForm(violation("identity_generation_failed")
                    .withArg("detail", result.failureText()));
            }
            String privateKey = Files.readString(key, StandardCharsets.UTF_8);
            String certificate = Files.readString(cert, StandardCharsets.UTF_8);
            ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_identity_rotated", () -> {
                server.set(HostTrustSlot.INCUS_TLS.clientPrivate(), privateKey);
                server.set(HostTrustSlot.INCUS_TLS.clientPublic(), certificate);
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

    /**
     * Enroll this host's client certificate on the daemon with an operator-minted
     * trust token ({@code incus config trust add}), then PROVE it took: the daemon
     * must answer {@code auth: trusted} on the freshly-identified connection.
     *
     * @throws Violations {@code incus_enroll_failed} naming the daemon's refusal, or
     *         when the daemon still reports the connection untrusted afterwards
     */
    public static void enrollWithToken(@NonNull Row server, @NonNull String token) {
        IncusClient client = IncusClients.forServer(server);
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        try {
            client.enrollWithToken(token.trim());
            if (!client.trusted()) {
                throw new IOException("the daemon accepted the token but still reports"
                    + " this client untrusted");
            }
        } catch (IOException e) {
            throw Violations.ofForm(violation("incus_enroll_failed")
                .withArg("name", name)
                .withArg("detail", String.valueOf(e.getMessage())));
        }
        Blast.slog("hohenheim.host.incus_enrolled", Map.of("server", name));
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Best effort: a leftover temp directory holds a key we already stored.
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
