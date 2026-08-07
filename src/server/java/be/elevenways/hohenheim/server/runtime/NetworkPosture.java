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
     * survives only as what record-less test/preview callers declare when they
     * exercise non-network behaviour. Stacks are not an exception either -- since
     * the Phase 7 lowering their services ARE instances and {@code StackServiceKind}
     * declares {@link #PRIVATE} through this enum like every other tier.
     */
    SHARED_BRIDGE
}
