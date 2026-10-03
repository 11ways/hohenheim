package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.instance.VariableKind;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.DeleteConfirmation;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RelatedPage;
import be.elevenways.zenit.cms.common.resource.ResourceFieldBinding;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.resource.RowSave;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.FieldAccess;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.OperationGate;
import be.elevenways.zenit.common.operation.SubjectArity;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.operation.RowDeleteOperations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The project environments' parts: the admin environment resource and the admin environment-variable resource.
 *
 * AIDEV-NOTE: both models carry no domain version, so create and update are row writes under the value-digest stale
 * check. The environment delete is core's row delete under its own id {@link #DELETE}: an environment still holding
 * instances or variables is offered dead with the holders named (its availability), and the ProjectGuards write funnel
 * stays the gate that refuses after the click. The variable delete is core's canonical row delete over the variable
 * model. Both entries are admin-only: ManagePanel offers no environment surface, and PaasApi.visibleEnvironment answers
 * to the same panel permission.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class EnvironmentParts {

    /** The environment entry's slug, which the project list's related pages name. */
    public static final String SLUG = "environments";

    /** The environment-variable entry's slug, which the environment list's related pages name. */
    public static final String VARIABLES_SLUG = "environment-variables";

    /** Only environment-owned values: an instance's own variable belongs to the instance surfaces. */
    public static final RowScope VARIABLE_ROWS =
        RowScope.within(() -> InstanceVariableModel.ENVIRONMENT_ID.isNotNull());

    private static final SubjectType<Row> SUBJECT = SubjectType.record(EnvironmentModel.MODEL_ID);

    private static final OperationGate OPERATOR = OperationGate.permission(HohenheimSources.ADMIN_ACCESS);

    /** Deletes an environment nothing groups under; offered dead, naming the holders, while something does. */
    public static final Operation<Row, Void, Integer> DELETE =
        RowDeleteOperations.declare(EnvironmentModel.class, SubjectArity.ONE, OPERATOR)
            .id(HohenheimIds.id("delete_environment"))
            .availability((environment, access) -> inUseReason(environment))
            .register();

    /** Removes one environment-owned variable; anything running keeps its value until its next deploy. */
    public static final Operation<Row, Void, Integer> DELETE_VARIABLE =
        RowDeleteOperations.delete(InstanceVariableModel.class, SubjectArity.ONE, OPERATOR);

    /** The environment list's quick-add entries; the project rides along as a preset. */
    private static final QuickCreateSpec ENVIRONMENT_QUICK_CREATE = QuickCreateSpec
        .of(EnvironmentModel.NAME.getName(), EnvironmentModel.DESCRIPTION.getName())
        .presets(EnvironmentModel.PROJECT_ID.getName());

    /**
     * The variable list's quick-add entries; the environment rides along as a preset.
     *
     * AIDEV-NOTE: the bar offers the PLAIN carrier only. A secret typed into a one-line bar on a page everyone can see
     * is not the place to mint one; the full form is, where the framework's mask/keep-on-blank pipeline is the surface.
     */
    private static final QuickCreateSpec VARIABLE_QUICK_CREATE = QuickCreateSpec
        .of(InstanceVariableModel.KEY.getName(), InstanceVariableModel.KIND.getName(),
            InstanceVariableModel.PLAIN_VALUE.getName())
        .presets(InstanceVariableModel.ENVIRONMENT_ID.getName());

    private EnvironmentParts() {
    }

    /** Loads the class, declaring both deletes at boot so they are verified with every other operation; idempotent. */
    public static void init() {
        // The static field initializers did the work.
    }

    /** @return the admin environment resource */
    public static @NonNull PanelResource<Row> admin() {
        RelationPick project = RelationPick.of(EnvironmentModel.PROJECT_ID, ProjectModel.MODEL_ID).build();
        FormSpec form = FormSpec.builder()
            .add(project)
            .add(EnvironmentModel.NAME)
            .add(EnvironmentModel.DESCRIPTION)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            // AIDEV-NOTE: the project stays a VISIBLE relation column rather than the name's subtext: relation titles
            // resolve for visible columns only, so a hidden project_id would render the raw foreign key.
            .column(ColumnSpec.fromField(EnvironmentModel.NAME).filterable().subtext("description").build())
            .column(ColumnSpec.fromField(EnvironmentModel.DESCRIPTION).hidden().build())
            .column(ColumnSpec.fromField(EnvironmentModel.PROJECT_ID).relation(project).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("environment"), SLUG, SUBJECT)
            .label(Microcopy.of("plural").withFilter("scope", "environment"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "environment"))
            // Demoted out of the sidebar: this sentence reaches a reader through the panel index and the related
            // pages of the project list.
            .description(CmsSupport.navHint("environment"))
            .icon(Icon.of("layer-group"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(15)
            .showInNav(false)
            .reads(ResourceReads.rows())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(EnvironmentModel.NAME, EnvironmentModel.DESCRIPTION).build())
            // AIDEV-NOTE: the name is the one inline cell. PROJECT_ID is a RelationPick (outside the compact cell
            // subset), and re-homing an environment in use is refused by the ProjectGuards write funnel.
            .form(ResourceForm.<Row>of(form)
                .createDefaults(request -> prefill(request, HohenheimParams.PROJECT_ID_PREFILL,
                    EnvironmentModel.PROJECT_ID.getName()))
                .quickCreate(ENVIRONMENT_QUICK_CREATE)
                .quickCreatePresets(access -> preset(access, EnvironmentModel.PROJECT_ID.getName(), "projects"))
                .inlineEditable(EnvironmentModel.NAME)
                .build())
            .writes(ResourceMutations.rows().create().update().delete(DELETE).build())
            // The dialog states the policy the write funnel enforces: an environment still holding variables or
            // workloads is refused, so the operator learns the order of operations before the click.
            .deleteConfirmation(DeleteConfirmation.of(DeleteConfirmation.body(
                Microcopy.of("delete_confirm").withFilter("scope", "environment"))))
            .relatedPages(RelatedPage.toPeer(VARIABLES_SLUG))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * @return the admin environment-variable resource: the table-backed variable mechanism instances use (one encrypted
     *         secret carrier, one plain carrier, one write funnel), scoped to the rows an ENVIRONMENT owns
     */
    public static @NonNull PanelResource<Row> variables() {
        RelationPick environment = RelationPick.of(InstanceVariableModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
            .build();
        // AIDEV-NOTE: both carriers are declared, but the bindings leave exactly ONE visible per kind. They are
        // separate entries because they are separate columns: a dynamic (schemaFrom) sub-form, which would switch them
        // reactively, cannot hold secret_value, since zenit refuses .encrypted() under a JSON sub-schema.
        FormSpec form = FormSpec.builder()
            .add(environment)
            .add(InstanceVariableModel.KEY)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceVariableModel.KIND))
            .add(InstanceVariableModel.PLAIN_VALUE)
            .add(InstanceVariableModel.SECRET_VALUE)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            // The chip carries the KEY: it is what gets pasted into a compose file or a shell.
            .column(ColumnSpec.fromField(InstanceVariableModel.KEY).filterable().subtext("kind").copyable().build())
            .column(ColumnSpec.fromField(InstanceVariableModel.KIND).filterable().hidden().build())
            .column(ColumnSpec.fromField(InstanceVariableModel.ENVIRONMENT_ID).relation(environment).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("environment_variable"), VARIABLES_SLUG,
                SubjectType.record(InstanceVariableModel.MODEL_ID))
            .label(Microcopy.of("plural").withFilter("scope", "environment_variable"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "environment_variable"))
            .description(CmsSupport.navHint("environment_variable"))
            .icon(Icon.of("sliders"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(15)
            .showInNav(false)
            // List, load and create alike: a create without an environment refuses rather than landing as an orphan.
            .scope(VARIABLE_ROWS)
            .reads(ResourceReads.rows())
            // The key only: PLAIN_VALUE would leak a lookup over config values, and SECRET_VALUE is a secret the
            // search layer refuses outright.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).facets().ruleFilters()
                .search(InstanceVariableModel.KEY).build())
            .form(ResourceForm.<Row>of(form)
                .bindings(List.of(
                    ResourceFieldBinding.of(InstanceVariableModel.PLAIN_VALUE.getName(),
                        FieldAccess.customRecordAware((access, record) -> carrierAccess(record, VariableKind.PLAIN))),
                    ResourceFieldBinding.of(InstanceVariableModel.SECRET_VALUE.getName(),
                        FieldAccess.customRecordAware((access, record) -> carrierAccess(record, VariableKind.SECRET)))))
                .createDefaults(request -> prefill(request, HohenheimParams.ENVIRONMENT_ID_PREFILL,
                    InstanceVariableModel.ENVIRONMENT_ID.getName()))
                .quickCreate(VARIABLE_QUICK_CREATE)
                .quickCreatePresets(access -> preset(access, InstanceVariableModel.ENVIRONMENT_ID.getName(),
                    SLUG))
                .build())
            .writes(ResourceMutations.rows().create().update().delete(DELETE_VARIABLE)
                .beforeSave(EnvironmentParts::placeValueInItsCarrier)
                .build())
            .deleteConfirmation(DeleteConfirmation.<Row>of(variableDeleteBody(null))
                .forRow((variable, request) -> variableDeleteBody(variable)))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * The reason an environment cannot go, in the write funnel's own words.
     *
     * @return null when nothing groups under the environment
     */
    static @Nullable Microcopy inUseReason(@NonNull Row environment) {
        var usage = DeleteImpact.environmentUsage(environment.get(EnvironmentModel.ID));
        return usage.isEmpty() ? null : usage.refusal();
    }

    /**
     * Keeps a variable's value in the column its kind declares.
     *
     * AIDEV-NOTE: on CREATE the form has no record and so offers only the plain carrier (see the bindings); a variable
     * created as a secret had its value typed there, and the submitted kind is the operator's declaration of what the
     * value IS, so it moves to the secret carrier once. On UPDATE a submitted kind retires the carrier that kind does
     * not use: without that a switch is impossible, since the model's one-carrier-per-kind hook refuses a secret row
     * still holding plain_value and the retired column is hidden. Both read the kind only when the write carries it,
     * because the inline-cell lane submits exactly one entry.
     */
    static void placeValueInItsCarrier(@NonNull RowSave save) {
        String kindName = InstanceVariableModel.KIND.getName();
        if (!save.values().containsKey(kindName)) {
            return;
        }
        VariableKind kind = declaredKind(save.values().get(kindName));
        Row row = save.row();
        if (save.isCreate()) {
            if (kind.isSecret() && save.values().containsKey(InstanceVariableModel.PLAIN_VALUE.getName())) {
                row.set(InstanceVariableModel.SECRET_VALUE,
                    (String) save.values().get(InstanceVariableModel.PLAIN_VALUE.getName()));
                row.set(InstanceVariableModel.PLAIN_VALUE, null);
            }
            return;
        }
        if (kind.isSecret()) {
            row.set(InstanceVariableModel.PLAIN_VALUE, null);
        } else {
            row.set(InstanceVariableModel.SECRET_VALUE, null);
        }
    }

    /**
     * Names the key and the environment it leaves; a workload reads its environment's values only at deploy
     * (InstanceVariables.valuesFor), so anything running keeps the value until its next deploy.
     *
     * @param variable the variable, null for the record-less fallback; one whose environment does not resolve keeps it
     */
    static @NonNull ConfirmationSpec variableDeleteBody(@Nullable Row variable) {
        String environment = variable == null ? null
            : DeleteImpact.environmentNameOf(variable.get(InstanceVariableModel.ENVIRONMENT_ID));
        String key = variable == null ? null : variable.get(InstanceVariableModel.KEY);
        if (environment == null || environment.isBlank() || key == null || key.isBlank()) {
            return DeleteConfirmation.body(Microcopy.of("delete_confirm").withFilter("scope", "environment_variable"));
        }
        return DeleteConfirmation.body(Microcopy.of("delete_confirm_named")
            .withFilter("scope", "environment_variable")
            .withArg("key", key)
            .withArg("environment", environment));
    }

    /**
     * EDITABLE only for the carrier the record's kind stores; CREATE has no record and so offers the plain carrier.
     *
     * AIDEV-NOTE: the form renderer and the write's FieldAccess enforcement ask this SAME resolver about the SAME row,
     * so a hand-crafted submission cannot write the carrier the form withheld. It is a server-side decision: flipping
     * the kind select does not swap the field live; the new carrier appears after the save.
     */
    private static FieldAccess.@NonNull Decision carrierAccess(@Nullable Object record,
                                                               @NonNull VariableKind carrierKind) {
        VariableKind stored = record instanceof Row row ? declaredKind(row.get(InstanceVariableModel.KIND))
            : VariableKind.PLAIN;
        return carrierKind == stored ? FieldAccess.Decision.EDITABLE : FieldAccess.Decision.HIDDEN;
    }

    /** Absent or blank is the column's default (plain); anything unrecognized fails closed as a secret. */
    private static @NonNull VariableKind declaredKind(@Nullable Object token) {
        return token == null || token.toString().isBlank() ? VariableKind.PLAIN : VariableKind.of(token);
    }

    /** A related-record link's {@code ?<parent>_id=} prefill. */
    private static @NonNull Map<String, Object> prefill(@NonNull PanelRequest request,
                                                        @NonNull ParameterDefinition<Integer> param,
                                                        @NonNull String field) {
        Integer id = CmsSupport.prefill(request.conduit(), param);
        return id != null ? Map.of(field, id) : Map.of();
    }

    /** The parent the quick-add bar adds into: the {@code ?<parent>_id=} prefill, else the tab's own record. */
    private static @NonNull Map<String, Object> preset(@NonNull AccessContext access, @NonNull String field,
                                                       @NonNull String parentSlug) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer id = CmsSupport.scopedParentId(conduit, field, parentSlug);
        return id != null ? Map.of(field, id) : Map.of();
    }
}
