package be.elevenways.hohenheim.server.docker;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.runtime.ContainerState;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.runtime.WorkloadLiveness;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published-port correction after a link-network change compares the WHOLE recorded set
 * with the WHOLE published set: a moved second port of a multi-publication workload is
 * corrected, no other claim of the owner is lost, and an unchanged set rewrites nothing.
 *
 * Hermetic: the ledger in a fresh SQLite, the daemon answer a fixed status.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class DatabaseLinkPortRefreshTest {

    private static final int OWNER = 987_001;

    @Test
    void theWholePublishedSetIsComparedAndRecorded() throws Exception {
        TestDatabases.freshDatabase();
        HohenheimTestRuntime.ensureBooted();
        int serverId = ServerModel.localServerId();

        // 1. The deploy recorded two observed publications of one workload.
        PortLedger.recordObservedAll(serverId, "127.0.0.1", List.of(41001, 41002), "tcp",
            InstanceModel.MODEL_ID, OWNER, null);
        assertThat(ports()).as("step 1: both publications are claimed")
            .containsExactlyInAnyOrder(41001, 41002);

        // 2. A network change moved ONLY the second port. The first pair still matches, which
        //    is exactly what the first-row comparison used to stop at.
        DatabaseLinkNetworks.refreshInstancePort(handle -> running(41001, 41003), serverId,
            OWNER, "handle");
        assertThat(ports()).as("step 2: the moved port is corrected and the unmoved one kept")
            .containsExactlyInAnyOrder(41001, 41003);

        // 3. The same published set again rewrites nothing: the claim rows are the very same.
        List<Object> before = claimIds();
        DatabaseLinkNetworks.refreshInstancePort(handle -> running(41003, 41001), serverId,
            OWNER, "handle");
        assertThat(claimIds()).as("step 3: an unchanged set leaves every claim row alone")
            .isEqualTo(before);

        // 4. A container that is not running corrects nothing.
        DatabaseLinkNetworks.refreshInstancePort(handle -> new InstanceStatus(ContainerState.STOPPED,
            List.of(), WorkloadLiveness.UNKNOWN), serverId, OWNER, "handle");
        assertThat(ports()).as("step 4: a stopped workload keeps its claims")
            .containsExactlyInAnyOrder(41001, 41003);
    }

    private static InstanceStatus running(int first, int second) {
        return new InstanceStatus(ContainerState.RUNNING, List.of(
            new InstanceStatus.PublishedPort(5432, "tcp", first, "127.0.0.1"),
            new InstanceStatus.PublishedPort(8080, "tcp", second, "127.0.0.1")),
            WorkloadLiveness.SERVING);
    }

    private static List<Integer> ports() {
        List<Integer> ports = new ArrayList<>();
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, OWNER)) {
            ports.add(claim.get(PortAllocationModel.PORT));
        }
        return ports;
    }

    private static List<Object> claimIds() {
        List<Object> ids = new ArrayList<>();
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, OWNER)) {
            ids.add(claim.get(PortAllocationModel.ID));
        }
        return ids;
    }
}
