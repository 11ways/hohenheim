package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.proxy.ProxyReloadHooks;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.DnsFixtures;
import be.elevenways.zenit.test.support.MigratedDatabase;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.test.support.RuntimeStopProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Hohenheim stop ends every worker that reads the database before the runtime closes it: the real ServerMain boots
 * a proxy and a DNS secondary whose refresh is in flight, the runtime stops, and nothing logs a failure.
 *
 * AIDEV-NOTE: a forked JVM (RuntimeStopProbe.fork), never this one: the stop is the process's last act, and only a
 * process of its own shows what its workers print once the datasource is gone. The fork prints a marker before the
 * stop, and everything after it is what the stop caused. The primary peer of the secondary zone is a UDP socket that
 * hears the refresh's SOA query and never answers, so the refresh is provably in flight when the stop comes.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ShutdownOrderJourneyTest {

    private static final String STOP_BEGIN = "SHUTDOWN_JOURNEY_STOP_BEGIN";
    private static final String PROXY_STATE = "SHUTDOWN_JOURNEY_PROXY_STATE=";
    private static final String LINGERING = "SHUTDOWN_JOURNEY_LINGERING=";
    private static final String DONE = "SHUTDOWN_JOURNEY_DONE";

    /** The workers Hohenheim's stop must end: the lanes that read the database, by thread-name prefix. */
    private static final List<String> WORKERS = List.of("dns-secondary", "dns-notify", "acme-renewal",
        "hohenheim-boot-recovery");

    /** What a stop that closed the datasource under a running worker prints, and any other failure or trace. */
    private static final List<String> FAILURES = List.of("Datasource is required but was null", "ERROR",
        "Exception in thread", "Caused by:", "\tat ", "shutdown_step_failed", "Failed to stop", "reload failed",
        "still ran", "pools_unterminated");

    @Test
    void aStopEndsEveryDatabaseWorkerBeforeTheDatasourceCloses(@TempDir Path directory) throws Exception {
        // The fork restores its database from the machine's migrated template, as every in-JVM class does.
        RuntimeStopProbe.Outcome outcome = RuntimeStopProbe.fork(Probe.class, directory.resolve("shutdown.log"));
        String output = outcome.output();

        // 1. The fork booted the real ServerMain, its secondary refresh reached the primary, and the stop ran.
        assertThat(outcome.exitCode()).as("step 1: the fork exited cleanly; its output:\n%s", output).isZero();
        assertThat(outcome.truncated()).as("step 1: the whole output was kept").isFalse();
        assertThat(output).as("step 1: the stop ran and the fork reported after it").contains(STOP_BEGIN, DONE);

        // 2. The stop ended the proxy and every database worker: none of their threads outlived it.
        assertThat(output).as("step 2: the proxy is stopped").contains(PROXY_STATE + ProxyServer.State.STOPPED);
        assertThat(output).as("step 2: no database worker outlived the stop").contains(LINGERING + "[]");

        // 3. Nothing the stop caused logged a failure: no worker and no late proxy reload read a closed datasource.
        List<String> failed = new ArrayList<>();
        for (String line : output.substring(output.indexOf(STOP_BEGIN)).split("\n")) {
            if (FAILURES.stream().anyMatch(line::contains)) {
                failed.add(line);
            }
        }
        assertThat(failed).as("step 3: the stop logged no failure").isEmpty();
    }

    /** The fork's entry point: boot, wait for the refresh to reach the primary, stop, report. */
    public static final class Probe {

        public static void main(String[] args) throws Exception {
            JobRunner listening = JobRunner.create("silent-primary");
            try (DatagramSocket primary = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                CountDownLatch queried = new CountDownLatch(1);
                listening.startBlockingThread(() -> hear(primary, queried));

                // Proxy and DNS on, every other role off; a private database holding a secondary zone whose primary
                // is the silent socket above (no TCP listener behind it).
                HohenheimSettingsBoot.forceDefinitions();
                for (HohenheimRoles.Role role : HohenheimRoles.Role.values()) {
                    Zenit.SETTINGS_VALUES.setValue(role.setting(),
                        role == HohenheimRoles.Role.PROXY || role == HohenheimRoles.Role.DNS);
                }
                Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Proxy.HTTP_PORT, 0);
                // ServerMain opens what database.url names, as on a host: here a migrated file the fork's exit leaves.
                MigratedDatabase database = MigratedDatabase.sqliteFile("hohenheim.db");
                database.beforeAll(null);
                ServerSettings.VALUES.setValue(ServerSettings.Database.URL, database.location().url());
                HohenheimDatabase.init();
                ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);
                ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.BOOT,
                    () -> secondaryZone(primary.getLocalPort()));

                ServerMain.main(new String[0]);
                if (!queried.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the secondary refresh never queried its primary");
                }

                System.out.println(STOP_BEGIN);
                ServerZenitRuntime.stop();
                // A write landing during the stop reloads the proxy after its commit: it must change nothing now.
                ProxyReloadHooks.reload();
                awaitSecondaryRefresh();
                ProxyServer proxy = ServerMain.getProxyServer();
                System.out.println(PROXY_STATE + (proxy == null ? "ABSENT" : proxy.getState()));
                System.out.println(LINGERING + lingeringWorkers());
                System.out.println(DONE);
            }
            System.exit(0);
        }

        /** Waits for the primary's first query; it is never answered. */
        private static void hear(DatagramSocket primary, CountDownLatch queried) {
            try {
                primary.receive(new DatagramPacket(new byte[512], 512));
                queried.countDown();
            } catch (Exception closed) {
                // The probe is ending.
            }
        }

        /** A secondary zone pulling from the silent primary. */
        private static void secondaryZone(int primaryPort) {
            DnsPeerModel peers = Models.get(DnsPeerModel.class);
            Row peer = peers.createEmptyRow();
            peer.set(DnsPeerModel.NAME, "silent-primary");
            peer.set(DnsPeerModel.TRANSFER_HOST, "127.0.0.1");
            peer.set(DnsPeerModel.TRANSFER_PORT, primaryPort);
            peer.set(DnsPeerModel.ENABLED, true);
            peers.save(peer);
            DnsFixtures.createZone("shutdown.example", DnsZoneModel.ROLE_SECONDARY, peer.get(DnsPeerModel.ID));
        }

        /**
         * Gives a secondary refresh a stop left running the time its blocked SOA query needs to end and write into
         * the closed datasource, so that failure prints; a correct stop left none, and this returns at once.
         */
        private static void awaitSecondaryRefresh() throws InterruptedException {
            for (Thread thread : workerThreads()) {
                if (thread.getName().startsWith("dns-secondary")) {
                    thread.join(10_000);
                }
            }
        }

        /**
         * @return the names of the database workers' threads still alive once each had a moment to end: a pool
         *         reports its termination just before its last thread returns
         */
        private static List<String> lingeringWorkers() throws InterruptedException {
            List<String> names = new ArrayList<>();
            for (Thread thread : workerThreads()) {
                thread.join(5_000);
                if (thread.isAlive()) {
                    names.add(thread.getName());
                }
            }
            names.sort(String::compareTo);
            return names;
        }

        /** The threads of the database workers: a serial lane's carries the prefix, a pool's the prefix and a dash. */
        private static List<Thread> workerThreads() {
            List<Thread> threads = new ArrayList<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                String name = thread.getName();
                if (WORKERS.stream().anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + "-"))) {
                    threads.add(thread);
                }
            }
            return threads;
        }
    }
}
