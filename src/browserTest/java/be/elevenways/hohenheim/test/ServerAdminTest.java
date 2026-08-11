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

    /** The seeded local host renders in the list, the sidebar and its Overview tab. */
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

        // The row-title target is the bespoke Overview page: structured state, no form.
        navigateToApp("/admin/servers/" + local.get(ServerModel.ID) + "/page/overview");
        waitForHydration();

        assertThat(page.locator(".hh-server-overview").count())
            .as("the overview page renders").isEqualTo(1);
        assertThat(page.locator(".hh-host-state[data-host-state]").count())
            .as("with the structured state cell in its header").isEqualTo(1);
        assertThat(page.locator("[data-capacity-state]").count())
            .as("and an explicit capacity state").isEqualTo(1);
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

    /**
     * A posture submitted through the resource form must LAND, on the implicit LOCAL
     * host especially: on a single-machine install the local row IS the compute host,
     * placement refuses trusted_only, and the local-row identity guard used to swallow
     * the posture silently while the form reported success. Identity stays immutable.
     */
    @Test
    @Order(3)
    void postureEditThroughTheFormLandsOnTheLocalHost() throws Exception {
        // 1. Remote hosts: posture rides the ordinary update path.
        Row edge = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("edge-9")).first();
        assertThat(edge).as("step 1 rides the edge-9 host created in step 2").isNotNull();
        var edgeUpdate = postForm("/admin/servers/" + edge.get(ServerModel.ID),
            "name=edge-9&ssh_target=ops%40edge9.example&posture=dedicated");
        assertThat(edgeUpdate.statusCode()).isIn(200, 302, 303);
        assertThat((String) Models.get(ServerModel.class)
            .findById(edge.get(ServerModel.ID)).get(ServerModel.POSTURE))
            .as("step 1: a remote host's submitted posture is stored")
            .isEqualTo(ServerModel.POSTURE_DEDICATED);

        // 2. The LOCAL host: the identity guard must not swallow the posture.
        Row local = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(local).isNotNull();
        Integer localId = local.get(ServerModel.ID);
        assertThat((String) local.get(ServerModel.POSTURE))
            .as("step 2 precondition: the local host still carries the default posture")
            .isEqualTo(ServerModel.POSTURE_TRUSTED_ONLY);

        var update = postForm("/admin/servers/" + localId,
            "name=local&ssh_target=evil%40intruder.example&posture=shared_container");
        assertThat(update.statusCode()).isIn(200, 302, 303);

        Row updated = Models.get(ServerModel.class).findById(localId);
        assertThat((String) updated.get(ServerModel.POSTURE))
            .as("step 2: the local host's submitted posture is STORED, not silently"
                + " dropped (the placement gate reads this column)")
            .isEqualTo(ServerModel.POSTURE_SHARED_CONTAINER);

        // 3. The identity guard itself still holds: the smuggled ssh_target is ignored.
        assertThat((String) updated.get(ServerModel.SSH_TARGET))
            .as("step 3: the local host's identity (ssh_target) stays immutable")
            .isNull();
        assertThat((String) updated.get(ServerModel.MODE))
            .as("step 3: the local host's mode stays local")
            .isEqualTo("local");
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
