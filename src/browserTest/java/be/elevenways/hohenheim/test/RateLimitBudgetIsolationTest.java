package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.Zenit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rate-limit budget one test spends is never the next test's: the suite signs every class in as one admin, so the
 * principal-keyed buckets would otherwise carry over (CertificateRequestJourneyTest once met a 429 for a 422).
 *
 * AIDEV-NOTE: the methods run in order on purpose; the second proves the first's spent budget did not reach it.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitBudgetIsolationTest extends HohenheimHttpTestBase {

    private static Boolean letsEncryptWas;
    /** The refusal a certificate request answers while Let's Encrypt is off, with budget left. */
    private static int refusedWhileOff;

    @BeforeAll
    static void switchLetsEncryptOff() {
        letsEncryptWas = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED);
        // Off, the request is refused before any order, yet still spends the operation's budget at admission.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, false);
    }

    @AfterAll
    static void restoreLetsEncrypt() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Ssl.LETSENCRYPT_ENABLED, letsEncryptWas);
    }

    @Test
    @Order(1)
    void anEarlierTestSpendsTheAdminsCertificateBudget() throws Exception {
        // 1. With budget left, the request is refused for what it is: Let's Encrypt is off.
        refusedWhileOff = request().statusCode();
        assertThat(refusedWhileOff).as("step 1: refused as switched off, not as over budget")
            .isGreaterThanOrEqualTo(400).isNotEqualTo(429);

        // 2. The admin spends the rest of the budget, as earlier classes of a combined run do, and is refused 429.
        boolean limited = false;
        for (int i = 0; i < HohenheimEndpoints.LE_REQUEST_LIMIT.requests() && !limited; i++) {
            limited = request().statusCode() == 429;
        }
        assertThat(limited).as("step 2: the spent budget refuses the admin within this test").isTrue();
    }

    @Test
    @Order(2)
    void aLaterTestStartsWithABudgetOfItsOwn() throws Exception {
        // 3. THE ASSERTION: the same admin, one test later, is answered as with budget left, never 429.
        assertThat(request().statusCode()).as("step 3: the earlier test's spent budget does not reach this one")
            .isEqualTo(refusedWhileOff);
    }

    private HttpResponse<String> request() throws Exception {
        return adminPostForm(ApiSupport.requestCertificateTarget(), ApiSupport.form(
            "domains", "isolation.rate-limit.test", "nice_name", "rate-limit-isolation", "challenge_type", "http")
            + "&" + ApiSupport.invokeTransport());
    }
}
