package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
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
 * Every scoped resource label must declare the mid-sentence spelling the generated list
 * empty state and create title compose it into.
 *
 * AIDEV-NOTE: zenit-cms resolves a resource's plural/singular through
 * {@code Labels.inSentence} (the {@code case=sentence} filter) for "No {$name} yet" /
 * "Nog geen {$name}" and "New {$name}" / "Een {$name} aanmaken". A locale declaring no
 * variant falls back to the TITLE form, which reads wrong mid-sentence in both languages
 * and fails no test by construction -- so a new resource label without its sentence
 * sibling has to fail HERE or nowhere.
 */
class ResourceLabelSentenceFormsTest {

    /** The catalog keys carrying a resource label. */
    private static final List<String> LABEL_KEYS = List.of("plural", "singular");

    /** The filter naming which spelling of a label an entry carries. */
    private static final String CASE_FILTER = "case";

    /** The {@link #CASE_FILTER} value of the mid-sentence spelling. */
    private static final String SENTENCE = "sentence";

    @Test
    void everyScopedResourceLabelDeclaresItsSentenceForm() throws Exception {
        // 1. Read THIS repo's own catalogs only -- the shared classpath merges every framework
        //    module's catalog, and a module's missing variant is not this suite's to judge.
        Path resources = Path.of("src/server/resources");
        assertThat(Files.isDirectory(resources))
            .as("the check needs the hohenheim project dir as its working directory").isTrue();

        try (URLClassLoader own = new URLClassLoader(
                new URL[] {resources.toUri().toURL()}, null)) {
            DefaultCatalogLoader loader = new DefaultCatalogLoader("META-INF/microcopy/", own);

            // 2. Every scoped title-form label must have a case=sentence sibling on the same scope.
            for (String tag : List.of("en", "nl")) {
                TreeSet<String> titles = labelScopes(loader, tag, false);
                TreeSet<String> sentences = labelScopes(loader, tag, true);

                assertThat(titles)
                    .as(tag + " catalog was actually loaded (a zero count would make this vacuous)")
                    .hasSizeGreaterThan(40);

                TreeSet<String> missing = new TreeSet<>(titles);
                missing.removeAll(sentences);
                assertThat(missing)
                    .as(tag + " resource labels without a case=sentence variant"
                        + " (add {\"text\": \"...\", \"scope\": \"...\", \"case\": \"sentence\"}"
                        + " beside the base entry)")
                    .isEmpty();

                TreeSet<String> orphaned = new TreeSet<>(sentences);
                orphaned.removeAll(titles);
                assertThat(orphaned)
                    .as(tag + " case=sentence variants whose title-form base entry is missing")
                    .isEmpty();
            }

            // 3. Both languages must declare the same (key, scope) pairs: a label declared in
            //    one catalog only resolves to the raw key for the other audience.
            TreeSet<String> english = labelScopes(loader, "en", false);
            TreeSet<String> dutch = labelScopes(loader, "nl", false);

            TreeSet<String> untranslated = new TreeSet<>(english);
            untranslated.removeAll(dutch);
            assertThat(untranslated).as("en resource labels with no nl counterpart").isEmpty();

            TreeSet<String> extra = new TreeSet<>(dutch);
            extra.removeAll(english);
            assertThat(extra).as("nl resource labels with no en counterpart").isEmpty();
        }
    }

    /** Every {@code key|scope} pair one locale declares for a label, in the asked spelling. */
    private static TreeSet<String> labelScopes(DefaultCatalogLoader loader, String tag,
                                               boolean sentenceForm) {
        LocaleChain chain = LocaleChain.ofTags(tag);
        TreeSet<String> pairs = new TreeSet<>();

        for (String key : LABEL_KEYS) {
            for (Translation candidate : loader.findCandidates(key, chain)) {
                List<String> scopes = new ArrayList<>();
                boolean isSentence = false;

                for (Translation.Filter filter : candidate.getFilters()) {
                    if ("scope".equals(filter.getName())) {
                        scopes.add(filter.getValue());
                    } else if (CASE_FILTER.equals(filter.getName())) {
                        isSentence = SENTENCE.equals(filter.getValue());
                    }
                }
                if (scopes.isEmpty() || isSentence != sentenceForm) {
                    continue;
                }
                pairs.add(key + "|" + scopes.getFirst());
            }
        }
        return pairs;
    }
}
