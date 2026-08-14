package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.cms.InstanceResource;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.cms.common.action.RowAction;
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
     * RESTART is one verb through the service, and the confirmation the resource declares
     * travels onto this page with it.
     */
    @Test
    @Order(2)
    void restartIsOfferedAsOneConfirmedActionThroughTheService() throws Exception {
        HttpResponse<String> page = get(overviewUrl());

        // 1. The action is projected onto the page, through the standard invoke lane.
        assertThat(page.body())
            .withFailMessage("step 1: no restart action exists -- an operator would have"
                + " to press stop and then deploy by hand")
            .contains("/action/restart_instance");

        // 2. THE CONFIRMATION, asserted on the declaration the page renders rather than on
        //    a substring of step 1: a restart stops a running workload, so the affordance
        //    must ask first. (Pre-fix this step asserted contains("restart_instance"),
        //    which is a substring of step 1's own target and could never fail.)
        RowAction.Invoke<Row> restart = restartAction();
        assertThat(restart.confirmation())
            .withFailMessage("step 2: the restart action declares no confirmation, so the"
                + " rendered button stops a running workload on a single click")
            .isNotNull();
        assertThat(restart.confirmation().body().key())
            .as("step 2: and the dialog asks the restart question by name")
            .isEqualTo("restart_confirm");

        // 3. And the projection onto THIS page keeps it: the rendered control carries the
        //    declared confirmation, not just the target URL. It rides the markup as the
        //    microcopy KEY (the CmsConfirm directive resolves the copy client-side), so
        //    the key is what a server-rendered body can be asked for.
        assertThat(page.body())
            .withFailMessage("step 3: the rendered restart control does not carry the"
                + " confirmation the resource declared")
            .contains("restart_confirm");
    }

    /** The declared restart row action, which the overview page projects. */
    private static RowAction.Invoke<Row> restartAction() {
        for (RowAction<Row> action : new InstanceResource().rowActions()) {
            if (action instanceof RowAction.Invoke<Row> invoke
                    && "restart_instance".equals(invoke.id().getPath())) {
                return invoke;
            }
        }
        throw new AssertionError("InstanceResource declares no restart_instance action");
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
            //    rather than printing a reachable-looking localhost. The precondition is
            //    PINNED, never branched on: a harness host that ever gained a public IPv4
            //    would silently stop testing the no-address rendering altogether.
            Row server = Models.get(ServerModel.class).findById(localHost);
            String declared = server.get(ServerModel.PUBLIC_IPV4);
            assertThat(declared == null ? "" : declared.strip())
                .withFailMessage("step 2: the fixture host must declare NO public IPv4 for"
                    + " the no-address rendering to be under test (found '%s')", declared)
                .isEmpty();
            assertThat(page.body())
                .withFailMessage("step 2: a port was rendered with no address and no"
                    + " statement that the host declares none")
                .contains("data-endpoint-noaddress");

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

    /**
     * A refused deploy leaves a DURABLE explanation on the record, not a one-shot toast.
     *
     * AIDEV-NOTE: step 4 is the whole point and is the assertion a flash toast can never
     * satisfy -- the session bucket is emptied by the read, so a reload, a second operator
     * or the same operator tomorrow used to get nothing at all. Before this, the overview
     * of an instance whose host was not admitted was byte-identical to one whose host was
     * fine: state "Created", Deploy offered, no reason anywhere.
     */
    @Test
    @Order(5)
    void aBlockedHostIsStatedOnTheOverviewAndNotOnlyToasted() throws Exception {
        var servers = Models.get(ServerModel.class);
        int serverId = ServerModel.localServerId();
        Row server = servers.findById(serverId);
        String admissionBefore = server.get(ServerModel.ADMISSION);

        try {
            // 1. THE NEGATIVE ANCHOR: a FULLY placeable host, so the page says nothing
            //    about blockers. Without this the next step cannot distinguish "explains
            //    the block" from "always shows a warning" -- and admission alone is not
            //    enough: the gate also asks for identity, posture, preflight and contact.
            HostFixtures.admitLocal();
            assertThat(get(overviewUrl()).body())
                .as("step 1: an admitted host produces no blocker banner")
                .doesNotContain("data-deploy-blocked");

            // 2. Block the host and change NOTHING else.
            server = servers.findById(serverId);
            server.set(ServerModel.ADMISSION, ServerModel.ADMISSION_BLOCKED);
            servers.save(server);

            String blocked = get(overviewUrl()).body();
            assertThat(blocked)
                .withFailMessage("step 2: the overview does not state the blocking condition,"
                    + " so the only explanation is the toast that follows the click")
                .contains("data-deploy-blocked");

            // 3. It points at the host whose preflight/admit fixes it -- an explanation
            //    with no lever is only half an answer.
            assertThat(blocked)
                .as("step 3: and links to the host that must be fixed")
                .contains("/admin/servers/" + serverId + "/page/overview");

            // 4. Deploy is STILL offered: keep-and-explain, pinned against a later
            //    well-meaning hide. The button is what proves the refusal is about the
            //    host rather than about the operator's own authority.
            assertThat(blocked)
                .as("step 4: the deploy control is still offered")
                .contains("/action/deploy_instance");

            // 5. THE DURABILITY: ask again, with no action in between. A toast is gone by
            //    now; this must not be.
            assertThat(get(overviewUrl()).body())
                .withFailMessage("step 5: the explanation did not survive a second render --"
                    + " it is action-scoped after all, which is the defect being fixed")
                .contains("data-deploy-blocked");
        } finally {
            Row restore = servers.findById(serverId);
            restore.set(ServerModel.ADMISSION, admissionBefore);
            servers.save(restore);
        }
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
