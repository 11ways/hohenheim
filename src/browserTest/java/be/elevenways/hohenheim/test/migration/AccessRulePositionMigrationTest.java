package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M011 against the access-rule positions production holds: every sibling run (one per list and parent) is renumbered
 * dense from 0 in its stored order, so core's TreeBehaviour takes the tree over as the readers saw it.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class AccessRulePositionMigrationTest {

    @Test
    void everySiblingRunBecomesDenseInItsStoredOrder() throws Exception {
        SqlDatasource datasource = TestDatabases.freshDatasource();

        // 1. Two lists as the previous build stored them: a root run whose rules were added in one batch (all 0), a
        //    group's children with gaps and a tie, and a second list already dense.
        int list = list(datasource);
        int other = list(datasource);
        int first = rule(datasource, list, null, "ip_allow", 0);
        int second = rule(datasource, list, null, "ip_deny", 0);
        int group = rule(datasource, list, null, "group", 0);
        int late = rule(datasource, list, group, "ip_allow", 7);
        int early = rule(datasource, list, group, "ip_deny", 3);
        int tied = rule(datasource, list, group, "ip_allow", 3);
        int dense = rule(datasource, other, null, "ip_allow", 0);
        int denseNext = rule(datasource, other, null, "ip_deny", 1);

        // 2. The data step numbers each run 0..n-1 by stored position, then key.
        M011_ReviewHardening.densifyRulePositions(datasource);
        assertThat(List.of(sort(datasource, first), sort(datasource, second), sort(datasource, group)))
            .as("step 2: a batch-added root run takes its key order").containsExactly(0, 1, 2);
        assertThat(List.of(sort(datasource, early), sort(datasource, tied), sort(datasource, late)))
            .as("step 2: a gapped run keeps its order, a tie broken by key").containsExactly(0, 1, 2);

        // 3. A dense run is left as stored, and a second run of the step changes nothing.
        assertThat(List.of(sort(datasource, dense), sort(datasource, denseNext)))
            .as("step 3: another list's dense run is untouched").containsExactly(0, 1);
        M011_ReviewHardening.densifyRulePositions(datasource);
        assertThat(List.of(sort(datasource, early), sort(datasource, tied), sort(datasource, late)))
            .as("step 3: a second run keeps every position").containsExactly(0, 1, 2);
    }

    private static int list(SqlDatasource datasource) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField name = StringField.builder().name("name").build();
            FrozenModel lists = new FrozenModel("access_lists", id, name);
            Row row = lists.createEmptyRow();
            row.set(name, "position-migration");
            ids.add(lists.save(row).get(id));
        });
        return ids.get(0);
    }

    private static int rule(SqlDatasource datasource, int listId, Integer parentId, String type, int storedSort) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            IntegerField list = IntegerField.builder().name("access_list_id").build();
            IntegerField parent = IntegerField.builder().name("parent_id").build();
            StringField kind = StringField.builder().name("type").build();
            IntegerField sort = IntegerField.builder().name("sort").build();
            FrozenModel rules = new FrozenModel("access_rules", id, list, parent, kind, sort);
            Row row = rules.createEmptyRow();
            row.set(list, listId);
            row.set(parent, parentId);
            row.set(kind, type);
            row.set(sort, storedSort);
            ids.add(rules.save(row).get(id));
        });
        return ids.get(0);
    }

    private static Integer sort(SqlDatasource datasource, int ruleId) {
        List<Integer> found = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            IntegerField sort = IntegerField.builder().name("sort").build();
            Row row = new FrozenModel("access_rules", id, sort).find().where(id.eq(ruleId)).first();
            found.add(row != null ? row.get(sort) : null);
        });
        return found.get(0);
    }
}
