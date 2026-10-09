package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.VolumeDeclarationOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
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
    public static final String SLUG = "instance-volumes";
    private VolumeParts() {}

    public static @NonNull PanelResource<Row> admin() {
        VolumeDeclarationOperations.init();
        return PanelResource.builder(HohenheimIds.id("instance_volume"), SLUG,
                SubjectType.record(InstanceVolumeModel.MODEL_ID))
            .label(Microcopy.of("plural").withFilter("scope", "instance_volume"))
            .recordLabel(Microcopy.of("singular").withFilter("scope", "instance_volume"))
            .description(CmsSupport.navHint("instance_volume"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(19).icon(Icon.of("database")).showInNav(false)
            .standsUnder(HohenheimSlugs.INSTANCES)
            .scope(RowScope.within(() -> InstanceVolumeModel.INSTANCE_ID.isNotNull()))
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceVolumeModel.INSTANCE_ID)
                .tab(InstanceVolumesTab.SLUG))
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
                    row.get(InstanceVolumeModel.INSTANCE_ID), HohenheimAccess.CONFIG)).build())
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
        return PanelAction.<Row, Void>places(VolumeOperations.DESTROY, ActionPlacement.ROW,
                (context, result) -> CmsActionResult.refreshWithToast(Microcopy.of("destroyed_toast")
                    .withFilter("scope", "instance_volume")
                    .withArg("name", context.subjects().getFirst().get(InstanceVolumeModel.NAME))))
            .style(ActionStyle.DESTRUCTIVE).inlineInRow(false)
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                .body(Microcopy.of("destroy_confirm").withFilter("scope", "instance_volume"))
                .confirmLabel(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                .style(ActionStyle.DESTRUCTIVE).build())
            .dynamicConfirmation(row -> {
                String name = row.get(InstanceVolumeModel.NAME);
                return ConfirmationSpec.builder()
                    .title(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                    .body(Microcopy.of("destroy_confirm_named").withFilter("scope", "instance_volume").withArg("name", name))
                    .confirmLabel(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                    .style(ActionStyle.DESTRUCTIVE).requireTypedConfirmation(name).build();
            }).build();
    }
}
