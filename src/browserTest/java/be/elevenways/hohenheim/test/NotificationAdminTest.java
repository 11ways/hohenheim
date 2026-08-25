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

    /** List + create form render, event subscriptions stay a closed vocabulary, and CRUD round trips. */
    @Test
    @Order(1)
    void channelListFormAndCrudJourney() throws Exception {
        navigateToApp("/admin/notifications");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Notification channels");

        // The shell sidebar carries the notifications entry.
        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/notifications']")).hasCount(1);

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

        // An unusable URL scheme fails the save: the resource rerenders the form with a violation.
        postForm("/admin/notifications/new", "name=bad-hook&format=slack&url=ftp%3A%2F%2Fnope");
        Row bad = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("bad-hook")).first();
        assertThat(bad).isNull();
    }

    /** The test-send row action reports a delivery failure through the session flash. */
    @Test
    @Order(2)
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

    /**
     * An incomplete channel is refused field by field: every missing REQUIRED field is
     * named, and the URL shape rule only speaks about a URL that was actually typed.
     */
    @Test
    @Order(3)
    void incompleteChannelSubmitNamesEveryMissingFieldJourney() throws Exception {
        // 1. A wholly empty submit: all three required fields are named at once, and the
        //    shape rule stays quiet about a box nobody filled in.
        var empty = postForm("/admin/notifications/new", "name=&format=&url=");
        assertThat(empty.statusCode()).as("step 1: the form rerenders").isEqualTo(200);
        assertThat(empty.body()).as("step 1: name is required").contains("name is required");
        assertThat(empty.body()).as("step 1: format is required").contains("format is required");
        assertThat(empty.body()).as("step 1: url is required").contains("url is required");
        assertThat(empty.body()).as("step 1: no format rule for an empty url")
            .doesNotContain("must start with http");

        // 2. Only the url left blank: still a required refusal, never the shape rule.
        var blankUrl = postForm("/admin/notifications/new", "name=half-filled&format=slack&url=");
        assertThat(blankUrl.body()).as("step 2: url is required").contains("url is required");
        assertThat(blankUrl.body()).as("step 2: no shape rule for a blank url")
            .doesNotContain("must start with http");
        assertThat(channelNamed("half-filled")).as("step 2: nothing persisted").isNull();

        // 3. A url that IS filled in but unusable: now the shape rule is the right answer.
        var garbage = postForm("/admin/notifications/new", "name=half-filled&format=slack&url=nonsense");
        assertThat(garbage.body()).as("step 3: the shape rule speaks").contains("must start with http");
        assertThat(garbage.body()).as("step 3: not a required refusal")
            .doesNotContain("url is required");
        assertThat(channelNamed("half-filled")).as("step 3: nothing persisted").isNull();

        // 4. Everything present: the same form now saves.
        var complete = postForm("/admin/notifications/new",
            "name=half-filled&format=slack&url=https%3A%2F%2Fhooks.example%2Fok");
        assertThat(complete.statusCode()).as("step 4: saved").isIn(200, 302, 303);
        assertThat(channelNamed("half-filled")).as("step 4: persisted").isNotNull();
    }

    /** An empty events subscription means "every event", and the form says so. */
    @Test
    @Order(4)
    void eventsPickerExplainsThatEmptyMeansEveryEvent() throws Exception {
        navigateToApp("/admin/notifications/new");
        waitForHydration();
        assertThat(page.content()).contains("Leave empty to receive every event");
    }

    private Row channelNamed(String name) {
        return Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq(name)).first();
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
