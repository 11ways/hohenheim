package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.instance.InstanceTemplateCapture;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The instance tier's admin surface, and the BASE of the grant-scoped /manage
 * projection ({@link ManageInstanceResource}). Create persists the record; deploy,
 * stop and the verified destroy are row actions through {@link InstanceService}.
 *
 * AIDEV-NOTE: every action below declares the record capability it needs in its own
 * visibleFor, even though this panel is admin-gated. Two reasons, both structural:
 * zenit-cms re-checks visibleFor on INVOKE (so the declaration is a gate, not a hint),
 * and the /manage subclass inherits these builders verbatim -- a capability spelled
 * only in the subclass would be a second policy over one action. For an admin the
 * predicate is a no-op: the precedence walk's admin bypass answers first.
 */
public class InstanceResource extends RowResource {

    protected final InstanceService instances = new InstanceService();

    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceModel.NAME)
        // AIDEV-NOTE: the derived entry offers EVERY registered kind, three of which the
        // OwnedInstances write guard can only refuse -- the affordance-that-can-only-refuse
        // shape the device surface documents. Supplied (never a resolved list): registry
        // entries arrive via BlastAutoLoadInit after class-load and tests REPLACE entries at
        // runtime, and a Supplied source still resolves on the context-free coercion path,
        // which is what makes a hand-posted generated-only kind fail at the form layer too.
        // This narrows SELECTION only; every label path reads EnumField.getValues() and
        // still sees all six, so existing generated rows keep rendering their kind.
        .add(Select.of(InstanceModel.KIND)
            .options(OptionSource.supplied(InstanceKinds::authorableOptions))
            .clearable(!Boolean.TRUE.equals(InstanceModel.KIND.getAttribute(FieldAttributes.REQUIRED)))
            .build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
        .add(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build())
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.CRASH_POLICY))
        .add(RelationPick.of(InstanceModel.BACKUP_TARGET_ID, BackupTargetModel.MODEL_ID)
            .clearable(true).build())
        // Grouping, never authority: ProjectGuards refuses an environment whose
        // project does not OWN this instance, on every writer.
        .add(RelationPick.of(InstanceModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
            .clearable(true).build())
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.SERVER_ID)
            .relation(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.INSTALL_STATE).filterable().build())
        .column(ColumnSpec.fromField(InstanceModel.CREATED_AT).filterable().build())
        .filter(FilterSpec.forField(InstanceModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(InstanceModel.NAME)).build())
        .filter(FilterSpec.forField(InstanceModel.KIND, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(InstanceModel.KIND)).build())
        .filter(FilterSpec.forField(InstanceModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(InstanceModel.STATUS)).build())
        .build();

    /** The server pick defaults to the local daemon (ensuring its row exists for the picker). */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = CmsSupport.mutable(formSpec().defaultValues());
        values.put("server_id", ServerModel.localServerId());
        return Map.copyOf(values);
    }

    /**
     * Soft-deleted instances are invisible everywhere, and so are GENERATED ones: an
     * instance a product tier owns (a Docker site's running release) is managed through
     * that record's own surface -- listing it here would be a second UI over the same
     * records, and the GeneratedRows write guard would refuse every action anyway.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(new CompositeCriteria(
            CompositeOperator.AND,
            InstanceModel.DELETED_AT.isNull(),
            InstanceModel.GENERATED_BY.isNull())));
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return "instances"; }
    @Override public @NonNull Model model() { return Models.get(InstanceModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.COMPUTE_GROUP; }
    @Override public int navOrder() { return 10; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "instance");
    }
    @Override public @NonNull Icon icon() { return Icon.of("cube"); }

    /**
     * An instance UPDATE demands {@code CONFIG} on the record
     * ({@code TenantWrites.checkInstanceWrite}), so the synthesized Edit affordance and
     * the detail form's Save are offered on exactly that answer -- the
     * {@link InstanceDeviceResource} shape: {@link ManageInstanceResource} reads by the
     * wider {@code view}, and without this a view-only delegate was shown an editor
     * whose every save the pipeline could only refuse. The boolean twin of the gate,
     * never a second authority.
     */
    @Override
    public boolean updatableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return super.updatableBy(record, accessContext)
            && HohenheimAccess.hasInstanceCapability(accessContext,
                idOf(record), HohenheimAccess.CONFIG);
    }

    private static int idOf(@NonNull Row record) {
        Integer id = record.get(InstanceModel.ID);
        return id != null ? id : -1;
    }

    /**
     * Delete IS the verified destroy: container removed (or observed absent) and port
     * claims released before the record is soft-deleted; an unreachable daemon is a
     * NAMED refusal ({@link InstanceService#destroy}) that keeps the record. Volumes
     * survive by design -- the reconciler surfaces them as orphans for an explicit
     * operator decision.
     */
    @Override
    public void deleteRow(@NonNull Row existing, @NonNull AccessContext accessContext) {
        // No accountability wrapper here: InstanceService.destroy owns it now, so the
        // release engine, preview expiry and database teardown record the same verb.
        this.instances.destroy(existing.get(InstanceModel.ID));
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.deployAction());
        actions.add(this.stopAction());
        actions.add(this.restartAction());
        actions.add(this.installAction());
        actions.add(this.reinstallAction());
        actions.add(this.appUpdateAction());
        actions.add(this.snapshotAction());
        actions.add(this.backupAction());
        actions.add(this.captureTemplateAction());
        actions.add(this.migrateAction());
        return actions;
    }

    /**
     * Open the migrate tab. A LINK, not an invoke, because the destination is an operator
     * choice the page makes -- and deliberately NOT inherited by
     * {@link ManageInstanceResource}, whose rowActions() names its own list: placement is
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
            .inlineInRow(false)
            .description(Microcopy.of("migrate_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> HohenheimAccess.isAdmin(ctx))
            // RowAction.Url is Uri-typed, so the typed target is rendered here. The panel
            // slug is the literal "admin" this action already produced (a row action has
            // no conduit to ask), so the URL does not move.
            .url(row -> new Uri(CmsRoutes.subpage("admin", this.slug(),
                row.get(InstanceModel.ID), InstanceMigratePage.SLUG).toUrl()))
            .build();
    }

    /**
     * The record's front door is the OVERVIEW, not the edit form: the form edits five
     * columns, the overview is where an operator sees state, power, disk and endpoint.
     * The synthesized edit action still points at the form.
     *
     * AIDEV-NOTE: this replaced a {@code rowUrl} override spelling
     * {@code CmsRoutes.subpage("admin", ...)}, which baked the PANEL SLUG into the
     * resource and is why the delegated panel needed its own copy. The framework now
     * derives the tab order, the row title link and the create landing per panel.
     */
    @Override
    public @Nullable String landingSubpage() {
        return InstanceOverviewPage.SLUG;
    }

    @Override
    public @NonNull List<RecordScopedPage<Row>> subpages() {
        List<RecordScopedPage<Row>> pages = new ArrayList<>(List.of(
            new InstanceOverviewPage(this),
            new InstanceConsolePage(), new InstanceFramebufferPage(),
            new InstanceProvisioningPage(),
            new InstanceFilesPage(), new InstanceStatsPage(),
            // The exec tab hides AND 404s itself for anyone without the exec capability
            // on the record (InstanceExecPage.visibleFor); the admin panel gate is not
            // the only thing standing between a delegate and an arbitrary command.
            new InstanceExecPage(),
            new InstanceSnapshotsPage(new InstanceSnapshotResource()),
            new InstanceBackupsPage(new InstanceBackupResource()),
            new InstanceSchedulesPage(), new InstanceDevicesPage(),
            // Operator-only: the page hides AND 404s itself for a delegate, and the
            // /manage resource never lists it at all.
            new InstanceMigratePage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
    }

    /** Run (or resume/retry) the template's install step. */
    private @NonNull RowAction<Row> installAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "install_instance"))
            .label(Microcopy.of("install").withFilter("scope", "instance"))
            .icon(Icon.of("wand-magic-sparkles"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> row.get(InstanceModel.TEMPLATE_ID) != null
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
            .inlineInRow(false)
            .visibleFor((row, ctx) -> row.get(InstanceModel.TEMPLATE_ID) != null
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
    protected @NonNull RowAction<Row> appUpdateAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "app_update_instance"))
            .label(Microcopy.of("app_update").withFilter("scope", "instance"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> InstanceAppUpdates.hasUpdateScript(row)
                && HohenheimAccess.hasInstanceCapability(
                    ctx, row.get(InstanceModel.ID), HohenheimAccess.CONFIG))
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
    protected @NonNull RowAction<Row> snapshotAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "snapshot_instance"))
            .label(Microcopy.of("snapshot").withFilter("scope", "instance"))
            .icon(Icon.of("camera"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> HohenheimAccess.hasInstanceCapability(
                ctx, row.get(InstanceModel.ID), HohenheimAccess.SNAPSHOTS))
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
    protected @NonNull RowAction<Row> backupAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "backup_instance"))
            .label(Microcopy.of("backup_now").withFilter("scope", "instance"))
            .icon(Icon.of("box-archive"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> HohenheimAccess.hasInstanceCapability(
                ctx, row.get(InstanceModel.ID), HohenheimAccess.BACKUPS))
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
            .inlineInRow(false)
            .description(Microcopy.of("capture_template_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> HohenheimAccess.isAdmin(ctx)
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
                // The panel slug is the literal "admin" for the migrateAction reason:
                // a row action has no conduit to ask, and this action is admin-only.
                return CmsActionResult.redirect(new Uri(CmsRoutes.detail("admin",
                    "instance-templates", templateId).toUrl()));
            })
            .build();
    }

    private static boolean supportsCapture(@NonNull Row row) {
        InstanceKindHandler handler = InstanceKinds.getHandler(row.get(InstanceModel.KIND));
        return handler != null && handler.supportsTemplateCapture();
    }

    protected @NonNull RowAction<Row> deployAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "deploy_instance"))
            .label(Microcopy.of("deploy").withFilter("scope", "instance"))
            .icon(Icon.of("play"))
            .visibleFor((row, ctx) -> HohenheimAccess.hasInstanceCapability(
                ctx, row.get(InstanceModel.ID), HohenheimAccess.POWER))
            .handler((row, ctx) -> {
                this.instances.deploy(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("deployed").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * Stop and start again, through {@link InstanceService#restart} -- the SAME
     * composition the scheduled power action runs, never a UI-side stop-then-deploy pair.
     */
    protected @NonNull RowAction<Row> restartAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "restart_instance"))
            .label(Microcopy.of("restart").withFilter("scope", "instance"))
            .icon(Icon.of("rotate-right"))
            .inlineInRow(false)
            .visibleFor((row, ctx) -> HohenheimAccess.hasInstanceCapability(
                ctx, row.get(InstanceModel.ID), HohenheimAccess.POWER))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("restart").withFilter("scope", "instance"))
                .body(Microcopy.of("restart_confirm").withFilter("scope", "instance"))
                .confirmLabel(Microcopy.of("restart").withFilter("scope", "instance"))
                .build())
            .handler((row, ctx) -> {
                this.instances.restart(row.get(InstanceModel.ID));
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("restarted_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    protected @NonNull RowAction<Row> stopAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "stop_instance"))
            .label(Microcopy.of("stop").withFilter("scope", "instance"))
            .icon(Icon.of("stop"))
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) ->
                InstanceModel.STATUS_RUNNING.equals(row.get(InstanceModel.STATUS))
                    && HohenheimAccess.hasInstanceCapability(
                        ctx, row.get(InstanceModel.ID), HohenheimAccess.POWER))
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
     * The instance tier's sibling catalogs, demoted out of the sidebar: where backups are
     * written, who may run how many instances, and which public names route to which workload.
     */
    @Override
    public @NonNull List<HeaderAction> headerActions() {
        List<HeaderAction> actions = new ArrayList<>(super.headerActions());
        actions.addAll(List.of(
            CmsSupport.relatedList("backup_targets_link", "backup-targets", "backup_target", Icon.of("box-archive")),
            CmsSupport.relatedList("instance_quotas_link", "instance-quotas", "instance_quota", Icon.of("gauge")),
            CmsSupport.relatedList("game_domains_link", "game-domains", "game_domain", Icon.of("gamepad"))));
        return actions;
    }

}
