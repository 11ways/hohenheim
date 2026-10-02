package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M011 against the game-domain mappings production holds: every stored mapping starts its lock version at 0, so an
 * edit reviewed on a pre-migration row is guarded like any other.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class GameDomainVersionMigrationTest {

    @Test
    void everyStoredMappingStartsItsLockVersionAtZero() throws Exception {
        SqlDatasource datasource = TestDatabases.freshDatasource();

        // 1. Two mappings as the previous build stored them, without a version, and one already counting.
        int first = mapping(datasource, 1, null);
        int second = mapping(datasource, 2, null);
        int counting = mapping(datasource, 3, 4);

        // 2. The data step starts the unversioned ones at 0.
        M011_ReviewHardening.startGameDomainVersions(datasource);
        assertThat(version(datasource, first)).as("step 2: a stored mapping starts at 0").isEqualTo(0);
        assertThat(version(datasource, second)).as("step 2: every stored mapping does").isEqualTo(0);

        // 3. A mapping that already counts keeps its version, and a second run changes nothing.
        assertThat(version(datasource, counting)).as("step 3: a counted version is kept").isEqualTo(4);
        M011_ReviewHardening.startGameDomainVersions(datasource);
        assertThat(version(datasource, counting)).as("step 3: a second run keeps it too").isEqualTo(4);
        assertThat(version(datasource, first)).as("step 3: and leaves the started ones at 0").isEqualTo(0);
    }

    private static int mapping(SqlDatasource datasource, int key, Integer storedVersion) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            IntegerField domain = IntegerField.builder().name("site_domain_id").build();
            IntegerField proxy = IntegerField.builder().name("proxy_instance_id").build();
            IntegerField version = IntegerField.builder().name("version").build();
            FrozenModel mappings = new FrozenModel("game_domains", id, domain, proxy, version);
            Row row = mappings.createEmptyRow();
            row.set(domain, 9000 + key);
            row.set(proxy, 9100 + key);
            ids.add(mappings.save(row).get(id));
            // The column default would start the row at 0; the previous build stored none.
            mappings.find().where(id.eq(ids.get(0))).assign(version, storedVersion).updateAll();
        });
        return ids.get(0);
    }

    private static Integer version(SqlDatasource datasource, int mappingId) {
        List<Integer> found = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            IntegerField version = IntegerField.builder().name("version").build();
            Row row = new FrozenModel("game_domains", id, version).find().where(id.eq(mappingId)).first();
            found.add(row != null ? row.get(version) : null);
        });
        return found.get(0);
    }
}
