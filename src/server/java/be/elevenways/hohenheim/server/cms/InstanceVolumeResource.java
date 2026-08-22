package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.access.AccessDecision;
import be.elevenways.zenit.cms.common.access.AccessFunction;
import be.elevenways.zenit.cms.common.access.QueryPredicate;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
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
 * Declared volumes of one instance (nav-hidden; reached through the Volumes tab). THE
 * operator surface over {@link InstanceVolumes}: declare, re-quota, and the one
 * irreversible verb -- destroy a volume WITH its data, behind a typed-name confirm.
 *
 * AIDEV-NOTE: every write funnels into InstanceVolumes and never into
 * super.persistRow/updateRow/deleteRow -- the row is only the DECLARATION; the
 * directory, its quota and its removal live on the host, and a row-only write would
 * report success while mounting nothing (the InstanceDeviceResource lesson). Declares
 * and re-quotas take effect at the next deploy (mountsFor materializes them); the form
 * help says so.
 */
public class InstanceVolumeResource extends RowResource {

    /** Form-only quota field in MB; stored as {@link InstanceVolumeModel#QUOTA_BYTES}. */
    static final IntegerField QUOTA_MB = IntegerField.builder().name("quota_mb")
        .label(HohenheimFormCopy.label("quota_mb"))
        .help(HohenheimFormCopy.help("volume_quota_mb"))
        .suffix("MB")
        .build();

    private final FormSpec formSpec = FormSpec.builder()
        .add(InstanceVolumeModel.INSTANCE_ID)
        .add(InstanceVolumeModel.NAME)
        .add(InstanceVolumeModel.CONTAINER_PATH)
        .add(QUOTA_MB)
        .add(InstanceVolumeModel.EXCLUSIVE)
        .build();

    private final TableSpec<Row> tableSpec = TableSpec.<Row>builder()
        .column(ColumnSpec.fromField(InstanceVolumeModel.NAME).filterable().build())
        .column(ColumnSpec.fromField(InstanceVolumeModel.INSTANCE_ID).build())
        .column(ColumnSpec.fromField(InstanceVolumeModel.CONTAINER_PATH).build())
        .column(ColumnSpec.fromField(InstanceVolumeModel.QUOTA_BYTES).build())
        .column(ColumnSpec.fromField(InstanceVolumeModel.USED_BYTES).build())
        .column(ColumnSpec.fromField(InstanceVolumeModel.EXCLUSIVE).build())
        .build();

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_volume"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("plural").withFilter("scope", "instance_volume"); }
    @Override public @NonNull String slug() { return "instance-volumes"; }
    @Override public @NonNull Model model() { return Models.get(InstanceVolumeModel.class); }
    @Override public @NonNull FormSpec formSpec() { return this.formSpec; }
    @Override public @NonNull TableSpec<Row> tableSpec() { return this.tableSpec; }
    @Override public @NonNull ListChrome listChrome() { return ListChrome.MINIMAL; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.DEPLOY_GROUP; }
    @Override public int navOrder() { return 19; }
    @Override public @NonNull Icon icon() { return Icon.of("database"); }
    @Override public boolean showInNav() { return false; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "instance_volume");
    }

    @Override
    public @NonNull AccessFunction<Row> accessFunction() {
        return ctx -> AccessDecision.allow(QueryPredicate.of(
            InstanceVolumeModel.INSTANCE_ID.isNotNull()));
    }

    /**
     * Declaring reaches the volume service (which validates the name and derives the
     * host path) rather than the row alone, so the framework's rollback-after-create
     * verification must not wrap it; the funnel checks the CONFIG capability first.
     */
    @Override
    public boolean verifiesScopeBeforeMutating() {
        return true;
    }

    @Override
    public @Nullable ResourceParent<Row> parent() {
        return ResourceParent.<Row>of("instances",
            InstanceVolumeResource::instanceIdOf).tab(InstanceVolumesPage.SLUG);
    }

    /** Editing a volume demands CONFIG on its instance, like every device write. */
    @Override
    public boolean writableBy(@NonNull Row record, @NonNull AccessContext accessContext) {
        return HohenheimAccess.reachesRecord(accessContext, InstanceModel.MODEL_ID,
            instanceIdOf(record), HohenheimAccess.CONFIG);
    }

    /**
     * There is NO plain delete: removing a declaration while keeping the directory
     * would orphan data silently, so the only removal is the typed-confirm DESTROY
     * row action below -- the destroy travels WITH its consumer (the InstanceVolumes
     * AIDEV-NOTE's contract).
     */
    @Override
    public boolean deletable() {
        return false;
    }

    /**
     * The one irreversible verb: destroy the volume's directory AND its declaration.
     * Admin-only and typed-name-confirmed, the delete-with-data guard, per volume.
     */
    private @NonNull RowAction<Row> destroyAction() {
        return RowAction.Invoke.<Row>builder(Identifier.of("hohenheim", "destroy_volume"))
            .label(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
            .icon(Icon.of("trash-can"))
            .style(ActionStyle.DESTRUCTIVE)
            .inlineInRow(false)
            .visibleFor((row, ctx) -> HohenheimAccess.isAdmin(ctx))
            .confirmation(ConfirmationSpec.builder()
                .title(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                .body(Microcopy.of("destroy_confirm").withFilter("scope", "instance_volume"))
                .confirmLabel(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                .style(ActionStyle.DESTRUCTIVE)
                .build())
            .dynamicConfirmation(row -> {
                String name = String.valueOf((Object) row.get(InstanceVolumeModel.NAME));
                return ConfirmationSpec.builder()
                    .title(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                    .body(Microcopy.of("destroy_confirm_named")
                        .withFilter("scope", "instance_volume").withArg("name", name))
                    .confirmLabel(Microcopy.of("destroy").withFilter("scope", "instance_volume"))
                    .style(ActionStyle.DESTRUCTIVE)
                    .requireTypedConfirmation(name)
                    .build();
            })
            .handler((row, ctx) -> {
                this.destroyVolume(row);
                return CmsActionResult.refreshWithToast(
                    Microcopy.of("destroyed_toast").withFilter("scope", "instance_volume")
                        .withArg("name", row.get(InstanceVolumeModel.NAME)));
            })
            .build();
    }

    @Override
    public @NonNull List<RowAction<Row>> rowActions() {
        List<RowAction<Row>> actions = new ArrayList<>(super.rowActions());
        actions.add(this.destroyAction());
        return actions;
    }

    /** The Volumes tab links here with ?instance_id= so the owner is preset. */
    @Override
    public @NonNull Map<String, Object> createValues(@NonNull Conduit conduit) {
        Map<String, Object> values = new LinkedHashMap<>(formSpec().defaultValues());
        String instanceId = conduit.getQueryParam("instance_id");
        if (instanceId != null && !instanceId.isEmpty()) {
            values.put("instance_id", instanceId);
        }
        return Map.copyOf(values);
    }

    /** The MB form value is derived from the stored byte quota. */
    @Override
    public @NonNull Map<String, Object> valuesFromRow(@NonNull Row row) {
        Map<String, Object> values = new LinkedHashMap<>(super.valuesFromRow(row));
        Long quotaBytes = row.get(InstanceVolumeModel.QUOTA_BYTES);
        values.put(QUOTA_MB.getName(),
            quotaBytes != null ? (int) (quotaBytes / (1024L * 1024L)) : null);
        return values;
    }

    @Override
    public @NonNull Object persistRow(@NonNull Map<String, Object> coerced,
                                      @NonNull AccessContext accessContext) {
        int instanceId = requireVolumeCapableInstance(coerced.get("instance_id"));
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        String name = String.valueOf(coerced.get("name"));
        Row declared = InstanceVolumes.declare(instanceId, name,
            String.valueOf(coerced.get("container_path")),
            quotaBytesOf(coerced, null), Boolean.TRUE.equals(coerced.get("exclusive")));
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            "volume_declared", name);
        return declared.get(InstanceVolumeModel.ID);
    }

    /**
     * Name and owning instance are the volume's identity (they derive the host path);
     * path, quota and exclusivity re-declare through the same funnel as create.
     */
    @Override
    public void updateRow(@NonNull Row existing, @NonNull Map<String, Object> coerced,
                          @NonNull AccessContext accessContext) {
        int instanceId = instanceIdOf(existing);
        HohenheimAccess.requireOperationCapability(instanceId, HohenheimAccess.CONFIG);
        String name = String.valueOf((Object) existing.get(InstanceVolumeModel.NAME));
        Object submittedName = CmsSupport.valueOf(coerced, existing, InstanceVolumeModel.NAME);
        if (!name.equals(String.valueOf(submittedName))) {
            // The name derives the host path, and there is no directory-rename lane.
            throw Violations.ofField("name", submittedName,
                CmsSupport.violationText("volume_rename_unsupported"));
        }
        Object submittedInstance =
            CmsSupport.valueOf(coerced, existing, InstanceVolumeModel.INSTANCE_ID);
        if (instanceId != parseId(submittedInstance)) {
            throw Violations.ofField("instance_id", submittedInstance,
                CmsSupport.violationText("volume_rehome_unsupported"));
        }
        InstanceVolumes.declare(instanceId, name,
            String.valueOf(CmsSupport.valueOf(coerced, existing,
                InstanceVolumeModel.CONTAINER_PATH)),
            quotaBytesOf(coerced, existing),
            Boolean.TRUE.equals(CmsSupport.valueOf(coerced, existing,
                InstanceVolumeModel.EXCLUSIVE)));
        ActivityLog.record(Models.get(InstanceModel.class), instanceId,
            "volume_redeclared", name);
    }

    /**
     * The destroy: directory removed at the daemon (verified by the backend operation),
     * then the declaration row. The workspace's {@code home} volume is refused by
     * name -- a workspace without its home cannot start, and "delete the workspace's
     * data" is the instance-level delete-with-data action's job.
     */
    private void destroyVolume(@NonNull Row existing) {
        int instanceId = instanceIdOf(existing);
        String name = String.valueOf((Object) existing.get(InstanceVolumeModel.NAME));
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance != null
                && WorkspaceKind.ID.toString().equals(instance.get(InstanceModel.KIND))
                && WorkspaceKind.HOME_VOLUME.equals(name)) {
            throw Violations.ofForm(CmsSupport.violationText("volume_home_protected"));
        }
        String serverName = ServerModel.nameOf(ServerModel.canonicalServerId(
            instance != null ? instance.get(InstanceModel.SERVER_ID) : null));
        ActivityLog.withAction(ActivityLog.ACTION_DELETE, "volume_destroyed",
            () -> InstanceVolumes.destroyOne(instanceId, name, serverName));
    }

    // -- plumbing -------------------------------------------------------------

    static int instanceIdOf(@NonNull Row row) {
        Integer id = row.get(InstanceVolumeModel.INSTANCE_ID);
        return id != null ? id : -1;
    }

    private static int parseId(@Nullable Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException unparseable) {
            return -1;
        }
    }

    /** The declared owner must exist, be live, and be of a kind that mounts volumes. */
    private static int requireVolumeCapableInstance(@Nullable Object value) {
        int instanceId = parseId(value);
        Row instance = instanceId > 0
            ? Models.get(InstanceModel.class).findById(instanceId) : null;
        if (instance == null || instance.get(InstanceModel.DELETED_AT) != null) {
            throw Violations.ofField("instance_id", value,
                CmsSupport.violationText("unknown_instance"));
        }
        InstanceKindHandler handler = InstanceKinds.getHandler(instance.get(InstanceModel.KIND));
        if (handler == null || !handler.supportsVolumes()) {
            throw Violations.ofField("instance_id", value,
                CmsSupport.violationText("volume_kind_unsupported"));
        }
        return instanceId;
    }

    /** The submitted MB quota as bytes; absent key keeps the stored quota, blank clears. */
    private static @Nullable Long quotaBytesOf(@NonNull Map<String, Object> coerced,
                                               @Nullable Row existing) {
        if (!coerced.containsKey(QUOTA_MB.getName())) {
            return existing != null ? existing.get(InstanceVolumeModel.QUOTA_BYTES) : null;
        }
        Object quota = coerced.get(QUOTA_MB.getName());
        if (quota == null || String.valueOf(quota).isBlank()) {
            return null;
        }
        long mb = quota instanceof Number number
            ? number.longValue() : Long.parseLong(String.valueOf(quota));
        if (mb <= 0) {
            throw Violations.ofField(QUOTA_MB.getName(), quota,
                CmsSupport.violationText("volume_quota_invalid"));
        }
        return mb * 1024L * 1024L;
    }
}
