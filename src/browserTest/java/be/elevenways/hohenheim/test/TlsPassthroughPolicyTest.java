package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static be.elevenways.hohenheim.test.ProxyTestSupport.bootRuntime;
import static be.elevenways.hohenheim.test.ProxyTestSupport.resetDatabase;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pins passthrough invariants at the model write boundary, not only in CMS forms. */
class TlsPassthroughPolicyTest {

    private static boolean initialized;

    @BeforeAll
    static void boot() throws Exception {
        if (initialized) return;
        initialized = true;
        bootRuntime();
    }

    @Test
    void domainWriteNormalizesCertificateOwnershipFlags() throws Exception {
        resetDatabase();
        Row site = passthroughSite();
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "passthrough.example.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, true);
        domain.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, false);
        Models.get(SiteDomainModel.class).save(domain);

        assertThat(domain.get(SiteDomainModel.FORCE_SSL)).isFalse();
        assertThat(domain.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)).isTrue();
    }

    @Test
    void nonCmsDomainWritesCannotAddHttpOnlyOptions() throws Exception {
        resetDatabase();
        Row site = passthroughSite();
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "path.example.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.PATH, "/api");

        assertThatThrownBy(() -> Models.get(SiteDomainModel.class).save(domain))
            .isInstanceOf(Violations.class)
            .hasMessageContaining("tls_passthrough_no_path");
    }

    @Test
    void regexDomainNormalizationPreservesJavaRegexCaseAndEscapes() throws Exception {
        resetDatabase();
        Row site = passthroughSite();
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "  \\QMixed.Example\\E  ");
        domain.set(SiteDomainModel.MATCH_TYPE, "regex");
        domains.save(domain);

        assertThat(domain.get(SiteDomainModel.HOSTNAME)).isEqualTo("\\QMixed.Example\\E");
    }

    @Test
    void siteTypeTransitionValidatesExistingDomains() throws Exception {
        resetDatabase();
        Row site = setupSite("hohenheim:proxy", "HTTP site", "http-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", 8080));
        Row domain = Models.get(SiteDomainModel.class).createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "transition.example.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.PATH, "/api");
        Models.get(SiteDomainModel.class).save(domain);

        site.set(SiteModel.SITE_TYPE, SiteModel.SITE_TYPE_TLS_PASSTHROUGH);
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1", "forward_port", 8443));
        assertThatThrownBy(() -> Models.get(SiteModel.class).save(site))
            .isInstanceOf(Violations.class)
            .hasMessageContaining("tls_passthrough_no_path");
    }

    @Test
    void validSiteTypeTransitionNormalizesExistingDomains() throws Exception {
        resetDatabase();
        Row site = setupSite("hohenheim:proxy", "HTTP site", "http-site",
            Map.of("forward_host", "127.0.0.1", "forward_port", 8080));
        SiteDomainModel domains = Models.get(SiteDomainModel.class);
        Row domain = domains.createEmptyRow();
        domain.set(SiteDomainModel.SITE_ID, site.get(SiteModel.ID));
        domain.set(SiteDomainModel.HOSTNAME, "transition.example.test");
        domain.set(SiteDomainModel.MATCH_TYPE, SiteDomainModel.MATCH_EXACT);
        domain.set(SiteDomainModel.FORCE_SSL, true);
        domain.set(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT, false);
        domains.save(domain);

        site.set(SiteModel.SITE_TYPE, SiteModel.SITE_TYPE_TLS_PASSTHROUGH);
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1", "forward_port", 8443));
        Models.get(SiteModel.class).save(site);

        Row saved = domains.findById(domain.get(SiteDomainModel.ID));
        assertThat(saved.get(SiteDomainModel.FORCE_SSL)).isFalse();
        assertThat(saved.get(SiteDomainModel.EXCLUDE_FROM_LETSENCRYPT)).isTrue();
    }

    @Test
    void siteWriteRejectsHttpAccessControl() throws Exception {
        resetDatabase();
        Row site = Models.get(SiteModel.class).createEmptyRow();
        site.set(SiteModel.NAME, "Invalid passthrough");
        site.set(SiteModel.SLUG, "invalid-passthrough");
        site.set(SiteModel.SITE_TYPE, SiteModel.SITE_TYPE_TLS_PASSTHROUGH);
        site.set(SiteModel.SETTINGS, Map.of("forward_host", "127.0.0.1", "forward_port", 8443));
        site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
        site.set(SiteModel.ENABLED, true);
        site.set(SiteModel.ACCESS_LIST_ID, 123);

        assertThatThrownBy(() -> Models.get(SiteModel.class).save(site))
            .isInstanceOf(Violations.class)
            .hasMessageContaining("tls_passthrough_no_access_list");
    }

    @Test
    void legacyCommaDelimitedTrustedProxyKeysCoerceForSettingsMigration() {
        var result = HohenheimSettings.Proxy.TRUSTED_PROXY_KEYS.coerce("first-key, second-key");

        assertThat(result.accepted()).isTrue();
        assertThat(result.value()).isEqualTo(List.of("first-key", "second-key"));
    }

    private static Row passthroughSite() {
        return setupSite(SiteModel.SITE_TYPE_TLS_PASSTHROUGH, "TLS passthrough", "tls-passthrough",
            Map.of("forward_host", "127.0.0.1", "forward_port", 8443));
    }
}
