package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimFigureFunctions;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.LiveFigureState;
import be.elevenways.hohenheim.instance.LiveFigureView;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.ports.PortPurpose;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.server.instance.InstanceFigureHandler;
import be.elevenways.hohenheim.server.instance.InstanceFigures;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.instance.FakeMinecraftServer;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.test.support.DatasourceProbe;
import be.elevenways.zenit.test.support.FakeChannelLink;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A game server's players online read live: on its overview and in the instances list, moving when the count moves,
 * said as not answering once the server stops answering, said as stopped (never asked) once its own state says so, and
 * heard only by those who may view the server; the list's live column reads nothing from the database however many
 * rows it shows.
 *
 * AIDEV-NOTE: the servers are FakeMinecraftServer on loopback ports, published as each instance's game port; the hub's
 * wait between probes is skipped by moving Now's offset past {@link InstanceFigures#INTERVAL}, never by a shorter
 * constant.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LiveFigureJourneyTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final String PREFIX = "live-figure-";

    @Test
    void aGameServersPlayersReadLiveOnItsOverviewAndListForThoseWhoMayViewIt(TestReporter reporter)
            throws Exception {
        // The hermetic daemon: the overview's live memory and CPU gauges of these running rows are refused at once by
        // its runtime, never asked of the host's real Docker for containers that do not exist.
        FakeDockerDaemon daemon = new FakeDockerDaemon();
        daemon.installContainerKind();
        Duration offset = Now.offset();
        List<Row> rows = new ArrayList<>();
        List<UUID> users = new ArrayList<>();
        FakeMinecraftServer alpha = FakeMinecraftServer.start(8, 20);
        FakeMinecraftServer bravo = FakeMinecraftServer.start(3, 10);
        try {
            Row alphaRow = gameServer(rows, PREFIX + "alpha", alpha.port());
            Row bravoRow = gameServer(rows, PREFIX + "bravo", bravo.port());
            int alphaId = alphaRow.get(InstanceModel.ID);
            int bravoId = bravoRow.get(InstanceModel.ID);
            UUID alphaTenant = tenant(users, "alpha", alphaId);
            UUID bravoTenant = tenant(users, "bravo", bravoId);

            // 1. The overview of a server that declares the Minecraft status probe says its players online, read live
            //    from its game port while the page is open, inside its Connect to card.
            navigateToApp("/admin/instances/" + alphaId + "/page/overview");
            waitForHydration();
            awaitFigures("step 1: the overview reads the players online", List.of("8 of 20 players online"));
            assertThat(page.locator("[data-live-figure='" + LiveFigureState.ANSWERING.token() + "']").count())
                .as("step 1: as an answered figure").isEqualTo(1);
            assertThat(InstanceFigures.viewers(alphaId)).as("step 1: the open overview is the hub's one viewer")
                .isEqualTo(1);

            // 2. Players join: the next probe, one interval later, moves the words on the open page.
            alpha.players(12);
            Now.setOffset(Now.offset().plus(InstanceFigures.INTERVAL));
            awaitFigures("step 2: the overview follows the count", List.of("12 of 20 players online"));

            // 3. The instances list shows each server's figure in its row, live, over ONE link for the whole page.
            int linksBefore = InstanceFigureHandler.openedLinks();
            navigateToApp("/admin/instances" + listQuery("name contains \"" + PREFIX + "\""));
            waitForHydration();
            awaitFigures("step 3: every row reads its own server",
                List.of("12 of 20 players online", "3 of 10 players online"));
            assertThat(InstanceFigureHandler.openedLinks() - linksBefore).as("step 3: the list opened one link")
                .isEqualTo(1);
            assertThat(List.of(InstanceFigures.viewers(alphaId), InstanceFigures.viewers(bravoId)))
                .as("step 3: which follows both rows").containsExactly(1, 1);

            // 4. The first server stops answering: within one interval and one probe its row says so, and no count
            //    from before stands in for it; the other row keeps reading.
            alpha.close();
            Now.setOffset(Now.offset().plus(InstanceFigures.INTERVAL));
            awaitFigures("step 4: a server that stopped answering says so",
                List.of("3 of 10 players online", "Not answering"));
            assertThat(page.locator("[data-live-figure='" + LiveFigureState.SILENT.token() + "']").count())
                .as("step 4: marked unanswered, not answered").isEqualTo(1);

            // 5. The first server's own state says stopped: its row says Stopped from that state, without a probe;
            //    past the longest unanswered wait, so the session's next round reads the row.
            var instances = Models.get(InstanceModel.class);
            Row stoppedRow = instances.findById(alphaId);
            stoppedRow.set(InstanceModel.STATUS, InstanceModel.STATUS_STOPPED);
            instances.save(stoppedRow);
            Now.setOffset(Now.offset().plus(Duration.ofMinutes(2)));
            awaitFigures("step 5: a stopped server says so from its own state",
                List.of("3 of 10 players online", "Stopped"));
            assertThat(page.locator("[data-live-figure='" + LiveFigureState.NOT_RUNNING.token() + "']").count())
                .as("step 5: marked not running, not unanswered").isEqualTo(1);

            // 6. A tenant's own list shows its own server's figure and nothing of another tenant's server.
            navigateToAppAs("/" + HohenheimSlugs.MANAGE + "/" + HohenheimSlugs.INSTANCES,
                sessionFor(bravoTenant).token());
            waitForHydration();
            awaitFigures("step 6: the tenant reads its own server", List.of("3 of 10 players online"));
            assertThat(page.content()).as("step 6: and never the other tenant's server")
                .doesNotContain(PREFIX + "alpha");

            // 7. The figure channel admits by the record: another tenant's server is refused as not permitted, the
            //    same as a server that does not exist, while its own server's figure arrives on the link.
            FakeChannelLink<Object, Object> link = new FakeChannelLink<>(HohenheimChannels.INSTANCE_FIGURE,
                "live-figure-tenant").principal(new UserPrincipal(bravoTenant, "Figure tenant bravo"));
            for (int asked : new int[]{alphaId, Integer.MAX_VALUE}) {
                Throwable refused = catchThrowable(() -> guarded(link).onOpen(asked));
                assertThat(ChannelException.reasonOf(refused))
                    .as("step 7: instance " + asked + " is refused as not permitted")
                    .isEqualTo(ZenitRefusalReason.PERMISSION_DENIED);
            }
            ChannelHandler<Object, Object> own = guarded(link);
            own.onOpen(bravoId);
            try {
                List<Object> frames = link.awaitSubmitted(frame -> frame instanceof Map<?, ?> map
                    && LiveFigureState.ANSWERING.token().equals(map.get(HohenheimFigureFunctions.STATE_KEY)), 1, WAIT);
                assertThat(((Map<?, ?>) frames.get(0)).get(HohenheimFigureFunctions.ONLINE_KEY))
                    .as("step 7: the tenant's own figure arrives").isEqualTo(3);
            } finally {
                own.onClose();
            }
            FakeChannelLink<Object, Object> otherLink = new FakeChannelLink<>(HohenheimChannels.INSTANCE_FIGURE,
                "live-figure-other").principal(new UserPrincipal(alphaTenant, "Figure tenant alpha"));
            Throwable reversed = catchThrowable(() -> guarded(otherLink).onOpen(bravoId));
            assertThat(ChannelException.reasonOf(reversed)).as("step 7: and the other way round")
                .isEqualTo(ZenitRefusalReason.PERMISSION_DENIED);

            // 8. The list's live column is read from each row's settings and the hub's memory, and its one link is
            //    admitted set-wise: one row and four rows cost the database the same, cells and admission together.
            navigateToApp("/admin");
            awaitNoViewers(alphaId, bravoId);
            gameServer(rows, PREFIX + "charlie", bravo.port());
            gameServer(rows, PREFIX + "delta", bravo.port());
            UUID lister = ApiSupport.user(PREFIX + "lister@hohenheim.local", "Figure lister");
            users.add(lister);
            for (Row row : rows) {
                RecordGrants.grant(GrantSubjectType.USER, lister, InstanceModel.MODEL_ID, row.get(InstanceModel.ID),
                    HohenheimCapabilities.MANAGE, true);
            }
            DatasourceProbe probe = DatasourceProbe.on((SqlDatasource) Datasources.getDefault());
            int oneRow = cellReads(probe, rows.subList(0, 1));
            int fourRows = cellReads(probe, rows);
            int oneAdmission = admissionReads(probe, lister, rows.subList(0, 1));
            int fourAdmissions = admissionReads(probe, lister, rows);
            reporter.publishEntry("instances.live_figure", "cell reads " + oneRow + " / " + fourRows
                + ", link admission reads " + oneAdmission + " / " + fourAdmissions + " (1 row / 4 rows)");
            assertThat(fourRows).as("step 8: four cells cost what one costs (" + oneRow + ")").isEqualTo(oneRow);
            assertThat(oneRow).as("step 8: which is nothing").isZero();
            assertThat(fourAdmissions).as("step 8: admitting four rows on the link costs what one costs ("
                + oneAdmission + ")").isEqualTo(oneAdmission);
        } finally {
            Now.setOffset(offset);
            navigateToApp("/admin");
            InstanceFigures.shutdown();
            alpha.close();
            bravo.close();
            for (Row row : rows) {
                PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, row.get(InstanceModel.ID));
                HardDeletes.row(Models.get(InstanceModel.class), row);
            }
            for (UUID user : users) {
                Models.get(UserModel.class).delete(user);
            }
            FakeDockerDaemon.restore();
            daemon.close();
        }
    }

    /** @return the database round trips of the live column's cells over {@code rows}, each cell drawn */
    private static int cellReads(DatasourceProbe probe, List<Row> rows) {
        List<LiveFigureView> cells = new ArrayList<>();
        probe.reset();
        for (Row row : rows) {
            cells.add(InstanceParts.liveFigureCellOf(row));
        }
        int reads = probe.roundTrips();
        assertThat(cells).as("every row that declares a probe draws a cell").doesNotContainNull();
        return reads;
    }

    /** @return the database round trips of opening one figure link over {@code rows} as {@code user} */
    private static int admissionReads(DatasourceProbe probe, UUID user, List<Row> rows) {
        FakeChannelLink<Object, Object> link = new FakeChannelLink<>(HohenheimChannels.INSTANCE_FIGURE,
            "live-figure-lister-" + rows.size()).principal(new UserPrincipal(user, "Figure lister"));
        ChannelHandler<Object, Object> handler = guarded(link);
        List<Object> ids = new ArrayList<>();
        for (Row row : rows) {
            ids.add(row.get(InstanceModel.ID));
        }
        probe.reset();
        handler.onOpen(ids);
        int reads = probe.roundTrips();
        handler.onClose();
        return reads;
    }

    /** @return a figure handler behind the channel's key guard, as the gateway hands one to a link */
    private static ChannelHandler<Object, Object> guarded(FakeChannelLink<Object, Object> link) {
        return HohenheimChannels.INSTANCE_FIGURE_KEYS.guard(link, InstanceFigureHandler::new);
    }

    /** Waits until the browser's links let go of the instances, so no session of theirs reads during a count. */
    private void awaitNoViewers(int... instanceIds) {
        try {
            page.waitForCondition(() -> {
                for (int instanceId : instanceIds) {
                    if (InstanceFigures.viewers(instanceId) > 0) {
                        return false;
                    }
                }
                return true;
            }, new Page.WaitForConditionOptions().setTimeout(WAIT.toMillis()));
        } catch (RuntimeException timedOut) {
            // The counts below still run; a lingering viewer shows up as an unequal count, named in the report.
        }
    }

    private void awaitFigures(String step, List<String> expected) {
        try {
            page.waitForCondition(() -> figures().equals(expected),
                new Page.WaitForConditionOptions().setTimeout(WAIT.toMillis()));
        } catch (RuntimeException timedOut) {
            // The assertion below names what the page said instead.
        }
        assertThat(figures()).as(step).isEqualTo(expected);
    }

    /** @return the words of every live figure on the page, sorted */
    private List<String> figures() {
        return page.locator("[data-live-figure]").allTextContents().stream().map(String::trim).sorted().toList();
    }

    /** A running Minecraft server instance on the local host, its game port published on {@code port} and held. */
    private static Row gameServer(List<Row> rows, String name, int port) {
        var instances = Models.get(InstanceModel.class);
        Row row = instances.createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "itzg/minecraft-server");
        settings.put(PublishedPort.FIELD, Map.of(PortPurpose.GAME.token(), port + ":25565/" + PublishedPort.TCP));
        settings.put(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING);
        row.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instances.save(row);
        rows.add(row);
        int id = row.get(InstanceModel.ID);
        if (rows.size() <= 2) {
            PortLedger.claim(ServerModel.localServerId(), null, port, PublishedPort.TCP, InstanceModel.MODEL_ID, id,
                "live figure journey");
        }
        return row;
    }

    /** @return a tenant holding {@code manage} on the instance */
    private static UUID tenant(List<UUID> users, String name, int instanceId) {
        UUID tenantId = ApiSupport.user(PREFIX + name + "@hohenheim.local", "Figure tenant " + name);
        users.add(tenantId);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, InstanceModel.MODEL_ID, instanceId,
            HohenheimCapabilities.MANAGE, true);
        return tenantId;
    }
}
