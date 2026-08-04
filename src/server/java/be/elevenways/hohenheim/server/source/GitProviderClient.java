package be.elevenways.hohenheim.server.source;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * The provider seam: what a git hosting provider must answer for repository/branch
 * selection, authenticated clones and deployment status reporting. One implementation
 * per {@code GitProviderModel.KIND}; construction goes through {@link GitProviders}.
 */
public interface GitProviderClient {

    /** A repository as the provider lists it. */
    record RepoRef(@NonNull String fullName, @NonNull String cloneUrl,
                   @Nullable String defaultBranch) {}

    /**
     * A clone/build credential: short-lived when the provider mints them (a GitHub App
     * installation token), otherwise the stored token with no upstream expiry.
     *
     * @param expiresAtMillis 0 when the upstream credential does not expire
     */
    record Credential(@NonNull String username, @NonNull String secret, long expiresAtMillis) {}

    /** Deployment status states, folded onto each provider's own vocabulary. */
    enum StatusState { PENDING, SUCCESS, FAILURE }

    /** @throws IOException when the provider refuses or is unreachable */
    @NonNull List<RepoRef> listRepositories() throws IOException;

    /** @throws IOException when the provider refuses or is unreachable */
    @NonNull List<String> listBranches(@NonNull String repository) throws IOException;

    /** The https clone URL of one repository at this provider. */
    @NonNull String cloneUrl(@NonNull String repository);

    /**
     * Mint (or fall back to) a credential able to clone {@code repository}.
     *
     * @throws IOException when minting fails or no credential is configured
     */
    @NonNull Credential cloneCredential(@NonNull String repository) throws IOException;

    /**
     * Report a deployment status onto one commit.
     *
     * @throws IOException when the provider refuses or is unreachable
     */
    void reportStatus(@NonNull String repository, @NonNull String commitSha,
                      @NonNull StatusState state, @NonNull String context,
                      @NonNull String description, @Nullable String targetUrl)
        throws IOException;
}
