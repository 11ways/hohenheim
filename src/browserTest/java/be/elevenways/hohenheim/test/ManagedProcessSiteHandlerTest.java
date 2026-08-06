package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ProclogModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessGroupSupport;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.security.NftRunner;
import be.elevenways.hohenheim.server.security.ProcessNetworkPolicy;
import be.elevenways.hohenheim.server.sitetype.FaultedSiteHandler;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.server.sitetype.types.NodeSiteType;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Process-management hardening tests: requested-capacity budgeting, fail-fast validation,
 * proclog UPSERT flushing, and the re-armable ready latch. Uses real (idle) child processes.
 */
class ManagedProcessSiteHandlerTest {

    private static ProcessMonitor monitor;
    private static PortAllocator portAllocator;

    private final List<ManagedProcessSiteHandler> handlers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();

        monitor = new ProcessMonitor();
        portAllocator = new PortAllocator();
    }

    @AfterEach
    void reapHandlers() {
        ProcessNetworkPolicy.overrideForTest(null);
        for (ManagedProcessSiteHandler handler : handlers) {
            handler.destroy();
        }
        handlers.clear();
    }

    /** A managed handler that spawns an inert sleeping child. */
    private final class SleepHandler extends ManagedProcessSiteHandler {

        private final AtomicInteger spawnCount = new AtomicInteger();
        private final SystemUsers.RunAsUser runAs;

        SleepHandler(int siteId, Map<String, Object> settings) {
            this(siteId, settings, null);
        }

        SleepHandler(int siteId, Map<String, Object> settings, SystemUsers.RunAsUser runAs) {
            super(siteId, "proc-test-" + siteId, settings, portAllocator, monitor);
            this.runAs = runAs;
            handlers.add(this);
        }

        @Override
        protected SystemUsers.RunAsUser getRunAsUser() {
            return this.runAs;
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            spawnCount.incrementAndGet();
            return List.of("sh", "-c", "echo hello-from-child; sleep 600 & wait");
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    /** A managed handler whose child emits one diagnostic and fails during startup. */
    private final class StartupFailureHandler extends ManagedProcessSiteHandler {

        StartupFailureHandler(int siteId) {
            super(siteId, "startup-failure-" + siteId, settings(false, 1), portAllocator, monitor);
            handlers.add(this);
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            return List.of("sh", "-c", "echo startup-failure-diagnostic >&2; exit 23");
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    private static Map<String, Object> settings(boolean waitForReady, int minProcesses) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("wait_for_ready", waitForReady);
        settings.put("minimum_processes", minProcesses);
        return settings;
    }

    private static void awaitCondition(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    /**
     * The product lane of the process tier's isolation: nothing is spawned under a run-as
     * identity whose kernel policy did not land, and the policy that is applied is THIS
     * site's identity.
     */
    @Test
    void aSiteWithARunAsUidNeverSpawnsWhenItsIsolationCannotBeApplied() throws Exception {
        SystemUsers.RunAsUser runAs = new SystemUsers.RunAsUser("site-9130", 4242, 4242,
            System.getProperty("java.io.tmpdir"));
        List<String> applied = new CopyOnWriteArrayList<>();

        // 1. Enforcement OFF: the refusal happens before anything is built or spawned.
        ProcessNetworkPolicy.overrideForTest(new ProcessNetworkPolicy(
            (args, stdin) -> new NftRunner.Result(0, "", ""), () -> false,
            Path.of("/etc/resolv.conf")));
        SleepHandler unenforceable = new SleepHandler(9130, settings(false, 1), runAs);
        assertThat(unenforceable.startProcess())
            .as("step 1: a site whose isolation is not enforceable does not spawn").isNull();
        Thread.sleep(500);
        assertThat(unenforceable.spawnCount)
            .as("step 1: the refusal is before buildCommand, not after a failed exec")
            .hasValue(0);
        assertThat(unenforceable.runningProcessCount())
            .as("step 1: no child is running").isZero();

        // 2. Enforcement ON but the kernel rejects the ruleset: same outcome, and the
        //    ruleset the applier tried carries this site's uid.
        ProcessNetworkPolicy.overrideForTest(new ProcessNetworkPolicy((args, stdin) -> {
            if (stdin != null) {
                applied.add(stdin);
            }
            return new NftRunner.Result(1, "", "Error: could not process rule");
        }, () -> true, Path.of("/etc/resolv.conf")));
        SleepHandler rejected = new SleepHandler(9131, settings(false, 1), runAs);
        assertThat(rejected.startProcess())
            .as("step 2: a kernel that rejects the policy stops the spawn").isNull();
        assertThat(rejected.spawnCount).as("step 2: still nothing built").hasValue(0);
        assertThat(applied).as("step 2: the applier ran, keyed on THIS site's run-as uid")
            .hasSize(1);
        assertThat(applied.get(0))
            .contains("chain inet hohenheim_net out_uid_4242")
            .contains("meta skuid 4242 ip daddr 169.254.0.0/16 drop");

        // 3. A site with NO run-as user has no identity to key a policy on, so it keeps
        //    spawning even with enforcement off -- the tier's declared unisolated shape,
        //    which WorkloadIdentity refuses independently whenever it actually matters.
        ProcessNetworkPolicy.overrideForTest(new ProcessNetworkPolicy(
            (args, stdin) -> new NftRunner.Result(0, "", ""), () -> false,
            Path.of("/etc/resolv.conf")));
        SleepHandler daemonUser = new SleepHandler(9132, settings(false, 1));
        daemonUser.startMinimumServers();
        awaitCondition("child spawned", () -> daemonUser.runningProcessCount() == 1);
        assertThat(daemonUser.spawnCount)
            .as("step 3: a site without a run-as user still spawns").hasValue(1);
    }

    @Test
    void destroyReapsTheChildOsProcess() throws Exception {
        // The JVM shutdown hook funnels into handler.destroy(); this pins that a
        // destroy actually kills the spawned OS process instead of orphaning it.
        SleepHandler handler = new SleepHandler(9105, settings(false, 1));
        handler.startMinimumServers();
        awaitCondition("child spawned", () -> handler.runningProcessCount() == 1);

        ManagedProcess managed = handler.getProcesses().get(0);
        long pid = managed.pid();
        List<ProcessHandle> descendants = managed.process().descendants().toList();
        assertThat(ProcessHandle.of(pid)).as("child alive before destroy").isPresent();
        assertThat(descendants).as("wrapper has a real child process").isNotEmpty();

        handler.destroy();
        awaitCondition("child reaped",
            () -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
        awaitCondition("descendants reaped",
            () -> descendants.stream().noneMatch(ProcessHandle::isAlive));
        Thread.sleep(1_000);
        assertThat(handler.runningProcessCount()).isZero();
        assertThat(handler.spawnCount).hasValue(1);
    }

    @Test
    void processGroupTerminationReportsHelperFailuresWithoutRealSudo() throws Exception {
        Process wrapper = SystemUsers.executionBuilder(null,
            SystemUsers.safeEnvironment(System.getProperty("user.home")),
            List.of("sleep", "600"), true).start();
        List<Long> groups = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ProcessGroupSupport.Operator operator = new ProcessGroupSupport.Operator() {
            @Override
            public ProcessGroupSupport.SignalResult signal(SystemUsers.RunAsUser runAs,
                                                            long group,
                                                            ProcessGroupSupport.Signal signal) {
                calls.incrementAndGet();
                groups.add(group);
                return ProcessGroupSupport.SignalResult.FAILED;
            }

            @Override
            public ProcessGroupSupport.GroupState probe(SystemUsers.RunAsUser runAs, long group) {
                groups.add(group);
                return ProcessGroupSupport.GroupState.UNKNOWN;
            }
        };
        ManagedProcess managed = new ManagedProcess(wrapper, 0, null, 9110,
            new SystemUsers.RunAsUser("site", 4242, 4242, "/srv/site"), operator);

        assertThat(managed.kill()).isFalse();

        awaitCondition("wrapper reaped", () -> !wrapper.isAlive());
        assertThat(calls).hasValue(1);
        assertThat(groups).containsOnly(wrapper.pid());
        assertThat(managed.getLogText()).contains("termination incomplete")
            .contains("term=FAILED").contains("groupAfterTerm=UNKNOWN")
            .contains("finalGroup=UNKNOWN");
    }

    @Test
    void processGroupTerminationDoesNotKillAnAbsentGroup() throws Exception {
        Process wrapper = SystemUsers.executionBuilder(null,
            SystemUsers.safeEnvironment(System.getProperty("user.home")),
            List.of("sleep", "600"), true).start();
        List<ProcessGroupSupport.Signal> signals = new CopyOnWriteArrayList<>();
        ProcessGroupSupport.Operator operator = new ProcessGroupSupport.Operator() {
            @Override
            public ProcessGroupSupport.SignalResult signal(SystemUsers.RunAsUser runAs,
                                                            long group,
                                                            ProcessGroupSupport.Signal signal) {
                signals.add(signal);
                wrapper.destroy();
                return ProcessGroupSupport.SignalResult.DELIVERED;
            }

            @Override
            public ProcessGroupSupport.GroupState probe(SystemUsers.RunAsUser runAs, long group) {
                return ProcessGroupSupport.GroupState.ABSENT;
            }
        };
        ManagedProcess managed = new ManagedProcess(wrapper, 0, null, 9113, null, operator);

        assertThat(managed.kill()).isTrue();
        assertThat(signals).containsExactly(ProcessGroupSupport.Signal.TERM);
    }

    @Test
    void processGroupTerminationKillsAReparentedReplacement() throws Exception {
        Process wrapper = SystemUsers.executionBuilder(null,
            SystemUsers.safeEnvironment(System.getProperty("user.home")),
            List.of("/bin/sh", "-c", "trap '' HUP; sleep 600 >/dev/null 2>&1 & echo $!"),
            true).start();
        long childPid = Long.parseLong(new String(
            wrapper.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        assertThat(wrapper.waitFor(2, TimeUnit.SECONDS)).isTrue();
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
        try {
            assertThat(child.isAlive()).isTrue();
            assertThat(child.parent().map(ProcessHandle::pid).orElse(-1L)).isNotEqualTo(wrapper.pid());

            ManagedProcess managed = new ManagedProcess(wrapper, 0, null, 9112, null);
            assertThat(managed.kill()).isTrue();
            awaitCondition("reparented process-group member reaped", () -> !child.isAlive());
        } finally {
            if (child.isAlive()) {
                new ProcessBuilder("/usr/bin/kill", "-KILL", "--", "-" + wrapper.pid())
                    .start().waitFor();
            }
        }
    }

    /** Emits the runtime environment before sleeping so the explicit environment contract is observable. */
    private final class EnvironmentHandler extends ManagedProcessSiteHandler {

        EnvironmentHandler(int siteId, Map<String, Object> settings) {
            super(siteId, "environment-" + siteId, settings, portAllocator, monitor);
            handlers.add(this);
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            return List.of("sh", "-c",
                "printf 'PORT=%s\\nHOME=%s\\nSITE=%s\\nRUNTIME=%s\\n' "
                    + "\"$PORT\" \"$HOME\" \"$SITE_VALUE\" \"$RUNTIME_VALUE\"; sleep 600");
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of("RUNTIME_VALUE", "runtime-ok");
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    @Test
    void runtimeReceivesPortHomeAndExplicitSiteEnvironment() throws Exception {
        Map<String, Object> settings = settings(false, 1);
        settings.put("environment_variables", Map.of("SITE_VALUE", "site-ok"));
        EnvironmentHandler handler = new EnvironmentHandler(9111, settings);
        handler.startMinimumServers();

        awaitCondition("runtime environment emitted", () -> !handler.getProcesses().isEmpty()
            && handler.getProcesses().get(0).getLogText().contains("RUNTIME=runtime-ok"));
        String output = handler.getProcesses().get(0).getLogText();
        assertThat(output)
            .contains("PORT=")
            .contains("HOME=" + System.getProperty("user.home"))
            .contains("SITE=site-ok")
            .contains("RUNTIME=runtime-ok");
    }

    @Test
    void processOutputUsesPlatformThreadsForBlockingPipes() throws Exception {
        // Virtual-thread blocking reads on Process pipes pinned both carriers on a
        // 1-vCPU host and starved every virtual-thread task incl. the DNS
        // responders (2026-07-22 VPS incident) -- the pumps MUST stay platform.
        SleepHandler handler = new SleepHandler(9109, settings(false, 1));
        handler.startMinimumServers();
        awaitCondition("child spawned", () -> handler.runningProcessCount() == 1);

        long pid = handler.getProcesses().get(0).pid();
        List<Thread> outputThreads = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().startsWith("process-output-" + pid + "-"))
            .toList();

        assertThat(outputThreads).hasSize(2).allMatch(thread -> !thread.isVirtual());
    }

    @Test
    void repeatStartCallsDoNotOverstartWhileChildrenBoot() throws Exception {
        // wait_for_ready children never signal, so they stay "requested" forever:
        // a second start call past the debounce window must see that capacity.
        SleepHandler handler = new SleepHandler(9101, settings(true, 2));
        handler.startMinimumServers();
        assertThat(handler.runningProcessCount()).isEqualTo(2);
        assertThat(handler.requestedProcessCount()).isEqualTo(2);

        Thread.sleep(600);  // past the 500ms debounce
        handler.startMinimumServers();
        Thread.sleep(200);

        assertThat(handler.runningProcessCount())
            .as("booting (not yet ready) children count toward the minimum")
            .isEqualTo(2);
    }

    @Test
    void faultedHandlerForBlankAndMissingScript() {
        NodeSiteType type = new NodeSiteType();
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.ID, 9102);
        site.set(SiteModel.NAME, "faulted-test");

        SiteRequestHandler blank = type.createHandler(site, Map.of());
        assertThat(blank).isInstanceOf(FaultedSiteHandler.class);
        assertThat(((FaultedSiteHandler) blank).reason()).contains("no script");
        assertThat(blank.getHealth()).isEqualTo(SiteHealth.DOWN);

        SiteRequestHandler missing = type.createHandler(site,
            Map.of("script", "/nonexistent/path/app.js"));
        assertThat(missing).isInstanceOf(FaultedSiteHandler.class);
        assertThat(((FaultedSiteHandler) missing).reason()).contains("does not exist");
    }

    @Test
    void proclogFlushUpsertsOneRowPerProcess() throws Exception {
        var model = Models.get(ProclogModel.class);
        SleepHandler handler = new SleepHandler(9103, settings(false, 1));
        handler.startMinimumServers();

        awaitCondition("child output captured", () -> {
            List<ManagedProcess> procs = handler.getProcesses();
            return !procs.isEmpty() && procs.get(0).getLogText().contains("hello-from-child");
        });

        // First flush inserts the row...
        handler.flushProclogsNow();
        List<Row> afterFirst = model.find().where(ProclogModel.SITE_ID.eq(9103)).all();
        assertThat(afterFirst).hasSize(1);
        Integer rowId = afterFirst.get(0).get(ProclogModel.ID);
        assertThat((Integer) afterFirst.get(0).get(ProclogModel.LINE_COUNT)).isGreaterThanOrEqualTo(1);

        // ...and every later flush (and the exit persist) updates the SAME row.
        handler.flushProclogsNow();
        ManagedProcess proc = handler.getProcesses().get(0);
        proc.kill();
        // persistProclog runs at the start of processExit, before list cleanup.
        awaitCondition("exit cleanup ran", () -> handler.getProcesses().isEmpty());

        List<Row> afterExit = model.find().where(ProclogModel.SITE_ID.eq(9103)).all();
        assertThat(afterExit).as("flushes + exit persist must UPSERT one row").hasSize(1);
        assertThat((Integer) afterExit.get(0).get(ProclogModel.ID)).isEqualTo(rowId);
        assertThat((Object) afterExit.get(0).get(ProclogModel.SAVED_AT)).isNotNull();
    }

    @Test
    void startupFailurePersistsCapturedDiagnostics() throws Exception {
        var model = Models.get(ProclogModel.class);
        StartupFailureHandler handler = new StartupFailureHandler(9108);
        Thread starter = Thread.startVirtualThread(handler::startProcess);

        awaitCondition("startup failure diagnostic persisted", () -> model.find()
            .where(ProclogModel.SITE_ID.eq(9108))
            .all()
            .stream()
            .anyMatch(row -> String.valueOf(row.get(ProclogModel.LOG_HTML))
                .contains("startup-failure-diagnostic")));

        starter.interrupt();
        starter.join(1_000);
    }

    /** Spawns a child that signals ready over the TCP IPC socket in the LEGACY envelope. */
    private final class LegacyReadyHandler extends ManagedProcessSiteHandler {

        LegacyReadyHandler(int siteId, Map<String, Object> settings) {
            super(siteId, "legacy-ready-" + siteId, settings, portAllocator, monitor);
            handlers.add(this);
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            // The original Node IPC envelope ({"alchemy":{"ready":true}}) over the TCP
            // bridge -- exactly what a pre-migration alchemy child would emit, behind
            // the mandatory auth line.
            return List.of("node", "-e",
                "const net=require('net');"
                    + "const s=net.connect(parseInt(process.env.HOHENHEIM_IPC_PORT),'127.0.0.1',()=>{"
                    + "s.write(JSON.stringify({type:'auth',token:process.env.HOHENHEIM_IPC_TOKEN})+'\\n');"
                    + "s.write(JSON.stringify({alchemy:{ready:true}})+'\\n');"
                    + "});"
                    + "setInterval(()=>{},10000);");
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    @Test
    void legacyNestedReadyEnvelopeMarksTheChildReady() throws Exception {
        LegacyReadyHandler handler = new LegacyReadyHandler(9106, settings(true, 1));
        handler.startMinimumServers();

        awaitCondition("legacy ready envelope accepted",
            () -> handler.getHealth() == SiteHealth.UP);
        assertThat(handler.requestedProcessCount()).isEqualTo(0);
    }

    /** A wait_for_ready child that never signals must be killed at the ready timeout. */
    private final class NeverReadyHandler extends ManagedProcessSiteHandler {

        NeverReadyHandler(int siteId, Map<String, Object> settings) {
            super(siteId, "never-ready-" + siteId, settings, portAllocator, monitor);
            handlers.add(this);
        }

        @Override
        protected List<String> buildCommand(String listenTarget) {
            // 601, not 600: distinguishable from SleepHandler children when scanning descendants.
            return List.of("sh", "-c", "sleep 601");
        }

        @Override
        protected long readyTimeoutMs() {
            return 1_500;
        }

        @Override
        protected Map<String, String> buildRuntimeEnvironment(int port) {
            return Map.of();
        }

        @Override
        protected File getWorkingDirectory() {
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }

    @Test
    void neverReadyChildIsKilledAtTheReadyTimeout() throws Exception {
        NeverReadyHandler handler = new NeverReadyHandler(9107, settings(true, 1));
        handler.startMinimumServers();

        // A never-ready child never enters the READY process list, so track the OS
        // process itself (the distinctive "sleep 601" descendant).
        awaitCondition("child spawned", () -> findSleep601().isPresent());
        ProcessHandle child = findSleep601().orElseThrow();

        // The ready timeout (1.5s in this subclass) must reap it. Destroy first so the
        // crash-recovery respawn doesn't keep replacing what we are watching.
        awaitCondition("never-ready child reaped", () -> !child.isAlive());
    }

    private static java.util.Optional<ProcessHandle> findSleep601() {
        return ProcessHandle.current().descendants()
            .filter(p -> p.info().commandLine().map(c -> c.contains("sleep 601")).orElse(false))
            .findFirst();
    }

    @Test
    void readyLatchReArmsAfterFullOutage() throws Exception {
        SleepHandler handler = new SleepHandler(9104, settings(false, 1));
        handler.startMinimumServers();
        awaitCondition("first child ready", () -> handler.getHealth() == SiteHealth.UP);

        // Kill the only child: full outage must re-arm the startup queue.
        handler.getProcesses().get(0).kill();
        awaitCondition("outage observed", () -> handler.getHealth() != SiteHealth.UP);

        // Crash recovery (or a fresh start call) brings a new child up, and readiness
        // counts down the RE-ARMED latch — health returns to UP.
        awaitCondition("replacement child ready", () -> handler.getHealth() == SiteHealth.UP);
        assertThat(handler.requestedProcessCount()).isEqualTo(0);
    }
}
