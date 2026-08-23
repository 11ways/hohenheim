package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.instance.ReadinessKind;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostShell;
import be.elevenways.hohenheim.server.build.BuildLog;
import be.elevenways.hohenheim.server.instance.DeployTrigger;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.InstanceReadiness;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVolumes;
import be.elevenways.hohenheim.server.instance.RuntimeImages;
import be.elevenways.hohenheim.server.instance.WorkspaceBuilds;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.hohenheim.server.instance.WorkspaceUids;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.ExecSupport;
import be.elevenways.hohenheim.server.runtime.InstanceRuntime;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What a workspace DECLARES, and what it refuses -- hermetic: no daemon, no host, no
 * filesystem. Every case here is either a derivation or a refusal that must land BEFORE
 * anything is created, which is the only moment at which a refusal is still worth having.
 *
 * <p>The live halves ({@code WorkspaceDockerLiveTest}, {@code WorkspaceIncusLiveTest})
 * prove the same declarations against real daemons; neither half replaces the other.</p>
 */
class WorkspaceKindTest {

    private static final String KIND = WorkspaceKind.ID.toString();

    private static SqlDatasource datasource;
    private static String savedDataPath;
    private static Integer savedUidBase;

    private static final List<Integer> instances = new ArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        savedDataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        savedUidBase = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Storage.VOLUME_UID_BASE);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, "/srv/hoh-ws");
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE, 200000);
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, savedDataPath);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE,
            savedUidBase);
    }

    /**
     * The Docker declaration: one bind at /home/site, a derived uid, the image's start
     * command and port, and NOTHING that would need a second authority to agree with.
     */
    @Test
    void aDockerWorkspaceDeclaresItsHomeBindItsUidAndItsImagesDefaults() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, "npm ci");
            int id = workspace("ws-docker", imageId, ServerModel.localServerId(), Map.of());

            InstanceSpec spec = InstanceKinds.getHandler(KIND)
                .specFor(id, storedSettings(id));

            // 1. THE bind, and only it. Everything outside /home/site is disposable, so a
            //    second declared mount would be a promise the reclaim story cannot keep.
            assertThat(spec.binds())
                .as("step 1: exactly one owned host directory, at the workspace home")
                .containsExactly(Map.entry("/srv/hoh-ws/volumes/" + id + "/home",
                    WorkspaceKind.HOME_PATH));
            assertThat(spec.volumes())
                .as("step 1: and no daemon-owned named volume -- the directory is ours")
                .isEmpty();

            // 2. The identity is a NUMBER derived from the id, and the spec carries it.
            assertThat(spec.runUser())
                .as("step 2: the workspace runs as volume_uid_base + instance id")
                .isEqualTo(200000 + id);
            assertThat(WorkspaceUids.forInstance(id))
                .as("step 2: and the derivation has exactly one home")
                .isEqualTo(spec.runUser());

            // 3. Docker starts the command itself, so it is a Cmd and not an environment
            //    variable an init would have to read.
            assertThat(spec.command())
                .as("step 3: the image's default command, as the container's own")
                .containsExactly("bash", "-lc", "npm start");
            assertThat(spec.env())
                .as("step 3: and no init hand-off on this runtime")
                .doesNotContainKey("HOHENHEIM_START_COMMAND");
            assertThat(spec.image())
                .as("step 3: from the runtime image's DOCKER variant")
                .isEqualTo("hohenheim/node-22:1");

            // 4. The image's default port is published on loopback.
            assertThat(spec.publication()).as("step 4: a publication is declared").isNotNull();
            assertThat(spec.publication().containerPort())
                .as("step 4: on the runtime image's default port").isEqualTo(3000);
            assertThat(spec.publication().publicExposure())
                .as("step 4: loopback only, never 0.0.0.0").isFalse();
        });
    }

    /** An instance override beats the image, on every field the image has a default for. */
    @Test
    void theInstanceOverridesTheImagesCommandPortAndBuild() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22b", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, "npm ci");
            Map<String, Object> settings = Map.of(
                "start_command", "node server.js --port 8080",
                "container_port", 8080,
                "build_command", "make build");
            int id = workspace("ws-override", imageId, ServerModel.localServerId(), settings);

            InstanceSpec spec = InstanceKinds.getHandler(KIND).specFor(id, settings);

            assertThat(spec.command())
                .as("step 1: the declared start command, whitespace intact")
                .containsExactly("bash", "-lc", "node server.js --port 8080");
            assertThat(spec.publication().containerPort())
                .as("step 2: the declared port").isEqualTo(8080);
            assertThat(WorkspaceKind.buildCommandOf(settings,
                    Models.get(RuntimeImageModel.class).findById(imageId)))
                .as("step 3: and the declared build command").isEqualTo("make build");
        });
    }

    /**
     * The Incus declaration differs in exactly the ways the runtime differs: the image is
     * the alias, the command travels in the environment for the image's init, and the
     * identity is still the same number.
     */
    @Test
    void anIncusWorkspaceHandsItsCommandToTheImagesInitAndKeepsTheSameUid() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("debian-13", "hohenheim/debian-13:1",
                "hohenheim/debian-13", null, null, null);
            int serverId = incusHost("ws-incus-host");
            int id = workspace("ws-incus", imageId, serverId, Map.of());

            InstanceSpec spec = InstanceKinds.getHandler(KIND).specFor(id, Map.of());

            assertThat(spec.image())
                .as("step 1: the runtime image's INCUS variant, never its docker tag")
                .isEqualTo("hohenheim/debian-13");
            assertThat(spec.command())
                .as("step 2: no Cmd -- an Incus container execs its init, not our command")
                .isNull();
            assertThat(spec.env())
                .as("step 2: the init reads the command and the identity from the"
                    + " environment, which is what survives lxc.init.cmd's word splitting")
                .containsEntry("HOHENHEIM_START_COMMAND", "sleep infinity")
                .containsEntry("HOHENHEIM_RUN_UID", String.valueOf(200000 + id))
                .containsEntry("HOHENHEIM_WORKDIR", WorkspaceKind.HOME_PATH);
            assertThat(spec.runUser())
                .as("step 3: and the uid derivation does not depend on the runtime")
                .isEqualTo(200000 + id);
            assertThat(spec.binds())
                .as("step 3: the same one home bind")
                .containsValue(WorkspaceKind.HOME_PATH);
        });
    }

    /**
     * A runtime image with no variant for the host's runtime refuses BY NAME, rather than
     * deploying something nobody built.
     */
    @Test
    void anImageWithNoVariantForTheHostsRuntimeRefusesByName() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("docker-only", "hohenheim/docker-only:1",
                null, "run", 80, null);
            int serverId = incusHost("ws-incus-only-docker");
            int id = workspace("ws-no-variant", imageId, serverId, Map.of());

            assertThatThrownBy(() -> InstanceKinds.getHandler(KIND).specFor(id, Map.of()))
                .as("an image with no incus variant cannot run on an incus host")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("runtime_image_no_variant");
        });
    }

    /**
     * A record naming no runtime image is refused at the WRITE, and still at the deploy.
     *
     * AIDEV-NOTE: the pre-fix defect -- the pick was clearable and the requirement lived
     * only in RuntimeImages.requireFor, so an imageless workspace SAVED happily and its
     * first Deploy refused, hours later and on a screen the operator was not on.
     */
    @Test
    void aWorkspaceWithoutARuntimeImageRefusesByName() {
        Db.run(datasource, () -> {
            // 1. The early half: the write itself refuses, on the form that submitted it.
            //    catchThrowable and not assertThatThrownBy: the latter's own "expecting a
            //    throwable" failure carries no description, so a broken guard would report
            //    an unnamed assertion.
            assertThat(catchThrowable(() -> workspace("ws-no-image", null,
                    ServerModel.localServerId(), Map.of())))
                .as("step 1: a workspace must name what it runs inside, at the write")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("runtime_image_required");

            // 2. FALSIFIED: the identical record saves the moment it names one, so the
            //    refusal is about the missing image and not about the kind.
            int imageId = runtimeImage("node-22i", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);
            int id = workspace("ws-no-image", imageId, ServerModel.localServerId(), Map.of());
            assertThat(id).as("step 2: naming an image is all that was missing").isPositive();

            // 3. The LATE half is untouched: a row that names none is still refused where
            //    the spec is derived, which is what covers records written before step 1
            //    existed and every writer that never goes through the model funnel.
            Row imageless = Models.get(InstanceModel.class).createEmptyRow();
            assertThatThrownBy(() -> RuntimeImages.requireFor(imageless))
                .as("step 3: and the deploy-time half still refuses by the same name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("runtime_image_required");
        });
    }

    /**
     * A host whose volume backend cannot enforce a quota cannot carry a workspace, and the
     * refusal names the host, the kind and the backend.
     */
    @Test
    void aHostWithNoVolumeQuotaRefusesToCarryAWorkspace() {
        Db.run(datasource, () -> {
            String local = ServerModel.nameOf(ServerModel.localServerId());
            setBackend(VolumeBackend.NONE);
            assertThatThrownBy(() -> InstanceKinds.getHandler(KIND)
                    .requirePlaceableOn(local, Map.of()))
                .as("step 1: a backend with no quota refuses placement by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("host_no_volume_quota");

            // 2. FALSIFIED: the same call passes the moment the host CAN enforce one, so
            //    the refusal is the backend's doing and not a blanket "no workspaces".
            setBackend(VolumeBackend.BTRFS);
            InstanceKinds.getHandler(KIND).requirePlaceableOn(local, Map.of());
            setBackend(VolumeBackend.NONE);
        });
    }

    /** The uid derivation refuses a base that would collide with anything else on the host. */
    @Test
    void theUidBaseIsRefusedWhenItWouldCollideWithTheHostOrWithIncus() {
        Db.run(datasource, () -> {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE, 1000);
            assertThatThrownBy(() -> WorkspaceUids.forInstance(1))
                .as("step 1: a base inside the host's own account range is refused")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_uid_base_invalid");

            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE,
                WorkspaceUids.INCUS_SUBUID_START);
            assertThatThrownBy(() -> WorkspaceUids.forInstance(1))
                .as("step 2: and so is one inside the range Incus maps containers into")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_uid_base_invalid");

            // 3. FALSIFIED: the shipped default passes, so the guard is about the value.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.VOLUME_UID_BASE,
                200000);
            assertThat(WorkspaceUids.forInstance(5))
                .as("step 3: the shipped base derives a uid in the safe window")
                .isEqualTo(200005);

            // 4. An id large enough to walk INTO the Incus range is refused on its own.
            assertThatThrownBy(() -> WorkspaceUids.forInstance(
                    WorkspaceUids.INCUS_SUBUID_START))
                .as("step 4: an instance id that walks the uid into Incus's range refuses")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_uid_out_of_range");
        });
    }

    /**
     * THE credential invariant: the provider token reaches git through the exec ENVIRONMENT
     * and appears nowhere in anything that lands in the volume.
     *
     * AIDEV-NOTE: hermetic half. It proves the token is not in the command, not in the URL
     * and not in a `-c` option a clone would persist -- which is everything the controller
     * controls. The live halves then grep the real volume after a real clone, because the
     * remaining risk (git writing it somewhere itself) is not visible from here.
     */
    @Test
    void theGitCredentialTravelsOnlyInTheExecEnvironment() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22c", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);
            int id = workspace("ws-clone", imageId, ServerModel.localServerId(), Map.of());
            Map<String, Object> settings = Map.of(
                "repository_url", "https://git.example.test/team/app.git",
                "branch", "main");

            RecordingRuntime runtime = new RecordingRuntime();
            InstanceService.Resolved resolved = new InstanceService.Resolved(
                Models.get(InstanceModel.class).findById(id),
                InstanceKinds.getHandler(KIND), runtime,
                InstanceKinds.getHandler(KIND).specFor(id, settings),
                ServerModel.localServerId(), Map.of(), settings);

            BuildLog log = new BuildLog(64 * 1024);
            String commit = new WorkspaceBuilds().checkout(resolved, "main", settings, log);

            assertThat(commit)
                .as("step 1: the checkout reports the commit git printed last")
                .isEqualTo("cafebabecafebabecafebabecafebabecafebabe");
            assertThat(runtime.lastCommand)
                .as("step 2: the clone runs as a shell snippet inside the workload")
                .startsWith("/bin/bash", "-lc");

            String script = runtime.lastCommand.get(2);
            assertThat(script)
                .as("step 3: the checkout lands in the workspace's own home volume")
                .contains(WorkspaceBuilds.CHECKOUT_PATH)
                .as("step 3: and the URL carries no credential component")
                .doesNotContain("@git.example.test")
                .as("step 3: and no -c option a clone would persist into .git/config")
                .doesNotContain("clone -c");
            assertThat(runtime.lastOptions.workdir())
                .as("step 4: it runs in the workspace home")
                .isEqualTo(WorkspaceKind.HOME_PATH);

            // 5. FALSIFIED the other way: an unbound source carries no credential at all,
            //    so an empty env here is the absence of a secret and not a lost one.
            assertThat(runtime.lastOptions.env())
                .as("step 5: a source with no provider passes no credential")
                .isEmpty();

            // 6. And what the checkout printed is CAPTURED, because the durable operation
            //    record the Deploys tab renders is exactly this text.
            assertThat(log.text())
                .as("step 6: the checkout's own output is captured for the deploy record")
                .contains("Cloning into '/home/site/app'");
        });
    }

    /**
     * The funnel's own decision: what deploying a record MEANS.
     *
     * AIDEV-NOTE: the pre-fix defect -- WorkspaceBuilds had exactly one caller outside its
     * tests, GitWebhookHandler, so pressing Deploy on a workspace produced a running
     * container over an EMPTY /home/site and the only way to get the code in was to
     * configure a webhook at the forge.
     */
    @Test
    void aWorkspaceDeploysItsSourceExactlyWhenItNamesARepository() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22f", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);

            // 1. A bare workspace is a bare container up, exactly as before: nothing to
            //    check out means nothing to check out.
            int bare = workspace("ws-lane-bare", imageId, ServerModel.localServerId(),
                Map.of());
            assertThat(WorkspaceBuilds.deploysSource(
                    Models.get(InstanceModel.class).findById(bare)))
                .as("step 1: a workspace with no repository deploys no source")
                .isFalse();

            // 2. The moment it names a raw repository URL, its deploy IS a source deploy.
            int sourced = workspace("ws-lane-url", imageId, ServerModel.localServerId(),
                Map.of("repository_url", "https://git.example.test/team/app.git"));
            assertThat(WorkspaceBuilds.deploysSource(
                    Models.get(InstanceModel.class).findById(sourced)))
                .as("step 2: a repository URL puts the record on the source lane")
                .isTrue();

            // 3. And so does the PROVIDER-bound spelling, which names no URL at all --
            //    reading only repository_url would have left every provider-bound
            //    workspace on the bare lane.
            int bound = workspace("ws-lane-bound", imageId, ServerModel.localServerId(),
                Map.of("repository", "team/app"));
            assertThat(WorkspaceBuilds.deploysSource(
                    Models.get(InstanceModel.class).findById(bound)))
                .as("step 3: a provider-bound repository counts as a source too")
                .isTrue();

            // 4. FALSIFIED across kinds: the same settings on another kind do NOT put it on
            //    the workspace lane -- the branch is about the KIND, not about the keys.
            Row other = Models.get(InstanceModel.class).createEmptyRow();
            other.set(InstanceModel.NAME, "ws-lane-other-kind");
            other.set(InstanceModel.KIND, "hohenheim:docker_container");
            other.set(InstanceModel.SETTINGS, Map.of("image", "nginx",
                "repository_url", "https://git.example.test/team/app.git"));
            other.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
            Models.get(InstanceModel.class).save(other);
            instances.add(other.get(InstanceModel.ID));
            assertThat(WorkspaceBuilds.deploysSource(other))
                .as("step 4: another kind carrying the same keys stays on its own lane")
                .isFalse();

            // 5. A record that is gone answers false rather than throwing: the funnel asks
            //    this before it resolves anything.
            assertThat(WorkspaceBuilds.deploysSource(null))
                .as("step 5: no record, no source lane").isFalse();
        });
    }

    /**
     * A source-declared workspace starts IN its checkout, and idles until there is one.
     *
     * AIDEV-NOTE: both halves are load-bearing. The build command already runs in
     * {@code WorkspaceBuilds.CHECKOUT_PATH}, so starting one directory up builds the app
     * in one place and runs it in another; and the deploy's checkout is an exec INSIDE
     * this container, so a first start that ran `npm start` against an empty home would
     * exit, take the container with it, and take the exec that was about to fill it too.
     */
    @Test
    void aSourcedWorkspaceStartsInsideItsCheckoutAndIdlesUntilThereIsOne() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22g", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);
            Map<String, Object> settings = Map.of(
                "repository_url", "https://git.example.test/team/app.git");
            int id = workspace("ws-start-sourced", imageId, ServerModel.localServerId(),
                settings);

            List<String> command = InstanceKinds.getHandler(KIND)
                .specFor(id, settings).command();

            assertThat(command)
                .as("step 1: still one bash -lc, so nothing about the driver changed")
                .startsWith("bash", "-lc")
                .hasSize(3);
            assertThat(command.get(2))
                .as("step 2: it idles while the checkout is absent, so the deploy's own"
                    + " exec has a live container to run in")
                .contains("if [ ! -d '" + WorkspaceBuilds.CHECKOUT_PATH + "' ]; then exec "
                    + WorkspaceKind.IDLE_COMMAND)
                .as("step 3: and once there is one, the declared command runs INSIDE it")
                .contains("cd '" + WorkspaceBuilds.CHECKOUT_PATH + "'; exec /bin/bash -lc"
                    + " 'npm start'");

            // 4. FALSIFIED: a workspace with NO repository is untouched -- it starts what
            //    it always started, in the image's own workdir.
            int bare = workspace("ws-start-bare", imageId, ServerModel.localServerId(),
                Map.of());
            assertThat(InstanceKinds.getHandler(KIND).specFor(bare, Map.of()).command())
                .as("step 4: a repository-less workspace starts exactly what it always did")
                .containsExactly("bash", "-lc", "npm start");
        });
    }

    /**
     * A credential typed into the raw repository URL is refused AT THE WRITE.
     *
     * AIDEV-NOTE: the provider lane's token travels in the exec environment and is never
     * written down; a hand-typed {@code https://user:token@host/repo.git} is the one
     * spelling git PERSISTS into .git/config inside the volume, where it outlives the
     * deploy that put it there.
     */
    @Test
    void aCredentialInTheRepositoryUrlIsRefusedOnTheForm() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22h", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);

            // 1. A token in the user-info is refused, by name, before anything is stored.
            assertThat(catchThrowable(() -> workspace("ws-token-url", imageId,
                    ServerModel.localServerId(),
                    Map.of("repository_url", "https://someone:ghp_secrettoken@git.example.test/a.git"))))
                .as("step 1: a credentialed clone URL never reaches the volume")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("repository_url_credential");

            // 2. So is a bare token with no username half.
            assertThat(catchThrowable(() -> workspace("ws-token-only", imageId,
                    ServerModel.localServerId(),
                    Map.of("repository_url", "https://ghp_secrettoken@git.example.test/a.git"))))
                .as("step 2: user-info with no colon is still a credential over https")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("repository_url_credential");

            // 3. FALSIFIED: the same URL without the credential saves, so the refusal is
            //    about the credential and not about the field.
            int clean = workspace("ws-clean-url", imageId, ServerModel.localServerId(),
                Map.of("repository_url", "https://git.example.test/a.git"));
            assertThat(clean).as("step 3: a clean URL is stored").isPositive();

            // 4. And an ssh clone URL keeps its username, which is how every one of them
            //    is spelled -- refusing it would refuse the whole ssh lane.
            int ssh = workspace("ws-ssh-url", imageId, ServerModel.localServerId(),
                Map.of("repository_url", "ssh://git@git.example.test/a.git"));
            assertThat(ssh).as("step 4: an ssh username is not a credential").isPositive();
        });
    }

    /** A deploy of a non-workspace through the workspace verb refuses instead of guessing. */
    @Test
    void theWorkspaceDeployVerbRefusesAnyOtherKind() {
        Db.run(datasource, () -> {
            Row row = Models.get(InstanceModel.class).createEmptyRow();
            row.set(InstanceModel.NAME, "ws-not-a-workspace");
            row.set(InstanceModel.KIND, "hohenheim:docker_container");
            row.set(InstanceModel.SETTINGS, Map.of("image", "nginx"));
            row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
            Models.get(InstanceModel.class).save(row);
            int id = row.get(InstanceModel.ID);
            instances.add(id);

            assertThatThrownBy(() -> new WorkspaceBuilds().deploy(id, "main", DeployTrigger.MANUAL))
                .as("the in-container checkout only applies to a workspace")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_kind_required");
        });
    }

    /** The home volume is declared as a real row, with the settings' quota on it. */
    @Test
    void theHomeVolumeIsAnOrdinaryDeclarationCarryingTheDeclaredQuota() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22d", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);
            Map<String, Object> settings = Map.of("home_quota_mb", 512);
            int id = workspace("ws-quota", imageId, ServerModel.localServerId(), settings);

            WorkspaceKind.ensureHomeDeclared(id, settings);

            List<Row> declared = InstanceVolumes.declaredFor(id);
            assertThat(declared).as("step 1: one declared volume, the home").hasSize(1);
            assertThat((String) declared.get(0).get(InstanceVolumeModel.NAME))
                .as("step 1: named home").isEqualTo(WorkspaceKind.HOME_VOLUME);
            assertThat((String) declared.get(0).get(InstanceVolumeModel.CONTAINER_PATH))
                .as("step 2: mounted where the spec binds it")
                .isEqualTo(WorkspaceKind.HOME_PATH);
            assertThat((Long) declared.get(0).get(InstanceVolumeModel.QUOTA_BYTES))
                .as("step 3: carrying the declared cap, in bytes")
                .isEqualTo(512L * 1024L * 1024L);
            assertThat((Boolean) declared.get(0).get(InstanceVolumeModel.EXCLUSIVE))
                .as("step 4: exclusive -- two workloads must never hold one home")
                .isTrue();

            // 5. Re-declaring is an update, never a second row: a deploy runs this on
            //    every converge.
            WorkspaceKind.ensureHomeDeclared(id, Map.of());
            assertThat(InstanceVolumes.declaredFor(id))
                .as("step 5: still one row").hasSize(1);
            assertThat((Long) InstanceVolumes.declaredFor(id).get(0)
                    .get(InstanceVolumeModel.QUOTA_BYTES))
                .as("step 5: with the quota following the settings").isNull();
        });
    }

    /**
     * The Incus host-uid translation, and the refusal when a host delegates nothing.
     *
     * AIDEV-NOTE: the number this returns is what the volume directory is chowned to. A
     * wrong one is not a crash: the container starts and its own login shell then fails on
     * its own home, which reads as a broken image. That is why it has a test of its own.
     */
    @Test
    void theIncusHostUidIsTheSubuidBasePlusTheNamespaceIdAndAHostWithNoRangeRefuses() {
        Db.run(datasource, () -> {
            HostShell stock = (script, timeout) ->
                new HostShell.Result(0, "root:1000000:1000000000\n");
            assertThat(WorkspaceUids.incusSubuidBase("live", stock))
                .as("step 1: the base is the first range the host delegates to root")
                .isEqualTo(1000000);
            assertThat(WorkspaceUids.incusHostUid(200042,
                    WorkspaceUids.incusSubuidBase("live", stock)))
                .as("step 2: and the host uid is that base plus the namespace id")
                .isEqualTo(1200042);

            // 3. A host that delegates nothing cannot map an unprivileged container at
            //    all, and says so instead of chowning to a number that means nothing.
            HostShell bare = (script, timeout) -> new HostShell.Result(0, "# nothing\n");
            assertThatThrownBy(() -> WorkspaceUids.incusSubuidBase("live", bare))
                .as("step 3: a host with no delegated range refuses by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("workspace_host_no_subuid_range");
        });
    }

    /**
     * Declared readiness is enforced, and a record that declares none waits for nothing.
     */
    @Test
    void declaredReadinessIsWaitedForAndAnUndeclaredOneIsNot() {
        Db.run(datasource, () -> {
            int imageId = runtimeImage("node-22e", "hohenheim/node-22:1",
                "hohenheim/node-22", "npm start", 3000, null);
            int id = workspace("ws-readiness", imageId, ServerModel.localServerId(),
                Map.of());
            Row instance = Models.get(InstanceModel.class).findById(id);
            InstanceStatus running = new InstanceStatus(ContainerState.RUNNING, List.of(),
                null);

            // 1. No template: nothing is declared, so nothing is waited for -- a record
            //    without a readiness contract must not block its own deploy.
            assertThat(InstanceReadiness.declaredKind(instance))
                .as("step 1: a record with no template declares no readiness").isNull();
            InstanceReadiness.await(instance, running);

            // 2. A template declaring HTTP readiness on a workload that publishes NO port
            //    refuses by name: the operator wrote a PATH down and there is no honest way
            //    to answer it without a port to reach it on.
            Row template = Models.get(InstanceTemplateModel.class).createEmptyRow();
            template.set(InstanceTemplateModel.NAME, "ws-readiness-template");
            template.set(InstanceTemplateModel.KIND, KIND);
            template.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.HTTP.token());
            template.set(InstanceTemplateModel.READINESS_TARGET, "/healthz");
            Models.get(InstanceTemplateModel.class).save(template);
            instance.set(InstanceModel.TEMPLATE_ID, template.get(InstanceTemplateModel.ID));
            Models.get(InstanceModel.class).save(instance);

            Row withTemplate = Models.get(InstanceModel.class).findById(id);
            assertThat(InstanceReadiness.declaredKind(withTemplate))
                .as("step 2: the template's declaration is what decides")
                .isEqualTo(ReadinessKind.HTTP);
            assertThatThrownBy(() -> InstanceReadiness.await(withTemplate, running))
                .as("step 2: and an http probe with no published port refuses by name")
                .isInstanceOf(Violations.class)
                .hasMessageContaining("readiness_needs_port");

            // 3. PORT is deliberately the other way: the stored column's default IS `port`,
            //    so a portless workload declaring it never meant a probe, and gating every
            //    templated non-service on it would be the vocabulary's default deciding.
            template.set(InstanceTemplateModel.READINESS_KIND, ReadinessKind.PORT.token());
            Models.get(InstanceTemplateModel.class).save(template);
            InstanceReadiness.await(Models.get(InstanceModel.class).findById(id), running);

            // 4. FALSIFIED: console_line is the console hub's, so it waits for nothing here.
            template.set(InstanceTemplateModel.READINESS_KIND,
                ReadinessKind.CONSOLE_LINE.token());
            Models.get(InstanceTemplateModel.class).save(template);
            InstanceReadiness.await(Models.get(InstanceModel.class).findById(id), running);
        });
    }

    // -- fixtures ---------------------------------------------------------------

    /** A runtime that records what it was asked to exec and answers a fixed transcript. */
    private static final class RecordingRuntime implements InstanceRuntime, ExecSupport {

        private List<String> lastCommand = List.of();
        private ExecOptions lastOptions = ExecOptions.none();

        @Override public @NonNull String create(@NonNull InstanceSpec spec) {
            return spec.handle();
        }

        @Override public void start(@NonNull String handle) { }

        @Override public void stop(@NonNull String handle, int graceSeconds) { }

        @Override public void destroy(@NonNull String handle) { }

        @Override public @NonNull InstanceStatus status(@NonNull String handle) {
            return new InstanceStatus(ContainerState.RUNNING, List.of(), null);
        }

        @Override
        public @NonNull ExecOutcome runExec(@NonNull InstanceSpec spec,
                                            @NonNull List<String> command,
                                            @NonNull ExecOptions options, long timeoutMs) {
            this.lastCommand = List.copyOf(command);
            this.lastOptions = options;
            // stdout AFTER stderr on purpose: a driver hands them back concatenated in
            // whichever order it collected them, and the marker is what makes the commit
            // findable either way.
            return new ExecOutcome(0, "hohenheim-commit:"
                + "cafebabecafebabecafebabecafebabecafebabe\n"
                + "Cloning into '/home/site/app'...\n");
        }
    }

    private static int runtimeImage(String name, String dockerImage, String incusImage,
                                    String command, Integer port, String buildCommand) {
        RuntimeImageModel model = Models.get(RuntimeImageModel.class);
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(RuntimeImageModel.NAME, name);
        }
        row.set(RuntimeImageModel.DOCKER_IMAGE, dockerImage);
        row.set(RuntimeImageModel.INCUS_IMAGE, incusImage);
        row.set(RuntimeImageModel.DEFAULT_COMMAND, command);
        row.set(RuntimeImageModel.DEFAULT_PORT, port);
        row.set(RuntimeImageModel.DEFAULT_BUILD_COMMAND, buildCommand);
        row.set(RuntimeImageModel.BUILD_CONTEXT, "images/" + name);
        row.set(RuntimeImageModel.ENABLED, true);
        model.save(row);
        return row.get(RuntimeImageModel.ID);
    }

    private static int workspace(String name, Integer runtimeImageId, int serverId,
                                 Map<String, Object> settings) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, KIND);
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, runtimeImageId);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        instances.add(id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> storedSettings(int instanceId) {
        Object settings = Models.get(InstanceModel.class).findById(instanceId)
            .get(InstanceModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int incusHost(String name) {
        ServerModel model = Models.get(ServerModel.class);
        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(ServerModel.NAME, name);
        }
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.INCUS_URL, "https://192.0.2.41:8443");
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        row.set(ServerModel.VOLUME_BACKEND, VolumeBackend.BTRFS.token());
        model.save(row);
        return row.get(ServerModel.ID);
    }

    private static void setBackend(VolumeBackend backend) {
        Row server = Models.get(ServerModel.class).findById(ServerModel.localServerId());
        server.set(ServerModel.VOLUME_BACKEND, backend.token());
        Models.get(ServerModel.class).save(server);
    }
}
