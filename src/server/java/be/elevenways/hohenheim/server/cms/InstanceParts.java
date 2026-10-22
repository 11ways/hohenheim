package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
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
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.game.GameAddresses;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.project.Projects;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.InstanceDeclarations;
import be.elevenways.hohenheim.server.instance.InstanceExposure;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstancePlacement;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.InstanceResize;
import be.elevenways.hohenheim.server.instance.InstanceTemplateHandlers;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.protoblast.common.typed.rule.Operand;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.page.CmsRecordLinks;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
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
import be.elevenways.zenit.cms.common.resource.RowWriteCall;
import be.elevenways.zenit.cms.common.resource.RowWriter;
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
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.hohenheim.server.project.ProjectGuards;
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
import java.util.Set;
import java.util.Objects;
import java.util.function.Supplier;

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

    /** The instance form's open section holding where its backups go. */
    private static final String BACKUPS_SECTION = "backups";

    /** The Configuration section holding where players connect: the game address of a service reached on ports. */
    private static final String CONNECT_SECTION = "connect";

    /** Virtual column naming the owning product record of a generated row. */
    static final String MANAGED_BY_COLUMN = "managed_by";

    /** The relational host filter's variable key. */
    static final String HOST_FILTER = "server.name";

    /**
     * The create form's project: the OWNER a new instance is created into, never a column (a project owns a record
     * through its manage grant, instance-tier-plan decision 7), so it exists on the create view only and an existing
     * record moves through its Access tab.
     */
    static final IntegerField PROJECT = IntegerField.builder("project_id")
        .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("project"))
        .help(HohenheimMicrocopy.HELP.of("instance_project"))
        .visibleIn(EditView.CREATE)
        .build();

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
            .reads(runtimeReads(InstanceParts::filterVocabulary))
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
            .form(form(createForm()).createDefaults((access, panel) -> InstanceParts.createDefaults(access))
                .bindings(List.of(ResourceFieldBinding.of(InstanceModel.KIND.getName(), kindAccess()),
                    ResourceFieldBinding.of(InstanceModel.GAME_ADDRESS.getName(), gameAddressAccess())))
                .build())
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
            // The tenant's words, where the toolbar, the heading and the command palette read them: what the rows ARE
            // (machines and services, each row's subtext naming its kind from the kind options), never "apps" or the
            // operator's "Instances".
            .label(HohenheimMicrocopy.MANAGE_APP.of("machines_and_services"))
            .description(HohenheimMicrocopy.MANAGE_APP.of("machines_and_services_hint"))
            .standsUnder(HohenheimSlugs.APPS)
            // One of the tenant's apps, as the Apps list words it: what the landing's Recent rows name its kind.
            .recordLabel(HohenheimMicrocopy.APP_LIST.of("singular"))
            .health(AppHealth.instances(true))
            // The host is operator inventory: never a rule, sort, search or value of this list.
            .withholds(HostFields.of(InstanceModel.MODEL_ID))
            .reads(runtimeReads(() -> runtimeSchemaVocabulary().build()))
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
            // Saving a new memory or CPU ceiling reaches a live workload the way its host's driver applies one (Docker
            // recreates the container, Incus rewrites the instance in place); on the create form there is nothing yet.
            .notice((instance, access) -> instance.get(InstanceModel.ID) == null ? null
                : HohenheimMicrocopy.INSTANCE.of("resize_notice")
                    .withFilter("runtime", InstanceKinds.hostRuntimeOf(instance)));
    }

    /**
     * The kind entry: editable on a create and wherever {@link InstanceKinds#kindChangesOf} offers a change, read-only
     * on a record whose driver can turn its workload into no other kind (a virtual machine stays one); the write hook
     * ({@code InstanceDeclarations}) refuses a change outside that set either way.
     */
    private static @NonNull FieldAccess kindAccess() {
        return FieldAccess.customRecordAware((access, record) -> record instanceof Row row
                && row.get(InstanceModel.ID) != null && InstanceKinds.kindChangesOf(row).size() < 2
            ? FieldAccess.Decision.READONLY : FieldAccess.Decision.EDITABLE)
            // Read-only or editable, never hidden: every stored instance reads it.
            .readableWhere(access -> null);
    }

    /**
     * The game address is offered on a stored instance reached on its own ports (a game server), and only there: a
     * create gets it from "Put something online", and a website's or machine's form has no use for it. The operator
     * twin alone carries the entry, as only an operator gives one when it is put online.
     */
    private static @NonNull FieldAccess gameAddressAccess() {
        return FieldAccess.customRecordAware((access, record) -> record instanceof Row row
                && row.get(InstanceModel.ID) != null && InstanceKinds.reachOf(row) == PutOnlineReach.PORTS
            ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.HIDDEN);
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
            rows.createAround(InstanceParts::createIntoProject);
        }
        return rows.update()
            .delete(InstanceOperations.DELETE)
            // The verified destroy reaches the host daemon and takes the host lease, which refuses to wait inside a
            // write transaction; a rollback could not undo a removed container anyway.
            .ownsWriteEnvelope(ResourceVerb.DELETE)
            .beforeSave(InstanceParts::beforeSave)
            .afterSave(InstanceParts::afterSave)
            .build();
    }

    /**
     * The operator's create: the ordinary save, run INTO the chosen project (or the chosen environment's) through the
     * one ownership funnel the template lane uses, then the creation owner's manage grant, as every create plants it.
     *
     * AIDEV-NOTE: the project is taken out of the values before the framework applies them: it is no column, and
     * the save hooks (placement included) run inside the funnel, so they see the project as the creation owner.
     */
    private static @NonNull Object createIntoProject(@NonNull RowWriteCall call, @NonNull RowWriter framework) {
        Map<String, Object> staged = new LinkedHashMap<>(call.values());
        Integer projectId = staged.remove(PROJECT.getName()) instanceof Number number ? number.intValue() : null;
        Integer environmentId = staged.getOrDefault(InstanceModel.ENVIRONMENT_ID.getName(), null)
            instanceof Number number ? number.intValue() : null;
        RowWriteCall ordinary = new RowWriteCall(call.verb(), null, staged, call.access());
        return Projects.createInto(projectId, environmentId, call.access(), PROJECT.getName(),
            InstanceModel.ENVIRONMENT_ID.getName(), () -> {
                Object key = Objects.requireNonNull(framework.write(ordinary), "the create answers its key");
                HohenheimAccess.grantCreatorManage(InstanceModel.MODEL_ID, key, call.access());
                return key;
            });
    }

    /** The delete dialog both twins show, naming the sites one record's destroy disables. */
    private static @NonNull DeleteConfirmation<Row> deleteConfirmation() {
        return DeleteConfirmation.<Row>of(deleteBody(null)).forRow((instance, request) -> deleteBody(instance));
    }

    /**
     * The create form's values: its defaults, plus the kind "Put something online" asked for ({@code ?kind=}) when it
     * names a kind that page offers.
     */
    private static @NonNull Map<String, Object> createDefaults(@NonNull AccessContext access) {
        Map<String, Object> values = new LinkedHashMap<>(createForm().defaultValues());
        String kind = CmsSupport.prefill(access.conduit(), HohenheimParams.PUT_ONLINE_KIND);
        Identifier kindId = kind == null ? null : Identifier.tryParse(kind);
        if (kindId != null && InstanceKindRegistry.registry().get(kindId) instanceof InstanceKindInfo info
                && info.putOnlineGroup() != null) {
            values.put(InstanceModel.KIND.getName(), kind);
        }
        return Map.copyOf(values);
    }

    /**
     * The create/edit form: choice cards decide the kind, and every placement pick follows it live (the host judged by
     * the placement verdict, the runtime image narrowed by kind).
     *
     * AIDEV-NOTE: the image resolver carries a SNAPSHOT of the kind registry's declarations, taken when the panel
     * builds its entry; a test that replaces a kind delegates its facts to the real one, and the kind options
     * themselves are Supplied, so a registry entry arriving after the build still resolves on coercion. The host
     * judge asks the registry on every call.
     */
    private static @NonNull FormSpec createForm() {
        return FormSpec.builder()
            // AIDEV-NOTE: a create page falls back to the RESOURCE label for its heading, and this label is the PLURAL
            // the nav needs; createTitle gives it an honest singular. An edit page leads with the record's own name,
            // like every other tab of the record, so no editTitle is declared.
            .createTitle(HohenheimMicrocopy.INSTANCE.of("create_title"))
            // AIDEV-NOTE: the kind entry offers what this record may BECOME: on a create every kind a human may author
            // (InstanceKinds.placeableOptions), on a stored record only its InstanceKinds.kindChangesOf, the set its
            // write hook enforces. Record-aware (OptionSource.dynamic): the render's AND the save's coercion context
            // carry the record (zenit-cms RecordFormWrite), so the API lane judges an update against the same cards.
            // Every label path reads EnumField.getValues() and still sees the whole registry, so existing generated
            // rows keep their label.
            .add(Select.of(InstanceModel.KIND)
                .options(OptionSource.dynamic(context -> InstanceKinds.kindOptionsFor(context.record())))
                .presentation(Select.Presentation.CARDS)
                .clearable(!Boolean.TRUE.equals(InstanceModel.KIND.getAttribute(FieldAttributes.REQUIRED)))
                .build())
            .add(InstanceModel.NAME)
            // A host is admitted, preflighted and trusted before it can carry anything, so it is never created from
            // inside another record's form -- and every host is offered, judged by the kind: one the kind cannot be
            // placed on is drawn disabled with the placement gate's reason, and refused in those words on the submit.
            .add(hostPick(InstanceModel.SERVER_ID))
            // The runtime image only offers images to kinds that run inside one, and is SHOWN only for those kinds (the
            // showWhen below): every other kind's deploy never reads the column. It stays clearable because a shown
            // pick may still be left for InstanceDeclarations to require on the write.
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
            // The project a NEW instance is created into (create view only): the source's own scope is the project
            // visibility policy, and the save re-judges membership (Projects.createInto).
            .add(RelationPick.of(PROJECT, ProjectModel.MODEL_ID)
                .creatable(false).clearable(true)
                .build())
            // Grouping, never authority: the environments offered are ProjectGuards' own answer for this record's
            // owner (its owning project's), the rule its write hook refuses by, re-checked on the submit; a new one
            // is created INTO that project, and an owner without one is told why the list is empty. On a create,
            // the project chosen beside it narrows them to that project's.
            .add(RelationPick.of(InstanceModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
                .clearable(true)
                .rulesFromContext(InstanceParts::environmentRules)
                .rulesFromSiblings(new HohenheimPickRules.EnvironmentProjectRules(PROJECT.getName()),
                    PROJECT.getName())
                .emptyReason(InstanceParts::environmentEmptyReason)
                .createPresets(InstanceParts::environmentPresets)
                .build())
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.SETTINGS))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceModel.CRASH_POLICY))
            // Where its backups go is the instance's own choice (no installation default exists), so it stands in an
            // open section of its own: Back up now refuses by naming it, and the Backups tab links here.
            .add(RelationPick.of(InstanceModel.BACKUP_TARGET_ID, BackupTargetModel.MODEL_ID)
                .clearable(true).build())
            // Where players connect (a game server's name, its records generated by GameAddresses); hidden on every
            // record not reached on its own ports (gameAddressAccess).
            .add(InstanceModel.GAME_ADDRESS)
            // What a person DECIDES when creating an instance is the kind, its name, where it runs and what it runs
            // inside; the failure policy is an answer this installation already has.
            //
            // AIDEV-NOTE: this section can only name TOP-LEVEL entries; the per-kind SETTINGS sub-form folds through
            // the schema-declared sections each kind adds (HohenheimFormSections). Never fold a field by hiding it
            // with visibleIn: that makes it unwritable.
            .section(FormSection.of(BACKUPS_SECTION, HohenheimMicrocopy.INSTANCE.of("backups"),
                InstanceModel.BACKUP_TARGET_ID.getName()))
            .section(FormSection.of(CONNECT_SECTION, HohenheimMicrocopy.INSTANCE_OVERVIEW.of("connect"),
                InstanceModel.GAME_ADDRESS.getName()))
            .section(FormSection.advanced(
                InstanceModel.CRASH_POLICY.getName()))
            .showWhen(InstanceModel.RUNTIME_IMAGE_ID.getName(), InstanceModel.KIND.getName(),
                InstanceKinds.kindsWhere(InstanceKindHandler::usesRuntimeImage).toArray(String[]::new))
            .build();
    }

    /**
     * THE host pick of a form that names its kind in a {@code kind} entry: every host, each judged by the chosen kind
     * through the create verdict ({@link InstancePlacement#createRefusalOn}: the placement gates, then room for the
     * kind's memory), charged to the owner the save would charge (the chosen project, else the chosen environment's,
     * else the caller's), live as those change and again on submit. Blank stays a choice: placement then picks the
     * host.
     */
    static @NonNull Select<Integer> hostPick(@NonNull IntegerField field) {
        return Select.of(field)
            .options(OptionSource.dynamic(context -> InstanceTemplateHandlers.hostOptions()))
            .judgedBySiblings(List.of(InstanceModel.KIND.getName(), PROJECT.getName(),
                InstanceModel.ENVIRONMENT_ID.getName()), InstanceParts::placementSubject, InstanceParts::hostRefusal)
            .build();
    }

    /**
     * What a host is judged for: the chosen kind, charged to the edited record's bucket or, on a create, to the bucket
     * the create-into funnel charges for the chosen project or environment.
     *
     * @return null without a kind, or when the funnel refuses the chosen project (the save refuses it on that field)
     */
    private static @Nullable HostSubject placementSubject(@NonNull EditContext context,
                                                          @NonNull Map<String, String> siblings) {
        String kind = siblings.get(InstanceModel.KIND.getName());
        if (kind == null) {
            return null;
        }
        Row stored = storedRecord(context);
        String bucket = stored != null ? stored.get(InstanceModel.QUOTA_BUCKET)
            : InstanceQuota.creationBucketInto(siblings.get(PROJECT.getName()),
                siblings.get(InstanceModel.ENVIRONMENT_ID.getName()), context.accessContext());
        return stored == null && bucket == null ? null : new HostSubject(kind, bucket);
    }

    /**
     * A kind and the bucket its workload is charged to.
     *
     * @param bucket null only for a stored record charged to none
     */
    private record HostSubject(@NonNull String kind, @Nullable String bucket) {
    }

    /**
     * Why the subject's kind cannot be placed on this host, charged to its bucket.
     *
     * AIDEV-NOTE: the host a stored record already names under its stored kind is never judged: moving or keeping a
     * placed workload is the migrate operation's question, and an unrelated edit must not be refused over it.
     */
    private static @Nullable Microcopy hostRefusal(@NonNull EditContext context, @NonNull HostSubject subject,
                                                   @NonNull Integer serverId) {
        Row stored = storedRecord(context);
        if (stored != null && serverId.equals(stored.get(InstanceModel.SERVER_ID))
                && subject.kind().equals(stored.get(InstanceModel.KIND))) {
            return null;
        }
        InstanceKindHandler handler = InstanceKinds.getHandler(subject.kind());
        if (handler == null) {
            return HohenheimMicrocopy.VIOLATIONS.of("instance_kind_unknown").withArg("kind", subject.kind());
        }
        // A create's settings come in a later step, so a new workload is priced at its kind's default memory; a
        // stored record at its own (InstancePlacement.createRefusalOn).
        return InstancePlacement.createRefusalOn(serverId, handler, subject.bucket(),
            stored == null ? Map.of() : InstanceModel.settingsOf(stored));
    }

    /** @return the stored record the form edits, null on a create */
    private static @Nullable Row storedRecord(@NonNull EditContext context) {
        return context.record() instanceof Row row && row.has(InstanceModel.ID.getName())
            && row.get(InstanceModel.ID) != null ? row : null;
    }

    /**
     * The environments the edited record (or, on a create, the record this caller would create) may be grouped in:
     * its grouping project's, plus the one it already sits in, since an unchanged environment is never judged. A
     * create whose creator answers to no project may be created INTO one, so it is offered the environments of every
     * project the caller sees, narrowed live by the project chosen beside it.
     */
    private static @NonNull Condition environmentRules(@NonNull EditContext context) {
        List<Condition> admitted = new ArrayList<>();
        Row project = ProjectGuards.groupingProject(ownerOf(context));
        if (project != null) {
            admitted.add(Condition.test(EnvironmentModel.PROJECT_ID.getName(), CoreTypes.EQUALS,
                Operand.of(project.get(ProjectModel.ID))));
        } else if (editedId(context) == null) {
            List<Object> creatable = creatableProjectIds(context);
            if (!creatable.isEmpty()) {
                admitted.add(Condition.test(EnvironmentModel.PROJECT_ID.getName(), CoreTypes.IN,
                    Operand.of(creatable)));
            }
        }
        Object current = context.record() instanceof Row row && row.has(InstanceModel.ENVIRONMENT_ID.getName())
            ? row.get(InstanceModel.ENVIRONMENT_ID) : null;
        if (current != null) {
            admitted.add(Condition.test(EnvironmentModel.ID.getName(), CoreTypes.EQUALS, Operand.of(current)));
        }
        return admitted.isEmpty() ? Condition.NOTHING : Condition.any(admitted);
    }

    /** @return the ids of the projects the caller sees ({@link Projects#visibleTo}), any of which a create may join */
    private static @NonNull List<Object> creatableProjectIds(@NonNull EditContext context) {
        List<Object> ids = new ArrayList<>();
        for (Row visible : Projects.visibleTo(context.accessContext())) {
            ids.add(visible.get(ProjectModel.ID));
        }
        return ids;
    }

    /**
     * Why the environment pick is empty: the record's own project has none, or it answers to no project; a create
     * that could be made into a project says that no project it may choose has one yet.
     */
    private static @NonNull Microcopy environmentEmptyReason(@NonNull EditContext context) {
        Set<String> owner = ownerOf(context);
        if (ProjectGuards.groupingProject(owner) == null && editedId(context) == null
                && !creatableProjectIds(context).isEmpty()) {
            return HohenheimMicrocopy.INSTANCE.of("environment_empty_creatable");
        }
        return ProjectGuards.noEnvironmentsReason(owner);
    }

    /** @return the environment an inline create makes: one of the grouping project's, null when there is none */
    private static @Nullable Map<String, Object> environmentPresets(@NonNull EditContext context) {
        Row project = ProjectGuards.groupingProject(ownerOf(context));
        return project == null ? null : Map.of(EnvironmentModel.PROJECT_ID.getName(), project.get(ProjectModel.ID));
    }

    /** @return the owner subjects the record the form edits answers to, or the caller's creation owner */
    private static @Nullable Set<String> ownerOf(@NonNull EditContext context) {
        return ProjectGuards.ownerOf(editedId(context), context.accessContext());
    }

    /** @return the id of the instance the form edits, null on a create */
    private static @Nullable Object editedId(@NonNull EditContext context) {
        Row stored = storedRecord(context);
        return stored == null ? null : stored.get(InstanceModel.ID);
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
            // The runtime status (runtimeSchemaVocabulary), never a facet counted on the stored column: an
            // application's own column is not what its row draws.
            .filter(FilterSpec.globalLeaf(InstanceModel.STATUS.getName(), FieldLabels.labelFor(InstanceModel.STATUS),
                InstanceModel.STATUS.getName(), CoreTypes.EQUALS).build())
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

    /** The runtime schema variables plus the relational host name the strip filter rides. */
    private static @NonNull RuleVocabulary filterVocabulary() {
        RuleVocabulary.Builder vocabulary = runtimeSchemaVocabulary();
        vocabulary.add(RelationRules.define(InstanceModel.SERVER, ServerModel.NAME)
            .label(FieldLabels.labelFor(InstanceModel.SERVER_ID)));
        return vocabulary.build();
    }

    /**
     * The schema's variables, its Status lowered onto each instance's runtime row (ApplicationReleases.runtimeMatches),
     * so a Status filter keeps exactly the rows whose drawn Status cell ({@link #runtimeReads}) says it.
     */
    private static RuleVocabulary.@NonNull Builder runtimeSchemaVocabulary() {
        RuleVocabulary.Builder vocabulary = RuleVocabulary.builder();
        for (VariableDefinition definition : SchemaVocabulary.of(Models.get(InstanceModel.class)).definitions()) {
            vocabulary.add(InstanceModel.STATUS.getName().equals(definition.key())
                ? definition.lowered(ApplicationReleases::runtimeMatches) : definition);
        }
        return vocabulary;
    }

    /**
     * Both twins' row reads: the Status cell is each instance's runtime status, an application's that of the release
     * serving it, read once per page; a lone row reads its own.
     *
     * AIDEV-NOTE: the application's own status column is never kept in step with its release (a mirror would make
     * every live-workload sweep treat the application as a guest of its own), so the list draws and filters the
     * runtime row's instead, both through ApplicationReleases.
     */
    private static @NonNull ResourceReads<Row> runtimeReads(@NonNull Supplier<RuleVocabulary> vocabulary) {
        return ResourceReads.rows()
            .mapCells((row, column) -> drawsStatus(column)
                ? ApplicationReleases.runtimeRowOf(row).get(InstanceModel.STATUS) : null)
            .pageBatch((rows, access) -> {
                Map<Integer, Row> runtimes = ApplicationReleases.runtimeRows(rows);
                return (row, column) -> drawsStatus(column)
                    ? runtimes.get((Integer) row.get(InstanceModel.ID)).get(InstanceModel.STATUS) : null;
            })
            .source(source -> source.vocabularyFrom(vocabulary));
    }

    /** @return whether the column draws the Status, which the runtime row answers */
    private static boolean drawsStatus(@NonNull ColumnSpec column) {
        return column.source() == InstanceModel.STATUS;
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
        // What the kind's driver offers, in the order it matters: a machine's disks, NICs and install media right after
        // its console; a workload's files and live metrics only where its driver has those lanes (WorkloadLane). Each
        // tab hides itself where its lane or kind fact is absent, so every kind's strip derives from those facts.
        tabs.add(new InstanceDevicesPage());
        tabs.add(new InstanceFilesPage());
        tabs.add(new InstanceStatsPage());
        // The sections read THIS panel's entries: the /manage backup twin places no restore-to-new, which is how it
        // stays operator-only there too.
        tabs.add(new InstanceBackupsPage(operator));
        tabs.add(new InstanceProvisioningPage());
        tabs.add(VolumeParts.tab(operator));
        // The attachments resolve in the hosting panel, which declares them only where this boot serves databases.
        if (InstanceAttachmentParts.databasesServed()) {
            tabs.add(InstanceAttachmentParts.databasesTab(operator));
        }
        if (operator) {
            // The page hides AND 404s itself for a delegate, and the /manage entry never lists it.
            tabs.add(new InstanceMigratePage());
        }
        return List.copyOf(tabs);
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
            if (save.values().containsKey(InstanceModel.GAME_ADDRESS.getName())) {
                GameAddresses.prepare(save.row());
            }
        }
    }

    /** A changed game address takes its records along (GameAddresses, the address's one home). */
    private static void afterSave(@NonNull RowSave save) {
        if (!save.isCreate() && save.values().containsKey(InstanceModel.GAME_ADDRESS.getName())) {
            GameAddresses.reconcile(save.row().get(InstanceModel.ID));
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
     * AIDEV-NOTE: the wording lives here and the guarantee lives in
     * {@code InstanceExposure.disableForDestroyedInstance}, called by {@code InstanceService.destroy}: a non-UI caller
     * never sees this text and still gets the disable.
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
