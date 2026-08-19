package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
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
 * Environment-scoped variables: the SAME table-backed variable mechanism instances
 * use (one encrypted secret carrier, one plain carrier, one write funnel), scoped to
 * the rows owned by an ENVIRONMENT. Instance-owned rows are invisible here -- they
 * belong to the instance surfaces -- and the scoped access predicate makes a create
 * without an environment refuse rather than silently landing as an orphan.
 */
public final class EnvironmentVariableResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceVariableModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
            .build())
        .add(InstanceVariableModel.KEY)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceVariableModel.KIND))
        .add(InstanceVariableModel.PLAIN_VALUE)
        .add(InstanceVariableModel.SECRET_VALUE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceVariableModel.KEY).filterable().build())
        .column(ColumnSpec.fromField(InstanceVariableModel.KIND).filterable().build())
        .column(ColumnSpec.fromField(InstanceVariableModel.ENVIRONMENT_ID)
            .relation(RelationPick.of(InstanceVariableModel.ENVIRONMENT_ID,
                EnvironmentModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "environment_variable"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "environment_variable"); }
    @Override public @NonNull String slug() { return "environment-variables"; }
    @Override public @NonNull Model model() { return Models.get(InstanceVariableModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 15; }

    @Override public boolean showInNav() { return false; }
    @Override public @NonNull Icon icon() { return Icon.of("sliders"); }

    /** Only environment-owned rows exist on this surface, list AND load AND create. */
    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(
            InstanceVariableModel.ENVIRONMENT_ID.isNotNull()));
    }

    /** Related-record prefill: /new?environment_id=N arrives preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String environmentId = conduit.getQueryParam("environment_id");
        if (environmentId != null && !environmentId.isEmpty()) {
            try {
                values.put("environment_id", Integer.parseInt(environmentId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return values;
    }
}
