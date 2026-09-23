package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.hohenheim.server.auth.BasicCredentials;
import be.elevenways.hohenheim.server.auth.SiteAuthContext;
import be.elevenways.hohenheim.server.auth.SiteAuthGate;
import be.elevenways.hohenheim.server.auth.SiteAuthProviderTypeHandler;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.auth.server.PasswordHasher;
import be.elevenways.zenit.common.orm.field.StringMapField;
import be.elevenways.zenit.common.orm.model.Schema;
import be.elevenways.zenit.server.security.SecureTokens;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Basic Auth provider backed by an operator-editable username to argon2-hash map.
 *
 * AIDEV-NOTE: the passwords used to be stored and compared in PLAINTEXT, which contradicted
 * BasicCredentials being THE argon2 home. They are hashed on save through BasicCredentials,
 * M011 hashed every value stored before that, and verification refuses anything unhashed.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public class BasicAuthProviderType implements SiteAuthProviderTypeHandler {

    public static final Identifier ID = Identifier.of("hohenheim", "basic");

    /** Config key holding the username -> password map. */
    public static final String CREDENTIALS = "credentials";

    // Legacy list-shape keys, still read for pre-migration rows.
    public static final String USERNAME = "username";
    public static final String PASSWORD_HASH = "password_hash";

    public static final Schema CONFIG_SCHEMA = new Schema();
    static {
        // secret(): the form shows each username with a BLANK password (FormSecrets masks
        // string-map values per key) and a blank submit restores the stored hash, so a
        // password is write-only and the argon2 hash never leaves the server.
        CONFIG_SCHEMA.addField(StringMapField.builder(CREDENTIALS)
            .label(HohenheimFormCopy.label(CREDENTIALS))
            .help(HohenheimFormCopy.help(CREDENTIALS))
            .secret()
            .build());
    }

    @Override
    public Identifier typeId() { return ID; }

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
    public Icon getIcon() {
        return Icon.LOCK;
    }

    @Override
    public String getColor() {
        return "amber";
    }

    @Override
    public SiteAuthGate createGate(SiteAuthContext context) {
        return new BasicAuthGate(context);
    }

    /**
     * Normalize the submitted username -> password map into stable insertion order, hashing
     * every typed password.
     *
     * AIDEV-NOTE: the edit form never shows the stored hash (the field is secret, so the box is
     * blank and the framework restores the hash on a blank submit); this method still keeps the
     * stored hash on a blank value for any writer that bypasses the form, and
     * {@code hashIfNeeded} leaves an already-argon2 value alone, so clearing the box never
     * silently sets an empty password. A new user saved with a blank password stores a blank
     * value, which never verifies.
     */
    @Override
    public Map<String, Object> normalizeConfigForSave(Map<String, Object> submitted,
                                                      @Nullable Map<String, Object> existing) {
        Map<String, String> stored = credentials(existing);
        Map<String, String> out = new LinkedHashMap<>();

        Object rawSubmitted = submitted != null ? submitted.get(CREDENTIALS) : null;
        if (rawSubmitted instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String username = str(entry.getKey());
                if (username == null || username.isBlank()) {
                    continue;
                }
                String password = str(entry.getValue());
                if (password == null || password.isBlank()) {
                    out.put(username, stored.getOrDefault(username, ""));
                } else {
                    out.put(username, BasicCredentials.hashIfNeeded(password));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(CREDENTIALS, out);
        return result;
    }

    /**
     * Verify a {@code Authorization: Basic} header against the stored credential map.
     *
     * An unknown username (or one without a usable hash) still pays one argon2 verification, so
     * the response time does not tell which usernames exist.
     *
     * @return the matching username, or null if the header is absent/malformed or no credential matches
     */
    public static @Nullable String verify(@Nullable String authHeader, Map<String, String> credentials) {
        BasicCredentials.Presented presented = BasicCredentials.parse(authHeader);
        if (presented == null) {
            return null;
        }

        String stored = credentials.get(presented.username());
        if (stored == null || stored.isBlank()) {
            PasswordHasher.verify(presented.password(), DummyHash.VALUE);
            return null;
        }
        return BasicCredentials.verifyPassword(presented.password(), stored, "basic auth provider")
            ? presented.username() : null;
    }

    /**
     * Stored username -> password map from a provider config. Reads the canonical map shape
     * and the legacy pre-migration list-of-{username,password_hash} shape.
     */
    public static Map<String, String> credentials(@Nullable Map<String, Object> config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        Object raw = config.get(CREDENTIALS);
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String username = str(entry.getKey());
                String password = str(entry.getValue());
                if (username != null && password != null) {
                    result.put(username, password);
                }
            }
        } else if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> cred) {
                    String username = str(cred.get(USERNAME));
                    String password = str(cred.get(PASSWORD_HASH));
                    if (username != null && password != null) {
                        result.put(username, password);
                    }
                }
            }
        }
        return result;
    }

    static @Nullable String str(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The hash an unknown username is verified against; lazily built, so class loading at
     * provider discovery pays no argon2.
     */
    private static final class DummyHash {
        static final String VALUE = PasswordHasher.hash(SecureTokens.randomToken());
    }
}
