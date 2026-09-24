package be.elevenways.hohenheim.test.quota;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceQuota;
import be.elevenways.hohenheim.server.instance.RootDisk;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.server.quota.QuotaReconciler;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every quota dimension is declared once in {@link ChargedModel}, installed, reconciled by
 * being declared, and booked through the one lifecycle that unwinds a refused write.
 *
 * AIDEV-NOTE: the ledger-writer scan is the guard against a dimension implemented BESIDE the
 * mechanism: a class that reserves or releases ledger buckets on its own is either a new
 * dimension (declare it in ChargedModel) or a deliberate explicit move (add it to
 * {@link #LEDGER_WRITERS} with its reason). Its OWN datasource, so every bucket asserted
 * here is this class's alone.
 */
class QuotaDimensionDriftTest {

    /** The files allowed to move ledger buckets outside a declared dimension, and why. */
    private static final Map<String, String> LEDGER_WRITERS = Map.of(
        "ChargedModel.java", "THE lifecycle every declared dimension is booked through",
        "OwnerQuota.java", "the owner budgets' guarded reserve",
        "QuotaReconciler.java", "the drift correction",
        "InstanceCapacity.java", "the host budget's reserve and the explicit migration-window moves",
        "InstanceQuota.java", "moveOwnerCharges, the uncapped heal when ownership moves");

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void everyDimensionIsDeclaredOnceInstalledAndReconciled() {
        // 1. Every declared model installed its hooks at boot.
        for (ChargedModel model : ChargedModel.values()) {
            assertThat(model.isInstalled()).as("step 1: %s is installed at boot", model).isTrue();
        }

        // 2. Every dimension is declared once, under a unique key.
        Set<String> keys = new HashSet<>();
        Set<ChargedDimension> seen = new HashSet<>();
        for (ChargedModel model : ChargedModel.values()) {
            for (ChargedDimension dimension : model.dimensions()) {
                assertThat(seen.add(dimension))
                    .as("step 2: dimension %s is declared on one model only", dimension.key()).isTrue();
                assertThat(keys.add(dimension.key()))
                    .as("step 2: dimension key %s is unique", dimension.key()).isTrue();
            }
        }

        // 3. Every owner budget is charged by a declared dimension, and no two distinct
        //    prefixes overlap (a bucket belongs to one budget).
        Set<String> prefixes = ChargedModel.prefixes();
        for (OwnerBudget budget : OwnerBudget.values()) {
            assertThat(prefixes).as("step 3: budget %s is charged by a declared dimension", budget)
                .contains(budget.prefix());
        }
        for (String prefix : prefixes) {
            for (String other : prefixes) {
                if (!prefix.equals(other)) {
                    assertThat(other.startsWith(prefix))
                        .as("step 3: prefix %s does not swallow %s", prefix, other).isFalse();
                }
            }
        }

        // 4. Every declared prefix is reconciled: a leak in a bucket no row names is named
        //    and corrected to zero, for each dimension, with no per-dimension code anywhere.
        Db.run(datasource, () -> {
            Map<String, String> probes = new LinkedHashMap<>();
            for (String prefix : prefixes) {
                String bucket = prefix + "drift-probe";
                probes.put(prefix, bucket);
                Quotas.reserve(bucket, 3, Long.MAX_VALUE);
            }
            QuotaReconciler.Result result = QuotaReconciler.reconcile();
            assertThat(result.abstained()).as("step 4: nothing moved under the scan").isFalse();
            List<String> corrected = new ArrayList<>();
            for (QuotaReconciler.Correction correction : result.corrections()) {
                corrected.add(correction.bucket());
            }
            for (Map.Entry<String, String> probe : probes.entrySet()) {
                assertThat(corrected).as("step 4: the leak under %s is named", probe.getKey())
                    .contains(probe.getValue());
                assertThat(Quotas.usedOf(probe.getValue()))
                    .as("step 4: and corrected to zero").isZero();
            }
        });
    }

    @Test
    void noLedgerBucketMovesOutsideTheMechanism() throws IOException {
        Path server = Path.of("src/server/java/be/elevenways/hohenheim");
        Path common = Path.of("src/common/java/be/elevenways/hohenheim");
        assertThat(Files.isDirectory(server) && Files.isDirectory(common))
            .as("the scan needs the hohenheim project dir as its working directory").isTrue();
        List<String> writers = new ArrayList<>();
        for (Path root : List.of(server, common)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    if (source.contains("Quotas.reserve(") || source.contains("Quotas.release(")) {
                        writers.add(file.getFileName().toString());
                    }
                }
            }
        }
        assertThat(LEDGER_WRITERS.keySet())
            .as("every ledger writer is the mechanism or a declared explicit move -- a quota"
                + " dimension is declared in ChargedModel, never hand-written beside it")
            .containsAll(writers);
    }

    @Test
    void aRefusalInAnyDimensionUnwindsTheOthersAndTheReconcileKeepsAnOpenWindow() {
        Db.run(datasource, () -> {
            String countBucket = InstanceQuota.bucketKeyOf("");
            String memoryBucket = InstanceQuota.memoryBucketOf("");
            int alpha = host("alpha");
            long slots = Quotas.usedOf(countBucket);
            long memory = Quotas.usedOf(memoryBucket);

            // 1. The ROOT disk refuses after the owner's slot, the owner's memory and the
            //    host's memory were booked by the same write: all three are handed back.
            Row refused = workload("unwind", alpha);
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("image", "fake/image");
            settings.put(RootDisk.SETTING, 20);
            refused.set(InstanceModel.SETTINGS, settings);
            Throwable thrown = catchThrowable(() -> Models.get(InstanceModel.class).save(refused));
            assertThat(thrown).as("step 1: a docker kind cannot carry a root disk")
                .isInstanceOf(Violations.class);
            assertThat(((Violations) thrown).all().get(0).message().key())
                .as("step 1: refused by the root-disk dimension, by name")
                .isEqualTo("root_disk_unsupported");
            assertThat(Quotas.usedOf(countBucket))
                .as("step 1: the owner's slot came back").isEqualTo(slots);
            assertThat(Quotas.usedOf(memoryBucket))
                .as("step 1: and the owner's memory").isEqualTo(memory);
            assertThat(InstanceCapacity.bookedMbOn(alpha))
                .as("step 1: and the host's memory").isZero();

            // 2. An open migration window books the destination while the row still names
            //    its source; the reconcile counts that booking instead of releasing it.
            Row landed = workload("windowed", alpha);
            Models.get(InstanceModel.class).save(landed);
            int landedId = landed.get(InstanceModel.ID);
            int beta = host("beta");
            long window = InstanceCapacity.bookedMbOf(landed);
            InstanceCapacity.reserve(beta, window);
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(landedId))
                .assign(InstanceModel.MIGRATE_TARGET_ID, beta)
                .assign(InstanceModel.MIGRATE_RESERVED_MB, (int) window)
                .updateAll();
            QuotaReconciler.Result result = QuotaReconciler.reconcile();
            List<String> corrected = new ArrayList<>();
            for (QuotaReconciler.Correction correction : result.corrections()) {
                corrected.add(correction.bucket());
            }
            assertThat(corrected).as("step 2: the destination's window booking is not drift")
                .doesNotContain(InstanceCapacity.bucketOf(beta));
            assertThat(InstanceCapacity.bookedMbOn(beta))
                .as("step 2: and it is still booked").isEqualTo(window);

            // 3. Cleanup through the real release paths.
            Models.get(InstanceModel.class).find()
                .where(InstanceModel.ID.eq(landedId))
                .assign(InstanceModel.MIGRATE_TARGET_ID, (Object) null)
                .assign(InstanceModel.MIGRATE_RESERVED_MB, (Object) null)
                .updateAll();
            InstanceCapacity.release(beta, window);
            Models.get(InstanceModel.class).delete((Object) landedId);
        });
    }

    // -- helpers --------------------------------------------------------------

    private static int host(String name) {
        Row row = Models.get(ServerModel.class).createEmptyRow();
        row.set(ServerModel.NAME, "drift-" + name);
        row.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        row.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        row.set(ServerModel.ADMISSION, ServerModel.ADMISSION_ADMITTED);
        row.set(ServerModel.POSTURE, ServerModel.POSTURE_SHARED_CONTAINER);
        Models.get(ServerModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store("drift-" + name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 65536L * 1024L * 1024L),
            true, Now.instant(), null));
        return row.get(ServerModel.ID);
    }

    private static Row workload(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "drift-" + name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.SERVER_ID, serverId);
        return row;
    }
}
