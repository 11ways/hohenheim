package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ReleasedRouteClaimModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.zenit.common.edit.EditView;
import be.elevenways.zenit.common.edit.FormEntry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site create form's optional first hostname: it writes a {@code site_domains} row in
 * the create's OWN transaction, so a claimed name is refused by the write pipeline's route
 * invariant and takes the half-made site down with it.
 */
class SiteCreateHostnameTest extends HohenheimTestBase {

    private static final String HOLDER_HOSTNAME = "held.createhostname.test";

    /** What the operator types: the model's hook is what folds it to the stored form. */
    private static final String HOLDER_HOSTNAME_TYPED = "Held.CreateHostname.Test";

    private static final String FREE_HOSTNAME = "free.createhostname.test";

    /** A static site, enabled so its hostname actually claims a route. */
    private static String createBody(String name, String hostname) {
        return "name=" + name.replace(" ", "+")
            + "&upstream_kind=hohenheim%3Astatic"
            + "&settings.root_path=%2Ftmp"
            + "&enabled=true"
            + "&hostname=" + hostname;
    }

    private static Row siteNamed(String name) {
        return Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first();
    }

    private static List<Row> domainsOf(Row site) {
        return Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.SITE_ID.eq(site.get(SiteModel.ID))).all();
    }

    private static long siteCount() {
        return Models.get(SiteModel.class).find().count();
    }

    @AfterEach
    void cleanUp() {
        Model siteModel = Models.get(SiteModel.class);
        Model domainModel = Models.get(SiteDomainModel.class);
        for (String name : new String[] {"Hostnameless Site", "Hostname Site", "Takeover Site"}) {
            Row site = siteNamed(name);
            if (site == null) {
                continue;
            }
            for (Row domain : domainsOf(site)) {
                domainModel.delete(domain);
            }
            siteModel.delete(site);
        }
        // Tearing a live site down IS a release, so cleanup itself ledgers quarantine rows;
        // they would otherwise refuse another class's claim on the same hostname.
        Models.get(ReleasedRouteClaimModel.class).find().delete();
    }

    @Test
    void createFormBindsTheFirstHostnameInTheSameTransaction() throws Exception {
        // 1. A blank hostname is still a legal create: the site is made, no domain row is,
        //    and the operator lands on the Domains tab exactly as before.
        HttpResponse<String> blank = httpPostForm("/admin/sites/new",
            createBody("Hostnameless Site", ""), sessionToken, csrfToken);
        assertThat(blank.statusCode())
            .as("step 1: a blank hostname creates the site").isIn(302, 303);
        Row hostnameless = siteNamed("Hostnameless Site");
        assertThat(hostnameless).as("step 1: the site row exists").isNotNull();
        assertThat(domainsOf(hostnameless))
            .as("step 1: and a blank hostname creates no domain row").isEmpty();
        assertThat(blank.headers().firstValue("Location"))
            .as("step 1: a hostname-less site still lands on its Domains tab")
            .hasValueSatisfying(location -> assertThat(location)
                .startsWith("/admin/sites/" + hostnameless.get(SiteModel.ID) + "/page/domains"));

        // 2. A free hostname creates exactly one site_domains row, bound to the new site,
        //    canonicalized and defaulted by the model's own write pipeline.
        HttpResponse<String> created = httpPostForm("/admin/sites/new",
            createBody("Hostname Site", HOLDER_HOSTNAME_TYPED), sessionToken, csrfToken);
        assertThat(created.statusCode())
            .as("step 2: a free hostname creates the site").isIn(302, 303);
        Row holder = siteNamed("Hostname Site");
        assertThat(holder).as("step 2: the site row exists").isNotNull();
        List<Row> domains = domainsOf(holder);
        assertThat(domains).as("step 2: exactly one hostname row was created").hasSize(1);
        Row domain = domains.get(0);
        assertThat((String) domain.get(SiteDomainModel.HOSTNAME))
            .as("step 2: stored in the model's canonical form").isEqualTo(HOLDER_HOSTNAME);
        assertThat((Object) domain.get(SiteDomainModel.SITE_ID))
            .as("step 2: bound to the site the form just created")
            .isEqualTo(holder.get(SiteModel.ID));
        assertThat((Object) domain.get(SiteDomainModel.MATCH_TYPE))
            .as("step 2: the match type is derived, not asked for")
            .isEqualTo(SiteDomainModel.MATCH_EXACT);
        assertThat((Object) domain.get(SiteDomainModel.FORCE_SSL))
            .as("step 2: carrying the field's own default, like the quick-add bar")
            .isEqualTo(SiteDomainModel.FORCE_SSL.getDefaultValue());
        assertThat((String) domain.get(SiteDomainModel.LIVE_ROUTE_KEY))
            .as("step 2: and it holds a live route claim").isNotNull();

        // 3. THE ROLLBACK: a hostname another live site already claims is refused by the
        //    SiteDomainModel write pipeline -- the same refusal a hand-made domain gets --
        //    and the site created moments earlier in that same transaction is gone with it.
        long sitesBefore = siteCount();
        HttpResponse<String> refused = httpPostForm("/admin/sites/new",
            createBody("Takeover Site", HOLDER_HOSTNAME), sessionToken, csrfToken);
        assertThat(refused.statusCode())
            .as("step 3: a claimed hostname rerenders the form instead of redirecting")
            .isEqualTo(200);
        assertThat(refused.body())
            .as("step 3: the refusal is the route-claim sentence, naming the holder")
            .contains("already claimed by site")
            .contains("Hostname Site");
        assertThat(refused.body())
            .as("step 3: and it is anchored on the hostname input")
            .contains("name=\"hostname\"");
        assertThat(siteNamed("Takeover Site"))
            .as("step 3: the refused create left no site row behind").isNull();
        assertThat(siteCount())
            .as("step 3: proving the whole create rolled back, not just the domain")
            .isEqualTo(sitesBefore);
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(HOLDER_HOSTNAME)).count())
            .as("step 3: and the holder still owns the hostname alone").isEqualTo(1);

        // 4. The EDIT form is untouched: the entry is CREATE-only, so it neither renders
        //    nor coerces there -- a hand-posted hostname on an update writes nothing.
        SiteResource resource = new SiteResource();
        assertThat(resource.formSpec().forView(EditView.CREATE).entries().stream()
                .map(FormEntry::name))
            .as("step 4: the create view carries the hostname entry")
            .contains(SiteDomainModel.HOSTNAME.getName());
        assertThat(resource.formSpec().forView(EditView.EDIT).entries().stream()
                .map(FormEntry::name))
            .as("step 4: the edit view does not")
            .doesNotContain(SiteDomainModel.HOSTNAME.getName());

        Integer holderId = holder.get(SiteModel.ID);
        assertThat(adminGet("/admin/sites/" + holderId).body())
            .as("step 4: so the edit form renders no hostname input")
            .doesNotContain("name=\"hostname\"");
        httpPostForm("/admin/sites/" + holderId,
            "name=Hostname+Site&hostname=" + FREE_HOSTNAME, sessionToken, csrfToken);
        assertThat(domainsOf(holder))
            .as("step 4: a hand-posted hostname on an update writes no second hostname row")
            .hasSize(1);
        assertThat(Models.get(SiteDomainModel.class).find()
            .where(SiteDomainModel.HOSTNAME.eq(FREE_HOSTNAME)).count())
            .as("step 4: nor a row anywhere else").isZero();
    }
}
