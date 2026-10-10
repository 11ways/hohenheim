package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimPickRules;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.instance.ManagedByCell;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceDeclarations;
import be.elevenways.hohenheim.server.instance.InstanceExposure;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceResize;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRecordLinks;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.RecordLead;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
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
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.orm.datasource.Row;
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

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The instance tier's shared parts, and the admin instance resource and its /manage twin built from them: the
 * kind-led create form with its placement and declarations, the resize on save, the fleet list with its "Managed by"
 * column, the verified destroy as the delete, the placed instance verbs ({@link InstanceActions}) and the record tabs.
 *
 * AIDEV-NOTE: the /manage twin is a NARROWING, never a gate of its own: its rows are the instances the caller holds a
 * grant on ({@link TenantScopes#INSTANCES}), its form edits the name and the crash policy, it creates nothing and its
 * delete is the operator's verified destroy, live for a holder of {@code destroy} and dead for everyone else; the
 * operations' gates and the model's write hooks judge every writer alike. It withholds the host ({@link HostFields}).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceParts {

    /** The artifact sections' stored-failure column, drawn only in the Backups tab. */
    private static final String FAILURE_COLUMN = "failure";

    /** Virtual column naming the owning product record of a generated row. */
    static final String MANAGED_BY_COLUMN = "managed_by";

    /** The relational host filter's variable key. */
    static final String HOST_FILTER = "server.name";

    private InstanceParts() {
    }

    /** @return the operator's instance resource: every live instance, the full form, the verified destroy */
    public static @NonNull PanelResource<Row> admin() {
        TableSpec<Row> table = adminTable();
        return entry(HohenheimIds.id("instance"))
            // Reached through the Apps list, whose toolbar links this list (HohenheimPanel's sidebar note); its pages
            // mark Apps in the sidebar.
            .showInNav(false)
            .standsUnder(HohenheimSlugs.APPS)
            .health(AppHealth.instances(false))
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
            .form(form(createForm()).createDefaults(InstanceParts::createDefaults).build())
            .writes(writes(true))
            .deleteConfirmation(deleteConfirmation())
            .authority(authority())
            .actions(InstanceActions.placedOperator())
            // AIDEV-NOTE: the declared tabs take no contributions, but zenit-auth's record access page rides every
            // entry over a grantable model (RecordAccessPage.ridesEveryEntry), so the instance page shows an Access
            // tab anyway; InstanceSurfacesBrowserTest pins it on both record cases.
            .tabs(ResourceTabs.of(adminTabs()).withHistory().historyInStrip())
            // The instance tier's sibling catalogs, demoted out of the sidebar: where backups are written, who may
            // run how many instances, which public names route to which workload, and the build/release history.
            //
            // AIDEV-NOTE: these are peers, not verbs, so they are DECLARED as related pages and rendered in the list
            // toolbar's one quiet overflow; each entry keeps the TARGET peer's own label, icon and description.
            .relatedPages(
                RelatedPage.toPeer(HohenheimSlugs.BACKUP_TARGETS),
                RelatedPage.toPeer(HohenheimSlugs.INSTANCE_QUOTAS),
                RelatedPage.toPeer(HohenheimSlugs.GAME_DOMAINS),
                RelatedPage.toPeer(HohenheimSlugs.BUILDS),
                RelatedPage.toPeer(HohenheimSlugs.RELEASES))
            .build();
    }

    /**
     * @return the /manage twin: the instances the caller holds a grant on, through two visible columns; their name and
     *         crash policy; no create, and the verified destroy, live for a holder of {@code destroy}
     */
    public static @NonNull PanelResource<Row> manage() {
        // Reached from the Apps list's toolbar (ManagePanel's sidebar note); its pages mark Apps in the sidebar. Admins
        // see every live instance; everyone else only the ones the walk confirms view on, which is what makes an
        // unowned id read as MISSING rather than forbidden. Generated (product-tier-owned) instances stay off the
        // delegated surface too: their one UI is the owning record's own page. The operator tabs a delegate needs,
        // declared like the admin set (no contributions). No related pages: the operator entry names sibling peers of
        // the ADMIN panel, which this panel does not register.
        return ManageTwin.reached(entry(ManageTwin.id("instance")), TenantScopes.INSTANCES,
                ResourceTabs.of(manageTabs()))
            .standsUnder(HohenheimSlugs.APPS)
            .health(AppHealth.instances(true))
            // The host is operator inventory: never a rule, sort, search or value of this list.
            .withholds(HostFields.of(InstanceModel.MODEL_ID))
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
            .writes(writes(false))
            .deleteConfirmation(deleteConfirmation())
            .authority(authority())
            // Power and the two artifact actions, placed operations gated by the record capability.
            .actions(InstanceActions.placedDelegated())
            .build();
    }

    /** The identity, nav placement and labels both twins share. */
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        return PanelResource.builder(id, HohenheimSlugs.INSTANCES, InstanceOperations.INSTANCE)
            .label(HohenheimMicrocopy.INSTANCE.of("plural"))
            .description(HohenheimMicrocopy.INSTANCE.of("nav_hint"))
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
            .landingTab(RecordOverview.SLUG)
            // The form tab's label says what the app is set to, beside what it is doing (the overview).
            .tabLabel(HohenheimMicrocopy.APP_OVERVIEW.of("configuration"))
            .lead((instance, access) -> instance.get(InstanceModel.ID) == null ? null
                : new RecordLead(AppOverview.instanceLead(instance, access.conduit()), null))
            .inlineEditable(InstanceModel.NAME, InstanceModel.CRASH_POLICY)
            // Saving a new memory or CPU ceiling RECREATES the workload's container, so it is briefly down; on the
            // create form there is nothing to recreate.
            .notice((instance, access) -> instance.get(InstanceModel.ID) == null ? null
                : HohenheimMicrocopy.INSTANCE.of("resize_notice"));
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
                    HohenheimCapabilities.CONFIG))
            .build();
    }

    /**
     * Both twins' row writes: the update with its resize, the verified destroy as the delete, the operator's create.
     *
     * AIDEV-NOTE: no delete authority on purpose. The destroy operation offers its Delete DEAD, with the gate's
     * own "instance_not_permitted", to a reader without {@code destroy}, so the button, a panel POST and the API's
     * delete (which runs through the admin entry) answer with one decision and one text; a hiding authority answered
     * the API with a bare 403 instead (InstanceApiTest).
     */
    private static @NonNull ResourceMutations<Row> writes(boolean creates) {
        ResourceMutations.RowBuilder rows = ResourceMutations.rows();
        if (creates) {
            rows.create();
        }
        return rows.update()
            .delete(InstanceOperations.DELETE)
            // The verified destroy reaches the host daemon and takes the host lease, which refuses to wait inside a
            // write transaction; a rollback could not undo a removed container anyway.
            .ownsWriteEnvelope(ResourceVerb.DELETE)
            .beforeSave(InstanceParts::beforeSave)
            .build();
    }

    /** The delete dialog both twins show, naming the sites one record's destroy disables. */
    private static @NonNull DeleteConfirmation<Row> deleteConfirmation() {
        return DeleteConfirmation.<Row>of(deleteBody(null)).forRow((instance, request) -> deleteBody(instance));
    }

    /**
     * The create/edit form: choice cards decide the kind, and every placement pick NARROWS from it live (host by
     * supported runtime + volume backend, runtime image by kind) -- the dependent-pick mechanism.
     *
     * AIDEV-NOTE: the resolvers carry SNAPSHOTS of the kind registry's declarations (runtimesByKind and friends),
     * taken when the panel builds its entry; a test that replaces a kind delegates its facts to the real one, and the
     * kind options themselves are Supplied, so a registry entry arriving after the build still resolves on coercion.
     */
    /**
     * The create form's values: its defaults, plus the kind "Put something online" asked for ({@code ?kind=}) when it
     * names a kind that page offers.
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull PanelRequest request) {
        Map<String, Object> values = new LinkedHashMap<>(createForm().defaultValues());
        String kind = CmsSupport.prefill(request.conduit(), HohenheimParams.PUT_ONLINE_KIND);
        Identifier kindId = kind == null ? null : Identifier.tryParse(kind);
        if (kindId != null && InstanceKindRegistry.REGISTRY.get(kindId) instanceof InstanceKindInfo info
                && info.putOnlineGroup() != null) {
            values.put(InstanceModel.KIND.getName(), kind);
        }
        return Map.copyOf(values);
    }

    private static @NonNull FormSpec createForm() {
        return FormSpec.builder()
            // AIDEV-NOTE: a form page falls back to the RESOURCE label for its heading, and this label is the PLURAL
            // the nav needs; createTitle/editTitle are the seam that gives the screen an honest singular heading.
            .createTitle(HohenheimMicrocopy.INSTANCE.of("create_title"))
            .editTitle(HohenheimMicrocopy.INSTANCE.of("edit_title"))
            // AIDEV-NOTE: the kind entry offers only what a human may author (the generated kinds are refused by
            // OwnedInstances anyway). Supplied, never a resolved list: registry entries arrive via BlastAutoLoadInit
            // after class-load, and a Supplied source still resolves on the context-free coercion path, which is what
            // makes a hand-posted generated-only kind fail at the form layer too. Every label path reads
            // EnumField.getValues() and still sees the whole registry, so existing generated rows keep their label.
            .add(Select.of(InstanceModel.KIND)
                .options(OptionSource.supplied(InstanceKinds::placeableOptions))
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
            .column(ResourceHealth.column())
            .column(ColumnSpec.fromField(InstanceModel.KIND).filterable().hidden().build())
            .column(ColumnSpec.fromField(InstanceModel.SERVER_ID)
                .relation(RelationPick.of(InstanceModel.SERVER_ID, ServerModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(InstanceModel.STATUS).filterable().subtext("install_state").build())
            .column(installStateColumn().filterable().hidden().build())
            // Who runs this record: blank for an authored row, the owning product record (linked) for a generated
            // one -- the honesty column that lets generated rows appear here without becoming a second UI.
            .column(ColumnSpec.virtual(MANAGED_BY_COLUMN,
                    HohenheimMicrocopy.INSTANCE.of("managed_by"))
                .renderer(HohenheimTemplateIds.CELL_MANAGED_BY).build())
            .column(ColumnSpec.fromField(InstanceModel.CREATED_AT).filterable().hidden().build())
            .filter(FilterSpec.leaf(InstanceModel.NAME, CoreTypes.CONTAINS)
                .label(FieldLabels.labelFor(InstanceModel.NAME)).build())
            .filter(FilterSpec.leaf(InstanceModel.KIND, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(InstanceModel.KIND)).build())
            .filter(FilterSpec.leaf(InstanceModel.STATUS, CoreTypes.EQUALS)
                .label(FieldLabels.labelFor(InstanceModel.STATUS)).build())
            // Relational host filter: "the instances on daystrom" is a typed host name, riding the server.name
            // variable the declared source's vocabulary adds.
            .filter(FilterSpec.globalLeaf(HOST_FILTER, FieldLabels.labelFor(InstanceModel.SERVER_ID), HOST_FILTER,
                CoreTypes.CONTAINS).build())
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

    /**
     * The operator's record tabs, in the strip's order: the overview (the landing), the deploys of an app built from
     * source, the console with its modes, files, metrics and backups stay visible beside the framework's edit tab; the
     * rest sit in "More".
     */
    private static @NonNull List<RecordTab<Row>> adminTabs() {
        return recordTabs(true);
    }

    /** The delegate's record tabs: the operator's set without the one-off command and the migration. */
    private static @NonNull List<RecordTab<Row>> manageTabs() {
        return recordTabs(false);
    }

    /** @param operator whether the tabs are the operator's, which adds the one-off command and the migration */
    private static @NonNull List<RecordTab<Row>> recordTabs(boolean operator) {
        List<RecordTab<Row>> tabs = new ArrayList<>();
        tabs.add(InstanceOverview.tab());
        tabs.add(new InstanceDeploymentsPage());
        // The console's hub tab first, then its modes (shell, the one-off command, a VM's screen), each routed and
        // gated as its own page and reached through the console's mode switch. The shell is a delegable tenant verb
        // bounded to a workload that runs as its own non-root uid; the one-off command is ADMIN-sensitivity with
        // deliberately no /manage surface (ConsoleModes.delegated()).
        tabs.addAll((operator ? ConsoleModes.operator() : ConsoleModes.delegated()).tabs());
        tabs.add(new InstanceFilesPage());
        tabs.add(new InstanceStatsPage());
        // The sections read THIS panel's entries: the /manage backup twin places no restore-to-new, which is how it
        // stays operator-only there too.
        tabs.add(backupsTab());
        tabs.add(new InstanceProvisioningPage());
        tabs.add(new InstanceDevicesPage());
        tabs.add(new InstanceVolumesTab());
        tabs.add(new InstanceDatabasesPage());
        if (operator) {
            // The page hides AND 404s itself for a delegate, and the /manage entry never lists it.
            tabs.add(new InstanceMigratePage());
        }
        return List.copyOf(tabs);
    }

    /**
     * The Backups tab: the framework's child list with a section per artifact resource of the hosting panel (its
     * /manage twin there) narrowed to this instance -- backups, snapshots and the schedules that make them -- each
     * row relaying its own entry's placed operations, so restore keeps its confirmation and operator-only refusal.
     *
     * AIDEV-NOTE: built per panel (a child list resolves in the panel it is declared on). An artifact's stored
     * failure text is the daemon's own wording, shown through {@link WithheldFailure}: an operator reads it, a
     * delegate reads that it failed.
     */
    private static @NonNull ChildList<Row> backupsTab() {
        return ChildList.<Row>sections(HohenheimSlugs.Tab.BACKUPS, HohenheimMicrocopy.INSTANCE.of("backups"),
                HohenheimSlugs.INSTANCE_BACKUPS, HohenheimSlugs.INSTANCE_SNAPSHOTS, HohenheimSlugs.INSTANCE_SCHEDULES)
            .icon(Icon.of("box-archive"))
            .hide(HohenheimSlugs.INSTANCE_BACKUPS, InstanceBackupModel.INSTANCE_ID.getName())
            .hide(HohenheimSlugs.INSTANCE_SNAPSHOTS, InstanceSnapshotModel.INSTANCE_ID.getName())
            .hide(HohenheimSlugs.INSTANCE_SCHEDULES, RecordScheduleModel.RECORD_ID.getName())
            // An empty section offers its making action instead of a dead end ("No backups yet" with Back up now).
            .parentActions(HohenheimSlugs.INSTANCE_BACKUPS, InstanceOperations.BACKUP.id())
            .parentActions(HohenheimSlugs.INSTANCE_SNAPSHOTS, InstanceOperations.SNAPSHOT.id())
            .column(HohenheimSlugs.INSTANCE_BACKUPS, SubjectType.record(InstanceBackupModel.MODEL_ID), failureColumn(),
                (backup, request) -> WithheldFailure.of(request.conduit()).shown(backup.get(InstanceBackupModel.ERROR)))
            .column(HohenheimSlugs.INSTANCE_SNAPSHOTS, SubjectType.record(InstanceSnapshotModel.MODEL_ID),
            failureColumn(),
                (snapshot, request) -> WithheldFailure.of(request.conduit())
                    .shown(snapshot.get(InstanceSnapshotModel.ERROR)));
    }

    /** The column an artifact's stored failure reads in, blank while it has none. */
    private static @NonNull ColumnSpec failureColumn() {
        return ColumnSpec.virtual(FAILURE_COLUMN, HohenheimMicrocopy.INSTANCE_ARTIFACTS.of("failure"))
            .build();
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
    private static @NonNull Map<String, Object> submittedSettings(@NonNull Map<String, Object> values) {
        return RawValues.map(values.get(InstanceModel.SETTINGS.getName()));
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
        Row stored = Models.get(InstanceModel.class).findById(instanceId);
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
            ? HohenheimMicrocopy.INSTANCE.of("delete_confirm")
            : HohenheimMicrocopy.INSTANCE.of("delete_confirm_stranding").withArg("sites", sites));
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
     * Where a surface links an instance row: its front door, the overview (or another subpage), and for a release row,
     * which the admin scope does not serve, its application's Deploys tab.
     *
     * AIDEV-NOTE: never the bare record URL: for an operator who may edit, that URL IS the edit form (only a read view
     * redirects to the landing tab), so a link there opens Edit instead of the overview.
     *
     * @param subpage the record subpage to open, or null for the front door; ignored for a release row
     * @return the route; the list itself for a release row no application owns
     */
    static @NonNull RouteTarget recordRoute(@NonNull String panel, @NonNull Row instance,
                                            @Nullable String subpage) {
        Integer id = instance.get(InstanceModel.ID);
        if (!ReleaseKind.ID.toString().equals(instance.get(InstanceModel.KIND))) {
            return CmsRoutes.subpage(panel, HohenheimSlugs.INSTANCES, id, subpage == null ? RecordOverview.SLUG
                : subpage);
        }
        int application = ApplicationReleases.linkOwnerOf(instance);
        return id == null || application == id ? CmsRoutes.list(panel, HohenheimSlugs.INSTANCES)
            : CmsRoutes.subpage(panel, HohenheimSlugs.INSTANCES, application, HohenheimSlugs.Tab.DEPLOYMENTS);
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
            return new ManagedByCell(null, HohenheimMicrocopy.INSTANCE.of("managed_by"),
                modelId + " #" + ownerId, null);
        }
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        PanelEntry ownerEntry = admin != null ? CmsRecordLinks.recordEntry(admin, ownerModel.getModelId()) : null;
        String url = ownerEntry != null
            ? CmsRoutes.detail(HohenheimSlugs.ADMIN, ownerEntry.slug(), ownerId).toUrl() : null;
        String name = ownerModel.getDisplayTitle(owner);
        return new ManagedByCell(
            ownerEntry != null ? ownerEntry.icon().name() : null,
            ownerEntry != null ? ownerEntry.label() : HohenheimMicrocopy.INSTANCE.of("managed_by"),
            name != null ? name : modelId + " #" + ownerId,
            url);
    }
}
