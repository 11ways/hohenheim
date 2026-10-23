package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimDeployFunctions;
import be.elevenways.hohenheim.HohenheimSources;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.build.BuildLog;
import be.elevenways.hohenheim.server.build.LiveBuildLogs;
import be.elevenways.hohenheim.server.util.KeyedConsumers;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.auth.model.GrantModel;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.channel.Channel;
import be.elevenways.zenit.common.channel.ChannelException;
import be.elevenways.zenit.common.channel.ChannelLink;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import be.elevenways.zenit.test.support.FakeChannelLink;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * How the deploy log reaches its viewers: steps only while the viewer is an operator, a followed build's end as its
 * final text, one live build per owner, and a link closing while a release publishes or a build starts or writes
 * never deadlocks.
 *
 * AIDEV-NOTE: the deadlock steps use {@link MonitorLink}, shaped like zenit's ServerChannelLink where it matters: its
 * close runs the handler's onClose UNDER the monitor its submit takes. Each step parks the publishing thread at the
 * door of that monitor until the closing thread holds it, the exact interleaving that hung when a registry lock was
 * held across a submit; latches with a bound prove both threads finish.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class DeployLogDeliveryTest extends HohenheimTestBase {

    private static final Duration BOUND = Duration.ofSeconds(10);

    @Test
    void aDeployLogFollowsItsViewerAndNeverDeadlocksAClosingLinkJourney() throws Exception {
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, "deploy-log-delivery");
        instance.set(InstanceModel.KIND, "hohenheim:docker_container");
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
        instance.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
        Models.get(InstanceModel.class).save(instance);
        int appId = instance.get(InstanceModel.ID);
        UUID operatorId = ApiSupport.user("deploy-log-delivery@hohenheim.local", "Deploy Log Operator");
        Principal operator = new UserPrincipal(operatorId, "Deploy Log Operator");
        RecordGrants.grant(GrantSubjectType.USER, operatorId, InstanceModel.MODEL_ID, appId,
            HohenheimCapabilities.VIEW, true);
        GrantService.createDirectGrant(GrantSubjectType.USER, operatorId, HohenheimSources.ADMIN_ACCESS.value(), true);
        List<KeyedConsumers.Registration> builds = new ArrayList<>();
        try {
            // 1. An operator hears the release's step log.
            FakeChannelLink<Object, Object> viewer = new FakeChannelLink<>(HohenheimChannels.DEPLOY_LOG,
                "deploy-log-delivery").principal(operator);
            DeployLogHandler handler = new DeployLogHandler(viewer);
            handler.onOpen(appId);
            DeployProgress.published(appId, "step one\n");
            assertThat(viewer.awaitSubmitted(steps("step one\n"), 1, BOUND))
                .as("step 1: the operator hears the published step log").hasSize(1);

            // 2. Demoted while the link is open, the next revalidation drops the step log: a publish reaches it no
            //    more (the delivery runs on the publishing thread, so its absence is decided when published returns).
            revokeAdminAccess(operatorId);
            assertThat(handler.readmitted()).as("step 2: still a viewer of the instance").isTrue();
            DeployProgress.published(appId, "step two\n");
            assertThat(viewer.submitted()).as("step 2: a demoted viewer hears no step frame")
                .noneMatch(steps("step two\n"));

            // 3. Promoted again, the next revalidation brings the step log back, opening with the stored one.
            GrantService.createDirectGrant(GrantSubjectType.USER, operatorId, HohenheimSources.ADMIN_ACCESS.value(),
                true);
            assertThat(handler.readmitted()).as("step 3: readmitted").isTrue();
            DeployProgress.published(appId, "step three\n");
            assertThat(viewer.awaitSubmitted(steps("step three\n"), 1, BOUND))
                .as("step 3: the operator hears steps again").hasSize(1);

            // 4. A link closing while a release publishes: the publisher waits on the link monitor the close holds,
            //    and the close detaches without waiting on any lock the publisher holds.
            MonitorLink publishing = MonitorLink.opened(operator, appId);
            racesClose(publishing, () -> DeployProgress.published(appId, "step four\n"), "a release publishing");

            // 5. A link closing while a build starts: the start reaches the link outside every registry lock.
            MonitorLink starting = MonitorLink.opened(operator, appId);
            BuildLog log = new BuildLog(4096);
            racesClose(starting, () -> builds.add(LiveBuildLogs.register(InstanceModel.MODEL_ID, appId, log)),
                "a build starting");
            assertThat(builds).as("step 5: the build registered").hasSize(1);
            assertThat(catchThrowable(() -> LiveBuildLogs.register(InstanceModel.MODEL_ID, appId, new BuildLog(1024))))
                .as("step 5: a second live build for the same owner is a bug, refused rather than replacing")
                .isInstanceOf(IllegalStateException.class);

            // 6. A link closing while the build writes: the delta reaches the link outside the log's monitor.
            MonitorLink writing = MonitorLink.opened(operator, appId);
            racesClose(writing, () -> log.line("compiling while a viewer leaves"), "a build writing");

            // 7. The viewer attached through the build hears it end: the tap drops and the final text replaces what
            //    the viewer holds.
            assertThat(viewer.awaitSubmitted(buildFrame("compiling while a viewer leaves\n", false), 1, BOUND))
                .as("step 7: the attached viewer heard the build write").hasSize(1);
            builds.remove(0).close();
            assertThat(viewer.awaitSubmitted(buildFrame(log.text(), true), 1, BOUND))
                .as("step 7: and its end, as a snapshot of the final text").hasSize(1);
            int heard = viewer.submitted().size();
            log.line("written after the end");
            assertThat(viewer.submitted()).as("step 7: an ended build's tap is dropped").hasSize(heard);
            handler.onClose();
            viewer.close();
        } finally {
            for (KeyedConsumers.Registration build : builds) {
                build.close();
            }
            revokeAdminAccess(operatorId);
            RecordGrants.revoke(GrantSubjectType.USER, operatorId, InstanceModel.MODEL_ID, appId,
                HohenheimCapabilities.VIEW);
            HardDeletes.byId(Models.get(InstanceModel.class), appId);
            Models.get(UserModel.class).delete(operatorId);
        }
    }

    /** Close {@code link} while {@code work} reaches it from another thread, both within the bound. */
    private static void racesClose(@NonNull MonitorLink link, @NonNull Runnable work, @NonNull String what)
            throws InterruptedException {
        link.gateNextSubmit();
        CountDownLatch workDone = new CountDownLatch(1);
        CountDownLatch closeDone = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JobRunner.startVirtualThread(() -> {
            try {
                work.run();
            } catch (Throwable failed) {
                failure.set(failed);
            } finally {
                workDone.countDown();
            }
        });
        JobRunner.startVirtualThread(() -> {
            try {
                if (link.awaitSubmitting()) {
                    link.close();
                }
            } catch (Throwable failed) {
                failure.set(failed);
            } finally {
                closeDone.countDown();
            }
        });
        assertThat(workDone.await(BOUND.toMillis(), TimeUnit.MILLISECONDS))
            .as(what + ": the work reaching the closing link finished").isTrue();
        assertThat(closeDone.await(BOUND.toMillis(), TimeUnit.MILLISECONDS))
            .as(what + ": the close finished").isTrue();
        assertThat(failure.get()).as(what + ": neither thread failed").isNull();
        assertThat(link.reachedTheDoor()).as(what + ": the work reached the link while it closed").isTrue();
        assertThat(link.isClosed()).as(what + ": the link closed").isTrue();
    }

    private static void revokeAdminAccess(@NonNull UUID userId) {
        for (Row grant : GrantService.listDirectGrants(GrantSubjectType.USER, userId)) {
            if (HohenheimSources.ADMIN_ACCESS.value().equals(grant.get(GrantModel.PERMISSION))) {
                GrantService.deleteDirectGrant(GrantSubjectType.USER, userId, grant.get(GrantModel.ID));
            }
        }
    }

    private static Predicate<Object> steps(@NonNull String text) {
        return frame -> frame instanceof Map<?, ?> map
            && HohenheimDeployFunctions.Source.STEP.token().equals(map.get(HohenheimDeployFunctions.SOURCE_KEY))
            && text.equals(map.get(HohenheimDeployFunctions.TEXT_KEY));
    }

    private static Predicate<Object> buildFrame(@NonNull String text, boolean snapshot) {
        return frame -> frame instanceof Map<?, ?> map
            && HohenheimDeployFunctions.Source.BUILD.token().equals(map.get(HohenheimDeployFunctions.SOURCE_KEY))
            && text.equals(map.get(HohenheimDeployFunctions.TEXT_KEY))
            && snapshot == Boolean.TRUE.equals(map.get(HohenheimDeployFunctions.SNAPSHOT_KEY));
    }

    /** A link whose close runs the handler's onClose under the monitor its submit takes, as ServerChannelLink's. */
    private static final class MonitorLink implements ChannelLink<Object, Object> {

        private final @NonNull Principal principal;
        private final List<Object> submitted = new ArrayList<>();
        private final CountDownLatch holding = new CountDownLatch(1);
        private volatile @Nullable CountDownLatch submitting;
        private @Nullable DeployLogHandler handler;
        private boolean closed;

        private MonitorLink(@NonNull Principal principal) {
            this.principal = principal;
        }

        /** @return a link whose handler opened on the instance, its opening frames delivered ungated */
        static @NonNull MonitorLink opened(@NonNull Principal principal, int instanceId) {
            MonitorLink link = new MonitorLink(principal);
            DeployLogHandler handler = new DeployLogHandler(link);
            link.handler = handler;
            handler.onOpen(instanceId);
            return link;
        }

        /** Park the next submit at the monitor's door until a close holds the monitor. */
        void gateNextSubmit() {
            this.submitting = new CountDownLatch(1);
        }

        boolean awaitSubmitting() throws InterruptedException {
            CountDownLatch gate = this.submitting;
            return gate != null && gate.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean reachedTheDoor() {
            CountDownLatch gate = this.submitting;
            return gate != null && gate.getCount() == 0;
        }

        @Override
        public void submit(@NonNull Object message) {
            CountDownLatch gate = this.submitting;
            if (gate != null && gate.getCount() > 0) {
                gate.countDown();
                try {
                    this.holding.await(BOUND.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (this) {
                if (this.closed) {
                    throw ChannelException.closedLink("monitor-link");
                }
                this.submitted.add(message);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (this.closed) {
                    return;
                }
                this.closed = true;
                this.holding.countDown();
                if (this.handler != null) {
                    this.handler.onClose();
                }
            }
        }

        @Override
        public synchronized boolean isOpen() {
            return !this.closed;
        }

        @Override
        public synchronized boolean isClosed() {
            return this.closed;
        }

        @Override
        public @NonNull Channel<Object, Object> getChannel() {
            return HohenheimChannels.DEPLOY_LOG;
        }

        @Override
        public @NonNull String getLinkId() {
            return "monitor-link";
        }

        @Override
        public @Nullable WebSocketSession getSession() {
            return null;
        }

        @Override
        public @Nullable Principal getPrincipal() {
            return this.principal;
        }

        @Override
        public void setAttribute(@NonNull String key, @Nullable Object value) {
        }

        @Override
        public @Nullable Object getAttribute(@NonNull String key) {
            return null;
        }
    }
}
