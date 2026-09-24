package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Full site lifecycle through the zenit-cms resource routes: create with
 * type-discriminated settings, nested env-var/api-key transports, git source
 * settings with webhook-secret generation, relation picks, and soft delete.
 */
class SiteLifecycleTest extends HohenheimTestBase {

    private Row site(String name) {
        return Models.get(SiteModel.class).find().where(SiteModel.NAME.eq(name)).first();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> settingsOf(String name) {
        Row row = site(name);
        assertThat(row).isNotNull();
        Object settings = row.get(SiteModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** Creating and editing an upstream-discriminated shape: the address upstream. */
    @Test
    void addressSiteCreatesAndEdits() throws Exception {
        var response = adminPostForm("/admin/sites/new",
            "name=Test+Backend&upstream_kind=hohenheim%3Aaddress"
            + "&settings.forward_host=127.0.0.1&settings.forward_port=8080");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Map<String, Object> proxySettings = settingsOf("Test Backend");
        assertThat(proxySettings.get("forward_host")).isEqualTo("127.0.0.1");
        assertThat(String.valueOf(proxySettings.get("forward_port"))).isEqualTo("8080");

        Row proxyRow = site("Test Backend");
        navigateToApp("/admin/sites/" + proxyRow.get(SiteModel.ID));
        waitForHydration();

        assertThat(page.locator("form").count()).isGreaterThan(0);
        assertThat(page.content()).contains("Test Backend");
        assertThat(page.content()).contains("127.0.0.1");

        // AIDEV-NOTE: the node half of this journey is gone with the host-user process
        // lane (phase-0 design section 3 deleted every site type that ran a workload). Its
        // secret-map and api-key coverage belonged to that lane; the settings-map contract
        // it also touched is pinned by PartialWriteContractTest and EnvironmentSecretsTest.
    }

    /** Redirect and git-sourced creation, the access-list relation pick, the row actions and soft delete. */
    @Test
    void redirectAndGitSitesThroughActionsToDeletion() throws Exception {
        var response = adminPostForm("/admin/sites/new",
            "name=Old+Domain&upstream_kind=hohenheim%3Aredirect"
            + "&settings.target_url=https%3A%2F%2Fexample.com&settings.http_status=301");
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat(settingsOf("Old Domain").get("target_url")).isEqualTo("https://example.com");

        // AIDEV-NOTE: the git-source half of this journey moved off the site with the
        // upstream rename (phase-0 design section 3): a repository is a property of the
        // application instance a site exposes, not of the site. The create form no longer
        // accepts source keys at all, which is what this asserts instead.
        response = adminPostForm("/admin/sites/new",
            "name=Git+App&upstream_kind=hohenheim%3Astatic&source=git"
            + "&settings.root_path=%2Fvar%2Fwww%2Fgitapp"
            + "&source_settings.repository_url=https%3A%2F%2Fexample.com%2Frepo.git");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row gitRow = site("Git App");
        assertThat(gitRow).isNotNull();
        assertThat(gitRow.has("source"))
            .as("a site carries no source column any more").isFalse();
        assertThat(gitRow.get(SiteModel.INSTANCE_ID))
            .as("and a static site exposes no instance").isNull();

        response = adminPostForm("/admin/access-lists/new",
            "name=Office+Only&satisfy=any&allowed_ips=10.0.0.0%2F8");
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row listRow = Models.get(AccessListModel.class).find()
            .where(AccessListModel.NAME.eq("Office Only")).first();
        assertThat(listRow).isNotNull();
        Integer listId = listRow.get(AccessListModel.ID);

        Row redirectRow = site("Old Domain");
        Integer redirectId = redirectRow.get(SiteModel.ID);
        response = adminPostForm("/admin/sites/" + redirectId,
            "name=Old+Domain&upstream_kind=hohenheim%3Aredirect"
            + "&settings.target_url=https%3A%2F%2Fexample.com"
            + "&access_list_id=" + listId);
        assertThat(response.statusCode()).isIn(200, 302, 303);
        assertThat((Integer) site("Old Domain").get(SiteModel.ACCESS_LIST_ID)).isEqualTo(listId);

        assertThat((Boolean) site("Old Domain").get(SiteModel.ENABLED)).isNotEqualTo(Boolean.FALSE);

        // Switching a site off is a confirmed action (it takes its hostnames out of the
        // route table), so the POST carries the confirmation proof like every other one.
        adminPostForm("/admin/sites/" + redirectId + "/action/toggle_site", confirmed(""));
        assertThat((Boolean) Models.get(SiteModel.class).findById(redirectId).get(SiteModel.ENABLED))
            .isEqualTo(false);

        adminPostForm("/admin/sites/" + redirectId + "/action/toggle_site", confirmed(""));
        assertThat((Boolean) Models.get(SiteModel.class).findById(redirectId).get(SiteModel.ENABLED))
            .isEqualTo(true);

        response = adminPostForm("/admin/sites/" + redirectId + "/action/clone_site", confirmed(""));
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row clone = site("Old Domain (copy)");
        assertThat(clone).isNotNull();
        assertThat((Boolean) clone.get(SiteModel.ENABLED))
            .as("clones start disabled")
            .isEqualTo(false);

        // AIDEV-NOTE: the api-key clone and mint steps that stood here belonged to the
        // managed-process control API, which dies with the host-user lane (phase-0 design
        // section 7) -- no upstream kind carries api_keys any more.

        Integer gitId = gitRow.get(SiteModel.ID);
        response = adminPostForm("/admin/sites/" + gitId + "/delete", confirmed(""));
        assertThat(response.statusCode()).isIn(200, 302, 303);

        Row after = StoredRows.byId(Models.get(SiteModel.class), gitId);
        assertThat((Object) after.get(SiteModel.DELETED_AT)).isNotNull();

        // The success toast rides the SESSION the browser shares, so pop it (proving the
        // outcome was reported) before asserting on the next render's content.
        assertThat(popFlash()).as("the delete reports itself as a toast").isNotNull()
            .extracting(flash -> flash.message().key()).isEqualTo("deleted");

        // One list render proves both the live site is listed and the soft-deleted one is
        // hidden. The live anchor is this journey's own redirect site, so the assertion
        // never depends on the address-site test having run first.
        navigateToApp("/admin/sites");
        waitForHydration();
        assertThat(page.content()).as("the live site is listed").contains("Old Domain");
        assertThat(page.content()).as("the soft-deleted site is hidden").doesNotContain("Git App");
    }
}
