package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.HistoryTestSupport;
import be.elevenways.zenit.test.support.StubHost;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static be.elevenways.hohenheim.test.ApiSupport.user;
import static be.elevenways.hohenheim.test.DnsFixtures.apiPeer;
import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static be.elevenways.hohenheim.test.DnsFixtures.record;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A secondary zone's records set and read by the assistant tools on the server holding the copy: the set is judged
 * here and applied on the owner through the peer API, whole or not at all, and a customer reaches only the names he
 * manages. The owner is this same app under another origin: a relay at a public-looking https name forwards the copy's
 * peer calls to the owner's own routes, so both ends run the real code.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DnsRecordForwardingTest extends HohenheimHttpTestBase {

    @Test
    void recordsOfACopyAreSetOnTheServerKeepingThemByAnAdministratorAndAManagingCustomer() throws Exception {
        String tag = HohenheimMcpTest.letters();
        String ownerOrigin = "owner-" + tag + ".forward.test";
        String copyOrigin = "copy-" + tag + ".forward.test";
        int ownerZone = createZone(ownerOrigin, DnsZoneModel.ROLE_PRIMARY, null);
        record(ownerZone, "www", DnsRecordModel.TYPE_A, "192.0.2.80");
        Row admin = TenantConduits.operatorUser();
        String peerKey = ApiKeyService.create(admin.get(UserModel.ID), "dns-forward-" + tag, List.of("hohenheim.*"),
            null).plaintext();
        String copyPath = "/api/dns/zones/" + copyOrigin;
        String ownerPath = "/api/dns/zones/" + ownerOrigin;
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        try (HistoryTestSupport.Scope recording = HistoryTestSupport.optionalRecording();
             StubHost relay = StubHost.startSecure("owner-" + tag + ".peer.test")) {
            relay.serveBy(copyPath + "/set-records", request -> relayed(requestTo(ownerPath + "/set-records")
                .header("Authorization", "Bearer " + peerKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(request.body()))));
            relay.serveBy(copyPath + "/records", request -> relayed(requestTo(ownerPath + "/records")
                .header("Authorization", "Bearer " + peerKey).GET()));
            String peerName = "forward-owner-" + tag;
            int peerId = apiPeer(peerName, relay.url(""));
            int copyZone = createZone(copyOrigin, DnsZoneModel.ROLE_SECONDARY, peerId);
            McpHttpSession operator = HohenheimMcpTest.presetSession(server, endpoint, admin.get(UserModel.ID),
                "Test Admin");

            // 1. An administrator sets two names on the copy: applied on the owner, its history naming this server
            //    and him; this server's copy is pulled at once, here from a peer serving no transfer, which it says.
            McpHttpSession.Answer set = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.50"))),
                    Map.of("name", "@", "type", "TXT", "values", List.of(Map.of("value", "hello"))))));
            assertThat(set.error()).as("step 1: " + set.text()).isFalse();
            assertThat(set.text()).as("step 1: the owner's answer").contains("shop A: 1 added")
                .contains("@ TXT: 1 added").contains("next transfer");
            assertThat(values(ownerZone, "shop", "A")).as("step 1: on the owner").containsExactly("203.0.113.50");
            assertThat(Models.get(DnsRecordModel.class).find().where(DnsRecordModel.ZONE_ID.eq(copyZone)).count())
                .as("step 1: no row written beside the copy").isZero();
            assertThat(lastForward(ownerZone)).as("step 1: the owner's history")
                .containsEntry(HohenheimActivityAction.SERVER_KEY, DnsFederationKeys.localName())
                .containsEntry(HohenheimActivityAction.PERSON_KEY, UserModel.personName(admin));

            // 2. A read of the copy answers the owner's live records, saying so first.
            McpHttpSession.Answer listed = operator.call("dns_record_list", Map.of("dns_zone", copyOrigin));
            assertThat(listed.error()).as("step 2: " + listed.text()).isFalse();
            // The answer's first line inside its untrusted-text envelope says where it read.
            assertThat(listed.text()).as("step 2: where it read").contains("Read from " + peerName);
            assertThat(listed.text()).as("step 2: every record").contains("shop A").contains("203.0.113.50")
                .contains("www A").contains("hello");

            // 3. A change and a removal in one call.
            McpHttpSession.Answer changed = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin,
                "records", List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value",
                    "203.0.113.51"))), Map.of("name", "@", "type", "TXT", "remove", true))));
            assertThat(changed.error()).as("step 3: " + changed.text()).isFalse();
            assertThat(changed.text()).as("step 3: what changed").contains("shop A: 1 added")
                .contains("1 removed").contains("@ TXT: 1 removed");
            assertThat(values(ownerZone, "shop", "A")).as("step 3: on the owner").containsExactly("203.0.113.51");
            assertThat(values(ownerZone, "@", "TXT")).as("step 3: removed there").isEmpty();

            // 4. All or nothing: an entry the owner refuses leaves the good entry unwritten too.
            McpHttpSession.Answer half = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.52"))),
                    Map.of("name", "bad", "type", "A", "values", List.of(Map.of("value", "not-an-address"))))));
            assertThat(half.error()).as("step 4: refused " + half.text()).isTrue();
            assertThat(values(ownerZone, "shop", "A")).as("step 4: nothing applied").containsExactly("203.0.113.51");
            assertThat(values(ownerZone, "bad", "A")).as("step 4: nor the bad entry").isEmpty();

            // 5. A dynamic DNS token is made where the records are kept: refused here, nothing sent.
            int sent = relay.hits(copyPath + "/set-records");
            McpHttpSession.Answer token = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.51",
                    "dyndns", "on"))))));
            assertThat(token.error()).as("step 5: refused " + token.text()).isTrue();
            assertThat(token.text()).as("step 5: saying where tokens are made").contains("keeps its records");
            assertThat(relay.hits(copyPath + "/set-records")).as("step 5: nothing forwarded").isEqualTo(sent);

            // 6. A customer managing the site of shop sets shop, and only shop: www and an MX record are refused
            //    here before anything is sent, and his read shows his name alone.
            UUID customerId = user("dns-forward-" + tag + "@forward.test", "Forward Customer");
            int siteId = DnsRecordApiTenantKeyTest.site("Forward shop " + tag, "forward-shop-" + tag,
                "shop." + copyOrigin);
            RecordGrants.grant(GrantSubjectType.USER, customerId, SiteModel.MODEL_ID, siteId,
                HohenheimCapabilities.MANAGE, true);
            McpHttpSession customer = HohenheimMcpTest.presetSession(server, endpoint, customerId,
                "Forward Customer");
            // He names the zone by its origin for the set and by its code for the read: a copy holds no record rows,
            // so he reaches it only by the hostname of his site lying inside it (TenantScopes.reachesZone).
            String copyCode = HohenheimMcpTest.code(DnsZoneModel.MODEL_ID, copyZone, TenantConduits.operator());
            McpHttpSession.Answer his = customer.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.60"))))));
            assertThat(his.error()).as("step 6: his name " + his.text()).isFalse();
            assertThat(values(ownerZone, "shop", "A")).as("step 6: on the owner").containsExactly("203.0.113.60");
            assertThat(lastForward(ownerZone)).as("step 6: the owner's history names him")
                .containsEntry(HohenheimActivityAction.PERSON_KEY, "Forward Customer");
            sent = relay.hits(copyPath + "/set-records");
            McpHttpSession.Answer foreign = customer.call("dns_record_set", Map.of("dns_zone", copyOrigin,
                "records", List.of(Map.of("name", "www", "type", "A", "values", List.of(Map.of("value",
                    "203.0.113.61"))))));
            assertThat(foreign.error()).as("step 6: another's name " + foreign.text()).isTrue();
            assertThat(foreign.text()).as("step 6: refused for the name")
                .contains("You do not manage a hostname that covers this name");
            McpHttpSession.Answer mail = customer.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "MX", "values", List.of(Map.of("value", "mx." + copyOrigin,
                    "priority", 10))))));
            assertThat(mail.error()).as("step 6: a type he may not set " + mail.text()).isTrue();
            assertThat(mail.text()).as("step 6: refused for the type")
                .contains("You can only manage A, AAAA, CNAME, TXT, SRV and TLSA records");
            assertThat(relay.hits(copyPath + "/set-records")).as("step 6: judged here, nothing forwarded")
                .isEqualTo(sent);
            assertThat(values(ownerZone, "www", "A")).as("step 6: www untouched").containsExactly("192.0.2.80");
            McpHttpSession.Answer seen = customer.call("dns_record_list", Map.of("dns_zone", copyCode));
            assertThat(seen.error()).as("step 6: his read " + seen.text()).isFalse();
            assertThat(seen.text()).as("step 6: his name only, the server unnamed").contains("shop A")
                .doesNotContain("www").doesNotContain(peerName);

            // 6b. An owner holding the zone as a copy itself refuses with 409: told so, naming it.
            relay.serve(copyPath + "/set-records", new StubHost.Answer(409, "application/json",
                "{\"error\":\"not_primary\"}".getBytes(StandardCharsets.UTF_8), Map.of()));
            McpHttpSession.Answer chained = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin,
                "records", List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value",
                    "203.0.113.69"))))));
            assertThat(chained.error()).as("step 6b: refused " + chained.text()).isTrue();
            assertThat(chained.text()).as("step 6b: a copy as well, naming it").contains(peerName)
                .contains("as a copy too");

            // 7. An owner that does not know the route (an older Hohenheim) is told apart: the refusal names it.
            relay.serve(copyPath + "/set-records", StubHost.Answer.status(404, Map.of()));
            McpHttpSession.Answer older = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.70"))))));
            assertThat(older.error()).as("step 7: refused " + older.text()).isTrue();
            assertThat(older.text()).as("step 7: naming the server keeping the records").contains(peerName);

            // 8. The owner gone: the set is refused saying so, the read answers this server's copy and says that.
            relay.close();
            McpHttpSession.Answer gone = operator.call("dns_record_set", Map.of("dns_zone", copyOrigin, "records",
                List.of(Map.of("name", "shop", "type", "A", "values", List.of(Map.of("value", "203.0.113.71"))))));
            assertThat(gone.error()).as("step 8: refused " + gone.text()).isTrue();
            assertThat(gone.text()).as("step 8: unreachable, naming it").contains(peerName)
                .contains("nothing was changed");
            McpHttpSession.Answer copy = operator.call("dns_record_list", Map.of("dns_zone", copyOrigin));
            assertThat(copy.error()).as("step 8: " + copy.text()).isFalse();
            assertThat(copy.text()).as("step 8: from the copy").contains("copy");
            assertThat(values(ownerZone, "shop", "A")).as("step 8: the owner as it was")
                .containsExactly("203.0.113.60");
        }
    }

    /** @return the relay's answer: the owner's status, type and body as it gave them to the copy's call */
    static StubHost.Answer relayed(HttpRequest.Builder toOwner) {
        HttpResponse<String> owner;
        try {
            owner = sendRequest(toOwner);
        } catch (Exception unreached) {
            throw new IllegalStateException("the relay could not reach the owner", unreached);
        }
        return new StubHost.Answer(owner.statusCode(), owner.headers().firstValue("Content-Type")
            .orElse("application/json"), owner.body().getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** @return the values the zone holds for a name and type, as stored */
    private static List<String> values(int zoneId, String name, String type) {
        return Models.get(DnsRecordModel.class).find().noCache().where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name)).where(DnsRecordModel.TYPE.eq(type)).all().stream()
            .map(row -> (String) row.get(DnsRecordModel.VALUE)).toList();
    }

    /** @return the body of the zone's last forwarded-records fact */
    private static Map<String, Object> lastForward(int zoneId) {
        List<RecordedChanges.Recorded> forwarded = RecordedChanges.withFact(Models.get(DnsZoneModel.class), zoneId,
            HohenheimActivityAction.RECORDS_FORWARDED);
        assertThat(forwarded).as("a forwarded set is in the owner's history").isNotEmpty();
        return Objects.requireNonNull(forwarded.getLast().fact(HohenheimActivityAction.RECORDS_FORWARDED));
    }
}
