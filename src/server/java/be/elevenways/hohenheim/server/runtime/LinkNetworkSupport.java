package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Optional driver capability: a policied LINK network joining exactly the workloads that
 * were explicitly connected to it (the game-domains proxy-to-backend lane). A link
 * network gets the SAME kernel policy as a per-instance network -- members reach each
 * other over it and nothing else -- so attaching two workloads opens exactly one
 * authorized pair, never the host, the metadata range or another tenant.
 *
 * AIDEV-NOTE: a link network is Docker-{@code Internal} by construction (no gateway, no
 * masquerade, never a member's default route). Every member keeps its OWN per-instance
 * network for egress; the link carries member-to-member traffic and nothing else, which
 * is what the {@code egress} declaration below bounds in the kernel on top of that.
 */
public interface LinkNetworkSupport {

    /**
     * Create (or verify) the link network named after {@code linkHandle} with its kernel
     * policy applied and read-back verified.
     *
     * @param egress the DECLARED egress posture of the link network itself: OPEN for a
     *        proxy-to-backend lane, NONE for a lane whose members (a database engine)
     *        have no legitimate outbound-initiated traffic -- a second interface must
     *        never widen what a NONE-egress workload was declared to do
     * @return the network name
     * @throws IOException when the network or its policy cannot be enforced
     */
    @NonNull String ensureLinkNetwork(@NonNull String linkHandle,
                                      @NonNull Map<String, String> ownerLabels,
                                      @NonNull Egress egress) throws IOException;

    /**
     * Connect an existing container to the link network; a no-op when already connected,
     * a loud failure when the container does not exist.
     *
     * @param aliases extra DNS names this member answers to ON THIS NETWORK ONLY -- the
     *        compose {@code service} name a sibling dials. Empty for a lane whose members
     *        already know each other's handles. An alias is scoped to one link network, so
     *        it can never make a workload resolvable to anything not connected to it.
     */
    void connectToLinkNetwork(@NonNull String linkHandle, @NonNull String containerHandle,
                              @NonNull List<String> aliases) throws IOException;

    /**
     * Disconnect every member and remove the link network plus its kernel chains;
     * verified absent afterwards.
     */
    void removeLinkNetwork(@NonNull String linkHandle) throws IOException;
}
