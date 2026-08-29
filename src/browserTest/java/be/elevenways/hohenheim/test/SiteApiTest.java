package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.proxy.RouteClaims;
import be.elevenways.zenit.auth.CapabilityScopes;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

import static be.elevenways.hohenheim.test.ApiSupport.codeOf;
import static be.elevenways.hohenheim.test.ApiSupport.form;
import static be.elevenways.hohenheim.test.ApiSupport.idOf;
import static be.elevenways.hohenheim.test.ApiSupport.user;
/**
 * The site/domain write lane of the PaaS API is the admin form's own pipeline reached
 * without a browser: every site kind a migration needs lands with its route claimed, a
 * stranger key is refused by name, the tenancy refusals are the panel's neutral sentence,
 * a removed hostname releases its claim to its owner, and the doors are exactly the
 * panels' (site create/delete admin-only, domain rows for whoever manages the site).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteApiTest extends HohenheimTestBase {

    private static final String PREFIX = "site-api-";
    private static final String ZONE = "site-api.test";

    private static Integer tenantId;
    private static Integer tenantSiteId;
    private static Integer catchAllSiteId;
    private static String keyAdmin;
    private static String keyTenant;
    private static String keyNarrow;

    /** Filled by the create journey, consumed by the delete journey. */
    private static Integer proxySiteId;
    private static Integer staticSiteId;
    private static Integer redirectSiteId;

    @BeforeAll
    static void seed() {
        tenantId = user("site-api-tenant@surface.test", "Site Api Tenant");
        tenantSiteId = site(PREFIX + "tenant", true);
        RecordGrants.grant(GrantSubjectType.USER, tenantId, SiteModel.MODEL_ID, tenantSiteId,
            HohenheimAccess.MANAGE, true);
        // The operator's catch-all: a live wildcard row nobody has a grant on, so every
        // free name under the zone is the operator's namespace.
        catchAllSiteId = site(PREFIX + "catch-all", true);
        domain(catchAllSiteId, "*." + ZONE, SiteDomainModel.MATCH_WILDCARD);

        int adminId = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first().get(UserModel.ID);
        keyAdmin = ApiKeyService.create(adminId, PREFIX + "admin", List.of("hohenheim.*"), null)
            .plaintext();
        keyTenant = ApiKeyService.create(tenantId, PREFIX + "tenant",
            List.of(CapabilityScopes.format(SiteModel.MODEL_ID, HohenheimAccess.MANAGE)), null)
            .plaintext();
        // The admin's OWN key narrowed to an unrelated vocabulary: no admin permission
        // survives the narrowing, so the create door must be shut for it.
        keyNarrow = ApiKeyService.create(adminId, PREFIX + "narrow", List.of("shortlink.*"), null)
            .plaintext();
    }

    @AfterAll
    static void cleanUp() {
        SiteModel sites = Models.get(SiteModel.class);
        for (Row site : sites.find().where(SiteModel.NAME.startsWith(PREFIX)).all()) {
            // A hard delete cascades the domain rows (SiteModel's remove hook).
            sites.delete(site.get(SiteModel.ID));
        }
    }

    // -- fixtures --------------------------------------------------------------

    private static int site(String name, boolean enabled) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, name);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.ENABLED, enabled);
        row.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        row.set(SiteModel.SETTINGS, new LinkedHashMap<>(Map.of("root_path", "/tmp/" + name)));
        Models.get(SiteModel.class).save(row);
        return row.get(SiteModel.ID);
    }

    private static int domain(int siteId, String hostname, String matchType) {
        Row row = Models.get(SiteDomainModel.class).createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, matchType);
        row.set(SiteDomainModel.FORCE_SSL, false);
        Models.get(SiteDomainModel.class).save(row);
        return row.get(SiteDomainModel.ID);
    }

    /** Whether the JSON carries {@code "key": value}, whatever the serializer's spacing. */
    private static boolean has(String json, String key, String jsonValue) {
        return Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*" + Pattern.quote(jsonValue))
            .matcher(json).find();
    }

    private static List<Row> domainsOf(int siteId) {
        return Models.get(SiteDomainModel.class).findBySiteId(siteId);
    }

    // -- the journeys ----------------------------------------------------------

    /** Every migration-relevant site kind lands through the form pipeline with its route claimed. */
    @Test
    @Order(1)
    void everySiteKindLandsWithItsRouteClaimed() throws Exception {
        // 1. An address upstream whose domain rewrites the Host header (the Apache
        //    fallthrough shape): the site row, then two hostnames on it.
        HttpResponse<String> proxy = keyPost(keyAdmin, "/api/v1/sites", form(
            "name", PREFIX + "proxy", "upstream_kind", "hohenheim:address", "enabled", "true",
            "settings.forward_scheme", "http", "settings.forward_host", "127.0.0.1",
            "settings.forward_port", "8080", "settings.rewrite_location", "false"));
        assertThat(proxy.statusCode()).as("step 1: the proxy site is created: " + proxy.body())
            .isEqualTo(200);
        proxySiteId = idOf(proxy.body());
        Row proxySite = Models.get(SiteModel.class).findById(proxySiteId);
        assertThat((Object) proxySite.get(SiteModel.SLUG))
            .as("step 1: the slug is derived exactly as SiteResource.persistRow derives it")
            .isEqualTo(PREFIX + "proxy");
        assertThat(String.valueOf(proxySite.get(SiteModel.SETTINGS)))
            .as("step 1: the settings were coerced against the address kind's schema")
            .contains("forward_port=8080").contains("rewrite_location=false");

        HttpResponse<String> earl = keyPost(keyAdmin, "/api/v1/sites/" + proxySiteId + "/domains",
            form("hostname", "Earl." + ZONE + ".", "custom_headers.0.key", "Host",
                "custom_headers.0.value", "earl.phoenix"));
        assertThat(earl.statusCode()).as("step 1: the Host-rewriting domain lands: " + earl.body())
            .isEqualTo(200);
        assertThat(has(earl.body(), "hostname", "\"earl." + ZONE + "\""))
            .as("step 1: the hostname is stored canonical (lowercased, root dot stripped): " + earl.body())
            .isTrue();
        assertThat(has(earl.body(), "Host", "\"earl.phoenix\""))
            .as("step 1: the Host rewrite rides custom_headers: " + earl.body()).isTrue();
        assertThat(has(earl.body(), "live", "true"))
            .as("step 1: the row holds its route claim: " + earl.body()).isTrue();
        HttpResponse<String> www = keyPost(keyAdmin, "/api/v1/sites/" + proxySiteId + "/domains",
            form("hostname", "www.earl." + ZONE, "force_ssl", "false"));
        assertThat(www.statusCode()).as("step 1: a second hostname lands: " + www.body())
            .isEqualTo(200);
        assertThat(domainsOf(proxySiteId))
            .extracting(row -> (String) row.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 1: both rows hold a live route claim, the dispatcher's own truth")
            .doesNotContainNull().hasSize(2);
        assertThat(RouteClaims.isLive(proxySite)).as("step 1: the site routes").isTrue();

        // 2. The detail read answers with the rows the write lane made.
        HttpResponse<String> detail = keyGet(keyAdmin, "/api/v1/sites/" + proxySiteId);
        assertThat(detail.body()).as("step 2: the detail projection carries the domains")
            .contains("\"domains\"").contains("www.earl." + ZONE);

        // 3. A static site with an SPA fallback, and a redirect with a status and path
        //    preservation: the two other shapes an old installation is full of.
        HttpResponse<String> statik = keyPost(keyAdmin, "/api/v1/sites", form(
            "name", PREFIX + "static", "upstream_kind", "hohenheim:static", "enabled", "true",
            "settings.root_path", "/tmp/site-api-static",
            "settings.fallback_file", "index.html"));
        assertThat(statik.statusCode()).as("step 3: the static site is created: " + statik.body())
            .isEqualTo(200);
        staticSiteId = idOf(statik.body());
        HttpResponse<String> redirect = keyPost(keyAdmin, "/api/v1/sites", form(
            "name", PREFIX + "redirect", "upstream_kind", "hohenheim:redirect", "enabled", "true",
            "settings.target_url", "https://www.earl." + ZONE,
            "settings.http_status", "301", "settings.preserve_path", "true"));
        assertThat(redirect.statusCode())
            .as("step 3: the redirect site is created: " + redirect.body()).isEqualTo(200);
        redirectSiteId = idOf(redirect.body());
        assertThat(String.valueOf(Models.get(SiteModel.class).findById(redirectSiteId)
                .get(SiteModel.SETTINGS)))
            .as("step 3: the redirect settings were coerced (status enum, boolean)")
            .contains("http_status=301").contains("preserve_path=true");
        HttpResponse<String> apex = keyPost(keyAdmin, "/api/v1/sites/" + redirectSiteId + "/domains",
            form("hostname", "earl-redirect." + ZONE));
        assertThat(apex.statusCode()).as("step 3: the redirect hostname lands: " + apex.body())
            .isEqualTo(200);

        // 4. A key the form does not declare is refused by name, and no site is created;
        //    a misspelled SETTING is refused the same way by the schema-resolved scope.
        HttpResponse<String> stranger = keyPost(keyAdmin, "/api/v1/sites", form(
            "name", PREFIX + "stranger", "upstream_kind", "hohenheim:static",
            "settings.root_path", "/tmp/x", "colour", "red"));
        assertThat(stranger.statusCode()).as("step 4: a stranger key is a typed refusal")
            .isEqualTo(422);
        assertThat(codeOf(stranger.body())).as("step 4: named as an unknown field")
            .isEqualTo("zenit.coercion.unknown_field");
        HttpResponse<String> misspelled = keyPost(keyAdmin, "/api/v1/sites", form(
            "name", PREFIX + "stranger", "upstream_kind", "hohenheim:static",
            "settings.root_paht", "/tmp/x"));
        assertThat(misspelled.statusCode()).as("step 4: a misspelled setting is refused too")
            .isEqualTo(422);
        assertThat(codeOf(misspelled.body())).isEqualTo("zenit.coercion.unknown_field");
        assertThat(Models.get(SiteModel.class).find()
                .where(SiteModel.NAME.eq(PREFIX + "stranger")).first())
            .as("step 4: neither refused create wrote a row").isNull();
    }

    /** The tenancy refusals are the panel's: neutral for a tenant, detailed for an admin. */
    @Test
    @Order(2)
    void aForeignWildcardRefusesATenantWithTheNeutralSentence() throws Exception {
        // 1. A tenant adding a free name under the operator's catch-all gets the one
        //    neutral sentence the /manage form gives -- no site, no pattern named.
        HttpResponse<String> tenant = keyPost(keyTenant, "/api/v1/sites/" + tenantSiteId + "/domains",
            form("hostname", "zzz." + ZONE));
        assertThat(tenant.statusCode()).as("step 1: refused as a typed violation").isEqualTo(422);
        assertThat(codeOf(tenant.body())).as("step 1: the neutral sentence")
            .isEqualTo("hostname_unavailable");
        assertThat(tenant.body()).as("step 1: the holder is not named")
            .doesNotContain(PREFIX + "catch-all").doesNotContain("*." + ZONE);

        // 2. The SAME claim by an admin on the tenant's site is refused with the detailed
        //    overlap sentence: the route was never free, the reader may just know why.
        HttpResponse<String> admin = keyPost(keyAdmin, "/api/v1/sites/" + tenantSiteId + "/domains",
            form("hostname", "zzz." + ZONE));
        assertThat(admin.statusCode()).isEqualTo(422);
        assertThat(codeOf(admin.body())).as("step 2: the detailed sentence for an admin")
            .isEqualTo("route_overlaps_other_site");

        // 3. A tenant may still set a name of its own, and only the delegated columns:
        //    a listener restriction is frozen by TenantWrites on this lane exactly as on
        //    the form, and the URL's site wins over a body naming another one.
        HttpResponse<String> own = keyPost(keyTenant, "/api/v1/sites/" + tenantSiteId + "/domains",
            form("hostname", "own.tenant-" + ZONE, "listen_on", "127.0.0.1"));
        assertThat(own.statusCode()).as("step 3: the frozen column is refused").isEqualTo(422);
        HttpResponse<String> mismatch = keyPost(keyTenant, "/api/v1/sites/" + tenantSiteId + "/domains",
            form("hostname", "own.tenant-" + ZONE, "site_id", String.valueOf(catchAllSiteId)));
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(codeOf(mismatch.body())).as("step 3: a contradicting site_id is refused, never preferred")
            .isEqualTo("domain_site_mismatch");
        HttpResponse<String> landed = keyPost(keyTenant, "/api/v1/sites/" + tenantSiteId + "/domains",
            form("hostname", "own.tenant-" + ZONE));
        assertThat(landed.statusCode()).as("step 3: the tenant's own name lands: " + landed.body())
            .isEqualTo(200);
    }

    /** The doors are the panels': site create/delete admin-only, domains for managers. */
    @Test
    @Order(3)
    void theDoorsAreExactlyThePanelsDoors() throws Exception {
        // 1. A tenant key and a scope-narrowed admin key cannot create a site at all.
        assertThat(keyPost(keyTenant, "/api/v1/sites", form("name", PREFIX + "nope",
            "upstream_kind", "hohenheim:static", "settings.root_path", "/tmp/x")).statusCode())
            .as("step 1: a tenant cannot create a site (the /manage panel has no create)")
            .isEqualTo(403);
        assertThat(keyPost(keyNarrow, "/api/v1/sites", form("name", PREFIX + "nope",
            "upstream_kind", "hohenheim:static", "settings.root_path", "/tmp/x")).statusCode())
            .as("step 1: a key narrowed away from the admin permission is refused")
            .isEqualTo(403);
        assertThat(keyPost(keyTenant, "/api/v1/sites/" + proxySiteId + "/delete", "").statusCode())
            .as("step 1: nor delete one").isEqualTo(403);
        assertThat(Models.get(SiteModel.class).find()
                .where(SiteModel.NAME.eq(PREFIX + "nope")).first())
            .as("step 1: nothing was created").isNull();

        // 2. A site the tenant does not manage is a uniform 404 on the domain lane, on
        //    the read and on the write alike.
        assertThat(keyGet(keyTenant, "/api/v1/sites/" + proxySiteId + "/domains").statusCode())
            .as("step 2: the foreign site's domains are not enumerable").isEqualTo(404);
        assertThat(keyPost(keyTenant, "/api/v1/sites/" + proxySiteId + "/domains",
            form("hostname", "hijack." + ZONE)).statusCode())
            .as("step 2: nor writable").isEqualTo(404);
        int foreignDomainId = domainsOf(proxySiteId).get(0).get(SiteDomainModel.ID);
        assertThat(keyPost(keyTenant, "/api/v1/sites/" + tenantSiteId + "/domains/"
            + foreignDomainId + "/delete", "").statusCode())
            .as("step 2: another site's domain row answers like a missing one").isEqualTo(404);
        assertThat(domainsOf(proxySiteId)).as("step 2: the foreign rows are untouched").hasSize(2);
    }

    /** Removing a hostname releases its claim to its owner; deleting a site trashes it. */
    @Test
    @Order(4)
    void aRemovedHostnameReleasesItsClaimAndADeletedSiteIsTrashed() throws Exception {
        // 1. The list names the rows; remove the www one.
        HttpResponse<String> list = keyGet(keyAdmin, "/api/v1/sites/" + proxySiteId + "/domains");
        assertThat(list.statusCode()).isEqualTo(200);
        Row www = domainsOf(proxySiteId).stream()
            .filter(row -> ("www.earl." + ZONE).equals(row.get(SiteDomainModel.HOSTNAME)))
            .findFirst().orElseThrow();
        int wwwId = www.get(SiteDomainModel.ID);
        HttpResponse<String> removed = keyPost(keyAdmin,
            "/api/v1/sites/" + proxySiteId + "/domains/" + wwwId + "/delete", "");
        assertThat(removed.statusCode()).as("step 1: the row is removed: " + removed.body())
            .isEqualTo(200);
        assertThat(Models.get(SiteDomainModel.class).findById(wwwId))
            .as("step 1: the row is gone").isNull();

        // 2. The released claim is the owner's to take back: the SAME hostname on the
        //    same site lands again (the quarantine only bars a different owner).
        HttpResponse<String> again = keyPost(keyAdmin, "/api/v1/sites/" + proxySiteId + "/domains",
            form("hostname", "www.earl." + ZONE));
        assertThat(again.statusCode()).as("step 2: the owner re-claims its released name: " + again.body())
            .isEqualTo(200);
        assertThat(has(again.body(), "live", "true")).as("step 2: and it routes again").isTrue();

        // 3. Deleting a site is the admin form's soft delete: trashed, invisible, its
        //    rows kept for a restore.
        HttpResponse<String> deleted = keyPost(keyAdmin, "/api/v1/sites/" + redirectSiteId + "/delete", "");
        assertThat(deleted.statusCode()).as("step 3: the site is deleted: " + deleted.body())
            .isEqualTo(200);
        assertThat((Object) Models.get(SiteModel.class).findById(redirectSiteId).get(SiteModel.DELETED_AT))
            .as("step 3: soft-deleted, exactly like the form").isNotNull();
        assertThat(keyGet(keyAdmin, "/api/v1/sites/" + redirectSiteId).statusCode())
            .as("step 3: a trashed site reads as absent").isEqualTo(404);
        assertThat(keyPost(keyAdmin, "/api/v1/sites/" + redirectSiteId + "/delete", "").statusCode())
            .as("step 3: and cannot be deleted twice").isEqualTo(404);
        assertThat(domainsOf(redirectSiteId)).as("step 3: its rows stay for a restore").hasSize(1);
        assertThat(keyGet(keyAdmin, "/api/v1/sites/" + staticSiteId).statusCode())
            .as("step 3: the static site is untouched").isEqualTo(200);
    }
}
