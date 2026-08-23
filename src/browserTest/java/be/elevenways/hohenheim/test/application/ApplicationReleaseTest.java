package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.application.InstanceUpstreamHandler;
import be.elevenways.hohenheim.server.application.ReleaseEngine;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.ReleaseKind;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application tier end to end, over {@link FakeDockerDaemon}: an operator authors ONE
 * record, each deploy generates a release, a site serves whichever release is serving, a
 * second deploy flips traffic and retires the old one, and a rollback flips back.
 *
 * <p>This is the journey the re-keying exists for. {@code ApplicationReleaseContractTest}
 * proves the engine's STATE MACHINE (probe outcomes, drain, retention, boot recovery);
 * this proves the SHAPE the phase-0 design asked for -- that the thing an operator edits
 * and the thing that runs are two records, and that a site is a hostname pointing at the
 * first of them.
 *
 * <p>WHAT THIS CANNOT PROVE, and what the live lane remains the only proof of: real bytes
 * in a real volume. The daemon here is a fake, so a bind mount is a mount SPEC and nothing
 * writes through it. What is asserted below is the property that makes survival possible --
 * that every release of an application mounts the SAME host directory, derived from the
 * APPLICATION and never from the release -- which is exactly the defect the deleted
 * site-keyed volumes were introduced to fix. {@code BtrfsVolumeLiveTest} proves the
 * filesystem half, and {@code ApplicationReleaseLiveTest} the traffic half.
 */
class ApplicationReleaseTest {

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;
    private static Integer savedProbeTimeout;
    private static Integer savedProbeInterval;
    private static Integer savedDrain;
    private static String savedDataPath;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
        savedProbeTimeout = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS);
        savedProbeInterval = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS);
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        savedDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 2);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_INTERVAL_MS, 50);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 0);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/hoh-test");
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_INTERVAL_MS, savedProbeInterval);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
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
                    .isEqualTo(ReleaseOperationModel.STATUS_SUCCEEDED);

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

                await("step 4: the flip's operation completes after its drain window",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        latestOp(applicationId).get(ReleaseOperationModel.STATUS)));

                // 5. ROLLBACK flips back onto the RETAINED release, and the site follows
                //    again. Nothing is rebuilt: the retained spec is digest-pinned.
                int pullsBefore = daemon.callCount("api:POST /images/create");
                ReleaseEngine.rollback(applicationId);
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
                    .isEqualTo(ApplicationReleases.storedSettings(
                        Models.get(InstanceModel.class).findById(firstId))
                            .get("source_fingerprint"));
                assertThat(handler.current())
                    .as("step 5: and the site follows it, re-resolved off the same"
                        + " generation the rollback bumped")
                    .isEqualTo(ApplicationUpstreams.resolve(applicationId).upstream());

                await("step 5: the rollback completes after its drain window",
                    () -> ReleaseOperationModel.STATUS_SUCCEEDED.equals(
                        latestOp(applicationId).get(ReleaseOperationModel.STATUS)));
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
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
