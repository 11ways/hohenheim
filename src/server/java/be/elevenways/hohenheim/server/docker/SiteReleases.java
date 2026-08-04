package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.build.BuildArtifacts;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.thread.JobRunner;
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
 * The health-gated zero-downtime release engine of the site tier: create candidate,
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
 * site (role {@code retired}: the instance row with the digest-pinned spec plus its
 * stopped container, whose existence also pins the image against the per-build prune).
 * Reclaim happens at the END of the next successful operation's drain phase: every
 * retired release except the newest is verified-destroyed and the site's build artifacts
 * are pruned down to the serving digest. Site delete destroys all of it (destroyFor).
 */
public final class SiteReleases {

    /** Keys of {@code adjustPaths}-injected checkout paths: per-slot, never source identity. */
    private static final List<String> VOLATILE_SETTINGS =
        List.of("build_context", "working_directory", "script", "root_path");

    private SiteReleases() {
    }

    // -- source identity ------------------------------------------------------

    /**
     * Identity of the SOURCE a release would be produced from: the resolved site
     * settings with slot-dependent absolute paths dropped (commit_sha carries the
     * source identity for git checkouts). Matching fingerprints mean a release would
     * change nothing -- which is what lets an unchanged routing reload skip the
     * sandbox build entirely.
     */
    public static @NonNull String sourceFingerprint(int siteId,
                                                    @NonNull Map<String, Object> settings) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        settings.forEach((key, value) -> {
            if (value != null && !VOLATILE_SETTINGS.contains(key)) {
                canonical.put(key, value);
            }
        });
        StringBuilder text = new StringBuilder("site:").append(siteId);
        appendCanonical(text, canonical);
        return sha256(text.toString());
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
     * Whether the site is PINNED to its rolled-back release: the newest succeeded
     * operation is a rollback and the source has not changed since -- the operator
     * rejected exactly this source, so converging back onto it would silently undo the
     * rollback on the next routing reload. Any source change dissolves the pin.
     */
    public static boolean pinnedByRollback(int siteId, @NonNull String siteFingerprint) {
        // Post-switch phases count: a rollback that has taken traffic but is still
        // draining MUST already pin, or the routing reload the rollback itself
        // triggers would converge straight back onto the rejected spec and undo it.
        Row latest = Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(siteId))
            .where(ReleaseOperationModel.STATUS.in(ReleaseOperationModel.STATUS_SWITCHING,
                ReleaseOperationModel.STATUS_DRAINING, ReleaseOperationModel.STATUS_SUCCEEDED))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
        return latest != null
            && ReleaseOperationModel.KIND_ROLLBACK.equals(latest.get(ReleaseOperationModel.KIND))
            && siteFingerprint.equals(latest.get(ReleaseOperationModel.SITE_FINGERPRINT));
    }

    // -- the operations -------------------------------------------------------

    /**
     * First release of a site that never had one: deploy directly, recorded as a
     * release operation. There is deliberately no probe GATE here -- with nothing
     * serving there is nothing a failed candidate could replace, and refusing would
     * only hide the workload's real state; the step log states this.
     *
     * @throws Violations when the deploy refuses (the site is down either way)
     */
    static SiteInstances.@NonNull SiteRuntime initialRelease(int siteId,
                                                             @Nullable String siteName,
                                                             int serverId,
                                                             @NonNull Map<String, Object> desired,
                                                             @NonNull String siteFingerprint) {
        Row op = newOperation(ReleaseOperationModel.KIND_RELEASE, siteId,
            siteFingerprint, siteFingerprint);
        step(op, "initial release: no prior release to protect, deploying directly");
        try {
            Row instance = newInstanceRow(siteId, siteName, serverId, desired,
                InstanceModel.ROLE_SERVING);
            int instanceId = instance.get(InstanceModel.ID);
            op.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, instanceId);
            transition(op, ReleaseOperationModel.STATUS_DEPLOYING,
                "instance " + instanceId + " created");
            InstanceStatus status = new InstanceService().deploy(instanceId);
            op.set(ReleaseOperationModel.IMAGE_ID, str(desired.get("image")));
            finish(op, ReleaseOperationModel.STATUS_SUCCEEDED, null, "deployed");
            return new SiteInstances.SiteRuntime(instanceId, status);
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
     * change can degrade a site to "stale but up", never to "down". When nothing is
     * serving, the spec is replaced in place (there is nothing to protect).
     */
    static SiteInstances.@NonNull SiteRuntime release(@NonNull DockerClient docker, int siteId,
                                                      @Nullable String siteName, int serverId,
                                                      @NonNull Row serving,
                                                      @NonNull Map<String, Object> sourceSettings,
                                                      @NonNull String siteFingerprint) {
        int servingId = serving.get(InstanceModel.ID);
        InstanceStatus oldLive = new InstanceService().liveStatus(servingId);
        boolean protecting = oldLive.running() && oldLive.publishedPort() != null;

        Row op = newOperation(ReleaseOperationModel.KIND_RELEASE, siteId,
            siteFingerprint, siteFingerprint);
        Map<String, Object> desired;
        try {
            step(op, "resolving the desired spec (build/pull + digest pin)");
            desired = SiteInstances.desiredSettings(docker, siteId, sourceSettings);
            desired.put("source_fingerprint", siteFingerprint);
        } catch (RuntimeException e) {
            finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(e),
                "spec resolution failed");
            if (protecting) {
                Blast.log("RELEASE: site", siteId, "release failed before a candidate"
                    + " existed; the prior release keeps serving -", reasonOf(e));
                return new SiteInstances.SiteRuntime(servingId, oldLive);
            }
            throw e;
        }

        if (specEquals(desired, SiteInstances.storedSettings(serving))
                && serverId == ServerModel.canonicalServerId(
                    serving.get(InstanceModel.SERVER_ID))) {
            // The source fingerprint drifted (legacy row, new derivation input) but the
            // SPEC did not: adopt the fingerprint so the fast lane hits from now on.
            serving.set(InstanceModel.SETTINGS, desired);
            Models.get(InstanceModel.class).save(serving);
            finish(op, ReleaseOperationModel.STATUS_SUCCEEDED, null,
                "spec unchanged; fingerprint adopted without a deploy");
            if (protecting) {
                return new SiteInstances.SiteRuntime(servingId, oldLive);
            }
            return new SiteInstances.SiteRuntime(servingId,
                new InstanceService().deploy(servingId));
        }

        if (!protecting || !(desired.get("container_port") instanceof Number)) {
            // Nothing serving traffic (or a portless workload the proxy cannot probe):
            // replace in place, today's semantics -- the step log says so.
            step(op, "prior release not serving traffic; replacing in place without a probe gate");
            serving.set(InstanceModel.SETTINGS, desired);
            serving.set(InstanceModel.SERVER_ID, serverId);
            Models.get(InstanceModel.class).save(serving);
            transition(op, ReleaseOperationModel.STATUS_DEPLOYING, "deploying in place");
            try {
                InstanceStatus status = new InstanceService().deploy(servingId);
                op.set(ReleaseOperationModel.IMAGE_ID, str(desired.get("image")));
                finish(op, ReleaseOperationModel.STATUS_SUCCEEDED, null, "deployed");
                return new SiteInstances.SiteRuntime(servingId, status);
            } catch (RuntimeException e) {
                finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(e), "deploy failed");
                throw e;
            }
        }

        try {
            return gatedSwap(docker, op, siteId, siteName, serverId, serving, desired);
        } catch (RuntimeException e) {
            // The health gate held: the candidate never took traffic and is gone; the
            // prior release keeps serving. Loud in the log AND durable on the record.
            Blast.log("RELEASE: site", siteId, "candidate refused -", reasonOf(e),
                "- the prior release keeps serving");
            return new SiteInstances.SiteRuntime(servingId, oldLive);
        }
    }

    /**
     * Roll the site back to its RETAINED release: one durable operation over the
     * retired instance's digest-pinned settings. Nothing is rebuilt, cloned or pulled
     * from a tag -- the artifact is addressed by content, so a deleted checkout or a
     * moved tag cannot change what this deploys.
     *
     * @throws Violations when no rollback target exists or the candidate fails its
     *         probe (the current release keeps serving either way)
     */
    public static void rollback(int siteId) {
        SiteInstances.inScopeUnchecked(siteId, () -> {
            Row serving = SiteInstances.ownedServing(siteId);
            Row target = newestRetired(siteId);
            if (target == null) {
                throw Violations.ofForm(Microcopy.of("release_no_rollback_target")
                    .withFilter("scope", "violations"));
            }
            if (serving == null) {
                throw Violations.ofForm(Microcopy.of("release_no_serving_release")
                    .withFilter("scope", "violations"));
            }
            Map<String, Object> desired = SiteInstances.storedSettings(target);
            int serverId = ServerModel.canonicalServerId(target.get(InstanceModel.SERVER_ID));
            String specFingerprint = str(desired.get("source_fingerprint"));
            String siteFingerprint =
                str(SiteInstances.storedSettings(serving).get("source_fingerprint"));

            InstanceStatus oldLive =
                new InstanceService().liveStatus(serving.get(InstanceModel.ID));
            if (!oldLive.running() || oldLive.publishedPort() == null) {
                throw Violations.ofForm(Microcopy.of("release_no_serving_release")
                    .withFilter("scope", "violations"));
            }
            Row op = newOperation(ReleaseOperationModel.KIND_ROLLBACK, siteId,
                siteFingerprint, specFingerprint);
            step(op, "rolling back to retired instance " + target.get(InstanceModel.ID)
                + " (image " + str(desired.get("image")) + ")");
            DockerClient docker = serverId == ServerModel.localServerId()
                ? new DockerClient()
                : new ServerService().clientFor(ServerModel.nameOf(serverId));
            gatedSwap(docker, op, siteId, serving.get(InstanceModel.NAME), serverId,
                serving, desired);
        });
        // The routing tier still proxies the OLD release's port until it rebuilds the
        // site's handler; the drain window keeps that upstream alive through the reload.
        ProxyServer proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }

    /**
     * The gate itself: candidate beside the serving release, probe, atomic switch,
     * drain, retain, reclaim. Throws on ANY failure before the switch, always after
     * destroying the candidate -- a failed candidate never exists as a serving role,
     * never keeps a container, and its port claim dies with it.
     */
    private static SiteInstances.@NonNull SiteRuntime gatedSwap(@NonNull DockerClient docker,
                                                                @NonNull Row op, int siteId,
                                                                @Nullable String siteName,
                                                                int serverId,
                                                                @NonNull Row serving,
                                                                @NonNull Map<String, Object> desired) {
        int servingId = serving.get(InstanceModel.ID);
        Integer candidateId = null;
        try {
            Row candidate = newInstanceRow(siteId, siteName, serverId, desired,
                InstanceModel.ROLE_CANDIDATE);
            candidateId = candidate.get(InstanceModel.ID);
            op.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidateId);
            transition(op, ReleaseOperationModel.STATUS_DEPLOYING,
                "candidate instance " + candidateId + " created beside serving instance "
                    + servingId);
            InstanceStatus candidateStatus = new InstanceService().deploy(candidateId);
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
            op.set(ReleaseOperationModel.RETIRED_INSTANCE_ID, servingId);
            op.set(ReleaseOperationModel.IMAGE_ID, str(desired.get("image")));
            transition(op, ReleaseOperationModel.STATUS_SWITCHING, "switching traffic");
            candidate.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_SERVING);
            Models.get(InstanceModel.class).save(candidate);
            serving.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_RETIRED);
            Models.get(InstanceModel.class).save(serving);
            transition(op, ReleaseOperationModel.STATUS_DRAINING,
                "instance " + candidateId + " now serving; instance " + servingId
                    + " retained as the rollback target, draining");
            scheduleDrain(siteId, op.get(ReleaseOperationModel.ID), servingId,
                str(desired.get("image")), Db.current());
            return new SiteInstances.SiteRuntime(candidateId, candidateStatus);
        } catch (RuntimeException gateHeld) {
            finish(op, ReleaseOperationModel.STATUS_FAILED, reasonOf(gateHeld),
                "candidate refused: " + reasonOf(gateHeld));
            if (candidateId != null) {
                destroyCandidateQuietly(candidateId);
            }
            throw gateHeld;
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
    private static void scheduleDrain(int siteId, int opId, int retiredId,
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
            withScope(datasource, () -> SiteInstances.inScopeUnchecked(siteId, () -> {
                Row op = Models.get(ReleaseOperationModel.class).findById(opId);
                if (op == null || !ReleaseOperationModel.STATUS_DRAINING.equals(
                        op.get(ReleaseOperationModel.STATUS))) {
                    return;
                }
                completeDrain(siteId, op, retiredId, servingImage, "drain window elapsed");
            }));
        });
    }

    /** The shared drain completion: stop retained, reclaim older, prune, succeed. */
    private static void completeDrain(int siteId, @NonNull Row op, int retiredId,
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
            Blast.log("RELEASE: site", siteId, "superseded release stop failed -",
                reasonOf(stopFailed));
        }
        reclaimOlderRetired(siteId, retiredId, op);
        pruneArtifactsQuietly(siteId, servingImage, op);
        finish(op, ReleaseOperationModel.STATUS_SUCCEEDED, null, "release complete");
    }

    /** Destroy every retired release of the site EXCEPT the newest (the one retained). */
    private static void reclaimOlderRetired(int siteId, int keepInstanceId, @NonNull Row op) {
        for (Row stale : ownedWithRole(siteId, InstanceModel.ROLE_RETIRED)) {
            int staleId = stale.get(InstanceModel.ID);
            if (staleId == keepInstanceId) {
                continue;
            }
            try {
                new InstanceService().destroy(staleId);
                step(op, "reclaimed superseded release instance " + staleId);
            } catch (RuntimeException e) {
                step(op, "WARNING: could not reclaim instance " + staleId + ": " + reasonOf(e));
                Blast.log("RELEASE: site", siteId, "could not reclaim instance", staleId,
                    "-", reasonOf(e));
            }
        }
    }

    private static void pruneArtifactsQuietly(int siteId, @NonNull String servingImage,
                                              @NonNull Row op) {
        try {
            BuildArtifacts.pruneSuperseded(new DockerClient(), SiteModel.MODEL_ID.toString(),
                siteId, servingImage);
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
            Integer siteId = op.get(ReleaseOperationModel.FOR_ID);
            if (siteId == null
                    || !SiteModel.MODEL_ID.toString().equals(
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
                SiteInstances.inScopeUnchecked(siteId, () -> recoverOne(siteId, op));
            } catch (RuntimeException e) {
                Blast.log("RELEASE: recovery of operation",
                    op.get(ReleaseOperationModel.ID), "failed -", reasonOf(e));
            }
        }
        sweepOrphanCandidates(answered);
    }

    private static void recoverOne(int siteId, @NonNull Row op) {
        String status = op.get(ReleaseOperationModel.STATUS);
        Integer candidateId = op.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
        Integer retiredId = op.get(ReleaseOperationModel.RETIRED_INSTANCE_ID);

        if (ReleaseOperationModel.STATUS_DRAINING.equals(status)) {
            completeDrain(siteId, op, retiredId != null ? retiredId : -1,
                servingImageOf(siteId), "boot recovery finished the lost drain");
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
                    old.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_RETIRED);
                    Models.get(InstanceModel.class).save(old);
                    step(op, "boot recovery completed the half-flipped switch");
                }
                transition(op, ReleaseOperationModel.STATUS_DRAINING,
                    "boot recovery: switch had completed, draining");
                completeDrain(siteId, op, retiredId, servingImageOf(siteId),
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

    /** Destroy candidate-role site instances no in-flight operation answers for. */
    private static void sweepOrphanCandidates(@NonNull List<Integer> answered) {
        List<Row> candidates = Models.get(InstanceModel.class).find()
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_CANDIDATE))
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(InstanceModel.DELETED_AT.isNull())
            .all();
        for (Row candidate : candidates) {
            int candidateId = candidate.get(InstanceModel.ID);
            if (answered.contains(candidateId)) {
                continue;
            }
            Integer siteId = candidate.get(InstanceModel.GENERATED_FOR_ID);
            Blast.log("RELEASE: destroying orphaned candidate instance", candidateId,
                "of site", siteId);
            SiteInstances.inScopeUnchecked(siteId != null ? siteId : -1,
                () -> destroyCandidateQuietly(candidateId));
        }
    }

    private static @NonNull String servingImageOf(int siteId) {
        Row serving = SiteInstances.ownedServing(siteId);
        return serving != null
            ? str(SiteInstances.storedSettings(serving).get("image")) : "";
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
    private static void probe(int port, @NonNull String path) {
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

    /** A fresh instance row of the site_container kind, in the given role. */
    private static @NonNull Row newInstanceRow(int siteId, @Nullable String siteName,
                                               int serverId,
                                               @NonNull Map<String, Object> desired,
                                               @NonNull String role) {
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME,
            siteName != null && !siteName.isBlank() ? siteName : "site-" + siteId);
        instance.set(InstanceModel.KIND, SiteContainerKind.ID.toString());
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

    static @NonNull List<Row> ownedWithRole(int siteId, @NonNull String role) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SiteModel.MODEL_ID.toString()))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.RUNTIME_ROLE.eq(role))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .all();
    }

    /** The site's newest retained release (the rollback target), or null. */
    public static @Nullable Row newestRetired(int siteId) {
        List<Row> retired = ownedWithRole(siteId, InstanceModel.ROLE_RETIRED);
        return retired.isEmpty() ? null : retired.get(0);
    }

    // -- operation record plumbing -------------------------------------------

    private static @NonNull Row newOperation(@NonNull String kind, int siteId,
                                             @NonNull String siteFingerprint,
                                             @NonNull String specFingerprint) {
        ReleaseOperationModel model = Models.get(ReleaseOperationModel.class);
        Row op = model.createEmptyRow();
        op.set(ReleaseOperationModel.KIND, kind);
        op.set(ReleaseOperationModel.FOR_MODEL, SiteModel.MODEL_ID.toString());
        op.set(ReleaseOperationModel.FOR_ID, siteId);
        op.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_PENDING);
        op.set(ReleaseOperationModel.SITE_FINGERPRINT, siteFingerprint);
        op.set(ReleaseOperationModel.SPEC_FINGERPRINT, specFingerprint);
        op.set(ReleaseOperationModel.STARTED_AT, Instant.now());
        op.set(ReleaseOperationModel.STEP_LOG, "");
        model.save(op);
        return op;
    }

    /** Append a timestamped step line and persist the row. */
    private static void step(@NonNull Row op, @NonNull String line) {
        String existing = op.get(ReleaseOperationModel.STEP_LOG);
        op.set(ReleaseOperationModel.STEP_LOG,
            (existing == null ? "" : existing) + Instant.now() + " " + line + "\n");
        Models.get(ReleaseOperationModel.class).save(op);
    }

    private static void transition(@NonNull Row op, @NonNull String status,
                                   @NonNull String line) {
        op.set(ReleaseOperationModel.STATUS, status);
        step(op, line);
    }

    private static void finish(@NonNull Row op, @NonNull String status,
                               @Nullable String failureReason, @NonNull String line) {
        Instant finished = Instant.now();
        op.set(ReleaseOperationModel.STATUS, status);
        op.set(ReleaseOperationModel.FAILURE_REASON, failureReason);
        op.set(ReleaseOperationModel.FINISHED_AT, finished);
        Instant started = op.get(ReleaseOperationModel.STARTED_AT);
        if (started != null) {
            op.set(ReleaseOperationModel.DURATION_MS,
                (int) (finished.toEpochMilli() - started.toEpochMilli()));
        }
        step(op, line);
        prune(op);
    }

    /** Keep the newest N operations per owning record; older rows go. */
    private static void prune(@NonNull Row op) {
        Integer keep = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.HISTORY_PER_SITE);
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
        return SiteInstances.settingsEqual(a, b);
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
