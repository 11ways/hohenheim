package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.VolumeDeclarationOperations;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Volume parts: typed declaration writes and the single destructive-with-data operation.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class VolumeParts {
    private VolumeParts() {}

    public static @NonNull PanelResource<Row> admin() {
        VolumeDeclarationOperations.init();
        return PanelResource.builder(HohenheimIds.id("instance_volume"), HohenheimSlugs.INSTANCE_VOLUMES,
                SubjectType.record(InstanceVolumeModel.MODEL_ID))
            .label(HohenheimMicrocopy.INSTANCE_VOLUME.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_VOLUME.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.INSTANCE_VOLUME))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(19).icon(Icon.of("database")).showInNav(false)
            .scope(RowScope.within(() -> InstanceVolumeModel.INSTANCE_ID.isNotNull()))
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceVolumeModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.VOLUMES))
            .form(ResourceForm.<Row>of(VolumeOperations.FORM).createDefaults(request -> {
                Map<String, Object> values = new LinkedHashMap<>(VolumeOperations.FORM.defaultValues());
                Integer owner = CmsSupport.prefill(request.conduit(), HohenheimParams.INSTANCE_ID_PREFILL);
                if (owner != null) values.put(InstanceVolumeModel.INSTANCE_ID.getName(), owner);
                return Map.copyOf(values);
            }).build())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL).build())
            .reads(ResourceReads.rows().mapValues((row, base) -> {
                Map<String, Object> values = new LinkedHashMap<>(base);
                values.put(VolumeOperations.QUOTA_MB.getName(), VolumeDeclarationOperations.declaration(row).quota_mb());
                return values;
            }))
            .writes(ResourceMutations.rows().create(VolumeOperations.CREATE)
                .update(VolumeOperations.UPDATE, row -> row.get(InstanceVolumeModel.VERSION)).build())
            .authority(ResourceAuthority.<Row>builder().update(null, (row, access) ->
                HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
                    row.get(InstanceVolumeModel.INSTANCE_ID), HohenheimCapabilities.CONFIG)).build())
            .actions(List.of(destroy()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions()).build();
    }

    static @NonNull TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceVolumeModel.NAME).filterable().build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.INSTANCE_ID).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.CONTAINER_PATH).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.QUOTA_BYTES).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.USED_BYTES).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.EXCLUSIVE).build()).build();
    }

    private static PanelAction<Row> destroy() {
        ConfirmationSpec destroy = ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE_VOLUME.of("destroy"),
            HohenheimMicrocopy.INSTANCE_VOLUME.of("destroy_confirm"), ActionStyle.DESTRUCTIVE);
        return PanelAction.<Row, Void>places(VolumeOperations.DESTROY, ActionPlacement.ROW,
                (context, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_VOLUME
                    .of("destroyed_toast")
                    .withArg("name", context.subjects().getFirst().get(InstanceVolumeModel.NAME))))
            .style(ActionStyle.DESTRUCTIVE).inlineInRow(false)
            .confirmation(destroy)
            .dynamicConfirmation(row -> destroy.withBody(HohenheimMicrocopy.INSTANCE_VOLUME
                .of("destroy_confirm_named").withArg("name", row.get(InstanceVolumeModel.NAME)))
                .withTypedConfirmation(row.get(InstanceVolumeModel.NAME))).build();
    }
}
