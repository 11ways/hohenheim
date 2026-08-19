package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * GitLab-compatible providers (gitlab.com or self-hosted): a personal/group access
 * token and nothing else, so this kind declares no per-kind settings schema.
 */
public final class GitlabProviderKind implements GitProviderKind {

    public static final Identifier ID = Identifier.of("hohenheim", "gitlab");

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "GitLab"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("gitlab").withFilter("scope", "git_provider_kind");
    }

    @Override
    public String getDescription() { return "gitlab.com or a self-hosted GitLab installation"; }

    @Override public Icon getIcon() { return Icon.of("gitlab"); }

    @Override public String getColor() { return "warning"; }

    @Override public Schema getSchema() { return null; }

    /** Blank means gitlab.com, the kind's public host. */
    @Override public boolean requiresBaseUrl() { return false; }

    @Override
    public @NonNull GitProviderClient clientFor(@NonNull Row provider, @Nullable String baseUrl) {
        return new GitlabProviderClient(baseUrl, provider.get(GitProviderModel.ACCESS_TOKEN));
    }
}
