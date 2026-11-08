package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.source.GitProviderClient;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.server.source.GithubProviderKind;
import be.elevenways.hohenheim.server.source.GitlabProviderKind;
import be.elevenways.hohenheim.server.source.SshProviderKind;
import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.ServedApp;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Registering an app's push webhook through each forge's own API, against a FAKE forge keeping the hooks it was
 * given: created once, brought up to date (never doubled) on a second press, in each forge's wire shape; a provider
 * kind without an API says so instead of pretending.
 */
class GitWebhookRegistrationTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final String PAYLOAD_URL = "https://app.example.test/_hohenheim/git-webhook/7";

    private static HttpServer server;
    private static OutboundFixture outbound;
    private static String base;

    /** The hooks the fake forge holds, per collection path, each as its parsed body plus its id. */
    private static final Map<String, List<Map<String, Object>>> HOOKS = new LinkedHashMap<>();
    /** Each request the fake received: method and path. */
    private static final List<String> TRAFFIC = new ArrayList<>();

    @BeforeAll
    static void startFakeForge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", GitWebhookRegistrationTest::answer);
        server.start();
        outbound = OutboundFixture.route("forge.example.test", server.getAddress().getPort());
        base = "http://" + outbound.host() + ":" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeForge() {
        if (outbound != null) outbound.close();
        if (server != null) server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private static synchronized void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        TRAFFIC.add(method + " " + path);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.endsWith("/hooks")) {
            List<Map<String, Object>> hooks = HOOKS.computeIfAbsent(path, key -> new ArrayList<>());
            if (method.equals("GET")) {
                // Pages as the forges do: per_page (GitHub, GitLab) or limit (Gitea), from page 1.
                Map<String, String> query = new LinkedHashMap<>();
                String raw = exchange.getRequestURI().getRawQuery();
                for (String pair : raw == null ? new String[0] : raw.split("&")) {
                    String[] parts = pair.split("=", 2);
                    query.put(parts[0], parts.length > 1 ? parts[1] : "");
                }
                int size = Integer.parseInt(query.getOrDefault("per_page", query.getOrDefault("limit", "30")));
                int page = Integer.parseInt(query.getOrDefault("page", "1"));
                int from = Math.min(hooks.size(), (page - 1) * size);
                respond(exchange, 200, Json.stringify(hooks.subList(from, Math.min(hooks.size(), from + size))));
                return;
            }
            Map<String, Object> hook = new LinkedHashMap<>((Map<String, Object>) new Dry().parse(body));
            hook.put("id", hooks.size() + 1);
            hooks.add(hook);
            respond(exchange, 201, Json.stringify(hook));
            return;
        }
        int slash = path.lastIndexOf('/');
        List<Map<String, Object>> hooks = HOOKS.get(path.substring(0, slash));
        if (hooks != null && (method.equals("PATCH") || method.equals("PUT"))) {
            int id = Integer.parseInt(path.substring(slash + 1));
            Map<String, Object> hook = new LinkedHashMap<>((Map<String, Object>) new Dry().parse(body));
            hook.put("id", id);
            hooks.set(id - 1, hook);
            respond(exchange, 200, Json.stringify(hook));
            return;
        }
        respond(exchange, 404, "{\"message\":\"Not Found\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static Row provider(int id, String kind) {
        Row provider = Models.get(GitProviderModel.class).createEmptyRow();
        provider.set(GitProviderModel.ID, id);
        provider.set(GitProviderModel.NAME, "fake-" + kind);
        provider.set(GitProviderModel.KIND, kind);
        provider.set(GitProviderModel.BASE_URL, base);
        provider.set(GitProviderModel.ACCESS_TOKEN, "forge-token");
        return provider;
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachForgeGetsOneWebhookCreatedThenUpdated() throws Exception {
        // 1. Gitea: a gitea hook on push and pull_request, the secret signing JSON payloads.
        GitProviderClient gitea = GitProviders.clientFor(provider(9301, GiteaProviderKind.ID.toString()));
        assertThat(gitea.registersWebhooks()).as("step 1: an API forge registers webhooks").isTrue();
        assertThat(gitea.ensurePushWebhook("acme/app", PAYLOAD_URL, "first-secret"))
            .as("step 1: the first press creates it").isEqualTo(GitProviderClient.HookRegistration.CREATED);
        List<Map<String, Object>> giteaHooks = HOOKS.get("/api/v1/repos/acme/app/hooks");
        assertThat(giteaHooks).as("step 1: one hook").hasSize(1);
        assertThat(giteaHooks.getFirst()).containsEntry("type", "gitea").containsEntry("active", true);
        assertThat((Map<String, Object>) giteaHooks.getFirst().get("config"))
            .containsEntry("url", PAYLOAD_URL).containsEntry("secret", "first-secret")
            .containsEntry("content_type", "json");
        assertThat((List<Object>) giteaHooks.getFirst().get("events")).containsExactly("push", "pull_request");

        // 2. A second press (a rotated secret) updates that one hook in place: a doubled hook deploys twice.
        assertThat(gitea.ensurePushWebhook("acme/app", PAYLOAD_URL, "rotated-secret"))
            .as("step 2: the second press updates").isEqualTo(GitProviderClient.HookRegistration.UPDATED);
        assertThat(giteaHooks).as("step 2: still one hook").hasSize(1);
        assertThat((Map<String, Object>) giteaHooks.getFirst().get("config"))
            .as("step 2: carrying the new secret").containsEntry("secret", "rotated-secret");
        assertThat(TRAFFIC).as("step 2: through Gitea's PATCH").contains("PATCH /api/v1/repos/acme/app/hooks/1");

        // 3. GitLab: a project hook on the whole nested path, the secret as its token, edited with PUT.
        GitProviderClient gitlab = GitProviders.clientFor(provider(9302, GitlabProviderKind.ID.toString()));
        gitlab.ensurePushWebhook("group/sub/app", PAYLOAD_URL, "lab-secret");
        gitlab.ensurePushWebhook("group/sub/app", PAYLOAD_URL, "lab-rotated");
        List<Map<String, Object>> gitlabHooks = HOOKS.get("/api/v4/projects/group%2Fsub%2Fapp/hooks");
        assertThat(gitlabHooks).as("step 3: one project hook").hasSize(1);
        assertThat(gitlabHooks.getFirst()).containsEntry("url", PAYLOAD_URL).containsEntry("token", "lab-rotated")
            .containsEntry("push_events", true).containsEntry("merge_requests_events", true);
        assertThat(TRAFFIC).as("step 3: through GitLab's PUT").contains("PUT /api/v4/projects/group%2Fsub%2Fapp/hooks/1");

        // 4. GitHub (Enterprise base): a web hook with TLS verification on, found beyond the first page of a
        //    repository holding many other webhooks, never left doubled, the others untouched.
        List<Map<String, Object>> busy = HOOKS.computeIfAbsent("/api/v3/repos/acme/site/hooks",
            key -> new ArrayList<>());
        for (int other = 1; other <= 60; other++) {
            busy.add(new LinkedHashMap<>(Map.of("id", other, "config",
                Map.of("url", "https://ci.example.test/hook/" + other))));
        }
        GitProviderClient github = GitProviders.clientFor(provider(9303, GithubProviderKind.ID.toString()));
        assertThat(github.ensurePushWebhook("acme/site", PAYLOAD_URL, "hub-secret"))
            .as("step 4: created beside the others").isEqualTo(GitProviderClient.HookRegistration.CREATED);
        assertThat(github.ensurePushWebhook("acme/site", PAYLOAD_URL, "hub-rotated"))
            .as("step 4: found again on the second page").isEqualTo(GitProviderClient.HookRegistration.UPDATED);
        assertThat(busy).as("step 4: one more hook, not two").hasSize(61);
        Map<String, Object> ours = busy.getLast();
        assertThat(ours).containsEntry("name", "web").containsEntry("active", true);
        assertThat((List<Object>) ours.get("events")).containsExactly("push", "pull_request");
        assertThat((Map<String, Object>) ours.get("config")).containsEntry("url", PAYLOAD_URL)
            .containsEntry("secret", "hub-rotated").containsEntry("content_type", "json")
            .containsEntry("insecure_ssl", "0");
        assertThat((Map<String, Object>) busy.getFirst().get("config"))
            .as("step 4: another hook is left alone").containsEntry("url", "https://ci.example.test/hook/1");

        // 5. An SSH source has no API: it says so rather than pretending.
        Row ssh = provider(9304, SshProviderKind.ID.toString());
        ssh.set(GitProviderModel.BASE_URL, null);
        GitProviderClient sshClient = GitProviders.clientFor(ssh);
        assertThat(sshClient.registersWebhooks()).as("step 5: an SSH source registers nothing").isFalse();
        assertThat(catchThrowable(() -> sshClient.ensurePushWebhook("acme/app", PAYLOAD_URL, "x")))
            .as("step 5: and refuses by name").isInstanceOf(IOException.class);
    }
}
