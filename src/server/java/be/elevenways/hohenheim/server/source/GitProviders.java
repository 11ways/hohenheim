package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.UrlPolicy;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one construction funnel of provider clients, and the derivation of the per-clone
 * credential environment. There are exactly THREE provider kinds:
 * {@link GithubProviderClient} (PAT or App-minted installation tokens),
 * {@link GitlabProviderClient} (v4 API, clone user {@code oauth2}) and
 * {@link GiteaProviderClient} (v1 API, token in the password position). Anything else is
 * refused by name below.
 *
 * ADDING A KIND is three edits and no more: a {@code KIND_*} constant plus an enum value
 * on {@link GitProviderModel#KIND}, an {@link ApiProviderClient} subclass, and one branch
 * here. Everything downstream is kind-agnostic on purpose -- the repository/branch
 * pickers, the credential environment below, the connection test and the webhook receiver
 * all route through this funnel or through provider-neutral headers.
 *
 * AIDEV-NOTE: Gitea was webhook-only until 2026-08-08 -- the inbound path accepted its
 * signature while {@code clientFor} refused the kind by name, so a Gitea repository could
 * not be picked or cloned with managed credentials. That half-wiring is closed; the
 * INBOUND path stays kind-agnostic (it verifies headers, it does not consult this funnel),
 * which is why a Gitea webhook worked at all without a client.
 */
public final class GitProviders {

    /** Providers speak http(s) only; embedded user-info is a credential in a URL. */
    private static final UrlPolicy BASE_URL_POLICY = UrlPolicy.builder()
        .schemes("http", "https")
        .build();

    private GitProviders() {
    }

    /**
     * @throws Violations naming the refusal (unknown provider, undeclared kind)
     */
    public static @NonNull GitProviderClient clientFor(int providerId) {
        Row provider = Models.get(GitProviderModel.class).findById(providerId);
        if (provider == null) {
            throw Violations.ofField("provider_id", providerId,
                Microcopy.of("git_provider_unknown").withFilter("scope", "violations"));
        }
        return clientFor(provider);
    }

    public static @NonNull GitProviderClient clientFor(@NonNull Row provider) {
        String kind = provider.get(GitProviderModel.KIND);
        String baseUrl = provider.get(GitProviderModel.BASE_URL);
        if (baseUrl != null && !baseUrl.isBlank()) {
            String problem = BASE_URL_POLICY.problemOf(baseUrl.trim());
            if (problem != null) {
                throw Violations.ofField("base_url", baseUrl,
                    Microcopy.of("git_provider_bad_base_url")
                        .withFilter("scope", "violations").withArg("reason", problem));
            }
        }
        if (GitProviderModel.KIND_GITHUB.equals(kind)) {
            Integer id = provider.get(GitProviderModel.ID);
            return new GithubProviderClient(id != null ? id : -1, baseUrl,
                provider.get(GitProviderModel.ACCESS_TOKEN),
                provider.get(GitProviderModel.APP_ID),
                provider.get(GitProviderModel.APP_INSTALLATION_ID),
                provider.get(GitProviderModel.APP_PRIVATE_KEY_PEM));
        }
        if (GitProviderModel.KIND_GITLAB.equals(kind)) {
            return new GitlabProviderClient(baseUrl,
                provider.get(GitProviderModel.ACCESS_TOKEN));
        }
        if (GitProviderModel.KIND_GITEA.equals(kind)) {
            // No public default host: a blank base URL would aim an operator's stored
            // token at gitea.com, a third party they never named. Refuse instead.
            if (baseUrl == null || baseUrl.isBlank()) {
                throw Violations.ofField("base_url", baseUrl,
                    Microcopy.of("git_provider_base_url_required")
                        .withFilter("scope", "violations"));
            }
            return new GiteaProviderClient(baseUrl,
                provider.get(GitProviderModel.ACCESS_TOKEN));
        }
        throw Violations.ofField("kind", kind,
            Microcopy.of("git_provider_kind_unavailable")
                .withFilter("scope", "violations").withArg("kind", String.valueOf(kind)));
    }

    /**
     * The extra git environment that authenticates a clone/fetch against a provider:
     * an {@code http.<base>.extraheader} Authorization header injected through git's
     * environment-config mechanism -- never on the command line (visible in /proc),
     * never in the URL (GitRepository refuses embedded user-info).
     *
     * @return null when the site is not provider-bound
     * @throws IOException when the provider cannot mint/supply a credential
     */
    public static @Nullable Map<String, String> credentialEnv(
            @NonNull Map<String, Object> sourceSettings) throws IOException {
        Integer providerId = providerIdOf(sourceSettings);
        String repository = str(sourceSettings.get("repository"));
        if (providerId == null || repository.isEmpty()) {
            return null;
        }
        GitProviderClient client = clientFor(providerId);
        GitProviderClient.Credential credential = client.cloneCredential(repository);
        String cloneUrl = client.cloneUrl(repository);
        String origin = originOf(cloneUrl);
        String basic = Base64.getEncoder().encodeToString(
            (credential.username() + ":" + credential.secret())
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "http." + origin + "/.extraHeader");
        env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        env.put("GIT_TERMINAL_PROMPT", "0");
        return env;
    }

    /** The clone URL a provider-bound site uses; null when not provider-bound. */
    public static @Nullable String boundCloneUrl(@NonNull Map<String, Object> sourceSettings) {
        Integer providerId = providerIdOf(sourceSettings);
        String repository = str(sourceSettings.get("repository"));
        if (providerId == null || repository.isEmpty()) {
            return null;
        }
        return clientFor(providerId).cloneUrl(repository);
    }

    static @Nullable Integer providerIdOf(@NonNull Map<String, Object> sourceSettings) {
        Object value = sourceSettings.get("provider_id");
        return value instanceof Number number && number.intValue() > 0
            ? number.intValue() : null;
    }

    /** Scheme + authority of a URL; the config key scope credentials bind to. */
    private static @NonNull String originOf(@NonNull String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0 ? url : url.substring(0, pathStart);
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
