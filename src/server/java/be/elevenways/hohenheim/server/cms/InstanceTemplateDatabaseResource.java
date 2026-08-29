package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
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
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The managed databases an instance template declares (InstanceTemplateFileResource
 * generalized). Hidden from the sidebar -- reached through a template's Contents tab.
 */
public final class InstanceTemplateDatabaseResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceTemplateDatabaseModel.TEMPLATE_ID,
            InstanceTemplateModel.MODEL_ID).build())
        .add(InstanceTemplateDatabaseModel.ENGINE)
        .add(InstanceTemplateDatabaseModel.ENV_PREFIX)
        .add(InstanceTemplateDatabaseModel.IMAGE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.ENV_PREFIX).copyable().build())
        .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.ENGINE).build())
        .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.IMAGE).build())
        .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.TEMPLATE_ID)
            .relation(RelationPick.of(InstanceTemplateDatabaseModel.TEMPLATE_ID,
                InstanceTemplateModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_template_database"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "template_database"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "template_database"); }
    @Override public @NonNull String slug() { return "instance-template-databases"; }
    @Override public @NonNull Model model() { return Models.get(InstanceTemplateDatabaseModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceTemplateDatabaseModel.ENV_PREFIX);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 19; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instance-templates",
            row -> row.get(InstanceTemplateDatabaseModel.TEMPLATE_ID)).tab("contents");
    }

    /** The Contents tab links here with ?template_id= so the pick arrives preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        values.put("env_prefix", InstanceDatabaseModel.DEFAULT_PREFIX);
        String templateId = conduit.getQueryParam("template_id");
        if (templateId != null && !templateId.isEmpty()) {
            try {
                values.put("template_id", Integer.parseInt(templateId));
            } catch (NumberFormatException ignored) {
                // Malformed prefill: render the bare form.
            }
        }
        return Map.copyOf(values);
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        return super.persistRow(validate(coerced, null), accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        super.updateRow(existing, validate(coerced, existing), accessContext);
    }

    /**
     * What the create-from-template funnel would refuse, refused while AUTHORING instead:
     * the template's kind must run on a driver with link networks, the engine must be one
     * the server knows, and two declarations of one template may not share a prefix
     * (case-insensitively -- the injection uppercases it).
     *
     * @return the coerced values with the prefix canonicalised to upper case
     */
    private static @NonNull Map<String, Object> validate(@NonNull Map<String, Object> raw,
                                                         @Nullable Row existing) {
        Map<String, Object> coerced = CmsSupport.mutable(raw);
        Integer templateId = CmsSupport.intOf(coerced, existing,
            InstanceTemplateDatabaseModel.TEMPLATE_ID);
        if (templateId == null) {
            throw Violations.ofField("template_id", null,
                CmsSupport.violationText("template_required"));
        }
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        if (template == null) {
            throw Violations.ofField("template_id", templateId,
                CmsSupport.violationText("template_missing"));
        }
        String kind = template.get(InstanceTemplateModel.KIND);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null || !handler.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER)) {
            throw Violations.ofField("template_id", templateId,
                CmsSupport.violationText("instance_kind_no_injection")
                    .withArg("kind", String.valueOf(kind)));
        }
        String engine = CmsSupport.textOf(coerced, existing, InstanceTemplateDatabaseModel.ENGINE);
        if (ManagedDatabase.Engine.forToken(engine) == null) {
            throw Violations.ofField("engine", engine,
                CmsSupport.violationText("unknown_engine").withArg("engine", engine));
        }
        String prefix = CmsSupport.textOf(coerced, existing, InstanceTemplateDatabaseModel.ENV_PREFIX)
            .trim().toUpperCase(Locale.ROOT);
        if (!prefix.matches(InstanceDatabaseModel.PREFIX_PATTERN)) {
            throw Violations.ofField("env_prefix", prefix,
                CmsSupport.violationText("prefix_format"));
        }
        Integer existingId = existing != null
            ? existing.get(InstanceTemplateDatabaseModel.ID) : null;
        for (Row sibling : Models.get(InstanceTemplateDatabaseModel.class)
                .findByTemplateId(templateId)) {
            if (existingId != null && existingId.equals(sibling.get(InstanceTemplateDatabaseModel.ID))) {
                continue;
            }
            String siblingPrefix = sibling.get(InstanceTemplateDatabaseModel.ENV_PREFIX);
            if (siblingPrefix != null && siblingPrefix.equalsIgnoreCase(prefix)) {
                throw Violations.ofField("env_prefix", prefix,
                    CmsSupport.violationText("template_database_prefix_taken")
                        .withArg("prefix", prefix));
            }
        }
        if (coerced.containsKey("env_prefix")) {
            coerced.put("env_prefix", prefix);
        }
        return coerced;
    }
}
