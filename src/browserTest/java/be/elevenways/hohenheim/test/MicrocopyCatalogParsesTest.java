package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.format.MessageParseException;
import be.elevenways.protoblast.common.i18n.format.ZenitMessageFormat;
import be.elevenways.zenit.microcopy.Translation;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every shipped microcopy message must PARSE: one unparseable source (an unescaped
 * literal brace, e.g. a doc string mentioning template placeholders) throws
 * {@link MessageParseException} at render time and takes the WHOLE page's form template
 * down with it -- the VM instance form was unrenderable over three help texts that
 * contained a literal <code>{{KEY}}</code>. Literal braces are spelled {@code \{}.
 */
class MicrocopyCatalogParsesTest {

    @Test
    void everyShippedMicrocopyMessageParses() {
        DefaultCatalogLoader loader = new DefaultCatalogLoader();
        List<String> failures = new ArrayList<>();
        int parsed = 0;
        for (String tag : List.of("en", "nl")) {
            LocaleChain chain = LocaleChain.ofTags(tag);
            for (String key : loader.keysFor(chain)) {
                for (Translation candidate : loader.findCandidates(key, chain)) {
                    String source = candidate.getSource();
                    if (source == null) {
                        continue;
                    }
                    try {
                        ZenitMessageFormat.parseUncached(source);
                        parsed++;
                    } catch (MessageParseException broken) {
                        failures.add(tag + " '" + key + "': " + broken.getMessage()
                            + " -- source: " + source);
                    }
                }
            }
        }
        assertThat(parsed)
            .as("the classpath catalogs were actually loaded (a zero count would make"
                + " this test vacuous)")
            .isGreaterThan(1000);
        assertThat(failures)
            .as("every shipped microcopy message parses; escape literal braces as \\{")
            .isEmpty();
    }

    /**
     * en and nl must be SYMMETRIC per (key, scope) pair: a key present in one catalog
     * and absent from the other resolves to the key itself for that audience, which
     * renders as a raw token on the page instead of a translation.
     *
     * AIDEV-NOTE: pairs, not bare keys -- the catalogs are FILTERED, so one key legitimately
     * carries a different entry per scope and a bare-key comparison would call the catalogs
     * symmetric while an individual scope was missing (which is exactly what
     * {@code status}/{@code spamservice} was: present in en, absent from nl, undetected).
     */
    @Test
    void theEnglishAndDutchCatalogsCoverTheSameKeysAndScopes() throws Exception {
        // Scoped to THIS repo's own catalogs on purpose: the shared classpath merges every
        // framework module's catalog, and a module's asymmetry is not this suite's to judge.
        Path resources = Path.of("src/server/resources");
        assertThat(Files.isDirectory(resources))
            .as("the check needs the hohenheim project dir as its working directory").isTrue();
        DefaultCatalogLoader loader;
        try (URLClassLoader own = new URLClassLoader(
                new URL[] {resources.toUri().toURL()}, null)) {
            loader = new DefaultCatalogLoader("META-INF/microcopy/", own);
            TreeSet<String> english = pairsOf(loader, "en");
            TreeSet<String> dutch = pairsOf(loader, "nl");
            assertSymmetric(english, dutch);
        }
    }

    private static void assertSymmetric(TreeSet<String> english, TreeSet<String> dutch) {
        assertThat(english).as("the en catalog was actually loaded").hasSizeGreaterThan(1000);

        TreeSet<String> untranslated = new TreeSet<>(english);
        untranslated.removeAll(dutch);
        TreeSet<String> orphaned = new TreeSet<>(dutch);
        orphaned.removeAll(english);

        assertThat(untranslated).as("en entries with no nl counterpart").isEmpty();
        assertThat(orphaned).as("nl entries with no en counterpart").isEmpty();
    }

    /**
     * Every {@code test_failed} variant must render the reason its call site passes.
     *
     * AIDEV-NOTE: the three "Send test" buttons (notification channel, backup target,
     * git provider) all hand this key a {@code reason} arg. An entry without the
     * placeholder still resolves -- the arg is simply dropped -- so the operator reads
     * "Test delivery failed" and has nothing to act on, and nothing else fails. The
     * notification_channel entry lost its placeholder in a catalog rewrite and stayed
     * that way undetected, which is what this test exists to catch.
     */
    @Test
    void everyTestFailedVariantNamesItsReason() throws Exception {
        Path resources = Path.of("src/server/resources");
        assertThat(Files.isDirectory(resources))
            .as("the check needs the hohenheim project dir as its working directory").isTrue();

        List<String> missing = new ArrayList<>();
        int checked = 0;

        try (URLClassLoader own = new URLClassLoader(
                new URL[] {resources.toUri().toURL()}, null)) {
            DefaultCatalogLoader loader = new DefaultCatalogLoader("META-INF/microcopy/", own);

            for (String tag : List.of("en", "nl")) {
                for (Translation candidate
                        : loader.findCandidates("test_failed", LocaleChain.ofTags(tag))) {
                    String source = candidate.getSource();

                    if (source == null) {
                        continue;
                    }
                    checked++;

                    if (!source.contains("{$reason}")) {
                        missing.add(tag + " '" + source + "'");
                    }
                }
            }
        }
        assertThat(checked).as("the test_failed variants were actually loaded")
            .isGreaterThanOrEqualTo(6);
        assertThat(missing)
            .as("every test_failed variant renders the {$reason} its caller passes")
            .isEmpty();
    }

    /** Every {@code key|filters} pair one locale's catalog declares. */
    private static TreeSet<String> pairsOf(DefaultCatalogLoader loader, String tag) {
        LocaleChain chain = LocaleChain.ofTags(tag);
        TreeSet<String> pairs = new TreeSet<>();
        for (String key : loader.keysFor(chain)) {
            for (Translation candidate : loader.findCandidates(key, chain)) {
                TreeSet<String> filters = new TreeSet<>();
                for (Translation.Filter filter : candidate.getFilters()) {
                    filters.add(filter.getName() + "=" + filter.getValue());
                }
                pairs.add(key + "|" + filters);
            }
        }
        return pairs;
    }
}
