package be.elevenways.hohenheim.server.auth;

import be.elevenways.hohenheim.model.SiteAuthProviderModel;
import be.elevenways.hohenheim.server.auth.types.ProteusAuthProviderType;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.server.identity.proteus.ProteusClient;
import be.elevenways.zenit.common.edit.EditContext;
import be.elevenways.zenit.common.edit.FormSecrets;
import be.elevenways.zenit.common.edit.PermissionSuggestionSources;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.KnownPermission;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feeds the required-permission autocomplete with the ASSIGNED Proteus realm's
 * observed vocabulary: the form's (non-secret) endpoint + realm client identify
 * the realm, the access key comes from the matching STORED provider row (form
 * values mask secrets), and results are cached so admin renders never hammer
 * (or hang on) the remote.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ProteusRealmSuggestions {

    /** Positive results stay fresh this long; failures are negative-cached equally. */
    private static final long CACHE_MILLIS = 120_000;

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    private record Cached(List<KnownPermission> entries, long at) {}

    private ProteusRealmSuggestions() {}

    public static void register() {
        PermissionSuggestionSources.register(
            SiteAuthProviderModel.PROTEUS_SUGGESTION_SOURCE, ProteusRealmSuggestions::resolve);
    }

    static @NonNull List<KnownPermission> resolve(@NonNull Map<String, Object> rootValues,
                                                  @NonNull EditContext context) {
        if (!(rootValues.get(SiteAuthProviderModel.CONFIG.getName()) instanceof Map<?, ?> config)) {
            return List.of();
        }
        String endpoint = stringOf(config.get(ProteusAuthProviderType.ENDPOINT));
        String realmClient = stringOf(config.get(ProteusAuthProviderType.REALM_CLIENT));
        if (endpoint == null || realmClient == null) {
            return List.of();
        }

        String cacheKey = endpoint + "|" + realmClient;
        Cached cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
            return cached.entries();
        }

        String accessKey = usableAccessKey(config.get(ProteusAuthProviderType.ACCESS_KEY));
        if (accessKey == null) {
            accessKey = storedAccessKey(endpoint, realmClient);
        }

        List<KnownPermission> entries = List.of();
        if (accessKey != null) {
            try {
                entries = new ProteusClient(endpoint, realmClient, accessKey).knownPermissions();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                Blast.log("hohenheim.proteus.suggestions_failed", endpoint, realmClient, String.valueOf(error));
            }
        }
        CACHE.put(cacheKey, new Cached(entries, System.currentTimeMillis()));
        return entries;
    }

    /** A form-supplied access key is only usable when it is a REAL value, not the mask. */
    private static @Nullable String usableAccessKey(@Nullable Object value) {
        String key = stringOf(value);
        return key != null && !FormSecrets.STORED_MARKER.equals(key) ? key : null;
    }

    /**
     * The stored provider row matching the form's endpoint + realm client carries
     * the real access key (form values mask secrets). Same realm = same
     * vocabulary, so any matching row will do.
     */
    private static @Nullable String storedAccessKey(@NonNull String endpoint, @NonNull String realmClient) {
        for (Row row : Models.get(SiteAuthProviderModel.class).find().all()) {
            if (!(row.get(SiteAuthProviderModel.CONFIG) instanceof Map<?, ?> config)) {
                continue;
            }
            if (endpoint.equals(stringOf(config.get(ProteusAuthProviderType.ENDPOINT)))
                && realmClient.equals(stringOf(config.get(ProteusAuthProviderType.REALM_CLIENT)))) {
                String key = stringOf(config.get(ProteusAuthProviderType.ACCESS_KEY));
                if (key != null) {
                    return key;
                }
            }
        }
        return null;
    }

    private static @Nullable String stringOf(@Nullable Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Test hook. */
    static void clearCache() {
        CACHE.clear();
    }
}
