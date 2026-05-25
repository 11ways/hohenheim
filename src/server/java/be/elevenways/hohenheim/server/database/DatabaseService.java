package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.zenit.common.orm.datasource.Datasource;
import be.elevenways.zenit.common.orm.datasource.Row;

import java.io.IOException;
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

    /** Back up a persisted database by name, using its stored engine and credentials. */
    public String backup(String name) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        return databases.backup(name, engineOf(row), user, password, database);
    }

    /** Restore a dump into a persisted database by name. */
    public void restore(String name, String dump) throws IOException {
        Row row = require(name);
        String user = row.get(DatabaseModel.DB_USER);
        String password = row.get(DatabaseModel.DB_PASSWORD);
        String database = row.get(DatabaseModel.DB_NAME);
        databases.restore(name, engineOf(row), user, password, database, dump);
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
