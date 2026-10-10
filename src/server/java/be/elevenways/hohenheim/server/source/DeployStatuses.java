package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.HandlerSupport;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Deployment status reporting back to the provider, best-effort by design: a status
 * post must never take a deploy down with it, so every failure degrades to a log line.
 * Reports only when the site is provider-bound and a commit sha is known.
 */
public final class DeployStatuses {

    /** The status context providers group hohenheim's reports under. */
    public static final String CONTEXT_DEPLOY = "hohenheim/deploy";

    /** The status context of preview deployments. */
    public static final String CONTEXT_PREVIEW = "hohenheim/preview";

    private DeployStatuses() {
    }

    /** Report a failed deploy of {@code commitSha} under {@link #CONTEXT_DEPLOY}; a null sha reports nothing. */
    public static void deployFailed(@NonNull Map<String, Object> sourceSettings, @Nullable String commitSha,
                                    @Nullable String reason) {
        report(sourceSettings, commitSha, GitProviderClient.StatusState.FAILURE, CONTEXT_DEPLOY,
            "Deploy failed: " + reason, null);
    }

    /** Report a push the trigger policy declined to deploy onto {@code commitSha}; a null sha reports nothing. */
    public static void deployDeclined(@NonNull Map<String, Object> sourceSettings, @Nullable String commitSha,
                                      @NonNull String reason) {
        report(sourceSettings, commitSha, GitProviderClient.StatusState.FAILURE, CONTEXT_DEPLOY,
            "Deploy declined: " + reason, null);
    }

    /**
     * Report asynchronously on a virtual thread; never throws.
     *
     * AIDEV-NOTE: the hop carries the caller's datasource ({@link HandlerSupport#inBackground}): the provider row is
     * read over there, and a bare virtual thread read it from the default binding instead of the caller's scope.
     */
    public static void report(@NonNull Map<String, Object> sourceSettings,
                              @Nullable String commitSha,
                              GitProviderClient.@NonNull StatusState state,
                              @NonNull String context, @NonNull String description,
                              @Nullable String targetUrl) {
        GitProviders.Binding binding = GitProviders.bindingOf(sourceSettings);
        if (binding == null || commitSha == null || commitSha.isBlank()) {
            return;
        }
        HandlerSupport.inBackground(() -> {
            try {
                GitProviders.clientFor(binding.providerId()).reportStatus(binding.repository(), commitSha,
                    state, context, description, targetUrl);
            } catch (Exception e) {
                Blast.log("GIT: status report (" + context + ", " + state + ") for",
                    binding.repository() + "@" + commitSha, "failed -", e.getMessage());
            }
        });
    }
}
