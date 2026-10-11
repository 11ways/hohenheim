package be.elevenways.hohenheim.server.cms;

import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.instance.InstanceTemplateCapture;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.CmsActionResultTranslator;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * THE instance verbs, built once for both panels: the operator list and the delegated subset read the same builders,
 * so an action's gate and handler exist exactly once. Every verb is a placed operation ({@link InstanceOperations},
 * invoked through the one invoke route) or a placed link (expose, migrate).
 *
 * AIDEV-NOTE: a placed operation's record capability is its gate's {@code subjectCapability}, asked by the same offer
 * that draws the button and by the pipeline on invoke, for an admin and a /manage principal alike; a generated row is
 * outside every operation's {@code applies}, so it is hidden and its invoke reads as missing. Its
 * {@code hiddenWhen}/{@code disabledWhen} are presentation only (stop of a stopped instance is idempotent, start of an
 * instance whose database is not ready, or whose host will refuse it, refuses with the same words). The operator verbs
 * answer to an operator alone through their authorizers (HohenheimAccess.operatorOnly).
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class InstanceActions {

    /**
     * The health band's fix for a blocked instance ({@link AppHealth}): its host's page, where Check and admit lives.
     */
    static final Identifier CHECK_HOST = HohenheimIds.id("instance_check_host");

    /** The address of the site serving this workload, in a new tab ({@link SiteActions#openSiteAction}). */
    static final Identifier OPEN_SITE = HohenheimIds.id("instance_open_site");

    private InstanceActions() {
    }

    /**
     * The operator panel's actions, Open site then Deploy first (the heading): the record band keeps
     * declaration order inside the inline band, so the first declared verb leads.
     */
    static @NonNull List<PanelAction<Row>> placedOperator() {
        return List.of(SiteActions.openSiteAction(OPEN_SITE, AppHealth::openUrlOfInstance, AppHealth::instanceServes),
            deployAction(false),
            stopAction(), restartAction(false), snapshotAction(), backupAction(),
            appUpdateAction(false), consoleCommandAction(), exposeAction(), rollbackAction(),
            installAction(), reinstallAction(), captureTemplateAction(), migrateAction(),
            destroyWithDataAction(), checkHostAction());
    }

    /**
     * The delegated panel's placed subset: power (deploy, restart, stop), the two artifact actions,
     * the app update and the console line.
     */
    static @NonNull List<PanelAction<Row>> placedDelegated() {
        return List.of(SiteActions.openSiteAction(OPEN_SITE, AppHealth::openUrlOfInstance, AppHealth::instanceServes),
            deployAction(true), restartAction(true),
            stopAction(), snapshotAction(), backupAction(), appUpdateAction(true), consoleCommandAction());
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
    private static @NonNull PanelAction<Row> deployAction(boolean delegated) {
        return PanelAction.<Row, InstanceOperations.PowerResult>places(InstanceOperations.START, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("deployed")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(HohenheimMicrocopy.INSTANCE.of("deploy"))
            .icon(Icon.of("play"))
            .hiddenWhen(row -> !InstanceKinds.isUserDeployable(row.get(InstanceModel.KIND)))
            .disabledWhen((row, access) -> {
                Integer id = row.get(InstanceModel.ID);
                Microcopy database = id == null ? null : InstanceDatabaseLinks.notReadyReason(id);
                return database != null ? database : OwnedInstances.placementReasonOf(row, delegated, access);
            })
            .build();
    }

    /**
     * Stop rides the OVERFLOW menu: it is confirmed anyway, and keeping it out of the strip leaves ONE inline verb and
     * one red button per row. Hidden unless the instance runs; a direct POST on a stopped one is a no-op success.
     */
    private static @NonNull PanelAction<Row> stopAction() {
        return PanelAction.<Row, InstanceOperations.PowerResult>places(InstanceOperations.STOP, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("stopped_toast")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(HohenheimMicrocopy.INSTANCE.of("stop"))
            .icon(Icon.of("stop"))
            .inlineInRow(false)
            .style(ActionStyle.DESTRUCTIVE)
            .hiddenWhen(row -> !InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS)))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("stop"),
                HohenheimMicrocopy.INSTANCE.of("stop_confirm"), ActionStyle.DESTRUCTIVE))
            .build();
    }

    /**
     * Stop and start again: the restart operation, ONE lock hold across both halves.
     *
     * @param delegated whether the button is drawn on /manage, where the host is operator inventory
     */
    private static @NonNull PanelAction<Row> restartAction(boolean delegated) {
        return PanelAction.<Row, InstanceOperations.PowerResult>places(InstanceOperations.RESTART, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("restarted_toast")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(HohenheimMicrocopy.INSTANCE.of("restart"))
            .icon(Icon.of("rotate-right"))
            .inlineInRow(false)
            .disabledWhen((row, access) -> OwnedInstances.placementReasonOf(row, delegated, access))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("restart"),
                HohenheimMicrocopy.INSTANCE.of("restart_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    /**
     * Cold capture: a running instance is stopped for the copy and redeployed after. The admin asks no note (preset
     * empty), as before; note editing waits for the operation input forms (F.16).
     */
    private static @NonNull PanelAction<Row> snapshotAction() {
        return PanelAction.<Row, Integer>places(InstanceOperations.SNAPSHOT, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("snapshot_taken")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(HohenheimMicrocopy.INSTANCE.of("snapshot"))
            .icon(Icon.of("camera"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .preset(row -> new InstanceOperations.SnapshotInput(null))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("snapshot"),
                HohenheimMicrocopy.INSTANCE.of("snapshot_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    /** Export to the configured backup target (refuses, named, when none is set). */
    private static @NonNull PanelAction<Row> backupAction() {
        return PanelAction.<Row, Integer>places(InstanceOperations.BACKUP, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE.of("backup_done")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(HohenheimMicrocopy.INSTANCE.of("backup_now"))
            .icon(Icon.of("box-archive"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("backup_now"),
                HohenheimMicrocopy.INSTANCE.of("backup_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    /**
     * To the instance's host, offered while that host refuses the instance (the same refusal the power buttons read):
     * the operator's way from "cannot start" to the check that admits the host.
     */
    private static @NonNull PanelAction<Row> checkHostAction() {
        return PanelAction.<Row>link(CHECK_HOST, ActionPlacement.ROW)
            .label(HohenheimMicrocopy.APP_HEALTH.of("check_host"))
            .icon(Icon.of("stethoscope"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .shownWhen((row, ctx) -> OwnedInstances.mayClearPlacement(ctx)
                    && OwnedInstances.placementRefusal(row) != null)
            .route((row, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.SERVERS,
                OwnedInstances.placementHost(row), RecordOverview.SLUG))
            .build();
    }

    /**
     * Open the site create form with THIS instance preselected as the upstream: the
     * "give it a hostname" affordance, offered only where the routing tier could
     * actually serve it (the kind declares {@code supportsSiteUpstream}).
     */
    private static @NonNull PanelAction<Row> exposeAction() {
        return PanelAction.<Row>link(HohenheimIds.id("expose_instance"), ActionPlacement.ROW)
            .label(HohenheimMicrocopy.INSTANCE.of("expose"))
            .icon(Icon.of("globe"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .description(HohenheimMicrocopy.INSTANCE.of("expose_hint"))
            .shownWhen((row, ctx) -> !InstanceParts.isGenerated(row) && supportsSiteUpstream(row)
                && HohenheimAccess.isAdmin(ctx))
            // The operator panel: this action is admin-only, a site create being an operator act.
            .route((row, request) -> CmsRoutes.create(HohenheimSlugs.ADMIN, HohenheimSlugs.SITES)
                .with(HohenheimParams.UPSTREAM_KIND_PREFILL, InstanceUpstreamKind.ID.toString())
                .with(HohenheimParams.INSTANCE_ID_PREFILL, row.get(InstanceModel.ID)))
            .build();
    }

    /**
     * Roll a release-managed record back to its retained release -- the same engine
     * verb the site row offers, now reachable from the application itself (an
     * unexposed application can still be rolled back).
     */
    private static @NonNull PanelAction<Row> rollbackAction() {
        return PanelAction.<Row, Void>places(InstanceOperations.ROLLBACK, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.INSTANCE.of("rollback_done")
                        .withArg("name", request.subject().get(InstanceModel.NAME))))
            .inlineInRow(false)
            .disabledWhen((row, access) -> OwnedInstances.placementReasonOf(row, false, access))
            .description(HohenheimMicrocopy.INSTANCE.of("rollback_hint"))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("rollback"),
                HohenheimMicrocopy.INSTANCE.of("rollback_confirm"), ActionStyle.DESTRUCTIVE))
            .build();
    }

    /** Whether a site's instance upstream could serve this row's kind. */
    private static boolean supportsSiteUpstream(@NonNull Row row) {
        InstanceKindHandler handler = InstanceKinds.handlerOf(row);
        return handler != null && handler.supportsSiteUpstream();
    }

    /** Run (or resume/retry) the template's install step. */
    private static @NonNull PanelAction<Row> installAction() {
        return PanelAction.<Row, Void>places(InstanceOperations.INSTALL, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.INSTANCE.of("installed_toast")
                        .withArg("name", request.subject().get(InstanceModel.NAME))))
            .inlineInRow(false)
            .disabledWhen((row, access) -> OwnedInstances.placementReasonOf(row, false, access))
            .build();
    }

    /**
     * Reinstall per the template's EXPLICIT data policy. A clear-policy template gets
     * a destructive dialog that demands the instance's name typed back; preserve gets
     * an ordinary confirmation. The dialog is the accident guard -- the POLICY itself
     * is enforced in InstanceInstalls.
     */
    private static @NonNull PanelAction<Row> reinstallAction() {
        return PanelAction.<Row, Void>places(InstanceOperations.REINSTALL, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(
                    HohenheimMicrocopy.INSTANCE.of("reinstalled_toast")
                        .withArg("name", request.subject().get(InstanceModel.NAME))))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .disabledWhen((row, access) -> OwnedInstances.placementReasonOf(row, false, access))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("reinstall"),
                HohenheimMicrocopy.INSTANCE.of("reinstall_confirm"), ActionStyle.DEFAULT))
            .dynamicConfirmation(row -> {
                boolean clears = templateClearsOnReinstall(row);
                ConfirmationSpec spec = ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("reinstall"),
                    HohenheimMicrocopy.INSTANCE.of(clears ? "reinstall_clear_confirm" : "reinstall_confirm")
                        .withArg("name", row.get(InstanceModel.NAME)),
                    clears ? ActionStyle.DESTRUCTIVE : ActionStyle.DEFAULT);
                return clears ? spec.withTypedConfirmation(String.valueOf((Object) row.get(InstanceModel.NAME))) : spec;
            })
            .build();
    }

    /**
     * In-place app update: the template's update_script runs inside the RUNNING system. The operation's gate (config)
     * and its applies (no generated instance) decide who sees it; only an instance with an update script offers it.
     */
    private static @NonNull PanelAction<Row> appUpdateAction(boolean delegated) {
        return PanelAction.<Row, String>places(InstanceOperations.APP_UPDATE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE
                    .of("app_updated_toast")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .label(HohenheimMicrocopy.INSTANCE.of("app_update"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .hiddenWhen(row -> !InstanceAppUpdates.hasUpdateScript(row))
            .disabledWhen((row, access) -> OwnedInstances.placementReasonOf(row, delegated, access))
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("app_update"),
                HohenheimMicrocopy.INSTANCE.of("app_update_confirm"), ActionStyle.DEFAULT))
            .build();
    }

    /**
     * One line to the workload's primary process, asked in the action's dialog; the console tab's own form posts to
     * the same invoke. The operation's gate (console) and its applies (no generated instance) decide who sees it;
     * only a running instance offers it.
     */
    private static @NonNull PanelAction<Row> consoleCommandAction() {
        return PanelAction.<Row, String>places(InstanceOperations.CONSOLE_COMMAND, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE
                    .of("console_command_sent_toast")
                    .withArg("name", request.subject().get(InstanceModel.NAME))))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .hiddenWhen(row -> !InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS)))
            .confirmation(ConfirmationSpec.of(InstanceOperations.CONSOLE_COMMAND.label(),
                HohenheimMicrocopy.INSTANCE_CONSOLE.of("send"),
                HohenheimMicrocopy.INSTANCE.of("console_command_confirm"), ActionStyle.DEFAULT))
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
    private static @NonNull PanelAction<Row> captureTemplateAction() {
        // The operator panel: capture is admin-only, and the minted template opens there.
        return CmsSupport.opensWhatItMade(InstanceOperations.CAPTURE_TEMPLATE,
                (panel, id) -> CmsRoutes.detail(HohenheimSlugs.ADMIN, HohenheimSlugs.INSTANCE_TEMPLATES, id).toUrl(),
                HohenheimMicrocopy.INSTANCE.of("capture_template"),
                HohenheimMicrocopy.INSTANCE.of("capture_template_confirm"))
            .description(HohenheimMicrocopy.INSTANCE.of("capture_template_hint"))
            .build();
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
    private static @NonNull PanelAction<Row> migrateAction() {
        return PanelAction.<Row>link(HohenheimIds.id("migrate_instance"), ActionPlacement.ROW)
            .label(HohenheimMicrocopy.INSTANCE.of("migrate"))
            .icon(Icon.of("truck-fast"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .description(HohenheimMicrocopy.INSTANCE.of("migrate_hint"))
            .shownWhen((row, ctx) -> !InstanceParts.isGenerated(row) && HohenheimAccess.isAdmin(ctx))
            .route((row, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES,
                row.get(InstanceModel.ID), HohenheimSlugs.Tab.MIGRATE))
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
    private static @NonNull PanelAction<Row> destroyWithDataAction() {
        return PanelAction.<Row, Void>places(InstanceOperations.DESTROY_WITH_DATA, ActionPlacement.ROW,
                (request, result) -> {
                    // The record is soft-deleted now, so a Refresh would soft-redirect back to a detail page that no
                    // longer resolves and the toast would never show (F5: "the page just sits there"). Stash the
                    // toast, land on the list.
                    CmsActionResultTranslator.stashSuccess(request.request().conduit(),
                        HohenheimMicrocopy.INSTANCE.of("deleted_with_data_toast")
                            .withArg("name", request.subject().get(InstanceModel.NAME)));
                    return CmsActionResult.redirect(new Uri(
                        CmsRoutes.list(request.request().panelSlug(), HohenheimSlugs.INSTANCES).toUrl()));
                })
            .inlineInRow(false)
            // The record-less fallback the dynamic one refines; a dynamic confirmation without it is refused at
            // registration (WriteAffordanceParityTest).
            .confirmation(ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE.of("delete_with_data"),
                HohenheimMicrocopy.INSTANCE.of("delete_with_data_confirm"), ActionStyle.DESTRUCTIVE))
            .dynamicConfirmation(row -> ConfirmationSpec.verb(
                    HohenheimMicrocopy.INSTANCE.of("delete_with_data"), withDataBody(row), ActionStyle.DESTRUCTIVE)
                    .withTypedConfirmation(String.valueOf((Object) row.get(InstanceModel.NAME))))
            .build();
    }

    /**
     * The delete-with-data body, naming the sites the destroy will disable when any do.
     * The typed-name gate on the dialog is unchanged either way.
     */
    private static @NonNull Microcopy withDataBody(@NonNull Row row) {
        String sites = InstanceParts.strandedSites(row);
        Microcopy body = sites == null
            ? HohenheimMicrocopy.INSTANCE.of("delete_with_data_confirm")
            : HohenheimMicrocopy.INSTANCE.of("delete_with_data_confirm_stranding")
                .withArg("sites", sites);
        return body.withArg("name", row.get(InstanceModel.NAME));
    }
}
