package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.DatabaseDumpModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.DatabaseContentLock;
import be.elevenways.hohenheim.server.database.DatabaseService;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.instance.InstanceOperationLock;
import be.elevenways.hohenheim.server.util.FileTrees;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A filesystem backup target in a temporary directory, named by the {@code database.backup_target} setting while the
 * fixture is open; closing it removes the dumps written to it, the target and the directory, and restores the setting.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class DumpTargetFixture implements AutoCloseable {

    /** The start of every scripted dump's SQL text; the dump's number and {@code ");\n"} follow it. */
    public static final String DUMP_SQL = "CREATE TABLE things (x integer);\nINSERT INTO things VALUES (";

    private final @NonNull Path root;
    private final int targetId;
    private final @NonNull String name;
    private final String previous;

    private DumpTargetFixture(@NonNull Path root, int targetId, @NonNull String name, String previous) {
        this.root = root;
        this.targetId = targetId;
        this.name = name;
        this.previous = previous;
    }

    /** Creates the target and points the dump setting at it. */
    public static @NonNull DumpTargetFixture install(@NonNull String name) throws IOException {
        Path root = Files.createTempDirectory("hohenheim-dump-target");
        Row row = Models.get(BackupTargetModel.class).createEmptyRow();
        row.set(BackupTargetModel.NAME, name);
        row.set(BackupTargetModel.KIND, "hohenheim:filesystem");
        row.set(BackupTargetModel.SETTINGS, new LinkedHashMap<>(Map.of("path", root.toString())));
        Models.get(BackupTargetModel.class).save(row);
        String previous = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.BACKUP_TARGET);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET, name);
        return new DumpTargetFixture(root, row.get(BackupTargetModel.ID), name, previous);
    }

    /** @return the target's directory */
    public @NonNull Path root() {
        return this.root;
    }

    /** @return the target's name */
    public @NonNull String name() {
        return this.name;
    }

    /** @return the committed file of a dump's key on this target */
    public @NonNull Path artifactOf(@NonNull Row dump) {
        return this.root.resolve(String.valueOf((Object) dump.get(DatabaseDumpModel.REMOTE_KEY)));
    }

    /**
     * A bare managed-database record (no engine behind it), for a dump lane whose dump is scripted.
     *
     * @return the saved row
     */
    public static @NonNull Row databaseRow(@NonNull String name, @NonNull String engine) {
        DatabaseModel databases = Models.get(DatabaseModel.class);
        Row row = databases.createEmptyRow();
        row.set(DatabaseModel.NAME, name);
        row.set(DatabaseModel.ENGINE, engine);
        row.set(DatabaseModel.DB_USER, "appuser");
        row.set(DatabaseModel.DB_PASSWORD, "s3cret");
        row.set(DatabaseModel.DB_NAME, "appdb");
        row.set(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE);
        row.set(DatabaseModel.SERVER_ID, ServerModel.localServerId());
        databases.save(row);
        return row;
    }

    /**
     * A dump lane whose engine is a script: every dump is a distinct, recognisable SQL text, and a database whose name
     * starts with {@code broken} fails its dump.
     */
    public static @NonNull Scripted scriptedDumps(@NonNull AtomicInteger counter) {
        return new Scripted(counter);
    }

    /**
     * The scripted engine: dumps and loads refuse to run outside the database's content lock, a load records what it
     * loaded and the permissions it found, and the name prefixes {@code halfway} and {@code stuck} fail a load,
     * {@code unprovisionable} a provision (after its record exists) and {@code stuck} a destroy.
     */
    public static final class Scripted extends DatabaseService {

        private final @NonNull AtomicInteger counter;
        private final List<String> loaded = new ArrayList<>();
        private final List<String> loadPermissions = new ArrayList<>();
        private volatile @Nullable Park park;

        private Scripted(@NonNull AtomicInteger counter) {
            this.counter = counter;
        }

        /** @return each load, as {@code "<database>: <content>"}, in order */
        public @NonNull List<String> loaded() {
            return List.copyOf(this.loaded);
        }

        /** @return each load's file and directory permissions, as {@code "<file> in <directory>"} */
        public @NonNull List<String> loadPermissions() {
            return List.copyOf(this.loadPermissions);
        }

        /**
         * Holds every following dump inside its content lock: it signals {@code reached}, then waits for
         * {@code release} (at most 30 seconds) before it writes.
         */
        public void parkDumps(@NonNull CountDownLatch reached, @NonNull CountDownLatch release) {
            this.park = new Park(reached, release);
        }

        @Override
        public Path backupToFile(String name, Path directory, String baseName,
                                 InstanceOperationLock.@NonNull Contention contention) throws IOException {
            requireLocked(name);
            Park parked = this.park;
            if (parked != null) {
                parked.reached().countDown();
                try {
                    parked.release().await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("the parked dump was interrupted", interrupted);
                }
            }
            if (name.startsWith("broken")) {
                throw new IOException("pg_dump exited 1");
            }
            Files.createDirectories(directory);
            Path dump = directory.resolve(baseName + ".sql");
            Files.writeString(dump, DUMP_SQL + this.counter.incrementAndGet() + ");\n");
            return dump;
        }

        @Override
        public void restoreFromFile(String name, Path source, InstanceOperationLock.@NonNull Contention contention)
                throws IOException {
            requireLocked(name);
            this.loadPermissions.add(PosixFilePermissions.toString(Files.getPosixFilePermissions(source)) + " in "
                + PosixFilePermissions.toString(Files.getPosixFilePermissions(source.getParent())));
            if (name.startsWith("halfway") || name.startsWith("stuck")) {
                throw new IOException("psql exited 3: ERROR: relation \"things\" already exists");
            }
            this.loaded.add(name + ": " + Files.readString(source));
        }

        @Override
        public ManagedDatabase.Connection provisionInserted(@NonNull Row created) throws IOException {
            if (String.valueOf((Object) created.get(DatabaseModel.NAME)).startsWith("unprovisionable")) {
                throw new IOException("provision failed: no engine could start");
            }
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(created.get(DatabaseModel.ID)))
                .assign(DatabaseModel.STATUS, DatabaseModel.STATUS_ACTIVE).updateAll();
            return null;
        }

        @Override
        public void destroyById(int recordId, boolean removeData) throws IOException {
            Row database = Models.get(DatabaseModel.class).findById(recordId);
            if (database != null && String.valueOf((Object) database.get(DatabaseModel.NAME)).startsWith("stuck")) {
                throw new IOException("the daemon did not answer");
            }
            Models.get(DatabaseModel.class).find().where(DatabaseModel.ID.eq(recordId)).delete();
        }

        private static void requireLocked(String name) throws IOException {
            Row database = Models.get(DatabaseModel.class).findByName(name);
            if (database == null || !DatabaseContentLock.isHeldHere(database.get(DatabaseModel.ID))) {
                throw new IOException("a dump or restore of " + name + " ran outside its content lock");
            }
        }

        /** A parked dump's two latches. */
        private record Park(@NonNull CountDownLatch reached, @NonNull CountDownLatch release) {
        }
    }

    @Override
    public void close() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.BACKUP_TARGET,
            this.previous == null ? "" : this.previous);
        Models.get(DatabaseDumpModel.class).find().where(DatabaseDumpModel.TARGET_ID.eq(this.targetId)).delete();
        Models.get(BackupTargetModel.class).find().where(BackupTargetModel.ID.eq(this.targetId)).delete();
        FileTrees.deleteQuietly(this.root);
    }
}
