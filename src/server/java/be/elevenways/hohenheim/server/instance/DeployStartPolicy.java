package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.setting.ContentLocales;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE decision a deploy trigger is allowed to take: may this deploy start a workload that
 * is currently down, and what does the refusal say.
 *
 * AIDEV-NOTE: single-homed here because it must hold for EVERY kind. It shipped for the
 * workspace lane only ({@code WorkspaceBuilds.declineToStart}), so a forge push to a
 * STOPPED application went straight into {@code ApplicationReleases.converge} and started
 * it -- the same defect the workspace half had been fixed for, one branch of
 * {@code InstanceService.deploy} away. Each lane hands its OWN evidence of "an operator
 * stopped this" (see the two readers below); nothing else re-spells the decision or the
 * refusal text.
 *
 * AIDEV-NOTE: the refusal is ONE microcopy key for every kind. Its predecessor was named
 * for the workspace, and a second key for applications would be the same sentence written
 * twice -- the name in it is the workload's own.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class DeployStartPolicy {

    private DeployStartPolicy() {
    }

    /**
     * The refusal for a lane that can ask the DAEMON, or null when the deploy may proceed.
     *
     * AIDEV-NOTE: {@code UNREACHABLE} deliberately does NOT decline. "The daemon says the
     * container is down" and "I could not ask the daemon" are different answers (see
     * {@link ContainerState}), and telling a pusher "you stopped this workload" because a
     * host was briefly unaddressable would be a confident lie. An unreachable host falls
     * through to the ordinary bring-up, which fails loudly and records FAILED with the
     * daemon's own reason.
     *
     * AIDEV-NOTE: STOPPED here is the daemon's, so a CRASHED container reads as an
     * operator's stop and a push is refused where it should have redeployed. That
     * conflation is known and deliberately left alone (P3); the stored-status reader below
     * does not share it.
     *
     * @param state the live state the daemon just reported
     */
    public static @Nullable Microcopy declineToStart(@NonNull DeployTrigger trigger,
                                                     @NonNull ContainerState state,
                                                     @NonNull Row instance) {
        return decline(trigger,
            state == ContainerState.STOPPED || state == ContainerState.ABSENT, instance);
    }

    /**
     * The refusal for a lane that must read the STORED status, or null when it may proceed.
     *
     * <p>Used where the record the operator stopped is not the record that owns a
     * container: an application's stop settles on its serving release, and the status that
     * stop wrote is the only evidence of an INTENTION -- a daemon that merely reports the
     * container down cannot tell a stop from a crash.</p>
     *
     * @param record the workload record whose stored status the operator's stop wrote,
     *        or null when nothing has ever been deployed (never a refusal: nobody stopped
     *        what nobody started)
     */
    public static @Nullable Microcopy declineToStartStored(@NonNull DeployTrigger trigger,
                                                           @Nullable Row record,
                                                           @NonNull Row named) {
        boolean stopped = record != null
            && InstanceModel.STATUS_STOPPED.equals(record.get(InstanceModel.STATUS));
        return decline(trigger, stopped, named);
    }

    /** The decision itself; both readers above end here and nothing else takes it. */
    private static @Nullable Microcopy decline(@NonNull DeployTrigger trigger, boolean stopped,
                                               @NonNull Row named) {
        if (trigger.startsStoppedWorkload() || !stopped) {
            return null;
        }
        return Microcopy.of("push_does_not_start_stopped_workload")
            .withFilter("scope", "violations")
            .withArg("name", String.valueOf((Object) named.get(InstanceModel.NAME)));
    }

    /**
     * The refusal as TEXT for the records a person reads later (a build operation row, a
     * forge commit status).
     *
     * AIDEV-NOTE: resolved here rather than stored as a violation key, because
     * {@code Violations.getMessage()} is a debug rendering by its own contract. There is
     * no reader to localize for at this point, so it is the installation's default content
     * locale -- the ErrorPages/InstanceShellHandler precedent.
     */
    public static @NonNull String textOf(@NonNull Microcopy declined) {
        return declined.resolve(LocaleChain.of(ContentLocales.getDefault()),
            MessageResolvers.getDefault());
    }
}
