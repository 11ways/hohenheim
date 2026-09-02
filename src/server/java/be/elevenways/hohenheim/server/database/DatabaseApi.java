package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.model.DatabaseEngineModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.api.ApiConduits;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cms.DatabaseResource;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.access.AccessRefusedException;
import be.elevenways.zenit.cms.server.page.ResourceWrites;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityLog;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The managed-database API (v1): the automation surface over the tier that had none, so
 * a teardown and a move onto a shared engine no longer need a browser.
 *
 * The instance lane's three rules hold here verbatim (see {@code InstanceApi}): no
 * authorization decision of its own beyond the shared visibility walk, no existence
 * oracle, and no field that was not enumerated. What is specific to this tier:
 *
 * 1. The DOORS are the panels'. The list is the {@code view} scope {@code
 *    ManageDatabaseResource} renders, and it projects the DELEGATED columns for a
 *    non-admin -- the engine a shared record lives on, its host and its ceilings are
 *    operator facts, and an engine name is another tenant's neighbour list. The move and
 *    the engine list are ADMIN-ONLY because only the admin panel offers them at all
 *    ({@code ManageDatabaseResource} overrides {@code rowActions} to drop the move, and
 *    there is no delegated engine resource). The delete rides
 *    {@link DatabaseResource}'s own pipeline, so {@code destroy} on the record and the
 *    in-use refusal are the resource's and the service's.
 *
 * 2. The move ANSWERS BEFORE IT ACTS. It runs in the background exactly as the row
 *    action does, so the answer is an accepted/queued shape and the record's status is
 *    where the outcome shows up -- but every refusal the lane would make on eligibility
 *    ({@link DatabaseService#moveRefusal}) is made HERE, synchronously and by name. A
 *    background lane that refuses is invisible to a script.
 */
public final class DatabaseApi {

    /** The admin resource whose delete pipeline the delete verb rides. */
    private static final DatabaseResource DATABASES = new DatabaseResource();

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

        HohenheimEndpoints.API_DATABASE_MOVE_SHARED.setHandler(conduit -> {
            AccessContext ctx = requireAdminKey(conduit);
            if (ctx == null) {
                return null;
            }
            Row row = visibleDatabase(conduit, ctx);
            if (row == null) {
                return null;
            }
            Microcopy refusal = DatabaseService.moveRefusal(row);
            if (refusal != null) {
                return ApiConduits.refusal(conduit, Violations.ofForm(refusal));
            }
            int databaseId = row.get(DatabaseModel.ID);
            String name = row.get(DatabaseModel.NAME);
            new DatabaseService().moveToSharedEngineInBackground(name);
            ActivityLog.record(Models.get(DatabaseModel.class), databaseId, "move_shared", name);
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
            try {
                // The resource's pipeline: deletableBy demands `destroy` on the record,
                // deleteUnavailableReason refuses while a workload holds it, and deleteRow
                // is DatabaseService.destroy -- which asks the destroy gate again itself.
                ResourceWrites.delete(DATABASES, row, ctx);
            } catch (Violations refused) {
                return ApiConduits.refusal(conduit, refused);
            } catch (AccessRefusedException refused) {
                conduit.forbidden();
                return null;
            }
            ActivityLog.record(Models.get(DatabaseModel.class), databaseId, "deleted", name);
            return ApiConduits.json(Map.of("id", databaseId, "name", name, "status", "deleted"));
        });

        HohenheimEndpoints.API_DATABASE_ENGINES.setHandler(conduit -> {
            AccessContext ctx = requireAdminKey(conduit);
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
    }

    // -- doors ----------------------------------------------------------------

    /**
     * An API-key context that also holds the admin panel permission, as narrowed by the
     * key's own scopes -- the site create/delete lane's shape, for the two verbs whose
     * only panel is the operator one.
     *
     * @return the access context, or null when the response has already been ended
     */
    private static @Nullable AccessContext requireAdminKey(@NonNull Conduit conduit) {
        AccessContext ctx = ApiConduits.requireKey(conduit);
        if (ctx == null) {
            return null;
        }
        if (!HohenheimAccess.isAdmin(ctx)) {
            conduit.forbidden();
            return null;
        }
        return ctx;
    }

    /**
     * The databases this context may see: admins everything, everyone else exactly the
     * records the walk confirms {@code view} on -- the SAME scope
     * {@code ManageDatabaseResource.accessFunction} renders.
     */
    private static @NonNull List<Row> visibleDatabases(@NonNull AccessContext ctx) {
        var query = Models.get(DatabaseModel.class).find();
        Criteria scope = HohenheimAccess.databaseScope(ctx, HohenheimAccess.VIEW);
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
                HohenheimAccess.VIEW)) {
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
        entry.put("attached", databaseId == null ? 0
            : InstanceDatabaseLinks.liveInstances(databaseId).size());
        if (admin) {
            // Operator facts: which engine row serves it, on which host, and the ceilings
            // booked against that host's budget. A shared record carries none of its own.
            entry.put("engine_id", database.get(DatabaseModel.ENGINE_ID));
            entry.put("server", ServerModel.nameOf(database.get(DatabaseModel.SERVER_ID)));
            entry.put("ephemeral", Boolean.TRUE.equals(database.get(DatabaseModel.EPHEMERAL)));
            entry.put("memory_limit_mb", database.get(DatabaseModel.MEMORY_LIMIT_MB));
            entry.put("cpu_limit", database.get(DatabaseModel.CPU_LIMIT));
            entry.put("failure_reason", stringOrEmpty(database.get(DatabaseModel.FAILURE_REASON)));
        }
        return entry;
    }

    /** The enumerated engine view; the superuser credentials are absent BY NAME. */
    static @NonNull Map<String, Object> engineProjection(@NonNull Row engine) {
        Integer engineId = engine.get(DatabaseEngineModel.ID);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", engineId);
        entry.put("name", engine.get(DatabaseEngineModel.NAME));
        entry.put("engine", String.valueOf((Object) engine.get(DatabaseEngineModel.ENGINE)));
        entry.put("image", stringOrEmpty(engine.get(DatabaseEngineModel.IMAGE)));
        entry.put("server", ServerModel.nameOf(engine.get(DatabaseEngineModel.SERVER_ID)));
        entry.put("memory_limit_mb", engine.get(DatabaseEngineModel.MEMORY_LIMIT_MB));
        entry.put("cpu_limit", engine.get(DatabaseEngineModel.CPU_LIMIT));
        entry.put("databases", engineId == null ? 0 : DatabaseEngines.databasesOn(engineId).size());
        entry.put("status", String.valueOf((Object) engine.get(DatabaseEngineModel.STATUS)));
        entry.put("failure_reason", stringOrEmpty(engine.get(DatabaseEngineModel.FAILURE_REASON)));
        return entry;
    }

    private static @NonNull String stringOrEmpty(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
