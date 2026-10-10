package be.elevenways.hohenheim.server.options;

import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.DatasourceDerived;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Live server-name registry used by type-specific placement fields.
 *
 * AIDEV-NOTE: the registry is derived from the datasource ServerModel resolves to, so it belongs to that datasource
 * instance through {@link DatasourceDerived}: ensureFresh() refreshes whenever it resolves to
 * another one than the last refresh read. A once-per-JVM latch kept offering the hosts of whichever class of a
 * shared-JVM lane filled it first.
 */
public final class ServerOptions {

    public static final Registry<TypeDefinition> REGISTRY = Registry.create(HohenheimIds.id("server"));

    private static final DatasourceDerived<Map<Identifier, TypeDefinition>> CACHE =
        new DatasourceDerived<>(ServerOptions.class);

    /** The ids the last refresh published: the only entries a refresh may prune. */
    private static Set<Identifier> published = Set.of();

    private ServerOptions() {}

    /** Fill the registry on first use or after a datasource swap; mutations call {@link #refresh()} directly. */
    public static void ensureFresh() {
        ServerModel servers = Models.get(ServerModel.class);
        if (servers.resolvesDatasource()) {
            CACHE.get(servers.getResolvedDatasource(), ServerOptions::populate);
        }
    }

    // AIDEV-NOTE: read-only on purpose. This used to open with ensureLocal(), so the FIRST
    // getSchema() of a type-switched sub-form INSERTED the local host row mid-render. The
    // row is seeded at boot now (LocalServerSeeder); refreshing the registry only reads.
    public static synchronized void refresh() {
        CACHE.refresh(Models.get(ServerModel.class).getResolvedDatasource(), ServerOptions::populate);
    }

    private static Map<Identifier, TypeDefinition> populate(Datasource source) {
        Map<Identifier, TypeDefinition> entries = new LinkedHashMap<>();
        for (Row row : Models.get(ServerModel.class).find().on(source).all()) {
            String name = row.get(ServerModel.NAME);
            if (name != null && !name.isBlank()) {
                // Keyed by the server's ID (the canonical host key), never its name:
                // stored settings keep pointing at the same host through a rename.
                entries.put(HohenheimIds.id(String.valueOf(row.get(ServerModel.ID))),
                    new ServerEntry(name, HostMode.parse(row.get(ServerModel.MODE))));
            }
        }
        // AIDEV-NOTE: overwrite, then prune; never clear first. A clear-then-refill left a
        // window in which a concurrent form render read an EMPTY registry and offered no
        // host at all. Readers now see either the old or the new entry for every live host,
        // and a removed host disappears only once every current one is in place.
        entries.forEach(REGISTRY::replace);
        for (Identifier previous : published) {
            if (!entries.containsKey(previous)) {
                REGISTRY.remove(previous);
            }
        }
        published = Set.copyOf(entries.keySet());
        return Map.copyOf(entries);
    }

    /** Display name for a stored server key, tolerant of a key whose server vanished. */
    public static String nameFromKey(@Nullable Object storedKey) {
        try {
            return ServerModel.canonicalNameOf(storedKey);
        } catch (IllegalArgumentException unknown) {
            return String.valueOf(storedKey);
        }
    }

    private record ServerEntry(String name, @Nullable HostMode mode) implements TypeDefinition {
        @Override public String getDisplayName() {
            return mode == HostMode.LOCAL ? name + " (local)" : name;
        }

        @Override public Microcopy getLabel() {
            return mode == HostMode.LOCAL
                ? HohenheimMicrocopy.SERVER_OPTION.of("server_local")
                    .withArg("name", name)
                : Microcopy.literal(name);
        }

        @Override public @Nullable Schema getSchema() { return null; }
    }
}
