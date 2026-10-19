package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.instance.AttachedDatabaseBackup;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceSnapshotModel;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.ChildList;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.cms.common.schema.ColumnSpec;
import be.elevenways.zenit.cms.server.page.ChildListSections;
import be.elevenways.zenit.cms.server.panel.PartsLists;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.SubjectType;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Backups tab: the framework's child list sections of the hosting panel (its /manage twin there) narrowed to this
 * instance -- backups, snapshots and the schedules that make them -- each row relaying its own entry's placed
 * operations, so restore keeps its confirmation and operator-only refusal; then the databases attached to the
 * instance with their last backup, so an operator reads from the app whether all of its data is covered.
 *
 * AIDEV-NOTE: the sections are built per panel (a child list resolves in the panel it is declared on) and embedded
 * here ({@link ChildListSections}), because the databases' line is no child of the instance's backups. An artifact's
 * stored failure text is the daemon's own wording, shown through {@link WithheldFailure}: an operator reads it, a
 * delegate reads that it failed. A backup's data disks and host volumes are columns only where the instance's kind
 * has them.
 *
 * AIDEV-NOTE: a database's last backup is the Databases list's own cell ({@link #LAST_BACKUP_COLUMN}, read through
 * that entry), never a second wording of it; the attached databases load in one read through that entry's scope.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class InstanceBackupsPage implements RecordTab.Rendered<Row> {

    /** The artifact sections' stored-failure column, drawn only in this tab. */
    private static final String FAILURE_COLUMN = "failure";

    /**
     * The Databases list's last-backup column.
     *
     * AIDEV-NOTE: DatabaseParts.LAST_BACKUP_COLUMN spelled again because that constant is private; the read below
     * fails loudly when the entry lacks it, so a rename cannot leave this tab silently empty.
     */
    static final String LAST_BACKUP_COLUMN = "last_backup";

    private final @NonNull ChildList<Row> sections;

    /** @param operator whether the tab is the operator's, which names the backup target in use and links to its choice */
    InstanceBackupsPage(boolean operator) {
        ChildList<Row> tab = ChildList.<Row>sections(HohenheimSlugs.Tab.BACKUPS,
                HohenheimMicrocopy.INSTANCE.of("backups"),
                HohenheimSlugs.INSTANCE_BACKUPS, HohenheimSlugs.INSTANCE_SNAPSHOTS, HohenheimSlugs.INSTANCE_SCHEDULES)
            .icon(Icon.of("box-archive"))
            .hide(HohenheimSlugs.INSTANCE_BACKUPS, InstanceBackupModel.INSTANCE_ID.getName())
            .hide(HohenheimSlugs.INSTANCE_SNAPSHOTS, InstanceSnapshotModel.INSTANCE_ID.getName())
            .hide(HohenheimSlugs.INSTANCE_SCHEDULES, RecordScheduleModel.RECORD_ID.getName())
            // A container's backups carry no data disks, a VM's no host volumes: each column follows the kind.
            .hideWhen(HohenheimSlugs.INSTANCE_BACKUPS, (instance, access) -> !InstanceDevicesPage.attachesDevices(
                instance), InstanceBackupParts.DATA_DISKS_COLUMN)
            .hideWhen(HohenheimSlugs.INSTANCE_BACKUPS, (instance, access) -> !VolumeParts.mountsVolumes(instance),
                InstanceBackupParts.HOST_VOLUMES_COLUMN)
            // An empty section offers its making action instead of a dead end ("No backups yet" with Back up now).
            .parentActions(HohenheimSlugs.INSTANCE_BACKUPS, InstanceOperations.BACKUP.id())
            .parentActions(HohenheimSlugs.INSTANCE_SNAPSHOTS, InstanceOperations.SNAPSHOT.id())
            .column(HohenheimSlugs.INSTANCE_BACKUPS, SubjectType.record(InstanceBackupModel.MODEL_ID), failureColumn(),
                (backup, request) -> WithheldFailure.of(request.conduit()).shown(backup.get(InstanceBackupModel.ERROR)))
            .column(HohenheimSlugs.INSTANCE_SNAPSHOTS, SubjectType.record(InstanceSnapshotModel.MODEL_ID),
                failureColumn(), (snapshot, request) -> WithheldFailure.of(request.conduit())
                    .shown(snapshot.get(InstanceSnapshotModel.ERROR)));
        this.sections = operator ? tab.headerLink(HohenheimSlugs.INSTANCE_BACKUPS, backupTargetLink()) : tab;
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_backups"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE.of("backups"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.BACKUPS; }
    @Override public @NonNull Icon icon() { return Icon.of("box-archive"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("sections", ChildListSections.embedded(request,
            CmsSupport.rowEntry(request.panel(), HohenheimSlugs.INSTANCES), instance, this.sections));
        vars.put("panelSlug", request.panelSlug());
        vars.put("databasesAfter", HohenheimSlugs.INSTANCE_BACKUPS);
        vars.put("databases", attachedDatabases(request, instance.get(InstanceModel.ID)));
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_BACKUPS, vars);
    }

    /**
     * Every database attached to the instance with its last backup, as the Databases list of the hosting panel draws
     * it, in one read of the attachments and one of the databases through that list's own entry and scope.
     *
     * @return empty where this panel lists no databases, or none is attached
     */
    public static @NonNull List<AttachedDatabaseBackup> attachedDatabases(@NonNull PanelRequest request,
                                                                          @Nullable Integer instanceId) {
        AccessContext access = request.access();
        PanelResource<Row> databases = CmsSupport.admittedRowEntry(request.panel(), HohenheimSlugs.DATABASES, access);
        if (databases == null || instanceId == null) {
            return List.of();
        }
        List<Object> ids = new ArrayList<>();
        for (Row link : Models.get(InstanceDatabaseModel.class).findByInstanceId(instanceId)) {
            ids.add(link.get(InstanceDatabaseModel.DATABASE_ID));
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        ColumnSpec lastBackup = Objects.requireNonNull(PartsLists.<Row>tableSpec(databases).column(LAST_BACKUP_COLUMN),
            () -> "the " + HohenheimSlugs.DATABASES + " list draws no " + LAST_BACKUP_COLUMN + " column");
        List<AttachedDatabaseBackup> lines = new ArrayList<>();
        for (Row database : PartsReads.<Row>loadRows(request, databases, ids, access)) {
            Object cell = PartsReads.cellValue(request, databases, null, database, lastBackup);
            lines.add(new AttachedDatabaseBackup(String.valueOf((Object) database.get(DatabaseModel.NAME)),
                CmsRoutes.open(request.panelSlug(), HohenheimSlugs.DATABASES, database.get(DatabaseModel.ID)).toUrl(),
                cell instanceof StateLineCell state ? state : null));
        }
        return List.copyOf(lines);
    }

    /**
     * The backups section's header link: the target this instance's backups go to, or the offer to choose one, both
     * leading to the Configuration where its Backups section holds the choice.
     */
    private static @NonNull PanelAction<Row> backupTargetLink() {
        return PanelAction.<Row>link(HohenheimIds.id("instance_backup_target"), ActionPlacement.HEADER)
            .label(HohenheimMicrocopy.INSTANCE.of("backup_target_choose"))
            .dynamicLabel(instance -> {
                Integer targetId = instance.get(InstanceModel.BACKUP_TARGET_ID);
                Row target = targetId == null ? null : Models.get(BackupTargetModel.class).findById(targetId);
                return target == null ? HohenheimMicrocopy.INSTANCE.of("backup_target_choose")
                    : HohenheimMicrocopy.INSTANCE.of("backups_go_to")
                        .withArg("target", String.valueOf((Object) target.get(BackupTargetModel.NAME)));
            })
            .icon(Icon.of("box-archive"))
            .route((instance, request) -> CmsRoutes.detail(request.panelSlug(), HohenheimSlugs.INSTANCES,
                instance.get(InstanceModel.ID)))
            .build();
    }

    /** The column an artifact's stored failure reads in, saying there is none while it has none. */
    private static @NonNull ColumnSpec failureColumn() {
        return ColumnSpec.virtual(FAILURE_COLUMN, HohenheimMicrocopy.INSTANCE_ARTIFACTS.of("failure"))
            .absent(HohenheimMicrocopy.INSTANCE_ARTIFACTS.of("failure_none"))
            .build();
    }
}
