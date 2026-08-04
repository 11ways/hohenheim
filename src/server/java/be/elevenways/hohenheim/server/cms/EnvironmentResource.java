package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Environments of a project (production, staging, ...): a plain generated resource.
 * The ProjectGuards write funnel enforces the grouping rules (no re-homing while in
 * use, no deleting a referenced environment) on every writer, not here.
 */
public final class EnvironmentResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(EnvironmentModel.PROJECT_ID, ProjectModel.MODEL_ID).build())
        .add(EnvironmentModel.NAME)
        .add(EnvironmentModel.DESCRIPTION)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(EnvironmentModel.NAME).filterable().build())
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
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.INFRA_GROUP; }
    @Override public int navOrder() { return 15; }
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
}
