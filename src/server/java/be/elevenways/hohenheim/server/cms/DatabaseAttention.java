package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

/**
 * The DATABASES role's attention items: each database that does not serve (its {@link DatabaseVerdict}), records a
 * failed operation rolled back, failed engines, an old engine a move left behind, and the apps a database holds back,
 * caused by it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class DatabaseAttention {

    private DatabaseAttention() {
    }

    /**
     * Every database that does not serve as one root item titled by its verdict ("Database shop is not running"),
     * naming the apps it holds back ("2 apps use it"); then active records a failed operation rolled back, and failed
     * engines.
     *
     * AIDEV-NOTE: the database is the root. The dashboard once showed one stopped shared database as an item per app
     * using it ("Instance Shop: Attached database shop is not running"); those per-app items now name the database as
     * their cause ({@link #unavailableAttachedDatabases}) and fold under this one wherever it is shown.
     */
    static void failedDatabases(List<AttentionItem> items) {
        Map<Integer, List<Row>> usedBy = InstanceDatabaseLinks.liveInstancesByDatabase();
        Map<Integer, Leftover> leftovers = leftovers();
        // Every database judged together: the serving instances and their hosts are read once, never once per row.
        List<Row> databases = Models.get(DatabaseModel.class).find().all();
        Function<Row, DatabaseVerdict> verdicts = DatabaseVerdict.ofDatabases(databases);
        for (Row row : databases) {
            DatabaseVerdict verdict = verdicts.apply(row);
            Microcopy title = verdict.state().attentionTitle(row.get(DatabaseModel.NAME));
            AttentionSeverity severity = verdict.state().severity();
            Integer id = row.get(DatabaseModel.ID);
            if (title == null || severity == null || id == null) {
                continue;
            }
            int apps = usedBy.getOrDefault(id, List.of()).size();
            Leftover leftover = leftovers.get(id);
            items.add(item(severity, "database", title, verdict.reason(),
                CmsRoutes.open(ADMIN, HohenheimSlugs.DATABASES, id),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_database"))
                .about(AttentionSubject.database(id),
                    apps == 0 ? null : HohenheimMicrocopy.ATTENTION_DETAIL.of("apps_use_it").withArg("count", apps))
                .withNote(leftover == null ? null : leftoverLine(leftover.instance())));
        }
        // An ACTIVE record carrying a reason is the one shape a status alone cannot show:
        // a failed move rolled the record back onto its untouched dedicated engine and
        // stamped WHY there, so without this the operator learns nothing happened only by
        // opening the record.
        for (Row row : Models.get(DatabaseModel.class).find()
                .where(DatabaseModel.STATUS.eq(DatabaseModel.STATUS_ACTIVE))
                .where(DatabaseModel.FAILURE_REASON.isNotNull())
                .all()) {
            String reason = row.get(DatabaseModel.FAILURE_REASON);
            if (reason == null || reason.isBlank()) {
                continue;
            }
            items.add(item(AttentionSeverity.WARNING, "database",
                HohenheimMicrocopy.ATTENTION_TITLE.of("database").withArg("name", row.get(DatabaseModel.NAME)),
                HohenheimMicrocopy.ATTENTION_DETAIL.of("database_operation_failed").withArg("reason", reason),
                CmsRoutes.detail(ADMIN, HohenheimSlugs.DATABASES, row.get(DatabaseModel.ID)),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_database")));
        }
        failedDatabaseEngines(items);
    }

    /**
     * A database moved to a shared engine whose old dedicated engine could not be removed (DATABASE_MOVE_LEFTOVER):
     * that instance still runs, holding its port, its memory booking and a copy of the data, while nothing uses it.
     * One item per leftover, in the alert's words, for as long as the instance lives.
     *
     * AIDEV-NOTE: the leftover is found from the record, never from the alert: an engine instance generated for a
     * database whose placement is no longer dedicated serves nothing ({@code EngineHost.serving} reads the engine).
     *
     * AIDEV-NOTE: one item per database. While the database itself does not serve, its own item ({@link
     * #failedDatabases}) is the root and says the leftover as its note, so this item is not drawn beside it. Either way
     * the leftover engine's own stoppage folds under the database ({@link WorkloadErrors#rootOf}): it serves nothing,
     * and removing it is the fix, never restarting it.
     */
    static void moveLeftovers(List<AttentionItem> items) {
        List<Leftover> leftovers = new ArrayList<>(leftovers().values());
        Function<Row, DatabaseVerdict> verdicts = DatabaseVerdict.ofDatabases(
            leftovers.stream().map(Leftover::database).toList());
        for (Leftover leftover : leftovers) {
            Row database = leftover.database();
            Integer databaseId = database.get(DatabaseModel.ID);
            if (verdicts.apply(database).state().attentionTitle(database.get(DatabaseModel.NAME)) != null) {
                continue;
            }
            items.add(item(AttentionSeverity.WARNING, "database",
                HohenheimMicrocopy.ALERT.of("database_move_leftover_subject")
                    .withArg("name", database.get(DatabaseModel.NAME)),
                leftoverLine(leftover.instance()), CmsRoutes.open(ADMIN, HohenheimSlugs.DATABASES, databaseId),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_database"))
                .about(AttentionSubject.database(databaseId), null));
        }
    }

    /**
     * An engine instance a move left behind and the database it was generated for.
     *
     * @param database the database, now shared
     * @param instance its old dedicated engine
     */
    private record Leftover(@NonNull Row database, @NonNull Row instance) {
    }

    /**
     * @return every engine instance a move left behind, keyed by the database it was generated for; those databases
     *         are read once
     */
    private static @NonNull Map<Integer, Leftover> leftovers() {
        List<Row> instances = Models.get(InstanceModel.class).find()
            .where(InstanceModel.GENERATED_FOR_MODEL.eq(DatabaseModel.MODEL_ID.toString()))
            .all();
        List<Integer> ids = new ArrayList<>();
        for (Row instance : instances) {
            ids.add(instance.get(InstanceModel.GENERATED_FOR_ID));
        }
        Map<Integer, List<Row>> databases = KeyedRows.byAsked(Models.get(DatabaseModel.class).find(),
            DatabaseModel.ID, ids);
        Map<Integer, Leftover> found = new LinkedHashMap<>();
        for (Row instance : instances) {
            Integer databaseId = instance.get(InstanceModel.GENERATED_FOR_ID);
            List<Row> rows = databaseId == null ? null : databases.get(databaseId);
            Row database = rows == null ? null : rows.get(0);
            if (database != null && DatabaseModel.isShared(database)) {
                found.putIfAbsent(database.get(DatabaseModel.ID), new Leftover(database, instance));
            }
        }
        return found;
    }

    /**
     * @return the database this instance is the old engine of, left behind by a move to a shared engine; null when it
     *         is no such leftover (any other instance, or the engine a dedicated database runs on)
     */
    static @Nullable Row leftoverOf(@NonNull Row instance) {
        if (!DatabaseModel.MODEL_ID.toString().equals(instance.get(InstanceModel.GENERATED_FOR_MODEL))) {
            return null;
        }
        Integer databaseId = instance.get(InstanceModel.GENERATED_FOR_ID);
        Row database = Models.get(DatabaseModel.class).findById(databaseId);
        return database != null && DatabaseModel.isShared(database) ? database : null;
    }

    /** @return the leftover in the alert's words: which old engine, on which host, still holding what */
    private static @NonNull Microcopy leftoverLine(@NonNull Row instance) {
        return HohenheimMicrocopy.ATTENTION_DETAIL.of("database_move_leftover")
            .withArg("engine", instance.get(InstanceModel.NAME))
            .withArg("host", ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID)));
    }

    /**
     * A database a workload uses that does not serve, with the verdict that says so.
     *
     * @param database the database record
     * @param verdict  its verdict, which does not serve
     */
    record NotServing(@NonNull Row database, @NonNull DatabaseVerdict verdict) {
    }

    /**
     * @return the first database this workload uses that does not serve, with its {@link DatabaseVerdict}; null when
     *         every one serves or it uses none
     */
    static @Nullable NotServing firstNotServing(int instanceId) {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        if (links == null) {
            return null;
        }
        List<Integer> ids = new ArrayList<>();
        for (Row link : links.findByInstanceId(instanceId)) {
            ids.add(link.get(InstanceDatabaseModel.DATABASE_ID));
        }
        Map<Integer, List<Row>> byId = KeyedRows.byAsked(Models.get(DatabaseModel.class).find(), DatabaseModel.ID,
            ids);
        List<Row> databases = new ArrayList<>();
        for (Integer id : ids) {
            List<Row> found = byId.get(id);
            if (found != null) {
                databases.add(found.get(0));
            }
        }
        Function<Row, DatabaseVerdict> verdicts = DatabaseVerdict.ofDatabases(databases);
        for (Row database : databases) {
            DatabaseVerdict verdict = verdicts.apply(database);
            if (!verdict.serves()) {
                return new NotServing(database, verdict);
            }
        }
        return null;
    }

    /** A shared engine that could not be brought up serves every database on it nothing. */
    private static void failedDatabaseEngines(List<AttentionItem> items) {
        for (Row row : Models.get(DatabaseEngineModel.class).find()
                .where(DatabaseEngineModel.STATUS.eq(DatabaseModel.STATUS_FAILED))
                .all()) {
            String reason = row.get(DatabaseEngineModel.FAILURE_REASON);
            items.add(item(AttentionSeverity.ERROR, "server",
                HohenheimMicrocopy.ATTENTION_TITLE.of("database_engine")
                    .withArg("name", row.get(DatabaseEngineModel.NAME)),
                reason == null || reason.isBlank()
                    ? HohenheimMicrocopy.ATTENTION_DETAIL.of("provisioning_failed")
                    : HohenheimMicrocopy.ATTENTION_DETAIL.of("engine_provisioning_failed_reason")
                        .withArg("reason", reason),
                CmsRoutes.detail(ADMIN, HohenheimSlugs.DATABASE_ENGINES,
                    row.get(DatabaseEngineModel.ID)),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_engine")));
        }
    }

    /**
     * Instances whose ATTACHED database does not serve, each caused by that database: the dashboard and the Databases
     * band fold them under the database's own item ({@link #failedDatabases}), and only where that root is not shown
     * (a database still being set up raises none) does an app's item stand.
     *
     * AIDEV-NOTE: STORED state only, through {@link DatabaseVerdict}: this used to ask the daemon, once per attachment
     * per dashboard render ({@code DatabaseService.detailOf} ends in {@code InstanceService.liveStatus}, an SSH or
     * HTTPS round trip for a remote host) -- the per-render probe the attention surface forbids. The OOM-killed
     * engine inside a still-running container is stored by the status sweep as {@code instances.workload_killed_at},
     * so it keeps its own words without a daemon call.
     */
    public static void unavailableAttachedDatabases(List<AttentionItem> items) {
        var linkModel = Models.get(InstanceDatabaseModel.class);
        if (linkModel == null) {
            return;
        }
        List<Row> links = linkModel.find().all();
        if (links.isEmpty()) {
            return;
        }
        // The apps, the databases and the databases' verdicts are each read once for every link.
        List<Integer> instanceIds = new ArrayList<>();
        List<Integer> databaseIds = new ArrayList<>();
        for (Row link : links) {
            instanceIds.add(link.get(InstanceDatabaseModel.INSTANCE_ID));
            databaseIds.add(link.get(InstanceDatabaseModel.DATABASE_ID));
        }
        Map<Integer, List<Row>> instances = KeyedRows.byAsked(Models.get(InstanceModel.class).find(),
            InstanceModel.ID, instanceIds);
        Map<Integer, List<Row>> byId = KeyedRows.byAsked(Models.get(DatabaseModel.class).find(), DatabaseModel.ID,
            databaseIds);
        List<Row> databases = new ArrayList<>();
        for (List<Row> found : byId.values()) {
            databases.add(found.get(0));
        }
        Function<Row, DatabaseVerdict> verdicts = DatabaseVerdict.ofDatabases(databases);
        for (Row link : links) {
            Integer instanceId = link.get(InstanceDatabaseModel.INSTANCE_ID);
            List<Row> app = instanceId == null ? null : instances.get(instanceId);
            if (app == null) {
                continue;
            }
            Row instance = app.get(0);
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            List<Row> found = databaseId == null ? null : byId.get(databaseId);
            if (found == null) {
                continue;   // dangling link; the tab shows it as (deleted)
            }
            Row database = found.get(0);
            DatabaseVerdict verdict = verdicts.apply(database);
            if (verdict.serves()) {
                continue;
            }
            items.add(item(AttentionSeverity.WARNING, "database",
                HohenheimMicrocopy.ATTENTION_TITLE.of("app_database_down")
                    .withArg("name", instance.get(InstanceModel.NAME))
                    .withArg("database", database.get(DatabaseModel.NAME)),
                verdict.reason() != null ? verdict.reason() : verdict.state().label(),
                InstanceParts.recordRoute(ADMIN, instance, HohenheimSlugs.Tab.DATABASES),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_databases"))
                .causedBy(AttentionSubject.database(databaseId)));
        }
    }
}
