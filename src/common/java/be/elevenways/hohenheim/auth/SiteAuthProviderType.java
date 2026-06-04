package be.elevenways.hohenheim.auth;

import be.elevenways.zenit.common.orm.field.TypeDefinition;

/**
 * Common metadata for a per-site auth-provider type, used by the SiteAuthProviderModel's
 * RegistryEnumField and the admin form's polymorphic schema. The server-side gate-creation
 * extension lives in SiteAuthProviderTypeHandler (it depends on Undertow).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public interface SiteAuthProviderType extends TypeDefinition {

    /**
     * Whether the provider record's {@code required_permission} column is meaningful. True for
     * claims-based providers (Proteus, OIDC); false for credential-only providers (Basic), whose
     * form then neither surfaces the field nor warns when it is blank.
     */
    default boolean usesRequiredPermission() {
        return true;
    }
}
