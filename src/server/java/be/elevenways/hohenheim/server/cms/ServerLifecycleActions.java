package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
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

import static be.elevenways.hohenheim.server.cms.ServerWords.hostCopy;
import static be.elevenways.hohenheim.server.cms.ServerWords.serverCopy;

/**
 * The host lifecycle operations and their one placement: acknowledgement, probes, admission and fleet decisions.
 *
 * AIDEV-NOTE: originally split out of ServerResource beside ServerTrustActions (review, 2026-09). ServerParts now
 * owns form/list wiring; the legacy RowAction handlers were replaced and deleted, not retained as a second lane.
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
        return List.of(
            place("acknowledge_posture", serverCopy("acknowledge"), row -> {
                HostPostureAcknowledgement.record(row);
                return serverCopy("posture_acknowledged").withArg("name", row.get(ServerModel.NAME));
            }, row -> ServerModel.postureNeedsAcknowledgement(row) && !ServerModel.postureAcknowledged(row))
                .description(serverCopy("acknowledge_hint")).icon(Icon.of("triangle-exclamation"))
                .style(ActionStyle.DESTRUCTIVE)
                .confirmation(ConfirmationSpec.builder().title(serverCopy("acknowledge"))
                    .body(serverCopy("acknowledge_generic")).style(ActionStyle.DESTRUCTIVE).build())
                .dynamicConfirmation(row -> ConfirmationSpec.builder().title(serverCopy("acknowledge"))
                    .body(serverCopy("acknowledge_body").withArg("name", row.get(ServerModel.NAME)))
                    .style(ActionStyle.DESTRUCTIVE).requireTypedConfirmation(row.get(ServerModel.NAME)).build()).build(),
            place("probe_server", serverCopy("probe_now"), row -> {
                String name = row.get(ServerModel.NAME);
                String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
                ServerService.Summary summary = new ServerService().probeAndStore(name);
                if (summary == null || !summary.reachable()) throw Violations.ofForm(CmsSupport.violationText("host_probe_failed")
                    .withArg("name", name).withArg("kind", summary != null && summary.errorKind() != null
                        ? summary.errorKind() : "unknown"));
                return serverCopy("host_probe_ok").withArg("name", name).withArg("summary", formatSummary(summary, label));
            }, row -> true).description(serverCopy("probe_now_hint")).icon(Icon.of("heart-pulse")).inlineInRow(false).build(),
            place("preflight_server", serverCopy("preflight"), row -> {
                HostPreflight.Report[] report = new HostPreflight.Report[1];
                ActivityLog.withAction(ZenitActivityAction.UPDATE, "preflight",
                    () -> report[0] = HostPreflight.runAndStore(row.get(ServerModel.NAME)));
                return serverCopy(report[0].passed() ? "preflight_passed" : "preflight_failed")
                    .withArg("name", row.get(ServerModel.NAME));
            }, row -> true).icon(Icon.of("stethoscope")).inlineInRow(false).build(),
            place("admit_server", serverCopy("admit"), row -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "admit");
                return serverCopy("host_admitted").withArg("name", row.get(ServerModel.NAME));
            }, row -> !ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("circle-check")).build(),
            place("cordon_server", serverCopy("cordon"), row -> {
                setAdmission(row, ServerModel.ADMISSION_CORDONED, "cordon");
                return serverCopy("host_cordoned").withArg("name", row.get(ServerModel.NAME));
            }, row -> ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("circle-pause")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(ConfirmationSpec.builder().title(serverCopy("cordon")).body(serverCopy("cordon_confirm"))
                    .confirmLabel(serverCopy("cordon")).style(ActionStyle.DESTRUCTIVE).build()).build(),
            place("drain_server", serverCopy("drain"), row -> {
                // AIDEV-NOTE: fenced updateAll writes fire no hook; InstanceMigrations records the moves explicitly.
                InstanceMigrations.DrainReport report = new InstanceMigrations().drain(row.get(ServerModel.ID));
                if (report.complete()) return serverCopy("host_drained").withArg("name", row.get(ServerModel.NAME))
                    .withArg("moved", report.moved().size());
                return serverCopy("host_drain_partial").withArg("name", row.get(ServerModel.NAME))
                    .withArg("moved", report.moved().size()).withArg("refused", report.refused().size())
                    .withArg("held", report.refused().stream().map(InstanceMigrations.DrainEntry::name)
                        .collect(Collectors.joining(", ")));
            }, row -> ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("truck-arrow-right")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(ConfirmationSpec.builder().title(serverCopy("drain")).body(serverCopy("drain_confirm"))
                    .style(ActionStyle.DESTRUCTIVE).build()).build(),
            place("uncordon_server", serverCopy("uncordon"), row -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "uncordon");
                return serverCopy("host_admitted").withArg("name", row.get(ServerModel.NAME));
            }, row -> ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
                .icon(Icon.of("circle-play")).build(),
            place("reap_controller_objects", serverCopy("reap_controller_objects"), row -> {
                IncusReaper.Reaped[] reaped = new IncusReaper.Reaped[1];
                ActivityLog.withAction(ZenitActivityAction.DELETE, "reap_controller_objects",
                    () -> reaped[0] = ReapIncusControllers.reapIncludingUnstamped(row));
                return serverCopy("controller_objects_reaped").withArg("name", row.get(ServerModel.NAME))
                    .withArg("removed", reaped[0].removed().size()).withArg("refused", reaped[0].refused().size());
            }, ServerModel::isIncus).icon(Icon.of("broom")).style(ActionStyle.DESTRUCTIVE)
                .confirmation(ConfirmationSpec.builder().title(serverCopy("reap_controller_objects"))
                    .body(serverCopy("reap_controller_objects_confirm")).style(ActionStyle.DESTRUCTIVE).build()).build());
    }

    static PanelAction.OperationBuilder<Row, Microcopy> place(String id, Microcopy label,
            Function<Row, Microcopy> handler, Predicate<Row> applies) {
        Operation<Row, Void, Microcopy> operation = Operation.declare(HohenheimIds.id(id)).label(label)
            .one(SubjectType.record(ServerModel.MODEL_ID)).gate(OperationGate.permission(HohenheimPanel.ACCESS))
            .result(Microcopy.class).register();
        OperationHandlers.attach(operation).applies(applies::test).handle(call -> handler.apply(call.subject()));
        return PanelAction.<Row, Microcopy>places(operation, ActionPlacement.ROW,
            (request, result) -> CmsActionResult.refreshWithToast(result.value()));
    }

    private static void setAdmission(Row row, String admission, String action) {
        ActivityLog.withAction(ZenitActivityAction.UPDATE, action, () -> {
            row.set(ServerModel.ADMISSION, admission);
            Models.get(ServerModel.class).save(row);
        });
    }

    private static String formatSummary(ServerService.Summary summary, String label) {
        String docker = summary.daemonVersion().isBlank() ? label : label + " " + summary.daemonVersion();
        String platform = summary.osType();
        if (!summary.architecture().isBlank()) platform = platform.isBlank() ? summary.architecture() : platform + "/" + summary.architecture();
        String os = summary.operatingSystem().isBlank() ? platform : summary.operatingSystem();
        if (!platform.isBlank() && !os.equals(platform)) os += " (" + platform + ")";
        if (os.isBlank()) os = hostCopy(serverCopy("host_unknown_platform"));
        double memory = Math.round(summary.memoryBytes() / 1_073_741_824.0 * 10) / 10.0;
        return hostCopy(serverCopy("host_summary").withArg("docker", docker).withArg("os", os)
            .withArg("cpus", String.valueOf(summary.cpus())).withArg("memory", String.valueOf(memory))
            .withArg("running", String.valueOf(summary.containersRunning()))
            .withArg("total", String.valueOf(summary.containersTotal())).withArg("images", String.valueOf(summary.images())));
    }
}
