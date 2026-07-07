package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The site History tab (framework activity feed): CMS edits show up as
 * attributed entries with field-level deltas (sites run the ALL policy), and
 * a revision-linked entry restores the earlier snapshot from the feed.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SiteHistoryTest extends HohenheimTestBase {

    private static final String SITE_FORM =
        "site_type=hohenheim%3Aproxy&source=local"
        + "&settings.forward_host=127.0.0.1&settings.forward_port=9090";

    private static Integer siteId;

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

    @Test
    @Order(1)
    void cmsEditsAppearAsFeedEntriesWithDeltas() throws Exception {
        var created = postForm("/admin/sites/new", "name=History+Site&" + SITE_FORM);
        assertThat(created.statusCode()).isIn(200, 302, 303);

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.NAME.eq("History Site")).first();
        assertThat(site).isNotNull();
        siteId = site.get(SiteModel.ID);

        var updated = postForm("/admin/sites/" + siteId, "name=Renamed+Site&" + SITE_FORM);
        assertThat(updated.statusCode()).isIn(200, 302, 303);

        navigateToApp("/admin/sites/" + siteId + "/page/history");
        waitForHydration();
        waitForSelector(".cms-record-history-page");

        // Create + update, both revision-linked (sites are Revisionable).
        assertThat(page.locator("pl-timeline-item[data-activity-action='create']").count()).isEqualTo(1);
        assertThat(page.locator("pl-timeline-item[data-activity-action='update']").count()).isEqualTo(1);
        assertThat(page.locator(
            "pl-timeline-item[data-activity-action='update'] pl-badge[data-activity-revision='2']").count())
            .isEqualTo(1);

        // Expanding the update entry reveals the name delta (ALL policy).
        click("pl-timeline-item[data-activity-action='update'] pl-collapsible-trigger button");
        waitForSelector("pl-timeline-item[data-activity-action='update'] pl-table-row[data-diff-field='name']");
        assertThat(page.locator(
                "pl-timeline-item[data-activity-action='update'] pl-table-row[data-diff-field='name'] .cms-diff-before")
            .textContent()).contains("History Site");
        assertThat(page.locator(
                "pl-timeline-item[data-activity-action='update'] pl-table-row[data-diff-field='name'] .cms-diff-after")
            .textContent()).contains("Renamed Site");
    }

    @Test
    @Order(2)
    void restoreFromTheFeedBringsTheOldNameBack() {
        navigateToApp("/admin/sites/" + siteId + "/page/history");
        waitForHydration();

        click("pl-timeline-item[data-activity-action='create'] pl-button[data-cms-restore-revision='1']");
        waitForSelector("pl-button[data-cms-confirm-ok]");
        click("pl-button[data-cms-confirm-ok]");

        waitForSelector(".cms-record-history-page");
        waitForSelector("pl-timeline-item[data-activity-action='restore']");

        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.ID.eq(siteId)).first();
        assertThat(site).isNotNull();
        assertThat((String) site.get(SiteModel.NAME)).isEqualTo("History Site");
    }
}
