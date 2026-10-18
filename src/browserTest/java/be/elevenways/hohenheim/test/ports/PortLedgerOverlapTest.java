package be.elevenways.hohenheim.test.ports;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.ports.PortClaimFence;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.testsupport.thread.Racers;
import be.elevenways.zenit.common.orm.OrmConstants;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.Datasource.Transaction;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.lease.LeaseModel;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger's multi-row answers: every observed publication of one owner survives one
 * record-after call, a rival is found behind the owner's own overlapping row, an owner's
 * re-claim keeps its row, and two different-key binds of one kernel port race to exactly
 * one row.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class PortLedgerOverlapTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    /**
     * Record-after with several publications: all of them are kept, a later observation
     * supersedes only the ports the owner no longer holds, and a pre-allocated reservation
     * is never superseded.
     */
    @Test
    void everyObservedPublicationOfOneOwnerSurvives() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            int ownerId = instance("multi-publication");

            // 1. Two publications observed in ONE call: both claims exist.
            assertThat(PortLedger.recordObservedAll(localId, "127.0.0.1", List.of(8401, 8402),
                    "tcp", InstanceModel.MODEL_ID, ownerId, null))
                .as("step 1: both observed ports are recorded").isTrue();
            assertThat(ports(ownerId))
                .as("step 1: the owner holds BOTH publications, not only the last one")
                .containsExactlyInAnyOrder(8401, 8402);
            Integer keptId = PortLedger.holderOf(
                PortLedger.claimKeyOf(localId, "127.0.0.1", 8401, "tcp")).get(PortAllocationModel.ID);

            // 2. A pre-allocated reservation of the same owner sits beside them.
            PortLedger.claimPreallocated(localId, "", 8403, "udp", InstanceModel.MODEL_ID,
                ownerId, null);

            // 3. A recreate observes 8401 again and 8404 instead of 8402: 8402 is released,
            //    8401 keeps its row, 8404 is new, the reservation is untouched.
            assertThat(PortLedger.recordObservedAll(localId, "127.0.0.1", List.of(8401, 8404),
                    "tcp", InstanceModel.MODEL_ID, ownerId, null))
                .as("step 3: the re-observation is recorded").isTrue();
            assertThat(ports(ownerId))
                .as("step 3: only the port the container no longer holds was released")
                .containsExactlyInAnyOrder(8401, 8403, 8404);
            Integer stillHeldId = PortLedger.holderOf(
                PortLedger.claimKeyOf(localId, "127.0.0.1", 8401, "tcp")).get(PortAllocationModel.ID);
            assertThat(stillHeldId)
                .as("step 3: the still-held port kept its row (updated in place)")
                .isEqualTo(keptId);
            assertThat(PortLedger.isPreallocated(PortLedger.holderOf(
                    PortLedger.claimKeyOf(localId, "", 8403, "udp"))))
                .as("step 3: the pre-allocated reservation survived the supersession")
                .isTrue();

            // 4. The single-port face still supersedes to exactly that one observation.
            assertThat(PortLedger.recordObserved(localId, "127.0.0.1", 8404, "tcp",
                    InstanceModel.MODEL_ID, ownerId, null))
                .as("step 4: the single-port record-after still records").isTrue();
            assertThat(ports(ownerId))
                .as("step 4: a single observation keeps that port plus the reservation")
                .containsExactlyInAnyOrder(8403, 8404);
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, ownerId);
        });
    }

    /**
     * The overlap refusal asks for the RIVAL, so the owner's own overlapping row coming
     * back first can no longer hide one; and an owner's re-claim updates its row in place.
     */
    @Test
    void aRivalIsFoundBehindTheOwnersOwnRowAndReclaimsUpdateInPlace() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();
            int ownerId = instance("overlap-owner");
            int rivalId = instance("overlap-rival");

            // 1. The owner binds loopback first (lower id), a rival binds a LAN address.
            PortLedger.claim(localId, "127.0.0.1", 8410, "tcp", InstanceModel.MODEL_ID,
                ownerId, null);
            PortLedger.claim(localId, "10.0.0.5", 8410, "tcp", InstanceModel.MODEL_ID,
                rivalId, null);

            // 2. The owner widening to a whole-host bind overlaps BOTH rows; the rival must
            //    be named even though the owner's own row is the first overlap.
            Integer firstOverlapOwner = PortLedger.conflictingHolder(localId, "", 8410, "tcp")
                .get(PortAllocationModel.OWNER_ID);
            assertThat(firstOverlapOwner)
                .as("step 2: the first overlapping row is the owner's own").isEqualTo(ownerId);
            assertThatThrownBy(() -> PortLedger.claim(localId, "0.0.0.0", 8410, "tcp",
                    InstanceModel.MODEL_ID, ownerId, null))
                .as("step 2: the rival's overlapping bind refuses the whole-host claim")
                .isInstanceOf(PortLedger.PortConflict.class)
                .hasMessageContaining("overlap-rival");
            assertThat(rowsOn(localId, 8410))
                .as("step 2: the refused claim changed nothing, both rows survive").hasSize(2);

            // 3. An owner re-claim of its parked row is an UPDATE: same id, held again.
            Row own = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "127.0.0.1", 8410, "tcp"));
            Integer ownRowId = own.get(PortAllocationModel.ID);
            PortLedger.releaseOwner(InstanceModel.MODEL_ID, ownerId);
            assertThat(PortLedger.isReleasing(PortLedger.holderOf(
                    PortLedger.claimKeyOf(localId, "127.0.0.1", 8410, "tcp"))))
                .as("step 3: the unverified release parked the owner's row").isTrue();
            PortLedger.claim(localId, "127.0.0.1", 8410, "tcp", InstanceModel.MODEL_ID,
                ownerId, "re-claimed");
            Row reclaimed = PortLedger.holderOf(PortLedger.claimKeyOf(localId, "127.0.0.1", 8410, "tcp"));
            Integer reclaimedId = reclaimed.get(PortAllocationModel.ID);
            assertThat(reclaimedId)
                .as("step 3: the re-claim updated the owner's row in place").isEqualTo(ownRowId);
            assertThat(PortLedger.isReleasing(reclaimed))
                .as("step 3: and it is held again").isFalse();

            // 4. With the rival gone the owner may widen, and its row is RE-KEYED in place.
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, rivalId);
            PortLedger.claim(localId, "", 8410, "tcp", InstanceModel.MODEL_ID, ownerId, null);
            List<Row> after = rowsOn(localId, 8410);
            assertThat(after).as("step 4: exactly one row holds the port").hasSize(1);
            Integer widenedId = after.get(0).get(PortAllocationModel.ID);
            assertThat(widenedId)
                .as("step 4: the widened claim kept the owner's row id").isEqualTo(ownRowId);
            String widenedKey = after.get(0).get(PortAllocationModel.CLAIM_KEY);
            assertThat(widenedKey)
                .as("step 4: the row now carries the whole-host key")
                .isEqualTo(PortLedger.claimKeyOf(localId, "", 8410, "tcp"));
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, ownerId);
        });
    }

    /**
     * A whole-host claim and an address-specific claim of one port have DIFFERENT keys,
     * so the unique index cannot arbitrate them; racing both must still end in one row
     * and one named refusal.
     */
    @Test
    void wholeHostAndSpecificClaimsRacingEndInOneRow() throws Exception {
        int[] owners = new int[2];
        int[] localId = new int[1];
        Db.run(datasource, () -> {
            owners[0] = instance("race-whole-host");
            owners[1] = instance("race-loopback");
            // Resolved BEFORE the race: localServerId() creates the local row on first use,
            // and two racers doing that at once collide on servers.name instead of on the
            // port this test is about.
            localId[0] = ServerModel.localServerId();
        });
        String[] binds = {"0.0.0.0", "127.0.0.1"};
        List<Throwable> refusals = Racers.race("two binds of one port", Duration.ofSeconds(60), 2, slot -> {
            Db.run(datasource, () -> PortLedger.claim(localId[0], binds[slot], 8420, "tcp",
                InstanceModel.MODEL_ID, owners[slot], null));
            return null;
        }).failures();
        Db.run(datasource, () -> {
            // 1. Exactly one of the two impossible-together binds holds the port.
            assertThat(rowsOn(localId[0], 8420))
                .as("step 1: one row survives the race of two different-key binds").hasSize(1);
            // 2. The loser got the ledger's named conflict, not a silent second row.
            assertThat(refusals).as("step 2: exactly one racer was refused").hasSize(1);
            assertThat(refusals.get(0))
                .as("step 2: the refusal is the named PortConflict")
                .isInstanceOf(PortLedger.PortConflict.class);
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, owners[0]);
            PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, owners[1]);
        });
    }

    /**
     * A claim whose lease a successor took over before the claim committed lands nothing: its decide-then-write
     * commits only while storage still names it the lease's owner, so a stalled claimant can never save a row it
     * decided on with lost ownership.
     */
    @Test
    void aClaimWhoseLeaseWasTakenOverBeforeItCommittedLandsNothing() throws Exception {
        int[] owner = new int[1];
        int[] localId = new int[1];
        Db.run(datasource, () -> {
            owner[0] = instance("stalled-claimant");
            localId[0] = ServerModel.localServerId();
        });
        String key = PortLedger.claimKeyOf(localId[0], "", 8471, "tcp");
        StealingDatasource stalled = new StealingDatasource(HohenheimDatabase.resolution().url());
        try {
            // 1. Between taking the host's claim lease and opening its write, the claimant's lease is taken over in
            //    storage by a successor (the expired-and-stolen shape a stall past the lease's TTL produces).
            stalled.stealBeforeTransaction = PortClaimFence.keyFor(localId[0]);
            assertThatThrownBy(() -> Db.run(stalled, () -> PortLedger.claim(localId[0], "", 8471, "tcp",
                    InstanceModel.MODEL_ID, owner[0], null)))
                .as("step 1: the claim is refused at its commit, naming the lost lease")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Lost lease");
            assertThat(stalled.stealBeforeTransaction).as("step 1: sanity, the lease was taken over").isNull();

            // 2. Nothing of the claim committed: the port holds no row of the stalled claimant.
            Db.run(datasource, () -> assertThat(PortLedger.holderOf(key))
                .as("step 2: the claim decided under lost ownership rolled back").isNull());
        } finally {
            stalled.close();
            // The successor never existed: hand the host's claim lease back, or every later claim of the host waits.
            new LeaseModel(datasource).find().where(LeaseModel.LEASE_KEY.eq(PortClaimFence.keyFor(localId[0])))
                .and(LeaseModel.OWNER_TOKEN.eq("port-successor")).assign(LeaseModel.OWNER_TOKEN, null).updateAll();
            Db.run(datasource, () -> PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, owner[0]));
        }
    }

    /**
     * A delete aimed at another control-plane database parks THAT database's claims of the removed owner, never the
     * ambient database's claims of an owner with the same id.
     */
    @Test
    void aDeleteAimedAtAnotherDatabaseParksOnlyThatDatabasesClaims() throws Exception {
        // A second control-plane database of its own, migrated from empty: TestDatabases swaps the shared one.
        File file = File.createTempFile("hohenheim-colliding", ".db");
        file.delete();
        file.deleteOnExit();
        SqliteDatasource other = new SqliteDatasource("jdbc:sqlite:" + file.getAbsolutePath());
        new MigrationRunner(other).migrate().requireSuccess();
        int ownerId = 990_001;
        try {
            // 1. Both databases hold an instance with the same id, and each holds a claim of it on port 8481.
            for (SqlDatasource database : List.of(datasource, other)) {
                Db.run(database, () -> {
                    Row row = Models.get(InstanceModel.class).createEmptyRow();
                    row.set(InstanceModel.ID, ownerId);
                    row.set(InstanceModel.NAME, "colliding-owner");
                    row.set(InstanceModel.KIND, "hohenheim:docker_container");
                    Models.get(InstanceModel.class).insert(row);
                    PortLedger.claim(ServerModel.localServerId(), "", 8481, "tcp", InstanceModel.MODEL_ID,
                        ownerId, null);
                });
            }

            // 2. With the first database ambient, the instance is hard-deleted from the OTHER one.
            Db.run(datasource, () -> assertThat(Models.get(InstanceModel.class).find()
                    .where(InstanceModel.ID.eq(ownerId))
                    .hint(OrmConstants.SOFT_DELETE_FORCE_HINT, Boolean.TRUE)
                    .on(other).delete())
                .as("step 2: the delete removed the other database's owner").isEqualTo(1));

            // 3. The other database's claim is parked, and the ambient database's claim of its own owner is not.
            Db.run(other, () -> assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, ownerId))
                .as("step 3: THE ASSERTION -- the removed owner's claim is parked where it was removed")
                .singleElement().satisfies(claim -> assertThat(PortLedger.isReleasing(claim)).isTrue()));
            Db.run(datasource, () -> assertThat(PortLedger.claimsOf(InstanceModel.MODEL_ID, ownerId))
                .as("step 3: the ambient database's same-id owner keeps its claim held")
                .singleElement().satisfies(claim -> assertThat(PortLedger.isReleasing(claim)).isFalse()));
        } finally {
            Db.run(datasource, () -> {
                PortLedger.releaseOwnerFully(InstanceModel.MODEL_ID, ownerId);
                HardDeletes.byId(Models.get(InstanceModel.class), ownerId);
            });
            other.close();
        }
    }

    /**
     * The control-plane database, opened a second time: before the next outermost transaction it hands the named
     * lease to a successor through the first connection, the way a stalled holder loses it.
     */
    private static final class StealingDatasource extends SqliteDatasource {

        private volatile @Nullable String stealBeforeTransaction;

        private StealingDatasource(String url) {
            super(url);
        }

        @Override
        public void withTransaction(Consumer<Transaction> block) {
            String lease = this.stealBeforeTransaction;
            if (lease != null && !super.hasActiveTransaction()) {
                int taken = new LeaseModel(datasource).find()
                    .where(LeaseModel.LEASE_KEY.eq(lease))
                    .assign(LeaseModel.OWNER_TOKEN, "port-successor")
                    .increment(LeaseModel.FENCE)
                    .updateAll();
                if (taken == 1) {
                    this.stealBeforeTransaction = null;
                }
            }
            super.withTransaction(block);
        }
    }

    private static int instance(String name) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static List<Integer> ports(int ownerId) {
        List<Integer> ports = new ArrayList<>();
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, ownerId)) {
            ports.add(claim.get(PortAllocationModel.PORT));
        }
        return ports;
    }

    private static List<Row> rowsOn(int serverId, int port) {
        return Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.SERVER_ID.eq(serverId))
            .and(PortAllocationModel.PORT.eq(port))
            .all();
    }
}
