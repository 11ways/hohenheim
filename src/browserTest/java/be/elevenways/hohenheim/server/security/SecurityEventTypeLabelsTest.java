package be.elevenways.hohenheim.server.security;

import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
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
        ShippedCatalogs catalogs = new ShippedCatalogs();
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

        // 4. Every described type also says, in both locales, what tipped a ban it caused: the same keys as the labels.
        assertThat(HohenheimSecurity.EVENT_CAUSES.keySet())
            .as("step 4: every labelled type carries a ban cause").isEqualTo(labels.keySet());
        List<String> causeless = new ArrayList<>();
        for (Map.Entry<String, Microcopy> cause : HohenheimSecurity.EVENT_CAUSES.entrySet()) {
            for (String tag : List.of("en", "nl")) {
                String resolved = cause.getValue().withArg("count", 3).resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.equals(cause.getValue().key()) || !resolved.contains("3")) {
                    causeless.add(tag + " " + cause.getKey() + " -> '" + resolved + "'");
                }
            }
        }
        assertThat(causeless).as("step 4: every ban cause resolves in en AND nl and names its count").isEmpty();

        // 5. Every cause also reads without a count, in both locales, for the legacy rows that never recorded one.
        List<String> uncounted = new ArrayList<>();
        for (String type : HohenheimSecurity.EVENT_CAUSES.keySet()) {
            for (String tag : List.of("en", "nl")) {
                String resolved = HohenheimSecurity.legacyCause("score 26 over threshold", type)
                    .resolve(LocaleChain.ofTags(tag), catalogs);
                String counted = HohenheimSecurity.causeOf(type, 3).resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.isBlank() || resolved.contains("{") || resolved.matches(".*\\d.*")
                        || resolved.equals(counted)) {
                    uncounted.add(tag + " " + type + " -> '" + resolved + "'");
                }
            }
        }
        assertThat(uncounted).as("step 5: every ban cause reads without a count in en AND nl").isEmpty();
    }

    /**
     * A ban's reason is what tipped it in words: "Tried 40 names this server does not serve", never the
     * score only the scorer can read; a reason stored before that reads as its event instead.
     */
    @Test
    void aBanReasonSaysWhatTippedIt() {
        ShippedCatalogs catalogs = new ShippedCatalogs();

        // 1. A domain-miss sweep's cause counts the names, singular and plural, in both locales.
        assertThat(HohenheimSecurity.causeOf(SecurityEventTypes.DOMAIN_MISS, 40)
            .resolve(LocaleChain.ofTags("en"), catalogs))
            .as("step 1: the reason as a sentence").isEqualTo("Tried 40 names this server does not serve");
        assertThat(HohenheimSecurity.causeOf(SecurityEventTypes.DOMAIN_MISS, 1)
            .resolve(LocaleChain.ofTags("en"), catalogs))
            .as("step 1: one name reads singular").isEqualTo("Tried 1 name this server does not serve");
        assertThat(HohenheimSecurity.causeOf(SecurityEventTypes.DOMAIN_MISS, 40)
            .resolve(LocaleChain.ofTags("nl"), catalogs))
            .as("step 1: and in Dutch").isEqualTo("Vroeg naar 40 namen die deze server niet bedient");

        // 2. A type this application describes nowhere still reads as a sentence, naming its own spelling.
        assertThat(HohenheimSecurity.causeOf("ws.something_new", 5).resolve(LocaleChain.ofTags("en"), catalogs))
            .as("step 2: an undescribed type").isEqualTo("Set off 5 security events: ws.something_new");

        // 3. The legacy score line stored before this reads as its event in the current style, without the count it
        //    never recorded (it still read "Went over the limit for: Unmatched domain request"); any other reason is
        //    left alone, and nothing rewrites the stored row.
        Microcopy legacy = HohenheimSecurity.legacyCause("score 26 over threshold", SecurityEventTypes.DOMAIN_MISS);
        assertThat(legacy).as("step 3: the legacy line is recognised").isNotNull();
        assertThat(legacy.resolve(LocaleChain.ofTags("en"), catalogs))
            .as("step 3: and read as its event").isEqualTo("Tried names this server does not serve");
        assertThat(legacy.resolve(LocaleChain.ofTags("nl"), catalogs))
            .as("step 3: and in Dutch").isEqualTo("Vroeg naar namen die deze server niet bedient");
        assertThat(HohenheimSecurity.legacyCause("score 26 over threshold", "ws.something_new")
                .resolve(LocaleChain.ofTags("en"), catalogs))
            .as("step 3: an undescribed type names its own spelling").isEqualTo("Set off security events: ws.something_new");
        assertThat(HohenheimSecurity.legacyCause("Login attempts on /wp-admin", SecurityEventTypes.DOMAIN_MISS))
            .as("step 3: an operator's own reason is never rewritten").isNull();
    }
}
