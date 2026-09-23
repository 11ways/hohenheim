package be.elevenways.hohenheim.server.options;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Live server-name registry used by type-specific placement fields. */
public final class ServerOptions {

    public static final Registry.Simple<TypeDefinition> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "servers"));

    private static volatile boolean populated = false;

    /** The ids the last refresh published: the only entries a refresh may prune. */
    private static Set<Identifier> published = Set.of();

    private ServerOptions() {}

    /** Fill the registry on first use; mutations call {@link #refresh()} directly. */
    public static void ensureFresh() {
        if (!populated) refresh();
    }

    // AIDEV-NOTE: read-only on purpose. This used to open with ensureLocal(), so the FIRST
    // getSchema() of a type-switched sub-form INSERTED the local host row mid-render. The
    // row is seeded at boot now (LocalServerSeeder); refreshing the registry only reads.
    public static synchronized void refresh() {
        Map<Identifier, TypeDefinition> entries = new LinkedHashMap<>();
        for (Row row : Models.get(ServerModel.class).find().all()) {
            String name = row.get(ServerModel.NAME);
            if (name != null && !name.isBlank()) {
                // Keyed by the server's ID (the canonical host key), never its name:
                // stored settings keep pointing at the same host through a rename.
                entries.put(Identifier.of("hohenheim", String.valueOf(row.get(ServerModel.ID))),
                    new ServerEntry(name, row.get(ServerModel.MODE)));
            }
        }
        // AIDEV-NOTE: overwrite, then prune; never clear first. A clear-then-refill left a
        // window in which a concurrent form render read an EMPTY registry and offered no
        // host at all. Readers now see either the old or the new entry for every live host,
        // and a removed host disappears only once every current one is in place.
        entries.forEach(REGISTRY::add);
        for (Identifier previous : published) {
            if (!entries.containsKey(previous)) {
                REGISTRY.remove(previous);
            }
        }
        published = Set.copyOf(entries.keySet());
        populated = true;
    }

    /** Display name for a stored server key, tolerant of a key whose server vanished. */
    public static String nameFromKey(@Nullable Object storedKey) {
        try {
            return ServerModel.nameOf(ServerModel.canonicalServerId(storedKey));
        } catch (IllegalArgumentException unknown) {
            return String.valueOf(storedKey);
        }
    }

    private record ServerEntry(String name, String mode) implements TypeDefinition {
        @Override public String getDisplayName() {
            return ServerModel.MODE_LOCAL.equals(mode) ? name + " (local)" : name;
        }

        @Override public Microcopy getLabel() {
            return ServerModel.MODE_LOCAL.equals(mode)
                ? Microcopy.of("server_local").withFilter("scope", "server_option")
                    .withArg("name", name)
                : Microcopy.literal(name);
        }

        @Override public @Nullable Schema getSchema() { return null; }
    }
}
