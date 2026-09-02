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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static Map<String, Object> sharedSettings(String engine) {
        Map<String, Object> settings = settings(engine, false, "dbengine-data");
        settings.put("shared", true);
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

    /**
     * A SHARED engine container books ONE number for the many logical databases it hosts,
     * and the engines that cannot share are unaffected by the flag.
     *
     * AIDEV-NOTE: the redis arm is the falsification. {@code shared} is a settings
     * boolean, and a container kind that read it without asking whether the engine can
     * share would hand a redis engine a footprint no redis engine has -- so this asserts
     * that the flag alone changes nothing for it, and that the engine itself refuses to
     * name a shared footprint at all.
     */
    @Test
    void aSharedEngineBooksOneFootprintForAllItsLogicalDatabases() {
        DatabaseContainerKind kind = new DatabaseContainerKind();

        // 1. Every sharing engine declares the same chosen 1024: unmeasured for a
        //    multi-database load, and the docblock beside it says so.
        for (ManagedDatabase.Engine engine : ManagedDatabase.Engine.values()) {
            if (!engine.supportsLogicalDatabases()) {
                continue;
            }
            assertThat(engine.sharedFootprintMb())
                .as("step 1 (%s): the declared shared footprint", engine)
                .isEqualTo(1024);
            assertThat(kind.defaultFootprintMb(sharedSettings(engine.token())))
                .as("step 1 (%s): and the kind books it off the settings flag", engine)
                .isEqualTo(1024);
        }

        // 2. Without the flag the SAME settings book one database's own footprint, so the
        //    flag is what moves the number and nothing else is.
        assertThat(kind.defaultFootprintMb(settings("mongo", false, "dbengine-data")))
            .as("step 2: an unflagged mongo container books the dedicated persistent shape")
            .isEqualTo(ManagedDatabase.Engine.MONGO.footprintMb(false));
        assertThat(kind.defaultFootprintMb(settings("postgres", false, "dbengine-data")))
            .as("step 2: and so does postgres")
            .isEqualTo(ManagedDatabase.Engine.POSTGRES.footprintMb(false));

        // 3. Redis cannot host logical databases, so the flag buys it nothing: it keeps
        //    its own dedicated footprint rather than a shared number it has no shape for.
        assertThat(kind.defaultFootprintMb(sharedSettings("redis")))
            .as("step 3: a redis container ignores the shared flag")
            .isEqualTo(ManagedDatabase.Engine.REDIS.footprintMb(false));
        assertThatThrownBy(ManagedDatabase.Engine.REDIS::sharedFootprintMb)
            .as("step 3: and the engine refuses to name a shared footprint at all")
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("no shared shape");

        // 4. The shared container is volume-backed like any persistent one: the number
        //    booked and the directory mounted still come from the one predicate.
        Db.run(datasource, () -> {
            InstanceSpec shared = kind.specFor(43, sharedSettings("mongo"));
            assertThat(shared.volumes())
                .as("step 4: the shared engine mounts its own data volume")
                .containsEntry("dbengine-data", "/data/db");
            assertThat(shared.tmpfs())
                .as("step 4: and carries no tmpfs to charge for").isEmpty();
        });
    }
}
