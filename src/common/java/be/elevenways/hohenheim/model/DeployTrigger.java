package be.elevenways.hohenheim.model;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.ui.BadgeVariant;
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
 * AIDEV-NOTE: the ONE declaring home of the trigger vocabulary. A release operation stores the word
 * ({@code ReleaseOperationModel.TRIGGER}, whose enum values are derived from these members with their words, icons and
 * colours), so adding a member is one edit here plus its {@code deploy_trigger} microcopy.
 *
 * AIDEV-NOTE: an UNRECOGNISED word FAILS CLOSED (it answers like the least-privileged
 * member, {@link #WEBHOOK}). It used to answer like {@link #SYSTEM}, the permissive
 * member, on the argument that every in-house lane is control-plane convergence -- true
 * of the lanes that existed, and worthless as a guard: a typo, a renamed member or a new
 * third-party surface that spelled its own word all silently bought permission to start a
 * workload an operator stopped. Nothing production-facing calls {@link #of} any more, so
 * failing closed costs nothing and the in-house lanes keep their permission by NAMING it.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public enum DeployTrigger {

    /** A person pressed a control on this record; they are standing there asking. */
    MANUAL("manual", null, true, true, false, "user", BadgeVariant.INFO),

    /**
     * An API key holder asked over {@code /api/v1} or the PaaS API: a deliberate request
     * from a credential an operator issued, so it may start what it deploys -- and it
     * keeps its own word so the activity row still says WHICH surface asked.
     */
    API("api", null, true, true, false, "key", BadgeVariant.INFO),

    /**
     * The control plane converging its own work: {@code InstanceService}'s default
     * reason, and everything that reaches deploy without naming a trigger.
     */
    SYSTEM("deploy", null, false, false, false, "gear", BadgeVariant.SECONDARY),

    /**
     * A record schedule step placed the start or restart. Exactly {@link #SYSTEM}'s start policy, so a scheduled
     * deploy behaves as before; only its recorded word names the surface that asked.
     */
    SCHEDULE("schedule", null, false, false, false, "calendar", BadgeVariant.SECONDARY),

    /**
     * Someone else's {@code git push}, relayed by a forge.
     *
     * This is the ONE trigger that may not start a stopped workload. An operator who
     * stopped a workload expressed an intention about their host's resources, and a
     * third party's push is not an argument against it -- the deploy is recorded, the
     * decision is stated, and nothing is spent.
     */
    WEBHOOK("webhook", "push_does_not_start_stopped_workload", false, false, false, "code-branch",
        BadgeVariant.SECONDARY),

    /**
     * The scheduled check found a new commit on the source's branch ({@code GitSourcePolls}): the same third-party
     * push as {@link #WEBHOOK}, only learned by asking, so it may not start a stopped workload either.
     */
    POLL("poll", "push_does_not_start_stopped_workload", false, false, false, "magnifying-glass",
        BadgeVariant.SECONDARY),

    /**
     * A build hook's secret URL was POSTed (a CMS publishing, a cron elsewhere): the hook itself is the recorded
     * principal, never a person behind it.
     *
     * AIDEV-NOTE: an automated rebuild, so it may neither start a workload an operator stopped nor undo a rollback;
     * whoever holds the URL is a machine the owner pointed at it, not someone standing at the record.
     */
    HOOK("hook", "hook_does_not_start_stopped_workload", false, false, true, "link", BadgeVariant.SECONDARY);

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
    private final @Nullable String declinedStartKey;
    private final boolean liftsRollbackPin;
    private final boolean pressedByPerson;
    private final boolean principalIsTrigger;
    private final @NonNull String icon;
    private final @NonNull BadgeVariant variant;

    /** @param declinedStartKey the violation a deploy on this trigger states for a stopped workload, null when it starts it */
    DeployTrigger(@NonNull String word, @Nullable String declinedStartKey, boolean liftsRollbackPin,
                  boolean pressedByPerson, boolean principalIsTrigger, @NonNull String icon,
                  @NonNull BadgeVariant variant) {
        this.word = word;
        this.declinedStartKey = declinedStartKey;
        this.liftsRollbackPin = liftsRollbackPin;
        this.pressedByPerson = pressedByPerson;
        this.principalIsTrigger = principalIsTrigger;
        this.icon = icon;
        this.variant = variant;
    }

    /** The reason word this trigger is recorded under. */
    public @NonNull String word() {
        return this.word;
    }

    /** @return the trigger in words, read off the {@code deploy_trigger} scope by its word */
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.DEPLOY_TRIGGER.of(this.word);
    }

    /** @return the plumage icon beside the words */
    public @NonNull String icon() {
        return this.icon;
    }

    /** @return the badge variant the trigger wears */
    public @NonNull BadgeVariant variant() {
        return this.variant;
    }

    /** Whether a deploy on this trigger may bring a workload that is down back up. */
    public boolean startsStoppedWorkload() {
        return this.declinedStartKey == null;
    }

    /** @return the {@code violations} key a deploy on this trigger states when it leaves a stopped workload down, null when it starts it */
    public @Nullable String declinedStartKey() {
        return this.declinedStartKey;
    }

    /**
     * Whether a deploy on this trigger releases the source a rollback rejected again.
     *
     * Only a deliberate request does: a person pressing Deploy after a rollback asks for the branch's head, while a
     * convergence, a schedule or a re-delivered push of the rejected commit must keep the rollback standing.
     */
    public boolean liftsRollbackPin() {
        return this.liftsRollbackPin;
    }

    /**
     * Whether the recorded principal itself asked for this deploy, so the row may read "{trigger} by {actor}".
     *
     * Otherwise the principal is only whose request the work was reached from (a resize, a database attach, a
     * schedule's owner) and the row reads "{trigger}, requested by {actor}".
     */
    public boolean pressedByPerson() {
        return this.pressedByPerson;
    }

    /**
     * Whether the recorded principal IS the credential that asked (a build hook), so the row names it right after the
     * trigger: "{trigger} {actor}".
     */
    public boolean principalIsTrigger() {
        return this.principalIsTrigger;
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
            if (trigger.startsStoppedWorkload()) {
                continue;
            }
            return trigger;
        }
        throw new IllegalStateException("DeployTrigger declares no restrictive member, so an"
            + " unrecognised reason word would fail OPEN");
    }
}
