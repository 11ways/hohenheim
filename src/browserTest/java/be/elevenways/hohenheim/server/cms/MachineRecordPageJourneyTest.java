package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.PanelAction;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A machine's record page speaks of a machine and an app's of an app, each read from the kind's and its driver's
 * declared facts: the start verb, the Network card instead of web addresses, only the tabs the driver has lanes for,
 * the devices in the strip, the resize notice and shell refusal worded for what runs, and only the kind changes the
 * driver can perform.
 */
class MachineRecordPageJourneyTest extends HohenheimTestBase {

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
            for (Identifier id : InstanceKindRegistry.REGISTRY.ids()) {
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

            // 2. The machine is started, the app deployed.
            assertThat(say(startAction().labelFor(vm))).as("step 2: a machine's start verb").isEqualTo("Start");
            assertThat(say(startAction().labelFor(app))).as("step 2: an app's start verb").isEqualTo("Deploy");

            // 3. The machine's overview has a Network card, never the web-address card's promise.
            String vmOverview = adminGet(vmPage + "/page/overview").body();
            assertThat(vmOverview).as("step 3: no web address is asked of a machine")
                .doesNotContain("No address yet. Add one so visitors can reach it.")
                .contains("Network").contains("Hidden behind its host")
                .as("step 3: an unreachable host is said in words")
                .contains("Its host did not answer, so its addresses are unknown right now")
                .as("step 3: its live gauges do not point at a Metrics tab it does not have")
                .doesNotContain("Read live while its Metrics tab is open");

            // 4. Its strip carries Devices; Files, Metrics and an empty Provisioning are absent and their routes 404.
            assertThat(vmOverview).as("step 4: the devices tab is in the strip")
                .contains(vmPage + "/page/devices")
                .doesNotContain(vmPage + "/page/files")
                .doesNotContain(vmPage + "/page/stats")
                .doesNotContain(vmPage + "/page/provisioning");
            assertThat(adminGet(vmPage + "/page/files").statusCode()).as("step 4: no file lane, no page")
                .isEqualTo(404);
            assertThat(adminGet(vmPage + "/page/stats").statusCode()).as("step 4: no live lane, no page")
                .isEqualTo(404);

            // 5. Its configuration words the resize as Incus applies it, and offers no other kind.
            String vmEdit = adminGet(vmPage).body();
            assertThat(vmEdit).as("step 5: the resize notice speaks of Incus")
                .contains("in place through Incus")
                .doesNotContain("its published port may change")
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
                .contains("recreates a running workload's container");
        } finally {
            for (int i = cleanup.size() - 1; i >= 0; i--) {
                cleanup.get(i).run();
            }
        }
    }

    private static PanelAction<Row> startAction() {
        for (PanelAction<Row> action : InstanceActions.placedOperator()) {
            if (action.id().equals(InstanceOperations.START.id())) {
                return action;
            }
        }
        throw new AssertionError("no start action");
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
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        row.set(InstanceModel.SERVER_ID, serverId);
        instances.save(row);
        cleanup.add(() -> HardDeletes.row(instances, row));
        return row;
    }

    private static String say(Microcopy copy) {
        return copy == null ? "" : copy.resolve(LocaleChain.ofTags("en"), Zenit.getMessageResolver());
    }
}
