package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.QueryConduits;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.security.AccessContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one remote list read learned (its total, whether the service answered) belongs to the
 * REQUEST that read it, never to the pooled thread that served it.
 *
 * The defect this pins: the state rode two ThreadLocals cleared only by the read that followed,
 * so a list that was not followed by its notice left "disconnected" on the thread, and the
 * next page that thread served told an operator a healthy service was down.
 */
class SpamserviceRequestStateTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    @Test
    void aDisconnectedListNeverLeaksIntoTheNextRequestOnTheSameThread() throws IOException {
        String clientId = UUID.randomUUID().toString();
        String keyId = UUID.randomUUID().toString();
        AtomicReference<SpamserviceClient> current = new AtomicReference<>();
        SpamserviceClientKeysResource keys = new SpamserviceClientKeysResource(current::get);
        TableView.Applied<ManagedClientKey> applied =
            TableView.forPrincipal(0, keys.id()).build().apply(keys.tableSpec());
        Map<String, String> scope = Map.of("client_id", clientId);

        // 1. Request A lists while the service is unreachable -- and, like a lane that lists
        //    without rendering the notice, never reads what it learned.
        AccessContext requestA = QueryConduits.accessOf(QueryConduits.request(HohenheimSlugs.ADMIN, scope));
        assertThat(keys.listRows(applied, requestA)).as("step 1: an unreachable service lists nothing").isEmpty();

        // 2. The service comes back. Request B, served next on this SAME thread, asks for its
        //    notice and its total BEFORE listing: it learned nothing yet, so the notice says
        //    nothing, and the total is read from the service for B itself -- never A's.
        current.set(this.serveOneKey(clientId, keyId));
        AccessContext requestB = QueryConduits.accessOf(QueryConduits.request(HohenheimSlugs.ADMIN, scope));
        assertThat(keys.listNotice(requestB))
            .as("step 2: request A's outage never reaches request B's page").isNull();
        assertThat(keys.countRows(applied, requestB))
            .as("step 2: nor does its empty total; B reads its own").isEqualTo(1L);

        // 3. B lists, and reads its OWN outcome: the rows, the total, no notice.
        assertThat(keys.listRows(applied, requestB)).as("step 3: request B lists the key").hasSize(1);
        assertThat(keys.countRows(applied, requestB)).as("step 3: with its own total").isEqualTo(1L);
        assertThat(keys.listNotice(requestB)).as("step 3: and no disconnected notice").isNull();

        // 4. Request A still reads what IT learned, however often it asks.
        assertThat(keys.listNotice(requestA)).as("step 4: request A keeps its own notice").isNotNull();
        assertThat(keys.listNotice(requestA)).as("step 4: reading it does not consume it").isNotNull();
        // A negative total is refused by zenit-cms's RecordPage (a 500 on the list), so an
        // unreachable service's total is the nothing it listed.
        assertThat(keys.countRows(applied, requestA)).as("step 4: and its empty total").isZero();
    }

    private SpamserviceClient serveOneKey(String clientId, String keyId) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> respond(exchange,
            "{\"items\":[{\"id\":\"" + keyId + "\",\"client_id\":\"" + clientId
                + "\",\"name\":\"primary\",\"active\":true,\"last_used\":null,\"created_at\":null}],"
                + "\"page\":1,\"page_size\":25,\"total\":1}"));
        this.server.start();
        return SpamserviceClient.builder("http://127.0.0.1:" + this.server.getAddress().getPort(), "test-key")
            .build();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
