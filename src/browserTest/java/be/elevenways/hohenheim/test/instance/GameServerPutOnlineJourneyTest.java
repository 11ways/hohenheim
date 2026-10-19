package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceTemplateModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.cms.PutOnline;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.server.instance.VmKind;
import be.elevenways.zenit.common.orm.field.attributes.FieldAttributes;
import be.elevenways.hohenheim.server.instance.InstanceTemplateHandlers;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.hohenheim.server.dns.DnsZoneStore;
import be.elevenways.hohenheim.server.game.GameAddresses;
import be.elevenways.hohenheim.server.game.GameTemplateSeeder;
import be.elevenways.hohenheim.test.FreePorts;
import be.elevenways.hohenheim.test.DnsFixtures;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.InstanceRowCleanup;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.operation.OperationRunStatus;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.common.edit.FormStep;
import be.elevenways.zenit.common.edit.SummaryLine;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.operation.OperationRun;
import be.elevenways.zenit.server.orm.seed.Seeds;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
            .where(InstanceTemplateModel.NAME.eq(GameTemplateSeeder.MINECRAFT_SERVER)).first();
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

        // 1. The chooser offers the game server as a family of its own, named as the board names it, and Continue
        //    opens THAT template, never the behind-the-proxy backend.
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: the chooser offers the standalone game server with its own card line")
            .contains("A Minecraft server players connect to directly, on its own port.");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=template:Minecraft%20server").headers()
                .firstValue("location").orElse(""))
            .as("step 1: its card opens the standalone template's wizard")
            .contains("template=" + templateId);
        assertThat(chooserCard(chooser, "Minecraft server")).as("step 1: the starter's card wears its own icon")
            .contains("name=\"gamepad\"");
        assertThat(chooserCard(chooser, "Velocity proxy")).as("step 1: so does the proxy's")
            .contains("name=\"diagram-project\"");

        // 2. The wizard asks where it runs, its options and its Ports: one entry for the game port and the game
        //    address; no address of the proxy and no HTTPS step.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?template=" + templateId).body();
        assertThat(wizard).as("step 2: where and ports")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"ports\"");
        assertThat(wizard).as("step 2: no HTTPS step and no address")
            .doesNotContain("data-zf-step=\"https\"")
            .doesNotContain("name=\"" + PutOnline.HOSTNAME.getName() + "\"");
        int portsStep = wizard.indexOf("data-zf-step=\"ports\"");
        assertThat(wizard.substring(portsStep, wizard.indexOf("name=\"" + PutOnline.PORTS + ".game\"", portsStep)))
            .as("step 2: the Ports group opening its step draws no legend repeating the step's heading")
            .doesNotContain("pl-fieldset-legend");
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
        String runPage = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "/run/" + run.id()).body();
        assertThat(runPage).as("step 3: the run page is headed by what it puts online, the name given in Where")
            .contains("Putting " + PREFIX + "survival online");
        assertThat(run.journey().stream().map(step -> step.key()).toList())
            .as("step 3: the run ends the journey the wizard walked: what, where, ports; never the Options step the"
                + " wizard passed over for a template without variables")
            .containsExactly("step_what", "step_where", "step_ports", "step_live");
        // The server writes these attributes in no fixed order (three orders seen across runs), so each is read alone.
        assertThat(openTag(runPage, "data-cms-operation-run-journey"))
            .as("step 3: its page draws those three steps done before its own")
            .contains(" leading-steps=\"3\"").contains(" total-steps=\"1\"").contains(" trailing-steps=\"0\"");
        assertThat(List.of(run.skipped(1), run.skipped(2), run.skipped(3)))
            .as("step 3: the template has no install step; its ports and its game address were done")
            .containsExactly(true, false, false);
        assertThat(Objects.requireNonNull(run.outcome()).key()).as("step 3: its start was refused, and it says so")
            .isEqualTo("outcome_not_started");
        assertThat(List.of(2, 3, 4).stream().map(run::stepDetail)
                .map(line -> line == null ? "none" : line.key()).toList())
            .as("step 3: the ports, the game address and the start each leave their line on the run")
            .containsExactly("done_ports", "done_game_address", "done_live_waiting");
        assertThat(String.valueOf(Objects.requireNonNull(run.stepDetail(3)).args().get("address")))
            .as("step 3: the game address step names the address").isEqualTo(gameAddress);
        assertThat(List.of(run.undone(4), run.leftAnyUndone()))
            .as("step 3: the refused start reads as not done, and the run as a success that still owes its start")
            .containsExactly(true, true);
        assertThat(String.valueOf(Objects.requireNonNull(run.stepDetail(2)).args().get("count")))
            .as("step 3: the ports line counts its ports, so one port reads in the singular").isEqualTo("1");

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

        // 6b. From its own page the operator reaches where it is named and what limits it: the Connect to card leads to
        //     its Configuration, and the Details card names the owner's quota the game server counts against.
        assertThat(overview).as("step 6b: the card offers changing the game address on the Configuration")
            .contains("Change game address").contains("href=\"/admin/instances/" + appId + "\"");
        assertThat(overview).as("step 6b: the Details card names the owner's quota it is charged to")
            .contains("Owner's quota").contains("/admin/" + HohenheimSlugs.INSTANCE_QUOTAS);
        assertThat(overview).as("step 6b: counting this game server among the owner's workloads")
            .containsPattern("[1-9][0-9]* (of [0-9]+ )?workloads");

        // 7. Its Configuration shows the game address it was given, as an operator's entry; a container reached as a
        //    website has no such entry.
        assertThat(adminGet("/admin/instances/" + appId).body())
            .as("step 7: the game server's Configuration carries its game address")
            .contains("name=\"" + InstanceModel.GAME_ADDRESS.getName() + "\"").contains("value=\"" + gameAddress + "\"");
        assertThat(adminGet("/admin/instances/" + holderId).body())
            .as("step 7: a container reached as a website has no game address entry")
            .doesNotContain("name=\"" + InstanceModel.GAME_ADDRESS.getName() + "\"");

        // 8. Changing it there moves its records: the old name's SRV and A records go, the new name's appear.
        String moved = "play." + ORIGIN;
        PanelResourceCalls.patch("admin", HohenheimSlugs.INSTANCES, appId,
            Map.of(InstanceModel.GAME_ADDRESS.getName(), "Play." + ORIGIN + "."), operator);
        assertThat((String) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.GAME_ADDRESS))
            .as("step 8: the new game address is stored as a plain name").isEqualTo(moved);
        assertThat(zoneRecord("_minecraft._tcp.mc", DnsRecordModel.TYPE_SRV)).as("step 8: the old SRV is gone").isNull();
        assertThat(zoneRecord("mc", DnsRecordModel.TYPE_A)).as("step 8: the old A record is gone").isNull();
        Row movedSrv = zoneRecord("_minecraft._tcp.play", DnsRecordModel.TYPE_SRV);
        assertThat(movedSrv).as("step 8: the new name has its SRV").isNotNull();
        assertThat(DnsRecordModel.portOf(movedSrv)).as("step 8: on the same game port").isEqualTo(hostPort);
        assertThat(zoneRecord("play", DnsRecordModel.TYPE_A)).as("step 8: and its A record").isNotNull();

        // 9. A name no managed zone covers is refused on the entry, and the stored address stays.
        Throwable uncovered = catchThrowable(() -> PanelResourceCalls.patch("admin", HohenheimSlugs.INSTANCES,
            appId, Map.of(InstanceModel.GAME_ADDRESS.getName(), "mc.unmanaged.test"), operator));
        assertThat(uncovered).as("step 9: an uncovered name is refused").isNotNull();
        assertThat((String) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.GAME_ADDRESS))
            .as("step 9: and the address stays").isEqualTo(moved);

        // 10. Emptying it removes the address and every record it made.
        PanelResourceCalls.patch("admin", HohenheimSlugs.INSTANCES, appId,
            Map.of(InstanceModel.GAME_ADDRESS.getName(), ""), operator);
        assertThat((String) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.GAME_ADDRESS))
            .as("step 10: no game address").isNull();
        assertThat(List.of(String.valueOf(zoneRecord("_minecraft._tcp.play", DnsRecordModel.TYPE_SRV)),
                String.valueOf(zoneRecord("play", DnsRecordModel.TYPE_A))))
            .as("step 10: its records are gone").containsOnly("null");
    }

    /**
     * An install seeded before the starters were renamed and the Velocity proxy published its game port: the rows
     * still carrying what was seeded are upgraded, an operator's edit sticks, and the proxy is put online through the
     * Ports step with a game address.
     */
    @Test
    void installedStartersAreUpgradedAndTheProxyGoesOnlineOnItsPorts() throws Exception {
        List<Row> made = new ArrayList<>();
        try {
            // 1. Starter rows as the earlier seed wrote them, and a proxy whose port an operator changed.
            Row server = starter(made, "Minecraft (Paper)", Map.of("image", "itzg/minecraft-server"));
            Row backend = starter(made, "Minecraft server (Paper)", Map.of("image", "itzg/minecraft-server"));
            Row proxy = starter(made, GameTemplateSeeder.VELOCITY_PROXY, Map.of("image", "dockcenter/velocity",
                "container_port", 25577, "port_exposure", "public"));
            Row edited = starter(made, GameTemplateSeeder.VELOCITY_PROXY, Map.of("image", "dockcenter/velocity",
                "container_port", 25599, "port_exposure", "public"));

            // 2. The upgrade renames the two servers so the chooser cannot confuse them.
            GameTemplateSeeder.upgradeStarters();
            assertThat(List.of(nameOf(server), nameOf(backend)))
                .as("step 2: the standalone server and the backend behind Velocity")
                .containsExactly(GameTemplateSeeder.MINECRAFT_SERVER, GameTemplateSeeder.MINECRAFT_BACKEND);

            // 3. The seeded proxy publishes its entry port as its game port with the SRV service; the edited one
            //    keeps what the operator made of it.
            Map<String, Object> upgraded = settingsOf(proxy);
            assertThat(PublishedPort.of(upgraded)).as("step 3: the proxy's game port")
                .containsExactly(PublishedPort.parse("game", "25577/tcp"));
            assertThat(upgraded).as("step 3: its SRV service, and no single public port any more")
                .containsEntry(PublishedPort.SRV_SERVICE, "minecraft")
                .doesNotContainKeys("container_port", "port_exposure");
            assertThat(settingsOf(edited)).as("step 3: an operator's edit sticks")
                .containsEntry("container_port", 25599).doesNotContainKey(PublishedPort.FIELD);

            // 4. Its wizard is the Ports flow: its game port and a game address, no address of the proxy, no HTTPS.
            String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?template="
                + proxy.get(InstanceTemplateModel.ID)).body();
            assertThat(wizard).as("step 4: a Ports step with the game port and the game address")
                .contains("data-zf-step=\"ports\"").contains("name=\"" + PutOnline.PORTS + ".game\"")
                .contains("name=\"" + PutOnline.GAME_ADDRESS.getName() + "\"");
            assertThat(wizard).as("step 4: no HTTPS step").doesNotContain("data-zf-step=\"https\"");
        } finally {
            for (Row row : made) {
                HardDeletes.row(Models.get(InstanceTemplateModel.class), row);
            }
        }
    }

    /** @return a starter template row as a seed writes it */
    private static Row starter(List<Row> made, String name, Map<String, Object> settings) {
        Row row = Models.get(InstanceTemplateModel.class).createEmptyRow();
        row.set(InstanceTemplateModel.NAME, name);
        row.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        row.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(settings));
        row.set(InstanceTemplateModel.SOURCE, InstanceTemplateModel.SOURCE_STARTER);
        Models.get(InstanceTemplateModel.class).save(row);
        made.add(row);
        return row;
    }

    private static String nameOf(Row template) {
        return Models.get(InstanceTemplateModel.class).findById(template.get(InstanceTemplateModel.ID))
            .get(InstanceTemplateModel.NAME);
    }

    private static Map<String, Object> settingsOf(Row template) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (Models.get(InstanceTemplateModel.class).findById(template.get(InstanceTemplateModel.ID))
                .get(InstanceTemplateModel.SETTINGS) instanceof Map<?, ?> stored) {
            stored.forEach((key, value) -> settings.put(String.valueOf(key), value));
        }
        return settings;
    }

    /**
     * A host port another owner holds, and a game address no managed zone covers, are refused on the Ports step
     * before anything is made; a free port the operator chooses is the one published.
     */
    @Test
    void aChosenHostPortIsRefusedWhenTakenAndPublishedWhenFree() throws Exception {
        AccessContext operator = TenantConduits.operator();
        int taken = FreePorts.tcpAndUdp();
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
        int chosen = FreePorts.tcpAndUdp();
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

    /**
     * The Ports step's summary names what the chosen template declares, read off the operation's subject through the
     * pipeline that draws the step: one line per declared port with its container port, and no game address line,
     * since that is asked on the same step, after the summary.
     */
    @Test
    void thePortsStepSummaryListsTheTemplatesDeclaredPorts() {
        AccessContext operator = TenantConduits.operator();
        List<FormStep> steps = PutOnline.PUT_SERVICE_ONLINE.input().form().steps();
        int portsAt = steps.indexOf(steps.stream().filter(step -> step.id().equals(PutOnline.PORTS)).findFirst()
            .orElseThrow());
        List<String> before = steps.subList(0, portsAt).stream().flatMap(step -> step.entryNames().stream()).toList();

        // 1. Validating Where and Options for the standalone Minecraft template answers the Ports step's summary:
        //    its name, where it runs, then its one declared port.
        List<SummaryLine> lines = OperationPipeline.validate(OperationRequest.of(PutOnline.PUT_SERVICE_ONLINE,
                CmsPlacementSurface.ADMIN_ACTION).caller(operator).subjects(List.of(minecraft))
                .form(serviceForm(PREFIX + "summary", "", "")),
            before, steps.get(portsAt).summary());
        assertThat(lines).extracting(line -> line.label().key())
            .as("step 1: name, host, then the game port by its purpose")
            .containsExactly("summary_name", "summary_runs_on", "purpose_game");

        // 2. The port's line names its container port and says how its host port is chosen.
        SummaryLine game = lines.get(2);
        assertThat(game.value().key()).as("step 2: the port line's words").isEqualTo("summary_port");
        assertThat(String.valueOf(game.value().args().get("port"))).as("step 2: its container port and protocol")
            .isEqualTo("25565/tcp");
    }

    /**
     * The container image's Ports step summarizes the rows typed on that same step: what is put online (its card's
     * words), its name and host, then one line per row with how its host port is chosen. The wizard asks it again
     * as the rows change, since the step's summary reads its own answers.
     */
    @Test
    void theContainerPortsStepSummaryListsTheRowsTypedOnIt() {
        AccessContext operator = TenantConduits.operator();
        List<FormStep> steps = PutOnline.PUT_WORKLOAD_SERVICE_ONLINE.input().form().steps();
        FormStep ports = steps.stream().filter(step -> step.id().equals(PutOnline.PORTS)).findFirst().orElseThrow();
        List<String> through = steps.subList(0, steps.indexOf(ports) + 1).stream()
            .flatMap(step -> step.entryNames().stream()).toList();

        // 1. The step's summary reads its own answers, so the wizard asks it again while rows are typed.
        assertThat(ports.summaryReadsItself()).as("step 1: the Ports summary reads the Ports step itself").isTrue();

        // 2. Validating through the Ports step answers What, the name, the host, then each typed row.
        List<SummaryLine> lines = OperationPipeline.validate(OperationRequest.of(PutOnline.PUT_WORKLOAD_SERVICE_ONLINE,
                CmsPlacementSurface.ADMIN_ACTION).caller(operator).form(imageServiceForm(PREFIX + "image-summary",
                Map.of(), List.of(portRow("game", "25565", "tcp"), Map.of(PutOnline.PORT_PURPOSE.getName(), "query",
                    PutOnline.PORT_CONTAINER.getName(), "25566", PutOnline.PORT_PROTOCOL.getName(), "udp",
                    PutOnline.PORT_HOST.getName(), "30123")), "")),
            through, ports.summary());
        assertThat(lines).extracting(line -> line.label().key())
            .as("step 2: THE ASSERTION -- What, name, host, then the two rows by their purpose")
            .containsExactly("summary_serves", "summary_name", "summary_runs_on", "purpose_game", "purpose_query");

        // 3. The What line names the card this flow was opened from: the container image on its own ports.
        assertThat(lines.get(0).value().key()).as("step 3: the What line reads as the ports card")
            .isEqualTo(PutOnlineReach.PORTS.cardLabel().key());

        // 4. A row without a host port gets a free one; a row with one names it.
        assertThat(lines.get(3).value().key()).as("step 4: the game row's host port is picked")
            .isEqualTo("summary_port_row_free");
        assertThat(String.valueOf(lines.get(3).value().args().get("port"))).isEqualTo("25565/tcp");
        assertThat(lines.get(4).value().key()).as("step 4: the query row names its host port")
            .isEqualTo("summary_port_row");
        assertThat(String.valueOf(lines.get(4).value().args().get("host"))).isEqualTo("30123");

        // 5. A row says where its port listens in the kind's own words: the container's, from its isolation fact.
        assertThat(lines.get(3).value().filters().asMap().get("workload"))
            .as("step 5: the row is worded for a container").isEqualTo(WorkloadIsolation.SHARED_KERNEL.token());
    }

    /**
     * The address a website flow asks is helped by the website reach's own words: a static site and a container image
     * put online as websites never hear of a game server, which is reached on its ports and never asked an address.
     */
    @Test
    void aWebsiteAddressHelpSpeaksOfTheWebsiteReachAlone() throws Exception {
        for (String kind : List.of("hohenheim:static_site", "hohenheim:docker_container")) {
            String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?workload=" + kind).body();
            // 1. The wizard asks the address.
            assertThat(wizard).as("step 1: the %s wizard asks an address", kind)
                .contains("name=\"" + PutOnline.HOSTNAME.getName() + "\"");
            // 2. BUG FIXED: its help no longer names a game server; it says what an empty address means here.
            assertThat(wizard).as("step 2: the %s address help names no game server", kind)
                .doesNotContain("like a game server")
                .contains("Leave it empty to give it an address later.");
        }
    }

    /**
     * A container image goes online as a service on its own ports, without a template: the chooser offers it on its
     * ports beside the website card; that wizard asks Where, Options and Ports, never an address or HTTPS; a port
     * name given twice is refused before a run; one run creates the container with a game and a query port, both
     * claimed publicly on its host and written as published ports, and its game address gets the game port's SRV.
     */
    @Test
    void aContainerImageGoesOnlineOnItsOwnPortsWithoutATemplate() throws Exception {
        AccessContext operator = TenantConduits.operator();
        String kind = "hohenheim:docker_container";

        // 1. The chooser offers the container image a second way, on its own ports; that card opens the ports flow,
        //    and the kind's own card still opens the website flow.
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: a card for the container image on its own ports")
            .contains("instance:" + kind + "@ports")
            .contains("on its own ports")
            .contains("clients connect to ports published on its host.");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=instance:" + kind + "@ports").headers()
                .firstValue("location").orElse(""))
            .as("step 1: the ports card opens the kind's wizard reached on its ports")
            .contains("workload=hohenheim%3Adocker_container").contains("reach=ports");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=instance:" + kind).headers()
                .firstValue("location").orElse(""))
            .as("step 1: the kind's own card opens its website wizard")
            .contains("workload=hohenheim%3Adocker_container").doesNotContain("reach=");

        // 2. The ports wizard asks where it runs, the image's settings and its ports with the game address; no
        //    address, no HTTPS. The website wizard of the same kind keeps its HTTPS step.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?workload=" + kind + "&reach=ports").body();
        assertThat(wizard).as("step 2: where, options and ports")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"settings\"")
            .contains("data-zf-step=\"ports\"").contains("name=\"" + PutOnline.GAME_ADDRESS.getName() + "\"");
        assertThat(wizard).as("step 2: no HTTPS step and no address")
            .doesNotContain("data-zf-step=\"https\"")
            .doesNotContain("name=\"" + PutOnline.HOSTNAME.getName() + "\"");
        assertThat(wizard).as("step 2: the lead speaks of ports, never of a certificate")
            .contains("publishes its ports");
        assertThat(wizard).as("step 2: the name entry is the one every flow shares")
            .contains("Instance name");
        assertThat(wizard).as("step 2: a port row's name is for Other only, never suggested on every row")
            .doesNotContain("placeholder=\"metrics\"");
        assertThat(wizard).as("step 2: THE ASSERTION -- the Ports step owns the ports; Options asks none of them")
            .doesNotContain("name=\"settings.container_port\"").doesNotContain("settings.published_ports")
            .doesNotContain("name=\"settings.host_port\"").doesNotContain("name=\"settings.port_exposure\"")
            .contains("name=\"settings.image\"");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?workload=" + kind).body())
            .as("step 2: the same kind as a website keeps its HTTPS step and asks its port in its settings")
            .contains("data-zf-step=\"https\"").contains("name=\"settings.container_port\"");
        assertThat(wizard).as("step 2: the kind-led Where step draws the Runs on card too")
            .contains("Runs on").contains("Hohenheim picks the host with room for it, unless you pick one.");
        FieldOption<Integer> host = InstanceTemplateHandlers.hostOptions().stream()
            .filter(option -> Integer.valueOf(hostId).equals(option.value())).findFirst().orElseThrow();
        assertThat(host.meters().get(0).text().key())
            .as("step 2: a measured host option carries the Hosts list's booked memory as its first meter")
            .isEqualTo("host_option_memory");
        assertThat(host.description()).as("step 2: and its standing as its line").isNotNull();
        assertThat(host.tone()).as("step 2: in the standing's badge tone").isNotNull();

        // 3. A port name given twice is refused in the request, on the second row, and nothing is made; settings
        //    carrying a published port of their own are no settings this flow takes (the Ports step owns them).
        Map<String, Object> twice = imageServiceForm(PREFIX + "image-twice", Map.of(),
            List.of(portRow("game", "25565", "tcp"), portRow("game", "25566", "tcp")), "");
        DomainRefusal refused = catchThrowableOfType(DomainRefusal.class, () -> OperationPipeline.invoke(
            OperationRequest.of(PutOnline.PUT_WORKLOAD_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                .caller(operator).form(twice)));
        assertThat(refused).as("step 3: a port name given twice is refused before a run").isNotNull();
        assertThat(refused.field()).as("step 3: on the second row's purpose").isEqualTo(PutOnline.PORTS + ".1.purpose");
        Map<String, Object> declared = imageServiceForm(PREFIX + "image-declared",
            Map.of(PublishedPort.FIELD, Map.of("0", Map.of("key", "game", "value", "25565/tcp"))),
            List.of(portRow("game", "25565", "tcp")), "");
        Violations again = catchThrowableOfType(Violations.class, () -> OperationPipeline.invoke(
            OperationRequest.of(PutOnline.PUT_WORKLOAD_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                .caller(operator).form(declared)));
        assertThat((Object) again).as("step 3: settings publishing a port of their own are refused").isNotNull();
        assertThat(again.all()).extracting(Violation::path).as("step 3: on that setting")
            .contains(InstanceModel.SETTINGS.getName() + "." + PublishedPort.FIELD);
        assertThat(List.of(String.valueOf(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.NAME.eq(PREFIX + "image-twice")).first()),
                String.valueOf(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.NAME.eq(PREFIX + "image-declared")).first())))
            .as("step 3: and nothing was made").containsOnly("null");

        // 4. One run with a game port, a query port and a game address: app, ports and game address done.
        String gameAddress = "svc." + ORIGIN;
        OperationRun run = PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                OperationRequest.of(PutOnline.PUT_WORKLOAD_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                    .caller(operator).form(imageServiceForm(PREFIX + "image-service", Map.of(),
                        List.of(portRow("game", "25565", "tcp"), portRow("query", "25565", "udp")), gameAddress)))),
            operator, "step 4");
        assertThat(run.status()).as("step 4: the run succeeded (%s)", run.outcome())
            .isEqualTo(OperationRunStatus.SUCCEEDED);
        assertThat(run.journey())
            .as("step 4: the run ends the journey the wizard walked: what, where, options, ports, live").hasSize(5);
        assertThat(List.of(run.skipped(1), run.skipped(2)))
            .as("step 4: its ports and its game address were done").containsExactly(false, false);

        // 5. The container publishes both ports, each on the host port the ledger holds publicly for it.
        Row app = Models.get(InstanceModel.class).find().where(InstanceModel.NAME.eq(PREFIX + "image-service"))
            .first();
        assertThat(app).as("step 5: the container exists").isNotNull();
        assertThat((String) app.get(InstanceModel.KIND)).as("step 5: as a container image").isEqualTo(kind);
        int appId = app.get(InstanceModel.ID);
        List<PublishedPort> published = PublishedPort.of(InstanceModel.settingsOf(app));
        assertThat(published).extracting(PublishedPort::name).as("step 5: the game and the query port")
            .containsExactly("game", "query");
        assertThat(published).as("step 5: each with a concrete host port")
            .allSatisfy(port -> assertThat(port.hostPort()).isNotNull());
        assertThat(published.get(0).spec()).as("step 5: the game port in Docker's spelling")
            .isEqualTo(published.get(0).hostPort() + ":25565/tcp");
        assertThat(published.get(1).spec()).as("step 5: the query port in Docker's spelling")
            .isEqualTo(published.get(1).hostPort() + ":25565/udp");
        for (PublishedPort port : published) {
            assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, appId))
                .as("step 5: the ledger holds %s publicly on its host", port.spec())
                .anySatisfy(claim -> {
                    assertThat((Integer) claim.get(PortAllocationModel.PORT)).isEqualTo(port.hostPort());
                    assertThat((String) claim.get(PortAllocationModel.PROTOCOL)).isEqualTo(port.protocol());
                    assertThat((String) claim.get(PortAllocationModel.HOST_IP)).isEmpty();
                    assertThat((Integer) claim.get(PortAllocationModel.SERVER_ID)).isEqualTo(hostId);
                    assertThat(PortLedger.isReleasing(claim)).isFalse();
                });
        }

        // 6. Its game address is stored and its SRV record points at the game port, never the query port.
        assertThat((String) Models.get(InstanceModel.class).findById(appId).get(InstanceModel.GAME_ADDRESS))
            .as("step 6: the game address is the container's").isEqualTo(gameAddress);
        Row srv = zoneRecord("_minecraft._tcp.svc", DnsRecordModel.TYPE_SRV);
        assertThat(srv).as("step 6: an SRV record for the game address exists").isNotNull();
        assertThat(DnsRecordModel.portOf(srv)).as("step 6: on the game port's host port")
            .isEqualTo(published.get(0).hostPort());
    }

    /**
     * A virtual machine goes online as a service on its own ports, a first-class choice beside the machine card: its
     * wizard asks Where, Options and the shared Ports step; one run creates it, claims its game port, realizes it as
     * a NAT proxy device of the Incus daemon and gives it its game address; its Overview says where players connect.
     */
    @Test
    void aVirtualMachineGoesOnlineOnItsOwnPorts() throws Exception {
        AccessContext operator = TenantConduits.operator();
        String kind = "hohenheim:vm";

        // 1. The chooser offers the virtual machine a second way, on its own ports; the machine card keeps its flow.
        String chooser = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE).body();
        assertThat(chooser).as("step 1: a card for the virtual machine on its own ports")
            .contains("instance:" + kind + "@ports");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=instance:" + kind + "@ports").headers()
                .firstValue("location").orElse(""))
            .as("step 1: the ports card opens the machine's wizard reached on its ports")
            .contains("workload=hohenheim%3Avm").contains("reach=ports");
        assertThat(adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?choice=instance:" + kind).headers()
                .firstValue("location").orElse(""))
            .as("step 1: the machine card still opens the machine wizard").doesNotContain("reach=");

        // 2. The ports wizard is the shared one: Where, the machine's settings without its ports, then Ports with the
        //    game address; no address and no HTTPS.
        String wizard = adminGet("/admin/" + HohenheimSlugs.PUT_ONLINE + "?workload=" + kind + "&reach=ports").body();
        assertThat(wizard).as("step 2: where, options and ports")
            .contains("data-zf-step=\"where\"").contains("data-zf-step=\"settings\"")
            .contains("data-zf-step=\"ports\"").contains("name=\"" + PutOnline.GAME_ADDRESS.getName() + "\"");
        assertThat(wizard).as("step 2: no HTTPS step and no address")
            .doesNotContain("data-zf-step=\"https\"")
            .doesNotContain("name=\"" + PutOnline.HOSTNAME.getName() + "\"");
        assertThat(wizard).as("step 2: the Ports step owns the ports; Options asks the machine's own settings")
            .doesNotContain("settings.published_ports").contains("name=\"settings.image\"");
        assertThat(wizard).as("step 2: the Ports step's lead never calls the guest's port a container port")
            .contains("what it is for, the port and its protocol").doesNotContain("its container port");
        assertThat(VmKind.PUBLISHED_PORTS.getAttribute(FieldAttributes.HELP).filters().asMap().get("workload"))
            .as("step 2: its Configuration's ports help is worded for a virtual machine")
            .isEqualTo(WorkloadIsolation.VIRTUAL_MACHINE.token());

        try (FakeIncusSocket incus = FakeIncusSocket.start()) {
            int vmHost = HostFixtures.admittedIncusHost(PREFIX + "vm-host");
            Row host = Models.get(ServerModel.class).findById(vmHost);
            host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
            host.set(ServerModel.INCUS_URL, incus.url());
            host.set(ServerModel.PUBLIC_IPV4, HOST_IPV4);
            Models.get(ServerModel.class).save(host);
            Integer vmId = null;
            try {
                String gameAddress = "vm." + ORIGIN;
                Map<String, Object> form = new LinkedHashMap<>();
                form.put(InstanceModel.NAME.getName(), PREFIX + "vm-service");
                form.put("serverId", vmHost);
                form.put(InstanceModel.KIND.getName(), kind);
                form.put(InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image",
                    "images:alpine/3.22/cloud", PublishedPort.SRV_SERVICE, "minecraft")));
                form.put(PutOnline.PORTS, List.of(portRow("game", "25565", "tcp")));
                form.put(PutOnline.GAME_ADDRESS.getName(), gameAddress);

                // 2b. THE ASSERTION: its Ports step's summary says where the game port listens in the machine's own
                //     words, from its kind's isolation fact.
                List<FormStep> steps = PutOnline.PUT_WORKLOAD_SERVICE_ONLINE.input().form().steps();
                FormStep ports = steps.stream().filter(step -> step.id().equals(PutOnline.PORTS)).findFirst()
                    .orElseThrow();
                List<SummaryLine> summary = OperationPipeline.validate(OperationRequest.of(
                        PutOnline.PUT_WORKLOAD_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION).caller(operator)
                        .form(form),
                    steps.subList(0, steps.indexOf(ports) + 1).stream().flatMap(step -> step.entryNames().stream())
                        .toList(), ports.summary());
                assertThat(summary.getLast().value().filters().asMap().get("workload"))
                    .as("step 2b: the game row is worded for a virtual machine")
                    .isEqualTo(WorkloadIsolation.VIRTUAL_MACHINE.token());

                // 3. One run with a game port and a game address: app, ports and game address done, and started.
                OperationRun run = PutOnlineJourneyTest.ended(PutOnlineJourneyTest.runOf(OperationPipeline.invoke(
                        OperationRequest.of(PutOnline.PUT_WORKLOAD_SERVICE_ONLINE, CmsPlacementSurface.ADMIN_ACTION)
                            .caller(operator).form(form))),
                    operator, "step 3");
                Row vm = Models.get(InstanceModel.class).find()
                    .where(InstanceModel.NAME.eq(PREFIX + "vm-service")).first();
                assertThat(vm).as("step 3: the machine exists (%s)", run.outcome()).isNotNull();
                vmId = vm.get(InstanceModel.ID);
                assertThat(run.status()).as("step 3: the run succeeded (%s)", run.outcome())
                    .isEqualTo(OperationRunStatus.SUCCEEDED);
                // Its first start meets the driver's kernel-truth check, which reads the local nft for the guest's
                // live tap; this daemon is hermetic and names none, so the start is left undone here
                // (IncusPublishedPortsLiveTest starts one on a real host).
                assertThat(List.of(run.skipped(1), run.skipped(2)))
                    .as("step 3: its ports and its game address were done (%s)", run.outcome())
                    .containsExactly(false, false);

                // 4. The ledger holds the game port publicly on the Incus host, and the settings name it.
                List<PublishedPort> published = PublishedPort.of(InstanceModel.settingsOf(
                    Models.get(InstanceModel.class).findById(vmId)));
                assertThat(published).extracting(PublishedPort::name).as("step 4: the game port is published")
                    .containsExactly("game");
                int hostPort = Objects.requireNonNull(published.getFirst().hostPort());
                assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, vmId)).as("step 4: claimed publicly on its host")
                    .anySatisfy(claim -> {
                        assertThat((Integer) claim.get(PortAllocationModel.PORT)).isEqualTo(hostPort);
                        assertThat((String) claim.get(PortAllocationModel.HOST_IP)).isEmpty();
                        assertThat((Integer) claim.get(PortAllocationModel.SERVER_ID)).isEqualTo(vmHost);
                    });

                // 5. THE ASSERTION: the Incus daemon holds the machine with a NAT proxy device forwarding that host
                //    port to the guest's game port.
                assertThat(incus.daemon.instances.values()).as("step 5: the machine has its game port's proxy")
                    .anySatisfy(instance -> {
                        Map<?, ?> proxy = (Map<?, ?>) ((Map<?, ?>) instance.get("devices")).get("hohport-tcp-25565");
                        assertThat(proxy).isNotNull();
                        assertThat(proxy.get("type")).isEqualTo("proxy");
                        assertThat(proxy.get("nat")).isEqualTo("true");
                        assertThat(proxy.get("listen")).isEqualTo("tcp:0.0.0.0:" + hostPort);
                    });

                // 6. Its game address has an SRV record on the game port, and its Overview says where to connect.
                Row srv = zoneRecord("_minecraft._tcp.vm", DnsRecordModel.TYPE_SRV);
                assertThat(srv).as("step 6: an SRV record for the game address exists").isNotNull();
                assertThat(DnsRecordModel.portOf(srv)).as("step 6: on the game port").isEqualTo(hostPort);
                String overview = adminGet("/admin/instances/" + vmId + "/page/overview").body();
                assertThat(overview).as("step 6: a Connect to card names the game address")
                    .contains("Connect to").contains("Players connect to " + gameAddress + ", or to a port below.");
                assertThat(overview).as("step 6: its row is the game port at the host's address")
                    .contains("data-endpoint-port=\"" + hostPort + "\"").contains(HOST_IPV4 + ":" + hostPort);
                assertThat(overview).as("step 6b: its Network card admits the published ports reach it from outside")
                    .contains("From outside its host, only its published ports reach it.")
                    .doesNotContain("Nothing outside its host can reach it directly.");
            } finally {
                if (vmId != null) {
                    GameAddresses.set(vmId, null);
                    PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, vmId);
                    InstanceRowCleanup.delete(vmId);
                }
                HardDeletes.byId(Models.get(ServerModel.class), vmHost);
            }
        }
    }

    /** A container template that publishes no ports of its own is a website: its wizard asks an address and HTTPS. */
    @Test
    void aContainerTemplateWithoutPublishedPortsKeepsTheWebsiteSteps() throws Exception {
        Row web = Models.get(InstanceTemplateModel.class).createEmptyRow();
        web.set(InstanceTemplateModel.NAME, PREFIX + "web");
        web.set(InstanceTemplateModel.KIND, "hohenheim:docker_container");
        web.set(InstanceTemplateModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "nginx", "container_port", 80)));
        web.set(InstanceTemplateModel.APPROVED_AT, Now.instant());
        web.set(InstanceTemplateModel.APPROVED_BY_USER_ID, TenantConduits.operatorUser().get(UserModel.ID));
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

    /** @return the kind-led service form for a container image on the test host */
    private static Map<String, Object> imageServiceForm(String name, Map<String, Object> moreSettings,
                                                        List<Map<String, Object>> ports, String gameAddress) {
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("image", "alpine", "tag", "latest",
            PublishedPort.SRV_SERVICE, "minecraft"));
        settings.putAll(moreSettings);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put(InstanceModel.NAME.getName(), name);
        form.put("serverId", hostId);
        form.put(InstanceModel.KIND.getName(), "hohenheim:docker_container");
        form.put(InstanceModel.SETTINGS.getName(), settings);
        form.put(PutOnline.PORTS, ports);
        form.put(PutOnline.GAME_ADDRESS.getName(), gameAddress);
        return form;
    }

    /** @return one Ports row as the item list posts it */
    private static Map<String, Object> portRow(String purpose, String containerPort, String protocol) {
        return Map.of(PutOnline.PORT_PURPOSE.getName(), purpose, PutOnline.PORT_CONTAINER.getName(), containerPort,
            PutOnline.PORT_PROTOCOL.getName(), protocol);
    }

    /** @return the record of this name and type in the test zone, or null */
    private static Row zoneRecord(String name, String type) {
        return Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .and(DnsRecordModel.NAME.eq(name))
            .and(DnsRecordModel.TYPE.eq(type))
            .first();
    }

    /** @return the opening tag of the first element carrying the marker attribute, empty when none does */
    private static String openTag(String page, String marker) {
        int at = page.indexOf(" " + marker);
        if (at < 0) {
            return "";
        }
        return page.substring(page.lastIndexOf('<', at), page.indexOf('>', at) + 1);
    }

    /** @return the drawn chooser card whose label is exactly this, empty when the chooser draws none */
    private static String chooserCard(String chooser, String label) {
        for (String card : chooser.split("<pl-choice-card")) {
            String drawn = card.contains("</pl-choice-card>") ? card.substring(0, card.indexOf("</pl-choice-card>"))
                : card;
            if (drawn.contains(">" + label + "<")) {
                return drawn;
            }
        }
        return "";
    }
}
