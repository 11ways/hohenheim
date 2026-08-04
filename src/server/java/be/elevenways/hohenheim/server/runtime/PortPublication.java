package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One declared port publication of an instance workload: which container port, which
 * protocol, and whether the world (public) or only this host (loopback) may reach it.
 *
 * AIDEV-NOTE: the acquisition strategy is DERIVED, never a third knob. Record-after
 * (ephemeral host port, claim written from the daemon's readback) exists only for the
 * loopback/tcp/no-fixed-port shape, because {@code publishedPort} readback is
 * structurally TCP-only and an ephemeral public port would silently move under the DNS
 * rows pointing at it. Everything else -- UDP, public exposure, an operator-fixed host
 * port -- REQUIRES pre-allocation: the claim is written BEFORE the container is created
 * and the daemon binding is verified against it after start.
 *
 * @param containerPort     port INSIDE the container
 * @param protocol          {@code tcp} or {@code udp} (lowercase)
 * @param publicExposure    true = bind the whole host ({@code 0.0.0.0}); false = loopback
 * @param declaredHostPort  operator-fixed host port, or null to auto-allocate
 * @param preallocatedPort  the host port the deploy claimed BEFORE create; null until
 *                          the pre-allocation step resolved it (or on record-after)
 */
public record PortPublication(int containerPort,
                              @NonNull String protocol,
                              boolean publicExposure,
                              @Nullable Integer declaredHostPort,
                              @Nullable Integer preallocatedPort) {

    public static final String TCP = "tcp";
    public static final String UDP = "udp";

    /** Whether this publication's host port must be claimed BEFORE the container exists. */
    public boolean requiresPreallocation() {
        return !TCP.equals(this.protocol) || this.publicExposure || this.declaredHostPort != null;
    }

    /** The daemon bind address this publication declares. */
    public @NonNull String hostBindAddress() {
        return this.publicExposure ? "0.0.0.0" : "127.0.0.1";
    }

    /** The ledger's canonical bind spelling: whole-host folds to the empty string. */
    public @NonNull String ledgerBindAddress() {
        return this.publicExposure ? "" : "127.0.0.1";
    }

    /** A copy carrying the host port the pre-allocation step claimed. */
    public @NonNull PortPublication withPreallocatedPort(int hostPort) {
        return new PortPublication(this.containerPort, this.protocol, this.publicExposure,
            this.declaredHostPort, hostPort);
    }
}
