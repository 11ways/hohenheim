package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.test.QueryConduits;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.schema.TableView;
import be.elevenways.zenit.common.data.RecordPage;
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
        PanelResource<ManagedClientKey> keys = SpamserviceClientKeysResource.create(current::get);
        TableView.Applied<ManagedClientKey> applied =
            TableView.forPrincipal(0L, keys.id()).build().apply(keys.list().table());
        Map<String, String> scope = Map.of();

        // 1. Request A lists while the service is unreachable -- and, like a lane that lists
        //    without rendering the notice, never reads what it learned.
        AccessContext requestA = QueryConduits.accessOf(QueryConduits.request(HohenheimSlugs.ADMIN, scope));
        assertThat(keys.list().childStorePages().page(clientId, applied, requestA).rows())
            .as("step 1: an unreachable service lists nothing").isEmpty();

        // 2. The service comes back. Request B, served next on this SAME thread, asks for its
        //    notice BEFORE listing: it learned nothing yet, so the notice says nothing -- never A's.
        current.set(this.serveOneKey(clientId, keyId));
        AccessContext requestB = QueryConduits.accessOf(QueryConduits.request(HohenheimSlugs.ADMIN, scope));
        assertThat(keys.list().notice(requestB))
            .as("step 2: request A's outage never reaches request B's page").isNull();

        // 3. B lists, and reads its OWN outcome: the rows, the total, no notice.
        RecordPage<ManagedClientKey> page = keys.list().childStorePages().page(clientId, applied, requestB);
        assertThat(page.rows()).as("step 3: request B lists the key").hasSize(1);
        assertThat(page.total()).as("step 3: with its own total").isEqualTo(1L);
        assertThat(keys.list().notice(requestB)).as("step 3: and no disconnected notice").isNull();

        // 4. Request A still reads what IT learned, however often it asks.
        assertThat(keys.list().notice(requestA)).as("step 4: request A keeps its own notice").isNotNull();
        assertThat(keys.list().notice(requestA)).as("step 4: reading it does not consume it").isNotNull();
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
