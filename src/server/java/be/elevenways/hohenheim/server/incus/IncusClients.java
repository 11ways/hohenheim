package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.model.HostTrustSlot;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Builds the {@link IncusClient} for one host record, failing CLOSED exactly like the
 * ssh lane: an https endpoint with no pinned server certificate or no client identity
 * is not connectable at all -- there is no "just this once" path back to ambient
 * trust, and no CA in this decision anywhere.
 */
public final class IncusClients {

    private IncusClients() {
    }

    /**
     * @throws IllegalArgumentException when the record does not declare the incus runtime
     * @throws HostKeys.HostTrustException when the https lane is unpinned or identity-less
     */
    public static @NonNull IncusClient forServer(@NonNull Row server) {
        if (!ServerModel.isIncus(server)) {
            throw new IllegalArgumentException("Host '" + server.get(ServerModel.NAME)
                + "' declares the " + ServerModel.runtimeOf(server)
                + " runtime; it has no Incus daemon to address");
        }
        IncusEndpoint endpoint = IncusEndpoint.of(server);
        if (!endpoint.https()) {
            return new IncusClient(new UnixIncusTransport(endpoint.socketPath()));
        }
        String name = String.valueOf((Object) server.get(ServerModel.NAME));
        String pinned = server.get(HostTrustSlot.INCUS_TLS.material());
        if (pinned == null || pinned.isBlank()) {
            throw new HostKeys.HostTrustException(HostProbe.FailureKind.NOT_PINNED,
                "Host '" + name + "' has no pinned Incus server certificate; scan and"
                    + " confirm it first");
        }
        String identityKey = server.get(HostTrustSlot.INCUS_TLS.clientPrivate());
        String identityCert = server.get(HostTrustSlot.INCUS_TLS.clientPublic());
        if (identityKey == null || identityKey.isBlank()
                || identityCert == null || identityCert.isBlank()) {
            throw new HostKeys.HostTrustException(HostProbe.FailureKind.NO_IDENTITY,
                "Host '" + name + "' has no Hohenheim client certificate; rotate the"
                    + " identity and enroll it on the daemon");
        }
        return new IncusClient(new HttpsIncusTransport(endpoint.host(), endpoint.port(),
            identityCert, identityKey, pinned));
    }
}
