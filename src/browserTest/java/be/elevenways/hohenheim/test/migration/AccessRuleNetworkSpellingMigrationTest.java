package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M011 against the network spellings the lax parser accepted: each is rewritten to the address the previous
 * build read, so no allow or deny leaf changes what it matches when zenit's parser turns strict.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AccessRuleNetworkSpellingMigrationTest {

    private static final IntegerField ID = IntegerField.builder().name("id").build();
    private static final IntegerField ACCESS_LIST_ID = IntegerField.builder().name("access_list_id").build();
    private static final StringField TYPE = StringField.builder().name("type").build();
    private static final SchemaField DATA = SchemaField.builder("data").build();
    private static final StringField SEARCH_TEXT = StringField.builder().name("search_text").build();

    /** One frozen view: a field belongs to exactly one schema. */
    private static final FrozenModel RULES =
        new FrozenModel("access_rules", ID, ACCESS_LIST_ID, TYPE, DATA, SEARCH_TEXT);

    @Test
    void everyLaxSpellingBecomesTheAddressTheOldParserRead() throws Exception {
        SqlDatasource datasource = TestDatabases.freshDatasource();
        int list = accessList(datasource);

        // 1. Rules as the previous build stored them: a leading-zero octet (read as DECIMAL), a
        //    zone id, a fullwidth IPv6 digit, a mapped CIDR with a leading zero, dotted quads
        //    embedded before the end of an IPv6 literal (production 91191333's parser read them
        //    as groups), a canonical rule, a value that never parsed, and a non-network rule
        //    carrying the same data key.
        int leadingZero = rule(datasource, list, "ip_deny", "010.000.0.1");
        int zoned = rule(datasource, list, "ip_allow", "fe80::1%eth0");
        int fullwidth = rule(datasource, list, "ip_deny", "2001:db8::１");
        int mappedCidr = rule(datasource, list, "ip_deny", "::ffff:010.0.0.0/104");
        int quadFirst = rule(datasource, list, "ip_deny", "1.2.3.4::1");
        int quadInTail = rule(datasource, list, "ip_allow", "::1.2.3.4:5");
        int quadCidr = rule(datasource, list, "ip_deny", "1.2.3.4::/32");
        int quadUncompressed = rule(datasource, list, "ip_deny", "1:2:3.4.5.6:7:8:9:a");
        int canonical = rule(datasource, list, "ip_allow", "192.0.2.0/24");
        int garbage = rule(datasource, list, "ip_deny", "not an address");
        int group = rule(datasource, list, "group", "010.0.0.1");
        List<Integer> lax = List.of(leadingZero, zoned, fullwidth, mappedCidr, quadFirst, quadInTail,
            quadCidr, quadUncompressed);
        for (int id : lax) {
            assertThat(AccessRuleModel.parseNetwork(network(datasource, id)))
                .as("step 1: rule %s no longer parses under the strict parser", id).isNull();
        }

        // 2. The data step rewrites each to the canonical spelling of the address it meant.
        M011_ReviewHardening.canonicalizeNetworks(datasource);
        assertThat(network(datasource, leadingZero)).as("step 2: decimal, as read before").isEqualTo("10.0.0.1");
        assertThat(network(datasource, zoned)).as("step 2: the zone dropped, as before").isEqualTo("fe80::1");
        assertThat(network(datasource, fullwidth)).as("step 2: the digit it read").isEqualTo("2001:db8::1");
        assertThat(network(datasource, mappedCidr)).as("step 2: the folded range").isEqualTo("10.0.0.0/8");
        assertThat(network(datasource, quadFirst)).as("step 2: a leading quad read as two groups")
            .isEqualTo("102:304::1");
        assertThat(network(datasource, quadInTail)).as("step 2: a quad inside the tail read as two groups")
            .isEqualTo("::102:304:5");
        assertThat(network(datasource, quadCidr)).as("step 2: the prefix kept over 128 bits")
            .isEqualTo("102:304::/32");
        assertThat(network(datasource, quadUncompressed)).as("step 2: a quad mid-address, uncompressed")
            .isEqualTo("1:2:304:506:7:8:9:a");
        for (int id : lax) {
            assertThat(AccessRuleModel.parseNetwork(network(datasource, id)))
                .as("step 2: rule %s parses again", id).isNotNull();
        }
        assertThat(searchText(datasource, leadingZero)).as("step 2: the search text follows")
            .contains("10.0.0.1").doesNotContain("010.000.0.1");

        // 3. Nothing else moves: the canonical rule, the value that never parsed, a group's data.
        assertThat(network(datasource, canonical)).as("step 3: canonical kept").isEqualTo("192.0.2.0/24");
        assertThat(network(datasource, garbage)).as("step 3: never readable, kept").isEqualTo("not an address");
        assertThat(network(datasource, group)).as("step 3: not a network rule").isEqualTo("010.0.0.1");

        // 4. A second run is a no-op, and the migration says it cannot be undone.
        M011_ReviewHardening.canonicalizeNetworks(datasource);
        assertThat(network(datasource, leadingZero)).as("step 4: stable").isEqualTo("10.0.0.1");
        assertThat(new M011_ReviewHardening().getIrreversibleReason())
            .as("step 4: the original spelling is not kept").isNotBlank();
    }

    private static int accessList(SqlDatasource datasource) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField name = StringField.builder().name("name").build();
            FrozenModel lists = new FrozenModel("access_lists", id, name);
            Row row = lists.createEmptyRow();
            row.set(name, "m017");
            ids.add(lists.save(row).get(id));
        });
        return ids.get(0);
    }

    /** Store a rule through a frozen view: the live model would refuse the legacy spelling. */
    private static int rule(SqlDatasource datasource, int list, String type, String network) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            FrozenModel rules = rules();
            Row row = rules.createEmptyRow();
            row.set(ACCESS_LIST_ID, list);
            row.set(TYPE, type);
            row.set(DATA, Map.of("network", network));
            row.set(SEARCH_TEXT, type + " " + network);
            ids.add(rules.save(row).get(ID));
        });
        return ids.get(0);
    }

    private static String network(SqlDatasource datasource, int id) {
        Object data = read(datasource, id).get(DATA);
        return data instanceof Map<?, ?> map ? String.valueOf(map.get("network")) : null;
    }

    private static String searchText(SqlDatasource datasource, int id) {
        return read(datasource, id).get(SEARCH_TEXT);
    }

    private static Row read(SqlDatasource datasource, int id) {
        List<Row> found = new ArrayList<>();
        Db.run(datasource, () -> found.add(rules().find().noCache().where(ID.eq(id)).first()));
        return found.get(0);
    }

    private static FrozenModel rules() {
        return RULES;
    }
}
