package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.util.Json;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GitHub-compatible provider: repository/branch listing, deployment statuses and
 * clone credentials against github.com or a GitHub Enterprise base URL.
 *
 * When App credentials are configured, {@link #cloneCredential} MINTS an installation
 * token (RS256 App JWT exchanged at {@code /app/installations/{id}/access_tokens},
 * upstream validity about one hour) -- the genuinely short-lived upstream credential the
 * builder wave deferred to this one; the stored access token is only the fallback.
 */
public class GithubProviderClient extends ApiProviderClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Re-mint margin: a cached installation token is reused until 5 min before expiry. */
    private static final long TOKEN_REUSE_MARGIN_MS = 5 * 60 * 1000L;

    private record CachedToken(@NonNull String token, long expiresAtMillis) {}

    /** Minted installation tokens per provider row; memory-only, dies with the process. */
    private static final Map<Integer, CachedToken> MINTED = new ConcurrentHashMap<>();

    private final int providerId;
    private final @NonNull String webBase;
    private final @NonNull String apiBase;
    private final @Nullable String accessToken;
    private final @Nullable String appId;
    private final @Nullable String appInstallationId;
    private final @Nullable String appPrivateKeyPem;

    GithubProviderClient(int providerId, @Nullable String baseUrl, @Nullable String accessToken,
                         @Nullable String appId, @Nullable String appInstallationId,
                         @Nullable String appPrivateKeyPem) {
        this.providerId = providerId;
        String base = baseUrl == null || baseUrl.isBlank()
            ? "https://github.com" : trimSlash(baseUrl.trim());
        this.webBase = base;
        this.apiBase = "https://github.com".equals(base)
            ? "https://api.github.com" : base + "/api/v3";
        this.accessToken = blankToNull(accessToken);
        this.appId = blankToNull(appId);
        this.appInstallationId = blankToNull(appInstallationId);
        this.appPrivateKeyPem = blankToNull(appPrivateKeyPem);
    }

    @Override
    protected void decorate(HttpRequest.@NonNull Builder request) {
        request.header("Accept", "application/vnd.github+json");
    }

    private boolean appConfigured() {
        return this.appId != null && this.appInstallationId != null
            && this.appPrivateKeyPem != null;
    }

    @Override
    public @NonNull List<RepoRef> listRepositories() throws IOException {
        List<RepoRef> repos = new ArrayList<>();
        if (appConfigured()) {
            // The installation endpoint wraps the list in {"repositories": [...]}.
            Object parsed = getJson(this.apiBase + "/installation/repositories?per_page=100",
                mintedToken().token());
            if (parsed instanceof Map<?, ?> map
                    && map.get("repositories") instanceof List<?> list) {
                collectRepos(list, repos);
            }
            return repos;
        }
        Object parsed = getJson(this.apiBase + "/user/repos?per_page=100&sort=updated", requireToken());
        if (parsed instanceof List<?> list) {
            collectRepos(list, repos);
        }
        return repos;
    }

    private static void collectRepos(@NonNull List<?> list, @NonNull List<RepoRef> out) {
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> repo && repo.get("full_name") != null) {
                out.add(new RepoRef(String.valueOf(repo.get("full_name")),
                    String.valueOf(repo.get("clone_url")),
                    repo.get("default_branch") != null
                        ? String.valueOf(repo.get("default_branch")) : null));
            }
        }
    }

    @Override
    public @NonNull List<String> listBranches(@NonNull String repository) throws IOException {
        Object parsed = getJson(this.apiBase + "/repos/" + repoPath(repository) + "/branches?per_page=100",
            anyToken());
        List<String> branches = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> branch && branch.get("name") != null) {
                    branches.add(String.valueOf(branch.get("name")));
                }
            }
        }
        return branches;
    }

    @Override
    public @NonNull String cloneUrl(@NonNull String repository) {
        return this.webBase + "/" + repoPath(repository) + ".git";
    }

    @Override
    public @NonNull Credential cloneCredential(@NonNull String repository) throws IOException {
        if (appConfigured()) {
            CachedToken minted = mintedToken();
            return new Credential("x-access-token", minted.token(), minted.expiresAtMillis());
        }
        return new Credential("x-access-token", requireToken(), 0);
    }

    @Override
    public void reportStatus(@NonNull String repository, @NonNull String commitSha,
                             @NonNull StatusState state, @NonNull String context,
                             @NonNull String description, @Nullable String targetUrl)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", switch (state) {
            case PENDING -> "pending";
            case SUCCESS -> "success";
            case FAILURE -> "failure";
        });
        body.put("context", context);
        body.put("description", truncate(description, 140));
        if (targetUrl != null && !targetUrl.isBlank()) {
            body.put("target_url", targetUrl);
        }
        postJson(this.apiBase + "/repos/" + repoPath(repository) + "/statuses/" + commitSha,
            anyToken(), Json.stringify(body));
    }

    // -- installation-token minting -------------------------------------------

    private @NonNull CachedToken mintedToken() throws IOException {
        CachedToken cached = MINTED.get(this.providerId);
        if (cached != null
                && cached.expiresAtMillis() - TOKEN_REUSE_MARGIN_MS > System.currentTimeMillis()) {
            return cached;
        }
        String jwt = appJwt();
        HttpRequest request = HttpRequest.newBuilder(URI.create(this.apiBase
                + "/app/installations/" + this.appInstallationId + "/access_tokens"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + jwt)
            .header("Accept", "application/vnd.github+json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IOException("GitHub App token mint refused: HTTP "
                + response.statusCode());
        }
        Object parsed = new Dry().parse(response.body());
        if (!(parsed instanceof Map<?, ?> map) || map.get("token") == null) {
            throw new IOException("GitHub App token mint returned no token");
        }
        long expiresAt = parseExpiry(map.get("expires_at"));
        CachedToken minted = new CachedToken(String.valueOf(map.get("token")), expiresAt);
        MINTED.put(this.providerId, minted);
        return minted;
    }

    private static long parseExpiry(@Nullable Object expiresAt) {
        if (expiresAt != null) {
            try {
                return Instant.parse(String.valueOf(expiresAt)).toEpochMilli();
            } catch (RuntimeException unparsable) {
                // fall through to the conservative default below
            }
        }
        return System.currentTimeMillis() + 55 * 60 * 1000L;
    }

    /** The App JWT: RS256 over {iat, exp, iss}, 9-minute validity, 60s clock skew. */
    private @NonNull String appJwt() throws IOException {
        long now = System.currentTimeMillis() / 1000;
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
            .getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540)
            + ",\"iss\":\"" + this.appId + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(parsePrivateKey(this.appPrivateKeyPem));
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64Url(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("GitHub App private key unusable: " + e.getMessage());
        }
    }

    /**
     * Parse an RSA private key PEM: PKCS#8 directly, PKCS#1 (the format GitHub App key
     * downloads use) via a minimal PrivateKeyInfo wrapper.
     */
    static @NonNull PrivateKey parsePrivateKey(@NonNull String pem)
            throws java.security.GeneralSecurityException {
        String body = pem.replaceAll("-----(BEGIN|END)[A-Z ]*-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            der = wrapPkcs1InPkcs8(der);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** PrivateKeyInfo(version 0, rsaEncryption, OCTET STRING(pkcs1)) by hand. */
    private static byte[] wrapPkcs1InPkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48,
            (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        byte[] version = {0x02, 0x01, 0x00};
        byte[] octetString = derWrap((byte) 0x04, pkcs1);
        byte[] inner = new byte[version.length + algorithm.length + octetString.length];
        System.arraycopy(version, 0, inner, 0, version.length);
        System.arraycopy(algorithm, 0, inner, version.length, algorithm.length);
        System.arraycopy(octetString, 0, inner, version.length + algorithm.length,
            octetString.length);
        return derWrap((byte) 0x30, inner);
    }

    private static byte[] derWrap(byte tag, byte[] content) {
        byte[] length;
        if (content.length < 128) {
            length = new byte[] {(byte) content.length};
        } else if (content.length < 256) {
            length = new byte[] {(byte) 0x81, (byte) content.length};
        } else {
            length = new byte[] {(byte) 0x82, (byte) (content.length >> 8),
                (byte) content.length};
        }
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    // -- plumbing -------------------------------------------------------------

    private @NonNull String requireToken() throws IOException {
        if (this.accessToken == null) {
            throw new IOException("Provider has no access token configured");
        }
        return this.accessToken;
    }

    /** App token when configured, else the stored token; API reads accept either. */
    private @NonNull String anyToken() throws IOException {
        return appConfigured() ? mintedToken().token() : requireToken();
    }

    /** Refuses path tricks: a repository is exactly {@code owner/name}. */
    private static @NonNull String repoPath(@NonNull String repository) {
        return validRepoPath(repository, false);
    }

    private static @NonNull String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Test/diagnostic hook: forget this provider's minted token. */
    static void forgetMinted(int providerId) {
        MINTED.remove(providerId);
    }
}
