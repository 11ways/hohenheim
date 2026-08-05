package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.zenit.common.data.DataItem;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared plumbing of the git picker providers: the browse-endpoint parameter map
 * and the local value-is-its-own-display item (repository paths and branch names
 * carry no richer identity, so hydration never needs a round trip).
 */
final class GitProviderParams {

    private GitProviderParams() {
    }

    static @NonNull Map<ParameterDefinition<?>, Object> of(int providerId,
                                                           @Nullable String text,
                                                           @Nullable String repository) {
        Map<ParameterDefinition<?>, Object> params = new LinkedHashMap<>();
        params.put(HohenheimEndpoints.PROVIDER_ID, providerId);
        if (text != null && !text.isBlank()) {
            params.put(HohenheimEndpoints.PROVIDER_TEXT, text.trim());
        }
        if (repository != null && !repository.isBlank()) {
            params.put(HohenheimEndpoints.PROVIDER_REPOSITORY, repository.trim());
        }
        return params;
    }

    static @Nullable DataItem literalItem(@NonNull String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return new DataItem(trimmed, trimmed, null, null, null, null, null, Map.of());
    }
}
