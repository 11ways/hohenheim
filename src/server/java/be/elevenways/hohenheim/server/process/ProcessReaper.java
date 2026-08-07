package be.elevenways.hohenheim.server.process;

import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.SystemUsers;
import be.elevenways.hohenheim.server.WorkloadIdentity;
import be.elevenways.hohenheim.server.sitetype.SiteHandlers;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.concurrent.TimeUnit;

/**
 * Kills managed child processes that OUTLIVED the controller that spawned them.
 *
 * AIDEV-NOTE: the gap this closes, stated plainly because two AIDEV-NOTEs used to deny it.
 * Children are spawned with ProcessBuilder and NOTHING kills them when the controller
 * exits: a reboot takes them with it, but an ordinary controller restart does not. The
 * survivors keep their listening socket (so {@link PortAllocator}'s boot sweep RETAINS
 * their ledger claim and the port stays unusable), they are in no {@code processMap} (so
 * they are invisible to the Processes tab and cannot be killed from the UI), and they write
 * no proclog. This class is what makes "after any restart the children are gone and come
 * back through here" true instead of aspirational.
 *
 * AIDEV-NOTE: identity is the ONLY thing that makes reaping safe, and the dangerous
 * direction is the one that matters. The reaper walks /proc and matches on a site's
 * EXCLUSIVELY CLAIMED uid ({@code system_users.site_id}, the claim
 * {@link WorkloadIdentity#forSite} takes). A site with no dedicated user is REFUSED by
 * name and reaped by nothing: its children's only identity is the DAEMON's own uid, and a
 * reaper keyed on that would kill the control plane -- the same trap
 * {@code ManagedProcessSiteHandler.isolate} refuses for network policy. Root and the
 * daemon's own uid are refused a second time here even if a row claims them, because a
 * poisoned {@code system_users} row must not be able to aim this class at pid 1.
 *
 * AIDEV-NOTE: liveness, never age. A child of the CURRENT controller generation is
 * excluded because a live handler holds its pid, not because it looks young -- a
 * wall-clock cutoff would reap a long-running healthy child on a restart-free host, and
 * would spare a fresh orphan spawned seconds before the restart. Port claims are not
 * touched here at all: the port sweep runs AFTER this and only frees a port it observes
 * unbound, so a claim is released exactly when its holder is really gone.
 */
public final class ProcessReaper {

    /** How long a reaped process gets between SIGTERM and SIGKILL. */
    private static final long GRACEFUL_TERM_MS = 2_000;

    /** How long the reaper waits for SIGKILL to take effect before reporting a survivor. */
    private static final long FORCE_WAIT_MS = 2_000;

    /** What one sweep did, per outcome, so the boot log can be judged. */
    public record ReapResult(int reaped, int survived, int sitesRefused, int sitesSwept) {}

    private ProcessReaper() {
    }

    /**
     * Kill every process owned by a managed site's dedicated uid that no live handler of
     * this controller generation claims.
     *
     * @return what was killed, what refused to die, and how many sites could not be judged
     */
    public static @NonNull ReapResult reapOrphans() {
        return reapOrphans(ProcessReaper::pidsOwnedBy, ProcessReaper::livePidsOf);
    }

    /**
     * The same sweep over injected process facts.
     *
     * AIDEV-NOTE: the seam exists because the two facts it injects are the two a test on a
     * developer machine cannot produce honestly -- a process really owned by a foreign uid
     * needs root, and a live handler needs a bound proxy. Everything the sweep DECIDES
     * (the identity refusals, the current-generation exclusion, TERM-then-KILL, the
     * verification that the process is really gone) runs for real against real processes
     * either way, and the two injected functions are pinned by their own tests:
     * {@code pidsOwnedBy} against this JVM's own children, {@code livePids} on the handler.
     *
     * @param ownedBy  every pid on this host whose real uid is the given one
     * @param liveOf   the pids the CURRENT controller generation holds for a site
     */
    static @NonNull ReapResult reapOrphans(@NonNull IntFunction<List<Long>> ownedBy,
                                           @NonNull IntFunction<Set<Long>> liveOf) {
        List<Row> claims = Models.get(SystemUserModel.class).find()
            .where(SystemUserModel.SITE_ID.isNotNull())
            .all();
        Integer daemonUid = WorkloadIdentity.daemonUid();
        int reaped = 0;
        int survived = 0;
        int refused = 0;
        int swept = 0;

        for (Row claim : claims) {
            Integer siteId = claim.get(SystemUserModel.SITE_ID);
            Integer uid = claim.get(SystemUserModel.UID);
            String name = claim.get(SystemUserModel.NAME);
            if (siteId == null || uid == null) {
                continue;
            }
            if (uid == 0 || (daemonUid != null && daemonUid.intValue() == uid)) {
                // A row claiming root or the controller's own identity is exactly what this
                // class may never act on, whatever the database says.
                Blast.log("PROCESS: REFUSING to reap for site", siteId, "- its claimed system"
                    + " user '" + name + "' is uid", uid, "which is root or the Hohenheim"
                    + " daemon's own identity; orphaned children of that site cannot be told"
                    + " apart from the control plane and are left running.");
                refused++;
                continue;
            }
            swept++;
            SystemUsers.RunAsUser runAs = new SystemUsers.RunAsUser(
                name == null ? "#" + uid : name, uid,
                claim.get(SystemUserModel.GID), claim.get(SystemUserModel.HOME));
            Set<Long> live = liveOf.apply(siteId);
            for (long pid : ownedBy.apply(uid)) {
                if (live.contains(pid) || pid == ProcessHandle.current().pid()) {
                    continue;
                }
                if (kill(pid, siteId, runAs)) {
                    reaped++;
                } else {
                    survived++;
                }
            }
        }

        refused += refuseSitesWithoutIdentity();

        if (reaped > 0 || survived > 0 || refused > 0) {
            Blast.log("PROCESS: orphan sweep across", swept, "site identities reaped", reaped,
                "process(es),", survived, "survived, and could not judge", refused, "site(s)");
        }
        return new ReapResult(reaped, survived, refused, swept);
    }

    /**
     * Name every enabled managed-process site that has no dedicated uid, so an operator
     * sees that its orphans are NOT covered rather than assuming the sweep covered them.
     *
     * @return how many sites were named
     */
    private static int refuseSitesWithoutIdentity() {
        int refused = 0;
        for (WorkloadIdentity.Finding finding : WorkloadIdentity.auditAll()) {
            Blast.log("PROCESS: site", finding.siteId(), "(" + finding.siteName() + ")",
                "has no dedicated system user (" + finding.problem() + "), so a child of it"
                + " that outlived a previous controller CANNOT be identified and is NOT"
                + " reaped; configure a system user for this site.");
            refused++;
        }
        return refused;
    }

    /**
     * The pids the CURRENT controller generation owns for a site: everything its live
     * handler holds, plus their descendants (a child's own children share its session).
     */
    private static @NonNull Set<Long> livePidsOf(int siteId) {
        Set<Long> live = new LinkedHashSet<>();
        ManagedProcessSiteHandler handler = SiteHandlers.managedProcess(siteId);
        if (handler == null) {
            return live;
        }
        for (long pid : handler.livePids()) {
            live.add(pid);
            ProcessHandle.of(pid).ifPresent(handle ->
                handle.descendants().forEach(child -> live.add(child.pid())));
        }
        return live;
    }

    /** Every pid on this host whose real uid is the given one, read from /proc. */
    static @NonNull List<Long> pidsOwnedBy(int uid) {
        List<Long> pids = new ArrayList<>();
        Path proc = Path.of("/proc");
        if (!Files.isDirectory(proc)) {
            return pids;
        }
        try (var entries = Files.list(proc)) {
            for (Path entry : entries.toList()) {
                Long pid = pidOf(entry);
                if (pid != null && realUidOf(entry) instanceof Integer owner && owner == uid) {
                    pids.add(pid);
                }
            }
        } catch (IOException unreadable) {
            Blast.log("PROCESS: could not walk /proc for orphaned children -",
                unreadable.getMessage());
        }
        return pids;
    }

    private static @Nullable Long pidOf(@NonNull Path entry) {
        String name = entry.getFileName().toString();
        for (int index = 0; index < name.length(); index++) {
            if (!Character.isDigit(name.charAt(index))) {
                return null;
            }
        }
        return name.isEmpty() ? null : Long.parseLong(name);
    }

    /** The REAL uid from /proc/&lt;pid&gt;/status, or null when the process is already gone. */
    private static @Nullable Integer realUidOf(@NonNull Path entry) {
        try {
            for (String line : Files.readAllLines(entry.resolve("status"))) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (IOException | RuntimeException gone) {
            return null;
        }
        return null;
    }

    /**
     * SIGTERM, then SIGKILL, then verify. Signalling goes through the JVM rather than a
     * sudo helper: the controller is the parent of nothing here, but a process owned by a
     * site uid is signalable by the daemon only when the daemon is root -- and when it is
     * not, the helper lane is the same one {@link ProcessGroupSupport} uses.
     *
     * @return true only when the process is really gone
     */
    private static boolean kill(long pid, int siteId, SystemUsers.@NonNull RunAsUser runAs) {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        if (handle == null || !handle.isAlive()) {
            return true;
        }
        Blast.log("PROCESS: reaping orphaned pid", pid, "of site", siteId,
            "(system user '" + runAs.name() + "') left behind by a previous controller");
        handle.destroy();
        ProcessGroupSupport.signalPid(runAs, pid, ProcessGroupSupport.Signal.TERM);
        if (awaitExit(handle, GRACEFUL_TERM_MS)) {
            return true;
        }
        handle.destroyForcibly();
        ProcessGroupSupport.signalPid(runAs, pid, ProcessGroupSupport.Signal.KILL);
        if (awaitExit(handle, FORCE_WAIT_MS)) {
            return true;
        }
        Blast.log("PROCESS: orphaned pid", pid, "of site", siteId, "SURVIVED both TERM and"
            + " KILL; its port claim is KEPT and nothing else is handed the port. Kill it by"
            + " hand (the controller may lack permission to signal uid " + runAs.uid() + ").");
        return false;
    }

    private static boolean awaitExit(@NonNull ProcessHandle handle, long millis) {
        try {
            handle.onExit().get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception timedOut) {
            if (timedOut instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return !handle.isAlive();
        }
    }
}
