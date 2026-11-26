package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.DnsZonePeerModel;
import be.elevenways.hohenheim.server.dns.DnsPeerMirrors;
import be.elevenways.hohenheim.server.mcp.HohenheimMcpModule;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServer;
import be.elevenways.zenit.ai.server.mcp.host.McpHostServers;
import be.elevenways.zenit.ai.server.mcp.host.McpHttpSession;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.StubHost;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static be.elevenways.hohenheim.test.DnsFixtures.apiPeer;
import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A primary zone listing a Hohenheim peer among its secondaries makes the peer keep its copy: on create and when a
 * secondary is added later, refused with nothing linked when the peer runs an older Hohenheim, already keeps a zone of
 * that origin, fails after it was asked, or cannot be reached. The peer is this same app: a relay at a public-looking
 * https name signs the request again for a copy origin of its own (the origin is inside the signature) and hands it to
 * this app's real add-only route, so both halves run.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class DnsZoneCopyTest extends HohenheimHttpTestBase {

    private static final String ADD_PATH = "/api/dns/peer-mirror-add";
    private static final String KEY_SECRET = "c2VjcmV0LXRzaWcta2V5LWZvci1ob2hlbmhlaW0tdGVzdA==";

    @Test
    void aPrimaryZoneListingAHohenheimPeerMakesItsCopyThere() throws Exception {
        String tag = HohenheimMcpTest.letters();
        Row admin = TenantConduits.operatorUser();
        String peerKey = ApiKeyService.create(admin.get(UserModel.ID), "dns-copy-" + tag, List.of("hohenheim.*"),
            null).plaintext();
        String keyName = "xfer-copy-" + tag;
        URI endpoint = URI.create(baseUrl() + "/zn/mcp/" + HohenheimMcpModule.SERVER_NAME);
        McpHostServer server = Objects.requireNonNull(McpHostServers.routed(HohenheimMcpModule.SERVER_NAME));
        try (StubHost relay = StubHost.startSecure("copies-" + tag + ".peer.test")) {
            relay.serveBy(ADD_PATH, request -> this.resigned(request, peerKey));
            String peerName = "copy-peer-" + tag;
            int peerId = apiPeer(peerName, relay.url(""));
            Row peer = Models.get(DnsPeerModel.class).findById(peerId);
            peer.set(DnsPeerModel.TSIG_KEY_NAME, keyName);
            peer.set(DnsPeerModel.TSIG_ALGORITHM, "hmac-sha256");
            peer.set(DnsPeerModel.TSIG_SECRET, KEY_SECRET);
            // Where the copy transfers from: the receiver refuses a copy it could never fill.
            peer.set(DnsPeerModel.TRANSFER_HOST, "127.0.0.1");
            Models.get(DnsPeerModel.class).save(peer);
            String peerCode = HohenheimMcpTest.code(DnsPeerModel.MODEL_ID, peerId, TenantConduits.operator());
            McpHttpSession operator = HohenheimMcpTest.presetSession(server, endpoint, admin.get(UserModel.ID),
                "Test Admin");

            // 1. A new primary zone listing the peer: the peer is asked whether it can, the zone and its link are
            //    written, then the peer makes its copy, a secondary pulling from this server.
            String made = "made-" + tag + ".copy.test";
            McpHttpSession.Answer created = operator.call("dns_zone_set", Map.of("origin", made,
                "secondaries", List.of(peerCode)));
            assertThat(created.error()).as("step 1: " + created.text()).isFalse();
            assertThat(linked(made, peerId)).as("step 1: linked here").isTrue();
            assertCopied(made, peerId, "step 1");
            assertThat(relay.hits(ADD_PATH)).as("step 1: asked before the zone and before the link, then made")
                .isEqualTo(3);

            // 2. A secondary listed later on a zone that exists makes its copy too.
            String later = "later-" + tag + ".copy.test";
            int laterZone = createZone(later, DnsZoneModel.ROLE_PRIMARY, null);
            McpHttpSession.Answer added = operator.call("dns_zone_set", Map.of("code", later,
                "secondaries", List.of(peerCode)));
            assertThat(added.error()).as("step 2: " + added.text()).isFalse();
            assertThat(linked(later, peerId)).as("step 2: linked here").isTrue();
            assertCopied(later, peerId, "step 2");
            assertThat(laterZone).as("step 2: the zone itself stays").isPositive();

            // 3. The peer already keeps a zone of that origin of its own: refused, nothing made here.
            String taken = "taken-" + tag + ".copy.test";
            createZone(copyOf(taken), DnsZoneModel.ROLE_PRIMARY, null);
            McpHttpSession.Answer conflict = operator.call("dns_zone_set", Map.of("origin", taken,
                "secondaries", List.of(peerCode)));
            assertThat(conflict.error()).as("step 3: refused " + conflict.text()).isTrue();
            assertThat(conflict.text()).as("step 3: naming the peer and why").contains(peerName)
                .contains("already keeps a zone");
            assertThat(zone(taken)).as("step 3: no zone made here").isNull();

            // 4. The peer answers the check but fails to make the copy: the zone stays, its link is taken back.
            String failing = "failing-" + tag + ".copy.test";
            AtomicInteger asked = new AtomicInteger();
            relay.serveBy(ADD_PATH, request -> asked.incrementAndGet() <= 2 ? this.resigned(request, peerKey)
                : StubHost.Answer.status(500, Map.of()));
            McpHttpSession.Answer failed = operator.call("dns_zone_set", Map.of("origin", failing,
                "secondaries", List.of(peerCode)));
            assertThat(failed.error()).as("step 4: refused " + failed.text()).isTrue();
            assertThat(failed.text()).as("step 4: naming the peer").contains(peerName).contains("did not make");
            assertThat(zone(failing)).as("step 4: the zone was made").isNotNull();
            assertThat(linked(failing, peerId)).as("step 4: its link taken back").isFalse();
            assertThat(zone(copyOf(failing))).as("step 4: no copy there").isNull();

            // 5. An older Hohenheim, which does not know the route: refused naming it, nothing made.
            relay.serve(ADD_PATH, StubHost.Answer.status(404, Map.of()));
            String older = "older-" + tag + ".copy.test";
            McpHttpSession.Answer old = operator.call("dns_zone_set", Map.of("origin", older,
                "secondaries", List.of(peerCode)));
            assertThat(old.error()).as("step 5: refused " + old.text()).isTrue();
            assertThat(old.text()).as("step 5: naming the peer as older").contains(peerName)
                .contains("older Hohenheim");
            assertThat(zone(older)).as("step 5: no zone made").isNull();

            // 6. The peer gone: refused saying it could not be asked, nothing made.
            relay.close();
            String gone = "gone-" + tag + ".copy.test";
            McpHttpSession.Answer unreached = operator.call("dns_zone_set", Map.of("origin", gone,
                "secondaries", List.of(peerCode)));
            assertThat(unreached.error()).as("step 6: refused " + unreached.text()).isTrue();
            assertThat(unreached.text()).as("step 6: naming the peer").contains(peerName)
                .contains("could not be asked");
            assertThat(zone(gone)).as("step 6: no zone made").isNull();
        }
    }

    /** @return the peer's answer to the request as it signs it again for the copy origin it keeps the zone under */
    private StubHost.Answer resigned(StubHost.Request request, String peerKey) {
        Map<String, String> form = DnsFixtures.formFields(request.body());
        String origin = copyOf(Objects.requireNonNull(form.get("origin")));
        String keyName = Objects.requireNonNull(form.get("key_name"));
        String signedAt = Objects.requireNonNull(form.get("signed_at"));
        String check = Objects.requireNonNull(form.get("check"));
        String signature = DnsPeerMirrors.addSignature(KEY_SECRET, keyName, Long.parseLong(signedAt), origin,
            Boolean.parseBoolean(check));
        return DnsRecordForwardingTest.relayed(requestTo(ADD_PATH)
            .header("Authorization", "Bearer " + peerKey)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(ApiSupport.form("key_name", keyName, "signed_at", signedAt,
                "signature", signature, "origin", origin, "check", check))));
    }

    /** Asserts the peer keeps a copy of the zone: a secondary of its copy origin, pulling from this server. */
    private static void assertCopied(String origin, int peerId, String step) {
        Row copy = zone(copyOf(origin));
        assertThat(copy).as(step + ": the peer keeps a copy").isNotNull();
        assertThat(DnsZoneModel.roleOf(copy)).as(step + ": a secondary").isEqualTo(DnsZoneModel.ROLE_SECONDARY);
        assertThat(copy.get(DnsZoneModel.PRIMARY_PEER_ID)).as(step + ": pulling from this server").isEqualTo(peerId);
    }

    /** @return the origin the peer keeps a zone's copy under: its first label marked */
    private static String copyOf(String origin) {
        return origin.replaceFirst("\\.", "-copy.");
    }

    private static @Nullable Row zone(String origin) {
        return Models.get(DnsZoneModel.class).find().noCache().where(DnsZoneModel.ORIGIN.eq(origin)).first();
    }

    private static boolean linked(String origin, int peerId) {
        Row zone = Objects.requireNonNull(zone(origin), origin);
        return Models.get(DnsZonePeerModel.class).find().noCache()
            .where(DnsZonePeerModel.ZONE_ID.eq(zone.get(DnsZoneModel.ID)))
            .where(DnsZonePeerModel.PEER_ID.eq(peerId)).first() != null;
    }
}
