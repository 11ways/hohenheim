package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.hohenheim.site.ProtectPath;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.server.operation.RecordCodes;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Signed link tab of a protected path as its owner uses it, against board screen 2: just after the secret was
 * replaced it lists both secrets by their last characters with End now on the old one and shows the new secret once;
 * Make a link turns a path and minutes into one link, shown once; drawn again, neither shows.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SignedLinkTabBrowserTest extends HohenheimTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    void theOwnerReplacesTheSecretAndMakesALinkOnTheTab() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Tadoma downloads " + tag, "tadoma-" + tag,
            new LinkedHashMap<String, Object>(Map.of("own_files", true)));
        int siteId = site.get(SiteModel.ID);
        ProxyTestSupport.addDomain(site, "downloads-" + tag + ".tadoma.test", "exact", null, false);
        Row path = null;
        try {
            // 1. Protected with a signed link in the panel; the owner's assistant then replaces the secret.
            HttpResponse<String> protect = adminPostForm(siteInvoke(ProtectPath.OPERATION, siteId),
                confirmed("path=%2Fbeta&method=" + ProtectPath.METHOD_SIGNED_LINK));
            assertThat(protect.statusCode()).as("step 1: protected").isIn(302, 303);
            path = Objects.requireNonNull(Models.get(ProtectedPathModel.class).find()
                .where(ProtectedPathModel.SITE_ID.eq(siteId)).first(), "step 1: the protected path");
            String code = RecordCodes.codeUnjudged(ProtectedPathModel.MODEL_ID,
                String.valueOf((Object) path.get(ProtectedPathModel.ID)));
            URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
            McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
            McpHttpSession admin = HohenheimMcpTest.presetSession(server, endpoint,
                TenantConduits.operatorUser().get(UserModel.ID), "Test Admin");
            assertThat(admin.call("protected_path_set", Map.of("code", code, "signed_link_secret", "replace"))
                .error()).as("step 1: replaced").isFalse();

            // 2. The tab: the new secret once, both secrets listed, End now on the old one only.
            String tab = CmsRoutes.subpage(HohenheimSlugs.ADMIN, HohenheimSlugs.PROTECTED_PATHS,
                path.get(ProtectedPathModel.ID), HohenheimSlugs.Tab.SIGNED_LINK).toUrl();
            navigateToApp(tab);
            waitForHydration();
            String secret = page.locator("[data-hh-new-secret]").getAttribute("value");
            assertThat(secret).as("step 2: the new secret, shown once").matches("[A-Za-z0-9_-]{43}");
            assertThat(page.locator("[data-hh-secret]").count()).as("step 2: both secrets listed").isEqualTo(2);
            assertThat(page.locator("[data-hh-secret='" + secret.substring(39) + "']").textContent())
                .as("step 2: the new one by its end").contains("ends in " + secret.substring(39));
            assertThat(page.locator("[data-hh-end-old]").count()).as("step 2: End now on the old one").isEqualTo(1);
            assertThat(page.locator("[data-hh-signed-link-how] pl-code").getAttribute("value"))
                .as("step 2: how a link is made, its newline shown as \\n").contains("HMAC-SHA256")
                .contains("?expires=").contains("\"<path>\\n<expires>\"");
            page.screenshot(new Page.ScreenshotOptions().setPath(Path.of("build/protected-path-signed-link.png"))
                .setFullPage(true));

            // 3. Make a link: one link to the installer, shown once beside until when it works.
            page.locator("[data-hh-signed-link-make] pl-input[name='path'] input")
                .fill("/beta/releases/Tadoma-1.2.0.dmg");
            page.locator("[data-hh-signed-link-make] [data-cms-page-action-submit]").click();
            page.waitForSelector("[data-hh-made-link]", new Page.WaitForSelectorOptions().setTimeout(WAIT.toMillis()));
            String link = page.locator("[data-hh-made-link]").getAttribute("value");
            assertThat(link).as("step 3: the link").contains("/beta/releases/Tadoma-1.2.0.dmg?expires=")
                .containsPattern("&signature=[0-9a-f]{64}$");
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("build/protected-path-signed-link-made.png")).setFullPage(true));

            // 4. Drawn again, neither the secret nor the link shows.
            navigateToApp(tab);
            waitForHydration();
            assertThat(page.locator("[data-hh-new-secret], [data-hh-made-link]").count()).as("step 4: once only")
                .isZero();
            assertThat(page.content()).as("step 4: nowhere in the page").doesNotContain(secret).doesNotContain(link);
        } finally {
            if (path != null) SignedLinkJourneyTest.forget(path);
            HardDeletes.byId(Models.get(SiteModel.class), siteId);
        }
    }
}
