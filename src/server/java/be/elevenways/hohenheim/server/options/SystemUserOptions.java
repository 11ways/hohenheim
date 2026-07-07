package be.elevenways.hohenheim.server.options;

import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Live registry of discovered system users, driving the run-as dropdown in
 * the node/command site-type settings. Refreshed by the UpdateSystemUsers
 * discovery task; stored setting values are {@code hohenheim:<username>} keys.
 */
public final class SystemUserOptions {

    public static final Registry.Simple<TypeDefinition> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "system_users"));

    private SystemUserOptions() {}

    /** Rebuild the registry from the system_users table. */
    public static synchronized void refresh() {
        REGISTRY.clear();
        for (Row row : Models.get(SystemUserModel.class).findActive()) {
            String name = row.get(SystemUserModel.NAME);
            if (name == null || name.isBlank()) {
                continue;
            }
            Integer uid = row.get(SystemUserModel.UID);
            REGISTRY.add(Identifier.of("hohenheim", name), new UserEntry(name, uid));
        }
    }

    /**
     * @param storedKey the {@code user} setting value ({@code hohenheim:<username>})
     * @return the username, or null when unset/blank
     */
    public static @Nullable String usernameFromKey(@Nullable Object storedKey) {
        if (!(storedKey instanceof String key) || key.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(key);
        return id != null ? id.getPath() : key;
    }

    private record UserEntry(String name, Integer uid) implements TypeDefinition {
        @Override public String getDisplayName() {
            return uid != null ? name + " (uid " + uid + ")" : name;
        }
        @Override public @Nullable Schema getSchema() { return null; }
        @Override public Map<String, Object> getProperties() { return Map.of(); }
    }
}
