package be.elevenways.hohenheim.server.application;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WHERE the workload a site names sends its traffic right now: the loopback address of the
 * consuming instance's published port, re-resolved whenever that instance's generation moves.
 *
 * AIDEV-NOTE: "application" throughout this class is the OWNER INSTANCE ID a site names
 * ({@code sites.instance_id}), not a separate record type. For a release-managed record the
 * consumer is its serving release; for a workspace or a docker container the consumer is the
 * record itself. Every key here is therefore an instance id, which is why
 * {@link #invalidateForInstance} can be called from the instance funnel with no knowledge of
 * which tier owns the record.
 *
 * AIDEV-NOTE: a resolution is a database read (the serving row plus its port claim), which
 * is far too much per request, and a value cached forever is the stale-address bug the
 * brief names. So the cache is GENERATION-keyed: every flip, deploy, stop and destroy bumps
 * the application's generation, and a handler holding an older generation re-resolves on its
 * next request. No listener, no invalidation broadcast, and no window in which a request is
 * forwarded to a container the engine already retired.
 *
 * AIDEV-NOTE: the generation map is PER JVM, so it carries only what THIS controller did.
 * A workload another controller deployed moves no generation here, and the only thing that
 * covers that today is the negative re-resolve in {@link InstanceUpstreamHandler} (a held
 * NULL is retried on a short timer). A held ADDRESS is still trusted until this controller
 * itself invalidates it; making that cross-controller is a shared-invalidation design, not
 * something to bolt onto this cache.
 *
 * AIDEV-NOTE: the address comes from the PORT LEDGER, not from the daemon. The ledger is
 * THE port authority and was written record-after by the very deploy that published the
 * port, so reading it costs one query instead of a docker inspect per resolution, and it
 * cannot disagree with what the rest of the product believes is claimed.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
public final class ApplicationUpstreams {

    /** Loopback, the one address the instance tier publishes record-after ports on. */
    public static final String BIND_ADDRESS = "127.0.0.1";

    private static final Map<Integer, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private ApplicationUpstreams() {
    }

    /** What a handler holds: the address, and the generation it was resolved at. */
    public record Resolution(@Nullable URI upstream, long generation) {
    }

    /** @return the application's current resolution generation */
    public static long generationOf(int applicationId) {
        return GENERATIONS.computeIfAbsent(applicationId, key -> new AtomicLong()).get();
    }

    /** Declare every resolved address of this application stale. */
    public static void invalidate(int applicationId) {
        GENERATIONS.computeIfAbsent(applicationId, key -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Declare every resolved address that could name THIS instance stale: its own key, and
     * the key of the owner it was generated for.
     *
     * AIDEV-NOTE: both keys, because a site names either. A workspace or a docker container
     * IS the record a site points at, so its own id is the key; a release is generated for
     * an application and the site points at the APPLICATION, whose generation is what the
     * handler watches. Invalidating only the id in hand would leave one of the two lanes
     * exactly as stale as before.
     */
    public static void invalidateForInstance(int instanceId) {
        invalidate(instanceId);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null) {
            return;
        }
        int owner = ApplicationReleases.linkOwnerOf(instance);
        if (owner != instanceId) {
            invalidate(owner);
        }
    }

    /**
     * Resolve the application's serving upstream.
     *
     * AIDEV-NOTE: the generation is read BEFORE the resolution, never after. Reading it
     * after would stamp a resolution with a generation that already includes a flip which
     * happened DURING it, and the handler would then keep a stale address believing it was
     * current -- the classic check-then-act inversion.
     *
     * @return the resolution; its upstream is null when the application has nothing serving
     */
    public static @NonNull Resolution resolve(int applicationId) {
        return resolve(applicationId, "http");
    }

    /**
     * {@link #resolve(int)} with the site's declared scheme, for a workload that terminates
     * TLS itself.
     */
    public static @NonNull Resolution resolve(int applicationId, @NonNull String scheme) {

        long generation = generationOf(applicationId);
        // consumerInstanceOf, not ownedServing: a release-managed record resolves its
        // serving release, and any OTHER kind with a published port (a workspace, a
        // docker container) resolves ITSELF -- which is what lets a site expose those
        // kinds too (their handlers declare supportsSiteUpstream).
        Integer servingId = ApplicationReleases.consumerInstanceOf(applicationId);

        if (servingId == null || !isServable(servingId)) {
            return new Resolution(null, generation);
        }

        Integer port = publishedPortOf(servingId);

        if (port == null) {
            return new Resolution(null, generation);
        }

        try {
            return new Resolution(new URI(scheme, null, BIND_ADDRESS, port, "/", null, null),
                generation);
        } catch (Exception malformed) {
            Blast.log("APPLICATION: upstream of application", applicationId,
                "is unrepresentable -", malformed.getMessage());
            return new Resolution(null, generation);
        }
    }

    /**
     * Whether the consuming record itself still claims a workload traffic could reach.
     *
     * AIDEV-NOTE: the ledger is the PORT authority, not a liveness authority. A workload
     * that died without a stop keeps its observed claim (only a settled stop releases one),
     * so a resolution that asked the ledger alone would forward to a dead container's host
     * port -- which the daemon is free to hand to somebody else's container. This is the
     * read that makes {@code InstanceStatusReconciler}'s correction reach the proxy.
     *
     * @return false for a missing, trashed or non-{@link InstanceModel#SERVABLE_STATUSES}
     *         record
     */
    private static boolean isServable(int instanceId) {
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        return instance != null && instance.get(InstanceModel.DELETED_AT) == null
            && InstanceModel.SERVABLE_STATUSES.contains(instance.get(InstanceModel.STATUS));
    }

    /**
     * The host port one release instance holds in the ledger.
     *
     * @return the port, or null when the release holds no held claim (never started, or
     *         its claim is parked in releasing and therefore not an address to forward to)
     */
    private static @Nullable Integer publishedPortOf(int releaseId) {
        for (Row claim : PortLedger.claimsOf(InstanceModel.MODEL_ID, releaseId)) {
            if (PortLedger.isReleasing(claim)) {
                continue;
            }
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port != null && port > 0) {
                return port;
            }
        }
        return null;
    }
}
