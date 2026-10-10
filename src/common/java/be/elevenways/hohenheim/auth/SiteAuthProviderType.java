package be.elevenways.hohenheim.auth;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.WordedKind;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Common metadata for a per-site auth-provider type, used by the SiteAuthProviderModel's
 * RegistryMemberField and the admin form's polymorphic schema. The server-side gate-creation
 * extension lives in SiteAuthProviderTypeHandler (it depends on Undertow).
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public interface SiteAuthProviderType extends WordedKind {

    @Override
    default @NonNull HohenheimMicrocopy labelScope() {
        return HohenheimMicrocopy.AUTH_PROVIDER_TYPE;
    }

    @Override
    default @NonNull HohenheimMicrocopy descriptionScope() {
        return HohenheimMicrocopy.AUTH_PROVIDER_TYPE_DESCRIPTION;
    }

    /**
     * Whether the provider record's {@code required_permission} column is meaningful. True for
     * claims-based providers (Proteus, OIDC); false for credential-only providers (Basic), whose
     * form then neither surfaces the field nor warns when it is blank.
     */
    default boolean usesRequiredPermission() {
        return true;
    }
}
