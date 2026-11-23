package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimRefusalReason;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.InstanceLogOperations;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.InstanceOperations.ConsoleCommandInput;
import be.elevenways.hohenheim.instance.InstanceOperations.ExecInput;
import be.elevenways.hohenheim.instance.InstanceOperations.ExecRun;
import be.elevenways.hohenheim.instance.InstanceOperations.PowerResult;
import be.elevenways.hohenheim.instance.InstanceOperations.SnapshotInput;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpToolSurface;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.operation.PlacementSurface;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.server.operation.OperationCall;
import be.elevenways.zenit.server.operation.Applicability;
import be.elevenways.zenit.server.operation.Availability;
import be.elevenways.zenit.server.operation.OperationHandlers;
import be.elevenways.zenit.server.task.record.SchedulePlacements;
import be.elevenways.zenit.server.task.record.RecordSchedules;
import be.elevenways.zenit.server.task.record.StepFailure;
import be.elevenways.hohenheim.instance.InstanceOperations.MigrateInput;
import be.elevenways.hohenheim.instance.InstanceVariableOperations;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.cms.DeleteImpact;
import be.elevenways.hohenheim.server.source.GitWebhookProvisioning;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.server.operation.InputScope;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The handlers of the instance operations and their schedule placements, attached once per JVM.
 *
 * AIDEV-NOTE: every handler funnels through the service a surface used before ({@link InstanceService},
 * {@link InstanceBackups}, {@link InstanceSnapshots}), which keeps its own gates, its operation lock and its activity
 * rows; a handler takes no lock of its own. The console line is the one handler that writes its activity row itself:
 * {@link InstanceConsoles} is also the product's own lane (GameDomains), whose lines are no operator's act. A restart
 * is {@link InstanceService#restart}, ONE lock hold across both halves, from every surface.
 *
 * AIDEV-NOTE: attached in a static initializer, so a JVM that boots twice (test hosts) attaches nothing twice;
 * {@link #init()} only forces the class to load before boot verifies every operation has its handler.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceOperationHandlers {

    /**
     * The deploy trigger per surface: the trigger decides {@link DeployStartPolicy}, so a surface missing here fails
     * closed rather than guessing. The admin's placed instance actions ({@code InstanceActions}) are a person's
     * click: MANUAL. An assistant's tool call is a key acting for its account, as an API call is: API.
     */
    private static final Map<Identifier, DeployTrigger> TRIGGERS = Map.of(
        ZenitPlacementSurface.SCHEDULE_STEP.id(), DeployTrigger.SCHEDULE,
        ZenitPlacementSurface.HTTP_API.id(), DeployTrigger.API,
        McpToolSurface.MCP_TOOL.id(), DeployTrigger.API,
        CmsPlacementSurface.ADMIN_ACTION.id(), DeployTrigger.MANUAL);

    /** The note a scheduled snapshot carries when its step stores none. */
    static final String SCHEDULED_NOTE = "scheduled";

    private static final RecordSource<InstanceModel> SUBJECTS = RecordSource.of(InstanceModel.REF)
        .id(HohenheimIds.id("instance_operation_subjects")).project(InstanceModel.ID).openToAllLoggedIn()
        .systemAccess(identity -> RecordSchedules.isScheduleWork(identity)
            ? InstanceModel.GENERATED_BY.isNull() : null).build();

    static {
        OperationHandlers.attach(InstanceOperations.START).source(SUBJECTS).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::start);
        OperationHandlers.attach(InstanceOperations.STOP).source(SUBJECTS).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::stop);
        OperationHandlers.attach(InstanceOperations.RESTART).source(SUBJECTS)
                .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::restart);
        OperationHandlers.attach(InstanceOperations.BACKUP).source(SUBJECTS)
                .applies(InstanceOperationHandlers::authored)
                .availability((row, access) -> InstanceBackups.backupRefusal(row))
            .handle(InstanceOperationHandlers::backup);
        // Only an app whose source is bound to a git provider has somewhere to register a webhook; the provider kind
        // and a working HTTPS address decide whether it can now (GitWebhookProvisioning).
        OperationHandlers.attach(InstanceOperations.CONNECT_WEBHOOK).source(SUBJECTS)
                .applies(row -> authored(row) && GitWebhookProvisioning.applies(row))
                .availability((row, access) -> GitWebhookProvisioning.refusal(row))
            .handle(call -> GitWebhookProvisioning.connect(instanceId(call)));
        OperationHandlers.attach(InstanceOperations.SNAPSHOT).source(SUBJECTS)
                .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::snapshot);
        // A generated instance's console is its product's (GameDomains sends through InstanceConsoles directly).
        OperationHandlers.attach(InstanceOperations.CONSOLE_COMMAND).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::consoleCommand);
        // AIDEV-NOTE: a background run; what refuses it at once is asked here, before the run starts, so a refused
        // rollback answers in the engine's own words on the surface that asked and mints no run: nothing kept to go
        // back to, read for every asked application in one go. The engine still refuses it under its lock.
        OperationHandlers.attach(InstanceOperations.ROLLBACK).source(SUBJECTS)
            .applies(instance -> authored(instance)
                && InstanceKinds.isReleaseManaged(instance.get(InstanceModel.KIND)))
            .availability(Availability.keyed((Row instance) -> instance.get(InstanceModel.ID),
                ReleaseEngine::rollbackRefusals))
            .handle(call -> {
                ReleaseEngine.rollback(instanceId(call), DeployTrigger.MANUAL);
                call.reportStepDone(HohenheimMicrocopy.DEPLOYMENTS.of("rollback_done"));
                return null;
            });
        // AIDEV-NOTE: the panel's deploy, a background run (START answers the API and a schedule in the call). What
        // refuses it at once is asked here, before a run starts, for every asked instance in one go: an attached
        // database that is not ready, or a deploy of it already under way. The funnel asks the admission again in
        // the run, where a refusal ends the run REFUSED in its own words.
        OperationHandlers.attach(InstanceOperations.DEPLOY).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .availability(Availability.keyed((Row instance) -> instance.get(InstanceModel.ID),
                InstanceOperationHandlers::deployRefusals))
            .handle(call -> {
                new InstanceService().deploy(instanceId(call), triggerOf(call.surface()));
                call.reportStepDone(HohenheimMicrocopy.DEPLOYMENTS.of("deploy_done"));
                return null;
            });
        // One ISO per machine through its record: a drive that holds one is emptied (the detach) before another goes in.
        OperationHandlers.attach(InstanceOperations.ATTACH_INSTALL_MEDIA).source(SUBJECTS)
            .applies(Applicability.batch((instances, access, source) -> byInstallMedia(instances,
                (instance, held) -> !held && authored(instance) && InstanceKinds.handlerOf(instance) != null
                    && InstanceKinds.handlerOf(instance).supportsInstallMedia())))
            .inputScope(InstanceOperationHandlers::hostMedia)
            .handle(call -> {
                int instanceId = instanceId(call);
                new InstanceDevices().attachInstallMedia(instanceId,
                    Objects.requireNonNull(call.input(), "the attach has its input").installMedia());
                History.event(Models.get(InstanceModel.class), instanceId,
                    Change.fact(HohenheimActivityAction.DEVICE_ATTACHED));
                return null;
            });
        OperationHandlers.attach(InstanceOperations.DETACH_INSTALL_MEDIA).source(SUBJECTS)
            .applies(Applicability.batch((instances, access, source) -> byInstallMedia(instances,
                (instance, held) -> held && authored(instance))))
            .handle(call -> {
                int instanceId = instanceId(call);
                new InstanceDevices().detachInstallMedia(instanceId);
                History.event(Models.get(InstanceModel.class), instanceId,
                    Change.fact(HohenheimActivityAction.DEVICE_DETACHED));
                return null;
            });
        OperationHandlers.attach(InstanceOperations.INSTALL).source(SUBJECTS)
            .applies(instance -> authored(instance) && instance.get(InstanceModel.TEMPLATE_ID) != null
                && !InstanceModel.INSTALL_NONE.equals(instance.get(InstanceModel.INSTALL_STATE))
                && !InstanceModel.INSTALL_INSTALLED.equals(instance.get(InstanceModel.INSTALL_STATE)))
            .handle(call -> {
                new InstanceInstalls().install(instanceId(call));
                return null;
            });
        OperationHandlers.attach(InstanceOperations.REINSTALL).source(SUBJECTS)
            .applies(instance -> authored(instance) && instance.get(InstanceModel.TEMPLATE_ID) != null
                && (InstanceModel.INSTALL_INSTALLED.equals(instance.get(InstanceModel.INSTALL_STATE))
                    || InstanceModel.INSTALL_FAILED.equals(instance.get(InstanceModel.INSTALL_STATE))))
            .handle(call -> {
                new InstanceInstalls().reinstall(instanceId(call));
                return null;
            });
        OperationHandlers.attach(InstanceOperations.CAPTURE_TEMPLATE).source(SUBJECTS)
            .applies(instance -> authored(instance)
                && InstanceModel.STATUS_STOPPED.equals(instance.get(InstanceModel.STATUS))
                && capturable(instance))
            .handle(call -> new InstanceTemplateCapture().capture(instanceId(call)));
        // Dead, with the reason on screen, for an app containers take their image from (new 2026-11-23: this
        // operation had no availability before); the service refuses a direct invoke in the same words.
        OperationHandlers.attach(InstanceOperations.DESTROY_WITH_DATA).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .availability(Availability.batch((instances, access) -> DeleteImpact.applicationsFollowed(instances)))
            .handle(call -> {
                new InstanceService().destroyWithData(instanceId(call));
                return null;
            });
        // The teardown funnel owns the accountability (the release engine, preview expiry and database teardown
        // record the same verb); generated rows are destroyed by their owning tier, never from an instance list. This
        // is the operator's own Delete, the one REQUESTED destroy: it retries an unfinished one past its budget. Its
        // dead reason is the permission's first, then the containers that take their image from an app.
        OperationHandlers.attach(InstanceOperations.DELETE).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .availability(Availability.<Row>batch(InstanceOperationHandlers::deleteRefusals))
            .handle(call -> {
                new InstanceService().destroy(instanceId(call), UnfinishedDestroys.Policy.REQUESTED);
                return 1;
            });
        OperationHandlers.attach(InstanceOperations.MIGRATE).source(SUBJECTS)
            .handle(InstanceOperationHandlers::migrate);
        OperationHandlers.attach(InstanceOperations.EXEC).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::exec);
        OperationHandlers.attach(InstanceOperations.APP_UPDATE).source(SUBJECTS)
                .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::appUpdate);
        // The app's own reads and writes, each through the service the API route reaches, which asks its capability
        // again (console for the log, config for a variable write).
        OperationHandlers.attach(InstanceLogOperations.READ_LOG).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .handle(call -> InstanceConsoles.tail(instanceId(call),
                InstanceConsoles.tailLines(call.input() == null ? null : call.input().lines())));
        OperationHandlers.attach(InstanceVariableOperations.LIST_VARIABLES).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .handle(call -> new InstanceVariableOperations.Variables(InstanceVariables.listed(
                Models.get(InstanceVariableModel.class).findByInstanceId(instanceId(call)))));
        OperationHandlers.attach(InstanceVariableOperations.SET_VARIABLE).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::setVariable);
        OperationHandlers.attach(InstanceVariableOperations.UNSET_VARIABLE).source(SUBJECTS)
            .applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::unsetVariable);
        // Sessions, offered and never invoked: their sockets ask offered() (see InstanceOperations.OPEN_SHELL).
        OperationHandlers.attach(InstanceOperations.OPEN_SHELL).applies(InstanceOperationHandlers::authored)
            .handle(InstanceOperationHandlers::session);
        OperationHandlers.attach(InstanceOperations.OPEN_FRAMEBUFFER)
            .applies(instance -> authored(instance)
                && VmKind.ID.toString().equals(instance.get(InstanceModel.KIND)))
            .handle(InstanceOperationHandlers::session);
        SchedulePlacements.place(InstanceOperations.START);
        SchedulePlacements.place(InstanceOperations.STOP);
        SchedulePlacements.place(InstanceOperations.RESTART);
        SchedulePlacements.place(InstanceOperations.BACKUP).onFailure(InstanceOperationHandlers::alertBackupFailed);
        SchedulePlacements.place(InstanceOperations.SNAPSHOT);
        SchedulePlacements.place(InstanceOperations.CONSOLE_COMMAND);
        SchedulePlacements.place(InstanceOperations.APP_UPDATE);
    }

    private InstanceOperationHandlers() {
    }

    /** Loads the class, attaching the handlers and placements; idempotent. */
    public static void init() {
        // The static initializer did the work.
    }

    /**
     * @return the trigger a deploy asked from {@code surface} runs under
     * @throws IllegalStateException for a surface that names no trigger
     */
    public static @NonNull DeployTrigger triggerOf(@NonNull PlacementSurface surface) {
        DeployTrigger trigger = TRIGGERS.get(surface.id());
        if (trigger == null) {
            throw new IllegalStateException("No deploy trigger is declared for the surface " + surface.id()
                + "; it decides whether a stopped workload may start, so it is never guessed");
        }
        return trigger;
    }

    /**
     * Whether {@code operation} is offered to the caller on the instance: its gate, its applicability and the caller's
     * authorization, the checks the operation's own invocation makes. A tab and the socket behind it both ask here, so
     * the tab shows exactly to whom the socket admits.
     */
    public static boolean offered(@NonNull Operation<Row, ?, ?> operation, @NonNull AccessContext access,
                                  @Nullable Row instance) {
        return instance != null
            && !(OperationPipeline.offer(operation, access, instance) instanceof OperationPipeline.Offer.Hidden);
    }

    /** {@link #offered(Operation, AccessContext, Row)} for a socket: its principal on the instance its route names. */
    public static boolean offered(@NonNull Operation<Row, ?, ?> operation, @Nullable Principal principal,
                                  @Nullable Integer instanceId) {
        return principal != null && instanceId != null && offered(operation, AccessContext.detached(principal),
            Models.get(InstanceModel.class).findById(instanceId));
    }

    /**
     * The handler of a session operation.
     *
     * @throws IllegalStateException always: a session opens through its socket, and no surface places it
     */
    private static <R> R session(@NonNull OperationCall<Row, Void> call) {
        throw new IllegalStateException("A session operation was invoked from " + call.surface().id()
            + ": its socket admits through offered(), and no surface places it");
    }

    private static boolean capturable(@NonNull Row instance) {
        InstanceKindHandler handler = InstanceKinds.handlerOf(instance);
        return handler != null && handler.supportsTemplateCapture();
    }

    /**
     * Whether an operation applies to this instance at all: a generated instance (a product tier's lowered runtime) is
     * managed only through its owning record's surface, on every surface (the API never lists it either).
     */
    public static boolean authored(@NonNull Row instance) {
        return instance.get(InstanceModel.GENERATED_BY) == null;
    }

    /** @return per asked instance that cannot deploy right now, why: a database not ready, else a deploy under way */
    private static @NonNull Map<Integer, Microcopy> deployRefusals(@NonNull Set<Integer> instanceIds) {
        Map<Integer, Microcopy> reasons = new HashMap<>(InstanceDatabaseLinks.notReadyReasons(instanceIds));
        for (Integer deploying : ApplicationReleases.deployingAmong(instanceIds)) {
            reasons.putIfAbsent(deploying, HohenheimMicrocopy.DEPLOYMENTS.of("deploy_in_progress"));
        }
        return reasons;
    }

    /**
     * The Delete's dead reasons over a page of instances: the destroy permission's first (HohenheimAccess), then the
     * containers that take their image from an app, read once for the page (DeleteImpact.applicationsFollowed).
     */
    private static @NonNull List<@Nullable Microcopy> deleteRefusals(@NonNull List<? extends Row> instances,
                                                                     @NonNull AccessContext access) {
        List<Microcopy> followed = DeleteImpact.applicationsFollowed(instances);
        List<Microcopy> reasons = new ArrayList<>(instances.size());
        for (int at = 0; at < instances.size(); at++) {
            Microcopy permission = HohenheimAccess.destroyUnavailableReason(access,
                instances.get(at).get(InstanceModel.ID));
            reasons.add(permission != null ? permission : followed.get(at));
        }
        return reasons;
    }

    /**
     * An install-media verb's verdicts over a page of instances, their drives read ONCE for the page: {@code applies}
     * decides each instance from whether a drive of it holds a medium.
     */
    private static @NonNull List<Boolean> byInstallMedia(@NonNull List<? extends Row> instances,
                                                         @NonNull BiPredicate<Row, Boolean> applies) {
        Set<Integer> holding = InstanceDevices.withInstallMedia(instances.stream()
            .map(instance -> instance.get(InstanceModel.ID)).filter(Objects::nonNull).toList());
        return instances.stream()
            .map(instance -> applies.test(instance, holding.contains(instance.get(InstanceModel.ID)))).toList();
    }

    /**
     * @throws DomainRefusal {@link HohenheimRefusalReason#DATABASE_NOT_READY}, retriable, while an attached database
     *                       is not active
     */
    private static @NonNull PowerResult start(@NonNull OperationCall<Row, Void> call) {
        int instanceId = instanceId(call);
        Microcopy notReady = InstanceDatabaseLinks.notReadyReason(instanceId);
        if (notReady != null) {
            throw new DomainRefusal(HohenheimRefusalReason.DATABASE_NOT_READY,
                "instance " + instanceId + " uses a database that is not active", notReady);
        }
        DeployTrigger trigger = triggerOf(call.surface());
        new InstanceService().deploy(instanceId, trigger);
        return new PowerResult(statusOf(instanceId), trigger.word(), false);
    }

    /** A stopped instance's stop runs nothing and answers success: stop is idempotent on every surface. */
    private static @NonNull PowerResult stop(@NonNull OperationCall<Row, Void> call) {
        int instanceId = instanceId(call);
        if (InstanceModel.STATUS_STOPPED.equals(statusOf(instanceId))) {
            return new PowerResult(InstanceModel.STATUS_STOPPED, null, true);
        }
        new InstanceService().stop(instanceId);
        return new PowerResult(statusOf(instanceId), null, false);
    }

    private static @NonNull PowerResult restart(@NonNull OperationCall<Row, Void> call) {
        int instanceId = instanceId(call);
        DeployTrigger trigger = triggerOf(call.surface());
        new InstanceService().restart(instanceId, trigger);
        return new PowerResult(statusOf(instanceId), trigger.word(), false);
    }

    private static @NonNull Integer backup(@NonNull OperationCall<Row, Void> call) {
        return new InstanceBackups().backupNow(instanceId(call));
    }

    private static @NonNull Integer snapshot(@NonNull OperationCall<Row, SnapshotInput> call) {
        SnapshotInput input = call.input();
        String note = input == null || input.note() == null || input.note().isBlank() ? null : input.note();
        if (note == null && call.surface() == ZenitPlacementSurface.SCHEDULE_STEP) {
            note = SCHEDULED_NOTE;
        }
        return new InstanceSnapshots().create(instanceId(call), note);
    }

    /**
     * Sends the line through {@link InstanceConsoles}, which asks the console capability on its own funnel, and
     * records it on the instance from every surface.
     *
     * @throws IllegalStateException for a blank line: a schedule step that stores none fails rather than sending
     *                               nothing
     */
    /**
     * Moves the workload; every refusal (an ineligible host, a device row, a held publication, an occupied
     * destination, an unreachable daemon) arrives as Violations, never a success.
     */
    private static @NonNull Integer migrate(@NonNull OperationCall<Row, MigrateInput> call) {
        MigrateInput input = call.input();
        Integer target = input == null ? null : input.targetServerId();
        if (target == null) {
            throw Violations.ofField(InstanceOperations.TARGET_SERVER.getName(), null,
                HohenheimMicrocopy.VIOLATIONS.of("migrate_target_required"));
        }
        new InstanceMigrations().migrateTo(instanceId(call), target);
        return target;
    }

    /** Runs the program through InstanceExec, which asks the exec capability once more on its funnel. */
    private static @NonNull ExecRun exec(@NonNull OperationCall<Row, ExecInput> call) {
        ExecInput input = call.input();
        String command = input == null || input.command() == null ? "" : input.command().strip();
        InstanceExec.Run run = new InstanceExec().run(instanceId(call), command);
        return new ExecRun(run.exitCode(), run.output());
    }

    private static @NonNull String consoleCommand(@NonNull OperationCall<Row, ConsoleCommandInput> call) {
        ConsoleCommandInput input = call.input();
        String command = input == null ? null : input.command();
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("no console command configured on this step");
        }
        int instanceId = instanceId(call);
        InstanceConsoles.sendCommand(instanceId, command);
        History.event(Models.get(InstanceModel.class), instanceId, Change.fact(HohenheimActivityAction.CONSOLE_COMMAND,
            Map.of(HohenheimActivityAction.COMMAND_KEY, command)));
        return "sent";
    }

    /** @return the update script's bounded output, shown to the operator */
    private static @NonNull String appUpdate(@NonNull OperationCall<Row, Void> call) {
        return new InstanceAppUpdates().update(instanceId(call));
    }

    /**
     * Sets one plain variable and records it on the app, as the API's set does.
     *
     * @throws Violations {@code variable_key_required}, {@code variable_holds_secret}
     */
    private static @NonNull String setVariable(
            @NonNull OperationCall<Row, InstanceVariableOperations.SetInput> call) {
        InstanceVariableOperations.SetInput input = Objects.requireNonNull(call.input(), "the set has its input");
        String key = Objects.requireNonNullElse(input.key(), "").strip();
        int instanceId = instanceId(call);
        new InstanceVariables().setPlainValue(instanceId, key, Objects.requireNonNullElse(input.plain_value(), ""));
        InstanceVariables.recordSet(instanceId, key);
        return key;
    }

    /**
     * Removes one variable and records it on the app, as the API's delete does.
     *
     * @throws Violations {@code variable_not_found} for a key the app holds no variable under
     */
    private static @NonNull String unsetVariable(
            @NonNull OperationCall<Row, InstanceVariableOperations.UnsetInput> call) {
        String key = Objects.requireNonNullElse(Objects.requireNonNull(call.input(), "the unset has its input").key(),
            "").strip();
        int instanceId = instanceId(call);
        new InstanceVariables().unset(instanceId, key);
        return key;
    }

    /**
     * The scheduled backup's operator alert; the failure stands either way. An outcome nobody knows (a step reaped
     * after a crash) alerts with its own words.
     */
    private static void alertBackupFailed(@NonNull StepFailure failure) {
        String key = failure.subjectKeys().isEmpty() ? null : failure.subjectKeys().get(0);
        Row instance = null;
        try {
            instance = key == null ? null : Models.get(InstanceModel.class).findById(Integer.parseInt(key));
        } catch (NumberFormatException notAnId) {
            // The name below falls back to the key.
        }
        String name = instance != null ? instance.get(InstanceModel.NAME) : "#" + key;
        Microcopy subject = HohenheimMicrocopy.ALERT.of("instance_backup_failed_subject")
            .withArg("name", name);
        Microcopy body = failure.outcomeUnknown()
            ? HohenheimMicrocopy.ALERT.of("instance_backup_unknown_body").withArg("name", name)
            : HohenheimMicrocopy.ALERT.of("instance_backup_failed_body").withArg("name", name)
                .withArg("reason", reasonOf(failure.cause()));
        Alerts.trySend(NotificationEvents.BACKUP_FAILED, Alerts.about(InstanceModel.MODEL_ID, key == null ? "-" : key),
            subject, body);
    }

    /** @return what failed, in words a person reads: a refusal's shown words, else the failure's message */
    private static @NonNull Microcopy reasonOf(@Nullable RuntimeException cause) {
        if (cause instanceof DomainRefusal refusal) {
            return refusal.shown();
        }
        if (cause == null) {
            return Microcopy.literal("");
        }
        return Microcopy.literal(HohenheimViolations.reasonOf(cause));
    }

    /**
     * The install-media attach's pick: the ISO volumes on the record's own host, a choice it must make (no blank);
     * none there says where media comes from.
     */
    private static @NonNull InputScope hostMedia(@Nullable Row instance, @NonNull FormSpec declared,
                                                 @Nullable AccessContext access) {
        Integer serverId = instance == null ? null : instance.get(InstanceModel.SERVER_ID);
        return InputScope.of(InstanceTemplateHandlers.replacing(declared, Map.of(
            InstanceOperations.DRIVE_MEDIA.getName(), Select.of(InstanceOperations.DRIVE_MEDIA)
                .options(OptionSource.of(new InstallMedia().optionsOn(serverId)))
                .clearable(false)
                .emptyReason(context -> InstallMedia.noneReason(serverId))
                .build())));
    }

    private static int instanceId(@NonNull OperationCall<Row, ?> call) {
        Integer id = call.subject().get(InstanceModel.ID);
        return id;
    }

    private static @NonNull String statusOf(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        return row == null ? "" : String.valueOf((Object) row.get(InstanceModel.STATUS));
    }
}
