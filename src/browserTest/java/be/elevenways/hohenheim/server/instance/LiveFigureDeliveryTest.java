package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimFigureFunctions;
import be.elevenways.hohenheim.instance.LiveFigureState;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.InstanceParts;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.hohenheim.test.MonitorLink;
import be.elevenways.hohenheim.test.instance.FakeMinecraftServer;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.common.time.Pace;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.ResourceList;
import be.elevenways.zenit.common.channel.ChannelHandler;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.FakeChannelLink;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How live figures reach a figure link: a viewer joining a held figure never deadlocks a link closing meanwhile, a
 * session that ends while a link follows it lets that link subscribe the instance again, and no instance list pages
 * more rows than one link follows.
 *
 * AIDEV-NOTE: the deadlock step uses {@link MonitorLink} (its close runs its onClose under the monitor its submit
 * takes, as zenit's ServerChannelLink does) and detaches another viewer of the same session from that onClose, the
 * lock cycle a held figure handed over under the session's monitor closed.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class LiveFigureDeliveryTest extends HohenheimHttpTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final String PREFIX = "live-figure-delivery-";

    @Test
    void aJoiningViewerNeverDeadlocksAClosingLinkAndAnEndedSessionIsFollowedAgainJourney() throws Exception {
        Duration offset = Now.offset();
        List<Row> rows = new ArrayList<>();
        UUID viewerId = ApiSupport.user(PREFIX + "viewer@hohenheim.local", "Figure delivery viewer");
        FakeMinecraftServer server = FakeMinecraftServer.start(6, 12);
        AtomicReference<InstanceFigures.Subscription> joined = new AtomicReference<>();
        try {
            Row row = LiveFigureSessionTest.gameServer(rows, PREFIX + "held", server.port());
            int instanceId = row.get(InstanceModel.ID);
            RecordGrants.grant(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, instanceId,
                HohenheimCapabilities.VIEW, true);
            UserPrincipal viewer = new UserPrincipal(viewerId, "Figure delivery viewer");

            // 1. A first viewer follows the instance: its session holds an answered figure.
            LinkedBlockingQueue<InstanceFigures.Figure> seen = new LinkedBlockingQueue<>();
            InstanceFigures.Subscription first = InstanceFigures.subscribe(List.of(row), seen::add).get(instanceId);
            assertThat(first).as("step 1: the instance is followed").isNotNull();
            LiveFigureSessionTest.await(seen, figure -> figure.state() == LiveFigureState.ANSWERING,
                "step 1: the session holds an answered figure");

            // 2. A link joins that session while it closes, and its close detaches the first viewer from the same
            //    session: the held figure reaches the link's door outside the session's monitor, so both finish.
            MonitorLink closing = new MonitorLink(viewer, HohenheimChannels.INSTANCE_FIGURE).onClose(first::close);
            MonitorLink.racesClose(closing, () -> joined.set(InstanceFigures.subscribe(List.of(row),
                figure -> closing.submit(figure.toMap())).get(instanceId)), "step 2: a viewer joining a held figure");
            assertThat(joined.get()).as("step 2: the joining viewer got its subscription").isNotNull();
            assertThat(InstanceFigures.viewers(instanceId)).as("step 2: and is the session's one viewer left")
                .isEqualTo(1);
            joined.getAndSet(null).close();

            // 3. A figure link follows the instance and hears it.
            FakeChannelLink<Object, Object> link = new FakeChannelLink<>(HohenheimChannels.INSTANCE_FIGURE,
                PREFIX + "link").principal(viewer);
            AtomicReference<InstanceFigureHandler> made = new AtomicReference<>();
            ChannelHandler<Object, Object> guarded = HohenheimChannels.INSTANCE_FIGURE_KEYS.guard(link, guardedLink -> {
                made.set(new InstanceFigureHandler(guardedLink));
                return made.get();
            });
            InstanceFigureHandler handler = made.get();
            guarded.onOpen(instanceId);
            try {
                link.awaitSubmitted(state(LiveFigureState.ANSWERING), 1, WAIT);
                assertThat(handler.follows(instanceId)).as("step 3: the link follows the instance").isTrue();

                // 4. The instance's probe is removed: its session says there is no figure and ends, and the link
                //    lets go of the id instead of holding it admitted with no session behind it.
                Map<String, Object> settings = LiveFigureSessionTest.settingsOf(instanceId);
                settings.remove(StatusProbe.SETTING);
                LiveFigureSessionTest.save(instanceId, settings);
                awaitSkippingWaits(() -> !handler.follows(instanceId));
                assertThat(handler.follows(instanceId)).as("step 4: the ended session's id is let go").isFalse();
                link.awaitSubmitted(state(LiveFigureState.SILENT), 1, WAIT);

                // 5. The probe comes back and the page names the instance again: the link subscribes it afresh.
                settings.put(StatusProbe.SETTING, StatusProbe.MINECRAFT_JAVA.token());
                LiveFigureSessionTest.save(instanceId, settings);
                guarded.onMessage(List.of(instanceId));
                assertThat(handler.follows(instanceId)).as("step 5: the link follows the instance again").isTrue();
                assertThat(InstanceFigures.asking(instanceId)).as("step 5: through a live session").isTrue();
                assertThat(link.awaitSubmitted(state(LiveFigureState.ANSWERING), 2, WAIT))
                    .as("step 5: and hears it again").hasSize(2);
            } finally {
                guarded.onClose();
                link.close();
            }
        } finally {
            InstanceFigures.Subscription left = joined.get();
            if (left != null) {
                left.close();
            }
            Now.setOffset(offset);
            InstanceFigures.shutdown();
            server.close();
            for (Row row : rows) {
                RecordGrants.revoke(GrantSubjectType.USER, viewerId, InstanceModel.MODEL_ID, row.get(InstanceModel.ID),
                    HohenheimCapabilities.VIEW);
            }
            LiveFigureSessionTest.cleanup(rows);
            Models.get(UserModel.class).delete(viewerId);
        }
    }

    @Test
    void noInstanceListPagesMoreRowsThanOneFigureLinkFollows() {
        // The page's one shared figure link names every figure the page shows, and a list page's rows are what it
        // shows at most.
        for (PanelResource<Row> resource : List.of(InstanceParts.admin(), InstanceParts.manage())) {
            ResourceList<Row> list = Objects.requireNonNull(resource.list(), "an instance list");
            assertThat(list.table().pageSize()).as(resource.id() + " pages fewer rows than one link follows")
                .isLessThan(HohenheimChannels.INSTANCE_FIGURE_KEYS.maxKeys());
        }
    }

    private static Predicate<Object> state(LiveFigureState state) {
        return frame -> frame instanceof Map<?, ?> map
            && state.token().equals(map.get(HohenheimFigureFunctions.STATE_KEY));
    }

    /** Waits for {@code done}, skipping each wait between probes by moving Now's offset; the bound counts polls. */
    private static void awaitSkippingWaits(BooleanSupplier done) throws InterruptedException {
        for (long polls = WAIT.toMillis() / 100; polls > 0 && !done.getAsBoolean(); polls--) {
            Now.setOffset(Now.offset().plus(InstanceFigures.INTERVAL));
            Pace.sleep(Duration.ofMillis(100));
        }
    }
}
