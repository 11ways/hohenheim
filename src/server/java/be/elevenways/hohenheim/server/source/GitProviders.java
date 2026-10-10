package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.hohenheim.model.StoredRows;
import be.elevenways.hohenheim.source.GitSourceSchema;
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
import java.util.Objects;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * The one construction funnel of provider clients, and the derivation of the per-clone
 * credential environment. WHICH client a row gets is the kind's own declaration
 * ({@link GitProviderKind#clientFor}), reached through the compile-time-discovered
 * {@link GitProviderKinds} map: {@link GithubProviderKind} (PAT or App-minted
 * installation tokens), {@link GitlabProviderKind} (v4 API, clone user {@code oauth2})
 * and {@link GiteaProviderKind} (v1 API, token in the password position). An undeclared
 * kind is refused by name -- unknown fails CLOSED.
 *
 * ADDING A KIND is ONE class: a {@link GitProviderKind} implementation (plus its
 * {@link ApiProviderClient} subclass). It registers itself, its label/icon/schema enter
 * the model's RegistryMemberField live, and nothing here changes. Everything downstream is
 * kind-agnostic on purpose -- the repository/branch pickers, the credential environment
 * below, the connection test and the webhook receiver all route through this funnel or
 * through provider-neutral headers.
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
            throw Violations.ofField(GitSourceSchema.PROVIDER_ID, providerId,
                HohenheimMicrocopy.VIOLATIONS.of("git_provider_unknown"));
        }
        return clientFor(provider);
    }

    /**
     * The client of one provider row, its API calls held to the row's reach: public
     * addresses only unless the provider is operator-owned ({@link SourceOwnership}).
     *
     * @throws Violations naming the refusal (undeclared kind, unusable or missing base URL)
     */
    public static @NonNull GitProviderClient clientFor(@NonNull Row provider) {
        GitProviderKind kind = requireKind(provider.get(GitProviderModel.KIND));
        return kind.clientFor(provider, validatedBaseUrl(kind,
            provider.get(GitProviderModel.BASE_URL)), SourceOwnership.providerGuard(provider));
    }

    /**
     * THE per-kind validity check of a provider row, asked by the write hook
     * ({@link #installKindInvariant}) so a bad row is refused at SAVE, and again here so
     * a row written outside a form (a seed, a test, a future API) can never build a
     * client that talks to the wrong host.
     *
     * @throws Violations naming the refusal (undeclared kind, unusable or missing base URL)
     */
    public static void validate(@Nullable String kindToken, @Nullable String baseUrl) {
        validatedBaseUrl(requireKind(kindToken), baseUrl);
    }

    /** @throws Violations {@code git_provider_kind_unavailable} for an undeclared kind */
    private static @NonNull GitProviderKind requireKind(@Nullable String kindToken) {
        GitProviderKind kind = GitProviderKinds.getHandler(kindToken);
        if (kind == null) {
            throw Violations.ofField(GitProviderModel.KIND.getName(), kindToken,
                HohenheimMicrocopy.VIOLATIONS.of("git_provider_kind_unavailable")
                    .withArg("kind", String.valueOf(kindToken)));
        }
        return kind;
    }

    /**
     * @return the trimmed base URL, or null when the kind uses its public host
     * @throws Violations when the URL is unusable, or missing on a kind that requires one
     */
    private static @Nullable String validatedBaseUrl(@NonNull GitProviderKind kind,
                                                     @Nullable String baseUrl) {
        String trimmed = trimmed(baseUrl);
        if (trimmed.isEmpty()) {
            if (kind.requiresBaseUrl()) {
                throw Violations.ofField(GitProviderModel.BASE_URL.getName(), baseUrl,
                    HohenheimMicrocopy.VIOLATIONS.of("git_provider_base_url_required"));
            }
            return null;
        }
        String problem = BASE_URL_POLICY.problemOf(trimmed);
        if (problem != null) {
            throw Violations.ofField(GitProviderModel.BASE_URL.getName(), baseUrl,
                HohenheimMicrocopy.VIOLATIONS.of("git_provider_bad_base_url").withArg("reason", problem));
        }
        return trimmed;
    }

    private static volatile boolean kindInvariantInstalled;

    /**
     * Install THE per-kind row invariant on the GitProviderModel write pipeline, so an
     * undeclared kind or a Gitea row without a base URL is refused at SAVE rather than at
     * the first deploy -- one check for the admin form, the delegated /manage form, a
     * revision restore and any future writer.
     */
    public static synchronized void installKindInvariant() {
        if (kindInvariantInstalled) {
            return;
        }
        kindInvariantInstalled = true;
        // beforeVALIDATE, and reading through the EFFECTIVE row: a partial write (the
        // inline cell lane submits ONE entry) must be judged against what the row will
        // actually hold, never against the keys this submission happened to carry.
        GitProviderModel.SCHEMA.addBeforeValidateHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = StoredRows.of(Models.get(GitProviderModel.class), row);
            validate(Objects.toString(row.afterWrite(GitProviderModel.KIND, stored), null),
                Objects.toString(row.afterWrite(GitProviderModel.BASE_URL, stored), null));
        });
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
        Binding binding = bindingOf(sourceSettings);
        if (binding == null) {
            return null;
        }
        GitProviderClient client = clientFor(binding.providerId());
        GitProviderClient.Credential credential = client.cloneCredential(binding.repository());
        String cloneUrl = client.cloneUrl(binding.repository());
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

    /**
     * The URL a source clones from: the bound provider's clone URL, else the declared {@code repository_url}.
     *
     * @throws Violations {@code source_no_repository} when the source declares neither
     */
    public static @NonNull String requireCloneUrl(@NonNull Map<String, Object> sourceSettings) {
        Binding binding = bindingOf(sourceSettings);
        String url = binding != null
            ? clientFor(binding.providerId()).cloneUrl(binding.repository())
            : trimmed(sourceSettings.get(GitSourceSchema.REPOSITORY_URL));
        if (url.isEmpty()) {
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("source_no_repository"));
        }
        return url;
    }

    /** @return the source's provider binding, or null when it names no provider or no repository */
    public static @Nullable Binding bindingOf(@NonNull Map<String, Object> sourceSettings) {
        Integer providerId = RawValues.positiveInt(sourceSettings.get(GitSourceSchema.PROVIDER_ID));
        String repository = trimmed(sourceSettings.get(GitSourceSchema.REPOSITORY));
        return providerId == null || repository.isEmpty() ? null : new Binding(providerId, repository);
    }

    /** A source bound to a git provider: its clone URL and credentials derive from the provider and repository. */
    public record Binding(int providerId, @NonNull String repository) {
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
}
