package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceDeviceModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PreviewDeploymentModel;
import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.instance.FakeDeviceDaemon;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tools of the other app kinds, an app's parts, runtime images and templates through the real MCP endpoint, as
 * an administrator's key: a runtime image made and changed, a workspace from it, a system container and a virtual
 * machine on an Incus host, a disk attached, grown and detached, a game address listed, a template made, approved,
 * exported and imported, the scripts and previews refused where they do not apply, describe naming each call, every
 * delete, and a customer's key refused what the panel refuses him.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimCatalogToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-catalog-";

    /** A record's code as an answer names it. */
    private static final Pattern CODE = Pattern.compile("\\b(AP|RI|TP|DV|GD)\\d+\\b");

    @Test
    void anAdministratorRunsAppKindsPartsAndTemplatesByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        AccessContext operator = TenantConduits.operator();
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            // A workspace mounts its home volume: the local host's data root holds a quota for it.
            HostFixtures.giveLocalVolumeQuota();
            String localHost = HohenheimMcpTest.code(HostModel.MODEL_ID, HostModel.localHostId(), operator);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            UUID customerId = ApiSupport.user(PREFIX + tag + "@surface.test", "Mcp Catalog Customer");
            McpHttpSession customer = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Mcp Catalog Customer");
            int incusId = HostFixtures.admittedIncusHost(PREFIX + "incus-" + tag);
            cleanUp.push(() -> HardDeletes.byId(Models.get(HostModel.class), incusId));
            String incus = HohenheimMcpTest.code(HostModel.MODEL_ID, incusId, operator);

            // 1. A runtime image of our own is made and changed; a create without its name is refused.
            assertThat(admin.call("runtime_image_set", Map.of("docker_image", "fake/node")).text())
                .as("step 1: a create without its name").contains("To create a runtime image, give name.");
            String image = made(admin, "runtime_image_set", Map.of("name", PREFIX + "node-" + tag,
                "docker_image", "fake/node:22", "incus_image", "images:debian/12", "default_port", 3000,
                "request", UUID.randomUUID().toString()), "RI", "step 1");
            int imageId = idOf(RuntimeImageModel.MODEL_ID, image);
            cleanUp.push(() -> HardDeletes.byId(Models.get(RuntimeImageModel.class), imageId));
            assertThat(admin.call("runtime_image_set", Map.of("code", image, "description", "Node for tests"))
                .error()).as("step 1: described").isFalse();
            assertThat((String) Models.get(RuntimeImageModel.class).findById(imageId)
                .get(RuntimeImageModel.DESCRIPTION)).as("step 1: stored").isEqualTo("Node for tests");
            Row builtin = Models.get(RuntimeImageModel.class).find()
                .where(RuntimeImageModel.BUILTIN.eq(true)).first();
            if (builtin != null) {
                String builtinCode = HohenheimMcpTest.code(RuntimeImageModel.MODEL_ID,
                    builtin.get(RuntimeImageModel.ID), operator);
                assertThat(admin.call("runtime_image_set", Map.of("code", builtinCode, "description", "mine"))
                    .error()).as("step 1: a built-in image is not changed").isTrue();
            }

            // 2. A workspace runs inside it: a create without the image is refused in the spec's words.
            assertThat(admin.call("app_workspace_set", Map.of("name", PREFIX + "ws-" + tag)).text())
                .as("step 2: a workspace needs its image").contains("runtime_image");
            String workspace = made(admin, "app_workspace_set", Map.of("name", PREFIX + "ws-" + tag,
                "runtime_image", image, "host", localHost, "start_command", "npm start"), "AP", "step 2");
            int workspaceId = idOf(InstanceModel.MODEL_ID, workspace);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceModel.class), workspaceId));
            Row ws = Models.get(InstanceModel.class).findById(workspaceId);
            assertThat((String) ws.get(InstanceModel.STATUS)).as("step 2: created, not deployed")
                .isEqualTo(InstanceModel.STATUS_CREATED);
            assertThat((Integer) ws.get(InstanceModel.RUNTIME_IMAGE_ID)).as("step 2: its image").isEqualTo(imageId);
            assertThat(admin.call("app_workspace_set", Map.of("code", workspace, "start_command", "npm run dev"))
                .error()).as("step 2: changed").isFalse();
            assertThat(admin.call("read", Map.of("codes", List.of(workspace))).text()).as("step 2: read shows it")
                .contains("npm run dev");
            assertThat(admin.call("describe", Map.of("code", workspace)).text()).as("step 2: its own kind's tool")
                .contains("app_workspace_set code=" + workspace).doesNotContain("app_vm_set code=");

            // 3. A system container and a virtual machine on the Incus host; a secret cloud_init never comes out.
            String container = made(admin, "app_system_container_set", Map.of("name", PREFIX + "lxc-" + tag,
                "host", incus, "image", "images:debian/12"), "AP", "step 3");
            int containerId = idOf(InstanceModel.MODEL_ID, container);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceModel.class), containerId));
            assertThat((Integer) Models.get(InstanceModel.class).findById(containerId).get(InstanceModel.HOST_ID))
                .as("step 3: on the Incus host").isEqualTo(incusId);
            assertThat(admin.call("app_system_container_set", Map.of("code", container, "memory_limit_mb", 1024))
                .error()).as("step 3: the container's memory").isFalse();
            String cloudInit = "#cloud-config\npassword: vm-secret-" + tag;
            String vm = made(admin, "app_vm_set", Map.of("name", PREFIX + "vm-" + tag, "host", incus,
                "image", "images:debian/12", "memory_limit_mb", 2048), "AP", "step 3");
            int vmId = idOf(InstanceModel.MODEL_ID, vm);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceModel.class), vmId));
            McpHttpSession.Answer booted = admin.call("app_vm_set", Map.of("code", vm, "secure_boot", true,
                "cloud_init", cloudInit));
            assertThat(booted.error()).as("step 3: " + booted.text()).isFalse();
            assertThat(booted.text()).as("step 3: what changed").contains("secure_boot").contains("cloud_init")
                .doesNotContain("vm-secret-");
            String vmRead = admin.call("read", Map.of("codes", List.of(vm))).text();
            assertThat(vmRead).as("step 3: read shows the setting, never the secret").contains("secure_boot")
                .doesNotContain("vm-secret-");
            assertThat(admin.call("app_vm_set", Map.of("code", container, "secure_boot", true)).text())
                .as("step 3: a container is not changed by the VM tool").contains("app_system_container_set");

            // 4. A disk is attached to a device-carrying app, grown and detached at the daemon.
            FakeDeviceDaemon.Kind.register();
            int machineId = deviceApp(PREFIX + "machine-" + tag, incusId);
            cleanUp.push(() -> {
                HardDeletes.byId(Models.get(InstanceModel.class), machineId);
                FakeDeviceDaemon.DAEMON.remove(FakeDeviceDaemon.handleOf(machineId));
            });
            String machine = HohenheimMcpTest.code(InstanceModel.MODEL_ID, machineId, operator);
            assertThat(admin.call("app_device_set", Map.of("app", machine, "name", "data")).text())
                .as("step 4: a device needs its kind").contains("To create a device, give");
            String disk = made(admin, "app_device_set", Map.of("app", machine, "kind", "disk", "name", "data",
                "size_gb", 2), "DV", "step 4");
            int diskId = idOf(InstanceDeviceModel.MODEL_ID, disk);
            FakeDeviceDaemon.Workload daemon = FakeDeviceDaemon.DAEMON.get(FakeDeviceDaemon.handleOf(machineId));
            assertThat(daemon.disks).as("step 4: attached at the daemon").containsEntry("data", 2);
            assertThat(admin.call("app_device_set", Map.of("code", disk, "size_gb", 3)).error())
                .as("step 4: grown").isFalse();
            assertThat(daemon.disks).as("step 4: grown at the daemon").containsEntry("data", 3);
            assertThat(admin.call("app_device_set", Map.of("code", disk, "name", "other")).error())
                .as("step 4: a device's name never changes").isTrue();
            assertThat(admin.call("describe", Map.of("code", disk)).text()).as("step 4: describe names its calls")
                .contains("app_device_set code=" + disk).contains("delete code=" + disk);
            assertThat(admin.call("delete", Map.of("code", disk)).error()).as("step 4: detached").isFalse();
            assertThat(daemon.disks).as("step 4: gone at the daemon").doesNotContainKey("data");
            assertThat(Models.get(InstanceDeviceModel.class).findById(diskId)).as("step 4: no row").isNull();

            // 5. An application built from an image, not a repository: the app a preview is refused for below.
            int backendId = FakeApps.application(PREFIX + "shop-" + tag, "v1");
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceModel.class), backendId));
            String backend = HohenheimMcpTest.code(InstanceModel.MODEL_ID, backendId, operator);
            McpHttpSession.Answer games = admin.call("list", Map.of("kind", "game_address"));
            assertThat(games.error()).as("step 5: game addresses are a list kind: " + games.text()).isFalse();

            // 6. A template is made, changed, approved alone, exported and imported again, not approved.
            assertThat(admin.call("template_set", Map.of("name", PREFIX + "tpl-" + tag)).text())
                .as("step 6: a template needs its kind").contains("To create a template, give");
            String template = made(admin, "template_set", Map.of("name", PREFIX + "tpl-" + tag,
                "kind", "system_container", "install_script", "echo installed"), "TP", "step 6");
            int templateId = idOf(InstanceTemplateModel.MODEL_ID, template);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceTemplateModel.class), templateId));
            assertThat(admin.call("template_set", Map.of("code", template, "stop_command", "stop")).error())
                .as("step 6: changed").isFalse();
            assertThat(admin.call("template_set", Map.of("code", template, "approved", true, "name", "x")).error())
                .as("step 6: approved is given alone").isTrue();
            assertThat(admin.call("template_set", Map.of("name", "x", "kind", "vm", "approved", true)).error())
                .as("step 6: a create approves nothing").isTrue();
            McpHttpSession.Answer approved = admin.call("template_set", Map.of("code", template, "approved", true));
            assertThat(approved.error()).as("step 6: " + approved.text()).isFalse();
            assertThat((Object) Models.get(InstanceTemplateModel.class).findById(templateId)
                .get(InstanceTemplateModel.APPROVED_AT)).as("step 6: approved").isNotNull();
            McpHttpSession.Answer exported = admin.call("template_export", Map.of("code", template));
            assertThat(exported.error()).as("step 6: " + exported.text()).isFalse();
            String document = documentOf(exported.text());
            assertThat(document).as("step 6: the document").contains("hohenheim-instance-template")
                .contains("echo installed");
            assertThat(admin.call("template_import", Map.of("document", document, "catalog_app", "nginx")).error())
                .as("step 6: one source only").isTrue();
            String imported = made(admin, "template_import", Map.of("document", document,
                "request", UUID.randomUUID().toString()), "TP", "step 6");
            int importedId = idOf(InstanceTemplateModel.MODEL_ID, imported);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceTemplateModel.class), importedId));
            assertThat(imported).as("step 6: a new template").isNotEqualTo(template);
            assertThat((Object) Models.get(InstanceTemplateModel.class).findById(importedId)
                .get(InstanceTemplateModel.APPROVED_AT)).as("step 6: not approved").isNull();
            assertThat(admin.call("template_set", Map.of("code", template, "approved", false)).error())
                .as("step 6: withdrawn").isFalse();
            assertThat((Object) Models.get(InstanceTemplateModel.class).findById(templateId)
                .get(InstanceTemplateModel.APPROVED_AT)).as("step 6: no longer approved").isNull();
            assertThat(admin.call("describe", Map.of("code", template)).text()).as("step 6: describe names them")
                .contains("template_set code=" + template).contains("template_export code=" + template);

            // 7. A script of an app made from no template, and a preview of a container, are refused.
            assertThat(admin.call("app_script_run", Map.of("code", container, "script", "install")).error())
                .as("step 7: no template, no install").isTrue();
            long previews = Models.get(PreviewDeploymentModel.class).find().count();
            McpHttpSession.Answer preview = admin.call("preview_run", Map.of("app", backend, "ref", "main"));
            assertThat(preview.error()).as("step 7: " + preview.text()).isTrue();
            assertThat(preview.text()).as("step 7: refused in the preview's own words")
                .containsIgnoringCase("preview");
            assertThat(Models.get(PreviewDeploymentModel.class).find().count()).as("step 7: none queued")
                .isEqualTo(previews);

            // 8. A customer's key is refused what the panel refuses him.
            assertThat(customer.call("runtime_image_set", Map.of("name", PREFIX + "theirs-" + tag)).error())
                .as("step 8: no runtime image").isTrue();
            assertThat(customer.call("template_set", Map.of("name", PREFIX + "theirs-" + tag,
                "kind", "vm")).error()).as("step 8: no template").isTrue();
            assertThat(customer.call("app_vm_set", Map.of("name", PREFIX + "theirs-" + tag)).error())
                .as("step 8: no machine without a template").isTrue();
            assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "theirs-" + tag))
                .count()).as("step 8: nothing made").isZero();

            // 9. Every delete, by code: both templates, then the runtime image once unused.
            for (String code : List.of(imported, template)) {
                McpHttpSession.Answer deleted = admin.call("delete", Map.of("code", code));
                assertThat(deleted.error()).as("step 9: " + code + ": " + deleted.text()).isFalse();
            }
            assertThat(Models.get(InstanceTemplateModel.class).findById(templateId)).as("step 9: gone").isNull();
            HardDeletes.byId(Models.get(InstanceModel.class), workspaceId);
            McpHttpSession.Answer imageDeleted = admin.call("delete", Map.of("code", image));
            assertThat(imageDeleted.error()).as("step 9: " + imageDeleted.text()).isFalse();
            assertThat(Models.get(RuntimeImageModel.class).findById(imageId)).as("step 9: the image is gone")
                .isNull();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
            local.restore();
        }
    }

    /** @return a stopped app of the device-carrying fake kind on the host, its workload on the fake daemon */
    private static int deviceApp(String name, int hostId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeDeviceDaemon.Kind.ID.toString());
        row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/image")));
        row.set(InstanceModel.HOST_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        FakeDeviceDaemon.DAEMON.put(FakeDeviceDaemon.handleOf(id), new FakeDeviceDaemon.Workload());
        return id;
    }

    /** @return the export document an answer carries inside its untrusted block */
    private static String documentOf(String answer) {
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        assertThat(start).as("the answer carries a document: " + answer).isGreaterThanOrEqualTo(0);
        return answer.substring(start, end + 1);
    }

    /** @return the code a create answered, of the kind expected */
    private static String made(McpHttpSession session, String tool, Map<String, Object> arguments, String prefix,
                               String step) {
        McpHttpSession.Answer made = session.call(tool, arguments);
        assertThat(made.error()).as(step + ": " + tool + ": " + made.text()).isFalse();
        Matcher code = CODE.matcher(made.text());
        while (code.find()) {
            if (code.group(1).equals(prefix)) return code.group();
        }
        throw new AssertionError(step + ": " + tool + " answered no " + prefix + " code: " + made.text());
    }

    /** @return the key of the record a code names */
    private static int idOf(Identifier model, String code) {
        return Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(model, code), code));
    }
}
