package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Basic Auth provider: a username -> password credential map. Passwords are hashed
 * with Argon2id at save time so no plaintext is ever stored; the gate verifies once per session.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthProviderType implements SiteAuthProviderTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "basic");

    /** Config key holding the username -> password-hash map. */
    public static final String CREDENTIALS = "credentials";

    // Legacy list-shape keys, still read by credentialHashes for pre-migration rows.
    public static final String USERNAME = "username";
    public static final String PASSWORD_HASH = "password_hash";

    private static final String ARGON2_PREFIX = "$argon2";

    public static final Schema CONFIG_SCHEMA = new Schema();
    static {
        CONFIG_SCHEMA.addField(StringMapField.builder(CREDENTIALS).build());
    }

    @Override
    public String getDisplayName() {
        return "HTTP Basic Auth";
    }

    @Override
    public Schema getSchema() {
        return CONFIG_SCHEMA;
    }

    @Override
    public boolean usesRequiredPermission() {
        return false;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of("description", "Username + password credentials checked on the proxy",
            "icon", "lock");
    }

    @Override
    public SiteAuthGate createGate(SiteAuthContext context) {
        return new BasicAuthGate(context);
    }

    /**
     * Normalize the submitted username -> password map for storage. A plaintext value is
     * hashed; a blank value carries the user's existing hash forward (edit semantics); a
     * value that already IS an Argon2 hash round-trips untouched, so redisplayed hashes
     * never get double-hashed.
     */
    @Override
    public Map<String, Object> normalizeConfigForSave(Map<String, Object> submitted,
                                                      @Nullable Map<String, Object> existing) {
        Map<String, String> existingHashes = credentialHashes(existing);
        Map<String, String> out = new LinkedHashMap<>();

        Object rawSubmitted = submitted != null ? submitted.get(CREDENTIALS) : null;
        if (rawSubmitted instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String username = str(entry.getKey());
                if (username == null || username.isBlank()) {
                    continue;
                }
                String password = str(entry.getValue());
                String hash;
                if (password == null || password.isEmpty()) {
                    hash = existingHashes.get(username);  // edit left blank: carry the existing hash
                    if (hash == null) {
                        continue;  // no new password and no prior hash: nothing to store
                    }
                } else if (password.startsWith(ARGON2_PREFIX)) {
                    hash = password;  // redisplayed stored hash resubmitted unchanged
                } else {
                    hash = PasswordHasher.hash(password);
                }
                out.put(username, hash);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CREDENTIALS, out);
        return result;
    }

    /**
     * Verify a {@code Authorization: Basic} header against the stored credential map.
     *
     * @return the matching username, or null if the header is absent/malformed or no credential matches
     */
    public static @Nullable String verify(@Nullable String authHeader, Map<String, String> credentials) {
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }

        String user;
        String pass;
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            user = decoded.substring(0, colon);
            pass = decoded.substring(colon + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String hash = credentials.get(user);
        if (hash != null && PasswordHasher.verify(pass, hash)) {
            return user;
        }
        return null;
    }

    /**
     * Stored username -> hash map from a provider config. Reads the canonical map shape
     * and the legacy pre-migration list-of-{username,password_hash} shape.
     */
    public static Map<String, String> credentialHashes(@Nullable Map<String, Object> config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        Object raw = config.get(CREDENTIALS);
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String username = str(entry.getKey());
                String hash = str(entry.getValue());
                if (username != null && hash != null) {
                    result.put(username, hash);
                }
            }
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> cred) {
                    String username = str(cred.get(USERNAME));
                    String hash = str(cred.get(PASSWORD_HASH));
                    if (username != null && hash != null) {
                        result.put(username, hash);
                    }
                }
            }
        }
        return result;
    }

    static @Nullable String str(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
