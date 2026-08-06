package be.elevenways.hohenheim.test;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.format.MessageParseException;
import be.elevenways.protoblast.common.i18n.format.ZenitMessageFormat;
import be.elevenways.zenit.microcopy.Translation;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
