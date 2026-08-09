package be.elevenways.hohenheim.test.process;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ProcessConfinement;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.process.ManagedProcess;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessCapacity;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Host-memory booking for managed child processes: the unit booked is the CHILD, the
 * amount booked is the amount the cgroup caps, and every exit path hands it back.
 *
 * AIDEV-NOTE: the tier auto-scales to five children per site on a 15s CPU timer, so a
 * per-RECORD booking would be a lie in both directions. What is asserted below is
 * therefore a delta per spawn and a delta per exit, never a total -- other tests in this
 * suite share the host row.
 */
class ProcessCapacityTest {

    private static ProcessMonitor monitor;
    private static PortAllocator portAllocator;
    private static int localServerId;

    private final List<ManagedProcessSiteHandler> handlers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        SiteTypes.boot();
        HohenheimEndpoints.init();
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        monitor = new ProcessMonitor();
        portAllocator = new PortAllocator();
        localServerId = ServerModel.localServerId();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB, 0);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO, 1.0);
    }

    @AfterEach
    void cleanUp() {
        for (ManagedProcessSiteHandler handler : handlers) {
            handler.destroy();
        }
        handlers.clear();
        // Hand back anything a refused/leaked path might still hold, so a failure in one
        // test cannot make the next one look like a budget bug.
        measureHost(null);
        ProcessCapacity.resetOn(localServerId);
    }

    /**
     * The booking journey: one charge appears per spawned child, and it is handed back on
     * a killed child and on a destroyed handler alike.
     */
    @Test
    void everyChildIsBookedAtItsCapAndReleasedOnExit() throws Exception {
        LiveLane.require(LiveLane.Need.CONFINEMENT, ProcessConfinement.availability().enforceable(),
            "SKIPPED: a declared cap needs an enforceable host ("
            + ProcessConfinement.availability().reason() + ")");

        long baseline = ProcessCapacity.bookedMbOn(localServerId);
        ManagedProcessSiteHandler handler = handler(4901, 64);

        // 1. One spawned child books exactly its declared cap.
        ManagedProcess first = handler.startProcess();
        assertThat(first).as("step 1: the child must actually start").isNotNull();
        assertThat(ProcessCapacity.bookedMbOn(localServerId) - baseline)
            .as("step 1: one child books exactly its declared 64 MB")
            .isEqualTo(64);

        // 2. A SECOND child of the same site books again -- the site is not the unit.
        ManagedProcess second = handler.startProcess();
        assertThat(second).as("step 2: the second child must start").isNotNull();
        assertThat(ProcessCapacity.bookedMbOn(localServerId) - baseline)
            .as("step 2: two children of one site book twice, not once")
            .isEqualTo(128);

        // 3. Killing one child hands exactly one charge back.
        second.kill();
        awaitBooked(baseline + 64);
        assertThat(ProcessCapacity.bookedMbOn(localServerId) - baseline)
            .as("step 3: a killed child releases its own booking and only its own")
            .isEqualTo(64);

        // 4. Destroying the handler hands the rest back -- no leak on the shutdown path,
        //    which also fires each child's exit callback (a double release would show as
        //    a negative delta clamped to 0 plus a quota drift slog).
        handler.destroy();
        awaitBooked(baseline);
        assertThat(ProcessCapacity.bookedMbOn(localServerId) - baseline)
            .as("step 4: destroy releases the remaining bookings exactly once")
            .isZero();
    }

    /** A site that declares no limit is capped by nothing AND booked for nothing. */
    @Test
    void anUndeclaredSiteBooksNothing() throws Exception {
        long baseline = ProcessCapacity.bookedMbOn(localServerId);
        ManagedProcessSiteHandler handler = handler(4902, null);

        ManagedProcess child = handler.startProcess();
        assertThat(child).as("an unbounded child still starts").isNotNull();
        assertThat(ProcessCapacity.bookedMbOn(localServerId) - baseline)
            .as("a cap that does not exist may never be booked against the host budget")
            .isZero();
    }

    /**
     * The consequence the gap was about: the auto-scaler now stops at the HOST's headroom
     * instead of at its own hard cap of five.
     */
    @Test
    void aFullHostRefusesTheNextChild() throws Exception {
        LiveLane.require(LiveLane.Need.CONFINEMENT, ProcessConfinement.availability().enforceable(),
            "SKIPPED: a declared cap needs an enforceable host ("
            + ProcessConfinement.availability().reason() + ")");

        // 1. Positive anchor first: with room, the child starts. The budget is 200 MB
        //    and each child declares 128, so exactly one fits -- deliberately not a
        //    multiple, or the second child would fit exactly and prove nothing.
        measureHost(200L);
        ManagedProcessSiteHandler handler = handler(4903, 128);
        assertThat(handler.startProcess())
            .as("step 1: a child that fits the host budget starts")
            .isNotNull();

        // 2. A second child of the same size does not fit the remaining 72 MB and is
        //    refused: the spawn returns null rather than running a workload that does
        //    not fit.
        assertThat(handler.startProcess())
            .as("step 2: the child that does not fit is refused, not started")
            .isNull();
        assertThat(handler.runningProcessCount())
            .as("step 2: exactly one child is running")
            .isEqualTo(1);

        // 3. The refusal did not spend anything: the booking is still one child's worth.
        assertThat(ProcessCapacity.bookedMbOn(localServerId))
            .as("step 3: a refused reservation spends nothing")
            .isEqualTo(128);
    }

    /** The two tiers ration ONE host budget, so a process booking narrows an instance's. */
    @Test
    void processBookingsNarrowTheInstanceBudget() {
        measureHost(256L);

        // 1. Positive anchor: with nothing booked, 200 MB of instance fits.
        InstanceCapacity.reserve(localServerId, 200);
        InstanceCapacity.release(localServerId, 200);

        // 2. With 200 MB of process children booked, the same instance no longer fits.
        ProcessCapacity.reserve(localServerId, 200);
        Throwable refusal = catchThrowable(() -> InstanceCapacity.reserve(localServerId, 200));
        assertThat(refusal)
            .as("an instance may not be admitted onto memory the process tier holds")
            .isInstanceOf(Violations.class);
        ProcessCapacity.release(localServerId, 200);
    }

    // -- helpers --------------------------------------------------------------

    /** Store (or clear) a measured memory total on the local host row. */
    private void measureHost(Long memoryMb) {
        Row server = Models.get(ServerModel.class).findById(localServerId);
        Map<String, Object> facts = new LinkedHashMap<>();
        if (memoryMb != null) {
            facts.put("mem_total", memoryMb * 1024L * 1024L);
        }
        HostPreflight.store(server.get(ServerModel.NAME), new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            facts, true, Instant.now(), null));
    }

    private void awaitBooked(long expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (ProcessCapacity.bookedMbOn(localServerId) == expected) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private ManagedProcessSiteHandler handler(int siteId, Integer memoryLimitMb) {
        Map<String, Object> settings = new LinkedHashMap<>();
        if (memoryLimitMb != null) {
            settings.put("memory_limit_mb", memoryLimitMb);
        }
        ManagedProcessSiteHandler handler = new ManagedProcessSiteHandler(
                siteId, "capacity-" + siteId, settings, portAllocator, monitor) {

            @Override
            protected List<String> buildCommand(String listenTarget) {
                return List.of("sh", "-c", "sleep 600 & wait");
            }

            @Override
            protected Map<String, String> buildRuntimeEnvironment(int port) {
                return Map.of();
            }

            @Override
            protected File getWorkingDirectory() {
                return new File(System.getProperty("java.io.tmpdir"));
            }
        };
        handlers.add(handler);
        return handler;
    }
}
