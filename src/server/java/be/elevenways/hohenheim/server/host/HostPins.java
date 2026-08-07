package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.HostTrustSlot;
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
 * THE pin bookkeeping every trust ceremony shares: first-scan pins UNVERIFIED,
 * an unchanged offer clears stale evidence, a DIFFERENT offer quarantines and re-pins
 * NOTHING, and recovery (repin) lands the host back at the bottom of the ceremony.
 * WHICH relationship is being pinned is a {@link HostTrustSlot} the caller names -- an
 * ssh host key ({@link HostKeys}) or an Incus server certificate -- so the ceremonies
 * cannot drift apart AND cannot write into each other's columns.
 */
public final class HostPins {

    private HostPins() {
    }

    /**
     * Whether the host is QUARANTINED on this slot: something observed an identity that
     * cannot be reconciled with the pin, recorded either as a stored OFFER (a rescan
     * disagreed) or as the record-wide {@code quarantined_at} stamp of a failed connection.
     *
     * AIDEV-NOTE: the two markers are written by DIFFERENT paths and only one fires per
     * case -- {@link #apply} stores the offer when a rescan disagrees, {@link
     * HostProbe#recordFailure} stamps the verdict when a live connection is refused and
     * nobody rescanned -- so asking about either is the only complete question. NEITHER
     * clears on a probe outcome: a rescan that AGREES drops the offer (that is a fresh
     * observation of the identity itself), and only {@link #repin} drops the stamp. A
     * quarantine never expires by itself. Never re-derive it from admission: quarantine
     * DOWNGRADES admission, but an operator may also block a perfectly trusted host, and
     * reading the downgrade as the cause would make those two indistinguishable.
     *
     * AIDEV-NOTE: the record-wide half was {@code last_error_kind = host_key_changed}
     * until 2026-08-07, and that was the defect: the same column is the last TRANSIENT
     * probe outcome, so {@code recordSuccess} and every weaker {@code recordFailure} wiped
     * a security verdict. {@link ServerModel#QUARANTINED_AT} is written by the quarantine
     * path and cleared by the repin ceremony, and by nothing else.
     *
     * AIDEV-NOTE: the OFFER half is per-slot, the quarantine stamp is
     * record-wide, and that asymmetry is deliberate. The evidence belongs to the wire it
     * was observed on, so a changed ssh host key never touches the pinned daemon
     * certificate or re-pins anything. The CONCLUSION does not: both lanes address the
     * same machine, so "this machine contradicted the identity we pinned" closes both
     * until an operator re-pins one of them. Fail-closed is the right side to err on for
     * the only strong tenant boundary shared iron has, and recovery is the same explicit
     * repin either way.
     */
    public static boolean isQuarantined(@NonNull Row server, @NonNull HostTrustSlot slot) {
        if (!slot.offeredOf(server).isBlank()) {
            return true;
        }
        return server.get(ServerModel.QUARANTINED_AT) != null;
    }

    /** Quarantine of the host's PRIMARY transport slot. */
    public static boolean isQuarantined(@NonNull Row server) {
        return isQuarantined(server, HostTrustSlot.transportOf(server));
    }

    /**
     * Record what a scan OFFERED against what is pinned in one slot.
     *
     * @param offeredMaterial the offered key/certificate in its stored spelling
     * @param offeredFingerprint the operator-facing digest of that material
     */
    public static HostKeys.@NonNull ScanResult apply(@NonNull Row server,
                                                     @NonNull HostTrustSlot slot,
                                                     @NonNull String offeredMaterial,
                                                     @NonNull String offeredFingerprint) {
        ServerModel model = Models.get(ServerModel.class);
        String pinned = server.get(slot.material());
        String name = String.valueOf((Object) server.get(ServerModel.NAME));

        if (pinned == null || pinned.isBlank()) {
            ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_pinned", () -> {
                server.set(slot.material(), offeredMaterial);
                server.set(slot.fingerprint(), offeredFingerprint);
                server.set(slot.pinnedAt(), Instant.now());
                server.set(slot.verified(), false);
                server.set(slot.offered(), null);
                model.save(server);
            });
            Blast.slog("hohenheim.host.key_pinned", Map.of(
                "server", name, "lane", slot.name(), "fingerprint", offeredFingerprint));
            return new HostKeys.ScanResult(HostKeys.ScanOutcome.PINNED, offeredFingerprint, null);
        }

        if (pinned.trim().equals(offeredMaterial.trim())) {
            if (!slot.offeredOf(server).isBlank()) {
                server.set(slot.offered(), null);
                model.save(server);
            }
            return new HostKeys.ScanResult(HostKeys.ScanOutcome.UNCHANGED,
                offeredFingerprint, null);
        }

        String previous = server.get(slot.fingerprint());
        server.set(slot.offered(), offeredMaterial);
        model.save(server);
        // The SAME funnel a failed connection uses: typed kind, quarantine, no re-pin.
        HostProbe.recordFailure(name, HostProbe.Outcome.failure(
            HostProbe.FailureKind.HOST_KEY_CHANGED,
            slot.name() + " identity changed: pinned " + previous
                + ", offered " + offeredFingerprint));
        return new HostKeys.ScanResult(HostKeys.ScanOutcome.MISMATCH,
            offeredFingerprint, previous);
    }

    /**
     * Record the operator's out-of-band confirmation of one slot's pinned fingerprint.
     * Nothing else in the product may set this flag: an unverified pin becoming verified
     * without a human comparing digests would make the whole ceremony decorative.
     */
    public static void confirm(@NonNull Row server, @NonNull HostTrustSlot slot) {
        if (!slot.isPinned(server)) {
            throw Violations.ofForm(HostKeys.violation("host_key_not_pinned"));
        }
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_verified", () -> {
            server.set(slot.verified(), true);
            Models.get(ServerModel.class).save(server);
        });
        Blast.slog("hohenheim.host.key_verified", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "lane", slot.name(),
            "fingerprint", String.valueOf((Object) server.get(slot.fingerprint()))));
    }

    /**
     * Replace one slot's pin with what the host was last seen OFFERING -- the explicit
     * operator act a mismatch demands. UNVERIFIED, preflight verdict dropped: the host
     * walks the whole ceremony again before it can be admitted.
     *
     * @param fingerprintOf derives the operator-facing digest of the offered material
     */
    public static void repin(@NonNull Row server, @NonNull HostTrustSlot slot,
                             @NonNull UnaryOperator<String> fingerprintOf) {
        String offered = slot.offeredOf(server);
        if (offered.isBlank()) {
            throw Violations.ofForm(HostKeys.violation("host_key_no_offer"));
        }
        String previous = server.get(slot.fingerprint());
        String fingerprint = fingerprintOf.apply(offered);
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "host_key_repinned", () -> {
            server.set(slot.material(), offered);
            server.set(slot.fingerprint(), fingerprint);
            server.set(slot.pinnedAt(), Instant.now());
            server.set(slot.verified(), false);
            server.set(slot.offered(), null);
            server.set(ServerModel.PREFLIGHT_OK, false);
            server.set(ServerModel.LAST_ERROR_KIND, null);
            server.set(ServerModel.LAST_ERROR, null);
            // THE one act that lifts a quarantine, and the reason it is an act: an
            // operator adopted the material the machine now presents, on purpose.
            server.set(ServerModel.QUARANTINED_AT, null);
            server.set(ServerModel.QUARANTINE_REASON, null);
            Models.get(ServerModel.class).save(server);
        });
        Blast.slog("hohenheim.host.key_repinned", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "lane", slot.name(),
            "previous", String.valueOf(previous), "fingerprint", fingerprint));
    }
}
