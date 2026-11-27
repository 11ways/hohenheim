package be.elevenways.hohenheim.server.upstream.kinds;

import be.elevenways.hohenheim.server.upstream.UpstreamKindHandler;
import be.elevenways.hohenheim.server.upstream.UpstreamKindHandlers;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.zenit.common.orm.field.Field;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * THE home of which settings of a site's kind the site's manager changes without being an administrator: a fact on
 * each declared setting ({@link #MANAGER_CHANGES}), read by the model's write check (TenantWrites), the site tools
 * (SiteKindWrites) and the /manage site form (SiteWrites.MANAGE_FORM).
 *
 * AIDEV-NOTE: fail closed. A setting that does not declare the fact, a key no schema of the kind declares and a kind
 * this build does not know are all administrator-only, so a setting added later stays host power until its
 * declaration says otherwise. What a manager changes is what the site shares and what its folders list; everything
 * that says where traffic goes or what the host serves (an upstream, a host path, a storage, the trust) is not.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class SiteManagerSettings {

    /** The declared fact: the site's manager changes this setting; absent means only an administrator does. */
    public static final IdentityKey<Boolean> MANAGER_CHANGES = IdentityKey.create("hohenheim_site_manager_changes");

    private SiteManagerSettings() {
    }

    /** @return whether the setting declares that the site's manager changes it */
    public static boolean managerChanges(@NonNull Field<?, ?> setting) {
        return Boolean.TRUE.equals(setting.getAttribute(MANAGER_CHANGES));
    }

    /** @return the settings of a kind's schema the site's manager changes, in declaration order */
    public static @NonNull List<Field<?, ?>> of(@NonNull Schema schema) {
        List<Field<?, ?>> settings = new ArrayList<>();
        for (Field<?, ?> field : schema.getFields().values()) {
            if (managerChanges(field)) settings.add(field);
        }
        return settings;
    }

    /** @return the settings of the stored kind the site's manager changes, none for a kind this build does not know */
    public static @NonNull List<Field<?, ?>> ofKind(@Nullable Object kind) {
        UpstreamKindHandler handler = kind == null ? null : UpstreamKindHandlers.getHandler(String.valueOf(kind));
        return handler == null ? List.of() : of(handler.getSchema());
    }

    /**
     * @return the first stored key whose value differs between the two maps and that the kind's manager does not
     *     change, null when every difference is the manager's
     */
    public static @Nullable String firstHostKeyChanged(@Nullable Object kind, @NonNull Map<?, ?> stored,
                                                       @NonNull Map<?, ?> written) {
        Set<String> managed = new LinkedHashSet<>();
        for (Field<?, ?> field : ofKind(kind)) managed.add(field.getName());
        Set<Object> keys = new LinkedHashSet<>(stored.keySet());
        keys.addAll(written.keySet());
        for (Object key : keys) {
            String name = String.valueOf(key);
            if (!managed.contains(name) && !Objects.equals(stored.get(key), written.get(key))) return name;
        }
        return null;
    }

    /**
     * The settings a manager's save stores: every stored key the manager does not change as it is stored, and the
     * manager's settings as posted, one left out of the post taken away (its default applies again).
     *
     * @param posted the manager's settings by their input names, as the /manage form and the tools post them
     * @return the settings to store, keyed by stored names
     */
    public static @NonNull Map<String, Object> merged(@Nullable Object kind, @NonNull Map<String, Object> stored,
                                                      @NonNull Map<String, Object> posted) {
        Map<String, Object> settings = new LinkedHashMap<>(stored);
        for (Field<?, ?> field : ofKind(kind)) {
            Object value = posted.get(field.getInputName());
            if (value == null) {
                settings.remove(field.getName());
            } else {
                settings.put(field.getName(), value);
            }
        }
        return settings;
    }

    /**
     * @return the settings without those the kind's manager changes: the part that says where the site's traffic
     *     goes and what the host serves, which OperatorTrustedWrites marks by who set it
     */
    public static @NonNull Map<String, Object> hostSettings(@Nullable Object kind,
                                                            @NonNull Map<String, Object> settings) {
        Map<String, Object> host = new LinkedHashMap<>(settings);
        for (Field<?, ?> field : ofKind(kind)) host.remove(field.getName());
        return host;
    }

    /** @return the manager's stored settings keyed by their input names, the shape the /manage form posts */
    public static @NonNull Map<String, Object> posted(@Nullable Object kind, @NonNull Map<String, Object> stored) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field<?, ?> field : ofKind(kind)) {
            Object value = stored.get(field.getName());
            if (value != null) values.put(field.getInputName(), value);
        }
        return values;
    }
}
