package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.HostPostureAcknowledgement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.incus.IncusReaper;
import be.elevenways.hohenheim.server.instance.InstanceMigrations;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.stream.Collectors;

import static be.elevenways.hohenheim.server.cms.ServerResource.hostCopy;
import static be.elevenways.hohenheim.server.cms.ServerResource.serverCopy;

/**
 * The host lifecycle row actions of the admin surface: posture acknowledgement, probe, preflight,
 * admission, cordon, drain and the shared-object reaper.
 *
 * AIDEV-NOTE: split out of ServerResource (review finding, 2026-09) beside
 * {@link ServerTrustActions}; the resource keeps its form, list and write path.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class ServerLifecycleActions {

    private ServerLifecycleActions() {
    }

    /** Every lifecycle action, in the order the row menu offers them. */
    static @NonNull List<RowAction<Row>> actions() {
        return List.of(
            acknowledgePostureAction(),
            probeAction(),
            preflightAction(),
            admitAction(),
            cordonAction(),
            drainAction(),
            uncordonAction(),
            reapControllerObjectsAction());
    }

    /**
     * THE deliberate half of the shared-object reaper: remove what other controllers left
     * on this Incus daemon, INCLUDING the objects whose controller never left a presence
     * stamp -- which the scheduled sweep refuses on principle, because absent evidence is
     * not evidence of absence.
     *
     * AIDEV-NOTE: this is the {@code OrphanActions} bargain, not a shortcut around the
     * sweep's caution. The sweep may only act on proof (a stamp that expired); an operator
     * looking at one named host may also act on judgement. What neither can do is take an
     * object something references -- {@code IncusReaper.reap} re-reads {@code used_by} at
     * removal time and the daemon refuses it a second time on its own.
     */
    private static @NonNull RowAction<Row> reapControllerObjectsAction() {
        return RowAction.Invoke.<Row>builder(
                Identifier.of("hohenheim", "reap_controller_objects"))
            .label(serverCopy("reap_controller_objects"))
            .icon(Icon.of("broom"))
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy("reap_controller_objects"))
                .body(serverCopy("reap_controller_objects_confirm"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .visibleFor((row, ctx) -> ServerModel.isIncus(row))
            .handler((row, ctx) -> {
                IncusReaper.Reaped[] reaped = new IncusReaper.Reaped[1];
                ActivityLog.withAction(ActivityLog.ACTION_DELETE, "reap_controller_objects",
                    () -> reaped[0] = ReapIncusControllers.reapIncludingUnstamped(row));
                return CmsActionResult.refreshWithToast(serverCopy("controller_objects_reaped")
                    .withArg("name", row.get(ServerModel.NAME))
                    .withArg("removed", reaped[0].removed().size())
                    .withArg("refused", reaped[0].refused().size()));
            })
            .build();
    }

    /**
     * The operator accepts THIS host's shared-container risk, by name and on the record.
     * The posture dropdown declares the intent; this act is what makes the host start
     * taking hostile-tenant containers, and it is the only thing that writes the five
     * acknowledgement columns.
     *
     * AIDEV-NOTE: deliberately NOT a form entry beside the posture select. A second
     * control over the same decision is a second authority, and a warning rendered as
     * field help next to a dropdown is exactly the "boolean hidden in settings" shape the
     * plan's clause refuses. There is also no REVOKE action: changing the posture clears
     * the acknowledgement through the schema hook, so a revoke would be a second path to
     * one state.
     *
     * AIDEV-NOTE: {@code requireTypedConfirmation} is a CLIENT-SIDE mis-click guard and
     * NOTHING else -- the phrase is compared in the dialog template and is never
     * submitted, so a direct POST bypasses it entirely. It is not evidence, it is not the
     * acknowledgement, and it must never be read as either. What actually records the act
     * is {@link HostPostureAcknowledgement#record}, which demands a real actor; what gates
     * the surface is the panel permission plus CSRF.
     */
    private static @NonNull RowAction<Row> acknowledgePostureAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "acknowledge_posture"))
            .label(serverCopy("acknowledge"))
            .description(serverCopy("acknowledge_hint"))
            .icon(Icon.of("triangle-exclamation"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) -> ServerModel.postureNeedsAcknowledgement(row)
                && !ServerModel.postureAcknowledged(row))
            .confirmation(ConfirmationSpec.builder()
                .title(serverCopy("acknowledge"))
                .body(serverCopy("acknowledge_generic"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(serverCopy("acknowledge"))
                .body(serverCopy("acknowledge_body")
                    .withArg("name", row.get(ServerModel.NAME)))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(row.get(ServerModel.NAME))
                .build())
            .handler((row, ctx) -> {
                HostPostureAcknowledgement.record(row);
                return CmsActionResult.refreshWithToast(serverCopy("posture_acknowledged")
                    .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * The EXPLICIT live probe. This used to be the {@code live_overview} pseudo-field,
     * which ran {@code probeAndStore} as a side effect of RENDERING the edit form --
     * a page view must never contact a remote daemon. Deliberate now: one click, one
     * probe, the typed outcome persisted and reported either way.
     */
    private static @NonNull RowAction<Row> probeAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "probe_server"))
            .label(serverCopy("probe_now"))
            .description(serverCopy("probe_now_hint"))
            .icon(Icon.of("heart-pulse"))
            .inlineInRow(false)
            .handler((row, ctx) -> {
                String name = row.get(ServerModel.NAME);
                String label = ServerModel.isIncus(row) ? "Incus" : "Docker";
                ServerService.Summary summary = new ServerService().probeAndStore(name);
                if (summary == null || !summary.reachable()) {
                    throw Violations.ofForm(CmsSupport.violationText("host_probe_failed")
                        .withArg("name", name)
                        .withArg("kind", summary != null && summary.errorKind() != null
                            ? summary.errorKind() : "unknown"));
                }
                return CmsActionResult.refreshWithToast(serverCopy("host_probe_ok")
                    .withArg("name", name)
                    .withArg("summary", formatSummary(summary, label)));
            })
            .build();
    }

    /** Run the full preflight and store its report; the toast states the verdict. */
    private static @NonNull RowAction<Row> preflightAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "preflight_server"))
            .label(Microcopy.of("preflight").withFilter("scope", "server"))
            .icon(Icon.of("stethoscope"))
            .inlineInRow(false)
            .handler((row, ctx) -> {
                String name = row.get(ServerModel.NAME);
                HostPreflight.Report[] report = new HostPreflight.Report[1];
                ActivityLog.withAction(ActivityLog.ACTION_UPDATE, "preflight",
                    () -> report[0] = HostPreflight.runAndStore(name));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of(report[0].passed() ? "preflight_passed" : "preflight_failed")
                        .withFilter("scope", "server")
                        .withArg("name", name));
            })
            .build();
    }

    /** Admit for placement; refused unless the LAST preflight passed. */
    private static @NonNull RowAction<Row> admitAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "admit_server"))
            .label(Microcopy.of("admit").withFilter("scope", "server"))
            .icon(Icon.of("circle-check"))
            .visibleFor((row, ctx) ->
                !ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "admit");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_admitted").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * Refuse NEW placement while existing workloads keep running.
     *
     * AIDEV-NOTE: confirmed because it is a fleet-wide scheduling change made from a row
     * menu, and the label alone does not say that running workloads are untouched --
     * admit and uncordon deliberately stay unconfirmed, being the reversals of this one.
     */
    private static @NonNull RowAction<Row> cordonAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "cordon_server"))
            .label(Microcopy.of("cordon").withFilter("scope", "server"))
            .icon(Icon.of("circle-pause"))
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("cordon").withFilter("scope", "server"))
                .body(Microcopy.of("cordon_confirm").withFilter("scope", "server"))
                .confirmLabel(Microcopy.of("cordon").withFilter("scope", "server"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .visibleFor((row, ctx) ->
                ServerModel.ADMISSION_ADMITTED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                setAdmission(row, ServerModel.ADMISSION_CORDONED, "cordon");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_cordoned").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    /**
     * Cold-migrate every live instance off a CORDONED host. A workload that cannot
     * move is refused by name and left untouched; the toast says which -- the drain
     * is only complete when the host holds none.
     */
    private static @NonNull RowAction<Row> drainAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "drain_server"))
            .label(Microcopy.of("drain").withFilter("scope", "server"))
            .icon(Icon.of("truck-arrow-right"))
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("drain").withFilter("scope", "server"))
                .body(Microcopy.of("drain_confirm").withFilter("scope", "server"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .visibleFor((row, ctx) ->
                ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                Integer serverId = row.get(ServerModel.ID);
                // AIDEV-NOTE: no ActivityLog.withAction here, deliberately. A drain writes
                // every instance through fenced updateAll calls, which fire no write hooks,
                // so the wrapper that used to sit here renamed nothing and recorded nothing.
                // InstanceMigrations records the per-instance moves and the host-level drain
                // explicitly instead. Traced 2026-08-07.
                InstanceMigrations.DrainReport report = new InstanceMigrations().drain(serverId);
                if (report.complete()) {
                    return CmsActionResult.refreshWithToast(
                        Microcopy.of("host_drained").withFilter("scope", "server")
                            .withArg("name", row.get(ServerModel.NAME))
                            .withArg("moved", report.moved().size()));
                }
                String held = report.refused().stream()
                    .map(InstanceMigrations.DrainEntry::name)
                    .collect(Collectors.joining(", "));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_drain_partial").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME))
                        .withArg("moved", report.moved().size())
                        .withArg("refused", report.refused().size())
                        .withArg("held", held));
            })
            .build();
    }

    /** Lift a cordon; the stored preflight verdict must still be green. */
    private static @NonNull RowAction<Row> uncordonAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "uncordon_server"))
            .label(Microcopy.of("uncordon").withFilter("scope", "server"))
            .icon(Icon.of("circle-play"))
            .visibleFor((row, ctx) ->
                ServerModel.ADMISSION_CORDONED.equals(row.get(ServerModel.ADMISSION)))
            .handler((row, ctx) -> {
                HostAdmission.requireAdmittable(row);
                setAdmission(row, ServerModel.ADMISSION_ADMITTED, "uncordon");
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("host_admitted").withFilter("scope", "server")
                        .withArg("name", row.get(ServerModel.NAME)));
            })
            .build();
    }

    private static void setAdmission(@NonNull Row row, @NonNull String admission,
                                     @NonNull String action) {
        ActivityLog.withAction(ActivityLog.ACTION_UPDATE, action, () -> {
            row.set(ServerModel.ADMISSION, admission);
            Models.get(ServerModel.class).save(row);
        });
    }

    private static @NonNull String formatSummary(ServerService.@NonNull Summary summary,
                                                 @NonNull String label) {
        String docker = summary.daemonVersion().isBlank() ? label
            : label + " " + summary.daemonVersion();
        String platform = summary.osType();
        if (!summary.architecture().isBlank()) {
            platform = platform.isBlank() ? summary.architecture() : platform + "/" + summary.architecture();
        }
        String operatingSystem = summary.operatingSystem().isBlank() ? platform : summary.operatingSystem();
        if (!platform.isBlank() && !operatingSystem.equals(platform)) {
            operatingSystem += " (" + platform + ")";
        }
        if (operatingSystem.isBlank()) {
            operatingSystem = hostCopy(serverCopy("host_unknown_platform"));
        }
        double memoryGib = Math.round(summary.memoryBytes() / 1_073_741_824.0 * 10) / 10.0;
        return hostCopy(serverCopy("host_summary")
            .withArg("docker", docker)
            .withArg("os", operatingSystem)
            .withArg("cpus", String.valueOf(summary.cpus()))
            .withArg("memory", String.valueOf(memoryGib))
            .withArg("running", String.valueOf(summary.containersRunning()))
            .withArg("total", String.valueOf(summary.containersTotal()))
            .withArg("images", String.valueOf(summary.images())));
    }
}
