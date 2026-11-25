package be.elevenways.hohenheim.test.quota;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.RootDisk;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.server.quota.QuotaReconciler;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.behaviour.HistoryBehaviour;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.history.HistoryDeclarations;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every quota dimension is declared once in {@link ChargedModel}, installed, reconciled by
 * being declared, booked through the one lifecycle that unwinds a refused write, and stamped
 * out of its model's history.
 *
 * AIDEV-NOTE: the guard against a dimension implemented BESIDE the mechanism -- a class that
 * reserves or releases ledger buckets on its own -- is the quota-ledger-writers local rule in
 * hohenheim's build script, which gates every compile; its exclusions are the declared
 * explicit moves. Its OWN datasource, so every bucket asserted here is this class's alone.
 */
class QuotaDimensionDriftTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
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
    void noChargeStampReachesItsModelsHistory() {
        for (ChargedModel charged : ChargedModel.values()) {
            Model model = charged.model();
            HistoryBehaviour declaration = HistoryDeclarations.of(model);
            assertThat(declaration).as("step 1: %s declares its history", charged).isNotNull();
            Set<String> captured = new HashSet<>();
            for (Field<?, ?> field : declaration.capturedFields(model)) {
                captured.add(field.getName());
            }
            for (ChargedDimension dimension : charged.dimensions()) {
                // 1. The fields a dimension stamps are the ones its own stamp writes, so a new stamp needs no list.
                Row stamped = new Row();
                dimension.stamp(stamped, new ChargedDimension.Charge(dimension.prefix() + "probe", 1, false));
                List<String> stamps = model.requireSchema().getFields().keySet().stream().filter(stamped::has)
                    .toList();
                assertThat(stamps).as("step 1: dimension %s stamps a field of %s", dimension.key(), charged)
                    .isNotEmpty();

                // 2. A stamp is the system's bookkeeping: no change of the record, created or later, captures it.
                assertThat(captured).as("step 2: %s's history captures no stamp of %s", charged, dimension.key())
                    .doesNotContainAnyElementsOf(stamps);
            }
        }
    }

    @Test
    void aRefusalInAnyDimensionUnwindsTheOthersAndTheReconcileKeepsAnOpenWindow() {
        Db.run(datasource, () -> {
            String countBucket = OwnerBudget.INSTANCES.bucketOf("");
            String memoryBucket = OwnerBudget.OWNER_MEMORY.bucketOf("");
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
            HardDeletes.byId(Models.get(InstanceModel.class), (Object) landedId);
        });
    }

    // -- helpers --------------------------------------------------------------

    private static int host(String name) {
        Row row = Models.get(HostModel.class).createEmptyRow();
        row.set(HostModel.NAME, "drift-" + name);
        row.set(HostModel.RUNTIME, HostModel.RUNTIME_DOCKER);
        row.set(HostModel.MODE, HostModel.MODE_LOCAL);
        row.set(HostModel.ADMISSION, HostModel.ADMISSION_ADMITTED);
        row.set(HostModel.POSTURE, HostModel.POSTURE_SHARED_CONTAINER);
        Models.get(HostModel.class).save(row);
        HostFixtures.acknowledgePosture(row);
        HostPreflight.store("drift-" + name, new HostPreflight.Report(
            List.of(new HostPreflight.Check("daemon", HostPreflight.STATUS_PASS, true, "ok")),
            Map.of(HostPreflight.MEM_TOTAL_FACT, 65536L * 1024L * 1024L),
            true, Now.instant(), null));
        return row.get(HostModel.ID);
    }

    private static Row workload(String name, int serverId) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "drift-" + name);
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, Map.of("image", "fake/image"));
        row.set(InstanceModel.HOST_ID, serverId);
        return row;
    }
}
