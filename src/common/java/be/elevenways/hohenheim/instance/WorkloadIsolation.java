package be.elevenways.hohenheim.instance;

/**
 * The boundary a workload's runtime puts between the guest and the host kernel -- the
 * ISOLATION axis of the plan's "every workload declares trusted/operator-owned vs
 * hostile-tenant" clause, which is a SECOND axis and not a re-spelling of the first.
 *
 * AIDEV-NOTE: the trust axis already exists as {@code InstanceKindHandler.tenantAuthored()}
 * (who wrote this workload); this one answers what CONTAINS it. They are independent: an
 * operator-authored container and a hostile-tenant container share the same kernel, and a
 * hostile-tenant VM does not. Placement needs both, because a host posture promises a
 * boundary and only the isolation axis can say whether a workload provides it.
 *
 * SHARED_KERNEL is the conservative answer and therefore the DEFAULT: a kind that has not
 * declared anything is charged with the weakest boundary rather than the strongest.
 */
public enum WorkloadIsolation {

    /** A container: the guest runs on the host's kernel, however hardened. */
    SHARED_KERNEL,

    /** A virtual machine: its own kernel behind a hypervisor boundary. */
    VIRTUAL_MACHINE
}
