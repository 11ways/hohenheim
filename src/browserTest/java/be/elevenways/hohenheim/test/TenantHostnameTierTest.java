package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.HostnameAuthority;
import be.elevenways.hohenheim.server.auth.TenantWrites;
import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A delegated tenant answers for the hostnames it holds and for NOTHING ELSE -- decided on
 * the tier a row actually routes in, not on the tier its {@code match_type} column claims.
 *
 * AIDEV-NOTE: this covers the bypass that shipped past a ledger, a migration and a test:
 * {@code TenantWrites} refused {@code match_type=wildcard} by reading the COLUMN, while
 * SiteDispatcher, HostnamePatterns and HostnameAuthority all derive the tier from the
 * hostname's SHAPE. A tenant submitting {@code hostname=*.victim.test} with
 * {@code match_type=exact} therefore stored a live wildcard route AND became the only
 * covering row for every name under the victim's domain -- which
 * {@code TenantWrites.requireRecordAuthority} reads as authority to write DNS inside the
 * victim's zone. The old test asserted the stored COLUMN came back {@code exact} and agreed
 * with the control it was supposed to check; that is why the assertions here are about the
 * ROW EXISTING and about {@link HostnameAuthority#canManage}, never about the column.
 */
@TestMethodOrder(OrderAnnotation.class)
class TenantHostnameTierTest extends HohenheimTestBase {

    /** The name the tenant tries to swallow, and a sibling under it that proves the reach. */
    private static final String VICTIM_APEX = "victim.tiertest.test";
    private static final String VICTIM_SIBLING = "billing." + VICTIM_APEX;

    private static Integer ownSiteId;
    private static Integer victimSiteId;
    private static Integer ownDomainId;
    private static String tenantSession;
    private static String tenantCsrf;
    private static UserPrincipal tenantPrincipal;
    private static UserPrincipal adminPrincipal;

    @BeforeAll
    static void seed() {
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);

        Row own = site(siteModel, "Tier Tenant", "tier-tenant");
        ownSiteId = own.get(SiteModel.ID);
        Row victim = site(siteModel, "Tier Victim", "tier-victim");
        victimSiteId = victim.get(SiteModel.ID);

        ownDomainId = domain(domainModel, ownSiteId, "owned.tiertest.test").get(SiteDomainModel.ID);
        // The victim serves the apex only. The sibling is a name it has NOT bound yet, which
        // is exactly the name a swallowing wildcard would hand to the tenant.
        domain(domainModel, victimSiteId, VICTIM_APEX);

        Row tenant = AuthModels.users().createEmptyRow();
        tenant.set(UserModel.EMAIL, "tenant-tier@hohenheim.local");
        tenant.set(UserModel.DISPLAY_NAME, "Tier Tenant");
        tenant.set(UserModel.ENABLED, true);
        tenant.set(UserModel.CREATED_AT, Instant.now());
        tenant.set(UserModel.UPDATED_AT, Instant.now());
        AuthModels.users().save(tenant);
        Integer tenantId = tenant.get(UserModel.ID);
        tenantPrincipal = new UserPrincipal(tenantId, "Tier Tenant");

        Row admin = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        adminPrincipal = new UserPrincipal(admin.get(UserModel.ID), "Test Admin");

        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, tenantId.longValue());
        tenantCsrf = ZenitAuth.randomToken();
        session.set(CsrfTokens.TOKEN, tenantCsrf);
        Zenit.getSessionStore().save(session);
        tenantSession = session.token().secret();

        RecordGrants.grant("user", tenantId, SiteModel.MODEL_ID, ownSiteId,
            HohenheimAccess.MANAGE, true);
    }

    private static Row site(Model model, String name, String slug) {
        Row row = model.createEmptyRow();
        row.set(SiteModel.NAME, name);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.SITE_TYPE, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        model.save(row);
        return row;
    }

    private static Row domain(Model model, Integer siteId, String hostname) {
        Row row = model.createEmptyRow();
        row.set(SiteDomainModel.SITE_ID, siteId);
        row.set(SiteDomainModel.HOSTNAME, hostname);
        row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        model.save(row);
        return row;
    }

    // --- Helpers ----------------------------------------------------------------------

    private HttpResponse<String> tenantPost(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + tenantSession)
            .header("X-Csrf-Token", tenantCsrf)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Row domainByHostname(String hostname) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(hostname)).first();
    }

    /** Whether the tenant answers for a name, asked exactly as a DNS write asks it. */
    private static boolean tenantAnswersFor(String hostname) {
        boolean[] answer = new boolean[1];
        TenantConduits.as(tenantPrincipal, () -> {
            AccessContext ctx = TenantWrites.acting();
            answer[0] = ctx != null && HostnameAuthority.canManage(ctx, hostname);
        });
        return answer[0];
    }

    /** The single refusal a tenant write produced, or null when the write went through. */
    private static Violation refusalOf(Runnable body) {
        Violations violations = catchThrowableOfType(
            () -> TenantConduits.as(tenantPrincipal, body), Violations.class);
        return violations != null ? violations.all().get(0) : null;
    }

    // --- The journeys -----------------------------------------------------------------

    /**
     * The takeover attempt, driven as the tenant: a glob-shaped hostname parked under an
     * {@code exact} column. The assertions are about the ROW and about the AUTHORITY it
     * would have conferred, because both of those are what the column-reading refusal let
     * through while looking green.
     */
    @Test
    @Order(1)
    void aGlobShapedHostnameIsRefusedWhateverTheMatchTypeColumnSays() throws Exception {
        // 1. Nobody answers for the victim's unbound sibling to begin with -- the authority
        //    walk fails closed on a name no live row covers.
        assertThat(tenantAnswersFor(VICTIM_SIBLING))
            .as("step 1: an unbound sibling has no owner yet").isFalse();

        // 2. The attack: a real tenant POST, past the rendered form, carrying the wildcard
        //    in the HOSTNAME and hiding it behind the delegable match type.
        tenantPost("/manage/domains/new", "site_id=" + ownSiteId
            + "&hostname=" + java.net.URLEncoder.encode("*." + VICTIM_APEX, "UTF-8")
            + "&match_type=" + SiteDomainModel.MATCH_EXACT);
        assertThat(domainByHostname("*." + VICTIM_APEX))
            .as("step 2: a hostname that ROUTES as a wildcard is not a delegable claim, "
                + "whatever the column says")
            .isNull();

        // 3. The consequence that made it a takeover rather than a config mistake: the
        //    glob row would have been the only row covering every name under the victim's
        //    domain, and TenantWrites.requireRecordAuthority reads exactly this as
        //    permission to write DNS there.
        assertThat(tenantAnswersFor(VICTIM_SIBLING))
            .as("step 3: the tenant answers for no name under the victim's domain").isFalse();
        assertThat(tenantAnswersFor(VICTIM_APEX))
            .as("step 3: nor for the victim's own apex").isFalse();

        // 4. The in-label glob is the same tier and is refused on the same grounds -- the
        //    rule is about the routing tier, not about a "*." prefix.
        tenantPost("/manage/domains/new", "site_id=" + ownSiteId
            + "&hostname=" + java.net.URLEncoder.encode("?." + VICTIM_APEX, "UTF-8")
            + "&match_type=" + SiteDomainModel.MATCH_EXACT);
        assertThat(domainByHostname("?." + VICTIM_APEX))
            .as("step 4: '?' routes in the wildcard tier too").isNull();

        // 5. A direct model.save, which reaches the datasource through no resource method,
        //    no form spec and no endpoint -- and names the refusal it enforces.
        Violation refusal = refusalOf(() -> {
            Model model = Models.get(SiteDomainModel.class);
            Row row = model.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, ownSiteId);
            row.set(SiteDomainModel.HOSTNAME, "*.direct." + VICTIM_APEX);
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            model.save(row);
        });
        assertThat(refusal).as("step 5: the write pipeline refuses it too").isNotNull();
        assertThat(refusal.message().key())
            .as("step 5: refused as an undelegable match type, on the EFFECTIVE tier")
            .isEqualTo("tenant_match_type_exact");
        assertThat(domainByHostname("*.direct." + VICTIM_APEX))
            .as("step 5: and left no row").isNull();

        // 6. POSITIVE ANCHOR: an ordinary exact hostname still saves for the tenant, and
        //    claims a live route -- the refusal is not refusing everything.
        assertThat(tenantPost("/manage/domains/new",
            "site_id=" + ownSiteId + "&hostname=ordinary.tiertest.test").statusCode())
            .isIn(200, 302, 303);
        Row ordinary = domainByHostname("ordinary.tiertest.test");
        assertThat(ordinary).as("step 6: a tenant can still bind an ordinary hostname").isNotNull();
        assertThat((String) ordinary.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 6: and it holds a live route claim").isNotNull();
        assertThat(tenantAnswersFor("ordinary.tiertest.test"))
            .as("step 6: and the tenant answers for it").isTrue();

        // 7. POSITIVE ANCHOR: an OPERATOR still authors a wildcard row. The tier rule is a
        //    delegation boundary, not a ban on wildcards.
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> {
            Model model = Models.get(SiteDomainModel.class);
            Row row = model.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, victimSiteId);
            row.set(SiteDomainModel.HOSTNAME, "*.operator.tiertest.test");
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_WILDCARD);
            model.save(row);
        })).as("step 7: an operator wildcard is untouched").doesNotThrowAnyException();
        Row operatorGlob = domainByHostname("*.operator.tiertest.test");
        assertThat(operatorGlob).as("step 7: and it stored").isNotNull();

        // 8. The stored COLUMN is normalized to the tier the row routes in, so no consumer
        //    that reads match_type raw (certificate ordering, the deployments query, the
        //    TLS-passthrough route build) can be told a different tier than the dispatcher
        //    uses. An operator glob submitted under an EXACT column is stored as wildcard.
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> {
            Model model = Models.get(SiteDomainModel.class);
            Row row = model.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, victimSiteId);
            row.set(SiteDomainModel.HOSTNAME, "*.drifted.tiertest.test");
            row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            model.save(row);
        })).as("step 8: an operator glob is legitimate whatever column it arrives under")
            .doesNotThrowAnyException();
        assertThat((String) domainByHostname("*.drifted.tiertest.test")
            .get(SiteDomainModel.MATCH_TYPE))
            .as("step 8: the stored column is the tier it routes in, never the one submitted")
            .isEqualTo(SiteDomainModel.MATCH_WILDCARD);
    }

    /**
     * A hostname that is not a hostname is refused at the MODEL, for everyone: the syntax
     * gate is a data-integrity rule, not a delegation rule, and it is what keeps a route
     * tuple's newline separator honest.
     */
    @Test
    @Order(2)
    void amalformedHostnameIsRefusedForOperatorsToo() {
        Model model = Models.get(SiteDomainModel.class);
        for (String malformed : List.of("bad_underscore.tiertest.test", "double..dot.test",
                "-leading.tiertest.test", "trailing-.tiertest.test",
                "spaced name.tiertest.test", "line\nbreak.tiertest.test")) {
            Violations refused = catchThrowableOfType(
                () -> TenantConduits.as(adminPrincipal, () -> {
                    Row row = model.createEmptyRow();
                    row.set(SiteDomainModel.SITE_ID, victimSiteId);
                    row.set(SiteDomainModel.HOSTNAME, malformed);
                    row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
                    model.save(row);
                }), Violations.class);
            assertThat((Throwable) refused).as("'%s' is not a hostname", malformed).isNotNull();
            assertThat(refused.all().get(0).message().key())
                .as("'%s' is refused as bad syntax, not as something else", malformed)
                .isEqualTo("hostname_invalid");
            assertThat(domainByHostname(malformed))
                .as("'%s' left no row", malformed).isNull();
        }

        // POSITIVE ANCHOR: the shapes that are legitimately stored still are -- a punycode
        // label, a single-label internal route, and an operator glob.
        for (String valid : List.of("xn--bcher-kva.tiertest.test", "internalhost",
                "a*.tiertest.test")) {
            assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> {
                Row row = model.createEmptyRow();
                row.set(SiteDomainModel.SITE_ID, victimSiteId);
                row.set(SiteDomainModel.HOSTNAME, valid);
                row.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
                model.save(row);
            })).as("'%s' is a legitimate stored spelling", valid).doesNotThrowAnyException();
        }
    }

    /**
     * The four delegation freezes that had no test at all, each asserted by the refusal it
     * NAMES and by the state it left -- a 403 from CSRF and a 403 from authorization are
     * indistinguishable, and a refusal that still wrote the row passes a status-only test.
     */
    @Test
    @Order(3)
    void everyDelegationFreezeNamesItsOwnRefusalAndWritesNothing() {
        Model model = Models.get(SiteDomainModel.class);

        // 1. tenant_site_not_managed: the site a CREATE binds to must be one the tenant
        //    manages. The resource AccessFunction scopes READS and answers nothing here.
        Violation foreignSite = refusalOf(() -> {
            Row row = model.createEmptyRow();
            row.set(SiteDomainModel.SITE_ID, victimSiteId);
            row.set(SiteDomainModel.HOSTNAME, "grab.tiertest.test");
            model.save(row);
        });
        assertThat(foreignSite).as("step 1: binding to an unmanaged site is refused").isNotNull();
        assertThat(foreignSite.message().key()).isEqualTo("tenant_site_not_managed");
        assertThat(foreignSite.fieldName()).isEqualTo(SiteDomainModel.SITE_ID.getName());
        assertThat(domainByHostname("grab.tiertest.test"))
            .as("step 1: and wrote nothing").isNull();

        // 2. tenant_listen_on_frozen: a listener restriction makes the row DISJOINT from
        //    every other row's listener set, which the route-overlap scan exempts by design.
        Violation listener = refusalOf(() -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.LISTEN_ON, "127.0.0.1");
            model.save(row);
        });
        assertThat(listener).as("step 2: a tenant listener restriction is refused").isNotNull();
        assertThat(listener.message().key()).isEqualTo("tenant_listen_on_frozen");
        assertThat(listener.fieldName()).isEqualTo(SiteDomainModel.LISTEN_ON.getName());
        assertThat((String) model.findById(ownDomainId).get(SiteDomainModel.LISTEN_ON))
            .as("step 2: the stored row is untouched").isNull();

        // 3. tenant_path_frozen: the same exemption one dimension over.
        Violation path = refusalOf(() -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.PATH, "/carve");
            model.save(row);
        });
        assertThat(path).as("step 3: a tenant path carve-out is refused").isNotNull();
        assertThat(path.message().key()).isEqualTo("tenant_path_frozen");
        assertThat(path.fieldName()).isEqualTo(SiteDomainModel.PATH.getName());
        assertThat((String) model.findById(ownDomainId).get(SiteDomainModel.PATH))
            .as("step 3: the stored row is untouched").isNull();

        // 4. tenant_field_frozen: the WHITELIST catch-all, which is what makes a column
        //    added later tenant-frozen by default instead of silently delegable.
        Violation frozen = refusalOf(() -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.STRIP_PATH, true);
            model.save(row);
        });
        assertThat(frozen).as("step 4: a non-delegated column is refused").isNotNull();
        assertThat(frozen.message().key()).isEqualTo("tenant_field_frozen");
        assertThat(frozen.fieldName()).isEqualTo(SiteDomainModel.STRIP_PATH.getName());
        assertThat((Boolean) model.findById(ownDomainId).get(SiteDomainModel.STRIP_PATH))
            .as("step 4: the stored row is untouched").isFalse();

        // 5. POSITIVE ANCHOR: the SAME four writes as an operator all go through, so every
        //    refusal above was about WHO was writing and not about the values.
        assertThatCode(() -> TenantConduits.as(adminPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.LISTEN_ON, "127.0.0.1");
            row.set(SiteDomainModel.PATH, "/carve");
            row.set(SiteDomainModel.STRIP_PATH, true);
            model.save(row);
        })).as("step 5: an operator is unconstrained").doesNotThrowAnyException();
        assertThat((String) model.findById(ownDomainId).get(SiteDomainModel.PATH))
            .as("step 5: and the operator's write landed").isEqualTo("/carve");

        TenantConduits.as(adminPrincipal, () -> {
            Row row = model.findById(ownDomainId);
            row.set(SiteDomainModel.LISTEN_ON, null);
            row.set(SiteDomainModel.PATH, null);
            row.set(SiteDomainModel.STRIP_PATH, false);
            model.save(row);
        });
    }
}
