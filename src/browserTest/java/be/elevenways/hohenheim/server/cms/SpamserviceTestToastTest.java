package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.server.spamservice.ManagedServiceProcess;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.hohenheim.test.HohenheimTestBase;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.test.support.PanelResourceCalls;
import be.elevenways.zenit.common.flash.FlashLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The installation's real TEST invoke turns a client's refusal into the operator's error toast.
 *
 * @author Jelle De Loecker
 * @since 0.10.0
 */
class SpamserviceTestToastTest extends HohenheimTestBase {

    @Test
    void aFailingClientKeepsItsReasonOnTheTestErrorToast() throws Exception {
        String reason = "The management backend is deliberately unavailable";
        AtomicInteger calls = new AtomicInteger();
        HttpServer management = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        management.createContext("/v1/manage/status", exchange -> {
            calls.incrementAndGet();
            byte[] body = ("{\"error\":{\"code\":\"unavailable\",\"message\":\"" + reason + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
            exchange.close();
        });
        management.start();
        SpamserviceManager manager = SpamserviceManager.get();
        Map<Field, Object> original = new LinkedHashMap<>();
        ManagedServiceProcess process = null;
        try {
            // 1. The real availability check sees a configured, running installation with a management client.
            // AIDEV-NOTE: isolate the singleton's runtime facts, not the operation attachment: replacing its handler
            // would bypass the production client-failure-to-Violations path this regression needs to exercise.
            process = ManagedServiceProcess.start(Subprocess.of("sleep", "60"), text -> text);
            replace(manager, original, "configurationPresent", true);
            replace(manager, original, "configurationEnabled", true);
            replace(manager, original, "process", process);
            replace(manager, original, "client", SpamserviceClient.builder(
                "http://127.0.0.1:" + management.getAddress().getPort(), "test-key").build());
            assertThat(SpamserviceOperations.connectedReason(manager)).as("step 1: TEST is available").isNull();

            // 2. Invoke the registered HEADER operation through its authenticated, CSRF-protected HTTP route.
            String invoke = CmsRoutes.invoke(HohenheimSlugs.ADMIN, SpamserviceInstallationResource.SLUG,
                SpamserviceOperations.TEST.id()).toUrl();
            var answer = httpPostForm(invoke, PanelResourceCalls.createEnvelope(), sessionToken, csrfToken);
            assertThat(answer.statusCode()).as("step 2: the form-less invoke returns to the installation")
                .isBetween(300, 399);
            assertThat(landingOf(answer)).as("step 2: the error stays on its installation")
                .isEqualTo(CmsRoutes.list(HohenheimSlugs.ADMIN, SpamserviceInstallationResource.SLUG).toUrl());
            assertThat(calls.get()).as("step 2: the real client reached the failing backend once").isEqualTo(1);

            // 3. The one-shot flash retains the refusal key, scope, reason and ERROR appearance.
            var notice = popFlash(answer);
            assertThat(notice).as("step 3: the refusal is delivered as a toast").isNotNull();
            assertThat(notice.toast()).as("step 3: the toast is an error").isEqualTo(FlashLevel.ERROR.toast());
            assertThat(notice.message().key()).as("step 3: the specific refusal key survives").isEqualTo("test_failed");
            assertThat(notice.message().filters().asMap()).as("step 3: the spamservice catalog scope survives")
                .containsEntry("scope", "spamservice");
            assertThat(notice.message().args().asMap()).as("step 3: the client's reason survives verbatim")
                .containsEntry("reason", reason);
        } finally {
            for (var entry : original.entrySet()) entry.getKey().set(manager, entry.getValue());
            if (process != null) process.stop();
            management.stop(0);
        }
    }

    private static void replace(SpamserviceManager manager, Map<Field, Object> original, String name, Object value)
            throws ReflectiveOperationException {
        Field field = SpamserviceManager.class.getDeclaredField(name);
        field.setAccessible(true);
        original.put(field, field.get(manager));
        field.set(manager, value);
    }
}
