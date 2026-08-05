package be.elevenways.hohenheim.model;

import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.Field;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * ONE trust relationship's storage: the pinned material an operator confirms, its
 * fingerprint, the pin bookkeeping, and the client credential we present on that same
 * wire. A host row carries TWO of these and they never share a column.
 *
 * AIDEV-NOTE: this record exists because the columns used to be shared. {@code host_key}
 * held an ssh {@code known_hosts} line for a docker host and an Incus server-certificate
 * PEM for an incus host, and {@code identity_private_key} likewise held either an ssh
 * private key or a TLS client key. One column cannot be the authority for two independent
 * trust relationships and it bit twice: {@code HostAdmission.requireBackupDestination}
 * needed an explicit runtime check so a PEM could not reach the known_hosts writer (which
 * would materialise a pin ssh can never match -- a pin that verifies nothing), and an
 * Incus host could not hold an ssh admin lane AT ALL because its TLS material occupied the
 * slot, which is exactly what kernel-truth isolation verification of a remote Incus daemon
 * needs. Splitting the columns makes writing one kind of secret into the other's slot
 * unexpressible rather than merely discouraged, and lets one host hold both at once.
 *
 * @param name             the operator-facing name of the wire this trust is about
 * @param material         the pinned key line / certificate PEM
 * @param fingerprint      the operator-facing digest of {@link #material}
 * @param verified         whether an operator confirmed the fingerprint out of band
 * @param pinnedAt         when the pin was established or replaced
 * @param offered          the material a rescan saw offered while it disagreed with the pin
 * @param clientPublic     the credential half an operator installs on the remote
 * @param clientPrivate    the credential half we keep (encrypted at rest)
 */
public record HostTrustSlot(@NonNull String name,
                            @NonNull Field<String, String> material,
                            @NonNull Field<String, String> fingerprint,
                            @NonNull BooleanField verified,
                            @NonNull DateTimeField pinnedAt,
                            @NonNull Field<String, String> offered,
                            @NonNull Field<String, String> clientPublic,
                            @NonNull Field<String, String> clientPrivate) {

    /** SSH host-key trust plus the per-host client keypair; every {@code ssh} argv reads it. */
    public static final HostTrustSlot SSH = new HostTrustSlot("ssh",
        ServerModel.HOST_KEY, ServerModel.HOST_KEY_FINGERPRINT,
        ServerModel.HOST_KEY_VERIFIED, ServerModel.HOST_KEY_PINNED_AT,
        ServerModel.HOST_KEY_OFFERED,
        ServerModel.IDENTITY_PUBLIC_KEY, ServerModel.IDENTITY_PRIVATE_KEY);

    /** Incus TLS trust: the pinned server certificate plus this host's client certificate. */
    public static final HostTrustSlot INCUS_TLS = new HostTrustSlot("incus",
        ServerModel.INCUS_SERVER_CERT, ServerModel.INCUS_SERVER_CERT_FINGERPRINT,
        ServerModel.INCUS_SERVER_CERT_VERIFIED, ServerModel.INCUS_SERVER_CERT_PINNED_AT,
        ServerModel.INCUS_SERVER_CERT_OFFERED,
        ServerModel.INCUS_CLIENT_CERT, ServerModel.INCUS_CLIENT_KEY);

    /**
     * The slot the host's PRIMARY transport authenticates with: TLS for an Incus daemon,
     * ssh for a Docker one. An Incus host's ssh admin lane is a SECOND relationship and
     * is always named explicitly ({@link #SSH}), never derived from the runtime.
     */
    public static @NonNull HostTrustSlot transportOf(@NonNull Row server) {
        return ServerModel.isIncus(server) ? INCUS_TLS : SSH;
    }

    /** Whether this slot holds pinned material at all. */
    public boolean isPinned(@NonNull Row server) {
        String pinned = server.get(this.material);
        return pinned != null && !pinned.isBlank();
    }

    /** Whether an operator confirmed this slot's pinned fingerprint out of band. */
    public boolean isConfirmed(@NonNull Row server) {
        return this.isPinned(server) && Boolean.TRUE.equals(server.get(this.verified));
    }

    /** The material a rescan saw offered while it disagreed with the pin, or null. */
    public @NonNull String offeredOf(@NonNull Row server) {
        String offered = server.get(this.offered);
        return offered != null ? offered : "";
    }
}
