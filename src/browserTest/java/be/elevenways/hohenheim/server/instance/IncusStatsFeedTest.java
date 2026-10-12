package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.server.incus.EchoIncusDaemon;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusStatsFeed;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.StatsFeed;
import be.elevenways.hohenheim.server.runtime.StatsReading;
import be.elevenways.hohenheim.server.runtime.StatsStreamSupport;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.protoblast.common.time.Now;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Incus driver's live-stats lane against an in-memory daemon: memory and network from the instance state, a
 * container's CPU from the state too, a virtual machine's CPU from the Prometheus metrics, and a VM whose metrics carry
 * no CPU (no guest agent) reading as "no CPU reading" rather than a zero.
 */
class IncusStatsFeedTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    void anIncusWorkloadsReadingsBecomeSamples() throws Exception {
        EchoIncusDaemon daemon = new EchoIncusDaemon();
        daemon.instanceStates.put("vm", state(-1, 100 * MIB, 512 * MIB, 1_000, 2_000));
        daemon.metrics = vmMetrics("vm", 10, 1_000, 5, 1_000);

        // 1. The Incus driver offers the live-stats lane, and a VM's first reading opens at once.
        assertThat(WorkloadLane.LIVE_STATS.offeredBy(IncusInstanceRuntime.class)).as("step 1: the lane").isTrue();
        IncusInstanceRuntime vm = new IncusInstanceRuntime(daemon.client(), Egress.OPEN,
            IncusWorkloadType.VIRTUAL_MACHINE);
        StatsReading opened;
        try (StatsFeed feed = ((StatsStreamSupport) vm).openStats("vm")) {
            opened = feed.next();
        }
        assertThat(opened).as("step 1: the driver's feed reads the VM").isNotNull();

        // 2. Memory against the guest's total; network summed without loopback; the VM's cores are its metric CPUs.
        StatsFeed feed = new IncusStatsFeed(daemon.client(), "vm", IncusWorkloadType.VIRTUAL_MACHINE, 1, 0);
        InstanceStats.Decoder decoder = new InstanceStats.Decoder();
        InstanceStats.Sample first = decoder.next(feed.next());
        assertThat(first.memoryBytes()).as("step 2: memory in use").isEqualTo(100 * MIB);
        assertThat(first.memoryLimit()).as("step 2: of the guest's total").isEqualTo(512 * MIB);
        assertThat(first.rxBytes()).as("step 2: received, loopback left out").isEqualTo(1_000);
        assertThat(first.txBytes()).as("step 2: sent, loopback left out").isEqualTo(2_000);
        assertThat(first.cores()).as("step 2: two guest CPUs").isEqualTo(2);
        assertThat(first.cpuReported()).as("step 2: a VM with its agent reports CPU").isTrue();
        assertThat(first.cpuPercent()).as("step 2: the first reading has no window yet").isZero();

        // 3. The guest's counters move: 1 busy second of 4 CPU seconds on 2 CPUs is 50%.
        daemon.metrics = vmMetrics("vm", 10.5, 1_001.5, 5.5, 1_001.5);
        assertThat(decoder.next(feed.next()).cpuPercent()).as("step 3: the measured share")
            .isCloseTo(50.0, within(0.001));

        // 4. The daemon has not refreshed its metrics yet: the last measured window stands, never a dip to zero.
        assertThat(decoder.next(feed.next()).cpuPercent()).as("step 4: the window stands").isCloseTo(50.0,
            within(0.001));

        // 5. A VM whose metrics carry no CPU lines (no guest agent) says so; the wire carries the flag, no figure.
        daemon.metrics = "# HELP incus_cpu_seconds_total x\nincus_memory_Active_bytes{name=\"vm\"} 1\n";
        InstanceStats.Sample agentless = decoder.next(feed.next());
        assertThat(agentless.cpuReported()).as("step 5: no CPU reading").isFalse();
        assertThat(agentless.toMap()).as("step 5: the wire says so in its flag")
            .containsEntry(HohenheimStatsFunctions.CPU_UNREPORTED_KEY, true)
            .doesNotContainKey(HohenheimStatsFunctions.Metric.CPU.key());
        assertThat(HohenheimStatsFunctions.cpuUnreported(agentless.toMap())).as("step 5: the page reads it").isTrue();

        // 6. A container's CPU is its cgroup nanoseconds against elapsed time on the cores limits.cpu gives it.
        daemon.instances.put("ct", Map.of("expanded_config", Map.of("limits.cpu", "2")));
        daemon.instanceStates.put("ct", state(4_000_000_000L, 50 * MIB, 0, 0, 0));
        Duration offset = Now.offset();
        try (StatsFeed container = ((StatsStreamSupport) new IncusInstanceRuntime(daemon.client(), Egress.OPEN,
                IncusWorkloadType.CONTAINER)).openStats("ct")) {
            InstanceStats.Decoder containerDecoder = new InstanceStats.Decoder();
            InstanceStats.Sample start = containerDecoder.next(container.next());
            assertThat(start.cores()).as("step 6: the cores limits.cpu gives").isEqualTo(2);
            daemon.instanceStates.put("ct", state(6_000_000_000L, 50 * MIB, 0, 0, 0));
            Now.setOffset(offset.plusSeconds(20));
            // 2 CPU seconds over 20 seconds is 10% of one core.
            assertThat(containerDecoder.next(container.next()).cpuPercent()).as("step 6: the container's share")
                .isCloseTo(10.0, within(0.5));
        } finally {
            Now.setOffset(offset);
        }

        // 7. A stopped instance ends the feed.
        daemon.instanceStates.put("vm", Map.of("status", "Stopped"));
        assertThat(feed.next()).as("step 7: a stopped instance has no readings").isNull();
        feed.close();
    }

    private static Map<String, Object> state(long cpuUsage, long memory, long total, long rx, long tx) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", "Running");
        state.put("cpu", Map.of("usage", cpuUsage));
        state.put("memory", Map.of("usage", memory, "total", total));
        state.put("network", Map.of(
            "eth0", Map.of("type", "broadcast", "counters", Map.of("bytes_received", rx, "bytes_sent", tx)),
            "lo", Map.of("type", "loopback", "counters", Map.of("bytes_received", 99, "bytes_sent", 99))));
        return state;
    }

    /** Two guest CPUs with their user and idle seconds, beside another instance's lines the feed must ignore. */
    private static String vmMetrics(String name, double user0, double idle0, double user1, double idle1) {
        return "# TYPE incus_cpu_seconds_total counter\n"
            + line(name, 0, "user", user0) + line(name, 0, "idle", idle0)
            + line(name, 1, "user", user1) + line(name, 1, "idle", idle1)
            + line("other", 0, "user", 999) + line("other", 0, "idle", 1);
    }

    private static String line(String name, int cpu, String mode, double seconds) {
        return "incus_cpu_seconds_total{cpu=\"" + cpu + "\",mode=\"" + mode + "\",name=\"" + name
            + "\",project=\"default\",type=\"virtual-machine\"} " + seconds + "\n";
    }
}
