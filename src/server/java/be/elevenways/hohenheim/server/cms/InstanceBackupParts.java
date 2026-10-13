package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceBackupOperations;
import be.elevenways.hohenheim.instance.InstanceBackupOperations.Restored;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceForm;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceMutations;
import be.elevenways.zenit.cms.common.resource.ResourceParent;
import be.elevenways.zenit.cms.common.resource.ResourceReads;
import be.elevenways.zenit.cms.common.resource.ResourceTabs;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.common.schema.TableSpec;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.edit.FormSpec;
import be.elevenways.zenit.common.edit.RelationPick;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The backup entries from shared parts: the operator's on /admin and the tenant's on /manage. Backups are born from
 * the instance's backup action or the nightly task, restored TO A NEW INSTANCE and deleted here.
 *
 * AIDEV-NOTE: restore-to-new is creative, not destructive (the source keeps running), so it confirms without the typed
 * phrase, and it stays OPERATOR-ONLY: it creates an instance outside the creation funnel (no create authority, no
 * placement, no creator grant, image from the archive manifest instead of an approved template), so the /manage twin
 * never places it and the operation refuses anyone else (InstanceBackupOperations).
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class InstanceBackupParts {

    /** The virtual column naming the data disks a backup carries, and the copy key of its label. */
    private static final String DATA_DISKS_COLUMN = "data_disks";

    private InstanceBackupParts() {
    }

    /**
     * The operator's backups: every backup, restore-to-new on a complete one, and the history tab.
     *
     * AIDEV-NOTE: a backup OUTLIVES its instance here (ResourceParent.survivingArchive): restoring a backup of a
     * deleted instance onto another host is the recovery restore-to-new exists for, so a trashed instance never makes
     * its backups read-only on the operator's surface. The tenant twin keeps the plain parent: what a tenant may do
     * with the backups of an instance it deleted is the operator's call.
     */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("instance_backup"), instanceParent().survivingArchive())
            .actions(List.of(restore()))
            .tabs(ResourceTabs.<Row>of(List.of(overview())).withHistory().withContributions())
            .build();
    }

    /**
     * The tenant's backups: those of instances it holds {@code backups} on, through the walk's tri-state (an id set
     * cannot express a whole-model row). The overview and the contributed tabs only: the admin history stays off the
     * delegated surface, which also 404s its routes.
     */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(base(ManageTwin.id("instance_backup"), instanceParent()),
                TenantScopes.INSTANCE_BACKUPS, ResourceTabs.<Row>of(List.of(overview())).withContributions())
            .build();
    }

    /** A backup's front door: its fields, then a card of what it carries beside the instance itself. */
    private static @NonNull RecordOverview<Row> overview() {
        return RecordOverview.<Row>fields().widgets(InstanceBackupParts::contents);
    }

    /**
     * The contents card (its data disks, its host volumes), null for a backup that carries nothing beside the instance
     * itself. A card of its own, so it sits in the overview's column the way the fields card does, never as a bare
     * fact list under it.
     */
    private static @Nullable WidgetTree contents(@NonNull Row backup, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        List<WidgetFact> facts = new ArrayList<>();
        String disks = dataDisksText(backup);
        if (disks != null) {
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_BACKUP.of(DATA_DISKS_COLUMN)
                .resolve(conduit.getLocales(), conduit.getMessageResolver()), disks));
        }
        List<String> hostVolumes = InstanceBackupModel.hostVolumesOf(backup);
        if (!hostVolumes.isEmpty()) {
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_BACKUP.of("host_volumes")
                .resolve(conduit.getLocales(), conduit.getMessageResolver()), String.join(", ", hostVolumes)));
        }
        if (facts.isEmpty()) {
            return null;
        }
        return new WidgetTree(List.of(CardWidget.of(HohenheimMicrocopy.INSTANCE_BACKUP.of("backup_contents"),
            new WidgetTree(List.of(new WidgetInstance(FactListWidget.ID, Map.of()).withData(List.copyOf(facts)))))));
    }

    /**
     * The data disks a backup carries as one line, the row's column and the page's fact alike.
     *
     * @return the device names joined, null for none
     */
    static @Nullable String dataDisksText(@NonNull Row backup) {
        List<String> disks = InstanceBackupModel.dataDisksOf(backup);
        return disks.isEmpty() ? null : String.join(", ", disks);
    }

    /** A backup belongs to its instance: listed in the instance's Backups tab, its record page leads back there. */
    private static @NonNull ResourceParent<Row> instanceParent() {
        return ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceBackupModel.INSTANCE_ID)
            .tab(HohenheimSlugs.Tab.BACKUPS);
    }

    /** The parts both twins share: identity, list, title and the delete that removes the artifact too. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id,
                                                            @NonNull ResourceParent<Row> parent) {
        RelationPick instance = RelationPick.of(InstanceBackupModel.INSTANCE_ID, InstanceModel.MODEL_ID).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceBackupModel.INSTANCE_ID).relation(instance).build())
            .column(ColumnSpec.fromField(InstanceBackupModel.TARGET_ID)
                .relation(RelationPick.of(InstanceBackupModel.TARGET_ID, BackupTargetModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.fromField(InstanceBackupModel.STATUS).filterable().subtext("size_bytes").build())
            .column(ColumnSpec.virtual(DATA_DISKS_COLUMN, HohenheimMicrocopy.INSTANCE_BACKUP.of(DATA_DISKS_COLUMN))
                .build())
            .column(ColumnSpec.fromField(InstanceBackupModel.SIZE_BYTES).byteSize().hidden().build())
            // AIDEV-NOTE: the remote key is what a restore is driven by on the target itself. It stays out of the
            // default layout (it is long and noise on a healthy list) but the picker can bring it back, chip and all.
            .column(ColumnSpec.fromField(InstanceBackupModel.REMOTE_KEY).hidden().copyable().build())
            .column(ColumnSpec.fromField(InstanceBackupModel.CREATED_AT).build())
            .build();
        return PanelResource.builder(id, HohenheimSlugs.INSTANCE_BACKUPS, InstanceBackupOperations.BACKUP)
            .label(HohenheimMicrocopy.INSTANCE_BACKUP.of("plural"))
            .recordLabel(HohenheimMicrocopy.INSTANCE_BACKUP.of("singular"))
            .icon(Icon.of("box-archive"))
            .navGroup(HohenheimPanel.DEPLOY_GROUP)
            .navOrder(17)
            .showInNav(false)
            .parent(parent)
            .reads(ResourceReads.rows().title(InstanceBackupParts::title))
            // A backup is immutable evidence: no create (born from the instance action or the nightly task) and no
            // update; readers view, actions act.
            .form(ResourceForm.<Row>of(FormSpec.builder().add(instance).build())
                .landingTab(RecordOverview.SLUG).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .computed(Objects.requireNonNull(table.column(DATA_DISKS_COLUMN)),
                    (backup, request) -> dataDisksText(backup))
                .build())
            .writes(ResourceMutations.rows().delete(InstanceBackupOperations.DELETE_BACKUP).build());
    }

    /**
     * The key the archive was committed under names this backup; one that never reached its target is named by when it
     * was taken.
     *
     * AIDEV-NOTE: declared HERE and not as a schema display field, because a display field is also the derived record
     * source's SEARCH field and {@code remote_key} is {@code filterable(false)} on purpose. The title never names the
     * instance: it is one String for every viewer, and the instance may be a trashed one this viewer cannot read (the
     * instance column and card name it under the viewer's own read). It answers for every row, so no page falls back to
     * the model's class name ("InstanceBackup #1").
     */
    static @NonNull String title(@NonNull Row backup) {
        String key = backup.get(InstanceBackupModel.REMOTE_KEY);
        if (key != null && !key.isBlank()) {
            return key;
        }
        Instant taken = backup.get(InstanceBackupModel.CREATED_AT);
        return taken != null ? TAKEN_AT.format(taken) : "#" + backup.get(InstanceBackupModel.ID);
    }

    /** A keyless backup's title: when it was taken, in UTC, to the minute. */
    private static final DateTimeFormatter TAKEN_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    /**
     * Restore-to-new, offered on a complete backup. A restore that could not bring everything back says so HERE, at
     * the moment the operator is looking: a bare "restored as #N" over a record missing its template, variables or
     * config files is the silent degradation the manifest inventory exists to end.
     */
    private static @NonNull PanelAction<Row> restore() {
        Microcopy verb = HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new");
        return PanelAction.<Row, Restored>places(InstanceBackupOperations.RESTORE_BACKUP, ActionPlacement.ROW,
                (request, result) -> {
                    Restored outcome = result.value();
                    if (outcome.missing() != null) {
                        return CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_BACKUP
                            .of("restored_new_partial")
                            .withArg("id", outcome.instanceId())
                            .withArg("missing", outcome.missing()));
                    }
                    return CmsActionResult.refreshWithToast(HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new")
                        .withArg("id", outcome.instanceId()));
                })
            .label(verb)
            .icon(Icon.of("clone"))
            .confirmation(ConfirmationSpec.verb(verb, HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new_confirm"),
                ActionStyle.DEFAULT))
            .build();
    }
}
