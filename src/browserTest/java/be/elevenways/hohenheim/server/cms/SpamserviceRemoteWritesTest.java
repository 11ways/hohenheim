package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.test.HohenheimHttpTestBase;
import be.elevenways.spamservice.client.ManagedClient;
import be.elevenways.spamservice.client.ManagedClientKey;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.operation.Operation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.test.support.TestAccessContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A spam client's and a spam key's change, the operations the panel's form and a tool both run: a client's is a
 * revision-guarded PUT of the whole client over its stored one, a key's sends only what it carries.
 *
 * AIDEV-NOTE: a client's remote update is a full-DTO PUT, and the inline cell lane hands the update a map holding
 * EXACTLY ONE entry -- so a rename used to PUT a disabled client with no language whitelist and a reset threshold to
 * the live spam filter. The change's patch base is the stored client, never an assumption that the remote API treats
 * absence as unchanged. The one place absence IS a documented "leave alone" is updateKey's nullable arguments, which
 * the key's change and the enable/revoke row actions rely on.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
class SpamserviceRemoteWritesTest extends HohenheimHttpTestBase {

    private HttpServer server;

    @AfterEach
    void stopManagementApi() {
        if (this.server != null) this.server.stop(0);
    }

    @Test
    void aClientChangeSendsTheWholeClientAndAKeyChangeOnlyWhatItCarries() throws IOException {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        String clientId = UUID.randomUUID().toString();
        String keyId = UUID.randomUUID().toString();
        SpamserviceClient client = this.client(clientId, method, body);

        // 1. Every setting sent: a revision-guarded PUT of them, without the provisioning facts.
        ManagedClient existing = new ManagedClient(clientId, "Original", true, true, false, false,
            "owned", null, "eng", 50, null, null, null, "r1");
        change(SpamserviceClientsResource.CHANGE, client, existing, Map.of("name", "Renamed", "enabled", true,
            "trusted", true, "provisioner", false, "manager", false, "allowed_languages", "eng",
            "spam_threshold", 55, "notes", "note"));
        assertThat(method.get()).as("step 1: a PUT").isEqualTo("PUT");
        assertThat(body.get()).as("step 1: guarded by the revision read, never the provisioning facts")
            .contains("\"revision\":\"r1\"").doesNotContain("external_id").doesNotContain("provisioned_by_client_id");

        // 2. A trusted, provisioning client with a language whitelist and a raised threshold, renamed alone: every
        //    one of them a setting a blank PUT would silently drop.
        ManagedClient provisioning = new ManagedClient(clientId, "Original", true, true, true, false,
            "owned", null, "eng,nld", 80, "keep this note", null, null, "r1");
        change(SpamserviceClientsResource.CHANGE, client, provisioning, Map.of("name", "Renamed"));
        assertThat(body.get()).as("step 2: the rename is the only field that moved")
            .contains("\"name\":\"Renamed\"").contains("\"enabled\":true")
            .contains("\"trusted\":true").contains("\"provisioner\":true")
            .contains("eng,nld").contains("\"spam_threshold\":80").contains("keep this note");

        // 3. A client whose stored name is null, changed by a write that carries no name: refused or sent, its name
        //    is never the four characters "null".
        body.set("");
        ManagedClient unnamed = new ManagedClient(clientId, null, true, false, false, false, "owned", null, "eng",
            50, null, null, null, "r1");
        Throwable unsent = catchThrowable(() -> change(SpamserviceClientsResource.CHANGE, client, unnamed,
            Map.of("enabled", true)));
        if (unsent != null) {
            assertThat(unsent).as("step 3: refused as a write, never a crash").isInstanceOf(Violations.class);
        }
        assertThat(body.get()).as("step 3: never renamed to the text \"null\"").doesNotContain("\"name\":\"null\"");

        // 4. Nor by a name the write DOES carry as null, what a blank submitted entry coerces to.
        body.set("");
        Map<String, Object> blankName = new HashMap<>();
        blankName.put("name", null);
        Throwable blank = catchThrowable(() -> change(SpamserviceClientsResource.CHANGE, client, provisioning,
            blankName));
        assertThat(blank).as("step 4: a blank name is refused as the name it is").isInstanceOf(Violations.class);
        assertThat(body.get()).as("step 4: and nothing is sent, least of all the text \"null\"").isEmpty();

        // 5. A key's change that carries no name sends none: it only turns the key off.
        ManagedClientKey key = new ManagedClientKey(keyId, clientId, "primary", true, null, null);
        change(SpamserviceClientKeysResource.CHANGE, client, key, Map.of("enabled", false));
        assertThat(method.get()).as("step 5: a PATCH").isEqualTo("PATCH");
        assertThat(body.get()).as("step 5: an absent name is sent as absent, never as \"null\"")
            .doesNotContain("name").contains("\"active\":false");
    }

    /** Runs a change as the panel's form and a tool ask it: the entries sent, over the record the service holds. */
    private static <S, I, R> void change(Operation<S, I, R> operation, SpamserviceClient client, S existing,
                                         Map<String, Object> entries) {
        OperationPipeline.invoke(OperationRequest.of(operation, CmsPlacementSurface.ADMIN_ACTION)
            .caller(TestAccessContexts.allAllowed())
            .subjects(List.of(existing))
            .patch(entries)
            .attachment(SpamserviceRemoteStore.CLIENTS, () -> client));
    }

    /** A management API answering every call with the client, remembering the last method and body. */
    private SpamserviceClient client(String clientId, AtomicReference<String> method, AtomicReference<String> body)
            throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ("{\"id\":\"" + clientId + "\",\"code\":\"SPC1\",\"name\":\"Renamed\",\"enabled\":true,"
                + "\"trusted\":true,\"provisioner\":false,\"manager\":false,\"external_id\":\"owned\","
                + "\"provisioned_by_client_id\":null,\"allowed_languages\":\"eng\",\"spam_threshold\":55,"
                + "\"notes\":\"note\",\"created_at\":null,\"updated_at\":null,\"revision\":\"r2\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        this.server.start();
        return SpamserviceClient.builder("http://127.0.0.1:" + this.server.getAddress().getPort(), "test-key").build();
    }
}
