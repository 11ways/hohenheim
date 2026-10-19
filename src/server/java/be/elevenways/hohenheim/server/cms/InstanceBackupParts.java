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
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.server.panel.PartsReads;
import be.elevenways.zenit.common.edit.FieldLabels;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.ActionPlacement;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.CmsActionResultTranslator;
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
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /** The virtual columns naming the data disks and the host volumes a backup carries; also their copy keys. */
    public static final String DATA_DISKS_COLUMN = "data_disks";
    public static final String HOST_VOLUMES_COLUMN = "host_volumes";

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

    /**
     * A backup's front door, which is also its read-only view (no Details tab beside it): its archive key as the page's
     * field, then what it is (status, the instance, size, where it went, its checksum) and what it carries.
     */
    private static @NonNull RecordOverview<Row> overview() {
        return RecordOverview.<Row>fields().widgets(InstanceBackupParts::widgets);
    }

    private static @NonNull WidgetTree widgets(@NonNull Row backup, @NonNull AccessContext access) {
        List<WidgetInstance> cards = new ArrayList<>();
        cards.add(CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_BACKUP.of("details"), details(backup, access)));
        WidgetInstance contents = contents(backup, access.conduit());
        if (contents != null) {
            cards.add(contents);
        }
        return new WidgetTree(cards);
    }

    /**
     * Status, the instance (its page, for a reader who may open it), size, the target it went to, the checksum read
     * back from that target, when it was taken and, while it failed, why; the archive key is the page's own field.
     */
    private static @NonNull List<WidgetFact> details(@NonNull Row backup, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        Panel panel = PanelRegistry.getBySlug(CmsSupport.panelSlug(conduit));
        List<WidgetFact> facts = new ArrayList<>();
        Object status = backup.get(InstanceBackupModel.STATUS);
        if (status != null) {
            facts.add(WidgetFact.badge(label(InstanceBackupModel.STATUS, conduit),
                WidgetBadge.of(InstanceBackupModel.STATUS, status, locales, resolver)));
        }
        WidgetFact instance = reference(label(InstanceBackupModel.INSTANCE_ID, conduit),
            InstanceBackupModel.INSTANCE_ID,
            InstanceModel.MODEL_ID, backup.get(InstanceBackupModel.INSTANCE_ID), panel, access);
        if (instance != null) {
            facts.add(instance);
        }
        Long size = backup.get(InstanceBackupModel.SIZE_BYTES);
        if (size != null) {
            facts.add(WidgetFact.of(label(InstanceBackupModel.SIZE_BYTES, conduit),
                UsageData.sizeOf(size).resolve(locales, resolver)));
        }
        WidgetFact target = reference(label(InstanceBackupModel.TARGET_ID, conduit), InstanceBackupModel.TARGET_ID,
            BackupTargetModel.MODEL_ID, backup.get(InstanceBackupModel.TARGET_ID), panel, access);
        if (target != null) {
            facts.add(target);
        }
        String checksum = backup.get(InstanceBackupModel.SHA256);
        if (checksum != null && !checksum.isBlank()) {
            facts.add(WidgetFact.of(label(InstanceBackupModel.SHA256, conduit), checksum));
        }
        Instant taken = backup.get(InstanceBackupModel.CREATED_AT);
        if (taken != null) {
            facts.add(WidgetFact.instant(label(InstanceBackupModel.CREATED_AT, conduit), taken.toString()));
        }
        String failure = WithheldFailure.of(conduit).shown(backup.get(InstanceBackupModel.ERROR));
        if (!failure.isEmpty()) {
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_ARTIFACTS.of("failure").resolve(locales, resolver),
                failure));
        }
        return facts;
    }

    /**
     * A fact naming the record a stored reference holds, read as the record pickers read one (both archived lanes,
     * a trashed record marked deleted) and linked to its page for a reader the panel lets open it.
     *
     * @return null for no reference, or a record this reader may not read, which is never named
     */
    private static @Nullable WidgetFact reference(@NonNull String label, @NonNull Field<?, ?> field,
                                                  @NonNull Identifier modelId, @Nullable Integer id,
                                                  @Nullable Panel panel, @NonNull AccessContext access) {
        if (id == null) {
            return null;
        }
        RecordSource<?> source = RelationPick.of(field, modelId).build().requireSource();
        LocaleChain locales = access.conduit().getLocales();
        Row row = source.resolveReferencedRow(id, locales, access);
        if (row == null) {
            return null;
        }
        DataItem item = source.referenceItem(row, locales, access);
        String title = item.title() != null ? item.title() : "#" + id;
        RouteTarget open = panel == null ? null : PartsReads.openRoute(panel, modelId, id, access);
        return open != null ? WidgetFact.link(label, title, open.toUrl()) : WidgetFact.of(label, title);
    }

    private static @NonNull String label(@NonNull Field<?, ?> field, @NonNull Conduit conduit) {
        return FieldLabels.labelFor(field).asString(conduit);
    }

    /**
     * The contents card (its data disks, its host volumes), null for a backup that carries nothing beside the instance
     * itself. A card of its own, so it sits in the overview's column the way the fields card does, never as a bare
     * fact list under it.
     */
    private static @Nullable WidgetInstance contents(@NonNull Row backup, @NonNull Conduit conduit) {
        List<WidgetFact> facts = new ArrayList<>();
        String disks = dataDisksText(backup);
        if (disks != null) {
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_BACKUP.of(DATA_DISKS_COLUMN)
                .resolve(conduit.getLocales(), conduit.getMessageResolver()), disks));
        }
        String hostVolumes = hostVolumesText(backup);
        if (hostVolumes != null) {
            facts.add(WidgetFact.of(HohenheimMicrocopy.INSTANCE_BACKUP.of(HOST_VOLUMES_COLUMN)
                .resolve(conduit.getLocales(), conduit.getMessageResolver()), hostVolumes));
        }
        if (facts.isEmpty()) {
            return null;
        }
        return CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_BACKUP.of("backup_contents"), facts);
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

    /**
     * The host volumes a backup carries as one line, the row's column and the page's fact alike.
     *
     * @return the volume names joined, null for none
     */
    static @Nullable String hostVolumesText(@NonNull Row backup) {
        List<String> volumes = InstanceBackupModel.hostVolumesOf(backup);
        return volumes.isEmpty() ? null : String.join(", ", volumes);
    }

    /** A backup belongs to its instance: listed in the instance's Backups tab, its record page leads back there. */
    private static @NonNull ResourceParent<Row> instanceParent() {
        return ResourceParent.of(HohenheimSlugs.INSTANCES, InstanceBackupModel.INSTANCE_ID)
            .tab(HohenheimSlugs.Tab.BACKUPS);
    }

    /**
     * The parts both twins share: identity, list, title and the delete that removes the artifact too.
     *
     * AIDEV-NOTE: the list leads with when the backup was taken and how it stands, the columns a phone keeps beside
     * the sticky actions; the target and the raw amounts wait behind the picker. Whether its data disks or its host
     * volumes apply is the instance kind's fact, which the Backups tab reads (InstanceBackupsPage).
     */
    private static PanelResource.@NonNull Builder<Row> base(@NonNull Identifier id,
                                                            @NonNull ResourceParent<Row> parent) {
        RelationPick instance = RelationPick.of(InstanceBackupModel.INSTANCE_ID, InstanceModel.MODEL_ID).build();
        TableSpec<Row> table = TableSpec.<Row>builder()
            .column(ColumnSpec.fromField(InstanceBackupModel.CREATED_AT).build())
            .column(ColumnSpec.fromField(InstanceBackupModel.STATUS).filterable().subtext("size_bytes").build())
            .column(ColumnSpec.virtual(DATA_DISKS_COLUMN, HohenheimMicrocopy.INSTANCE_BACKUP.of(DATA_DISKS_COLUMN))
                .absent(HohenheimMicrocopy.INSTANCE_BACKUP.of("data_disks_none")).build())
            .column(ColumnSpec.virtual(HOST_VOLUMES_COLUMN,
                    HohenheimMicrocopy.INSTANCE_BACKUP.of(HOST_VOLUMES_COLUMN))
                .absent(HohenheimMicrocopy.INSTANCE_BACKUP.of("host_volumes_none")).build())
            .column(ColumnSpec.fromField(InstanceBackupModel.INSTANCE_ID).relation(instance).build())
            .column(ColumnSpec.fromField(InstanceBackupModel.TARGET_ID)
                .relation(RelationPick.of(InstanceBackupModel.TARGET_ID, BackupTargetModel.MODEL_ID).build())
                .hidden().build())
            .column(ColumnSpec.fromField(InstanceBackupModel.SIZE_BYTES).hidden().build())
            // AIDEV-NOTE: the remote key is what a restore is driven by on the target itself. It stays out of the
            // default layout (it is long and noise on a healthy list) but the picker can bring it back, chip and all.
            .column(ColumnSpec.fromField(InstanceBackupModel.REMOTE_KEY).hidden().copyable().build())
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
            // update; readers view, actions act. Its archive key is the page's one field, never its title; the instance
            // is named, linked, on the overview's details card.
            .form(ResourceForm.<Row>of(FormSpec.builder().add(InstanceBackupModel.REMOTE_KEY).build())
                .landingTab(RecordOverview.SLUG).build())
            .list(ResourceList.rows(table).chrome(ListChrome.MINIMAL)
                .computed(Objects.requireNonNull(table.column(DATA_DISKS_COLUMN)),
                    (backup, request) -> dataDisksText(backup))
                .computed(Objects.requireNonNull(table.column(HOST_VOLUMES_COLUMN)),
                    (backup, request) -> hostVolumesText(backup))
                .build())
            .writes(ResourceMutations.rows().delete(InstanceBackupOperations.DELETE_BACKUP).build());
    }

    /**
     * A backup is named by when it was taken; its archive key stays a field on its page (it is long, and the same
     * instance prefix on every row of a list).
     *
     * AIDEV-NOTE: declared HERE and not as a schema display field, because a display field is also the derived record
     * source's SEARCH field. The title never names the instance: the instance may be a trashed one this viewer cannot
     * read (the instance column and card name it under the viewer's own read). The moment is typed copy, so each reader
     * reads it on their own clock. It answers for every row, so no page falls back to the model's class name
     * ("InstanceBackup #1").
     */
    static @NonNull Microcopy title(@NonNull Row backup) {
        Instant taken = backup.get(InstanceBackupModel.CREATED_AT);
        return taken != null ? HohenheimMicrocopy.INSTANCE_BACKUP.of("title_taken").withArg("at", taken)
            : Microcopy.literal("#" + backup.get(InstanceBackupModel.ID));
    }

    /**
     * Restore-to-new, offered on a complete backup. It ends on the NEW instance with a toast naming it; a restore that
     * could not bring everything back says so in that toast, at the moment the operator is looking: a bare "restored as
     * #N" over a record missing its template, variables or config files is the silent degradation the manifest
     * inventory exists to end.
     *
     * AIDEV-NOTE: a Refresh stayed on the backup page, which offered no way to the new record (walked on daystrom,
     * round r2y); the toast is stashed for the page the redirect lands on, the destroyWithDataAction shape.
     */
    private static @NonNull PanelAction<Row> restore() {
        Microcopy verb = HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new");
        return PanelAction.<Row, Restored>places(InstanceBackupOperations.RESTORE_BACKUP, ActionPlacement.ROW,
                (request, outcome) -> {
                    Microcopy told = outcome.missing() != null
                        ? HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new_partial")
                            .withArg("id", outcome.instanceId())
                            .withArg("missing", outcome.missing())
                        : HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new").withArg("id", outcome.instanceId());
                    if (outcome.movedPorts() != null) {
                        told = HohenheimMicrocopy.INSTANCE_BACKUP.of("restored_new_ports_moved").withArg("told", told)
                            .withArg("ports", outcome.movedPorts());
                    }
                    CmsActionResultTranslator.stashSuccess(request.request().conduit(), told);
                    return CmsActionResult.redirect(new Uri(CmsRoutes.subpage(request.request().panelSlug(),
                        HohenheimSlugs.INSTANCES, outcome.instanceId(), RecordOverview.SLUG).toUrl()));
                })
            .label(verb)
            .icon(Icon.of("clone"))
            // A rare verb in a list read by when and how each backup went: in the row's menu, so a phone keeps the
            // created and status columns beside a narrow actions column; the record's band still draws it inline.
            .inlineInRow(false)
            .confirmation(ConfirmationSpec.verb(verb, HohenheimMicrocopy.INSTANCE_BACKUP.of("restore_new_confirm"),
                ActionStyle.DEFAULT))
            .build();
    }
}
