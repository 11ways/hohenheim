package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceChildDeletes;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-instance config files from parts: seeded from the template, editable per instance, staged into the container
 * before every start. Hidden from the sidebar; reached through an instance's Provisioning tab.
 *
 * AIDEV-NOTE: the path and mode rule is the model's own (ContainerFileRules, installed on the instance file schema),
 * so this entry declares no write hook: the form, the inline cell, the API and a direct save all answer to it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceFileParts {

    /** The entry's slug, which the Provisioning tab links into when its panel registers it. */
    public static final String SLUG = "instance-files";

    private InstanceFileParts() {
    }

    /** @return the operator's instance config files */
    public static @NonNull PanelResource<Row> admin() {
        RelationPick instance = RelationPick.of(InstanceFileModel.INSTANCE_ID, InstanceModel.MODEL_ID).build();
        FormSpec form = FormSpec.builder()
            .add(instance)
            .add(InstanceFileModel.CONTAINER_PATH)
            .add(InstanceFileModel.CONTENT)
            .add(InstanceFileModel.MODE)
            .build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceFileModel.CONTAINER_PATH).subtext("mode").copyable().build())
            .column(ColumnSpec.fromField(InstanceFileModel.MODE).hidden().build())
            // AIDEV-NOTE: a GENERATED row's write is refused by the model with a 422 that the list gave no hint
            // about, because nothing here said the row was generated.
            .column(ColumnSpec.fromField(InstanceFileModel.GENERATED_BY).build())
            .column(ColumnSpec.fromField(InstanceFileModel.INSTANCE_ID).relation(instance).build())
            .build();
        return PanelResource.builder(HohenheimIds.id("instance_file"), SLUG,
                SubjectType.record(InstanceFileModel.MODEL_ID))
            .label(Microcopy.of("plural").withFilter("scope", "instance_file"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "instance_file"))
            .icon(Icon.of("file-code"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(19)
            .showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceFileModel.INSTANCE_ID).tab("provisioning"))
            .reads(ResourceReads.rows())
            .form(ResourceForm.<Row>of(form)
                // The Provisioning tab links here with ?instance_id= so the pick arrives preselected.
                .createDefaults(request -> {
                    Map<String, Object> values = new LinkedHashMap<>(form.defaultValues());
                    Integer instanceId = CmsSupport.prefill(request.conduit(), HohenheimParams.INSTANCE_ID_PREFILL);
                    if (instanceId != null) {
                        values.put(InstanceFileModel.INSTANCE_ID.getName(), instanceId);
                    }
                    return Map.copyOf(values);
                })
                // AIDEV-NOTE: the path a file lands on and the mode it lands with, the two answers an operator
                // corrects while reading the list, both staged into the container at the next DEPLOY. CONTENT is
                // excluded and always will be: it is the file BODY, encrypted at rest and routinely multi-line.
                .inlineEditable(InstanceFileModel.CONTAINER_PATH, InstanceFileModel.MODE)
                .build())
            // The path only: CONTENT is encrypted at rest, so a search over it would match ciphertext.
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).search(InstanceFileModel.CONTAINER_PATH)
                .build())
            .writes(ResourceMutations.rows().create().update().delete(InstanceChildDeletes.FILE).build())
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }
}
