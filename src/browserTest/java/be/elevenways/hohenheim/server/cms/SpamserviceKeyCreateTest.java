package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.refusal.ZenitRefusalReason;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.test.support.TestAccessContexts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** A Spamservice key create is a receipted command whose raw key is its one-time result. */
class SpamserviceKeyCreateTest extends HohenheimTestBase {

    private HttpServer server;

    @AfterEach
    void stopManagementApi() {
        if (this.server != null) this.server.stop(0);
    }

    @Test
    void aCreatedKeyIsItsOneTimeResultAndALostRetryIsRefusedSayingWhy() throws IOException {
        String clientId = UUID.randomUUID().toString();
        String keyId = UUID.randomUUID().toString();
        AtomicInteger creates = new AtomicInteger();
        SpamserviceClient client = client(clientId, keyId, creates);
        UUID invocation = UUID.randomUUID();

        // 1. The create mints one key and answers it with its raw value: the only place it is ever shown.
        SecretResult<String> created = create(client, clientId, invocation);
        assertThat(created.key()).as("step 1: the key is addressed under its client").isEqualTo(clientId + "~" + keyId);
        assertThat(created.secret().reveal()).as("step 1: the raw key is the result").isEqualTo("spam_once");
        assertThat(creates).as("step 1: one remote create").hasValue(1);

        // 2. The answer is lost and the same command is retried: the receipt kept no copy of the key, so the retry is
        //    refused with the reason instead of minting a second key.
        DomainRefusal retried = catchThrowableOfType(DomainRefusal.class, () -> create(client, clientId, invocation));
        assertThat(retried).as("step 2: the retry is refused").isNotNull();
        assertThat(retried.is(ZenitRefusalReason.SECRET_ALREADY_DISCLOSED))
            .as("step 2: because the key was shown once").isTrue();
        assertThat(creates).as("step 2: no second remote create").hasValue(1);
    }

    private static SecretResult<String> create(SpamserviceClient client, String clientId, UUID invocation) {
        return OperationPipeline.invoke(OperationRequest.of(SpamserviceClientKeysResource.CREATE,
                CmsPlacementSurface.ADMIN_ACTION)
            .caller(TestAccessContexts.allAllowed())
            .form(Map.of("client_id", clientId, "name", "primary"))
            .invocation(invocation)
            .attachment(SpamserviceClientKeysResource.CLIENTS, () -> client)).value();
    }

    /** A management API answering every key create with one generated key, counting the creates. */
    private SpamserviceClient client(String clientId, String keyId, AtomicInteger creates) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", exchange -> {
            creates.incrementAndGet();
            byte[] bytes = ("{\"id\":\"" + keyId + "\",\"client_id\":\"" + clientId
                + "\",\"name\":\"primary\",\"key\":\"spam_once\",\"generated\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        this.server.start();
        return SpamserviceClient.builder("http://127.0.0.1:" + this.server.getAddress().getPort(), "test-key").build();
    }
}
