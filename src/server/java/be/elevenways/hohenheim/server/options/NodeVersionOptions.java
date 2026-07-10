package be.elevenways.hohenheim.server.options;

import be.elevenways.hohenheim.model.NodeVersionModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Live registry of discovered node versions, driving the node-version
 * dropdown in the node site-type settings. Refreshed by the
 * UpdateNodeVersions discovery task; stored setting values are
 * {@code hohenheim:<version>} keys.
 */
public final class NodeVersionOptions {

    public static final Registry.Simple<TypeDefinition> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "node_versions"));

    private NodeVersionOptions() {}

    /** Rebuild the registry from the node_versions table. */
    public static synchronized void refresh() {
        REGISTRY.clear();
        for (Row row : Models.get(NodeVersionModel.class).findActive()) {
            String version = row.get(NodeVersionModel.VERSION);
            if (version == null || version.isBlank()) {
                continue;
            }
            REGISTRY.add(Identifier.of("hohenheim", version),
                new VersionEntry(version, row.get(NodeVersionModel.PATH)));
        }
    }

    /**
     * Resolve the {@code node} setting value to a node binary path. Unset,
     * dangling, or legacy values fall back to {@code "node"} (PATH lookup) so
     * a stale reference never crashes a spawn.
     */
    public static @NonNull String resolvePath(@Nullable Object storedKey) {
        String version = versionFromKey(storedKey);
        if (version == null) {
            return "node";
        }
        Row row = Models.get(NodeVersionModel.class).find()
            .where(NodeVersionModel.VERSION.eq(version))
            .first();
        if (row == null) {
            return "node";
        }
        String path = row.get(NodeVersionModel.PATH);
        return path != null && !path.isBlank() ? path : "node";
    }

    private static @Nullable String versionFromKey(@Nullable Object storedKey) {
        if (!(storedKey instanceof String key) || key.isBlank()) {
            return null;
        }
        Identifier id = Identifier.tryParse(key);
        return id != null ? id.getPath() : key;
    }

    private record VersionEntry(String version, String path) implements TypeDefinition {
        @Override public String getDisplayName() {
            return "Node " + version;
        }
        @Override public @Nullable Schema getSchema() { return null; }
    }
}
