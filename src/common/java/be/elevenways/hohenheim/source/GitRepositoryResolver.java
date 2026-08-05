package be.elevenways.hohenheim.source;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.forms.common.edit.SiblingProviderResolver;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Repository picker resolver: a provider exists once the {@code provider_id}
 * sibling holds a usable id (live publishes deliver transport strings, stored
 * values arrive as Numbers -- both parse here).
 */
@HawkeyeClass(alwaysBundle = true)
public record GitRepositoryResolver() implements SiblingProviderResolver {

    @Override
    public @Nullable DataProvider resolve(@NonNull Map<String, Object> siblingValues) {
        Integer providerId = providerIdOf(siblingValues.get("provider_id"));
        return providerId == null ? null : new GitRepositoryProvider(providerId);
    }

    static @Nullable Integer providerIdOf(@Nullable Object value) {
        if (value instanceof Number number) {
            return number.intValue() > 0 ? number.intValue() : null;
        }
        if (value != null) {
            try {
                int parsed = Integer.parseInt(String.valueOf(value).trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }
}
