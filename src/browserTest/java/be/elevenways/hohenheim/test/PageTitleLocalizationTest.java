package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule "every page and error is localized", enforced instead of swept.
 *
 * AIDEV-NOTE: this exists because the sweep alone provably does not hold. Three pages
 * were fixed in one sweep, nine were listed as remaining -- and by the time
 * the inventory was written the count was ELEVEN call sites, because
 * InstanceDevicesPage was authored after the list and inherited the defect from its
 * neighbours. That no page builds its title by concatenating an English literal is the
 * page-title-literals and proxy-error-title-literals local rules in hohenheim's build
 * script, which gate every compile; this test keeps the half they cannot see.
 */
class PageTitleLocalizationTest {

    /**
     * Every scope a page passes to {@code CmsSupport.pageTitle}, plus the proxy error
     * strings, RESOLVES in en and nl -- the half the source guard structurally cannot
     * see. A mistyped scope, a key added to en only, or a translation that forgot the
     * name argument all pass the scanner and render a raw token to the visitor.
     */
    @Test
    void everyPageTitleScopeResolvesInBothLocales() {
        ShippedCatalogs catalogs = new ShippedCatalogs();
        List<String> broken = new ArrayList<>();

        Microcopy putOnlineTitle = HohenheimMicrocopy.PUT_ONLINE.of("put_online");
        for (String tag : List.of("en", "nl")) {
            LocaleChain chain = LocaleChain.ofTags(tag);
            if (catalogs.resolveSource(putOnlineTitle.key(), chain, putOnlineTitle.filters()) == null) {
                broken.add(tag + " put_online[scope=put_online] is not shipped");
            }
        }

        for (HohenheimMicrocopy scope : List.of(HohenheimMicrocopy.INSTANCE_DEVICE, HohenheimMicrocopy.SCHEDULE_STEP,
                HohenheimMicrocopy.DNS_ZONE_FILE, HohenheimMicrocopy.DNS_ZONE_RECORDS, HohenheimMicrocopy.DEV_SESSIONS,
                HohenheimMicrocopy.SITE_DATABASES, HohenheimMicrocopy.DATABASE_RESTORE,
                HohenheimMicrocopy.SPAMSERVICE_SAMPLE, HohenheimMicrocopy.INSTANCE_DEPLOYMENTS,
                HohenheimMicrocopy.INSTANCE_VOLUME, HohenheimMicrocopy.SITE_DOMAINS)) {
            for (String tag : List.of("en", "nl")) {
                String resolved = scope.of("page_title")
                    .withArg("name", "acme-one")
                    .resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.contains("page_title") || !resolved.contains("acme-one")) {
                    broken.add(tag + " page_title[scope=" + scope.scope() + "] -> '" + resolved + "'");
                }
            }
        }

        for (String key : List.of("not_found_title", "not_found_message", "bad_gateway_title",
                "unreachable_message", "dev_offline_title", "dev_offline_message")) {
            for (String tag : List.of("en", "nl")) {
                String resolved = HohenheimMicrocopy.PROXY_ERROR.of(key)
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
