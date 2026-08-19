package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.server.source.GiteaProviderKind;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.server.source.GitProviderClient;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.protoblast.common.dry.Dry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Gitea provider client against a FAKE v1 API, with every wire shape taken from the
 * upstream Gitea source rather than assumed from GitHub's: page+limit pagination,
 * {@code /api/v1/user/repos}, {@code /api/v1/repos/{owner}/{repo}/branches}, the
 * {@code statuses/{sha}} CreateStatusOption body and Gitea's own commit-status vocabulary.
 * Asserted on the traffic the fake captured, never on the client's own claims.
 */
class GiteaProviderClientTest {

    private static HttpServer server;
    private static String base;

    private static volatile String lastAuth;
    private static volatile String lastRawPath;
    private static volatile String lastQuery;
    private static volatile String lastStatusBody;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger REDIRECT_TARGET_HITS = new AtomicInteger();

    @BeforeAll
    static void startFakeGitea() throws Exception {
        be.elevenways.hohenheim.test.HohenheimTestRuntime.ensureBooted();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            REQUESTS.incrementAndGet();
            lastRawPath = rawPath;
            lastQuery = exchange.getRequestURI().getRawQuery();
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            switch (rawPath) {
                case "/api/v1/user/repos" -> respond(exchange, 200,
                    "[{\"id\":5,\"name\":\"app\",\"full_name\":\"acme/app\","
                        + "\"clone_url\":\"" + base + "/acme/app.git\","
                        + "\"default_branch\":\"trunk\"}]");
                case "/api/v1/repos/acme/app/branches" -> respond(exchange, 200,
                    "[{\"name\":\"trunk\"},{\"name\":\"next\"}]");
                case "/api/v1/repos/acme/app/statuses/abc123" -> {
                    lastStatusBody = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                    respond(exchange, 201, "{\"id\":1,\"status\":\"failure\"}");
                }
                // The endpoint a `..` segment would normalize INTO: hit means escape.
                case "/api/v1/repos/admin/branches", "/api/v1/admin/branches" -> {
                    REDIRECT_TARGET_HITS.incrementAndGet();
                    respond(exchange, 200, "[{\"name\":\"escaped\"}]");
                }
                case "/api/v1/repos/redir/repo/branches" -> {
                    exchange.getResponseHeaders().add("Location", base + "/redirected");
                    respond(exchange, 302, "");
                }
                case "/redirected" -> {
                    REDIRECT_TARGET_HITS.incrementAndGet();
                    respond(exchange, 200, "[]");
                }
                default -> respond(exchange, 404, "{\"message\":\"Not Found\"}");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeGitea() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static Row giteaProvider(int id, String baseUrl) {
        Row provider = Models.get(GitProviderModel.class).createEmptyRow();
        provider.set(GitProviderModel.ID, id);
        provider.set(GitProviderModel.NAME, "fake-gitea");
        provider.set(GitProviderModel.KIND, GiteaProviderKind.ID.toString());
        provider.set(GitProviderModel.BASE_URL, baseUrl);
        provider.set(GitProviderModel.ACCESS_TOKEN, "gitea-pat-token");
        return provider;
    }

    // -------------------------------------------------------------------------

    @Test
    void listsReposAndBranchesOverTheV1ApiWithGiteasOwnPagination() throws Exception {
        GitProviderClient client = GitProviders.clientFor(giteaProvider(9201, base));

        // 1. Repository listing hits /api/v1/user/repos with page+limit (Gitea's
        //    ListOptions), NOT GitHub's per_page, and carries the stored token.
        List<GitProviderClient.RepoRef> repos = client.listRepositories();
        assertThat(repos).as("step 1: one repository listed").hasSize(1);
        assertThat(repos.get(0).fullName()).isEqualTo("acme/app");
        assertThat(repos.get(0).cloneUrl()).isEqualTo(base + "/acme/app.git");
        assertThat(repos.get(0).defaultBranch())
            .as("step 1: Gitea's default_branch is read, not guessed as main")
            .isEqualTo("trunk");
        assertThat(lastRawPath).isEqualTo("/api/v1/user/repos");
        assertThat(lastQuery).as("step 1: Gitea paginates on page+limit")
            .isEqualTo("page=1&limit=100");
        assertThat(lastAuth).as("step 1: the stored token authenticates the call")
            .isEqualTo("Bearer gitea-pat-token");

        // 2. Branch listing addresses the repository as PLAIN segments -- Gitea has no
        //    subgroup nesting, so nothing is percent-encoded the way GitLab needs.
        assertThat(client.listBranches("acme/app"))
            .as("step 2: branches listed").containsExactly("trunk", "next");
        assertThat(lastRawPath).isEqualTo("/api/v1/repos/acme/app/branches");

        // 3. The clone URL derives from the provider's own base (a self-hosted forge).
        assertThat(client.cloneUrl("acme/app")).isEqualTo(base + "/acme/app.git");

        // 4. The clone credential puts the token in the PASSWORD position: Gitea reads a
        //    non-empty password as the access token whatever the username is.
        GitProviderClient.Credential credential = client.cloneCredential("acme/app");
        assertThat(credential.secret()).as("step 4: the token is the secret")
            .isEqualTo("gitea-pat-token");
        assertThat(credential.username()).as("step 4: a fixed, non-empty clone user")
            .isNotBlank().isNotEqualTo("gitea-pat-token");
        assertThat(credential.expiresAtMillis())
            .as("step 4: Gitea mints nothing, so there is no upstream expiry")
            .isZero();
    }

    @Test
    void statusReportsUseGiteasOwnStateVocabulary() throws Exception {
        GitProviderClient client = GitProviders.clientFor(giteaProvider(9202, base));

        client.reportStatus("acme/app", "abc123", GitProviderClient.StatusState.FAILURE,
            "hohenheim/deploy", "Build failed", "https://example.test/deploys/1");

        assertThat(lastRawPath).isEqualTo("/api/v1/repos/acme/app/statuses/abc123");
        assertThat(lastAuth).isEqualTo("Bearer gitea-pat-token");
        Object parsed = new Dry().parse(lastStatusBody);
        assertThat(parsed).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) parsed;
        // Gitea's CommitStatusState is pending/success/error/failure/warning: FAILURE is
        // "failure" (GitHub's word), never GitLab's "failed".
        assertThat(body.get("state")).as("FAILURE folds onto Gitea's 'failure'")
            .isEqualTo("failure");
        assertThat(body.get("context")).isEqualTo("hohenheim/deploy");
        assertThat(body.get("description")).isEqualTo("Build failed");
        assertThat(body.get("target_url")).isEqualTo("https://example.test/deploys/1");
    }

    @Test
    void pathTricksAndNestedPathsAreRefusedBeforeAnyRequestIsMade() {
        GitProviderClient client = GitProviders.clientFor(giteaProvider(9203, base));
        REDIRECT_TARGET_HITS.set(0);
        int before = REQUESTS.get();

        // A dot-dot SEGMENT passes the name charset and would URL-normalize into another
        // API endpoint; the fake serves that endpoint, so an escape would be observable.
        assertThat(catchThrowable(() -> client.listBranches("acme/../admin")))
            .as("a dot-dot segment is refused")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> client.listBranches("../admin")))
            .as("a leading dot-dot segment is refused")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> client.listBranches("acme/..")))
            .as("a trailing dot-dot segment is refused on its own merits, not on segment count")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> client.listBranches("acme/./app")))
            .as("a single-dot segment is refused")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> client.listBranches("single")))
            .as("an owner-less path is refused")
            .isInstanceOf(IllegalArgumentException.class);
        // Gitea has no subgroups: a three-segment path is not a repository there.
        assertThat(catchThrowable(() -> client.listBranches("acme/group/app")))
            .as("Gitea has no nesting, so a third segment is refused")
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(REQUESTS.get())
            .as("not one request left the client for any refused path")
            .isEqualTo(before);
        assertThat(REDIRECT_TARGET_HITS.get())
            .as("no path trick reached another API endpoint").isZero();
    }

    @Test
    void aRedirectingProviderIsNeverFollowed() {
        REDIRECT_TARGET_HITS.set(0);
        GitProviderClient client = GitProviders.clientFor(giteaProvider(9204, base));
        assertThat(catchThrowable(() -> client.listBranches("redir/repo")))
            .as("a 302 from the provider is a refusal, not a hop")
            .isInstanceOf(java.io.IOException.class);
        assertThat(REDIRECT_TARGET_HITS.get())
            .as("the Authorization header never walked to the redirect target")
            .isZero();
    }

    @Test
    void aTokenlessProviderRefusesEveryAuthenticatedOperation() {
        Row provider = giteaProvider(9205, base);
        provider.set(GitProviderModel.ACCESS_TOKEN, null);
        GitProviderClient client = GitProviders.clientFor(provider);

        assertThat(catchThrowable(client::listRepositories))
            .as("listing without a token is a loud refusal, not an anonymous call")
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("no access token");
        assertThat(catchThrowable(() -> client.cloneCredential("acme/app")))
            .as("no credential is ever fabricated")
            .isInstanceOf(java.io.IOException.class);
    }

    /**
     * A blank base URL must be a NAMED refusal, not a silent default: unlike github.com
     * and gitlab.com there is no host the operator obviously meant, so defaulting would
     * aim their stored token at a third-party forge they never named.
     */
    @Test
    void aGiteaProviderWithoutABaseUrlIsRefusedByNameRatherThanDefaulted() {
        Row blank = giteaProvider(9206, null);
        Throwable refused = catchThrowable(() -> GitProviders.clientFor(blank));
        assertThat(refused)
            .as("a base-url-less Gitea provider is refused")
            .isInstanceOf(be.elevenways.zenit.common.validation.Violations.class);

        Row empty = giteaProvider(9207, "   ");
        assertThat(catchThrowable(() -> GitProviders.clientFor(empty)))
            .as("whitespace is not a base URL either")
            .isInstanceOf(be.elevenways.zenit.common.validation.Violations.class);

        // Positive anchor: the same row WITH a base URL builds a working client.
        assertThat(GitProviders.clientFor(giteaProvider(9208, base)).cloneUrl("acme/app"))
            .isEqualTo(base + "/acme/app.git");
    }

    /**
     * The anti-drift guard for the defect this wave closed: Gitea was DECLARED on the
     * inbound webhook path while {@code clientFor} refused the kind by name, so a
     * repository could be webhooked but never picked or cloned. Every value the KIND enum
     * offers an operator must therefore construct a client -- a kind that only exists in
     * the dropdown is a half-wired provider, which is what this asserts can no longer ship.
     */
    @Test
    void everyDeclaredProviderKindConstructsAClient() {
        int id = 9300;
        for (String kind : GitProviderModel.KIND.getValues().keySet()) {
            Row provider = giteaProvider(id++, base);
            provider.set(GitProviderModel.KIND, kind);
            assertThat(GitProviders.clientFor(provider))
                .as("kind '" + kind + "' is offered in the form, so it must have a client")
                .isNotNull();
        }
    }
}
