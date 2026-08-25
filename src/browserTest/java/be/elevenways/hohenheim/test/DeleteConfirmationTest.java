package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.CertificateResource;
import be.elevenways.hohenheim.server.cms.DnsZoneResource;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.zenit.cms.common.action.ConfirmationSpec;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A delete dialog must name what deleting THIS record takes with it: the zone's origin,
 * its record count, what resolves inside it and whether it answers for the very hostname
 * the admin panel is being reached at -- and the same per-record naming for sites and
 * certificates.
 *
 * AIDEV-NOTE: asserted on the resources rather than through the rendered page, because
 * the confirmation is the ONLY half of a delete that a non-UI caller legitimately does
 * not get; the delete itself is proven by the write-path tests.
 */
class DeleteConfirmationTest {

    private static final String ORIGIN = "delete-confirm.test";

    private static SqlDatasource datasource;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
    }

    @Test
    void everyDeleteDialogNamesWhatThisRecordTakesWithIt() {
        Db.run(datasource, () -> {
            DnsZoneResource zones = new DnsZoneResource();

            // 1. A zone nothing depends on yet: the dialog NAMES the origin and how many
            //    stored records go with it, and gates the click behind typing the origin.
            int zoneId = zone(ORIGIN);
            record(zoneId, "www");
            record(zoneId, "mail");
            Row zone = Models.get(DnsZoneModel.class).findById(zoneId);

            ConfirmationSpec named = zones.deleteConfirmationFor(zone);
            assertThat(named.body().key())
                .as("step 1: a zone with no dependents gets the named wording")
                .isEqualTo("delete_confirm_named");
            assertThat(named.body().args().get("origin"))
                .as("step 1: and it names the zone").isEqualTo(ORIGIN);
            assertThat(named.body().args().get("records"))
                .as("step 1: with the count of records that go with it").isEqualTo(2L);
            assertThat(named.requireTypedConfirmation())
                .as("step 1: typing the origin is what arms the button")
                .isEqualTo(ORIGIN);

            // 2. The record-LESS dialog can only speak about the type, which is exactly
            //    why the record-aware hook exists -- and it arms no typed confirmation,
            //    because there is no origin to type.
            assertThat(zones.deleteConfirmation().body().key())
                .as("step 2: the type-level dialog keeps the generic wording")
                .isEqualTo("delete_confirm");
            assertThat(zones.deleteConfirmation().requireTypedConfirmation())
                .as("step 2: and demands no typed phrase").isNull();

            // 3. A site hostname and a certificate inside the zone: both are named, so an
            //    operator sees what loses its DNS before clicking.
            int siteId = site("shop", "shop." + ORIGIN);
            certificate("wildcard", "*." + ORIGIN + ", shop." + ORIGIN);

            ConfirmationSpec dependents = zones.deleteConfirmationFor(zone);
            assertThat(dependents.body().key())
                .as("step 3: dependents switch the zone to the dependent wording")
                .isEqualTo("delete_confirm_dependents");
            assertThat(String.valueOf(dependents.body().args().get("dependents")))
                .as("step 3: the site hostname and the certificate names are both named")
                .contains("shop." + ORIGIN)
                .contains("*." + ORIGIN);

            // 4. A zone that answers for the hostname THIS request arrived on: the one
            //    delete that can lock the operator out of the surface they are clicking in.
            TenantConduits.arrivingAt("https://panel." + ORIGIN, () -> {
                ConfirmationSpec locking = zones.deleteConfirmationFor(zone);
                assertThat(locking.body().key())
                    .as("step 4: the admin-hostname warning replaces the plain one")
                    .isEqualTo("delete_confirm_admin_dependents");
                assertThat(locking.body().args().get("host"))
                    .as("step 4: and names the hostname the panel is being reached at")
                    .isEqualTo("panel." + ORIGIN);
            });

            // 5. A request arriving on a hostname OUTSIDE the zone is never warned about
            //    a lockout that cannot happen.
            TenantConduits.arrivingAt("https://panel.elsewhere.test", () ->
                assertThat(zones.deleteConfirmationFor(zone).body().key())
                    .as("step 5: an unrelated admin hostname keeps the dependent wording")
                    .isEqualTo("delete_confirm_dependents"));

            // 6. The site dialog names the hostnames that stop answering.
            SiteResource sites = new SiteResource();
            Row shop = Models.get(SiteModel.class).findById(siteId);
            ConfirmationSpec siteConfirm = sites.deleteConfirmationFor(shop);
            assertThat(siteConfirm.body().key())
                .as("step 6: a site with hostnames gets the hostname wording")
                .isEqualTo("delete_confirm_hostnames");
            assertThat(siteConfirm.body().args().get("hostnames"))
                .as("step 6: naming the hostname that stops answering")
                .isEqualTo("shop." + ORIGIN);
            assertThat(siteConfirm.body().args().get("name"))
                .as("step 6: and the site it belongs to").isEqualTo("shop");

            // 7. A site with no hostname bound gets the generic body instead of a
            //    sentence naming nothing.
            Row bare = Models.get(SiteModel.class).findById(site("bare", null));
            assertThat(sites.deleteConfirmationFor(bare).body().key())
                .as("step 7: a hostname-less site keeps the generic wording")
                .isEqualTo("delete_confirm");

            // 8. The certificate dialog names the domains whose HTTPS stops working.
            CertificateResource certificates = new CertificateResource();
            Row wildcard = Models.get(CertificateModel.class).find()
                .where(CertificateModel.NICE_NAME.eq("wildcard")).first();
            ConfirmationSpec certConfirm = certificates.deleteConfirmationFor(wildcard);
            assertThat(certConfirm.body().key())
                .as("step 8: a certificate with domains gets the domain wording")
                .isEqualTo("delete_confirm_domains");
            assertThat(String.valueOf(certConfirm.body().args().get("domains")))
                .as("step 8: naming every name it secures")
                .contains("*." + ORIGIN)
                .contains("shop." + ORIGIN);

            // 9. A certificate with no stored names cannot name any, and says the rest.
            Row nameless = Models.get(CertificateModel.class).findById(certificate("empty", ""));
            assertThat(certificates.deleteConfirmationFor(nameless).body().key())
                .as("step 9: a nameless certificate keeps the generic wording")
                .isEqualTo("delete_confirm");
        });
    }

    // -- fixtures ---------------------------------------------------------------

    private static int zone(String origin) {
        Row row = Models.get(DnsZoneModel.class).createEmptyRow();
        row.set(DnsZoneModel.ORIGIN, origin);
        row.set(DnsZoneModel.ENABLED, true);
        row.set(DnsZoneModel.DEFAULT_TTL, 3600);
        row.set(DnsZoneModel.NEGATIVE_TTL, 300);
        row.set(DnsZoneModel.SOA_REFRESH, 7200);
        row.set(DnsZoneModel.SOA_RETRY, 3600);
        row.set(DnsZoneModel.SOA_EXPIRE, 1209600);
        Models.get(DnsZoneModel.class).save(row);
        return row.get(DnsZoneModel.ID);
    }

    private static void record(int zoneId, String name) {
        Row row = Models.get(DnsRecordModel.class).createEmptyRow();
        row.set(DnsRecordModel.ZONE_ID, zoneId);
        row.set(DnsRecordModel.NAME, name);
        row.set(DnsRecordModel.TYPE, "A");
        row.set(DnsRecordModel.VALUE, "203.0.113.10");
        row.set(DnsRecordModel.TTL, 300);
        row.set(DnsRecordModel.ENABLED, true);
        Models.get(DnsRecordModel.class).save(row);
    }

    private static int site(String slug, String hostname) {
        Row row = Models.get(SiteModel.class).createEmptyRow();
        row.set(SiteModel.NAME, slug);
        row.set(SiteModel.SLUG, slug);
        row.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        row.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + slug));
        row.set(SiteModel.STATUS, "active");
        row.set(SiteModel.ENABLED, true);
        Models.get(SiteModel.class).save(row);
        int siteId = row.get(SiteModel.ID);
        if (hostname != null) {
            Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
            domain.set(SiteDomainModel.SITE_ID, siteId);
            domain.set(SiteDomainModel.HOSTNAME, hostname);
            domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
            Models.get(SiteDomainModel.class).save(domain);
        }
        return siteId;
    }

    private static int certificate(String niceName, String domains) {
        Row row = Models.get(CertificateModel.class).createEmptyRow();
        row.set(CertificateModel.NICE_NAME, niceName);
        row.set(CertificateModel.DOMAIN_NAMES_TEXT, domains);
        row.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
        row.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
        Models.get(CertificateModel.class).save(row);
        return row.get(CertificateModel.ID);
    }
}
