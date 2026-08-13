package be.elevenways.hohenheim.server.task;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusKernelIsolation;
import be.elevenways.hohenheim.server.incus.IncusTransport;
import be.elevenways.hohenheim.server.incus.IncusWebSocket;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.util.Http11;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.comms.CommsChannels;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolation is a SECURITY BOUNDARY, so a sweep that cannot confirm it -- or that had to cut
 * a workload off to keep one -- must reach an operator, not a log file.
 *
 * AIDEV-NOTE: both sweeps used to report every one of those states through Blast.log and
 * nothing else: no throw (so the run recorded COMPLETED and AttentionCollector.failedTasks
 * structurally could not fire), no Alerts reference, no isolation collector and no isolation
 * notification event. This class pins the operator-reachable artifact, not the log line;
 * asserting on log output here would be re-asserting the defect.
 */
class IsolationVisibilityTest {

    private static final String HOST = "daystrom";
    private static final String HANDLE = "hoh-instance-7";
    private static final String LIVE_TAP = "tapc97b9701";

    private static HttpServer receiver;
    private static SystemTaskHistoryModel registeredHistory;
    private static final AtomicInteger DELIVERIES = new AtomicInteger();

    @BeforeAll
    static void setUp() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            DELIVERIES.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();

        // Inline delivery, the CertExpiryAlertTest shape: the receiver's count is settled
        // by the time publish() returns, so no polling and no sleeping.
        Comms.install(new CommsDispatcher(Map.of(
            CommsChannels.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));

        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row channel = channels.createEmptyRow();
        channel.set(NotificationChannelModel.NAME, "isolation-watch");
        channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        channel.set(NotificationChannelModel.URL,
            "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook");
        // Subscribed to the isolation event ONLY: a channel that receives this because it
        // subscribes to everything would prove nothing about the event's existence.
        channel.set(NotificationChannelModel.EVENTS,
            List.of(NotificationEvents.WORKLOAD_ISOLATION));
        channels.save(channel);
    }

    @AfterAll
    static void tearDown() throws Exception {
        Comms.install(null);
        if (receiver != null) {
            receiver.stop(0);
        }
        IsolationFindings.forgetTransitionStateForTest(null);
        if (registeredHistory != null) {
            Models.unregisterInstance(registeredHistory);
            registeredHistory = null;
        }
        TestDatabases.freshDatabase();
    }

    @Test
    void anUnconfirmedOrContainedHostReachesAnOperatorAndNotJustTheLog() {
        IsolationFindings.forgetTransitionStateForTest(null);
        DELIVERIES.set(0);

        // 1. A clean sweep says nothing and fails nothing. A collector that always fires is
        //    the same defect as one that never does.
        assertThatCode(() -> VerifyWorkloadIsolation.report(List.of(
                new VerifyWorkloadIsolation.HostOutcome(HOST, true, List.of("site-a"),
                    List.of(), List.of(), List.of()))).publish())
            .as("step 1: a fully enforced host must not fail the run")
            .doesNotThrowAnyException();
        assertThat(DELIVERIES.get()).as("step 1: and must not alert").isZero();

        // 2. "This host's workloads' isolation is UNCONFIRMED" -- enforcement is off while
        //    workloads run. It FAILS the run (which is what puts it on the dashboard) and
        //    it reaches a person the first time.
        assertThatThrownBy(() -> VerifyWorkloadIsolation.report(List.of(
                new VerifyWorkloadIsolation.HostOutcome(HOST, false, List.of(), List.of(),
                    List.of(), List.of("per-workload enforcement is off")))).publish())
            .as("step 2: an unconfirmed host must fail the task run")
            .isInstanceOf(IsolationFindings.IsolationUnresolved.class)
            .hasMessageContaining("UNCONFIRMED")
            .hasMessageContaining(HOST);
        assertThat(DELIVERIES.get())
            .as("step 2: and must notify an operator, not only the log")
            .isEqualTo(1);

        // 3. The same unresolved state on the next five-minute tick still fails the run --
        //    the dashboard item must not disappear -- but does NOT re-notify. 288 identical
        //    messages a day is how a channel stops being read.
        assertThatThrownBy(() -> VerifyWorkloadIsolation.report(List.of(
                new VerifyWorkloadIsolation.HostOutcome(HOST, false, List.of(), List.of(),
                    List.of(), List.of("per-workload enforcement is off")))).publish())
            .as("step 3: an unresolved state keeps failing the run")
            .isInstanceOf(IsolationFindings.IsolationUnresolved.class);
        assertThat(DELIVERIES.get())
            .as("step 3: but a persistent state alerts on the transition only")
            .isEqualTo(1);

        // 4. CONTAINMENT is the sharp end: a tenant just lost availability so its
        //    neighbours would keep their boundary. That alerts every single run.
        assertThatThrownBy(() -> VerifyWorkloadIsolation.report(List.of(
                new VerifyWorkloadIsolation.HostOutcome(HOST, true, List.of(), List.of(),
                    List.of("stopped instance " + HANDLE),
                    List.of("containment failed: daemon refused")))).publish())
            .as("step 4: a contained workload must fail the run")
            .isInstanceOf(IsolationFindings.IsolationUnresolved.class)
            .hasMessageContaining(HANDLE);
        assertThat(DELIVERIES.get())
            .as("step 4: and must always notify")
            .isEqualTo(2);
        assertThatThrownBy(() -> VerifyWorkloadIsolation.report(List.of(
                new VerifyWorkloadIsolation.HostOutcome(HOST, true, List.of(), List.of(),
                    List.of("stopped instance " + HANDLE), List.of()))).publish())
            .isInstanceOf(IsolationFindings.IsolationUnresolved.class);
        assertThat(DELIVERIES.get())
            .as("step 4: containment is never damped")
            .isEqualTo(3);

        // 5. The Incus sweep is the same mechanism, so its findings publish identically.
        IsolationFindings.forgetTransitionStateForTest(null);
        assertThatThrownBy(() -> VerifyIncusIsolation.report(List.of(
                new VerifyIncusIsolation.HostOutcome(HOST, true, List.of(), List.of(),
                    List.of(HANDLE), List.of()))).publish())
            .as("step 5: the Incus tier must publish through the same lane")
            .isInstanceOf(IsolationFindings.IsolationUnresolved.class);
        assertThat(DELIVERIES.get()).as("step 5: and reach the same channel").isEqualTo(4);

        // 6. The dashboard half: a failed run of either sweep becomes an attention item.
        //    This is the projection the executor's throw is FOR -- before it, the task
        //    always recorded success, so this collector could never see it.
        registerTaskHistoryIfAbsent();
        recordFailedRun(VerifyWorkloadIsolation.class.getName());
        List<AttentionItem> items = new ArrayList<>();
        AttentionCollector.failedTasks(items);
        assertThat(items)
            .as("step 6: the failed isolation sweep must reach the dashboard")
            .anySatisfy(item -> assertThat(item.title().args().get("name"))
                .isEqualTo(VerifyWorkloadIsolation.class.getName()));
    }

    /**
     * The daemon losing an interface NAME is not the same as a host refusing to answer, and
     * conflating them let a running guest keep connectivity while the sweep declined to
     * judge it -- no repair, no stop, and no way to tell the two apart.
     */
    @Test
    void aWorkloadWhoseInterfaceTheDaemonCannotNameIsRepairedThenContained() throws Exception {
        // 1. No live interface for a running workload is its OWN signal, not a generic
        //    unreadable-kernel IOException.
        FakeDaemon nameless = new FakeDaemon(null);
        IncusKernelIsolation blind = new IncusKernelIsolation(nameless, enforcingKernel());
        assertThatThrownBy(() -> blind.inspect(HANDLE))
            .as("step 1: an unnameable interface must be distinguishable by TYPE")
            .isInstanceOf(IncusKernelIsolation.NoLiveInterface.class);

        // 2. An unreadable KERNEL stays what it always was: a plain IOException, and never
        //    the new signal. The host-level "refusing to answer is not evidence of a leak"
        //    rule must survive this change untouched.
        IncusKernelIsolation unreadable = new IncusKernelIsolation(
            new FakeDaemon(LIVE_TAP), (args, stdin) -> new NftRunner.Result(1, "", "boom"));
        assertThatThrownBy(() -> unreadable.inspect(HANDLE))
            .as("step 2: an unreadable kernel must NOT become the new signal")
            .isInstanceOf(IOException.class)
            .isNotInstanceOf(IncusKernelIsolation.NoLiveInterface.class);

        // 3. REPAIR: the missing name is a divergence, so enforce() reloads the device --
        //    which is exactly what rebuilds the daemon's NIC accounting -- and returns
        //    clean. Before the fix nothing was reloaded and nothing was stopped.
        FakeDaemon healing = new FakeDaemon(null);
        healing.tapAfterReload = LIVE_TAP;
        assertThatCode(() -> new IncusKernelIsolation(healing, enforcingKernel()).enforce(HANDLE))
            .as("step 3: a reload that restores the interface is a repair")
            .doesNotThrowAnyException();
        assertThat(healing.reloads)
            .as("step 3: and the repair must actually have been attempted")
            .isEqualTo(1);

        // 4. CONTAIN: a workload the daemon still cannot name after its own reload is
        //    refused, which is the signal VerifyIncusIsolation stops it on.
        FakeDaemon stubborn = new FakeDaemon(null);
        assertThatThrownBy(() -> new IncusKernelIsolation(stubborn, enforcingKernel())
                .enforce(HANDLE))
            .as("step 4: an unnameable workload must be refused, not skipped")
            .isInstanceOf(IOException.class);
        assertThat(stubborn.reloads)
            .as("step 4: and only after the repair was tried")
            .isEqualTo(1);
    }

    // === helpers ==========================================================

    /** A kernel whose ruleset isolates {@link #LIVE_TAP} correctly. */
    private static NftRunner enforcingKernel() {
        StringBuilder ruleset = new StringBuilder("table bridge incus {\n  chain fwd {\n");
        for (String range : List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                "169.254.0.0/16")) {
            ruleset.append("    iifname \"").append(LIVE_TAP).append("\" ip daddr ")
                .append(range).append(" drop\n");
        }
        for (String range : List.of("fc00::/7", "fe80::/10")) {
            ruleset.append("    iifname \"").append(LIVE_TAP).append("\" ip6 daddr ")
                .append(range).append(" drop\n");
        }
        ruleset.append("  }\n}\n");
        return (args, stdin) -> new NftRunner.Result(0, ruleset.toString(), "");
    }

    /**
     * An Incus daemon that runs one workload with one NIC, whose host interface name it
     * may or may not currently know.
     */
    private static final class FakeDaemon extends IncusClient {

        private String tap;
        private String tapAfterReload;
        private int reloads;

        private FakeDaemon(String tap) {
            super(new UnusedTransport());
            this.tap = tap;
        }

        @Override
        public Map<String, Object> instance(String name) {
            Map<String, Object> nic = new LinkedHashMap<>();
            nic.put("type", "nic");
            Map<String, Object> devices = new LinkedHashMap<>();
            devices.put("eth0", nic);
            Map<String, Object> config = new LinkedHashMap<>();
            if (this.tap != null) {
                config.put("volatile.eth0.host_name", this.tap);
            }
            Map<String, Object> instance = new LinkedHashMap<>();
            instance.put("devices", devices);
            instance.put("config", config);
            return instance;
        }

        @Override
        public void updateInstance(String name, Map<String, Object> definition) {
            this.reloads++;
            if (this.tapAfterReload != null) {
                this.tap = this.tapAfterReload;
            }
        }
    }

    /** The wire this fake never uses: it answers in Java, above the transport. */
    private static final class UnusedTransport implements IncusTransport {

        @Override
        public Http11.Raw exchange(String method, String pathAndQuery, String jsonBody,
                                   long timeoutMs) {
            throw new UnsupportedOperationException("the fake daemon answers in Java");
        }

        @Override
        public Http11.Raw exchangeUpload(String method, String pathAndQuery, Path bodyFile,
                                         String contentType, Map<String, String> extraHeaders,
                                         long timeoutMs) {
            throw new UnsupportedOperationException("the fake daemon answers in Java");
        }

        @Override
        public Http11.Raw exchangeDownload(String method, String pathAndQuery, Path destination,
                                           long maxBytes, long timeoutMs) {
            throw new UnsupportedOperationException("the fake daemon answers in Java");
        }

        @Override
        public IncusWebSocket openWebSocket(String pathAndQuery, long connectTimeoutMs) {
            throw new UnsupportedOperationException("the fake daemon answers in Java");
        }

        @Override
        public String describe() {
            return "fake incus daemon";
        }
    }

    /**
     * The task service is stopped in this lane, so its datasource-scoped model is not
     * registered; the InstanceAttentionTest precedent registers it by hand. Only OURS is
     * unregistered afterwards, because a stale instance would outlive the datasource the
     * next class swaps in.
     */
    private static void registerTaskHistoryIfAbsent() {
        if (Models.get(SystemTaskHistoryModel.MODEL_ID) != null) {
            return;
        }
        registeredHistory = new SystemTaskHistoryModel(Datasources.getDefault());
        Models.registerInstance(registeredHistory);
    }

    private static void recordFailedRun(String typePath) {
        SystemTaskHistoryModel history = Models.get(SystemTaskHistoryModel.class);
        Row run = history.createEmptyRow();
        run.set(SystemTaskHistoryModel.TASK_TYPE, typePath);
        run.set(SystemTaskHistoryModel.STATUS, TaskStatus.FAILED.name());
        run.set(SystemTaskHistoryModel.STARTED_AT, Instant.now());
        run.set(SystemTaskHistoryModel.ENDED_AT, Instant.now());
        run.set(SystemTaskHistoryModel.ERROR, "isolation UNCONFIRMED");
        history.save(run);
    }
}
