package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.source.GitRefNames;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * THE control-plane checkout: clone or fetch one ref of a git source into a directory this
 * controller owns, and hand back the commit it landed on.
 *
 * AIDEV-NOTE: extracted from {@code PreviewDeployments} in phase-0 brief 7 because the
 * application deploy lane needs exactly the same three steps (bind the provider credential,
 * clone-or-fetch, read the commit identity back) and a second copy of them is how the two
 * lanes drift on the one thing that matters -- whether the token ever touches
 * {@code .git/config}. It does not: the credential lives in the environment of the command
 * that needs it and nowhere else.
 *
 * AIDEV-NOTE: the checkout is a BUILD CONTEXT, never a serving directory. The deleted
 * host-user lane checked out into a slot the web server then served from, which is what made
 * a checkout a security boundary; here the bytes only ever reach a sandboxed build.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class GitCheckout {

    private GitCheckout() {
    }

    /** The directory a given owner record's checkouts live in. */
    public static @NonNull File directoryFor(@NonNull Identifier ownerModel, int ownerId) {
        String dataPath = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        String base = dataPath == null || dataPath.isBlank() ? "/opt/hohenheim/data" : dataPath;
        return new File(new File(new File(base, "checkouts"), ownerModel.getPath()),
            String.valueOf(ownerId));
    }

    /**
     * Materialize {@code ref} of the source into {@code checkout}.
     *
     * AIDEV-NOTE: the URL and the ref are judged HERE, before git runs, with named
     * refusals: a stored value predates every write gate, and a ref may come straight off
     * a forge payload. A LOCAL source (a controller path) is honoured only for an
     * operator-owned owner ({@link SourceOwnership#localSourcesAllowed}); otherwise one
     * tenant's repository URL could name another tenant's checkout directory.
     *
     * @param  sourceSettings the {@code GitSourceSchema} settings of the owning record
     * @return the commit SHA the checkout landed on
     * @throws Violations when no repository is declared, when the URL or the ref may not be
     *         cloned, when the clone or fetch fails, or when the checkout has no commit
     *         identity to build from
     */
    public static @NonNull String materialize(@NonNull Identifier ownerModel, int ownerId,
                                              @NonNull String ref,
                                              @NonNull Map<String, Object> sourceSettings,
                                              @NonNull File checkout) throws Exception {

        String boundUrl = GitProviders.boundCloneUrl(sourceSettings);
        String repoUrl = boundUrl != null ? boundUrl : str(sourceSettings.get("repository_url"));

        if (repoUrl.isEmpty()) {
            throw Violations.ofForm(violation("source_no_repository"));
        }

        if (!GitRefNames.isValid(ref)) {
            throw Violations.ofForm(violation("source_ref_invalid"));
        }

        if (GitRepository.embeddedCredential(repoUrl) != null) {
            throw Violations.ofForm(violation("repository_url_credential"));
        }

        boolean localAllowed = SourceOwnership.localSourcesAllowed(ownerModel, ownerId);

        if (!GitRepository.isRemoteCloneUrl(repoUrl)
                && !(localAllowed && GitRepository.isLocalCloneUrl(repoUrl))) {
            if (GitRepository.isLocalCloneUrl(repoUrl)) {
                throw Violations.ofForm(violation("source_repository_local_refused"));
            }
            throw Violations.ofForm(violation("source_repository_url_refused"));
        }

        // shallow_clone defaults to true (the field's declared default): only an explicit
        // false asks for the whole history.
        GitRepository repo = new GitRepository(repoUrl, ref,
            !Boolean.FALSE.equals(sourceSettings.get(GitSourceSchema.SHALLOW_CLONE)),
            Boolean.TRUE.equals(sourceSettings.get(GitSourceSchema.SUBMODULES)), null,
            localAllowed);

        if (boundUrl != null) {
            repo.setCredentialEnv(() -> {
                try {
                    return GitProviders.credentialEnv(sourceSettings);
                } catch (Exception e) {
                    Blast.log("CHECKOUT:", ownerModel, ownerId,
                        "provider credential unavailable -", e.getMessage());
                    return null;
                }
            });
        }

        GitRepository.GitResult result;

        if (checkout.isDirectory() && repo.isMatchingRepo(checkout)) {
            result = repo.fetchAndReset(checkout);
        } else {
            deleteTree(checkout);
            File parent = checkout.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            result = repo.clone(checkout);
        }

        if (!result.success()) {
            throw Violations.ofForm(violation("source_checkout_failed")
                .withArg("reason", result.output()));
        }

        String commit = repo.getCurrentCommit(checkout);

        if (commit == null || commit.isBlank()) {
            throw Violations.ofForm(violation("source_checkout_failed")
                .withArg("reason", "no commit identity"));
        }

        return commit;
    }

    /**
     * Remove a checkout tree; a checkout that cannot be replaced is cloned fresh.
     *
     * AIDEV-NOTE: through {@link FileTrees}, which never follows a symlink. The File-based
     * walk this replaced listed a symlinked directory's TARGET, so a repository committing
     * a link to a directory outside its checkout had that directory's contents deleted the
     * next time the checkout was replaced.
     */
    public static void deleteTree(@Nullable File directory) {
        if (directory == null) {
            return;
        }
        IOException failure = FileTrees.delete(directory.toPath());
        if (failure != null) {
            Blast.log("CHECKOUT: could not remove", directory.getAbsolutePath(), "-",
                failure.getMessage());
        }
    }

    private static @NonNull Microcopy violation(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    private static @NonNull String str(@Nullable Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
