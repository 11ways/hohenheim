package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.instance.InstanceTemplateCapture;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
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
 * THE instance row actions, built once for both panels: the operator list and the delegated subset
 * read the same builders, so an action's capability gate and handler exist exactly once.
 *
 * AIDEV-NOTE: every action declares the record capability it needs in its own visibleFor, even
 * though the operator panel is admin-gated. zenit-cms re-checks visibleFor on INVOKE (so the
 * declaration is a gate, not a hint), and the /manage panel offers these very instances -- a
 * capability spelled only for one panel would be a second policy over one action. For an admin
 * the predicate is a no-op: the precedence walk's admin bypass answers first. A row action has no
 * conduit to ask for its panel, so the operator-only ones name {@link HohenheimSlugs#ADMIN}.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class InstanceRowActions {

    private final InstanceService instances;

    InstanceRowActions(@NonNull InstanceService instances) {
        this.instances = instances;
    }

    /**
     * The operator panel's actions, Deploy first: RecordActionBands keeps declaration order
     * inside the inline band, so the first declared verb leads.
     */
    @NonNull List<RowAction<Row>> operator() {
        return List.of(this.deployAction(), this.stopAction(), this.restartAction(),
            this.exposeAction(), this.rollbackAction(), this.installAction(),
            this.reinstallAction(), this.appUpdateAction(), this.snapshotAction(),
            this.backupAction(), this.captureTemplateAction(), this.migrateAction(),
            this.destroyWithDataAction());
    }

    /**
     * The delegated panel's subset: power, the two artifact actions and the in-place app update.
     * Placement, template capture, install and every destroy stay operator acts.
     */
    @NonNull List<RowAction<Row>> delegated() {
        return List.of(this.deployAction(), this.stopAction(), this.snapshotAction(),
            this.backupAction(), this.appUpdateAction());
    }

    /**
     * Deploy, offered only where it means something: the record is not owned by a product
     * tier AND its KIND is one a person may power at all
     * ({@link InstanceKinds#isUserDeployable}, which reads the kind's own
     * {@code generatedOnly()} declaration).
     *
     * AIDEV-NOTE: the kind half is not a restatement of {@code isGenerated(row)}. That one
     * is a per-RECORD fact (generated_by), so it says nothing about a record of an
     * owner-managed kind whose attribution column is unset, and it answers TRUE for a kind
     * that has no handler at all -- a Deploy button whose invoke could only refuse. Asking
     * the kind is what makes the offer and the driver answer to one declaration.
     */
    private @NonNull RowAction<Row> deployAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "deploy_instance"))
            .label(Microcopy.of("deploy").withFilter("scope", "instance"))
            .icon(Icon.of("play"))
            // AIDEV-NOTE: deliberately NOT ActionStyle.PRIMARY (reverted 2026-08-22). The
            // style is what makes a button render FILLED, and on a list row that put a
            // solid accent-coloured Deploy beside the red Delete on every single row --
            // louder than a row deserves. It bought nothing on the record surface either:
            // Deploy is the FIRST action rowActions() declares, and RecordActionBands
            // keeps declaration order inside the inline band, so it already leads.
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row)
                && InstanceKinds.isUserDeployable(row.get(InstanceModel.KIND))
                && HohenheimAccess.reachesRecord(ctx, InstanceModel.MODEL_ID,
                    row.get(InstanceModel.ID), HohenheimAccess.POWER))
            // Offered but DEAD while an attached managed database is not active, with the
            // database and its state on screen: the same resolver InstanceService refuses
            // the POST with, so the button is never the gate.
            .unavailableWhen((row, ctx) -> {
                Integer id = row.get(InstanceModel.ID);
                return id == null ? null : InstanceDatabaseLinks.notReadyReason(id);
            })
            .handler((row, ctx) -> {
                this.instances.deploy(row.get(InstanceModel.ID), DeployTrigger.MANUAL);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("deployed").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * Stop rides the OVERFLOW menu: it is confirmed anyway (so the dialog was always a
     * second click), and keeping it out of the strip leaves ONE inline verb and one
     * red button per row -- the calm row the admin-UI wave promises.
     */
    private @NonNull RowAction<Row> stopAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "stop_instance"))
            .label(Microcopy.of("stop").withFilter("scope", "instance"))
            .icon(Icon.of("stop"))
            .inlineInRow(false)
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row)
                && InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS))
                    && HohenheimAccess.reachesRecord(ctx, InstanceModel.MODEL_ID,
                        row.get(InstanceModel.ID), HohenheimAccess.POWER))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("stop").withFilter("scope", "instance"))
                .body(Microcopy.of("stop_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("stop").withFilter("scope", "instance"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .handler((row, ctx) -> {
                this.instances.stop(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("stopped_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * Stop and start again, through {@link InstanceService#restart} -- the SAME
     * composition the scheduled power action runs, never a UI-side stop-then-deploy pair.
     */
    private @NonNull RowAction<Row> restartAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "restart_instance"))
            .label(Microcopy.of("restart").withFilter("scope", "instance"))
            .icon(Icon.of("rotate-right"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && HohenheimAccess.reachesRecord(ctx,
                InstanceModel.MODEL_ID, row.get(InstanceModel.ID), HohenheimAccess.POWER))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("restart").withFilter("scope", "instance"))
                .body(Microcopy.of("restart_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("restart").withFilter("scope", "instance"))
                .build())
            .handler((row, ctx) -> {
                this.instances.restart(row.get(InstanceModel.ID), DeployTrigger.MANUAL);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("restarted_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * Open the site create form with THIS instance preselected as the upstream: the
     * "give it a hostname" affordance, offered only where the routing tier could
     * actually serve it (the kind declares {@code supportsSiteUpstream}).
     */
    private @NonNull RowAction<Row> exposeAction() {
        return RowAction.Url.<Row>builder(Identifier.of("hohenheim", "expose_instance"))
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
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "rollback_instance"))
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
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "install_instance"))
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
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "reinstall_instance"))
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

    /** In-place app update: the template's update_script runs inside the RUNNING system. */
    private @NonNull RowAction<Row> appUpdateAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "app_update_instance"))
            .label(Microcopy.of("app_update").withFilter("scope", "instance"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && InstanceAppUpdates.hasUpdateScript(row)
                && HohenheimAccess.reachesRecord(ctx, InstanceModel.MODEL_ID,
                    row.get(InstanceModel.ID), HohenheimAccess.CONFIG))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("app_update").withFilter("scope", "instance"))
                .body(Microcopy.of("app_update_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("app_update").withFilter("scope", "instance"))
                .build())
            .handler((row, ctx) -> {
                new InstanceAppUpdates().update(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("app_updated_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
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

    /** Cold capture: a running instance is stopped for the copy and redeployed after. */
    private @NonNull RowAction<Row> snapshotAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "snapshot_instance"))
            .label(Microcopy.of("snapshot").withFilter("scope", "instance"))
            .icon(Icon.of("camera"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && HohenheimAccess.reachesRecord(ctx,
                InstanceModel.MODEL_ID, row.get(InstanceModel.ID), HohenheimAccess.SNAPSHOTS))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("snapshot").withFilter("scope", "instance"))
                .body(Microcopy.of("snapshot_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("snapshot").withFilter("scope", "instance"))
                .build())
            .handler((row, ctx) -> {
                new InstanceSnapshots().create(row.get(InstanceModel.ID), null);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("snapshot_taken").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /** Export to the configured backup target (refuses, named, when none is set). */
    private @NonNull RowAction<Row> backupAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "backup_instance"))
            .label(Microcopy.of("backup_now").withFilter("scope", "instance"))
            .icon(Icon.of("box-archive"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !InstanceResource.isGenerated(row) && HohenheimAccess.reachesRecord(ctx,
                InstanceModel.MODEL_ID, row.get(InstanceModel.ID), HohenheimAccess.BACKUPS))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("backup_now").withFilter("scope", "instance"))
                .body(Microcopy.of("backup_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("backup_now").withFilter("scope", "instance"))
                .build())
            .handler((row, ctx) -> {
                new InstanceBackups().backupNow(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("backup_done").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * Publish this STOPPED instance's state as a prepared template (unapproved), then
     * open the minted template's form. OPERATOR-ONLY and deliberately NOT inherited as
     * an offer by /manage principals: capture mints catalog authority, and the service
     * re-refuses a tenant with the uniform refusal
     * ({@link InstanceTemplateCapture}).
     */
    private @NonNull RowAction<Row> captureTemplateAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "capture_template"))
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
        return RowAction.Url.<Row>builder(Identifier.of("hohenheim", "migrate_instance"))
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
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "destroy_instance_data"))
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
