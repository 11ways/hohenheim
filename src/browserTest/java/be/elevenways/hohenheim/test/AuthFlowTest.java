package be.elevenways.hohenheim.test;

import be.elevenways.zenit.auth.AuthKeys;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.ZenitAuth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.security.csrf.CsrfTokens;
import be.elevenways.zenit.common.session.Session;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auth integration: the admin tree is gated by zenit-auth (anonymous -> /login), the login page
 * offers password login, an authenticated session reaches the dashboard, and assets stay public.
 * The login/logout/setup mechanics themselves are owned and tested by zenit-auth.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowTest extends HohenheimTestBase {

    private HttpResponse<String> get(String path, boolean followRedirects) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
            .build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void anonymousSurfaceGatesTheAdminOffersLoginAndServesAssets() throws Exception {
        HttpResponse<String> response = get("/", false);   // no session cookie
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/login");

        response = get("/login", false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("action=\"/login\"");
        assertThat(response.body()).contains("name=\"password\"");

        response = get("/hohenheim.css", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(".hh-deploy-log");
    }

    @Test
    @Order(2)
    void authenticatedShellOffersAccountAndSignsOut() {
        // Sign out on a THROWAWAY session: the class-shared one must survive
        // for every later test class in this JVM.
        Row user = AuthModels.users().find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        Session throwaway = Zenit.getSessionStore().create();
        throwaway.set(AuthKeys.USER_ID, ((Integer) user.get(UserModel.ID)).longValue());
        throwaway.set(CsrfTokens.TOKEN, ZenitAuth.randomToken());
        Zenit.getSessionStore().save(throwaway);
        context.addCookies(java.util.List.of(new com.microsoft.playwright.options.Cookie(
            AuthCookieSupport.sessionCookieName(), throwaway.token().secret())
            .setDomain("localhost").setPath("/")));

        // Navigate RAW, never through navigateToApp: the base helper re-injects the
        // JVM-shared session cookie over the throwaway one, and the sign-out below
        // would then revoke the session every later class in this JVM depends on
        // (the fork-wide 403/hydration wedge this comment exists to prevent).
        page.navigate("http://localhost:" + getServerPort() + "/admin");
        waitForHydration();
        assertThat(page.locator("pl-app-sidebar").count()).isEqualTo(1);

        // The zenit-auth topbar contribution renders in the shell.
        assertThat(page.locator("zn-auth-user-menu").count()).isEqualTo(1);

        // Opening it proves the tag HYDRATED (the dropdown is client-driven).
        // Click the name text: a click landing on the icon SVG has a null
        // event target (protoblast defect) and never toggles the menu.
        page.click("zn-auth-user-menu .zn-user-menu-name");
        page.waitForSelector("he-bottom .pl-dropdown-menu-content__popup a[href='/account']");
        assertThat(page.locator("he-bottom .pl-dropdown-menu-content__popup [data-user-menu-signout]").count())
            .isEqualTo(1);

        // Sign out posts /logout with the CSRF token and lands on the login page.
        page.click("he-bottom .pl-dropdown-menu-content__popup [data-user-menu-signout]");
        page.waitForURL("**/login**");

        // The session really ended: the admin tree redirects again.
        page.navigate("http://localhost:" + getServerPort() + "/admin");
        page.waitForURL("**/login**");
    }
}
