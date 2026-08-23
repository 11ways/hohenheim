package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.server.BootSettle;
import be.elevenways.hohenheim.server.build.BuildArtifacts;
import be.elevenways.hohenheim.server.database.DatabaseEnvInjection;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.orm.RecordStamp;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The health-gated zero-downtime release engine of the application tier: create candidate,
 * probe, switch, drain, retain, reclaim -- every attempt a durable
 * {@link ReleaseOperationModel} row, every step visible in its log. A failed candidate
 * NEVER replaces the serving release: it is destroyed and the operation records failed
 * while the prior release keeps serving. Rollback is the SAME gated operation over the
 * RETAINED release's digest-pinned instance settings -- no rebuild, no source, no tag.
 *
 * AIDEV-NOTE: where the atomicity of the traffic switch comes from -- this engine runs
 * inside the construction of the INCOMING routing generation (SiteDispatcher builds the
 * whole new RouteTable before the volatile swap, and pins every in-flight request to the
 * generation it arrived on). The engine only ever RETURNS a fully-probed upstream, so a
 * request is either pinned to the outgoing generation (old release, kept running through
 * the drain window) or dispatched by the new one (candidate, already healthy). No
 * request can observe a half-configured state; there is no in-place mutation to race.
 *
 * AIDEV-NOTE: retention/reclaim policy -- exactly ONE superseded release is retained per
 * application (role {@code retired}: the instance row with the digest-pinned spec plus its
 * stopped container, whose existence also pins the image against the per-build prune).
 * Reclaim happens at the END of the next successful operation's drain phase: every
 * retired release except the newest is verified-destroyed and the application's build artifacts
 * are pruned down to the serving digest. Application delete destroys all of it (destroyFor).
 */
public final class ReleaseEngine {

    /** The activity action a SETTLED application rollback is recorded under. */
    public static final String ACTIVITY_ROLLBACK_ACTION = "rolled_back";

    /** Keys of {@code adjustPaths}-injected checkout paths: per-slot, never source identity. */
    private static final List<String> VOLATILE_SETTINGS =
        List.of("build_context", "working_directory", "script", "root_path");

    private ReleaseEngine() {
    }

    // -- source identity ------------------------------------------------------

    /**
     * Identity of the SOURCE a release would be produced from: the resolved application
     * settings with slot-dependent absolute paths dropped (commit_sha carries the
     * source identity for git checkouts). Matching fingerprints mean a release would
     * change nothing -- which is what lets an unchanged routing reload skip the
     * sandbox build entirely.
     */
    public static @NonNull String sourceFingerprint(int applicationId,
                                                    @NonNull Map<String, Object> settings) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (value != null && !VOLATILE_SETTINGS.contains(key)) {
                canonical.put(key, value);
            }
        });
        StringBuilder text = new StringBuilder("application:").append(applicationId);
        appendCanonical(text, canonical);
        appendDatabaseLinks(text, applicationId);
        return sha256(text.toString());
    }

    /**
     * Fold the application's database attachments into the source identity: an attach, a
     * detach, a prefix change, a credential re-provision or a status flip must all read
     * as "the release would change" -- the injected environment and the link-network
     * joins only converge through a release, and the fast reuse lane would otherwise
     * skip them forever on an unchanged image.
     */
    private static void appendDatabaseLinks(StringBuilder text, int applicationId) {
        List<Row> links = Models.get(InstanceDatabaseModel.class).findByInstanceId(applicationId);
        if (links.isEmpty()) {
            return;
        }
        DatabaseModel databases = Models.get(DatabaseModel.class);
        text.append("|dblinks:");
        for (Row link : links) {
            text.append(link.get(InstanceDatabaseModel.ID)).append(':')
                .append(link.get(InstanceDatabaseModel.DATABASE_ID)).append(':')
                .append(DatabaseEnvInjection.normalizedPrefix(
                    link.get(InstanceDatabaseModel.ENV_PREFIX)))
                .append(':');
            Row database = databases.find()
                .where(DatabaseModel.ID.eq(link.get(InstanceDatabaseModel.DATABASE_ID)))
                .first();
            if (database != null) {
                text.append(database.get(DatabaseModel.STATUS)).append(':')
                    .append(database.get(DatabaseModel.UPDATED_AT));
            }
            text.append(';');
        }
    }

    private static void appendCanonical(StringBuilder text, Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            text.append('{');
            sorted.forEach((k, v) -> {
                text.append(k).append('=');
                appendCanonical(text, v);
                text.append(';');
            });
            text.append('}');
        } else if (value instanceof List<?> list) {
            text.append('[');
            for (Object entry : list) {
                appendCanonical(text, entry);
                text.append(';');
            }
            text.append(']');
        } else if (value instanceof Number number) {
            // JSON round-trips change boxed numeric types; fold like settingsEqual does.
            text.append(number.doubleValue());
        } else {
            text.append(value);
        }
    }

    private static @NonNull String sha256(@NonNull String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Whether the application is PINNED to its rolled-back release: the newest succeeded
     * operation is a rollback and the source has not changed since -- the operator
     * rejected exactly this source, so converging back onto it would silently undo the
     * rollback on the next routing reload. Any source change dissolves the pin.
     */
    public static boolean pinnedByRollback(int applicationId, @NonNull String ownerFingerprint) {
        // Post-switch phases count: a rollback that has taken traffic but is still
        // draining MUST already pin, or the routing reload the rollback itself
        // triggers would converge straight back onto the rejected spec and undo it.
        Row latest = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .where(ReleaseOperationModel.STATUS.in(ReleaseOperationModel.STATUS_SWITCHING,
                ReleaseOperationModel.STATUS_DRAINING, ReleaseOperationModel.STATUS_SUCCEEDED))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
        return latest != null
            && ReleaseOperationModel.KIND_ROLLBACK.equals(latest.get(ReleaseOperationModel.KIND))
            && ownerFingerprint.equals(latest.get(ReleaseOperationModel.OWNER_FINGERPRINT));
    }

    // -- the operations -------------------------------------------------------

    /**
     * First release of an application that never had one: deploy directly, recorded as a
     * release operation. There is deliberately no probe GATE here -- with nothing
     * serving there is nothing a failed candidate could replace, and refusing would
     * only hide the workload's real state; the step log states this.
     *
     * @throws Violations when the deploy refuses (the application is down either way)
     */
    static ApplicationReleases.@NonNull Release initialRelease(int applicationId,
                                                             @Nullable String applicationName,
                                                             int serverId,
                                                             @NonNull Map<String, Object> desired,
                                                             @NonNull String ownerFingerprint) {
        Row op = newOperation(ReleaseOperationModel.KIND_RELEASE, applicationId,
            ownerFingerprint, ownerFingerprint);
        step(op, "initial release: no prior release to protect, deploying directly");
        try {
            Row instance = newInstanceRow(applicationId, applicationName, serverId, desired,
                InstanceModel.ROLE_SERVING);
            int instanceId = instance.get(InstanceModel.ID);
            transition(stamp(op).set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, instanceId),
                ReleaseOperationModel.STATUS_DEPLOYING,
                "instance " + instanceId + " created");
            InstanceStatus status = new InstanceService().deploy(instanceId);
            ApplicationUpstreams.invalidate(applicationId);
            finish(stamp(op).set(ReleaseOperationModel.IMAGE_ID, str(desired.get("image"))),
                ReleaseOperationModel.STATUS_SUCCEEDED, null, "deployed");
            return new ApplicationReleases.Release(instanceId, status);
        } catch (RuntimeException e) {
            finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(e), "deploy failed");
            throw e;
        }
    }

    /**
     * A forward release of changed source. When the prior release is actually serving
     * (running with a published port), the candidate is deployed BESIDE it, probed, and
     * only a healthy candidate takes the traffic over; any failure destroys the
     * candidate, records it, and RETURNS the prior release still serving -- a source
     * change can degrade an application to "stale but up", never to "down". When nothing is
     * serving, the spec is replaced in place (there is nothing to protect).
     */
    static ApplicationReleases.@NonNull Release release(@NonNull DockerClient docker, int applicationId,
                                                      @Nullable String applicationName, int serverId,
                                                      @NonNull Row serving,
                                                      @NonNull Map<String, Object> sourceSettings,
                                                      @NonNull String ownerFingerprint) {
        int servingId = serving.get(InstanceModel.ID);
        InstanceStatus oldLive = new InstanceService().liveStatus(servingId);
        // AIDEV-NOTE: workloadDead() is part of this test, not decoration. A container the
        // daemon reports RUNNING whose process was OOM-killed has NOTHING worth protecting:
        // with it counted as "protecting", an unchanged converge took the fingerprint-adopt
        // branch below, returned the same dead release and recorded the operation SUCCEEDED
        // ("spec unchanged; ... without a deploy"), so the reuse lane's own OOMKilled
        // refusal (ApplicationReleases.reusableStatus) achieved nothing and the application kept
        // pointing at a dead workload forever. Not protecting means the honest thing
        // happens instead: redeploy in place, no probe gate, because there is no live
        // traffic to gate against.
        boolean protecting = oldLive.running() && oldLive.publishedPort() != null
            && !oldLive.workloadDead();


        Row op = newOperation(ReleaseOperationModel.KIND_RELEASE, applicationId,
            ownerFingerprint, ownerFingerprint);
        Map<String, Object> desired;
        try {
            step(op, "resolving the desired spec (build/pull + digest pin)");
            desired = ApplicationReleases.desiredSettings(docker,
                ApplicationReleases.requireApplication(applicationId), sourceSettings);
            desired.put("source_fingerprint", ownerFingerprint);
        } catch (RuntimeException e) {
            finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(e),
                "spec resolution failed");
            if (protecting) {
                Blast.log("RELEASE: application", applicationId, "release failed before a candidate"
                    + " existed; the prior release keeps serving -", reasonOf(e));
                return new ApplicationReleases.Release(servingId, oldLive);
            }
            throw e;
        }

        if (specEquals(desired, ApplicationReleases.storedSettings(serving))
                && serverId == ServerModel.canonicalServerId(
                    serving.get(InstanceModel.SERVER_ID))) {
            // The source fingerprint drifted (legacy row, new derivation input) but the
            // SPEC did not: adopt the fingerprint so the fast lane hits from now on.
            Row adopting = reload(servingId);
            adopting.set(InstanceModel.SETTINGS, desired);
            Models.get(InstanceModel.class).save(adopting);
            finish(op, ReleaseOperationModel.STATUS_SUCCEEDED, null,
                "spec unchanged; fingerprint adopted without a deploy");
            if (protecting) {
                return new ApplicationReleases.Release(servingId, oldLive);
            }
            return new ApplicationReleases.Release(servingId,
                new InstanceService().deploy(servingId));
        }

        // A volume no two workloads may hold at once cannot be gated: the candidate would
        // mount the serving release's data WHILE it is still writing to it. Declaring one
        // buys stop-then-start (a real, visible outage) instead of a silent double mount.
        if (protecting && InstanceVolumes.hasExclusive(applicationId)) {
            step(op, "an exclusive volume is declared; stopping the serving release before"
                + " the new one starts instead of gating a candidate beside it");
            protecting = false;
        }

        // The pre-deploy snapshot: a point-in-time copy of the data the new release is
        // about to write to, taken BEFORE anything starts. A host whose backend cannot
        // snapshot refuses the deploy by name rather than pretending it protected anything.
        snapshotVolumes(op, applicationId, serverId);

        if (!protecting || !(desired.get("container_port") instanceof Number)) {
            // Nothing serving traffic (or a portless workload the proxy cannot probe):
            // replace in place, today's semantics -- the step log says so.
            step(op, "prior release not serving traffic; replacing in place without a probe gate");
            Row replacing = reload(servingId);
            replacing.set(InstanceModel.SETTINGS, desired);
            replacing.set(InstanceModel.SERVER_ID, serverId);
            Models.get(InstanceModel.class).save(replacing);
            transition(op, ReleaseOperationModel.STATUS_DEPLOYING, "deploying in place");
            try {
                InstanceStatus status = new InstanceService().deploy(servingId);
                ApplicationUpstreams.invalidate(applicationId);
                finish(stamp(op).set(ReleaseOperationModel.IMAGE_ID, str(desired.get("image"))),
                    ReleaseOperationModel.STATUS_SUCCEEDED, null, "deployed");
                return new ApplicationReleases.Release(servingId, status);
            } catch (RuntimeException e) {
                finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(e), "deploy failed");
                throw e;
            }
        }

        try {
            return gatedSwap(docker, op, applicationId, applicationName, serverId, serving, desired);
        } catch (RuntimeException e) {
            // The health gate held: the candidate never took traffic and is gone; the
            // prior release keeps serving. Loud in the log AND durable on the record.
            Blast.log("RELEASE: application", applicationId, "candidate refused -", reasonOf(e),
                "- the prior release keeps serving");
            return new ApplicationReleases.Release(servingId, oldLive);
        }
    }

    /**
     * Roll the application back to its RETAINED release: one durable operation over the
     * retired instance's digest-pinned settings. Nothing is rebuilt, cloned or pulled
     * from a tag -- the artifact is addressed by content, so a deleted checkout or a
     * moved tag cannot change what this deploys.
     *
     * AIDEV-NOTE: the operator verbs mint a candidate for the same application a webhook
     * converge does, so they take the SAME per-application convergence lock -- a rollback
     * racing a push would otherwise leave both their candidates role {@code serving}.
     *
     * @throws Violations when no rollback target exists or the candidate fails its
     *         probe (the current release keeps serving either way)
     */
    public static void rollback(int applicationId) {
        synchronized (ConvergenceLocks.forApplication(applicationId)) {
            rollbackLocked(applicationId);
        }
        // AIDEV-NOTE: no proxy reload any more, and that is the point of the re-keying. A
        // flip no longer changes the ROUTE TABLE at all -- the site's route names an
        // application, and the instance upstream handler re-resolves the serving release's
        // address off the generation gatedSwap just bumped. Rebuilding every site's handler
        // to move one upstream was the site-keyed shape.
        // AIDEV-NOTE: recorded HERE, in the engine, not on the surfaces. The admin row
        // action (SiteResource#rollbackAction) recorded nothing while the automation API
        // recorded "rollback_triggered" -- the same one-surface-audited asymmetry the
        // instance power path had. The engine writes its state through role saves and
        // ReleaseOperation rows, neither of which is an activity row about the SITE.
        ActivityLog.record(Models.get(InstanceModel.class), applicationId, ACTIVITY_ROLLBACK_ACTION,
            null);
    }

    /** {@link #rollback}'s body; callers hold the application's convergence lock. */
    private static void rollbackLocked(int applicationId) {
        ApplicationReleases.inScopeUnchecked(applicationId, () -> {
            Row serving = ApplicationReleases.ownedServing(applicationId);
            Row target = newestRetired(applicationId);
            if (target == null) {
                throw Violations.ofForm(Microcopy.of("release_no_rollback_target")
                    .withFilter("scope", "violations"));
            }
            if (serving == null) {
                throw Violations.ofForm(Microcopy.of("release_no_serving_release")
                    .withFilter("scope", "violations"));
            }
            Map<String, Object> desired = ApplicationReleases.storedSettings(target);
            int serverId = ServerModel.canonicalServerId(target.get(InstanceModel.SERVER_ID));
            String specFingerprint = str(desired.get("source_fingerprint"));
            String ownerFingerprint =
                str(ApplicationReleases.storedSettings(serving).get("source_fingerprint"));

            InstanceStatus oldLive =
                new InstanceService().liveStatus(serving.get(InstanceModel.ID));
            if (!oldLive.running() || oldLive.publishedPort() == null) {
                throw Violations.ofForm(Microcopy.of("release_no_serving_release")
                    .withFilter("scope", "violations"));
            }
            Row op = newOperation(ReleaseOperationModel.KIND_ROLLBACK, applicationId,
                ownerFingerprint, specFingerprint);
            step(op, "rolling back to retired instance " + target.get(InstanceModel.ID)
                + " (image " + str(desired.get("image")) + ")");
            DockerClient docker = serverId == ServerModel.localServerId()
                ? new DockerClient()
                : new ServerService().clientFor(ServerModel.nameOf(serverId));
            gatedSwap(docker, op, applicationId, serving.get(InstanceModel.NAME), serverId,
                serving, desired);
        });
    }

    /**
     * The gate itself: candidate beside the serving release, probe, atomic switch,
     * drain, retain, reclaim. Throws on ANY failure before the switch, always after
     * destroying the candidate -- a failed candidate never exists as a serving role,
     * never keeps a container, and its port claim dies with it.
     */
    private static ApplicationReleases.@NonNull Release gatedSwap(@NonNull DockerClient docker,
                                                                @NonNull Row op, int applicationId,
                                                                @Nullable String applicationName,
                                                                int serverId,
                                                                @NonNull Row serving,
                                                                @NonNull Map<String, Object> desired) {
        int servingId = serving.get(InstanceModel.ID);
        Integer candidateId = null;
        InstanceService instances = new InstanceService();
        try {
            Row candidate = newInstanceRow(applicationId, applicationName, serverId, desired,
                InstanceModel.ROLE_CANDIDATE);
            candidateId = candidate.get(InstanceModel.ID);
            transition(stamp(op).set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidateId),
                ReleaseOperationModel.STATUS_DEPLOYING,
                "candidate instance " + candidateId + " created beside serving instance "
                    + servingId);
            InstanceStatus candidateStatus = instances.deploy(candidateId);
            Integer port = candidateStatus.publishedPort();
            if (port == null) {
                throw Violations.ofForm(Microcopy.of("release_no_published_port")
                    .withFilter("scope", "violations"));
            }
            String healthPath = healthPath(desired);
            transition(op, ReleaseOperationModel.STATUS_PROBING,
                "candidate deployed on 127.0.0.1:" + port + "; probing " + healthPath);
            probe(port, healthPath);
            step(op, "health probe passed");

            // The switch: roles flip candidate-first, so a crash between the two writes
            // leaves the NEWEST serving row winning ownedServing (and boot recovery
            // completes the flip). Traffic follows atomically via the routing
            // generation swap -- see the class AIDEV-NOTE.
            transition(stamp(op)
                    .set(ReleaseOperationModel.RETIRED_INSTANCE_ID, servingId)
                    .set(ReleaseOperationModel.IMAGE_ID, str(desired.get("image"))),
                ReleaseOperationModel.STATUS_SWITCHING, "switching traffic");
            // AIDEV-NOTE: both flips are FENCED SINGLE-COLUMN writes, never Models.save of
            // the Row objects held here. Those objects are stale by construction -- the
            // candidate's was built before its deploy stamped status/fence/fingerprint, and
            // the serving one was loaded before the spec was resolved (a sandbox build, so
            // possibly minutes ago). A whole-row save would rewrite every column back to
            // its load-time value; that is what silently undid the volume heal and could
            // rewind claim_fence. See InstanceOperationGuard.stampRole.
            instances.assignRuntimeRole(candidateId, InstanceModel.ROLE_SERVING);
            candidate.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
            instances.assignRuntimeRole(servingId, InstanceModel.ROLE_RETIRED);
            serving.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_RETIRED);
            // The proxy resolves the serving release's address lazily and re-resolves when
            // this generation moves; without this bump it would keep forwarding to the
            // retired container until the drain stopped it, which IS the dropped request
            // the gated swap exists to prevent.
            ApplicationUpstreams.invalidate(applicationId);
            transition(op, ReleaseOperationModel.STATUS_DRAINING,
                "instance " + candidateId + " now serving; instance " + servingId
                    + " retained as the rollback target, draining");
            scheduleDrain(applicationId, op.get(ReleaseOperationModel.ID), servingId,
                str(desired.get("image")), Db.currentOrDefault());
            return new ApplicationReleases.Release(candidateId, candidateStatus);
        } catch (RuntimeException gateHeld) {
            finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(gateHeld),
                "candidate refused: " + reasonOf(gateHeld));
            if (candidateId != null) {
                destroyCandidateQuietly(candidateId);
            }
            throw gateHeld;
        }
    }

    /**
     * Snapshot every volume the application declares, before a deploy touches them.
     *
     * AIDEV-NOTE: the refusal is deliberately LOUD and not swallowed. An application with
     * no volumes snapshots nothing and deploys as before; an application WITH volumes on a
     * host that cannot snapshot is refused by name, because "we took a backup" is the one
     * claim that must never be a guess (phase-0 design section 5: no tar fallback).
     */
    private static void snapshotVolumes(@NonNull Row op, int applicationId, int serverId) {
        List<String> snapshots = InstanceVolumes.snapshotAll(applicationId,
            ServerModel.nameOf(serverId), "predeploy");
        if (!snapshots.isEmpty()) {
            step(op, "pre-deploy snapshot of " + snapshots.size() + " volume(s): "
                + String.join(", ", snapshots));
        }
    }

    // -- drain, retain, reclaim ----------------------------------------------

    /**
     * After the drain window: stop the superseded release (it stays retained as the
     * rollback target), reclaim every OLDER retired release, prune superseded build
     * artifacts, and stamp the operation succeeded. Runs on a virtual thread under the
     * datasource and attribution scopes captured at switch time; a controller crash
     * before it runs is finished by {@link #recoverInterrupted()}.
     */
    private static void scheduleDrain(int applicationId, int opId, int retiredId,
                                      @NonNull String servingImage,
                                      @Nullable Datasource datasource) {
        Integer seconds = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.DRAIN_SECONDS);
        long waitMs = Math.max(0, (seconds != null ? seconds : 15) * 1000L);
        JobRunner.startVirtualThread(() -> {
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            withScope(datasource, () -> ApplicationReleases.inScopeUnchecked(applicationId, () -> {
                Row op = Models.get(ReleaseOperationModel.class).findById(opId);
                if (op == null || !ReleaseOperationModel.STATUS_DRAINING.equals(
                        op.get(ReleaseOperationModel.STATUS))) {
                    return;
                }
                completeDrain(applicationId, op, retiredId, servingImage, "drain window elapsed");
            }));
        });
    }

    /** The shared drain completion: stop retained, reclaim older, prune, succeed. */
    private static void completeDrain(int applicationId, @NonNull Row op, int retiredId,
                                      @NonNull String servingImage, @NonNull String how) {
        try {
            Row retired = Models.get(InstanceModel.class).findById(retiredId);
            if (retired != null && retired.get(InstanceModel.DELETED_AT) == null
                    && InstanceModel.ROLE_RETIRED.equals(retired.get(InstanceModel.RUNTIME_ROLE))
                    && InstanceModel.STATUS_RUNNING.equals(retired.get(InstanceModel.STATUS))) {
                new InstanceService().stop(retiredId);
                step(op, how + "; superseded release stopped, retained as the rollback target");
            } else {
                step(op, how + "; superseded release already settled");
            }
        } catch (RuntimeException stopFailed) {
            // The release itself succeeded (traffic switched); the failed stop is
            // recorded verbatim and the instance tier's parked claims/reconciler
            // surface the leftover -- never a silent shrug, never a false "failed".
            step(op, "WARNING: superseded release stop failed: " + reasonOf(stopFailed));
            Blast.log("RELEASE: application", applicationId, "superseded release stop failed -",
                reasonOf(stopFailed));
        }
        reclaimOlderRetired(applicationId, retiredId, op);
        pruneArtifactsQuietly(applicationId, servingImage, op);
        // Detached databases lose their link network HERE, after the switch and once the
        // superseded release stopped: disconnecting a still-serving container would
        // re-allocate its published port under the proxy.
        try {
            InstanceDatabaseNetworks.sweepFor(applicationId, false);
        } catch (RuntimeException e) {
            step(op, "WARNING: database link-network sweep failed: " + reasonOf(e));
        }
        finish(op, ReleaseOperationModel.STATUS_SUCCEEDED, null, "release complete");
    }

    /** Destroy every retired release of the application EXCEPT the newest (the one retained). */
    private static void reclaimOlderRetired(int applicationId, int keepInstanceId, @NonNull Row op) {
        for (Row stale : ownedWithRole(applicationId, InstanceModel.ROLE_RETIRED)) {
            int staleId = stale.get(InstanceModel.ID);
            if (staleId == keepInstanceId) {
                continue;
            }
            try {
                new InstanceService().destroy(staleId);
                step(op, "reclaimed superseded release instance " + staleId);
            } catch (RuntimeException e) {
                step(op, "WARNING: could not reclaim instance " + staleId + ": " + reasonOf(e));
                Blast.log("RELEASE: application", applicationId, "could not reclaim instance", staleId,
                    "-", reasonOf(e));
            }
        }
    }

    private static void pruneArtifactsQuietly(int applicationId, @NonNull String servingImage,
                                              @NonNull Row op) {
        try {
            BuildArtifacts.pruneSuperseded(new DockerClient(), InstanceModel.MODEL_ID.toString(),
                applicationId, servingImage);
        } catch (RuntimeException e) {
            step(op, "WARNING: artifact prune failed: " + reasonOf(e));
        }
    }

    // -- boot recovery --------------------------------------------------------

    /**
     * Settle every operation a controller crash left in flight: pre-switch operations
     * lose their candidate and stamp {@code interrupted}; a half-flipped switch is
     * completed; a lost drain is finished (stop, reclaim, succeed). Also destroys any
     * orphaned candidate-role instance no in-flight operation answers for.
     */
    public static void recoverInterrupted() {
        ReleaseOperationModel ops = Models.get(ReleaseOperationModel.class);
        List<Row> inFlight = ops.find()
            .where(ReleaseOperationModel.STATUS.in(ReleaseOperationModel.STATUS_PENDING,
                ReleaseOperationModel.STATUS_DEPLOYING, ReleaseOperationModel.STATUS_PROBING,
                ReleaseOperationModel.STATUS_SWITCHING, ReleaseOperationModel.STATUS_DRAINING))
            .orderBy(ReleaseOperationModel.ID, SortOrder.ASC)
            .all();
        List<Integer> answered = new ArrayList<>();
        for (Row op : inFlight) {
            Integer applicationId = op.get(ReleaseOperationModel.FOR_ID);
            if (applicationId == null
                    || !InstanceModel.MODEL_ID.toString().equals(
                        op.get(ReleaseOperationModel.FOR_MODEL))) {
                finish(op, ReleaseOperationModel.STATUS_INTERRUPTED,
                    "interrupted by a controller restart", "boot recovery: unknown owner");
                continue;
            }
            Integer candidateId = op.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
            if (candidateId != null) {
                answered.add(candidateId);
            }
            try {
                // The borrowed-lease discipline BootSettle documents: recoverOne stops,
                // destroys and redeploys through InstanceService, each of which takes the
                // host's fence -- so an unguarded sweep would seize (and hold for this
                // process's lifetime) the lease of every host a stuck release sits on, and
                // would act on an operation a rival controller is still driving. A host
                // another controller holds is skipped: the release is that controller's.
                Integer serverId = releaseHostOf(op);
                Runnable recover = () ->
                    ApplicationReleases.inScopeUnchecked(applicationId, () -> recoverOne(applicationId, op));
                if (serverId == null) {
                    recover.run();
                } else {
                    BootSettle.underBorrowedHostLease(HostLeases.production(), serverId,
                        recover);
                }
            } catch (RuntimeException e) {
                Blast.log("RELEASE: recovery of operation",
                    op.get(ReleaseOperationModel.ID), "failed -", reasonOf(e));
            }
        }
        sweepOrphanCandidates(answered);
        sweepDuplicateServing();
    }

    /**
     * The repair lane for an application left with MORE THAN ONE serving release: every
     * row but {@link ApplicationReleases#ownedServing}'s own pick (the newest id) is
     * retired and reclaimed, loudly.
     *
     * AIDEV-NOTE: this is a NET, not the fix -- the per-application convergence lock is.
     * It exists because a lock is a per-PROCESS guarantee while the state it prevents is
     * durable: a database written by a controller from before the lock (or by two of them)
     * can already hold it, and nothing else would ever notice -- {@link #reclaimOlderRetired}
     * walks only {@code retired} rows and {@link #sweepOrphanCandidates} only
     * {@code candidate} ones, so a second serving release runs forever, holding its port
     * claim and its booked capacity.
     */
    public static void sweepDuplicateServing() {
        Map<Integer, List<Row>> byApplication = new LinkedHashMap<>();
        for (Row serving : Models.get(InstanceModel.class).find()
                .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_SERVING))
                .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(InstanceModel.DELETED_AT.isNull())
                .orderBy(InstanceModel.ID, SortOrder.DESC)
                .all()) {
            Integer applicationId = serving.get(InstanceModel.GENERATED_FOR_ID);
            if (applicationId != null) {
                byApplication.computeIfAbsent(applicationId, ignored -> new ArrayList<>())
                    .add(serving);
            }
        }
        for (Map.Entry<Integer, List<Row>> entry : byApplication.entrySet()) {
            List<Row> serving = entry.getValue();
            if (serving.size() < 2) {
                continue;
            }
            int applicationId = entry.getKey();
            // Newest-first, the ordering ownedServing itself resolves by: the row the proxy
            // is already forwarding to stays, every stranded older one is reclaimed.
            int keepId = serving.get(0).get(InstanceModel.ID);
            for (Row stranded : serving.subList(1, serving.size())) {
                int strandedId = stranded.get(InstanceModel.ID);
                Blast.log("RELEASE: application", applicationId, "had", serving.size(),
                    "serving releases; instance", keepId, "serves, reclaiming stranded"
                        + " instance", strandedId);
                ApplicationReleases.inScopeUnchecked(applicationId, () -> {
                    try {
                        InstanceService instances = new InstanceService();
                        instances.assignRuntimeRole(strandedId, InstanceModel.ROLE_RETIRED);
                        instances.destroy(strandedId);
                    } catch (RuntimeException e) {
                        Blast.log("RELEASE: could not reclaim stranded serving instance",
                            strandedId, "-", reasonOf(e));
                    }
                });
            }
        }
    }

    /**
     * The host a stuck release operation's workloads live on: the candidate's, else the
     * retired one's. Null when neither instance row is resolvable, in which case there is
     * no lease to borrow and the recovery has no daemon work to fence.
     */
    private static @Nullable Integer releaseHostOf(@NonNull Row op) {
        for (Integer instanceId : new Integer[] {
                op.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID),
                op.get(ReleaseOperationModel.RETIRED_INSTANCE_ID)}) {
            if (instanceId == null) {
                continue;
            }
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            Integer serverId = instance != null ? instance.get(InstanceModel.SERVER_ID) : null;
            if (serverId != null) {
                return serverId;
            }
        }
        return null;
    }

    private static void recoverOne(int applicationId, @NonNull Row op) {
        String status = op.get(ReleaseOperationModel.STATUS);
        Integer candidateId = op.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
        Integer retiredId = op.get(ReleaseOperationModel.RETIRED_INSTANCE_ID);

        if (ReleaseOperationModel.STATUS_DRAINING.equals(status)) {
            completeDrain(applicationId, op, retiredId != null ? retiredId : -1,
                servingImageOf(applicationId), "boot recovery finished the lost drain");
            return;
        }
        if (ReleaseOperationModel.STATUS_SWITCHING.equals(status) && candidateId != null) {
            Row candidate = Models.get(InstanceModel.class).findById(candidateId);
            boolean flipped = candidate != null
                && InstanceModel.ROLE_SERVING.equals(candidate.get(InstanceModel.RUNTIME_ROLE));
            if (flipped && retiredId != null) {
                Row old = Models.get(InstanceModel.class).findById(retiredId);
                if (old != null && InstanceModel.ROLE_SERVING.equals(
                        old.get(InstanceModel.RUNTIME_ROLE))) {
                    new InstanceService().assignRuntimeRole(retiredId,
                        InstanceModel.ROLE_RETIRED);
                    step(op, "boot recovery completed the half-flipped switch");
                }
                transition(op, ReleaseOperationModel.STATUS_DRAINING,
                    "boot recovery: switch had completed, draining");
                completeDrain(applicationId, op, retiredId, servingImageOf(applicationId),
                    "boot recovery finished the lost drain");
                return;
            }
        }
        // Pre-switch: the candidate never took traffic; destroy it and record the truth.
        if (candidateId != null) {
            destroyCandidateQuietly(candidateId);
        }
        finish(op, ReleaseOperationModel.STATUS_INTERRUPTED,
            "interrupted by a controller restart",
            "boot recovery: candidate destroyed, prior release untouched");
    }

    /** Destroy candidate-role release instances no in-flight operation answers for. */
    private static void sweepOrphanCandidates(@NonNull List<Integer> answered) {
        List<Row> candidates = Models.get(InstanceModel.class).find()
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_CANDIDATE))
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(InstanceModel.DELETED_AT.isNull())
            .all();
        for (Row candidate : candidates) {
            int candidateId = candidate.get(InstanceModel.ID);
            if (answered.contains(candidateId)) {
                continue;
            }
            Integer applicationId = candidate.get(InstanceModel.GENERATED_FOR_ID);
            Blast.log("RELEASE: destroying orphaned candidate instance", candidateId,
                "of application", applicationId);
            ApplicationReleases.inScopeUnchecked(applicationId != null ? applicationId : -1,
                () -> destroyCandidateQuietly(candidateId));
        }
    }

    private static @NonNull String servingImageOf(int applicationId) {
        Row serving = ApplicationReleases.ownedServing(applicationId);
        return serving != null
            ? str(ApplicationReleases.storedSettings(serving).get("image")) : "";
    }

    // -- the health probe -----------------------------------------------------

    /**
     * Interrogate the candidate on its published loopback port until it answers a
     * complete HTTP response below 500, or the probe window closes.
     *
     * AIDEV-NOTE: this is deliberately NOT the console readiness_line matcher. That
     * mechanism is a template-declared substring watch on an attached console stream --
     * the right gate for game/template workloads that announce readiness in text. A
     * reverse-proxied SITE is gated on the thing the proxy will actually do: an HTTP
     * round-trip against the port that will serve production traffic. Two mechanisms,
     * two declared homes, one job each.
     */
    public static void probe(int port, @NonNull String path) {
        Integer timeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        Integer interval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        long deadline = System.currentTimeMillis()
            + Math.max(1, timeout != null ? timeout : 60) * 1000L;
        long pause = Math.max(50, interval != null ? interval : 500);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        URI target = URI.create("http://127.0.0.1:" + port
            + (path.startsWith("/") ? path : "/" + path));
        String lastFailure = "no response";
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<Void> response = client.send(HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) {
                    return;
                }
                lastFailure = "HTTP " + response.statusCode();
            } catch (IOException notUp) {
                lastFailure = notUp.getMessage() != null ? notUp.getMessage() : "connect failed";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw Violations.ofForm(Microcopy.of("release_probe_failed")
                    .withFilter("scope", "violations").withArg("reason", "interrupted"));
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw Violations.ofForm(Microcopy.of("release_probe_failed")
            .withFilter("scope", "violations").withArg("reason", lastFailure));
    }

    private static @NonNull String healthPath(@NonNull Map<String, Object> desired) {
        String path = str(desired.get("health_path"));
        return path.isEmpty() ? "/" : path;
    }

    // -- internals ------------------------------------------------------------

    /**
     * Re-read an instance row immediately before a WHOLE-ROW save.
     *
     * AIDEV-NOTE: {@code Models.save} writes every column PRESENT on the Row, and a row
     * loaded from the database carries all of them -- so saving a Row loaded before the
     * spec was resolved (a sandbox build, so possibly minutes ago) rewrites status,
     * claim_fence and image_fingerprint back to their load-time values. Rewinding
     * claim_fence would erase a rival controller's authority over the record. Unlike the
     * role flip, a SETTINGS write cannot become a hook-free {@code updateAll}: the
     * image-change hook that clears the fingerprint pin ({@code InstanceImagePin}) lives
     * in the save pipeline, so the fix here is a reload, not a targeted assign.
     *
     * @throws Violations when the release's own row vanished mid-operation
     */
    private static @NonNull Row reload(int instanceId) {
        Row fresh = Models.get(InstanceModel.class).findById(instanceId);
        if (fresh == null) {
            throw Violations.ofForm(Microcopy.of("release_no_serving_release")
                .withFilter("scope", "violations"));
        }
        return fresh;
    }

    /** A fresh instance row of the release kind, in the given role. */
    private static @NonNull Row newInstanceRow(int applicationId, @Nullable String applicationName,
                                               int serverId,
                                               @NonNull Map<String, Object> desired,
                                               @NonNull String role) {
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME,
            applicationName != null && !applicationName.isBlank() ? applicationName : "application-" + applicationId);
        instance.set(InstanceModel.KIND, ReleaseKind.ID.toString());
        instance.set(InstanceModel.SETTINGS, desired);
        instance.set(InstanceModel.SERVER_ID, serverId);
        instance.set(InstanceModel.RUNTIME_ROLE, role);
        Models.get(InstanceModel.class).save(instance);
        return instance;
    }

    /**
     * Verified destroy of a refused candidate; failure parks, logs, never throws.
     * Refuses anything that is not still a CANDIDATE -- a row that made it to serving
     * must never be destroyed by a cleanup path.
     */
    private static void destroyCandidateQuietly(int candidateId) {
        try {
            Row row = Models.get(InstanceModel.class).findById(candidateId);
            if (row == null || row.get(InstanceModel.DELETED_AT) != null
                    || !InstanceModel.ROLE_CANDIDATE.equals(row.get(InstanceModel.RUNTIME_ROLE))) {
                return;
            }
            new InstanceService().destroy(candidateId);
        } catch (RuntimeException e) {
            // The record stays (status error, claims parked); the reconciler surfaces it.
            Blast.log("RELEASE: could not destroy refused candidate", candidateId,
                "-", reasonOf(e));
        }
    }

    static @NonNull List<Row> ownedWithRole(int applicationId, @NonNull String role) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(applicationId))
            .where(InstanceModel.RUNTIME_ROLE.eq(role))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .all();
    }

    /** The application's newest retained release (the rollback target), or null. */
    public static @Nullable Row newestRetired(int applicationId) {
        List<Row> retired = ownedWithRole(applicationId, InstanceModel.ROLE_RETIRED);
        return retired.isEmpty() ? null : retired.get(0);
    }

    // -- operation record plumbing -------------------------------------------

    private static @NonNull Row newOperation(@NonNull String kind, int applicationId,
                                             @NonNull String ownerFingerprint,
                                             @NonNull String specFingerprint) {
        ReleaseOperationModel model = Models.get(ReleaseOperationModel.class);
        Row op = model.createEmptyRow();
        op.set(ReleaseOperationModel.KIND, kind);
        op.set(ReleaseOperationModel.FOR_MODEL, InstanceModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, applicationId);
        op.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_PENDING);
        op.set(ReleaseOperationModel.OWNER_FINGERPRINT, ownerFingerprint);
        op.set(ReleaseOperationModel.SPEC_FINGERPRINT, specFingerprint);
        op.set(ReleaseOperationModel.STARTED_AT, Instant.now());
        op.set(ReleaseOperationModel.STEP_LOG, "");
        model.save(op);
        return op;
    }

    /**
     * Start a NARROW write against the operation record.
     *
     * AIDEV-NOTE: every write to an operation row goes through here, for the reason
     * {@code InstanceOperationGuard.stampRole} exists on the instance row. An operation
     * is held open across minutes of daemon work -- a sandbox build, a probe window, a
     * drain -- and the drain's own row is LOADED (every column present) before that
     * work starts. {@code Models.save} writes every column present, so a step recording
     * one log line used to rewrite the kind, the owner, the fingerprints and the
     * candidate/retired pointers back to their load-time values, and report success. A
     * step that changes two columns writes two columns; a caller with more to record
     * stages it on the same stamp.
     */
    private static @NonNull RecordStamp stamp(@NonNull Row op) {
        return RecordStamp.on(Models.get(ReleaseOperationModel.class), op);
    }

    /** Append a timestamped step line and persist it with whatever else is staged. */
    private static void step(@NonNull Row op, @NonNull String line) {
        step(stamp(op), line);
    }

    private static void step(@NonNull RecordStamp stamp, @NonNull String line) {
        String existing = stamp.row().get(ReleaseOperationModel.STEP_LOG);
        stamp.set(ReleaseOperationModel.STEP_LOG,
                (existing == null ? "" : existing) + Instant.now() + " " + line + "\n")
            .write();
    }

    private static void transition(@NonNull Row op, @NonNull String status,
                                   @NonNull String line) {
        transition(stamp(op), status, line);
    }

    private static void transition(@NonNull RecordStamp stamp, @NonNull String status,
                                   @NonNull String line) {
        step(stamp.set(ReleaseOperationModel.STATUS, status), line);
    }

    private static void finish(@NonNull Row op, @NonNull String status,
                               @Nullable String failureReason, @NonNull String line) {
        finish(stamp(op), status, failureReason, line);
    }

    private static void finish(@NonNull RecordStamp stamp, @NonNull String status,
                               @Nullable String failureReason, @NonNull String line) {
        Instant finished = Instant.now();
        stamp.set(ReleaseOperationModel.STATUS, status)
            .set(ReleaseOperationModel.FAILURE_REASON, failureReason)
            .set(ReleaseOperationModel.FINISHED_AT, finished);
        Instant started = stamp.row().get(ReleaseOperationModel.STARTED_AT);
        if (started != null) {
            stamp.set(ReleaseOperationModel.DURATION_MS,
                (int) (finished.toEpochMilli() - started.toEpochMilli()));
        }
        step(stamp, line);
        prune(stamp.row());
    }

    /** Keep the newest N operations per owning record; older rows go. */
    private static void prune(@NonNull Row op) {
        Integer keep = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.HISTORY_PER_RECORD);
        int limit = keep != null && keep > 0 ? keep : 50;
        ReleaseOperationModel model = Models.get(ReleaseOperationModel.class);
        List<Row> stale = model.find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(op.get(ReleaseOperationModel.FOR_MODEL)))
            .where(ReleaseOperationModel.FOR_ID.eq(op.get(ReleaseOperationModel.FOR_ID)))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .offset(limit)
            .limit(1000)
            .all();
        for (Row old : stale) {
            model.delete(old.get(ReleaseOperationModel.ID));
        }
    }

    /** Spec equality ignoring the source fingerprint (an identity, not a spec input). */
    private static boolean specEquals(@NonNull Map<String, Object> left,
                                      @NonNull Map<String, Object> right) {
        Map<String, Object> a = new LinkedHashMap<>(left);
        Map<String, Object> b = new LinkedHashMap<>(right);
        a.remove("source_fingerprint");
        b.remove("source_fingerprint");
        return ApplicationReleases.settingsEqual(a, b);
    }

    private static void withScope(@Nullable Datasource datasource, @NonNull Runnable body) {
        if (datasource != null) {
            Db.run(datasource, body);
        } else {
            body.run();
        }
    }

    private static @NonNull String reasonOf(@NonNull Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
