package be.elevenways.hohenheim.server.runtime;

/**
 * The DECLARED network posture of a workload kind, chosen where the kind builds its
 * driver ({@code InstanceKindHandler.runtimeFor}) -- never a per-record knob, so a
 * tenant-reachable settings form can never opt a workload out of isolation.
 */
public enum NetworkPosture {

    /**
     * One private user-defined network per workload with the verified nft policy
     * ({@code WorkloadNetworks.ensure}); deploy REFUSES on a host that cannot enforce
     * it. The tenant-authored posture and the default.
     */
    PRIVATE,

    /**
     * The daemon's shared default bridge, no per-workload network and no nft
     * requirement. Since the isolation wave no KIND declares this any more; it
     * survives only as the operator-declared posture of a pre-isolation STACK record
     * (stamped by migration, flipped deliberately in the operator-only stack form)
     * and as what test fixtures declare when they exercise non-network behaviour.
     */
    SHARED_BRIDGE
}
