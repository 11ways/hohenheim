package be.elevenways.hohenheim.test.process;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.ProcessConfinement;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.process.ManagedProcessSiteHandler;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.process.ProcessMonitor;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The host-process tier's resource cap: a DECLARED memory limit is a real cgroup the
 * KERNEL enforces, or the site is refused outright.
 *
 * AIDEV-NOTE: every assertion here reads the KERNEL, never the configuration. A test that
 * checked "the command line contains MemoryMax" would pass just as happily against a
 * scope systemd created and then ignored, which is the paper limit this whole mechanism
 * exists to refuse -- so the child reports its own {@code memory.max} and its own cgroup's
 * {@code memory.events} oom_kill counter back through stdout.
 */
class ProcessConfinementTest {

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
    void cleanUp() {
        ProcessConfinement.overrideForTest(null);
        for (ManagedProcessSiteHandler handler : handlers) {
            handler.destroy();
        }
        handlers.clear();
    }

    /**
     * The whole point of the mechanism, walked end to end against the kernel: a child
     * under a declared cap sees that cap, a child that stays inside it runs to completion,
     * and a child that exceeds it never finishes its allocation.
     *
     * AIDEV-NOTE: the over-allocation is judged on TWO accepted kernel outcomes, and that
     * is not a weakened assertion -- it is the two shapes cgroup enforcement actually
     * takes, measured on this machine at roughly 4:1. Usually the in-cgroup OOM killer
     * picks the allocating process and the surviving shell reports {@code oom_kill 1} out
     * of its own {@code memory.events}; sometimes the whole scope is terminated instead
     * (systemd-oomd acting on the same pressure), and then the child is signalled dead
     * with no output at all. Both are the cap being enforced. What is NEVER accepted is
     * the allocation SUCCEEDING, which is what an unenforced cap looks like and what the
     * uncapped control in step 4 proves this host would otherwise do.
     */
    @Test
    void aDeclaredMemoryLimitIsEnforcedByTheKernel() throws Exception {
        assumeTrue(ProcessConfinement.availability().enforceable(),
            "SKIPPED: this host cannot create a systemd scope ("
                + ProcessConfinement.availability().reason() + ")");

        // 1. The cap the operator declared is the cap the kernel carries.
        Ran capped = run(32, "echo MAX=$(cat $CG/memory.max); echo ALIVE");
        assertThat(capped.output())
            .as("step 1: the child's own cgroup must carry the declared 32 MB cap")
            .contains("MAX=" + (32L * 1024 * 1024));

        // 2. Positive anchor: a workload that stays inside its cap is untouched. Without
        //    this, step 3 would pass just as well for a spawn that never runs at all.
        assertThat(capped.output())
            .as("step 2: a child that stays under its cap runs to completion")
            .contains("ALIVE");
        assertThat(capped.exitCode()).as("step 2: and exits cleanly").isZero();

        // 3. A child that exceeds the cap never completes its allocation, and the kernel
        //    says so -- either at the cgroup's oom_kill counter or by killing the scope.
        Ran exceeded = run(32, ALLOCATE);
        assertThat(exceeded.output())
            .as("step 3: a 200 MB allocation under a 32 MB cap must NEVER succeed")
            .doesNotContain("ALLOCATED");
        assertThat(exceeded.output().matches("(?s).*oom_kill [1-9].*") || exceeded.exitCode() != 0)
            .as("step 3: the kernel must have killed it (exit %d, output '%s')",
                exceeded.exitCode(), exceeded.output())
            .isTrue();

        // 4. The control: the SAME allocation with no declared cap succeeds, so step 3
        //    measured the cap and not a machine that cannot allocate 200 MB.
        Ran uncapped = run(null, ALLOCATE);
        assertThat(uncapped.output())
            .as("step 4: the same allocation without a declared cap must succeed")
            .contains("ALLOCATED");
        assertThat(uncapped.exitCode()).as("step 4: and exit cleanly").isZero();
    }

    /** Allocate ~200 MB and hold it; reports success and the cgroup's OOM counter. */
    private static final String ALLOCATE =
        "head -c 200000000 /dev/zero 2>/dev/null | tail -c 200000000 >/dev/null 2>&1"
            + " && echo ALLOCATED;"
            + " echo OOM=$(grep '^oom_kill ' $CG/memory.events 2>/dev/null)";

    /** The pids cap rides the same scope, so a fork bomb hits its own budget. */
    @Test
    void theScopeAlsoCarriesTheProcessCap() throws Exception {
        assumeTrue(ProcessConfinement.availability().enforceable(),
            "SKIPPED: this host cannot create a systemd scope ("
                + ProcessConfinement.availability().reason() + ")");

        Ran output = run(32, "echo TASKS=$(cat $CG/pids.max)");
        assertThat(output.output())
            .as("a capped child's cgroup must carry the configured TasksMax")
            .contains("TASKS=" + ProcessConfinement.pidsLimit());
    }

    /**
     * A host that cannot enforce a declared limit REFUSES the site by name rather than
     * spawning it uncapped -- booking without enforcing is the defect, not the fix.
     */
    @Test
    void aHostThatCannotEnforceRefusesADeclaredLimitByName() {
        ProcessConfinement.overrideForTest(new ProcessConfinement.Availability(
            ProcessConfinement.Mode.NONE, "no systemd on this host (test override)"));

        // 1. Declaring a limit on such a host is a refusal that NAMES the reason.
        assertThatThrownBy(() -> handler(4801, 128))
            .as("step 1: a declared limit on an unenforceable host must refuse")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot enforce")
            .hasMessageContaining("no systemd on this host (test override)")
            .hasMessageContaining("memory_limit_mb");

        // 2. Positive anchor: the same host still runs a site that declares NO limit, so
        //    the refusal above is about the declaration and not about the path being dead.
        ManagedProcessSiteHandler unbounded = handler(4802, null);
        assertThat(unbounded.getSiteId())
            .as("step 2: an undeclared site still constructs on the same host")
            .isEqualTo(4802);
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Run one shell snippet through the real spawn path with an optional declared cap.
     * {@code $CG} is the child's own cgroup directory.
     */
    private Ran run(Integer memoryMb, String script) throws Exception {
        List<String> confinement = ProcessConfinement.scopePrefix("cap-test", memoryMb, null);
        Map<String, String> environment = new LinkedHashMap<>(
            SystemUsers.safeEnvironment(System.getProperty("java.io.tmpdir")));
        if (!confinement.isEmpty()) {
            ProcessConfinement.contributeEnvironment(environment);
        }
        ProcessBuilder builder = SystemUsers.executionBuilder(null, environment,
            List.of("/bin/sh", "-c",
                "CG=/sys/fs/cgroup$(cut -d: -f3 /proc/self/cgroup); " + script),
            true, confinement);
        builder.directory(new File(System.getProperty("java.io.tmpdir")));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS))
            .as("the probe child must finish; it printed: %s", output)
            .isTrue();
        return new Ran(process.exitValue(), output);
    }

    /** One probe child's verdict: how it ended and what it managed to report. */
    private record Ran(int exitCode, String output) {}

    private ManagedProcessSiteHandler handler(int siteId, Integer memoryLimitMb) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("minimum_processes", 0);
        if (memoryLimitMb != null) {
            settings.put("memory_limit_mb", memoryLimitMb);
        }
        ManagedProcessSiteHandler handler = new ManagedProcessSiteHandler(
                siteId, "confinement-" + siteId, settings, portAllocator, monitor) {

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
