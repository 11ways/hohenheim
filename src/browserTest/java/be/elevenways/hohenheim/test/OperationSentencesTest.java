package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.operation.Operations;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Hohenheim operation tells the activity log what happened in its own words, in every shipped language: the set
 * is the operation registry itself, so an operation registered without a sentence fails here, not in a feed that reads
 * "Jelle: Delete Shop".
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class OperationSentencesTest extends HohenheimTestBase {

    @Test
    void everyHohenheimOperationDeclaresASentenceEveryShippedLanguageSpeaks() {
        String namespace = HohenheimIds.id("operation").getNamespace();
        ShippedCatalogs catalogs = new ShippedCatalogs();
        List<String> missing = new ArrayList<>();
        int seen = 0;

        // 1. The registry is the set: every operation this product registered at boot.
        for (Identifier id : Operations.registry().ids()) {
            if (!namespace.equals(id.getNamespace())) {
                continue;
            }
            seen++;
            Operation<?, ?, ?> operation = Operations.require(id);
            Microcopy sentence = operation.happened();
            if (sentence == null) {
                missing.add(id + " declares no sentence");
                continue;
            }
            // 2. Each sentence resolves in every shipped language.
            for (String language : List.of("en", "nl")) {
                if (catalogs.resolveSource(sentence.key(), LocaleChain.ofTags(language), sentence.filters()) == null) {
                    missing.add(id + " has no " + language + " sentence");
                }
            }
        }

        assertThat(seen).as("step 1: the test sees Hohenheim's operations").isGreaterThanOrEqualTo(90);
        assertThat(missing).as("step 2: every operation tells what happened, in en and nl").isEmpty();
    }
}
