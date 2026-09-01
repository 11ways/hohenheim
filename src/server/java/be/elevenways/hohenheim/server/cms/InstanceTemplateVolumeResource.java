package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceTemplates;
import be.elevenways.hohenheim.server.instance.InstanceTemplates.VolumeDeclaration;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The volumes an instance template declares (InstanceTemplateDatabaseResource
 * generalized). Hidden from the sidebar -- reached through a template's Contents tab.
 *
 * AIDEV-NOTE: these rows are DECLARATIONS about a future instance, so unlike
 * {@link InstanceVolumeResource} nothing here reaches a host: no directory is minted, no
 * quota applied and no destroy-with-data verb exists. The instance-side copy
 * ({@code InstanceTemplates.createFromTemplate}) is what turns one into a real volume.
 */
public final class InstanceTemplateVolumeResource extends RowResource {

    private final FormSpec formSpec = FormSpec.builder()
        .add(RelationPick.of(InstanceTemplateVolumeModel.TEMPLATE_ID,
            InstanceTemplateModel.MODEL_ID).build())
        .add(InstanceTemplateVolumeModel.NAME)
        .add(InstanceTemplateVolumeModel.CONTAINER_PATH)
        .add(InstanceTemplateVolumeModel.QUOTA_BYTES)
        .add(InstanceTemplateVolumeModel.EXCLUSIVE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.NAME).copyable().build())
        .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.CONTAINER_PATH).build())
        .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.QUOTA_BYTES).build())
        .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.EXCLUSIVE).build())
        .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.TEMPLATE_ID)
            .relation(RelationPick.of(InstanceTemplateVolumeModel.TEMPLATE_ID,
                InstanceTemplateModel.MODEL_ID).build())
            .build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_template_volume"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "template_volume"); }
    @Override public @Nullable Microcopy recordLabel() { return Microcopy.of("singular").withFilter("scope", "template_volume"); }
    @Override public @NonNull String slug() { return "instance-template-volumes"; }
    @Override public @NonNull Model model() { return Models.get(InstanceTemplateVolumeModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }

    @Override
    public @NonNull List<Field<?, ?>> searchFields() {
        return List.of(InstanceTemplateVolumeModel.NAME);
    }

    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 20; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instance-templates",
            row -> row.get(InstanceTemplateVolumeModel.TEMPLATE_ID)).tab("contents");
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
        validate(coerced, null);
        return super.persistRow(coerced, accessContext);
    }

    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        validate(coerced, existing);
        super.updateRow(existing, coerced, accessContext);
    }

    /**
     * What the create-from-template funnel would refuse, refused while AUTHORING instead.
     *
     * AIDEV-NOTE: it asks {@link InstanceTemplates#requireVolumesDeclarable} -- the SAME
     * rule set the create and the import ask -- over this template's WHOLE declared set
     * with the submitted row folded in, because two of those rules (the kind, the
     * one-container-path-one-directory collision) are facts about the set rather than
     * about the row being saved.
     */
    private static void validate(@NonNull Map<String, Object> coerced, @Nullable Row existing) {
        Integer templateId = CmsSupport.intOf(coerced, existing,
            InstanceTemplateVolumeModel.TEMPLATE_ID);
        if (templateId == null) {
            throw Violations.ofField("template_id", null,
                CmsSupport.violationText("template_required"));
        }
        Row template = Models.get(InstanceTemplateModel.class).findById(templateId);
        if (template == null) {
            throw Violations.ofField("template_id", templateId,
                CmsSupport.violationText("template_missing"));
        }

        Object quota = CmsSupport.valueOf(coerced, existing,
            InstanceTemplateVolumeModel.QUOTA_BYTES);
        VolumeDeclaration submitted = new VolumeDeclaration(
            CmsSupport.textOf(coerced, existing, InstanceTemplateVolumeModel.NAME),
            CmsSupport.textOf(coerced, existing, InstanceTemplateVolumeModel.CONTAINER_PATH),
            quota instanceof Number number ? number.longValue() : null,
            Boolean.TRUE.equals(CmsSupport.valueOf(coerced, existing,
                InstanceTemplateVolumeModel.EXCLUSIVE)));

        Integer existingId = existing != null
            ? existing.get(InstanceTemplateVolumeModel.ID) : null;
        List<VolumeDeclaration> declared = new ArrayList<>();
        declared.add(submitted);
        for (Row sibling : Models.get(InstanceTemplateVolumeModel.class)
                .findByTemplateId(templateId)) {
            if (existingId != null && existingId.equals(sibling.get(InstanceTemplateVolumeModel.ID))) {
                continue;
            }
            declared.add(VolumeDeclaration.of(sibling));
        }
        InstanceTemplates.requireVolumesDeclarable(
            InstanceKinds.getHandler(template.get(InstanceTemplateModel.KIND)), declared);
    }
}
