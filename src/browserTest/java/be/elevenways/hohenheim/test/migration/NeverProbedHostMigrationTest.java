package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.host.VolumeBackend;
import be.elevenways.hohenheim.migration.M012_UncheckedVolumeStorage;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import be.elevenways.zenit.common.orm.migration.MigrationRunnerResult;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static be.elevenways.hohenheim.test.migration.InstallsAt.withIntegrityMode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An install that already applied M011 (starfleet, daystrom) upgrades through M012 under strict integrity, and only
 * its never-probed hosts move from the {@code none} the old default stored to not checked.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class NeverProbedHostMigrationTest {

    /** The M011 digest starfleet and daystrom record in zenit_migrations, read from both live databases. */
    private static final String LIVE_M011_DIGEST = "69057f3cbfb510cb97a42ac3a0c981877cab1863463aca3f1d2196c45a5261b7";

    @Test
    void anInstallThatAppliedM011GetsTheUncheckedStepOnUpgrade() throws Exception {
        File file = File.createTempFile("hohenheim-never-probed", ".db");
        file.delete();
        file.deleteOnExit();

        // 1. The install the deploy lane serves: Hohenheim's stream applied through 011, with the digest the live
        //    databases record, so the rehearsal of the next jar judges exactly this ledger.
        InstallsAt.migrateThrough(file.toPath(), "011");
        SqliteDatasource datasource = new SqliteDatasource("jdbc:sqlite:" + file.getAbsolutePath());
        assertThat(datasource.rawQuery("SELECT checksum FROM zenit_migrations WHERE stream = 'be.elevenways.hohenheim'"
                + " AND version = '011'").get(0).get("checksum"))
            .as("step 1: the install records M011 under the digest starfleet and daystrom hold")
            .isEqualTo(LIVE_M011_DIGEST);

        // 2. Hosts as that build stored them: one never probed (the column default), one probed to none, one btrfs.
        Instant probed = Now.instant();
        int neverProbed = host(datasource, "never-probed", null, null);
        int probedNone = host(datasource, "probed-none", "none", probed);
        int probedBtrfs = host(datasource, "probed-btrfs", "btrfs", probed);
        assertThat(backendOf(datasource, neverProbed))
            .as("step 2: before the upgrade a never-probed host stores none").isEqualTo("none");

        // 3. The upgrade under integrity=fail applies M012 alone: M011 is unchanged, so nothing is refused.
        withIntegrityMode("fail", () -> {
            MigrationRunnerResult upgrade = new MigrationRunner(datasource).migrate();
            assertThat(upgrade.isSuccess()).as("step 3: the upgrade from 011 under integrity=fail succeeds").isTrue();
            assertThat(upgrade.getAppliedCount()).as("step 3: it applies exactly M012").isEqualTo(1);
        });
        assertThat(datasource.rawQuery("SELECT name FROM zenit_migrations WHERE stream = 'be.elevenways.hohenheim'"
                + " AND version = '012'"))
            .as("step 3: the ledger records M012").hasSize(1);

        // 4. Only the never-probed host reads not checked; a probe's own finding stays as measured.
        assertThat(backendOf(datasource, neverProbed))
            .as("step 4: the never-probed host is not checked").isEqualTo(VolumeBackend.UNCHECKED.token());
        assertThat(backendOf(datasource, probedNone))
            .as("step 4: a host probed to none keeps none").isEqualTo("none");
        assertThat(backendOf(datasource, probedBtrfs))
            .as("step 4: a btrfs host keeps btrfs").isEqualTo("btrfs");

        // 5. A host added afterwards takes the new default, and a second run of the data step changes nothing.
        int added = host(datasource, "added-later", null, null);
        assertThat(backendOf(datasource, added))
            .as("step 5: a new host's storage starts not checked").isEqualTo(VolumeBackend.UNCHECKED.token());
        M012_UncheckedVolumeStorage.uncheckNeverProbedHosts(datasource);
        assertThat(backendOf(datasource, probedNone))
            .as("step 5: a second run leaves the probed none").isEqualTo("none");
        datasource.close();
    }

    private static int host(SqliteDatasource datasource, String name, @Nullable String backend,
                            @Nullable Instant probedAt) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField nameField = StringField.builder().name("name").build();
            StringField backendField = StringField.builder().name("volume_backend").build();
            DateTimeField probedField = DateTimeField.builder().name("volume_probed_at").build();
            FrozenModel servers = backend == null
                ? new FrozenModel("servers", id, nameField)
                : new FrozenModel("servers", id, nameField, backendField, probedField);
            Row row = servers.createEmptyRow();
            row.set(nameField, name);
            if (backend != null) {
                row.set(backendField, backend);
                row.set(probedField, probedAt);
            }
            ids.add(servers.save(row).get(id));
        });
        return ids.get(0);
    }

    private static String backendOf(SqliteDatasource datasource, int hostId) {
        return String.valueOf(datasource.rawQuery("SELECT volume_backend FROM servers WHERE id = " + hostId)
            .get(0).get("volume_backend"));
    }
}
