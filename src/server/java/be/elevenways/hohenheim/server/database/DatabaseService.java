package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle entry point for managed databases: ties the persisted {@link DatabaseModel} record
 * (desired config) to the container operations in {@link ManagedDatabase} (live data). Backup
 * and restore resolve the engine and credentials from the record, so callers pass only a name.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public class DatabaseService {

    private final ManagedDatabase databases;
    private final DatabaseModel model;

    public DatabaseService() {
        this(new DockerClient(), HohenheimDatabase.datasource());
    }

    public DatabaseService(DockerClient docker, Datasource datasource) {
        this.databases = new ManagedDatabase(docker);
        this.model = new DatabaseModel(datasource);
    }

    /**
     * Provision (or re-provision) a database container and persist its record. Provisioning runs
     * first so a failed provision leaves no orphan record; an existing record is updated in place.
     */
    public ManagedDatabase.Connection create(String name, ManagedDatabase.Engine engine, String image,
                                             String user, String password, String database,
                                             boolean ephemeral) throws IOException {
        ManagedDatabase.Connection connection =
            databases.provision(name, engine, image, user, password, database, ephemeral);

        Row row = model.findByName(name);
        if (row == null) {
            row = model.createEmptyRow();
            row.set(DatabaseModel.NAME, name);
        }
        row.set(DatabaseModel.ENGINE, engine.name().toLowerCase());
        row.set(DatabaseModel.IMAGE, image);
        row.set(DatabaseModel.DB_USER, user);
        row.set(DatabaseModel.DB_PASSWORD, password);
        row.set(DatabaseModel.DB_NAME, database);
        row.set(DatabaseModel.EPHEMERAL, ephemeral);
        model.save(row);

        return connection;
    }

    /** All persisted database records. */
    public List<Row> list() {
        return model.find().all();
    }

    /** A persisted database plus its best-effort live container status, for the admin list. */
    public record Summary(String name, String engine, String image, String database, String user,
                          boolean ephemeral, boolean running, Integer port) {}

    /** Full detail for one database, including the password (admin detail page only). */
    public record Detail(String name, String engine, String image, String database, String user,
                         String password, boolean ephemeral, boolean running, Integer port) {}

    /** Full detail for one database by name with live status, or null if there is no such record. */
    public Detail detail(String name) {
        Row row = model.findByName(name);
        if (row == null) {
            return null;
        }
        ManagedDatabase.Engine engine = engineOf(row);
        ManagedDatabase.LiveStatus status = databases.status(name, engine);
        String image = row.get(DatabaseModel.IMAGE);
        return new Detail(
            row.get(DatabaseModel.NAME),
            engine.name().toLowerCase(),
            image != null ? image : "",
            row.get(DatabaseModel.DB_NAME),
            row.get(DatabaseModel.DB_USER),
            row.get(DatabaseModel.DB_PASSWORD),
            Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL)),
            status.running(),
            status.port());
    }

    /** All databases with live status (running + published port), best-effort per record. */
    public List<Summary> summaries() {
        List<Summary> result = new ArrayList<>();
        for (Row row : model.find().all()) {
            ManagedDatabase.Engine engine = engineOf(row);
            ManagedDatabase.LiveStatus status = databases.status(row.get(DatabaseModel.NAME), engine);
            String image = row.get(DatabaseModel.IMAGE);
            result.add(new Summary(
                row.get(DatabaseModel.NAME),
                engine.name().toLowerCase(),
                image != null ? image : "",
                row.get(DatabaseModel.DB_NAME),
                row.get(DatabaseModel.DB_USER),
                Boolean.TRUE.equals(row.get(DatabaseModel.EPHEMERAL)),
                status.running(),
                status.port()
            ));
        }
        return result;
    }

    /** Back up a persisted database by name, using its stored engine and credentials. */
    public String backup(String name) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        return databases.backup(name, engineOf(row), user, password, database);
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
        databases.backupToFile(name, engine,
            row.get(DatabaseModel.DB_USER), row.get(DatabaseModel.DB_PASSWORD),
            row.get(DatabaseModel.DB_NAME), target);
        return target;
    }

    /** Restore a dump into a persisted database by name. */
    public void restore(String name, String dump) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        databases.restore(name, engineOf(row), user, password, database, dump);
    }

    /** Restore a dump file (text or binary) into a persisted database by name. */
    public void restoreFromFile(String name, Path source) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        databases.restoreFromFile(name, engineOf(row), user, password, database, source);
    }

    /** Stop + remove the container (optionally its data volume) and delete the record. */
    public void destroy(String name, boolean removeData) throws IOException {
        databases.destroy(name, removeData);
        model.find().where(DatabaseModel.NAME.eq(name)).delete();
    }

    private Row require(String name) throws IOException {
        Row row = model.findByName(name);
        if (row == null) {
            throw new IOException("No managed database named '" + name + "'");
        }
        return row;
    }

    private static ManagedDatabase.Engine engineOf(Row row) {
        String engine = row.get(DatabaseModel.ENGINE);
        return ManagedDatabase.Engine.valueOf(engine.toUpperCase());
    }
}
