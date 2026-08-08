package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.guard.NamedPattern;
import be.elevenways.protoblast.guard.ScanResult;
import be.elevenways.protoblast.guard.ScanRoot;
import be.elevenways.protoblast.guard.SourceRule;
import be.elevenways.protoblast.guard.SourceRuleScanner;
import be.elevenways.protoblast.guard.Violation;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Phase 6 gate clause "every page and error is localized", enforced instead of
 * swept: no page may build its title by concatenating an English literal.
 *
 * AIDEV-NOTE: this exists because the sweep alone provably does not hold. Three pages
 * were fixed in the 2026-08-04 wave, nine were listed as remaining -- and by the time
 * the inventory was written the count was ELEVEN call sites, because
 * InstanceDevicesPage was authored after the list and inherited the defect from its
 * neighbours. A guard is worth more than the eleven edits it protects. The walk is the
 * shared protoblast-source-guard scanner in judge mode; the accepted spellings are
 * deliberately only three, and the third one is the DECISION about bare record names:
 * a title that is nothing but the record's own name carries no translatable copy (an
 * instance/site/zone name is user data and is never translated -- see the plan's
 * localization rules), so it stays legal, while ANY literal mixed into a title is not.
 */
class PageTitleLocalizationTest {

    /** A page title assignment; group 1 is the whole value expression. */
    private static final Pattern TITLE = Pattern.compile("vars\\.put\\(\"title\",\\s*(.*)$");

    /** A proxy error page's title argument, which sits beside the status-code literal. */
    private static final Pattern PROXY_TITLE = Pattern.compile("render\\(\"\\d{3}\",\\s*(\".*)$");

    /** The only spellings a title may start with; everything else must carry no literal. */
    private static final List<String> RESOLVED = List.of("CmsSupport.pageTitle(", "Microcopy.of(");

    @Test
    void noPageBuildsItsTitleByConcatenatingALiteral() throws IOException {
        Path pages = Path.of("src/server/java/be/elevenways/hohenheim/server/cms");
        Path errorPages = Path.of(
            "src/server/java/be/elevenways/hohenheim/server/proxy/ErrorPages.java");
        assertThat(Files.isDirectory(pages))
            .as("the scan needs the hohenheim project dir as its working directory")
            .isTrue();
        assertThat(Files.isRegularFile(errorPages))
            .as("ErrorPages is the one PUBLIC-facing error surface and must be scanned")
            .isTrue();

        SourceRule rule = SourceRule.builder("page-title-localization")
            .consequence("a page title mixing an English literal into a record name is"
                + " unlocalizable: resolve it through CmsSupport.pageTitle (microcopy key"
                + " page_title in the page's own scope, en AND nl)")
            .root(ScanRoot.of(pages, "cms"))
            .root(ScanRoot.of(errorPages, "proxy"))
            .extensions("java")
            .pattern(NamedPattern.of("page-title", TITLE, "cms"))
            .pattern(NamedPattern.of("proxy-error-title", PROXY_TITLE, "proxy"))
            .judge(match -> {
                String value = match.group(1).trim();
                for (String accepted : RESOLVED) {
                    if (value.startsWith(accepted)) {
                        return null;
                    }
                }
                // No literal at all: a bare record name, which is user data by decision.
                return value.contains("\"") ? value : null;
            })
            .build();

        ScanResult result = SourceRuleScanner.scan(rule);
        assertThat(result.scannedFiles())
            .as("the scan actually walked the page classes (a zero count is vacuous)")
            .isGreaterThan(20);
        assertThat(result.violations())
            .as(() -> "page titles built by concatenating a literal:\n"
                + String.join("\n", result.violations().stream().map(Violation::format).toList()))
            .isEmpty();
    }

    /**
     * Every scope a page passes to {@code CmsSupport.pageTitle}, plus the proxy error
     * strings, RESOLVES in en and nl -- the half the source guard structurally cannot
     * see. A mistyped scope, a key added to en only, or a translation that forgot the
     * name argument all pass the scanner and render a raw token to the visitor.
     */
    @Test
    void everyPageTitleScopeResolvesInBothLocales() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        List<String> broken = new ArrayList<>();

        for (String scope : List.of("instance_device", "instance_schedule", "schedule_step",
                "dns_secondaries", "dns_zone_file", "dns_zone_records", "dev_sessions",
                "site_databases", "database_restore", "spamservice_sample", "site_processes",
                "site_deployments", "site_domains")) {
            for (String tag : List.of("en", "nl")) {
                String resolved = Microcopy.of("page_title").withFilter("scope", scope)
                    .withArg("name", "acme-one")
                    .resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.contains("page_title") || !resolved.contains("acme-one")) {
                    broken.add(tag + " page_title[scope=" + scope + "] -> '" + resolved + "'");
                }
            }
        }

        for (String key : List.of("not_found_title", "not_found_message", "bad_gateway_title",
                "unreachable_message", "dev_offline_title", "dev_offline_message")) {
            for (String tag : List.of("en", "nl")) {
                String resolved = Microcopy.of(key).withFilter("scope", "proxy_error")
                    .withArg("name", "acme-one")
                    .resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.contains(key)) {
                    broken.add(tag + " " + key + "[scope=proxy_error] -> '" + resolved + "'");
                }
            }
        }

        assertThat(broken)
            .as("every page title and proxy error string resolves to real copy carrying"
                + " its record name, in en AND nl")
            .isEmpty();
    }
}
