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
 * Gitea-compatible providers (Gitea, Forgejo, Gogs descendants): a token, and a base URL
 * that is REQUIRED because the kind has no public default host.
 */
public final class GiteaProviderKind implements GitProviderKind {

    public static final Identifier ID = Identifier.of("hohenheim", "gitea");

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "Gitea"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("gitea").withFilter("scope", "git_provider_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("gitea").withFilter("scope", "git_provider_kind_description");
    }

    /**
     * AIDEV-NOTE: the generic Git mark, not a Gitea mark -- FontAwesome ships none, and
     * the name "gitea" resolved to the missing-icon placeholder for as long as this kind
     * existed. The display name beside it already says which forge this is.
     */
    @Override public Icon getIcon() { return Icon.of("git-alt"); }

    @Override public String getColor() { return "success"; }

    @Override public Schema getSchema() { return null; }

    /**
     * No public default host: a blank base URL would aim an operator's stored token at
     * gitea.com, a third party they never named. Refused at SAVE and at client build.
     */
    @Override public boolean requiresBaseUrl() { return true; }

    /** The base URL is non-null by construction: {@link GitProviders} validated it. */
    @Override
    public @NonNull GitProviderClient clientFor(@NonNull Row provider, @Nullable String baseUrl) {
        return new GiteaProviderClient(
            java.util.Objects.requireNonNull(baseUrl, "gitea base url"),
            provider.get(GitProviderModel.ACCESS_TOKEN));
    }
}
