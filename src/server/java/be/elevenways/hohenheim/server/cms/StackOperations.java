package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.StackDeploymentModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.docker.DockerReclaim;
import be.elevenways.hohenheim.server.stack.StackInstances;
import be.elevenways.hohenheim.server.stack.StackRuntime;
import be.elevenways.hohenheim.server.task.ReclaimDockerImages;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationFact;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * The stack entries' verbs as operations: deploying, stopping, rolling back, purging the volumes of and refreshing a
 * stack, the daemon-wide image reclaim, and the stack and service deletes that take their workloads down first.
 *
 * AIDEV-NOTE: the ids are the legacy row and header actions' own. These entries live on /admin only, so every gate is
 * the admin panel's permission. Deploy, stop, rollback and the purge are QUEUED onto the stack's worker and recorded
 * by StackRuntime when they SETTLE, which is why no handler here writes an activity row: the runtime snapshots the
 * caller's attribution and re-enters it on the worker. Refresh is a read and the reclaim is per DAEMON, neither a stack
 * operation to record. Both deletes own their write envelope: their Docker teardown runs outside any transaction,
 * because the stack's worker writes rows of its own while it runs.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class StackOperations {

    private static final OperationGate OPERATOR = OperationGate.permission(HohenheimPanel.ACCESS);

    /** One stack record. */
    public static final SubjectType<Row> STACK = SubjectType.record(StackModel.MODEL_ID);

    /** One stack service record. */
    public static final SubjectType<Row> SERVICE = SubjectType.record(StackServiceModel.MODEL_ID);

    /** Queues a deploy of the stack's desired state. */
    public static final Operation<Row, Void, Void> DEPLOY = Operation.declare(HohenheimIds.id("deploy_stack"))
        .happened(OperationSentences.of("deploy_stack"))
        .label(words("deploy"))
        .icon(Icon.of("rocket"))
        .one(STACK)
        .gate(OPERATOR)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Queues stopping the stack's containers; applies to an active or degraded stack. */
    public static final Operation<Row, Void, Void> STOP = Operation.declare(HohenheimIds.id("stop_stack"))
        .happened(OperationSentences.of("stop_stack"))
        .label(words("stop"))
        .icon(Icon.of("circle-stop"))
        .one(STACK)
        .gate(OPERATOR)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Queues redeploying the last successful deployment; applies when one exists. */
    public static final Operation<Row, Void, Void> ROLLBACK = Operation.declare(HohenheimIds.id("rollback_stack"))
        .happened(OperationSentences.of("rollback_stack"))
        .label(words("rollback"))
        .icon(Icon.of("clock-rotate-left"))
        .one(STACK)
        .gate(OPERATOR)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Queues removing the stack's OWNED volumes; external volumes survive it. */
    public static final Operation<Row, Void, Void> PURGE_VOLUMES =
        Operation.declare(HohenheimIds.id("purge_stack_volumes"))
            .happened(OperationSentences.of("purge_stack_volumes"))
            .label(words("purge_volumes"))
            .icon(Icon.of("hard-drive"))
            .one(STACK)
            .gate(OPERATOR)
            .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** Reads the stack's live state back into its status; the result is that status. */
    public static final Operation<Row, Void, String> REFRESH = Operation.declare(HohenheimIds.id("refresh_stack"))
        .happened(OperationSentences.of("refresh_stack"))
        .label(words("refresh_status"))
        .icon(Icon.of("rotate"))
        .one(STACK)
        .gate(OPERATOR)
        .result(String.class)
        .facts(OperationFact.REACHES_OUTSIDE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Starts the image reclaim sweep over every daemon, the nightly ReclaimDockerImages task's own. */
    public static final Operation<Void, Void, Void> RECLAIM_IMAGES =
        Operation.declare(HohenheimIds.id("reclaim_images"))
            .happened(OperationSentences.of("reclaim_images"))
            .label(words("reclaim_images"))
            .icon(Icon.of("broom"))
            .noSubject()
            .gate(OPERATOR)
            .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
            .command(CmsCommands.EXTERNAL)
            .register();

    /** Removes the stack's owned containers and network (volumes stay), then the stack with its rows. */
    public static final Operation<Row, Void, Integer> DELETE_STACK = Operation.declare(HohenheimIds.id("delete_stack"))
        .happened(OperationSentences.of("delete_stack"))
        .label(Microcopy.of("delete").withFilter("scope", "cms"))
        .icon(Icon.TRASH)
        .one(STACK)
        .gate(OPERATOR)
        .result(Integer.class)
        .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
        .command(CmsCommands.EXTERNAL)
        .register();

    /** Removes the service's owned workload, then the service with its config files. */
    public static final Operation<Row, Void, Integer> DELETE_SERVICE =
        Operation.declare(HohenheimIds.id("delete_stack_service"))
            .happened(OperationSentences.of("delete_stack_service"))
            .label(Microcopy.of("delete").withFilter("scope", "cms"))
            .icon(Icon.TRASH)
            .one(SERVICE)
            .gate(OPERATOR)
            .result(Integer.class)
            .facts(OperationFact.REACHES_OUTSIDE, OperationFact.DESTRUCTIVE)
            .command(CmsCommands.EXTERNAL)
            .register();

    static {
        OperationHandlers.attach(DEPLOY).handle(call -> {
            StackRuntime.get().deployAsync(call.subject().get(StackModel.ID), "manual");
            return null;
        });
        OperationHandlers.attach(STOP)
            .applies(stack -> StackModel.STATUS_ACTIVE.equals(stack.get(StackModel.STATUS))
                || StackModel.STATUS_DEGRADED.equals(stack.get(StackModel.STATUS)))
            .handle(call -> {
                StackRuntime.get().stopAsync(call.subject().get(StackModel.ID));
                return null;
            });
        OperationHandlers.attach(ROLLBACK)
            .applies(stack -> Models.get(StackDeploymentModel.class)
                .findLatestSuccessful(stack.get(StackModel.ID)) != null)
            .handle(call -> {
                StackRuntime.get().rollbackAsync(call.subject().get(StackModel.ID));
                return null;
            });
        OperationHandlers.attach(PURGE_VOLUMES).handle(call -> {
            StackRuntime.get().purgeVolumesAsync(call.subject().get(StackModel.ID));
            return null;
        });
        OperationHandlers.attach(REFRESH)
            .handle(call -> StackRuntime.get().refreshStatus(call.subject().get(StackModel.ID)));
        OperationHandlers.attach(RECLAIM_IMAGES).handle(call -> {
            reclaimInBackground();
            return null;
        });
        OperationHandlers.attach(DELETE_STACK).handle(call -> deleteStack(call.subject()));
        OperationHandlers.attach(DELETE_SERVICE).handle(call -> deleteService(call.subject()));
    }

    private StackOperations() {
    }

    /** Loads the class, declaring the operations and attaching their handlers; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /** @return the stack scope's words for {@code key} */
    static @NonNull Microcopy words(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "stack");
    }

    /**
     * A sweep visits every daemon and can remove multi-GB images, so it never runs on the request thread; the outcome
     * lands in the server log exactly like the nightly task's.
     */
    private static void reclaimInBackground() {
        JobRunner.startVirtualThread(() -> {
            Map<String, DockerReclaim.Outcome> outcomes = StackRuntime.get().reclaimImages(
                ReclaimDockerImages.minimumAge(), ReclaimDockerImages.includeUnattributed());
            DockerReclaim.Outcome total = outcomes.values().stream()
                .reduce(DockerReclaim.Outcome.EMPTY, DockerReclaim.Outcome::plus);
            Blast.log("DOCKER RECLAIM: manual sweep removed", total.removed(), "images,", total.megabytes(),
                "MiB, skipped", total.skipped());
        });
    }

    /**
     * Tears the stack's containers and network down, then deletes the stack row; the row cascade (services, their
     * files, the deployment history) is the model funnel's (StackCascades), in ONE transaction with the stack row, so a
     * failure mid-sweep never leaves a stack alive with its children gone. The Docker teardown stays outside it.
     *
     * @throws Violations when the runtime teardown fails; the reason goes to the log, the operator reads a stable one
     */
    private static @NonNull Integer deleteStack(@NonNull Row stack) {
        Integer stackId = stack.get(StackModel.ID);
        try {
            StackRuntime.get().destroy(stackId, false);
        } catch (IOException teardown) {
            Blast.log("STACK: delete of stack", stackId, "refused -- the runtime teardown failed:",
                teardown.getMessage());
            throw Violations.ofForm(CmsSupport.violationText("stack_destroy_failed"));
        }
        boolean[] deleted = new boolean[1];
        Models.get(StackModel.class).getResolvedDatasource().withTransaction(transaction ->
            deleted[0] = Models.get(StackModel.class).delete(stack));
        return deleted[0] ? 1 : 0;
    }

    /**
     * Refuses while a sibling depends on the service, takes its owned workload down, then deletes the service row
     * (its config files, and the refusal while its workload is still live, are the model funnel's: StackCascades).
     *
     * AIDEV-NOTE: the owned instance must die WITH the record: InstanceService.destroy soft-deletes, so no remove hook
     * fires and nothing else would ever take the workload down. The next deploy's prune is the SECOND line, and the
     * funnel's own refusal the LAST (it fires for a direct delete).
     *
     * @throws Violations when a sibling still depends on the service, or its workload cannot be taken down
     */
    private static @NonNull Integer deleteService(@NonNull Row service) {
        Integer serviceId = service.get(StackServiceModel.ID);
        Integer stackId = service.get(StackServiceModel.STACK_ID);
        if (stackId != null) {
            StackParts.refuseWhenDependedUpon(stackId, serviceId, String.valueOf((Object) service.get(
                StackServiceModel.NAME)));
        }
        try {
            StackInstances.destroyFor(serviceId);
        } catch (IOException undeletable) {
            throw Violations.ofForm(CmsSupport.violationText("stack_destroy_failed"));
        }
        return Models.get(StackServiceModel.class).delete(service) ? 1 : 0;
    }
}
