package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceBackupModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;
import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

/**
 * The instance tier's attention items: crashes, backups, disk pressure and failed application deploys.
 *
 * Every projection reads a STORED fact (the status crash detection stamped, backup rows, the disk
 * sweeper's observation, release operations); asking every daemon whether each workload is alive
 * is what the attention surface's no-per-render-probe rule forbids.
 *
 * AIDEV-NOTE: every link to an instance row goes through {@link InstanceParts#recordRoute}, because
 * release rows are not served by the instance list (its accessFunction) and a direct link to one
 * 404s; their surface is their application's Deploys tab.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class InstanceAttention {

    /** Above this fraction of an ENFORCED root-disk ceiling an instance needs attention. */
    private static final double DISK_HIGH = 0.85;

    /** ... and above this it is about to break rather than merely worth watching. */
    private static final double DISK_CRITICAL = 0.95;

    private InstanceAttention() {
    }

    // AIDEV-NOTE: the instance collectors are PUBLIC for the same reason
    // stuckReleasingPorts is -- a test proves each projection directly, positive and
    // negative, instead of asserting against whatever the whole dashboard happens to hold.

    /**
     * Instances the runtime gave up on: the status CRASH DETECTION already stamped
     * (an unobserved exit under crash policy none, or a crash loop that tripped flap
     * protection).
     */
    public static void crashedInstances(List<AttentionItem> items) {
        crashedInstances(items, AppHealth.sitesHeldBack());
    }

    /**
     * AIDEV-NOTE: the crashed workload is the ROOT of what its sites' visitors get: each such site's verdict names the
     * workload as its cause ({@link AppHealth}), so the dashboard folds their "error page" items under this one, which
     * says how many sites it keeps from their visitors and keeps the workload's own action.
     * Its own root, where it has one (its verdict's cause half, {@link AppHealth#instanceReading}: the host that
     * refuses its start now, the database whose old engine it is), holds it in turn, so a whole chain reads as that one
     * item. A failed start on a host that is not admitted once read as its own red item beside the host's; the host is
     * the root, and the failure stays in the app's Recent.
     *
     * @param sitesHeld what each record keeps from visitors ({@link AppHealth#sitesHeldBack}), read once for the tier
     */
    static void crashedInstances(List<AttentionItem> items, Map<AttentionSubject, Integer> sitesHeld) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_ERROR))
                .all()) {
            AttentionSubject subject = AttentionSubject.instance(instance.get(InstanceModel.ID));
            WorkloadErrors.Reading reading = WorkloadErrors.readingOf(instance, true);
            items.add(item(AttentionSeverity.ERROR, "box",
                WorkloadErrors.stoppageOf(instance).title(instance.get(InstanceModel.NAME)),
                reading.worded(),
                InstanceParts.recordRoute(ADMIN, instance, HohenheimSlugs.Tab.CONSOLE),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_console"))
                .about(subject, sitesHeldText(sitesHeld.get(subject)))
                .causedBy(AppHealth.instanceReading(instance, false, null).cause())
                .withNote(reading.technical()));
        }
    }

    /** @return "Visitors of its site get an error page", null when the workload keeps no site from its visitors */
    static @Nullable Microcopy sitesHeldText(@Nullable Integer sites) {
        return sites == null || sites == 0 ? null : HohenheimMicrocopy.ATTENTION_DETAIL.of("sites_held_back")
            .withArg("count", sites);
    }

    /**
     * Instances whose destroy's end was given up on after its automatic retries (UnfinishedDestroys): left
     * destroying until an operator's requested Delete retries it, with the failure as stored on the record.
     *
     * AIDEV-NOTE: a generated row takes no Delete of its own: the Delete of the record it was generated for carries
     * the request down (ApplicationReleases, DatabaseService, DatabaseEngines, StackRuntime and StackInstances pass
     * UnfinishedDestroys.Policy.REQUESTED), so its item opens that owner. A tier that starts owning instances must
     * pass the policy the same way. One owner retries WITHOUT its Delete: a shared database still owning the
     * dedicated engine its move left behind, whose Delete would drop the live logical database (its
     * FINISH_OLD_ENGINE action is the retry). The owners are read in one query per owning model, however many rows
     * are held.
     */
    public static void unfinishedDestroys(List<AttentionItem> items) {
        List<Row> held = Models.get(InstanceModel.class).find()
            .where(InstanceModel.STATUS.in(InstanceModel.TERMINATING_STATUSES))
            .and(InstanceModel.STOPPAGE_CAUSE.eq(HohenheimActivityAction.DESTROY_UNFINISHED.id().getPath()))
            .all();
        Map<Integer, RetryingOwner> owners = retryingOwnersOf(held);
        for (Row instance : held) {
            WorkloadErrors.Reading reading = WorkloadErrors.readingOf(instance, true);
            String name = instance.get(InstanceModel.NAME);
            RouteTarget route;
            Microcopy action;
            if (instance.get(InstanceModel.GENERATED_BY) == null) {
                // Its own Delete is the requested retry.
                route = InstanceParts.recordRoute(ADMIN, instance, null);
                action = HohenheimMicrocopy.ATTENTION_ACTION.of("act_delete_again").withArg("name", name);
            } else {
                RetryingOwner owner = owners.get(instance.get(InstanceModel.ID));
                // An owner that is gone, or served by no admin entry, leaves the row to be opened where it is.
                route = owner == null ? InstanceParts.recordRoute(ADMIN, instance, null) : owner.route();
                action = owner == null
                    ? HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_app").withArg("name", name)
                    : owner.action();
            }
            items.add(item(AttentionSeverity.ERROR, "trash-can",
                HohenheimMicrocopy.ATTENTION_TITLE.of("instance_unfinished_destroy").withArg("name", name),
                reading.worded(), route, action)
                .about(AttentionSubject.instance(instance.get(InstanceModel.ID)), null)
                .withNote(reading.technical()));
        }
    }

    /** The live record whose requested action retries a held generated row, that action's words, and its page. */
    private record RetryingOwner(@NonNull Microcopy action, @NonNull RouteTarget route) {

        /** @return the owner whose requested Delete tears the row down */
        static @NonNull RetryingOwner deleting(@NonNull String name, @NonNull RouteTarget route) {
            return new RetryingOwner(HohenheimMicrocopy.ATTENTION_ACTION.of("act_delete_owner_again")
                .withArg("name", name), route);
        }
    }

    /**
     * Reads the retrying owner of every generated row in {@code rows} at once: a release's application, a preview
     * instance's application (through its preview), and otherwise the record it was generated for.
     *
     * @return by instance id; a row whose owner is gone or unserved is absent
     */
    private static @NonNull Map<Integer, RetryingOwner> retryingOwnersOf(@NonNull List<Row> rows) {
        String applications = InstanceModel.MODEL_ID.toString();
        String previews = PreviewDeploymentModel.MODEL_ID.toString();
        Map<String, Set<Integer>> idsByModel = new LinkedHashMap<>();
        for (Row row : rows) {
            String model = row.get(InstanceModel.GENERATED_FOR_MODEL);
            Integer id = row.get(InstanceModel.GENERATED_FOR_ID);
            if (row.get(InstanceModel.GENERATED_BY) != null && model != null && id != null) {
                idsByModel.computeIfAbsent(model, ignored -> new HashSet<>()).add(id);
            }
        }
        Map<Integer, Integer> applicationOfPreview = new HashMap<>();
        Set<Integer> previewIds = idsByModel.remove(previews);
        if (previewIds != null) {
            for (Row preview : Models.get(PreviewDeploymentModel.class).find()
                    .where(PreviewDeploymentModel.ID.in(List.copyOf(previewIds)))
                    .all()) {
                Integer application = preview.get(PreviewDeploymentModel.APPLICATION_ID);
                if (application != null) {
                    applicationOfPreview.put(preview.get(PreviewDeploymentModel.ID), application);
                }
            }
        }
        Set<Integer> applicationIds = new HashSet<>(applicationOfPreview.values());
        Set<Integer> ownedByApplications = idsByModel.remove(applications);
        if (ownedByApplications != null) {
            applicationIds.addAll(ownedByApplications);
        }
        Map<Integer, RetryingOwner> byApplication = new HashMap<>();
        if (!applicationIds.isEmpty()) {
            for (Row application : Models.get(InstanceModel.class).find()
                    .where(InstanceModel.ID.in(List.copyOf(applicationIds)))
                    .all()) {
                int id = application.get(InstanceModel.ID);
                byApplication.put(id, RetryingOwner.deleting(application.get(InstanceModel.NAME),
                    CmsRoutes.subpage(ADMIN, HohenheimSlugs.INSTANCES, id, RecordOverview.SLUG)));
            }
        }
        Map<String, Map<Integer, RetryingOwner>> byOtherModel = new HashMap<>();
        idsByModel.forEach((model, ids) -> byOtherModel.put(model, recordOwners(model, ids)));

        Map<Integer, RetryingOwner> owners = new HashMap<>();
        for (Row row : rows) {
            String model = row.get(InstanceModel.GENERATED_FOR_MODEL);
            Integer id = row.get(InstanceModel.GENERATED_FOR_ID);
            if (row.get(InstanceModel.GENERATED_BY) == null || model == null || id == null) {
                continue;
            }
            RetryingOwner owner;
            if (applications.equals(model)) {
                owner = byApplication.get(id);
            } else if (previews.equals(model)) {
                Integer application = applicationOfPreview.get(id);
                owner = application == null ? null : byApplication.get(application);
            } else {
                owner = byOtherModel.getOrDefault(model, Map.of()).get(id);
            }
            if (owner != null) {
                owners.put(row.get(InstanceModel.ID), owner);
            }
        }
        return owners;
    }

    /**
     * The live records of one owning model among {@code ids}, each opened at its front door on the admin panel.
     *
     * @return by record id; empty for a model this application does not know or no admin entry serves
     */
    @SuppressWarnings("unchecked")
    private static @NonNull Map<Integer, RetryingOwner> recordOwners(@NonNull String modelToken,
                                                                     @NonNull Set<Integer> ids) {
        Identifier modelId = Identifier.tryParse(modelToken);
        Model model = modelId == null ? null : Models.get(modelId);
        if (model == null) {
            return Map.of();
        }
        // Every owning model is keyed by an integer id (GENERATED_FOR_ID), so its key field takes these ids as-is.
        Field<Object, ?> key = (Field<Object, ?>) model.getPrimaryKeyField();
        Map<Integer, RetryingOwner> owners = new HashMap<>();
        for (Row owner : model.find().where(key.in(List.<Object>copyOf(ids))).all()) {
            Object id = model.getPrimaryKeyValue(owner);
            BoundEndpoint<?> route = id == null ? null : AdminRecordLinks.detail(model.getModelId(), String.valueOf(id));
            if (route != null) {
                String title = model.getDisplayTitle(owner);
                String name = title != null ? title : modelToken + " #" + id;
                // A shared database's held instance is the engine its move left behind: its own action retries it.
                boolean oldEngine = DatabaseModel.MODEL_ID.equals(model.getModelId()) && DatabaseModel.isShared(owner);
                owners.put(((Number) id).intValue(), oldEngine
                    ? new RetryingOwner(HohenheimMicrocopy.ATTENTION_ACTION.of("act_finish_old_engine")
                        .withArg("name", name), route)
                    : RetryingOwner.deleting(name, route));
            }
        }
        return owners;
    }

    /**
     * Instances whose LATEST backup failed -- the failedDeployments shape: only the most
     * recent attempt per instance speaks, so one old failure followed by successes is not
     * an alarm and a currently-failing schedule is.
     */
    public static void failedInstanceBackups(List<AttentionItem> items) {
        var backups = Models.get(InstanceBackupModel.class);
        for (Row instance : Models.get(InstanceModel.class).find()
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Row latest = backups.newestOf(id);
            if (latest == null || !InstanceBackupModel.STATUS_FAILED
                    .equals(latest.get(InstanceBackupModel.STATUS))) {
                continue;
            }
            items.add(item(AttentionSeverity.ERROR, "box-archive",
                HohenheimMicrocopy.ATTENTION_TITLE.of("instance_backup")
                    .withArg("name", instance.get(InstanceModel.NAME)),
                literal(latest.get(InstanceBackupModel.ERROR)),
                InstanceParts.recordRoute(ADMIN, instance, HohenheimSlugs.Tab.BACKUPS),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_backups")));
        }
    }

    /**
     * Instances whose backup signal has degraded to SILENCE: a backup target is declared
     * on the record (the operator's statement that this instance is supposed to be backed
     * up) but no COMPLETE backup exists, or the newest one is older than
     * {@code backup.stale_after_days}. The latest-FAILED collector above answers "is it
     * failing right now"; this one answers the question that collector structurally
     * cannot -- "when did it last SUCCEED" -- so an instance never backed up, or failing
     * so long its failures predate its rows, stops reading as green. Both may fire for
     * one instance (failing nightly AND stale); that is escalation, not duplication.
     */
    public static void staleInstanceBackups(List<AttentionItem> items) {
        Integer days = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Backup.STALE_AFTER_DAYS);
        if (days == null || days <= 0) {
            return;
        }
        Instant threshold = Now.instant().minus(Duration.ofDays(days));
        var backups = Models.get(InstanceBackupModel.class);
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.BACKUP_TARGET_ID.isNotNull())
                .all()) {
            Integer id = instance.get(InstanceModel.ID);
            if (id == null) {
                continue;
            }
            Row newestComplete = backups.find()
                .where(InstanceBackupModel.INSTANCE_ID.eq(id))
                .where(InstanceBackupModel.STATUS.eq(InstanceBackupModel.STATUS_COMPLETE))
                .orderBy(InstanceBackupModel.ID, SortOrder.DESC)
                .first();
            if (newestComplete == null) {
                items.add(item(AttentionSeverity.WARNING, "box-archive",
                    HohenheimMicrocopy.ATTENTION_TITLE.of("instance_backup_never")
                        .withArg("name", instance.get(InstanceModel.NAME)),
                    HohenheimMicrocopy.ATTENTION_DETAIL.of("instance_backup_never"),
                    InstanceParts.recordRoute(ADMIN, instance, HohenheimSlugs.Tab.BACKUPS),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_backups")));
                continue;
            }
            Instant completedAt = newestComplete.get(InstanceBackupModel.CREATED_AT);
            if (completedAt == null || completedAt.isBefore(threshold)) {
                long age = completedAt == null
                    ? -1 : Duration.between(completedAt, Now.instant()).toDays();
                items.add(item(AttentionSeverity.WARNING, "box-archive",
                    HohenheimMicrocopy.ATTENTION_TITLE.of("instance_backup_stale")
                        .withArg("name", instance.get(InstanceModel.NAME)),
                    HohenheimMicrocopy.ATTENTION_DETAIL.of("instance_backup_stale").withArg("days", age),
                    InstanceParts.recordRoute(ADMIN, instance, HohenheimSlugs.Tab.BACKUPS),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_backups")));
            }
        }
    }

    /**
     * Instances close to filling their root disk, from the STORED observation
     * ({@code ObserveInstanceDisk}).
     *
     * AIDEV-NOTE: a null observation is silence, never zero, and a zero LIMIT is silence
     * too. Both mean "nothing is rationing this disk, or nothing measured it" -- Docker's
     * whole tier is in that state by design, because it enforces no root quota at all. An
     * item here therefore always names a real ceiling a real number is approaching.
     */
    public static void instancesLowOnDisk(List<AttentionItem> items) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.DISK_OBSERVED_AT.isNotNull())
                .all()) {
            Long used = instance.get(InstanceModel.DISK_USED_BYTES);
            Long limit = instance.get(InstanceModel.DISK_LIMIT_BYTES);
            if (used == null || limit == null || limit <= 0) {
                continue;
            }
            double fraction = (double) used / limit;
            if (fraction < DISK_HIGH) {
                continue;
            }
            items.add(item(fraction >= DISK_CRITICAL ? AttentionSeverity.ERROR : AttentionSeverity.WARNING,
                "hard-drive",
                HohenheimMicrocopy.ATTENTION_TITLE.of("instance_disk")
                    .withArg("name", instance.get(InstanceModel.NAME)),
                HohenheimMicrocopy.ATTENTION_DETAIL.of("instance_disk").withArg("percent", Math.round(fraction * 100))
                    .withArg("limit", limit),
                InstanceParts.recordRoute(ADMIN, instance, null),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_app")
                    .withArg("name", instance.get(InstanceModel.NAME))));
        }
    }

    /**
     * The newest release operation of every application, when it FAILED.
     *
     * AIDEV-NOTE: this used to read the {@code deployments} table of the deleted host-slot
     * lane. The release engine's own {@code release_operations} row IS the deploy history
     * now -- one record of what was attempted, with its step log -- so there is no second
     * table to keep in step with it.
     */
    static void failedDeployments(List<AttentionItem> items) {
        failedDeployments(items, AppHealth.sitesHeldBack());
    }

    /**
     * @param sitesHeld what each record keeps from visitors: an application whose deploy failed and does not run is
     *                  the root of its sites' error pages, as a crashed workload is
     */
    static void failedDeployments(List<AttentionItem> items, Map<AttentionSubject, Integer> sitesHeld) {
        var instanceModel = Models.get(InstanceModel.class);
        var operations = Models.get(ReleaseOperationModel.class);
        if (instanceModel == null || operations == null) {
            return;
        }
        for (Row application : instanceModel.find()
                .where(InstanceModel.KIND.in(InstanceKinds.kindsWhere(InstanceKindHandler::releaseManaged)))
                .all()) {
            Integer applicationId = application.get(InstanceModel.ID);
            if (applicationId == null) {
                continue;
            }
            List<Row> latest = operations.findForOwner(InstanceModel.MODEL_ID.toString(),
                applicationId, 1);
            if (latest.isEmpty()) {
                continue;
            }
            Row operation = latest.get(0);
            if (ReleaseOperationModel.LIFECYCLE.is(
                    operation.get(ReleaseOperationModel.STATUS), OperationStatus.FAILED)) {
                AttentionSubject subject = AttentionSubject.instance(applicationId);
                items.add(item(AttentionSeverity.ERROR, "rocket",
                    AppHealth.Stoppage.DEPLOY_FAILED.title(application.get(InstanceModel.NAME)),
                    literal(operation.get(ReleaseOperationModel.FAILURE_REASON)),
                    CmsRoutes.subpage(ADMIN, HohenheimSlugs.INSTANCES, applicationId,
                        HohenheimSlugs.Tab.DEPLOYMENTS),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_see_deploy"))
                    .about(subject, sitesHeldText(sitesHeld.get(subject))));
            }
        }
    }
}
