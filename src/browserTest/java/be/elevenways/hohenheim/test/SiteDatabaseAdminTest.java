package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDatabaseModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site-database attachment surface: attach through the CMS form, the site's
 * Databases tab (gated to env-injection-capable types), the link-time reachability
 * refusals, and the database delete guard. No Docker -- records only.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteDatabaseAdminTest extends HohenheimTestBase {

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Integer site(String name, String type) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.SITE_TYPE, type);
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
    }


    /** The named test server's row id, created as an SSH host on first use. */
    private static int serverId(String name) {
        if ("local".equals(name)) {
            return ServerModel.localServerId();
        }
        ServerModel servers = Models.get(ServerModel.class);
        Row row = servers.findByName(name);
        if (row == null) {
            row = servers.createEmptyRow();
            row.set(ServerModel.NAME, name);
            row.set(ServerModel.MODE, ServerModel.MODE_SSH);
            servers.save(row);
        }
        return row.get(ServerModel.ID);
    }

    private static Integer database(String name, String server) {
        DatabaseModel databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, "postgres");
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "linkpass");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        row.set(DatabaseModel.SERVER_ID, serverId(server));
        databases.save(row);
        return row.get(DatabaseModel.ID);
    }

    /** Attach, render the tab, then every refusal (no-workload type, remote, server mismatch, duplicate prefix, duplicate pair, delete guard). */
    @Test
    @Order(1)
    void attachmentJourneyWithEveryRefusal() throws Exception {
        Integer nodeSiteId = site("linkable", "hohenheim:node");
        Integer dockerSiteId = site("dockerized", "hohenheim:docker");
        Integer staticSiteId = site("staticky", "hohenheim:static");
        Integer localDbId = database("linkdb", "local");
        Integer remoteDbId = database("remotedb", "db-host-2");

        var response = postForm("/admin/site-databases/new",
            "site_id=" + nodeSiteId + "&database_id=" + localDbId + "&env_prefix=DB");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        List<Row> links = Models.get(SiteDatabaseModel.class).findBySiteId(nodeSiteId);
        assertThat(links).hasSize(1);
        assertThat((Integer) links.get(0).get(SiteDatabaseModel.DATABASE_ID)).isEqualTo(localDbId);
        assertThat((String) links.get(0).get(SiteDatabaseModel.ENV_PREFIX)).isEqualTo("DB");

        navigateToApp("/admin/sites/" + nodeSiteId + "/page/databases");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("linkdb");
        assertThat(body).contains("DB_HOST");
        assertThat(body).contains("DATABASE_URL");
        assertThat(page.locator("#attach-database-link").count()).isEqualTo(1);

        // visibleFor gates the tab: 404 only for types that run no workload at all.
        // Docker sites are attachable since the isolation wave (container-network
        // injection), so their tab renders.
        var staticTab = get("/admin/sites/" + staticSiteId + "/page/databases");
        assertThat(staticTab.statusCode()).isEqualTo(404);
        var dockerTab = get("/admin/sites/" + dockerSiteId + "/page/databases");
        assertThat(dockerTab.statusCode()).isEqualTo(200);

        // The refusal rerenders the form with the ACTUAL reason (persist-stage
        // Violations reach the browser since the pipeline honors them).
        var noWorkload = postForm("/admin/site-databases/new",
            "site_id=" + staticSiteId + "&database_id=" + localDbId + "&env_prefix=DB");
        assertThat(noWorkload.statusCode()).isEqualTo(200);
        assertThat(noWorkload.body()).contains("cannot receive database credentials");
        assertThat(Models.get(SiteDatabaseModel.class).findBySiteId(staticSiteId)).isEmpty();

        // A Docker site attaches a SAME-SERVER database: accepted and persisted.
        var dockerAttach = postForm("/admin/site-databases/new",
            "site_id=" + dockerSiteId + "&database_id=" + localDbId + "&env_prefix=DB");
        assertThat(dockerAttach.statusCode()).isIn(200, 302, 303);
        assertThat(Models.get(SiteDatabaseModel.class).findBySiteId(dockerSiteId)).hasSize(1);

        // A Docker site + a database on a DIFFERENT server: the cross-host refusal
        // names both servers -- a link network cannot span daemons.
        var mismatch = postForm("/admin/site-databases/new",
            "site_id=" + dockerSiteId + "&database_id=" + remoteDbId + "&env_prefix=FAR");
        assertThat(mismatch.statusCode()).isEqualTo(200);
        assertThat(mismatch.body()).contains("db-host-2").contains("link network");
        assertThat(Models.get(SiteDatabaseModel.class).findBySiteId(dockerSiteId)).hasSize(1);

        // Each refusal carries its specific message, args resolved ({$server}, {$prefix}).
        var remote = postForm("/admin/site-databases/new",
            "site_id=" + nodeSiteId + "&database_id=" + remoteDbId + "&env_prefix=REMOTE");
        assertThat(remote.statusCode()).isEqualTo(200);
        assertThat(remote.body()).contains("db-host-2");

        Integer secondDb = database("seconddb", "local");
        var dupPrefix = postForm("/admin/site-databases/new",
            "site_id=" + nodeSiteId + "&database_id=" + secondDb + "&env_prefix=db");
        assertThat(dupPrefix.statusCode()).isEqualTo(200);
        assertThat(dupPrefix.body()).contains("already used by another database");

        var dupPair = postForm("/admin/site-databases/new",
            "site_id=" + nodeSiteId + "&database_id=" + localDbId + "&env_prefix=OTHER");
        assertThat(dupPair.statusCode()).isEqualTo(200);
        assertThat(dupPair.body()).contains("already attached to this site");

        // None of the three refusals persisted anything.
        assertThat(Models.get(SiteDatabaseModel.class).findBySiteId(nodeSiteId)).hasSize(1);

        var refused = postForm("/admin/databases/" + localDbId + "/delete", "");
        assertThat(refused.statusCode()).isEqualTo(200);   // rerender, not the post-delete redirect
        // The refusal names the dependent WORKLOAD ({$workloads} arg resolved). The
        // message stopped saying "sites" on 2026-08-08: the in-use gate counts attached
        // instances too, so a site-only wording would have been a lie in half the cases.
        assertThat(refused.body()).contains("linkable");
        assertThat(refused.body()).contains("detach it there first");
        assertThat(Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ID.eq(localDbId)).first()).isNotNull();

        // Detaching clears the guard. (The destroy path needs Docker, so the
        // guard's pass-through is covered by the unit-level resolver tests;
        // here we only pin the refusal while a live site depends on it.)
        Models.get(SiteDatabaseModel.class).find()
            .where(SiteDatabaseModel.SITE_ID.eq(nodeSiteId)).delete();
        assertThat(Models.get(SiteDatabaseModel.class).findBySiteId(nodeSiteId)).isEmpty();
    }
}
