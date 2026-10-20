package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.UsageCell;
import be.elevenways.hohenheim.instance.VolumeOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.VolumeDeclarationOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceAuthority;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RowScope;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Volume parts: the operator's typed declaration writes and the single destructive-with-data operation, the tenant's
 * read-only twin, and the instance's Volumes tab as the framework's child list over them.
 *
 * AIDEV-NOTE: a volume's instance is its identity (its host directory is named after it), so the parent is FIXED: the
 * form names it read-only and a create is written under the instance its Volumes tab names.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class VolumeParts {

    /** The volumes of some instance; the /manage scope narrows this same base per principal. */
    public static final RowScope ROWS = RowScope.within(() -> InstanceVolumeModel.INSTANCE_ID.isNotNull());

    /** The virtual column drawing a volume's use against its size limit. */
    private static final String USAGE_COLUMN = "usage";

    private VolumeParts() {}

    /** @return the operator's volumes: declare, change and delete with data, under an instance that mounts them */
    public static @NonNull PanelResource<Row> admin() {
        VolumeDeclarationOperations.init();
        return base(HohenheimIds.id("instance_volume"))
            .scope(ROWS)
            .writes(ResourceMutations.rows().create(VolumeOperations.CREATE)
                .update(VolumeOperations.UPDATE, row -> row.get(InstanceVolumeModel.VERSION)).build())
            // The CONFIG capability the operations' own gates ask, asked earlier so the tab offers only what they do.
            .authority(ResourceAuthority.<Row>builder()
                .createUnder((instance, access) -> instance instanceof Integer id
                    && HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID, id, HohenheimCapabilities.CONFIG))
                .update(null, (row, access) -> HohenheimAccess.reachesRecord(access, InstanceModel.MODEL_ID,
                    row.get(InstanceVolumeModel.INSTANCE_ID), HohenheimCapabilities.CONFIG)).build())
            .actions(List.of(destroy()))
            .tabs(ResourceTabs.<Row>of(List.of(overview())).withHistory().withContributions()).build();
    }

    /** @return the tenant's volumes: those of instances it may view, read-only */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(base(ManageTwin.id("instance_volume")), TenantScopes.INSTANCE_VOLUMES,
                ResourceTabs.<Row>of(List.of(overview())).withContributions())
            .build();
    }

    /**
     * The Volumes tab of an instance whose kind mounts declared volumes: the volume list of the hosting panel narrowed
     * to it; for any other kind a declared row would ride no deploy, so the tab is absent.
     *
     * @param operator whether the tab is the operator's, over {@link #admin}, or a delegate's
     */
    public static @NonNull ChildList<Row> tab(boolean operator) {
        return ChildList.<Row>of(operator ? admin() : manage())
            .icon(Icon.of("folder-open"))
            .secondary()
            .lead(HohenheimMicrocopy.INSTANCE_VOLUME.of("lead"))
            .visibleWhen((instance, access) -> mountsVolumes(instance))
            .hide(HohenheimSlugs.INSTANCE_VOLUMES, InstanceVolumeModel.INSTANCE_ID.getName());
    }

    /** @return whether the instance's kind mounts declared volumes */
    static boolean mountsVolumes(@NonNull Row instance) {
        InstanceKindHandler handler = InstanceKinds.handlerOf(instance);
        return handler != null && handler.supportsVolumes();
    }

    /** The parts both twins share: identity, the fixed parent, the form, the list and its reads. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        TableSpec<Row> table = tableSpec();
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_VOLUMES,
                SubjectType.record(InstanceVolumeModel.REF))
            .label(HohenheimMicrocopy.INSTANCE_VOLUME.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_VOLUME.of("singular"))
            .description(CmsSupport.navHint(HohenheimMicrocopy.INSTANCE_VOLUME))
            .navGroup(HohenheimPanel.DEPLOY_GROUP).navOrder(19).icon(Icon.of("folder-open")).showInNav(false)
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceVolumeModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.VOLUMES).fixed())
            .form(ResourceForm.<Row>of(VolumeOperations.FORM)
                .landingTab(RecordOverview.SLUG)
                // A declared volume is seen where it lives: its instance's Volumes tab, beside its siblings.
                .createdRecordUrl((volume, request) -> CmsRoutes.subpage(request.panelSlug(), HohenheimSlugs.INSTANCES,
                    volume.get(InstanceVolumeModel.INSTANCE_ID), HohenheimSlugs.Tab.VOLUMES))
                .build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .computed(Objects.requireNonNull(table.column(USAGE_COLUMN)), (volume, request) -> usageCell(volume))
                .build())
            .reads(ResourceReads.rows().mapValues(Set.of(VolumeOperations.QUOTA_MB.getName()), (row, base) -> {
                Map<String, Object> values = new LinkedHashMap<>(base);
                values.put(VolumeOperations.QUOTA_MB.getName(), VolumeDeclarationOperations.declaration(row)
                        .quota_mb());
                return values;
            }));
    }

    /**
     * The volume list, the Volumes tab's section and the nav-hidden list alike: the name, where it is mounted with the
     * host directory under it, its use against its limit, when that was measured, and whether it is exclusive. The raw
     * amounts stay behind the picker, worded as sizes.
     */
    static @NonNull TableSpec<Row> tableSpec() {
        RelationPick instance = RelationPick.of(InstanceVolumeModel.INSTANCE_ID, InstanceModel.MODEL_ID).build();
        return TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceVolumeModel.NAME).filterable().build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.INSTANCE_ID).relation(instance).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.CONTAINER_PATH)
                .subtext(InstanceVolumeModel.HOST_PATH.getName()).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.HOST_PATH).copyable().hidden().build())
            .column(ColumnSpec.virtual(USAGE_COLUMN, HohenheimMicrocopy.INSTANCE_VOLUME.of("usage"))
                .renderer(HohenheimTemplateIds.CELL_USAGE).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.OBSERVED_AT).build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.QUOTA_BYTES).hidden().build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.USED_BYTES).hidden().build())
            .column(ColumnSpec.fromField(InstanceVolumeModel.EXCLUSIVE).build()).build();
    }

    /** @return the volume's use as the usage cell: the bar against its size limit, and the amounts in words */
    static @NonNull UsageCell usageCell(@NonNull Row volume) {
        Long used = volume.get(InstanceVolumeModel.USED_BYTES);
        Long quota = volume.get(InstanceVolumeModel.QUOTA_BYTES);
        boolean barred = used != null && quota != null && quota > 0;
        return new UsageCell(barred, barred ? UsageData.percentOf(used, quota) : 0, usageText(volume));
    }

    /**
     * @return the volume's use against its limit as one gauge reading, null while it was never measured or has no
     *         limit, where a bar would have nothing to fill
     */
    static @Nullable UsageData usageData(@NonNull Row volume) {
        Long used = volume.get(InstanceVolumeModel.USED_BYTES);
        Long quota = volume.get(InstanceVolumeModel.QUOTA_BYTES);
        Instant observed = volume.get(InstanceVolumeModel.OBSERVED_AT);
        if (used == null || quota == null || quota <= 0) {
            return null;
        }
        return UsageData.bytes(used, quota, observed == null ? null : observed.toString());
    }

    /** @return "3 GB of 10 GB", "3 GB, no limit", or that it was never measured */
    static @NonNull Microcopy usageText(@NonNull Row volume) {
        Long used = volume.get(InstanceVolumeModel.USED_BYTES);
        Long quota = volume.get(InstanceVolumeModel.QUOTA_BYTES);
        if (used == null) {
            return HohenheimMicrocopy.INSTANCE_VOLUME.of("never_measured");
        }
        return quota == null ? HohenheimMicrocopy.INSTANCE_VOLUME.of("usage_unlimited").withArg("used", used)
            : HohenheimMicrocopy.INSTANCE_VOLUME.of("usage_of").withArg("used", used).withArg("quota", quota);
    }

    /** A volume's front door: its declaration, then its use and, where it was derived, its directory on the host. */
    private static @NonNull RecordOverview<Row> overview() {
        return RecordOverview.<Row>fields().widgets(VolumeParts::usageWidgets);
    }

    /**
     * The In use card: the gauge against the size limit (it says when it was measured), else the use in words with
     * that moment; then the host directory, the evidence a delete with data removes.
     */
    private static @NonNull WidgetTree usageWidgets(@NonNull Row volume, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        List<WidgetInstance> cards = new ArrayList<>();
        UsageData usage = usageData(volume);
        if (usage != null) {
            cards.add(CardWidget.of(HohenheimMicrocopy.INSTANCE_VOLUME.of("usage"),
                new WidgetTree(List.of(AppOverview.gauge(HohenheimMicrocopy.INSTANCE_VOLUME.of("volume_gauge")
                    .withArg("name", String.valueOf((Object) volume.get(InstanceVolumeModel.NAME))), usage)))));
        } else {
            List<WidgetFact> facts = new ArrayList<>();
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_VOLUME.of("usage").asString(conduit),
                usageText(volume).asString(conduit)));
            Instant observed = volume.get(InstanceVolumeModel.OBSERVED_AT);
            if (observed != null) {
                facts.add(WidgetFact.instant(HohenheimMicrocopy.HOHENHEIM_FIELD.of("observed_at").asString(conduit),
                    observed.toString()));
            }
            cards.add(CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_VOLUME.of("usage"), facts));
        }
        String hostPath = volume.get(InstanceVolumeModel.HOST_PATH);
        if (hostPath != null) {
            cards.add(CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_VOLUME.of("on_host"), List.of(
                WidgetFact.of(HohenheimMicrocopy.HOHENHEIM_FIELD.of("host_path").asString(conduit), hostPath))));
        }
        return new WidgetTree(cards);
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
