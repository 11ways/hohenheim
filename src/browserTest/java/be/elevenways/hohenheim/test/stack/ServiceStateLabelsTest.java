package be.elevenways.hohenheim.test.stack;

import be.elevenways.hohenheim.server.stack.ServiceState;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every live state a stack service can be in has copy behind its label, in en and nl.
 *
 * AIDEV-NOTE: the states were strings spelled in three places, and "unreachable" had no {@code stack_state} copy, so
 * a service on a silent host read as its raw key. The walk is over the enum, so a state added tomorrow is covered.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class ServiceStateLabelsTest {

    @Test
    void everyServiceStateHasCopyInBothLocales() {
        ShippedCatalogs catalogs = new ShippedCatalogs();
        List<String> missing = new ArrayList<>();

        // 1. Every member, in both shipped locales.
        for (ServiceState state : ServiceState.values()) {
            for (String tag : List.of("en", "nl")) {
                String resolved = state.label().resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.equals(state.label().key())) {
                    missing.add(tag + " " + state.token());
                }
            }
        }

        // 2. The finding: the services page never shows a raw state token.
        assertThat(missing).as("step 2: every service state resolves to real copy in en AND nl").isEmpty();
    }
}
