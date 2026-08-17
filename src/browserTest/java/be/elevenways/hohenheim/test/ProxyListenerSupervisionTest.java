package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.task.SuperviseProxyListeners;
import be.elevenways.zenit.common.task.DefaultTaskContext;
import be.elevenways.zenit.comms.CommsChannel;
import be.elevenways.zenit.comms.server.Comms;
import be.elevenways.zenit.comms.server.CommsDispatcher;
import be.elevenways.zenit.comms.server.transport.TransportTypes;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Aug 04 2026 incident recovery at ProxyServer level: a FAILED listener is restarted
 * by supervision under an exponential backoff that bounds the INTERVAL (30s doubling to a
 * 1h cap), never the attempt count; the outage is VISIBLE without any force_ssl site; and
 * the listener-down alert fires exactly once per outage. Behaviour journeys with a
 * controlled clock -- no wall-clock sleeps drive the backoff.
 */
class ProxyListenerSupervisionTest {

    private static final long HALF_MINUTE = 30_000;
    private static final long ONE_HOUR = 60L * 60 * 1000;

    private static ProxyServer proxy;
    private static HttpServer receiver;

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        Zenit.getHawkeye().setClientScriptLocation("/cms.js");
    }

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        ServerMain.adoptProxyServer(null);
        if (receiver != null) {
            receiver.stop(0);
            receiver = null;
        }
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
    }

    @Test
    @Timeout(120)
    void supervisorHealsHttpsWithBoundedBackoffAndAlertsOnce() throws Exception {
        // Step 0: an alert receiver behind an admin-managed webhook channel, delivering
        // inline so hit counts are settled when superviseListeners returns.
        AtomicInteger alertHits = new AtomicInteger();
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            alertHits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        Comms.install(new CommsDispatcher(Map.of(
            CommsChannel.WEBHOOK, List.of(TransportTypes.create("webhook://default"))), 1, true));
        NotificationChannelModel channels = Models.get(NotificationChannelModel.class);
        Row channel = channels.createEmptyRow();
        channel.set(NotificationChannelModel.NAME, "listener-watch");
        channel.set(NotificationChannelModel.KIND, NotificationChannelModel.KIND_WEBHOOK);
        channel.set(NotificationChannelModel.FORMAT, NotificationChannelModel.FORMAT_GENERIC);
        channel.set(NotificationChannelModel.URL,
            "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook");
        channel.set(NotificationChannelModel.EVENTS, List.of(NotificationEvents.PROXY_LISTENER_DOWN));
        channels.save(channel);

        // Step 1: a certificate exists but the HTTPS port is already taken -> HTTPS start
        // FAILS while HTTP runs. This is the incident's observable state (443 dead, 80 fine).
        installCertificate("supervised.test");
        try (ServerSocket blocker = new ServerSocket(0)) {
            int blockedPort = blocker.getLocalPort();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, blockedPort);

            AtomicLong clock = new AtomicLong(System.currentTimeMillis());
            proxy = new ProxyServer();
            proxy.setClockForTesting(clock::get);
            proxy.start();

            assertThat(proxy.getHttpState())
                .as("step 1: HTTP is running -- exactly the state that hid the incident")
                .isEqualTo(ProxyServer.State.RUNNING);
            assertThat(proxy.getHttpsState())
                .as("step 1: HTTPS failed to start on the occupied port")
                .isEqualTo(ProxyServer.State.FAILED);
            assertThat(proxy.getState())
                .as("step 1: the combined dashboard state surfaces the dead HTTPS listener"
                    + " (pre-fix this accessor returned RUNNING: HTTP-only)")
                .isEqualTo(ProxyServer.State.FAILED);
            assertThat(proxy.getFailureReason())
                .as("step 1: the combined failure reason names the HTTPS failure")
                .isNotBlank();

            // Step 2: the outage is VISIBLE with ZERO force_ssl sites. The old surface
            // (httpsUnavailableWithForceSsl) is silent here -- that silence is the
            // observability defect this change fixes, not a vacuous assertion.
            ServerMain.adoptProxyServer(proxy);
            try {
                List<AttentionItem> oldSurface = new ArrayList<>();
                AttentionCollector.httpsUnavailableWithForceSsl(oldSurface);
                assertThat(oldSurface)
                    .as("step 2: the force_ssl-gated item stays silent without force_ssl sites")
                    .isEmpty();
                List<AttentionItem> newSurface = new ArrayList<>();
                AttentionCollector.failedProxyListeners(newSurface);
                assertThat(newSurface)
                    .as("step 2: the listener item fires on listener state alone")
                    .hasSize(1);
                assertThat(newSurface.get(0).severity())
                    .as("step 2: and it is an ERROR, not a note an operator can scroll past")
                    .isEqualTo("error");
            } finally {
                ServerMain.adoptProxyServer(null);
            }

            // Step 3: bounded supervision. The failed start recorded attempt 1 with a 30s gap.
            assertThat(proxy.getHttpsRestartAttemptsForTesting())
                .as("step 3: the failed start is restart attempt 1").isEqualTo(1);
            long next1 = proxy.getHttpsNextRestartAttemptAtForTesting();
            assertThat(next1 - clock.get())
                .as("step 3: attempt 1 arms a 30s backoff").isEqualTo(HALF_MINUTE);

            proxy.superviseListeners();
            assertThat(proxy.getHttpsRestartAttemptsForTesting())
                .as("step 3: a tick BEFORE the due time must not attempt (the volatile gate)")
                .isEqualTo(1);
            assertThat(alertHits.get())
                .as("step 3: no alert after a single failure").isZero();

            clock.set(next1);
            proxy.superviseListeners();
            assertThat(proxy.getHttpsRestartAttemptsForTesting())
                .as("step 3: the due tick attempts the restart, which fails again")
                .isEqualTo(2);
            long next2 = proxy.getHttpsNextRestartAttemptAtForTesting();
            assertThat(next2 - clock.get())
                .as("step 3: the gap doubles to 60s -- no hot loop").isEqualTo(2 * HALF_MINUTE);
            assertThat(alertHits.get())
                .as("step 3: the outage survived a supervised retry -> ONE alert")
                .isEqualTo(1);

            clock.set(next2);
            proxy.superviseListeners();
            assertThat(proxy.getHttpsNextRestartAttemptAtForTesting() - clock.get())
                .as("step 3: the gap doubles again to 120s").isEqualTo(4 * HALF_MINUTE);
            assertThat(alertHits.get())
                .as("step 3: further supervised failures never re-alert").isEqualTo(1);

            // Step 4: the backoff caps at 1h and RETRIES FOREVER -- it never gives up.
            long gap = 0;
            for (int i = 0; i < 12 && gap < ONE_HOUR; i++) {
                clock.set(proxy.getHttpsNextRestartAttemptAtForTesting());
                proxy.superviseListeners();
                gap = proxy.getHttpsNextRestartAttemptAtForTesting() - clock.get();
            }
            assertThat(gap).as("step 4: the backoff reaches the 1h ceiling").isEqualTo(ONE_HOUR);
            clock.set(proxy.getHttpsNextRestartAttemptAtForTesting());
            proxy.superviseListeners();
            assertThat(proxy.getHttpsNextRestartAttemptAtForTesting() - clock.get())
                .as("step 4: the ceiling holds -- attempts stay bounded in interval, not count")
                .isEqualTo(ONE_HOUR);
            assertThat(alertHits.get()).as("step 4: still exactly one alert").isEqualTo(1);
        }

        // Step 5: the port frees up (blocker closed); the next due supervised tick heals.
        AtomicLong clock = reclock(proxy);
        clock.set(proxy.getHttpsNextRestartAttemptAtForTesting());
        proxy.superviseListeners();
        assertThat(proxy.getHttpsState())
            .as("step 5: the supervised restart brings HTTPS back RUNNING")
            .isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.isHttpsTerminationAvailable())
            .as("step 5: HTTPS termination is available again").isTrue();
        assertThat(proxy.getState())
            .as("step 5: the combined state reads RUNNING").isEqualTo(ProxyServer.State.RUNNING);
        assertThat(proxy.getFailureReason())
            .as("step 5: no failure reason remains").isNull();
        assertThat(proxy.getHttpsRestartAttemptsForTesting())
            .as("step 5: recovery resets the restart bookkeeping").isZero();
        ServerMain.adoptProxyServer(proxy);
        try {
            List<AttentionItem> healed = new ArrayList<>();
            AttentionCollector.failedProxyListeners(healed);
            assertThat(healed).as("step 5: the attention item clears").isEmpty();
        } finally {
            ServerMain.adoptProxyServer(null);
        }

        proxy.stop();
        proxy = null;
    }

    @Test
    @Timeout(60)
    void httpListenerRidesTheSameBoundedRestartPath() {
        // Step 1: HTTP on a privileged port fails at start and records attempt 1.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        ProxyServer httpProxy = new ProxyServer();
        httpProxy.setClockForTesting(clock::get);
        httpProxy.start();
        try {
            assertThat(httpProxy.getHttpState())
                .as("step 1: HTTP fails on the privileged port")
                .isEqualTo(ProxyServer.State.FAILED);
            assertThat(httpProxy.getHttpRestartAttemptsForTesting())
                .as("step 1: the HTTP failure rides the shared bounded restart state")
                .isEqualTo(1);
            long next = httpProxy.getHttpNextRestartAttemptAtForTesting();
            assertThat(next - clock.get())
                .as("step 1: HTTP arms the same 30s initial backoff").isEqualTo(HALF_MINUTE);

            // Step 2: not due -> no attempt, even though the port setting is now fine.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
            httpProxy.superviseListeners();
            assertThat(httpProxy.getHttpState())
                .as("step 2: before the due time the supervisor must not touch HTTP")
                .isEqualTo(ProxyServer.State.FAILED);

            // Step 3: the due tick restarts HTTP and resets the bookkeeping.
            clock.set(next);
            httpProxy.superviseListeners();
            assertThat(httpProxy.getHttpState())
                .as("step 3: the due supervised tick restarts HTTP")
                .isEqualTo(ProxyServer.State.RUNNING);
            assertThat(httpProxy.getHttpRestartAttemptsForTesting())
                .as("step 3: recovery resets the HTTP restart bookkeeping").isZero();
        } finally {
            httpProxy.stop();
        }
    }

    /**
     * The WIRING, which the healing tests above cannot see: the minutely
     * {@link SuperviseProxyListeners} task must reach the live proxy through
     * {@code ServerMain}, or it records a successful run having supervised nothing.
     */
    @Test
    @Timeout(60)
    void theMinutelyTaskReachesTheAdoptedProxyAndHealsIt() {
        // Step 1: an HTTP listener that failed to start (privileged port), attempt 1 armed.
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 80);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTPS_PORT, 0);
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        ProxyServer taskProxy = new ProxyServer();
        taskProxy.setClockForTesting(clock::get);
        taskProxy.start();
        try {
            assertThat(taskProxy.getHttpState())
                .as("step 1: HTTP failed to start, so there is something to heal")
                .isEqualTo(ProxyServer.State.FAILED);
            long due = taskProxy.getHttpNextRestartAttemptAtForTesting();

            // Step 2: the restart would now succeed, and the retry is due -- but with no
            // proxy adopted the task supervises NOTHING. This is the executor's null
            // branch, and a run that reports success while doing nothing is exactly what
            // made the wiring invisible to the bootstrap test.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
            clock.set(due);
            ServerMain.adoptProxyServer(null);
            runSupervisionTask();
            assertThat(taskProxy.getHttpState())
                .as("step 2: with no adopted proxy the task cannot heal anything")
                .isEqualTo(ProxyServer.State.FAILED);
            assertThat(taskProxy.getHttpRestartAttemptsForTesting())
                .as("step 2: and it attempted no restart at all")
                .isEqualTo(1);

            // Step 3: the SAME task, with the proxy ServerMain holds in production, heals
            // the listener -- so the schedule's executor really is joined to the proxy.
            ServerMain.adoptProxyServer(taskProxy);
            runSupervisionTask();
            assertThat(taskProxy.getHttpState())
                .withFailMessage("step 3: the minutely task did not reach the adopted"
                    + " proxy -- the schedule exists but supervises nothing (state %s)",
                    taskProxy.getHttpState())
                .isEqualTo(ProxyServer.State.RUNNING);
            assertThat(taskProxy.getHttpRestartAttemptsForTesting())
                .as("step 3: and the healed listener reset its restart bookkeeping")
                .isZero();
        } finally {
            ServerMain.adoptProxyServer(null);
            taskProxy.stop();
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
        }
    }

    /** Run the scheduled task exactly as the task service does: its own executor. */
    private static void runSupervisionTask() {
        SuperviseProxyListeners task = new SuperviseProxyListeners();
        task.executor(new DefaultTaskContext(task));
    }

    /** The try-with-resources scope ends before step 5; rebind a clock the proxy already uses. */
    private static AtomicLong reclock(ProxyServer target) {
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        target.setClockForTesting(clock::get);
        return clock;
    }

    private static void installCertificate(String hostname) throws Exception {
        var certModel = Models.get(CertificateModel.class);
        KeyPair keyPair = TlsCertificateTest.generateKeyPair();
        X509Certificate cert = TlsCertificateTest.generateSelfSignedCert(keyPair, hostname);
        Row certRow = certModel.createEmptyRow();
        certRow.set(CertificateModel.NICE_NAME, "Supervision Test");
        certRow.set(CertificateModel.PROVIDER, "custom");
        certRow.set(CertificateModel.STATUS, "active");
        certRow.set(CertificateModel.CERTIFICATE_PEM, TlsCertificateTest.certToPem(cert));
        certRow.set(CertificateModel.PRIVATE_KEY_PEM, TlsCertificateTest.keyToPem(keyPair));
        certModel.save(certRow);
    }
}
