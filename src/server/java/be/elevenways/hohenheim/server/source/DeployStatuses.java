package be.elevenways.hohenheim.server.source;

import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.thread.JobRunner;
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

    /** Report asynchronously on a virtual thread; never throws. */
    public static void report(@NonNull Map<String, Object> sourceSettings,
                              @Nullable String commitSha,
                              GitProviderClient.@NonNull StatusState state,
                              @NonNull String context, @NonNull String description,
                              @Nullable String targetUrl) {
        Integer providerId = GitProviders.providerIdOf(sourceSettings);
        String repository = str(sourceSettings.get("repository"));
        if (providerId == null || repository.isEmpty()
                || commitSha == null || commitSha.isBlank()) {
            return;
        }
        JobRunner.startVirtualThread(() -> {
            try {
                GitProviders.clientFor(providerId).reportStatus(repository, commitSha,
                    state, context, description, targetUrl);
            } catch (Exception e) {
                Blast.log("GIT: status report (" + context + ", " + state + ") for",
                    repository + "@" + commitSha, "failed -", e.getMessage());
            }
        });
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
