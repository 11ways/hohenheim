package be.elevenways.hohenheim.server.runtime;

import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The KIND-DECLARED incus workload flavour: same driver, same transport, one declared
 * difference set (the Egress shape). What a VM changes is enumerated HERE so the
 * driver never infers it: the API type string, {@code security.secureboot=false}
 * (the images are not Secure Boot signed -- the first launch fails naming exactly
 * that), and a longer exec-ready window because a VM's incus agent comes up with the
 * guest, tens of seconds after start, while a container accepts execs almost at once.
 */
public enum IncusWorkloadType {

    CONTAINER("container", 30_000),
    // ~4 minutes to agent-up OBSERVED on a small host's first boot; the window is a
    // ceiling, not a wait -- a ready agent answers the first try.
    VIRTUAL_MACHINE("virtual-machine", 600_000);

    private final @NonNull String apiType;
    private final long execReadyTimeoutMs;

    IncusWorkloadType(@NonNull String apiType, long execReadyTimeoutMs) {
        this.apiType = apiType;
        this.execReadyTimeoutMs = execReadyTimeoutMs;
    }

    /** The {@code type} value of the instance create body and the daemon's read-back. */
    public @NonNull String apiType() {
        return this.apiType;
    }

    /** How long a freshly started workload may refuse execs before that is a failure. */
    public long execReadyTimeoutMs() {
        return this.execReadyTimeoutMs;
    }
}
