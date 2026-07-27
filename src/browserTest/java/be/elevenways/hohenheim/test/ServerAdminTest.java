package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ServerModel;
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
 * Server inventory through the zenit-cms resource routes: the seeded local
 * host, SSH-target validation, and the local-host edit/delete guards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerAdminTest extends HohenheimTestBase {

    /** The seeded local host renders in the list, the sidebar and its read-only detail overview. */
    @Test
    @Order(1)
    void serverInventoryRendersTheLocalHost() {
        navigateToApp("/admin/servers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).contains("Servers");
        assertThat(body).contains("local");        // ensureLocal() seeded the implicit host

        // The shell sidebar carries the servers entry.
        PlaywrightAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/servers']")).hasCount(1);

        Row local = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(local).isNotNull();

        navigateToApp("/admin/servers/" + local.get(ServerModel.ID));
        waitForHydration();

        var overview = page.locator("pl-field[data-path='live_overview']");
        assertThat(overview.locator(".zf-field-readonly").count()).isEqualTo(1);
        assertThat(overview.locator(".zf-field-readonly").innerText()).contains("Docker");
        assertThat(overview.locator("input, textarea").count()).isZero();
    }

    /** SSH-target round trip, argument-injection refusal and the implicit local host's guards. */
    @Test
    @Order(2)
    void serverWritesRoundTripAndGuardTheLocalHost() throws Exception {
        var create = postForm("/admin/servers/new", "name=edge-9&ssh_target=deploy%40edge9.example");
        assertThat(create.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("edge-9")).first();
        assertThat(row).isNotNull();
        assertThat((String) row.get(ServerModel.SSH_TARGET)).isEqualTo("deploy@edge9.example");
        assertThat((String) row.get(ServerModel.MODE)).isEqualTo("ssh");
        Integer id = row.get(ServerModel.ID);

        var update = postForm("/admin/servers/" + id, "name=edge-9&ssh_target=ops%40edge9.example");
        assertThat(update.statusCode()).isIn(200, 302, 303);

        Row updated = Models.get(ServerModel.class).findById(id);
        assertThat((String) updated.get(ServerModel.SSH_TARGET)).isEqualTo("ops@edge9.example");

        // An argument-injecting SSH target is refused outright.
        postForm("/admin/servers/new", "name=evil&ssh_target=-oProxyCommand%3Dcalc");
        Row evil = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("evil")).first();
        assertThat(evil).isNull();

        Row local = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(local).isNotNull();
        Integer localId = local.get(ServerModel.ID);

        postForm("/admin/servers/" + localId, "name=local&ssh_target=evil%40host");
        Row after = Models.get(ServerModel.class).findById(localId);
        assertThat((Object) after.get(ServerModel.SSH_TARGET))
            .as("the implicit local host must not accept an SSH target")
            .isNull();

        postForm("/admin/servers/" + localId + "/delete", "");
        assertThat(Models.get(ServerModel.class).findById(localId))
            .as("the implicit local host must not be deletable")
            .isNotNull();
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
