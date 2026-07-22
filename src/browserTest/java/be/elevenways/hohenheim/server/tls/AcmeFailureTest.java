package be.elevenways.hohenheim.server.tls;

import org.junit.jupiter.api.Test;
import org.shredzone.acme4j.Login;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Problem;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AcmeFailureTest {

    @Test
    void failureReasonIncludesNestedTransportDiagnostic() {
        Exception failure = new Exception("ACME order failed",
            new Exception("Network error",
                new IOException("unable to find valid certification path")));

        assertThat(AcmeService.failureReason(failure))
            .isEqualTo("ACME order failed: Network error: unable to find valid certification path");
    }

    @Test
    void invalidDnsAndHttpChallengesIncludeAcmeProblemDetails() throws Exception {
        Map<String, Object> error = Map.of(
            "type", "urn:ietf:params:acme:error:dns",
            "title", "DNS problem",
            "detail", "TXT record did not propagate");
        Login login = login();
        Challenge[] challenges = {
            new Dns01Challenge(login, failedChallengeJson("dns-01", "1", error)),
            new Http01Challenge(login, failedChallengeJson("http-01", "2", error))
        };

        for (Challenge challenge : challenges) {
            assertThat(AcmeService.challengeFailure("example.test", challenge).getMessage())
                .contains("Challenge failed for example.test")
                .contains("type=urn:ietf:params:acme:error:dns")
                .contains("title=DNS problem")
                .contains("detail=TXT record did not propagate");
        }
    }

    @Test
    void rejectedOrderIncludesAcmeProblemDetails() throws Exception {
        Problem problem = problem("urn:ietf:params:acme:error:rejectedIdentifier",
            "Rejected identifier", "CAA policy forbids issuance");
        Order order = new FailedOrder(problem);

        assertThat(AcmeService.orderFailure(order).getMessage())
            .contains("Order rejected by CA")
            .contains("type=urn:ietf:params:acme:error:rejectedIdentifier")
            .contains("title=Rejected identifier")
            .contains("detail=CAA policy forbids issuance");
    }

    private static Problem problem(String type, String title, String detail) throws Exception {
        return new Problem(JSON.fromMap(Map.of(
            "type", type,
            "title", title,
            "detail", detail)), URI.create("https://acme.test/").toURL());
    }

    private static JSON failedChallengeJson(String type, String id, Map<String, Object> error) {
        return JSON.fromMap(Map.of(
            "type", type,
            "url", "https://acme.test/challenge/" + id,
            "status", "invalid",
            "token", "challenge-token-" + id,
            "error", error));
    }

    private static Login login() throws Exception {
        Session session = new Session("acme://letsencrypt.org/staging");
        return session.login(URI.create("https://acme.test/account/1").toURL(),
            KeyPairUtils.createKeyPair(2048));
    }

    private static final class FailedOrder extends Order {
        private final Problem problem;

        private FailedOrder(Problem problem) throws Exception {
            super(login(), URI.create("https://acme.test/order/1").toURL());
            this.problem = problem;
        }

        @Override public Optional<Problem> getError() { return Optional.of(problem); }
    }
}
