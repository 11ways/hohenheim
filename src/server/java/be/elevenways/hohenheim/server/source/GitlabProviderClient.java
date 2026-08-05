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
 * The GitLab-compatible provider (gitlab.com or self-hosted), speaking the v4 API:
 * membership project listing, branch listing, commit statuses, and the stored access
 * token as the {@code oauth2} clone user. No token-minting lane exists here -- GitLab
 * personal/project access tokens are the only credential shape (the GitHub App
 * equivalent would be a GitLab OAuth application, which needs a per-user consent flow
 * hohenheim's admin-owned provider records deliberately do not model).
 *
 * AIDEV-NOTE: project paths may be NESTED (group/subgroup/project); everywhere the
 * path rides the API URL it is percent-encoded whole ({@code %2F} separators), which
 * the v4 API accepts in place of a numeric project id.
 */
public class GitlabProviderClient extends ApiProviderClient {

    private final @NonNull String webBase;
    private final @NonNull String apiBase;
    private final @Nullable String accessToken;

    GitlabProviderClient(@Nullable String baseUrl, @Nullable String accessToken) {
        String base = baseUrl == null || baseUrl.isBlank()
            ? "https://gitlab.com" : trimSlash(baseUrl.trim());
        this.webBase = base;
        this.apiBase = base + "/api/v4";
        this.accessToken = blankToNull(accessToken);
    }

    @Override
    public @NonNull List<RepoRef> listRepositories() throws IOException {
        Object parsed = getJson(this.apiBase
            + "/projects?membership=true&per_page=100&order_by=last_activity_at", requireToken());
        List<RepoRef> repos = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> project
                        && project.get("path_with_namespace") != null) {
                    repos.add(new RepoRef(String.valueOf(project.get("path_with_namespace")),
                        String.valueOf(project.get("http_url_to_repo")),
                        project.get("default_branch") != null
                            ? String.valueOf(project.get("default_branch")) : null));
                }
            }
        }
        return repos;
    }

    @Override
    public @NonNull List<String> listBranches(@NonNull String repository) throws IOException {
        Object parsed = getJson(this.apiBase + "/projects/" + projectPath(repository)
            + "/repository/branches?per_page=100", requireToken());
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
        return this.webBase + "/" + validRepoPath(repository, true) + ".git";
    }

    @Override
    public @NonNull Credential cloneCredential(@NonNull String repository) throws IOException {
        // The username GitLab expects for any bearer-style token over HTTPS git.
        return new Credential("oauth2", requireToken(), 0);
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
            case FAILURE -> "failed";
        });
        body.put("context", context);
        body.put("description", truncate(description, 140));
        if (targetUrl != null && !targetUrl.isBlank()) {
            body.put("target_url", targetUrl);
        }
        postJson(this.apiBase + "/projects/" + projectPath(repository)
            + "/statuses/" + commitSha, requireToken(), Json.stringify(body));
    }

    private @NonNull String requireToken() throws IOException {
        if (this.accessToken == null) {
            throw new IOException("Provider has no access token configured");
        }
        return this.accessToken;
    }

    /**
     * The validated project path as one percent-encoded URL segment. Only the
     * separators need encoding: validation already restricts every segment to
     * {@code [A-Za-z0-9_.-]}.
     */
    private static @NonNull String projectPath(@NonNull String repository) {
        return validRepoPath(repository, true).replace("/", "%2F");
    }
}
