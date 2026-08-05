package be.elevenways.hohenheim.source;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.common.data.DataQuery;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The {@link DataProvider} over one git provider's repository listing, riding the
 * admin-gated {@code GIT_PROVIDER_REPOSITORIES} endpoint. A record on purpose:
 * structural equality makes an unchanged sibling re-resolution a reactive no-op.
 * {@code resolve} answers locally -- the repository path IS its display text, so
 * rendering an edit form never calls the third-party API.
 */
@HawkeyeClass(alwaysBundle = true)
public record GitRepositoryProvider(int providerId) implements DataProvider {

    @Override
    public @NonNull DataPage load(@NonNull DataQuery query) {
        try {
            DataPage page = HohenheimEndpoints.GIT_PROVIDER_REPOSITORIES.call(null,
                GitProviderParams.of(this.providerId, query.text(), null));
            return page != null ? page : DataPage.empty();
        } catch (RuntimeException failure) {
            Blast.log("hohenheim.git.repositories_load_failed", this.providerId,
                String.valueOf(failure));
            return DataPage.empty();
        }
    }

    @Override
    public @Nullable DataItem resolve(@NonNull String value) {
        return GitProviderParams.literalItem(value);
    }
}
