package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.AccessRuleModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.security.BanState;
import be.elevenways.hohenheim.server.auth.types.BasicAuthProviderType;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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

        // 4. The lists page says what a list is for, and makes a list through its one header action: no quick-add bar
        //    beside the search (board Access-List).
        assertThat(lists).as("step 4: the lead reads as the board").contains("Who may reach a protected path");
        assertThat(lists).as("step 4: no quick-add bar").doesNotContain("data-cms-quick-add-open");
    }

    @Test
    void theBlockedAddressesOpenOnWhatIsBlockedNow() throws Exception {
        ban("203.0.113.41", "Tried 26 names this server does not serve", true);
        ban("203.0.113.42", "An old block", false);
        Row legacy = ban("203.0.113.43", "score 26 over threshold", true);
        legacy.set(BanModel.SOURCE, BanModel.SOURCE_AUTO);
        legacy.set(BanModel.EVENT_TYPE, SecurityEventTypes.DOMAIN_MISS);
        Models.get(BanModel.class).save(legacy);
        Row permanent = ban("203.0.113.44", "Login attempts on /wp-admin", true);
        permanent.set(BanModel.EXPIRES_AT, null);
        Models.get(BanModel.class).save(permanent);

        // 1. The list opens on the addresses blocked NOW, a default the reader can remove.
        String now = adminGet("/admin/bans").body();
        assertThat(now).as("step 1: the default reads as blocked now").contains("Blocked now");
        assertThat(now).as("step 1: a blocked address is listed with why").contains("203.0.113.41")
            .contains("Tried 26 names this server does not serve");
        assertThat(now).as("step 1: a lifted block stays out of the default view").doesNotContain("203.0.113.42");
        assertThat(now.replaceAll("<[^>]+>", " ")).as("step 1: the columns read address, by and until")
            .containsPattern("\\bAddress\\b").containsPattern("\\bBy\\b").containsPattern("\\bUntil\\b");

        // 2. An automatic block stored with the old score line reads as what tipped it in the current style, from its
        //    stored event type: never as a score, never as "Went over the limit for" (DEP10, 08b).
        String legacyRow = now.substring(now.indexOf("203.0.113.43"));
        assertThat(legacyRow.substring(0, Math.min(legacyRow.length(), 1500)))
            .as("step 2: the old score line reads as its event, without a count it never recorded")
            .contains("Tried names this server does not serve");
        assertThat(now).as("step 2: the score and the old wording are gone")
            .doesNotContain("score 26 over threshold").doesNotContain("Went over the limit");
        String rendered = now.replaceAll("(?s)<script.*?</script>", "");
        String renderedRow = rendered.substring(rendered.indexOf("203.0.113.43"));
        // The row from its address up to its state cell, which follows the By cell.
        assertThat(renderedRow.substring(0, renderedRow.indexOf("data-state=")))
            .as("step 2: by the threat scorer reads as the source's word, never its stored token")
            .contains("Automatic").doesNotContain(">auto<");

        // 3. The page is the blocked addresses, blocked through its one header action and its form; no quick-add
        //    bar (board Access-Blocked).
        assertThat(now).as("step 3: named as the board names it").contains("Blocked addresses");
        assertThat(now).as("step 3: the header action").contains("Block an address");
        assertThat(now).as("step 3: no quick-add bar").doesNotContain("data-cms-quick-add-open");
        assertThat(now).as("step 3: the old words are gone").doesNotContain("IP bans");

        // 4. A block without an expiry holds until it is lifted, and says so; the row action is the board's "Lift".
        assertThat(now).as("step 4: no expiry reads as until lifted").contains("Until lifted");
        assertThat(now).as("step 4: the row action reads Lift").contains("Lift").doesNotContain("Lift ban");
        assertThat(now).as("step 4: a block that holds offers its Lift").contains("lift_ban");

        // 5. A block past its expiry that the sweep has not cleared yet (stored active) is not blocked now: one
        //    definition answers the filter, the state cell and Lift (DEP9: listed under "Blocked now" as Expired,
        //    with Lift offered).
        Row expired = ban("203.0.113.45", "Held until a minute ago", true);
        expired.set(BanModel.EXPIRES_AT, Now.instant().minus(Duration.ofMinutes(1)));
        Models.get(BanModel.class).save(expired);
        assertThat(BanModel.blockedNow(expired, Now.instant())).as("step 5: an expired block is not blocked now")
            .isFalse();
        assertThat(BanState.of(expired, Now.instant())).as("step 5: its state reads expired")
            .isEqualTo(BanState.EXPIRED);
        assertThat(adminGet("/admin/bans").body()).as("step 5: the default Blocked now view leaves it out")
            .contains("203.0.113.41").doesNotContain("203.0.113.45");
        String notBlocked = adminGet("/admin/bans?filter." + BanModel.BLOCKED_NOW + "=false").body();
        assertThat(notBlocked).as("step 5: it is listed among the blocks that do not hold")
            .contains("203.0.113.45").contains("data-state=\"" + BanState.EXPIRED.token() + "\"")
            .doesNotContain("203.0.113.41");
        assertThat(notBlocked).as("step 5: and no block there offers Lift, the expired one included")
            .doesNotContain("lift_ban");
        // 5b. A lifted block's "Until" is when it was lifted, never the expiry it no longer has (DD10a).
        Row lifted = Models.get(BanModel.class).find().where(BanModel.IP.eq("203.0.113.42")).first();
        Instant liftedAt = lifted.get(BanModel.LIFTED_AT);
        Instant formerExpiry = lifted.get(BanModel.EXPIRES_AT);
        assertThat(BanState.until(lifted)).as("step 5b: until reads the lift").isEqualTo(liftedAt);
        assertThat(notBlocked).as("step 5b: the lifted row's Until is its lift")
            .contains("datetime=\"" + liftedAt + "\"")
            .doesNotContain("datetime=\"" + formerExpiry + "\"");

        // 5c. With no miss in the past hour the Recent misses card says so in one sentence, with no stray "Unknown"
        //     beside it (DD10a: the empty state was a fact without a value).
        Duration offset = Now.offset();
        Now.setOffset(offset.plus(BanParts.RECENT_MISSES).plusMinutes(1));
        String quiet;
        try {
            quiet = adminGet("/admin/bans").body().replaceAll("(?s)<script.*?</script>", "");
        } finally {
            Now.setOffset(offset);
        }
        String quietCard = quiet.substring(quiet.indexOf("Recent misses"));
        quietCard = quietCard.substring(0, Math.min(quietCard.length(), 1500));
        assertThat(quietCard).as("step 5c: the empty state is its sentence")
            .contains("No address asked for a name this server does not serve in the past hour")
            .doesNotContain("widget-fact-empty").doesNotContain("Unknown");

        // 6. Recent misses (board Access-Blocked): the threat scorer's requests for names this server does not serve,
        //    one line per address, the most recent names first and the rest counted.
        String scanner = "198.51.100." + (100 + (int) (Math.random() * 100));
        HohenheimSecurity.scorer().recordMiss(scanner, "admin.example.net");
        HohenheimSecurity.scorer().recordMiss(scanner, "git.example.com");
        HohenheimSecurity.scorer().recordMiss(scanner, "WWW.Example.org");
        String misses = adminGet("/admin/bans").body();
        assertThat(misses).as("step 6: the card under the list").contains("Recent misses")
            .contains("Requests for names this server does not serve, the past hour");
        String missLine = misses.substring(misses.indexOf(scanner));
        assertThat(missLine.substring(0, Math.min(missLine.length(), 800)))
            .as("step 6: the address's line names its newest misses and counts the rest")
            .contains("Asked for www.example.org, git.example.com and 1 more");

        // 7. Never block: the security.never_ban setting as it stands, with the way to change it in settings.
        List<String> previous = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Security.NEVER_BAN);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, List.of("203.0.113.0/28"));
        try {
            String never = adminGet("/admin/bans").body();
            assertThat(never).as("step 7: the Never block card").contains("Never block")
                .contains("203.0.113.0/28")
                .as("step 7: this server's own addresses are never blocked either")
                .contains("Its own addresses and local networks").contains("Every address in this network");
            assertThat(never.replace("&amp;", "&")).as("step 7: changed where every setting is, the security group")
                .contains("Change in settings")
                .contains(AttentionCollector.securitySettingsTarget().toUrl());
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Security.NEVER_BAN, previous);
        }
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

        // 3. The providers are named as the board names them.
        assertThat(access).as("step 3: the tab reads sign-in providers").contains("Sign-in providers")
            .doesNotContain("Auth providers");
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

    private static Row ban(String ip, String reason, boolean active) {
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
        return ban;
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
