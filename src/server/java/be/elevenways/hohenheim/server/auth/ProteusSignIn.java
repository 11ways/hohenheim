package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusIdentityProvider;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URI;
import java.util.Locale;

/**
 * The panel's Proteus sign-in, offered at boot from {@code hohenheim.auth_proteus} next to password sign-in.
 *
 * AIDEV-NOTE: fails closed. Switched on without {@code required_permission} it is NOT offered and
 * {@link #heldBack()} says so (the dashboard's attention item), since it would admit everybody the realm signs in.
 * The requirement decides who signs in, not what they may do, and ends no open session (ProteusIdentityProvider).
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class ProteusSignIn {

    /** The provider's slug: its sign-in routes are {@code /login/proteus/...}. */
    public static final String SLUG = "proteus";

    private static volatile boolean heldBack;

    private ProteusSignIn() {
    }

    /**
     * Registers the provider when switched on, set up and naming a required permission.
     *
     * @throws IllegalStateException when called again after it registered: it runs once per boot, as every setting it
     *     reads is restart-required
     */
    public static synchronized void registerIfConfigured() {
        heldBack = false;

        if (!HohenheimSettings.isOn(HohenheimSettings.AuthProteus.ENABLED)) {
            return;
        }

        String required = text(HohenheimSettings.AuthProteus.REQUIRED_PERMISSION);
        if (required == null) {
            heldBack = true;
            return;
        }

        String endpoint = text(HohenheimSettings.AuthProteus.ENDPOINT);
        String realmClient = text(HohenheimSettings.AuthProteus.REALM_CLIENT);
        String accessKey = text(HohenheimSettings.AuthProteus.ACCESS_KEY);
        if (endpoint == null || realmClient == null || accessKey == null) {
            return;
        }

        IdentityProviderRegistry.register(
            new ProteusIdentityProvider(SLUG, "Proteus",
                // The operator's own setting: any address (a realm on this host or the LAN), still pinned.
                new ProteusClient(endpoint, realmClient, accessKey, OutboundUrlGuard.ANY_ADDRESS),
                text(HohenheimSettings.AuthProteus.AUTHENTICATOR), false, required),
            AutoProvisioningSink.builder().build());
    }

    /** @return whether the last boot left a switched-on Proteus sign-in out for naming no required permission */
    public static boolean heldBack() {
        return heldBack;
    }

    /**
     * The Proteus realm this server signs people in with: the realm client at the endpoint its provider calls, under
     * which a realm's subject names one person. Two servers name the same person by one subject only when both answer
     * the same realm here.
     *
     * AIDEV-NOTE: read from the restart-required settings the offered provider was built from, so it is the realm
     * every Proteus identity linked since this boot came from; an identity linked under an earlier endpoint or realm
     * client keeps its subject and is read as this realm's (the link stores no realm). Scheme and host compare
     * without case; any other difference in how the two servers spell the endpoint reads as another realm.
     *
     * @return the realm, null while Proteus sign-in is not offered or its endpoint is no address
     */
    public static @Nullable String realm() {
        if (IdentityProviderRegistry.get(SLUG) == null) {
            return null;
        }
        String endpoint = text(HohenheimSettings.AuthProteus.ENDPOINT);
        String realmClient = text(HohenheimSettings.AuthProteus.REALM_CLIENT);
        if (endpoint == null || realmClient == null) {
            return null;
        }
        try {
            URI base = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/").normalize();
            if (base.getScheme() == null || base.getRawAuthority() == null) {
                return null;
            }
            return base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getRawAuthority().toLowerCase(Locale.ROOT)
                + base.getRawPath() + "realm/" + realmClient;
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** @return the setting's trimmed text, null when unset or blank */
    private static @Nullable String text(@NonNull SettingDefinition<String> setting) {
        String value = Zenit.SETTINGS_VALUES.getValue(setting);
        return value == null || value.isBlank() ? null : value.strip();
    }
}
