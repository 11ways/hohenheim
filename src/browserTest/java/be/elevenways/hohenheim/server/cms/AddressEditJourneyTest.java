package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimFormSections;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An address's page reads as three questions: which requests it answers, what it does about HTTPS and the
 * headers it changes, each a section of its own, under a line saying what it serves and whether the name points here.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class AddressEditJourneyTest extends HohenheimTestBase {

    @Test
    void anAddressPageGroupsItsSettingsAndHeadsWithItsState() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Row site = site("address-edit-" + suffix);
        Row domain = domain(site, "edit-" + suffix + ".test");

        HttpResponse<String> page = adminGet("/admin/domains/" + domain.get(SiteDomainModel.ID));
        assertThat(page.statusCode()).as("step 0: the address page renders").isEqualTo(200);
        String html = page.body();

        // 1. Three sections, in this order: which requests, HTTPS, headers.
        int requests = html.indexOf("data-section=\"" + HohenheimFormSections.ADDRESS_REQUESTS + "\"");
        int https = html.indexOf("data-section=\"" + HohenheimFormSections.ADDRESS_HTTPS + "\"");
        int headers = html.indexOf("data-section=\"" + HohenheimFormSections.ADDRESS_HEADERS + "\"");
        assertThat(requests).as("step 1: a Which requests section").isPositive();
        assertThat(https).as("step 1: an HTTPS section after it").isGreaterThan(requests);
        assertThat(headers).as("step 1: and Headers last").isGreaterThan(https);
        assertThat(html).as("step 1: the old catch-all Advanced section is gone")
            .doesNotContain("data-section=\"advanced\"");

        // 2. The heading's lead names the app this address serves.
        assertThat(html).as("step 2: the lead names the app").contains("Address of address-edit-" + suffix);
    }

    private static Row site(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.STATUS, "active");
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return site;
    }

    private static Row domain(Row site, String hostname) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        domains.save(domain);
        return domain;
    }
}
