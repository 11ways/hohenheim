package be.elevenways.hohenheim.source;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.common.edit.EmptyNarrowingReason;
import be.elevenways.zenit.forms.common.edit.SiblingProviderResolver;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

import static be.elevenways.hohenheim.RawValues.trimmed;

/**
 * Branch picker resolver: needs BOTH a usable {@code provider_id} and a chosen
 * {@code repository} sibling before any branch listing makes sense.
 */
@HawkeyeClass(alwaysBundle = true)
public record GitBranchResolver() implements SiblingProviderResolver, EmptyNarrowingReason {

    @Override
    public @Nullable DataProvider resolve(@NonNull Map<String, Object> siblingValues) {
        Integer providerId = RawValues.positiveInt(siblingValues.get(GitSourceSchema.PROVIDER_ID));
        Object repository = siblingValues.get(GitSourceSchema.REPOSITORY);
        String repositoryPath = trimmed(repository);
        if (providerId == null || repositoryPath.isEmpty()) {
            return null;
        }
        return new GitBranchProvider(providerId, repositoryPath);
    }

    /** A resolvable repository listing no branches means the token cannot read it. */
    @Override
    public @Nullable Microcopy reasonNothingQualifies(@NonNull Map<String, Object> siblingValues) {
        return HohenheimMicrocopy.GIT_PROVIDER.of("no_branches");
    }
}
