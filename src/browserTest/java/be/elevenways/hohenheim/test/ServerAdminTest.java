package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.protoblast.testsupport.browser.BoundedAssertions;
import be.elevenways.protoblast.common.time.Now;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server inventory through the zenit-cms resource routes: the seeded local
 * host, SSH-target validation, and the local-host edit/delete guards.
 */
class ServerAdminTest extends HohenheimTestBase {

    /**
     * The BOOT-seeded local host renders in the list, the sidebar and its Overview tab --
     * and rendering the list inserts nothing.
     *
     * AIDEV-NOTE: this used to assert that the LIST RENDER is what seeded the host
     * (ServerResource.listRows opened with ensureLocal()), i.e. it pinned a GET performing
     * an INSERT with full write hooks. The row is an installation invariant and is created
     * by LocalServerSeeder at the SEED boot stage; the render must be read-only, which is
     * why the row count is asserted AROUND the navigation and not merely the status.
     */
    @Test
    void serverInventoryRendersTheLocalHost() {
        // 1. The local host exists BEFORE anything rendered it: the seeder made it at boot.
        Row seeded = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(seeded)
            .as("step 1: the local host is seeded at boot, not by a page render")
            .isNotNull();
        long serversBefore = Models.get(ServerModel.class).find().count();

        // 2. Rendering the list shows it, and writes nothing.
        navigateToApp("/admin/servers");
        waitForHydration();

        String body = page.locator("body").textContent();
        assertThat(body).as("step 2: the list page renders").contains("Hosts");
        assertThat(body).as("step 2: and shows the seeded implicit host").contains("local");
        assertThat(Models.get(ServerModel.class).find().count())
            .as("step 2: a GET of the server list must INSERT nothing")
            .isEqualTo(serversBefore);

        // 3. The shell sidebar carries the servers entry.
        BoundedAssertions.assertThat(
            page.locator("pl-app-sidebar a[href='/admin/servers']")).hasCount(1);

        Row local = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(local)
            .as("step 3: and the rendered host is still the same seeded row")
            .isNotNull();
        assertThat((Object) local.get(ServerModel.ID)).isEqualTo(seeded.get(ServerModel.ID));

        // 4. The row-title target is the bespoke Overview page: structured state, no form.
        navigateToApp("/admin/servers/" + local.get(ServerModel.ID) + "/page/overview");
        waitForHydration();

        assertThat(page.locator(".hh-server-overview").count())
            .as("step 4: the overview page renders").isEqualTo(1);
        assertThat(page.locator(".hh-host-state[data-host-state]").count())
            .as("step 4: with the structured state cell in its header").isEqualTo(1);
        assertThat(page.locator(".widget-usage").count())
            .as("step 4: and an explicit state for its memory and for its disk").isEqualTo(2);
    }

    /**
     * The Hosts list's status line under a host's name reads as one run: "Docker 29.7.1, seen 2 hours ago" wraps as
     * text in its cell, the time following the words, never standing as a column of its own beside them.
     */
    @Test
    void theHostStatusLineIsOneRunOfWordsAndTime() {
        // 1. A host whose daemon answered two hours ago, at the width where the name column wraps its status line.
        Row host = Models.get(ServerModel.class).createEmptyRow();
        host.set(ServerModel.NAME, "status-line-host");
        host.set(ServerModel.RUNTIME, ServerModel.RUNTIME_DOCKER);
        host.set(ServerModel.MODE, ServerModel.MODE_LOCAL);
        host.set(ServerModel.CAPABILITIES, new LinkedHashMap<>(Map.of("docker_version", "29.7.1")));
        host.set(ServerModel.LAST_SEEN_AT, Now.instant().minus(Duration.ofHours(2)));
        Models.get(ServerModel.class).save(host);
        try {
            page.setViewportSize(1280, 900);
            navigateToApp("/admin/servers");
            waitForHydration();

            // 2. The time follows the words in the same run: it starts on or below the words' last line.
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) page.evaluate("() => {"
                + " const cell = [...document.querySelectorAll('.hh-host-state')]"
                + "   .find(c => c.textContent.includes('29.7.1'));"
                + " const time = cell.querySelector('pl-relative-time');"
                + " const words = document.createRange();"
                + " words.setStart(cell, 0); words.setEndBefore(time);"
                + " const rects = [...words.getClientRects()].filter(r => r.width > 0);"
                + " const last = rects[rects.length - 1];"
                + " return {text: cell.textContent.replace(/\\s+/g, ' ').trim(),"
                + "   lastWordsTop: last.top, timeTop: time.getClientRects()[0].top}; }");
            assertThat((String) line.get("text")).as("step 2: the line names the daemon and when it was seen")
                .contains("Docker 29.7.1").contains("ago");
            assertThat(((Number) line.get("timeTop")).doubleValue())
                .as("step 2: the time follows the words' last line, not a column beside them")
                .isGreaterThanOrEqualTo(((Number) line.get("lastWordsTop")).doubleValue() - 1);
        } finally {
            HardDeletes.row(Models.get(ServerModel.class), Models.get(ServerModel.class).findById(
                host.get(ServerModel.ID)));
        }
    }

    /** SSH-target round trip, argument-injection refusal and the implicit local host's guards. */
    @Test
    void serverWritesRoundTripAndGuardTheLocalHost() throws Exception {
        var create = adminPostForm("/admin/servers/new", "name=edge-9&ssh_target=deploy%40edge9.example");
        assertThat(create.statusCode()).isIn(200, 302, 303);

        Row row = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("edge-9")).first();
        assertThat(row).isNotNull();
        assertThat((String) row.get(ServerModel.SSH_TARGET)).isEqualTo("deploy@edge9.example");
        assertThat((String) row.get(ServerModel.MODE)).isEqualTo("ssh");
        Integer id = row.get(ServerModel.ID);

        var update = adminPostEdit("/admin/servers/" + id, "name=edge-9&ssh_target=ops%40edge9.example");
        assertThat(update.statusCode()).isIn(200, 302, 303);

        Row updated = Models.get(ServerModel.class).findById(id);
        assertThat((String) updated.get(ServerModel.SSH_TARGET)).isEqualTo("ops@edge9.example");

        // An argument-injecting SSH target is refused outright.
        adminPostForm("/admin/servers/new", "name=evil&ssh_target=-oProxyCommand%3Dcalc");
        Row evil = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("evil")).first();
        assertThat(evil).isNull();

        Row local = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(local).isNotNull();
        Integer localId = local.get(ServerModel.ID);

        var smuggled = assertNotStale(adminPostEdit("/admin/servers/" + localId,
            "name=local&ssh_target=evil%40host"));
        assertThat(smuggled.statusCode())
            .as("the local host's save lands with its read-only ssh_target withheld, not refused as stale")
            .isIn(302, 303);
        Row after = Models.get(ServerModel.class).findById(localId);
        assertThat((Object) after.get(ServerModel.SSH_TARGET))
            .as("the implicit local host must not accept an SSH target")
            .isNull();

        adminPostForm("/admin/servers/" + localId + "/delete", confirmed(""));
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
    void postureEditThroughTheFormLandsOnTheLocalHost() throws Exception {
        // 1. Remote hosts: posture rides the ordinary update path. The host is this test's
        //    own, so the journey never depends on another test having run first.
        var edgeCreate = adminPostForm("/admin/servers/new",
            "name=edge-posture&ssh_target=deploy%40edge-posture.example");
        assertThat(edgeCreate.statusCode()).as("step 1: the remote host is created")
            .isIn(200, 302, 303);
        Row edge = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("edge-posture")).first();
        assertThat(edge).as("step 1: the created remote host exists").isNotNull();
        var edgeUpdate = adminPostEdit("/admin/servers/" + edge.get(ServerModel.ID),
            "name=edge-posture&ssh_target=ops%40edge-posture.example&posture=dedicated");
        assertThat(edgeUpdate.statusCode()).isIn(200, 302, 303);
        assertThat((String) Models.get(ServerModel.class)
            .findById(edge.get(ServerModel.ID)).get(ServerModel.POSTURE))
            .as("step 1: a remote host's submitted posture is stored")
            .isEqualTo(ServerModel.POSTURE_DEDICATED);

        // 2. The LOCAL host: the identity guard must not swallow the posture. The local row
        //    is shared by every class in this JVM, so it is handed back unchanged below.
        HostFixtures.LocalHostState localBefore = HostFixtures.captureLocal();
        try {
            localPostureLands();
        } finally {
            localBefore.restore();
        }
    }

    /** Steps 2 and 3 of {@link #postureEditThroughTheFormLandsOnTheLocalHost}, on the shared local row. */
    private void localPostureLands() throws Exception {
        Row local = Models.get(ServerModel.class).find()
            .where(ServerModel.NAME.eq("local")).first();
        assertThat(local).isNotNull();
        Integer localId = local.get(ServerModel.ID);
        // A class earlier in this JVM may have admitted the shared local row (HostFixtures.admitLocal leaves it
        // shared_container); start from the default posture, which the caller's restore hands back afterwards.
        local.set(ServerModel.POSTURE, ServerModel.POSTURE_TRUSTED_ONLY);
        Models.get(ServerModel.class).save(local);

        var update = adminPostEdit("/admin/servers/" + localId,
            "name=local&ssh_target=evil%40intruder.example&posture=shared_container");
        assertThat(update.statusCode()).isIn(200, 302, 303);

        Row updated = Models.get(ServerModel.class).findById(localId);
        assertThat((String) updated.get(ServerModel.POSTURE))
            .as("step 2: the local host's submitted posture is STORED, not silently"
                + " dropped (the placement gate reads this column)")
            .isEqualTo(ServerModel.POSTURE_SHARED_CONTAINER);
        // 2b. And storing it GRANTS nothing. This bare form post is exactly the "boolean
        //     hidden in settings" shape the posture rule refuses to accept as a risk
        //     acknowledgement: it declares the intent and nothing else, so the host is
        //     unacknowledged and takes no tenant container until an operator says so by
        //     name. See HostPostureAcknowledgementTest for the whole journey.
        assertThat(ServerModel.postureAcknowledged(updated))
            .as("step 2b: a bare form post is a DECLARATION, never an acknowledgement --"
                + " no actor, no timestamp, no warning version was recorded by it")
            .isFalse();

        // 3. The identity guard itself still holds: the smuggled ssh_target is ignored.
        assertThat((String) updated.get(ServerModel.SSH_TARGET))
            .as("step 3: the local host's identity (ssh_target) stays immutable")
            .isNull();
        assertThat((String) updated.get(ServerModel.MODE))
            .as("step 3: the local host's mode stays local")
            .isEqualTo("local");
    }
}
