package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.cms.ServerResource;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The admin resource's dead delete and the removal refusal agree about what holds a host,
 * and the implicit local host row is created exactly once however many first calls race.
 */
class HostRemovalAgreementTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * A shared database engine and a port claim used to be counted by the removal refusal
     * but not by the resource's delete availability, so the button looked alive and the
     * delete then refused.
     */
    @Test
    void theDeleteButtonIsDeadExactlyWhenTheRemovalRefuses() {
        Db.run(datasource, () -> {
            ServerResource servers = new ServerResource();
            AccessContext operator = AccessContext.of(TenantConduits.stubFor(null));
            Row host = Models.get(ServerModel.class).createEmptyRow();
            host.set(ServerModel.NAME, "agree-host");
            Models.get(ServerModel.class).save(host);
            int hostId = host.get(ServerModel.ID);

            // 1. POSITIVE ANCHOR: an unreferenced host is offered alive.
            assertThat(servers.deleteUnavailableReason(host, operator))
                .as("step 1: an empty host's delete is available").isNull();

            // 2. A shared database engine on the host: dead, WITH the engine counted, and the
            //    funnel refuses the very same host.
            Row engine = Models.get(DatabaseEngineModel.class).createEmptyRow();
            engine.set(DatabaseEngineModel.NAME, "agree-engine");
            engine.set(DatabaseEngineModel.ENGINE, DatabaseModel.ENGINE_POSTGRES);
            engine.set(DatabaseEngineModel.IMAGE, "postgres:16");
            engine.set(DatabaseEngineModel.SERVER_ID, hostId);
            engine.set(DatabaseEngineModel.ROOT_USER, "root");
            engine.set(DatabaseEngineModel.ROOT_PASSWORD, "agree-root-pw");
            engine.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_ACTIVE);
            Models.get(DatabaseEngineModel.class).save(engine);
            Microcopy byEngine = servers.deleteUnavailableReason(host, operator);
            assertThat(byEngine).as("step 2: a host carrying a shared engine is not deletable")
                .isNotNull();
            assertThat(byEngine.key()).as("step 2: with the in-use reason").isEqualTo("delete_in_use");
            assertThat(byEngine.args().get("engines")).as("step 2: naming the engine").isEqualTo(1L);
            assertThat(byEngine.args().get("workloads"))
                .as("step 2: and counting it in the total").isEqualTo(1L);
            assertThat(violationKeyOf(catchThrowable(() ->
                    Models.get(ServerModel.class).delete((Object) hostId))))
                .as("step 2: the removal refuses the same host").isEqualTo("server_in_use");
            Models.get(DatabaseEngineModel.class).delete(engine.get(DatabaseEngineModel.ID));

            // 3. A port claim on the host: the same agreement.
            PortLedger.claim(hostId, "", 47811, "tcp", null, null, "agreement probe");
            Microcopy byPort = servers.deleteUnavailableReason(host, operator);
            assertThat(byPort).as("step 3: a host holding a port claim is not deletable").isNotNull();
            assertThat(byPort.args().get("ports")).as("step 3: naming the claim").isEqualTo(1L);
            assertThat(violationKeyOf(catchThrowable(() ->
                    Models.get(ServerModel.class).delete((Object) hostId))))
                .as("step 3: and the removal refuses it too").isEqualTo("server_in_use");

            // 4. Released, the host is offered alive again and really goes.
            PortLedger.releaseKey(PortLedger.claimKeyOf(hostId, "", 47811, "tcp"));
            assertThat(servers.deleteUnavailableReason(host, operator))
                .as("step 4: with nothing left the delete is available again").isNull();
            Models.get(ServerModel.class).delete((Object) hostId);
            assertThat(Models.get(ServerModel.class).findById(hostId))
                .as("step 4: and the removal the button promised goes through").isNull();
        });
    }

    /** Racing first calls used to insert the local host twice; the loser hit the unique name. */
    @Test
    void concurrentFirstCallsCreateTheLocalHostOnce() throws Exception {
        // 1. Start from a store with NO local host row (the boot seed may have planted one).
        Db.run(datasource, () -> Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq(ServerModel.LOCAL_HOST_NAME)).delete());
        assertThat(Db.supply(datasource, () ->
                Models.get(ServerModel.class).findByName(ServerModel.LOCAL_HOST_NAME)))
            .as("step 1: the local host row is gone").isNull();

        // 2. Eight first calls behind one barrier.
        int racers = 8;
        CyclicBarrier start = new CyclicBarrier(racers);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<Integer>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return Db.supply(datasource, ServerModel::localServerId);
                }));
            }
            Set<Integer> ids = new HashSet<>();
            for (Future<Integer> call : calls) {
                ids.add(call.get(30, TimeUnit.SECONDS));
            }

            // 3. Every caller got the same row, and exactly one exists.
            assertThat(ids).as("step 3: every racing caller resolved the same id").hasSize(1);
            assertThat(Db.supply(datasource, () -> Models.get(ServerModel.class).find()
                    .where(ServerModel.NAME.eq(ServerModel.LOCAL_HOST_NAME)).count()))
                .as("step 3: and exactly one local host row was written").isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String violationKeyOf(Throwable thrown) {
        assertThat(thrown).as("the delete was refused with Violations").isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }
}
