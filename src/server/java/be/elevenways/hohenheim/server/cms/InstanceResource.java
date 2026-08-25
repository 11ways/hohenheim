package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.instance.ManagedByCell;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceAppUpdates;
import be.elevenways.hohenheim.server.instance.InstanceBackups;
import be.elevenways.hohenheim.server.instance.InstanceExposure;
import be.elevenways.hohenheim.server.instance.InstanceInstalls;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceSnapshots;
import be.elevenways.hohenheim.server.instance.InstanceTemplateCapture;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.upstream.kinds.InstanceUpstreamKind;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.Resource;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.edit.FormSection;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.OptionSource;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.edit.Select;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.CompositeCriteria;
import be.elevenways.zenit.common.orm.query.criteria.CompositeOperator;
import be.elevenways.zenit.common.orm.query.rules.RelationRules;
import be.elevenways.zenit.common.orm.query.rules.SchemaVocabulary;
import be.elevenways.zenit.common.orm.query.rules.VariableDefinition;
import be.elevenways.zenit.common.orm.query.rules.Vocabulary;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** Virtual column naming the owning product record of a generated row. */
    static final String MANAGED_BY_COLUMN = "managed_by";

    /** The relational host filter's variable key ({@code filterVocabulary()}). */
    static final String HOST_FILTER = "server.name";

    protected final InstanceService instances = new InstanceService();

    /**
     * The create/edit form: choice cards decide the kind, and every placement pick
     * NARROWS from it live (host by supported runtime + volume backend, template and
     * runtime image by kind) -- the dependent-pick mechanism, never a page of
     * always-appropriate dropdowns.
     *
     * AIDEV-NOTE: built PER CALL, not cached in a field, because the resolvers carry
     * SNAPSHOTS of the kind registry's declarations (runtimesByKind and friends) and
     * tests replace registry entries at runtime -- the same staleness the Supplied
     * option source below exists to avoid. Structural resolver equality makes the
     * rebuild a reactive no-op for the browser.
     */
    private @NonNull FormSpec buildFormSpec() {
        return FormSpec.builder()
            // AIDEV-NOTE: a form page falls back to the RESOURCE label for its heading
            // (zenit-cms ResourceFormPageRenderer.resolvePageTitle), and this resource's
            // label is the PLURAL the nav needs -- so the create screen was headed
            // "Instances" while it created exactly one. createTitle/editTitle are the only
            // seam the framework offers; there is no singular label and no derivation, so
            // every resource that wants an honest heading has to spell one here.
            .createTitle(Microcopy.of("create_title").withFilter("scope", "instance"))
            .editTitle(Microcopy.of("edit_title").withFilter("scope", "instance"))
            // AIDEV-NOTE: the kind entry offers only what a human may author (the
            // generated kinds are refused by OwnedInstances anyway -- an option that can
            // only refuse is the affordance shape this panel bans). Supplied (never a
            // resolved list): registry entries arrive via BlastAutoLoadInit after
            // class-load and tests REPLACE entries at runtime, and a Supplied source
            // still resolves on the context-free coercion path, which is what makes a
            // hand-posted generated-only kind fail at the form layer too. This narrows
            // SELECTION only; every label path reads EnumField.getValues() and still
            // sees the whole registry, so existing generated rows keep their label.
            .add(Select.of(InstanceModel.KIND)
                .options(OptionSource.supplied(InstanceKinds::authorableOptions))
                .presentation(Select.Presentation.CARDS)
                .clearable(!Boolean.TRUE.equals(InstanceModel.KIND.getAttribute(FieldAttributes.REQUIRED)))
                .build())
            .add(InstanceModel.NAME)
            // A host is admitted, preflighted and trusted before it can carry anything,
            // so it is never created from inside another record's form -- and the offer
            // follows the kind: only hosts whose runtime the kind supports, and (for
            // volume-mounting kinds) whose data root can enforce quotas. The submit is
            // re-narrowed server-side (relation_out_of_scope), the picker is never the gate.
            .add(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID)
                .creatable(false)
                .rulesFromSiblings(new HohenheimPickRules.KindHostRules(
                    InstanceModel.KIND.getName(),
                    InstanceKinds.runtimesByKind(),
                    InstanceKinds.kindsWhere(InstanceKindHandler::supportsVolumes)), "kind")
                .build())
            // The runtime image ("yolk") only offers images to kinds that run inside one;
            // for every other kind the narrowing resolves to nothing and SAYS SO (the
            // resolver's EmptyNarrowingReason), because that kind's deploy never reads
            // the column and a choice the deploy ignores is worse than no choice.
            // clearable stays TRUE because most kinds have no image to name; what makes
            // it REQUIRED for the kinds that do is InstanceDeclarations, on the write, so
            // the refusal lands on this form instead of on a deploy hours later.
            .add(RelationPick.of(InstanceModel.RUNTIME_IMAGE_ID, RuntimeImageModel.MODEL_ID)
                .creatable(false).clearable(true)
                .rulesFromSiblings(new HohenheimPickRules.RuntimeImageRules(
                    InstanceModel.KIND.getName(),
                    InstanceKinds.kindsWhere(InstanceKindHandler::usesRuntimeImage),
                    InstanceKinds.kindsWhere(handler ->
                        !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER))), "kind")
                .build())
            // AIDEV-NOTE: deliberately NO template pick here. TEMPLATE_ID is only ever
            // stamped by InstanceTemplates.createFromTemplate, which coerces the
            // template's variables and arms the install lifecycle; a bare FK pick on
            // this form would mint a template-linked record with none of that -- the
            // half-templated silent-success shape. Creating from a template stays the
            // template page's own flow (InstanceFromTemplatePage).
            // Grouping, never authority: ProjectGuards refuses an environment whose
            // project does not OWN this instance, on every writer.
            .add(RelationPick.of(InstanceModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
                .clearable(true).build())
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.CRASH_POLICY))
            .add(RelationPick.of(InstanceModel.BACKUP_TARGET_ID, BackupTargetModel.MODEL_ID)
                .clearable(true).build())
            // What a person DECIDES when creating an instance is the kind, its name, where
            // it runs and what it runs inside; the failure policy and where its backups go
            // are answers this installation already has. They fold, they still post, and a
            // refusal on one forces the section back open.
            //
            // AIDEV-NOTE: this section can only name TOP-LEVEL entries. The long tail of
            // this form is the per-kind SETTINGS sub-form, whose spec is DERIVED from the
            // kind's Schema, and it folds through the schema-declared sections each kind
            // adds (Schema.addSection, carried onto the derived spec by
            // FieldFormEntryRegistry.deriveSpec) -- see HohenheimFormSections. Never fold
            // a field by hiding it with visibleIn: that makes it unwritable, which is a
            // different and worse thing than a disclosure.
            .section(FormSection.advanced(
                InstanceModel.CRASH_POLICY.getName(),
                InstanceModel.BACKUP_TARGET_ID.getName()))
            .build();
    }

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The kind qualifies the name and the install state qualifies the status; both
        // stay declared (and filterable) so the picker and the filter strip keep them.
        .column(ColumnSpec.fromField(InstanceModel.NAME).filterable().subtext("kind").build())
        .column(ColumnSpec.fromField(InstanceModel.KIND).filterable().hidden().build())
        .column(ColumnSpec.fromField(InstanceModel.SERVER_ID)
            .relation(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
        .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().subtext("install_state").build())
        .column(ColumnSpec.fromField(InstanceModel.INSTALL_STATE).filterable().hidden().build())
        // Who runs this record: blank for an authored row, the owning product record
        // (linked) for a generated one -- the honesty column that lets generated rows
        // appear here at all without becoming a second UI over their owner.
        .column(ColumnSpec.virtual(MANAGED_BY_COLUMN,
                Microcopy.of("managed_by").withFilter("scope", "instance"))
            .renderer("hohenheim:cms/cell/managed-by").build())
        .column(ColumnSpec.fromField(InstanceModel.CREATED_AT).filterable().hidden().build())
        .filter(FilterSpec.forField(InstanceModel.NAME, FilterSpec.Kind.TEXT)
            .label(FieldLabels.labelFor(InstanceModel.NAME)).build())
        .filter(FilterSpec.forField(InstanceModel.KIND, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(InstanceModel.KIND)).build())
        .filter(FilterSpec.forField(InstanceModel.STATUS, FilterSpec.Kind.SELECT)
            .label(FieldLabels.labelFor(InstanceModel.STATUS)).build())
        // Relational host filter: "the instances on daystrom" is a typed host name,
        // riding the server.name variable filterVocabulary() declares below.
        .filter(FilterSpec.global(HOST_FILTER, FieldLabels.labelFor(InstanceModel.SERVER_ID),
            FilterSpec.Kind.TEXT).build())
        .build();

    // AIDEV-NOTE: no server_id prefill anymore, ON PURPOSE. The host pick narrows from
    // the chosen kind, and a narrowing CHANGE clears the selection (the dependent-pick
    // contract), so a prefilled local daemon was wiped by the first card click anyway --
    // a default that survives only when you never choose anything is noise, not help.

    /**
     * Soft-deleted rows are invisible; everything else is LISTED, generated rows
     * included -- with a "Managed by" column instead of a hole in the fleet. The one
     * exception is {@code release} rows: one application deploys releases faster than
     * an operator reads a list, they are deploy artifacts rather than things you
     * manage, and their application's Deploys tab is their surface.
     *
     * AIDEV-NOTE: the release exclusion is structural (criteria), not a default filter
     * value -- the list layer has no declared-default-filter mechanism, and an excluded
     * row set with its own dedicated surface is the honest shape. Kind-filtering on
     * "release" yields the framework's empty state, which states the truth.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(new CompositeCriteria(
            CompositeOperator.AND,
            InstanceModel.DELETED_AT.isNull(),
            InstanceModel.KIND.ne(ReleaseKind.ID.toString()))));
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return "instances"; }
    @Override public @NonNull Model model() { return Models.get(InstanceModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.buildFormSpec(); }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** The schema's variables plus the relational host name the strip filter rides. */
    @Override
    public @NonNull Vocabulary filterVocabulary() {
        Vocabulary.Builder vocabulary = Vocabulary.builder();
        for (VariableDefinition definition : SchemaVocabulary.of(this.model()).definitions()) {
            vocabulary.add(definition);
        }
        vocabulary.add(RelationRules.define(InstanceModel.SERVER, ServerModel.NAME)
            .label(FieldLabels.labelFor(InstanceModel.SERVER_ID)));
        return vocabulary.build();
    }

    /** {@link #MANAGED_BY_COLUMN} resolves the owning product record; the rest is fields. */
    @Override
    public @Nullable Object cellValue(@NonNull Row row, @NonNull ColumnSpec column) {
        if (MANAGED_BY_COLUMN.equals(column.name())) {
            return managedByCellOf(row);
        }
        return super.cellValue(row, column);
    }

    /**
     * Blank the status column's install subtext for a record with no install lifecycle.
     *
     * AIDEV-NOTE: the PRESENTATION seam, deliberately not {@link #cellValue}: that one is
     * the DATA value (the raw stored key filters, sort and the copy chip read), while
     * rowCells is asked once per RENDERED row and is what the composite cell reads both
     * halves out of. A null second half renders nothing at all, so a record whose install
     * state says nothing shows only its status pill. The column itself stays declared and
     * filterable -- this hides a member's badge, never the vocabulary.
     */
    @Override
    public @NonNull Map<String, Object> rowCells(TableView.Applied<Row> applied, @NonNull Row row) {

        Map<String, Object> cells = super.rowCells(applied, row);
        String column = InstanceModel.INSTALL_STATE.getName();

        if (!cells.containsKey(column)
                || InstanceModel.isNotableInstallState(cells.get(column))) {
            return cells;
        }

        Map<String, Object> quiet = new LinkedHashMap<>(cells);
        quiet.put(column, null);
        return Collections.unmodifiableMap(quiet);
    }

    /**
     * The owning record of a generated row as a linked cell; null (blank) for authored
     * rows. The owning resource is FOUND on the admin panel by its model id, so the
     * label, icon and slug can never drift from the resource that actually serves it
     * (a row action has no conduit to ask, hence the literal "admin" -- the
     * migrateAction precedent).
     */
    static @Nullable ManagedByCell managedByCellOf(@NonNull Row row) {
        if (row.get(InstanceModel.GENERATED_BY) == null) {
            return null;
        }
        String modelId = row.get(InstanceModel.GENERATED_FOR_MODEL);
        Integer ownerId = row.get(InstanceModel.GENERATED_FOR_ID);
        if (modelId == null || ownerId == null) {
            return null;
        }
        Identifier ownerModelId = Identifier.tryParse(modelId);
        Model ownerModel = ownerModelId != null ? Models.get(ownerModelId) : null;
        Row owner = ownerModel != null ? ownerModel.findById(ownerId) : null;
        if (ownerModel == null || owner == null) {
            // The owner is gone (or unknown): state the raw attribution instead of a link.
            return new ManagedByCell(null,
                Microcopy.of("managed_by").withFilter("scope", "instance"),
                modelId + " #" + ownerId, null);
        }
        Resource<?> ownerResource = adminResourceForModel(ownerModel.getModelId());

        String url = ownerResource != null
            ? CmsRoutes.detail("admin", ownerResource.slug(), ownerId).toUrl() : null;
        String name = ownerModel.getDisplayTitle(owner);
        return new ManagedByCell(
            ownerResource != null ? ownerResource.icon().name() : null,
            ownerResource != null ? ownerResource.label()
                : Microcopy.of("managed_by").withFilter("scope", "instance"),
            name != null ? name : modelId + " #" + ownerId,
            url);
    }

    /** The admin panel's resource over a model, or null when none serves it. */
    private static @Nullable Resource<?> adminResourceForModel(@NonNull Identifier modelId) {
        Panel panel = PanelRegistry.getBySlug("admin");
        if (panel == null) {
            return null;
        }
        for (PanelPeer peer : panel.peers()) {
            if (peer instanceof Resource<?> resource && resource.model() != null
                    && resource.model().getModelId().equals(modelId)) {
                return resource;
            }
        }
        return null;
    }
    /**
     * Same shape as sites: an instance fleet is the other list that outgrows a filter row,
     * so it keeps both the rule builder and per-operator saved views.
     */
    @Override public @NonNull ListChrome listChrome() { return ListChrome.DEFAULT; }

    /** The name is the only text an instance carries; everything else is structure. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceModel.NAME);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 30; }

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
     *
     * AIDEV-NOTE: reachesRecord, never hasInstanceCapability -- this and every
     * {@code visibleFor} below run once per RENDERED ROW, and the un-memoized walk was
     * SEVEN grant-store round trips per instance row. The fresh walk stays for the write
     * gates that look identical (TenantWrites, InstanceScheduleResource.requireManage):
     * the memo deliberately does not see a grant written earlier in the same request.
     */
    @Override
    public boolean updatableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        // A GENERATED row is read-only here whoever asks: OwnedInstances refuses every
        // write outside the owning tier's system scope, so an editable form could only
        // collect refusals. The owning record's own surface is where it is managed.
        return record.get(InstanceModel.GENERATED_BY) == null
            && super.updatableBy(record, accessContext)
            && HohenheimAccess.reachesRecord(accessContext, InstanceModel.MODEL_ID,
                record.get(InstanceModel.ID), HohenheimAccess.CONFIG);
    }

    /** Generated rows are destroyed by their owning tier, never from this list. */
    @Override
    public boolean deletableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return record.get(InstanceModel.GENERATED_BY) == null
            && super.deletableBy(record, accessContext);
    }

    /** Whether this row's lifecycle belongs to a product tier rather than an operator. */
    static boolean isGenerated(@NonNull Row row) {
        return row.get(InstanceModel.GENERATED_BY) != null;
    }

    /**
     * The label and the crash policy -- the two answers that describe an instance without
     * moving it or changing what it runs.
     *
     * AIDEV-NOTE: a rename is capacity-NEUTRAL ({@code InstanceCapacity} books memory per
     * record, never per name), which is why it is the one text field safe to retype in a
     * list of live workloads. KIND is excluded because switching it NULLS
     * IMAGE_FINGERPRINT -- the record would stop knowing what is actually running.
     * SERVER_ID is excluded because it REBOOKS host memory: a placement decision is not a
     * cell edit, and it is a RelationPick outside the compact subset anyway. SETTINGS is
     * the dynamic sub-form (image, command, environment) and can never be one cell.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceModel.NAME, InstanceModel.CRASH_POLICY);
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

    /**
     * The generic delete dialog, with the one fact an operator must know before
     * clicking: this removes the workload but KEEPS its data (the separate
     * delete-with-data action is the one that does not).
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmation() {
        return deleteConfirmation(
            Microcopy.of("delete_confirm").withFilter("scope", "instance"));
    }

    /**
     * The same dialog for ONE record, NAMING the sites the destroy will disable -- the
     * second fact an operator must know, and one that only exists per record.
     *
     * AIDEV-NOTE: the wording lives here and the guarantee lives in
     * {@code InstanceExposure.disableForDestroyedInstance}, called by
     * {@code InstanceService.destroy}. A non-UI caller (the API, the release engine)
     * never sees this text and still gets the disable; a surface that only WARNED would
     * have left the 503 in place for everything but the button.
     */
    @Override
    public @NonNull ConfirmationSpec deleteConfirmationFor(@NonNull Row record) {
        String sites = strandedSites(record);
        return sites == null ? deleteConfirmation()
            : deleteConfirmation(Microcopy.of("delete_confirm_stranding")
                .withFilter("scope", "instance").withArg("sites", sites));
    }

    /**
     * The live sites this record is the upstream of, joined for a dialog.
     *
     * @return null when nothing exposes it, so the caller keeps the generic wording
     */
    private static @Nullable String strandedSites(@NonNull Row record) {
        Integer instanceId = record.get(InstanceModel.ID);
        if (instanceId == null) {
            return null;
        }
        List<String> names = InstanceExposure.liveSiteNamesExposing(instanceId);
        return names.isEmpty() ? null : String.join(", ", names);
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.deployAction());
        actions.add(this.stopAction());
        actions.add(this.restartAction());
        actions.add(this.exposeAction());
        actions.add(this.rollbackAction());
        actions.add(this.installAction());
        actions.add(this.reinstallAction());
        actions.add(this.appUpdateAction());
        actions.add(this.snapshotAction());
        actions.add(this.backupAction());
        actions.add(this.captureTemplateAction());
        actions.add(this.migrateAction());
        actions.add(this.destroyWithDataAction());
        return actions;
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
            .visibleFor((row, ctx) -> !isGenerated(row) && supportsSiteUpstream(row)
                && HohenheimAccess.isAdmin(ctx))
            // The literal "admin" panel for the migrateAction reason (no conduit here),
            // and this action is admin-only: a site create is an operator act.
            .url(row -> new Uri(CmsEndpoints.CREATE_FORM
                .with(CmsEndpoints.PANEL_PARAM, "admin")
                .with(CmsEndpoints.RESOURCE_PARAM, "sites")
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
            .visibleFor((row, ctx) -> !isGenerated(row)
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
    static boolean supportsSiteUpstream(@NonNull Row row) {
        InstanceKindHandler handler = InstanceKinds.getHandler(row.get(InstanceModel.KIND));
        return handler != null && handler.supportsSiteUpstream();
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
            .visibleFor((row, ctx) -> !isGenerated(row) && HohenheimAccess.isAdmin(ctx))
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
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("deleted_with_data_toast").withFilter("scope", "instance")
                        .withArg("name", row.get(InstanceModel.NAME)));
            })
            .build();
    }

    /**
     * The delete-with-data body, naming the sites the destroy will disable when any do.
     * The typed-name gate on the dialog is unchanged either way.
     */
    private static @NonNull Microcopy withDataBody(@NonNull Row row) {
        String sites = strandedSites(row);
        Microcopy body = sites == null
            ? Microcopy.of("delete_with_data_confirm").withFilter("scope", "instance")
            : Microcopy.of("delete_with_data_confirm_stranding")
                .withFilter("scope", "instance").withArg("sites", sites);
        return body.withArg("name", row.get(InstanceModel.NAME));
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
            .inlineOnRecord(false)
            .inlineInRow(false)
            .description(Microcopy.of("migrate_hint").withFilter("scope", "instance"))
            .visibleFor((row, ctx) -> !isGenerated(row) && HohenheimAccess.isAdmin(ctx))
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
            new InstanceDeploymentsPage(),
            new InstanceFilesPage(), new InstanceStatsPage(),
            // The exec tab hides AND 404s itself for anyone without the exec capability
            // on the record (InstanceExecPage.visibleFor); the admin panel gate is not
            // the only thing standing between a delegate and an arbitrary command.
            new InstanceExecPage(),
            // The interactive shell tab hides AND 404s itself without the `shell`
            // capability on the record (InstanceShellPage.visibleFor). It is NOT the exec
            // tab under another name: exec is admin-only and single-shot, this is a
            // delegable tenant verb bounded to a workload that runs as a non-root uid.
            new InstanceShellPage(),
            new InstanceSnapshotsPage(new InstanceSnapshotResource()),
            new InstanceBackupsPage(new InstanceBackupResource()),
            new InstanceSchedulesPage(), new InstanceDevicesPage(),
            new InstanceVolumesPage(),
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
            .visibleFor((row, ctx) -> !isGenerated(row)
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
            .visibleFor((row, ctx) -> !isGenerated(row)
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
    protected @NonNull RowAction<Row> appUpdateAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "app_update_instance"))
            .label(Microcopy.of("app_update").withFilter("scope", "instance"))
            .icon(Icon.of("arrow-up-from-bracket"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !isGenerated(row) && InstanceAppUpdates.hasUpdateScript(row)
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
    protected @NonNull RowAction<Row> snapshotAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "snapshot_instance"))
            .label(Microcopy.of("snapshot").withFilter("scope", "instance"))
            .icon(Icon.of("camera"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !isGenerated(row) && HohenheimAccess.reachesRecord(ctx,
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
    protected @NonNull RowAction<Row> backupAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "backup_instance"))
            .label(Microcopy.of("backup_now").withFilter("scope", "instance"))
            .icon(Icon.of("box-archive"))
            .inlineOnRecord(false)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> !isGenerated(row) && HohenheimAccess.reachesRecord(ctx,
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
            .visibleFor((row, ctx) -> !isGenerated(row) && HohenheimAccess.isAdmin(ctx)
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
    protected @NonNull RowAction<Row> deployAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "deploy_instance"))
            .label(Microcopy.of("deploy").withFilter("scope", "instance"))
            .icon(Icon.of("play"))
            // AIDEV-NOTE: deliberately NOT ActionStyle.PRIMARY (reverted 2026-08-22). The
            // style is what makes a button render FILLED, and on a list row that put a
            // solid accent-coloured Deploy beside the red Delete on every single row --
            // louder than a row deserves. It bought nothing on the record surface either:
            // Deploy is the FIRST action rowActions() declares, and RecordActionBands
            // keeps declaration order inside the inline band, so it already leads.
            .visibleFor((row, ctx) -> !isGenerated(row)
                && InstanceKinds.isUserDeployable(row.get(InstanceModel.KIND))
                && HohenheimAccess.reachesRecord(ctx, InstanceModel.MODEL_ID,
                    row.get(InstanceModel.ID), HohenheimAccess.POWER))
            .handler((row, ctx) -> {
                this.instances.deploy(row.get(InstanceModel.ID), DeployTrigger.MANUAL);
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
            .visibleFor((row, ctx) -> !isGenerated(row) && HohenheimAccess.reachesRecord(ctx,
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
     * Stop rides the OVERFLOW menu: it is confirmed anyway (so the dialog was always a
     * second click), and keeping it out of the strip leaves ONE inline verb and one
     * red button per row -- the calm row the admin-UI wave promises.
     */
    protected @NonNull RowAction<Row> stopAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "stop_instance"))
            .label(Microcopy.of("stop").withFilter("scope", "instance"))
            .icon(Icon.of("stop"))
            .inlineInRow(false)
            .style(ActionStyle.DESTRUCTIVE)
            .visibleFor((row, ctx) -> !isGenerated(row)
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
     * The instance tier's sibling catalogs, demoted out of the sidebar: where backups are
     * written, who may run how many instances, which public names route to which workload,
     * and the build/release history the tier produces.
     *
     * AIDEV-NOTE: these are peers, not verbs, so they are DECLARED as related pages and
     * rendered in the list toolbar's one quiet overflow -- never pushed through
     * HeaderAction.Url into the title bar, which is what this replaced. Each entry keeps
     * the TARGET peer's own label, icon and description, so a demoted peer still has
     * exactly one name.
     */
    @Override
    public @NonNull List<RelatedPage> relatedPages() {
        return List.of(
            RelatedPage.toPeer("backup-targets"),
            RelatedPage.toPeer("instance-quotas"),
            RelatedPage.toPeer("game-domains"),
            RelatedPage.toPeer("builds"),
            RelatedPage.toPeer("releases"));
    }

}
