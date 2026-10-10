package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceKindFields;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseParts;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.operation.ZenitPlacementSurface;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.activity.ZenitActivityAction;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The managed-database API (v1): the automation surface over the tier that had none, so
 * a teardown and a move onto a shared engine no longer need a browser.
 *
 * The instance lane's three rules hold here verbatim (see {@code InstanceApi}): no
 * authorization decision of its own beyond the shared visibility walk, no existence
 * oracle, and no field that was not enumerated. What is specific to this tier:
 *
 * 1. The DOORS are the panels'. The list is the {@code view} scope the /manage
 *    databases ({@link DatabaseParts#manage}) render, and it projects the DELEGATED columns for a
 *    non-admin -- the engine a shared record lives on, its host and its ceilings are
 *    operator facts, and an engine name is another tenant's neighbour list. The move and
 *    the engine list are ADMIN-ONLY because only the admin panel offers them at all
 *    (the /manage twin places no move, and there is no delegated engine resource). The
 *    delete is the panel's own {@link DatabaseParts#DELETE} operation, so {@code destroy}
 *    on the record and the in-use refusal are the operation's and the service's.
 *
 * 2. The move ANSWERS BEFORE IT ACTS. It runs in the background exactly as the row
 *    action does, so the answer is an accepted/queued shape and the record's status is
 *    where the outcome shows up -- but every refusal the lane would make on eligibility
 *    ({@link DatabaseService#moveRefusal}) is made HERE, synchronously and by name. A
 *    background lane that refuses is invisible to a script.
 */
public final class DatabaseApi {

    private DatabaseApi() {
    }

    public static void init() {
        HohenheimEndpoints.API_DATABASES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            boolean admin = HohenheimAccess.isAdmin(ctx);
            List<Map<String, Object>> databases = new ArrayList<>();
            for (Row row : visibleDatabases(ctx)) {
                databases.add(projection(row, admin));
            }
            return ApiConduits.json(Map.of("databases", databases));
        });

        HohenheimEndpoints.API_DATABASE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleDatabase(conduit, ctx);
            if (row == null) {
                return null;
            }
            return ApiConduits.json(projection(row, HohenheimAccess.isAdmin(ctx)));
        });

        HohenheimEndpoints.API_DATABASE_MOVE_SHARED.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleDatabase(conduit, ctx);
            if (row == null) {
                return null;
            }
            if (ApiConduits.rowEntry(conduit, ApiConduits.adminPanel(), HohenheimSlugs.DATABASES) == null) {
                return null;
            }
            // AIDEV-NOTE: the eligibility is asked HERE first, of the operation's own declaration
            // (DatabaseService.moveRefusal, which its applies() reads), because the pipeline refuses a subject the
            // operation does not apply to as NOT_FOUND without words -- this frozen wire answers the named 422.
            Microcopy refusal = DatabaseService.moveRefusal(row);
            if (refusal != null) {
                return ApiConduits.refusal(conduit, Violations.ofForm(refusal));
            }
            int databaseId = row.get(DatabaseModel.ID);
            String name = row.get(DatabaseModel.NAME);
            try {
                // The panel's move_database_shared operation, the one writer. Its claim is atomic and
                // synchronous: a second submit of the same move (or one racing the panel's action) is refused
                // here, by name.
                OperationPipeline.invoke(OperationRequest.of(DatabaseParts.MOVE_TO_SHARED,
                        ZenitPlacementSurface.HTTP_API)
                    .caller(ctx)
                    .subjects(List.of(row)));
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused);
            }
            ActivityLog.record(Models.get(DatabaseModel.class), databaseId, HohenheimActivityAction.MOVE_SHARED, name);
            // The panel's toast, as data: the work is accepted, and the RECORD's status is
            // the thing to watch (provisioning while it runs, active when it settles).
            return ApiConduits.json(Map.of("id", databaseId, "name", name,
                "status", "queued", "watch", "status"));
        });

        HohenheimEndpoints.API_DATABASE_DELETE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleDatabase(conduit, ctx);
            if (row == null) {
                return null;
            }
            int databaseId = row.get(DatabaseModel.ID);
            String name = row.get(DatabaseModel.NAME);
            Panel panel = ApiConduits.adminPanel();
            PanelResource<Row> databases = ApiConduits.rowEntry(conduit, panel, HohenheimSlugs.DATABASES);
            if (databases == null) {
                return null;
            }
            try {
                // The panel's delete operation: it demands `destroy` on the record, its
                // availability refuses while a workload holds it, and its handler is
                // DatabaseService.destroy -- which asks the destroy gate again itself.
                ResourceWrites.delete(panel, databases, row, ctx);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (DomainRefusal refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
            ActivityLog.record(Models.get(DatabaseModel.class), databaseId, ZenitActivityAction.DELETE, name);
            return ApiConduits.json(Map.of("id", databaseId, "name", name, "status", "deleted"));
        });

        HohenheimEndpoints.API_DATABASE_ENGINES.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            List<Map<String, Object>> engines = new ArrayList<>();
            for (Row row : Models.get(DatabaseEngineModel.class).find()
                    .orderBy(DatabaseEngineModel.ID, SortOrder.ASC).all()) {
                engines.add(engineProjection(row));
            }
            return ApiConduits.json(Map.of("engines", engines));
        });

        HohenheimEndpoints.API_DATABASE_ENGINE.setHandler(conduit -> {
            AccessContext ctx = ApiConduits.requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            Integer engineId = conduit.getParameter(HohenheimEndpoints.DB_ENGINE_ID);
            Row engine = engineId == null ? null
                : Models.get(DatabaseEngineModel.class).findById(engineId);
            if (engine == null) {
                conduit.notFound();
                return null;
            }
            Map<String, Object> body = new LinkedHashMap<>(engineProjection(engine));
            List<Map<String, Object>> logical = new ArrayList<>();
            for (Row database : DatabaseEngines.databasesOn(engineId)) {
                logical.add(logicalProjection(database));
            }
            body.put("logical_databases", logical);
            // The engine's own container, when somebody is watching it: the stats hub's
            // last sample, never a stream this read would have to open (see lastMemoryMb).
            Row instance = DatabaseInstances.ownedBy(EngineHost.ofEngine(engine));
            Long usage = instance == null ? null
                : InstanceStats.lastMemoryMb(instance.get(InstanceModel.ID));
            if (usage != null) {
                body.put("usage_mb", usage);
            }
            return ApiConduits.json(body);
        });
    }

    // -- doors ----------------------------------------------------------------

    /**
     * The databases this context may see: admins everything, everyone else exactly the
     * records the walk confirms {@code view} on -- the SAME scope
     * /manage databases ({@link DatabaseParts#manage}) render.
     */
    private static @NonNull List<Row> visibleDatabases(@NonNull AccessContext ctx) {
        var query = Models.get(DatabaseModel.class).find();
        Criteria scope = HohenheimAccess.databaseScope(ctx, HohenheimCapabilities.VIEW);
        if (scope != null) {
            query.where(scope);
        }
        return query.orderBy(DatabaseModel.ID, SortOrder.ASC).all();
    }

    /**
     * Resolve the route's database for this context, ending the response with a 404 when
     * it is absent OR not permitted -- one answer for both, so this surface is no
     * existence oracle.
     *
     * @return the row, or null when the response has already been ended
     */
    private static @Nullable Row visibleDatabase(@NonNull Conduit conduit,
                                                 @NonNull AccessContext ctx) {
        Integer databaseId = conduit.getParameter(HohenheimEndpoints.DATABASE_ID);
        Row row = databaseId == null ? null
            : Models.get(DatabaseModel.class).findById(databaseId);
        if (row == null || !HohenheimAccess.hasDatabaseCapability(ctx, databaseId,
                HohenheimCapabilities.VIEW)) {
            conduit.notFound();
            return null;
        }
        return row;
    }

    // -- projections -----------------------------------------------------------

    /**
     * THE enumerated view of a managed database. A whitelist, never a row dump: the
     * credentials have no representation here at all (the Credentials tab answers to its
     * own capability), and a delegated caller sees the /manage columns only.
     *
     * @param admin whether the caller holds the operator panel's permission
     */
    static @NonNull Map<String, Object> projection(@NonNull Row database, boolean admin) {
        Integer databaseId = database.get(DatabaseModel.ID);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", databaseId);
        entry.put("name", database.get(DatabaseModel.NAME));
        entry.put("engine", String.valueOf((Object) database.get(DatabaseModel.ENGINE)));
        entry.put("db_name", database.get(DatabaseModel.DB_NAME));
        entry.put("placement", DatabaseService.placementOf(database));
        entry.put("status", String.valueOf((Object) database.get(DatabaseModel.STATUS)));
        // The status's own declared fact, so a poller never carries a list of statuses:
        // pending means keep waiting, and an unrecognised status reads pending too.
        entry.put("outcome", DatabaseModel.outcomeOf(database.get(DatabaseModel.STATUS)));
        entry.put("attached", databaseId == null ? 0
            : InstanceDatabaseLinks.liveInstances(databaseId).size());
        if (admin) {
            // Operator facts: which engine row serves it, on which host, and the ceilings
            // booked against that host's budget. A shared record carries none of its own.
            entry.put("engine_id", database.get(DatabaseModel.ENGINE_ID));
            entry.put("server", ServerModel.nameOf(database.get(DatabaseModel.SERVER_ID)));
            entry.put("ephemeral", Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL)));
            entry.put(InstanceKindFields.MEMORY_LIMIT_MB, database.get(DatabaseModel.MEMORY_LIMIT_MB));
            // The ceiling the record RUNS under, which memory_limit_mb alone never told:
            // a record on the defaults declares nothing, and a shared record's ceiling is
            // its engine's. The CLI prints this rather than deriving it.
            putMemoryCeiling(entry, database);
            entry.put(InstanceKindFields.CPU_LIMIT, database.get(DatabaseModel.CPU_LIMIT));
            entry.put("failure_reason", Objects.toString(database.get(DatabaseModel.FAILURE_REASON), ""));
        }
        return entry;
    }

    /**
     * One logical database as its ENGINE's detail page lists it: who it is, how it landed
     * and what it runs under. Operator surface (the engine list's own door), so the
     * logical user is named; the password has no representation here either.
     */
    static @NonNull Map<String, Object> logicalProjection(@NonNull Row database) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", database.get(DatabaseModel.ID));
        entry.put("name", database.get(DatabaseModel.NAME));
        entry.put("db_name", database.get(DatabaseModel.DB_NAME));
        entry.put("db_user", Objects.toString(database.get(DatabaseModel.DB_USER), ""));
        entry.put("status", String.valueOf((Object) database.get(DatabaseModel.STATUS)));
        entry.put("outcome", DatabaseModel.outcomeOf(database.get(DatabaseModel.STATUS)));
        putMemoryCeiling(entry, database);
        return entry;
    }

    /**
     * The effective ceiling and where it came from, both absent when the record's engine
     * host cannot be resolved -- an absent field is the honest answer, a guessed number
     * is not.
     */
    private static void putMemoryCeiling(@NonNull Map<String, Object> entry,
                                         @NonNull Row database) {
        DatabaseService.MemoryCeiling ceiling = DatabaseService.memoryCeilingOf(database);
        if (ceiling != null) {
            entry.put("effective_memory_mb", ceiling.megabytes());
            entry.put("memory_source", ceiling.source());
        }
    }

    /** The enumerated engine view; the superuser credentials are absent BY NAME. */
    static @NonNull Map<String, Object> engineProjection(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", engineId);
        entry.put("name", engine.get(DatabaseEngineModel.NAME));
        entry.put("engine", String.valueOf((Object) engine.get(DatabaseEngineModel.ENGINE)));
        entry.put("image", Objects.toString(engine.get(DatabaseEngineModel.IMAGE), ""));
        entry.put("server", ServerModel.nameOf(engine.get(DatabaseEngineModel.SERVER_ID)));
        entry.put(InstanceKindFields.MEMORY_LIMIT_MB, engine.get(DatabaseEngineModel.MEMORY_LIMIT_MB));
        entry.put(InstanceKindFields.CPU_LIMIT, engine.get(DatabaseEngineModel.CPU_LIMIT));
        entry.put("databases", engineId == null ? 0 : DatabaseEngines.databasesOn(engineId).size());
        entry.put("status", String.valueOf((Object) engine.get(DatabaseEngineModel.STATUS)));
        entry.put("failure_reason", Objects.toString(engine.get(DatabaseEngineModel.FAILURE_REASON), ""));
        return entry;
    }
}
