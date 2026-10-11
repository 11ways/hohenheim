package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceSnapshotOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceSnapshotOperationHandlers;
import be.elevenways.protoblast.common.registry.Identifier;
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
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * Snapshot inventory twins. Only the operator note is editable; payload restore/delete are domain operations.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceSnapshotParts {
    private InstanceSnapshotParts() {}
    public static @NonNull PanelResource<Row> admin() {
        return entry(HohenheimIds.id("instance_snapshot"))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions()).build();
    }
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(entry(ManageTwin.id("instance_snapshot")), TenantScopes.INSTANCE_SNAPSHOTS,
            ResourceTabs.<Row>none().withContributions()).build();
    }
    private static PanelResource.@NonNull Builder<Row> entry(@NonNull Identifier id) {
        InstanceSnapshotOperationHandlers.init();
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_SNAPSHOTS, InstanceSnapshotOperations.SNAPSHOT)
            .label(HohenheimMicrocopy.INSTANCE_SNAPSHOT.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_SNAPSHOT.of("singular"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(16).icon(Icon.of("camera")).showInNav(false)
            // A snapshot belongs to its instance: listed in the instance's Backups tab, its record page leads back
            // there.
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceSnapshotModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.BACKUPS))
            .form(ResourceForm.<Row>of(FormSpec.builder().add(InstanceSnapshotModel.NOTE).build())
                .inlineEditable(InstanceSnapshotModel.NOTE).build())
            .list(ResourceList.rows(tableSpec()).chrome(ListChrome.MINIMAL).search(InstanceSnapshotModel.NOTE).build())
            .reads(ResourceReads.rows().title(InstanceSnapshotParts::title))
            .writes(ResourceMutations.rows().update().delete(InstanceSnapshotOperations.DELETE).build())
            .authority(ResourceAuthority.<Row>builder().write(null,
                    (row, access) -> HohenheimAccess.reachesRecord(access,
                InstanceModel.MODEL_ID, row.get(InstanceSnapshotModel.INSTANCE_ID),
                    HohenheimCapabilities.SNAPSHOTS)).build())
            .actions(List.of(restore()));
    }
    private static TableSpec<Row> tableSpec() {
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceSnapshotModel.INSTANCE_ID)
                .relation(RelationPick.of(InstanceSnapshotModel.INSTANCE_ID, InstanceModel.MODEL_ID).build()).build())
            .column(ColumnSpec.fromField(InstanceSnapshotModel.STATUS).filterable().subtext("total_bytes").build())
            .column(ColumnSpec.fromField(InstanceSnapshotModel.NOTE).build())
            .column(ColumnSpec.fromField(InstanceSnapshotModel.TOTAL_BYTES).byteSize().hidden().build())
            .column(ColumnSpec.fromField(InstanceSnapshotModel.CREATED_AT).build()).build();
    }
    private static @Nullable String title(Row row) {
        String note = row.get(InstanceSnapshotModel.NOTE);
        if (note != null && !note.isBlank()) return note;
        String nativeName = row.get(InstanceSnapshotModel.NATIVE_NAME);
        return nativeName != null && !nativeName.isBlank() ? nativeName : null;
    }
    private static PanelAction<Row> restore() {
        ConfirmationSpec restore = ConfirmationSpec.verb(HohenheimMicrocopy.INSTANCE_SNAPSHOT.of("restore"),
            HohenheimMicrocopy.INSTANCE_SNAPSHOT.of("restore_confirm_generic"), ActionStyle.DESTRUCTIVE);
        return PanelAction.<Row, Void>places(InstanceSnapshotOperations.RESTORE, ActionPlacement.ROW,
                (request, result) -> CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_SNAPSHOT
                    .of("restored")
                    .withArg("name", instanceName(request.subject()))))
            .style(ActionStyle.DESTRUCTIVE)
            .confirmation(restore)
            .dynamicConfirmation(row -> restore.withBody(HohenheimMicrocopy.INSTANCE_SNAPSHOT
                .of("restore_confirm").withArg("name", instanceName(row)))
                .withTypedConfirmation(instanceName(row))).build();
    }
    private static String instanceName(Row row) {
        Row owner = StoredRows.byId(Models.get(InstanceModel.class), row.get(InstanceSnapshotModel.INSTANCE_ID));
        return String.valueOf((Object) (owner == null ? row.get(InstanceSnapshotModel.INSTANCE_ID)
                : owner.get(InstanceModel.NAME)));
    }
}
