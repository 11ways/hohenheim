package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.SecurityEventModel;
import be.elevenways.hohenheim.model.SecurityReporterModel;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.server.security.ReporterBanBudget;
import be.elevenways.hohenheim.server.security.SecurityReporters;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The /zn/security/ingest endpoint over real HTTP: token auth, aggregation
 * upserts, malformed-body refusal, batch caps, the scorer feed (remote events
 * count toward auto-bans), and the last_seen throttle.
 */
class SecurityIngestTest extends HohenheimTestBase {

    private HttpResponse<String> postIngest(String token, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + "/zn/security/ingest"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String eventJson(String type, String ip, int count) {
        long now = System.currentTimeMillis();
        return "{\"type\":\"" + type + "\",\"ip\":\"" + ip + "\",\"count\":" + count
            + ",\"first_at\":" + (now - 1000) + ",\"last_at\":" + now
            + ",\"detail\":{\"path\":\"/login\"}}";
    }

    @Test
    void happyPathAggregatesAndAcknowledges() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-a", null, true);
        assertThat(minted.raw()).startsWith("zsec_");

        HttpResponse<String> first = postIngest(minted.raw(),
            "{\"events\":[" + eventJson("auth.login_failed", "203.0.113.100", 3) + "]}");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"accepted\":1");

        // Same (type, ip, day): the SECOND post must increment, not add a row.
        HttpResponse<String> second = postIngest(minted.raw(),
            "{\"events\":[" + eventJson("auth.login_failed", "203.0.113.100", 2) + "]}");
        assertThat(second.statusCode()).isEqualTo(200);

        var events = Models.get(SecurityEventModel.class).find()
            .where(SecurityEventModel.REPORTER_ID.eq(minted.reporterId()))
            .where(SecurityEventModel.IP.eq("203.0.113.100"))
            .all();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get(SecurityEventModel.COUNT)).isEqualTo(5);
        assertThat(events.get(0).get(SecurityEventModel.TYPE)).isEqualTo("auth.login_failed");
        assertThat(events.get(0).get(SecurityEventModel.LAST_DETAIL)).contains("/login");
    }

    @Test
    void missingOrInvalidTokenIs401() throws Exception {
        HttpResponse<String> missing = postIngest(null, "{\"events\":[]}");
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.body()).contains("invalid_token");

        HttpResponse<String> wrong = postIngest("zsec_definitely-wrong", "{\"events\":[]}");
        assertThat(wrong.statusCode()).isEqualTo(401);
    }

    @Test
    void disabledReporterIs401() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-off", null, false);
        HttpResponse<String> response = postIngest(minted.raw(), "{\"events\":[]}");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void malformedBodiesAre422() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-b", null, true);

        for (String bad : new String[] {
                "",                                            // empty
                "not json",                                    // garbage
                "{\"nope\":true}",                             // no events
                "{\"events\":[{\"ip\":\"1.2.3.4\"}]}",         // missing type
                "{\"events\":[{\"type\":\"x\"}]}",             // missing ip
                "{\"events\":[{\"type\":\"x\",\"ip\":\"1.2.3.4\",\"count\":0}]}",  // count < 1
                "{\"events\":[{\"type\":\"x\",\"ip\":\"1.2.3.4\",\"detail\":\"str\"}]}",  // detail not a map
                "{\"events\":[42]}"}) {                        // event not an object
            HttpResponse<String> response = postIngest(minted.raw(), bad);
            assertThat(response.statusCode()).as("body: %s", bad).isEqualTo(422);
        }
    }

    @Test
    void oversizedBatchesAre422() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-c", null, true);
        StringBuilder body = new StringBuilder("{\"events\":[");
        for (int i = 0; i < 501; i++) {
            if (i > 0) body.append(',');
            body.append("{\"type\":\"t\",\"ip\":\"203.0.113.1\"}");
        }
        body.append("]}");
        HttpResponse<String> response = postIngest(minted.raw(), body.toString());
        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void remoteEventsFeedTheScorerAndTriggerAutoBans() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-d", null, true);

        // ONE event caps at 10 points regardless of its claimed count: a
        // poisoned single event can never insta-ban.
        HttpResponse<String> first = postIngest(minted.raw(),
            "{\"events\":[" + eventJson("auth.lockout", "203.0.113.200", 1_000_000) + "]}");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(HohenheimSecurity.scorer().isOverThreshold("203.0.113.200")).isFalse();
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.200")).count()).isZero();

        // Accumulation across events crosses the threshold (10+10+10 > 25).
        postIngest(minted.raw(),
            "{\"events\":[" + eventJson("auth.lockout", "203.0.113.200", 3) + "]}");
        postIngest(minted.raw(),
            "{\"events\":[" + eventJson("auth.lockout", "203.0.113.200", 3) + "]}");

        assertThat(HohenheimSecurity.scorer().isOverThreshold("203.0.113.200")).isTrue();
        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.200"))
            .where(BanModel.ACTIVE.eq(true))
            .first();
        assertThat(ban).isNotNull();
        assertThat(ban.get(BanModel.SOURCE)).isEqualTo(BanModel.SOURCE_AUTO);
        assertThat(ban.get(BanModel.EVENT_TYPE)).isEqualTo("auth.lockout");
        // Poisoning incidents stay auditable: the reason names the reporter.
        assertThat(ban.get(BanModel.REASON)).contains("reporter app-d");
    }

    @Test
    void nonLiteralIpsAreStoredButNeverScoredOrBanned() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-f", null, true);

        HttpResponse<String> response = postIngest(minted.raw(),
            "{\"events\":[" + eventJson("auth.lockout", "local", 1_000_000) + ","
                + eventJson("auth.lockout", "evil.example.com", 1_000_000) + "]}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"accepted\":2");

        // Stored for analytics...
        var events = Models.get(SecurityEventModel.class).find()
            .where(SecurityEventModel.REPORTER_ID.eq(minted.reporterId()))
            .where(SecurityEventModel.IP.eq("local"))
            .all();
        assertThat(events).hasSize(1);

        // ...but never fed to the scorer and never bannable.
        assertThat(HohenheimSecurity.scorer().isOverThreshold("local")).isFalse();
        assertThat(HohenheimSecurity.scorer().isOverThreshold("evil.example.com")).isFalse();
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("local")).count()).isZero();
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("evil.example.com")).count()).isZero();
    }

    @Test
    void oversizedBodiesAre413BeforeParsing() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-g", null, true);

        // > 256KB of (even valid-looking) JSON must refuse before fromJson.
        StringBuilder body = new StringBuilder("{\"events\":[],\"padding\":\"");
        body.append("x".repeat(300 * 1024));
        body.append("\"}");
        HttpResponse<String> response = postIngest(minted.raw(), body.toString());
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("payload_too_large");
    }

    @Test
    void reporterAutoBanBudgetSuppressesTheTwentyFirstBan() throws Exception {
        ReporterBanBudget.resetForTests();
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-h", null, true);

        // Each target IP needs 3 capped events (10 points each) to cross 25.
        for (int i = 0; i < 21; i++) {
            String ip = "192.0.2." + (i + 1);
            for (int hit = 0; hit < 3; hit++) {
                assertThat(postIngest(minted.raw(),
                    "{\"events\":[" + eventJson("auth.lockout", ip, 3) + "]}").statusCode())
                    .isEqualTo(200);
            }
        }

        // The first 20 IPs got auto-banned; the 21st was suppressed by the budget.
        for (int i = 0; i < 20; i++) {
            assertThat(Models.get(BanModel.class).find()
                .where(BanModel.IP.eq("192.0.2." + (i + 1)))
                .where(BanModel.ACTIVE.eq(true))
                .count()).as("ip 192.0.2.%s", i + 1).isEqualTo(1);
        }
        assertThat(HohenheimSecurity.scorer().isOverThreshold("192.0.2.21")).isTrue();
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.0.2.21")).count()).isZero();

        ReporterBanBudget.resetForTests();
    }

    @Test
    void lastSeenWritesAreThrottled() throws Exception {
        SecurityReporters.MintedToken minted = SecurityReporters.create("app-e", null, true);

        postIngest(minted.raw(), "{\"events\":[]}").statusCode();
        Row reporter = Models.get(SecurityReporterModel.class).findById(minted.reporterId());
        Instant firstSeen = reporter.get(SecurityReporterModel.LAST_SEEN_AT);
        assertThat(firstSeen).isNotNull();

        // A second request inside the 60s throttle must not rewrite last_seen.
        postIngest(minted.raw(), "{\"events\":[]}").statusCode();
        Row again = Models.get(SecurityReporterModel.class).findById(minted.reporterId());
        assertThat(again.get(SecurityReporterModel.LAST_SEEN_AT)).isEqualTo(firstSeen);
    }
}
