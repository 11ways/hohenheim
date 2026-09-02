package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * THE engine PROCESS a managed database lives in, as the runtime tier sees it: which
 * product record owns the container, what runs in it, and the root credentials the
 * controller drives it with. A dedicated database is its own host; a shared database's
 * host is its {@link DatabaseEngineModel} row. Every operation that touches a
 * container (readiness, dump, restore, link networks, destroy) resolves this once
 * through {@link #serving(Row)} and never branches on the placement itself.
 *
 * @param ownerModel   the product record that owns the engine instance
 * @param ownerId      that record's id
 * @param name         the name-keyed identity (data volume, instance name)
 * @param engine       the engine kind
 * @param image        the container image, or null for the engine's default
 * @param ephemeral    whether the data directory is a tmpfs
 * @param rootUser     the engine superuser
 * @param rootPassword its password
 * @param initDatabase the database the engine's init creates and the probe dials
 * @param serverId     the host
 * @param limits       the declared container ceilings
 * @param shared       whether this process hosts logical databases
 */
public record EngineHost(@NonNull Identifier ownerModel, int ownerId, @NonNull String name,
                         ManagedDatabase.@NonNull Engine engine, @Nullable String image,
                         boolean ephemeral, @NonNull String rootUser, @NonNull String rootPassword,
                         @NonNull String initDatabase, int serverId, @NonNull ResourceLimits limits,
                         boolean shared) {

    /** A DEDICATED database record: the container is its own, the record user is root. */
    public static @NonNull EngineHost dedicated(@NonNull Row database) {
        return new EngineHost(DatabaseModel.MODEL_ID, database.get(DatabaseModel.ID),
            String.valueOf((Object) database.get(DatabaseModel.NAME)),
            ManagedDatabase.engineOf(database), database.get(DatabaseModel.IMAGE),
            Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL)),
            orEmpty(database.get(DatabaseModel.DB_USER)),
            orEmpty(database.get(DatabaseModel.DB_PASSWORD)),
            orEmpty(database.get(DatabaseModel.DB_NAME)),
            ServerModel.canonicalServerId(database.get(DatabaseModel.SERVER_ID)),
            ResourceLimits.of(database.get(DatabaseModel.MEMORY_LIMIT_MB),
                database.get(DatabaseModel.CPU_LIMIT)),
            false);
    }

    /** A SHARED engine row. */
    public static @NonNull EngineHost ofEngine(@NonNull Row engineRow) {
        String token = engineRow.get(DatabaseEngineModel.ENGINE);
        ManagedDatabase.Engine engine = ManagedDatabase.Engine.forToken(token);
        if (engine == null) {
            throw new IllegalArgumentException("Unknown database engine token: " + token);
        }
        String rootUser = orEmpty(engineRow.get(DatabaseEngineModel.ROOT_USER));
        return new EngineHost(DatabaseEngineModel.MODEL_ID, engineRow.get(DatabaseEngineModel.ID),
            String.valueOf((Object) engineRow.get(DatabaseEngineModel.NAME)),
            engine, engineRow.get(DatabaseEngineModel.IMAGE), false,
            rootUser, orEmpty(engineRow.get(DatabaseEngineModel.ROOT_PASSWORD)),
            engine.rootDatabase(rootUser),
            ServerModel.canonicalServerId(engineRow.get(DatabaseEngineModel.SERVER_ID)),
            ResourceLimits.of(engineRow.get(DatabaseEngineModel.MEMORY_LIMIT_MB),
                engineRow.get(DatabaseEngineModel.CPU_LIMIT)),
            true);
    }

    /**
     * The host SERVING a managed database record, whatever its placement.
     *
     * @throws IllegalStateException for a shared record whose engine row is gone -- a
     *         dangling binding is a defect to name, never a dedicated host to guess
     */
    public static @NonNull EngineHost serving(@NonNull Row database) {
        if (!DatabaseModel.isShared(database)) {
            return dedicated(database);
        }
        Integer engineId = database.get(DatabaseModel.ENGINE_ID);
        Row engine = engineId == null ? null
            : Models.get(DatabaseEngineModel.class).findById(engineId);
        if (engine == null) {
            throw new IllegalStateException("Managed database '"
                + database.get(DatabaseModel.NAME) + "' is shared but its engine "
                + engineId + " does not exist");
        }
        return ofEngine(engine);
    }

    /** The named data volume of a persistent host; keyed to the NAME so it outlives runtime rows. */
    public @NonNull String dataVolume() {
        return this.shared
            ? ControllerScope.handle(ControllerScope.KIND_DB_ENGINE, this.name) + "-data"
            : DatabaseInstances.dataVolumeOf(this.name);
    }

    /** The instance record's name: recognisable in the admin list, never an identity. */
    public @NonNull String instanceName() {
        return (this.shared ? "dbengine-" : "db-") + this.name;
    }

    /** The image to run: the declared one, else the engine's default. */
    public @NonNull String resolvedImage() {
        return this.image == null || this.image.isBlank() ? this.engine.defaultImage : this.image;
    }

    private static @NonNull String orEmpty(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
