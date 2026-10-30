package be.elevenways.hohenheim.test.application;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.ArtifactSourceModel;
import be.elevenways.hohenheim.model.OperationStatus;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.testsupport.time.Poll;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.server.application.ApplicationDeploys;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.model.DeployTrigger;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.application.InstanceUpstreamHandler;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.application.SpecSnapshots;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.Accountability;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.PrincipalRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application tier end to end, over {@link FakeDockerDaemon}: an operator authors ONE
 * record, each deploy generates a release, a site serves whichever release is serving, a
 * second deploy flips traffic and retires the old one, and a rollback flips back.
 *
 * This is the journey the re-keying exists for. {@code ApplicationReleaseContractTest}
 * proves the engine's STATE MACHINE (probe outcomes, drain, retention, boot recovery);
 * this proves the intended SHAPE -- that the thing an operator edits
 * and the thing that runs are two records, and that a site is a hostname pointing at the
 * first of them.
 *
 * WHAT THIS CANNOT PROVE, and what the live lane remains the only proof of: real bytes
 * in a real volume. The daemon here is a fake, so a bind mount is a mount SPEC and nothing
 * writes through it. What is asserted below is the property that makes survival possible --
 * that every release of an application mounts the SAME host directory, derived from the
 * APPLICATION and never from the release -- which is exactly the defect the deleted
 * site-keyed volumes were introduced to fix. {@code BtrfsVolumeLiveTest} proves the
 * filesystem half, and {@code ApplicationReleaseLiveTest} the traffic half.
 */
class ApplicationReleaseTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;
    private static String savedDataPath;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        daemon = new FakeDockerDaemon();
        daemon.install();
        savedProbeTimeout = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        savedDataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/hoh-test");
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        Zenit.SETTINGS_VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        Zenit.SETTINGS_VALUES.setValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
    }

    /**
     * Author an application, deploy it, expose it through a site, deploy again, roll back.
     */
    @Test
    void anApplicationDeploysFlipsAndRollsBackWhileItsSiteKeepsAnswering() {
        Db.run(datasource, () -> {
            int applicationId = application("shop", "v1");
            int siteId = siteExposing(applicationId, "shop");
            try {
                // 1. The application is NOT a container. Deploying it generates one.
                assertThat(ApplicationReleases.ownedServing(applicationId))
                    .as("step 1: an authored application has no release until it deploys")
                    .isNull();
                ApplicationReleases.Release first =
                    ApplicationReleases.converge(applicationId, Map.of());
                Row serving = ApplicationReleases.ownedServing(applicationId);
                assertThat(serving)
                    .as("step 1: the deploy generated a serving release").isNotNull();
                int firstId = serving.get(InstanceModel.ID);
                assertThat(firstId)
                    .as("step 1: which is a DIFFERENT record from the application")
                    .isNotEqualTo(applicationId);
                assertThat(serving.get(InstanceModel.KIND))
                    .as("step 1: of the release kind")
                    .isEqualTo(ReleaseKind.ID.toString());
                assertThat(serving.get(InstanceModel.GENERATED_FOR_MODEL))
                    .as("step 1: generated FOR the application, not for a site")
                    .isEqualTo(InstanceModel.MODEL_ID.toString());
                assertThat((Object) serving.get(InstanceModel.GENERATED_FOR_ID))
                    .as("step 1: and attributed to this one")
                    .isEqualTo(applicationId);
                assertThat(daemon.isRunning(FakeDockerDaemon.handleOf(firstId)))
                    .as("step 1: the release container really runs at the daemon").isTrue();

                // 2. READINESS: the release published a loopback port and the health gate
                //    round-tripped against it (the fake really listens).
                assertThat(first.status().publishedPort())
                    .as("step 2: the release published a port to be probed on").isNotNull();
                assertThat(latestOp(applicationId).get(ReleaseOperationModel.STATUS))
                    .as("step 2: the deploy is a recorded, succeeded release operation")
                    .isEqualTo(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));

                // 3. THE SITE ANSWERS THROUGH IT: the instance upstream resolves the
                //    serving release's published port off the ledger. The handler
                //    deliberately converges nothing -- routing only resolves.
                InstanceUpstreamHandler handler =
                    new InstanceUpstreamHandler(siteId, applicationId);
                URI upstream = handler.current();
                assertThat(upstream)
                    .as("step 3: the site resolves an upstream").isNotNull();
                assertThat(upstream.getPort())
                    .as("step 3: and it is the serving release's published port")
                    .isEqualTo(first.status().publishedPort());
                assertThat(upstream.getHost())
                    .as("step 3: on loopback, where the instance tier publishes")
                    .isEqualTo("127.0.0.1");

                // 4. A SECOND DEPLOY flips: a new release serves, the old one is retired
                //    and kept as the rollback target, and the site follows WITHOUT anyone
                //    rebuilding the route table.
                setSettings(applicationId, "v2");
                ApplicationReleases.Release second =
                    ApplicationReleases.converge(applicationId, Map.of());
                int secondId = ApplicationReleases.ownedServing(applicationId)
                    .get(InstanceModel.ID);
                assertThat(secondId)
                    .as("step 4: the flip produced a NEW serving release")
                    .isNotEqualTo(firstId);
                assertThat(Models.get(InstanceModel.class).findById(firstId)
                        .get(InstanceModel.RUNTIME_ROLE))
                    .as("step 4: and retired the previous one")
                    .isEqualTo(InstanceModel.ROLE_RETIRED);
                URI afterFlip = handler.current();
                assertThat(afterFlip.getPort())
                    .as("step 4: the SAME handler now resolves the new release's port --"
                        + " a cached address would still be forwarding to the container"
                        + " the drain is about to stop")
                    .isEqualTo(second.status().publishedPort());
                assertThat(afterFlip.getPort())
                    .as("step 4: which is not the retired release's port")
                    .isNotEqualTo(upstream.getPort());

                await("step 4: the flip's operation completes after its drain window"
                    + " and its drain lets go of the application", () -> ReleaseSettling.settled(applicationId));

                // 5. ROLLBACK flips back onto the RETAINED release, and the site follows
                //    again. Nothing is rebuilt: the retained spec is digest-pinned.
                int pullsBefore = daemon.callCount("api:POST /images/create");
                // Read BEFORE the rollback: its drain (window 0 here) reclaims this older
                // retired release on another thread, so reading it afterwards races that.
                Object retainedFingerprint = ApplicationReleases.storedSettings(
                    Models.get(InstanceModel.class).findById(firstId)).get("source_fingerprint");
                ReleaseEngine.rollback(applicationId, DeployTrigger.MANUAL);
                Row back = ApplicationReleases.ownedServing(applicationId);
                assertThat(ApplicationReleases.storedSettings(back).get("image"))
                    .as("step 5: the rolled-back release runs the pinned v1 digest")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));
                assertThat(daemon.callCount("api:POST /images/create"))
                    .as("step 5: and pulled nothing -- no tag, no source, no registry")
                    .isEqualTo(pullsBefore);
                // AIDEV-NOTE: a rollback mints a NEW release row from the RETAINED SPEC,
                // it does not re-promote the retained row. That is deliberate: the gated
                // swap needs a candidate to probe beside the serving one, and re-promoting
                // a stopped container would take traffic before anything answered. The
                // digest above is the identity that matters, not the row id.
                assertThat((Object) back.get(InstanceModel.ID))
                    .as("step 5: the rollback ran a FRESH candidate, not the retained row")
                    .isNotEqualTo(firstId)
                    .isNotEqualTo(secondId);
                assertThat(ApplicationReleases.storedSettings(back).get("source_fingerprint"))
                    .as("step 5: carrying the retained release's own source identity")
                    .isEqualTo(retainedFingerprint);
                assertThat(handler.current())
                    .as("step 5: and the site follows it, re-resolved off the same"
                        + " generation the rollback bumped")
                    .isEqualTo(ApplicationUpstreams.resolve(applicationId).upstream());

                await("step 5: the rollback completes after its drain window"
                    + " and its drain lets go of the application", () -> ReleaseSettling.settled(applicationId));
                assertThat(Models.get(InstanceModel.class).findById(firstId))
                    .as("step 5: the drain reclaimed the older retired release; exactly one"
                        + " superseded release stays retained")
                    .isNull();
                assertThat(Models.get(InstanceModel.class).findById(secondId)
                        .get(InstanceModel.RUNTIME_ROLE))
                    .as("step 5: and that one is the release the rollback superseded")
                    .isEqualTo(InstanceModel.ROLE_RETIRED);
            } finally {
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    /**
     * A host with no memory to spare still updates and rolls back an application, and the release a deploy
     * retires hands its memory back.
     *
     * AIDEV-NOTE: found on the 2 GB test host, where every update was refused host_capacity_reached: the candidate
     * was booked beside the serving release it replaces, and the retired release kept its booking for as long as it
     * was retained although its container never runs again (a rollback boots a fresh candidate).
     */
    @Test
    void aFullHostStillUpdatesRollsBackAndDeploysForwardAgain() {
        Integer savedReserve = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB);
        Double savedRatio = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO);
        int host = ServerModel.localServerId();
        Db.run(datasource, () -> {
            int applicationId = application("full", "v1");
            try {
                // 1. The first release books its footprint on the host, and the host is then made full: 1 MB free.
                ApplicationReleases.converge(applicationId, Map.of());
                Row first = ApplicationReleases.ownedServing(applicationId);
                long footprint = InstanceCapacity.bookedMbOf(first);
                assertThat(footprint).as("step 1: the serving release books memory").isPositive();
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB, 0);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO, 1.0);
                long booked = InstanceCapacity.bookedMbOn(host);
                HostFixtures.makeLocalPlaceable(booked + 1);
                assertThat(InstanceCapacity.budgetMbOf(Models.get(ServerModel.class).findById(host)))
                    .as("step 1: the host's budget leaves one megabyte free").isEqualTo(booked + 1);

                // 2. An update still lands: the candidate is admitted against the release it replaces.
                setSettings(applicationId, "v2");
                ApplicationReleases.converge(applicationId, Map.of());
                Row second = ApplicationReleases.ownedServing(applicationId);
                assertThat((Object) second.get(InstanceModel.ID))
                    .as("step 2: the update produced a new serving release on the full host")
                    .isNotEqualTo(first.get(InstanceModel.ID));

                // 3. The retired release hands its memory back, so the host holds one release's worth again.
                Row retired = Models.get(InstanceModel.class).findById(first.get(InstanceModel.ID));
                assertThat(retired.get(InstanceModel.RUNTIME_ROLE))
                    .as("step 3: the first release is retired").isEqualTo(InstanceModel.ROLE_RETIRED);
                assertThat((Object) retired.get(InstanceModel.CAPACITY_MB))
                    .as("step 3: its host booking is handed back").isEqualTo(0);
                assertThat((Object) retired.get(InstanceModel.QUOTA_MEMORY_MB))
                    .as("step 3: and so is its owner booking").isEqualTo(0);
                assertThat(InstanceCapacity.bookedMbOn(host))
                    .as("step 3: the host books exactly what it booked before the update").isEqualTo(booked);
                await("step 3: the update's drain lets go of the application",
                    () -> ReleaseSettling.settled(applicationId));

                // 4. A rollback on the same full host lands too, and the books still hold one release.
                ReleaseEngine.rollback(applicationId, DeployTrigger.MANUAL);
                assertThat(ApplicationReleases.storedSettings(ApplicationReleases.ownedServing(applicationId))
                        .get("image"))
                    .as("step 4: the rollback serves v1 again").isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));
                await("step 4: the rollback's drain lets go of the application",
                    () -> ReleaseSettling.settled(applicationId));
                assertThat(InstanceCapacity.bookedMbOn(host))
                    .as("step 4: the host still books one release of the application").isEqualTo(booked);

                // 5. The rollback stands against a plain converge, and a person pressing Deploy lifts it: the
                //    rejected source is released again instead of the button doing nothing.
                ApplicationReleases.converge(applicationId, Map.of());
                assertThat(ApplicationReleases.storedSettings(ApplicationReleases.ownedServing(applicationId))
                        .get("image"))
                    .as("step 5: a plain converge keeps the rolled-back release")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v1"));
                ApplicationDeploys.deploy(applicationId, null, null, DeployTrigger.MANUAL);
                assertThat(ApplicationReleases.storedSettings(ApplicationReleases.ownedServing(applicationId))
                        .get("image"))
                    .as("step 5: Deploy now serves the branch's source again")
                    .isEqualTo(FakeDockerDaemon.digestOf("fake/app:v2"));
                await("step 5: the forward deploy's drain lets go of the application",
                    () -> ReleaseSettling.settled(applicationId));
            } finally {
                ApplicationReleases.destroyFor(applicationId);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB, savedReserve);
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO, savedRatio);
                HostFixtures.makeLocalPlaceable(16 * 1024);
            }
        });
    }

    /**
     * A release reads finished only once its operation let go of the application, so a client that waits for
     * succeeded and then rolls back is never refused by the release it just read about.
     */
    @Test
    void aReleaseReadsFinishedOnlyOnceItsOperationLetGoOfTheApplication() {
        Db.run(datasource, () -> {
            int applicationId = application("settling", "v1");
            try {
                // 1. A deploy verb holds the application around its converge, as ApplicationDeploys and
                //    ArtifactDeploys do: while the verb still holds it, the release it made reads in flight.
                InstanceOperationLock.production().exclusive(applicationId, InstanceOperationLock.Contention.QUEUE,
                    () -> {
                        ApplicationReleases.converge(applicationId, Map.of());
                        assertThat(ReleaseOperationModel.IN_FLIGHT_STATUSES)
                            .as("step 1: a release whose verb still holds the application has not finished")
                            .contains((String) latestOp(applicationId).get(ReleaseOperationModel.STATUS));
                    });

                // 2. The verb let go: the release reads succeeded, and nothing holds the application.
                assertThat(latestOp(applicationId).get(ReleaseOperationModel.STATUS))
                    .as("step 2: succeeded once the verb let go")
                    .isEqualTo(ReleaseOperationModel.LIFECYCLE.stored(OperationStatus.SUCCEEDED));
                assertThat(InstanceOperationLock.production().runIfIdle(applicationId, () -> { }))
                    .as("step 2: and the application is idle").isTrue();

                // 3. A second release flips and drains on its own thread. A client waits for it to read succeeded
                //    and rolls back at once: the rollback, which refuses a busy application, is admitted.
                setSettings(applicationId, "v2");
                ApplicationReleases.converge(applicationId, Map.of());
                await("step 3: the second release reads succeeded after its drain", () ->
                    ReleaseOperationModel.LIFECYCLE.is(latestOp(applicationId).get(ReleaseOperationModel.STATUS),
                        OperationStatus.SUCCEEDED));
                ReleaseEngine.rollback(applicationId, DeployTrigger.MANUAL);
                assertThat(latestOp(applicationId).get(ReleaseOperationModel.KIND))
                    .as("step 3: the rollback ran").isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);
                await("step 3: the rollback's drain settles", () -> ReleaseSettling.settled(applicationId));
            } finally {
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    /**
     * A site that names no instance is DOWN and says so, instead of forwarding nowhere.
     *
     * AIDEV-NOTE: this is the falsification of the resolve-only handler. Its predecessor
     * deployed inside its constructor, so "no upstream" and "the deploy failed" were the
     * same state; here an application that never deployed simply has nothing serving.
     */
    @Test
    void aSiteWithNothingServingIsHonestlyDown() {
        Db.run(datasource, () -> {
            int applicationId = application("never-deployed", "v1");
            int siteId = siteExposing(applicationId, "never-deployed");
            try {
                InstanceUpstreamHandler handler =
                    new InstanceUpstreamHandler(siteId, applicationId);
                assertThat(handler.current())
                    .as("step 1: an application that never deployed resolves no upstream")
                    .isNull();
                assertThat(handler.getHealth().name())
                    .as("step 2: and the site reports DOWN rather than UP with no target")
                    .isEqualTo("DOWN");
                assertThat(ApplicationUpstreams.resolve(applicationId).upstream())
                    .as("step 3: the resolver agrees, so nothing disagrees about it")
                    .isNull();
            } finally {
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    /**
     * Volume IDENTITY across a flip: every release of an application mounts the SAME host
     * directory, derived from the APPLICATION. A path nobody declared is mounted by none.
     */
    @Test
    void everyReleaseMountsTheApplicationsOwnVolumeDirectory() {
        Db.run(datasource, () -> {
            int applicationId = application("stateful", "v1");
            try {
                // 1. A declared volume's host path is keyed to the APPLICATION. Deriving it
                //    from the release would hand every deploy an empty directory.
                InstanceVolumes.declare(applicationId, "data", "/var/lib/app", null, false);
                String hostPath = InstanceVolumes.hostPathFor(applicationId, "data");
                assertThat(hostPath)
                    .as("step 1: the host path names the application, under the volume root")
                    .isEqualTo("/srv/hoh-test/volumes/" + applicationId + "/data");

                // 2. The release spec mounts exactly what the release STORED, verbatim.
                //    Re-deriving here is the defect; a rollback re-deploys a stored map.
                Map<String, Object> stored = new LinkedHashMap<>(Map.of(
                    "image", "sha256:abc",
                    "container_port", 8080,
                    ApplicationReleases.VOLUME_MOUNTS, Map.of(hostPath, "/var/lib/app")));
                InstanceSpec olderRelease = new ReleaseKind().specFor(999, stored);
                assertThat(olderRelease.binds())
                    .as("step 2: a release whose OWN id is unrelated still mounts the"
                        + " application's directory")
                    .isEqualTo(Map.of(hostPath, "/var/lib/app"));

                // 3. An undeclared path is mounted by nothing, and the hardening rule
                //    refuses it even if a caller tried.
                assertThat(olderRelease.binds().values())
                    .as("step 3: nothing outside the declaration is mounted")
                    .doesNotContain("/etc", "/var/run/docker.sock");
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("HostConfig", new LinkedHashMap<>(Map.of("Mounts",
                    List.of(Map.of("Type", "bind", "Source", "/etc",
                        "Target", "/host-etc")))));
                try {
                    ContainerHardening.applyTo(spec, ContainerHardening.SERVICE);
                    assertThat(false)
                        .as("step 3: an undeclared host path must never be bindable").isTrue();
                } catch (IllegalArgumentException refused) {
                    assertThat(refused.getMessage())
                        .as("step 3: and the refusal names the volume root")
                        .contains("/srv/hoh-test/volumes");
                }

                // 4. The declaration outlives releases: it hangs off the application, so a
                //    reclaim of a retired release cannot take the data with it.
                assertThat(InstanceVolumes.declaredFor(applicationId))
                    .as("step 4: the volume is declared on the application")
                    .hasSize(1);
                assertThat(InstanceVolumes.declaredFor(999))
                    .as("step 4: and on no release").isEmpty();
            } finally {
                Models.get(InstanceVolumeModel.class).find()
                    .where(InstanceVolumeModel.INSTANCE_ID.eq(applicationId)).delete();
                ApplicationReleases.destroyFor(applicationId);
            }
        });
    }

    /**
     * Every release operation records what started it, who it is accountable to and what it deployed, whichever
     * entry point asked: the boot converge, a person's Deploy button, the automation API, a forge push, an API
     * rollback, a lane that names nothing, and a backup restore that carries a commit.
     */
    @Test
    void everyReleaseOperationRecordsWhatStartedItAndWhatItDeployed() {
        Db.run(datasource, () -> {
            int applicationId = application("attributed", "v1");
            int restoredId = application("attributed-restored", "v1");
            Row withSecret = Models.get(InstanceModel.class).findById(applicationId);
            Map<String, Object> secretSettings = settingsFor("v1");
            secretSettings.put("environment_variables", Map.of("DB_PASSWORD", "attribution-secret-value"));
            withSecret.set(InstanceModel.SETTINGS, secretSettings);
            Models.get(InstanceModel.class).save(withSecret);
            // The test body is the installation's own work; a person and an API key holder act through attribution.
            PrincipalRef system = Accountability.current().actorReference();
            PrincipalRef person = PrincipalRef.account(UUID.randomUUID());
            PrincipalRef keyHolder = PrincipalRef.account(UUID.randomUUID());
            Row source = Models.get(ArtifactSourceModel.class).createEmptyRow();
            try {
                // 1. The boot converge (InstanceService.deploy naming no trigger) is the system's own work, and the
                //    spec it recorded keeps only the release schema's keys, every environment value as a keyed digest.
                assertThat(system).as("step 1: the test body acts as the system principal").isNotNull();
                assertThat(system.kind().account()).as("step 1: which is no person").isFalse();
                new InstanceService().deploy(applicationId);
                Row boot = latestOp(applicationId);
                assertThat(boot.get(ReleaseOperationModel.TRIGGER))
                    .as("step 1: the boot converge records the system trigger").isEqualTo(DeployTrigger.SYSTEM.word());
                assertThat(ReleaseOperationModel.TRIGGERER.read(boot))
                    .as("step 1: accountable to the system principal").isEqualTo(system);
                assertThat(boot.get(ReleaseOperationModel.COMMIT_SHA))
                    .as("step 1: an image source records no commit").isNull();
                assertThat(boot.get(ReleaseOperationModel.BUILD_OPERATION_ID))
                    .as("step 1: and no build, since nothing was built").isNull();
                assertThat(boot.get(ReleaseOperationModel.SPEC_SNAPSHOT))
                    .as("step 1: the snapshot names the pinned image")
                    .contains(FakeDockerDaemon.digestOf("fake/app:v1"))
                    .as("step 1: and the variable with a keyed fingerprint").contains("DB_PASSWORD")
                    .contains(SpecSnapshots.fingerprint("attribution-secret-value"))
                    .as("step 1: never its value").doesNotContain("attribution-secret-value")
                    .as("step 1: nor an unkeyed digest of it, confirmable from a backup")
                    .doesNotContain(SecureTokens.sha256Hex("attribution-secret-value").substring(0, 12))
                    .as("step 1: and no key the release schema does not declare").doesNotContain("crash_policy");
                assertThat(SpecSnapshots.fingerprint("attribution-secret-value"))
                    .as("step 1: the stored form is recognisable").matches("hmac:[0-9a-f]{12}");

                // 2. A person pressing Deploy (SiteControlHandlers runs InstanceService.deploy MANUAL as its caller).
                setSettings(applicationId, "v2");
                Accountability.runAs(Accountability.ofActor(person, AccountabilityOrigin.WEB),
                    () -> new InstanceService().deploy(applicationId, DeployTrigger.MANUAL));
                Row manual = latestOp(applicationId);
                assertThat(manual.get(ReleaseOperationModel.TRIGGER))
                    .as("step 2: the button records the manual trigger").isEqualTo(DeployTrigger.MANUAL.word());
                assertThat(ReleaseOperationModel.TRIGGERER.read(manual))
                    .as("step 2: and the person who pressed it").isEqualTo(person);
                await("step 2: the deploy settles", () -> ReleaseSettling.settled(applicationId));

                // 3. The automation API (PaasApi.queueDeploy) deploys on a background thread; the key holder rides it.
                setSettings(applicationId, "v3");
                int beforeApi = latestOp(applicationId).get(ReleaseOperationModel.ID);
                Accountability.runAs(Accountability.ofActor(keyHolder, AccountabilityOrigin.WEB),
                    () -> ApplicationDeploys.deployInBackground(applicationId, null, DeployTrigger.API));
                await("step 3: the background deploy settles", () -> {
                    Row latest = latestOp(applicationId);
                    return latest.get(ReleaseOperationModel.ID) != beforeApi && ReleaseSettling.settled(applicationId);
                });
                Row api = latestOp(applicationId);
                assertThat(api.get(ReleaseOperationModel.TRIGGER))
                    .as("step 3: the API deploy records the api trigger").isEqualTo(DeployTrigger.API.word());
                assertThat(ReleaseOperationModel.TRIGGERER.read(api))
                    .as("step 3: accountable to the key holder across the thread hop").isEqualTo(keyHolder);

                // 4. A forge push (SourceDeploys on GitWebhookHandler's signed, system-run delivery).
                setSettings(applicationId, "v4");
                ApplicationDeploys.deploy(applicationId, "main", "0123456789abcdef0123", DeployTrigger.WEBHOOK);
                Row push = latestOp(applicationId);
                assertThat(push.get(ReleaseOperationModel.TRIGGER))
                    .as("step 4: the push records the webhook trigger").isEqualTo(DeployTrigger.WEBHOOK.word());
                assertThat(ReleaseOperationModel.TRIGGERER.read(push))
                    .as("step 4: accountable to the system, never a person").isEqualTo(system);
                await("step 4: the push settles", () -> ReleaseSettling.settled(applicationId));

                // 5. A rollback over the API (PaasApi) records the api trigger on a rollback operation.
                Accountability.runAs(Accountability.ofActor(keyHolder, AccountabilityOrigin.WEB),
                    () -> ReleaseEngine.rollback(applicationId, DeployTrigger.API));
                Row rollback = latestOp(applicationId);
                assertThat(rollback.get(ReleaseOperationModel.KIND))
                    .as("step 5: a rollback operation").isEqualTo(ReleaseOperationModel.KIND_ROLLBACK);
                assertThat(rollback.get(ReleaseOperationModel.TRIGGER))
                    .as("step 5: started over the API").isEqualTo(DeployTrigger.API.word());
                assertThat(ReleaseOperationModel.TRIGGERER.read(rollback))
                    .as("step 5: by the key holder").isEqualTo(keyHolder);
                assertThat(rollback.get(ReleaseOperationModel.SPEC_SNAPSHOT))
                    .as("step 5: its snapshot is the retained release's spec")
                    .contains(FakeDockerDaemon.digestOf("fake/app:v3"));
                await("step 5: the rollback settles", () -> ReleaseSettling.settled(applicationId));

                // 6. A lane that enters no attribution records the system trigger, never a person's.
                setSettings(applicationId, "v5");
                ApplicationReleases.converge(applicationId, Map.of());
                Row unattributed = latestOp(applicationId);
                assertThat(unattributed.get(ReleaseOperationModel.TRIGGER))
                    .as("step 6: an unattributed release reads as the system's").isEqualTo(DeployTrigger.SYSTEM.word());
                assertThat(ReleaseOperationModel.TRIGGERER.read(unattributed))
                    .as("step 6: and attributed to nobody, never a guessed principal").isNull();
                await("step 6: the converge settles", () -> ReleaseSettling.settled(applicationId));

                // 7. A backup restore of an uploaded artifact records the artifact's digest as its commit.
                source.set(ArtifactSourceModel.APPLICATION_ID, restoredId);
                source.set(ArtifactSourceModel.ARTIFACT_SHA256, "b".repeat(64));
                Models.get(ArtifactSourceModel.class).save(source);
                ApplicationReleases.restore(restoredId, FakeDockerDaemon.digestOf("fake/app:v1"), resolved -> { });
                Row restored = latestOp(restoredId);
                assertThat(restored.get(ReleaseOperationModel.TRIGGER))
                    .as("step 7: a restore is the system's work").isEqualTo(DeployTrigger.SYSTEM.word());
                assertThat(restored.get(ReleaseOperationModel.COMMIT_SHA))
                    .as("step 7: recording the commit it deployed").isEqualTo("b".repeat(64));
            } finally {
                ApplicationReleases.destroyFor(applicationId);
                ApplicationReleases.destroyFor(restoredId);
                Models.get(ArtifactSourceModel.class).find()
                    .where(ArtifactSourceModel.APPLICATION_ID.eq(restoredId)).delete();
            }
        });
    }

    // -- fixtures --------------------------------------------------------------

    /** An authored application record with an image source the fake daemon answers for. */
    private static int application(String name, String tag) {
        Row application = Models.get(InstanceModel.class).createEmptyRow();
        application.set(InstanceModel.NAME, name);
        application.set(InstanceModel.KIND, ApplicationKind.ID.toString());
        application.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
        return application.get(InstanceModel.ID);
    }

    private static void setSettings(int applicationId, String tag) {
        Row application = Models.get(InstanceModel.class).findById(applicationId);
        application.set(InstanceModel.SETTINGS, settingsFor(tag));
        Models.get(InstanceModel.class).save(application);
    }

    private static Map<String, Object> settingsFor(String tag) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", tag);
        settings.put("container_port", 8080);
        return settings;
    }

    /** A site whose one upstream is this application. */
    private static int siteExposing(int applicationId, String slug) {
        return ProxyTestSupport.setupInstanceSite("Site " + slug, slug, applicationId)
            .get(SiteModel.ID);
    }

    private static Row latestOp(int applicationId) {
        return Models.get(ReleaseOperationModel.class).find()
            .where(ReleaseOperationModel.FOR_MODEL.eq(InstanceModel.MODEL_ID.toString()))
            .where(ReleaseOperationModel.FOR_ID.eq(applicationId))
            .orderBy(ReleaseOperationModel.ID, SortOrder.DESC)
            .first();
    }

    /** Bounded wait: the drain completes on a virtual thread, not inline. */
    private static void await(String what, BooleanSupplier condition) {
        Poll.until(what, Duration.ofSeconds(15), Duration.ofMillis(50), condition);
    }
}
