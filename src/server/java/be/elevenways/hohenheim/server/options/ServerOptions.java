package be.elevenways.hohenheim.server.options;

import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.registry.Registry;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.TypeDefinition;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Live server-name registry used by type-specific placement fields. */
public final class ServerOptions {

    public static final Registry.Simple<TypeDefinition> REGISTRY =
        new Registry.Simple<>(Identifier.of("hohenheim", "servers"));

    private static volatile boolean populated = false;

    private ServerOptions() {}

    /** Fill the registry on first use; mutations call {@link #refresh()} directly. */
    public static void ensureFresh() {
        if (!populated) refresh();
    }

    public static synchronized void refresh() {
        new ServerService().ensureLocal();
        var entries = new java.util.LinkedHashMap<Identifier, TypeDefinition>();
        for (Row row : Models.get(ServerModel.class).find().all()) {
            String name = row.get(ServerModel.NAME);
            if (name != null && !name.isBlank()) {
                entries.put(Identifier.of("hohenheim", name), new ServerEntry(name, row.get(ServerModel.MODE)));
            }
        }
        REGISTRY.clear();
        entries.forEach(REGISTRY::add);
        populated = true;
    }

    public static String nameFromKey(@Nullable Object storedKey) {
        if (!(storedKey instanceof String key) || key.isBlank()) return ServerService.LOCAL;
        Identifier id = Identifier.tryParse(key);
        return id != null ? id.getPath() : key;
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
