package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.test.support.TestAccessContexts;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.operation.OperationRunModel;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static be.elevenways.hohenheim.test.MicrocopyText.say;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A machine's record page speaks of a machine and an app's of an app, each read from the kind's and its driver's
 * declared facts: the start verb (Deploy wherever deploying means deploying code), the Network card instead of web
 * addresses, only the tabs the driver has lanes for (Metrics on Incus too), the devices in the strip, the resize notice
 * and shell refusal worded for what runs, and only the kind changes the driver can perform, offered and written.
 */
class MachineRecordPageJourneyTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "machine-page-";

    @Test
    void aVirtualMachineReadsAsAMachineAndADockerAppAsAnApp() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            Row host = incusHost(cleanup);
            Row vm = instance(cleanup, PREFIX + "vm", "hohenheim:vm", host.get(ServerModel.ID),
                Map.of("image", "alpine/3.22", "image_origin", "catalog"));
            Row app = instance(cleanup, PREFIX + "app", "hohenheim:docker_container", ServerModel.localServerId(),
                Map.of("image", "alpine", "command", "sleep 60"));
            String vmPage = "/admin/instances/" + vm.get(InstanceModel.ID);
            String appPage = "/admin/instances/" + app.get(InstanceModel.ID);

            // 1. Every kind's declared driver is the class its runtimeFor builds, on each runtime it supports, so the
            //    lanes the page reads can never disagree with the driver.
            for (Identifier id : InstanceKindRegistry.registry().ids()) {
                InstanceKindHandler kind = InstanceKinds.getHandler(id.toString());
                // A release-managed kind owns no container (its runtimeFor refuses by name): its lanes are its
                // serving release's, whose kind is checked here in its own right.
                if (kind == null || kind.releaseManaged()) {
                    continue;
                }
                for (String runtime : kind.supportedRuntimes()) {
                    String serverName = ServerModel.RUNTIME_INCUS.equals(runtime)
                        ? host.get(ServerModel.NAME) : ServerModel.LOCAL_HOST_NAME;
                    assertThat(kind.runtimeFor(serverName).getClass())
                        .as("step 1: %s on %s builds its declared driver", id, runtime)
                        .isEqualTo(kind.driverOn(runtime));
                }
            }

            // 2. The machine is started, the app deployed; a workspace is started, and deployed once it names a
            //    repository, because then deploying it builds and ships that code.
            assertThat(say(startAction().labelFor(vm))).as("step 2: a machine's start verb").isEqualTo("Start");
            assertThat(say(startAction().labelFor(app))).as("step 2: an app's start verb").isEqualTo("Deploy");
            Row box = workspace(cleanup, PREFIX + "box", Map.of());
            assertThat(say(startAction().labelFor(box))).as("step 2: a bare workspace is started").isEqualTo("Start");
            Row coded = workspace(cleanup, PREFIX + "coded",
                Map.of("repository_url", "https://example.invalid/app.git"));
            assertThat(say(startAction().labelFor(coded))).as("step 2: a workspace with code is deployed")
                .isEqualTo("Deploy");
            assertThat(adminGet("/admin/instances/" + coded.get(InstanceModel.ID) + "/page/overview").body())
                .as("step 2: and it has the Deployments tab that Deploy fills")
                .contains("/admin/instances/" + coded.get(InstanceModel.ID) + "/page/deployments");

            // 2b. A running machine is not offered Start: it already runs, so Restart and Stop are its power verbs.
            //     Stopped, it is offered Start again. A running app keeps Deploy, which ships its code again.
            assertThat(hidden(InstanceOperations.DEPLOY.id(), vm)).as("step 2b: a running VM offers no Start")
                .isTrue();
            assertThat(hidden(InstanceOperations.RESTART.id(), vm)).as("step 2b: it offers Restart")
                .isFalse();
            assertThat(hidden(InstanceOperations.STOP.id(), vm)).as("step 2b: and Stop").isFalse();
            Row stoppedVm = Models.get(InstanceModel.class).findById(vm.get(InstanceModel.ID));
            stoppedVm.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            assertThat(hidden(InstanceOperations.DEPLOY.id(), stoppedVm))
                .as("step 2b: a stopped VM offers Start").isFalse();
            assertThat(hidden(InstanceOperations.STOP.id(), stoppedVm))
                .as("step 2b: and no Stop").isTrue();
            assertThat(hidden(InstanceOperations.DEPLOY.id(), app))
                .as("step 2b: a running app still offers Deploy").isFalse();
            assertThat(hidden(InstanceOperations.DEPLOY.id(), box))
                .as("step 2b: a running bare workspace is started, so it offers no Start either").isTrue();

            // 3. The machine's overview has a Network card, never the web-address card's promise.
            String vmOverview = adminGet(vmPage + "/page/overview").body();
            assertThat(vmOverview).as("step 3: no web address is asked of a machine")
                .doesNotContain("No address yet. Add one so visitors can reach it.")
                .contains("Network").contains("Hidden behind its host")
                .as("step 3: an unreachable host is said in words")
                .contains("Its host did not answer, so its addresses are unknown right now")
                .as("step 3: its live gauges wait for the reading the overview itself opens")
                .contains("Waiting for its first live reading.");

            // 4. Its strip carries Devices and Metrics (the Incus driver reads live stats); Files and an empty
            //    Provisioning are absent and their routes 404.
            assertThat(InstanceKinds.offers(vm, WorkloadLane.LIVE_STATS)).as("step 4: Incus has a live-stats lane")
                .isTrue();
            assertThat(vmOverview).as("step 4: the devices tab is in the strip")
                .contains(vmPage + "/page/devices")
                .contains(vmPage + "/page/stats")
                .doesNotContain(vmPage + "/page/files")
                .doesNotContain(vmPage + "/page/provisioning");
            assertThat(adminGet(vmPage + "/page/files").statusCode()).as("step 4: no file lane, no page")
                .isEqualTo(404);
            assertThat(adminGet(vmPage + "/page/stats").statusCode()).as("step 4: the Metrics tab renders")
                .isEqualTo(200);

            // 5. Its configuration words the resize as Incus applies it, and offers no other kind.
            String vmEdit = adminGet(vmPage).body();
            assertThat(vmEdit).as("step 5: the resize notice speaks of Incus")
                .contains("in place through Incus")
                .doesNotContain("It keeps its published ports")
                .as("step 5: no kind card offers a website kind")
                .doesNotContain("Build from a repository");
            assertThat(InstanceKinds.kindChangesOf(vm)).as("step 5: a running VM can only stay a VM")
                .containsExactly("hohenheim:vm");
            Row rekinded = Models.get(InstanceModel.class).findById(vm.get(InstanceModel.ID));
            rekinded.set(InstanceModel.KIND, "hohenheim:system_container");
            assertThatThrownBy(() -> Models.get(InstanceModel.class).save(rekinded))
                .as("step 5: the write refuses the change the form does not offer")
                .isInstanceOf(Violations.class);

            // 6. Its shell refusal does not speak of a container's root.
            assertThat(adminGet(vmPage + "/page/shell").body()).as("step 6: no container words on a VM")
                .doesNotContain("root inside the container is root on the host");

            // 7. The Docker app keeps the website and container shape.
            String appOverview = adminGet(appPage + "/page/overview").body();
            assertThat(appOverview).as("step 7: an app asks for its address")
                .contains("No address yet. Add one so visitors can reach it.")
                .contains(appPage + "/page/files").contains(appPage + "/page/stats")
                .contains(appPage + "/page/provisioning")
                .doesNotContain(appPage + "/page/devices");
            assertThat(InstanceKinds.offers(app, WorkloadLane.FILES)).as("step 7: the Docker driver browses files")
                .isTrue();
            assertThat(adminGet(appPage).body()).as("step 7: its resize recreates the container")
                .contains("recreates a running workload's container")
                .as("step 7: and says the ports are kept, naming the one case a number can still change")
                .contains("It keeps its published ports")
                .contains("only a number the host picked itself")
                .doesNotContain("published port may change")
                .as("step 7: its kind cards offer only what this record can become")
                .contains("data-value=\"hohenheim:docker_container\"")
                .doesNotContain("data-value=\"hohenheim:vm\"")
                .doesNotContain("data-value=\"hohenheim:system_container\"");
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    @Test
    void aFinishedMachineRunOpensTheScreenOnlyWhenItsDriveHoldsInstallMedia() throws Exception {
        List<Runnable> cleanup = new ArrayList<>();
        try {
            Row host = incusHost(cleanup);
            Row vm = instance(cleanup, PREFIX + "iso", "hohenheim:vm", host.get(ServerModel.ID),
                Map.of("image_origin", "install_media"));
            int vmId = vm.get(InstanceModel.ID);
            String vmPage = "/admin/instances/" + vmId + "/page/";
            String runPage = "/admin/" + HohenheimSlugs.PUT_ONLINE + "/run/" + finishedMachineRun(cleanup, vmId);

            // 1. A machine put online without install media: its finished run leads on to its overview.
            assertThat(adminGet(runPage).body()).as("step 1: onward to the overview")
                .contains(vmPage + "overview")
                .doesNotContain(vmPage + HohenheimSlugs.Tab.FRAMEBUFFER);

            // 2. With install media in its drive, the same finished run leads on to its screen, where the installer
            //    waits: the tab the put-online placement declares, read from the record once the run ended.
            var devices = Models.get(InstanceDeviceModel.class);
            Row cdrom = devices.createEmptyRow();
            cdrom.set(InstanceDeviceModel.INSTANCE_ID, vmId);
            cdrom.set(InstanceDeviceModel.TYPE, InstanceDeviceModel.TYPE_CDROM);
            cdrom.set(InstanceDeviceModel.NAME, "cdrom");
            cdrom.set(InstanceDeviceModel.SOURCE_MEDIA, "debian.iso");
            devices.save(cdrom);
            cleanup.add(() -> HardDeletes.row(devices, cdrom));
            assertThat(adminGet(runPage).body()).as("step 2: onward to the screen")
                .contains(vmPage + HohenheimSlugs.Tab.FRAMEBUFFER);
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    /** @return the id of a put-online machine run over the record that has ended, as a succeeded wizard leaves it */
    private static long finishedMachineRun(List<Runnable> cleanup, int instanceId) {
        OperationRunModel runs = Models.get(OperationRunModel.class);
        Row run = runs.createEmptyRow();
        run.set(OperationRunModel.OPERATION, PutOnline.PUT_MACHINE_ONLINE.id().toString());
        run.set(OperationRunModel.INVOCATION_ID, UUID.randomUUID());
        run.set(OperationRunModel.SUBJECT_TYPE, InstanceOperations.INSTANCE.id().toString());
        run.set(OperationRunModel.SUBJECT_KEY, String.valueOf(instanceId));
        run.set(OperationRunModel.STATUS, OperationRunStatus.SUCCEEDED.storageKey());
        run.set(OperationRunModel.PROGRESS, 1.0);
        run.set(OperationRunModel.STARTED_AT, Now.instant());
        run.set(OperationRunModel.ENDED_AT, Now.instant());
        runs.save(run);
        cleanup.add(() -> HardDeletes.row(runs, run));
        return run.get(OperationRunModel.ID);
    }

    private static PanelAction<Row> startAction() {
        return action(InstanceOperations.DEPLOY.id());
    }

    private static PanelAction<Row> action(Identifier id) {
        for (PanelAction<Row> action : InstanceActions.placedOperator()) {
            if (action.id().equals(id)) {
                return action;
            }
        }
        throw new AssertionError("no action " + id);
    }

    /** @return whether the placed operation's state hides it on this one row */
    private static boolean hidden(Identifier id, Row row) {
        return action(id).hiddenOn(List.of(row), TestAccessContexts.anonymous()).test(row);
    }

    private static Row incusHost(List<Runnable> cleanup) {
        var servers = Models.get(ServerModel.class);
        Row host = servers.createEmptyRow();
        host.set(ServerModel.NAME, PREFIX + "host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        host.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        host.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        servers.save(host);
        HostFixtures.acknowledgePosture(host);
        cleanup.add(() -> HardDeletes.row(servers, host));
        return host;
    }

    private static Row instance(List<Runnable> cleanup, String name, String kind, int serverId,
                                Map<String, Object> settings) {
        return instance(cleanup, name, kind, serverId, settings, null);
    }

    /** A workspace on the local Docker host, on a runtime image of its own (a workspace requires one). */
    private static Row workspace(List<Runnable> cleanup, String name, Map<String, Object> settings) {
        var images = Models.get(RuntimeImageModel.class);
        Row image = images.createEmptyRow();
        image.set(RuntimeImageModel.NAME, name + "-image");
        image.set(RuntimeImageModel.DOCKER_IMAGE, "alpine");
        image.set(RuntimeImageModel.ENABLED, true);
        images.save(image);
        cleanup.add(() -> HardDeletes.row(images, image));
        return instance(cleanup, name, "hohenheim:workspace", ServerModel.localServerId(), settings,
            image.get(RuntimeImageModel.ID));
    }

    private static Row instance(List<Runnable> cleanup, String name, String kind, int serverId,
                                Map<String, Object> settings, Integer runtimeImageId) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        row.set(InstanceModel.SERVER_ID, serverId);
        row.set(InstanceModel.RUNTIME_IMAGE_ID, runtimeImageId);
        instances.save(row);
        cleanup.add(() -> HardDeletes.row(instances, row));
        return row;
    }
}
