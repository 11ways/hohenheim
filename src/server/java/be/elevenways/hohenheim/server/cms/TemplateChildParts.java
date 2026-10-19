package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.server.instance.TemplateDeclarationGuards;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.QuickCreateSpec;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FieldFormEntryRegistry;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The template's authored children: shared parent/read/write parts, each declaration's own form and list.
 * Catalog labels, descriptions and file bodies remain verbatim; field labels and refusals are localized.
 *
 * AIDEV-NOTE: authoring invariants live in model save hooks. Files use the shared instance/template file rule;
 * no path/mode parser or per-resource validation copy is declared here.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class TemplateChildParts {

    /** The column naming a declaration's template, the same in every declaration's list (the tab hides it). */
    public static final String OWNER_COLUMN = InstanceTemplateVariableModel.TEMPLATE_ID.getName();
    private TemplateChildParts() {}

    public static @NonNull PanelResource<Row> variables() {
        FormSpec spec = FormSpec.builder()
            .add(RelationPick.of(InstanceTemplateVariableModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build())
            .add(InstanceTemplateVariableModel.KEY).add(InstanceTemplateVariableModel.LABEL)
            .add(InstanceTemplateVariableModel.DESCRIPTION)
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateVariableModel.TYPE))
            .add(FieldFormEntryRegistry.INSTANCE.deriveEntry(InstanceTemplateVariableModel.SETTINGS))
            .add(InstanceTemplateVariableModel.REQUIRED).add(InstanceTemplateVariableModel.DEFAULT_VALUE).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.KEY).subtext("label").copyable().build())
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.LABEL).hidden().build())
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.TYPE).build())
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.REQUIRED).build())
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.DEFAULT_VALUE).build())
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateVariableModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID)
                        .build()).build())
            .build();
        return entry(InstanceTemplateVariableModel.MODEL_ID, HohenheimSlugs.INSTANCE_TEMPLATE_VARIABLES,
            HohenheimMicrocopy.TEMPLATE_VARIABLE, 17,
            Icon.of("sliders"),
                contents(InstanceTemplateVariableModel.TEMPLATE_ID))
            .form(ResourceForm.<Row>of(spec).quickCreate(QuickCreateSpec.of(InstanceTemplateVariableModel.KEY.getName(),
                    InstanceTemplateVariableModel.LABEL.getName(), InstanceTemplateVariableModel.TYPE.getName(),
                    InstanceTemplateVariableModel.REQUIRED.getName())
                    .presets(InstanceTemplateVariableModel.TEMPLATE_ID.getName()))
                .quickCreatePresets(access -> CmsSupport.parentPreset(access,
                    InstanceTemplateVariableModel.TEMPLATE_ID.getName(), HohenheimSlugs.INSTANCE_TEMPLATES))
                .inlineEditable(InstanceTemplateVariableModel.LABEL, InstanceTemplateVariableModel.DESCRIPTION,
                    InstanceTemplateVariableModel.REQUIRED, InstanceTemplateVariableModel.DEFAULT_VALUE).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateVariableModel.KEY,
                InstanceTemplateVariableModel.LABEL, InstanceTemplateVariableModel.DESCRIPTION)
                .emptyDescription(HohenheimMicrocopy.TEMPLATE_CONTENTS.of("no_variables_description")).build()).build();
    }

    public static @NonNull PanelResource<Row> files() {
        FormSpec spec = FormSpec.builder()
            .add(RelationPick.of(InstanceTemplateFileModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build())
            .add(InstanceTemplateFileModel.CONTAINER_PATH).add(InstanceTemplateFileModel.CONTENT)
            .add(InstanceTemplateFileModel.MODE).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateFileModel.CONTAINER_PATH).subtext("mode").copyable().build())
            .column(ColumnSpec.fromField(InstanceTemplateFileModel.MODE).hidden().build())
            .column(ColumnSpec.fromField(InstanceTemplateFileModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateFileModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID)
                        .build()).build())
            .build();
        return entry(InstanceTemplateFileModel.MODEL_ID, HohenheimSlugs.INSTANCE_TEMPLATE_FILES,
            HohenheimMicrocopy.TEMPLATE_FILE, 18,
            Icon.of("file-code"),
                contents(InstanceTemplateFileModel.TEMPLATE_ID))
            .form(ResourceForm.<Row>of(spec)
                .inlineEditable(InstanceTemplateFileModel.CONTAINER_PATH, InstanceTemplateFileModel.MODE).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateFileModel.CONTAINER_PATH)
                .emptyDescription(HohenheimMicrocopy.TEMPLATE_CONTENTS.of("no_files_description")).build()).build();
    }

    /**
     * A template's volumes: the size limit is entered in megabytes, the volume's own form field ({@link
     * VolumeOperations#QUOTA_MB}), and stored in bytes through the volume's one conversion; the list says it as a
     * size. The template is the volume's fixed parent, as an instance is an instance volume's: named on every view,
     * written from the create's parent, never posted.
     */
    public static @NonNull PanelResource<Row> volumes() {
        FormSpec spec = FormSpec.builder()
            .add(RelationPick.of(InstanceTemplateVolumeModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build())
            .add(InstanceTemplateVolumeModel.NAME).add(InstanceTemplateVolumeModel.CONTAINER_PATH)
            .add(VolumeOperations.QUOTA_MB).add(InstanceTemplateVolumeModel.EXCLUSIVE).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.NAME).copyable().build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.CONTAINER_PATH).build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.QUOTA_BYTES).byteSize()
                .absent(HohenheimMicrocopy.INSTANCE_VOLUME.of("no_quota")).build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.EXCLUSIVE).build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateVolumeModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID)
                        .build()).build())
            .build();
        String quotaMb = VolumeOperations.QUOTA_MB.getName();
        return entry(InstanceTemplateVolumeModel.MODEL_ID, HohenheimSlugs.INSTANCE_TEMPLATE_VOLUMES,
            HohenheimMicrocopy.TEMPLATE_VOLUME, 20,
            Icon.of("folder-open"),
                contents(InstanceTemplateVolumeModel.TEMPLATE_ID).fixed())
            .reads(ResourceReads.rows().mapValues(Set.of(quotaMb), (row, values) -> {
                Map<String, Object> mapped = new LinkedHashMap<>(values);
                mapped.put(quotaMb, VolumeOperations.quotaMegabytes(row.get(InstanceTemplateVolumeModel.QUOTA_BYTES)));
                return mapped;
            }))
            .writes(ResourceMutations.rows().create().update().delete()
                // The limit is stored in bytes: a submitted MB value (absent: left alone) is converted before the
                // template's declaration guards judge the row.
                .beforeSave(save -> {
                    if (save.values().containsKey(quotaMb)) {
                        save.row().set(InstanceTemplateVolumeModel.QUOTA_BYTES,
                            VolumeOperations.quotaBytes((Integer) save.values().get(quotaMb)));
                    }
                })
                .build())
            .form(ResourceForm.<Row>of(spec).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateVolumeModel.NAME)
                .emptyDescription(HohenheimMicrocopy.TEMPLATE_CONTENTS.of("no_volumes_description")).build()).build();
    }

    /**
     * The template's databases: the env prefix names one, its engine under it, so a phone keeps both in view. The
     * template is the declaration's fixed parent, like a volume's.
     */
    public static @NonNull PanelResource<Row> databases() {
        FormSpec spec = FormSpec.builder()
            .add(RelationPick.of(InstanceTemplateDatabaseModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build())
            .add(InstanceTemplateDatabaseModel.ENGINE).add(InstanceTemplateDatabaseModel.ENV_PREFIX)
            .add(InstanceTemplateDatabaseModel.IMAGE).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.ENV_PREFIX).copyable()
                .subtext(InstanceTemplateDatabaseModel.ENGINE.getName()).build())
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.ENGINE).hidden().build())
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.IMAGE).build())
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateDatabaseModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID)
                        .build()).build())
            .build();
        return entry(InstanceTemplateDatabaseModel.MODEL_ID, HohenheimSlugs.INSTANCE_TEMPLATE_DATABASES,
            HohenheimMicrocopy.TEMPLATE_DATABASE, 19,
            Icon.of("database"),
                contents(InstanceTemplateDatabaseModel.TEMPLATE_ID).fixed())
            .form(ResourceForm.<Row>of(spec).createDefaults((access, panel) -> {
                Map<String, Object> values = new LinkedHashMap<>(spec.defaultValues());
                values.put(InstanceTemplateDatabaseModel.ENV_PREFIX.getName(), InstanceDatabaseModel.DEFAULT_PREFIX);
                return Map.copyOf(values);
            }).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateDatabaseModel.ENV_PREFIX)
                .emptyDescription(HohenheimMicrocopy.TEMPLATE_CONTENTS.of("no_databases_description")).build()).build();
    }

    /** @return the template a declaration belongs to, reached through its Contents tab */
    private static @NonNull ResourceParent<Row> contents(@NonNull IntegerField owner) {
        return ResourceParent.of(HohenheimSlugs.INSTANCE_TEMPLATES, owner).tab(HohenheimSlugs.Tab.CONTENTS);
    }

    private static PanelResource.@NonNull Builder<Row> entry(Identifier model, String slug, HohenheimMicrocopy scope,
        int order,
            Icon icon, ResourceParent<Row> parent) {
        TemplateDeclarationGuards.init();
        return PanelResource.builder(model, slug, SubjectType.record(model))
            .label(scope.of("plural"))
            .recordLabel(scope.of("singular"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(order).icon(icon).showInNav(false)
            .parent(parent)
            .reads(ResourceReads.rows()).writes(ResourceMutations.rows().create().update().delete().build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions());
    }
}
