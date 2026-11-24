package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.hohenheim.server.cms.DeleteImpact;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceImagePolicy;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.server.project.ProjectGuards;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import be.elevenways.zenit.common.security.SystemPurpose;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Docker containers that take their image from an app ({@code instances.image_app_id}): what one runs, the write
 * guards of the reference, and the one convergence that restarts them on the version their app serves.
 *
 * AIDEV-NOTE: "never two at once" is not this class's: one record is one container name, every create replaces that
 * one handle, and every create holds the record's claim (InstanceOperationLock). This class only decides WHEN a
 * container restarts, and every restart it asks for is InstanceService.restart queued on that same claim through
 * InstanceStatusReconciler.redeployInBackground, the one background-redeploy home.
 *
 * AIDEV-NOTE: the doors. A switch of the app (converge, rollback, restore, in a finally so a throw after the switch is
 * covered) and boot (convergeAll) call {@link #converge}, whose refusals are recorded on the container once per
 * switch; the status sweep calls {@link #followFromSweep} for a container it confirmed running, whose attempts only
 * log. The sweep never acts on a container in {@code error}: a switch gives an errored one ONE start on a new image,
 * and anything after that is its crash policy's or its operator's.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class AppImageFollowers {

    /**
     * What a container that takes its image from an app would run now.
     *
     * @param image   the local image id of the app's serving release, "" when refused
     * @param refusal why it can run nothing now, or null
     */
    public record Source(@NonNull String image, @Nullable Microcopy refusal) {
    }

    /** {@link Source} with the rows it was read from, for the convergence's decision. */
    private record Followed(@Nullable Row app, @Nullable Row serving, @NonNull String image,
                            @Nullable Microcopy refusal) {
    }

    private static volatile boolean installed;

    private AppImageFollowers() {
    }

    // -- what a container runs --------------------------------------------------

    /**
     * What this container runs when it takes its image from an app; asked at every resolve, so a reference that
     * drifted since its write (the app trashed, moved or given to another owner) is refused at the next create.
     *
     * @return null for a record without the reference, which takes today's path untouched
     */
    public static @Nullable Source sourceOf(@NonNull Row instance) {
        Followed followed = followedBy(instance);
        return followed == null ? null : new Source(followed.image(), followed.refusal());
    }

    private static @Nullable Followed followedBy(@NonNull Row instance) {
        Integer applicationId = instance.get(InstanceModel.IMAGE_APP_ID);
        if (applicationId == null) {
            return null;
        }
        Row app = applicationRow(applicationId);
        if (app == null) {
            return new Followed(null, null, "", HohenheimMicrocopy.VIOLATIONS.of("image_from_app_unknown"));
        }
        Integer instanceId = instance.get(InstanceModel.ID);
        // Fails CLOSED on unreadable grants, like every owner comparison.
        if (instanceId == null || !HohenheimAccess.sameOwner(InstanceModel.MODEL_ID, instanceId, applicationId)) {
            return new Followed(app, null, "", appRefusal("image_from_app_other_owner", app));
        }
        if (HostModel.canonicalHostId(instance.get(InstanceModel.HOST_ID))
                != HostModel.canonicalHostId(app.get(InstanceModel.HOST_ID))) {
            return new Followed(app, null, "", appRefusal("image_from_app_other_server", app));
        }
        Row serving = ApplicationReleases.ownedServing(applicationId);
        String image = ReleaseEngine.imageOf(serving);
        if (image.isEmpty()) {
            return new Followed(app, serving, "", appRefusal("image_from_app_not_deployed", app));
        }
        return new Followed(app, serving, image, null);
    }

    /** @return the live record of an app, null when it is missing, trashed or of another kind */
    private static @Nullable Row applicationRow(int applicationId) {
        Row app = Models.get(InstanceModel.class).findById(applicationId);
        return app != null && ApplicationKind.ID.toString().equals(app.get(InstanceModel.KIND)) ? app : null;
    }

    private static @NonNull Microcopy appRefusal(@NonNull String key, @NonNull Row app) {
        return HohenheimMicrocopy.VIOLATIONS.of(key).withArg("app", nameOf(app));
    }

    private static @NonNull String nameOf(@NonNull Row instance) {
        return String.valueOf((Object) instance.get(InstanceModel.NAME));
    }

    /**
     * The live containers that take their image from each of these apps, in one indexed read; a trashed container is
     * hidden by the default read, so it never counts.
     *
     * @return containers by app id, each list in name order; an app without any is absent
     */
    public static @NonNull Map<Integer, List<Row>> followersAmong(@NonNull Collection<Integer> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<Row>> byApp = KeyedRows.byAsked(Models.get(InstanceModel.class).find(),
            InstanceModel.IMAGE_APP_ID, applicationIds);
        Map<Integer, List<Row>> ordered = new LinkedHashMap<>();
        byApp.forEach((applicationId, followers) -> {
            List<Row> sorted = new ArrayList<>(followers);
            sorted.sort(Comparator.comparing(AppImageFollowers::nameOf));
            ordered.put(applicationId, List.copyOf(sorted));
        });
        return ordered;
    }

    /**
     * Whether a container still runs an older version than the release that serves now: a running one runs another
     * image, or was started before that release was created (a deploy that changed only variables or the command
     * makes a new release with the same image, and must restart it too); an errored one counts only for another image.
     *
     * AIDEV-NOTE: the serving release's created_at is the moment of the newest switch's candidate, before its command
     * and its probe: a container started inside that window is not restarted by a same-image deploy, which is harmless
     * (it read its own variables at that start). Stopped and created containers are never behind: an operator's Stop
     * wins, and the container follows at its next Start, which resolves what serves then.
     *
     * @param errorFollows whether a container in {@code error} on another image counts (a switch's door; the sweep's
     *                     door never acts on one)
     */
    static boolean behind(@NonNull Row follower, @NonNull Row serving, @NonNull String image, boolean errorFollows) {
        String status = follower.get(InstanceModel.STATUS);
        boolean otherImage = !image.equals(follower.get(InstanceModel.IMAGE_FINGERPRINT));
        if (InstanceModel.STATUS_RUNNING.equals(status) || InstanceModel.STATUS_STARTING.equals(status)) {
            Instant started = follower.get(InstanceModel.STARTED_AT);
            Instant switched = serving.get(InstanceModel.CREATED_AT);
            return otherImage || started == null || (switched != null && started.isBefore(switched));
        }
        return errorFollows && InstanceModel.STATUS_ERROR.equals(status) && otherImage;
    }

    /**
     * What a record page says of a container's reference.
     *
     * @param app          the app it names, null when that is gone or no app
     * @param refusal      why it can run nothing now, or null
     * @param olderVersion whether it runs while still on an older version than its app serves
     */
    public record Reading(@Nullable Row app, @Nullable Microcopy refusal, boolean olderVersion) {
    }

    /** @return the reading of this container's reference, null for a record without one */
    public static @Nullable Reading readingOf(@NonNull Row instance) {
        Followed followed = followedBy(instance);
        if (followed == null) {
            return null;
        }
        return new Reading(followed.app(), followed.refusal(), followed.refusal() == null
            && followed.serving() != null && behind(instance, followed.serving(), followed.image(), false));
    }

    /**
     * Which of one app's containers run while still on an older version than it serves, its serving release read
     * once whatever their number.
     *
     * @return the ids of those containers
     */
    public static @NonNull Set<Integer> olderVersionAmong(int applicationId, @NonNull List<Row> followers) {
        Set<Integer> older = new LinkedHashSet<>();
        if (followers.isEmpty()) {
            return older;
        }
        Row serving = ApplicationReleases.ownedServing(applicationId);
        String image = ReleaseEngine.imageOf(serving);
        if (image.isEmpty()) {
            return older;
        }
        for (Row follower : followers) {
            if (behind(follower, serving, image, false)) {
                older.add(follower.get(InstanceModel.ID));
            }
        }
        return older;
    }

    // -- the doors ----------------------------------------------------------------

    /**
     * THE convergence of a switch: every container that takes its image from this app and is behind what serves now
     * is restarted on it, each on its own claim; never throws.
     *
     * AIDEV-NOTE: an app without such a container pays one indexed read. Called in a finally by every switch door, so
     * what serves after a throw is followed too; it only reads and queues.
     */
    public static void converge(int applicationId) {
        try {
            List<Row> followers = followersAmong(List.of(applicationId)).getOrDefault(applicationId, List.of());
            if (followers.isEmpty()) {
                return;
            }
            Row serving = ApplicationReleases.ownedServing(applicationId);
            String image = ReleaseEngine.imageOf(serving);
            if (image.isEmpty()) {
                return;
            }
            for (Row follower : followers) {
                if (behind(follower, serving, image, true)) {
                    follow(follower, applicationId, true);
                }
            }
        } catch (RuntimeException failed) {
            Blast.log("APPLICATION: the containers taking their image from application", applicationId,
                "could not be restarted on its new version -", failed.getMessage());
        }
    }

    /**
     * The status sweep's door, for a container it just confirmed running: one still behind its app follows now, and a
     * refusal only logs; never throws.
     *
     * @param follower the record as the sweep read it; one without the reference costs nothing
     */
    public static void followFromSweep(@NonNull Row follower) {
        Integer applicationId = follower.get(InstanceModel.IMAGE_APP_ID);
        if (applicationId == null) {
            return;
        }
        try {
            Followed followed = followedBy(follower);
            if (followed != null && followed.refusal() == null && followed.serving() != null
                    && behind(follower, followed.serving(), followed.image(), false)) {
                follow(follower, applicationId, false);
            }
        } catch (RuntimeException failed) {
            Blast.log("APPLICATION: the sweep could not restart", follower.get(InstanceModel.NAME),
                "on the version of its app -", failed.getMessage());
        }
    }

    /**
     * Boot's door: a controller stopped between a switch and its restarts leaves containers behind, and every app
     * with one converges now.
     */
    public static void convergeAll() {
        try {
            Set<Integer> applicationIds = new LinkedHashSet<>();
            for (Row follower : Models.get(InstanceModel.class).find()
                    .where(InstanceModel.IMAGE_APP_ID.isNotNull()).all()) {
                applicationIds.add(follower.get(InstanceModel.IMAGE_APP_ID));
            }
            applicationIds.forEach(AppImageFollowers::converge);
        } catch (RuntimeException failed) {
            Blast.log("APPLICATION: the containers taking their image from an app could not be converged at boot -",
                failed.getMessage());
        }
    }

    /** Queues one container's restart on its own claim, decided again under it. */
    private static void follow(@NonNull Row follower, int applicationId, boolean switchDoor) {
        InstanceStatusReconciler.redeployInBackground(new InstanceService(), follower.get(InstanceModel.ID),
            nameOf(follower), new Follow(applicationId, switchDoor));
    }

    /**
     * A container's restart on the version its app serves: the same verb for a running and an errored one, run as
     * declared system work (the caller may be a tenant with power on the app only).
     *
     * @param switchDoor whether a switch asked; only then is a refusal recorded on the container, so a standing
     *                   refusal is one activity row per switch, never one per sweep
     */
    private record Follow(int applicationId, boolean switchDoor)
            implements InstanceStatusReconciler.BackgroundRedeploy {

        @Override
        public @NonNull String what() {
            return "restart on the version its app serves";
        }

        @Override
        public @NonNull SystemPurpose purpose() {
            return HohenheimSystemPurposes.IMAGE_FOLLOW;
        }

        @Override
        public boolean stillWanted(@NonNull Row current) {
            if (!Objects.equals(current.get(InstanceModel.IMAGE_APP_ID), this.applicationId)) {
                return false;
            }
            Row serving = ApplicationReleases.ownedServing(this.applicationId);
            String image = ReleaseEngine.imageOf(serving);
            return !image.isEmpty() && behind(current, serving, image, this.switchDoor);
        }

        @Override
        public void run(@NonNull InstanceService instances, int instanceId) {
            instances.restart(instanceId, DeployTrigger.SYSTEM);
            History.event(Models.get(InstanceModel.class), instanceId, Change.fact(HohenheimActivityAction.FOLLOWED_APP,
                Map.of(HohenheimActivityAction.APP_KEY, appName())));
        }

        @Override
        public void refused(@NonNull InstanceService instances, int instanceId, @NonNull RuntimeException refused) {
            if (!this.switchDoor) {
                return;
            }
            // Only while the old version still runs: a refusal after the halt is the record's own error, its cause
            // stamped by the deploy that failed.
            Row current = Models.get(InstanceModel.class).findById(instanceId);
            String status = current == null ? null : current.get(InstanceModel.STATUS);
            if (InstanceModel.STATUS_RUNNING.equals(status) || InstanceModel.STATUS_STARTING.equals(status)) {
                History.event(Models.get(InstanceModel.class), instanceId, Change.fact(
                    HohenheimActivityAction.APP_FOLLOW_REFUSED, Map.of(HohenheimActivityAction.APP_KEY, appName(),
                        HohenheimActivityAction.REASON_KEY, HohenheimViolations.reasonOf(refused))));
            }
        }

        private @NonNull String appName() {
            Row app = Models.get(InstanceModel.class).findById(this.applicationId);
            return app == null ? String.valueOf(this.applicationId) : nameOf(app);
        }
    }

    /**
     * The deploy-log line naming the containers this app's switch restarts, written by the engine on the operation's
     * own thread; an English literal like every step line.
     *
     * @return the line, or null when no container follows the app or none is behind
     */
    public static @Nullable String stepLine(int applicationId) {
        List<Row> followers = followersAmong(List.of(applicationId)).getOrDefault(applicationId, List.of());
        if (followers.isEmpty()) {
            return null;
        }
        Row serving = ApplicationReleases.ownedServing(applicationId);
        String image = ReleaseEngine.imageOf(serving);
        if (image.isEmpty()) {
            return null;
        }
        List<Row> restarting = new ArrayList<>();
        for (Row follower : followers) {
            if (behind(follower, serving, image, true)) {
                restarting.add(follower);
            }
        }
        return restarting.isEmpty() ? null
            : "containers restarting on this version: " + DeleteImpact.workloadNames(restarting);
    }

    // -- the write guards ---------------------------------------------------------

    /**
     * Install the write guards of the reference; idempotent, called at the MODULES boot stage.
     *
     * AIDEV-NOTE: beforeValidate, not beforeWrite, for ProjectGuards' reason (a beforeWrite refusal would spend a
     * quota slot). Like its environment hook, a reference is judged only when the write PLACES it or moves what it
     * depends on, so a record that drifted (the app moved owner through grants) can still be edited, stopped and
     * deleted; drift is refused at resolve.
     */
    public static synchronized void installGuards() {
        if (installed) {
            return;
        }
        installed = true;
        InstanceModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null || !(stages(row, InstanceModel.IMAGE_APP_ID) || stages(row, InstanceModel.KIND)
                    || stages(row, InstanceModel.HOST_ID) || stages(row, InstanceModel.SETTINGS))) {
                return;
            }
            Row stored = StoredRows.of(Models.get(InstanceModel.class), row);
            judgeContainer(row, stored);
            if (stored != null) {
                judgeApplication(row, stored);
            }
        });
    }

    private static boolean stages(@NonNull Row row, @NonNull Field<?, ?> field) {
        return row.has(field.getName());
    }

    /** The follower's side: a reference placed, or kind, host or image changed under one. */
    private static void judgeContainer(@NonNull Row row, @Nullable Row stored) {
        Integer applicationId = row.afterWrite(InstanceModel.IMAGE_APP_ID, stored);
        Object storedApplication = stored == null ? null : stored.get(InstanceModel.IMAGE_APP_ID);
        boolean placed = stages(row, InstanceModel.IMAGE_APP_ID) && !Objects.equals(applicationId, storedApplication);
        if (applicationId == null) {
            return;
        }
        String field = InstanceModel.IMAGE_APP_ID.getName();
        // Operator-only: an update of the column is already frozen to a tenant (TenantWrites' instance whitelist does
        // not name it); a create is not judged there, so this closes it, as the operator-only network mode is closed.
        if (placed && TenantWrites.isTenantOriginated()) {
            throw Violations.ofField(field, applicationId, HohenheimMicrocopy.VIOLATIONS.of("tenant_field_frozen"));
        }
        if ((placed || changed(row, stored, InstanceModel.KIND))
                && !DockerContainerKind.ID.toString().equals(row.afterWrite(InstanceModel.KIND, stored))) {
            throw Violations.ofField(field, applicationId, HohenheimMicrocopy.VIOLATIONS.of("image_from_app_kind"));
        }
        if ((placed || stages(row, InstanceModel.SETTINGS))
                && !InstanceImagePolicy.declaresNoImage(row.afterWrite(InstanceModel.SETTINGS, stored))) {
            throw Violations.ofField(InstanceModel.SETTINGS.getName() + ".image", null,
                HohenheimMicrocopy.VIOLATIONS.of("image_and_image_from_app"));
        }
        boolean moved = changed(row, stored, InstanceModel.HOST_ID);
        if (!placed && !moved) {
            return;
        }
        Row app = applicationRow(applicationId);
        if (app == null) {
            throw Violations.ofField(field, applicationId, HohenheimMicrocopy.VIOLATIONS.of("image_from_app_unknown"));
        }
        if (placed) {
            Object rowId = stored != null ? stored.get(InstanceModel.ID) : null;
            // A tenant's placement was refused above, so the owner here is a stored record's or the operator's.
            Set<String> owner = ProjectGuards.ownerOf(rowId, null);
            Set<String> appOwner = HohenheimAccess.manageSubjectsOf(InstanceModel.MODEL_ID, applicationId);
            if (owner == null || !owner.equals(appOwner)) {
                throw Violations.ofField(field, applicationId, appRefusal("image_from_app_other_owner", app));
            }
        }
        if (HostModel.canonicalHostId(row.afterWrite(InstanceModel.HOST_ID, stored))
                != HostModel.canonicalHostId(app.get(InstanceModel.HOST_ID))) {
            throw Violations.ofField(field, applicationId, appRefusal("image_from_app_other_server", app));
        }
    }

    /** The app's side: its host or its kind may not change while containers take their image from it. */
    private static void judgeApplication(@NonNull Row row, @NonNull Row stored) {
        if (!ApplicationKind.ID.toString().equals(stored.get(InstanceModel.KIND))) {
            return;
        }
        boolean moved = stages(row, InstanceModel.HOST_ID)
            && HostModel.canonicalHostId(row.get(InstanceModel.HOST_ID))
                != HostModel.canonicalHostId(stored.get(InstanceModel.HOST_ID));
        boolean rekinded = changed(row, stored, InstanceModel.KIND);
        if (!moved && !rekinded) {
            return;
        }
        int applicationId = stored.get(InstanceModel.ID);
        List<Row> followers = followersAmong(List.of(applicationId)).getOrDefault(applicationId, List.of());
        if (followers.isEmpty()) {
            return;
        }
        String containers = DeleteImpact.workloadNames(followers);
        if (moved) {
            throw Violations.ofField(InstanceModel.HOST_ID.getName(), row.get(InstanceModel.HOST_ID),
                HohenheimMicrocopy.VIOLATIONS.of("app_server_change_has_followers").withArg("containers", containers));
        }
        throw Violations.ofField(InstanceModel.KIND.getName(), row.get(InstanceModel.KIND),
            HohenheimMicrocopy.VIOLATIONS.of("app_kind_change_has_followers").withArg("containers", containers));
    }

    /** @return whether the write stages another value of this column than the stored row holds */
    private static boolean changed(@NonNull Row row, @Nullable Row stored,
                                   @NonNull Field<?, ?> field) {
        return stored != null && stages(row, field) && !Objects.equals(row.get(field.getName()), stored.get(field));
    }
}
