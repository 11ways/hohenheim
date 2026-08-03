package be.elevenways.hohenheim.test.ports;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The port ledger's pure claim/conflict logic against a temp SQLite: deliberately free
 * of any Docker daemon, so this coverage exists even on a machine where every
 * daemon-gated stack test skips.
 */
class PortLedgerTest {

    private static SqliteDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-portledger-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
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

    /** Claim, conflict (named), release, re-claim: the ledger's exclusivity contract. */
    @Test
    void claimConflictNamesTheHolderAndReleaseFreesTheTuple() {
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
            // 4. Releasing the owner frees the tuple for the next claimant.
            PortLedger.releaseOwner(DatabaseModel.MODEL_ID, dbId);
            PortLedger.claim(localId, "", 8302, "tcp", null, null, "reclaimed");
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8302, "tcp")))
                .as("step 4: the released tuple was re-claimable").isNotNull();
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

            // 4. Deleting the owning record releases its claims -- through the model's
            //    remove hooks, so EVERY delete path frees the port, not just destroy().
            assertThat(PortLedger.recordObserved(localId, "127.0.0.1", 8333, "tcp",
                    DatabaseModel.MODEL_ID, dbId, null))
                .as("step 4: the record holds a port again before it is deleted").isTrue();
            Models.get(DatabaseModel.class).delete(dbId);
            assertThat(PortLedger.claimsOf(DatabaseModel.MODEL_ID, dbId))
                .as("step 4: the record's death released its port").isEmpty();
        });
    }

    /**
     * The stacks consumer end to end at the MODEL tier: saving a service syncs its
     * declared claims, editing diff-syncs, a contested save is refused AND rolled back,
     * moving the stack re-keys, deleting releases.
     */
    @Test
    void stackServiceSavesSyncEditReKeyAndReleaseLedgerClaims() {
        Db.run(datasource, () -> {
            int localId = ServerModel.localServerId();

            Row stack = Models.get(StackModel.class).createEmptyRow();
            stack.set(StackModel.NAME, "ledgerflow");
            Models.get(StackModel.class).save(stack);
            Integer stackId = stack.get(StackModel.ID);
            assertThat(stack.get(StackModel.SERVER_ID))
                .as("step 0: a stack without an explicit host defaults to the local FK")
                .isEqualTo(localId);

            // 1. Saving a service with a declared host port claims it.
            Row service = Models.get(StackServiceModel.class).createEmptyRow();
            service.set(StackServiceModel.STACK_ID, stackId);
            service.set(StackServiceModel.NAME, "web");
            service.set(StackServiceModel.ENABLED, true);
            service.set(StackServiceModel.IMAGE, "alpine:latest");
            service.setRecords(StackServiceModel.PORTS, List.of(port(80, 8310, "tcp", "")));
            Models.get(StackServiceModel.class).save(service);
            Integer serviceId = service.get(StackServiceModel.ID);
            String firstKey = PortLedger.claimKeyOf(localId, "", 8310, "tcp");
            assertThat(PortLedger.holderOf(firstKey))
                .as("step 1: the declared port is claimed in the ledger").isNotNull();

            // 2. A SECOND service declaring the same port is refused by the ledger's
            //    backstop and its row is rolled back with the failed claim.
            Row rival = Models.get(StackServiceModel.class).createEmptyRow();
            rival.set(StackServiceModel.STACK_ID, stackId);
            rival.set(StackServiceModel.NAME, "rival");
            rival.set(StackServiceModel.ENABLED, true);
            rival.set(StackServiceModel.IMAGE, "alpine:latest");
            rival.setRecords(StackServiceModel.PORTS, List.of(port(81, 8310, "tcp", "0.0.0.0")));
            assertThatThrownBy(() -> Models.get(StackServiceModel.class).save(rival))
                .as("step 2: the contested save is a named conflict")
                .isInstanceOf(PortLedger.PortConflict.class)
                .hasMessageContaining("web");
            assertThat(Models.get(StackServiceModel.class).find()
                .where(StackServiceModel.NAME.eq("rival")).count())
                .as("step 2: the refused service row did not survive the rollback")
                .isEqualTo(0);

            // 3. Editing the ports diff-syncs: the old claim is released, the new held.
            Row reloaded = Models.get(StackServiceModel.class).findById(serviceId);
            reloaded.setRecords(StackServiceModel.PORTS, List.of(port(80, 8311, "udp", "")));
            Models.get(StackServiceModel.class).save(reloaded);
            assertThat(PortLedger.holderOf(firstKey))
                .as("step 3: the abandoned tcp claim was released").isNull();
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8311, "udp")))
                .as("step 3: the udp claim is held (protocol is a first-class value)")
                .isNotNull();

            // 4. Moving the stack to another host re-keys the claims onto that host.
            Row edge = Models.get(ServerModel.class).createEmptyRow();
            edge.set(ServerModel.NAME, "ledger-edge");
            edge.set(ServerModel.MODE, ServerModel.MODE_SSH);
            Models.get(ServerModel.class).save(edge);
            Row movedStack = Models.get(StackModel.class).findById(stackId);
            movedStack.set(StackModel.SERVER_ID, edge.get(ServerModel.ID));
            Models.get(StackModel.class).save(movedStack);
            assertThat(PortLedger.holderOf(PortLedger.claimKeyOf(localId, "", 8311, "udp")))
                .as("step 4: the local host no longer holds the claim").isNull();
            assertThat(PortLedger.holderOf(
                    PortLedger.claimKeyOf(edge.get(ServerModel.ID), "", 8311, "udp")))
                .as("step 4: the new host holds it").isNotNull();

            // 5. Deleting the service releases every claim it held.
            Models.get(StackServiceModel.class).delete(serviceId);
            assertThat(Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.OWNER_MODEL.eq(StackServiceModel.MODEL_ID.toString()))
                .and(PortAllocationModel.OWNER_ID.eq(serviceId)).count())
                .as("step 5: deletion releases the service's claims")
                .isEqualTo(0);
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
