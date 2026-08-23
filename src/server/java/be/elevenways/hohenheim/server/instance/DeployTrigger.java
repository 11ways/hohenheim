package be.elevenways.hohenheim.server.instance;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * WHO asked for a deploy, and the one thing that answer is allowed to decide: whether
 * this deploy may START a workload that is not running.
 *
 * AIDEV-NOTE: the trigger already travelled as a bare {@code reason} string
 * ({@code InstanceService.deploy(int, String)}, recorded on the activity row and on the
 * build operation). This enum is that string's DECLARING HOME rather than a second
 * vocabulary beside it -- {@link #of} reads the word the caller already passes, so no
 * surface has to learn a new one and the existing recording keeps working verbatim.
 *
 * AIDEV-NOTE: an UNRECOGNISED word answers like {@link #SYSTEM}, and that is a decision
 * with a reason rather than a shrug. Every deploy lane in this repo other than the forge
 * webhook is control-plane convergence with nobody else's intent involved -- a migration
 * settling on its destination, a preview environment the same push just created, a stack
 * adopting a container, a schedule chain an operator authored. Refusing to start those
 * would leave a workload the control plane itself just moved permanently down. The
 * third-party lane is the one that has to be BUILT, and building one means declaring a
 * member here; the {@code WorkspaceBuilds} test journey is what fails if a webhook lane
 * ever stops resolving to {@link #WEBHOOK}.
 */
public enum DeployTrigger {

    /** A person pressed a control on this record; they are standing there asking. */
    MANUAL("manual", true),

    /**
     * The control plane converging its own work: {@code InstanceService}'s default
     * reason, and everything that reaches deploy without naming a trigger.
     */
    SYSTEM(InstanceService.DEFAULT_DEPLOY_REASON, true),

    /**
     * Someone else's {@code git push}, relayed by a forge.
     *
     * <p>This is the ONE trigger that may not start a stopped workload. An operator who
     * stopped a workspace expressed an intention about their host's resources, and a
     * third party's push is not an argument against it -- the deploy is recorded, the
     * decision is stated, and nothing is spent.</p>
     */
    WEBHOOK("webhook", false);

    private final @NonNull String word;
    private final boolean startsStoppedWorkload;

    DeployTrigger(@NonNull String word, boolean startsStoppedWorkload) {
        this.word = word;
        this.startsStoppedWorkload = startsStoppedWorkload;
    }

    /** The reason word this trigger travels as. */
    public @NonNull String word() {
        return this.word;
    }

    /** Whether a deploy on this trigger may bring a workload that is down back up. */
    public boolean startsStoppedWorkload() {
        return this.startsStoppedWorkload;
    }

    /** The trigger a deploy reason names; an unrecognised word reads as {@link #SYSTEM}. */
    public static @NonNull DeployTrigger of(@Nullable String reason) {
        if (reason != null) {
            for (DeployTrigger trigger : values()) {
                if (trigger.word.equalsIgnoreCase(reason.trim())) {
                    return trigger;
                }
            }
        }
        return SYSTEM;
    }
}
