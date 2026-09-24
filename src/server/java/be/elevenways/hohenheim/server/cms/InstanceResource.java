package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.ManagedByCell;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceDeclarations;
import be.elevenways.hohenheim.server.instance.InstanceExposure;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceResize;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
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
import be.elevenways.zenit.common.orm.query.rules.RuleVocabulary;
import be.elevenways.zenit.common.orm.query.rules.SchemaVocabulary;
import be.elevenways.zenit.common.orm.query.rules.VariableDefinition;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
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
 * AIDEV-NOTE: every row action (built in {@link InstanceRowActions}) declares the record
 * capability it needs in its own visibleFor, even though this panel is admin-gated. Two
 * reasons, both structural: zenit-cms re-checks visibleFor on INVOKE (so the declaration
 * is a gate, not a hint), and the /manage subclass offers the same builders -- a
 * capability spelled only for one panel would be a second policy over one action. For an
 * admin the predicate is a no-op: the precedence walk's admin bypass answers first.
 */
public class InstanceResource extends RowResource {

    /** Virtual column naming the owning product record of a generated row. */
    static final String MANAGED_BY_COLUMN = "managed_by";

    /** The relational host filter's variable key ({@code filterVocabulary()}). */
    static final String HOST_FILTER = "server.name";

    /** This resource's slug on both panels. */
    public static final String SLUG = HohenheimSlugs.INSTANCES;

    protected final InstanceService instances = new InstanceService();

    /** The instance verbs both panels offer, built once over {@link #instances}. */
    final InstanceRowActions rowActionSet = new InstanceRowActions(this.instances);

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
     * CREATE-time placement: an empty host pick is the CHOOSER's answer, never the local
     * daemon by default.
     *
     * AIDEV-NOTE: this surface used to write whatever the picker posted, and an empty pick
     * wrote NULL -- which every reader downstream spells "the local daemon". The pick
     * narrows to hosts that ACCEPT the workload, so on a fresh installation it offers
     * nothing at all and the submit still landed on the blocked local machine: the visible
     * form and the authoritative write disagreed. {@link InstancePlacement#forActor} is THE
     * placement funnel every other create walks, and now this one does too -- it is not a
     * second policy here, only the call that was missing.
     *
     * The UPDATE lane deliberately does not run this: a create DERIVES a host, an edit
     * carries whatever the operator submitted, and re-deriving on save would move a
     * running workload nobody asked to move (moving one is the migrate action, which
     * rebooks the memory it takes).
     */
    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        Map<String, Object> values = CmsSupport.mutable(coerced);
        // ONE pass: the placement answer and the model's own declaration refusals are
        // collected together, so an operator who left the host empty AND mistyped the
        // repository URL reads both sentences on one render instead of one per submit.
        // The write hook re-judges the declarations inside the save (InstanceDeclarations).
        Violations refused = new Violations();
        Integer placed = placedHostFor(values, accessContext, refused);
        refused.addAll(InstanceDeclarations.judge(valuesToRow(values), null));
        if (!refused.isEmpty()) {
            throw refused;
        }
        values.put(InstanceModel.SERVER_ID.getName(), placed);
        return super.persistRow(values, accessContext);
    }

    /**
     * The host this submission lands on, priced and gated exactly like every other create.
     *
     * @param refused where a placement refusal lands, RE-PATHED onto the host entry -- the
     *        same message ({@code no_placement_available} and friends), never a parallel
     *        one: a sentence about the host belongs on the field the operator can see, not
     *        in the form's generic error box
     * @return the host, or null when the placement was refused
     */
    private static @Nullable Integer placedHostFor(@NonNull Map<String, Object> values,
                                                   @NonNull AccessContext accessContext,
                                                   @NonNull Violations refused) {
        Integer requested = values.get(InstanceModel.SERVER_ID.getName()) instanceof Number number
            ? number.intValue() : null;
        InstanceKindHandler handler = InstanceKinds.getHandler(
            values.get(InstanceModel.KIND.getName()) == null ? null
                : String.valueOf(values.get(InstanceModel.KIND.getName())));
        try {
            return InstancePlacement.forActor(accessContext, requested,
                InstancePlacement.Workload.of(handler, submittedSettings(values)));
        } catch (Violations placement) {
            for (Violation refusal : placement.all()) {
                refused.addAll(Violations.ofField(
                    InstanceModel.SERVER_ID.getName(), requested, refusal.message()));
            }
            if (placement.isEmpty()) {
                // A refusal that names no reason is a placement bug, never a host to write:
                // an empty set would refuse nothing and land the instance on a null host.
                throw new IllegalStateException("Placement refused without naming a reason");
            }
            return null;
        }
    }

    /**
     * THE resize: a save that moves {@code memory_limit_mb} or {@code cpu_limit} on a LIVE
     * workload recreates its container, because a cgroup ceiling is stamped at create.
     *
     * AIDEV-NOTE: the DATABASE tier already did this ({@code DatabaseResource.updateRow}),
     * and the instance tier did not -- so an instance resize moved BOTH ledgers (host and
     * owner, through the write hooks) while the daemon kept the old cap, and charge == cap
     * held only after somebody pressed Restart. {@link InstanceResize} owns the decision so
     * a second writer (the automation API, a future bulk edit) can adopt it in one line
     * rather than re-spelling "did the ceilings move".
     *
     * Everything is read off {@code existing} BEFORE super applies the submitted values:
     * afterwards the row IS the new state and the comparison has nothing to compare to.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        Integer instanceId = existing.get(InstanceModel.ID);
        Map<String, Object> before = InstanceResize.settingsOf(existing);
        String storedStatus = existing.get(InstanceModel.STATUS);
        super.updateRow(existing, coerced, accessContext);
        if (instanceId != null) {
            InstanceResize.recreateAfterCommit(instanceId, before,
                InstanceResize.settingsOf(existing), storedStatus);
        }
    }

    /**
     * States the consequence the fields cannot: saving a new memory or CPU ceiling
     * RECREATES the workload's container, so it is briefly down. Rendered only on a stored
     * record -- on the create form there is nothing to recreate.
     */
    @Override
    public @Nullable Microcopy formNotice(@NonNull Row record,
                                          @NonNull AccessContext accessContext) {
        return record.get(InstanceModel.ID) == null ? super.formNotice(record, accessContext)
            : Microcopy.of("resize_notice").withFilter("scope", "instance");
    }

    /** The submitted per-kind settings, which price the workload; a SchemaField answers Object. */
    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> submittedSettings(@NonNull Map<String, Object> values) {
        return values.get(InstanceModel.SETTINGS.getName()) instanceof Map<?, ?> settings
            ? (Map<String, Object>) settings : Map.of();
    }

    /**
     * Soft-deleted rows are invisible; everything else is LISTED, generated rows
     * included -- with a "Managed by" column instead of a hole in the fleet. The one
     * exception is {@code release} rows: one application deploys releases faster than
     * an operator reads a list, they are deploy artifacts rather than things you
     * manage, and their application's Deploys tab is their surface.
     *
     * AIDEV-NOTE: the release exclusion is structural (criteria), deliberately NOT
     * {@code defaultFilterState()}, although zenit-cms has that mechanism now. Two
     * reasons. A default is ESCAPABLE by design (a removable chip), which is right for a
     * view preference and wrong for rows that have their own surface. And this access
     * function is also the SCOPE of the admin instance record source (CmsRecordSources
     * derives its accessCriteria from it), so a default-filter-only exclusion would put
     * release rows into every instance picker of the panel. Because the rows 404 here,
     * nothing may link to one directly: {@link #recordRoute} is the one way a surface
     * links an instance row, and it sends a release to its application's Deploys tab.
     * Kind-filtering on "release" yields the framework's empty state, which states the
     * truth.
     */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(new CompositeCriteria(
            CompositeOperator.AND,
            InstanceModel.DELETED_AT.isNull(),
            InstanceModel.KIND.ne(ReleaseKind.ID.toString()))));
    }

    /**
     * Where a surface links an instance row: its own record (or one of its subpages), and
     * for a release row, which {@link #accessFunction} does not serve, its application's
     * Deploys tab.
     *
     * @param subpage the record subpage to open, or null for the record itself; ignored
     *                for a release row
     * @return the route; the list itself for a release row no application owns
     */
    static @NonNull RouteTarget recordRoute(@NonNull String panel, @NonNull Row instance,
                                            @Nullable String subpage) {
        Integer id = instance.get(InstanceModel.ID);
        if (!ReleaseKind.ID.toString().equals(instance.get(InstanceModel.KIND))) {
            return subpage == null ? CmsRoutes.detail(panel, SLUG, id)
                : CmsRoutes.subpage(panel, SLUG, id, subpage);
        }
        int application = ApplicationReleases.linkOwnerOf(instance);
        return id == null || application == id ? CmsRoutes.list(panel, SLUG)
            : CmsRoutes.subpage(panel, SLUG, application, InstanceDeploymentsPage.SLUG);
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Model model() { return Models.get(InstanceModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.buildFormSpec(); }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }

    /** The schema's variables plus the relational host name the strip filter rides. */
    @Override
    public @NonNull RuleVocabulary filterVocabulary() {
        RuleVocabulary.Builder vocabulary = RuleVocabulary.builder();
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
     * (a row action has no conduit to ask, hence the operator panel -- the
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
            ? CmsRoutes.detail(HohenheimSlugs.ADMIN, ownerResource.slug(), ownerId).toUrl() : null;
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
        Panel panel = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
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

    /**
     * Offered but DEAD without the {@code destroy} capability on this record, carrying the
     * teardown funnel's own refusal: the delete IS
     * {@link InstanceService#destroy}, whose gate a caller who can merely SEE the workload
     * cannot pass, and this is the same resolver it refuses the POST with -- so the button
     * is never the gate and a delegate reads WHY instead of clicking into a 422.
     */
    @Override
    public @Nullable Microcopy deleteUnavailableReason(@NonNull Row record,
                                                       @NonNull AccessContext accessContext) {
        Integer instanceId = record.get(InstanceModel.ID);
        if (instanceId == null) {
            return super.deleteUnavailableReason(record, accessContext);
        }
        Microcopy refused = HohenheimAccess.destroyUnavailableReason(accessContext, instanceId);
        return refused != null ? refused : super.deleteUnavailableReason(record, accessContext);
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
    static @Nullable String strandedSites(@NonNull Row record) {
        Integer instanceId = record.get(InstanceModel.ID);
        if (instanceId == null) {
            return null;
        }
        List<String> names = InstanceExposure.liveSiteNamesExposing(instanceId);
        return names.isEmpty() ? null : String.join(", ", names);
    }

    /** The synthesized affordances, then the operator's instance verbs ({@link InstanceRowActions}). */
    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.addAll(this.rowActionSet.operator());
        return actions;
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
            new InstanceVolumesPage(), new InstanceDatabasesPage(),
            // Operator-only: the page hides AND 404s itself for a delegate, and the
            // /manage resource never lists it at all.
            new InstanceMigratePage()));
        pages.addAll(this.frameworkSubpages());
        return pages;
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
            RelatedPage.toPeer(BackupTargetResource.SLUG),
            RelatedPage.toPeer(InstanceQuotaResource.SLUG),
            RelatedPage.toPeer(GameDomainResource.SLUG),
            RelatedPage.toPeer(BuildOperationResource.SLUG),
            RelatedPage.toPeer(ReleaseOperationResource.SLUG));
    }

}
