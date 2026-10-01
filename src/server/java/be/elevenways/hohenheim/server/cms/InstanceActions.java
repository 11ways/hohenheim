package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceTemplateCapture;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.CmsActionResultTranslator;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;

/**
 * THE instance verbs, built once for both panels: the operator list and the delegated subset read the same builders,
 * so an action's gate and handler exist exactly once. Power, backup, snapshot, the app update and the console line are
 * placed operations ({@link InstanceOperations}, invoked through the one invoke route); the other verbs stay legacy row
 * actions until their operations land (stage 5).
 *
 * AIDEV-NOTE: a placed operation's record capability is its gate's {@code subjectCapability}, asked by the same offer
 * that draws the button and by the pipeline on invoke, for an admin and a /manage principal alike; a generated row is
 * outside every operation's {@code applies}, so it is hidden and its invoke reads as missing. Its
 * {@code hiddenWhen}/{@code disabledWhen} are presentation only (stop of a stopped instance is idempotent, start of an
 * instance whose database is not ready refuses with the same words). A legacy row action still declares its record
 * capability in visibleFor, which zenit-cms re-checks on INVOKE; it has no panel to ask, so the operator-only ones
 * name {@link HohenheimSlugs#ADMIN}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class InstanceActions {

    private final InstanceService instances;

    InstanceActions(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * The operator panel's placed operations, Deploy first: the record band keeps declaration order inside the
     * inline band, and placed actions lead the declared row actions, so the first declared verb leads.
     */
    @NonNull List<PanelAction<Row>> placedOperator() {
        return List.of(deployAction(), stopAction(), restartAction(), snapshotAction(), backupAction(),
            appUpdateAction(), consoleCommandAction());
    }

    /**
     * The delegated panel's placed subset: power without restart, the two artifact actions, the app update and the
     * console line.
     */
    @NonNull List<PanelAction<Row>> placedDelegated() {
        return List.of(deployAction(), stopAction(), snapshotAction(), backupAction(), appUpdateAction(),
            consoleCommandAction());
    }

    /** The operator panel's legacy row actions. */
    @NonNull List<RowAction<Row>> operator() {
        return List.of(this.exposeAction(), this.rollbackAction(), this.installAction(),
            this.reinstallAction(), this.captureTemplateAction(), this.migrateAction(),
            this.destroyWithDataAction());
    }

    /**
     * The delegated panel's legacy subset: none. Placement, template capture, install and every destroy stay operator
     * acts; the app update is placed.
     */
    @NonNull List<RowAction<Row>> delegated() {
        return List.of();
    }

    /**
     * Deploy (the start operation), offered only where it means something: the record is not owned by a product
     * tier AND its KIND is one a person may power at all ({@link InstanceKinds#isUserDeployable}, which reads the
     * kind's own {@code generatedOnly()} declaration). Dead, with the database and its state on screen, while an
     * attached managed database is not active: the same words the handler refuses a direct POST with.
     *
     * AIDEV-NOTE: deliberately NOT ActionStyle.PRIMARY (reverted 2026-08-22): the style renders the button FILLED, a
     * solid accent Deploy beside the red Delete on every row; it already leads as the first declared verb.
     */
    private static @NonNull PanelAction<Row> deployAction() {
        return PanelAction.<Row, InstanceOperations.PowerResult>places(InstanceOperations.START, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("deployed")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(Microcopy.of("deploy").withFilter("scope", "instance"))
            .icon(Icon.of("play"))
            .hiddenWhen(row -> !InstanceKinds.isUserDeployable(row.get(InstanceModel.KIND)))
            .disabledWhen(row -> {
                Integer id = row.get(InstanceModel.ID);
                return id == null ? null : InstanceDatabaseLinks.notReadyReason(id);
            })
            .build();
    }

    /**
     * Stop rides the OVERFLOW menu: it is confirmed anyway, and keeping it out of the strip leaves ONE inline verb and
     * one red button per row. Hidden unless the instance runs; a direct POST on a stopped one is a no-op success.
     */
    private static @NonNull PanelAction<Row> stopAction() {
        return PanelAction.<Row, InstanceOperations.PowerResult>places(InstanceOperations.STOP, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("stopped_toast")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(Microcopy.of("stop").withFilter("scope", "instance"))
            .icon(Icon.of("stop"))
            .inlineInRow(false)
            .style(ActionStyle.DESTRUCTIVE)
            .hiddenWhen(row -> !InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("stop").withFilter("scope", "instance"))
                .body(Microcopy.of("stop_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("stop").withFilter("scope", "instance"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .build();
    }

    /** Stop and start again: the restart operation, ONE lock hold across both halves. */
    private static @NonNull PanelAction<Row> restartAction() {
        return PanelAction.<Row, InstanceOperations.PowerResult>places(InstanceOperations.RESTART, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("restarted_toast")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(Microcopy.of("restart").withFilter("scope", "instance"))
            .icon(Icon.of("rotate-right"))
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("restart").withFilter("scope", "instance"))
                .body(Microcopy.of("restart_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("restart").withFilter("scope", "instance"))
                .build())
            .build();
    }

    /**
     * Cold capture: a running instance is stopped for the copy and redeployed after. The admin asks no note (preset
     * empty), as before; note editing waits for the operation input forms (F.16).
     */
    private static @NonNull PanelAction<Row> snapshotAction() {
        return PanelAction.<Row, Integer>places(InstanceOperations.SNAPSHOT, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("snapshot_taken")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(Microcopy.of("snapshot").withFilter("scope", "instance"))
            .icon(Icon.of("camera"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .preset(row -> new InstanceOperations.SnapshotInput(null))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("snapshot").withFilter("scope", "instance"))
                .body(Microcopy.of("snapshot_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("snapshot").withFilter("scope", "instance"))
                .build())
            .build();
    }

    /** Export to the configured backup target (refuses, named, when none is set). */
    private static @NonNull PanelAction<Row> backupAction() {
        return PanelAction.<Row, Integer>places(InstanceOperations.BACKUP, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("backup_done")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(Microcopy.of("backup_now").withFilter("scope", "instance"))
            .icon(Icon.of("box-archive"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("backup_now").withFilter("scope", "instance"))
                .body(Microcopy.of("backup_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("backup_now").withFilter("scope", "instance"))
                .build())
            .build();
    }

    /**
     * Open the site create form with THIS instance preselected as the upstream: the
     * "give it a hostname" affordance, offered only where the routing tier could
     * actually serve it (the kind declares {@code supportsSiteUpstream}).
     */
    private @NonNull RowAction<Row> exposeAction() {
        return RowAction.Url.<Row>builder(HohenheimIds.id("expose_instance"))
            .label(Microcopy.of("expose").withFilter("scope", "instance"))
            .icon(Icon.of("globe"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .description(Microcopy.of("expose_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && supportsSiteUpstream(row)
                && HohenheimAccess.isAdmin(ctx))
            // The operator panel for the migrateAction reason (no conduit here), and this
            // action is admin-only: a site create is an operator act.
            .url(row -> new Uri(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, HohenheimSlugs.ADMIN)
                .with(CmsEndpoints.RESOURCE_PARAM, HohenheimSlugs.SITES)
                .with(HohenheimParams.UPSTREAM_KIND_PREFILL, InstanceUpstreamKind.ID.toString())
                .with(HohenheimParams.INSTANCE_ID_PREFILL, row.get(InstanceModel.ID))
                .toUrl()))
            .build();
    }

    /**
     * Roll a release-managed record back to its retained release -- the same engine
     * verb the site row offers, now reachable from the application itself (an
     * unexposed application can still be rolled back).
     */
    private @NonNull RowAction<Row> rollbackAction() {
        return RowAction.Invoke.<Row>builder(HohenheimIds.id("rollback_instance"))
            .label(Microcopy.of("rollback").withFilter("scope", "instance"))
            .icon(Icon.of("clock-rotate-left"))
            .inlineInRow(false)
            .description(Microcopy.of("rollback_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row)
                && InstanceKinds.isReleaseManaged(row.get(InstanceModel.KIND))
                && HohenheimAccess.reachesRecord(ctx, InstanceModel.MODEL_ID,
                    row.get(InstanceModel.ID), HohenheimAccess.POWER))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("rollback").withFilter("scope", "instance"))
                .body(Microcopy.of("rollback_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("rollback").withFilter("scope", "instance"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .handler((row, ctx) -> {
                ReleaseEngine.rollback(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("rollback_done").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /** Whether a site's instance upstream could serve this row's kind. */
    private static boolean supportsSiteUpstream(@NonNull Row row) {
        InstanceKindHandler handler = InstanceKinds.getHandler(row.get(InstanceModel.KIND));
        return handler != null && handler.supportsSiteUpstream();
    }

    /** Run (or resume/retry) the template's install step. */
    private @NonNull RowAction<Row> installAction() {
        return RowAction.Invoke.<Row>builder(HohenheimIds.id("install_instance"))
            .label(Microcopy.of("install").withFilter("scope", "instance"))
            .icon(Icon.of("wand-magic-sparkles"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row)
                && row.get(InstanceModel.TEMPLATE_ID) != null
                && !InstanceModel.INSTALL_NONE.equals(row.get(InstanceModel.INSTALL_STATE))
                && !InstanceModel.INSTALL_INSTALLED.equals(row.get(InstanceModel.INSTALL_STATE)))
            .handler((row, ctx) -> {
                new InstanceInstalls().install(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("installed_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * Reinstall per the template's EXPLICIT data policy. A clear-policy template gets
     * a destructive dialog that demands the instance's name typed back; preserve gets
     * an ordinary confirmation. The dialog is the accident guard -- the POLICY itself
     * is enforced in InstanceInstalls.
     */
    private @NonNull RowAction<Row> reinstallAction() {
        return RowAction.Invoke.<Row>builder(HohenheimIds.id("reinstall_instance"))
            .label(Microcopy.of("reinstall").withFilter("scope", "instance"))
            .icon(Icon.of("rotate"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row)
                && row.get(InstanceModel.TEMPLATE_ID) != null
                && (InstanceModel.INSTALL_INSTALLED.equals(row.get(InstanceModel.INSTALL_STATE))
                    || InstanceModel.INSTALL_FAILED.equals(row.get(InstanceModel.INSTALL_STATE))))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("reinstall").withFilter("scope", "instance"))
                .body(Microcopy.of("reinstall_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("reinstall").withFilter("scope", "instance"))
                .build())
            .dynamicConfirmation(row -> {
                boolean clears = templateClearsOnReinstall(row);
                ConfirmationSpec.Builder spec = ConfirmationSpec.builder()
                    .title(Microcopy.of("reinstall").withFilter("scope", "instance"))
                    .body(Microcopy.of(clears ? "reinstall_clear_confirm" : "reinstall_confirm")
                        .withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)))
                    .confirmLabel(Microcopy.of("reinstall").withFilter("scope", "instance"));
                if (clears) {
                    spec.style(ActionStyle.DESTRUCTIVE)
                        .requireTypedConfirmation(
                            String.valueOf((Object) row.get(InstanceModel.NAME)));
                }
                return spec.build();
            })
            .handler((row, ctx) -> {
                new InstanceInstalls().reinstall(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("reinstalled_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * In-place app update: the template's update_script runs inside the RUNNING system. The operation's gate (config)
     * and its applies (no generated instance) decide who sees it; only an instance with an update script offers it.
     */
    private static @NonNull PanelAction<Row> appUpdateAction() {
        return PanelAction.<Row, String>places(InstanceOperations.APP_UPDATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("app_updated_toast")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(Microcopy.of("app_update").withFilter("scope", "instance"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .hiddenWhen(row -> !InstanceAppUpdates.hasUpdateScript(row))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("app_update").withFilter("scope", "instance"))
                .body(Microcopy.of("app_update_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("app_update").withFilter("scope", "instance"))
                .build())
            .build();
    }

    /**
     * One line to the workload's primary process, asked in the action's dialog; the console tab's own form posts to
     * the same invoke. The operation's gate (console) and its applies (no generated instance) decide who sees it;
     * only a running instance offers it.
     */
    private static @NonNull PanelAction<Row> consoleCommandAction() {
        return PanelAction.<Row, String>places(InstanceOperations.CONSOLE_COMMAND, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(Microcopy.of("console_command_sent_toast")
                    .withFilter("scope", "instance").withArg("name", request.subject().get(InstanceModel.NAME))))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .hiddenWhen(row -> !InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS)))
            .confirmation(ConfirmationSpec.builder()
                .title(InstanceOperations.CONSOLE_COMMAND.label())
                .body(Microcopy.of("console_command_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("send").withFilter("scope", "instance_console"))
                .build())
            .build();
    }

    private static boolean templateClearsOnReinstall(@NonNull Row instance) {
        Object templateId = instance.get(InstanceModel.TEMPLATE_ID);
        if (!(templateId instanceof Integer id)) {
            return false;
        }
        Row template = Models.get(InstanceTemplateModel.class).findById(id);
        return template != null && InstanceTemplateModel.REINSTALL_CLEAR
            .equals(template.get(InstanceTemplateModel.REINSTALL_POLICY));
    }

    /**
     * Publish this STOPPED instance's state as a prepared template (unapproved), then
     * open the minted template's form. OPERATOR-ONLY and deliberately NOT inherited as
     * an offer by /manage principals: capture mints catalog authority, and the service
     * re-refuses a tenant with the uniform refusal
     * ({@link InstanceTemplateCapture}).
     */
    private @NonNull RowAction<Row> captureTemplateAction() {
        return RowAction.Invoke.<Row>builder(HohenheimIds.id("capture_template"))
            .label(Microcopy.of("capture_template").withFilter("scope", "instance"))
            .icon(Icon.of("box-archive"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .description(Microcopy.of("capture_template_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && HohenheimAccess.isAdmin(ctx)
                && InstanceModel.STATUS_STOPPED.equals(row.get(InstanceModel.STATUS))
                && supportsCapture(row))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("capture_template").withFilter("scope", "instance"))
                .body(Microcopy.of("capture_template_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("capture_template").withFilter("scope", "instance"))
                .build())
            .handler((row, ctx) -> {
                int templateId = new InstanceTemplateCapture()
                    .capture(row.get(InstanceModel.ID));
                // The operator panel for the migrateAction reason: a row action has no
                // conduit to ask, and this action is admin-only.
                return CmsActionResult.redirect(new Uri(CmsRoutes.detail(HohenheimSlugs.ADMIN,
                    HohenheimSlugs.INSTANCE_TEMPLATES, templateId).toUrl()));
            })
            .build();
    }

    private static boolean supportsCapture(@NonNull Row row) {
        InstanceKindHandler handler = InstanceKinds.getHandler(row.get(InstanceModel.KIND));
        return handler != null && handler.supportsTemplateCapture();
    }

    /**
     * Open the migrate tab. A LINK, not an invoke, because the destination is an operator
     * choice the page makes -- and deliberately NOT in {@link #delegated()}: placement is
     * an operator authority.
     *
     * Visible whenever the viewer is an operator, INCLUDING while a capture, restore or
     * migration protects the record -- the page then states which status blocks the move
     * and offers no destination. A hidden control explains nothing.
     */
    private @NonNull RowAction<Row> migrateAction() {
        return RowAction.Url.<Row>builder(HohenheimIds.id("migrate_instance"))
            .label(Microcopy.of("migrate").withFilter("scope", "instance"))
            .icon(Icon.of("truck-fast"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .description(Microcopy.of("migrate_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && HohenheimAccess.isAdmin(ctx))
            // RowAction.Url is Uri-typed, so the typed target is rendered here. The panel
            // slug is the operator panel this action always produced (a row action has no
            // conduit to ask), so the URL does not move.
            .url(row -> new Uri(CmsRoutes.subpage(HohenheimSlugs.ADMIN, InstanceResource.SLUG,
                row.get(InstanceModel.ID), InstanceMigratePage.SLUG).toUrl()))
            .build();
    }

    /**
     * The one irreversible verb: destroy the workload AND the volumes it owns.
     *
     * AIDEV-NOTE: a SEPARATE action beside delete, not a checkbox on it. Delete keeps the
     * data by design (the class note on {@code deleteRow}), so an operator who wants the
     * bytes gone has to say so, and the dialog demands the instance's name typed back --
     * the same guard the reinstall-that-clears and the host-retire actions use.
     */
    private @NonNull RowAction<Row> destroyWithDataAction() {
        return RowAction.Invoke.<Row>builder(HohenheimIds.id("destroy_instance_data"))
            .label(Microcopy.of("delete_with_data").withFilter("scope", "instance"))
            .icon(Icon.of("trash-can"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && HohenheimAccess.isAdmin(ctx))
            // The record-less fallback the dynamic one refines; a dynamic confirmation
            // without it is refused at registration (WriteAffordanceParityTest).
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("delete_with_data").withFilter("scope", "instance"))
                .body(Microcopy.of("delete_with_data_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("delete_with_data").withFilter("scope", "instance"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> ConfirmationSpec.builder()
                .title(Microcopy.of("delete_with_data").withFilter("scope", "instance"))
                .body(withDataBody(row))
                .confirmLabel(Microcopy.of("delete_with_data").withFilter("scope", "instance"))
                .style(ActionStyle.DESTRUCTIVE)
                .requireTypedConfirmation(String.valueOf((Object) row.get(InstanceModel.NAME)))
                .build())
            .handler((row, ctx) -> {
                this.instances.destroyWithData(row.get(InstanceModel.ID));
                // The record is soft-deleted now, so a Refresh would soft-redirect back to
                // a detail page that no longer resolves and the toast would never show
                // (F5: "the page just sits there"). Stash the toast, land on the list.
                Microcopy done = Microcopy.of("deleted_with_data_toast")
                    .withFilter("scope", "instance")
                    .withArg("name", row.get(InstanceModel.NAME));
                Conduit conduit = ctx.access().conduit();
                if (conduit == null) {
                    return CmsActionResult.refreshWithToast(done);
                }
                CmsActionResultTranslator.stashSuccess(conduit, done);
                return CmsActionResult.redirect(new Uri(
                    CmsRoutes.list(CmsSupport.panelSlug(conduit), InstanceResource.SLUG).toUrl()));
            })
            .build();
    }

    /**
     * The delete-with-data body, naming the sites the destroy will disable when any do.
     * The typed-name gate on the dialog is unchanged either way.
     */
    private static @NonNull Microcopy withDataBody(@NonNull Row row) {
        String sites = InstanceResource.strandedSites(row);
        Microcopy body = sites == null
            ? Microcopy.of("delete_with_data_confirm").withFilter("scope", "instance")
            : Microcopy.of("delete_with_data_confirm_stranding")
                .withFilter("scope", "instance").withArg("sites", sites);
        return body.withArg("name", row.get(InstanceModel.NAME));
    }
}
