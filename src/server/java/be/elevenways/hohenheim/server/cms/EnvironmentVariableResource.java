package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Environment-scoped variables: the SAME table-backed variable mechanism instances
 * use (one encrypted secret carrier, one plain carrier, one write funnel), scoped to
 * the rows owned by an ENVIRONMENT. Instance-owned rows are invisible here -- they
 * belong to the instance surfaces -- and the scoped access predicate makes a create
 * without an environment refuse rather than silently landing as an orphan.
 */
public final class EnvironmentVariableResource extends RowResource {

    /** The list's quick-add entries; the environment rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(InstanceVariableModel.KEY.getName(), InstanceVariableModel.KIND.getName(),
            InstanceVariableModel.PLAIN_VALUE.getName())
        .presets(InstanceVariableModel.ENVIRONMENT_ID.getName());

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceVariableModel.ENVIRONMENT_ID, EnvironmentModel.MODEL_ID)
            .build())
        .add(InstanceVariableModel.KEY)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceVariableModel.KIND))
        .add(InstanceVariableModel.PLAIN_VALUE)
        .add(InstanceVariableModel.SECRET_VALUE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        // The chip carries the KEY: it is what gets pasted into a compose file or a shell.
        .column(ColumnSpec.fromField(InstanceVariableModel.KEY).filterable()
            .subtext("kind").copyable().build())
        .column(ColumnSpec.fromField(InstanceVariableModel.KIND).filterable().hidden().build())
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
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** The key only: PLAIN_VALUE would leak a lookup over config values, and SECRET_VALUE is a secret the search layer refuses outright. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceVariableModel.KEY);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 15; }

    /**
     * Demoted out of the sidebar, so this sentence reaches a reader through the panel
     * index and the related-pages menu of the list that names it.
     */
    @Override public @Nullable Microcopy description() { return CmsSupport.navHint("environment_variable"); }

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

    /**
     * The list's quick-add bar; the environment rides along as a host-supplied preset.
     *
     * AIDEV-NOTE: the bar renders the PLAIN carrier only. SECRET_VALUE is a
     * {@code .secret()} column, and a secret typed into a one-line bar sitting in a page
     * everyone can see is not the place to mint one -- the full form is, where the
     * framework's own mask/keep-on-blank pipeline is the whole surface.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /** The environment the bar adds into: the {@code ?environment_id=} prefill, else the tab's record. */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer environmentId = CmsSupport.scopedParentId(conduit,
            InstanceVariableModel.ENVIRONMENT_ID.getName(), "environments");
        return environmentId != null
            ? Map.of(InstanceVariableModel.ENVIRONMENT_ID.getName(), environmentId) : Map.of();
    }

    /**
     * The plain value only -- retyping a config value is the everyday edit here.
     *
     * AIDEV-NOTE: it is consumed at DEPLOY ({@code InstanceService} substitutes it into
     * the workload's environment), and {@code InstanceVariableModel} is NOT one of
     * {@code ProxyReloadHooks.ROUTING_MODELS}, so nothing reconciles on the write itself.
     * The new value reaches the workload at its next deploy, not at this click.
     *
     * AIDEV-NOTE: KIND is excluded because it is the carrier DISCRIMINATOR -- switching
     * plain/secret decides which COLUMN holds the value, so flipping it in a cell leaves
     * the value behind in the other one. KEY is excluded because substitution resolves BY
     * it: renaming it in place silently unbinds every reference that named the old key.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceVariableModel.PLAIN_VALUE);
    }
}
