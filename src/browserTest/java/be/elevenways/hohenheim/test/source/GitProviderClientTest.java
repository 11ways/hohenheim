package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.server.source.GithubProviderKind;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The GitHub provider client against a FAKE provider API: repository/branch listing,
 * deployment statuses, and the App JWT -> installation-token mint -- asserted on the
 * wire the fake captured (headers, bodies, real RS256 signature verification), never on
 * the client's own claims. Also proves an undeclared kind refuses by name and a
 * redirecting provider is never followed.
 */
class GitProviderClientTest {

    private static HttpServer server;
    private static String base;
    private static KeyPair appKey;

    private static volatile String lastReposAuth;
    private static volatile String lastBranchesAuth;
    private static volatile String lastStatusAuth;
    private static volatile String lastStatusBody;
    private static volatile String lastMintJwt;
    private static final AtomicInteger MINT_CALLS = new AtomicInteger();
    private static final AtomicInteger REDIRECT_TARGET_HITS = new AtomicInteger();

    @BeforeAll
    static void startFakeProvider() throws Exception {
        be.elevenways.hohenheim.test.HohenheimTestRuntime.ensureBooted();
        appKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/user/repos", exchange -> {
            lastReposAuth = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 200, "[{\"full_name\":\"acme/app\","
                + "\"clone_url\":\"" + base + "/acme/app.git\","
                + "\"default_branch\":\"main\"}]");
        });
        server.createContext("/api/v3/repos/acme/app/branches", exchange -> {
            lastBranchesAuth = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 200, "[{\"name\":\"main\"},{\"name\":\"dev\"}]");
        });
        server.createContext("/api/v3/repos/acme/app/statuses/abc123", exchange -> {
            lastStatusAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastStatusBody = new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
            respond(exchange, 201, "{\"id\":1}");
        });
        server.createContext("/api/v3/app/installations/55/access_tokens", exchange -> {
            MINT_CALLS.incrementAndGet();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            lastMintJwt = auth != null && auth.startsWith("Bearer ")
                ? auth.substring("Bearer ".length()) : null;
            respond(exchange, 201, "{\"token\":\"ghs_minted_token\","
                + "\"expires_at\":\"2099-01-01T00:00:00Z\"}");
        });
        // A "provider" that redirects; the hidden target counts its hits.
        server.createContext("/api/v3/repos/redir/repo/branches", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/redirected");
            respond(exchange, 302, "");
        });
        server.createContext("/redirected", exchange -> {
            REDIRECT_TARGET_HITS.incrementAndGet();
            respond(exchange, 200, "[]");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeProvider() {
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

    private static Row patProvider(int id) {
        Row provider = Models.get(GitProviderModel.class).createEmptyRow();
        provider.set(GitProviderModel.ID, id);
        provider.set(GitProviderModel.NAME, "fake");
        provider.set(GitProviderModel.KIND, GithubProviderKind.ID.toString());
        provider.set(GitProviderModel.BASE_URL, base);
        provider.set(GitProviderModel.ACCESS_TOKEN, "pat-token");
        return provider;
    }

    private static Row appProvider(int id, String pem) {
        Row provider = patProvider(id);
        provider.set(GitProviderModel.ACCESS_TOKEN, null);
        provider.set(GitProviderModel.SETTINGS, Map.of(
            GithubProviderKind.APP_ID.getName(), "42",
            GithubProviderKind.APP_INSTALLATION_ID.getName(), "55"));
        provider.set(GitProviderModel.APP_PRIVATE_KEY_PEM, pem);
        return provider;
    }

    private static String pkcs8Pem() {
        return "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(appKey.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }

    /**
     * A PKCS#1 PEM of the same key (the format GitHub App downloads use), derived by
     * unwrapping the PKCS#8 PrivateKeyInfo: SEQ(version, algorithm, OCTETSTRING(pkcs1)).
     */
    private static String pkcs1Pem() {
        byte[] pkcs8 = appKey.getPrivate().getEncoded();
        int[] pos = {0};
        expectTag(pkcs8, pos, 0x30);            // PrivateKeyInfo SEQUENCE
        skipEntry(pkcs8, pos);                  // version INTEGER
        skipEntry(pkcs8, pos);                  // algorithm SEQUENCE
        expectTag(pkcs8, pos, 0x04);            // privateKey OCTET STRING
        byte[] pkcs1 = java.util.Arrays.copyOfRange(pkcs8, pos[0], pkcs8.length);
        return "-----BEGIN RSA PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(pkcs1)
            + "\n-----END RSA PRIVATE KEY-----\n";
    }

    /** Reads tag + DER length, leaving pos at the content. */
    private static int expectTag(byte[] der, int[] pos, int tag) {
        assertThat(der[pos[0]] & 0xFF).as("DER tag").isEqualTo(tag);
        pos[0]++;
        int first = der[pos[0]++] & 0xFF;
        if (first < 0x80) {
            return first;
        }
        int lengthBytes = first & 0x7F;
        int length = 0;
        for (int i = 0; i < lengthBytes; i++) {
            length = (length << 8) | (der[pos[0]++] & 0xFF);
        }
        return length;
    }

    private static void skipEntry(byte[] der, int[] pos) {
        pos[0]++;
        int first = der[pos[0]++] & 0xFF;
        int length = first;
        if (first >= 0x80) {
            int lengthBytes = first & 0x7F;
            length = 0;
            for (int i = 0; i < lengthBytes; i++) {
                length = (length << 8) | (der[pos[0]++] & 0xFF);
            }
        }
        pos[0] += length;
    }

    // -------------------------------------------------------------------------

    @Test
    void patLaneListsRepositoriesAndBranchesWithTheStoredToken() throws Exception {
        GitProviderClient client = GitProviders.clientFor(patProvider(9001));

        // 1. Repository listing carries the PAT and parses the provider's answer.
        List<GitProviderClient.RepoRef> repos = client.listRepositories();
        assertThat(repos).as("step 1: one repository listed").hasSize(1);
        assertThat(repos.get(0).fullName()).isEqualTo("acme/app");
        assertThat(repos.get(0).defaultBranch()).isEqualTo("main");
        assertThat(lastReposAuth).as("step 1: the stored token authenticates the call")
            .isEqualTo("Bearer pat-token");

        // 2. Branch listing for the selected repository.
        assertThat(client.listBranches("acme/app"))
            .as("step 2: branches listed").containsExactly("main", "dev");
        assertThat(lastBranchesAuth).isEqualTo("Bearer pat-token");

        // 3. The clone URL derives from the provider base, and the PAT credential
        //    carries the x-access-token username GitHub expects.
        assertThat(client.cloneUrl("acme/app")).isEqualTo(base + "/acme/app.git");
        GitProviderClient.Credential credential = client.cloneCredential("acme/app");
        assertThat(credential.username()).isEqualTo("x-access-token");
        assertThat(credential.secret()).isEqualTo("pat-token");

        // 4. A malformed repository never becomes a URL path. The dot-dot case
        //    matters separately: ".." matches the name charset and would
        //    URL-normalize into a different API endpoint.
        assertThat(catchThrowable(() -> client.listBranches("../../evil")))
            .as("step 4: path tricks are refused before any request")
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> client.listBranches("acme/..")))
            .as("step 4: a dot-dot segment is refused before any request")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusReportPostsTheCommitStatusOntoTheProvider() throws Exception {
        GitProviderClient client = GitProviders.clientFor(patProvider(9002));
        client.reportStatus("acme/app", "abc123", GitProviderClient.StatusState.SUCCESS,
            "hohenheim/deploy", "Deployed", "https://example.test/");

        assertThat(lastStatusAuth).as("the status post is authenticated")
            .isEqualTo("Bearer pat-token");
        Object parsed = new Dry().parse(lastStatusBody);
        assertThat(parsed).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) parsed;
        assertThat(body.get("state")).as("the state maps onto GitHub's vocabulary")
            .isEqualTo("success");
        assertThat(body.get("context")).isEqualTo("hohenheim/deploy");
        assertThat(body.get("description")).isEqualTo("Deployed");
        assertThat(body.get("target_url")).isEqualTo("https://example.test/");
    }

    @Test
    void appLaneMintsARealShortLivedInstallationToken() throws Exception {
        MINT_CALLS.set(0);
        GitProviderClient client = GitProviders.clientFor(appProvider(9003, pkcs8Pem()));

        // 1. The credential is the MINTED token, not anything stored.
        GitProviderClient.Credential credential = client.cloneCredential("acme/app");
        assertThat(credential.secret()).as("step 1: the minted token is the credential")
            .isEqualTo("ghs_minted_token");
        assertThat(credential.expiresAtMillis())
            .as("step 1: the upstream expiry is carried on the credential")
            .isGreaterThan(System.currentTimeMillis());

        // 2. The mint was authorized by a REAL RS256 App JWT: three segments, RS256
        //    header, the app id as issuer, and a signature the app's PUBLIC key verifies.
        assertThat(lastMintJwt).as("step 2: a JWT reached the mint endpoint").isNotNull();
        String[] segments = lastMintJwt.split("\\.");
        assertThat(segments).as("step 2: JWT has three segments").hasSize(3);
        String header = new String(Base64.getUrlDecoder().decode(segments[0]),
            StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(segments[1]),
            StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"RS256\"");
        assertThat(payload).as("step 2: issued by the configured App id")
            .contains("\"iss\":\"42\"");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(appKey.getPublic());
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2])))
            .as("step 2: the JWT signature verifies against the App's public key")
            .isTrue();

        // 3. A second credential within the validity window REUSES the minted token --
        //    the provider is not asked again.
        client.cloneCredential("acme/app");
        assertThat(MINT_CALLS.get())
            .as("step 3: the installation token is minted once and cached").isEqualTo(1);

        // 4. API reads ride the minted token too (no PAT exists on this provider).
        List<String> branches = client.listBranches("acme/app");
        assertThat(branches).containsExactly("main", "dev");
        assertThat(lastBranchesAuth).isEqualTo("Bearer ghs_minted_token");
    }

    @Test
    void appKeyInGithubsPkcs1DownloadFormatWorks() throws Exception {
        MINT_CALLS.set(0);
        GitProviderClient client = GitProviders.clientFor(appProvider(9004, pkcs1Pem()));
        assertThat(client.cloneCredential("acme/app").secret())
            .as("a BEGIN RSA PRIVATE KEY (PKCS#1) App key mints exactly like PKCS#8")
            .isEqualTo("ghs_minted_token");
        assertThat(MINT_CALLS.get()).isEqualTo(1);
    }

    @Test
    void anUndeclaredKindRefusesByName() {
        Row provider = Models.get(GitProviderModel.class).createEmptyRow();
        provider.set(GitProviderModel.ID, 9005);
        provider.set(GitProviderModel.NAME, "bb");
        provider.set(GitProviderModel.KIND, "bitbucket");
        Throwable refused = catchThrowable(() -> GitProviders.clientFor(provider));
        assertThat(refused)
            .as("an undeclared kind refuses loudly, never half-works")
            .isInstanceOf(be.elevenways.zenit.common.validation.Violations.class);
    }

    @Test
    void aRedirectingProviderIsNeverFollowed() {
        REDIRECT_TARGET_HITS.set(0);
        GitProviderClient client = GitProviders.clientFor(patProvider(9006));
        Throwable refused = catchThrowable(() -> client.listBranches("redir/repo"));
        assertThat(refused)
            .as("a 302 from the provider is a refusal, not a hop")
            .isInstanceOf(java.io.IOException.class);
        assertThat(REDIRECT_TARGET_HITS.get())
            .as("the Authorization header never walked to the redirect target")
            .isZero();
    }

}
