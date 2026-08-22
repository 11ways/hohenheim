package be.elevenways.hohenheim.source;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common git-provider-kind metadata (the InstanceKindInfo shape): lives in src/common so
 * the admin UI can enumerate kinds and resolve their per-kind settings schema without
 * server dependencies.
 *
 * AIDEV-NOTE: the per-kind schema deliberately carries only NON-SECRET configuration. A
 * credential stays a static {@code .secret().encrypted()} COLUMN on GitProviderModel,
 * because a field under a JSON SchemaField cannot be encrypted at all
 * ({@code Schema.refuseEncryptedJsonSubFields}) -- moving a token here would silently
 * store it in plaintext.
 */
public interface GitProviderKindInfo extends TypeDefinition {

    /** @return the registry identifier; its string form is the stored column value */
    @NonNull Identifier typeId();

    /**
     * Whether a provider of this kind refuses a blank base URL: a kind with no public
     * default host must never default, or a stored token is aimed at a third-party forge
     * the operator never named.
     */
    boolean requiresBaseUrl();

    /** Short description shown in the kind selector UI. */
    @NonNull Microcopy getDescription();
}
