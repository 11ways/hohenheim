package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.notification.Alerts;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static be.elevenways.hohenheim.server.cms.AttentionItems.action;
import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;

/**
 * The DATABASES role's attention items: each database that does not serve (its {@link DatabaseVerdict}), records a
 * failed operation rolled back, failed engines, an old engine a move left behind, and the apps a database holds back,
 * caused by it.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class DatabaseAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    private DatabaseAttention() {
    }

    /**
     * Every database that does not serve as one root item titled by its verdict ("Database shop is not running"),
     * naming the apps it holds back ("2 apps use it"); then active records a failed operation rolled back, and failed
     * engines.
     *
     * AIDEV-NOTE: the database is the root. D10b's dashboard showed one stopped shared database as an item per app
     * using it ("Instance Shop: Attached database shop is not running"); those per-app items now name the database as
     * their cause ({@link #unavailableAttachedDatabases}) and fold under this one wherever it is shown.
     */
    static void failedDatabases(List<AttentionItem> items) {
        Map<Integer, List<Row>> usedBy = InstanceDatabaseLinks.liveInstancesByDatabase();
        for (Row row : Models.get(DatabaseModel.class).find().all()) {
            DatabaseVerdict verdict = DatabaseVerdict.ofDatabase(row);
            Microcopy title = verdict.state().attentionTitle(row.get(DatabaseModel.NAME));
            Integer id = row.get(DatabaseModel.ID);
            if (title == null || id == null) {
                continue;
            }
            int apps = usedBy.getOrDefault(id, List.of()).size();
            items.add(item(verdict.state().severity(), "database", title, verdict.reason(),
                CmsRoutes.open(ADMIN, DatabaseParts.SLUG, id), action("act_open_database"))
                .about(AttentionSubject.database(id),
                    apps == 0 ? null : copy("apps_use_it", "attention_detail", "count", apps)));
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
                copy("database", "attention_title", "name", row.get(DatabaseModel.NAME)),
                copy("database_operation_failed", "attention_detail", "reason", reason),
                CmsRoutes.detail(ADMIN, "databases", row.get(DatabaseModel.ID)),
                action("act_open_database")));
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
     */
    static void moveLeftovers(List<AttentionItem> items) {
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.GENERATED_FOR_MODEL.eq(DatabaseModel.MODEL_ID.toString()))
                .all()) {
            Integer databaseId = instance.get(InstanceModel.GENERATED_FOR_ID);
            Row database = databaseId == null ? null : Models.get(DatabaseModel.class).findById(databaseId);
            if (database == null || !DatabaseModel.PLACEMENT_SHARED.equals(database.get(DatabaseModel.PLACEMENT))) {
                continue;
            }
            Object name = database.get(DatabaseModel.NAME);
            items.add(item(AttentionSeverity.WARNING, "database",
                Alerts.copy("database_move_leftover_subject").withArg("name", name),
                copy("database_move_leftover", "attention_detail", "engine", instance.get(InstanceModel.NAME),
                    "host", ServerModel.nameOf(ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)))),
                CmsRoutes.open(ADMIN, DatabaseParts.SLUG, databaseId), action("act_open_database")));
        }
    }

    /** A shared engine that could not be brought up serves every database on it nothing. */
    private static void failedDatabaseEngines(List<AttentionItem> items) {
        for (Row row : Models.get(DatabaseEngineModel.class).find()
                .where(DatabaseEngineModel.STATUS.eq(DatabaseModel.STATUS_FAILED))
                .all()) {
            String reason = row.get(DatabaseEngineModel.FAILURE_REASON);
            items.add(item(AttentionSeverity.ERROR, "server",
                copy("database_engine", "attention_title",
                    "name", row.get(DatabaseEngineModel.NAME)),
                reason == null || reason.isBlank()
                    ? copy("provisioning_failed", "attention_detail")
                    : copy("engine_provisioning_failed_reason", "attention_detail",
                        "reason", reason),
                CmsRoutes.detail(ADMIN, DatabaseParts.ENGINES_SLUG,
                    row.get(DatabaseEngineModel.ID)),
                action("act_open_engine")));
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
        var instanceModel = Models.get(InstanceModel.class);
        var databaseModel = Models.get(DatabaseModel.class);
        Map<Integer, DatabaseVerdict> verdicts = new HashMap<>();
        for (Row link : links) {
            Row instance = instanceModel.find()
                .where(InstanceModel.ID.eq(link.get(InstanceDatabaseModel.INSTANCE_ID)))
                .first();
            if (instance == null) {
                continue;
            }
            Integer databaseId = link.get(InstanceDatabaseModel.DATABASE_ID);
            Row database = databaseId == null ? null : databaseModel.find()
                .where(DatabaseModel.ID.eq(databaseId))
                .first();
            if (database == null) {
                continue;   // dangling link; the tab shows it as (deleted)
            }
            DatabaseVerdict verdict = verdicts.computeIfAbsent(databaseId, id -> DatabaseVerdict.ofDatabase(database));
            if (verdict.serves()) {
                continue;
            }
            items.add(item(AttentionSeverity.WARNING, "database",
                copy("app_database_down", "attention_title",
                    "name", instance.get(InstanceModel.NAME), "database", database.get(DatabaseModel.NAME)),
                verdict.reason() != null ? verdict.reason() : verdict.state().label(),
                InstanceParts.recordRoute(ADMIN, instance, InstanceDatabasesPage.SLUG),
                action("act_open_databases"))
                .causedBy(AttentionSubject.database(databaseId)));
        }
    }
}
