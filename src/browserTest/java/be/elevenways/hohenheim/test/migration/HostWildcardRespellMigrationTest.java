package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M011_ReviewHardening;
import be.elevenways.hohenheim.server.proxy.HostPatternGrammar;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * M011's host wildcard respelling against the rows a previous build stored: a leading {@code *.} becomes
 * {@code **.} with its claim key, and a pattern the HostPattern grammar refuses fails the migration naming every
 * such row before anything is written, so an upgrade never silently takes a site offline.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class HostWildcardRespellMigrationTest {

    @Test
    void aRefusedPatternFailsTheUpgradeByNameAndACorrectedInstallRespells() throws Exception {
        HohenheimTestRuntime.ensureBooted();
        assertThat(HostPatternGrammar.LOADED).as("the server half of the seam is installed").isTrue();
        SqlDatasource datasource = TestDatabases.freshDatasource();

        // 1. The rows as the previous build stored them: a one-or-more wildcard, a wildcard with '**' in the
        //    middle (one label there, which HostPattern refuses), an exact host, a regex the step leaves alone, and
        //    a released wildcard claim of the same refused shape.
        int site = site(datasource, "m011-wildcards");
        int legacy = domain(datasource, site, "*.ok.respell.test", "wildcard");
        int midRun = domain(datasource, site, "a.**.bad.respell.test", "wildcard");
        int exact = domain(datasource, site, "exact.respell.test", "exact");
        int regex = domain(datasource, site, "^(.+)\\.rx\\.respell\\.test$", "regex");
        int released = released(datasource, site, "x.**.gone.respell.test");

        // 2. The step refuses, naming every offending row with its table, id, site, pattern and the grammar's reason.
        Throwable refusal = catchThrowable(() -> M011_ReviewHardening.respellHostWildcards(datasource));
        assertThat(refusal).as("step 2: the upgrade fails").isInstanceOf(IllegalStateException.class);
        assertThat(refusal.getMessage())
            .as("step 2: both refused rows are listed")
            .contains("2 stored host pattern(s)")
            .contains("site_domains #" + midRun + " (site " + site + "): 'a.**.bad.respell.test'")
            .contains("released_route_claims #" + released + " (site " + site + "): 'x.**.gone.respell.test'")
            .contains("is only ever the first label")
            .doesNotContain("ok.respell.test").doesNotContain("exact.respell.test");

        // 3. And it wrote nothing: the valid wildcard still has its old spelling and key.
        assertThat(hostname(datasource, legacy)).as("step 3: nothing respelled before the refusal")
            .isEqualTo("*.ok.respell.test");

        // 4. The operator deletes the refused rows; the step now respells the wildcard and its key and leaves the
        //    exact host and the regex alone.
        delete(datasource, "site_domains", midRun);
        delete(datasource, "released_route_claims", released);
        M011_ReviewHardening.respellHostWildcards(datasource);
        assertThat(hostname(datasource, legacy)).as("step 4: the leading wildcard is respelled")
            .isEqualTo("**.ok.respell.test");
        assertThat(routeKey(datasource, legacy)).as("step 4: with its claim key")
            .isEqualTo("**.ok.respell.test\n\n");
        assertThat(hostname(datasource, exact)).as("step 4: the exact host is untouched")
            .isEqualTo("exact.respell.test");
        assertThat(hostname(datasource, regex)).as("step 4: the regex is untouched")
            .isEqualTo("^(.+)\\.rx\\.respell\\.test$");

        // 5. Running it again changes nothing.
        M011_ReviewHardening.respellHostWildcards(datasource);
        assertThat(hostname(datasource, legacy)).as("step 5: idempotent").isEqualTo("**.ok.respell.test");
    }

    private static int site(SqlDatasource datasource, String slug) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = integer("id");
            StringField name = text("name");
            StringField slugField = text("slug");
            StringField upstreamKind = text("upstream_kind");
            FrozenModel sites = new FrozenModel("sites", id, name, slugField, upstreamKind);
            Row row = sites.createEmptyRow();
            row.set(name, slug);
            row.set(slugField, slug);
            row.set(upstreamKind, "hohenheim:redirect");
            ids.add(sites.save(row).get(id));
        });
        return ids.get(0);
    }

    private static int domain(SqlDatasource datasource, int siteId, String hostname, String matchType) {
        return insert(datasource, "site_domains", "site_id", "live_route_key", siteId, hostname, matchType);
    }

    private static int released(SqlDatasource datasource, int siteId, String hostname) {
        return insert(datasource, "released_route_claims", "former_site_id", "claim_key", siteId, hostname,
            "wildcard");
    }

    /** One stored pattern row with the claim key the old code stamped: hostname, no path, no listeners. */
    private static int insert(SqlDatasource datasource, String table, String siteColumn, String keyColumn,
                              int siteId, String hostname, String matchType) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = integer("id");
            IntegerField site = integer(siteColumn);
            StringField host = text("hostname");
            StringField match = text("match_type");
            StringField key = text(keyColumn);
            FrozenModel rows = new FrozenModel(table, id, site, host, match, key);
            Row row = rows.createEmptyRow();
            row.set(site, siteId);
            row.set(host, hostname);
            row.set(match, matchType);
            row.set(key, hostname + "\n\n");
            ids.add(rows.save(row).get(id));
        });
        return ids.get(0);
    }

    private static void delete(SqlDatasource datasource, String table, int rowId) {
        Db.run(datasource, () -> {
            IntegerField id = integer("id");
            new FrozenModel(table, id).find().where(id.eq(rowId)).delete();
        });
    }

    private static String hostname(SqlDatasource datasource, int rowId) {
        return domainColumn(datasource, rowId, "hostname");
    }

    private static String routeKey(SqlDatasource datasource, int rowId) {
        return domainColumn(datasource, rowId, "live_route_key");
    }

    private static String domainColumn(SqlDatasource datasource, int rowId, String column) {
        List<String> values = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = integer("id");
            StringField value = text(column);
            values.add(new FrozenModel("site_domains", id, value).find().where(id.eq(rowId)).first().get(value));
        });
        return values.get(0);
    }

    /** A field belongs to one schema, so every frozen model gets fresh ones. */
    private static IntegerField integer(String name) {
        return IntegerField.builder().name(name).build();
    }

    private static StringField text(String name) {
        return StringField.builder().name(name).build();
    }
}
