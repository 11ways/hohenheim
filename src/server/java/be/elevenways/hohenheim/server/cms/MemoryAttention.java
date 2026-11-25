package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.protoblast.common.value.Percent;
import be.elevenways.hohenheim.server.host.HostMemory;
import be.elevenways.hohenheim.server.instance.InstanceCapacity;
import be.elevenways.hohenheim.server.instance.MemoryHealth;
import be.elevenways.hohenheim.server.instance.MemoryHistory;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static be.elevenways.hohenheim.HohenheimSlugs.ADMIN;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;

/**
 * The memory health checks as dashboard attention items: an app's use against what it reserves and may grow to, an
 * app the kernel killed for memory, and a host itself short of memory.
 *
 * Every projection reads remembered or stored facts (the sampler's {@link MemoryHistory}, the status reconciler's
 * {@code workload_killed_at}, the host's stored {@link HostMemory} reading), never a daemon, by the attention
 * surface's no-per-render-probe rule.
 *
 * AIDEV-NOTE: a release's memory is its application's: an item names and opens the application, where the amounts
 * are set. The rows behind the items are read in one query per kind of row, however many apps are sampled.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class MemoryAttention {

    private MemoryAttention() {
    }

    /** Every finding the sampled apps' histories hold ({@link MemoryHealth}), one item each. */
    public static void appMemory(@NonNull List<AttentionItem> items) {
        appMemory(items, MemoryHealth.Thresholds.current(), Now.instant());
    }

    /** {@link #appMemory(List)} judged by these thresholds at {@code now}. */
    public static void appMemory(@NonNull List<AttentionItem> items, MemoryHealth.@NonNull Thresholds thresholds,
                                 @NonNull Instant now) {
        Set<Integer> tracked = MemoryHistory.tracked();
        if (tracked.isEmpty()) {
            return;
        }
        List<Row> workloads = Models.get(InstanceModel.class).find()
            .where(InstanceModel.ID.in(List.copyOf(tracked)))
            .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_RUNNING)).all();
        Map<Integer, Row> apps = appsOf(workloads);
        for (Row workload : workloads) {
            long reservedMb = InstanceCapacity.reservedMbOf(workload);
            long maximumMb = InstanceCapacity.maximumMbOf(workload);
            Row app = apps.get(ApplicationReleases.linkOwnerOf(workload));
            for (MemoryHealth.Judgement judgement : MemoryHealth.judge(
                    MemoryHistory.trackOf(workload.get(InstanceModel.ID)), reservedMb, maximumMb, thresholds, now)) {
                String key = judgement.finding().key();
                long maximumBytes = ByteUnit.MEGABYTE.bytes(maximumMb);
                items.add(appItem(judgement.finding().severity(), key, app, HohenheimMicrocopy.ATTENTION_DETAIL.of(key)
                    .withArg("used", judgement.usedBytes())
                    .withArg("reserved", ByteUnit.MEGABYTE.bytes(reservedMb))
                    .withArg("maximum", maximumBytes)
                    .withArg("suggested", ByteUnit.MEGABYTE.bytes(judgement.suggestedMb()))
                    .withArg("percent", maximumBytes > 0 ? Percent.share(judgement.usedBytes(), maximumBytes, 0)
                        : Percent.of(0))
                    .withArg("window", thresholds.sustained())
                    .withArg("days", thresholds.farBelow().toDays())));
            }
        }
    }

    /**
     * Every app the kernel killed for memory inside its still-running container, as the status reconciler stored it:
     * an alert naming the app and the moment the kill was first seen.
     *
     * AIDEV-NOTE: a database's engine is that database's own item first ({@code database_out_of_memory}): the item
     * names the database as its cause, so the dashboard folds it under that one while it is shown.
     */
    public static void killedForMemory(@NonNull List<AttentionItem> items) {
        List<Row> killed = Models.get(InstanceModel.class).find()
            .where(InstanceModel.WORKLOAD_KILLED_AT.isNotNull()).all();
        if (killed.isEmpty()) {
            return;
        }
        Map<Integer, Row> apps = appsOf(killed);
        String databases = DatabaseModel.MODEL_ID.toString();
        for (Row workload : killed) {
            Row app = apps.get(ApplicationReleases.linkOwnerOf(workload));
            Integer owner = workload.get(InstanceModel.GENERATED_FOR_ID);
            AttentionSubject cause = owner != null && databases.equals(workload.get(InstanceModel.GENERATED_FOR_MODEL))
                ? AttentionSubject.database(owner) : null;
            items.add(appItem(AttentionSeverity.ERROR, "app_memory_killed", app,
                HohenheimMicrocopy.ATTENTION_DETAIL.of("app_memory_killed")
                    .withArg("at", workload.get(InstanceModel.WORKLOAD_KILLED_AT)))
                .causedBy(cause));
        }
    }

    /**
     * Every host whose fresh stored reading says it is short of memory: what is still available fell under its share,
     * or its swap filled past its share. One item per host; available memory is named first, being the nearer harm.
     */
    public static void hostsShortOfMemory(@NonNull List<AttentionItem> items) {
        int lowPercent = HohenheimSettings.positiveOrDefault(HohenheimSettings.Capacity.HOST_MEMORY_LOW_PERCENT);
        int swapPercent = HohenheimSettings.positiveOrDefault(HohenheimSettings.Capacity.HOST_SWAP_FULL_PERCENT);
        Instant freshAfter = Now.instant().minus(MemoryHistory.MAX_GAP);
        for (Row server : Models.get(HostModel.class).find().all()) {
            HostMemory.Reading reading = HostMemory.storedOf(server);
            if (reading == null || reading.measuredAt() == null || reading.measuredAt().isBefore(freshAfter)) {
                continue;
            }
            Microcopy detail = shortOfMemory(reading, lowPercent, swapPercent);
            if (detail == null) {
                continue;
            }
            items.add(item(AttentionSeverity.WARNING, "memory",
                HohenheimMicrocopy.ATTENTION_TITLE.of("host_memory_low").withArg("name", server.get(HostModel.NAME)),
                detail,
                CmsRoutes.open(ADMIN, HohenheimSlugs.HOSTS, server.get(HostModel.ID)),
                HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_host")));
        }
    }

    /** @return what says the host is short of memory, null when it is not */
    private static @Nullable Microcopy shortOfMemory(HostMemory.@NonNull Reading reading, int lowPercent,
                                                     int swapPercent) {
        if (reading.availableBytes() * 100 < reading.totalBytes() * lowPercent) {
            return HohenheimMicrocopy.ATTENTION_DETAIL.of("host_memory_available_low")
                .withArg("available", reading.availableBytes()).withArg("total", reading.totalBytes());
        }
        if (reading.swapTotalBytes() > 0 && reading.swapUsedBytes() * 100 >= reading.swapTotalBytes() * swapPercent) {
            return HohenheimMicrocopy.ATTENTION_DETAIL.of("host_swap_filling")
                .withArg("used", reading.swapUsedBytes()).withArg("total", reading.swapTotalBytes());
        }
        return null;
    }

    /** @return an item about one app, titled {@code key} with its name, that opens the app */
    private static @NonNull AttentionItem appItem(@NonNull AttentionSeverity severity, @NonNull String key,
                                                  @NonNull Row app, @NonNull Microcopy detail) {
        String name = app.get(InstanceModel.NAME);
        return item(severity, "memory", HohenheimMicrocopy.ATTENTION_TITLE.of(key).withArg("name", name), detail,
            InstanceParts.recordRoute(ADMIN, app, null),
            HohenheimMicrocopy.ATTENTION_ACTION.of("act_open_app").withArg("name", name));
    }

    /** @return each row's app by id, read in one query: a release's application, any other row itself */
    private static @NonNull Map<Integer, Row> appsOf(@NonNull List<Row> workloads) {
        Map<Integer, Row> apps = new HashMap<>();
        Set<Integer> owners = new HashSet<>();
        for (Row workload : workloads) {
            int owner = ApplicationReleases.linkOwnerOf(workload);
            if (Integer.valueOf(owner).equals(workload.get(InstanceModel.ID))) {
                apps.put(owner, workload);
            } else {
                owners.add(owner);
            }
        }
        if (!owners.isEmpty()) {
            for (Row app : Models.get(InstanceModel.class).find()
                    .where(InstanceModel.ID.in(List.copyOf(owners))).all()) {
                apps.put(app.get(InstanceModel.ID), app);
            }
        }
        for (Row workload : workloads) {
            apps.putIfAbsent(ApplicationReleases.linkOwnerOf(workload), workload);
        }
        return apps;
    }
}
