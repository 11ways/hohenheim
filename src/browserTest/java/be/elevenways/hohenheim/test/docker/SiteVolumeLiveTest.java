package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerSiteRequestHandler;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.SiteInstances;
import be.elevenways.hohenheim.server.docker.SiteReleases;
import be.elevenways.hohenheim.server.docker.SiteVolumes;
import be.elevenways.hohenheim.server.orm.GeneratedRows;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A docker site's PERSISTENT storage across the release engine, against a real daemon:
 * the state a tenant writes into a named volume survives a health-gated swap and a
 * rollback, and a volume that predates the site-keyed naming is adopted WITH its data.
 *
 * AIDEV-NOTE: every state assertion is made from INSIDE the container that is serving,
 * never off the settings or the Mounts list alone. The defect this class pins reported
 * total success at every level -- the release succeeded, the mount existed, the settings
 * named a volume -- while the volume the new release mounted was a different, empty one.
 * Only reading the tenant's own file back through the running workload can tell those
 * apart. Assertions are scoped to THIS test's site handles: live classes share one
 * daemon under parallel forks, so a daemon-wide volume count would be a flake.
 */
class SiteVolumeLiveTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String SITE_MODEL = SiteModel.MODEL_ID.toString();

    private static boolean booted;
    private static PrivateNetns netns;
    private static Integer savedProbeTimeout;
    private static Integer savedDrain;

    // The site release container is NetworkPosture.PRIVATE and refuses to deploy where the
    // policy cannot be enforced, so the fixture points the production applier at a real
    // nftables in a private namespace (the same shape DockerSiteHandlerTest uses).
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
        savedDrain = HohenheimSettings.VALUES.getValue(HohenheimSettings.Releases.DRAIN_SECONDS);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, 15);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, 2);
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Releases.PROBE_TIMEOUT_SECONDS, savedProbeTimeout);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Releases.DRAIN_SECONDS, savedDrain);
    }

    /**
     * THE journey: write tenant state into the volume, release forward through the health
     * gate, roll back, and read the same bytes back every time -- out of the container
     * that is actually serving, and always out of ONE volume.
     */
    @Test
    void tenantStateInANamedVolumeSurvivesAGatedReleaseAndARollback() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");

        String repoA = "hohenheim-vol-a-" + System.nanoTime();
        String repoB = "hohenheim-vol-b-" + System.nanoTime();
        String digestA = TestImages.loadHttpServer(docker, repoA + ":latest", "vol-release-one");
        String digestB = TestImages.loadHttpServer(docker, repoB + ":latest", "vol-release-two");

        Row site = ProxyTestSupport.setupSite("hohenheim:docker", "Volume journey site",
            "volume-journey", settingsFor(repoA));
        int siteId = site.get(SiteModel.ID);
        String volume = SiteVolumes.volumeOf(siteId, "data");
        String state = "tenant-state-" + siteId;
        try {
            // 1. The first release runs, and the tenant writes state into the volume --
            //    read straight back out of the workload, so step 3 compares against a
            //    fact rather than an assumption.
            //
            //    AIDEV-NOTE: the volume NAMING assertions are deliberately last (step 5).
            //    They are corroboration; the subject of this test is whether the bytes
            //    survive, and a name assertion up here short-circuits the journey before
            //    it can prove the thing that actually broke.
            DockerSiteRequestHandler first = new DockerSiteRequestHandler(siteId,
                "volume journey", settingsFor(repoA));
            Integer firstInstance = first.getInstanceId();
            assertThat(firstInstance).as("step 1: the first release deployed").isNotNull();
            write(docker, handleOf(firstInstance), "/data/state.txt", state);
            assertThat(read(docker, handleOf(firstInstance), "/data/state.txt"))
                .as("step 1: the workload can read its own state back").isEqualTo(state);

            // 2. A health-gated release forward: a NEW instance row, and the state is
            //    still there -- inside the container that is now serving.
            DockerSiteRequestHandler second = new DockerSiteRequestHandler(siteId,
                "volume journey", settingsFor(repoB));
            Integer secondInstance = second.getInstanceId();
            assertThat(secondInstance).as("step 2: the swap deployed a candidate").isNotNull();
            assertThat(secondInstance)
                .as("step 2: the gated swap really did mint a NEW instance row")
                .isNotEqualTo(firstInstance);
            assertThat(imageOf(docker, handleOf(secondInstance)))
                .as("step 2: the new release runs the new digest").isEqualTo(digestB);
            assertThat(read(docker, handleOf(secondInstance), "/data/state.txt"))
                .as("step 2: the tenant's state survived the release").isEqualTo(state);
            assertThat(mountedVolumeAt(docker, handleOf(secondInstance), "/data"))
                .as("step 2: because it is the SAME volume").isEqualTo(volume);

            // 3. The tenant writes MORE state on the new release, then rolls back: a
            //    rollback re-deploys a retired row from its STORED settings, which is the
            //    path that would mount a third volume if the row were left unhealed.
            write(docker, handleOf(secondInstance), "/data/after.txt", "after-swap");
            SiteReleases.rollback(siteId);
            // The serving row is READ back rather than assumed: what matters is that the
            // release now serving is the prior digest and that it mounts the SAME volume.
            int rolledBack = servingOf(siteId).get(InstanceModel.ID);
            assertThat(imageOf(docker, handleOf(rolledBack)))
                .as("step 3: the rollback serves the prior digest again").isEqualTo(digestA);
            assertThat(mountedVolumeAt(docker, handleOf(rolledBack), "/data"))
                .as("step 3: the rolled-back release mounts the same volume, not a third")
                .isEqualTo(volume);
            assertThat(read(docker, handleOf(rolledBack), "/data/state.txt"))
                .as("step 3: the original state survived the rollback").isEqualTo(state);
            assertThat(read(docker, handleOf(rolledBack), "/data/after.txt"))
                .as("step 3: and so did state written by the release that was rolled back")
                .isEqualTo("after-swap");

            // 4. The volume's IDENTITY: keyed to the site and owner-labelled to the site
            //    record, which is what makes it survive a reclaimed release row.
            OwnerLabels.Owner owner = volumeOwner(docker, volume);
            assertThat(owner).as("step 4: the volume carries owner labels").isNotNull();
            assertThat(String.valueOf(owner.model())).as("step 4: attributed to the SITE model")
                .isEqualTo(SITE_MODEL);
            assertThat(owner.id()).as("step 4: attributed to THIS site")
                .isEqualTo(String.valueOf(siteId));

            // 5. ONE volume for the whole journey, scoped to this site's own names.
            assertThat(volumesUnder(docker, ControllerScope.handle(
                    ControllerScope.KIND_SITE, siteId) + "-vol-"))
                .as("step 5: the site keeps exactly one named volume")
                .containsExactly(volume);
            for (Row instance : instancesOf(siteId)) {
                int id = instance.get(InstanceModel.ID);
                assertThat(volumesUnder(docker, ControllerScope.handle(
                        ControllerScope.KIND_INSTANCE, id) + "-vol-"))
                    .as("step 5: no instance-keyed volume was minted for instance %s", id)
                    .isEmpty();
                assertThat(storedVolumeKeys(instance))
                    .as("step 5: instance %s stores the site-keyed name, so a later"
                        + " rollback cannot mount anything else", id)
                    .containsExactly(volume);
            }
        } finally {
            cleanupSite(site);
            removeVolumeQuietly(docker, volume);
            removeImageQuietly(docker, repoA + ":latest");
            removeImageQuietly(docker, repoB + ":latest");
            removeImageQuietly(docker, digestA);
            removeImageQuietly(docker, digestB);
        }
    }

    /**
     * ADOPTION: a site whose volume predates the site-keyed naming keeps its data. The
     * legacy volume is left in place on purpose (nothing in this product removes a volume
     * autonomously), and the stored instance row is rewritten so a rollback lands on the
     * adopted volume too.
     */
    @Test
    void aLegacyInstanceKeyedVolumeIsAdoptedWithItsDataAndTheRowIsHealed() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        // The adoption copies through the declared detector image; a cold cache would
        // otherwise make this test pass by never adopting anything.
        String detector = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Builds.DETECTOR_IMAGE);
        LiveLane.requireImage(docker, detector);

        String repo = "hohenheim-vol-legacy-" + System.nanoTime();
        String digest = TestImages.loadHttpServer(docker, repo + ":latest", "legacy-release");

        Row site = ProxyTestSupport.setupSite("hohenheim:docker", "Volume adoption site",
            "volume-adoption", settingsFor(repo));
        int siteId = site.get(SiteModel.ID);
        String siteVolume = SiteVolumes.volumeOf(siteId, "data");
        String legacyVolume = null;
        try {
            // 1. Bring the site up, then MANUFACTURE the pre-fix shape: the serving row
            //    stores the logical key "data" and the data lives in the instance-keyed
            //    volume that the old code derived from it.
            Map<String, Object> withoutVolume = settingsFor(repo);
            withoutVolume.remove("volumes");
            DockerSiteRequestHandler first = new DockerSiteRequestHandler(siteId,
                "volume adoption", withoutVolume);
            Integer firstInstance = first.getInstanceId();
            assertThat(firstInstance).as("step 1: the first release deployed").isNotNull();
            legacyVolume = handleOf(firstInstance) + "-vol-data";
            docker.createVolume(legacyVolume,
                OwnerLabels.of(InstanceModel.MODEL_ID, firstInstance));
            seedVolume(docker, legacyVolume, "/legacy/legacy.txt", "legacy-payload");
            storeLogicalVolumeName(siteId, firstInstance);
            assertThat(volumesUnder(docker, siteVolume))
                .as("step 1: the site-keyed volume does not exist yet, so an adoption is"
                    + " the only way the data can reach the next release").isEmpty();

            // 2. A converge adopts the legacy volume's DATA into the site-keyed one.
            DockerSiteRequestHandler second = new DockerSiteRequestHandler(siteId,
                "volume adoption", changedSettingsFor(repo));
            Integer secondInstance = second.getInstanceId();
            assertThat(secondInstance).as("step 2: the converge deployed").isNotNull();
            // The DATA first: a name assertion that fires earlier would hide whether the
            // release can still see the tenant's bytes, which is the whole question.
            assertThat(read(docker, handleOf(secondInstance), "/data/legacy.txt"))
                .as("step 2: the release still sees the legacy volume's data")
                .isEqualTo("legacy-payload");
            assertThat(mountedVolumeAt(docker, handleOf(secondInstance), "/data"))
                .as("step 2: out of the site-keyed volume it was adopted into")
                .isEqualTo(siteVolume);
            assertThat(String.valueOf(volumeOwner(docker, siteVolume).model()))
                .as("step 2: and attributed to the SITE, not to the dead instance row")
                .isEqualTo(SITE_MODEL);

            // 3. The legacy volume is UNTOUCHED: adoption never deletes the only copy.
            assertThat(volumesUnder(docker, legacyVolume))
                .as("step 3: the legacy volume survives for the operator to decide about")
                .containsExactly(legacyVolume);

            // 4. The row heal: every live instance row of the site now stores the
            //    site-keyed name, so a rollback cannot land on the legacy volume again.
            for (Row instance : instancesOf(siteId)) {
                assertThat(storedVolumeKeys(instance))
                    .as("step 4: instance %s stores the site-keyed volume name",
                        instance.get(InstanceModel.ID))
                    .containsExactly(siteVolume);
            }
        } finally {
            cleanupSite(site);
            removeVolumeQuietly(docker, siteVolume);
            if (legacyVolume != null) {
                removeVolumeQuietly(docker, legacyVolume);
            }
            removeImageQuietly(docker, repo + ":latest");
            removeImageQuietly(docker, digest);
        }
    }

    // -- helpers --------------------------------------------------------------

    private static Map<String, Object> settingsFor(String repo) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", repo);
        settings.put("tag", "latest");
        settings.put("container_port", 8080);
        settings.put("volumes", Map.of("data", "/data"));
        return settings;
    }

    /** The same site with one changed knob, so the converge is a real release. */
    private static Map<String, Object> changedSettingsFor(String repo) {
        Map<String, Object> settings = settingsFor(repo);
        settings.put("health_path", "/index.html");
        return settings;
    }

    private static String handleOf(int instanceId) {
        return ControllerScope.handle(ControllerScope.KIND_INSTANCE, instanceId);
    }

    /** Write a file through the running workload itself. */
    private static void write(DockerClient docker, String handle, String path, String content)
            throws IOException {
        DockerClient.ExecResult written = docker.exec(handle,
            List.of("/bin/sh", "-c", "printf '%s' '" + content + "' > " + path));
        assertThat(written.exitCode()).as("writing %s: %s", path, written.output()).isZero();
    }

    /**
     * Read a file back through the running workload.
     *
     * AIDEV-NOTE: the exit code is checked SEPARATELY from the content and the content is
     * what the assertion compares. A helper that returned output on any exit would report
     * "cat: no such file" as a value, and this whole class exists because a missing file
     * used to look like success.
     */
    private static String read(DockerClient docker, String handle, String path)
            throws IOException {
        DockerClient.ExecResult result = docker.exec(handle, List.of("/bin/sh", "-c",
            "cat " + path));
        assertThat(result.exitCode())
            .as("reading %s inside %s printed: %s", path, handle, result.output()).isZero();
        return result.stdout().trim();
    }

    /** Put a file in a volume nothing is mounting yet, through a throwaway container. */
    private static void seedVolume(DockerClient docker, String volume, String path,
                                  String content) throws IOException {
        String handle = "hohenheim-volseed-" + System.nanoTime();
        docker.createContainer(handle, Map.of(
            "Image", "alpine:latest",
            "Cmd", List.of("/bin/sh", "-c",
                "printf '%s' '" + content + "' > " + path),
            "HostConfig", Map.of("NetworkMode", "none", "Mounts", List.of(Map.of(
                "Type", "volume", "Source", volume, "Target", "/legacy")))),
            ContainerHardening.SERVICE);
        try {
            docker.startContainer(handle);
            for (int attempt = 0; attempt < 100; attempt++) {
                Object state = docker.inspectContainer(handle).get("State");
                if (state instanceof Map<?, ?> s && "exited".equals(s.get("Status"))) {
                    assertThat(String.valueOf(s.get("ExitCode")))
                        .as("seeding the legacy volume must succeed").isEqualTo("0");
                    return;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("Timed out seeding volume " + volume);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } finally {
            try {
                docker.removeContainer(handle, true);
            } catch (IOException ignored) {
                // best-effort teardown
            }
        }
    }

    /** Rewrite a row into the PRE-FIX spelling: the logical mount name as the key. */
    @SuppressWarnings("unchecked")
    private static void storeLogicalVolumeName(int siteId, int instanceId) throws Exception {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        Map<String, Object> settings =
            new LinkedHashMap<>((Map<String, Object>) instance.get(InstanceModel.SETTINGS));
        settings.put("volumes", Map.of("data", "/data"));
        instance.set(InstanceModel.SETTINGS, settings);
        GeneratedRows.as(new GeneratedRows.Attribution(SiteInstances.SOURCE, SITE_MODEL, siteId),
            () -> Models.get(InstanceModel.class).save(instance));
    }

    @SuppressWarnings("unchecked")
    private static List<String> storedVolumeKeys(Row instance) {
        Map<String, Object> settings = (Map<String, Object>) instance.get(InstanceModel.SETTINGS);
        Object volumes = settings == null ? null : settings.get("volumes");
        return volumes instanceof Map<?, ?> map
            ? map.keySet().stream().map(String::valueOf).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static String mountedVolumeAt(DockerClient docker, String handle, String target)
            throws IOException {
        Object mounts = docker.inspectContainer(handle).get("Mounts");
        if (mounts instanceof List<?> list) {
            for (Object mount : list) {
                if (mount instanceof Map<?, ?> entry && target.equals(entry.get("Destination"))) {
                    return String.valueOf(entry.get("Name"));
                }
            }
        }
        return null;
    }

    private static OwnerLabels.Owner volumeOwner(DockerClient docker, String volume)
            throws IOException {
        Object labels = docker.inspectVolume(volume).get("Labels");
        return labels instanceof Map<?, ?> map ? OwnerLabels.parse(map) : null;
    }

    /** Volume names starting with a prefix -- scoped to this test's own handles. */
    private static List<String> volumesUnder(DockerClient docker, String prefix)
            throws IOException {
        List<String> found = new ArrayList<>();
        for (Object volume : docker.listVolumes()) {
            if (volume instanceof Map<?, ?> entry
                    && String.valueOf(entry.get("Name")).startsWith(prefix)) {
                found.add(String.valueOf(entry.get("Name")));
            }
        }
        return found;
    }

    private static String imageOf(DockerClient docker, String handle) throws IOException {
        return String.valueOf(docker.inspectContainer(handle).get("Image"));
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

    private static List<Row> instancesOf(int siteId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(SITE_MODEL))
            .where(InstanceModel.GENERATED_FOR_ID.eq(siteId))
            .where(InstanceModel.DELETED_AT.isNull())
            .all();
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

    private static void removeVolumeQuietly(DockerClient docker, String volume) {
        try {
            docker.removeVolume(volume, true);
        } catch (IOException ignored) {
            // best-effort teardown
        }
    }

    private static void removeImageQuietly(DockerClient docker, String image) {
        try {
            docker.removeImage(image, true);
        } catch (IOException ignored) {
            // best-effort teardown
        }
    }
}
