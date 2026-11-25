package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateFileModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.InstanceVolumeModel;
import be.elevenways.hohenheim.model.StackFileModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.test.application.FakeApps;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.instance.FakeHostVolumes;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.record.RecordScheduleModel;
import be.elevenways.zenit.common.task.record.RecordScheduleStepModel;
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

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An app's volumes, config files and schedules through the real MCP endpoint, as an administrator's key: a volume
 * declared, changed, listed and deleted; a config file set, listed, changed and removed, and a template's and a stack
 * service's set and listed by their owner; a schedule made with steps, listed by its app, changed, run, its run read
 * and deleted, and a back up step an app cannot take refused at save; then a customer's key refused what the customer
 * panel refuses him.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimAppPartToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-parts-";

    @Test
    void anAdministratorSetsAnAppsVolumesFilesAndSchedulesByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        HostFixtures.LocalHostState local = HostFixtures.captureLocal();
        try (FakeApps apps = FakeApps.install((SqlDatasource) Models.get(InstanceModel.class)
                .getResolvedDatasource())) {
            HostFixtures.makeLocalPlaceable(16L * 1024);
            // A volume is made on the host's volume storage, a directory here.
            FakeHostVolumes.install();
            cleanUp.addLast(FakeHostVolumes::uninstall);
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            int appId = HohenheimDataToolsTest.record(InstanceModel.class, cleanUp, row -> {
                row.set(InstanceModel.NAME, PREFIX + "app-" + tag);
                row.set(InstanceModel.KIND, "hohenheim:docker_container");
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
                row.set(InstanceModel.HOST_ID, HostModel.localHostId());
            });
            String appCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, appId, TenantConduits.operator());
            // An app built from its repository: its kind mounts declared volumes, which a container's does not.
            int gitId = HohenheimDataToolsTest.record(InstanceModel.class, cleanUp, row -> {
                row.set(InstanceModel.NAME, PREFIX + "git-" + tag);
                row.set(InstanceModel.KIND, ApplicationKind.ID.toString());
                row.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("repository_url",
                    "https://example.invalid/app.git", "branch", "main")));
                row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
                row.set(InstanceModel.HOST_ID, HostModel.localHostId());
            });
            String gitCode = HohenheimMcpTest.code(InstanceModel.MODEL_ID, gitId, TenantConduits.operator());

            // 1. A volume is declared on the built app, its size limit changed; its name is fixed; describe names
            //    its change; it is listed and deleted with its data.
            McpHttpSession.Answer lacking = admin.call("volume_set", Map.of("owner", gitCode, "name", "data"));
            assertThat(lacking.error()).as("step 1: " + lacking.text()).isTrue();
            assertThat(lacking.text()).as("step 1: what a create needs").contains("container_path");
            McpHttpSession.Answer declared = admin.call("volume_set", Map.of("owner", gitCode, "name", "data",
                "container_path", "/data", "size_limit_mb", 64));
            assertThat(declared.error()).as("step 1: " + declared.text()).isFalse();
            String volumeCode = codeIn(declared.text(), "VO");
            int volumeId = keyOf(InstanceVolumeModel.MODEL_ID, volumeCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceVolumeModel.class), volumeId));
            McpHttpSession.Answer grown = admin.call("volume_set", Map.of("code", volumeCode, "size_limit_mb", 128));
            assertThat(grown.error()).as("step 1: " + grown.text()).isFalse();
            assertThat(grown.text()).as("step 1: what changed").contains(volumeCode).contains("size_limit_mb");
            assertThat(row(InstanceVolumeModel.MODEL_ID, volumeId).get(InstanceVolumeModel.QUOTA_BYTES))
                .as("step 1: the new limit").isEqualTo(128L * 1024 * 1024);
            assertThat((String) row(InstanceVolumeModel.MODEL_ID, volumeId).get(InstanceVolumeModel.CONTAINER_PATH))
                .as("step 1: what was not sent stays").isEqualTo("/data");
            McpHttpSession.Answer renamed = admin.call("volume_set", Map.of("code", volumeCode, "name", "other"));
            assertThat(renamed.error()).as("step 1: a name is fixed: " + renamed.text()).isTrue();
            assertThat(renamed.text()).as("step 1: the refusal names it").contains("name");
            McpHttpSession.Answer bad = admin.call("volume_set", Map.of("code", volumeCode, "size_limit_mb", 0));
            assertThat(bad.error()).as("step 1: " + bad.text()).isTrue();
            assertThat(bad.text()).as("step 1: the refusal names it").contains("size_limit_mb");
            assertThat(admin.call("describe", Map.of("code", volumeCode)).text())
                .as("step 1: describe names the change").contains("volume_set code=" + volumeCode);
            assertThat(admin.call("list", Map.of("kind", "volume", "search", "data")).text())
                .as("step 1: listed").contains(volumeCode);
            McpHttpSession.Answer destroyed = admin.call("delete", Map.of("code", volumeCode));
            assertThat(destroyed.error()).as("step 1: " + destroyed.text()).isFalse();
            assertThat(Models.get(InstanceVolumeModel.class).findById(volumeId)).as("step 1: gone").isNull();

            // 2. A config file is set by its owner and path, listed by its app, its mode changed alone, a relative
            //    path refused by name, and removed.
            McpHttpSession.Answer file = admin.call("config_file_set", Map.of("owner", appCode,
                "path", "/etc/app.conf", "content", "token={{API_TOKEN}}", "mode", "0600"));
            assertThat(file.error()).as("step 2: " + file.text()).isFalse();
            assertThat(file.text()).as("step 2: never the content").contains("created")
                .doesNotContain("API_TOKEN");
            Row stored = fileAt(appId, "/etc/app.conf");
            Integer fileId = stored.get(InstanceFileModel.ID);
            cleanUp.push(() -> HardDeletes.byId(Models.get(InstanceFileModel.class), fileId));
            McpHttpSession.Answer listed = admin.call("list", Map.of("kind", "config_file", "of", appCode));
            assertThat(listed.error()).as("step 2: " + listed.text()).isFalse();
            assertThat(listed.text()).as("step 2: listed by its app").contains("/etc/app.conf");
            McpHttpSession.Answer moded = admin.call("config_file_set", Map.of("owner", appCode,
                "path", "/etc/app.conf", "mode", "0640"));
            assertThat(moded.error()).as("step 2: " + moded.text()).isFalse();
            Row changed = fileAt(appId, "/etc/app.conf");
            assertThat((String) changed.get(InstanceFileModel.MODE)).as("step 2: the new mode").isEqualTo("0640");
            assertThat((String) changed.get(InstanceFileModel.CONTENT)).as("step 2: the content stays")
                .isEqualTo("token={{API_TOKEN}}");
            McpHttpSession.Answer relative = admin.call("config_file_set", Map.of("owner", appCode,
                "path", "etc/other.conf"));
            assertThat(relative.error()).as("step 2: " + relative.text()).isTrue();
            assertThat(relative.text()).as("step 2: the refusal names it").contains("path");
            McpHttpSession.Answer removed = admin.call("config_file_set", Map.of("owner", appCode,
                "path", "/etc/app.conf", "remove", true));
            assertThat(removed.error()).as("step 2: " + removed.text()).isFalse();
            assertThat(Models.get(InstanceFileModel.class).find().where(InstanceFileModel.INSTANCE_ID.eq(appId))
                .count()).as("step 2: removed").isZero();
            assertThat(admin.call("describe", Map.of("code", appCode)).text())
                .as("step 2: describe names the call").contains("config_file_set");

            // 2b. A template's and a stack service's config files are set by their owner's code and listed by it.
            int templateId = HohenheimDataToolsTest.record(InstanceTemplateModel.class, cleanUp, row -> {
                row.set(InstanceTemplateModel.NAME, PREFIX + "template-" + tag);
                row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
                row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine", "tag",
                    "latest")));
            });
            String templateCode = HohenheimMcpTest.code(InstanceTemplateModel.MODEL_ID, templateId,
                TenantConduits.operator());
            int stackId = HohenheimDataToolsTest.record(StackModel.class, cleanUp, row -> {
                row.set(StackModel.NAME, PREFIX + "stack-" + tag);
                row.set(StackModel.HOST_ID, HostModel.localHostId());
            });
            int serviceId = HohenheimDataToolsTest.record(StackServiceModel.class, cleanUp, row -> {
                row.set(StackServiceModel.STACK_ID, stackId);
                row.set(StackServiceModel.NAME, "web");
                row.set(StackServiceModel.IMAGE, "alpine");
            });
            String serviceCode = HohenheimMcpTest.code(StackServiceModel.MODEL_ID, serviceId,
                TenantConduits.operator());
            for (List<String> owner : List.of(List.of(templateCode, "template_config_file"),
                    List.of(serviceCode, "stack_config_file"))) {
                McpHttpSession.Answer set = admin.call("config_file_set", Map.of("owner", owner.get(0),
                    "path", "/etc/owned.conf", "content", "x=1"));
                assertThat(set.error()).as("step 2b: " + set.text()).isFalse();
                McpHttpSession.Answer owned = admin.call("list", Map.of("kind", owner.get(1), "of", owner.get(0)));
                assertThat(owned.error()).as("step 2b: " + owned.text()).isFalse();
                assertThat(owned.text()).as("step 2b: listed by its owner").contains("/etc/owned.conf");
                McpHttpSession.Answer dropped = admin.call("config_file_set", Map.of("owner", owner.get(0),
                    "path", "/etc/owned.conf", "remove", true));
                assertThat(dropped.error()).as("step 2b: " + dropped.text()).isFalse();
            }
            assertThat(Models.get(InstanceTemplateFileModel.class).find()
                .where(InstanceTemplateFileModel.TEMPLATE_ID.eq(templateId)).count()).as("step 2b: removed").isZero();
            assertThat(Models.get(StackFileModel.class).find()
                .where(StackFileModel.STACK_SERVICE_ID.eq(serviceId)).count()).as("step 2b: removed").isZero();

            // 3. A schedule is made with two steps, a step given an entry its action does not take is refused by
            //    name, its cron and chain changed, run, its run read, and deleted.
            McpHttpSession.Answer stray = admin.call("schedule_set", Map.of("app", appCode, "cron", "0 3 * * *",
                "steps", List.of(Map.of("action", "stop", "command", "say hi"))));
            assertThat(stray.error()).as("step 3: " + stray.text()).isTrue();
            assertThat(stray.text()).as("step 3: the refusal names it").contains("steps");
            McpHttpSession.Answer made = admin.call("schedule_set", Map.of("app", appCode, "name", PREFIX + tag,
                "cron", "0 3 * * *", "timezone", "Europe/Brussels", "steps", List.of(
                    Map.of("action", "stop", "on_failure", "continue"),
                    Map.of("action", "console_command", "command", "say hi", "delay_seconds", 5))));
            assertThat(made.error()).as("step 3: " + made.text()).isFalse();
            String scheduleCode = codeIn(made.text(), "SC");
            int scheduleId = Integer.parseInt(Objects.requireNonNull(
                RecordCodes.keyOfCode(RecordScheduleModel.MODEL_ID, scheduleCode), scheduleCode));
            cleanUp.push(() -> HardDeletes.byId(Models.get(RecordScheduleModel.class), scheduleId));
            List<Row> chain = Models.get(RecordScheduleStepModel.class).findChain(scheduleId);
            assertThat(chain).as("step 3: two steps").hasSize(2);
            assertThat(String.valueOf(chain.get(1).get(RecordScheduleStepModel.INPUT)))
                .as("step 3: the console line reached its step").contains("say hi");
            assertThat((Integer) chain.get(1).get(RecordScheduleStepModel.OFFSET_SECONDS)).as("step 3: its delay")
                .isEqualTo(5);
            McpHttpSession.Answer rechained = admin.call("schedule_set", Map.of("code", scheduleCode,
                "cron", "30 4 * * *", "steps", List.of(Map.of("action", "stop"))));
            assertThat(rechained.error()).as("step 3: " + rechained.text()).isFalse();
            assertThat((String) row(RecordScheduleModel.MODEL_ID, scheduleId).get(RecordScheduleModel.CRON))
                .as("step 3: the new cron").isEqualTo("30 4 * * *");
            assertThat(Models.get(RecordScheduleStepModel.class).findChain(scheduleId)).as("step 3: one step")
                .hasSize(1);
            assertThat(admin.call("list", Map.of("kind", "schedule", "of", appCode)).text())
                .as("step 3: listed by its app").contains(scheduleCode);
            assertThat(admin.call("describe", Map.of("code", scheduleCode)).text())
                .as("step 3: describe names the calls").contains("schedule_set code=" + scheduleCode)
                .contains("schedule_run code=" + scheduleCode).contains("delete code=" + scheduleCode);
            McpHttpSession.Answer ran = admin.call("schedule_run", Map.of("code", scheduleCode));
            assertThat(ran.error()).as("step 3: " + ran.text()).isFalse();
            String runCode = codeIn(ran.text(), "SCR");
            McpHttpSession.Answer runRead = admin.call("read", Map.of("codes", List.of(runCode)));
            assertThat(runRead.error()).as("step 3: " + runRead.text()).isFalse();
            assertThat(runRead.text()).as("step 3: the run read").contains(runCode);
            assertThat(admin.call("list", Map.of("kind", "schedule_run", "of", scheduleCode)).text())
                .as("step 3: the schedule's runs").contains(runCode);
            McpHttpSession.Answer gone = admin.call("delete", Map.of("code", scheduleCode));
            assertThat(gone.error()).as("step 3: " + gone.text()).isFalse();
            assertThat(Models.get(RecordScheduleModel.class).findById(scheduleId)).as("step 3: deleted").isNull();

            // 4. A back up step on the app built from its repository is refused at save, naming steps, and nothing
            //    of the schedule stays.
            McpHttpSession.Answer never = admin.call("schedule_set", Map.of("app", gitCode, "cron", "0 3 * * *",
                "steps", List.of(Map.of("action", "back_up"))));
            assertThat(never.error()).as("step 4: " + never.text()).isTrue();
            assertThat(never.text()).as("step 4: the refusal names it").contains("steps");
            assertThat(Models.get(RecordScheduleModel.class).find()
                .where(RecordScheduleModel.RECORD_ID.eq(String.valueOf(gitId))).count())
                .as("step 4: nothing was kept").isZero();

            // 5. A customer managing both apps is refused a volume, as his panel offers him none, and may shape a
            //    schedule, as his panel does.
            UUID customerId = user(PREFIX + tag + "@surface.test", "Mcp Parts Customer");
            for (int managed : List.of(appId, gitId)) {
                RecordGrants.grant(GrantSubjectType.USER, customerId, InstanceModel.MODEL_ID, managed,
                    HohenheimCapabilities.MANAGE, true);
            }
            McpHttpSession customer = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Mcp Parts Customer");
            McpHttpSession.Answer his = customer.call("volume_set", Map.of("owner", gitCode, "name", "cache",
                "container_path", "/cache"));
            assertThat(his.error()).as("step 5: " + his.text()).isTrue();
            assertThat(Models.get(InstanceVolumeModel.class).find().where(InstanceVolumeModel.INSTANCE_ID.eq(gitId))
                .count()).as("step 5: no volume").isZero();
            McpHttpSession.Answer hisSchedule = customer.call("schedule_set", Map.of("app", appCode,
                "cron", "0 5 * * *", "steps", List.of(Map.of("action", "stop"))));
            assertThat(hisSchedule.error()).as("step 5: " + hisSchedule.text()).isFalse();
            String hisCode = codeIn(hisSchedule.text(), "SC");
            int hisId = Integer.parseInt(Objects.requireNonNull(
                RecordCodes.keyOfCode(RecordScheduleModel.MODEL_ID, hisCode), hisCode));
            cleanUp.push(() -> HardDeletes.byId(Models.get(RecordScheduleModel.class), hisId));
            assertThat(customer.call("delete", Map.of("code", hisCode)).error()).as("step 5: he deletes it")
                .isFalse();
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
            local.restore();
        }
    }

    /** @return the first code of the kind an answer names */
    private static String codeIn(String text, String prefix) {
        Matcher code = Pattern.compile("\\b" + prefix + "\\d+\\b").matcher(text);
        assertThat(code.find()).as("a " + prefix + " code in: " + text).isTrue();
        return code.group();
    }

    /** @return the key of the record a code names */
    private static int keyOf(Identifier model, String code) {
        return Integer.parseInt(Objects.requireNonNull(RecordCodes.keyOfCode(model, code), code));
    }

    private static Row row(Identifier model, int id) {
        return Objects.requireNonNull(Models.get(model).findById(id), "record " + id);
    }

    /** @return the app's config file at the path */
    private static Row fileAt(int appId, String path) {
        return Objects.requireNonNull(Models.get(InstanceFileModel.class).find()
            .where(InstanceFileModel.INSTANCE_ID.eq(appId)).where(InstanceFileModel.CONTAINER_PATH.eq(path)).first(),
            "a file at " + path);
    }
}
