package be.elevenways.hohenheim.site;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.data.DataPage;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.common.data.DataQuery;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The suggestions of a path typed on a site's settings: its own files and folders under what was typed, read through
 * {@code SITE_FILE_SUGGESTIONS}, which judges the reader as the site's Files tab does.
 *
 * AIDEV-NOTE: a record on purpose, like GitRepositoryProvider: structural equality keeps an unchanged provider a
 * reactive no-op. A typed path is its own words, so {@link #resolve} answers without a round trip.
 *
 * @param siteId the site whose own files are suggested
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@HawkeyeClass(alwaysBundle = true)
public record SiteFileSuggestions(int siteId) implements DataProvider {

    @Override
    public @NonNull DataPage load(@NonNull DataQuery query) {
        Map<ParameterDefinition<?>, Object> params = new LinkedHashMap<>();
        params.put(HohenheimEndpoints.SITE_ID, this.siteId);
        if (query.text() != null && !query.text().isBlank()) {
            params.put(HohenheimEndpoints.PROVIDER_TEXT, query.text().strip());
        }
        try {
            DataPage page = HohenheimEndpoints.SITE_FILE_SUGGESTIONS.call(null, params);
            return page != null ? page : DataPage.empty();
        } catch (RuntimeException failure) {
            Blast.log("hohenheim.site_files.suggestions_failed", this.siteId, String.valueOf(failure));
            return DataPage.empty();
        }
    }

    @Override
    public @Nullable DataItem resolve(@NonNull String value) {
        return value.isBlank() ? null : DataItem.ofText(value);
    }
}
