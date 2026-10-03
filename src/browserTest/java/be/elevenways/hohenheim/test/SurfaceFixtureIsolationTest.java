package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.RuntimeImageModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A surface fixture replaces shared rows and sessions while keeping production seed data and HTTP admission. */
class SurfaceFixtureIsolationTest extends HohenheimTestBase {

    @Test
    void aFreshSurfaceWorldDropsForeignChoicesAndReplaysBootSeeds() throws Exception {
        // 1. Another journey leaves choices a surface fixture did not declare.
        var providers = Models.get(SiteAuthProviderModel.class);
        Row provider = providers.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, "foreign-surface-provider");
        providers.save(provider);
        var hosts = Models.get(ServerModel.class);
        Row host = hosts.createEmptyRow();
        host.set(ServerModel.NAME, "foreign-surface-host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        host.set(ServerModel.MODE, ServerModel.MODE_SSH);
        host.set(ServerModel.SSH_TARGET, "operator@surface.capture.test");
        hosts.save(host);
        String previousSession = sessionToken;

        // 2. The capture gets only its own world, with the real built-in records seeded again.
        freshSeededDatabase();
        assertThat(providers.find().count()).as("step 2: no foreign provider choices survive").isZero();
        assertThat(hosts.find().all()).as("step 2: only the seeded local host survives")
            .hasSize(1).allMatch(row -> row.get(ServerModel.ID).equals(ServerModel.localServerId()));
        var images = Models.get(RuntimeImageModel.class);
        long builtins = images.find().where(RuntimeImageModel.BUILTIN.eq(true)).count();
        assertThat(builtins).as("step 2: process-wide boot does not omit this database's seeds").isPositive();
        assertThat(sessionToken).as("step 2: the authenticated session belongs to this database")
            .isNotEqualTo(previousSession);
        assertThat(adminGet("/admin/dashboard").statusCode())
            .as("step 2: the retained HTTP server admits the fresh session").isEqualTo(200);

        // 3. A second reset preserves the same code-owned catalog without inheriting old session state.
        freshSeededDatabase();
        assertThat(images.find().where(RuntimeImageModel.BUILTIN.eq(true)).count())
            .as("step 3: replayed seeds have stable cardinality").isEqualTo(builtins);
        assertThat(adminGet("/admin/dashboard").statusCode())
            .as("step 3: repeated resets keep HTTP admission working").isEqualTo(200);
    }
}
