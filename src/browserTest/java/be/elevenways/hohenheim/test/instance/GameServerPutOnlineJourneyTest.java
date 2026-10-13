package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.game.GameAddresses;
import be.elevenways.hohenheim.server.game.GameTemplateSeeder;
import be.elevenways.hohenheim.test.DnsFixtures;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.InstanceRowCleanup;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * "Put something online" for a game server: a template that publishes ports of its own is put online through a Ports
 * step instead of an address and HTTPS, its host ports are claimed before its first start, its game address gets an
 * SRV and an A record in the zone that covers it, and its Overview says where players connect. A website template
 * keeps its address and HTTPS steps.
 *
 * AIDEV-NOTE: the workload runs on the hermetic FakeDockerDaemon (installContainerKind), never on the machine's own
 * Docker: the seeded template's image is a real Minecraft server.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class GameServerPutOnlineJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "game-put-online-";

    /** The managed zone the game address lives in. */
    private static final String ORIGIN = "game-put-online.test";

    /** The documentation address the host declares, which the A record carries. */
    private static final String HOST_IPV4 = "203.0.113.25";

    private static FakeDockerDaemon daemon;
    private static int hostId;
    private static int zoneId;
    private static Row minecraft;
    private static int holderId;

    @BeforeAll
    static void seed() {
        Seeds.run(Datasources.getDefault(), new GameTemplateSeeder());
        minecraft = Models.get(InstanceTemplateModel.class).find()
            .where(InstanceTemplateModel.NAME.eq("Minecraft (Paper)")).first();
        daemon = new FakeDockerDaemon();
        daemon.install();
        daemon.installContainerKind();

        hostId = HostFixtures.admittedDockerHost(PREFIX + "host", 8192);
        Row host = Models.get(ServerModel.class).findById(hostId);
        host.set(ServerModel.PUBLIC_IPV4, HOST_IPV4);
        Models.get(ServerModel.class).save(host);

        zoneId = DnsFixtures.createZone(ORIGIN, DnsZoneModel.ROLE_PRIMARY, null);
        DnsZoneStore.INSTANCE.reload();

        // Another owner holding a public host port on the same host, as another game server would.
        Row holder = Models.get(InstanceModel.class).createEmptyRow();
        holder.set(InstanceModel.NAME, PREFIX + "holder");
        holder.set(InstanceModel.KIND, "hohenheim:docker_container");
        holder.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        holder.set(InstanceModel.SERVER_ID, hostId);
        holder.set(InstanceModel.SETTINGS, Map.of("image", "alpine", "tag", "latest"));
        Models.get(InstanceModel.class).save(holder);
        holderId = holder.get(InstanceModel.ID);
    }

    @AfterAll
    static void cleanUp() {
        try {
            for (Row instance : Models.get(InstanceModel.class).find()
                    .where(InstanceModel.SERVER_ID.eq(hostId)).all()) {
                int id = instance.get(InstanceModel.ID);
                GameAddresses.set(id, null);
                PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, id);
                InstanceRowCleanup.delete(id);
            }
            HardDeletes.byId(Models.get(ServerModel.class), hostId);
        } finally {
            FakeDockerDaemon.restore();
            daemon.close();
        }
    }

    /**
     * A game server goes online from the chooser: its wizard asks where it runs and its ports, never an address or
     * HTTPS; one run creates it, claims its game port, points its game address here and starts it; its Overview
     * says where players connect.
     */
    @Test
    void aGameServerGoesOnlineOnItsOwnPortWithItsGameAddress() throws Exception {
        AccessContext operator = TenantConduits.operator();
        assertThat(minecraft).as("the standalone Minecraft template is seeded").isNotNull();
        int templateId = minecraft.get(InstanceTemplateModel.ID);

        // 1. The chooser offers the game server as a family of its own, and Continue opens THAT template, never the
        //    behind-the-proxy backend whose name it shares a word with.
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: the chooser offers the standalone game server with its own card line")
            .contains("A Minecraft server players connect to directly, on its own port.");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=template:Minecraft").headers()
                .firstValue("location").orElse(""))
            .as("step 1: its card opens the standalone template's wizard")
            .contains("template=" + templateId);

        // 2. The wizard asks where it runs, its options and its Ports: one entry for the game port and the game
        //    address; no address of the proxy and no HTTPS step.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?template=" + templateId).body();
        assertThat(wizard).as("step 2: where and ports")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"ports\"");
        assertThat(wizard).as("step 2: no HTTPS step and no address")
            .doesNotContain("data-zf-step=\"https\"")
            .doesNotContain("name=\"" + PutOnline.HOSTNAME.getName() + "\"");
        assertThat(wizard).as("step 2: the Ports step asks the game port's host port and the game address")
            .contains("name=\"" + PutOnline.PORTS + ".game\"")
            .contains("name=\"" + PutOnline.GAME_ADDRESS.getName() + "\"");
        assertThat(wizard).as("step 2: the game port's help names its container port in words, never a key")
            .contains("The host port for its 25565/tcp. Empty: Hohenheim picks a free one.")
            .doesNotContain("outcome_port_host_help");
        assertThat(wizard).as("step 2: the lead speaks of ports and a game address, never of a certificate")
            .contains("publishes its ports")
            .doesNotContain("Creates the app, its address and its certificate in one go.");

        // 3. Put online with the game port left empty (Hohenheim picks) and a game address in the managed zone. The
        //    run makes the app, claims the port and writes the records: five steps, install skipped. Its first start
        //    is the last step and is refused here: the template is ready on a console line and the hermetic daemon
        //    has no console lane (console_unsupported). The run still ends, saying so, with everything before the
        //    start in place, which is what an operator finds on a host whose daemon refuses the start.
        String gameAddress = "mc." + ORIGIN;
        Map<String, Object> form = serviceForm(PREFIX + "survival", "", gameAddress);
        OperationRun run = PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                OperationRequest.of(PutOnline.PUT_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(operator).subjects(List.of(minecraft)).form(form))),
            operator, "step 3");
        assertThat(run.status()).as("step 3: the run succeeded (%s)", run.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(Objects.requireNonNull(run.steps()).journeyBefore())
            .as("step 3: the run ends the journey the wizard walked: what, where, options, ports").hasSize(4);
        assertThat(List.of(run.skipped(1), run.skipped(2), run.skipped(3)))
            .as("step 3: the template has no install step; its ports and its game address were done")
            .containsExactly(true, false, false);
        assertThat(Objects.requireNonNull(run.outcome()).key()).as("step 3: its start was refused, and it says so")
            .isEqualTo("outcome_not_started");

        // 4. Its settings name the host port the ledger claimed for the game port, a held public claim on its host.
        Row app = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "survival")).first();
        assertThat(app).as("step 4: the game server exists").isNotNull();
        int appId = app.get(InstanceModel.ID);
        List<PublishedPort> published = PublishedPort.of(InstanceModel.settingsOf(app));
        assertThat(published).as("step 4: one published port, the game port").hasSize(1);
        Integer hostPort = published.getFirst().hostPort();
        assertThat(hostPort).as("step 4: with a concrete host port").isNotNull();
        assertThat(published.getFirst().spec()).as("step 4: in Docker's spelling, on the template's container port")
            .isEqualTo(hostPort + ":25565/tcp");
        assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, appId))
            .as("step 4: the ledger holds that port publicly on its host for it")
            .anySatisfy(claim -> {
                assertThat((Integer) claim.get(PortAllocationModel.PORT)).isEqualTo(hostPort);
                assertThat((String) claim.get(PortAllocationModel.HOST_IP)).isEmpty();
                assertThat((Integer) claim.get(PortAllocationModel.SERVER_ID)).isEqualTo(hostId);
                assertThat(PortLedger.isReleasing(claim)).isFalse();
            });

        // 5. The game address is stored and generated an SRV record on the game port and an A record at the host's
        //    declared address, both in the managed zone.
        assertThat((String) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.GAME_ADDRESS))
            .as("step 5: the game address is the instance's").isEqualTo(gameAddress);
        Row srv = zoneRecord("_minecraft._tcp.mc", DnsRecordModel.TYPE_SRV);
        assertThat(srv).as("step 5: an SRV record for the game address exists").isNotNull();
        assertThat(DnsRecordModel.portOf(srv)).as("step 5: on the claimed host port").isEqualTo(hostPort);
        assertThat((String) srv.get(DnsRecordModel.VALUE)).as("step 5: aimed at the game address")
            .isEqualTo(gameAddress);
        Row a = zoneRecord("mc", DnsRecordModel.TYPE_A);
        assertThat(a).as("step 5: an A record for the game address exists").isNotNull();
        assertThat((String) a.get(DnsRecordModel.VALUE)).as("step 5: carrying the host's declared address")
            .isEqualTo(HOST_IPV4);

        // 6. The Overview says where players connect: the game address, then the game port at the host's address.
        String overview = adminGet("/admin/instances/" + appId + "/page/overview").body();
        assertThat(overview).as("step 6: a Connect to card names the game address")
            .contains("Connect to").contains("Players connect to " + gameAddress + ", or to a port below.");
        assertThat(overview).as("step 6: its row is the game port at the host's address")
            .contains("data-endpoint-port=\"" + hostPort + "\"")
            .contains(HOST_IPV4 + ":" + hostPort);
        int row = overview.indexOf("data-endpoint-port=\"" + hostPort + "\"");
        assertThat(overview.substring(row, overview.indexOf("</pl-table-row>", row)))
            .as("step 6: and says it is the game port").contains("data-endpoint-purpose").contains(">Game<");
    }

    /**
     * A host port another owner holds, and a game address no managed zone covers, are refused on the Ports step
     * before anything is made; a free port the operator chooses is the one published.
     */
    @Test
    void aChosenHostPortIsRefusedWhenTakenAndPublishedWhenFree() throws Exception {
        AccessContext operator = TenantConduits.operator();
        int taken = freePort();
        PortLedger.claimPreallocated(hostId, "", taken, PublishedPort.TCP, InstanceModel.MODEL_ID, holderId,
            "game put online test");

        // 1. The taken port is refused in the request, on the game port's own field, and nothing is created.
        DomainRefusal refused = catchThrowableOfType(DomainRefusal.class, () -> OperationPipeline.invoke(
            OperationRequest.of(PutOnline.PUT_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION).caller(operator)
                .subjects(List.of(minecraft)).form(serviceForm(PREFIX + "clash", String.valueOf(taken), ""))));
        assertThat(refused).as("step 1: a port another owner holds is refused before a run").isNotNull();
        assertThat(refused.field()).as("step 1: on the game port").isEqualTo(PutOnline.PORTS + ".game");
        assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "clash")).first())
            .as("step 1: and no game server was made").isNull();

        // 1b. A game address no managed zone covers is refused the same way, on its own field: the app is never made
        //     only for the run to stop at its records.
        DomainRefusal uncovered = catchThrowableOfType(DomainRefusal.class, () -> OperationPipeline.invoke(
            OperationRequest.of(PutOnline.PUT_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION).caller(operator)
                .subjects(List.of(minecraft)).form(serviceForm(PREFIX + "elsewhere", "", "mc.unmanaged.test"))));
        assertThat(uncovered).as("step 1b: a game address in no managed zone is refused before a run").isNotNull();
        assertThat(uncovered.field()).as("step 1b: on the game address")
            .isEqualTo(PutOnline.GAME_ADDRESS.getName());
        assertThat(Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "elsewhere")).first())
            .as("step 1b: and no game server was made").isNull();

        // 2. A free port the operator chooses is claimed and published as chosen; without a game address that step
        //    is skipped.
        int chosen = freePort();
        OperationRun run = PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                OperationRequest.of(PutOnline.PUT_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION).caller(operator)
                    .subjects(List.of(minecraft)).form(serviceForm(PREFIX + "creative", String.valueOf(chosen), "")))),
            operator, "step 2");
        assertThat(run.status()).as("step 2: the run succeeded (%s)", run.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(run.skipped(3)).as("step 2: no game address was given, so its step is skipped").isTrue();
        Row app = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "creative")).first();
        assertThat(PublishedPort.of(InstanceModel.settingsOf(app)).getFirst().hostPort())
            .as("step 2: the chosen port is the one published").isEqualTo(chosen);
    }

    /** A container template that publishes no ports of its own is a website: its wizard asks an address and HTTPS. */
    @Test
    void aContainerTemplateWithoutPublishedPortsKeepsTheWebsiteSteps() throws Exception {
        Row web = Models.get(InstanceTemplateModel.class).createEmptyRow();
        web.set(InstanceTemplateModel.NAME, PREFIX + "web");
        web.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        web.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "nginx", "container_port", 80)));
        web.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        web.set(InstanceTemplateModel.APPROVED_BY_USER_ID, 1L);
        Models.get(InstanceTemplateModel.class).save(web);
        try {
            // 1. Its wizard is the website flow: Where, then HTTPS with the address, and no Ports step.
            String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?template="
                + web.get(InstanceTemplateModel.ID)).body();
            assertThat(wizard).as("step 1: where and HTTPS")
                .contains("data-zf-step=\"where\"").contains("data-zf-step=\"https\"");
            assertThat(wizard).as("step 1: no Ports step and no game address")
                .doesNotContain("data-zf-step=\"ports\"")
                .doesNotContain("name=\"" + PutOnline.GAME_ADDRESS.getName() + "\"");
        } finally {
            HardDeletes.row(Models.get(InstanceTemplateModel.class), web);
        }
    }

    /** @return the service form for a game server on the test host */
    private static Map<String, Object> serviceForm(String name, String gamePort, String gameAddress) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("name", name);
        form.put("serverId", hostId);
        form.put("variables", Map.of());
        form.put(PutOnline.PORTS, Map.of("game", gamePort));
        form.put(PutOnline.GAME_ADDRESS.getName(), gameAddress);
        return form;
    }

    /** @return the record of this name and type in the test zone, or null */
    private static Row zoneRecord(String name, String type) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .and(DnsRecordModel.NAME.eq(name))
            .and(DnsRecordModel.TYPE.eq(type))
            .first();
    }

    /** @return a port nothing on this machine listens on right now, which the local host's probe admits */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
