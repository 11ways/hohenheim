package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Environments of a project (production, staging, ...): a plain generated resource.
 * The ProjectGuards write funnel enforces the grouping rules (no re-homing while in
 * use, no deleting a referenced environment) on every writer, not here.
 */
public final class EnvironmentResource extends RowResource {

    /** The list's quick-add entries; the project rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(EnvironmentModel.NAME.getName(), EnvironmentModel.DESCRIPTION.getName())
        .presets(EnvironmentModel.PROJECT_ID.getName());

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(EnvironmentModel.PROJECT_ID, ProjectModel.MODEL_ID).build())
        .add(EnvironmentModel.NAME)
        .add(EnvironmentModel.DESCRIPTION)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // AIDEV-NOTE: the project stays a VISIBLE relation column rather than the name's
        // subtext -- relation titles resolve for visible columns only, so a hidden
        // project_id would render the raw foreign key under every name.
        .column(ColumnSpec.fromField(EnvironmentModel.NAME).filterable().subtext("description").build())
        .column(ColumnSpec.fromField(EnvironmentModel.DESCRIPTION).hidden().build())
        .column(ColumnSpec.fromField(EnvironmentModel.PROJECT_ID)
            .relation(RelationPick.of(EnvironmentModel.PROJECT_ID, ProjectModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "environment"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "environment"); }
    @Override public @NonNull String slug() { return "environments"; }
    @Override public @NonNull Model model() { return Models.get(EnvironmentModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** Name and description are all an environment carries. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(EnvironmentModel.NAME, EnvironmentModel.DESCRIPTION);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 15; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("layer-group"); }

    /** Related-record prefill: /new?project_id=N arrives preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String projectId = conduit.getQueryParam("project_id");
        if (projectId != null && !projectId.isEmpty()) {
            try {
                values.put("project_id", Integer.parseInt(projectId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return values;
    }

    /**
     * The list's quick-add bar; the project rides along as a host-supplied preset, exactly
     * like the {@code ?project_id=} prefill the full form already reads.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /** The project the bar adds into: the {@code ?project_id=} prefill, else the tab's own record. */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer projectId = CmsSupport.scopedParentId(conduit,
            EnvironmentModel.PROJECT_ID.getName(), "projects");
        return projectId != null
            ? Map.of(EnvironmentModel.PROJECT_ID.getName(), projectId) : Map.of();
    }

    /**
     * Both, and there is nothing else on the record.
     *
     * AIDEV-NOTE: PROJECT_ID stays off this list for two reasons that agree -- it is a
     * RelationPick, which is outside the compact cell subset anyway, and re-homing an
     * environment that is in use is refused by the {@code ProjectGuards} write funnel.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(EnvironmentModel.NAME, EnvironmentModel.DESCRIPTION);
    }

    /**
     * The variables owned by these environments, demoted out of the sidebar.
     */
    @Override
    public @NonNull List<HeaderAction> headerActions() {
        List<HeaderAction> actions = new ArrayList<>(super.headerActions());
        actions.addAll(List.of(
            CmsSupport.relatedList("environment_variables_link", "environment-variables", "environment_variable", Icon.of("sliders"))));
        return actions;
    }

}
