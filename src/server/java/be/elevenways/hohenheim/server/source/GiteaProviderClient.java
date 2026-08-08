package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.server.util.Json;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Gitea-compatible provider (Gitea, Forgejo and Gogs-descended forges), speaking the
 * documented v1 API: {@code /api/v1/user/repos}, {@code /api/v1/repos/{owner}/{repo}/branches}
 * and {@code /api/v1/repos/{owner}/{repo}/statuses/{sha}}. Repository paths are exactly
 * {@code owner/repo} -- Gitea has no subgroup nesting, so unlike GitLab the path rides the
 * URL as plain segments and a third segment is refused.
 *
 * AIDEV-NOTE: Gitea's own state vocabulary is pending/success/error/failure/warning
 * (modules/commitstatus/commit_status.go), so FAILURE folds onto {@code "failure"} -- the
 * GitHub word, NOT GitLab's {@code "failed"}. Verified against the upstream source rather
 * than assumed from GitHub's API.
 *
 * AIDEV-NOTE: the clone credential puts the token in the PASSWORD position with a fixed
 * username. Gitea's services/auth/basic.go treats the password as the access token
 * whenever it is non-empty and not the literal {@code x-oauth-basic} ("Assume password is
 * token"), so the username is free -- and keeping the secret in the secret field is what
 * {@link GitProviderClient.Credential} means. No token MINTING lane exists: Gitea has no
 * GitHub-App equivalent, so a personal/organisation access token is the only shape.
 */
public class GiteaProviderClient extends ApiProviderClient {

    /** The Basic-auth username the token rides beside; Gitea reads the token off the password. */
    private static final String CLONE_USER = "hohenheim";

    private final @NonNull String webBase;
    private final @NonNull String apiBase;
    private final @Nullable String accessToken;

    /**
     * @param baseUrl the installation's web base; REQUIRED (see
     *        {@code GitProviders.clientFor}, which refuses a blank one by name)
     */
    GiteaProviderClient(@NonNull String baseUrl, @Nullable String accessToken) {
        String base = trimSlash(baseUrl.trim());
        this.webBase = base;
        this.apiBase = base + "/api/v1";
        this.accessToken = blankToNull(accessToken);
    }

    @Override
    public @NonNull List<RepoRef> listRepositories() throws IOException {
        // Gitea paginates on page+limit (ListOptions), not GitHub's per_page.
        Object parsed = getJson(this.apiBase + "/user/repos?page=1&limit=100", requireToken());
        List<RepoRef> repos = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> repo && repo.get("full_name") != null) {
                    repos.add(new RepoRef(String.valueOf(repo.get("full_name")),
                        String.valueOf(repo.get("clone_url")),
                        repo.get("default_branch") != null
                            ? String.valueOf(repo.get("default_branch")) : null));
                }
            }
        }
        return repos;
    }

    @Override
    public @NonNull List<String> listBranches(@NonNull String repository) throws IOException {
        Object parsed = getJson(this.apiBase + "/repos/" + repoPath(repository)
            + "/branches?page=1&limit=100", requireToken());
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
        return new Credential(CLONE_USER, requireToken(), 0);
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
            requireToken(), Json.stringify(body));
    }

    private @NonNull String requireToken() throws IOException {
        if (this.accessToken == null) {
            throw new IOException("Provider has no access token configured");
        }
        return this.accessToken;
    }

    /** {@code owner/repo}, refusing nesting and every path trick. */
    private static @NonNull String repoPath(@NonNull String repository) {
        return validRepoPath(repository, false);
    }
}
