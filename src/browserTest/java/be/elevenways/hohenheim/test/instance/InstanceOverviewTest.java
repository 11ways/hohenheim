package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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
 * The instance Overview tab: the machinery already stamped disk observations and port
 * claims, and no page rendered either.
 *
 * AIDEV-NOTE: the pre-fix defects, verified 2026-08-11 -- ONE, the overview URL answered
 * 404 and the list row title opened the five-column edit form. TWO,
 * {@code DISK_USED_BYTES}/{@code DISK_LIMIT_BYTES} were written by
 * {@code ObserveInstanceDisk} and read by {@code AttentionCollector} ALONE, so an
 * operator could never see how full a disk was until it crossed the attention threshold.
 * THREE, the port ledger's published port appeared on no instance surface at all.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceOverviewTest extends HohenheimTestBase {

    private static Integer instanceId;

    private int instance() {
        if (instanceId != null) {
            return instanceId;
        }
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, "overview-instance");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "command", "sleep 60"));
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        instanceId = row.get(InstanceModel.ID);
        return instanceId;
    }

    private String overviewUrl() {
        return "/admin/instances/" + instance() + "/page/overview";
    }

    /**
     * The page exists, is the list row's target, and carries the power controls as the
     * resource's OWN actions -- not a hand-rolled form beside them.
     */
    @Test
    @Order(1)
    void theOverviewIsTheRecordsLandingPageAndCarriesTheResourcesOwnActions()
            throws Exception {
        // 1. THE ABSENCE: pre-fix this URL answered 404.
        HttpResponse<String> page = get(overviewUrl());
        assertThat(page.statusCode())
            .withFailMessage("step 1: the instance overview page does not exist (HTTP %s)",
                page.statusCode())
            .isEqualTo(200);

        // 2. The list row title opens it, so the operator lands on state rather than on
        //    a five-column edit form.
        HttpResponse<String> list = get("/admin/instances");
        assertThat(list.body())
            .withFailMessage("step 2: the instance list row does not target the overview")
            .contains("/admin/instances/" + instanceId + "/page/overview");

        // 3. Power rides the resource's declared actions, translated through the standard
        //    invoke lane -- so their confirmations and capability gates come along.
        assertThat(page.body()).as("step 3: the action block renders")
            .contains("data-instance-actions");
        assertThat(page.body())
            .withFailMessage("step 3: the deploy action is not projected onto the page")
            .contains("/action/deploy_instance");
        assertThat(page.body())
            .withFailMessage("step 3: invoke targets do not carry _return, so a refresh"
                + " result would leave the page the operator is on")
            .contains("_return");
    }

    /**
     * RESTART is one verb through the service, and it is the SAME composition the
     * scheduled power action runs.
     */
    @Test
    @Order(2)
    void restartIsOfferedAsOneActionThroughTheService() throws Exception {
        HttpResponse<String> page = get(overviewUrl());
        assertThat(page.body())
            .withFailMessage("step 1: no restart action exists -- an operator would have"
                + " to press stop and then deploy by hand")
            .contains("/action/restart_instance");

        // 2. And the confirmation travels with it (it stops a running workload).
        assertThat(page.body()).as("step 2: the restart action is confirmed")
            .contains("restart_instance");
    }

    /**
     * Disk is an EXPLICIT not-measured state on a runtime that measures nothing, and a
     * real bar the moment an observation lands. Never a zero bar reading as "empty disk".
     */
    @Test
    @Order(3)
    void diskRendersNotMeasuredForDockerAndARealBarOnceObserved() throws Exception {
        var instances = Models.get(InstanceModel.class);

        // 1. Docker stamps nothing: the honest rendering is a named state, not a bar.
        HttpResponse<String> unmeasured = get(overviewUrl());
        assertThat(unmeasured.body())
            .withFailMessage("step 1: an unmeasured disk does not render an explicit state")
            .contains("data-disk-state=\"not-measured\"");
        assertThat(unmeasured.body())
            .withFailMessage("step 1: a zero usage bar rendered over a disk nobody"
                + " measured -- that reads as an empty disk")
            .doesNotContain("<pl-usage-bar");

        // 2. Stamp the observation the disk sweeper writes. THE DEFECT: pre-fix these
        //    two columns reached no page at all.
        Row row = instances.findById(instance());
        row.set(InstanceModel.DISK_USED_BYTES, 3_221_225_472L);
        row.set(InstanceModel.DISK_LIMIT_BYTES, 4_294_967_296L);
        row.set(InstanceModel.DISK_OBSERVED_AT, Instant.parse("2026-08-11T08:00:00Z"));
        instances.save(row);
        try {
            HttpResponse<String> measured = get(overviewUrl());
            assertThat(measured.body())
                .withFailMessage("step 2: a stored disk observation still renders nowhere")
                .contains("data-disk-state=\"measured\"")
                .contains("<pl-usage-bar");
            assertThat(measured.body()).as("step 2: with the stored numbers")
                .contains("3221225472").contains("4294967296");
            assertThat(measured.body()).as("step 2: and the observation's own timestamp")
                .contains("2026-08-11T08:00:00Z");

            // 3. A limit of 0 rations nothing, so it falls back to not-measured rather
            //    than dividing by it.
            Row unenforced = instances.findById(instanceId);
            unenforced.set(InstanceModel.DISK_LIMIT_BYTES, 0L);
            instances.save(unenforced);
            assertThat(get(overviewUrl()).body())
                .as("step 3: a zero ceiling is silence, never a full bar")
                .contains("data-disk-state=\"not-measured\"");
        } finally {
            Row cleared = instances.findById(instanceId);
            cleared.set(InstanceModel.DISK_USED_BYTES, (Long) null);
            cleared.set(InstanceModel.DISK_LIMIT_BYTES, (Long) null);
            cleared.set(InstanceModel.DISK_OBSERVED_AT, (Instant) null);
            instances.save(cleared);
        }
    }

    /**
     * The published port reaches a page, joined to the host address -- and when the host
     * declares no public address the page SAYS so instead of inventing localhost.
     */
    @Test
    @Order(4)
    void theLedgersPublishedPortRendersWithItsHostAddress() throws Exception {
        int id = instance();
        int localHost = ServerModel.localServerId();
        PortLedger.claim(localHost, null, 25565, "tcp", InstanceModel.MODEL_ID, id,
            "overview test");
        try {
            // 1. THE DEFECT: pre-fix the ledger's claim appeared on no instance surface.
            HttpResponse<String> page = get(overviewUrl());
            assertThat(page.body())
                .withFailMessage("step 1: the instance's published port renders nowhere")
                .contains("data-endpoint-port=\"25565\"");

            // 2. The host declares no public IP in this harness, so the page says so
            //    rather than printing a reachable-looking localhost.
            Row server = Models.get(ServerModel.class).findById(localHost);
            String declared = server.get(ServerModel.PUBLIC_IPV4);
            if (declared == null || declared.isBlank()) {
                assertThat(page.body())
                    .withFailMessage("step 2: a port was rendered with no address and no"
                        + " statement that the host declares none")
                    .contains("data-endpoint-noaddress");
            }

            // 3. Declare one: the address now joins the port.
            server.set(ServerModel.PUBLIC_IPV4, "203.0.113.7");
            Models.get(ServerModel.class).save(server);
            try {
                assertThat(get(overviewUrl()).body())
                    .as("step 3: the declared host address joins the published port")
                    .contains("203.0.113.7:25565");
            } finally {
                Row restore = Models.get(ServerModel.class).findById(localHost);
                restore.set(ServerModel.PUBLIC_IPV4, declared);
                Models.get(ServerModel.class).save(restore);
            }
        } finally {
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, id);
        }

        // 4. With the claim gone the page states the absence instead of an empty table.
        assertThat(get(overviewUrl()).body())
            .as("step 4: no claim renders an explicit empty state")
            .contains("<pl-empty-state");
    }

    // -- plumbing -----------------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
