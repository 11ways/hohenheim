package be.elevenways.hohenheim.source;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.zenit.common.data.DataProvider;
import be.elevenways.zenit.forms.common.edit.SiblingProviderResolver;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Branch picker resolver: needs BOTH a usable {@code provider_id} and a chosen
 * {@code repository} sibling before any branch listing makes sense.
 */
@HawkeyeClass(alwaysBundle = true)
public record GitBranchResolver() implements SiblingProviderResolver {

    @Override
    public @Nullable DataProvider resolve(@NonNull Map<String, Object> siblingValues) {
        Integer providerId = GitRepositoryResolver.providerIdOf(siblingValues.get("provider_id"));
        Object repository = siblingValues.get("repository");
        String repositoryPath = repository == null ? "" : String.valueOf(repository).trim();
        if (providerId == null || repositoryPath.isEmpty()) {
            return null;
        }
        return new GitBranchProvider(providerId, repositoryPath);
    }
}
