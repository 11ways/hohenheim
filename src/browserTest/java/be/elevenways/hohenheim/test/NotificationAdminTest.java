package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification-channel CRUD through the zenit-cms resource routes plus the
 * test-send row action.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationAdminTest extends HohenheimTestBase {

    @Test
    @Order(1)
    void notificationsListRendersEmptyState() {
        navigateToApp("/admin/notifications");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Notification Channels");
    }

    @Test
    @Order(2)
    void sidebarLinksToNotifications() {
        navigateToApp("/admin");
        waitForHydration();

        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/notifications']")).hasCount(1);
    }

    @Test
    @Order(2)
    void eventSubscriptionsAreClosedChoices() {
        navigateToApp("/admin/notifications/new");
        waitForHydration();

        var events = page.locator("pl-select[name='events']");
        assertThat(events.count()).isEqualTo(1);
        assertThat(events.getAttribute("tags")).isNull();

        // Item children portal into the overlay popup at hydration, so the
        // closed vocabulary is counted inside the open popup.
        page.click("pl-select[name='events'] .pl-select-field");
        page.waitForSelector("he-bottom .pl-select-popup[data-open]");
        assertThat(page.locator(
            "he-bottom .pl-select-popup[data-open] div[role='option']").count())
            .isEqualTo(NotificationEvents.ALL.size());
        page.keyboard().press("Escape");
    }

    @Test
    @Order(3)
    void channelCreateAndEditRoundTrips() throws Exception {
        var create = postForm("/admin/notifications/new",
            "name=ops-room&format=slack&url=https%3A%2F%2Fhooks.example%2Fold");
        assertThat(create.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("ops-room")).first();
        assertThat(row).isNotNull();
        assertThat((String) row.get(NotificationChannelModel.URL)).isEqualTo("https://hooks.example/old");
        assertThat((String) row.get(NotificationChannelModel.KIND)).isEqualTo("webhook");
        Integer id = row.get(NotificationChannelModel.ID);

        var update = postForm("/admin/notifications/" + id,
            "name=ops-room&format=discord&url=https%3A%2F%2Fhooks.example%2Fnew");
        assertThat(update.statusCode()).isIn(200, 302, 303);

        Row updated = Models.get(NotificationChannelModel.class).findById(id);
        assertThat((String) updated.get(NotificationChannelModel.URL)).isEqualTo("https://hooks.example/new");
        assertThat((String) updated.get(NotificationChannelModel.FORMAT)).isEqualTo("discord");
    }

    @Test
    @Order(4)
    void invalidUrlIsRejected() throws Exception {
        var create = postForm("/admin/notifications/new",
            "name=bad-hook&format=slack&url=ftp%3A%2F%2Fnope");
        // Save fails: the resource rerenders the form with a violation.
        Row row = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("bad-hook")).first();
        assertThat(row).isNull();
    }

    @Test
    @Order(5)
    void testSendActionReportsDeliveryFailure() throws Exception {
        // A channel pointing at a port nothing listens on -> delivery must report failure.
        var create = postForm("/admin/notifications/new",
            "name=dead-hook&format=generic&url=http%3A%2F%2F127.0.0.1%3A1%2Fhook");
        assertThat(create.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("dead-hook")).first();
        assertThat(row).isNotNull();
        Integer id = row.get(NotificationChannelModel.ID);

        var test = postForm("/admin/notifications/" + id + "/action/test_channel", "");
        assertThat(test.statusCode()).isIn(200, 302, 303);
        // The failure toast rides the SESSION (popped on the next render); the
        // redirect URL stays clean.
        String location = test.headers().firstValue("Location").orElse("");
        assertThat(location).doesNotContain("_flash=");

        navigateToApp("/admin/notifications/" + id);
        waitForHydration();
        assertThat(page.content()).contains("Test delivery failed");
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
