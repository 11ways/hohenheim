package be.elevenways.hohenheim.test.source;

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
 * The GitLab provider client against a FAKE v4 API (wire shapes from the GitLab REST
 * docs: membership project listing, percent-encoded project paths, branch listing,
 * commit statuses with GitLab's own state vocabulary, oauth2 clone user). Asserted on
 * the traffic the fake captured, never on the client's own claims; a mock because no
 * live GitLab credential exists in this environment.
 */
class GitlabProviderClientTest {

    private static HttpServer server;
    private static String base;

    private static volatile String lastAuth;
    private static volatile String lastRawPath;
    private static volatile String lastStatusBody;
    private static final AtomicInteger REDIRECT_TARGET_HITS = new AtomicInteger();

    @BeforeAll
    static void startFakeGitlab() throws Exception {
        be.elevenways.hohenheim.test.HohenheimTestRuntime.ensureBooted();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One root context dispatching on the RAW path, so the %2F-encoded project
        // path is asserted exactly as it rode the wire.
        server.createContext("/", exchange -> {
            String rawPath = exchange.getRequestURI().getRawPath();
            lastRawPath = rawPath;
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            switch (rawPath) {
                case "/api/v4/projects" -> respond(exchange, 200,
                    "[{\"id\":77,\"path_with_namespace\":\"group/sub/app\","
                        + "\"http_url_to_repo\":\"" + base + "/group/sub/app.git\","
                        + "\"default_branch\":\"main\"}]");
                case "/api/v4/projects/group%2Fsub%2Fapp/repository/branches" -> respond(exchange, 200,
                    "[{\"name\":\"main\"},{\"name\":\"dev\"}]");
                case "/api/v4/projects/group%2Fsub%2Fapp/statuses/abc123" -> {
                    lastStatusBody = new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                    respond(exchange, 201, "{\"id\":1}");
                }
                case "/api/v4/projects/redir%2Frepo/repository/branches" -> {
                    exchange.getResponseHeaders().add("Location", base + "/redirected");
                    respond(exchange, 302, "");
                }
                case "/redirected" -> {
                    REDIRECT_TARGET_HITS.incrementAndGet();
                    respond(exchange, 200, "[]");
                }
                default -> respond(exchange, 404, "{\"message\":\"404 Not Found\"}");
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeGitlab() {
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

    private static Row gitlabProvider(int id) {
        Row provider = Models.get(GitProviderModel.class).createEmptyRow();
        provider.set(GitProviderModel.ID, id);
        provider.set(GitProviderModel.NAME, "fake-gitlab");
        provider.set(GitProviderModel.KIND, GitProviderModel.KIND_GITLAB);
        provider.set(GitProviderModel.BASE_URL, base);
        provider.set(GitProviderModel.ACCESS_TOKEN, "glpat-token");
        return provider;
    }

    // -------------------------------------------------------------------------

    @Test
    void listsProjectsAndBranchesWithTheStoredTokenAndEncodedNestedPaths() throws Exception {
        GitProviderClient client = GitProviders.clientFor(gitlabProvider(9101));

        // 1. Project listing carries the token as Bearer and parses the v4 shape,
        //    including a NESTED (subgroup) project path.
        List<GitProviderClient.RepoRef> repos = client.listRepositories();
        assertThat(repos).as("step 1: one project listed").hasSize(1);
        assertThat(repos.get(0).fullName())
            .as("step 1: nested path_with_namespace survives whole")
            .isEqualTo("group/sub/app");
        assertThat(repos.get(0).defaultBranch()).isEqualTo("main");
        assertThat(lastAuth).as("step 1: the stored token authenticates the call")
            .isEqualTo("Bearer glpat-token");
        assertThat(lastRawPath).isEqualTo("/api/v4/projects");

        // 2. Branch listing addresses the project by its PERCENT-ENCODED path (the
        //    v4 id-or-path convention); the raw wire path proves the encoding.
        assertThat(client.listBranches("group/sub/app"))
            .as("step 2: branches listed").containsExactly("main", "dev");
        assertThat(lastRawPath)
            .as("step 2: the project path rode the URL as one %2F-encoded segment")
            .isEqualTo("/api/v4/projects/group%2Fsub%2Fapp/repository/branches");

        // 3. Clone URL derives from the provider base; the credential carries the
        //    oauth2 username GitLab expects for token clones.
        assertThat(client.cloneUrl("group/sub/app")).isEqualTo(base + "/group/sub/app.git");
        GitProviderClient.Credential credential = client.cloneCredential("group/sub/app");
        assertThat(credential.username()).as("step 3: oauth2 is the clone user")
            .isEqualTo("oauth2");
        assertThat(credential.secret()).isEqualTo("glpat-token");
        assertThat(credential.expiresAtMillis()).isZero();

        // 4. Path tricks are refused before any request: a dot-dot SEGMENT passes the
        //    name charset but would URL-normalize into another endpoint.
        assertThat(catchThrowable(() -> client.listBranches("group/../admin")))
            .as("step 4: dot-dot segments are refused before any request")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> client.listBranches("single-segment")))
            .as("step 4: a namespace-less path is refused")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusReportUsesGitlabsOwnStateVocabulary() throws Exception {
        GitProviderClient client = GitProviders.clientFor(gitlabProvider(9102));

        // FAILURE maps onto "failed" -- GitLab's vocabulary, not GitHub's "failure".
        client.reportStatus("group/sub/app", "abc123", GitProviderClient.StatusState.FAILURE,
            "hohenheim/deploy", "Build failed", "https://example.test/deploys/1");

        assertThat(lastAuth).as("the status post is authenticated")
            .isEqualTo("Bearer glpat-token");
        assertThat(lastRawPath)
            .isEqualTo("/api/v4/projects/group%2Fsub%2Fapp/statuses/abc123");
        Object parsed = new Dry().parse(lastStatusBody);
        assertThat(parsed).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) parsed;
        assertThat(body.get("state")).as("FAILURE folds onto GitLab's 'failed'")
            .isEqualTo("failed");
        assertThat(body.get("context")).isEqualTo("hohenheim/deploy");
        assertThat(body.get("description")).isEqualTo("Build failed");
        assertThat(body.get("target_url")).isEqualTo("https://example.test/deploys/1");
    }

    @Test
    void aTokenlessProviderRefusesEveryAuthenticatedOperation() {
        Row provider = gitlabProvider(9103);
        provider.set(GitProviderModel.ACCESS_TOKEN, null);
        GitProviderClient client = GitProviders.clientFor(provider);

        assertThat(catchThrowable(client::listRepositories))
            .as("listing without a token is a loud refusal, not an anonymous call")
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("no access token");
        assertThat(catchThrowable(() -> client.cloneCredential("group/sub/app")))
            .as("no credential is ever fabricated")
            .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void aRedirectingProviderIsNeverFollowed() {
        REDIRECT_TARGET_HITS.set(0);
        GitProviderClient client = GitProviders.clientFor(gitlabProvider(9104));
        Throwable refused = catchThrowable(() -> client.listBranches("redir/repo"));
        assertThat(refused)
            .as("a 302 from the provider is a refusal, not a hop")
            .isInstanceOf(java.io.IOException.class);
        assertThat(REDIRECT_TARGET_HITS.get())
            .as("the Authorization header never walked to the redirect target")
            .isZero();
    }
}
