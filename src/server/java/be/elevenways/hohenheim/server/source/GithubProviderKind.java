package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.GitProviderModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.common.ui.BadgeColor;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * GitHub-compatible providers (github.com and GitHub Enterprise): a personal access
 * token, or a GitHub App whose id and installation id live in the per-kind settings
 * while its private key stays an encrypted column.
 */
public final class GithubProviderKind implements GitProviderKind {

    public static final Identifier ID = HohenheimIds.id("github");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** GitHub App id; with {@link #APP_INSTALLATION_ID} and the key, tokens are MINTED. */
    public static final StringField APP_ID = SETTINGS_SCHEMA.addField(
        StringField.builder().name("app_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_app_id"))
            .help(HohenheimMicrocopy.HELP.of("provider_app_id"))
            .build());

    public static final StringField APP_INSTALLATION_ID = SETTINGS_SCHEMA.addField(
        StringField.builder().name("app_installation_id")
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_app_installation_id"))
            .help(HohenheimMicrocopy.HELP.of("provider_app_installation_id"))
            .build());

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "GitHub"; }

    @Override public Icon getIcon() { return Icon.of("github"); }

    @Override public BadgeColor color() { return BadgeVariant.INFO; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** Blank means github.com, the kind's public host. */
    @Override public boolean requiresBaseUrl() { return false; }

    @Override
    public @NonNull GitProviderClient clientFor(@NonNull Row provider, @Nullable String baseUrl,
                                                @NonNull OutboundUrlGuard guard) {
        Integer id = provider.get(GitProviderModel.ID);
        Map<String, Object> settings = RawValues.map(provider.get(GitProviderModel.SETTINGS));
        return new GithubProviderClient(id != null ? id : -1, baseUrl,
            provider.get(GitProviderModel.ACCESS_TOKEN),
            Objects.toString(settings.get(APP_ID.getName()), null),
            Objects.toString(settings.get(APP_INSTALLATION_ID.getName()), null),
            provider.get(GitProviderModel.APP_PRIVATE_KEY_PEM), guard);
    }
}
