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
import be.elevenways.zenit.cms.common.action.PanelAction;
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
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

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

    private InstanceBackupParts() {
    }

    /** The operator's backups: every backup, restore-to-new on a complete one, and the history tab. */
    public static @NonNull PanelResource<Row> admin() {
        return base(HohenheimIds.id("instance_backup"))
            .actions(List.of(restore()))
            .tabs(ResourceTabs.<Row>none().withHistory().withContributions())
            .build();
    }

    /**
     * The tenant's backups: those of instances it holds {@code backups} on, through the walk's tri-state (an id set
     * cannot express a whole-model row). The contributed tabs only: the admin history stays off the delegated
     * surface, which also 404s its routes.
     */
    public static @NonNull PanelResource<Row> manage() {
        return ManageTwin.reached(base(ManageTwin.id("instance_backup")), TenantScopes.INSTANCE_BACKUPS,
                ResourceTabs.<Row>none().withContributions())
            .build();
    }

    /** The parts both twins share: identity, list, title and the delete that removes the artifact too. */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id) {
        RelationPick instance = RelationPick.of(InstanceBackupModel.INSTANCE_ID, InstanceModel.MODEL_ID).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceBackupModel.INSTANCE_ID).relation(instance).build())
            .column(ColumnSpec.fromField(InstanceBackupModel.TARGET_ID)
                .relation(RelationPick.of(InstanceBackupModel.TARGET_ID, BackupTargetModel.MODEL_ID).build())
                .build())
            .column(ColumnSpec.fromField(InstanceBackupModel.STATUS).filterable().subtext("size_bytes").build())
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
            // A backup belongs to its instance: listed in the instance's Backups tab, its record page leads back there.
            .parent(ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceBackupModel.INSTANCE_ID)
                .tab(HohenheimSlugs.Tab.BACKUPS))
            .reads(ResourceReads.rows().title(InstanceBackupParts::title))
            // A backup is immutable evidence: no create (born from the instance action or the nightly task) and no
            // update; readers view, actions act.
            .form(ResourceForm.<Row>of(FormSpec.builder().add(instance).build()).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL).build())
            .writes(ResourceMutations.rows().delete(InstanceBackupOperations.DELETE_BACKUP).build());
    }

    /**
     * The key the archive was committed under names this backup.
     *
     * AIDEV-NOTE: declared HERE and not as a schema display field, because a display field is also the derived record
     * source's SEARCH field and {@code remote_key} is {@code filterable(false)} on purpose. A backup that never
     * reached its target has no key and keeps the framework's id fallback: nothing else on the row was written by a
     * human.
     */
    private static @Nullable String title(@NonNull Row backup) {
        String key = backup.get(InstanceBackupModel.REMOTE_KEY);
        return key != null && !key.isBlank() ? key : null;
    }

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
            .confirmation(Confirmations.of(verb, HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new_confirm"),
                ActionStyle.DEFAULT))
            .build();
    }
}
