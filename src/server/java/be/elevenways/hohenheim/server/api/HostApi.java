package be.elevenways.hohenheim.server.api;

import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The HOST capacity surface (v1): the memory picture the admin overview page renders,
 * answered as data, because an operator migrating databases onto shared engines had no
 * way to read a host's booking except {@code docker stats} -- which measures something
 * else entirely (live RSS, not the booked ceilings placement decides on).
 *
 * The three rules of this lane, unchanged from {@code InstanceApi} and {@code DatabaseApi}:
 * no authorization decision of its own, no existence oracle, no field that was not
 * enumerated. What is specific here:
 *
 * 1. ADMIN-ONLY, both verbs. A host row exists on the operator panel alone, and the
 *    single-host form enumerates every workload on the machine -- which is every tenant's
 *    workload, by name.
 * 2. EVERY NUMBER IS THE PANEL'S OWN. The ledger is {@link InstanceCapacity#viewOf}, the
 *    same call the overview page's bar and facts read, and a workload's booked amount is
 *    {@link InstanceCapacity#bookedMbOf} -- the number its release will hand back. Nothing
 *    is recomputed here, so the CLI and the panel cannot disagree.
 * 3. LIVE usage is reported only when it already EXISTS ({@link InstanceStats#lastMemoryMb}:
 *    the stats hub's last sample, present while somebody watches that workload). This
 *    surface never opens a daemon stats stream -- one host listing would become N of them
 *    for a number that is not the one placement decides on anyway -- so the field is absent
 *    rather than invented.
 */
public final class HostApi {

    private HostApi() {
    }

    public static void init() {
        HohenheimEndpoints.API_V1_HOSTS.setHandler(conduit -> {
            if (requireAdminKey(conduit) == null) {
                return null;
            }
            List<Map<String, Object>> hosts = new ArrayList<>();
            for (Row server : Models.get(ServerModel.class).find()
                    .orderBy(ServerModel.ID, SortOrder.ASC).all()) {
                hosts.add(projection(server));
            }
            return ApiConduits.json(Map.of("hosts", hosts));
        });

        HohenheimEndpoints.API_V1_HOST.setHandler(conduit -> {
            if (requireAdminKey(conduit) == null) {
                return null;
            }
            Integer serverId = conduit.getParameter(HohenheimEndpoints.SERVER_ID);
            Row server = serverId == null ? null
                : Models.get(ServerModel.class).findById(serverId);
            if (server == null) {
                conduit.notFound();
                return null;
            }
            Map<String, Object> body = new LinkedHashMap<>(projection(server));
            body.put("workloads", workloadsOn(serverId));
            return ApiConduits.json(body);
        });
    }

    /**
     * An API-key context holding the admin panel permission, as narrowed by the key's own
     * scopes -- the engine list's door.
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

    // -- projections -----------------------------------------------------------

    /** One host: who it is, whether it takes placement, and its memory ledger. */
    static @NonNull Map<String, Object> projection(@NonNull Row server) {
        Integer serverId = server.get(ServerModel.ID);
        HostCapacityView capacity = serverId == null ? null
            : InstanceCapacity.viewOf(server, serverId);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", serverId);
        entry.put("name", server.get(ServerModel.NAME));
        entry.put("runtime", String.valueOf((Object) server.get(ServerModel.RUNTIME)));
        entry.put("mode", String.valueOf((Object) server.get(ServerModel.MODE)));
        entry.put("admission", String.valueOf((Object) server.get(ServerModel.ADMISSION)));
        entry.put("preflight_ok", Boolean.TRUE.equals(server.get(ServerModel.PREFLIGHT_OK)));
        if (capacity == null) {
            return entry;
        }
        // measured false is an explicit answer: the budget columns are then zero because
        // this host HAS no placement budget, never because it is empty.
        entry.put("measured", capacity.measured());
        entry.put("stale", capacity.stale());
        entry.put("budget_mb", capacity.budgetMb());
        entry.put("booked_mb", capacity.bookedMb());
        entry.put("bookable_mb", capacity.bookableMb());
        // What is left to book, which is the number an operator planning a move wants and
        // the only one the panel's three facts leave to arithmetic.
        entry.put("free_mb", Math.max(0, capacity.bookableMb() - capacity.bookedMb()));
        entry.put("overcommit_ratio", overcommitRatio());
        entry.put("reserve_mb", reserveMb());
        entry.put("measured_at", capacity.measuredAtIso());
        entry.put("facts_max_age_hours", capacity.maxAgeHours());
        return entry;
    }

    /**
     * Every workload booked on this host, engine containers included: a shared database
     * engine and a dedicated database are both {@code database_container} instances since
     * the tier was lowered, so one instance walk enumerates the whole ledger.
     */
    private static @NonNull List<Map<String, Object>> workloadsOn(int serverId) {
        List<Map<String, Object>> workloads = new ArrayList<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(serverId))
                .where(InstanceModel.DELETED_AT.isNull())
                .orderBy(InstanceModel.ID, SortOrder.ASC).all()) {
            Integer instanceId = instance.get(InstanceModel.ID);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", instanceId);
            entry.put("kind", String.valueOf((Object) instance.get(InstanceModel.KIND)));
            entry.put("name", instance.get(InstanceModel.NAME));
            entry.put("status", String.valueOf((Object) instance.get(InstanceModel.STATUS)));
            entry.put("booked_mb", InstanceCapacity.bookedMbOf(instance));
            Long usage = instanceId == null ? null : InstanceStats.lastMemoryMb(instanceId);
            if (usage != null) {
                entry.put("usage_mb", usage);
            }
            workloads.add(entry);
        }
        return workloads;
    }

    /** The declared overcommit factor; 1.0 when unset or nonsensical, as the budget reads it. */
    private static double overcommitRatio() {
        Double ratio = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO);
        return ratio == null || ratio <= 0 ? 1.0 : ratio;
    }

    /** The memory held back for everything that is not a booked workload. */
    private static int reserveMb() {
        Integer reserve = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB);
        return reserve == null ? 0 : Math.max(0, reserve);
    }
}
