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
import be.elevenways.zenit.server.http.HostPattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * M011's host wildcard translation against the rows a previous build stored: every spelling of the legacy glob grammar
 * becomes the HostPattern spelling of the same hosts, its claim key with it, and a row it cannot carry exactly (a
 * pattern the grammar refuses, a pair whose route order would change) fails the migration by name before anything is
 * written, so an upgrade never silently drops, widens or reorders a route.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class HostWildcardRespellMigrationTest {

    @Test
    void everyLegacySpellingCarriesItsHostsAndWhatCannotBeCarriedFailsByName() throws Exception {
        HohenheimTestRuntime.ensureBooted();
        assertThat(HostPatternGrammar.LOADED).as("the server half of the seam is installed").isTrue();
        SqlDatasource datasource = TestDatabases.freshDatasource();

        // 1. The rows as the previous build stored them, in every shape its glob grammar admitted: a leading '*.'
        //    (one or more labels), a star run inside a label, an all-star first label that was NOT the special
        //    prefix (exactly one label), an all-star middle label, an exact host, a regex, a released one-or-more
        //    claim; a row stored before hostnames were validated; and two equally specific wildcards that both match
        //    a-bc.pair.respell.test, whose old tie order the new spelling would flip.
        int site = site(datasource, "m011-wildcards");
        int legacy = domain(datasource, site, "*.ok.respell.test", "wildcard");
        int starRun = domain(datasource, site, "a**.run.respell.test", "wildcard");
        int oneLabel = domain(datasource, site, "**.one.respell.test", "wildcard");
        int middle = domain(datasource, site, "a.**.mid.respell.test", "wildcard");
        int exact = domain(datasource, site, "exact.respell.test", "exact");
        int regex = domain(datasource, site, "^(.+)\\.rx\\.respell\\.test$", "regex");
        int released = released(datasource, site, "*.gone.respell.test");
        int unvalidated = domain(datasource, site, "bad_label.*.respell.test", "wildcard");
        int pairFirst = domain(datasource, site, "a**bc.pair.respell.test", "wildcard");
        int pairSecond = domain(datasource, site, "a*-*c.pair.respell.test", "wildcard");

        // 2. The step refuses what it cannot carry exactly, naming table, id, site, pattern and reason: the row the
        //    grammar refuses, and both rows of the pair whose route order would change. Nothing else is listed.
        Throwable refusal = catchThrowable(() -> M011_ReviewHardening.respellHostWildcards(datasource));
        assertThat(refusal).as("step 2: the upgrade fails").isInstanceOf(IllegalStateException.class);
        assertThat(refusal.getMessage())
            .as("step 2: exactly the three rows it cannot carry are listed")
            .contains("3 stored host pattern(s)")
            .contains("site_domains #" + unvalidated + " (site " + site + "): 'bad_label.*.respell.test'")
            .contains("is not a label")
            .contains("site_domains #" + pairFirst + " (site " + site + "): 'a**bc.pair.respell.test'")
            .contains("site_domains #" + pairSecond + " (site " + site + "): 'a*-*c.pair.respell.test'")
            .contains("route order")
            .doesNotContain("ok.respell.test").doesNotContain("run.respell.test").doesNotContain("one.respell.test")
            .doesNotContain("mid.respell.test").doesNotContain("gone.respell.test");

        // 3. And it wrote nothing.
        assertThat(hostname(datasource, legacy)).as("step 3: nothing respelled before the refusal")
            .isEqualTo("*.ok.respell.test");

        // 4. The operator deletes the refused rows; every other legacy spelling becomes the HostPattern spelling of
        //    the very same hosts, its claim key with it; the exact host and the regex are untouched.
        delete(datasource, "site_domains", unvalidated);
        delete(datasource, "site_domains", pairFirst);
        delete(datasource, "site_domains", pairSecond);
        M011_ReviewHardening.respellHostWildcards(datasource);
        assertThat(hostname(datasource, legacy)).as("step 4: one or more labels is '**.'")
            .isEqualTo("**.ok.respell.test");
        assertThat(routeKey(datasource, legacy)).as("step 4: with its claim key")
            .isEqualTo("**.ok.respell.test\n\n");
        assertThat(hostname(datasource, starRun)).as("step 4: a star run inside a label is one star")
            .isEqualTo("a*.run.respell.test");
        assertThat(routeKey(datasource, starRun)).as("step 4: with its claim key")
            .isEqualTo("a*.run.respell.test\n\n");
        assertThat(hostname(datasource, oneLabel)).as("step 4: an all-star first label is exactly one label")
            .isEqualTo("*.one.respell.test");
        assertThat(hostname(datasource, middle)).as("step 4: an all-star middle label is one label")
            .isEqualTo("a.*.mid.respell.test");
        assertThat(hostname(datasource, exact)).as("step 4: the exact host is untouched")
            .isEqualTo("exact.respell.test");
        assertThat(hostname(datasource, regex)).as("step 4: the regex is untouched")
            .isEqualTo("^(.+)\\.rx\\.respell\\.test$");

        // 5. The translations route exactly the hosts the legacy spellings did: nothing dropped, nothing widened.
        HostPattern run = HostPattern.parse(hostname(datasource, starRun));
        assertThat(List.of(run.matches("a.run.respell.test"), run.matches("ab.run.respell.test"),
                run.matches("x.a.run.respell.test")))
            .as("step 5: a**. matched a and ab, one label, as before").containsExactly(true, true, false);
        HostPattern one = HostPattern.parse(hostname(datasource, oneLabel));
        assertThat(List.of(one.matches("a.one.respell.test"), one.matches("a.b.one.respell.test")))
            .as("step 5: legacy **. matched exactly one label, as before").containsExactly(true, false);
        HostPattern many = HostPattern.parse(hostname(datasource, legacy));
        assertThat(List.of(many.matches("a.ok.respell.test"), many.matches("a.b.ok.respell.test"),
                many.matches("ok.respell.test")))
            .as("step 5: legacy *. matched one or more labels and never the apex").containsExactly(true, true, false);
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
