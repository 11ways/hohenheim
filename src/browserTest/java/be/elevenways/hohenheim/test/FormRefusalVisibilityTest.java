package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refused save must explain itself in words, never in machine tokens: the
 * 2026-08-15 starfleet rebuild read these exact refusals as SILENT because the
 * rerendered field errors carried raw {@code zenit.coercion.*} keys (no catalog
 * entry existed for the whole coercion vocabulary) and the LE request lane's
 * refusal only lives in a flash the next page renders.
 */
class FormRefusalVisibilityTest extends HohenheimTestBase {

    private HttpResponse<String> post(String path, String body) throws Exception {
        return httpPostForm(path, body, sessionToken, csrfToken);
    }

    @Test
    void refusedSavesExplainThemselvesInWordsNotMachineTokens() throws Exception {
        // Step 1: a static site with settings, the positive anchor.
        var create = post("/admin/sites/new",
            "name=Refusal+Probe&upstream_kind=hohenheim%3Astatic"
                + "&settings.root_path=%2Ftmp%2Frefusal-probe&settings.indexes=true");
        assertThat(create.statusCode()).as("the well-formed create succeeds").isEqualTo(302);

        Row site = Models.get(SiteModel.class).find().where(SiteModel.NAME.eq("Refusal Probe")).first();
        assertThat(site).isNotNull();
        Integer id = site.get(SiteModel.ID);
        Object before = site.get(SiteModel.SETTINGS);
        assertThat(before).as("settings persisted").isInstanceOf(Map.class);
        assertThat((Map<String, Object>) before).containsEntry("root_path", "/tmp/refusal-probe");

        // Step 2: an update that submits settings WITHOUT their upstream_kind
        // discriminator is refused, leaves the record untouched, and the
        // rerendered error is a human sentence naming the missing sibling.
        var noSibling = post("/admin/sites/" + id,
            "name=Refusal+Probe&settings.root_path=%2Ftmp%2Felsewhere");
        assertThat(noSibling.statusCode()).as("refusal rerenders the form").isEqualTo(200);
        site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(id)).first();
        Object afterNoSibling = site.get(SiteModel.SETTINGS);
        assertThat(afterNoSibling)
            .as("the refused write left the stored settings alone")
            .isEqualTo(before);
        assertThat(noSibling.body())
            .as("the field error is resolved copy, not a raw key")
            .contains("could not be saved because no upstream_kind")
            .doesNotContain("data-unresolved>zenit.coercion");

        // Step 3: a nonsense scalar for a boolean setting is refused with the
        // boolean sentence, again with nothing written.
        var badBoolean = post("/admin/sites/" + id,
            "name=Refusal+Probe&upstream_kind=hohenheim%3Astatic"
                + "&settings.root_path=%2Ftmp%2Frefusal-probe&settings.indexes=index.html");
        assertThat(badBoolean.statusCode()).isEqualTo(200);
        site = Models.get(SiteModel.class).find().where(SiteModel.ID.eq(id)).first();
        Object afterBadBoolean = site.get(SiteModel.SETTINGS);
        assertThat(afterBadBoolean).isEqualTo(before);
        assertThat(badBoolean.body())
            .contains("This is not a valid on/off value")
            .doesNotContain("data-unresolved>zenit.coercion");

        // Step 4: the LE request lane refuses comma-joined domains with a 302
        // whose follow-up page RENDERS the flash naming the bad hostnames --
        // pinned because a curl-only probe once read this refusal as "302 and
        // then nothing".
        var commaDomains = post("/admin/certificates-request",
            "domains=a.example.com%2Cb.example.com");
        assertThat(commaDomains.statusCode()).isEqualTo(302);
        var followUp = httpGet("/admin/certificates-request", sessionToken);
        assertThat(followUp.statusCode()).isEqualTo(200);
        assertThat(followUp.body())
            .as("the refusal flash renders on the page the redirect lands on")
            .contains("Invalid hostnames")
            .contains("a.example.com,b.example.com");
    }
}
