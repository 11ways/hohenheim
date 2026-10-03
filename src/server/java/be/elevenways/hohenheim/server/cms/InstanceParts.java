package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.ManagedByCell;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceDeclarations;
import be.elevenways.hohenheim.server.instance.InstanceExposure;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceResize;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.FilterSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldAccess;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The instance tier's shared parts, and the admin instance resource and its /manage twin built from them: the
 * kind-led create form with its placement and declarations, the resize on save, the fleet list with its "Managed by"
 * column, the verified destroy as the delete, the placed instance verbs ({@link InstanceActions}) and the record tabs.
 *
 * AIDEV-NOTE: the /manage twin is a NARROWING, never a gate of its own: its rows are the instances the caller holds a
 * grant on ({@link TenantScopes#INSTANCES}), its form edits the name and the crash policy, it creates and deletes
 * nothing; the operations' gates and the model's write hooks judge every writer alike.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceParts {

    /** This entry's slug on both panels. */
    public static final String SLUG = HohenheimSlugs.INSTANCES;

    /** Virtual column naming the owning product record of a generated row. */
    static final String MANAGED_BY_COLUMN = "managed_by";

    /** The relational host filter's variable key. */
    static final String HOST_FILTER = "server.name";

    private InstanceParts() {
    }

    /** @return the operator's instance resource: every live instance, the full form, the verified destroy */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = adminTable();
        return entry("instance")
            // Soft-deleted rows are invisible (the model's soft-delete behaviour hides them from every default find);
            // everything else is LISTED, generated rows included -- with a "Managed by" column instead of a hole in
            // the fleet. The one exception is release rows: one application deploys releases faster than an operator
            // reads a list, and their application's Deploys tab is their surface.
            //
            // AIDEV-NOTE: the release exclusion is structural, deliberately NOT a default filter: a default is
            // escapable by design (a removable chip), and this scope is also the SCOPE of the admin instance record
            // source, so a default-filter-only exclusion would put release rows into every instance picker of the
            // panel. Because the rows 404 here, nothing may link to one directly: recordRoute is the one way a
            // surface links an instance row. Kind-filtering on "release" yields the framework's empty state.
            .scope(RowScope.within(() -> InstanceModel.KIND.ne(ReleaseKind.ID.toString())))
            .reads(ResourceReads.rows().source(source -> source.vocabularyFrom(InstanceParts::filterVocabulary)))
            // Same shape as sites: an instance fleet outgrows a filter row, so it keeps the rule builder and
            // per-operator saved views.
            .list(ResourceList.rows(table)
                .chrome(ListChrome.DEFAULT)
                .facets().ruleFilters()
                // The name is the only text an instance carries; everything else is structure.
                .search(InstanceModel.NAME)
                .computed(Objects.requireNonNull(table.column(MANAGED_BY_COLUMN)),
                    (row, request) -> managedByCellOf(row))
                .build())
            .form(form(createForm()).build())
            .writes(ResourceMutations.rows()
                .create()
                .update()
                .delete(InstanceOperations.DELETE)
                // The verified destroy reaches the host daemon and takes the host lease, which refuses to wait inside
                // a write transaction; a rollback could not undo a removed container anyway.
                .ownsWriteEnvelope(ResourceVerb.DELETE)
                .beforeSave(InstanceParts::beforeSave)
                .build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(deleteBody(null))
                .forRow((instance, request) -> deleteBody(instance)))
            .authority(authority())
            .actions(InstanceActions.placedOperator())
            // AIDEV-NOTE: no contributions, on purpose (decided 2026-10-03): the tab set is DECLARED, so zenit-auth's
            // contributed access tab does not appear on a live product surface as a side effect of this conversion.
            // Whether contributed access tabs belong on Hohenheim entities is an open product question.
            .tabs(ResourceTabs.of(adminTabs()).withHistory())
            // The instance tier's sibling catalogs, demoted out of the sidebar: where backups are written, who may
            // run how many instances, which public names route to which workload, and the build/release history.
            //
            // AIDEV-NOTE: these are peers, not verbs, so they are DECLARED as related pages and rendered in the list
            // toolbar's one quiet overflow; each entry keeps the TARGET peer's own label, icon and description.
            .relatedPages(
                RelatedPage.toPeer(BackupTargetResource.SLUG),
                RelatedPage.toPeer(InstanceQuotaParts.SLUG),
                RelatedPage.toPeer(GameDomainResource.SLUG),
                RelatedPage.toPeer(BuildOperationResource.SLUG),
                RelatedPage.toPeer(ReleaseOperationResource.SLUG))
            .build();
    }

    /**
     * @return the /manage twin: the instances the caller holds a grant on, through two visible columns; their name and
     *         crash policy; no create and no delete
     */
    public static @NonNull PanelResource<Row> manage() {
        return entry("manage_instance")
            // Admins see every live instance; everyone else only the ones the walk confirms view on, which is what
            // makes an unowned id read as MISSING rather than forbidden. Generated (product-tier-owned) instances stay
            // off the delegated surface too: their one UI is the owning record's own page.
            .scope(TenantScopes.INSTANCES)
            // NAV-ONLY (zero granted instances hide the empty list); the route stays scoped.
            //
            // AIDEV-NOTE: reachesAny, not "ids.isEmpty()" -- the walk's whole-model rows (the admin bypass here) cover
            // records that carry no grant, so an id set answers "nothing" for a subject who reaches everything.
            .hasInScopeRecords(access -> HohenheimAccess.reachesAny(access, InstanceModel.MODEL_ID,
                HohenheimAccess.VIEW))
            .reads(ResourceReads.rows())
            // Not the admin fleet list's views and rule builder: a tenant sees their instances through two columns.
            .list(ResourceList.rows(TableSpec.<Row>builder()
                    .column(ColumnSpec.fromField(InstanceModel.NAME).subtext("kind").build())
                    .column(ColumnSpec.fromField(InstanceModel.KIND).hidden().build())
                    .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().subtext("install_state").build())
                    .column(installStateColumn().hidden().build())
                    .build())
                .chrome(ListChrome.MINIMAL)
                .facets().ruleFilters()
                .search(InstanceModel.NAME)
                .build())
            .form(form(FormSpec.builder()
                    .add(InstanceModel.NAME)
                    .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.CRASH_POLICY))
                    .build())
                .bindings(List.of(
                    ResourceFieldBinding.of(InstanceModel.NAME.getName(), FieldAccess.ALWAYS_EDITABLE),
                    ResourceFieldBinding.of(InstanceModel.CRASH_POLICY.getName(), FieldAccess.ALWAYS_EDITABLE)))
                .build())
            .writes(ResourceMutations.rows()
                .update()
                .beforeSave(InstanceParts::beforeSave)
                .build())
            .authority(authority())
            // Power without restart and the two artifact actions, placed operations gated by the record capability.
            .actions(InstanceActions.placedDelegated())
            // The operator tabs a delegate needs, declared like the admin set (no contributions); never the admin
            // history. No related pages: the operator entry names sibling peers of the ADMIN panel, which this panel
            // does not register.
            .tabs(ResourceTabs.of(manageTabs()))
            .build();
    }

    /** The identity, nav placement and labels both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull String id) {
        return PanelResource.builder(HohenheimIds.id(id), SLUG, InstanceOperations.INSTANCE)
            .label(Microcopy.of("plural").withFilter("scope", "instance"))
            .description(Microcopy.of("nav_hint").withFilter("scope", "instance"))
            .icon(Icon.of("cube"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(30);
    }

    /**
     * The form part both twins share: the record's front door is the OVERVIEW, not the edit form (the form edits five
     * columns, the overview is where an operator sees state, power, disk and endpoint), and an edit states what a
     * resize does.
     *
     * AIDEV-NOTE: the label and the crash policy are the two inline-editable answers, because they describe an
     * instance without moving it or changing what it runs: a rename is capacity-NEUTRAL (InstanceCapacity books memory
     * per record, never per name). KIND is excluded because switching it NULLS IMAGE_FINGERPRINT, SERVER_ID because it
     * REBOOKS host memory, SETTINGS because it is the dynamic sub-form.
     */
    private static ResourceForm.@NonNull Builder<Row> form(@NonNull FormSpec spec) {
        return ResourceForm.<Row>of(spec)
            .landingTab(InstanceOverview.SLUG)
            .inlineEditable(InstanceModel.NAME, InstanceModel.CRASH_POLICY)
            // Saving a new memory or CPU ceiling RECREATES the workload's container, so it is briefly down; on the
            // create form there is nothing to recreate.
            .notice((instance, access) -> instance.get(InstanceModel.ID) == null ? null
                : Microcopy.of("resize_notice").withFilter("scope", "instance"));
    }

    /**
     * An UPDATE demands {@code CONFIG} on the record ({@code TenantWrites.checkInstanceWrite}), so the synthesized
     * Edit affordance and the form's Save are offered on exactly that answer: the boolean twin of the gate, never a
     * second authority. A GENERATED row is read-only whoever asks: OwnedInstances refuses every write outside the
     * owning tier's system scope, and the owning record's own surface is where it is managed.
     *
     * AIDEV-NOTE: reachesRecord, never hasInstanceCapability -- this runs once per RENDERED ROW, and the un-memoized
     * walk was SEVEN grant-store round trips per instance row. The fresh walk stays for the write gates that look
     * identical: the memo deliberately does not see a grant written earlier in the same request.
     */
    private static @NonNull ResourceAuthority<Row> authority() {
        return ResourceAuthority.<Row>builder()
            .update(null, (instance, access) -> !isGenerated(instance)
                && HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, instance.get(InstanceModel.ID),
                    HohenheimAccess.CONFIG))
            .build();
    }

    /**
     * The create/edit form: choice cards decide the kind, and every placement pick NARROWS from it live (host by
     * supported runtime + volume backend, runtime image by kind) -- the dependent-pick mechanism.
     *
     * AIDEV-NOTE: the resolvers carry SNAPSHOTS of the kind registry's declarations (runtimesByKind and friends),
     * taken when the panel builds its entry; a test that replaces a kind delegates its facts to the real one, and the
     * kind options themselves are Supplied, so a registry entry arriving after the build still resolves on coercion.
     */
    private static @NonNull FormSpec createForm() {
        return FormSpec.builder()
            // AIDEV-NOTE: a form page falls back to the RESOURCE label for its heading, and this label is the PLURAL
            // the nav needs; createTitle/editTitle are the seam that gives the screen an honest singular heading.
            .createTitle(Microcopy.of("create_title").withFilter("scope", "instance"))
            .editTitle(Microcopy.of("edit_title").withFilter("scope", "instance"))
            // AIDEV-NOTE: the kind entry offers only what a human may author (the generated kinds are refused by
            // OwnedInstances anyway). Supplied, never a resolved list: registry entries arrive via BlastAutoLoadInit
            // after class-load, and a Supplied source still resolves on the context-free coercion path, which is what
            // makes a hand-posted generated-only kind fail at the form layer too. Every label path reads
            // EnumField.getValues() and still sees the whole registry, so existing generated rows keep their label.
            .add(Select.of(InstanceModel.KIND)
                .options(OptionSource.supplied(InstanceKinds::authorableOptions))
                .presentation(Select.Presentation.CARDS)
                .clearable(!Boolean.TRUE.equals(InstanceModel.KIND.getAttribute(FieldAttributes.REQUIRED)))
                .build())
            .add(InstanceModel.NAME)
            // A host is admitted, preflighted and trusted before it can carry anything, so it is never created from
            // inside another record's form -- and the offer follows the kind. The submit is re-narrowed server-side
            // (relation_out_of_scope), the picker is never the gate.
            .add(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID)
                .creatable(false)
                .rulesFromSiblings(new HohenheimPickRules.KindHostRules(
                    InstanceModel.KIND.getName(),
                    InstanceKinds.runtimesByKind(),
                    InstanceKinds.kindsWhere(InstanceKindHandler::supportsVolumes)), "kind")
                .build())
            // The runtime image only offers images to kinds that run inside one; for every other kind the narrowing
            // resolves to nothing and SAYS SO. It stays clearable because most kinds have no image to name; what
            // makes it REQUIRED for the kinds that do is InstanceDeclarations, on the write.
            .add(RelationPick.of(InstanceModel.RUNTIME_IMAGE_ID, RuntimeImageModel.MODEL_ID)
                .creatable(false).clearable(true)
                .rulesFromSiblings(new HohenheimPickRules.RuntimeImageRules(
                    InstanceModel.KIND.getName(),
                    InstanceKinds.kindsWhere(InstanceKindHandler::usesRuntimeImage),
                    InstanceKinds.kindsWhere(handler ->
                        !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER))), "kind")
                .build())
            // AIDEV-NOTE: deliberately NO template pick here. TEMPLATE_ID is only ever stamped by
            // InstanceTemplates.createFromTemplate, which coerces the template's variables and arms the install
            // lifecycle; creating from a template stays the template page's own flow (InstanceFromTemplatePage).
            // Grouping, never authority: ProjectGuards refuses an environment whose project does not OWN this
            // instance, on every writer.
            .add(RelationPick.of(InstanceModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
                .clearable(true).build())
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.CRASH_POLICY))
            .add(RelationPick.of(InstanceModel.BACKUP_TARGET_ID, BackupTargetModel.MODEL_ID)
                .clearable(true).build())
            // What a person DECIDES when creating an instance is the kind, its name, where it runs and what it runs
            // inside; the failure policy and where its backups go are answers this installation already has.
            //
            // AIDEV-NOTE: this section can only name TOP-LEVEL entries; the per-kind SETTINGS sub-form folds through
            // the schema-declared sections each kind adds (HohenheimFormSections). Never fold a field by hiding it
            // with visibleIn: that makes it unwritable.
            .section(FormSection.advanced(
                InstanceModel.CRASH_POLICY.getName(),
                InstanceModel.BACKUP_TARGET_ID.getName()))
            .build();
    }

    private static @NonNull TableSpec<Row> adminTable() {
        return TableSpec.<Row>builder()
            // The kind qualifies the name and the install state qualifies the status; both stay declared (and
            // filterable) so the picker and the filter strip keep them.
            .column(ColumnSpec.fromField(InstanceModel.NAME).filterable().subtext("kind").build())
            .column(ColumnSpec.fromField(InstanceModel.KIND).filterable().hidden().build())
            .column(ColumnSpec.fromField(InstanceModel.SERVER_ID)
                .relation(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().subtext("install_state").build())
            .column(installStateColumn().filterable().hidden().build())
            // Who runs this record: blank for an authored row, the owning product record (linked) for a generated
            // one -- the honesty column that lets generated rows appear here without becoming a second UI.
            .column(ColumnSpec.virtual(MANAGED_BY_COLUMN,
                    Microcopy.of("managed_by").withFilter("scope", "instance"))
                .renderer(HohenheimTemplateIds.CELL_MANAGED_BY).build())
            .column(ColumnSpec.fromField(InstanceModel.CREATED_AT).filterable().hidden().build())
            .filter(FilterSpec.forField(InstanceModel.NAME, FilterSpec.Kind.TEXT)
                .label(FieldLabels.labelFor(InstanceModel.NAME)).build())
            .filter(FilterSpec.forField(InstanceModel.KIND, FilterSpec.Kind.SELECT)
                .label(FieldLabels.labelFor(InstanceModel.KIND)).build())
            .filter(FilterSpec.forField(InstanceModel.STATUS, FilterSpec.Kind.SELECT)
                .label(FieldLabels.labelFor(InstanceModel.STATUS)).build())
            // Relational host filter: "the instances on daystrom" is a typed host name, riding the server.name
            // variable the declared source's vocabulary adds.
            .filter(FilterSpec.global(HOST_FILTER, FieldLabels.labelFor(InstanceModel.SERVER_ID),
                FilterSpec.Kind.TEXT).build())
            .build();
    }

    /**
     * The install state, drawn as the status column's subtext only while it says something: a record with no install
     * lifecycle shows its status pill alone. The column itself stays declared and filterable -- the renderer hides a
     * member's badge, never the vocabulary.
     */
    private static ColumnSpec.@NonNull Builder installStateColumn() {
        return ColumnSpec.fromField(InstanceModel.INSTALL_STATE).renderer(HohenheimTemplateIds.CELL_INSTALL_STATE);
    }

    /** The schema's variables plus the relational host name the strip filter rides. */
    private static @NonNull RuleVocabulary filterVocabulary() {
        RuleVocabulary.Builder vocabulary = RuleVocabulary.builder();
        for (VariableDefinition definition : SchemaVocabulary.of(Models.get(InstanceModel.class)).definitions()) {
            vocabulary.add(definition);
        }
        vocabulary.add(RelationRules.define(InstanceModel.SERVER, ServerModel.NAME)
            .label(FieldLabels.labelFor(InstanceModel.SERVER_ID)));
        return vocabulary.build();
    }

    private static @NonNull List<RecordTab<Row>> adminTabs() {
        return List.of(
            InstanceOverview.tab(),
            new InstanceConsolePage(), new InstanceFramebufferPage(),
            new InstanceProvisioningPage(),
            new InstanceDeploymentsPage(),
            new InstanceFilesPage(), new InstanceStatsPage(),
            // The exec tab hides AND 404s itself for anyone without the exec capability on the record
            // (InstanceExecPage.visibleFor); the admin panel gate is not the only thing standing between a delegate
            // and an arbitrary command.
            new InstanceExecPage(),
            // The interactive shell tab hides AND 404s itself without the `shell` capability on the record. It is NOT
            // the exec tab under another name: exec is admin-only and single-shot, this is a delegable tenant verb
            // bounded to a workload that runs as a non-root uid.
            new InstanceShellPage(),
            new InstanceSnapshotsPage(new InstanceSnapshotResource()),
            new InstanceBackupsPage(),
            new InstanceSchedulesPage(), new InstanceDevicesPage(),
            new InstanceVolumesPage(), new InstanceDatabasesPage(),
            // Operator-only: the page hides AND 404s itself for a delegate, and the /manage entry never lists it.
            new InstanceMigratePage());
    }

    private static @NonNull List<RecordTab<Row>> manageTabs() {
        return List.of(
            InstanceOverview.tab(),
            new InstanceConsolePage(), new InstanceFramebufferPage(),
            new InstanceProvisioningPage(),
            new InstanceDeploymentsPage(),
            new InstanceFilesPage(), new InstanceStatsPage(),
            // Offered on the TENANT panel too, unlike the exec tab: exec is ADMIN-sensitivity with deliberately no
            // /manage surface, while `shell` is a delegable tenant verb bounded to a workload that runs as its own
            // non-root uid. It hides AND 404s itself without the capability.
            new InstanceShellPage(),
            // The artifact tabs read THIS panel's entries: the backup twin places no restore-to-new, which is how it
            // stays operator-only here too.
            new InstanceSnapshotsPage(new ManageInstanceSnapshotResource()),
            new InstanceBackupsPage(),
            new InstanceSchedulesPage(), new InstanceDevicesPage(),
            new InstanceVolumesPage(), new InstanceDatabasesPage());
    }

    /**
     * Both twins' default writes: a create's placement and declarations, an update's resize.
     *
     * @throws Violations when the placement or the model's declarations refuse a create
     */
    private static void beforeSave(@NonNull RowSave save) {
        if (save.isCreate()) {
            place(save);
        } else {
            resize(save);
        }
    }

    /**
     * CREATE-time placement: an empty host pick is the CHOOSER's answer, never the local daemon by default.
     *
     * AIDEV-NOTE: this surface used to write whatever the picker posted, and an empty pick wrote NULL -- which every
     * reader downstream spells "the local daemon". {@link InstancePlacement#forActor} is THE placement funnel every
     * other create walks, and this one does too. ONE pass: the placement answer and the model's own declaration
     * refusals are collected together, so an operator reads both sentences on one render; the write hook re-judges the
     * declarations inside the save. The UPDATE lane deliberately does not place: an edit carries whatever the operator
     * submitted, and moving a running workload is the migrate operation.
     */
    private static void place(@NonNull RowSave save) {
        Violations refused = new Violations();
        Integer placed = placedHostFor(save.values(), save.access(), refused);
        refused.addAll(InstanceDeclarations.judge(save.row(), null));
        if (!refused.isEmpty()) {
            throw refused;
        }
        save.row().set(InstanceModel.SERVER_ID, placed);
    }

    /**
     * The host this submission lands on, priced and gated exactly like every other create.
     *
     * @param refused where a placement refusal lands, RE-PATHED onto the host entry: a sentence about the host belongs
     *                on the field the operator can see, not in the form's generic error box
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
                // A refusal that names no reason is a placement bug, never a host to write.
                throw new IllegalStateException("Placement refused without naming a reason");
            }
            return null;
        }
    }

    /** The submitted per-kind settings, which price the workload; a SchemaField answers Object. */
    @SuppressWarnings("unchecked")
    private static @NonNull Map<String, Object> submittedSettings(@NonNull Map<String, Object> values) {
        return values.get(InstanceModel.SETTINGS.getName()) instanceof Map<?, ?> settings
            ? (Map<String, Object>) settings : Map.of();
    }

    /**
     * THE resize: a save that moves {@code memory_limit_mb} or {@code cpu_limit} on a LIVE workload recreates its
     * container, because a cgroup ceiling is stamped at create; {@link InstanceResize} owns the decision.
     *
     * AIDEV-NOTE: the submitted values are already applied to the row, so the BEFORE state is the stored row, read
     * here inside the panel's write transaction; the recreate waits for that transaction's commit. A configuration
     * save never carries the operation-owned columns ({@code InstanceModel.prepareConfigurationSave}).
     */
    private static void resize(@NonNull RowSave save) {
        Integer instanceId = save.row().get(InstanceModel.ID);
        Row stored = instanceId == null ? null : Models.get(InstanceModel.class).findById(instanceId);
        InstanceModel.prepareConfigurationSave(save.row());
        if (instanceId != null && stored != null) {
            InstanceResize.recreateAfterCommit(instanceId, InstanceResize.settingsOf(stored),
                InstanceResize.settingsOf(save.row()), stored.get(InstanceModel.STATUS));
        }
    }

    /**
     * The delete dialog: this removes the workload but KEEPS its data (the separate delete-with-data verb is the one
     * that does not), and for one record it NAMES the sites the destroy will disable.
     *
     * AIDEV-NOTE: the wording lives here and the guarantee lives in {@code InstanceExposure.disableForDestroyedInstance},
     * called by {@code InstanceService.destroy}: a non-UI caller never sees this text and still gets the disable.
     *
     * @param instance the instance, null for the record-less fallback
     */
    private static @NonNull ConfirmationSpec deleteBody(@Nullable Row instance) {
        String sites = instance == null ? null : strandedSites(instance);
        return DeleteConfirmation.body(sites == null
            ? Microcopy.of("delete_confirm").withFilter("scope", "instance")
            : Microcopy.of("delete_confirm_stranding").withFilter("scope", "instance").withArg("sites", sites));
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

    /** Whether this row's lifecycle belongs to a product tier rather than an operator. */
    static boolean isGenerated(@NonNull Row row) {
        return row.get(InstanceModel.GENERATED_BY) != null;
    }

    /**
     * Where a surface links an instance row: its own record (or one of its subpages), and for a release row, which the
     * admin scope does not serve, its application's Deploys tab.
     *
     * @param subpage the record subpage to open, or null for the record itself; ignored for a release row
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

    /**
     * The owning record of a generated row as a linked cell; null (blank) for authored rows. The owning entry is FOUND
     * on the admin panel by its model, so the label, icon and slug can never drift from the entry that serves it.
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
            return new ManagedByCell(null, Microcopy.of("managed_by").withFilter("scope", "instance"),
                modelId + " #" + ownerId, null);
        }
        PanelEntry ownerEntry = adminEntryForModel(ownerModel.getModelId());
        String url = ownerEntry != null
            ? CmsRoutes.detail(HohenheimSlugs.ADMIN, ownerEntry.slug(), ownerId).toUrl() : null;
        String name = ownerModel.getDisplayTitle(owner);
        return new ManagedByCell(
            ownerEntry != null ? ownerEntry.icon().name() : null,
            ownerEntry != null ? ownerEntry.label() : Microcopy.of("managed_by").withFilter("scope", "instance"),
            name != null ? name : modelId + " #" + ownerId,
            url);
    }

    /** The admin panel's resource entry over a model, legacy or parts; null when none serves it. */
    private static @Nullable PanelEntry adminEntryForModel(@NonNull Identifier modelId) {
        Panel panel = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        if (panel == null) {
            return null;
        }
        for (PanelEntry entry : panel.entries()) {
            if (modelId.equals(Panel.modelIdOf(entry))) {
                return entry;
            }
        }
        return null;
    }
}
