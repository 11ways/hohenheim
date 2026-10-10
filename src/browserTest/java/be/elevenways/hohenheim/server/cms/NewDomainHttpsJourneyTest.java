package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.site.SiteTls;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new address is served over plain HTTP until a working certificate covers it, then forces HTTPS by itself, once.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class NewDomainHttpsJourneyTest extends HohenheimTestBase {

    @Test
    void aNewAddressForcesHttpsOnceItsCertificateWorks() {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        CertificateModel certificates = Models.get(CertificateModel.class);
        Row site = site("new-domain-https");

        // 1. A new address is not forced: no certificate covers it yet, so forcing would send every visitor to an error
        //    page. It is armed to switch itself on, and the site's HTTPS cell says what is true: no certificate.
        Row fresh = domain(site, "fresh.https-journey.test", null);
        assertThat((Boolean) fresh.get(SiteDomainModel.FORCE_SSL)).as("step 1: a new address is not forced").isFalse();
        assertThat((Boolean) fresh.get(SiteDomainModel.FORCE_SSL_AUTO)).as("step 1: but armed").isTrue();
        assertThat(SiteParts.tlsOf(site)).as("step 1: the site's HTTPS reads missing, never forced")
            .isEqualTo(SiteTls.MISSING);

        // 2. An explicit choice disarms the latch: switched on and off again by the operator, it stays off.
        Row chosen = domain(site, "chosen.https-journey.test", true);
        chosen.set(SiteDomainModel.FORCE_SSL, false);
        domains.save(chosen);
        chosen = domains.findById(chosen.get(SiteDomainModel.ID));
        assertThat((Boolean) chosen.get(SiteDomainModel.FORCE_SSL_AUTO)).as("step 2: an explicit choice disarms")
            .isFalse();

        // 3. A working certificate covering both arrives: the armed address is forced and disarmed, with an activity
        //    entry; the operator's explicit "off" is left alone.
        Row cert = certificate("https-journey", "fresh.https-journey.test,chosen.https-journey.test");
        fresh = domains.findById(fresh.get(SiteDomainModel.ID));
        assertThat((Boolean) fresh.get(SiteDomainModel.FORCE_SSL)).as("step 3: the armed address is forced").isTrue();
        assertThat((Boolean) fresh.get(SiteDomainModel.FORCE_SSL_AUTO)).as("step 3: once: the latch cleared").isFalse();
        assertThat(Models.get(ActivityModel.class).find()
                .where(ActivityModel.MODEL.eq(SiteDomainModel.MODEL_ID.toString()))
                .where(ActivityModel.RECORD_ID.eq(String.valueOf((Object) fresh.get(SiteDomainModel.ID))))
                .where(ActivityModel.ACTION.eq(HohenheimActivityAction.HTTPS_FORCED.id().toString())).all())
            .as("step 3: the switch is recorded in the address's activity").hasSize(1);
        assertThat((Boolean) domains.findById(chosen.get(SiteDomainModel.ID)).get(SiteDomainModel.FORCE_SSL))
            .as("step 3: an explicit off stays off").isFalse();
        assertThat(SiteParts.tlsOf(site)).as("step 3: every exact address works").isEqualTo(SiteTls.WORKS);

        // 4. An address added while a working certificate already covers it is forced in the same write.
        Row late = domain(site, "late.https-journey.test", null);
        assertThat((Boolean) late.get(SiteDomainModel.FORCE_SSL)).as("step 4: a name no certificate covers is not")
            .isFalse();
        Row wildcard = certificate("https-journey-wildcard", "*.https-journey.test");
        Row underWildcard = domain(site, "under.https-journey.test", null);
        assertThat((Boolean) underWildcard.get(SiteDomainModel.FORCE_SSL))
            .as("step 4: an address written under a working certificate is forced at once").isTrue();

        // 5. The certificate is lost: a forced address keeps failing closed, and the operator SEES it -- the site's
        //    HTTPS cell turns to the error page and an attention item names the address.
        certificates.delete(cert);
        certificates.delete(wildcard);
        assertThat((Boolean) domains.findById(fresh.get(SiteDomainModel.ID)).get(SiteDomainModel.FORCE_SSL))
            .as("step 5: losing the certificate never unforces").isTrue();
        assertThat(SiteParts.tlsOf(site)).as("step 5: the site reads broken")
            .isEqualTo(SiteTls.BROKEN);
        List<AttentionItem> items = new ArrayList<>();
        ProxyAttention.forcedWithoutCertificate(items);
        assertThat(items).as("step 5: one item per forced address without a working certificate")
            .anySatisfy(item -> assertThat(String.valueOf(item.title().args().asMap().get("hostname")))
                .isEqualTo("fresh.https-journey.test"));
    }

    private static Row site(String name) {
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(site);
        return site;
    }

    private static Row domain(Row site, String hostname, Boolean forceSsl) {
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, hostname);
        if (forceSsl != null) {
            domain.set(SiteDomainModel.FORCE_SSL, forceSsl);
        }
        domains.save(domain);
        return domains.findById(domain.get(SiteDomainModel.ID));
    }

    private static Row certificate(String name, String names) {
        CertificateModel certificates = Models.get(CertificateModel.class);
        Row cert = certificates.createEmptyRow();
        cert.set(CertificateModel.NICE_NAME, name);
        cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        cert.set(CertificateModel.DOMAIN_NAMES_TEXT, names);
        certificates.save(cert);
        return cert;
    }
}
