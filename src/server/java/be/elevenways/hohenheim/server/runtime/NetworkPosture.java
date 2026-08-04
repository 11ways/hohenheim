package be.elevenways.hohenheim.server.runtime;

/**
 * The DECLARED network posture of a workload kind, chosen where the kind builds its
 * driver ({@code InstanceKindHandler.runtimeFor}) -- never a per-record knob, so a
 * tenant-reachable settings form can never opt a workload out of isolation.
 */
public enum NetworkPosture {

    /**
     * One private user-defined network per workload with the verified nft policy
     * ({@code InstanceNetworks.ensure}); deploy REFUSES on a host that cannot enforce
     * it. The tenant-authored posture and the default.
     */
    PRIVATE,

    /**
     * The daemon's shared default bridge, no per-workload network and no nft
     * requirement: the operator-authored posture Docker sites, stacks and managed
     * databases have always run with. A kind declaring this must be one only
     * operator-tier code can author (see SiteContainerKind's scope guard).
     */
    SHARED_BRIDGE
}
