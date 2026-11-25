package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.task.TaskRuntime;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An access rule moved among its siblings, a change taken back, system task runs asked to stop or pause and a file
 * handed in, through the real MCP endpoint as an administrator's key: each done, read back, and refused a wrong call
 * by the argument it names or in the words the panel says.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class HohenheimMoveUndoTaskToolsTest extends HohenheimHttpTestBase {

    private static final String PREFIX = "mcp-move-";

    @Test
    void anAdministratorMovesRulesUndoesHisChangeAndAsksTaskRunsByToolsAlone() throws Exception {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        String tag = HohenheimMcpTest.letters();
        Deque<Runnable> cleanUp = new ArrayDeque<>();
        try {
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");

            // 1. A list with three rules, each after the one added before it.
            McpHttpSession.Answer list = admin.call("access_list_set", Map.of("name", PREFIX + "list-" + tag));
            assertThat(list.error()).as("step 1: " + list.text()).isFalse();
            String listCode = HohenheimAppPartToolsTest.codeIn(list.text(), "AL");
            int listId = HohenheimAppPartToolsTest.keyOf(AccessListModel.MODEL_ID, listCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(AccessListModel.class), listId));
            List<String> codes = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (String network : List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) {
                McpHttpSession.Answer rule = admin.call("access_rule_set", Map.of("access_list", listCode,
                    "kind", "ip_allow", "network", network));
                assertThat(rule.error()).as("step 1: " + rule.text()).isFalse();
                codes.add(HohenheimAppPartToolsTest.codeIn(rule.text(), "RU"));
                ids.add(HohenheimAppPartToolsTest.keyOf(AccessRuleModel.MODEL_ID, codes.getLast()));
            }
            assertThat(order(ids)).as("step 1: in the order they were added").containsExactly(0, 1, 2);

            // 2. The last one moved up, then the first one down: read back in their new order.
            McpHttpSession.Answer up = admin.call("access_rule_move", Map.of("code", codes.get(2), "direction", "up"));
            assertThat(up.error()).as("step 2: " + up.text()).isFalse();
            assertThat(up.text()).as("step 2: the answer names the rule").contains(codes.get(2));
            assertThat(order(ids)).as("step 2: the third before the second").containsExactly(0, 2, 1);
            McpHttpSession.Answer down = admin.call("access_rule_move", Map.of("code", codes.get(0),
                "direction", "down"));
            assertThat(down.error()).as("step 2: " + down.text()).isFalse();
            assertThat(order(ids)).as("step 2: the first after the third").containsExactly(2, 0, 1);

            // 3. A move past either end is refused in the tree's words, and the order stays.
            McpHttpSession.Answer pastEnd = admin.call("access_rule_move", Map.of("code", codes.get(1),
                "direction", "down"));
            assertThat(pastEnd.error()).as("step 3: past the end refused").isTrue();
            assertThat(pastEnd.text()).as("step 3: in words").contains("This item is already last");
            McpHttpSession.Answer pastTop = admin.call("access_rule_move", Map.of("code", codes.get(2),
                "direction", "up"));
            assertThat(pastTop.error()).as("step 3: past the top refused").isTrue();
            assertThat(pastTop.text()).as("step 3: in words").contains("This item is already first");
            assertThat(order(ids)).as("step 3: the order stays").containsExactly(2, 0, 1);

            // 4. A direction it does not take, or none, is refused by the argument's name.
            assertThat(admin.call("access_rule_move", Map.of("code", codes.get(1), "direction", "sideways")).text())
                .as("step 4: a wrong direction").contains("direction");
            assertThat(admin.call("access_rule_move", Map.of("code", codes.get(1))).text())
                .as("step 4: no direction").contains("direction");

            // 5a. A change that kept nothing to undo says so in words: an access list keeps no revisions, so its
            //     satisfy changed under the caller's own request id leaves nothing an undo takes back, and with
            //     activity recording off no action of it at all.
            String listRequest = UUID.randomUUID().toString();
            assertThat(admin.call("access_list_set", Map.of("code", listCode, "satisfy", "all",
                "request", listRequest)).error()).as("step 5a: satisfy changed").isFalse();
            assertThat(satisfy(listId)).as("step 5a: all must pass").isEqualTo(AccessListModel.SATISFY_ALL);
            McpHttpSession.Answer keptNothing = admin.call("undo", Map.of("change", listRequest));
            assertThat(keptNothing.error()).as("step 5a: " + keptNothing.text()).isTrue();
            assertThat(keptNothing.text()).as("step 5a: in words, saying why")
                .contains("This change kept nothing to undo").contains(HistoryTestSupport.recordsOptional()
                    ? "nothing it changed keeps the history" : "activity recording is switched off");
            assertThat(satisfy(listId)).as("step 5a: still all").isEqualTo(AccessListModel.SATISFY_ALL);

            // 5b. An app keeps its revisions: renamed under the caller's own request id, a stranger is refused its
            //     undo as not his, its maker takes it back by it, an unknown change and a second undo are refused,
            //     and the app reads as before.
            McpHttpSession.Answer made = admin.call("site_dev_namespace_set", Map.of("name", PREFIX + "dev-" + tag,
                "registration_token", "zdev_" + tag));
            assertThat(made.error()).as("step 5b: " + made.text()).isFalse();
            String siteCode = HohenheimAppPartToolsTest.codeIn(made.text(), "S");
            int siteId = HohenheimAppPartToolsTest.keyOf(SiteModel.MODEL_ID, siteCode);
            cleanUp.push(() -> HardDeletes.byId(Models.get(SiteModel.class), siteId));
            String request = UUID.randomUUID().toString();
            McpHttpSession.Answer renamed = admin.call("site_dev_namespace_set", Map.of("code", siteCode,
                "name", PREFIX + "dev2-" + tag, "request", request));
            assertThat(renamed.error()).as("step 5b: " + renamed.text()).isFalse();
            assertThat(siteName(siteId)).as("step 5b: renamed").isEqualTo(PREFIX + "dev2-" + tag);
            UUID strangerId = user(PREFIX + UUID.randomUUID() + "@surface.test", "Mcp Stranger");
            McpHttpSession stranger = HohenheimMcpTest.presetSession(server, endpoint, strangerId, "Mcp Stranger");
            McpHttpSession.Answer notHis = stranger.call("undo", Map.of("change", request));
            assertThat(notHis.error()).as("step 5b: " + notHis.text()).isTrue();
            assertThat(notHis.text()).as("step 5b: not his").contains("Only the person who made this change");
            assertThat(siteName(siteId)).as("step 5b: still renamed").isEqualTo(PREFIX + "dev2-" + tag);
            McpHttpSession.Answer undone = admin.call("undo", Map.of("change", request));
            assertThat(undone.error()).as("step 5b: " + undone.text()).isFalse();
            assertThat(undone.text()).as("step 5b: the undo's own words").containsIgnoringCase("undone");
            assertThat(siteName(siteId)).as("step 5b: THE ASSERTION -- the name is back")
                .isEqualTo(PREFIX + "dev-" + tag);
            McpHttpSession.Answer unknown = admin.call("undo", Map.of("change", UUID.randomUUID().toString()));
            assertThat(unknown.error()).as("step 5b: an unknown change").isTrue();
            assertThat(unknown.text()).as("step 5b: in words").contains("This change cannot be undone here");
            McpHttpSession.Answer nothing = admin.call("undo", Map.of());
            assertThat(nothing.error()).as("step 5b: no change named").isTrue();
            assertThat(nothing.text()).as("step 5b: refused by its name").contains("change");
            McpHttpSession.Answer again = admin.call("undo", Map.of("change", request));
            assertThat(again.error()).as("step 5b: a second undo of it").isTrue();
            assertThat(again.text()).as("step 5b: in words").contains("can no longer be undone");
            assertThat(siteName(siteId)).as("step 5b: still as before").isEqualTo(PREFIX + "dev-" + tag);

            // 6. Two system task runs, named by their codes in the list: the open one is no task that pauses and this
            //    app runs no task service, so a pause, a resume and a stop are refused in the panel's words; the ended
            //    one is refused a stop; a pause without paused is refused by its name.
            assertThat(TaskRuntime.service()).as("step 6: the served app runs no task service").isNull();
            Model runs = Models.get(SystemTaskHistoryModel.MODEL_ID);
            if (runs == null) {
                SystemTaskHistoryModel registered = new SystemTaskHistoryModel(Datasources.getDefault());
                Models.registerInstance(registered);
                cleanUp.push(() -> Models.unregisterInstance(registered));
                runs = registered;
            }
            String open = runCode(runs, PREFIX + "open-" + tag, false, cleanUp);
            String ended = runCode(runs, PREFIX + "ended-" + tag, true, cleanUp);
            McpHttpSession.Answer listed = admin.call("list", Map.of("kind", "task_run", "search", PREFIX));
            assertThat(listed.error()).as("step 6: " + listed.text()).isFalse();
            assertThat(listed.text()).as("step 6: both runs by their codes").contains(open).contains(ended);
            McpHttpSession.Answer paused = admin.call("task_run_pause", Map.of("code", open, "paused", true));
            assertThat(paused.error()).as("step 6: " + paused.text()).isTrue();
            assertThat(paused.text()).as("step 6: its task cannot pause").contains("cannot be paused");
            McpHttpSession.Answer resumed = admin.call("task_run_pause", Map.of("code", open, "paused", false));
            assertThat(resumed.error()).as("step 6: " + resumed.text()).isTrue();
            assertThat(resumed.text()).as("step 6: it is not paused").contains("not paused");
            McpHttpSession.Answer unsaid = admin.call("task_run_pause", Map.of("code", open));
            assertThat(unsaid.error()).as("step 6: no paused given").isTrue();
            assertThat(unsaid.text()).as("step 6: paused named").contains("paused");
            McpHttpSession.Answer stop = admin.call("task_run_end", Map.of("code", open));
            assertThat(stop.error()).as("step 6: " + stop.text()).isTrue();
            assertThat(stop.text()).as("step 6: no task service here").contains("Tasks are not running here");
            assertThat(admin.call("task_run_end", Map.of("code", ended)).error()).as("step 6: an ended run").isTrue();
            assertThat(admin.call("task_run_end", Map.of("code", listCode)).error()).as("step 6: no run").isTrue();

            // 7. A file is handed in: the slot's reference and URL come back, the URL takes the bytes once.
            McpHttpSession.Answer slot = admin.call("request_upload_path", Map.of("filename", "notes.txt"));
            assertThat(slot.error()).as("step 7: " + slot.text()).isFalse();
            assertThat(slot.text()).as("step 7: a reference").containsPattern("reference: U[A-Z0-9]{8}");
            String url = slot.text().lines().filter(line -> line.startsWith("upload_url: ")).findFirst()
                .orElseThrow().substring("upload_url: ".length());
            URI upload = URI.create(baseUrl() + URI.create(url).getRawPath());
            HttpClient client = HttpClient.newHttpClient();
            assertThat(post(client, upload)).as("step 7: the bytes are taken").isEqualTo(200);
            assertThat(post(client, upload)).as("step 7: a used slot takes no second file").isEqualTo(404);
        } finally {
            while (!cleanUp.isEmpty()) cleanUp.pop().run();
        }
    }

    /** @return each rule's index in the order given, as the list orders them now */
    private static List<Integer> order(List<Integer> ids) {
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort((left, right) -> Integer.compare(HohenheimSiteAccessToolsTest.sortOf(left),
            HohenheimSiteAccessToolsTest.sortOf(right)));
        return sorted.stream().map(ids::indexOf).toList();
    }

    private static String satisfy(int listId) {
        return HohenheimAppPartToolsTest.row(AccessListModel.MODEL_ID, listId).get(AccessListModel.SATISFY);
    }

    private static String siteName(int siteId) {
        return HohenheimAppPartToolsTest.row(SiteModel.MODEL_ID, siteId).get(SiteModel.NAME);
    }

    /** @return the code of a run of a task that cannot pause, as the scheduler stores one, saying the message */
    private static String runCode(Model runs, String message, boolean ended, Deque<Runnable> cleanUp) {
        Row run = runs.createEmptyRow();
        run.set(SystemTaskHistoryModel.TASK_TYPE, VerifyWorkloadIsolation.ID.toString());
        run.set(SystemTaskHistoryModel.STATUS, (ended ? TaskStatus.COMPLETED : TaskStatus.STARTED).name());
        run.set(SystemTaskHistoryModel.STARTED_AT, Now.instant());
        if (ended) run.set(SystemTaskHistoryModel.ENDED_AT, Now.instant());
        run.set(SystemTaskHistoryModel.PROGRESS_MESSAGE, message);
        runs.save(run);
        long id = run.get(SystemTaskHistoryModel.ID);
        cleanUp.push(() -> runs.deleteAll(List.of(id)));
        return Objects.requireNonNull(RecordCodes.codeUnjudged(SystemTaskHistoryModel.MODEL_ID, String.valueOf(id)),
            "the run's code");
    }

    private static int post(HttpClient client, URI upload) throws Exception {
        return client.send(HttpRequest.newBuilder(upload).header("Content-Type", "text/plain")
            .header("X-Filename", "notes.txt").POST(HttpRequest.BodyPublishers.ofString("hand-in"))
            .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
