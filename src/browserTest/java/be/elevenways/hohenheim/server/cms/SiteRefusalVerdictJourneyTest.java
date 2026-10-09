package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.test.ProxyTestSupport.addDomain;
import static be.elevenways.hohenheim.test.ProxyTestSupport.httpPort;
import static be.elevenways.hohenheim.test.ProxyTestSupport.rawRequest;
import static be.elevenways.hohenheim.test.ProxyTestSupport.setupInstanceSite;
import static be.elevenways.hohenheim.test.ProxyTestSupport.startProxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A site the proxy turns every visitor away from carries the proxy's OWN reason in its verdict: the visitor's 503, the
 * attention item and the app's problem band say the same words, never the generic "does not answer".
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
class SiteRefusalVerdictJourneyTest extends HohenheimTestBase {

    private static final String PREFIX = "d7d-refusal-";
    private static final LocaleChain EN = LocaleChain.ofTags("en");

    @Test
    @Timeout(60)
    void aSiteTheProxyRefusesSaysWhyInItsBand() throws Exception {
        ProxyServer previous = ServerMain.getProxyServer();
        ProxyServer proxy = null;
        Row instance = instance();
        Row site = setupInstanceSite(PREFIX + "site", PREFIX + "site", instance.get(InstanceModel.ID));
        Row down = setupInstanceSite(PREFIX + "down", PREFIX + "down", instance.get(InstanceModel.ID));
        try {
            // 1. A stored site whose app is gone: its upstream names no instance any more (written past validation,
            //    which refuses it, because this is a row an older state left behind).
            addDomain(site, "refusal.d7d.test", "exact", null, false);
            addDomain(down, "down.d7d.test", "exact", null, false);
            Models.get(SiteModel.class).find().where(SiteModel.ID.eq(site.get(SiteModel.ID)))
                .assign(SiteModel.INSTANCE_ID, null).bypassBehaviours().updateAll();
            proxy = startProxy();
            ServerMain.adoptProxyServer(proxy);

            // 2. The visitor gets a 503 in words, not a code.
            assertThat(rawRequest(httpPort(proxy), "refusal.d7d.test", "/"))
                .as("step 2: the visitor is refused").contains("503")
                .as("step 2: and told why in words").contains("This site is set up wrong: it names no app to serve");

            // 3. The app's verdict is an error page whose reason is that refusal, never "does not answer".
            Row stored = Models.get(SiteModel.class).findById(site.get(SiteModel.ID));
            RecordHealth verdict = AppHealth.sites(false).read(stored, operator());
            assertThat(verdict.tone()).as("step 3: visitors get an error page").isEqualTo(HealthTone.BROKEN);
            assertThat(say(verdict.headline())).as("step 3: headed as one").isEqualTo("Visitors get an error page");
            assertThat(say(verdict.detail())).as("step 3: with the proxy's own reason")
                .isEqualTo("It refuses every request: it names no app to serve")
                .as("step 3: never the generic one").doesNotContain("does not answer");
            assertThat(verdict.fixes()).as("step 3: and offers the fix: the site's own configuration")
                .containsExactly(SiteActions.CHANGE_CONFIGURATION);
            assertThat(AppHealth.refusedBySettings(stored)).as("step 3: which its row action offers here").isTrue();
            assertThat(AppHealth.sites(true).read(stored, operator()).fixes())
                .as("step 3: /manage's form cannot change what a site serves, so its verdict offers no such fix")
                .isEmpty();

            // 4. The attention item reads the same reason, from the same recorded problem.
            List<AttentionItem> items = new ArrayList<>();
            ProxyAttention.routingProblems(items, proxy.getDispatcher().routingProblems());
            assertThat(items).as("step 4: the dashboard names the same reason")
                .anySatisfy(item -> assertThat(say(item.detail()))
                    .isEqualTo("It refuses every request: it names no app to serve"));
            assertThat(items).as("step 4: titled by the app's name in a person's words, an error like its verdict")
                .anySatisfy(item -> {
                    assertThat(say(item.title())).isEqualTo(PREFIX + "site does not answer as it is set up");
                    assertThat(item.severity()).isEqualTo(AttentionSeverity.ERROR);
                });

            // 5. A site whose app does not answer reads like every other item: what visitors get, why in the app's
            //    own verdict, and the way to it; the refused site is not drawn a second time as merely "down".
            List<AttentionItem> health = new ArrayList<>();
            ProxyAttention.unhealthySites(health);
            List<AttentionItem> mine = health.stream()
                .filter(item -> say(item.title()).contains(PREFIX)).toList();
            assertThat(mine).as("step 5: one item, for the site that does not answer").hasSize(1);
            AttentionItem item = mine.get(0);
            assertThat(say(item.title())).as("step 5: titled by what its visitors get, naming the app as the Apps list"
                    + " does (its workload's name)")
                .isEqualTo("Visitors of " + PREFIX + "app get an error page");
            Row downRow = Models.get(SiteModel.class).findById(down.get(SiteModel.ID));
            assertThat(say(item.detail())).as("step 5: with the app verdict's own reason")
                .isEqualTo(say(AppHealth.siteHealth(downRow).detail()))
                .as("step 5: never the bare word").isNotEqualTo("Down");
            assertThat(say(item.action())).as("step 5: and the way there").isEqualTo("Open " + PREFIX + "app");
        } finally {
            ServerMain.adoptProxyServer(previous);
            if (proxy != null) {
                proxy.stop();
            }
            HardDeletes.row(Models.get(SiteModel.class), Models.get(SiteModel.class).findById(site.get(SiteModel.ID)));
            HardDeletes.row(Models.get(SiteModel.class), Models.get(SiteModel.class).findById(down.get(SiteModel.ID)));
            HardDeletes.row(Models.get(InstanceModel.class), instance);
        }
    }

    private static Row instance() {
        Row app = Models.get(InstanceModel.class).createEmptyRow();
        Map.of(InstanceModel.NAME.getName(), (Object) (PREFIX + "app"),
            InstanceModel.KIND.getName(), "hohenheim:docker_container",
            InstanceModel.SETTINGS.getName(), new LinkedHashMap<>(Map.of("image", "alpine", "command", "sleep 60")),
            InstanceModel.STATUS.getName(), InstanceModel.STATUS_STOPPED,
            InstanceModel.SERVER_ID.getName(), ServerModel.localServerId()).forEach(app::set);
        Models.get(InstanceModel.class).save(app);
        return app;
    }

    private static AccessContext operator() {
        Row admin = Models.get(UserModel.class).find().where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        return AccessContext.of(TenantConduits.stubFor(new UserPrincipal(admin.get(UserModel.ID), "Test Admin")));
    }

    private static String say(Microcopy copy) {
        return copy == null ? "" : copy.resolve(EN, Zenit.getMessageResolver());
    }
}
