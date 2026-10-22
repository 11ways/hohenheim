package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.NotificationChannelModel;
import be.elevenways.hohenheim.server.cms.NotificationChannelParts;
import be.elevenways.hohenheim.server.notification.NotificationEvents;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.notifications.server.DeliveryModel;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification-channel CRUD through the zenit-cms resource routes plus the
 * test-send row action.
 */
class NotificationAdminTest extends HohenheimTestBase {

    /** List + create form render, event subscriptions stay a closed vocabulary, and CRUD round trips. */
    @Test
    void channelListFormAndCrudJourney() throws Exception {
        navigateToApp("/admin/notifications");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Notification channels");

        // The channels stand under Settings (their list sits in its Notifications section): no tab strip of their
        // own, and the sidebar's one Settings row is the branch the page belongs to.
        BoundedAssertions.assertThat(page.locator("[data-cms-cluster-tabs]")).hasCount(0);
        BoundedAssertions.assertThat(
            page.locator("pl-app-sidebar pl-nav-item[href='/admin/configure'].active-branch")).hasCount(1);

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

        var create = adminPostForm("/admin/notifications/new",
            "name=ops-room&format=slack&url=https%3A%2F%2Fhooks.example%2Fold");
        assertThat(create.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("ops-room")).first();
        assertThat(row).isNotNull();
        assertThat((String) row.get(NotificationChannelModel.URL)).isEqualTo("https://hooks.example/old");
        assertThat((String) row.get(NotificationChannelModel.KIND)).isEqualTo("webhook");
        Integer id = row.get(NotificationChannelModel.ID);

        var update = adminPostEdit("/admin/notifications/" + id,
            "name=ops-room&format=discord&url=https%3A%2F%2Fhooks.example%2Fnew");
        assertThat(update.statusCode()).isIn(200, 302, 303);

        Row updated = Models.get(NotificationChannelModel.class).findById(id);
        assertThat((String) updated.get(NotificationChannelModel.URL)).isEqualTo("https://hooks.example/new");
        assertThat((String) updated.get(NotificationChannelModel.FORMAT)).isEqualTo("discord");

        // An unusable URL scheme fails the save: the resource rerenders the form with a violation.
        adminPostForm("/admin/notifications/new", "name=bad-hook&format=slack&url=ftp%3A%2F%2Fnope");
        Row bad = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("bad-hook")).first();
        assertThat(bad).isNull();
    }

    /** The test-send row action reports a delivery failure through the session flash. */
    @Test
    void testSendActionReportsDeliveryFailure() throws Exception {
        // A channel pointing at a port nothing listens on -> delivery must report failure.
        var create = adminPostForm("/admin/notifications/new",
            "name=dead-hook&format=generic&url=http%3A%2F%2F127.0.0.1%3A1%2Fhook");
        assertThat(create.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq("dead-hook")).first();
        assertThat(row).isNotNull();
        Integer id = row.get(NotificationChannelModel.ID);

        // The test send is the placed operation, posted to the one invoke route with the row as its subject.
        var test = adminPostForm(CmsRoutes.invoke(HohenheimSlugs.ADMIN, "notifications",
                NotificationChannelParts.TEST.id())
            .with(CmsEndpoints.SUBJECT_PARAM, String.valueOf(id)).toUrl(), "");
        assertThat(test.statusCode()).isIn(302, 303);
        // The failure toast rides the SESSION; the redirect hands it off to the load of
        // its own Location, so the test follows that Location to see it.
        assertThat(landingOf(test)).isEqualTo("/admin/notifications/" + id);

        navigateToApp(test.headers().firstValue("Location").orElseThrow());
        waitForHydration();
        String content = page.content();
        assertThat(content).contains("Test delivery failed");
        // THAT it failed is not feedback; the toast must name WHY. The reason rides
        // the flash as an arg, so a catalog entry without {$reason} silently drops it.
        assertThat(content)
            .as("the failure toast must name the transport's own reason")
            .containsPattern("Test delivery failed:\\s*\\S");

        // A test send is one-shot: the row it left behind is terminal, so the sweeper
        // never goes back to hammer a URL the operator was already told is dead.
        Row delivery = Models.get(DeliveryModel.class).find()
            .orderBy(DeliveryModel.ID, SortOrder.DESC).first();
        assertThat(delivery).as("the test send persisted a delivery row").isNotNull();
        assertThat((String) delivery.get(DeliveryModel.STATUS))
            .as("an inline test delivery must not stay queued for retry")
            .isEqualTo("failed");

        // The operator finds that failure without asking anyone: the delivery log in the system group lists the
        // row, failed.
        navigateToApp("/admin/deliveries");
        waitForHydration();
        String deliveryRow = page.locator("pl-table-row[data-row-key='" + delivery.get(DeliveryModel.ID) + "']")
            .innerText();
        assertThat(deliveryRow).as("the delivery log lists the failed test send").contains("failed");
    }

    /**
     * An incomplete channel is refused field by field: every missing REQUIRED field is
     * named, and the URL shape rule only speaks about a URL that was actually typed.
     */
    @Test
    void incompleteChannelSubmitNamesEveryMissingFieldJourney() throws Exception {
        // 1. A wholly empty submit: all three required fields are named at once, and the
        //    shape rule stays quiet about a box nobody filled in.
        var empty = adminPostForm("/admin/notifications/new", "name=&format=&url=");
        assertThat(empty.statusCode()).as("step 1: the form rerenders").isEqualTo(200);
        // The refusal speaks the field's LABEL, never its key.
        assertThat(empty.body()).as("step 1: name is required").contains("Name is required");
        assertThat(empty.body()).as("step 1: the raw key never prints").doesNotContain("name is required");
        assertThat(empty.body()).as("step 1: format is required").containsIgnoringCase("format is required");
        assertThat(empty.body()).as("step 1: url is required").containsIgnoringCase("url is required");
        assertThat(empty.body()).as("step 1: no format rule for an empty url")
            .doesNotContain("must start with http");

        // 2. Only the url left blank: still a required refusal, never the shape rule.
        var blankUrl = adminPostForm("/admin/notifications/new", "name=half-filled&format=slack&url=");
        assertThat(blankUrl.body()).as("step 2: url is required").containsIgnoringCase("url is required");
        assertThat(blankUrl.body()).as("step 2: no shape rule for a blank url")
            .doesNotContain("must start with http");
        assertThat(channelNamed("half-filled")).as("step 2: nothing persisted").isNull();

        // 3. A url that IS filled in but unusable: now the shape rule is the right answer.
        var garbage = adminPostForm("/admin/notifications/new", "name=half-filled&format=slack&url=nonsense");
        assertThat(garbage.body()).as("step 3: the shape rule speaks").contains("must start with http");
        assertThat(garbage.body()).as("step 3: not a required refusal")
            .doesNotContain("url is required");
        assertThat(channelNamed("half-filled")).as("step 3: nothing persisted").isNull();

        // 4. Everything present: the same form now saves.
        var complete = adminPostForm("/admin/notifications/new",
            "name=half-filled&format=slack&url=https%3A%2F%2Fhooks.example%2Fok");
        assertThat(complete.statusCode()).as("step 4: saved").isIn(200, 302, 303);
        assertThat(channelNamed("half-filled")).as("step 4: persisted").isNotNull();
    }

    /** An empty events subscription means "every event", and the form says so. */
    @Test
    void eventsPickerExplainsThatEmptyMeansEveryEvent() throws Exception {
        navigateToApp("/admin/notifications/new");
        waitForHydration();
        assertThat(page.content()).contains("Leave empty to receive every event");
    }

    /**
     * Which events reach which channel, for every channel at once: the grid opens on what each channel receives, a
     * box unticked there stops that event, and the list names what a channel skips.
     */
    @Test
    void theEventGridRoutesEveryChannelAtOnceJourney() throws Exception {
        Models.get(NotificationChannelModel.class).find().delete();
        adminPostForm("/admin/notifications/new", "name=grid-ops&format=slack&url=https%3A%2F%2Fhooks.example%2Fops");
        adminPostForm("/admin/notifications/new", "name=grid-pager&format=discord&url=https%3A%2F%2Fhooks.example%2Fp");
        Row pager = channelNamed("grid-pager");
        assertThat(pager).as("setup: both channels exist").isNotNull();
        assertThat(channelNamed("grid-ops")).isNotNull();

        // 1. The header's menu opens the grid in a sheet, every box ticked: an empty subscription is every event.
        navigateToApp("/admin/notifications");
        waitForHydration();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("More actions")).first().click();
        page.getByRole(AriaRole.MENUITEM, new Page.GetByRoleOptions().setName("Which events go where")).click();
        Locator sheet = page.getByRole(AriaRole.DIALOG, new Page.GetByRoleOptions().setName("Which events go where"));
        BoundedAssertions.assertThat(sheet).isVisible();
        Locator backupToPager = sheet.getByRole(AriaRole.CHECKBOX,
            new Locator.GetByRoleOptions().setName("Backup failed: grid-pager"));
        BoundedAssertions.assertThat(backupToPager).isChecked();
        assertThat(sheet.getByRole(AriaRole.CHECKBOX).count())
            .as("step 1: one box per event per channel").isEqualTo(NotificationEvents.values().length * 2);

        // 2. Unticking one box and saving stops exactly that event for that channel. The save answers by refreshing
        //    the list it was opened over: the step waits for that page, never for the database alone.
        backupToPager.click();
        waitForNavigationRendered(() -> sheet.getByRole(AriaRole.BUTTON,
            new Locator.GetByRoleOptions().setName("Save")).click());
        BoundedAssertions.assertThat(sheet).isHidden();
        List<String> pagerEvents = Models.get(NotificationChannelModel.class)
            .findById(pager.get(NotificationChannelModel.ID)).get(NotificationChannelModel.EVENTS);
        assertThat(pagerEvents).as("step 2: the pager keeps every other event")
            .hasSize(NotificationEvents.values().length - 1)
            .doesNotContain(NotificationEvents.BACKUP_FAILED.token());
        List<String> opsEvents = channelNamed("grid-ops").get(NotificationChannelModel.EVENTS);
        assertThat(opsEvents == null ? List.<String>of() : opsEvents)
            .as("step 2: a channel left fully ticked keeps receiving everything, stored as no subscription").isEmpty();

        // 3. The refreshed list names what the pager skips instead of the sixteen events it keeps.
        assertThat(page.content()).as("step 3: the summary names the skipped event")
            .contains("Every event except Backup failed");
    }

    private Row channelNamed(String name) {
        return Models.get(NotificationChannelModel.class).find()
            .where(NotificationChannelModel.NAME.eq(name)).first();
    }
}
