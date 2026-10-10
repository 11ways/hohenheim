package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.activity.OperationSentences;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.host.HostProbe;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationHandlers;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/**
 * The host lifecycle operations and their one placement: acknowledgement, probes, admission and fleet decisions.
 *
 * AIDEV-NOTE: originally split out of ServerResource beside ServerTrustActions. ServerParts now
 * owns form/list wiring; the former row action handlers were replaced and deleted, not retained as a second lane.
 * A typed confirmation is a mis-click guard, never evidence of acknowledgement: HostPostureAcknowledgement records
 * the real actor. Changing posture clears acceptance through the model hook; there is no parallel revoke control.
 *
 * AIDEV-NOTE: the deliberate reaper may remove unstamped objects; the scheduled sweep requires an expired presence
 * stamp. Both still ask IncusReaper's used_by refusal at removal time; judgment never overrides a live reference.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class ServerLifecycleActions {
    private static final List<PanelAction<Row>> PLACED = declarePlaced();
    private ServerLifecycleActions() {}
    static @NonNull List<PanelAction<Row>> placed() { return PLACED; }

    private static List<PanelAction<Row>> declarePlaced() {
        ConfirmationSpec acknowledge = Confirmations.of(HohenheimMicrocopy.SERVER.of("acknowledge"),
            HohenheimMicrocopy.SERVER.of("acknowledge_generic"), ActionStyle.DESTRUCTIVE);
        return List.of(
            place("acknowledge_posture", HohenheimMicrocopy.SERVER.of("acknowledge"), row -> {
                HostPostureAcknowledgement.record(row);
                return HohenheimMicrocopy.SERVER.of("posture_acknowledged").withArg("name", row.get(ServerModel.NAME));
            }, row -> ServerModel.postureNeedsAcknowledgement(row) && !ServerModel.postureAcknowledged(row))
                .description(HohenheimMicrocopy.SERVER.of("acknowledge_hint")).icon(Icon.of("triangle-exclamation"))
                .style(ActionStyle.DESTRUCTIVE)
                .confirmation(acknowledge)
                .dynamicConfirmation(row -> Confirmations.typed(acknowledge.withBody(HohenheimMicrocopy.SERVER
                    .of("acknowledge_body").withArg("name", row.get(ServerModel.NAME))), row.get(ServerModel.NAME)))
                .build(),
            place("probe_server", HohenheimMicrocopy.SERVER.of("probe_now"), row -> {
                String name = row.get(ServerModel.NAME);
                String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
                ServerService.Summary summary = new ServerService().probeAndStore(name);
                if (summary == null || !summary.reachable()) throw Violations.ofForm(
                    HohenheimMicrocopy.VIOLATIONS.of("host_probe_failed").withArg("name", name)
                    .withArg("kind", summary != null && summary.errorKind() != null
                        ? HostProbe.FailureKind.labelOf(summary.errorKind()) : HostProbe.FailureKind.UNREACHABLE.label()));
                return HohenheimMicrocopy.SERVER.of("host_probe_ok").withArg("name", name)
                    .withArg("summary", formatSummary(summary, label));
            // Check again is the host page's one verb: it measures everything a probe does and the
            // hourly sweep keeps the contact and the memory reading fresh, so the bare probe waits in the More menu.
            }, row -> true).description(HohenheimMicrocopy.SERVER.of("probe_now_hint")).icon(Icon.of("heart-pulse"))
                .inlineInRow(false)
                .inlineOnRecord(false).build(),
            place("check_host", HohenheimMicrocopy.SERVER.of("check_and_admit"), ServerLifecycleActions::checkHost,
                row -> true)
                .dynamicLabel(row -> HohenheimMicrocopy.SERVER.of(awaitsAdmission(row) ? "check_and_admit"
                    : "check_again"))
                .description(HohenheimMicrocopy.SERVER.of("check_and_admit_hint"))
                // The first inline verb leads by position, not by a filled style (InstanceActions' deploy note).
                .icon(Icon.of("stethoscope")).build(),
            place("cordon_server", HohenheimMicrocopy.SERVER.of("cordon"), row -> {
                setAdmission(row, ServerModel.ADMISSION_CORDONED, "cordon");
                return HohenheimMicrocopy.SERVER.of("host_cordoned").withArg("name", row.get(ServerModel.NAME));
            }, row -> ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("circle-pause")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(Confirmations.of(HohenheimMicrocopy.SERVER.of("cordon"),
                    HohenheimMicrocopy.SERVER.of("cordon_confirm"), ActionStyle.DESTRUCTIVE)).build(),
            place("drain_server", HohenheimMicrocopy.SERVER.of("drain"), row -> {
                // AIDEV-NOTE: fenced updateAll writes fire no hook; InstanceMigrations records the moves explicitly.
                InstanceMigrations.DrainReport report = new InstanceMigrations().drain(row.get(ServerModel.ID));
                if (report.complete()) return HohenheimMicrocopy.SERVER.of("host_drained")
                    .withArg("name", row.get(ServerModel.NAME))
                    .withArg("moved", report.moved().size());
                return HohenheimMicrocopy.SERVER.of("host_drain_partial").withArg("name", row.get(ServerModel.NAME))
                    .withArg("moved", report.moved().size()).withArg("refused", report.refused().size())
                    .withArg("held", report.refused().stream().map(InstanceMigrations.DrainEntry::name)
                        .collect(Collectors.joining(", ")));
            }, row -> ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("truck-arrow-right")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(Confirmations.of(HohenheimMicrocopy.SERVER.of("drain"),
                    HohenheimMicrocopy.SERVER.of("drain_confirm"), ActionStyle.DESTRUCTIVE)).build(),
            place("uncordon_server", HohenheimMicrocopy.SERVER.of("uncordon"), row -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "uncordon");
                return HohenheimMicrocopy.SERVER.of("host_admitted").withArg("name", row.get(ServerModel.NAME));
            }, row -> ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("circle-play")).build(),
            place("reap_controller_objects", HohenheimMicrocopy.SERVER.of("reap_controller_objects"), row -> {
                IncusReaper.Reaped[] reaped = new IncusReaper.Reaped[1];
                ActivityLog.withAction(ZenitActivityAction.DELETE, "reap_controller_objects",
                    () -> reaped[0] = ReapIncusControllers.reapIncludingUnstamped(row));
                return HohenheimMicrocopy.SERVER.of("controller_objects_reaped")
                    .withArg("name", row.get(ServerModel.NAME))
                    .withArg("removed", reaped[0].removed().size()).withArg("refused", reaped[0].refused().size());
            }, ServerModel::isIncus).icon(Icon.of("broom")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(Confirmations.of(HohenheimMicrocopy.SERVER.of("reap_controller_objects"),
                    HohenheimMicrocopy.SERVER.of("reap_controller_objects_confirm"), ActionStyle.DESTRUCTIVE)).build());
    }

    static PanelAction.OperationBuilder<Row, Microcopy> place(String id, Microcopy label,
            Function<Row, Microcopy> handler, Predicate<Row> applies) {
        Operation<Row, Void, Microcopy> operation = Operation.declare(HohenheimIds.id(id))
            .happened(OperationSentences.of(id)).label(label)
            .one(SubjectType.record(ServerModel.MODEL_ID)).gate(OperationGate.permission(HohenheimPanel.ACCESS))
            .command(CmsCommands.EXTERNAL)
            .result(Microcopy.class).register();
        OperationHandlers.attach(operation).applies(applies::test).handle(call -> handler.apply(call.subject()));
        return PanelAction.<Row, Microcopy>places(operation, ActionPlacement.ROW,
            (request, result) -> CmsActionResult.refreshWithToast(result.value()));
    }

    /**
     * Preflight, then, on a host waiting for admission whose required checks all pass, admission: one operator
     * verb where there used to be two buttons offered in the wrong order. An admitted or cordoned host is only
     * re-checked; uncordoning stays its own decision.
     *
     * AIDEV-NOTE: the command runs outside a transaction (CmsCommands.EXTERNAL), so the refusal below keeps the
     * report preflight stored: the host page's Must pass section shows exactly what failed.
     *
     * @throws Violations {@code host_check_failed} naming the failed required checks, or admission's own refusal
     */
    private static Microcopy checkHost(Row row) {
        String name = row.get(ServerModel.NAME);
        HostPreflight.Report[] report = new HostPreflight.Report[1];
        ActivityLog.withAction(ZenitActivityAction.UPDATE, "preflight",
            () -> report[0] = HostPreflight.runAndStore(name));
        List<Microcopy> failed = report[0].checks().stream()
            .filter(check -> check.required() && check.failed())
            .map(check -> HostPreflight.checkLabel(check.name()))
            .toList();
        if (!awaitsAdmission(row)) {
            return failed.isEmpty() ? HohenheimMicrocopy.SERVER.of("host_checked").withArg("name", name)
                : HohenheimMicrocopy.SERVER.of("host_checked_failing").withArg("name", name).withArg("checks", failed);
        }
        if (!failed.isEmpty() || !report[0].passed()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("host_check_failed").withArg("name", name)
                .withArg("checks", failed.isEmpty() ? (Object) "-" : failed));
        }
        // Admission reads what preflight just stored, never the subject loaded before it ran.
        Row stored = Models.get(ServerModel.class).findById(row.get(ServerModel.ID));
        HostAdmission.requireAdmittable(stored);
        setAdmission(stored, ServerModel.ADMISSION_ADMITTED, "admit");
        return HohenheimMicrocopy.SERVER.of("host_checked_admitted").withArg("name", name);
    }

    /** @return whether the host is neither admitted nor cordoned: checking it may admit it */
    private static boolean awaitsAdmission(Row row) {
        return !ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION))
            && !ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION));
    }

    private static void setAdmission(Row row, String admission, String action) {
        ActivityLog.withAction(ZenitActivityAction.UPDATE, action, () -> {
            row.set(ServerModel.ADMISSION, admission);
            Models.get(ServerModel.class).save(row);
        });
    }

    private static Microcopy formatSummary(ServerService.Summary summary, String label) {
        String docker = summary.daemonVersion().isBlank() ? label : label + " " + summary.daemonVersion();
        String platform = summary.osType();
        if (!summary.architecture().isBlank()) platform = platform.isBlank() ? summary.architecture() : platform + "/" + summary.architecture();
        String os = summary.operatingSystem().isBlank() ? platform : summary.operatingSystem();
        if (!platform.isBlank() && !os.equals(platform)) os += " (" + platform + ")";
        double memory = Math.round(summary.memoryBytes() / 1_073_741_824.0 * 10) / 10.0;
        return HohenheimMicrocopy.SERVER.of("host_summary").withArg("docker", docker)
            .withArg("os", os.isBlank() ? HohenheimMicrocopy.SERVER.of("host_unknown_platform") : os)
            .withArg("cpus", String.valueOf(summary.cpus())).withArg("memory", String.valueOf(memory))
            .withArg("running", String.valueOf(summary.containersRunning()))
            .withArg("total", String.valueOf(summary.containersTotal()))
            .withArg("images", String.valueOf(summary.images()));
    }
}
