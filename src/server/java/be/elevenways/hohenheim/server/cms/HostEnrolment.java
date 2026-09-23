package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.incus.IncusTrust;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

import static be.elevenways.hohenheim.server.cms.ServerResource.hostCopy;
import static be.elevenways.hohenheim.server.cms.ServerResource.serverCopy;

/**
 * The second phase of enrolling a host: minting its client identities, pinning its Incus certificate
 * and spending a one-use trust token, run AFTER the host row is committed and outside any transaction.
 *
 * AIDEV-NOTE: this used to run inside the CMS save transaction (review finding, 2026-09). A late
 * failure then rolled back the row and the freshly minted client key while the remote daemon kept
 * trusting that certificate and the one-use token was already spent -- an orphaned trust entry and a
 * lost token, reported as a plain form error -- and the SQLite write lock was held across a TLS scan
 * and an HTTPS enrolment. Now the row is the durable "pending" record (a new host is admission
 * BLOCKED until preflight and admit anyway), every step persists its own outcome, and a failure is
 * RECORDED on the host (last_error_kind / last_error, shown on the Overview and in the list) instead of
 * erased. Recovery is re-derivable: the next probe clears the record when the daemon does trust the
 * certificate, a rotate re-mints a missing identity, and saving with a fresh token retries enrolment.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HostEnrolment {

    /** The steps that reach outside this datasource, each persisting its own outcome on the row. */
    public interface Ceremony {

        /**
         * Mint the client credential of every trust lane the host declares and lacks.
         *
         * @throws Violations when a key tool refused
         */
        void mintIdentities(@NonNull Row server);

        /**
         * Capture and pin the certificate the host's Incus daemon offers.
         *
         * @throws Violations when no TLS endpoint answered
         */
        HostKeys.@NonNull ScanResult pinIncusCertificate(@NonNull Row server);

        /**
         * Enroll the host's client certificate on its daemon with a one-use trust token.
         *
         * @throws Violations when the daemon refused or still reports the client untrusted
         */
        void enrollWithToken(@NonNull Row server, @NonNull String token);
    }

    /** What the second phase achieved; {@code failure} is the operator-facing reason when incomplete. */
    public record Outcome(boolean complete, @Nullable Microcopy failure) {

        static final Outcome COMPLETE = new Outcome(true, null);
    }

    /** Restores the previous ceremony when a test's replacement goes out of scope. */
    public interface Replacement extends AutoCloseable {
        @Override
        void close();
    }

    /** The real ceremony: local key tools plus the host's own daemon. */
    public static final Ceremony LIVE = new Ceremony() {
        @Override
        public void mintIdentities(@NonNull Row server) {
            for (ServerTrustActions.TrustLane lane : ServerTrustActions.TRUST_LANES) {
                ServerTrustActions.ensureLaneIdentity(server, lane);
            }
        }

        @Override
        public HostKeys.@NonNull ScanResult pinIncusCertificate(@NonNull Row server) {
            return IncusTrust.scanAndPin(server);
        }

        @Override
        public void enrollWithToken(@NonNull Row server, @NonNull String token) {
            IncusTrust.enrollWithToken(server, token);
        }
    };

    private static volatile @NonNull Ceremony ceremony = LIVE;

    private HostEnrolment() {
    }

    /**
     * Swap the ceremony for a fake until the returned handle closes.
     *
     * @return the handle that restores the previous ceremony
     */
    public static @NonNull Replacement replaceCeremonyForTesting(@NonNull Ceremony replacement) {
        Objects.requireNonNull(replacement, "replacement cannot be null");
        Ceremony previous = ceremony;
        ceremony = replacement;
        return () -> ceremony = previous;
    }

    /** Phase two of a CREATE: mint every declared identity, then spend the token when one was pasted. */
    public static @NonNull Outcome afterCreate(@NonNull Object serverId, @Nullable String token) {
        return run(serverId, token, true);
    }

    /** Phase two of an UPDATE: spend the token when one was pasted; identities are left as they are. */
    public static @NonNull Outcome afterUpdate(@NonNull Object serverId, @Nullable String token) {
        return run(serverId, token, false);
    }

    private static @NonNull Outcome run(@NonNull Object serverId, @Nullable String token, boolean mint) {
        requireOutsideTransaction();
        ServerModel servers = Models.get(ServerModel.class);
        Row server = servers.findById(serverId);
        if (server == null) {
            return Outcome.COMPLETE;
        }
        Ceremony steps = ceremony;
        if (mint) {
            try {
                steps.mintIdentities(server);
            } catch (Violations refused) {
                return failed(serverId, HostProbe.FailureKind.NO_IDENTITY,
                    HandlerSupport.violationMessage(refused));
            }
        }
        if (token == null) {
            return Outcome.COMPLETE;
        }
        server = Objects.requireNonNull(servers.findById(serverId));
        try {
            if (!HostTrustSlot.INCUS_TLS.isPinned(server)) {
                HostKeys.ScanResult scan = steps.pinIncusCertificate(server);
                if (scan.outcome() == HostKeys.ScanOutcome.MISMATCH) {
                    // HostPins already quarantined the host; enrolling a credential on a
                    // daemon whose certificate contradicts the pin would defeat the ceremony.
                    return failed(serverId, HostProbe.FailureKind.HOST_KEY_CHANGED,
                        CmsSupport.violationText("incus_cert_mismatch")
                            .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME)))
                            .withArg("pinned", String.valueOf(scan.previous()))
                            .withArg("offered", scan.fingerprint()));
                }
            }
        } catch (Violations refused) {
            return failed(serverId, HostProbe.FailureKind.UNREACHABLE,
                HandlerSupport.violationMessage(refused));
        }
        server = Objects.requireNonNull(servers.findById(serverId));
        try {
            steps.enrollWithToken(server, token);
        } catch (Violations refused) {
            // The token may be SPENT even though this failed (the daemon can accept it and
            // still report the client untrusted): the record says so, and the next probe
            // clears it if the daemon does trust the certificate after all.
            return failed(serverId, HostProbe.FailureKind.UNTRUSTED,
                HandlerSupport.violationMessage(refused));
        }
        // The enrolment PROVED the daemon answers this client as trusted: that is a contact.
        HostProbe.recordSuccess(String.valueOf((Object) server.get(ServerModel.NAME)));
        return Outcome.COMPLETE;
    }

    /**
     * Record an incomplete enrolment on the committed host row.
     *
     * AIDEV-NOTE: written directly rather than through HostProbe.recordFailure, which alerts the
     * notification channels on the transition: the operator who pasted the target is looking at
     * the result right now (a warning toast plus the Overview), and a fresh host that never
     * answered did not "stop answering".
     */
    private static @NonNull Outcome failed(@NonNull Object serverId, HostProbe.@NonNull FailureKind kind,
                                           @NonNull Microcopy reason) {
        ServerModel servers = Models.get(ServerModel.class);
        Row server = servers.findById(serverId);
        String name = server != null ? String.valueOf((Object) server.get(ServerModel.NAME)) : "?";
        Microcopy failure = serverCopy("enrolment_incomplete")
            .withArg("name", name)
            .withArg("reason", reason);
        if (server != null) {
            server.set(ServerModel.LAST_ERROR_KIND, kind.token);
            server.set(ServerModel.LAST_ERROR, hostCopy(failure));
            servers.save(server);
        }
        Blast.slog("hohenheim.host.enrolment_incomplete",
            Map.of("server", name, "kind", kind.token));
        return new Outcome(false, failure);
    }

    /**
     * Refuse to run inside a write transaction: every step here can wait on a remote daemon.
     *
     * @throws IllegalStateException when called inside a transaction on the host model's datasource
     */
    private static void requireOutsideTransaction() {
        ServerModel servers = Models.get(ServerModel.class);
        if (!servers.resolvesDatasource()) {
            return;
        }
        Datasource datasource = servers.getResolvedDatasource();
        if (datasource.hasActiveTransaction()) {
            throw new IllegalStateException("host enrolment reaches a remote daemon and must run"
                + " outside any write transaction");
        }
    }
}
