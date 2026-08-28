package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The personal account pages render in zenit-auth's own neutral card, and every one of
 * them anchors the named block that soft navigation repopulates.
 *
 * AIDEV-NOTE: this suite used to assert the opposite -- that the account pages ride the
 * admin shell. zenit-auth reverted that deliberately (bff5174, U-07): the host frame
 * zenit-cms answers with is "the shell of the first panel this viewer may open", so a
 * read-only viewer changing their password met an admin sidebar listing someone else's
 * resources, and a newly created account met it on the forced-rotation form. The frame
 * CONTRACT stays for a host that wants to grow its own account chrome; nothing here
 * chooses one. The block assertions are the second half of that story: these pages carry
 * plumage custom elements, so the render engine injects the client bundle and their links
 * soft-navigate -- into a block that has to exist, or the URL and the title change while
 * the previous page's body stays on screen.
 */
class AccountShellTest extends HohenheimTestBase {

    /**
     * Hawkeye repopulates he-block contents on a soft navigation, and only those. The
     * opening tag is matched WITHOUT its closing bracket: the renderer stamps a
     * template-id onto the element, which is not what this asserts.
     */
    private static final String NAMED_BLOCK = "<he-block block-name=\"main\"";

    @Test
    void theAccountPagesRenderInTheirOwnCardAndCanSwapIt() throws Exception {
        // 1. The overview renders in the neutral auth card, outside any panel chrome.
        HttpResponse<String> account = adminGet("/account");
        assertThat(account.statusCode()).as("step 1: /account renders").isEqualTo(200);
        assertThat(account.body())
            .as("step 1: the page sits in the auth card, not in an admin panel's shell")
            .contains("auth-shell")
            .contains("auth-card")
            .doesNotContain("cms-sidebar-nav");
        assertThat(account.body())
            .as("step 1: and it anchors the block a soft navigation repopulates")
            .contains(NAMED_BLOCK);

        // 2. A subpage keeps the card AND the one way back to the account overview --
        //    the link whose soft navigation the block above is what makes work.
        HttpResponse<String> sessions = adminGet("/account/sessions");
        assertThat(sessions.statusCode()).as("step 2: /account/sessions renders").isEqualTo(200);
        assertThat(sessions.body())
            .as("step 2: the subpage links back to the overview from inside the same block")
            .contains("data-auth-back-to-account")
            .contains(NAMED_BLOCK);

        // 3. Revoking one session is a confirm page first, reached from the sessions list
        //    by its own link. This is the navigation the 2026-08-28 pass saw change the
        //    URL and the title while leaving the sessions list on screen.
        Matcher link = Pattern.compile("/account/sessions/[^\"']+/revoke")
            .matcher(sessions.body());
        assertThat(link.find()).as("step 3: the list offers a revoke link").isTrue();

        HttpResponse<String> revoke = adminGet(link.group());
        assertThat(revoke.statusCode()).as("step 3: the revoke confirm page renders").isEqualTo(200);
        assertThat(revoke.body())
            .as("step 3: the page that link soft-navigates to anchors the same block")
            .contains(NAMED_BLOCK)
            .contains("data-auth-session-revoke-submit");

        // 4. Signing out everywhere is a confirm page first; a GET revokes nothing.
        HttpResponse<String> confirm = adminGet("/account/logout-everywhere");
        assertThat(confirm.statusCode()).as("step 4: the confirm page renders").isEqualTo(200);
        assertThat(confirm.body())
            .as("step 4: the confirm page explains and asks")
            .contains("data-auth-logout-everywhere");
        assertThat(adminGet("/account").statusCode())
            .as("step 4: the admin session survived the GET")
            .isEqualTo(200);
    }
}
