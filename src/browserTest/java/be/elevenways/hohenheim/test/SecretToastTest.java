package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.cms.SiteResource;
import be.elevenways.hohenheim.server.process.SiteApiKeys;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.model.SessionModel;
import be.elevenways.zenit.auth.server.session.DatasourceUserAwareSessionStore;
import be.elevenways.zenit.cms.common.action.ActionContext;
import be.elevenways.zenit.cms.common.action.CmsActionResult;
import be.elevenways.zenit.cms.common.action.RowAction;
import be.elevenways.zenit.cms.common.flash.CmsFlash;
import be.elevenways.zenit.cms.common.flash.FlashToast;
import be.elevenways.zenit.cms.server.page.CmsActionResultTranslator;
import be.elevenways.zenit.cms.server.page.CmsPageContext;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.api.ResponseCarrier;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.routing.BodyDefinition;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.session.Session;
import be.elevenways.zenit.server.security.SecretDisclosures;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.setupSite;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 0.6 gate row for one-shot disclosures: a minted site API key appears in
 * the redirect's rendered toast EXACTLY once, while the REAL datasource-backed
 * session row (auth_sessions.data) never holds the plaintext -- only a
 * single-use SecretDisclosures handle.
 */
class SecretToastTest {

    private static boolean initialized = false;

    @BeforeAll
    static void initRuntime() throws Exception {
        if (initialized) return;
        initialized = true;
        ProxyTestSupport.bootRuntime();
    }

    @Test
    void mintedSiteApiKeyIsDisclosedOnceAndAbsentFromDurableSessionData() {
        // 1. A managed-process site (the command type declares api_keys).
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("start_command", "");
        settings.put("minimum_processes", 1);
        Row site = setupSite("hohenheim:command", "Secret Toast Site", "secret-toast-site", settings);
        int siteId = site.get(SiteModel.ID);

        // 2. The REAL durable session store over the same datasource hohenheim uses
        //    when auth.session.store=datasource.
        DatasourceUserAwareSessionStore store =
            new DatasourceUserAwareSessionStore(Datasources.getDefault());
        Session session = store.create();
        FlashConduit conduit = new FlashConduit(session, "tab-secret");

        // 3. Invoke the real generate-api-key row action and translate its result,
        //    exactly like the CMS action endpoint does.
        RowAction.Invoke<Row> mint = findMintAction();
        CmsActionResult result = mint.invoke(site, ActionContext.of(AccessContext.anonymous()));
        CmsActionResultTranslator.translate(conduit, result, new Uri("/admin/sites"));
        assertThat(conduit.softRedirectedTo)
            .as("3. the action must still redirect to the clean sites list")
            .isEqualTo("/admin/sites");

        // 4. The raw auth_sessions.data row (captured BEFORE the render) carries the
        //    flash as a handle, never the plaintext.
        String rawBefore = rawSessionData(session.id().value());
        String decodedFlash = decodedFlashPayload(rawBefore);
        assertThat(decodedFlash)
            .as("4. the durable flash payload must carry a single-use handle")
            .contains(SecretDisclosures.HANDLE_PREFIX);

        // 5. The redirect's render discloses THE minted key: its digest is exactly
        //    what the site row stored.
        FlashToast toast = CmsPageContext.readFlashToast(conduit);
        assertThat(toast).as("5. the redirect's render must receive the toast").isNotNull();
        assertThat(toast.message().key())
            .as("5. the toast keeps the api_key_minted key")
            .isEqualTo("api_key_minted");
        String disclosed = String.valueOf(toast.message().args().asMap().get("key"));
        assertThat(storedKeys(siteId))
            .as("5. the disclosed plaintext must digest to the stored key digest")
            .contains(SiteApiKeys.digest(disclosed))
            .doesNotContain(disclosed);

        // 6. Counterfactual on the durable row: the plaintext never rested there.
        assertThat(rawBefore)
            .as("6. raw auth_sessions.data must not contain the minted plaintext")
            .doesNotContain(disclosed);
        assertThat(decodedFlash)
            .as("6. the base64url-DECODED flash payload must not contain it either"
                + " (base64 is not encryption)")
            .doesNotContain(disclosed);

        // 7. Exactly once: a second render gets nothing, and the durable row keeps
        //    neither the bucket nor a handle.
        assertThat(CmsPageContext.readFlashToast(conduit))
            .as("7. a second render must not re-disclose the key")
            .isNull();
        assertThat(rawSessionData(session.id().value()))
            .as("7. the popped bucket leaves no disclosure reference in the row")
            .doesNotContain(SecretDisclosures.HANDLE_PREFIX)
            .doesNotContain(disclosed);
    }

    // ------------------------------------------------------------------

    private static RowAction.Invoke<Row> findMintAction() {
        SiteResource resource = new SiteResource();
        for (RowAction<Row> action : resource.rowActions()) {
            if (action instanceof RowAction.Invoke<Row> invoke
                && Identifier.of("hohenheim", "generate_api_key").equals(invoke.id())) {
                return invoke;
            }
        }
        throw new AssertionError("generate_api_key row action not found on SiteResource");
    }

    private static String rawSessionData(String sessionId) {
        Row row = new SessionModel(Datasources.getDefault()).find()
            .where(SessionModel.ID.eq(sessionId)).first();
        assertThat(row).as("the auth_sessions row must exist").isNotNull();
        return String.valueOf(row.get(SessionModel.DATA));
    }

    /** Base64url-decode every flash bucket found in the serialized session data. */
    @SuppressWarnings("unchecked")
    private static String decodedFlashPayload(String rawData) {
        Object parsed = Zenit.DRY.parse(rawData);
        assertThat(parsed).as("auth_sessions.data must parse as the attribute map")
            .isInstanceOf(Map.class);
        Object buckets = ((Map<String, Object>) parsed)
            .get(CmsFlash.PENDING_BY_TAB.getId().toString());
        assertThat(buckets).as("the pending-flash buckets must be present")
            .isInstanceOf(Map.class);
        StringBuilder decoded = new StringBuilder();
        for (Object encoded : ((Map<String, Object>) buckets).values()) {
            decoded.append(new String(
                Base64.getUrlDecoder().decode(String.valueOf(encoded)), StandardCharsets.UTF_8));
        }
        return decoded.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> storedKeys(int siteId) {
        Object settings = Models.get(SiteModel.class).findById(siteId).get(SiteModel.SETTINGS);
        Object keys = settings instanceof Map<?, ?> map ? map.get(SiteApiKeys.SETTING_NAME) : null;
        return keys instanceof List<?> list ? (List<String>) list : List.of();
    }

    /** Minimal conduit: the REAL persistent session, a tab id, a recording redirect. */
    private static final class FlashConduit implements Conduit {

        private final Session session;
        private final @Nullable String tabHeader;
        private @Nullable String softRedirectedTo;

        FlashConduit(Session session, @Nullable String tabHeader) {
            this.session = session;
            this.tabHeader = tabHeader;
        }

        @Override public Session session() { return this.session; }

        @Override public @Nullable String getRequestHeader(String name) {
            return "x-hawkeye-tab".equals(name) ? this.tabHeader : null;
        }

        @Override public <T> ActionResult<T> softRedirect(String url) {
            this.softRedirectedTo = url;
            return null;
        }

        @Override public <T> @Nullable T getParameter(ParameterDefinition<T> parameter) { return null; }
        @Override public <T> @Nullable T getBody(BodyDefinition<T> definition) { return null; }
        @Override public boolean isHawkeyeRequest() { return true; }
        @Override public void enableStreamingResponse() { }
        @Override public ResponseCarrier getResponseCarrier() { throw new UnsupportedOperationException(); }
        @Override public void notFound() { }
        @Override public void forbidden() { }
        @Override public void badRequest() { }
        @Override public void badRequest(String message) { }
        @Override public <T> ActionResult<T> hardRedirect(String url) { throw new UnsupportedOperationException(); }
    }
}
