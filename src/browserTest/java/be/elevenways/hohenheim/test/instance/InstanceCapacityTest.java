package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The per-HOST memory ledger: the booking is ADJACENT to the instance write (a beforeWrite
 * hook over the core reservation ledger), so two racing creates cannot both spend the last
 * megabyte, and EVERY terminating path hands the booking back.
 *
 * AIDEV-NOTE: the terminating-path coverage is the point, not decoration.
 * InstanceService.destroy soft-deletes through save(), so the remove hooks never fire
 * there -- a release that only rode the remove hooks would leak on every destroy and
 * progressively lock the host out. The soft-delete transition, the restore transition, the
 * HOST CHANGE an admin makes through the CMS form, and the hard delete are each walked
 * below. The MIGRATION lane is deliberately NOT here: it never touches this hook (a fenced
 * updateAll fires none), so it is asserted against the real InstanceMigrations in
 * InstanceMigrationTest.
 *
 * No daemon is contacted: every decision here is over stored record state.
 */
class InstanceCapacityTest {

    private static final String PREFIX = "capacity-";
    private static final String DOCKER_KIND = "hohenheim:docker_container";

    private static SqliteDatasource datasource;

    private final List<Integer> hosts = new ArrayList<>();
    private final List<Integer> instances = new ArrayList<>();

    /** Its OWN database: a neighbour's live instance row would move every budget here. */
    @BeforeAll
    static void setUp() throws Exception {
        File db = File.createTempFile("hohenheim-capacity-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB, 0);
        HohenheimSettings.VALUES.setValue(
            HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO, 1.0);
    }

    @AfterEach
    void cleanUp() {
        Db.run(datasource, () -> {
            for (Integer id : this.instances) {
                Models.get(InstanceModel.class).delete(id);
            }
            for (Integer id : this.hosts) {
                Models.get(ServerModel.class).delete(id);
            }
        });
        this.instances.clear();
        this.hosts.clear();
    }

    private int host(String name, Long memoryMb, Instant probedAt) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, PREFIX + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        int id = row.get(ServerModel.ID);
        this.hosts.add(id);
        Map<String, Object> facts = new LinkedHashMap<>();
        if (memoryMb != null) {
            facts.put("mem_total", memoryMb * 1024L * 1024L);
        }
        HostPreflight.store(PREFIX + name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            facts, true, probedAt, null));
        return id;
    }

    private Row newWorkload(int serverId, String name, Integer memoryMb) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, PREFIX + name);
        row.set(InstanceModel.KIND, DOCKER_KIND);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "fake/image");
        if (memoryMb != null) {
            settings.put("memory_limit_mb", memoryMb);
        }
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, serverId);
        return row;
    }

    private int save(Row row) {
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        this.instances.add(id);
        return id;
    }

    /**
     * The violation identity a refusal carries.
     *
     * AIDEV-NOTE: the description travels INTO the type assertion on purpose. A gate that
     * stopped refusing hands this method null, and a bare "expecting actual not to be
     * null" names neither the gate nor what it failed to stop -- which is the whole point
     * of a counterfactual.
     */
    private static String keyOf(String description, Throwable thrown) {
        assertThat(thrown).as(description).isNotNull();
        assertThat(thrown).as(description).isInstanceOf(Violations.class);
        return ((Violations) thrown).all().get(0).message().key();
    }

    /**
     * One workload walked through every state that changes what a host is carrying:
     * created, resized, migrated, soft-deleted, restored, hard-deleted.
     */
    @Test
    void everyTerminatingAndMovingPathHandsTheBookingBack() {
        Db.run(datasource, () -> {
            int alpha = host("alpha", 2048L, Instant.now());
            int beta = host("beta", 2048L, Instant.now());

            // 1. A create books its DECLARED limit and stamps it on the row.
            Row row = newWorkload(alpha, "walker", 512);
            int id = save(row);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 1: the create booked its 512 MB on alpha").isEqualTo(512);
            assertThat((Integer) Models.get(InstanceModel.class).findById(id)
                    .get(InstanceModel.CAPACITY_MB))
                .as("step 1: and the booked amount is stamped on the row").isEqualTo(512);

            // 2. A workload declaring NO limit is booked at its KIND's footprint -- the
            //    denominator can never be zero, which is what made a limits-only budget
            //    decoration.
            int silent = save(newWorkload(alpha, "undeclared", null));
            assertThat((Integer) Models.get(InstanceModel.class).findById(silent)
                    .get(InstanceModel.CAPACITY_MB))
                .as("step 2: an undeclared workload books the docker kind's 512 MB floor")
                .isEqualTo(512);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 2: alpha now carries both bookings").isEqualTo(1024);

            // 3. A resize charges the DELTA against the same host, never a second booking.
            Row resized = Models.get(InstanceModel.class).findById(id);
            resized.set(InstanceModel.SETTINGS,
                Map.of("image", "fake/image", "memory_limit_mb", 256));
            Models.get(InstanceModel.class).save(resized);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 3: shrinking 512 -> 256 released the delta").isEqualTo(768);

            // 4. An admin REPOINTING the host through the CMS form (the only host change
            //    that reaches this hook) moves the charge in the same write.
            //
            //    AIDEV-NOTE: this step used to claim it covered a MIGRATION. It never did:
            //    a production migration hands the record over with a fenced updateAll,
            //    which fires no write hooks at all, so this shape reported a capability
            //    nothing implemented. The real lane is asserted against the real
            //    InstanceMigrations in InstanceMigrationTest -- never re-title this step.
            Row moved = Models.get(InstanceModel.class).findById(id);
            moved.set(InstanceModel.SERVER_ID, beta);
            Models.get(InstanceModel.class).save(moved);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 4: the host it left got its 256 MB back").isEqualTo(512);
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 4: and the new host carries it now").isEqualTo(256);

            // 5. The SOFT delete -- the only lane InstanceService.destroy takes, and the
            //    one the remove hooks never see -- releases against the host the row was
            //    booked on.
            Row trashed = Models.get(InstanceModel.class).findById(id);
            trashed.set(InstanceModel.DELETED_AT, Instant.now());
            Models.get(InstanceModel.class).save(trashed);
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 5: a soft delete hands the booking back").isEqualTo(0);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 5: and does not touch the host it left").isEqualTo(512);

            // 6. A restore is a NEW claim on the destination's headroom.
            Row restored = Models.get(InstanceModel.class).findById(id);
            restored.set(InstanceModel.DELETED_AT, null);
            Models.get(InstanceModel.class).save(restored);
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 6: the restore booked its 256 MB again").isEqualTo(256);

            // 7. The hard delete releases through the remove-hook pairing.
            Models.get(InstanceModel.class).delete(id);
            this.instances.remove(Integer.valueOf(id));
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 7: the hard delete handed it back too").isEqualTo(0);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 7: the untouched neighbour still carries its own booking")
                .isEqualTo(512);
        });
    }

    /**
     * The gate itself: the last megabyte can be spent exactly once, however many creates
     * race for it, and both refusal identities say what the operator should do.
     */
    @Test
    void racingCreatesCannotOverbookAHostAndUnmeasurableHostsRefuseByName() throws Exception {
        int[] hostId = new int[1];
        Db.run(datasource, () -> hostId[0] = host("race", 1024L, Instant.now()));
        int alpha = hostId[0];

        // 1. SIX threads behind a barrier each write a 1024 MB workload onto a host with
        //    exactly 1024 MB of budget: only one can win. The ledger's guarded statement
        //    is the enforcement -- there is no read-then-write anywhere on this path.
        //
        // AIDEV-NOTE: six racers, not two, and the number is load-bearing. With two, a
        // read-then-write "if it fits, book it" implementation over the SAME arithmetic
        // still passed this journey roughly two runs in three -- the losing window is a
        // few microseconds wide. Six racers all read before any of them writes, so the
        // non-atomic shape overbooks reliably. Never lower it to "keep the test fast".
        int racers = 6;
        CyclicBarrier barrier = new CyclicBarrier(racers);
        AtomicReference<Throwable>[] failures = new AtomicReference[racers];
        for (int i = 0; i < racers; i++) {
            failures[i] = new AtomicReference<>();
        }
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            int slot = i;
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    Db.run(datasource, () -> {
                        Models.get(InstanceModel.class)
                            .save(newWorkload(alpha, "racer" + slot, 1024));
                    });
                } catch (Throwable refused) {
                    failures[slot].set(refused);
                }
            });
            worker.start();
            threads.add(worker);
        }
        for (Thread thread : threads) {
            thread.join();
        }

        Db.run(datasource, () -> {
            List<Row> landed = Models.get(InstanceModel.class).find()
                .where(InstanceModel.NAME.startsWith(PREFIX + "racer")).all();
            for (Row row : landed) {
                this.instances.add(row.get(InstanceModel.ID));
            }
            assertThat(landed)
                .as("step 1: exactly ONE racing 1024 MB create landed").hasSize(1);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 1: the ledger counts exactly the winner's 1024 MB")
                .isEqualTo(1024);
            Throwable loser = null;
            int refusedCount = 0;
            for (AtomicReference<Throwable> failure : failures) {
                if (failure.get() != null) {
                    refusedCount++;
                    loser = failure.get();
                }
            }
            assertThat(refusedCount)
                .as("step 1: and the other five were each refused, none silently dropped")
                .isEqualTo(racers - 1);
            assertThat(keyOf("step 1: the racing loser was REFUSED rather than admitted"
                    + " onto a host with no headroom left", loser))
                .as("step 1: and the refusal is the NAMED host-capacity one, not a 500")
                .isEqualTo("host_capacity_reached");

            // 2. POSITIVE ANCHOR: the refusal is about the LAST megabyte, not about the
            //    host. Freeing the winner lets the same size land immediately.
            int winner = landed.get(0).get(InstanceModel.ID);
            Models.get(InstanceModel.class).delete(winner);
            this.instances.remove(Integer.valueOf(winner));
            int replacement = save(newWorkload(alpha, "after-free", 1024));
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 2: the freed megabytes were spendable again").isEqualTo(1024);
            assertThat((Integer) Models.get(InstanceModel.class).findById(replacement)
                    .get(InstanceModel.CAPACITY_MB))
                .as("step 2: and the replacement carries its own stamp").isEqualTo(1024);

            // 3. A host nothing has measured books WITHOUT being judged, and that is the
            //    scope decision, not an oversight: usage is counted either way, so the
            //    ledger holds honest numbers the moment a preflight lands. Refusing here
            //    would mean a fresh install could not run a single site on its own daemon.
            //    What protects the property that matters is that placement never CHOOSES
            //    such a host -- asserted in InstancePlacementTest, and the budget being
            //    null is the same fact both sides read.
            int blind = host("unmeasured", null, Instant.now());
            assertThat(InstanceCapacity.budgetMbOf(
                    Models.get(ServerModel.class).findById(blind)))
                .as("step 3: an unmeasured host has NO budget, not an unlimited one")
                .isNull();
            int blindWorkload = save(newWorkload(blind, "blind", 128));
            assertThat(InstanceCapacity.bookedMbOn(blind))
                .as("step 3: the explicitly-named host still takes the workload, and the"
                    + " booking is COUNTED against it rather than skipped")
                .isEqualTo(128);
            assertThat((Integer) Models.get(InstanceModel.class).findById(blindWorkload)
                    .get(InstanceModel.CAPACITY_MB))
                .as("step 3: stamped like any other booking").isEqualTo(128);

            // 4. A host whose reading is older than the declared freshness bound is in
            //    exactly the same position: a measurement is evidence with a shelf life,
            //    and capacity is the first gate that reads one back for a decision.
            int stale = host("stale", 4096L, Instant.now().minus(Duration.ofDays(30)));
            assertThat(InstanceCapacity.budgetMbOf(
                    Models.get(ServerModel.class).findById(stale)))
                .as("step 4: a month-old reading yields NO budget")
                .isNull();

            // 5. POSITIVE ANCHOR for the bound, and the proof it is the AGE that decided:
            //    removing it (0 = the operator's explicit choice to trust a reading of any
            //    age) makes the SAME stored reading the budget again, and the gate that
            //    comes with it can fail.
            HohenheimSettings.VALUES.setValue(
                HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS, 0);
            try {
                assertThat(InstanceCapacity.budgetMbOf(
                        Models.get(ServerModel.class).findById(stale)))
                    .as("step 5: with no bound the old reading is the budget again")
                    .isEqualTo(4096L);
                save(newWorkload(stale, "stale-w", 4096));
                assertThat(InstanceCapacity.bookedMbOn(stale))
                    .as("step 5: and a workload that exactly fills it lands").isEqualTo(4096);
                assertThat(keyOf("step 5: while the NEXT megabyte is refused -- an unbounded"
                        + " reading is still a real budget, not an absent one",
                        catchThrowable(() -> Models.get(InstanceModel.class)
                            .save(newWorkload(stale, "stale-over", 1)))))
                    .as("step 5: by name")
                    .isEqualTo("host_capacity_reached");
            } finally {
                HohenheimSettings.VALUES.setValue(
                    HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS, 168);
            }
        });
    }
}
