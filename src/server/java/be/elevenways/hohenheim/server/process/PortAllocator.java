package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.ports.PortLedger;
import be.elevenways.hohenheim.server.util.PortProbe;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocates loopback-reachable TCP ports for managed child processes, holding each one in
 * the persistent {@link PortLedger} for the lifetime of the process instance.
 *
 * AIDEV-NOTE: a managed-process port is owned by a PROCESS INSTANCE, not by a record --
 * allocate happens inside startProcessOnce and release on every exit path, so a site with
 * capacity N holds N ports and a crash loop churns them. That is why these rows carry a
 * NULL owner_model/owner_id and describe themselves in {@code note} instead; a site id in
 * an owner column would claim a record owns a port the record cannot release.
 *
 * The in-memory map is a CACHE, not the authority: the ledger row is what makes an
 * allocation survive a controller restart, and the unique claim key is what closes the
 * same-JVM race the old putIfAbsent only pretended to close (the probe socket is shut
 * before the claim, so nothing in this process ever guarded against another process).
 *
 * ORDER: ledger first, OS probe second, claim last. The probe is the ONLY thing that can
 * see a consumer no ledger will ever contain -- IpcChannel's per-spawn
 * {@code ServerSocket(0)}, this machine's testcontainers publishing into 32768-60999, or a
 * managed child that OUTLIVED the controller that spawned it (proven: a ProcessBuilder
 * child is reparented to init and keeps its listening socket across a controller crash),
 * so it may never be skipped. The up-front ledger read and PortLedger.claim's own refusal
 * (unique key plus the whole-host/specific-address overlap rule) are TWO independent
 * refusals of the same thing, deliberately: removing either alone still refuses a taken
 * port -- proven by counterfactual -- and removing both hands a docker site's published
 * port to a managed process. The read also keeps a host with many held ports from probing
 * and then failing an INSERT once per held port on every spawn.
 */
public class PortAllocator {

    private static final int PORT_RANGE = 5000;

    /** Prefix of the ledger note every managed-process claim carries. */
    static final String NOTE_PREFIX = "managed process";

    /** The bind address a managed-process claim covers: the whole host, matching the probe. */
    private static final String BIND_ADDRESS = "";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Identifies THIS controller run in the ledger note. A row carrying another token was
     * written by a previous JVM, which is exactly what the boot sweep is allowed to touch --
     * so a sweep can never free a port this controller just allocated, whenever it runs.
     */
    private final String controllerToken = Long.toHexString(RANDOM.nextLong());

    private final ConcurrentHashMap<Integer, Integer> reservedPorts = new ConcurrentHashMap<>();

    /**
     * Allocate a free port for the given site and hold it in the ledger.
     *
     * @throws RuntimeException if no port is available
     */
    public int allocate(int siteId) {
        int firstPort = getFirstPort();
        int lastPort = firstPort + PORT_RANGE;
        int serverId = ServerModel.localServerId();
        Set<Integer> claimed = claimedPorts(serverId, firstPort, lastPort);

        for (int port = firstPort; port < lastPort; port++) {
            if (reservedPorts.containsKey(port) || claimed.contains(port)) {
                continue;
            }
            if (!isPortFree(port)) {
                continue;
            }
            try {
                PortLedger.claim(serverId, BIND_ADDRESS, port, "tcp", null, null, noteFor(siteId));
            } catch (PortLedger.PortConflict conflict) {
                continue;   // a rival writer took it between the read and the insert
            }
            reservedPorts.put(port, siteId);
            return port;
        }

        throw new RuntimeException("No free port in range " + firstPort + "-" + lastPort);
    }

    /** Release a previously allocated port, in memory and in the ledger. */
    public void release(int port) {
        reservedPorts.remove(port);
        PortLedger.releaseKey(PortLedger.claimKeyOf(ServerModel.localServerId(),
            BIND_ADDRESS, port, "tcp"));
    }

    /** What a boot sweep did: rows whose port was observed free, and rows still bound. */
    public record SweepResult(int released, int retained) {}

    /**
     * Reconcile managed-process claims left behind by a PREVIOUS controller run: a port
     * this machine no longer has bound is released, a port that is STILL BOUND keeps its
     * claim and is reported.
     *
     * AIDEV-NOTE: the single most dangerous operation in this class, and the reason the
     * OS probe outranks the ledger here. Managed children are spawned with ProcessBuilder
     * and nothing kills them when the controller exits -- verified on this machine: the
     * child is reparented to init and its listening socket survives a controller halt with
     * no shutdown hooks run at all. Freeing such a row would hand the port to the next
     * allocate, whose child then dies with EADDRINUSE -- and startProcess RETRIES on
     * address-in-use, so the whole failure would show up as a slightly slower start and a
     * retry log. A still-bound port must therefore keep its row (it stays unallocatable)
     * and say so loudly; it never disappears quietly. Rows written by THIS controller are
     * never candidates, so the sweep is safe to run at any time and more than once.
     */
    public @NonNull SweepResult sweepPreviousControllerClaims() {
        int serverId = ServerModel.localServerId();
        int released = 0;
        int retained = 0;
        for (Row claim : Models.get(PortAllocationModel.class).find()
                .where(PortAllocationModel.SERVER_ID.eq(serverId))
                .and(PortAllocationModel.OWNER_MODEL.isNull())
                .all()) {
            String note = claim.get(PortAllocationModel.NOTE);
            if (note == null || !note.startsWith(NOTE_PREFIX) || note.endsWith(controllerToken)) {
                continue;   // not ours to judge, or written by this very controller
            }
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port == null) {
                continue;
            }
            if (isPortFree(port)) {
                Models.get(PortAllocationModel.class).delete(claim.get(PortAllocationModel.ID));
                released++;
            } else {
                retained++;
                Blast.log("PORTS: port", port, "is still bound by a process that outlived a previous"
                    + " controller (" + note + "); its claim is KEPT so nothing else is handed the"
                    + " port. Kill the orphaned process to free it.");
            }
        }
        if (released > 0 || retained > 0) {
            Blast.log("PORTS: boot sweep released", released,
                "stale managed-process claim(s) and kept", retained, "still-bound one(s)");
        }
        return new SweepResult(released, retained);
    }

    /** The ledger note for one process-instance claim: what holds it, and which controller. */
    private String noteFor(int siteId) {
        return NOTE_PREFIX + " site=" + siteId + " controller=" + controllerToken;
    }

    /** Every port the ledger holds on this host inside the allocation window, in ONE query. */
    private static Set<Integer> claimedPorts(int serverId, int firstPort, int lastPort) {
        Set<Integer> claimed = new HashSet<>();
        List<Row> rows = Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.SERVER_ID.eq(serverId))
            .and(PortAllocationModel.PORT.gte(firstPort))
            .and(PortAllocationModel.PORT.lt(lastPort))
            .all();
        for (Row claim : rows) {
            // A whole-host allocation conflicts with EVERY bind address on the port, so
            // the address is not filtered here -- see PortLedger.conflictingHolder.
            Integer port = claim.get(PortAllocationModel.PORT);
            if (port != null && "tcp".equals(claim.get(PortAllocationModel.PROTOCOL))) {
                claimed.add(port);
            }
        }
        return claimed;
    }

    /**
     * Check if a port is free by attempting to bind it. Answers only about THIS host and
     * only for the instant it runs, which is precisely why it cannot replace the ledger.
     */
    private boolean isPortFree(int port) {
        return PortProbe.isFree("", port, "tcp");
    }

    /** Privileged or missing values fall back to 4748. */
    private int getFirstPort() {
        Integer configured = HohenheimSettings.VALUES.getValue(HohenheimSettings.Proxy.FIRST_PORT);
        if (configured == null || configured <= 1024) {
            return 4748;
        }
        return configured;
    }

    public int getReservedCount() {
        return reservedPorts.size();
    }
}
