package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Access pages read the way the Access boards do: a list by what it lets in and where it is used, the blocked
 * addresses that are blocked now, and the sign-in providers beside people in one Access area.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
class AccessPagesJourneyTest extends HohenheimTestBase {

    @Test
    void anAccessListSaysWhatItLetsInAndWhereItIsUsed() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Row shop = site("shop-" + suffix);
        Row wiki = site("wiki-" + suffix);
        int staff = list("Staff " + suffix);
        rule(staff, AccessRuleModel.TYPE_BASIC_AUTH, Map.of("username", "anna", "password", "correct horse"));
        rule(staff, AccessRuleModel.TYPE_BASIC_AUTH, Map.of("username", "jan", "password", "battery staple"));
        protect(shop, "/wp-admin", staff);
        wiki.set(SiteModel.ACCESS_LIST_ID, staff);
        Models.get(SiteModel.class).save(wiki);
        int unused = list("Unused " + suffix);

        // 1. The lists read by what they let in and how many places use them, in words.
        String lists = adminGet("/admin/access-lists?q=" + suffix).body();
        assertThat(lists).as("step 1: a lets-in column").contains("Lets in");
        assertThat(lists).as("step 1: a password list says who it lets in").contains("Password, 2 people");
        assertThat(lists).as("step 1: the list is used in two places").contains("2 places");
        assertThat(lists).as("step 1: a list nothing uses says so").contains("No rules yet")
            .contains("Nothing yet");

        // 2. A list's rules tab names every place it protects: a whole app, or one path on an app.
        HttpResponse<String> rules = adminGet("/admin/access-lists/" + staff + "/page/rules");
        assertThat(rules.statusCode()).as("step 2: the rules tab renders").isEqualTo(200);
        assertThat(rules.body()).as("step 2: the whole wiki is protected").contains("Everything on wiki-" + suffix);
        assertThat(rules.body()).as("step 2: one path on the shop").contains("/wp-admin on shop-" + suffix);

        // 3. A list nothing uses says how to put it to use.
        assertThat(adminGet("/admin/access-lists/" + unused + "/page/rules").body())
            .as("step 3: an unused list says nothing uses it").contains("Nothing uses this list yet");
    }

    @Test
    void theBlockedAddressesOpenOnWhatIsBlockedNow() throws Exception {
        ban("203.0.113.41", "Tried names this server does not serve", true);
        ban("203.0.113.42", "An old block", false);

        // 1. The list opens on the addresses blocked NOW, a default the reader can remove.
        String now = adminGet("/admin/bans").body();
        assertThat(now).as("step 1: the default reads as blocked now").contains("Blocked now");
        assertThat(now).as("step 1: a blocked address is listed with why").contains("203.0.113.41")
            .contains("Tried names this server does not serve");
        assertThat(now).as("step 1: a lifted block stays out of the default view").doesNotContain("203.0.113.42");
        assertThat(now.replaceAll("<[^>]+>", " ")).as("step 1: the columns read address, by and until")
            .containsPattern("\\bAddress\\b").containsPattern("\\bBy\\b").containsPattern("\\bUntil\\b");
    }

    @Test
    void signInProvidersSitBesidePeopleAndSayWhereTheyAreUsed() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Row provider = provider("Acme " + suffix);
        int list = list("Acme staff " + suffix);
        rule(list, AccessRuleModel.TYPE_AUTH_PROVIDER, Map.of("provider_id", provider.get(SiteAuthProviderModel.ID)));
        provider("Unused " + suffix);

        // 1. The Access area's tabs reach the sign-in providers beside people and blocked addresses.
        String access = adminGet("/admin/access-lists").body();
        assertThat(access).as("step 1: the providers are a tab of the Access area").contains("/admin/auth-providers")
            .contains("/admin/bans").contains("/admin/users");

        // 2. Each provider says how many places use it.
        String providers = adminGet("/admin/auth-providers?q=" + suffix).body();
        assertThat(providers).as("step 2: a used-by column").contains("Used by");
        assertThat(providers).as("step 2: one list's rule uses the provider").contains("1 place");
        assertThat(providers).as("step 2: an unused provider says so").contains("Not used yet");
    }

    private static Row site(String name) {
        SiteModel sites = Models.get(SiteModel.class);
        Row site = sites.createEmptyRow();
        site.set(SiteModel.NAME, name);
        site.set(SiteModel.SLUG, name);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
        site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp"));
        site.set(SiteModel.ENABLED, false);
        sites.save(site);
        return site;
    }

    private static int list(String name) {
        Row list = Models.get(AccessListModel.class).createEmptyRow();
        list.set(AccessListModel.NAME, name);
        list.set(AccessListModel.SATISFY, AccessListModel.SATISFY_ANY);
        Models.get(AccessListModel.class).save(list);
        return list.get(AccessListModel.ID);
    }

    private static void rule(int listId, String type, Map<String, Object> data) {
        Row rule = Models.get(AccessRuleModel.class).createEmptyRow();
        rule.set(AccessRuleModel.ACCESS_LIST_ID, listId);
        rule.set(AccessRuleModel.TYPE, type);
        rule.set(AccessRuleModel.DATA, new LinkedHashMap<>(data));
        rule.set(AccessRuleModel.ENABLED, true);
        Models.get(AccessRuleModel.class).save(rule);
    }

    private static void protect(Row site, String path, int listId) {
        Row row = Models.get(ProtectedPathModel.class).createEmptyRow();
        row.set(ProtectedPathModel.SITE_ID, site.get(SiteModel.ID));
        row.set(ProtectedPathModel.PATH, path);
        row.set(ProtectedPathModel.ACCESS_LIST_ID, listId);
        Models.get(ProtectedPathModel.class).save(row);
    }

    private static void ban(String ip, String reason, boolean active) {
        Row ban = Models.get(BanModel.class).createEmptyRow();
        ban.set(BanModel.IP, ip);
        ban.set(BanModel.REASON, reason);
        ban.set(BanModel.SOURCE, BanModel.SOURCE_MANUAL);
        ban.set(BanModel.ACTIVE, active);
        ban.set(BanModel.EXPIRES_AT, Now.instant().plus(Duration.ofHours(12)));
        if (!active) {
            ban.set(BanModel.LIFTED_AT, Now.instant());
        }
        Models.get(BanModel.class).save(ban);
    }

    private static Row provider(String name) {
        SiteAuthProviderModel providers = Models.get(SiteAuthProviderModel.class);
        Row provider = providers.createEmptyRow();
        provider.set(SiteAuthProviderModel.NAME, name);
        provider.set(SiteAuthProviderModel.PROVIDER_TYPE, "hohenheim:basic");
        provider.set(SiteAuthProviderModel.CONFIG, new BasicAuthProviderType()
            .normalizeConfigForSave(Map.of("credentials", Map.of("alice", "s3cret")), null));
        providers.save(provider);
        return provider;
    }
}
