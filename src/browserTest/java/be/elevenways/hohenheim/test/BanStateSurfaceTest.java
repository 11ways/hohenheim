package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.security.BanStateCell;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ban's detail shows what is STORED (its expiry, whether it was lifted and by whom)
 * and the list shows one state badge.
 *
 * Pinned defect (QA 2026-08-29, F9): the detail rendered "Duration: None" for an
 * enforced 24h ban -- the duration is a create-time choice backing no column -- and
 * after a lift nothing on the page said so.
 */
class BanStateSurfaceTest extends HohenheimTestBase {

    private static final String IP = "203.0.113.77";

    private static Integer banId;

    @AfterAll
    static void cleanUp() {
        if (banId != null) {
            Models.get(BanModel.class).delete(banId);
        }
    }

    @Test
    void theDetailShowsTheExpiryAndTheLiftAndTheListShowsOneStateBadge() throws Exception {
        Row ban = BanService.INSTANCE.createBan(IP, "qa", BanModel.SOURCE_MANUAL, null,
            Duration.ofHours(24));
        banId = ban.get(BanModel.ID);

        // 1. The create form asks for a duration and shows no stored state.
        HttpResponse<String> createForm = adminGet("/admin/bans/new");
        assertThat(createForm.statusCode()).isEqualTo(200);
        assertThat(createForm.body())
            .as("step 1: the create form offers the duration choice")
            .contains("data-path=\"duration\"")
            .doesNotContain("data-path=\"expires_at\"")
            .doesNotContain("data-path=\"lifted_by\"");

        // 2. The detail shows the expiry the ban was stored with, never a "None" duration.
        HttpResponse<String> detail = adminGet("/admin/bans/" + banId);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body())
            .as("step 2: the record shows its stored expiry and lift state")
            .contains("data-path=\"expires_at\"")
            .contains("data-path=\"active\"")
            .doesNotContain("data-path=\"duration\"");

        // 3. The list badge reads active.
        HttpResponse<String> list = adminGet("/admin/bans");
        assertThat(list.body())
            .as("step 3: the list shows the enforced state")
            .contains("data-ban-state=\"" + BanStateCell.ACTIVE + "\"");

        // 4. Lifting stamps the actor, and both surfaces say so.
        BanService.INSTANCE.lift(Models.get(BanModel.class).findById(banId), "qa-operator");
        HttpResponse<String> lifted = adminGet("/admin/bans/" + banId);
        assertThat(lifted.body())
            .as("step 4: the record names who lifted it")
            .contains("data-path=\"lifted_by\"")
            .contains("qa-operator");
        HttpResponse<String> listAfter = adminGet("/admin/bans");
        assertThat(listAfter.body())
            .as("step 4: the list shows the lifted state")
            .contains("data-ban-state=\"" + BanStateCell.LIFTED + "\"");

        // 5. The state derivation itself: a lift beats an expiry, an expiry beats active.
        Instant now = Instant.now();
        assertThat(BanStateCell.of(false, now, now.plus(Duration.ofHours(1)), now).token())
            .isEqualTo(BanStateCell.LIFTED);
        assertThat(BanStateCell.of(true, null, now.minusSeconds(1), now).token())
            .isEqualTo(BanStateCell.EXPIRED);
        assertThat(BanStateCell.of(true, null, null, now).token())
            .as("step 5: a permanent active ban is active").isEqualTo(BanStateCell.ACTIVE);
    }
}
