package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.incus.ControllerPresence;
import be.elevenways.hohenheim.server.incus.IncusClient;
import be.elevenways.hohenheim.server.incus.IncusNetworkPolicy;
import be.elevenways.hohenheim.server.runtime.Egress;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.server.runtime.IncusWorkloadType;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.server.task.ReapIncusControllers;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This controller's shared objects on an Incus daemon (isolation ACL, extra-NIC bridge, presence marker) live exactly
 * as long as one of its instances there: the destroy of the last one hands them back, and the sweep does not stamp a
 * presence marker onto a daemon where the controller holds nothing.
 *
 * AIDEV-NOTE: before this, every daemon a controller ever deployed to kept its ACL and marker for as long as that
 * controller lived (the reaper only takes a DEPARTED controller's objects), and the sweep re-stamped the marker on every
 * enrolled host every 15 minutes: daystrom held hohenheim-u548fz9h-isolation/presence with no instance of that
 * controller left on it (2026-10-12).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class IncusSharedObjectReleaseTest extends HohenheimTestBase {

    private static final String PREFIX = "release-";

    @Test
    void theLastInstanceOnADaemonTakesTheControllersSharedObjectsWithIt() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusInstanceRuntime incus = runtime(daemon);

        // 1. Two instances, the second with an extra NIC: the ACL, the bridge and the marker exist.
        InstanceSpec first = spec(PREFIX + "first");
        InstanceSpec second = spec(PREFIX + "second");
        incus.create(first);
        incus.create(second);
        incus.ensureNic(second, "eth1");
        assertThat(daemon.aclStore.keySet()).as("step 1: the isolation ACL and the presence marker exist")
            .contains(IncusNetworkPolicy.aclName(), ControllerPresence.aclName());
        assertThat(daemon.networkStore).as("step 1: and the extra-NIC bridge")
            .containsKey(IncusNetworkPolicy.extraNetwork());

        // 2. Destroying one instance keeps everything the other still references.
        incus.destroy(first.handle());
        assertThat(daemon.aclStore.keySet()).as("step 2: the second instance still references the ACL")
            .contains(IncusNetworkPolicy.aclName(), ControllerPresence.aclName());
        assertThat(daemon.networkStore).as("step 2: and the bridge")
            .containsKey(IncusNetworkPolicy.extraNetwork());

        // 3. Destroying the last one hands every shared object back, the marker last.
        incus.destroy(second.handle());
        assertThat(daemon.aclStore.keySet()).as("step 3: no ACL of ours outlives our last instance here")
            .doesNotContain(IncusNetworkPolicy.aclName(), ControllerPresence.aclName());
        assertThat(daemon.networkStore).as("step 3: nor the bridge")
            .doesNotContainKey(IncusNetworkPolicy.extraNetwork());
    }

    @Test
    void theSweepStampsNoMarkerWhereTheControllerHoldsNothing() throws Exception {
        FakeIncusTransport daemon = new FakeIncusTransport();
        IncusClient client = new IncusClient(daemon);
        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, "release-daemon");

        // 1. A daemon where this controller holds nothing: the sweep reaches it and leaves no marker behind.
        ReapIncusControllers.HostOutcome empty = ReapIncusControllers.sweepHost(host, client);
        assertThat(empty.reachable()).as("step 1: the sweep reached the daemon").isTrue();
        assertThat(daemon.aclStore).as("step 1: no presence marker on a daemon holding none of our objects")
            .doesNotContainKey(ControllerPresence.aclName());

        // 2. Once an instance of ours is there, the sweep keeps its marker fresh.
        runtime(daemon).create(spec(PREFIX + "swept"));
        daemon.aclStore.remove(ControllerPresence.aclName());
        ReapIncusControllers.sweepHost(host, client);
        assertThat(daemon.aclStore).as("step 2: the marker is stamped while our objects are here")
            .containsKey(ControllerPresence.aclName());
    }

    private static IncusInstanceRuntime runtime(FakeIncusTransport daemon) {
        return new IncusInstanceRuntime(new IncusClient(daemon), Egress.OPEN, IncusWorkloadType.CONTAINER, null);
    }

    private static InstanceSpec spec(String handle) {
        return InstanceSpec.builder(handle, "alpine/3.22", ResourceLimits.none(),
                new ContainerHardening.Profile("fake", List.of()),
                OwnerLabels.of(InstanceModel.MODEL_ID, Math.abs(handle.hashCode())))
            .build();
    }
}
