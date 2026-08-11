package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Files tab's gate and its runtime honesty.
 *
 * AIDEV-NOTE: the pre-fix defects, verified 2026-08-11 -- ONE, {@code InstanceFilesPage}
 * declared NO {@code visibleFor} at all, so the tab rendered for every viewer of the
 * record and the refusal arrived as a red error banner from the service. TWO, an Incus
 * workload got the same banner ({@code files_unsupported}) rather than a named "not
 * available for this runtime yet" state, so a declared runtime asymmetry read as breakage.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceFilesTabGateTest extends HohenheimTestBase {

    private static Integer dockerInstanceId;
    private static Integer incusInstanceId;
    private static String consoleSession;
    private static String readerSession;

    @BeforeAll
    static void seed() {
        dockerInstanceId = instance("files-docker", "hohenheim:docker_container",
            ServerModel.localServerId());

        // An Incus host and an Incus workload: the runtime with no file lane.
        var servers = Models.get(ServerModel.class);
        Row incusHost = servers.createEmptyRow();
        incusHost.set(ServerModel.NAME, "files-incus-host");
        incusHost.set(ServerModel.RUNTIME, ServerModel.RUNTIME_INCUS);
        incusHost.set(ServerModel.SSH_TARGET, "nobody@files-incus-host.invalid");
        servers.save(incusHost);
        int incusHostId = servers.findByName("files-incus-host").get(ServerModel.ID);
        incusInstanceId = instance("files-incus", "hohenheim:incus_container", incusHostId);

        consoleSession = delegate("files-console@hohenheim.local", HohenheimAccess.CONSOLE);
        readerSession = delegate("files-reader@hohenheim.local", HohenheimAccess.FILES_READ);
    }

    private static int instance(String name, String kind, int serverId) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, kind);
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.SERVER_ID, serverId);
        instances.save(row);
        return row.get(InstanceModel.ID);
    }

    private static String delegate(String email, String capability) {
        Row user = AuthModels.users().createEmptyRow();
        user.set(UserModel.EMAIL, email);
        user.set(UserModel.DISPLAY_NAME, email);
        user.set(UserModel.ENABLED, true);
        user.set(UserModel.CREATED_AT, Instant.now());
        user.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(user);
        int userId = user.get(UserModel.ID);
        RecordGrants.grant("user", userId, InstanceModel.MODEL_ID, dockerInstanceId,
            capability, true);
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, (long) userId);
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);
        return session.token().secret();
    }

    /**
     * The tab is gated on {@code files.read}: a console delegate is neither offered it
     * nor able to reach it, while a files.read delegate gets both.
     */
    @Test
    @Order(1)
    void theFilesTabAnswersToFilesReadAndNotToMereViewership() throws Exception {
        String record = "/manage/instances/" + dockerInstanceId;

        // 1. The console delegate reaches the record -- without this the rest is vacuous.
        HttpResponse<String> asConsole = get(record, consoleSession);
        assertThat(asConsole.statusCode()).as("step 1: the console delegate sees the record")
            .isEqualTo(200);

        // 2. THE DEFECT: pre-fix the tab was offered here and the click produced an error
        //    banner instead of an honest absence.
        assertThat(asConsole.body())
            .withFailMessage("step 2: a console-only delegate is offered the files tab,"
                + " whose every operation refuses -- an affordance that can only fail")
            .doesNotContain("/page/files");
        assertThat(get(record + "/page/files", consoleSession).statusCode())
            .withFailMessage("step 2: and can reach the files route by hand")
            .isEqualTo(404);

        // 3. Positive anchor: a files.read delegate is offered it AND can open it, so
        //    step 2 is the gate rather than a deleted tab.
        HttpResponse<String> asReader = get(record, readerSession);
        assertThat(asReader.body()).as("step 3: a files.read delegate is offered the tab")
            .contains("/page/files");
        assertThat(get(record + "/page/files", readerSession).statusCode())
            .as("step 3: and can open it")
            .isEqualTo(200);
    }

    /**
     * A runtime with no file lane says so, instead of rendering a browser that answers
     * every request with a refusal banner.
     */
    @Test
    @Order(2)
    void anIncusWorkloadStatesTheRuntimeHasNoFileLaneYet() throws Exception {
        HttpResponse<String> page = get(
            "/admin/instances/" + incusInstanceId + "/page/files", sessionToken);
        assertThat(page.statusCode()).as("step 1: the tab renders for an operator")
            .isEqualTo(200);

        // 2. THE DEFECT: pre-fix this rendered the files_unsupported violation as a red
        //    error alert, which reads as breakage rather than as a declared asymmetry.
        assertThat(page.body())
            .withFailMessage("step 2: an Incus workload does not state that its runtime"
                + " has no file lane yet")
            .contains("data-files-unsupported");
        assertThat(page.body())
            .withFailMessage("step 2: and still renders the refusal as an error alert")
            .doesNotContain("<pl-alert variant=\"destructive\"");

        // 3. Positive anchor: the Docker workload still gets a real browser.
        assertThat(get("/admin/instances/" + dockerInstanceId + "/page/files", sessionToken)
                .body())
            .as("step 3: the Docker tier still browses")
            .doesNotContain("data-files-unsupported");
    }

    /**
     * Stats states its contract: live-only rings, plus the ONE persisted observation and
     * why a Docker workload has none.
     */
    @Test
    @Order(3)
    void statsStatesItsLiveOnlyContractAndThePersistedDiskObservation() throws Exception {
        String statsUrl = "/admin/instances/" + dockerInstanceId + "/page/stats";
        HttpResponse<String> page = get(statsUrl, sessionToken);
        assertThat(page.statusCode()).isEqualTo(200);

        // 1. The live-only contract is stated on the page, not left implied by an empty
        //    chart.
        assertThat(page.body())
            .withFailMessage("step 1: the stats page does not state that its readings are"
                + " live only and stored nowhere")
            .contains("data-stats-contract");

        // 2. THE DEFECT: the stored disk observation appeared on no stats page at all.
        assertThat(page.body())
            .withFailMessage("step 2: the persisted disk observation renders nowhere on"
                + " the stats page")
            .contains("data-disk-state=\"not-measured\"");

        // 3. Once observed it is a real bar, on the same page as the live rings.
        var instances = Models.get(InstanceModel.class);
        Row row = instances.findById(dockerInstanceId);
        row.set(InstanceModel.DISK_USED_BYTES, 1_000L);
        row.set(InstanceModel.DISK_LIMIT_BYTES, 4_000L);
        row.set(InstanceModel.DISK_OBSERVED_AT, Instant.now());
        instances.save(row);
        try {
            assertThat(get(statsUrl, sessionToken).body())
                .as("step 3: a stored observation renders beside the live rings")
                .contains("data-disk-state=\"measured\"");
        } finally {
            Row cleared = instances.findById(dockerInstanceId);
            cleared.set(InstanceModel.DISK_USED_BYTES, (Long) null);
            cleared.set(InstanceModel.DISK_LIMIT_BYTES, (Long) null);
            cleared.set(InstanceModel.DISK_OBSERVED_AT, (Instant) null);
            instances.save(cleared);
        }
    }

    // -- plumbing -----------------------------------------------------------------

    private HttpResponse<String> get(String path, String session) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + session)
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
