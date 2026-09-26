package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.orm.DatabaseEngine;
import be.elevenways.zenit.server.orm.DatasourceFactory;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import be.elevenways.zenit.server.setting.ServerSettings;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Database initialization and datasource management. SQLite only: the whole control
 * plane is written against one embedded engine (single-writer serialization is what
 * makes the route-claim registry correct), so {@code init()} refuses to construct
 * anything but a SQLite datasource.
 *
 * AIDEV-NOTE: the datasource is zenit's DEFAULT datasource, resolved by the framework:
 * {@code database.url} (settings/local.dry, or ZENIT__DATABASE__URL) wins, and the
 * hohenheim settings only supply the FALLBACK url ({@link #fallbackUrl}). The hohenheim
 * keys {@code database.path} and {@code database.url} are deprecated app-owned spellings
 * that production installs still set (the installer seeded an absolute database.path until
 * 2026-09-24; a fresh install now gets zenit's database.url in local.dry, and an existing
 * host is never re-pointed), so they stay honoured as that fallback and an upgraded server
 * opens the same file.
 *
 * AIDEV-NOTE: FOREIGN KEYS ARE ON, deliberately. zenit opens every SQLite url with
 * foreign_keys=on unless the url says otherwise (zenit 8a86d3c2, 2026-09-22), and
 * hohenheim does not opt out: the schema declares its references and a delete that would
 * orphan rows is a defect to refuse, not a state to accumulate. The production database
 * ran with enforcement off until then, so orphans may already exist; {@link #init}
 * reports them loudly at boot, the {@code CheckForeignKeys} task keeps reporting them to
 * an operator until they are repaired, and {@code --foreign-key-orphans} is the offline
 * repair. Nothing here ever deletes one on its own.
 */
public class HohenheimDatabase {

    private static SqlDatasource datasource;

    /**
     * Opens the datasource and migrates it; migrations are auto-discovered from the classpath
     * (hohenheim's single InitialMigration plus zenit-auth auth_* and zenit system_task*),
     * never hand-listed.
     */
    public static void init() {
        openDatasource();
        new MigrationRunner(datasource).migrate().requireSuccess();
        reportForeignKeyViolations();
        // Mint/read the namespace token every daemon resource name carries, so it is in
        // the boot log before anything can be deployed under it.
        ControllerIdentity.resolve();
    }

    /**
     * Builds the SQLite datasource and registers it as the framework default, without
     * migrating: the migration-CLI path supplies it to ServerZenitRuntime, which owns
     * the run and closes it afterwards.
     *
     * @return the registered default datasource
     * @throws IllegalStateException when the resolved url is not SQLite; nothing was built
     */
    public static SqlDatasource openDatasource() {
        String fallback = fallbackUrl();
        DatasourceFactory.Resolution resolved = resolution(fallback);
        requireSqlite(DatabaseEngine.forUrl(resolved.url()), resolved.url());
        // Make this the framework's default datasource so model singletons resolve it. Done here
        // (not just at boot) so test classes that re-init with a fresh DB stay in sync. The
        // framework resolves the SAME inputs again, so what it opens is what was checked.
        datasource = ServerZenitRuntime.registerDefaultDatasource(fallback);
        return datasource;
    }

    /**
     * THE resolution of the control-plane database url: zenit's precedence over hohenheim's
     * fallback. The file a restore replaces and the file a rehearsal refuses are read from
     * here too, so they can never name a different database than the one the server opens.
     */
    public static DatasourceFactory.@NonNull Resolution resolution() {
        return resolution(fallbackUrl());
    }

    private static DatasourceFactory.@NonNull Resolution resolution(@NonNull String fallback) {
        ServerZenitRuntime.loadDefaultSettings();
        return DatasourceFactory.resolve(ServerSettings.VALUES, fallback);
    }

    /**
     * The url the framework falls back to when {@code database.url} is not configured:
     * the deprecated hohenheim {@code database.url} when set, else the SQLite file at the
     * hohenheim {@code database.path}.
     */
    static @NonNull String fallbackUrl() {
        String legacyUrl = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.URL);
        if (legacyUrl != null && !legacyUrl.isBlank()) {
            Blast.log("Datasource: hohenheim database.url is deprecated; move it to zenit's"
                + " database.url (settings/local.dry or ZENIT__DATABASE__URL)");
            return legacyUrl.trim();
        }
        String path = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.PATH);
        if (Zenit.SETTINGS_VALUES.hasValue(HohenheimSettings.Database.PATH)) {
            Blast.log("Datasource: hohenheim database.path is deprecated; set zenit's"
                + " database.url to jdbc:sqlite:" + path + " instead");
        }
        return "jdbc:sqlite:" + path;
    }

    public static SqlDatasource datasource() {
        return datasource;
    }

    /**
     * Closes the opened datasource; idempotent, so an offline command may close it EARLY
     * and the dispatcher's own finally block can still close unconditionally.
     *
     * AIDEV-NOTE: the reference is deliberately kept rather than nulled. Every offline
     * command reaches the database through {@link #datasource()} (ControlPlaneBackups does),
     * and the pre-existing shape after a CLI invocation was exactly "closed, still
     * referenced, process about to exit". Nulling it would turn that into an NPE instead
     * of the driver's own "closed" refusal, which says less.
     */
    public static void closeDatasource() {
        SqlDatasource open = datasource;
        if (open != null) {
            open.close();
        }
    }

    /**
     * One row violating a declared foreign key, as SQLite's {@code foreign_key_check}
     * reports it.
     *
     * @param table  the referencing (child) table
     * @param rowId  the child row's rowid, or null for a WITHOUT ROWID table
     * @param parent the referenced table the row points into
     */
    public record ForeignKeyViolation(@NonNull String table, @Nullable Long rowId, @NonNull String parent) {
    }

    /**
     * Every row of the open control-plane database that violates a declared foreign key.
     *
     * @return the violations in the order SQLite reports them; empty for a clean database
     */
    public static @NonNull List<ForeignKeyViolation> foreignKeyViolations() {
        return foreignKeyViolations(datasource);
    }

    /** {@link #foreignKeyViolations()} over an explicit datasource. */
    public static @NonNull List<ForeignKeyViolation> foreignKeyViolations(@NonNull SqlDatasource source) {
        List<ForeignKeyViolation> violations = new ArrayList<>();
        for (Row row : source.rawQuery("PRAGMA foreign_key_check")) {
            Object rowId = row.get("rowid");
            violations.add(new ForeignKeyViolation(String.valueOf(row.get("table")),
                rowId instanceof Number number ? number.longValue() : null,
                String.valueOf(row.get("parent"))));
        }
        return violations;
    }

    /**
     * The per-(table, parent) counts of a violation list, one line each, for a log or an
     * alert.
     */
    public static @NonNull List<String> summarize(@NonNull List<ForeignKeyViolation> violations) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ForeignKeyViolation violation : violations) {
            counts.merge(violation.table() + " -> " + violation.parent(), 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        counts.forEach((pair, count) -> lines.add(pair + ": " + count + " orphaned row(s)"));
        return lines;
    }

    /**
     * Log every foreign-key violation at boot, loudly: an orphaned row makes the next write
     * that touches its table fail now that enforcement is on.
     */
    private static void reportForeignKeyViolations() {
        List<ForeignKeyViolation> violations;
        try {
            violations = foreignKeyViolations();
        } catch (RuntimeException unreadable) {
            Blast.log("DATABASE INTEGRITY: foreign_key_check could not run -", unreadable.getMessage());
            return;
        }
        if (violations.isEmpty()) {
            return;
        }
        Blast.log("DATABASE INTEGRITY:", violations.size(), "row(s) violate a declared foreign key;"
            + " writes touching them will fail. Inspect with --foreign-key-orphans:", summarize(violations));
    }

    /**
     * Refuses any non-SQLite engine before a datasource exists.
     *
     * @throws IllegalStateException when the resolved engine is not SQLite
     */
    // AIDEV-NOTE: This guard is deliberately ABSOLUTE (no settings override). It used to be
    // justified by SQLite-only raw SQL inside the migration chain; that chain is gone (one
    // consolidated migration, portable operations only) but the guard is NOT, because the
    // reason that outlives it is stronger: RouteClaims' overlap refusal is guaranteed by
    // SQLite's single-writer transaction serialization, not by any index -- overlapping
    // (not equal) route claims spell DIFFERENT keys, so only a serialized scan can refuse
    // them. On a concurrent-writer engine two operators could both be handed the same
    // hostname. Every control-plane boot path (ServerMain, tests) funnels through init(),
    // so refusing here cannot be bypassed. Lifting this needs a real cross-writer claim
    // arbiter first, never a config flag.
    private static void requireSqlite(DatabaseEngine engine, String url) {
        if (engine == DatabaseEngine.SQLITE) {
            return;
        }
        throw new IllegalStateException(
            "Hohenheim's own database must be SQLite, but database.url resolved to "
            + engine + " (" + url + "). The control plane depends on SQLite's single-writer "
            + "transaction serialization: RouteClaims refuses OVERLAPPING route claims inside one "
            + "serialized write transaction, and no unique index can express that overlap, so a "
            + "concurrent-writer engine would silently hand two sites the same hostname. "
            + "Point database.url at a jdbc:sqlite: file instead. There is deliberately no override.");
    }
}
