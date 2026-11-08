package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.OnboardingStage;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.host.HostStanding;
import be.elevenways.hohenheim.host.PreflightCheckView;
import be.elevenways.hohenheim.model.HostMode;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.PortAllocationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.DataDirectory;
import be.elevenways.hohenheim.server.database.DatabaseBackups;
import be.elevenways.hohenheim.server.docker.DockerHealth;
import be.elevenways.hohenheim.server.host.DiskSpace;
import be.elevenways.hohenheim.server.host.HostDisk;
import be.elevenways.hohenheim.server.host.VolumeBackends;
import be.elevenways.hohenheim.server.security.WorkloadNetworkPolicy;
import be.elevenways.hohenheim.server.task.VerifyWorkloadIsolation;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.task.TaskStatus;
import be.elevenways.zenit.common.task.orm.SystemTaskHistoryModel;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static be.elevenways.hohenheim.server.cms.AttentionItems.byHost;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;
import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;

/**
 * The host tier's attention items: the local daemon, host admission and the port ledger.
 *
 * Reads the boot probe's recorded answer and stored rows only; a host is never dialled per render.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class HostAttention {

    /** How long a claim may sit in {@code releasing} before it is an alarm: two hourly
     *  reconciler sweeps should have observed and freed it by then. */
    static final Duration RELEASING_STUCK_AFTER = Duration.ofHours(2);

    private HostAttention() {
    }

    /**
     * A docker-requiring node whose probe found no daemon: a red item, not silence.
     *
     * @param health the probe to read, injectable so the decision is testable without a daemon
     * @return the item, or null when the daemon answered or no role needed it
     */
    public static @Nullable AttentionItem dockerUnreachable(@NonNull DockerHealth health) {
        if (health.status() != DockerHealth.Status.UNREACHABLE) {
            return null;
        }
        return item(AttentionSeverity.ERROR, "cubes",
            HohenheimMicrocopy.ATTENTION_TITLE.of("docker_unreachable"),
            literal(health.problem()),
            CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG),
            HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_settings"));
    }

    /**
     * Every host that takes no new apps by its verdict ({@link HostVerdict}), and every host that holds apps back.
     *
     * AIDEV-NOTE: ONE item per host, from the verdict the Hosts list and the host page word, titled by its standing
     * ({@link HostStanding#attentionTitle}: "cannot run apps yet" before admission, "takes no new apps" after): a
     * waiting host says why it waits ("Never checked yet", its failed required checks) with Check and admit; an
     * ADMITTED host the gate refuses (a stale memory reading, a posture, a check that no longer passes) says the gate's
     * own words with the remedy that clears them (Check again re-measures; a posture or a trust decision opens the
     * host). Starfleet's local host was once refused by placement over a memory reading from 2026-08-29 while
     * nothing raised it. A cordoned host is a deliberate state and raises nothing until an app waits on it, and
     * an item is the ROOT of what its host holds back: it names how many apps wait for it and the dashboard folds
     * their own items under it. It states the checklist's admission stage, so while that step is open the step
     * presents it. Gated on the same roles that put the Hosts list in the panel, so the link always exists.
     */
    public static void hostsTakingNoApps(List<AttentionItem> items) {
        hostsTakingNoApps(items, AppHealth.heldBackByHost());
    }

    /** @param heldBack what each host holds back ({@link AppHealth#heldBackByHost}), read once for the tier */
    static void hostsTakingNoApps(List<AttentionItem> items, Map<Integer, AppHealth.HeldBack> heldBack) {
        for (Row server : Models.get(ServerModel.class).find().all()) {
            int id = server.get(ServerModel.ID);
            HostVerdict verdict = HostVerdict.of(server);
            AppHealth.HeldBack held = heldBack.get(id);
            AttentionSeverity raised = verdict.standing().severity();
            if (raised == null && held == null) {
                continue;
            }
            String name = server.get(ServerModel.NAME);
            // A host that raises nothing itself but holds apps back warns for them.
            items.add(item(raised != null ? raised : AttentionSeverity.WARNING, "server",
                verdict.standing().attentionTitle(name), raised != null ? verdict.reason() : held.reason(),
                CmsRoutes.open(ADMIN, HohenheimSlugs.SERVERS, id),
                verdict.remedyAction(name))
                .about(AttentionSubject.host(id), heldBackText(held))
                .forStage(OnboardingStage.ADMISSION));
        }
    }

    /**
     * Every disk filling up ({@link DiskFill}): each filesystem the controller writes its database, backups and dumps
     * to, measured here, and with {@code hosts} each host's instance-data disk from its stored fresh reading. ONE item
     * per filesystem: a local host whose volume root shares a controller disk is that disk's item.
     *
     * AIDEV-NOTE: Robbedoes' root disk filled to 89% under kept backup archives on 2026-10-30, the nightly dump ran
     * out of room and took the shared MongoDB container down with it, and nothing said so for weeks: the live hosts
     * have no mail transport, so the dashboard is the one channel an operator is sure to see.
     *
     * @param hosts whether host workloads run here, so hosts carry disks of their own
     */
    static void disksFillingUp(@NonNull List<AttentionItem> items, boolean hosts) {
        disksFillingUp(items, hosts, HostAttention::spaceOf);
    }

    /**
     * {@link #disksFillingUp(List, boolean)} with the controller's filesystems measured by {@code measure}, so a test
     * proves the projection without filling a disk.
     */
    public static void disksFillingUp(@NonNull List<AttentionItem> items, boolean hosts,
                                      @NonNull Function<FileStore, @Nullable DiskSpace> measure) {
        // Only a store that answered counts as reported: a local host on a store the controller could not measure
        // still speaks from its own stored reading.
        Set<FileStore> seen = new HashSet<>();
        Set<FileStore> measured = new HashSet<>();
        for (Supplier<String> setting : List.<Supplier<String>>of(() -> DataDirectory.path().toString(),
                () -> DatabaseBackups.backupRoot().toString())) {
            Path path = pathOf(setting);
            FileStore store = path == null ? null : storeOf(path);
            DiskSpace space = store == null || !seen.add(store) ? null : measure.apply(store);
            if (space == null) {
                continue;
            }
            measured.add(store);
            AttentionSeverity severity = space.fill().severity();
            if (severity != null) {
                items.add(item(severity, "hard-drive",
                    HohenheimMicrocopy.ATTENTION_TITLE.of("controller_disk").withArg("path", path.toString()),
                    diskDetail(space)));
            }
        }
        if (!hosts) {
            return;
        }
        Path volumeRoot = pathOf(VolumeBackends::volumeRoot);
        FileStore localVolumes = volumeRoot == null ? null : storeOf(volumeRoot);
        for (Row server : Models.get(ServerModel.class).find().all()) {
            if (HostMode.LOCAL.declaredBy(server) && localVolumes != null && measured.contains(localVolumes)) {
                continue;
            }
            HostDisk.Reading reading = HostDisk.storedOf(server);
            AttentionSeverity severity = reading == null || !reading.fresh() ? null : reading.space().fill().severity();
            if (severity == null) {
                continue;
            }
            int id = server.get(ServerModel.ID);
            items.add(item(severity, "hard-drive",
                HohenheimMicrocopy.ATTENTION_TITLE.of("host_disk").withArg("name", server.get(ServerModel.NAME)),
                diskDetail(reading.space()),
                CmsRoutes.open(ADMIN, HohenheimSlugs.SERVERS, id),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_host")));
        }
    }

    /** @return the setting's path made absolute, null when the stored value names no path */
    private static @Nullable Path pathOf(@NonNull Supplier<String> setting) {
        String value = setting.get();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException unusable) {
            return null;
        }
    }

    /** @return the filesystem holding {@code path}, or its nearest existing parent; null when none answers */
    private static @Nullable FileStore storeOf(@NonNull Path path) {
        for (Path at = path.toAbsolutePath(); at != null; at = at.getParent()) {
            if (Files.exists(at)) {
                try {
                    return Files.getFileStore(at);
                } catch (IOException unreadable) {
                    return null;
                }
            }
        }
        return null;
    }

    /** @return the filesystem's size and what an unprivileged writer may use, null when it cannot say */
    private static @Nullable DiskSpace spaceOf(@NonNull FileStore store) {
        try {
            long total = store.getTotalSpace();
            long free = Math.min(store.getUsableSpace(), total);
            return total > 0 ? new DiskSpace(total, free) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static @NonNull Microcopy diskDetail(@NonNull DiskSpace space) {
        return HohenheimMicrocopy.ATTENTION_DETAIL.of("disk_filling").withArg("percent", space.percentUsed())
            .withArg("free", space.freeBytes()).withArg("total", space.totalBytes());
    }

    /**
     * Every host where per-workload firewall rules are switched off while something there needs them: ONE item per
     * host, the root of the starts it refused and of the isolation sweep that cannot check it.
     *
     * AIDEV-NOTE: the dashboard once showed this one cause three times in the deploy lane's and the sweep's raw English
     * ("Check app isolation failed: per-app firewall rules are switched off there", "db-archive stopped after an
     * error: REFUSED to deploy '...-net': ... security.nftables_enabled is off ..."). A refused start records
     * {@code WORKLOAD_ISOLATION_REFUSED} (its cause's fact is HOST_ISOLATION), so the workload's item names this host
     * as its root ({@link WorkloadErrors#rootOf}) and folds under this one, which counts it; the sweep's failed run
     * folds here too ({@link #isolationRootOfSweep}). The switch ({@code security.nftables_enabled}) is one setting
     * for every host today, asked per host through {@link WorkloadNetworkPolicy#forServer} as the deploy lane and the
     * sweep ask it; switching it on also needs passwordless sudo for nft on each host, which is why the item names
     * the host. The setting's key and the sudo need are the technical note under the words.
     */
    static void isolationUnenforced(@NonNull List<AttentionItem> items) {
        Map<Integer, Integer> held = isolationHeld();
        for (int host : isolationRoots(held)) {
            String name = ServerModel.nameOf(host);
            int apps = held.getOrDefault(host, 0);
            items.add(item(apps > 0 ? AttentionSeverity.ERROR : AttentionSeverity.WARNING, "shield-halved",
                HohenheimMicrocopy.ATTENTION_TITLE.of("isolation_unenforced").withArg("host", name),
                HohenheimMicrocopy.ATTENTION_DETAIL.of("isolation_unenforced"),
                AttentionCollector.securitySettingsTarget(),
                    HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_settings"))
                .about(AttentionSubject.host(host),
                    apps == 0 ? null : HohenheimMicrocopy.ATTENTION_DETAIL.of("could_not_start_held")
                        .withArg("count", apps))
                .withNote(HohenheimMicrocopy.ATTENTION_DETAIL.of("isolation_unenforced_note").withArg("host", name)));
        }
    }

    /**
     * @return the host whose switched-off firewall rules are why the workload isolation sweep's newest run failed, so
     *         its failed-task item folds under that host's item; null when no such root is shown
     */
    static @Nullable AttentionSubject isolationRootOfSweep() {
        List<Integer> roots = isolationRoots(isolationHeld());
        return roots.isEmpty() ? null : AttentionSubject.host(roots.get(0));
    }

    /** @return whether per-workload network policy is enforced on this host, as its deploy lane asks it */
    static boolean enforcesIsolation(int serverId) {
        return WorkloadNetworkPolicy.forServer(ServerModel.nameOf(serverId)).isEnabled();
    }

    /** @return per host, how many errored workloads it refused to start for its switched-off firewall rules */
    private static @NonNull Map<Integer, Integer> isolationHeld() {
        Map<Integer, Integer> held = new LinkedHashMap<>();
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_ERROR))
                .all()) {
            AttentionSubject root = WorkloadErrors.rootOf(instance);
            if (root != null && ServerModel.MODEL_ID.equals(root.model())) {
                held.merge(root.id(), 1, Integer::sum);
            }
        }
        return held;
    }

    /**
     * @return the hosts without enforcement that hold back a start, or carry live workloads while the isolation
     *         sweep's newest run failed (it cannot check them)
     */
    private static @NonNull List<Integer> isolationRoots(@NonNull Map<Integer, Integer> held) {
        Set<Integer> unchecked = new HashSet<>();
        if (isolationSweepFailed()) {
            for (Row instance : Models.get(InstanceModel.class).find()
                    .where(InstanceModel.STATUS.in(InstanceModel.LIVE_GUEST_STATUSES))
                    .all()) {
                unchecked.add(ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)));
            }
        }
        List<Integer> roots = new ArrayList<>();
        for (Row server : Models.get(ServerModel.class).find().all()) {
            int id = server.get(ServerModel.ID);
            if (ServerModel.isIncus(server) || enforcesIsolation(id)) {
                continue;   // the Incus tier keeps workloads apart by its own sweep
            }
            if (held.containsKey(id) || unchecked.contains(id)) {
                roots.add(id);
            }
        }
        return roots;
    }

    /** @return whether the workload isolation sweep's newest run failed */
    private static boolean isolationSweepFailed() {
        if (Models.get(SystemTaskHistoryModel.MODEL_ID) == null) {
            return false;
        }
        List<Row> newest = Models.get(SystemTaskHistoryModel.class)
            .findRecentForType(VerifyWorkloadIsolation.ID.toString(), 1);
        return !newest.isEmpty()
            && TaskStatus.FAILED.name().equals(newest.get(0).get(SystemTaskHistoryModel.STATUS));
    }

    /** @return "2 apps wait for it", null when the host holds nothing back */
    private static @Nullable Microcopy heldBackText(AppHealth.@Nullable HeldBack held) {
        return held == null ? null : HohenheimMicrocopy.ATTENTION_DETAIL.of("held_back").withArg("count", held.apps());
    }

    /** @return the host's required preflight checks that did not pass, in words, in the stored report's order */
    static @NonNull List<Microcopy> failedRequiredChecks(@NonNull Row server) {
        List<Microcopy> failed = new ArrayList<>();
        for (PreflightCheckView check : ServerOverviewState.preflightReport(server).mustPass()) {
            if (check.notPassing()) {
                failed.add(check.label());
            }
        }
        return failed;
    }

    /**
     * Port claims stuck in {@code releasing} past the age threshold -- the ledger's
     * never-cleared alarm. A row lands there when a teardown could not verify itself
     * (or a host was removed); the reconciler deletes it once it OBSERVES the port
     * free, so one that lingers means the port is genuinely still bound by something
     * we no longer manage, or the host is unobservable. The flip time is updated_at:
     * releasing rows are never re-saved (the park is idempotent). The threshold is a
     * parameter only so a test can prove the projection without forging timestamps.
     */
    public static void stuckReleasingPorts(List<AttentionItem> items, Instant threshold) {
        List<Row> stuck = Models.get(PortAllocationModel.class).find()
            .where(PortAllocationModel.STATUS.eq(PortAllocationModel.STATUS_RELEASING))
            .all().stream()
            .filter(claim -> {
                Instant parkedAt = claim.get(PortAllocationModel.UPDATED_AT);
                return parkedAt != null && !parkedAt.isAfter(threshold);
            })
            .toList();
        byHost(stuck, claim -> serverNameOf(claim.get(PortAllocationModel.SERVER_ID)),
            claim -> claim.get(PortAllocationModel.PORT) + "/" + claim.get(PortAllocationModel.PROTOCOL))
            .forEach((server, ports) -> items.add(item(AttentionSeverity.WARNING, "ethernet",
            HohenheimMicrocopy.ATTENTION_TITLE.of("ports_releasing").withArg("server", server),
            HohenheimMicrocopy.ATTENTION_DETAIL.of("ports_releasing").withArg("count", ports.size())
                .withArg("hours", RELEASING_STUCK_AFTER.toHours()).withArg("ports", String.join(", ", ports)))));
    }

    // A releasing claim can outlive its servers row (host removal parks claims and
    // deletes nothing), so a dangling id must still render, not throw.
    private static String serverNameOf(@Nullable Integer serverId) {
        try {
            return ServerModel.nameOf(serverId);
        } catch (IllegalArgumentException gone) {
            return "removed host #" + serverId;
        }
    }
}
