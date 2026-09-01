package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.DatabaseContainerKind;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.runtime.InstanceSpec;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A managed database's DEFAULT memory footprint is read for the persistence shape it
 * actually runs in, and off the SAME stored fact that decides the data directory's mode.
 *
 * AIDEV-NOTE: the pairing in step 4 is the whole point of this class. The engine table
 * declares two numbers and {@code specFor} mounts two ways, and the defect this pins was
 * exactly a footprint measured on one shape being booked for the other -- so it asserts
 * the mount and the number TOGETHER for each shape. Asserting them apart would pass
 * happily on a version where the predicate that picks the number and the predicate that
 * picks the mount have drifted into two.
 *
 * No daemon is contacted: every decision here is over a settings map. The control plane
 * IS booted, because {@code specFor} names its container through the controller identity,
 * which is a stored row.
 */
class EngineFootprintShapeTest {

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    private static Map<String, Object> settings(String engine, boolean ephemeral,
                                                String dataVolume) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("engine", engine);
        settings.put("ephemeral", ephemeral);
        settings.put("data_volume", dataVolume);
        return settings;
    }

    @Test
    void aPersistentMongoBooksTheShapeItRunsInRatherThanTheTmpfsMeasurement() {
        DatabaseContainerKind kind = new DatabaseContainerKind();

        // 1. THE MEASUREMENT, and the defect it corrects: mongo's 1280 came from an
        //    EPHEMERAL container whose tmpfs was ~300 MiB of the reading. All seven
        //    persistent mongos on production robbedoes measured 112-513 MiB anon, and
        //    WiredTiger floors its cache at 256 MiB below a 1.5 GiB cap whatever the cap
        //    says -- so the persistent shape books 512 and the ephemeral one still books
        //    the tmpfs it really carries.
        assertThat(ManagedDatabase.Engine.MONGO.footprintMb(true))
            .as("step 1: an ephemeral mongo still books its tmpfs")
            .isEqualTo(1280);
        assertThat(ManagedDatabase.Engine.MONGO.footprintMb(false))
            .as("step 1: a persistent mongo books the measured shape, not the tmpfs one")
            .isEqualTo(512);

        // 2. The three engines nobody has measured persistently keep ONE number for both
        //    shapes: a measured peak can prove a cap too small, never justify lowering one.
        for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
            if (engine == ManagedDatabase.Engine.MONGO) {
                continue;
            }
            assertThat(engine.footprintMb(false))
                .as("step 2 (%s): unmeasured persistently, so it declares one number", engine)
                .isEqualTo(engine.footprintMb(true));
        }

        // 3. The kind reads the shape rather than assuming one, and an UNRECOGNISED engine
        //    still books the largest number declared in either shape -- over-booking costs
        //    budget, under-booking hands a workload a ceiling below its own startup peak.
        assertThat(kind.defaultFootprintMb(settings("mongo", false, "db-data")))
            .as("step 3: a volume-backed mongo defaults to the persistent footprint")
            .isEqualTo(512);
        assertThat(kind.defaultFootprintMb(settings("mongo", true, "db-data")))
            .as("step 3: the ephemeral flag books the tmpfs shape").isEqualTo(1280);
        assertThat(kind.defaultFootprintMb(settings("mongo", false, "")))
            .as("step 3: and a database with NO volume is ephemeral whatever the flag says,"
                + " because there is nothing to mount").isEqualTo(1280);
        assertThat(kind.defaultFootprintMb(settings("nosuchengine", false, "db-data")))
            .as("step 3: an unknown engine books the largest declared footprint")
            .isEqualTo(ManagedDatabase.Engine.maxFootprintMb());

        // 4. FALSIFICATION: the number booked and the directory mounted come from one
        //    predicate. A persistent spec mounts a volume and no tmpfs; an ephemeral one
        //    the reverse -- and each is asserted beside the footprint claimed for it.
        Db.run(datasource, () -> {
            InstanceSpec persistent = kind.specFor(41, settings("mongo", false, "db-data"));
            assertThat(persistent.volumes())
                .as("step 4: the shape that books 512 really mounts its data volume")
                .containsEntry("db-data", "/data/db");
            assertThat(persistent.tmpfs())
                .as("step 4: and carries no tmpfs to charge for").isEmpty();

            InstanceSpec ephemeral = kind.specFor(42, settings("mongo", true, "db-data"));
            assertThat(ephemeral.tmpfs())
                .as("step 4: the shape that books 1280 really mounts a tmpfs")
                .containsKey("/data/db");
            assertThat(ephemeral.volumes())
                .as("step 4: and mounts no volume").isEmpty();
        });
    }
}
