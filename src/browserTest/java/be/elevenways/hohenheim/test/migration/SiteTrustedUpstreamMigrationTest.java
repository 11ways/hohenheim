package be.elevenways.hohenheim.test.migration;

import be.elevenways.hohenheim.migration.M012_SiteTrustedUpstream;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.field.BooleanField;
import be.elevenways.zenit.common.orm.field.DateTimeField;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.migration.FrozenModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M012 against the rows production holds: exactly the tenant-owned sites whose stored upstream
 * dials a judged target get {@code trusted_upstream}, because an operator wrote every such
 * setting (tenants could not author one before the tenant upstream gates).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
class SiteTrustedUpstreamMigrationTest {

    @Test
    void everyTenantOwnedDialingSiteIsTrustedAndNothingElse() throws Exception {
        SqlDatasource datasource = TestDatabases.freshDatasource();

        // 1. The sites as the previous build stored them: tenant-owned address, passthrough and
        //    static sites; a tenant-owned redirect (dials nothing); an operator-owned address
        //    site; one whose only grant EXPIRED; and one whose grant is not manage.
        int address = site(datasource, "m012-address", "hohenheim:address");
        int passthrough = site(datasource, "m012-passthrough", "hohenheim:tls_passthrough");
        int staticSite = site(datasource, "m012-static", "hohenheim:static");
        int redirect = site(datasource, "m012-redirect", "hohenheim:redirect");
        int operator = site(datasource, "m012-operator", "hohenheim:address");
        int expired = site(datasource, "m012-expired", "hohenheim:address");
        int viewOnly = site(datasource, "m012-view", "hohenheim:address");
        Instant now = Now.instant();
        grant(datasource, address, "manage", true, null);
        grant(datasource, passthrough, "manage", true, now.plus(Duration.ofDays(30)));
        grant(datasource, staticSite, "manage", true, null);
        grant(datasource, redirect, "manage", true, null);
        grant(datasource, expired, "manage", true, now.minus(Duration.ofDays(1)));
        grant(datasource, viewOnly, "view", true, null);

        // 2. The data step trusts the three tenant-owned dialing sites.
        M012_SiteTrustedUpstream.trustExistingTenantUpstreams(datasource);
        assertThat(trusted(datasource, address)).as("step 2: tenant address site trusted").isTrue();
        assertThat(trusted(datasource, passthrough)).as("step 2: tenant passthrough site trusted").isTrue();
        assertThat(trusted(datasource, staticSite)).as("step 2: tenant static site trusted").isTrue();

        // 3. And nothing else: a kind that dials nothing, an operator site, a dead grant and a
        //    grant that is not ownership all keep the default.
        assertThat(trusted(datasource, redirect)).as("step 3: a redirect dials nothing").isNotEqualTo(Boolean.TRUE);
        assertThat(trusted(datasource, operator)).as("step 3: an operator site needs no trust")
            .isNotEqualTo(Boolean.TRUE);
        assertThat(trusted(datasource, expired)).as("step 3: an expired grant owns nothing")
            .isNotEqualTo(Boolean.TRUE);
        assertThat(trusted(datasource, viewOnly)).as("step 3: a view grant is not ownership")
            .isNotEqualTo(Boolean.TRUE);

        // 4. Running it again changes nothing.
        M012_SiteTrustedUpstream.trustExistingTenantUpstreams(datasource);
        assertThat(trusted(datasource, address)).as("step 4: a second run keeps the flag").isTrue();
        assertThat(trusted(datasource, operator)).as("step 4: and adds none").isNotEqualTo(Boolean.TRUE);
    }

    private static int site(SqlDatasource datasource, String slug, String kind) {
        List<Integer> ids = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            StringField name = StringField.builder().name("name").build();
            StringField slugField = StringField.builder().name("slug").build();
            StringField upstreamKind = StringField.builder().name("upstream_kind").build();
            FrozenModel sites = new FrozenModel("sites", id, name, slugField, upstreamKind);
            Row row = sites.createEmptyRow();
            row.set(name, slug);
            row.set(slugField, slug);
            row.set(upstreamKind, kind);
            ids.add(sites.save(row).get(id));
        });
        return ids.get(0);
    }

    private static void grant(SqlDatasource datasource, int siteId, String capability, boolean value,
                              Instant expiresAt) {
        Db.run(datasource, () -> {
            StringField id = StringField.builder().name("id").build();
            StringField subjectType = StringField.builder().name("subject_type").build();
            IntegerField subjectId = IntegerField.builder().name("subject_id").build();
            StringField model = StringField.builder().name("model").build();
            StringField recordId = StringField.builder().name("record_id").build();
            StringField capabilityField = StringField.builder().name("capability").build();
            BooleanField valueField = BooleanField.builder("value").build();
            DateTimeField expires = DateTimeField.builder().name("expires_at").build();
            FrozenModel grants = new FrozenModel("auth_record_grants", id, subjectType, subjectId, model,
                recordId, capabilityField, valueField, expires);
            Row row = grants.createEmptyRow();
            row.set(id, "m012-" + siteId + "-" + capability);
            row.set(subjectType, "user");
            row.set(subjectId, 4242);
            row.set(model, "hohenheim:site");
            row.set(recordId, String.valueOf(siteId));
            row.set(capabilityField, capability);
            row.set(valueField, value);
            row.set(expires, expiresAt);
            grants.insert(row);
        });
    }

    private static Boolean trusted(SqlDatasource datasource, int siteId) {
        List<Boolean> found = new ArrayList<>();
        Db.run(datasource, () -> {
            IntegerField id = IntegerField.builder().name("id").build();
            BooleanField trusted = BooleanField.builder("trusted_upstream").build();
            Row row = new FrozenModel("sites", id, trusted).find().where(id.eq(siteId)).first();
            found.add(row != null ? row.get(trusted) : null);
        });
        return found.get(0);
    }
}
