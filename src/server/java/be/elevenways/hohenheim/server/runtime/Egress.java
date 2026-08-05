package be.elevenways.hohenheim.server.runtime;

/**
 * The DECLARED egress posture of a workload kind: whether the workload may open
 * connections to the outside world at all. Declared where the kind builds its driver,
 * exactly like {@link NetworkPosture} -- never a per-record knob -- so egress is a
 * stated fact on the workload, not an ambient default a reader has to infer from the
 * absence of a rule.
 */
public enum Egress {

    /**
     * Outbound connections to public addresses are allowed (the tenant-range denies
     * still apply). Game servers, sites and stacks declare this: they fetch plugins,
     * dependencies and upstream APIs.
     */
    OPEN,

    /**
     * No workload-initiated outbound traffic at all beyond replies to established
     * connections. Managed databases declare this: an engine container has no
     * legitimate outbound-initiated traffic, and an exfiltrating or trojaned one
     * should find the door closed.
     */
    NONE
}
