package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateDatabaseModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceTemplateVariableModel;
import be.elevenways.hohenheim.model.InstanceTemplateVolumeModel;
import be.elevenways.hohenheim.server.instance.TemplateDeclarationGuards;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
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
    public static final String VARIABLES = "instance-template-variables";
    public static final String FILES = "instance-template-files";
    public static final String VOLUMES = "instance-template-volumes";
    public static final String DATABASES = "instance-template-databases";
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
            .column(ColumnSpec.fromField(InstanceTemplateVariableModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateVariableModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build()).build())
            .build();
        return entry(InstanceTemplateVariableModel.MODEL_ID, VARIABLES, "template_variable", 17, Icon.of("sliders"),
                InstanceTemplateVariableModel.TEMPLATE_ID)
            .form(form(spec, false).quickCreate(QuickCreateSpec.of(InstanceTemplateVariableModel.KEY.getName(),
                    InstanceTemplateVariableModel.LABEL.getName(), InstanceTemplateVariableModel.TYPE.getName(),
                    InstanceTemplateVariableModel.REQUIRED.getName()).presets(InstanceTemplateVariableModel.TEMPLATE_ID.getName()))
                .quickCreatePresets(access -> {
                    if (access.conduit() == null) return Map.of();
                    Integer owner = CmsSupport.scopedParentId(access.conduit(), InstanceTemplateVariableModel.TEMPLATE_ID.getName(),
                        HohenheimSlugs.INSTANCE_TEMPLATES);
                    return owner == null ? Map.of() : Map.of(InstanceTemplateVariableModel.TEMPLATE_ID.getName(), owner);
                })
                .inlineEditable(InstanceTemplateVariableModel.LABEL, InstanceTemplateVariableModel.DESCRIPTION,
                    InstanceTemplateVariableModel.REQUIRED, InstanceTemplateVariableModel.DEFAULT_VALUE).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateVariableModel.KEY,
                InstanceTemplateVariableModel.LABEL, InstanceTemplateVariableModel.DESCRIPTION).build()).build();
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
                .relation(RelationPick.of(InstanceTemplateFileModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build()).build())
            .build();
        return entry(InstanceTemplateFileModel.MODEL_ID, FILES, "template_file", 18, Icon.of("file-code"),
                InstanceTemplateFileModel.TEMPLATE_ID)
            .form(form(spec, false).inlineEditable(InstanceTemplateFileModel.CONTAINER_PATH, InstanceTemplateFileModel.MODE).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateFileModel.CONTAINER_PATH).build()).build();
    }

    public static @NonNull PanelResource<Row> volumes() {
        FormSpec spec = FormSpec.builder()
            .add(RelationPick.of(InstanceTemplateVolumeModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build())
            .add(InstanceTemplateVolumeModel.NAME).add(InstanceTemplateVolumeModel.CONTAINER_PATH)
            .add(InstanceTemplateVolumeModel.QUOTA_BYTES).add(InstanceTemplateVolumeModel.EXCLUSIVE).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.NAME).copyable().build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.CONTAINER_PATH).build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.QUOTA_BYTES).build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.EXCLUSIVE).build())
            .column(ColumnSpec.fromField(InstanceTemplateVolumeModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateVolumeModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build()).build())
            .build();
        return entry(InstanceTemplateVolumeModel.MODEL_ID, VOLUMES, "template_volume", 20, Icon.of("database"),
                InstanceTemplateVolumeModel.TEMPLATE_ID)
            .form(form(spec, false).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateVolumeModel.NAME).build()).build();
    }

    public static @NonNull PanelResource<Row> databases() {
        FormSpec spec = FormSpec.builder()
            .add(RelationPick.of(InstanceTemplateDatabaseModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build())
            .add(InstanceTemplateDatabaseModel.ENGINE).add(InstanceTemplateDatabaseModel.ENV_PREFIX)
            .add(InstanceTemplateDatabaseModel.IMAGE).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.ENV_PREFIX).copyable().build())
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.ENGINE).build())
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.IMAGE).build())
            .column(ColumnSpec.fromField(InstanceTemplateDatabaseModel.TEMPLATE_ID)
                .relation(RelationPick.of(InstanceTemplateDatabaseModel.TEMPLATE_ID, InstanceTemplateModel.MODEL_ID).build()).build())
            .build();
        return entry(InstanceTemplateDatabaseModel.MODEL_ID, DATABASES, "template_database", 19, Icon.of("database"),
                InstanceTemplateDatabaseModel.TEMPLATE_ID)
            .form(form(spec, true).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceTemplateDatabaseModel.ENV_PREFIX).build()).build();
    }

    private static PanelResource.@NonNull Builder<Row> entry(Identifier model, String slug, String scope, int order,
            Icon icon, IntegerField owner) {
        TemplateDeclarationGuards.init();
        return PanelResource.builder(model, slug, SubjectType.record(model))
            .label(Microcopy.of("plural").withFilter("scope", scope))
            .recordLabel(Microcopy.of("singular").withFilter("scope", scope))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(order).icon(icon).showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCE_TEMPLATES, owner).tab("contents"))
            .reads(ResourceReads.rows()).writes(ResourceMutations.rows().create().update().delete().build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions());
    }

    private static ResourceForm.@NonNull Builder<Row> form(FormSpec spec, boolean database) {
        return ResourceForm.<Row>of(spec).createDefaults(request -> defaults(spec, request, database));
    }

    private static Map<String, Object> defaults(FormSpec spec, PanelRequest request, boolean database) {
        Map<String, Object> values = new LinkedHashMap<>(spec.defaultValues());
        if (database) values.put(InstanceTemplateDatabaseModel.ENV_PREFIX.getName(), InstanceDatabaseModel.DEFAULT_PREFIX);
        Integer owner = CmsSupport.prefill(request.conduit(), HohenheimParams.TEMPLATE_ID_PREFILL);
        if (owner != null) values.put(InstanceTemplateVariableModel.TEMPLATE_ID.getName(), owner);
        return Map.copyOf(values);
    }
}
