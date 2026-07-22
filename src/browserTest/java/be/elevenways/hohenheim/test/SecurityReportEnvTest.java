package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SecurityReporterModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.security.SecurityReportEnv;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-site reporter env injection: lazy minting, reuse, rotate-heal when the
 * raw copy is lost, and no injection while the base URL is unset.
 */
class SecurityReportEnvTest extends HohenheimTestBase {

    @AfterEach
    void resetBaseUrl() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.INGEST_BASE_URL, "");
    }

    private int createSite(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        site.set(SiteModel.SITE_TYPE, "hohenheim:dead");
        site.set(SiteModel.ENABLED, true);
        sites.save(site);
        return site.get(SiteModel.ID);
    }

    @Test
    void noBaseUrlMeansNoInjection() {
        int siteId = createSite("Env No Base");
        assertThat(SecurityReportEnv.forSite(siteId)).isEmpty();
        // And nothing was minted either.
        assertThat(Models.get(SecurityReporterModel.class).find()
            .where(SecurityReporterModel.SITE_ID.eq(siteId)).count()).isZero();
    }

    @Test
    void firstCallMintsAReporterAndStoresTheRawToken() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.INGEST_BASE_URL,
            "https://admin.example.com/");
        int siteId = createSite("Env Mint");

        Map<String, String> env = SecurityReportEnv.forSite(siteId);
        assertThat(env.get(SecurityReportEnv.URL_VAR))
            .isEqualTo("https://admin.example.com/zn/security/ingest");
        String token = env.get(SecurityReportEnv.TOKEN_VAR);
        assertThat(token).startsWith("zsec_");

        Row reporter = Models.get(SecurityReporterModel.class).find()
            .where(SecurityReporterModel.SITE_ID.eq(siteId)).first();
        assertThat(reporter).isNotNull();
        assertThat(reporter.get(SecurityReporterModel.NAME)).isEqualTo("Env Mint");
        assertThat(reporter.get(SecurityReporterModel.ENABLED)).isTrue();
        // The reporters table stores only the hash; the raw copy lives on the site row.
        assertThat(reporter.get(SecurityReporterModel.TOKEN_HASH))
            .isEqualTo(SecureTokens.sha256Hex(token));
        Row site = Models.get(SiteModel.class).findById(siteId);
        assertThat(site.get(SiteModel.SECURITY_REPORT_TOKEN)).isEqualTo(token);
    }

    @Test
    void repeatCallsReuseTheSameReporterAndToken() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.INGEST_BASE_URL,
            "https://admin.example.com");
        int siteId = createSite("Env Reuse");

        Map<String, String> first = SecurityReportEnv.forSite(siteId);
        Map<String, String> second = SecurityReportEnv.forSite(siteId);
        assertThat(second).isEqualTo(first);
        assertThat(Models.get(SecurityReporterModel.class).find()
            .where(SecurityReporterModel.SITE_ID.eq(siteId)).count()).isEqualTo(1);
    }

    @Test
    void lostRawTokenHealsByRotating() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.INGEST_BASE_URL,
            "https://admin.example.com");
        int siteId = createSite("Env Heal");

        Map<String, String> first = SecurityReportEnv.forSite(siteId);

        // Simulate a lost raw copy (e.g. an admin rotated the reporter).
        Models.get(SiteModel.class).find()
            .where(SiteModel.ID.eq(siteId))
            .assign(SiteModel.SECURITY_REPORT_TOKEN, null)
            .bypassBehaviours()
            .updateAll();

        Map<String, String> healed = SecurityReportEnv.forSite(siteId);
        String newToken = healed.get(SecurityReportEnv.TOKEN_VAR);
        assertThat(newToken).startsWith("zsec_");
        assertThat(newToken).isNotEqualTo(first.get(SecurityReportEnv.TOKEN_VAR));

        // Still ONE reporter, hash matching the new raw value.
        var reporters = Models.get(SecurityReporterModel.class).find()
            .where(SecurityReporterModel.SITE_ID.eq(siteId)).all();
        assertThat(reporters).hasSize(1);
        assertThat(reporters.get(0).get(SecurityReporterModel.TOKEN_HASH))
            .isEqualTo(SecureTokens.sha256Hex(newToken));
    }

    @Test
    void unknownSiteInjectsNothing() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.INGEST_BASE_URL,
            "https://admin.example.com");
        assertThat(SecurityReportEnv.forSite(999_999)).isEmpty();
    }
}
