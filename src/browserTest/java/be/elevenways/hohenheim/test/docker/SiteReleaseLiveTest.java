package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.docker.SiteReleases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The health-gated zero-downtime release engine against a REAL daemon and (for the
 * traffic tests) a REAL ProxyServer: continuous HTTP requests ride through an actual
 * routing-generation swap, so "no failed request" and "a failed candidate never
 * receives traffic" are asserted as TRAFFIC, not as status fields. Skipped when the
 * daemon socket or the alpine base image is absent.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class SiteReleaseLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String SITE_MODEL = SiteModel.MODEL_ID.toString();

    private static boolean booted;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;

    // AIDEV-NOTE: the netns fixture is what lets the release engine deploy at all on a
    // developer machine. Since the isolation wave every site release container is
    // NetworkPosture.PRIVATE and its deploy/destroy REFUSES where the network policy
    // cannot be enforced; that refusal is correct product behaviour and stays -- the
    // fixture points the PRODUCTION applier at a real nftables in a private namespace
    // instead of weakening anything (same pattern as DockerSiteHandlerTest).
    private static PrivateNetns netns;

    @BeforeAll
    static void boot() throws Exception {
        if (!booted) {
            booted = true;
            ProxyTestSupport.bootRuntime();
        }
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
        savedProbeTimeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 6);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 100);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 2);
    }

    @AfterAll
    static void restoreSettings() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
    }

    /**
     * THE zero-downtime journey through the proxy: serve, swap under continuous
     * traffic without one failed request, retain the superseded release, roll back to
     * it, hold the rollback pin across a reload, reclaim, and dissolve the pin.
     */
    @Test
    void healthySwapDropsNoRequestRetainsRollsBackAndReclaims() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");

        String repoA = "hohenheim-rel-a-" + System.nanoTime();
        String repoB = "hohenheim-rel-b-" + System.nanoTime();
        String digestA = TestImages.loadHttpServer(docker, repoA + ":latest", "release-one");
        String digestB = TestImages.loadHttpServer(docker, repoB + ":latest", "release-two");

        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Release journey site",
            "release-journey", settingsFor(repoA));
        ProxyTestSupport.addDomain(site, "release.test", "exact", null, false);
        int siteId = site.get(SiteModel.ID);

        ProxyServer proxy = ProxyTestSupport.startProxy();
        Hammer hammer = null;
        try {
            int port = ProxyTestSupport.httpPort(proxy);

            // 1. The initial release serves through the proxy.
            String first = ProxyTestSupport.rawRequest(port, "release.test", "/");
            assertThat(first).as("step 1: the initial release answers through the proxy")
                .contains("200").contains("release-one");
            Row serving1 = servingOf(siteId);
            assertThat(serving1).as("step 1: a serving-role instance exists").isNotNull();
            int firstInstanceId = serving1.get(InstanceModel.ID);
            assertThat(settingsOf(serving1).get("image"))
                .as("step 1: the release is pinned to the image digest, not the tag")
                .isEqualTo(digestA);

            // 2. Swap to release B under CONTINUOUS traffic.
            Hammer swapHammer = new Hammer(port, "release.test", 3);
            hammer = swapHammer;
            site.set(SiteModel.SETTINGS, settingsFor(repoB));
            Models.get(SiteModel.class).save(site);
            proxy.reload();
            // Bounded wait instead of a fixed sleep: under parallel forks a starved
            // lane can legitimately still be on release-one after 400ms, which is a
            // scheduling artifact, not a routing regression. The zero-failed-requests
            // and never-regress assertions below are untouched -- this only decides
            // WHEN to stop hammering.
            await("step 2: every lane observed the new release before the hammer stops",
                30_000, () -> swapHammer.everyLaneEndsOn("release-two"));
            hammer.close();

            assertThat(hammer.failures)
                .as("step 2: not one request failed across the swap: %s", hammer.failures)
                .isEmpty();
            for (List<String> lane : hammer.lanes()) {
                assertThat(lane).as("step 2: every response is a COMPLETE release")
                    .allMatch(body -> body.equals("release-one") || body.equals("release-two"));
                int firstNew = lane.indexOf("release-two");
                if (firstNew >= 0) {
                    assertThat(lane.subList(firstNew, lane.size()))
                        .as("step 2: once a lane saw the new release it never regressed")
                        .allMatch("release-two"::equals);
                }
                assertThat(lane).as("step 2: the lane ends on the new release")
                    .isNotEmpty();
                assertThat(lane.get(lane.size() - 1)).isEqualTo("release-two");
            }
            assertThat(hammer.lanes().stream().anyMatch(l -> l.contains("release-one")))
                .as("step 2: the hammer really did observe the old release too").isTrue();

            // 3. The superseded release is RETAINED as the rollback target: role
            //    retired, container kept, stopped once the drain window passed.
            Row retired = onlyWithRole(siteId, InstanceModel.ROLE_RETIRED);
            assertThat(retired.get(InstanceModel.ID))
                .as("step 3: the retired release is the previously-serving instance")
                .isEqualTo(firstInstanceId);
            String retiredHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, firstInstanceId);
            await("step 3: the retired container stops after the drain window",
                12_000, () -> !isRunning(docker, retiredHandle));
            assertThat(inspectImageOf(docker, retiredHandle))
                .as("step 3: the retained container still runs the OLD digest")
                .isEqualTo(digestA);
            Row swapOp = latestOp(siteId);
            await("step 3: the release operation completes after drain",
                12_000, () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                    reload(swapOp).get(ReleaseOperationModel.STATUS)));
            String steps = reload(swapOp).get(ReleaseOperationModel.STEP_LOG);
            assertThat(steps).as("step 3: every phase is visible on the durable record")
                .contains("candidate instance").contains("probing")
                .contains("health probe passed").contains("switching traffic")
                .contains("retained as the rollback target").contains("release complete");

            // 4. Roll back: one durable operation over the retained digest-pinned spec.
            SiteReleases.rollback(siteId);
            proxy.reload();
            String rolledBack = ProxyTestSupport.rawRequest(port, "release.test", "/");
            assertThat(rolledBack)
                .as("step 4: after the rollback the proxy serves the PRIOR release")
                .contains("200").contains("release-one");
            Row servingBack = servingOf(siteId);
            assertThat(settingsOf(servingBack).get("image"))
                .as("step 4: the rolled-back release runs the pinned OLD digest")
                .isEqualTo(digestA);
            Row rollbackOp = latestOp(siteId);
            assertThat(rollbackOp.get(ReleaseOperationModel.KIND))
                .as("step 4: the rollback is its own recorded operation")
                .isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);

            // 5. RECLAIM: the rollback superseded the v2 release, which becomes the new
            //    rollback target; the ORIGINAL v1 instance (older retired) is destroyed
            //    at the daemon -- retention is exactly one release deep.
            await("step 5: the rollback operation completes after drain",
                12_000, () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                    reload(rollbackOp).get(ReleaseOperationModel.STATUS)));
            Row original = Models.get(InstanceModel.class).findById(firstInstanceId);
            assertThat((Object) original.get(InstanceModel.DELETED_AT))
                .as("step 5: the older retired release was reclaimed (record)").isNotNull();
            assertThat(containerExists(docker, retiredHandle))
                .as("step 5: the older retired release was reclaimed (daemon)").isFalse();
            Row newTarget = onlyWithRole(siteId, InstanceModel.ROLE_RETIRED);
            assertThat(settingsOf(newTarget).get("image"))
                .as("step 5: the v2 release is the NEW rollback target")
                .isEqualTo(digestB);

            // 6. The rollback PINS: a routing reload with unchanged site settings must
            //    not converge back onto the rejected spec.
            int opsBefore = opCount(siteId);
            proxy.reload();
            assertThat(ProxyTestSupport.rawRequest(port, "release.test", "/"))
                .as("step 6: a reload does not undo the rollback")
                .contains("release-one");
            assertThat(opCount(siteId))
                .as("step 6: the pinned reload released nothing").isEqualTo(opsBefore);

            // 7. A SOURCE CHANGE dissolves the pin: the operator changed something, so
            //    the declared spec wins again through a fresh gated release.
            Map<String, Object> changed = settingsFor(repoB);
            changed.put("health_path", "/index.html");
            site.set(SiteModel.SETTINGS, changed);
            Models.get(SiteModel.class).save(site);
            proxy.reload();
            assertThat(ProxyTestSupport.rawRequest(port, "release.test", "/"))
                .as("step 7: a changed source dissolves the pin and releases forward")
                .contains("release-two");
        } finally {
            if (hammer != null) {
                hammer.close();
            }
            proxy.stop();
            cleanupSite(site);
            removeQuietly(docker, repoA + ":latest");
            removeQuietly(docker, repoB + ":latest");
            removeQuietly(docker, digestA);
            removeQuietly(docker, digestB);
        }
    }

    /**
     * THE health-gate counterfactual as TRAFFIC: a candidate that ANSWERS (500) but is
     * unhealthy never receives one proxied request -- every response across the whole
     * failed release came from the prior release -- and the candidate is destroyed.
     */
    @Test
    void failedCandidateNeverReceivesProductionTraffic() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");

        String repoGood = "hohenheim-rel-good-" + System.nanoTime();
        String repoEvil = "hohenheim-rel-evil-" + System.nanoTime();
        String digestGood = TestImages.loadHttpServer(docker, repoGood + ":latest", "keep-serving");
        // An ANSWERING but unhealthy candidate: if the gate ever let it serve, its body
        // would be visible in the hammer log -- that is the traffic proof.
        String digestEvil = TestImages.loadResponder(docker, repoEvil + ":latest",
            "HTTP/1.1 500 Broken", "evil-answer");

        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Gate site",
            "release-gate", settingsFor(repoGood));
        ProxyTestSupport.addDomain(site, "gate.test", "exact", null, false);
        int siteId = site.get(SiteModel.ID);

        ProxyServer proxy = ProxyTestSupport.startProxy();
        Hammer hammer = null;
        try {
            int port = ProxyTestSupport.httpPort(proxy);
            assertThat(ProxyTestSupport.rawRequest(port, "gate.test", "/"))
                .as("step 1: the good release serves").contains("200").contains("keep-serving");
            Row serving = servingOf(siteId);
            int servingId = serving.get(InstanceModel.ID);

            // 2. Release the unhealthy candidate under continuous traffic.
            hammer = new Hammer(port, "gate.test", 3);
            site.set(SiteModel.SETTINGS, settingsFor(repoEvil));
            Models.get(SiteModel.class).save(site);
            proxy.reload();
            Thread.sleep(400);
            hammer.close();

            // 3. NOT ONE request reached the candidate, and not one failed: the prior
            //    release answered every single request throughout the failed release.
            assertThat(hammer.failures)
                .as("step 3: no request failed while the gate held: %s", hammer.failures)
                .isEmpty();
            for (List<String> lane : hammer.lanes()) {
                assertThat(lane)
                    .as("step 3: every response of the whole window is the PRIOR release")
                    .allMatch("keep-serving"::equals);
                assertThat(lane).isNotEmpty();
            }

            // 4. The operation records the refusal durably.
            Row op = latestOp(siteId);
            assertThat(op.get(ReleaseOperationModel.STATUS))
                .as("step 4: the operation is FAILED, never silently forgotten")
                .isEqualTo(ReleaseOperationModel.STATUS_FAILED);
            assertThat((String) op.get(ReleaseOperationModel.FAILURE_REASON))
                .as("step 4: the failure names the probe")
                .contains("release_probe_failed");

            // 5. The candidate is GONE: record soft-deleted, container removed, port
            //    claim released; the serving release is untouched.
            Integer candidateId = op.get(ReleaseOperationModel.CANDIDATE_INSTANCE_ID);
            assertThat(candidateId).as("step 5: the candidate was recorded").isNotNull();
            Row candidate = Models.get(InstanceModel.class).findById(candidateId);
            assertThat((Object) candidate.get(InstanceModel.DELETED_AT))
                .as("step 5: the refused candidate's record is soft-deleted").isNotNull();
            assertThat(containerExists(docker, ControllerScope.handle(ControllerScope.KIND_INSTANCE, candidateId)))
                .as("step 5: the refused candidate's container is gone").isFalse();
            assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, candidateId))
                .as("step 5: the refused candidate holds no port claim").isEmpty();
            Row still = servingOf(siteId);
            assertThat(still.get(InstanceModel.ID))
                .as("step 5: the serving release is the SAME instance").isEqualTo(servingId);
            assertThat(settingsOf(still).get("image"))
                .as("step 5: still pinned to the good digest").isEqualTo(digestGood);
            assertThat(isRunning(docker, ControllerScope.handle(ControllerScope.KIND_INSTANCE, servingId)))
                .as("step 5: and still running at the daemon").isTrue();
        } finally {
            if (hammer != null) {
                hammer.close();
            }
            proxy.stop();
            cleanupSite(site);
            removeQuietly(docker, repoGood + ":latest");
            removeQuietly(docker, repoEvil + ":latest");
            removeQuietly(docker, digestGood);
            removeQuietly(docker, digestEvil);
        }
    }

    /**
     * THE rollback counterfactual: the tag has MOVED (twice) and is then DELETED, there
     * never was any source -- and the rollback still runs the prior artifact, addressed
     * purely by its content digest.
     */
    @Test
    void rollbackNeedsNoSourceAndSurvivesAMovedAndDeletedTag() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");

        int siteId = 955_101;
        String repo = "hohenheim-rel-mv-" + System.nanoTime();
        String tagRef = repo + ":latest";
        String digest1 = TestImages.loadHttpServer(docker, tagRef, "moved-one");
        String decoy = null;
        String digest2 = null;
        try {
            // 1. Release v1 from the tag; the instance pins the digest behind it.
            Map<String, Object> settings = settingsFor(repo);
            DockerSiteRequestHandler first =
                new DockerSiteRequestHandler(siteId, "mv-site", settings);
            assertThat(first.getUpstream()).as("step 1: v1 serves").isNotNull();
            assertThat(get(first.getUpstream())).contains("moved-one");
            Row serving1 = servingOf(siteId);
            assertThat(settingsOf(serving1).get("image"))
                .as("step 1: pinned to the digest the tag pointed at").isEqualTo(digest1);

            // 2. The tag MOVES to v2; a source change releases v2 through the gate.
            digest2 = TestImages.loadHttpServer(docker, tagRef, "moved-two");
            Map<String, Object> changed = settingsFor(repo);
            changed.put("health_path", "/index.html");
            DockerSiteRequestHandler second =
                new DockerSiteRequestHandler(siteId, "mv-site", changed);
            assertThat(get(second.getUpstream())).as("step 2: v2 serves").contains("moved-two");
            assertThat(settingsOf(servingOf(siteId)).get("image")).isEqualTo(digest2);
            Row retired = onlyWithRole(siteId, InstanceModel.ROLE_RETIRED);
            assertThat(retired.get(InstanceModel.ID))
                .isEqualTo(serving1.get(InstanceModel.ID));
            Row swapOp = latestOp(siteId);
            await("step 2: the swap completes after drain", 12_000,
                () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                    reload(swapOp).get(ReleaseOperationModel.STATUS)));

            // 3. Move the tag onto an UNRELATED decoy, then delete it outright. Any
            //    rollback that consulted the tag would now deploy the decoy or die.
            decoy = TestImages.load(docker, tagRef, "decoy");
            docker.removeImage(tagRef, false);
            assertThat(imagePresent(docker, tagRef))
                .as("step 3: the tag is GONE").isFalse();

            // 4. Roll back: the retained spec deploys BY DIGEST -- no tag, no source.
            SiteReleases.rollback(siteId);
            Row servingBack = servingOf(siteId);
            assertThat(settingsOf(servingBack).get("image"))
                .as("step 4: the rolled-back release is the pinned v1 digest")
                .isEqualTo(digest1);
            int backId = servingBack.get(InstanceModel.ID);
            String backHandle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, backId);
            assertThat(inspectImageOf(docker, backHandle))
                .as("step 4: the DAEMON runs exactly the pinned artifact")
                .isEqualTo(digest1);
            int backPort = docker.publishedPort(backHandle, 8080);
            assertThat(get(URI.create("http://127.0.0.1:" + backPort + "/")))
                .as("step 4: and it serves the v1 content").contains("moved-one");
            Row rollbackOp = latestOp(siteId);
            assertThat(rollbackOp.get(ReleaseOperationModel.KIND))
                .isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);
            assertThat((String) rollbackOp.get(ReleaseOperationModel.IMAGE_ID))
                .as("step 4: the operation pins the artifact it released")
                .isEqualTo(digest1);
        } finally {
            SiteInstances.destroyFor(siteId);
            removeQuietly(docker, tagRef);
            removeQuietly(docker, digest1);
            if (digest2 != null) {
                removeQuietly(docker, digest2);
            }
            if (decoy != null) {
                removeQuietly(docker, decoy);
            }
        }
    }

    /**
     * Boot recovery settles what a controller crash left behind: a lost drain is
     * finished (stop + succeed) and a pre-switch operation loses its candidate and
     * stamps interrupted -- asserted at the daemon, not just on the record.
     */
    @Test
    void interruptedOperationsAreSettledByRecovery() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");

        int siteId = 955_201;
        String repo1 = "hohenheim-rel-rc1-" + System.nanoTime();
        String repo2 = "hohenheim-rel-rc2-" + System.nanoTime();
        String digest1 = TestImages.loadHttpServer(docker, repo1 + ":latest", "rec-one");
        String digest2 = TestImages.loadHttpServer(docker, repo2 + ":latest", "rec-two");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 600);
        try {
            // 1. A real release whose drain will NOT run for 600s -- the lost-drain shape.
            new DockerSiteRequestHandler(siteId, "rc-site", settingsFor(repo1));
            new DockerSiteRequestHandler(siteId, "rc-site", settingsFor(repo2));
            Row retired = onlyWithRole(siteId, InstanceModel.ROLE_RETIRED);
            int retiredId = retired.get(InstanceModel.ID);
            assertThat(isRunning(docker, ControllerScope.handle(ControllerScope.KIND_INSTANCE, retiredId)))
                .as("step 1: the superseded release is still draining (running)").isTrue();
            Row op = latestOp(siteId);
            assertThat(op.get(ReleaseOperationModel.STATUS))
                .isEqualTo(ReleaseOperationModel.STATUS_DRAINING);

            // 2. A fabricated pre-switch operation with a live candidate (the shape a
            //    crash during probing leaves).
            int[] candidateId = new int[1];
            GeneratedRows.as(new GeneratedRows.Attribution(SiteInstances.SOURCE,
                SITE_MODEL, siteId), () -> {
                Row candidate = Models.get(InstanceModel.class).createEmptyRow();
                candidate.set(InstanceModel.NAME, "rc-candidate");
                candidate.set(InstanceModel.KIND, "hohenheim:release");
                candidate.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of(
                    "image", "alpine:latest", "command", "sleep 300")));
                candidate.set(InstanceModel.RUNTIME_ROLE, InstanceModel.ROLE_CANDIDATE);
                Models.get(InstanceModel.class).save(candidate);
                candidateId[0] = candidate.get(InstanceModel.ID);
                new InstanceService().deploy(candidateId[0]);
            });
            Row staleOp = Models.get(ReleaseOperationModel.class).createEmptyRow();
            staleOp.set(ReleaseOperationModel.KIND, ReleaseOperationModel.KIND_RELEASE);
            staleOp.set(ReleaseOperationModel.FOR_MODEL, SITE_MODEL);
            staleOp.set(ReleaseOperationModel.FOR_ID, siteId);
            staleOp.set(ReleaseOperationModel.STATUS, ReleaseOperationModel.STATUS_PROBING);
            staleOp.set(ReleaseOperationModel.CANDIDATE_INSTANCE_ID, candidateId[0]);
            staleOp.set(ReleaseOperationModel.STARTED_AT, Instant.now());
            Models.get(ReleaseOperationModel.class).save(staleOp);

            // 3. Recovery settles BOTH: drain finished, candidate destroyed.
            SiteReleases.recoverInterrupted();

            assertThat(isRunning(docker, ControllerScope.handle(ControllerScope.KIND_INSTANCE, retiredId)))
                .as("step 3: recovery stopped the draining release at the daemon").isFalse();
            assertThat(reload(op).get(ReleaseOperationModel.STATUS))
                .as("step 3: the lost drain finishes as SUCCEEDED (the switch had happened)")
                .isEqualTo(ReleaseOperationModel.STATUS_SUCCEEDED);
            assertThat((String) reload(op).get(ReleaseOperationModel.STEP_LOG))
                .contains("boot recovery finished the lost drain");

            assertThat(reload(staleOp).get(ReleaseOperationModel.STATUS))
                .as("step 3: the pre-switch operation is INTERRUPTED, visibly")
                .isEqualTo(ReleaseOperationModel.STATUS_INTERRUPTED);
            Row deadCandidate = Models.get(InstanceModel.class).findById(candidateId[0]);
            assertThat((Object) deadCandidate.get(InstanceModel.DELETED_AT))
                .as("step 3: the orphaned candidate's record died").isNotNull();
            assertThat(containerExists(docker, ControllerScope.handle(ControllerScope.KIND_INSTANCE, candidateId[0])))
                .as("step 3: and its container is gone at the daemon").isFalse();
            assertThat(servingOf(siteId).get(InstanceModel.SETTINGS)).isNotNull();
            assertThat(settingsOf(servingOf(siteId)).get("image"))
                .as("step 3: the serving release was never touched").isEqualTo(digest2);
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 2);
            SiteInstances.destroyFor(siteId);
            removeQuietly(docker, repo1 + ":latest");
            removeQuietly(docker, repo2 + ":latest");
            removeQuietly(docker, digest1);
            removeQuietly(docker, digest2);
        }
    }

    // -- fixtures and helpers -------------------------------------------------

    private static Map<String, Object> settingsFor(String repo) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", repo);
        settings.put("tag", "latest");
        settings.put("container_port", 8080);
        return settings;
    }

    private static void cleanupSite(Row site) {
        try {
            SiteInstances.destroyFor(site.get(SiteModel.ID));
        } catch (RuntimeException ignored) {
            // teardown best effort; the assertions are the outcome
        }
        site.set(SiteModel.DELETED_AT, Instant.now());
        site.set(SiteModel.ENABLED, false);
        Models.get(SiteModel.class).save(site);
    }

    private static Row servingOf(int siteId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SITE_MODEL))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.RUNTIME_ROLE.eq(InstanceModel.ROLE_SERVING))
            .where(InstanceModel.DELETED_AT.isNull())
            .orderBy(InstanceModel.ID, SortOrder.DESC)
            .first();
    }

    private static Row onlyWithRole(int siteId, String role) {
        List<Row> rows = Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SITE_MODEL))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.RUNTIME_ROLE.eq(role))
            .where(InstanceModel.DELETED_AT.isNull())
            .all();
        assertThat(rows).as("exactly one %s-role release of site %s", role, siteId).hasSize(1);
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settingsOf(Row instance) {
        return (Map<String, Object>) instance.get(InstanceModel.SETTINGS);
    }

    private static Row latestOp(int siteId) {
        List<Row> ops = Models.get(ReleaseOperationModel.class)
            .findForOwner(SITE_MODEL, siteId, 1);
        assertThat(ops).as("a release operation exists for site %s", siteId).isNotEmpty();
        return ops.get(0);
    }

    private static int opCount(int siteId) {
        return Models.get(ReleaseOperationModel.class)
            .findForOwner(SITE_MODEL, siteId, 1000).size();
    }

    private static Row reload(Row op) {
        return Models.get(ReleaseOperationModel.class)
            .findById(op.get(ReleaseOperationModel.ID));
    }

    private static boolean imagePresent(DockerClient docker, String reference)
            throws IOException {
        if (reference.startsWith("sha256:")) {
            for (Object image : docker.listImages()) {
                if (reference.equals(((Map<?, ?>) image).get("Id"))) {
                    return true;
                }
            }
            return false;
        }
        return TestImages.idOf(docker, reference) != null;
    }

    private static boolean containerExists(DockerClient docker, String handle) {
        try {
            docker.inspectContainer(handle);
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    private static boolean isRunning(DockerClient docker, String handle) {
        try {
            Map<String, Object> inspect = docker.inspectContainer(handle);
            return inspect.get("State") instanceof Map<?, ?> state
                && Boolean.TRUE.equals(state.get("Running"));
        } catch (IOException gone) {
            return false;
        }
    }

    private static String inspectImageOf(DockerClient docker, String handle)
            throws IOException {
        return String.valueOf(docker.inspectContainer(handle).get("Image"));
    }

    private static String get(URI target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body();
    }

    private static void await(String what, long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private static void removeQuietly(DockerClient docker, String reference) {
        try {
            docker.removeImage(reference, true);
        } catch (IOException ignored) {
            // already gone or still referenced by a test leftover; reclaim covers it
        }
    }

    /**
     * Continuous multi-lane HTTP traffic through the proxy: each lane is one thread
     * issuing sequential requests, recording the release marker each response carried.
     * A failed request (socket error, non-200, unrecognizable body) is a FAILURE.
     */
    private static final class Hammer implements AutoCloseable {

        private final List<Thread> threads = new ArrayList<>();
        private final List<List<String>> laneBodies = new ArrayList<>();
        final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean stop;

        Hammer(int port, String host, int lanes) {
            for (int lane = 0; lane < lanes; lane++) {
                List<String> bodies = Collections.synchronizedList(new ArrayList<>());
                this.laneBodies.add(bodies);
                Thread thread = new Thread(() -> {
                    while (!this.stop) {
                        try {
                            String response = ProxyTestSupport.rawRequest(port, host, "/");
                            String marker = markerOf(response);
                            if (marker == null) {
                                this.failures.add("unrecognized response: "
                                    + response.lines().findFirst().orElse("<empty>"));
                            } else {
                                bodies.add(marker);
                            }
                            Thread.sleep(10);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Exception e) {
                            this.failures.add("request failed: " + e.getMessage());
                        }
                    }
                }, "release-hammer-" + lane);
                thread.setDaemon(true);
                thread.start();
                this.threads.add(thread);
            }
        }

        private static String markerOf(String response) {
            if (!response.startsWith("HTTP/1.1 200")) {
                return null;
            }
            for (String marker : new String[] {"release-one", "release-two",
                    "keep-serving", "evil-answer"}) {
                if (response.contains(marker)) {
                    return marker;
                }
            }
            return null;
        }

        List<List<String>> lanes() {
            return this.laneBodies;
        }

        /** Whether every lane has observed at least one response and currently ends on {@code marker}. */
        boolean everyLaneEndsOn(String marker) {
            for (List<String> lane : this.laneBodies) {
                synchronized (lane) {
                    if (lane.isEmpty() || !marker.equals(lane.get(lane.size() - 1))) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public void close() throws InterruptedException {
            this.stop = true;
            for (Thread thread : this.threads) {
                thread.join(5000);
            }
        }
    }
}
