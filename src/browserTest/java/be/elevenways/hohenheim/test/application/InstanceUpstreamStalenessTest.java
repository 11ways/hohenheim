package be.elevenways.hohenheim.test.application;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.application.ApplicationUpstreams;
import be.elevenways.hohenheim.server.application.InstanceUpstreamHandler;
import be.elevenways.hohenheim.server.instance.DockerContainerKind;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler;
import be.elevenways.hohenheim.server.instance.InstanceStatusReconciler.Verdict;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeDockerDaemon;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NON-release-managed half of the instance upstream: a site pointing straight at a
 * workload (a docker container here, a workspace on the same terms) must follow that
 * workload's address, and must stop naming it when the workload dies.
 *
 * AIDEV-NOTE: the defect this exists for. Only the release engine ever invalidated
 * {@link ApplicationUpstreams}, so the two OTHER kinds that declare {@code
 * supportsSiteUpstream} -- workspace and docker container -- froze whatever their handler
 * resolved at route-build time. Two consequences, both P0: routes built while the workload
 * was down answered 503 forever, and, because a loopback publication's host port is
 * EPHEMERAL, a restarted workload left the site forwarding to a number the daemon is free to
 * hand to a different tenant's container. {@code ApplicationReleaseTest} proves the release
 * lane, which was never broken; this proves the lane that was.
 *
 * <p>WHAT THIS CANNOT PROVE: that a real Docker daemon reissues a freed ephemeral port to
 * somebody else. Step 4 below stands a real {@link ServerSocket} on the freed port to make
 * the redeploy land elsewhere DETERMINISTICALLY, which is the same hazard modelled rather
 * than observed; the live lane remains the only proof of the kernel's own behaviour.
 */
class InstanceUpstreamStalenessTest {

    private static SqlDatasource datasource;
    private static FakeDockerDaemon daemon;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        daemon = new FakeDockerDaemon();
        daemon.install();
        daemon.installContainerKind();
        Db.run(datasource, HostFixtures::admitLocal);
    }

    @AfterAll
    static void tearDown() {
        FakeDockerDaemon.restore();
        if (daemon != null) {
            daemon.close();
            daemon = null;
        }
    }

    /**
     * A site exposing a plain workload: down before the first deploy, live after it, down
     * again after a stop, and following the workload onto its NEW ephemeral port after a
     * redeploy -- all through the one handler the route table built once.
     */
    @Test
    void aSiteExposingAPlainWorkloadFollowsItsEphemeralPortWithoutARouteRebuild() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            int instanceId = container("shopfront");
            int siteId = siteExposing(instanceId, "shopfront");
            String handle = FakeDockerDaemon.handleOf(instanceId);
            try {
                // 0. THE VOCABULARY the resolution reads: every status the column can
                //    store is classified, and the two that claim no workload are the only
                //    unroutable ones. A status added without a decision here would
                //    silently become routable (or silently take a site down).
                List<String> unservable =
                    new ArrayList<>(InstanceModel.STATUS.getValues().keySet());
                unservable.removeAll(InstanceModel.SERVABLE_STATUSES);
                assertThat(InstanceModel.STATUS.getValues().keySet())
                    .as("step 0: every servable status is a status the column can store")
                    .containsAll(InstanceModel.SERVABLE_STATUSES);
                assertThat(unservable)
                    .as("step 0: and exactly the two that claim no workload are unroutable"
                        + " -- a new status must be classified in SERVABLE_STATUSES")
                    .containsExactlyInAnyOrder(InstanceModel.STATUS_CREATED,
                        InstanceModel.STATUS_STOPPED);

                // 1. THE ROUTE IS BUILT WHILE THE WORKLOAD IS DOWN. This is the ordinary
                //    case -- a controller restart, an edit to the site, anything that
                //    rebuilds the table before somebody presses Deploy.
                InstanceUpstreamHandler handler =
                    new InstanceUpstreamHandler(siteId, instanceId);
                assertThat(handler.current())
                    .as("step 1: nothing is deployed, so the site names no upstream")
                    .isNull();
                assertThat(handler.getHealth().name())
                    .as("step 1: and reports DOWN rather than UP with no target")
                    .isEqualTo("DOWN");
                assertThat((Object) ApplicationReleases.consumerInstanceOf(instanceId))
                    .as("step 1: this record IS its own consumer -- no release engine is"
                        + " involved anywhere in this journey")
                    .isEqualTo(instanceId);

                // 2. THE FIRST DEPLOY. Nothing rebuilds the routing table (no site, domain
                //    or certificate is written), and no application generation exists to
                //    move -- yet the SAME handler must answer with the address now.
                service.deploy(instanceId);
                URI first = handler.current();
                assertThat(first)
                    .as("step 2: the deploy alone makes the site resolvable again --"
                        + " a handler that only re-reads on a release flip answers 503"
                        + " here forever")
                    .isNotNull();
                assertThat(first.getHost())
                    .as("step 2: on loopback, where the instance tier publishes")
                    .isEqualTo("127.0.0.1");
                assertThat(first.getPort())
                    .as("step 2: at the port the daemon actually bound")
                    .isEqualTo(ledgerPortOf(instanceId));
                assertThat(handler.getHealth().name())
                    .as("step 2: and the site is UP").isEqualTo("UP");
                assertThat(daemon.isRunning(handle))
                    .as("step 2: because the workload really runs at the daemon").isTrue();

                // 3. A STOP takes the address away again: the claims are released as
                //    observed and the record stops claiming a workload.
                int portA = first.getPort();
                service.stop(instanceId);
                assertThat(handler.current())
                    .as("step 3: a stopped workload is no upstream")
                    .isNull();
                assertThat(handler.getHealth().name())
                    .as("step 3: and the site says so").isEqualTo("DOWN");

                // 4. THE REDEPLOY, and the reason this defect is worse than a 503: the host
                //    port is EPHEMERAL, so the workload comes back somewhere else. Another
                //    listener stands on the freed port for the duration -- exactly what a
                //    different tenant's container would be -- so the new port is guaranteed
                //    to differ and a handler that kept its cached address would now be
                //    forwarding this site's traffic into that listener.
                ServerSocket squatter = occupy(portA);
                try {
                    assertThat(squatter.getLocalPort())
                        .as("step 4: the freed port is held by somebody else")
                        .isEqualTo(portA);
                    service.deploy(instanceId);
                    URI second = handler.current();
                    assertThat(second)
                        .as("step 4: the redeployed workload is resolvable").isNotNull();
                    assertThat(second.getPort())
                        .as("step 4: the SAME handler resolves the NEW host port, with no"
                            + " application generation and no routing-model write to carry"
                            + " it -- a cached address would point at the foreign listener")
                        .isNotEqualTo(portA);
                    assertThat(second.getPort())
                        .as("step 4: and it is the port the ledger now records")
                        .isEqualTo(ledgerPortOf(instanceId));
                } finally {
                    release(squatter);
                }

                // 5. DESTROY ends it: the record is gone and the site is honestly down,
                //    through the same one handler.
                service.destroy(instanceId);
                assertThat(handler.current())
                    .as("step 5: a destroyed workload leaves no upstream behind")
                    .isNull();
            } finally {
                Row row = Models.get(InstanceModel.class).findById(instanceId);
                if (row != null && row.get(InstanceModel.DELETED_AT) == null) {
                    service.destroy(instanceId);
                }
            }
        });
    }

    /**
     * The reconciler's correction is a ROUTING fact: a workload that died with nobody
     * watching keeps its port claim in the ledger, so only the corrected status can stop
     * the proxy naming a dead container's address.
     */
    @Test
    void aWorkloadCorrectedToStoppedStopsAnsweringItsDeadAddress() {
        Db.run(datasource, () -> {
            InstanceService service = new InstanceService();
            InstanceStatusReconciler reconciler = new InstanceStatusReconciler();
            int instanceId = container("crasher");
            int siteId = siteExposing(instanceId, "crasher");
            String handle = FakeDockerDaemon.handleOf(instanceId);
            try {
                // 1. Deployed and serving: the site has an address.
                service.deploy(instanceId);
                InstanceUpstreamHandler handler =
                    new InstanceUpstreamHandler(siteId, instanceId);
                assertThat(handler.current())
                    .as("step 1: a running workload is an upstream").isNotNull();

                // 2. THE CRASH: the process exits with nobody watching. No control-plane
                //    path ran, so the record still says running and the ledger still holds
                //    the claim -- the address is stale and nothing in the data says so.
                daemon.killWorkload(handle);
                assertThat((String) statusOf(instanceId))
                    .as("step 2: the record still claims running")
                    .isEqualTo(InstanceModel.STATUS_RUNNING);
                assertThat(ledgerPortOf(instanceId))
                    .as("step 2: and the ledger still holds the dead workload's claim --"
                        + " only a settled stop releases one")
                    .isNotNull();

                // 3. THE SWEEP corrects the record against daemon truth.
                InstanceStatusReconciler.Outcome corrected = reconciler.reconcile(instanceId);
                assertThat(corrected.verdict())
                    .as("step 3: the daemon disagrees, so the record is corrected")
                    .isEqualTo(Verdict.CORRECTED);
                assertThat((String) statusOf(instanceId))
                    .as("step 3: to stopped").isEqualTo(InstanceModel.STATUS_STOPPED);

                // 4. AND THE PROXY FOLLOWS. The correction is a hook-free updateAll, so
                //    nothing observes it on its own; without the invalidation the handler
                //    keeps naming the dead container's host port, which the daemon may
                //    already have handed to somebody else.
                assertThat(handler.current())
                    .as("step 4: the corrected record is no longer an upstream")
                    .isNull();
                assertThat(handler.getHealth().name())
                    .as("step 4: and the site reports DOWN honestly")
                    .isEqualTo("DOWN");

                // 5. FALSIFIED the other way: deploy it again and the same handler answers
                //    again -- so step 4 was the correction and not a handler that had
                //    simply stopped resolving anything.
                service.deploy(instanceId);
                assertThat(handler.current())
                    .as("step 5: a redeployed workload is an upstream once more")
                    .isNotNull();

                // 6. A CONFIRMATION is not a correction: the sweep agrees with the record,
                //    and the address it already resolved stands untouched.
                URI beforeSweep = handler.current();
                assertThat(reconciler.reconcile(instanceId).verdict())
                    .as("step 6: a daemon that agrees confirms")
                    .isEqualTo(Verdict.CONFIRMED);
                assertThat(handler.current())
                    .as("step 6: and nothing about the upstream moved")
                    .isEqualTo(beforeSweep);
            } finally {
                Row row = Models.get(InstanceModel.class).findById(instanceId);
                if (row != null && row.get(InstanceModel.DELETED_AT) == null) {
                    service.destroy(instanceId);
                }
            }
        });
    }

    // -- fixtures --------------------------------------------------------------

    /** A plain docker-container record with an ephemeral loopback publication. */
    private static int container(String name) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/app");
        settings.put("tag", "v1");
        settings.put("container_port", 8080);
        Row instance = Models.get(InstanceModel.class).createEmptyRow();
        instance.set(InstanceModel.NAME, name);
        instance.set(InstanceModel.KIND, DockerContainerKind.ID.toString());
        instance.set(InstanceModel.SERVER_ID, ServerModel.localServerId());
        instance.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(instance);
        return instance.get(InstanceModel.ID);
    }

    /** A site whose one upstream is this instance. */
    private static int siteExposing(int instanceId, String slug) {
        return ProxyTestSupport.setupInstanceSite("Site " + slug, slug, instanceId)
            .get(SiteModel.ID);
    }

    private static String statusOf(int instanceId) {
        return Models.get(InstanceModel.class).findById(instanceId).get(InstanceModel.STATUS);
    }

    /**
     * The instance's held host-port claim, read straight off the ledger -- the independent
     * oracle the resolution is asserted against, never the resolution itself.
     */
    private static Integer ledgerPortOf(int instanceId) {
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId)) {
            if (PortLedger.isReleasing(claim)) {
                continue;
            }
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port != null && port > 0) {
                return port;
            }
        }
        return null;
    }

    /**
     * Stand a real listener on a just-freed loopback port, so the next ephemeral bind
     * cannot land on it. Loud on failure: a silently skipped occupation would turn step 4
     * into an assertion that passes by luck.
     */
    private static ServerSocket occupy(int port) {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1);
            return socket;
        } catch (IOException unavailable) {
            throw new IllegalStateException(
                "could not occupy the freed port " + port + ": " + unavailable.getMessage(),
                unavailable);
        }
    }

    /** Hand the occupied port back; a close that fails would leave it held for the JVM. */
    private static void release(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException stubborn) {
            throw new IllegalStateException("could not release the occupied port", stubborn);
        }
    }
}
