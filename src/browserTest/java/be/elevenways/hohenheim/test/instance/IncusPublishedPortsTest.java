package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.PortPublication;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A machine's published ports on an Incus daemon: one NAT proxy per port, forwarding every host address to the guest's
 * eth0, which is pinned to a free address of the bridge, kept by a converge, re-pinned on an import's destination.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class IncusPublishedPortsTest extends HohenheimTestBase {

    @Test
    void aMachinesPortsAreNatProxiesToItsPinnedAddressAcrossConvergeStartAndImport() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusInstanceRuntime incus = runtime(daemon);
        daemon.dynamicLeases.add(Map.of("address", "10.120.5.254", "hostname", "someone-else", "type", "dynamic"));
        InstanceSpec machine = spec("ports-vm", 30001, 30002);

        // 1. Create: eth0 is pinned to the highest free address (the gateway and a lease skipped), and each port is a
        //    NAT proxy listening on every host address and connecting to that address.
        incus.create(machine);
        Map<String, Object> devices = devicesOf(daemon, machine.handle());
        assertThat(((Map<?, ?>) devices.get("eth0")).get("ipv4.address"))
            .as("step 1: eth0 is pinned below the leased .254").isEqualTo("10.120.5.253");
        assertThat(devices.get("hohport-tcp-25565")).as("step 1: the game port's proxy")
            .isEqualTo(Map.of("type", "proxy", "nat", "true", "listen", "tcp:0.0.0.0:30001",
                "connect", "tcp:10.120.5.253:25565"));
        assertThat(devices.get("hohport-udp-25565")).as("step 1: the query port's proxy")
            .isEqualTo(Map.of("type", "proxy", "nat", "true", "listen", "udp:0.0.0.0:30002",
                "connect", "udp:10.120.5.253:25565"));

        // 2. Converge keeps the pinned address although a higher one is free now, and removes a stale proxy of ours.
        daemon.dynamicLeases.clear();
        Map<String, Object> stale = new LinkedHashMap<>(devices);
        stale.put("hohport-tcp-9999", Map.of("type", "proxy", "nat", "true", "listen", "tcp:0.0.0.0:9999",
            "connect", "tcp:10.120.5.253:9999"));
        daemon.instances.get(machine.handle()).put("devices", stale);
        incus.create(machine);
        devices = devicesOf(daemon, machine.handle());
        assertThat(((Map<?, ?>) devices.get("eth0")).get("ipv4.address"))
            .as("step 2: a redeploy never moves the pinned address").isEqualTo("10.120.5.253");
        assertThat(devices).as("step 2: exactly the declared proxies remain")
            .containsKeys("hohport-tcp-25565", "hohport-udp-25565").doesNotContainKey("hohport-tcp-9999");

        // 3. Running, the status reports both ports with their container ports; the daemon's NAT start check passes;
        //    stopped, nothing forwards.
        incus.start(machine.handle());
        daemon.instanceStatus = "Running";
        InstanceStatus running = incus.status(machine.handle());
        assertThat(running.publishedFor(25565, "tcp")).as("step 3: the game port as the daemon holds it")
            .isEqualTo(new InstanceStatus.PublishedPort(25565, "tcp", 30001, "0.0.0.0"));
        assertThat(running.publishedFor(25565, "udp").hostPort()).as("step 3: and the query port").isEqualTo(30002);
        daemon.instanceStatus = "Stopped";
        assertThat(incus.status(machine.handle()).publishedPorts()).as("step 3: a stopped machine forwards nothing")
            .isEmpty();

        Path exported = Files.createTempFile("hohenheim-fake-export", ".json");
        Files.writeString(exported, Json.stringify(daemon.instances.get(machine.handle())));
        try {
            // 4. A restore onto a host whose bridge has another subnet: eth0 is pinned in THAT subnet through the
            //    import override (the export's address would refuse the import), and the proxies forward the newly
            //    claimed ports to it, which the start check accepts.
            FakeIncusTransport destination = new FakeIncusTransport();
            destination.bridgeAddress = "10.77.0.1/24";
            InstanceSpec copy = spec("ports-copy", 30001, 30002).withPreallocatedPorts(List.of(
                new PortPublication(25565, "tcp", true, 30001, 31001),
                new PortPublication(25565, "udp", true, 30002, 31002)));
            runtime(destination).importBackup(copy, exported, Map.of(), false);
            Map<String, Object> landed = devicesOf(destination, copy.handle());
            assertThat(((Map<?, ?>) landed.get("eth0")).get("ipv4.address"))
                .as("step 4: the copy is pinned in the destination's subnet").isEqualTo("10.77.0.254");
            assertThat(((Map<?, ?>) landed.get("hohport-tcp-25565")).get("connect"))
                .as("step 4: its proxy connects to that address").isEqualTo("tcp:10.77.0.254:25565");
            assertThat(((Map<?, ?>) landed.get("hohport-udp-25565")).get("listen"))
                .as("step 4: on the port claimed for it").isEqualTo("udp:0.0.0.0:31002");
            runtime(destination).start(copy.handle());

            // 5. A migration (no claim yet) keeps the declared ports; a machine publishing nothing is unpinned, so its
            //    source address cannot refuse the import either.
            FakeIncusTransport moved = new FakeIncusTransport();
            moved.bridgeAddress = "10.88.0.1/24";
            runtime(moved).importBackup(machine, exported, Map.of(), false);
            assertThat(((Map<?, ?>) devicesOf(moved, machine.handle()).get("hohport-tcp-25565")).get("listen"))
                .as("step 5: a migration keeps the declared port").isEqualTo("tcp:0.0.0.0:30001");
            FakeIncusTransport plain = new FakeIncusTransport();
            plain.bridgeAddress = "10.99.0.1/24";
            InstanceSpec unpublished = spec("ports-none");
            runtime(plain).importBackup(unpublished, exported, Map.of(), false);
            Map<String, Object> bare = devicesOf(plain, unpublished.handle());
            assertThat(((Map<?, ?>) bare.get("eth0")).containsKey("ipv4.address")).as("step 5: no pin without ports")
                .isFalse();
            assertThat(bare.keySet()).as("step 5: and no proxies").noneMatch(name -> name.startsWith("hohport-"));
        } finally {
            Files.deleteIfExists(exported);
        }

        // 6. Destroy takes the machine and its proxies.
        incus.destroy(machine.handle());
        assertThat(daemon.instances).as("step 6: the machine is gone").doesNotContainKey(machine.handle());
    }

    private static IncusInstanceRuntime runtime(FakeIncusTransport daemon) {
        return new IncusInstanceRuntime(new IncusClient(daemon), Egress.OPEN, IncusWorkloadType.VIRTUAL_MACHINE, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> devicesOf(FakeIncusTransport daemon, String handle) {
        return (Map<String, Object>) daemon.instances.get(handle).get("devices");
    }

    private static InstanceSpec spec(String handle, int... hostPorts) {
        List<PortPublication> publications = hostPorts.length == 0 ? List.of() : List.of(
            new PortPublication(25565, "tcp", true, hostPorts[0], null),
            new PortPublication(25565, "udp", true, hostPorts[1], null));
        return InstanceSpec.builder(handle, "alpine/3.22/cloud", ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, Math.abs(handle.hashCode())))
            .publications(publications)
            .build();
    }
}
