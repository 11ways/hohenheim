package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
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
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed variables of an instance template. Hidden from the sidebar -- reached through
 * a template's Contents tab. The registry-driven type selector switches the per-type
 * settings sub-form client-side.
 */
public final class InstanceTemplateVariableResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceTemplateVariableModel.TEMPLATE_ID,
            InstanceTemplateModel.MODEL_ID).build())
        .add(InstanceTemplateVariableModel.KEY)
        .add(InstanceTemplateVariableModel.LABEL)
        .add(InstanceTemplateVariableModel.DESCRIPTION)
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateVariableModel.TYPE))
        .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateVariableModel.SETTINGS))
        .add(InstanceTemplateVariableModel.REQUIRED)
        .add(InstanceTemplateVariableModel.DEFAULT_VALUE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceTemplateVariableModel.KEY).build())
        .column(ColumnSpec.fromField(InstanceTemplateVariableModel.TYPE).build())
        .column(ColumnSpec.fromField(InstanceTemplateVariableModel.REQUIRED).build())
        .column(ColumnSpec.fromField(InstanceTemplateVariableModel.TEMPLATE_ID)
            .relation(RelationPick.of(InstanceTemplateVariableModel.TEMPLATE_ID,
                InstanceTemplateModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_template_variable"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "template_variable"); }
    @Override public @NonNull String slug() { return "instance-template-variables"; }
    @Override public @NonNull Model model() { return Models.get(InstanceTemplateVariableModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.COMPUTE_GROUP; }
    @Override public int navOrder() { return 17; }
    @Override public @NonNull Icon icon() { return Icon.of("sliders"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instance-templates",
            row -> row.get(InstanceTemplateVariableModel.TEMPLATE_ID)).tab("contents");
    }

    /** The Contents tab links here with ?template_id= so the pick arrives preselected. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
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
        requireUniqueKey(coerced, null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        requireUniqueKey(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /** One row per (template, key): two rows answering for one env name is a coin flip. */
    private void requireUniqueKey(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Object templateId = coerced.containsKey("template_id") ? coerced.get("template_id")
            : existing != null ? existing.get(InstanceTemplateVariableModel.TEMPLATE_ID) : null;
        Object key = coerced.containsKey("key") ? coerced.get("key")
            : existing != null ? existing.get(InstanceTemplateVariableModel.KEY) : null;
        if (!(templateId instanceof Integer template) || !(key instanceof String keyName)) {
            return;   // coercion/required validation owns the missing-value refusals
        }
        Integer existingId = existing != null
            ? existing.get(InstanceTemplateVariableModel.ID) : null;
        for (Row sibling : this.model().find()
                .where(InstanceTemplateVariableModel.TEMPLATE_ID.eq(template))
                .where(InstanceTemplateVariableModel.KEY.eq(keyName)).all()) {
            if (!sibling.get(InstanceTemplateVariableModel.ID).equals(existingId)) {
                throw Violations.ofField("key", keyName,
                    CmsSupport.violationText("variable_key_taken").withArg("key", keyName));
            }
        }
    }
}
