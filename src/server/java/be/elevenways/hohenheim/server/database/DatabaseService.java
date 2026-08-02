package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.util.DatasourceScoped;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Lifecycle entry point for managed databases: ties the persisted {@link DatabaseModel} record
 * (desired config) to the container operations in {@link ManagedDatabase} (live data). Backup
 * and restore resolve the engine and credentials from the record, so callers pass only a name.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DatabaseService extends DatasourceScoped {

    // The status vocabulary is owned by the model's EnumField declaration.
    public static final String STATUS_PROVISIONING = DatabaseModel.STATUS_PROVISIONING;
    public static final String STATUS_ACTIVE = DatabaseModel.STATUS_ACTIVE;
    public static final String STATUS_FAILED = DatabaseModel.STATUS_FAILED;

    // Background pool for provisioning (image pull + container start can take tens of seconds);
    // shared because handlers construct DatabaseService per request. Bounded to limit load.
    private static final ExecutorService PROVISION_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "db-provision");
        thread.setDaemon(true);
        return thread;
    });

    // Resolves the ManagedDatabase for a database's target server (server name -> client). In
    // production this routes through ServerService (local socket or remote SSH); tests inject a
    // single fixed client.
    private final Function<String, ManagedDatabase> managedFor;

    public DatabaseService() {
        super(null);
        ServerService servers = new ServerService();
        this.managedFor = serverName -> new ManagedDatabase(servers.clientFor(serverName));
    }

    public DatabaseService(DockerClient docker, Datasource datasource) {
        super(datasource);
        ManagedDatabase fixed = new ManagedDatabase(docker);
        this.managedFor = serverName -> fixed;   // tests: a single host regardless of server name
    }

    private static DatabaseModel model() {
        return Models.get(DatabaseModel.class);
    }

    /** Provision synchronously on the local host; see the server-aware overload. */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral) throws IOException {
        return create(name, engine, image, user, password, database, ephemeral, ServerService.LOCAL);
    }

    /**
     * Provision a database synchronously on {@code serverName} and persist its record as active.
     * Blocking -- intended for tests and scripted use; request handlers should use {@link #createAsync}.
     */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral, String serverName) throws IOException {
        return create(name, engine, image, user, password, database, ephemeral, serverName,
            ResourceLimits.none());
    }

    /**
     * Synchronous create with optional container resource caps. The record is persisted
     * as "provisioning" BEFORE the container exists (matching the async path), so the
     * container and volume can be born carrying the record's owner labels; a failed
     * provision leaves a "failed" record rather than nothing.
     */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral, String serverName,
                                             ResourceLimits limits) throws IOException {
        Integer recordId = upsertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, STATUS_PROVISIONING);
        try {
            ManagedDatabase.Connection connection = managedFor.apply(serverName)
                .provision(name, engine, image, user, password, database, ephemeral, limits, recordId);
            setStatus(name, STATUS_ACTIVE);
            return connection;
        } catch (IOException e) {
            setStatus(name, STATUS_FAILED);
            throw e;
        }
    }

    /** Provision asynchronously on the local host; see the server-aware overload. */
    public void createAsync(String name, ManagedDatabase.Engine engine, String image,
                            String user, String password, String database, boolean ephemeral) {
        createAsync(name, engine, image, user, password, database, ephemeral, ServerService.LOCAL);
    }

    /**
     * Persist the record immediately as "provisioning" and provision on {@code serverName} in the
     * background, flipping the status to active or failed when done -- so a slow image pull (or a
     * remote SSH round-trip) doesn't block the request.
     */
    public void createAsync(String name, ManagedDatabase.Engine engine, String image,
                            String user, String password, String database, boolean ephemeral,
                            String serverName) {
        createAsync(name, engine, image, user, password, database, ephemeral, serverName,
            ResourceLimits.none());
    }

    /** Async create with optional container resource caps. */
    public void createAsync(String name, ManagedDatabase.Engine engine, String image,
                            String user, String password, String database, boolean ephemeral,
                            String serverName, ResourceLimits limits) {
        Integer recordId = upsertRecord(name, engine, image, user, password, database, ephemeral,
            serverName, limits, STATUS_PROVISIONING);
        PROVISION_EXECUTOR.submit(() -> {
            try {
                managedFor.apply(serverName)
                    .provision(name, engine, image, user, password, database, ephemeral, limits,
                        recordId);
                setStatus(name, STATUS_ACTIVE);
            } catch (Exception e) {
                setStatus(name, STATUS_FAILED);
                Blast.log("DB: provisioning failed for", name, "-", e.getMessage());
            }
        });
    }

    /** @return the persisted record's id, so provisioning can label its resources */
    private Integer upsertRecord(String name, ManagedDatabase.Engine engine, String image, String user,
                                 String password, String database, boolean ephemeral, String serverName,
                                 ResourceLimits limits, String status) {
        return query(() -> {
            DatabaseModel model = model();
            Row row = model.findByName(name);
            if (row == null) {
                row = model.createEmptyRow();
                row.set(DatabaseModel.NAME, name);
            }
            row.set(DatabaseModel.ENGINE, engine.name().toLowerCase(Locale.ROOT));
            row.set(DatabaseModel.IMAGE, image);
            row.set(DatabaseModel.DB_USER, user);
            row.set(DatabaseModel.DB_PASSWORD, password);
            row.set(DatabaseModel.DB_NAME, database);
            row.set(DatabaseModel.EPHEMERAL, ephemeral);
            row.set(DatabaseModel.MEMORY_LIMIT_MB, limits.memoryMb());
            row.set(DatabaseModel.CPU_LIMIT, limits.cpus());
            row.set(DatabaseModel.STATUS, status);
            row.set(DatabaseModel.SERVER_NAME, serverName);
            model.save(row);
            return row.get(DatabaseModel.ID);
        });
    }

    private void setStatus(String name, String status) {
        exec(() -> {
            DatabaseModel model = model();
            Row row = model.findByName(name);
            if (row != null) {
                row.set(DatabaseModel.STATUS, status);
                model.save(row);
            }
        });
    }

    /** All persisted database records. */
    public List<Row> list() {
        return query(() -> model().find().all());
    }

    /** A persisted database plus its best-effort live container status, for the admin list.
     *  {@code status} is the provisioning lifecycle (provisioning/active/failed); {@code running}
     *  + {@code port} are the live container state (meaningful once active). */
    public record Summary(String name, String engine, String image, String database, String user,
                          boolean ephemeral, String server, String status, boolean running, Integer port) {}

    /** Full detail for one database, including the password (admin detail page only). */
    public record Detail(String name, String engine, String image, String database, String user,
                         String password, boolean ephemeral, String server, String status,
                         boolean running, Integer port) {}

    /** Full detail for one database by name with live status, or null if there is no such record. */
    public Detail detail(String name) {
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            return null;
        }
        return detailOf(row);
    }

    /** Full detail for an already-loaded database row, including live container status. */
    public Detail detailOf(Row row) {
        ManagedDatabase.Engine engine = engineOf(row);
        ManagedDatabase.LiveStatus live = liveStatus(row, engine);
        String image = row.get(DatabaseModel.IMAGE);
        return new Detail(
            row.get(DatabaseModel.NAME),
            engine.name().toLowerCase(Locale.ROOT),
            image != null ? image : "",
            row.get(DatabaseModel.DB_NAME),
            row.get(DatabaseModel.DB_USER),
            row.get(DatabaseModel.DB_PASSWORD),
            Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL)),
            serverOf(row),
            statusOf(row),
            live.running(),
            live.port());
    }

    /** All databases with live status (running + published port), best-effort per record. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Row row : query(() -> model().find().all())) {
            ManagedDatabase.Engine engine = engineOf(row);
            ManagedDatabase.LiveStatus live = liveStatus(row, engine);
            String image = row.get(DatabaseModel.IMAGE);
            result.add(new Summary(
                row.get(DatabaseModel.NAME),
                engine.name().toLowerCase(Locale.ROOT),
                image != null ? image : "",
                row.get(DatabaseModel.DB_NAME),
                row.get(DatabaseModel.DB_USER),
                Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL)),
                serverOf(row),
                statusOf(row),
                live.running(),
                live.port()
            ));
        }
        return result;
    }

    // Best-effort live status on the record's target host; never throws (an unreachable host -> down).
    private ManagedDatabase.LiveStatus liveStatus(Row row, ManagedDatabase.Engine engine) {
        try {
            return managedFor.apply(serverOf(row)).status(row.get(DatabaseModel.NAME), engine);
        } catch (Exception e) {
            return new ManagedDatabase.LiveStatus(false, null);
        }
    }

    private static String statusOf(Row row) {
        String status = row.get(DatabaseModel.STATUS);
        return status != null ? status : STATUS_ACTIVE;   // records predating the status column
    }

    private static String serverOf(Row row) {
        String server = row.get(DatabaseModel.SERVER_NAME);
        return server != null ? server : ServerService.LOCAL;   // records predating the server column
    }

    /**
     * Back up a persisted database into {@code directory}, naming the file {@code baseName} plus
     * the engine's dump extension, and return the written path. Handles text (SQL) and binary
     * (RDB / mongodump archive) engines.
     */
    public Path backupToFile(String name, Path directory, String baseName) throws IOException {
        Row row = require(name);
        ManagedDatabase.Engine engine = engineOf(row);
        Files.createDirectories(directory);
        Path target = directory.resolve(baseName + "." + engine.dumpExtension());
        managedFor.apply(serverOf(row)).backupToFile(name, engine,
            row.get(DatabaseModel.DB_USER), row.get(DatabaseModel.DB_PASSWORD),
            row.get(DatabaseModel.DB_NAME), target);
        return target;
    }

    /** A dump ready to stream to the browser: filename, MIME type, and the dump bytes. */
    public record BackupDownload(String filename, String contentType, byte[] content) {}

    /**
     * Back up a persisted database by name into a downloadable artifact (SQL text or the engine's
     * native binary dump). The whole dump is held in memory; streaming is a follow-up for large
     * databases.
     */
    public BackupDownload backupDownload(String name) throws IOException {
        Row row = require(name);
        ManagedDatabase.Engine engine = engineOf(row);
        Path directory = Files.createTempDirectory("hohenheim-backup");
        Path dump = directory.resolve(name + "." + engine.dumpExtension());
        try {
            managedFor.apply(serverOf(row)).backupToFile(name, engine,
                row.get(DatabaseModel.DB_USER), row.get(DatabaseModel.DB_PASSWORD),
                row.get(DatabaseModel.DB_NAME), dump);
            return new BackupDownload(dump.getFileName().toString(), engine.dumpContentType(),
                Files.readAllBytes(dump));
        } finally {
            Files.deleteIfExists(dump);
            Files.deleteIfExists(directory);
        }
    }

    /** Restore a dump file (text or binary) into a persisted database by name. */
    public void restoreFromFile(String name, Path source) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        managedFor.apply(serverOf(row)).restoreFromFile(name, engineOf(row), user, password, database, source);
    }

    /** Stop + remove the container (optionally its data volume) and delete the record. */
    public void destroy(String name, boolean removeData) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row != null) {
            managedFor.apply(serverOf(row)).destroy(name, removeData);
        }
        exec(() -> model().find().where(DatabaseModel.NAME.eq(name)).delete());
    }

    private Row require(String name) throws IOException {
        Row row = query(() -> model().findByName(name));
        if (row == null) {
            throw new IOException("No managed database named '" + name + "'");
        }
        return row;
    }

    private static ManagedDatabase.Engine engineOf(Row row) {
        String engine = row.get(DatabaseModel.ENGINE);
        return ManagedDatabase.Engine.valueOf(engine.toUpperCase(Locale.ROOT));
    }
}
