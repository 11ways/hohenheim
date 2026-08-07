package be.elevenways.hohenheim.server.host;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * The typed replacement for "swallow every exception to null": a probe either
 * REACHED the daemon (info in hand) or failed with a NAMED class, and both outcomes
 * land on the host record ({@code last_seen_at} / {@code last_error_kind} /
 * {@code last_error}) so an allocator reads state, never a zeroed guess.
 *
 * AIDEV-NOTE: classification is message-based because the SSH transport surfaces
 * everything as an IOException whose text is ssh/docker stderr; that is the only
 * evidence there is. An unmatched message is UNREACHABLE, never a silent success.
 */
public final class HostProbe {

    /** The distinguishable failure classes a probe can report. */
    public enum FailureKind {
        UNREACHABLE("unreachable"),
        DNS("dns"),
        SSH_AUTH("ssh_auth"),
        HOST_KEY_CHANGED("host_key_changed"),
        NOT_PINNED("not_pinned"),
        NO_IDENTITY("no_identity"),
        DOCKER_ABSENT("docker_absent"),
        /** The Incus daemon answered but does not trust this client's certificate. */
        UNTRUSTED("untrusted"),
        TIMEOUT("timeout");

        public final String token;

        FailureKind(String token) {
            this.token = token;
        }
    }

    /** Exactly one of {@code info} or {@code kind} is set. */
    public record Outcome(@Nullable Map<String, Object> info,
                          @Nullable FailureKind kind, @Nullable String detail) {

        public boolean reachable() {
            return this.info != null;
        }

        public static @NonNull Outcome success(@NonNull Map<String, Object> info) {
            return new Outcome(info, null, null);
        }

        public static @NonNull Outcome failure(@NonNull FailureKind kind, @NonNull String detail) {
            return new Outcome(null, kind, detail);
        }
    }

    private HostProbe() {
    }

    /** Classify a probe failure by its message evidence. */
    public static @NonNull Outcome classify(@NonNull Exception error) {
        String message = error.getMessage() != null ? error.getMessage() : error.toString();
        // A refusal we RAISED carries its own class; never re-derive it from prose.
        if (error instanceof HostKeys.HostTrustException refusal) {
            return Outcome.failure(refusal.kind, message);
        }
        String folded = BlastString.lower(message);
        FailureKind kind = FailureKind.UNREACHABLE;
        if (folded.contains("timed out") || folded.contains("timeout")) {
            kind = FailureKind.TIMEOUT;
        } else if (folded.contains("could not resolve hostname")
            || folded.contains("name or service not known")
            || folded.contains("temporary failure in name resolution")) {
            kind = FailureKind.DNS;
        } else if (folded.contains("host key verification failed")
            || folded.contains("remote host identification has changed")
            // The Incus lane's pin refusal (IncusTls' own words wrapped by the handshake).
            || folded.contains("does not match the pinned certificate")) {
            kind = FailureKind.HOST_KEY_CHANGED;
        } else if (folded.contains("permission denied")
            || folded.contains("authentication failed")
            || folded.contains("too many authentication failures")) {
            kind = FailureKind.SSH_AUTH;
        } else if (folded.contains("no such file or directory")
            || folded.contains("cannot connect to the docker daemon")
            || folded.contains("command not found")
            || folded.contains("docker: not found")) {
            kind = FailureKind.DOCKER_ABSENT;
        }
        return Outcome.failure(kind, message);
    }

    /**
     * Persist a successful daemon contact on the host record.
     *
     * AIDEV-NOTE: this clears the TRANSIENT probe verdict and nothing else. It used to
     * clear the quarantine too, because {@code last_error_kind} carried both: a host whose
     * certificate contradicted its pin was un-quarantined by the next probe that happened
     * to reach it, with no operator act anywhere. Reaching a daemon is not evidence about
     * WHICH machine answered -- that is exactly what the pin decides -- so it may not
     * clear a pin verdict. See {@link ServerModel#QUARANTINED_AT}.
     */
    public static void recordSuccess(@NonNull String serverName) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null) {
            return;
        }
        server.set(ServerModel.LAST_SEEN_AT, Instant.now());
        server.set(ServerModel.LAST_ERROR_KIND, null);
        server.set(ServerModel.LAST_ERROR, null);
        Models.get(ServerModel.class).save(server);
    }

    /**
     * Persist a typed probe failure on the host record; {@code last_seen_at} keeps its
     * value. A {@link FailureKind#HOST_KEY_CHANGED} additionally QUARANTINES the host --
     * see {@link #quarantine}.
     */
    public static void recordFailure(@NonNull String serverName, @NonNull Outcome outcome) {
        Row server = Models.get(ServerModel.class).findByName(serverName);
        if (server == null || outcome.kind() == null) {
            return;
        }
        server.set(ServerModel.LAST_ERROR_KIND, outcome.kind().token);
        server.set(ServerModel.LAST_ERROR, outcome.detail());
        if (outcome.kind() == FailureKind.HOST_KEY_CHANGED) {
            quarantine(server, outcome.detail());
        }
        Models.get(ServerModel.class).save(server);
    }

    /**
     * A host whose identity we can no longer verify stops receiving tenant workloads:
     * the verdict is STAMPED in its own column, the preflight verdict is dropped (so the
     * admit gate refuses) and an ADMITTED host falls back to blocked. Running workloads
     * are left alone -- cleanup must stay possible -- but nothing new lands here until an
     * operator re-pins deliberately.
     *
     * AIDEV-NOTE: this is the only automatic admission DOWNgrade in the product. There is
     * deliberately no matching automatic upgrade: recovery is HostPins.repin plus a fresh
     * preflight plus a fresh admit, three explicit operator acts.
     *
     * AIDEV-NOTE: the FIRST contradiction's timestamp and reason are kept -- a later
     * contradiction is the same unbroken quarantine, and moving the stamp forward would
     * hide how long the host has been in it.
     */
    private static void quarantine(@NonNull Row server, @Nullable String reason) {
        if (server.get(ServerModel.QUARANTINED_AT) == null) {
            server.set(ServerModel.QUARANTINED_AT, Instant.now());
            server.set(ServerModel.QUARANTINE_REASON, reason);
        }
        server.set(ServerModel.PREFLIGHT_OK, false);
        if (ServerModel.ADMISSION_ADMITTED.equals(server.get(ServerModel.ADMISSION))) {
            server.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
        }
        Blast.slog("hohenheim.host.quarantined", Map.of(
            "server", String.valueOf((Object) server.get(ServerModel.NAME)),
            "reason", FailureKind.HOST_KEY_CHANGED.token));
    }
}
