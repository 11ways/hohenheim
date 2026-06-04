package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.common.orm.field.SchemaField;
import be.elevenways.zenit.common.orm.field.StringField;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Basic Auth provider: a repeatable username + password credential list. Passwords are hashed
 * with Argon2id at save time so no plaintext is ever stored; the gate verifies once per session.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthProviderType implements SiteAuthProviderTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "basic");

    /** Config key holding the repeatable credential list. */
    public static final String CREDENTIALS = "credentials";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";           // submitted (plaintext) only
    public static final String PASSWORD_HASH = "password_hash";  // persisted

    public static final Schema CREDENTIAL_SCHEMA = new Schema();
    static {
        CREDENTIAL_SCHEMA.addField(StringField.builder().name(USERNAME).build());
        CREDENTIAL_SCHEMA.addField(StringField.builder().name(PASSWORD).build());
    }

    public static final Schema CONFIG_SCHEMA = new Schema();
    static {
        CONFIG_SCHEMA.addField(SchemaField.builder(CREDENTIALS).subSchema(CREDENTIAL_SCHEMA).build());
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

    @Override
    public Map<String, Object> normalizeConfigForSave(Map<String, Object> submitted,
                                                      @Nullable Map<String, Object> existing) {
        Map<String, String> existingHashes = existingHashes(existing);
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> cred : credentialList(submitted)) {
            String username = str(cred.get(USERNAME));
            if (username == null || username.isBlank()) {
                continue;
            }

            String password = str(cred.get(PASSWORD));
            String hash;
            if (password != null && !password.isEmpty()) {
                hash = PasswordHasher.hash(password);
            } else {
                hash = existingHashes.get(username);  // edit left blank: carry the existing hash
                if (hash == null) {
                    continue;  // no new password and no prior hash: nothing to store
                }
            }

            Map<String, Object> stored = new HashMap<>();
            stored.put(USERNAME, username);
            stored.put(PASSWORD_HASH, hash);
            out.add(stored);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(CREDENTIALS, out);
        return result;
    }

    /**
     * Verify a {@code Authorization: Basic} header against the stored credential list.
     *
     * @return the matching username, or null if the header is absent/malformed or no credential matches
     */
    public static @Nullable String verify(@Nullable String authHeader, List<Map<String, Object>> credentials) {
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

        for (Map<String, Object> cred : credentials) {
            if (user.equals(str(cred.get(USERNAME)))) {
                String hash = str(cred.get(PASSWORD_HASH));
                if (hash != null && PasswordHasher.verify(pass, hash)) {
                    return user;
                }
            }
        }
        return null;
    }

    static Map<String, String> existingHashes(@Nullable Map<String, Object> existing) {
        Map<String, String> map = new HashMap<>();
        for (Map<String, Object> cred : credentialList(existing)) {
            String username = str(cred.get(USERNAME));
            String hash = str(cred.get(PASSWORD_HASH));
            if (username != null && hash != null) {
                map.put(username, hash);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> credentialList(@Nullable Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object raw = config.get(CREDENTIALS);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    static @Nullable String str(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
