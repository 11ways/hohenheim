package be.elevenways.hohenheim.source;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.common.edit.EmptyNarrowingReason;
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
public record GitRepositoryResolver() implements SiblingProviderResolver, EmptyNarrowingReason {

    @Override
    public @Nullable DataProvider resolve(@NonNull Map<String, Object> siblingValues) {
        Integer providerId = RawValues.positiveInt(siblingValues.get(GitSourceSchema.PROVIDER_ID));
        return providerId == null ? null : new GitRepositoryProvider(providerId);
    }

    /**
     * A chosen provider that lists nothing is a CREDENTIAL answer, not an empty account:
     * the token is scoped away from the repositories, or the connection is stale.
     */
    @Override
    public @Nullable Microcopy reasonNothingQualifies(@NonNull Map<String, Object> siblingValues) {
        return HohenheimMicrocopy.GIT_PROVIDER.of("no_repositories");
    }
}
