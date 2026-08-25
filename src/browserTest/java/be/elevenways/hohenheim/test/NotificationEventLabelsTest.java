package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.zenit.microcopy.server.DefaultCatalogLoader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every notification event this build declares has copy behind its label, in en and nl.
 *
 * AIDEV-NOTE: the vocabulary is already DRY -- {@code NotificationEvents.ALL} and the
 * subscription picker both derive from the enum's members, so adding an event is one
 * edit. What NOTHING noticed was the second half of that edit: five of thirteen members
 * shipped with no catalog entry, so the picker offered "instance_crash_loop" beside
 * "Certificate expiring" and the channel list read the stored tokens back out raw. A
 * missing key renders as the key, which is invisible to the compiler and to every render
 * test -- this is the only thing that can fail the build over it.
 */
class NotificationEventLabelsTest {

    @Test
    void everyDeclaredEventHasCopyInBothLocales() {
        DefaultCatalogLoader catalogs = new DefaultCatalogLoader();
        List<String> missing = new ArrayList<>();

        // 1. The walk is over the ENUM, never over a list of tokens written here: a member
        //    added tomorrow is covered without anyone remembering this file.
        assertThat(NotificationEvents.values())
            .as("step 1: the vocabulary is not empty (an empty walk is vacuous)")
            .hasSizeGreaterThan(10);

        for (NotificationEvents event : NotificationEvents.values()) {
            for (String tag : List.of("en", "nl")) {
                String resolved = event.label().resolve(LocaleChain.ofTags(tag), catalogs);
                if (resolved.equals(event.label().key())) {
                    missing.add(tag + " " + event.token() + " -> '" + resolved + "'");
                }
            }
        }

        // 2. And the finding: a subscription checkbox never shows a stored token.
        assertThat(missing)
            .as("step 2: every notification event resolves to real copy in en AND nl")
            .isEmpty();
    }

    /**
     * The reverse lookup the channel list reads its subtext through: it answers for every
     * declared token and refuses anything else, so an unknown stored subscription can
     * never borrow another event's words.
     */
    @Test
    void tokensResolveBackToTheirOwnMemberAndNothingElse() {
        for (NotificationEvents event : NotificationEvents.values()) {
            assertThat(NotificationEvents.byToken(event.token()))
                .as("step 1: " + event.token() + " resolves back to its own member")
                .isSameAs(event);
        }
        assertThat(NotificationEvents.byToken("hohenheim_never_declared_this"))
            .as("step 2: an undeclared token has no member").isNull();
        assertThat(NotificationEvents.byToken(null))
            .as("step 3: and neither has a missing one").isNull();
    }
}
