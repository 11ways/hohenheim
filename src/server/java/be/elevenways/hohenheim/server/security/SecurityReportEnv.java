package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SecurityReporterModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives the ZENIT_SECURITY_REPORT_URL / ZENIT_SECURITY_REPORT_TOKEN
 * environment for a managed site's child processes, lazily auto-minting one
 * SecurityReporter per site. The reporters table stores only the token HASH;
 * the raw value lives on the site row ({@code sites.security_report_token})
 * because it must be re-injected on every spawn -- a lost/mismatched raw copy
 * self-heals by rotating the reporter. No {@code security.ingest_base_url}
 * setting means no injection at all.
 */
public final class SecurityReportEnv {

    public static final String URL_VAR = "ZENIT_SECURITY_REPORT_URL";
    public static final String TOKEN_VAR = "ZENIT_SECURITY_REPORT_TOKEN";

    /** The ingest route, appended to the configured base URL. */
    public static final String INGEST_PATH = "/zn/security/ingest";

    private SecurityReportEnv() {
    }

    /**
     * Fail-soft by design: a spawn must never die on reporter plumbing, so any
     * resolution error degrades to "no variables" with a log.
     */
    public static @NonNull Map<String, String> forSite(int siteId) {
        try {
            String base = HohenheimSettings.VALUES.getValue(
                HohenheimSettings.Security.INGEST_BASE_URL);
            if (base == null || base.isBlank()) {
                return Map.of();
            }

            SiteModel sites = Models.get(SiteModel.class);
            Row site = sites.find().where(SiteModel.ID.eq(siteId)).first();
            if (site == null) {
                return Map.of();
            }

            String token = resolveToken(site);
            if (token == null) {
                return Map.of();
            }

            Map<String, String> env = new LinkedHashMap<>();
            env.put(URL_VAR, ingestUrl(base));
            env.put(TOKEN_VAR, token);
            return env;
        } catch (RuntimeException e) {
            Blast.log("SECURITY: reporter env resolution failed for site", siteId, "-", e.getMessage());
            return Map.of();
        }
    }

    static @NonNull String ingestUrl(@NonNull String base) {
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + INGEST_PATH;
    }

    /** The site's raw reporter token, minting or rotating the reporter as needed. */
    private static @Nullable String resolveToken(@NonNull Row site) {
        Integer siteId = site.get(SiteModel.ID);
        if (siteId == null) {
            return null;
        }

        SecurityReporterModel reporters = Models.get(SecurityReporterModel.class);
        Row reporter = reporters.find()
            .where(SecurityReporterModel.SITE_ID.eq(siteId))
            .first();

        String raw = site.get(SiteModel.SECURITY_REPORT_TOKEN);

        if (reporter == null) {
            String name = site.get(SiteModel.NAME);
            SecurityReporters.MintedToken minted = SecurityReporters.create(
                name != null && !name.isBlank() ? name : "site-" + siteId, siteId, true);
            storeRaw(siteId, minted.raw());
            return minted.raw();
        }

        String hash = reporter.get(SecurityReporterModel.TOKEN_HASH);
        if (raw != null && !raw.isBlank() && hash != null
                && SecureTokens.constantTimeEquals(hash, SecureTokens.sha256Hex(raw))) {
            return raw;
        }

        // The raw copy is missing or stale (e.g. an admin rotated the reporter):
        // rotate again so the hash and the injected value agree.
        Integer reporterId = reporter.get(SecurityReporterModel.ID);
        if (reporterId == null) {
            return null;
        }
        SecurityReporters.MintedToken minted = SecurityReporters.rotate(reporterId);
        storeRaw(siteId, minted.raw());
        return minted.raw();
    }

    private static void storeRaw(int siteId, @NonNull String raw) {
        // Targeted assign, not a full row save: the sites model is revisionable
        // and this bookkeeping write must not create revisions or clobber
        // concurrent admin edits.
        Models.get(SiteModel.class).find()
            .where(SiteModel.ID.eq(siteId))
            .assign(SiteModel.SECURITY_REPORT_TOKEN, raw)
            .bypassBehaviours()
            .updateAll();
    }
}
