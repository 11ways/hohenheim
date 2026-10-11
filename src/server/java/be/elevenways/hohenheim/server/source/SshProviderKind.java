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
 * Any git host reached over SSH with a private key the operator pastes (a deploy key, or an account's own key): no
 * forge API, so the connection names the one repository it lists, branches come from the remote itself and a deploy
 * reports no status anywhere.
 */
public final class SshProviderKind implements GitProviderKind {

    public static final Identifier ID = HohenheimIds.id("ssh");
    public static final Schema SETTINGS_SCHEMA = new Schema();

    /** The repository the key reaches, as git clones it ({@code git@host:org/repo.git} or {@code ssh://...}). */
    public static final StringField REPOSITORY = SETTINGS_SCHEMA.addField(
        StringField.builder().name("repository")
            .required()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of("provider_ssh_repository"))
            .help(HohenheimMicrocopy.HELP.of("provider_ssh_repository"))
            .build());

    @Override public @NonNull Identifier typeId() { return ID; }

    @Override public @NonNull String getDisplayName() { return "SSH key"; }

    @Override public Icon getIcon() { return Icon.of("key"); }

    @Override public BadgeColor color() { return BadgeVariant.SECONDARY; }

    @Override public Schema getSchema() { return SETTINGS_SCHEMA; }

    /** No API, so nothing to aim a base URL at: the repository URL names the host. */
    @Override public boolean requiresBaseUrl() { return false; }

    @Override public boolean clonesWithKey() { return true; }

    @Override
    public @NonNull GitProviderClient clientFor(@NonNull Row provider, @Nullable String baseUrl,
                                                @NonNull OutboundUrlGuard guard) {
        Integer id = provider.get(GitProviderModel.ID);
        Map<String, Object> settings = RawValues.map(provider.get(GitProviderModel.SETTINGS));
        return new SshProviderClient(id != null ? id : -1,
            Objects.toString(settings.get(REPOSITORY.getName()), "").trim(),
            provider.get(GitProviderModel.SSH_PRIVATE_KEY));
    }
}
