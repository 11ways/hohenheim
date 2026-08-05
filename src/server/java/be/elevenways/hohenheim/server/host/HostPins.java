package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.Instant;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * THE pin bookkeeping both trust ceremonies share: first-scan pins UNVERIFIED,
 * an unchanged offer clears stale evidence, a DIFFERENT offer quarantines and re-pins
 * NOTHING, and recovery (repin) lands the host back at the bottom of the ceremony.
 * What is being pinned -- an ssh host key line ({@link HostKeys}) or an Incus server
 * certificate PEM -- is the caller's business; this class only owns the state machine,
 * so the two ceremonies cannot drift apart.
 */
public final class HostPins {

    private HostPins() {
    }

    /**
     * Whether the host is QUARANTINED: something observed an identity that cannot be
     * reconciled with the pin, recorded either as a stored OFFER (a rescan disagreed)
     * or as the typed {@code host_key_changed} verdict of a failed connection.
     *
     * AIDEV-NOTE: the two markers are written by DIFFERENT paths and only one fires per
     * case -- {@link #apply} stores the offer when a rescan disagrees, {@link
     * HostProbe#recordFailure} stores the kind when a live connection is refused and
     * nobody rescanned -- so asking about either is the only complete question. Each
     * clears on its own honest event (a rescan that agrees drops the offer, a reached
     * daemon drops the error kind, {@link #repin} drops both); a quarantine never
     * expires by itself. Never re-derive it from admission: quarantine DOWNGRADES
     * admission, but an operator may also block a perfectly trusted host, and reading
     * the downgrade as the cause would make those two indistinguishable.
     */
    public static boolean isQuarantined(@NonNull Row server) {
        String offered = server.get(ServerModel.HOST_KEY_OFFERED);
        if (offered != null && !offered.isBlank()) {
            return true;
        }
        return HostProbe.FailureKind.HOST_KEY_CHANGED.token
            .equals(server.get(ServerModel.LAST_ERROR_KIND));
    }

    /**
     * Record what a scan OFFERED against what is pinned.
     *
     * @param offeredMaterial the offered key/certificate in its stored spelling
     * @param offeredFingerprint the operator-facing digest of that material
     */
    public static HostKeys.@NonNull ScanResult apply(@NonNull Row server,
                                                     @NonNull String offeredMaterial,
                                                     @NonNull String offeredFingerprint) {
        ServerModel model = Models.get(ServerModel.class);
        String pinned = server.get(ServerModel.HOST_KEY);
        String name = String.valueOf((Object) server.get(ServerModel.NAME));

        if (pinned == null || pinned.isBlank()) {
            ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_pinned", () -> {
                server.set(ServerModel.HOST_KEY, offeredMaterial);
                server.set(ServerModel.HOST_KEY_FINGERPRINT, offeredFingerprint);
                server.set(ServerModel.HOST_KEY_PINNED_AT, Instant.now());
                server.set(ServerModel.HOST_KEY_VERIFIED, false);
                server.set(ServerModel.HOST_KEY_OFFERED, null);
                model.save(server);
            });
            Blast.slog("hohenheim.host.key_pinned", Map.of(
                "server", name, "fingerprint", offeredFingerprint));
            return new HostKeys.ScanResult(HostKeys.ScanOutcome.PINNED, offeredFingerprint, null);
        }

        if (pinned.trim().equals(offeredMaterial.trim())) {
            if (server.get(ServerModel.HOST_KEY_OFFERED) != null) {
                server.set(ServerModel.HOST_KEY_OFFERED, null);
                model.save(server);
            }
            return new HostKeys.ScanResult(HostKeys.ScanOutcome.UNCHANGED,
                offeredFingerprint, null);
        }

        String previous = server.get(ServerModel.HOST_KEY_FINGERPRINT);
        server.set(ServerModel.HOST_KEY_OFFERED, offeredMaterial);
        model.save(server);
        // The SAME funnel a failed connection uses: typed kind, quarantine, no re-pin.
        HostProbe.recordFailure(name, HostProbe.Outcome.failure(
            HostProbe.FailureKind.HOST_KEY_CHANGED,
            "Host key changed: pinned " + previous + ", offered " + offeredFingerprint));
        return new HostKeys.ScanResult(HostKeys.ScanOutcome.MISMATCH,
            offeredFingerprint, previous);
    }

    /**
     * Replace the pin with what the host was last seen OFFERING -- the explicit
     * operator act a mismatch demands. UNVERIFIED, preflight verdict dropped: the host
     * walks the whole ceremony again before it can be admitted.
     *
     * @param fingerprintOf derives the operator-facing digest of the offered material
     */
    public static void repin(@NonNull Row server, @NonNull UnaryOperator<String> fingerprintOf) {
        String offered = server.get(ServerModel.HOST_KEY_OFFERED);
        if (offered == null || offered.isBlank()) {
            throw Violations.ofForm(HostKeys.violation("host_key_no_offer"));
        }
        String previous = server.get(ServerModel.HOST_KEY_FINGERPRINT);
        String fingerprint = fingerprintOf.apply(offered);
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
}
