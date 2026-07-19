package be.elevenways.hohenheim.server.auth.types;

import be.elevenways.hohenheim.HohenheimFormCopy;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Basic Auth provider backed by an operator-visible username/password map.
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

    private static final String ARGON2_PREFIX = "$argon2";

    public static final Schema CONFIG_SCHEMA = new Schema();
    static {
        CONFIG_SCHEMA.addField(StringMapField.builder(CREDENTIALS)
            .label(HohenheimFormCopy.label(CREDENTIALS))
            .help(HohenheimFormCopy.help(CREDENTIALS))
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
     * Normalize the submitted username -> password map into stable insertion order.
     */
    @Override
    public Map<String, Object> normalizeConfigForSave(Map<String, Object> submitted,
                                                      @Nullable Map<String, Object> existing) {
        Map<String, String> out = new LinkedHashMap<>();

        Object rawSubmitted = submitted != null ? submitted.get(CREDENTIALS) : null;
        if (rawSubmitted instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String username = str(entry.getKey());
                if (username == null || username.isBlank()) {
                    continue;
                }
                String password = str(entry.getValue());
                out.put(username, password != null ? password : "");
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

        String stored = credentials.get(user);
        boolean matches = stored != null && (stored.startsWith(ARGON2_PREFIX)
            ? PasswordHasher.verify(pass, stored)
            : SecureTokens.constantTimeEquals(pass, stored));
        if (matches) {
            return user;
        }
        return null;
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
}
