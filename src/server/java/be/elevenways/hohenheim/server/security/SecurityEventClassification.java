package be.elevenways.hohenheim.server.security;

import be.elevenways.zenit.common.security.KnownSecurityEvents;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Central classification of known positive security events that local threat scoring must ignore. */
public final class SecurityEventClassification {

    private static final Set<String> POSITIVE_TYPES = ConcurrentHashMap.newKeySet();

    static {
        registerPositive(SecurityEventTypes.AUTH_LOGIN_SUCCEEDED);
    }

    private SecurityEventClassification() {
    }

    /** Registers known positive event types for analytics while excluding them from threat scoring. */
    public static void registerPositive(@NonNull String... types) {
        for (String type : types) {
            if (type != null && !type.isBlank()) {
                POSITIVE_TYPES.add(type);
            }
        }
        KnownSecurityEvents.register(types);
    }

    public static boolean isPositive(@NonNull String type) {
        return POSITIVE_TYPES.contains(type);
    }
}
