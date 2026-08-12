package be.elevenways.hohenheim.test.ports;

import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The port ledger's pure claim/conflict logic against a temp SQLite: deliberately free
 * of any Docker daemon, so this coverage exists even on a machine where every
 * daemon-gated stack test skips.
 */
class PortLedgerTest {

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

    /** The canonical claim string folds every equivalent operator spelling onto one key. */
    @Test
    void canonicalClaimFoldsEquivalentSpellings() {
        // 1. 0.0.0.0, blank and null bind addresses are ONE whole-host bind.
        assertThat(PortLedger.portClaim("0.0.0.0", 8300, "tcp"))
            .as("step 1: 0.0.0.0 folds into the whole-host bind")
            .isEqualTo(PortLedger.portClaim("", 8300, "tcp"))
            .isEqualTo(PortLedger.portClaim(null, 8300, "tcp"));
        // 2. A missing/blank protocol defaults to tcp; case folds.
        assertThat(PortLedger.portClaim("", 8300, null))
            .as("step 2: protocol defaults to tcp and folds case")
            .isEqualTo(PortLedger.portClaim(" ", 8300, "TCP"));
        // 3. udp is a DIFFERENT claim, never folded away.
        assertThat(PortLedger.portClaim("", 8300, "udp"))
            .as("step 3: udp stays a distinct claim")
            .isNotEqualTo(PortLedger.portClaim("", 8300, "tcp"));
    }

    /**
     * The host-key bug, pinned directly: the local daemon reached as {@code ""} and as
     * {@code "local"} (and every other legacy spelling) must resolve to ONE server id
     * and therefore ONE claim set.
     */
    @Test
    void localHostSpellingsResolveToOneClaimSet() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            // 1. Every legacy spelling of the local daemon is THE local server.
            assertThat(ServerModel.canonicalServerId(""))
                .as("step 1: blank resolves to the local server").isEqualTo(localId);
            assertThat(ServerModel.canonicalServerId(null))
                .as("step 1: null resolves to the local server").isEqualTo(localId);
            assertThat(ServerModel.canonicalServerId("local"))
                .as("step 1: 'local' resolves to the local server").isEqualTo(localId);
            assertThat(ServerModel.canonicalServerId("hohenheim:local"))
                .as("step 1: the legacy registry key resolves to the local server")
                .isEqualTo(localId);
            assertThat(ServerModel.canonicalServerId("hohenheim:" + localId))
                .as("step 1: the id registry key resolves to the local server")
                .isEqualTo(localId);
            // 2. Consequently the claim keys coincide: one machine, one claim set.
            assertThat(PortLedger.claimKeyOf(ServerModel.canonicalServerId(""), "0.0.0.0", 8301, null))
                .as("step 2: '' and 'local' spell ONE claim key")
                .isEqualTo(PortLedger.claimKeyOf(ServerModel.canonicalServerId("local"), "", 8301, "tcp"));
            // 3. An unknown name refuses loudly instead of minting a fourth spelling.
            assertThatThrownBy(() -> ServerModel.canonicalServerId("no-such-host"))
                .as("step 3: unknown names are refused")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-such-host");
        });
    }

    /**
     * Claim, conflict (named), the three-state release contract: an UNVERIFIED release
     * parks the claim in {@code releasing} where it still blocks rivals (a port that
     * might be bound is not available), the owner may re-claim its own parked tuple,
     * and only an OBSERVED release frees it for the next claimant.
     */
    @Test
    void claimConflictNamesTheHolderAndOnlyObservedReleaseFreesTheTuple() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            Row db = Models.get(DatabaseModel.class).createEmptyRow();
            db.set(DatabaseModel.NAME, "claimdb");
            db.set(DatabaseModel.ENGINE, "postgres");
            db.set(DatabaseModel.DB_USER, "u");
            db.set(DatabaseModel.DB_PASSWORD, "p");
            db.set(DatabaseModel.DB_NAME, "d");
            Models.get(DatabaseModel.class).save(db);
            Integer dbId = db.get(DatabaseModel.ID);

            // 1. First claim wins.
            PortLedger.claim(localId, "", 8302, "tcp", DatabaseModel.MODEL_ID, dbId, null);
            // 2. A rival claim of an EQUIVALENT spelling is refused, naming the holder.
            assertThatThrownBy(() ->
                    PortLedger.claim(localId, "0.0.0.0", 8302, "TCP", null, null, "rival"))
                .as("step 2: the equivalent spelling is the same tuple and names its holder")
                .isInstanceOf(PortLedger.PortConflict.class)
                .hasMessageContaining("claimdb");
            // 3. A different port is untouched by the conflict.
            PortLedger.claim(localId, "", 8303, "tcp", null, null, "another port");
            // 4. An UNVERIFIED release parks the claim: the row survives as releasing...
            PortLedger.releaseOwner(DatabaseModel.MODEL_ID, dbId);
            Row parked = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8302, "tcp"));
            assertThat(parked).as("step 4: the parked claim row survives").isNotNull();
            assertThat(PortLedger.isReleasing(parked))
                .as("step 4: the parked claim is in the releasing state").isTrue();
            // 5. ...and a releasing row still BLOCKS a rival claim.
            assertThatThrownBy(() ->
                    PortLedger.claim(localId, "", 8302, "tcp", null, null, "rival"))
                .as("step 5: a releasing claim still blocks a new claimant")
                .isInstanceOf(PortLedger.PortConflict.class)
                .hasMessageContaining("claimdb");
            // 6. The OWNER may re-claim its own parked tuple (a restored workload
            //    landing on the same port is not its own rival).
            PortLedger.claim(localId, "", 8302, "tcp", DatabaseModel.MODEL_ID, dbId, null);
            Row reclaimed = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8302, "tcp"));
            assertThat(PortLedger.isReleasing(reclaimed))
                .as("step 6: the owner's re-claim is held again, not releasing").isFalse();
            // 7. Only the OBSERVED release deletes the row and frees the tuple.
            PortLedger.releaseOwnerObserved(DatabaseModel.MODEL_ID, dbId);
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8302, "tcp")))
                .as("step 7: the observed release removed the row").isNull();
            PortLedger.claim(localId, "", 8302, "tcp", null, null, "reclaimed");
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8302, "tcp")))
                .as("step 7: the freed tuple was re-claimable").isNotNull();
        });
    }

    /**
     * Kernel exclusivity, not key equality: a whole-host bind and a specific-address bind
     * of the same port cannot both exist, and the ledger must refuse the second even
     * though their claim keys differ. Two DIFFERENT specific addresses may co-exist.
     */
    @Test
    void wholeHostAndSpecificAddressClaimsOfOnePortExcludeEachOther() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();

            // 1. A loopback-specific claim (what a docker publication looks like).
            PortLedger.claim(localId, "127.0.0.1", 8320, "tcp", null, null, "docker publication");
            // 2. A whole-host claim of the same port is impossible at the kernel, so refused.
            assertThatThrownBy(() -> PortLedger.claim(localId, "0.0.0.0", 8320, "tcp", null, null, "stack"))
                .as("step 2: a whole-host bind cannot co-exist with a loopback bind")
                .isInstanceOf(PortLedger.PortConflict.class)
                .hasMessageContaining("docker publication");
            // 3. ... and the reverse direction is refused too.
            PortLedger.claim(localId, "", 8321, "tcp", null, null, "stack whole host");
            assertThatThrownBy(() ->
                    PortLedger.claim(localId, "127.0.0.1", 8321, "tcp", null, null, "docker"))
                .as("step 3: a loopback bind cannot co-exist with a whole-host bind")
                .isInstanceOf(PortLedger.PortConflict.class)
                .hasMessageContaining("stack whole host");
            // 4. Two distinct specific addresses genuinely do not exclude each other.
            PortLedger.claim(localId, "10.0.0.5", 8320, "tcp", null, null, "lan bind");
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "10.0.0.5", 8320, "tcp")))
                .as("step 4: a different specific address is a different kernel resource")
                .isNotNull();
            // 5. A different PROTOCOL on a taken port is untouched by the overlap rule.
            PortLedger.claim(localId, "", 8320, "udp", null, null, "udp is another resource");
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8320, "udp")))
                .as("step 5: udp/tcp overlap nothing").isNotNull();
        });
    }

    /**
     * The record-after contract end to end: the kernel already handed the port out, so
     * the ledger LEARNS it, re-learns it when the container is recreated on a different
     * ephemeral port, never steals a tuple, never throws at the caller, and gives the
     * port back when the owning record dies.
     */
    @Test
    void recordAfterLearnsRelearnsReportsAndReleasesWithTheRecord() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            Row db = Models.get(DatabaseModel.class).createEmptyRow();
            db.set(DatabaseModel.NAME, "recordafter");
            db.set(DatabaseModel.ENGINE, "postgres");
            db.set(DatabaseModel.DB_USER, "u");
            db.set(DatabaseModel.DB_PASSWORD, "p");
            db.set(DatabaseModel.DB_NAME, "d");
            Models.get(DatabaseModel.class).save(db);
            Integer dbId = db.get(DatabaseModel.ID);

            // 1. The observed port is learned, owned by the record.
            assertThat(PortLedger.recordObserved(localId, "127.0.0.1", 8330, "tcp",
                    DatabaseModel.MODEL_ID, dbId, null))
                .as("step 1: the observed port is recorded").isTrue();
            Row held = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "127.0.0.1", 8330, "tcp"));
            assertThat(held).as("step 1: the claim row exists").isNotNull();
            assertThat(PortLedger.isOwnedBy(held, DatabaseModel.MODEL_ID, dbId))
                .as("step 1: it is owned by the database record").isTrue();

            // 2. A recreate that lands on a different ephemeral port re-keys the claim:
            //    exactly one row per owner, and the abandoned port becomes free again.
            assertThat(PortLedger.recordObserved(localId, "127.0.0.1", 8331, "tcp",
                    DatabaseModel.MODEL_ID, dbId, null))
                .as("step 2: the new port is recorded").isTrue();
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "127.0.0.1", 8330, "tcp")))
                .as("step 2: the port the container no longer holds was released").isNull();
            assertThat(PortLedger.claimsOf(DatabaseModel.MODEL_ID, dbId))
                .as("step 2: an owner holds exactly one recorded port").hasSize(1);

            // 3. A tuple another owner already holds is REPORTED, not thrown, and not
            //    stolen: the running container keeps the port, the stale row keeps the row.
            PortLedger.claim(localId, "", 8332, "tcp", null, null, "an older claimant");
            assertThat(PortLedger.recordObserved(localId, "127.0.0.1", 8332, "tcp",
                    DatabaseModel.MODEL_ID, dbId, null))
                .as("step 3: a contested observation reports failure instead of throwing").isFalse();
            assertThat(PortLedger.describeHolder(
                    PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8332, "tcp"))))
                .as("step 3: the rival still holds its tuple").isEqualTo("an older claimant");

            // 4. Deleting the owning record through the model's remove hooks PARKS its
            //    claims in releasing (a record delete observes nothing about the port);
            //    the row survives, still blocks rivals, and only an observer frees it.
            assertThat(PortLedger.recordObserved(localId, "127.0.0.1", 8333, "tcp",
                    DatabaseModel.MODEL_ID, dbId, null))
                .as("step 4: the record holds a port again before it is deleted").isTrue();
            Models.get(DatabaseModel.class).delete(dbId);
            List<Row> outliving = PortLedger.claimsOf(DatabaseModel.MODEL_ID, dbId);
            assertThat(outliving)
                .as("step 4: the claim outlives its record").hasSize(1);
            assertThat(PortLedger.isReleasing(outliving.get(0)))
                .as("step 4: the outliving claim is parked in releasing").isTrue();
            assertThatThrownBy(() ->
                    PortLedger.claim(localId, "127.0.0.1", 8333, "tcp", null, null, "rival"))
                .as("step 4: the parked claim of a dead record still blocks a rival")
                .isInstanceOf(PortLedger.PortConflict.class);
            // 5. The observer's deletion closes the loop.
            PortLedger.releaseObserved(outliving.get(0));
            assertThat(PortLedger.claimsOf(DatabaseModel.MODEL_ID, dbId))
                .as("step 5: the observed release removed the parked claim").isEmpty();
        });
    }

    // AIDEV-NOTE: the stacks consumer's MODEL-tier journey lived here until the Phase 7
    // stack lowering (2026-08-07) deleted its subject. A stack service no longer claims
    // its declared host ports when the ROW is saved -- since the tier lowered onto the
    // instance runtime contract the claim belongs to the service's owned INSTANCE and is
    // made claim-before-create by the DEPLOY, then verified against the daemon's own
    // binding. The replacement journey is StackInstancesTest
    // #loweredServicesGainTheLedgerClaimAndTheCapacityCap, which asserts the claim, its
    // owner, the daemon binding, the contested-port refusal and the release on destroy.
    // What stays exercised here is the remove-hook pairing (captureDoomedOwners /
    // releaseDoomedOwners), which still releases a PRE-lowering service's leftover claims.

    /**
     * The instance tier's remove hooks: deleting an instance record must PARK its port
     * claims in {@code releasing} (its container may still be bound), never delete them
     * and never leave them {@code held} by a ghost. Without the before/after hook PAIR
     * on InstanceModel this fails in the worst way: the claim stays {@code held} with
     * no owner, no observer ever frees it, and the port is permanently unclaimable.
     */
    @Test
    void deletingAnInstanceParksItsClaimsInReleasing() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            // 1. An instance record owning one observed loopback port.
            Row instance = Models.get(InstanceModel.class).createEmptyRow();
            instance.set(InstanceModel.NAME, "hook-check");
            instance.set(InstanceModel.KIND, "hohenheim:docker_container");
            Models.get(InstanceModel.class).save(instance);
            int instanceId = instance.get(InstanceModel.ID);
            PortLedger.claim(localId, "127.0.0.1", 8340, "tcp",
                InstanceModel.MODEL_ID, instanceId, null);
            assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, instanceId))
                .as("step 1: the instance holds its claim").hasSize(1);

            // 2. Hard-delete the record (the criteria-only remove path every delete takes).
            Models.get(InstanceModel.class).delete(instanceId);
            Row survivor = PortLedger.holderOf(
                PortLedger.claimKeyOf(localId, "127.0.0.1", 8340, "tcp"));
            assertThat(survivor)
                .as("step 2: the claim SURVIVES its owner (a record delete observes nothing)")
                .isNotNull();
            assertThat(PortLedger.isReleasing(survivor))
                .as("step 2: and is parked in releasing, so only an observer frees it")
                .isTrue();

            // 3. It still blocks a rival until observed free -- reserved-until-observed.
            assertThatThrownBy(() -> PortLedger.claim(localId, "127.0.0.1", 8340, "tcp",
                    null, null, "rival"))
                .as("step 3: the parked claim still refuses a rival")
                .isInstanceOf(PortLedger.PortConflict.class);
            PortLedger.releaseObserved(survivor);
        });
    }

    /**
     * The pre-allocation strategy's ledger semantics: a stable reservation the stop path
     * keeps and the failure path never parks, arbitrated on ROWS when two allocations
     * race -- the unique claim key is the arbiter, never a log line.
     */
    @Test
    void preallocatedClaimsAreStableReservationsAndTheLedgerArbitratesRaces()
            throws Exception {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            Row one = Models.get(InstanceModel.class).createEmptyRow();
            one.set(InstanceModel.NAME, "prealloc-one");
            one.set(InstanceModel.KIND, "hohenheim:docker_container");
            Models.get(InstanceModel.class).save(one);
            int oneId = one.get(InstanceModel.ID);

            // 1. A UDP public pre-allocation writes a held, mode-stamped row.
            PortLedger.claimPreallocated(localId, "", 8350, "udp",
                InstanceModel.MODEL_ID, oneId, null);
            Row claim = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8350, "udp"));
            assertThat(claim).as("step 1: the pre-allocated claim exists").isNotNull();
            assertThat(PortLedger.isPreallocated(claim))
                .as("step 1: the row carries the pre-allocation discriminator").isTrue();
            assertThat(PortLedger.isReleasing(claim))
                .as("step 1: and is held, not releasing").isFalse();

            // 2. The STOP release keeps the reservation; the failure park skips it too.
            PortLedger.releaseOwnerObserved(InstanceModel.MODEL_ID, oneId);
            Row afterStop = PortLedger.holderOf(
                PortLedger.claimKeyOf(localId, "", 8350, "udp"));
            assertThat(afterStop)
                .as("step 2: a stop's observed release KEEPS the pre-allocated number")
                .isNotNull();
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, oneId);
            assertThat(PortLedger.isReleasing(PortLedger.holderOf(
                    PortLedger.claimKeyOf(localId, "", 8350, "udp"))))
                .as("step 2: a failure park never parks the reservation either")
                .isFalse();

            // 3. Only the end-of-life release frees it.
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, oneId);
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8350, "udp")))
                .as("step 3: the verified destroy releases the reservation")
                .isNull();
            Models.get(InstanceModel.class).delete(oneId);
        });

        // 4. TWO CONCURRENT pre-allocations of one tuple: exactly one row wins, the
        //    loser gets the NAMED conflict -- asserted on rows, not on a log line.
        int[] ids = new int[2];
        Db.run(datasource, () -> {
            for (int i = 0; i < 2; i++) {
                Row rival = Models.get(InstanceModel.class).createEmptyRow();
                rival.set(InstanceModel.NAME, "prealloc-race-" + i);
                rival.set(InstanceModel.KIND, "hohenheim:docker_container");
                Models.get(InstanceModel.class).save(rival);
                ids[i] = rival.get(InstanceModel.ID);
            }
        });
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        java.util.List<Throwable> refusals =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread[] racers = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int ownerId = ids[i];
            racers[i] = new Thread(() -> Db.run(datasource, () -> {
                try {
                    barrier.await();
                    PortLedger.claimPreallocated(ServerModel.localServerId(), "", 8351,
                        "udp", InstanceModel.MODEL_ID, ownerId, null);
                } catch (PortLedger.PortConflict conflict) {
                    refusals.add(conflict);
                } catch (Exception unexpected) {
                    refusals.add(unexpected);
                }
            }));
            racers[i].start();
        }
        for (Thread racer : racers) {
            racer.join();
        }
        Db.run(datasource, () -> {
            List<Row> rows = Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.SERVER_ID.eq(ServerModel.localServerId()))
                .and(PortAllocationModel.PORT.eq(8351))
                .all();
            assertThat(rows)
                .as("step 4: exactly ONE ledger row exists for the contested tuple")
                .hasSize(1);
            assertThat(refusals)
                .as("step 4: exactly one racer was refused, with the NAMED conflict")
                .hasSize(1);
            assertThat(refusals.get(0))
                .as("step 4: the refusal is the ledger's PortConflict, naming the holder")
                .isInstanceOf(PortLedger.PortConflict.class);
            Integer winner = rows.get(0).get(PortAllocationModel.OWNER_ID);
            String winnerName = Models.get(InstanceModel.class).findById(winner)
                .get(InstanceModel.NAME);
            assertThat(((PortLedger.PortConflict) refusals.get(0)).getHolder())
                .as("step 4: the refusal names the WINNING instance")
                .contains(winnerName);
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, ids[0]);
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, ids[1]);
            Models.get(InstanceModel.class).delete(ids[0]);
            Models.get(InstanceModel.class).delete(ids[1]);
        });
    }

    private static Row port(int container, int host, String protocol, String hostIp) {
        Row port = new Row();
        port.set(StackServiceModel.PORT_CONTAINER, container);
        port.set(StackServiceModel.PORT_HOST, host);
        port.set(StackServiceModel.PORT_PROTOCOL, protocol);
        port.set(StackServiceModel.PORT_HOST_IP, hostIp);
        return port;
    }
}
