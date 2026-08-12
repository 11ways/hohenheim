package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.host.HostLeases;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.process.PortAllocator;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.runtime.InstanceStatus;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.lease.Leases;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * TWO CONTROLLERS AGAINST ONE DATABASE -- the fencing discipline's counterfactuals.
 * Controller B is a rival identity over {@code Leases.independent}, arbitrated purely
 * by the stored lease row, exactly like a second process. The stall is the injectable
 * {@code beforeOutcomeWrite} pause in InstanceService: it sits between the daemon
 * operations and the fenced outcome write, which is the only ordering the fence
 * property depends on -- everything A does at the daemon has already happened when a
 * SIGSTOP'd controller resumes too, so pausing there tests the same claim without
 * process signals.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class HostFencingTest {

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);

    private static SqliteDatasource datasource;
    private static PrivateNetns netns;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-host-fencing-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        if (PrivateNetns.available()) {
            netns = new PrivateNetns();
            WorkloadNetworkPolicy.overrideForTest(netns.enforcingPolicy());
        }
    }

    @AfterAll
    static void tearDown() {
        WorkloadNetworkPolicy.overrideForTest(null);
        if (netns != null) {
            netns.close();
            netns = null;
        }
    }

    /**
     * THE counterfactual that proves fencing is real. A acquires the host lease with a
     * 2s TTL and stalls between its daemon work and its outcome write; B takes the
     * lease over after expiry and deploys the same instance; A resumes and finishes.
     * A's write must affect ZERO rows and A must RAISE -- and the HOST is asserted
     * directly: exactly one container for the instance, exactly one port claim, both
     * B's. Asserting only "A's call failed" is the pre-installed defect the plan warns
     * about.
     */
    @Test
    void aStalledControllersDeployCannotStick() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET),
            "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, "alpine:latest");
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy");

        Db.run(datasource, () -> {
            HostFixtures.admitLocal();
            int localId = ServerModel.localServerId();

            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", "alpine");
            settings.put("tag", "latest");
            settings.put("command", "sleep 600");
            settings.put("container_port", 8080);
            Row record = Models.get(InstanceModel.class).createEmptyRow();
            record.set(InstanceModel.NAME, "fence-victim");
            record.set(InstanceModel.KIND, "hohenheim:docker_container");
            record.set(InstanceModel.SETTINGS, settings);
            Models.get(InstanceModel.class).save(record);
            int id = record.get(InstanceModel.ID);
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);

            // Controller A: the shared JVM front, but a 2s TTL so a stall loses the lease.
            CountDownLatch aStalled = new CountDownLatch(1);
            CountDownLatch resumeA = new CountDownLatch(1);
            AtomicReference<Runnable> pause = new AtomicReference<>(() -> {});
            HostLeases leasesA = new HostLeases(Leases::of, Duration.ofSeconds(2));
            InstanceService controllerA = new InstanceService(leasesA,
                () -> pause.get().run());
            pause.set(() -> {
                aStalled.countDown();
                try {
                    if (!resumeA.await(120, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("controller A was never resumed");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Controller B: a RIVAL identity -- its own coordinator, row-arbitrated.
            Leases rivalCoordinator = Leases.independent(datasource);
            InstanceService controllerB = new InstanceService(
                new HostLeases(d -> rivalCoordinator, Duration.ofSeconds(30)), () -> {});

            AtomicReference<Throwable> aOutcome = new AtomicReference<>();
            Thread aThread = new Thread(() -> {
                Db.run(datasource, () ->
                    aOutcome.set(catchThrowable(() -> controllerA.deploy(id))));
            }, "controller-a");

            try {
                // 1. A deploys and STALLS after create+start, before its outcome write.
                aThread.start();
                assertThat(aStalled.await(120, TimeUnit.SECONDS))
                    .as("step 1: controller A reached its stall point").isTrue();
                // A's fence, read off its JVM-held lease (no DB statement involved).
                long aFence = leasesA.requireFence(localId);

                // 2. B takes the host lease over after A's TTL expires and deploys the
                //    SAME instance to completion (removing A's container by owner label:
                //    the winner's reconciliation, not the loser's cleanup).
                InstanceStatus bStatus = controllerB.deploy(id);
                assertThat(bStatus.publishedPort())
                    .as("step 2: controller B's deploy published a port").isNotNull();

                // 3. A resumes and finishes its deploy: the fenced write matches ZERO
                //    rows and A RAISES the named fenced-out violation.
                resumeA.countDown();
                aThread.join(TimeUnit.SECONDS.toMillis(120));
                assertThat(aThread.isAlive()).as("step 3: controller A finished").isFalse();
                assertThat(aOutcome.get())
                    .as("step 3: A's resumed write is a hard failure, not a shrug")
                    .isInstanceOfSatisfying(Violations.class, violations ->
                        assertThat(violations.all()).anySatisfy(violation ->
                            assertThat(violation.message().key())
                                .as("step 3: the named fenced-out refusal")
                                .isEqualTo("instance_fenced_out")));

                // 4. The RECORD carries B's outcome: status running, and the stored
                //    fence is B's (strictly greater than A's ownership generation).
                Row after = Models.get(InstanceModel.class).findById(id);
                assertThat((String) after.get(InstanceModel.STATUS))
                    .as("step 4: the record says running -- B's outcome stuck")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                assertThat((Long) after.get(InstanceModel.CLAIM_FENCE))
                    .as("step 4: the stored fence is a strictly later generation than A's")
                    .isGreaterThan(aFence);

                // 5. HOST state, not just the API answer: exactly ONE container carries
                //    this instance's owner labels, and it is RUNNING (B's).
                int owned = 0;
                for (Object entry : docker.listContainers(true)) {
                    if (entry instanceof Map<?, ?> summary
                        && summary.get("Labels") instanceof Map<?, ?> labels
                        // Scoped to THIS controller: a sibling test class's database
                        // numbers its instances from 1 too, and only the controller label
                        // tells the two #1s apart on a shared daemon.
                        && OwnerLabels.isOurs(OwnerLabels.parse(labels))
                        && OwnerLabels.parse(labels).model().equals(InstanceModel.MODEL_ID)
                        && OwnerLabels.parse(labels).id().equals(String.valueOf(id))) {
                        owned++;
                    }
                }
                assertThat(owned)
                    .as("step 5: exactly one container exists for the instance").isEqualTo(1);
                assertThat(new InstanceService().liveStatus(id).publishedPort())
                    .as("step 5: and it publishes B's port")
                    .isEqualTo(bStatus.publishedPort());

                // 6. Exactly one ledger claim for the instance, and it is B's port. A
                //    never reached the ledger: the fence gate sits BEFORE the record-
                //    after claim, or a stale loser would delete the winner's row.
                List<Row> claims = PortLedger.claimsOf(InstanceModel.MODEL_ID, id);
                assertThat(claims)
                    .as("step 6: exactly one port claim for the instance").hasSize(1);
                assertThat((Integer) claims.get(0).get(PortAllocationModel.PORT))
                    .as("step 6: and it is B's observed port")
                    .isEqualTo(bStatus.publishedPort());
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                resumeA.countDown();
                cleanup(docker, handle);
            }
        });
    }

    /**
     * The boot-sweep regression: the old sweep ran unconditionally and deleted a live
     * peer's not-yet-bound claim (the whole allocate-to-spawn window). Under the host
     * lease it is structurally impossible: a rival cannot sweep while the peer holds
     * the lease, cannot allocate either, and once the peer's lease is gone the claims
     * are honestly a previous generation's.
     */
    @Test
    void theBootSweepCannotDeleteALivePeersClaims() {
        Db.run(datasource, () -> {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 34748);
            int serverId = ServerModel.localServerId();

            HostLeases controllerA = new HostLeases(Leases::of, Duration.ofSeconds(30));
            PortAllocator allocatorA = new PortAllocator(controllerA);

            Leases rival = Leases.independent(datasource);
            HostLeases controllerB = new HostLeases(d -> rival, Duration.ofSeconds(30));
            PortAllocator allocatorB = new PortAllocator(controllerB);

            try {
                // 1. A allocates: this is the allocate-to-spawn window -- the port is
                //    claimed in the ledger but nothing is bound at the kernel yet.
                int port = allocatorA.allocate(11);
                String key = PortLedger.claimKeyOf(serverId, "", port, "tcp");
                assertThat(PortLedger.holderOf(key))
                    .as("step 1: A's claim is in the ledger").isNotNull();

                // 2. Rival controller B boots and sweeps: it cannot take the host lease,
                //    so it deletes NOTHING -- the old code deleted A's claim right here
                //    (owner-less, note-matched, port observed free).
                PortAllocator.SweepResult swept = allocatorB.sweepPreviousControllerClaims();
                assertThat(swept.released())
                    .as("step 2: a rival's sweep releases nothing while A lives").isZero();
                assertThat(PortLedger.holderOf(key))
                    .as("step 2: A's claim SURVIVES the rival's boot sweep").isNotNull();

                // 3. B cannot allocate on this host either: one fenced controller owns
                //    each host mutation.
                assertThat(catchThrowable(() -> allocatorB.allocate(12)))
                    .as("step 3: a rival controller cannot allocate while A holds the host")
                    .isInstanceOf(Violations.class);

                // 4. A dies (releases its lease). B's next sweep takes the lease over and
                //    NOW judges A's claims as a previous generation: the port is unbound,
                //    so the claim is released.
                controllerA.releaseAll();
                PortAllocator.SweepResult after = allocatorB.sweepPreviousControllerClaims();
                assertThat(after.released())
                    .as("step 4: the dead controller's unbound claim is released").isEqualTo(1);
                assertThat(PortLedger.holderOf(key))
                    .as("step 4: and its row is gone").isNull();

                // 5. A legacy fence-less claim (pre-M056 rows) is still sweepable.
                PortLedger.claim(serverId, "", 34999, "tcp", null, null,
                    "managed process site=9 controller=deadbeefcafe");
                assertThat(allocatorB.sweepPreviousControllerClaims().released())
                    .as("step 5: a legacy fence-less claim is judged like a previous"
                        + " generation's").isEqualTo(1);
            } finally {
                controllerA.releaseAll();
                controllerB.releaseAll();
                HohenheimSettings.VALUES.setValue(HohenheimSettings.Proxy.FIRST_PORT, 4748);
                Models.get(PortAllocationModel.class).find().delete();
            }
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static void cleanup(DockerClient docker, String handle) {
        try {
            docker.removeContainer(handle, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(handle));
        } catch (IOException ignored) {
            // never created
        }
    }
}
