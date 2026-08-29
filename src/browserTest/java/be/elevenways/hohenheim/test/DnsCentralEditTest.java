package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.dns.DnsRecordDto;
import be.elevenways.hohenheim.dns.DnsRecordListResponse;
import be.elevenways.hohenheim.model.DnsPeerModel;
import be.elevenways.hohenheim.model.DnsRecordModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.server.dns.DnsFederationKeys;
import be.elevenways.hohenheim.server.dns.DnsPeerApi;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.AuthCookieSupport;
import be.elevenways.zenit.auth.server.AuthModels;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.xbill.DNS.TSIG;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static be.elevenways.hohenheim.test.DnsFixtures.apiPeer;
import static be.elevenways.hohenheim.test.DnsFixtures.createZone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Central DNS editing: the record API an owning instance exposes, and the
 * viewing instance's read-through + edit-forwarding on a secondary zone's
 * Records tab (against a scripted peer over real HTTP).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DnsCentralEditTest extends HohenheimTestBase {

    private static String apiKey;
    private static int ownedZoneId;
    private static PeerStub stub;

    /** One recorded call on the scripted peer. */
    record StubCall(String method, String path, String authorization, String body) {}

    /** Minimal scripted peer: records every call, answers from a settable script. */
    private static final class PeerStub implements AutoCloseable {
        final HttpServer server;
        final List<StubCall> calls = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile String body = "{}";

        PeerStub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                calls.add(new StubCall(exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    requestBody));
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.close();
            stub = null;
        }
    }

    // ------------------------------------------------------------------
    // The owner-side record API
    // ------------------------------------------------------------------

    /** Full record CRUD over the owner API, plus its refusals on secondary and unknown zones. */
    @Test
    @Order(1)
    void ownerSideRecordApiJourney() throws Exception {
        ownedZoneId = createZone("owned.example", DnsZoneModel.ROLE_PRIMARY, null);

        Row user = AuthModels.users().find()
            .where(UserModel.EMAIL.eq("test@hohenheim.local")).first();
        apiKey = ApiKeyService.create(user.get(UserModel.ID), "dns-central-test",
            List.of("hohenheim.*"), null).plaintext();

        // A session cookie must NOT be able to act on the csrf-exempt API route.
        var sessionAttempt = postForm("/api/dns/zones/owned.example/records",
            "name=www&type=A&value=192.0.2.1");
        assertThat(sessionAttempt.statusCode()).isEqualTo(403);

        long serialBefore = zoneSerial(ownedZoneId);

        // Create.
        var created = apiPost("/api/dns/zones/owned.example/records",
            "name=www&type=A&value=192.0.2.1&ttl=300");
        assertThat(created.statusCode()).isEqualTo(200);
        int recordId = recordIdOf("owned.example", "www");
        assertThat(created.body()).isEqualTo("{\"id\":" + recordId + "}");
        assertThat(zoneSerial(ownedZoneId)).isGreaterThan(serialBefore);

        // List includes it, with its id.
        var list = apiGet("/api/dns/zones/owned.example/records");
        assertThat(list.statusCode()).isEqualTo(200);
        DnsRecordListResponse listed = Zenit.DRY.fromJson(list.body(), DnsRecordListResponse.class);
        assertThat(listed.zone()).isEqualTo("owned.example");
        assertThat(listed.records()).singleElement().satisfies(record -> {
            assertThat(record).isInstanceOf(DnsRecordDto.class);
            assertThat(record.id()).isEqualTo(recordId);
            assertThat(record.name()).isEqualTo("www");
            assertThat(record.value()).isEqualTo("192.0.2.1");
            assertThat(record.ttl()).isEqualTo(300);
        });
        assertThat(list.body()).contains("\"managed_by\":null");

        // Update only the value; the rest keeps its stored state.
        var updated = apiPost("/api/dns/zones/owned.example/records/" + recordId, "value=192.0.2.7");
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).isEqualTo("{\"id\":" + recordId + "}");
        Row record = Models.get(DnsRecordModel.class).findById(recordId);
        assertThat((String) record.get(DnsRecordModel.VALUE)).isEqualTo("192.0.2.7");
        assertThat((String) record.get(DnsRecordModel.NAME)).isEqualTo("www");
        assertThat((Integer) record.get(DnsRecordModel.TTL)).isEqualTo(300);

        // Validation refusals answer 422 with the violation key.
        var invalid = apiPost("/api/dns/zones/owned.example/records", "name=bad&type=A&value=not-an-ip");
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("\"error\":\"validation\"");

        // Delete.
        var deleted = apiPost("/api/dns/zones/owned.example/records/" + recordId + "/delete", "");
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(deleted.body()).isEqualTo("{\"status\":\"deleted\"}");
        assertThat(Models.get(DnsRecordModel.class).findById(recordId)).isNull();

        // Secondary zones are not writable through the API, unknown zones 404.
        int peerId = apiPeer("api-refusal-peer", null);
        createZone("replica.example", DnsZoneModel.ROLE_SECONDARY, peerId);

        var refused = apiPost("/api/dns/zones/replica.example/records", "name=www&type=A&value=192.0.2.1");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("not_primary");

        var unknown = apiGet("/api/dns/zones/nobody.example/records");
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // The viewing-side read-through + forwarding
    // ------------------------------------------------------------------

    /** The viewing instance reads through the owning peer, forwards edits, and degrades gracefully. */
    @Test
    @Order(2)
    void centralEditThroughTheOwningPeerJourney() throws Exception {
        stub = new PeerStub();
        int centralPeerId = apiPeer("central-peer", stub.baseUrl());
        int zoneId = createZone("central.example", DnsZoneModel.ROLE_SECONDARY, centralPeerId);

        stub.body = "{\"zone\":\"central.example\",\"serial\":9,\"records\":["
            + "{\"id\":5,\"name\":\"www\",\"type\":\"A\",\"ttl\":300,\"value\":\"198.51.100.9\",\"enabled\":true}]}";

        DnsPeerApi api = DnsPeerApi.forPeer(Models.get(DnsPeerModel.class).findById(centralPeerId));
        assertThat(api).isNotNull();
        assertThat(api.listRecords("central.example")).singleElement().satisfies(record -> {
            assertThat(record).isInstanceOf(DnsRecordDto.class);
            assertThat(record.id()).isEqualTo(5);
            assertThat(record.ttl()).isEqualTo(300);
        });

        var recordsTab = get("/admin/dns-zones/" + zoneId + "/page/records");
        assertThat(recordsTab.statusCode()).isEqualTo(200);
        assertThat(recordsTab.body()).contains("198.51.100.9");
        assertThat(recordsTab.body()).contains("central-peer"); // the forwarded-edits banner names the owner

        StubCall listCall = stub.calls.get(stub.calls.size() - 1);
        assertThat(listCall.method()).isEqualTo("GET");
        assertThat(listCall.path()).isEqualTo("/api/dns/zones/central.example/records");
        assertThat(listCall.authorization()).isEqualTo("Bearer test-peer-key");

        var editPage = get("/admin/dns-zones/" + zoneId + "/page/records?record=5");
        assertThat(editPage.statusCode()).isEqualTo(200);
        assertThat(editPage.body()).contains("name=\"record_id\" value=\"5\"")
            .contains("198.51.100.9");

        // Remote record edits are forwarded to the owner.
        stub.calls.clear();
        stub.status = 200;
        stub.body = "{\"id\":6}";
        var created = postForm("/admin/dns-zones/" + zoneId + "/remote-records",
            "name=api&type=CNAME&value=owned.example.&ttl=&priority=&weight=&port=&enabled=true");
        assertThat(created.statusCode()).isEqualTo(302);
        assertThat(created.headers().firstValue("Location").orElse(""))
            .describedAs("the confirmation rides the session flash, so the URL stays clean")
            .doesNotContain("saved");
        var savedFlash = popFlash();
        assertThat(savedFlash).describedAs("the save stashes a confirmation flash").isNotNull();
        assertThat(savedFlash.message().key()).isEqualTo("edit_saved");

        StubCall createCall = stub.calls.get(stub.calls.size() - 1);
        assertThat(createCall.method()).isEqualTo("POST");
        assertThat(createCall.path()).isEqualTo("/api/dns/zones/central.example/records");
        assertThat(createCall.authorization()).isEqualTo("Bearer test-peer-key");
        assertThat(createCall.body()).contains("name=api").contains("type=CNAME")
            .contains("value=" + URLEncoder.encode("owned.example.", StandardCharsets.UTF_8));

        // Update and delete address the owner's record id.
        stub.calls.clear();
        stub.body = "";
        var updated = postForm("/admin/dns-zones/" + zoneId + "/remote-records",
            "record_id=6&name=api&type=CNAME&value=other.example.&enabled=true");
        assertThat(updated.headers().firstValue("Location").orElse("")).doesNotContain("saved");
        assertThat(popFlash()).isNotNull()
            .extracting(flash -> flash.message().key()).isEqualTo("edit_saved");
        assertThat(stub.calls.get(0).path()).isEqualTo("/api/dns/zones/central.example/records/6");

        stub.calls.clear();
        var deleted = postForm("/admin/dns-zones/" + zoneId + "/remote-records",
            "action=delete&record_id=6");
        assertThat(deleted.headers().firstValue("Location").orElse("")).doesNotContain("saved");
        assertThat(popFlash()).isNotNull()
            .extracting(flash -> flash.message().key()).isEqualTo("edit_saved");
        assertThat(stub.calls.get(0).path()).isEqualTo("/api/dns/zones/central.example/records/6/delete");

        stub.body = "{\"records\":[{\"name\":\"missing-id\",\"type\":\"A\",\"value\":\"192.0.2.1\",\"enabled\":true}]}";
        DnsPeerApi staleApi = DnsPeerApi.forPeer(Models.get(DnsPeerModel.class)
            .findById(zonePeerId("central.example")));
        assertThatThrownBy(() -> staleApi.listRecords("central.example"))
            .isInstanceOf(DnsPeerApi.PeerApiException.class)
            .hasMessage("Unexpected response from peer");

        // The peer client maps create/update/delete validation refusals.
        DnsPeerApi refusalApi = DnsPeerApi.forPeer(Models.get(DnsPeerModel.class)
            .findById(zonePeerId("central.example")));
        assertThat(refusalApi).isNotNull();
        stub.status = 422;

        stub.body = validationRefusal("name", "create_refused", "Create refused");
        assertThatThrownBy(() -> refusalApi.createRecord("central.example", Map.of("name", "www")))
            .isInstanceOfSatisfying(DnsPeerApi.PeerApiException.class, refusal -> {
                assertThat(refusal.getViolationField()).isEqualTo("name");
                assertThat(refusal.getViolationKey()).isEqualTo("create_refused");
                assertThat(refusal.getMessage()).isEqualTo("Create refused");
            });

        stub.body = validationRefusal("value", "update_refused", "Update refused");
        assertThatThrownBy(() -> refusalApi.updateRecord("central.example", 5, Map.of("value", "bad")))
            .isInstanceOfSatisfying(DnsPeerApi.PeerApiException.class, refusal -> {
                assertThat(refusal.getViolationField()).isEqualTo("value");
                assertThat(refusal.getViolationKey()).isEqualTo("update_refused");
                assertThat(refusal.getMessage()).isEqualTo("Update refused");
            });

        stub.body = validationRefusal("id", "delete_refused", "Delete refused");
        assertThatThrownBy(() -> refusalApi.deleteRecord("central.example", 5))
            .isInstanceOfSatisfying(DnsPeerApi.PeerApiException.class, refusal -> {
                assertThat(refusal.getViolationField()).isEqualTo("id");
                assertThat(refusal.getViolationKey()).isEqualTo("delete_refused");
                assertThat(refusal.getMessage()).isEqualTo("Delete refused");
            });

        // The owner's validation refusal (by microcopy key) resolves locally.
        stub.calls.clear();
        stub.status = 422;
        stub.body = "{\"error\":\"validation\",\"field\":\"value\",\"key\":\"dns_record_duplicate\"}";
        var refused = postForm("/admin/dns-zones/" + zoneId + "/remote-records",
            "name=www&type=A&value=198.51.100.9&enabled=true");
        String location = refused.headers().firstValue("Location").orElse("");
        assertThat(location)
            .describedAs("the refusal rides the session flash, never the URL")
            .doesNotContain("error=");
        // The violation KEY round-trips: both instances ship the same catalogs, so the
        // toast resolves in the reader's own locale rather than the peer's.
        assertThat(popFlash()).isNotNull()
            .extracting(flash -> flash.message().key()).isEqualTo("dns_record_duplicate");

        // An unreachable owner degrades the tab to the read-only replica view.
        stub.close();
        var fallback = get("/admin/dns-zones/" + zoneId + "/page/records");
        assertThat(fallback.statusCode()).isEqualTo(200);
        assertThat(fallback.body()).doesNotContain("add-remote-record-link");
    }

    /**
     * The remote-record delete form confirms through the shell dialog (its
     * {@code use:CmsConfirm.destructive} directive) before forwarding to the owner.
     *
     * AIDEV-NOTE: counterfactual (pre-conversion): the form carried a DEAD data-confirm
     * attribute, so the first click forwarded the delete immediately -- the dialog
     * assertion fails and the cancel branch already finds the delete call on the stub.
     */
    @Test
    @Order(3)
    void remoteRecordDeleteConfirmsThroughTheShellDialog() throws Exception {
        stub = new PeerStub();
        int peerId = apiPeer("confirm-peer", stub.baseUrl());
        int zoneId = createZone("confirm.example", DnsZoneModel.ROLE_SECONDARY, peerId);

        stub.body = "{\"zone\":\"confirm.example\",\"serial\":3,\"records\":["
            + "{\"id\":7,\"name\":\"www\",\"type\":\"A\",\"ttl\":300,\"value\":\"198.51.100.7\",\"enabled\":true}]}";

        navigateToApp("/admin/dns-zones/" + zoneId + "/page/records");
        waitForHydration();

        String deleteButton = "form:has(input[name='action'][value='delete']) pl-button";
        waitForSelector(deleteButton);
        stub.calls.clear();

        // Cancel does NOT forward a delete to the owner.
        click(deleteButton);
        assertIsVisible(".pl-alertdialog-modal[data-open]");
        click("[data-cms-confirm-cancel]");
        assertIsNotVisible(".pl-alertdialog-modal");
        page.waitForTimeout(400);
        assertThat(stub.calls).as("cancel must not forward the delete").isEmpty();

        // Confirm forwards the delete to the owning peer.
        click(deleteButton);
        assertIsVisible(".pl-alertdialog-modal[data-open]");
        click("[data-cms-confirm-ok]");
        page.waitForCondition(() -> stub.calls.stream()
            .anyMatch(call -> "/api/dns/zones/confirm.example/records/7/delete".equals(call.path())));
    }

    /**
     * Transfer-key negotiation, both halves: this instance mints a shared TSIG key onto a
     * Hohenheim peer, and the endpoint it exposes installs a peer's key here.
     *
     * AIDEV-NOTE: the two halves are the SAME endpoint -- the client half calls
     * /api/dns/peer-key on the stub, the server half is this instance answering it. A
     * change that breaks the symmetry fails one of the two here.
     */
    @Test
    @Order(4)
    void transferKeyNegotiationJourney() throws Exception {
        if (stub != null) {
            stub.close();
        }
        stub = new PeerStub();
        int peerId = apiPeer("negotiate-peer", stub.baseUrl());
        DnsPeerModel peers = Models.get(DnsPeerModel.class);

        // 1. The key name is derived from both instance names, so the peer can be told
        //    exactly what to echo back.
        String localName = DnsFederationKeys.localName();
        String keyName = DnsFederationKeys.keyNameFor(localName, "negotiate-peer");
        assertThat(keyName).startsWith("xfer-");
        stub.status = 200;
        stub.body = "{\"status\":\"ok\",\"key_name\":\"" + keyName + "\",\"peer\":\"us\"}";

        var negotiated = postForm("/admin/dns-peers/" + peerId + "/action/negotiate_transfer_key", confirmed(""));
        assertThat(negotiated.statusCode()).describedAs("the action runs").isIn(200, 302, 303);

        // 2. The peer was called on the symmetric endpoint, with the API key.
        StubCall call = stub.calls.get(stub.calls.size() - 1);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/dns/peer-key");
        assertThat(call.authorization()).isEqualTo("Bearer test-peer-key");
        assertThat(call.body()).contains("key_name=" + keyName)
            .contains("algorithm=hmac-sha256")
            .contains("peer=" + URLEncoder.encode(localName, StandardCharsets.UTF_8));

        // 3. Both sides hold the SAME material: what went over the wire is what was stored.
        String sentSecret = formValue(call.body(), "secret");
        Row stored = peers.findById(peerId);
        assertThat((String) stored.get(DnsPeerModel.TSIG_KEY_NAME)).isEqualTo(keyName);
        assertThat((String) stored.get(DnsPeerModel.TSIG_ALGORITHM)).isEqualTo("hmac-sha256");
        assertThat((String) stored.get(DnsPeerModel.TSIG_SECRET)).isEqualTo(sentSecret);
        // ...and it is usable TSIG material, not just a random string.
        assertThat(new TSIG(TSIG.HMAC_SHA256, keyName + ".", sentSecret)).isNotNull();

        // 4. Falsification -- a peer confirming a DIFFERENT key name stores nothing: the
        //    two sides would look each other up under names that never match.
        stub.body = "{\"status\":\"ok\",\"key_name\":\"xfer-somebody-else\",\"peer\":\"us\"}";
        postForm("/admin/dns-peers/" + peerId + "/action/negotiate_transfer_key", confirmed(""));
        assertThat((String) peers.findById(peerId).get(DnsPeerModel.TSIG_SECRET))
            .describedAs("a mismatched confirmation must not rotate the working key")
            .isEqualTo(sentSecret);

        // 5. Falsification -- a peer that refuses leaves the working key alone too.
        stub.status = 500;
        stub.body = "nope";
        postForm("/admin/dns-peers/" + peerId + "/action/negotiate_transfer_key", confirmed(""));
        assertThat((String) peers.findById(peerId).get(DnsPeerModel.TSIG_SECRET))
            .isEqualTo(sentSecret);

        // 6. The receiving half: a peer installs its key HERE over the same endpoint.
        String incomingSecret = DnsFederationKeys.mintSecret();
        var installed = apiPost("/api/dns/peer-key",
            "peer=office&key_name=xfer-office-us&algorithm=hmac-sha256"
            + "&secret=" + URLEncoder.encode(incomingSecret, StandardCharsets.UTF_8));
        assertThat(installed.statusCode()).isEqualTo(200);
        assertThat(installed.body()).contains("xfer-office-us");
        Row incoming = peers.findByTsigKeyName("xfer-office-us");
        assertThat(incoming).isNotNull();
        assertThat((String) incoming.get(DnsPeerModel.TSIG_SECRET)).isEqualTo(incomingSecret);
        assertThat(DnsPeerModel.typeOf(incoming))
            .describedAs("we hold no admin credentials for the caller")
            .isEqualTo(DnsPeerModel.TYPE_NAMESERVER);

        // 7. Re-negotiating rotates the SAME row rather than growing a second peer.
        String rotated = DnsFederationKeys.mintSecret();
        var again = apiPost("/api/dns/peer-key",
            "peer=office&key_name=xfer-office-us&algorithm=hmac-sha256"
            + "&secret=" + URLEncoder.encode(rotated, StandardCharsets.UTF_8));
        assertThat(again.statusCode()).isEqualTo(200);
        Row rotatedRow = peers.findByTsigKeyName("xfer-office-us");
        assertThat(rotatedRow.get(DnsPeerModel.ID)).isEqualTo(incoming.get(DnsPeerModel.ID));
        assertThat((String) rotatedRow.get(DnsPeerModel.TSIG_SECRET)).isEqualTo(rotated);

        // 8. Falsification of the guards: unusable material and an unknown algorithm are
        //    refused before storage, and a session cookie can never plant a key at all.
        assertThat(apiPost("/api/dns/peer-key",
            "peer=bad&key_name=xfer-bad&algorithm=hmac-sha256&secret=not-base-64!!")
            .statusCode()).isEqualTo(422);
        assertThat(apiPost("/api/dns/peer-key",
            "peer=bad&key_name=xfer-bad&algorithm=rot13&secret="
            + URLEncoder.encode(DnsFederationKeys.mintSecret(), StandardCharsets.UTF_8))
            .statusCode()).isEqualTo(422);
        assertThat(postForm("/api/dns/peer-key",
            "peer=bad&key_name=xfer-bad&algorithm=hmac-sha256&secret="
            + URLEncoder.encode(DnsFederationKeys.mintSecret(), StandardCharsets.UTF_8))
            .statusCode()).isEqualTo(403);
        assertThat(peers.findByTsigKeyName("xfer-bad"))
            .describedAs("no refused negotiation may leave a row behind").isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures + plumbing
    // ------------------------------------------------------------------

    /** One field out of a urlencoded form body. */
    private static String formValue(String body, String field) {
        for (String pair : body.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && pair.substring(0, split).equals(field)) {
                return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("no " + field + " in the negotiation body");
    }

    private static long zoneSerial(int zoneId) {
        Integer serial = Models.get(DnsZoneModel.class).findById(zoneId).get(DnsZoneModel.SERIAL);
        return serial != null ? serial : 0;
    }

    private static int zoneIdOf(String origin) {
        return Models.get(DnsZoneModel.class).findByOrigin(origin).get(DnsZoneModel.ID);
    }

    private static int zonePeerId(String origin) {
        return Models.get(DnsZoneModel.class).findByOrigin(origin).get(DnsZoneModel.PRIMARY_PEER_ID);
    }

    private static String validationRefusal(String field, String key, String message) {
        return "{\"error\":\"validation\",\"field\":\"" + field + "\",\"key\":\"" + key
            + "\",\"message\":\"" + message + "\"}";
    }

    private static int recordIdOf(String origin, String name) {
        int zoneId = zoneIdOf(origin);
        Row record = Models.get(DnsRecordModel.class).find()
            .where(DnsRecordModel.ZONE_ID.eq(zoneId))
            .where(DnsRecordModel.NAME.eq(name))
            .first();
        return record.get(DnsRecordModel.ID);
    }

    private HttpResponse<String> apiGet(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Authorization", "Bearer " + apiKey)
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> apiPost(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + getServerPort() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", AuthCookieSupport.sessionCookieName() + "=" + sessionToken)
            .header("X-Csrf-Token", csrfToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }
}
