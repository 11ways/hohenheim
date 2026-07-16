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

    private ServerOptions() {}

    public static synchronized void refresh() {
        new ServerService().ensureLocal();
        REGISTRY.clear();
        for (Row row : Models.get(ServerModel.class).find().all()) {
            String name = row.get(ServerModel.NAME);
            if (name != null && !name.isBlank()) {
                REGISTRY.add(Identifier.of("hohenheim", name), new ServerEntry(name, row.get(ServerModel.MODE)));
            }
        }
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
                : Microcopy.of(name);
        }

        @Override public @Nullable Schema getSchema() { return null; }
    }
}
