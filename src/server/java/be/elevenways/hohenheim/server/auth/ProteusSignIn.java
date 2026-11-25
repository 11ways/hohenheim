package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusSignInSwitch;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The panel's Proteus sign-in: zenit-auth's switch over {@code hohenheim.auth_proteus}, offered at boot next to
 * password sign-in. A first sign-in makes an account without rights.
 *
 * AIDEV-NOTE: fails closed (ProteusSignInSwitch). Switched on without {@code required_permission} it is NOT offered
 * and {@link #heldBack()} says so (the dashboard's attention item).
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class ProteusSignIn {

    /** The provider's slug: its sign-in routes are {@code /login/proteus/...}. */
    public static final String SLUG = ProteusSignInSwitch.SLUG;

    private static final ProteusSignInSwitch SWITCH = new ProteusSignInSwitch(HohenheimSettings.AuthProteus.SETTINGS,
        AutoProvisioningSink.builder().build());

    private ProteusSignIn() {
    }

    /**
     * Registers the provider when switched on, set up and naming a required permission.
     *
     * @throws IllegalStateException when called again after it registered
     */
    public static void registerIfConfigured() {
        SWITCH.registerIfConfigured();
    }

    /** @return whether the last boot left a switched-on Proteus sign-in out for naming no required permission */
    public static boolean heldBack() {
        return SWITCH.heldBack();
    }

    /** @return the Proteus realm this server signs people in with, null while Proteus sign-in is not offered */
    public static @Nullable String realm() {
        return SWITCH.realm();
    }
}
