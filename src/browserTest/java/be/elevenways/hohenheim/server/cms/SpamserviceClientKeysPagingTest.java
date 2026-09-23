package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.QueryConduits;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client's keys are reachable however many it has, and a malformed client scope in the URL is
 * absence, never a 500.
 */
class SpamserviceClientKeysPagingTest {

    private static final int REMOTE_PAGE = 200;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    @Test
    void theTwoHundredAndFirstKeyIsEditableAndAMalformedScopeIsRefusedCleanly() throws IOException {
        String clientId = UUID.randomUUID().toString();
        List<String> keyIds = new ArrayList<>();
        for (int i = 0; i < REMOTE_PAGE + 1; i++) {
            keyIds.add(UUID.randomUUID().toString());
        }
        AtomicInteger requests = new AtomicInteger();
        SpamserviceClient client = this.serveKeys(clientId, keyIds, requests);
        SpamserviceClientKeysResource keys = new SpamserviceClientKeysResource(() -> client);

        // 1. The 201st key lives on the service's SECOND page. It used to be listed and then
        //    404 on edit/enable/revoke, because loading looked at the first 200 only.
        String last = keyIds.get(REMOTE_PAGE);
        ManagedClientKey loaded = keys.loadRow(keys.parsePrimaryKey(clientId + "~" + last),
            AccessContext.anonymous());
        assertThat(loaded).as("step 1: a key beyond the first remote page loads").isNotNull();
        assertThat(loaded.id()).as("step 1: and it is the key asked for").isEqualTo(last);

        // 2. A key the client does not hold walks the pages and answers null -- a bounded
        //    walk, stopping at the total the service reported.
        int before = requests.get();
        ManagedClientKey missing = keys.loadRow(
            keys.parsePrimaryKey(clientId + "~" + UUID.randomUUID()), AccessContext.anonymous());
        assertThat(missing).as("step 2: an unknown key is absent").isNull();
        assertThat(requests.get() - before).as("step 2: the walk stops at the reported total")
            .isEqualTo(2);

        // 3. A malformed ?client_id= on the create form is no prefill, not an exception.
        Conduit malformed = QueryConduits.request(HohenheimSlugs.ADMIN, Map.of("client_id", "not-a-uuid"));
        assertThat(keys.createValues(malformed))
            .as("step 3: a malformed scope prefills nothing").doesNotContainKey("client_id");

        // 4. ... and a well-formed one prefills the typed client id.
        Conduit scoped = QueryConduits.request(HohenheimSlugs.ADMIN, Map.of("client_id", clientId));
        assertThat(keys.createValues(scoped))
            .as("step 4: a valid scope prefills the client").containsEntry("client_id", UUID.fromString(clientId));

        // 5. The list under a malformed scope is empty and asks the service nothing.
        TableView.Applied<ManagedClientKey> applied =
            TableView.forPrincipal(0, keys.id()).build().apply(keys.tableSpec());
        int beforeList = requests.get();
        assertThat(keys.listRows(applied, QueryConduits.accessOf(malformed)))
            .as("step 5: a malformed scope lists nothing").isEmpty();
        assertThat(requests.get()).as("step 5: without a remote call").isEqualTo(beforeList);

        // 6. The list under the real scope reads the first page and reports the full total.
        AccessContext scopedAccess = QueryConduits.accessOf(scoped);
        assertThat(keys.listRows(applied, scopedAccess)).as("step 6: the scoped list has rows").isNotEmpty();
        assertThat(keys.countRows(applied, scopedAccess))
            .as("step 6: the total counts every key").isEqualTo(REMOTE_PAGE + 1L);
    }

    /** Serves {@code keyIds} for {@code clientId} in pages of the requested size. */
    private SpamserviceClient serveKeys(String clientId, List<String> keyIds, AtomicInteger requests)
            throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            requests.incrementAndGet();
            Map<String, String> query = query(exchange);
            int page = Integer.parseInt(query.getOrDefault("page", "1"));
            int size = Integer.parseInt(query.getOrDefault("page_size", "25"));
            StringBuilder items = new StringBuilder();
            int from = (page - 1) * size;
            for (int i = from; i < Math.min(keyIds.size(), from + size); i++) {
                if (items.length() > 0) {
                    items.append(',');
                }
                items.append("{\"id\":\"").append(keyIds.get(i)).append("\",\"client_id\":\"")
                    .append(clientId).append("\",\"name\":\"key-").append(i)
                    .append("\",\"active\":true,\"last_used\":null,\"created_at\":null}");
            }
            respond(exchange, "{\"items\":[" + items + "],\"page\":" + page + ",\"page_size\":" + size
                + ",\"total\":" + keyIds.size() + "}");
        });
        this.server.start();
        return SpamserviceClient.builder("http://127.0.0.1:" + this.server.getAddress().getPort(), "test-key")
            .build();
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        Map<String, String> values = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    values.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
