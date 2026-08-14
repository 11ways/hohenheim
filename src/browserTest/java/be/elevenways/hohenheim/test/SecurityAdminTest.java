package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Security admin surfaces: ban list + manual-ban form (with private-IP
 * refusal) + lift action, and the dashboard's security band (active-bans stat
 * plus the bans-created chart over the ban model).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityAdminTest extends HohenheimTestBase {

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Manual ban creation with its refusals, the ban list, and the lift row action. */
    @Test
    @Order(1)
    void banAdminJourney() throws Exception {
        var response = postForm("/admin/bans/new",
            "ip=203.0.113.77&reason=scanner&duration=7d");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row created = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.77")).first();
        assertThat(created).isNotNull();
        assertThat(created.get(BanModel.ACTIVE)).isTrue();
        assertThat(created.get(BanModel.SOURCE)).isEqualTo(BanModel.SOURCE_MANUAL);
        assertThat(created.get(BanModel.REASON)).isEqualTo("scanner");
        assertThat(created.get(BanModel.EXPIRES_AT)).isAfter(Instant.now().plusSeconds(6 * 86400));

        var privateIp = postForm("/admin/bans/new", "ip=192.168.1.1&duration=24h");
        // Validation failure re-renders the form (no redirect) and creates nothing.
        assertThat(Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("192.168.1.1")).count()).isZero();
        assertThat(privateIp.statusCode()).isEqualTo(200);

        HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN,
            List.of("203.0.113.66", "198.51.100.0/24"));
        try {
            for (String ip : new String[] {"203.0.113.66", "198.51.100.9"}) {
                var allowlisted = postForm("/admin/bans/new", "ip=" + ip + "&duration=24h");
                // Validation failure re-renders the form and creates nothing.
                assertThat(allowlisted.statusCode()).isEqualTo(200);
                assertThat(Models.get(BanModel.class).find()
                    .where(BanModel.IP.eq(ip)).count()).as("ip %s", ip).isZero();
            }
        } finally {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of());
        }

        navigateToApp("/admin/bans");
        waitForHydration();
        String content = page.locator("body").textContent();
        assertThat(content).contains("203.0.113.77");
        assertThat(content).contains("manual");
        assertThat(content).contains("Lift ban");

        Row ban = Models.get(BanModel.class).find()
            .where(BanModel.IP.eq("203.0.113.77")).first();
        var lift = postForm("/admin/bans/" + ban.get(BanModel.ID) + "/action/lift_ban", "");
        assertThat(lift.statusCode()).isIn(200, 302, 303);

        Row lifted = Models.get(BanModel.class).findById(ban.get(BanModel.ID));
        assertThat(lifted.get(BanModel.ACTIVE)).isFalse();
        assertThat(lifted.get(BanModel.LIFTED_BY)).isNotNull();
    }

    /** The dashboard's security band: the active-bans stat and the bans-created chart. */
    @Test
    @Order(2)
    void dashboardShowsTheBanStatAndBansChart() {
        navigateToApp("/admin/dashboard");
        waitForHydration();
        assertThat(page.locator("a.widget-stat-link[href='/admin/bans']").count()).isEqualTo(1);
        // The deleted security-events surface is gone from the dashboard.
        assertThat(page.locator("a.widget-stat-link[href='/admin/security-events']").count())
            .isZero();
        // The bans-created chart renders over the hohenheim.ban source.
        assertThat(page.locator(".widget-chart pl-chart").count()).isGreaterThanOrEqualTo(1);
    }
}
