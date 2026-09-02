package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.Secrets;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle of SHARED database engines ({@link DatabaseEngineModel}): resolving or
 * creating the one engine of a kind on a host, keeping it running, and taking it down
 * once nothing lives on it.
 *
 * AIDEV-NOTE: an engine is never provisioned by a job of its own when a database needs
 * it -- the database's provisioning job calls {@link #ensureRunning} itself, serialized
 * per engine. The provisioning pool has two threads; two databases each waiting on a
 * third job to bring their engine up would hold both threads and starve it. Whoever
 * needs the engine brings it up, and the lock makes the second caller find it running.
 */
public final class DatabaseEngines {

    /** The GeneratedRows source token, and the Accountability origin of every write here. */
    public static final String SOURCE = "database_engine";

    private static final Map<Integer, Object> LOCKS = new ConcurrentHashMap<>();

    private DatabaseEngines() {
    }

    private static DatabaseEngineModel model() {
        return Models.get(DatabaseEngineModel.class);
    }

    /** The engine row, or an IOException naming the id. */
    public static @NonNull Row require(int engineId) throws IOException {
        Row row = model().findById(engineId);
        if (row == null) {
            throw new IOException("No shared database engine with id " + engineId);
        }
        return row;
    }

    /** Every managed database record living on one engine, by id. */
    public static @NonNull List<Row> databasesOn(int engineId) {
        return Models.get(DatabaseModel.class).find()
            .where(DatabaseModel.ENGINE_ID.eq(engineId)).all();
    }

    /**
     * THE shared engine of one kind on one host: the existing row, or a fresh one whose
     * engine INSTANCE ROW is reserved inline (so a host without room refuses on the
     * caller's form) and whose container the first {@link #ensureRunning} brings up.
     *
     * @param requestedImage the image the caller wants, or null/blank for the engine's
     *        default; an existing engine running a DIFFERENT image is a refusal, never a
     *        silent placement onto the wrong major version
     * @throws Violations {@code database_placement_unsupported},
     *         {@code database_image_engine_mismatch}, or any quota / capacity refusal of
     *         the reservation
     */
    public static @NonNull Row findOrCreateShared(int serverId,
                                                  ManagedDatabase.@NonNull Engine engine,
                                                  @Nullable String requestedImage) {
        if (!engine.supportsLogicalDatabases()) {
            throw Violations.ofField(DatabaseModel.PLACEMENT.getName(), DatabaseModel.PLACEMENT_SHARED,
                CmsSupport.violationText("database_placement_unsupported")
                    .withArg("engine", engine.token()));
        }
        Row existing = model().findOnHost(serverId, engine.token());
        if (existing != null) {
            requireImageMatch(existing, requestedImage);
            return existing;
        }
        String image = requestedImage == null || requestedImage.isBlank()
            ? null : requestedImage.trim();
        Row row = model().createEmptyRow();
        row.set(DatabaseEngineModel.NAME, nameFor(serverId, engine));
        row.set(DatabaseEngineModel.ENGINE, engine.token());
        row.set(DatabaseEngineModel.IMAGE, image);
        row.set(DatabaseEngineModel.SERVER_ID, serverId);
        row.set(DatabaseEngineModel.ROOT_USER, rootUserOf(engine));
        row.set(DatabaseEngineModel.ROOT_PASSWORD, Secrets.generatePassword());
        row.set(DatabaseEngineModel.STATUS, DatabaseModel.STATUS_PROVISIONING);
        model().save(row);
        reserveRow(row, ResourceLimits.none());
        Blast.log("DB-ENGINE: created shared", engine.token(), "engine",
            row.get(DatabaseEngineModel.NAME), "on server", ServerModel.nameOf(serverId));
        return row;
    }

    /**
     * Reserve (or re-reserve with new ceilings) the engine's instance row inline.
     *
     * @throws Violations quota, capacity, fence or attribution refusals, unwrapped
     */
    public static int reserveRow(@NonNull Row engineRow, @NonNull ResourceLimits limits) {
        EngineHost host = EngineHost.ofEngine(engineRow);
        EngineHost sized = new EngineHost(host.ownerModel(), host.ownerId(), host.name(),
            host.engine(), host.image(), false, host.rootUser(), host.rootPassword(),
            host.initDatabase(), host.serverId(), limits, true);
        try {
            return DatabaseInstances.reserveEngineRow(sized);
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @throws Violations {@code database_image_engine_mismatch} when a non-blank
     *         requested image differs from what the engine runs
     */
    public static void requireImageMatch(@NonNull Row engineRow, @Nullable String requestedImage) {
        if (requestedImage == null || requestedImage.isBlank()) {
            return;
        }
        String running = EngineHost.ofEngine(engineRow).resolvedImage();
        if (!running.equals(requestedImage.trim())) {
            throw Violations.ofField(DatabaseModel.IMAGE.getName(), requestedImage,
                CmsSupport.violationText("database_image_engine_mismatch")
                    .withArg("image", requestedImage.trim())
                    .withArg("engine", String.valueOf((Object) engineRow.get(DatabaseEngineModel.NAME)))
                    .withArg("running", running));
        }
    }

    /**
     * Bring the engine's container up if it is not serving, and stamp the outcome on
     * the row. Serialized per engine, so concurrent databases provisioning onto a fresh
     * engine deploy it once. Rejoins every attached workload's link network afterwards,
     * because a (re)deploy replaces the container.
     *
     * @throws IOException when the engine cannot be brought up; the row reads failed
     *         with the reason
     */
    public static void ensureRunning(int engineId) throws IOException {
        synchronized (LOCKS.computeIfAbsent(engineId, key -> new Object())) {
            Row row = require(engineId);
            EngineHost host = EngineHost.ofEngine(row);
            ManagedDatabase.LiveStatus live = DatabaseInstances.liveStatus(host);
            if (DatabaseModel.STATUS_ACTIVE.equals(row.get(DatabaseEngineModel.STATUS))
                    && live.running() && !live.workloadDead()) {
                return;
            }
            try {
                DatabaseInstances.deploy(host);
                setStatus(engineId, DatabaseModel.STATUS_ACTIVE, null);
            } catch (IOException e) {
                setStatus(engineId, DatabaseModel.STATUS_FAILED, e.getMessage());
                throw e;
            }
            for (Row database : databasesOn(engineId)) {
                Integer databaseId = database.get(DatabaseModel.ID);
                if (databaseId != null) {
                    InstanceDatabaseNetworks.reattachForDatabase(databaseId);
                }
            }
        }
    }

    /**
     * Recreate the engine container with the row's CURRENT ceilings: the resize lane,
     * run after the row and its reservation were written. Same recreate discipline as a
     * dedicated database's resize; open connections drop until the engine is back.
     */
    public static void redeploy(int engineId) throws IOException {
        synchronized (LOCKS.computeIfAbsent(engineId, key -> new Object())) {
            Row row = require(engineId);
            try {
                DatabaseInstances.deploy(EngineHost.ofEngine(row));
                setStatus(engineId, DatabaseModel.STATUS_ACTIVE, null);
            } catch (IOException e) {
                setStatus(engineId, DatabaseModel.STATUS_FAILED, e.getMessage());
                throw e;
            }
            for (Row database : databasesOn(engineId)) {
                Integer databaseId = database.get(DatabaseModel.ID);
                if (databaseId != null) {
                    InstanceDatabaseNetworks.reattachForDatabase(databaseId);
                }
            }
        }
    }

    /** {@link #ensureRunning} on the provisioning pool, flipping the row's status. */
    public static void provisionInBackground(int engineId) {
        DatabaseService.submit(() -> {
            try {
                ensureRunning(engineId);
            } catch (Exception e) {
                Blast.log("DB-ENGINE: provisioning failed for engine", engineId, "-",
                    e.getMessage());
            }
        });
    }

    /**
     * {@link #redeploy} on the provisioning pool: the resize lane's background half.
     *
     * AIDEV-NOTE: NOT {@link #provisionInBackground}. That one calls {@code ensureRunning},
     * which returns early for an engine that is already active and running -- exactly the
     * engine a resize has to recreate, so a resize submitted through it would book the new
     * ceiling and change nothing on the host.
     */
    public static void redeployInBackground(int engineId) {
        DatabaseService.submit(() -> {
            try {
                redeploy(engineId);
            } catch (Exception e) {
                Blast.log("DB-ENGINE: resize failed for engine", engineId, "-", e.getMessage());
            }
        });
    }

    /**
     * Verified end of life: refused by name while any managed database still lives on
     * the engine, else the instance is destroyed through {@link DatabaseInstances}
     * (verified), the data volume follows when asked, and only then the row goes.
     *
     * @throws IOException when the teardown could not be confirmed; the row is kept
     *         as {@code destroy_failed} with the reason
     */
    public static void destroy(int engineId, boolean removeData) throws IOException {
        Row row = require(engineId);
        List<Row> hosted = databasesOn(engineId);
        if (!hosted.isEmpty()) {
            throw Violations.ofForm(CmsSupport.violationText("database_engine_in_use")
                .withArg("name", String.valueOf((Object) row.get(DatabaseEngineModel.NAME)))
                .withArg("databases", names(hosted)));
        }
        try {
            DatabaseInstances.destroyFor(EngineHost.ofEngine(row), removeData);
        } catch (IOException e) {
            setStatus(engineId, DatabaseModel.STATUS_DESTROY_FAILED, e.getMessage());
            throw new IOException("Destroy of engine '" + row.get(DatabaseEngineModel.NAME)
                + "' could not verify its teardown (record kept): " + e.getMessage(), e);
        }
        model().find().where(DatabaseEngineModel.ID.eq(engineId)).delete();
    }

    /** The force-destroy escape hatch: abandon the instance, delete the row, no daemon. */
    public static void forceDestroy(int engineId) throws IOException {
        Row row = require(engineId);
        List<Row> hosted = databasesOn(engineId);
        if (!hosted.isEmpty()) {
            throw Violations.ofForm(CmsSupport.violationText("database_engine_in_use")
                .withArg("name", String.valueOf((Object) row.get(DatabaseEngineModel.NAME)))
                .withArg("databases", names(hosted)));
        }
        DatabaseInstances.abandonInstance(EngineHost.ofEngine(row));
        model().find().where(DatabaseEngineModel.ID.eq(engineId)).delete();
    }

    /** The names of managed databases, joined for a sentence. */
    public static @NonNull String names(@NonNull List<Row> databases) {
        StringBuilder names = new StringBuilder();
        for (Row database : databases) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(String.valueOf((Object) database.get(DatabaseModel.NAME)));
        }
        return names.toString();
    }

    /** Stamp an outcome; a reason is stored on failure and cleared otherwise. */
    public static void setStatus(int engineId, @NonNull String status, @Nullable String reason) {
        Row row = model().findById(engineId);
        if (row != null) {
            row.set(DatabaseEngineModel.STATUS, status);
            row.set(DatabaseEngineModel.FAILURE_REASON, reason);
            model().save(row);
        }
    }

    /**
     * The auto-created engine's name: {@code <engine>-<host>}, made unique with a numeric
     * suffix if an operator already took that spelling.
     */
    static @NonNull String nameFor(int serverId, ManagedDatabase.@NonNull Engine engine) {
        String base = engine.token() + "-" + ServerModel.nameOf(serverId);
        String candidate = base;
        for (int n = 2; model().findByName(candidate) != null; n++) {
            candidate = base + "-" + n;
        }
        return candidate;
    }

    /**
     * The superuser name an auto-created engine boots with. MySQL's is fixed by the
     * image; the others take the same word so an operator sees one spelling everywhere.
     */
    static @NonNull String rootUserOf(ManagedDatabase.@NonNull Engine engine) {
        return "root";
    }
}
