package be.elevenways.hohenheim.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Fetch-Metadata decision behind the managed-database backup GET guard: only a
 * {@code cross-site} request (an {@code <img src>}/cross-origin drive riding the victim's
 * session cookie) is refused, so it can never force a full dump + a false audit row. A real
 * click ({@code same-origin}/{@code same-site}), a top-level navigation ({@code none}) and a
 * header-less non-browser client all pass.
 *
 * AIDEV-NOTE: this verifies the guard DECISION as a pure function. A full HTTP integration
 * that drives the attack past auth into the dump could not be landed here: the guard sits
 * inside the handler behind {@code requiresLogin}, and neither Java's HttpClient (it drops the
 * restricted {@code Sec-*} header) nor a raw socket authenticated as the seeded admin in this
 * browser-test harness, while the audit-row-on-success half additionally needs a live engine
 * (the Docker-gated DatabaseServiceTest exercises the dump path itself). The wiring -- the
 * guard runs at the TOP of the DATABASES_BACKUP handler, before the existence check, the dump
 * and the ActivityLog.record -- is in HohenheimHandlers.initDatabases.
 */
class CrossSiteFetchGuardTest {

    @Test
    void onlyACrossSiteFetchIsRefused() {
        // The attack: a cross-site drive is the ONE value refused.
        assertThat(HohenheimHandlers.isCrossSiteFetch("cross-site"))
            .as("a cross-site drive is refused").isTrue();
        assertThat(HohenheimHandlers.isCrossSiteFetch("Cross-Site"))
            .as("the match is case-insensitive").isTrue();

        // The counterfactuals: every legitimate origin passes, so the guard does not simply
        // refuse everything (a vacuous guard would fail one of these).
        assertThat(HohenheimHandlers.isCrossSiteFetch("same-origin"))
            .as("a same-origin click passes").isFalse();
        assertThat(HohenheimHandlers.isCrossSiteFetch("same-site"))
            .as("a same-site click passes").isFalse();
        assertThat(HohenheimHandlers.isCrossSiteFetch("none"))
            .as("a top-level navigation passes").isFalse();
        assertThat(HohenheimHandlers.isCrossSiteFetch(null))
            .as("a header-less non-browser client (ddclient/curl) passes").isFalse();
    }
}
