package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.server.PasswordService;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: a session lost server-side (restart) mid-visit renders the
 * login form in place on the next soft navigation; submitting it must not
 * fail CSRF (the SSE-shipped token beats the stale shell meta) and must
 * land the user on the page they were navigating to.
 */
class SessionLossLoginTest extends HohenheimTestBase {

    @Test
    void sessionLossRecoversThroughTheInPlaceLoginForm() {
        // Give the seeded admin a real password to log back in with.
        Row user = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        PasswordService.setPassword((Integer) user.get(UserModel.ID), "hunter2-session-test");

        // A dedicated session: revoking it cannot break the shared one other
        // test classes ride.
        Session session = Zenit.getSessionStore().create();
        session.set(AuthKeys.USER_ID, ((Integer) user.get(UserModel.ID)).longValue());
        session.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(session);

        page.context().addCookies(List.of(
            new Cookie(AuthCookieSupport.sessionCookieName(), session.token().secret())
                .setDomain("localhost")
                .setPath("/")));
        page.navigate("http://localhost:" + getServerPort() + "/admin/dashboard");
        waitForHydration();

        // The "server restart": the session vanishes server-side while the
        // page (and its boot-time CSRF meta) stays loaded.
        Zenit.getSessionStore().revoke(session.id());

        // Navigation to a protected page lands on the login form. The login page
        // extends the AUTH shell, and soft navigation into another document shell is
        // a FULL page load since hawkeye af7ac3ec -- so the admin sidebar is gone,
        // deliberately, instead of hosting a foreign shell's content.
        page.locator("pl-app-sidebar a[href='/admin/sites']").click();
        page.waitForSelector("form[action='/login']");

        // Submitting that form used to ALWAYS fail with CSRF_INVALID: the
        // client posted the dead boot-time token from the stale shell meta.
        page.fill("form[action='/login'] input[name='email']", "test@hohenheim.local");
        page.fill("form[action='/login'] input[name='password']", "hunter2-session-test");
        page.locator("form[action='/login'] pl-button button").click();

        // Login succeeds AND returns to the page the user was headed to.
        page.waitForURL("**/admin/sites");
        waitForHydration();
        assertThat(page.locator("h1").first().textContent()).contains("Sites");
        assertThat(page.locator("body").textContent()).doesNotContain("CSRF");
    }
}
