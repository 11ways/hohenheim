package be.elevenways.hohenheim.test.ports;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PortAllocator} as a ledger-backed authority: allocations are rows that outlive
 * the JVM, and the boot sweep may only free what it has OBSERVED to be free. Deliberately
 * daemon-free, like PortLedgerTest, so this coverage exists on a machine with no Docker.
 */
class PortAllocatorTest {

    /** A window well clear of the kernel ephemeral range and of a running dev server. */
    private static final int FIRST_PORT = 24748;

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        HohenheimTestRuntime.ensureBooted();
    }

    @AfterAll
    static void restoreDefault() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 4748);
    }

    /** The configured window is honoured, and a privileged value falls back to the default. */
    @Test
    void theAllocationWindowFollowsTheConfiguredFirstPort() {
        Db.run(datasource, () -> {
            // 1. The configured first port opens the window.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, FIRST_PORT);
            PortAllocator allocator = new PortAllocator();
            int configured = allocator.allocate(1);
            assertThat(configured).as("step 1: allocation comes from the configured window")
                .isBetween(FIRST_PORT, FIRST_PORT + 5000);
            allocator.release(configured);

            // 2. A privileged first port is refused and the default window used instead.
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 80);
            int fallback = allocator.allocate(1);
            assertThat(fallback).as("step 2: a privileged first port falls back to 4748")
                .isBetween(4748, 4748 + 5000);
            allocator.release(fallback);

            // 3. Releasing everything empties the in-memory cache as well as the ledger.
            assertThat(allocator.getReservedCount())
                .as("step 3: no port stays reserved after release").isZero();
        });
    }

    /**
     * The allocation is a PERSISTED claim, not a JVM-lifetime map entry: a second
     * allocator -- what a controller restart produces -- can see it and will not hand the
     * same port out again. This is the whole point of putting the allocator behind the
     * ledger.
     */
    @Test
    void anAllocationIsAPersistedClaimASecondControllerHonours() {
        Db.run(datasource, () -> {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, FIRST_PORT);
            PortAllocator first = new PortAllocator();
            int port = first.allocate(77);

            // 1. The claim is in the TABLE, owned by no record (a process instance owns it)
            //    and described by its note.
            Row claim = PortLedger.holderOf(PortLedger.claimKeyOf(
                ServerModel.localServerId(), "", port, "tcp"));
            assertThat(claim).as("step 1: the allocation is a ledger row").isNotNull();
            assertThat((String) claim.get(PortAllocationModel.OWNER_MODEL))
                .as("step 1: a process-instance port has no owning RECORD").isNull();
            assertThat((String) claim.get(PortAllocationModel.NOTE))
                .as("step 1: the note says what holds it")
                .contains("managed process site=77");
            assertThat((Long) claim.get(PortAllocationModel.CONTROLLER_FENCE))
                .as("step 1: the claim carries the writing controller's host-lease fence,"
                    + " not a note-string token")
                .isNotNull();

            // 2. A fresh allocator -- i.e. a restarted controller, empty cache -- refuses
            //    to hand the same port out, because the LEDGER told it, not memory.
            PortAllocator restarted = new PortAllocator();
            int afterRestart = restarted.allocate(78);
            assertThat(afterRestart)
                .as("step 2: a restarted controller does not re-hand-out a held port")
                .isNotEqualTo(port);

            // 3. Cross-authority: a port another authority holds on a SPECIFIC address
            //    (what a docker-site publication looks like) is never handed to a managed
            //    process, even though the OS reports it free -- nothing is bound to it.
            int contested = first.allocate(79);
            first.release(contested);
            PortLedger.claim(ServerModel.localServerId(), "127.0.0.1", contested, "tcp",
                null, null, "a docker site publication");
            int afterContested = new PortAllocator().allocate(80);
            assertThat(afterContested)
                .as("step 3: a port another authority holds is not allocated, free or not")
                .isNotEqualTo(contested);
            PortLedger.releaseKey(PortLedger.claimKeyOf(ServerModel.localServerId(),
                "127.0.0.1", contested, "tcp"));

            // 4. Releasing removes the row, so the port becomes allocatable again.
            new PortAllocator().release(afterContested);
            restarted.release(afterRestart);
            first.release(port);
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(
                    ServerModel.localServerId(), "", port, "tcp")))
                .as("step 4: release deletes the claim row").isNull();
        });
    }

    /**
     * THE dangerous path. A managed child spawned with ProcessBuilder is reparented to
     * init and keeps its listening socket when the controller dies, so the boot sweep may
     * only delete a claim whose port it has OBSERVED to be free. If it freed a bound port,
     * the next allocation would hand it out, the child would die EADDRINUSE, and
     * startProcess's retry would turn the whole failure into a slower boot and a log line.
     */
    @Test
    void theBootSweepNeverFreesAPortThatIsStillBound() {
        Db.run(datasource, () -> {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, FIRST_PORT);
            int serverId = ServerModel.localServerId();
            PortAllocator allocator = new PortAllocator();
            int mine = allocator.allocate(5);

            try (ServerSocket survivor = new ServerSocket()) {
                survivor.setReuseAddress(true);
                survivor.bind(new InetSocketAddress("0.0.0.0", 0));
                int boundPort = survivor.getLocalPort();
                int freePort = freePortNear(boundPort);

                // Two claims from a PREVIOUS controller run: one whose process is still
                // alive and holding the port, one whose process is long gone.
                PortLedger.claim(serverId, "", boundPort, "tcp", null, null,
                    "managed process site=1 controller=deadbeefcafe");
                PortLedger.claim(serverId, "", freePort, "tcp", null, null,
                    "managed process site=2 controller=deadbeefcafe");

                // 1. The sweep frees ONLY the observed-free one; the bound port keeps its
                //    claim, which is what stops the next allocation handing it out.
                PortAllocator.SweepResult result = allocator.sweepPreviousControllerClaims();
                assertThat(result.retained())
                    .as("step 1: the still-bound port was retained, not freed").isEqualTo(1);
                assertThat(result.released())
                    .as("step 1: the dead process's port was released").isEqualTo(1);
                assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(serverId, "", boundPort, "tcp")))
                    .as("step 1: the bound port's claim row still exists").isNotNull();
                assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(serverId, "", freePort, "tcp")))
                    .as("step 1: the free port's stale claim is gone").isNull();

                // 2. THIS controller's own claim is never a sweep candidate, whenever the
                //    sweep runs -- its controller_fence equals the held lease's fence.
                assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(serverId, "", mine, "tcp")))
                    .as("step 2: the running controller's own allocation survives the sweep")
                    .isNotNull();

                // 3. Once the survivor really lets go, a later sweep does free it.
                survivor.close();
                assertThat(allocator.sweepPreviousControllerClaims().released())
                    .as("step 3: the port is released once it is observed free").isEqualTo(1);
                assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(serverId, "", boundPort, "tcp")))
                    .as("step 3: and its row is gone").isNull();
            } catch (IOException e) {
                throw new IllegalStateException("could not bind the survivor socket", e);
            } finally {
                allocator.release(mine);
                Models.get(PortAllocationModel.class).find().delete();
            }
        });
    }

    /** A port near the bound one that is genuinely free right now, for the released half. */
    private static int freePortNear(int boundPort) {
        for (int port = boundPort + 1; port < boundPort + 50; port++) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("0.0.0.0", port));
                return port;
            } catch (IOException ignored) {
                // taken; try the next one
            }
        }
        throw new IllegalStateException("no free port near " + boundPort);
    }
}
