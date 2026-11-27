package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.RecordCodes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

import static be.elevenways.hohenheim.test.HohenheimAppPartToolsTest.codeIn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An address made by address_set gets its certificate as the "Put online" wizard gives one, through the same orderer:
 * ordered by default, a refused order (Let's Encrypt switched off, as the wizard's tests keep it) never undoing the
 * address, and the answer saying in plain words what HTTPS gives it, with the call that orders one; a name a working
 * certificate already covers answers that it works. Let's Encrypt itself is never called.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class AddressHttpsToolJourneyTest extends HohenheimHttpTestBase {

    @Test
    void aNewAddressSaysWhatHttpsGivesIt() {
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
            TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
        String tag = HohenheimMcpTest.letters();
        Boolean letsEncryptWas = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED);
        Deque<Row> made = new ArrayDeque<>();
        try {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
            Row site = Models.get(SiteModel.class).createEmptyRow();
            site.set(SiteModel.NAME, "address-https-" + tag);
            site.set(SiteModel.SLUG, "address-https-" + tag);
            site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
            site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
            site.set(SiteModel.ENABLED, true);
            Models.get(SiteModel.class).save(site);
            made.push(site);
            String siteCode = Objects.requireNonNull(RecordCodes.codeUnjudged(SiteModel.MODEL_ID,
                String.valueOf((Object) site.get(SiteModel.ID))));

            // 1. Created with the wizard's default (automatic) while Let's Encrypt is off: the order is refused, the
            //    address stays, and the answer says why and which call orders one later.
            String hostname = "shop." + tag + ".address-https.test";
            McpHttpSession.Answer automatic = admin.call("address_set", Map.of("site", siteCode, "hostname",
                hostname));
            assertThat(automatic.error()).as("step 1: " + automatic.text()).isFalse();
            String code = codeIn(automatic.text(), "D");
            Row address = Models.get(SiteDomainModel.class).find().where(SiteDomainModel.HOSTNAME.eq(hostname))
                .first();
            assertThat(address).as("step 1: a refused order never undoes the address").isNotNull();
            made.push(address);
            assertThat(automatic.text()).as("step 1: why there is no certificate, and the retry")
                .contains("It has no certificate yet. Let's Encrypt is switched off in Settings.")
                .contains("certificate_order address=" + code);

            // 2. A name a working certificate already covers, created with https later: HTTPS works, by that
            //    certificate's code.
            Row cert = Models.get(CertificateModel.class).createEmptyRow();
            cert.set(CertificateModel.NICE_NAME, "address-https-" + tag);
            cert.set(CertificateModel.PROVIDER, CertificateModel.PROVIDER_CUSTOM);
            cert.set(CertificateModel.STATUS, CertificateModel.STATUS_ACTIVE);
            cert.set(CertificateModel.DOMAIN_NAMES_TEXT, "covered." + tag + ".address-https.test");
            Models.get(CertificateModel.class).save(cert);
            made.push(cert);
            String certCode = Objects.requireNonNull(RecordCodes.codeUnjudged(CertificateModel.MODEL_ID,
                String.valueOf((Object) cert.get(CertificateModel.ID))));
            McpHttpSession.Answer covered = admin.call("address_set", Map.of("site", siteCode, "hostname",
                "covered." + tag + ".address-https.test", "https", "later"));
            assertThat(covered.error()).as("step 2: " + covered.text()).isFalse();
            made.push(Objects.requireNonNull(Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.HOSTNAME.eq("covered." + tag + ".address-https.test")).first()));
            assertThat(covered.text()).as("step 2: it works, by the certificate's code")
                .contains("HTTPS works: certificate " + certCode + " covers it.");

            // 3. A choice the wizard does not offer here is refused by the argument's name.
            McpHttpSession.Answer wildcard = admin.call("address_set", Map.of("site", siteCode, "hostname",
                "other." + tag + ".address-https.test", "https", "wildcard"));
            assertThat(wildcard.error()).as("step 3: " + wildcard.text()).isTrue();
            assertThat(wildcard.text()).as("step 3: names the argument").contains("https");
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, letsEncryptWas);
            while (!made.isEmpty()) {
                Row row = made.pop();
                if (row.getModel() != null) row.getModel().delete(row);
            }
        }
    }
}
