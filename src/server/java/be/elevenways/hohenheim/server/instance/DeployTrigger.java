package be.elevenways.hohenheim.server.instance;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * WHO asked for a deploy, and the one thing that answer is allowed to decide: whether
 * this deploy may START a workload that is not running.
 *
 * AIDEV-NOTE: this is the PARAMETER TYPE of the deploy spine, not a reading of a string
 * a caller happened to pass. {@code InstanceService.deploy}, {@code WorkspaceBuilds.deploy},
 * {@code ApplicationDeploys.deploy} and the preview lane all take a member, so renaming a
 * member's {@link #word} can no longer flip a security decision without the compiler
 * saying so. The word survives only at the STORAGE boundary -- the activity row's detail
 * and the build operation's record -- which is what {@link #of} exists to read back.
 *
 * AIDEV-NOTE: an UNRECOGNISED word FAILS CLOSED (it answers like the least-privileged
 * member, {@link #WEBHOOK}). It used to answer like {@link #SYSTEM}, the permissive
 * member, on the argument that every in-house lane is control-plane convergence -- true
 * of the lanes that existed, and worthless as a guard: a typo, a renamed member or a new
 * third-party surface that spelled its own word all silently bought permission to start a
 * workload an operator stopped. Nothing production-facing calls {@link #of} any more, so
 * failing closed costs nothing and the in-house lanes keep their permission by NAMING it.
 */
public enum DeployTrigger {

    /** A person pressed a control on this record; they are standing there asking. */
    MANUAL("manual", true),

    /**
     * An API key holder asked over {@code /api/v1} or the PaaS API: a deliberate request
     * from a credential an operator issued, so it may start what it deploys -- and it
     * keeps its own word so the activity row still says WHICH surface asked.
     */
    API("api", true),

    /**
     * The control plane converging its own work: {@code InstanceService}'s default
     * reason, and everything that reaches deploy without naming a trigger.
     */
    SYSTEM(InstanceService.DEFAULT_DEPLOY_REASON, true),

    /**
     * Someone else's {@code git push}, relayed by a forge.
     *
     * <p>This is the ONE trigger that may not start a stopped workload. An operator who
     * stopped a workload expressed an intention about their host's resources, and a
     * third party's push is not an argument against it -- the deploy is recorded, the
     * decision is stated, and nothing is spent.</p>
     */
    WEBHOOK("webhook", false);

    /**
     * What an unrecognised stored word reads as: the first DECLARED member that may not
     * start a stopped workload.
     *
     * AIDEV-NOTE: derived rather than written down, so a future member set in which every
     * trigger is permissive fails the class initializer instead of silently restoring the
     * fail-open behaviour this constant exists to end.
     */
    private static final @NonNull DeployTrigger LEAST_PRIVILEGED = leastPrivileged();

    private final @NonNull String word;
    private final boolean startsStoppedWorkload;

    DeployTrigger(@NonNull String word, boolean startsStoppedWorkload) {
        this.word = word;
        this.startsStoppedWorkload = startsStoppedWorkload;
    }

    /** The reason word this trigger is recorded under. */
    public @NonNull String word() {
        return this.word;
    }

    /** Whether a deploy on this trigger may bring a workload that is down back up. */
    public boolean startsStoppedWorkload() {
        return this.startsStoppedWorkload;
    }

    /**
     * The trigger a STORED reason word names; an unrecognised or absent word fails closed.
     *
     * @return the named member, or {@link #LEAST_PRIVILEGED} for anything else
     */
    public static @NonNull DeployTrigger of(@Nullable String reason) {
        if (reason != null) {
            for (DeployTrigger trigger : values()) {
                if (trigger.word.equalsIgnoreCase(reason.trim())) {
                    return trigger;
                }
            }
        }
        return LEAST_PRIVILEGED;
    }

    private static @NonNull DeployTrigger leastPrivileged() {
        for (DeployTrigger trigger : values()) {
            if (!trigger.startsStoppedWorkload) {
                return trigger;
            }
        }
        throw new IllegalStateException("DeployTrigger declares no restrictive member, so an"
            + " unrecognised reason word would fail OPEN");
    }
}
