package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.model.UserModel;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The personal account pages render in plumage's shared card frame (zenit-auth's account pages, since 66a0858), and
 * every one of them anchors the named block that soft navigation repopulates.
 *
 * AIDEV-NOTE: this suite used to assert the opposite -- that the account pages ride the
 * admin shell. zenit-auth reverted that deliberately (bff5174): the host frame
 * zenit-cms answers with is "the shell of the first panel this viewer may open", so a
 * read-only viewer changing their password met an admin sidebar listing someone else's
 * resources, and a newly created account met it on the forced-rotation form. The frame
 * CONTRACT stays for a host that wants to grow its own account chrome; nothing here
 * chooses one. The block assertions are the second half of that story: these pages carry
 * plumage custom elements, so the render engine injects the client bundle and their links
 * soft-navigate -- into a block that has to exist, or the URL and the title change while
 * the previous page's body stays on screen.
 */
class AccountShellTest extends HohenheimHttpTestBase {

    /**
     * Hawkeye repopulates he-block contents on a soft navigation, and only those. The
     * opening tag is matched WITHOUT its closing bracket: the renderer stamps a
     * template-id onto the element, which is not what this asserts.
     */
    private static final String NAMED_BLOCK = "<he-block block-name=\"main\"";

    /** The card frame and its card, by tag; the renderer may stamp attributes onto either opening tag. */
    private static final Pattern CARD_FRAME = Pattern.compile("<pl-card-frame[\\s>]");
    private static final Pattern CARD = Pattern.compile("<pl-card-frame-card[\\s>]");

    @Test
    void theAccountPagesRenderInTheirOwnCardAndCanSwapIt() throws Exception {
        // 1. The overview renders in the neutral card frame, outside any panel chrome.
        HttpResponse<String> account = adminGet("/account");
        assertThat(account.statusCode()).as("step 1: /account renders").isEqualTo(200);
        assertThat(account.body())
            .as("step 1: the page sits in the card frame, not in an admin panel's shell")
            .containsPattern(CARD_FRAME)
            .containsPattern(CARD)
            .doesNotContain("cms-sidebar-nav");
        assertThat(account.body())
            .as("step 1: and it anchors the block a soft navigation repopulates")
            .contains(NAMED_BLOCK);

        // 2. A subpage keeps the card AND the one way back to the account overview --
        //    the link whose soft navigation the block above is what makes work.
        HttpResponse<String> sessions = adminGet("/account/sessions");
        assertThat(sessions.statusCode()).as("step 2: /account/sessions renders").isEqualTo(200);
        assertThat(sessions.body())
            .as("step 2: the subpage links back to the overview from inside the same block, in the same card")
            .contains("data-auth-back-to-account")
            .contains(NAMED_BLOCK)
            .containsPattern(CARD);

        // 3. Signing out another browser is asked in its own row first, reached from the sessions list by its own
        //    link. This is the navigation the 2026-08-28 pass saw change the URL and the title while leaving the
        //    sessions list on screen.
        sessionFor(TenantConduits.operatorUser().get(UserModel.ID));
        HttpResponse<String> listed = adminGet("/account/sessions");
        Matcher link = Pattern.compile("href=\"(\\?signout=[^\"&]+)\"").matcher(listed.body());
        assertThat(link.find()).as("step 3: the list offers the other browser's sign-out link").isTrue();

        HttpResponse<String> asking = adminGet("/account/sessions" + link.group(1));
        assertThat(asking.statusCode()).as("step 3: the row asking renders").isEqualTo(200);
        assertThat(asking.body())
            .as("step 3: the page that link soft-navigates to anchors the same block and asks in the row")
            .contains(NAMED_BLOCK)
            .contains("data-auth-session-confirm")
            .contains("data-auth-session-revoke-submit");

        // 4. Signing out everywhere is asked in a dialog of the sessions page; a GET revokes nothing.
        assertThat(asking.body())
            .as("step 4: the dialog explains and asks")
            .contains("data-auth-logout-everywhere-dialog")
            .contains("data-auth-logout-everywhere");
        assertThat(adminGet("/account").statusCode())
            .as("step 4: the admin session survived the GETs")
            .isEqualTo(200);
    }
}
