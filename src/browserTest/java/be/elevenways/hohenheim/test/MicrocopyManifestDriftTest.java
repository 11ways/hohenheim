package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.common.setting.ContentLocales;
import be.elevenways.zenit.microcopy.server.MicrocopyManifestDrift;
import org.junit.jupiter.api.Test;

/**
 * Every {@code t(...)} reference the compiled templates on this classpath declare
 * (hohenheim's own and every framework module's, filters included) must resolve through
 * the shipped catalogs for the chain a viewer gets, or the page prints the raw key. The
 * gate is zenit-microcopy's; this suite only names the chains the app serves.
 *
 * AIDEV-NOTE: the Dutch chain is built the way the framework builds it, through
 * ContentLocales.endingWithDefault -- never hand-spelled as "nl,en". A browser sending
 * only {@code Accept-Language: nl} used to get a chain of exactly {@code [nl]} and leaked
 * 17 keys from framework modules shipping English-only catalogs (zenit-widget, zenit-media,
 * zenit-comms); Conduit.setLocales now terminates every request chain with the default
 * content locale, so that viewer sees English rather than raw keys. This test judges that
 * produced chain, so it fails if the guarantee is ever removed.
 */
class MicrocopyManifestDriftTest {

    @Test
    void everyTemplateReferenceResolvesInEnglish() {
        MicrocopyManifestDrift.onClasspath().requireResolvable(LocaleChain.ofTags("en"));
    }

    @Test
    void everyTemplateReferenceResolvesForADutchOnlyViewer() {
        // The chain a "Accept-Language: nl" browser actually receives, from the same home
        // the request pipeline uses -- not a hand-written "nl,en".
        MicrocopyManifestDrift.onClasspath()
            .requireResolvable(ContentLocales.endingWithDefault(LocaleChain.ofTags("nl")));
    }
}
