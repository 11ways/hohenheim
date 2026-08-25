package be.elevenways.hohenheim.test;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The personal account pages render INSIDE the admin shell for an operator who may
 * access a panel: sidebar, brand link and the account's own way back, never the
 * standalone card that used to eject them from the panel.
 */
class AccountShellTest extends HohenheimTestBase {

    @Test
    void theAccountPagesKeepTheAdminChrome() throws Exception {
        // 1. The overview is framed by the shell of the first panel the admin may access.
        HttpResponse<String> account = adminGet("/account");
        assertThat(account.statusCode()).as("step 1: /account renders").isEqualTo(200);
        assertThat(account.body())
            .as("step 1: the page sits inside the panel shell with its sidebar")
            .contains("cms-app-shell")
            .contains("cms-sidebar-nav")
            .contains("cms-framed-page");
        assertThat(account.body())
            .as("step 1: the standalone frame's exit link is not rendered inside the shell")
            .doesNotContain("auth-frame-exit");

        // 2. A subpage keeps the chrome AND the one way back to the account overview.
        HttpResponse<String> sessions = adminGet("/account/sessions");
        assertThat(sessions.statusCode()).as("step 2: /account/sessions renders").isEqualTo(200);
        assertThat(sessions.body())
            .as("step 2: the subpage keeps the shell and links back to the overview")
            .contains("cms-app-shell")
            .contains("data-auth-back-to-account");

        // 3. Signing out everywhere is a confirm page first; a GET revokes nothing.
        HttpResponse<String> confirm = adminGet("/account/logout-everywhere");
        assertThat(confirm.statusCode()).as("step 3: the confirm page renders").isEqualTo(200);
        assertThat(confirm.body())
            .as("step 3: the confirm page explains and asks inside the shell")
            .contains("data-auth-logout-everywhere")
            .contains("cms-app-shell");
        assertThat(adminGet("/account").statusCode())
            .as("step 3: the admin session survived the GET")
            .isEqualTo(200);
    }
}
