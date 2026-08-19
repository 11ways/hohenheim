package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
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
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed variables of an instance template. Hidden from the sidebar -- reached through
 * a template's Contents tab. The registry-driven type selector switches the per-type
 * settings sub-form client-side.
 */
public final class InstanceTemplateVariableResource extends RowResource {

    /** The Contents tab's quick-add entries; the template rides along as a preset. */
    private static final QuickCreateSpec QUICK_CREATE = QuickCreateSpec
        .of(InstanceTemplateVariableModel.KEY.getName(),
            InstanceTemplateVariableModel.LABEL.getName(),
            InstanceTemplateVariableModel.TYPE.getName(),
            InstanceTemplateVariableModel.REQUIRED.getName())
        .presets(InstanceTemplateVariableModel.TEMPLATE_ID.getName());

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
        // The key is what gets typed into a template body, so it carries the chip; the
        // label is the human name of the same thing.
        .column(ColumnSpec.fromField(InstanceTemplateVariableModel.KEY)
            .subtext("label").copyable().build())
        .column(ColumnSpec.fromField(InstanceTemplateVariableModel.LABEL).hidden().build())
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
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    /** A variable is hunted for by its key or by the words the form shows for it. */
    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceTemplateVariableModel.KEY, InstanceTemplateVariableModel.LABEL, InstanceTemplateVariableModel.DESCRIPTION);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 17; }
    @Override public @NonNull Icon icon() { return Icon.of("sliders"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instance-templates",
            row -> row.get(InstanceTemplateVariableModel.TEMPLATE_ID)).tab("contents");
    }

    /**
     * The Contents tab's quick-add bar: the four answers a variable needs to exist, with
     * the template riding along as a host-supplied preset.
     *
     * AIDEV-NOTE: TYPE renders even though a type declaring per-type SETTINGS cannot be
     * completed here -- the sub-schema fact rides the type option itself, so the framework
     * flips Add into a link to the full form on its own. Adding a variable type therefore
     * needs no change here.
     */
    @Override
    public @Nullable QuickCreateSpec quickCreate() {
        return QUICK_CREATE;
    }

    /** The template the bar adds into: the {@code ?template_id=} prefill, else the tab's own record. */
    @Override
    public @NonNull Map<String, Object> quickCreatePresetValues(@NonNull AccessContext accessContext) {
        Conduit conduit = accessContext.conduit();
        if (conduit == null) {
            return Map.of();
        }
        Integer templateId = CmsSupport.scopedParentId(conduit,
            InstanceTemplateVariableModel.TEMPLATE_ID.getName(), "instance-templates");
        return templateId != null
            ? Map.of(InstanceTemplateVariableModel.TEMPLATE_ID.getName(), templateId) : Map.of();
    }

    /**
     * The wording and the optionality of a variable, which are what an operator retypes
     * while reading a template's Contents tab.
     *
     * AIDEV-NOTE: TYPE is deliberately absent -- switching it swaps the SETTINGS
     * sub-schema the type declares, so the write would drop or demand typed extras a
     * one-cell editor never showed. KEY is absent because it is the {@code {{KEY}}}
     * substitution token: every file body and command that names it keeps naming the OLD
     * spelling, so a rename is a full-form act with the rest of the template in view.
     */
    @Override
    public @NonNull List<Field<?, ?>> inlineEditableFields() {
        return List.of(InstanceTemplateVariableModel.LABEL,
            InstanceTemplateVariableModel.DESCRIPTION,
            InstanceTemplateVariableModel.REQUIRED,
            InstanceTemplateVariableModel.DEFAULT_VALUE);
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
