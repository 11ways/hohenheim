package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.security.BanState;
import be.elevenways.hohenheim.server.security.BanService;
import be.elevenways.protoblast.common.time.Now;
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
            .contains("data-state=\"" + BanState.ACTIVE.token() + "\"");

        // 4. Lifting stamps the actor, and both surfaces say so.
        BanService.INSTANCE.lift(Models.get(BanModel.class).findById(banId), "qa-operator");
        HttpResponse<String> lifted = adminGet("/admin/bans/" + banId);
        assertThat(lifted.body())
            .as("step 4: the record names who lifted it")
            .contains("data-path=\"lifted_by\"")
            .contains("qa-operator");
        // The list opens on what is blocked NOW; the lifted ban shows under the explicit "not blocked now" filter.
        HttpResponse<String> listAfter = adminGet("/admin/bans?filter." + BanModel.BLOCKED_NOW + "=false");
        assertThat(listAfter.body())
            .as("step 4: the list shows the lifted state")
            .contains("data-state=\"" + BanState.LIFTED.token() + "\"");

        // 5. The state derivation itself: a lift beats an expiry, an expiry beats the stored active flag.
        Instant now = Now.instant();
        assertThat(BanState.of(stored(false, now, now.plus(Duration.ofHours(1))), now))
            .as("step 5: a lift beats an expiry").isEqualTo(BanState.LIFTED);
        assertThat(BanState.of(stored(true, null, now.minusSeconds(1)), now))
            .as("step 5: a passed expiry beats the flag the sweep clears late").isEqualTo(BanState.EXPIRED);
        assertThat(BanState.of(stored(true, null, null), now))
            .as("step 5: a permanent active ban is active").isEqualTo(BanState.ACTIVE);

        // 6. "Until" follows the same precedence: a lift beats the expiry, and a permanent ban has none.
        assertThat(BanState.until(stored(false, now, now.plus(Duration.ofHours(1)))))
            .as("step 6: a lifted ban holds until its lift").isEqualTo(now);
        assertThat(BanState.until(stored(true, null, now.plus(Duration.ofHours(1)))))
            .as("step 6: an enforced ban until its expiry").isEqualTo(now.plus(Duration.ofHours(1)));
        assertThat(BanState.until(stored(true, null, null))).as("step 6: a permanent ban until lifted").isNull();
    }

    /** An unsaved ban row carrying only the facts the state reads. */
    private static Row stored(boolean active, Instant liftedAt, Instant expiresAt) {
        Row ban = Models.get(BanModel.class).createEmptyRow();
        ban.set(BanModel.ACTIVE, active);
        ban.set(BanModel.LIFTED_AT, liftedAt);
        ban.set(BanModel.EXPIRES_AT, expiresAt);
        return ban;
    }
}
