package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.server.source.GitProviders;
import be.elevenways.protoblast.common.util.BlastString;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.result.DryResult;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Git provider browsing: the repository and branch listings behind the admin pickers and
 * automation, read-only against the provider and answered as typed DataPages.
 *
 * AIDEV-NOTE: moved out of DnsZoneHandlers, where it sat only because both happened to
 * be admin tab plumbing. The provider CLIENT is GitProviders' to build (and to guard as an
 * outbound fetch); this class only filters and shapes the answer.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
final class GitProviderHandlers {

    private GitProviderHandlers() {
    }

    static void init() {
        HohenheimEndpoints.GIT_PROVIDER_REPOSITORIES.setHandler(conduit -> {
            Integer providerId = conduit.getParameter(HohenheimEndpoints.PROVIDER_ID);
            String text = searchText(conduit);
            try {
                List<DataItem> items = new ArrayList<>();
                for (var repo : GitProviders.clientFor(providerId).listRepositories()) {
                    if (!text.isEmpty() && !BlastString.lower(repo.fullName()).contains(text)) {
                        continue;
                    }
                    items.add(new DataItem(repo.fullName(), repo.fullName(),
                        repo.defaultBranch(), null, null, null, null, Map.of()));
                }
                return new DryResult<>(DataPage.singlePage(items));
            } catch (Exception e) {
                return providerFailure(conduit, e);
            }
        });

        HohenheimEndpoints.GIT_PROVIDER_BRANCHES.setHandler(conduit -> {
            Integer providerId = conduit.getParameter(HohenheimEndpoints.PROVIDER_ID);
            String repository = conduit.getQueryParam(HohenheimEndpoints.PROVIDER_REPOSITORY.getName());
            if (repository == null || repository.isBlank()) {
                conduit.badRequest("repository required");
                return null;
            }
            String text = searchText(conduit);
            try {
                List<DataItem> items = new ArrayList<>();
                for (String branch : GitProviders.clientFor(providerId).listBranches(repository)) {
                    if (!text.isEmpty() && !BlastString.lower(branch).contains(text)) {
                        continue;
                    }
                    items.add(new DataItem(branch, branch, null, null, null, null, null, Map.of()));
                }
                return new DryResult<>(DataPage.singlePage(items));
            } catch (Exception e) {
                return providerFailure(conduit, e);
            }
        });
    }

    /** The optional search text, trimmed and lower-cased; "" when absent. */
    private static @NonNull String searchText(@NonNull Conduit conduit) {
        String value = conduit.getQueryParam(HohenheimEndpoints.PROVIDER_TEXT.getName());
        return value == null ? "" : BlastString.lower(value.trim());
    }

    /**
     * A provider that could not be read is a 502 carrying the failure's own text: these
     * routes are operator-only (ADMIN_ACCESS), and the operator configured the provider.
     */
    private static <T> @Nullable DryResult<T> providerFailure(@NonNull Conduit conduit, @NonNull Exception failure) {
        conduit.setResponseStatus(502);
        conduit.endWithContentType("text/plain", String.valueOf(failure.getMessage()));
        return null;
    }
}
