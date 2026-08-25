package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every security event type an operator can be shown has a label, in en and nl.
 *
 * AIDEV-NOTE: the ban list renders the STORED dotted type ({@code proxy.domain_miss}),
 * which is machine data; the only thing that turns it into words is a description
 * registered against it. Two independent halves rot silently -- a core type nobody
 * described, and a described type whose key was never written -- and both render as
 * something an operator cannot act on. Both are checked here, off
 * {@link SecurityEventTypes#builtIns()} rather than off a list repeated in this file, so
 * a type added to core fails HERE instead of appearing raw in production.
 */
class SecurityEventTypeLabelsTest {

    @Test
    void everyCoreEventTypeIsDescribedInBothLocales() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        Map<String, Microcopy> labels = HohenheimSecurity.EVENT_LABELS;

        // 1. The vocabulary home is core's own declaring collection.
        assertThat(SecurityEventTypes.builtIns())
            .as("step 1: core declares a vocabulary to cover (an empty walk is vacuous)")
            .hasSizeGreaterThan(5);

        // 2. Every member of it is described by this application.
        assertThat(labels.keySet())
            .as("step 2: every core security event type carries a label")
            .containsAll(SecurityEventTypes.builtIns());

        // 3. And every label it names is real copy, not a raw key.
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Microcopy> label : labels.entrySet()) {
            for (String tag : List.of("en", "nl")) {
                String resolved = label.getValue().resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.equals(label.getValue().key())) {
                    missing.add(tag + " " + label.getKey() + " -> '" + resolved + "'");
                }
            }
        }
        assertThat(missing)
            .as("step 3: every described event type resolves in en AND nl")
            .isEmpty();
    }
}
