package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.cms.DnsZoneSecondariesPage.Freshness;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the secondary freshness meanings to their typed badge looks.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class DnsZoneSecondariesPageTest {

    @Test
    void eachFreshnessStateHasItsSemanticBadgeVariant() {
        // 1. Every state declares its intended role, including the quiet unprobed state.
        for (Freshness freshness : Freshness.values()) {
            BadgeVariant expected = switch (freshness) {
                case UNPROBED -> BadgeVariant.SECONDARY;
                case CURRENT -> BadgeVariant.SUCCESS;
                case BEHIND -> BadgeVariant.WARNING;
                case STALE -> BadgeVariant.DESTRUCTIVE;
            };
            assertEquals(expected, freshness.variant(), "step 1: " + freshness + " keeps its semantic badge look");
        }
    }
}
