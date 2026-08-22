package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * GitHub-compatible providers (github.com and GitHub Enterprise): a personal access
 * token, or a GitHub App whose id and installation id live in the per-kind settings
 * while its private key stays an encrypted column.
 */
public final class GithubProviderKind implements GitProviderKind {

    public static final Identifier ID = Identifier.of("hohenheim", "github");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** GitHub App id; with {@link #APP_INSTALLATION_ID} and the key, tokens are MINTED. */
    public static final StringField APP_ID = SETTINGS_SCHEMA.addField(
        StringField.builder().name("app_id")
            .label(HohenheimFormCopy.label("provider_app_id"))
            .help(HohenheimFormCopy.help("provider_app_id"))
            .build());

    public static final StringField APP_INSTALLATION_ID = SETTINGS_SCHEMA.addField(
        StringField.builder().name("app_installation_id")
            .label(HohenheimFormCopy.label("provider_app_installation_id"))
            .help(HohenheimFormCopy.help("provider_app_installation_id"))
            .build());

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "GitHub"; }

    @Override
    public @NonNull Microcopy getLabel() {
        return Microcopy.of("github").withFilter("scope", "git_provider_kind");
    }

    @Override
    public @NonNull Microcopy getDescription() {
        return Microcopy.of("github").withFilter("scope", "git_provider_kind_description");
    }

    @Override public Icon getIcon() { return Icon.of("github"); }

    @Override public String getColor() { return "info"; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** Blank means github.com, the kind's public host. */
    @Override public boolean requiresBaseUrl() { return false; }

    @Override
    public @NonNull GitProviderClient clientFor(@NonNull Row provider, @Nullable String baseUrl) {
        Integer id = provider.get(GitProviderModel.ID);
        Map<String, Object> settings = GitProviders.settingsOf(provider);
        return new GithubProviderClient(id != null ? id : -1, baseUrl,
            provider.get(GitProviderModel.ACCESS_TOKEN),
            text(settings.get(APP_ID.getName())),
            text(settings.get(APP_INSTALLATION_ID.getName())),
            provider.get(GitProviderModel.APP_PRIVATE_KEY_PEM));
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
