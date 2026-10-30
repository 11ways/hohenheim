package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimChannels;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.auth.ProteusRealmSuggestions;
import be.elevenways.hohenheim.server.cli.OfflineBoot;
import be.elevenways.zenit.auth.AuthSettings;
import be.elevenways.zenit.auth.server.AuthRegistry;
import be.elevenways.zenit.auth.server.AuthRequirement;
import be.elevenways.zenit.auth.server.identity.AutoProvisioningSink;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusIdentityProvider;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.migration.MigrationCapableDatasource;
import be.elevenways.zenit.server.HostBoot;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommands;
import be.elevenways.zenit.server.net.OutboundUrlGuard;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Hohenheim's part of {@code ServerZenitRuntime.run}: its two command-line lanes and its pre-boot, which main and every
 * test boot share.
 *
 * AIDEV-NOTE: the lanes ask this host for what they need, nothing is opened on a normal boot, and the argv gate runs
 * before all of them (the OfflineBoot precedent): a typo'd or undeclared flag is refused in words (stderr, exit 1)
 * before the migration lane opens the database, and --help answers without touching anything. It used to run only
 * after the migration lane, so `--run-migrations --typo` migrated first and refused after.
 *
 * AIDEV-NOTE: migrations must run through ServerZenitRuntime, not HohenheimDatabase.init(): the framework prints the
 * sentinels zenit-dev's migration preflight reads. A hand-rolled branch printed neither, so every successful run was
 * reported as "exited without proving completion" and the server never started. The framework's lane also owns
 * --repair-migration-checksums, which a hand-rolled guard around that call silently hid on this host.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class HohenheimHost implements HostBoot {

    /** Settings, grant declarations and the datasource; the lane closes what this returns. */
    @Override
    public @NonNull MigrationCapableDatasource openForMigrations() {
        HohenheimSettingsBoot.load();
        HohenheimAccess.declareGrantableModels();
        return HohenheimDatabase.openDatasource();
    }

    @Override
    public OfflineCommands.@NonNull PreparedHost prepare(OfflineCommand.@NonNull Needs needs) {
        return OfflineBoot.prepareHost(needs);
    }

    /**
     * AIDEV-NOTE: the grant declarations come BEFORE the migrations, not with the rest of the auth wiring. They carry
     * the per-model LIVENESS definition, and zenit-auth's one-time orphan purge (M009) is a migration: a declaration
     * that lands afterwards leaves that purge judging a hand-stamped soft delete as a live record. Pure registry
     * writes, so nothing here needs the database.
     */
    @Override
    public void beforeDatasources() {
        // Load the framework's settings chain, hohenheim.* included (a retired settings/hohenheim.dry is adopted into
        // settings/local.dry first). Also captures the HohenheimRoles snapshot, which every role gate reads.
        HohenheimSettingsBoot.load();

        // Upstream kinds and auth-provider types self-register through compile-time discovery (BlastAutoLoadInit);
        // nothing needs an explicit boot here since the host-user process lane was deleted.

        HohenheimEndpoints.init();
        HohenheimChannels.init();
        HohenheimAccess.declareGrantableModels();
    }

    /** Also migrates: a boot's schema is current before the first stage reads it. */
    @Override
    public @NonNull SqlDatasource registerDatasources() {
        HohenheimDatabase.init();
        return HohenheimDatabase.datasource();
    }

    /**
     * zenit-auth (session store, CSRF, middleware, /login + /setup + /account + /admin) is the discovered
     * ZenitAuthModule, installed at the MODULES stage over the default datasource just registered. Password login is
     * native; Proteus SSO is added here when configured. The users/roles resources live in HohenheimPanel's security
     * group, so zenit-auth's own default panel would be a second UI over the same records: the module's drain reads
     * this setting, so it is set before the stages.
     *
     * Everything an incoming request needs (client script location, endpoint handlers, panels, the security engine)
     * is installed by the discovered HohenheimHostWiring module at the MODULES stage, which completes before the
     * listener binds. Nothing request-facing may be added after the stages.
     */
    @Override
    public void beforeStages() {
        Zenit.SETTINGS_VALUES.setValue(AuthSettings.CMS_AUTO_PANEL, false);
        installAuthBaselines();
        registerProteusIfConfigured();
    }

    // baseline("/") is a catch-all (zenit-auth 620125d): every admin path requires login except
    // the routes that declare authenticatesItself() where they are defined (HohenheimEndpoints
    // HEALTH and DYNDNS_UPDATE) and zenit-auth's own sign-in pages. Git webhooks are served by
    // the PROXY listener (SiteDispatcher), not this server, so they stay reachable.
    private static void installAuthBaselines() {
        AuthRegistry.baseline("/", AuthRequirement.requiresLogin());
        // declareGrantableModels() already ran, before the migrations -- see beforeDatasources().
        // AIDEV-NOTE: Hohenheim's permissions are declared at their homes (Permission.declare: HohenheimSources,
        // HohenheimAccess, TenantDatabases), each loaded at boot, and the grants editor lists Permissions.declared().
        // Every one of them is DELEGABLE except hohenheim.admin.system (HohenheimSources says why); the owner's call
        // of 2026-08-15 stands: holding a permission includes granting it, so an admin may mint a peer admin. Do not
        // reintroduce the asymmetry without the owner saying so. PermissionVocabularyTest is the guard.
        ProteusRealmSuggestions.register();
    }

    // Register the Proteus realm as an SSO option when configured; password login is always available.
    private static void registerProteusIfConfigured() {
        if (!HohenheimSettings.isOn(HohenheimSettings.AuthProteus.ENABLED)) {
            return;
        }
        String endpoint = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ENDPOINT);
        String realmClient = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.REALM_CLIENT);
        String accessKey = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.ACCESS_KEY);
        if (endpoint == null || endpoint.isBlank() || realmClient == null || realmClient.isBlank()
            || accessKey == null || accessKey.isBlank()) {
            return;
        }
        String authenticator = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.AuthProteus.AUTHENTICATOR);
        IdentityProviderRegistry.register(
            new ProteusIdentityProvider("proteus", "Proteus",
                // The operator's own setting: any address (a realm on this host or the LAN), still pinned.
                new ProteusClient(endpoint, realmClient, accessKey, OutboundUrlGuard.ANY_ADDRESS), authenticator,
                false),
            AutoProvisioningSink.builder().build());
    }
}
