package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.cms.MemoryAttention;
import be.elevenways.hohenheim.server.host.HostMemory;
import be.elevenways.hohenheim.server.instance.MemoryHealth;
import be.elevenways.hohenheim.server.instance.MemoryHistory;
import be.elevenways.hohenheim.server.runtime.DockerStatsFeed;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The memory health checks, each driven by injected samples and stored readings and said on the dashboard in English
 * and Dutch: use that stays above the reservation (and a short peak that must not warn), use far below it over the
 * whole window, use near the maximum, a kill for memory, and a host short of memory.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class MemoryHealthAttentionTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final String PREFIX = "memcheck-";

    private static final long MIB = 1024L * 1024;

    private static final MemoryHealth.Thresholds THRESHOLDS = new MemoryHealth.Thresholds(
        Duration.ofMinutes(60), 85, 33, Duration.ofDays(7), 25);

    @Test
    void eachMemoryCheckSpeaksOnlyWhenItsStateLasts() {
        Db.run(APP.database(), () -> {
            List<Integer> apps = new ArrayList<>();
            List<Integer> hosts = new ArrayList<>();
            Instant now = Now.instant();
            MemoryHistory.clear();
            try {
                // 1. Use above the reservation for the whole hour warns, suggesting the peak plus its headroom
                //    (320 MB + 25% = 400 MB, up to a whole 64 MB: 448 MB), never past the maximum.
                MemoryHistory.Track above = track(now, Duration.ofMinutes(70), minute -> 300 + minute % 3 * 10);
                assertThat(MemoryHealth.judge(above, 256, 1024, THRESHOLDS, now))
                    .as("step 1: an hour above 256 MB reserved")
                    .containsExactly(new MemoryHealth.Judgement(
                        MemoryHealth.Finding.ABOVE_RESERVED, 320 * MIB, 448));
                assertThat(MemoryHealth.judge(above, 256, 352, THRESHOLDS, now))
                    .as("step 1: the suggestion stops at the maximum, which the use is near too")
                    .extracting(MemoryHealth.Judgement::suggestedMb).containsExactly(352L, 0L);

                // 2. A short peak says nothing, and neither does a history younger than the window.
                MemoryHistory.Track peak = track(now, Duration.ofMinutes(70), minute -> minute == 30 ? 320 : 200);
                assertThat(MemoryHealth.judge(peak, 256, 1024, THRESHOLDS, now))
                    .as("step 2: one sample above the reservation is a peak, not a state").isEmpty();
                assertThat(MemoryHealth.judge(track(now, Duration.ofMinutes(30), minute -> 320), 256, 1024,
                        THRESHOLDS, now))
                    .as("step 2: half an hour watched cannot say an hour lasted").isEmpty();
                assertThat(MemoryHealth.judge(new MemoryHistory.Track(
                        List.of(new MemoryHistory.Sample(now.minus(Duration.ofHours(1)), 500 * MIB))),
                        256, 512, THRESHOLDS, now))
                    .as("step 2: a history whose newest sample is an hour old says nothing").isEmpty();

                // 3. An app far below its reservation for the whole week is hinted down, on the dashboard; one
                //    watched for three days is not yet.
                int host = HostFixtures.admittedDockerHost(PREFIX + "apps", 8192);
                hosts.add(host);
                int idle = app(apps, "idle", host, 512, null);
                recordEvery(idle, now, Duration.ofDays(8), step -> 60 + step % 5 * 10);
                int young = app(apps, "young", host, 512, null);
                recordEvery(young, now, Duration.ofDays(3), step -> 60);
                assertThat(appItems(now, "en")).as("step 3: the idle app is hinted, the young one is not")
                    .containsExactly("info " + PREFIX + "idle reserves far more memory than it uses | Over the last 7 "
                        + "days it used at most 100 MB of the 512 MB it reserves. Lowering its reserved memory to "
                        + "128 MB frees room on its host. -> /admin/instances/" + idle + "/page/overview");

                // 4. Near its maximum warns on the newest sample alone.
                MemoryHistory.clear();
                int full = app(apps, "full", host, 512, null);
                MemoryHistory.record(full, new MemoryHistory.Sample(now.minus(Duration.ofMinutes(2)), 460 * MIB),
                    THRESHOLDS.longest());
                assertThat(appItems(now, "en")).as("step 4: 460 MB of a 512 MB maximum")
                    .containsExactly("warning " + PREFIX + "full is close to its maximum memory | It uses 460 MB, 90% "
                        + "of its 512 MB maximum. At the maximum the kernel kills it, so raise its maximum memory. -> "
                        + "/admin/instances/" + full + "/page/overview");
                assertThat(appItems(now, "nl")).as("step 4: in Dutch")
                    .containsExactly("warning " + PREFIX + "full zit dicht bij het maximale geheugen | Het gebruikt "
                        + "460 MB, 90% van het maximum van 512 MB. Bij het maximum stopt de kernel het, dus verhoog "
                        + "het maximale geheugen. -> /admin/instances/" + full + "/page/overview");

                // 4b. Use above a reservation below the maximum, the whole hour long, says to raise the reservation.
                int grown = app(apps, "grown", host, 1024, 256);
                recordEvery(grown, now, Duration.ofMinutes(70), step -> 300 + step % 3 * 10);
                assertThat(appItems(now, "en")).as("step 4b: an hour above 256 MB reserved, near no maximum")
                    .filteredOn(line -> line.contains(PREFIX + "grown")).containsExactly("warning " + PREFIX
                        + "grown uses more memory than it reserves | For the last 1h it used up to 320 MB, more than "
                        + "the 256 MB its host reserves for it. Raise its reserved memory to 448 MB. -> "
                        + "/admin/instances/" + grown + "/page/overview");

                // 5. A kill the status reconciler stored is an alert naming the app and the moment, on the dashboard.
                int killed = app(apps, "killed", host, 512, null);
                Instant killedAt = now.minus(Duration.ofMinutes(20));
                Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(killed))
                    .assign(InstanceModel.WORKLOAD_KILLED_AT, killedAt)
                    .bypassBehaviours()
                    .updateAll();
                List<AttentionItem> kills = new ArrayList<>();
                MemoryAttention.killedForMemory(kills);
                List<String> english = said(kills, "en");
                assertThat(english).as("step 5: one alert for the killed app")
                    .filteredOn(line -> line.contains(PREFIX)).singleElement().satisfies(line -> assertThat(line)
                        .startsWith("error " + PREFIX + "killed was killed for running out of memory | The kernel "
                            + "killed a process in it at ")
                        .endsWith(". It runs on without that process until it is restarted: raise its maximum "
                            + "memory, then restart it. -> /admin/instances/" + killed + "/page/overview")
                        .doesNotContain("{$"));
                assertThat(said(kills, "nl")).as("step 5: in Dutch")
                    .filteredOn(line -> line.contains(PREFIX)).singleElement().satisfies(line -> assertThat(line)
                        .startsWith("error " + PREFIX + "killed werd gestopt omdat het geheugen op was | De kernel "
                            + "stopte er om ").doesNotContain("{$"));
                assertThat(said(AttentionCollector.collect(), "en")).as("step 5: the dashboard collects it")
                    .anySatisfy(line -> assertThat(line).startsWith("error " + PREFIX + "killed was killed"));

                // 6. A host short of memory: little available, or swap filling; a roomy host and a stale reading
                //    say nothing.
                long gib = 1024 * MIB;
                int tight = measuredHost(hosts, "tight", 8 * gib, 512 * MIB, 0, 0, now);
                int swapping = measuredHost(hosts, "swapping", 8 * gib, 4 * gib, 2 * gib, gib, now);
                measuredHost(hosts, "roomy", 8 * gib, 6 * gib, 2 * gib, 2 * gib, now);
                measuredHost(hosts, "stale", 8 * gib, 100 * MIB, 0, 0, now.minus(Duration.ofHours(2)));
                assertThat(hostItems("en")).as("step 6: the tight and the swapping host warn, nothing else")
                    .containsExactlyInAnyOrder(
                        "warning " + PREFIX + "tight is short of memory | Only 512 MB of its 8 GB is still available. "
                            + "Move or shrink an app before the kernel starts killing them. -> /admin/hosts/" + tight
                            + "/open",
                        "warning " + PREFIX + "swapping is short of memory | 1 GB of its 2 GB swap is in use, which "
                            + "slows every app on it. Move or shrink an app. -> /admin/hosts/" + swapping + "/open");
                assertThat(hostItems("nl")).as("step 6: in Dutch")
                    .contains("warning " + PREFIX + "tight komt geheugen tekort | Nog maar 512 MB van de 8 GB is "
                        + "beschikbaar. Verplaats of verklein een app voordat de kernel apps begint te stoppen. -> "
                        + "/admin/hosts/" + tight + "/open");

                // 7. The readings themselves: the kernel's meminfo, and Docker's working set without page cache.
                assertThat(HostMemory.parse("MemTotal:       16384 kB\nMemAvailable:    4096 kB\n"
                        + "SwapTotal:        2048 kB\nSwapFree:          512 kB\n"))
                    .as("step 7: meminfo in kB read as bytes")
                    .isEqualTo(new HostMemory.Reading(16384 * 1024L, 4096 * 1024L, 2048 * 1024L, 512 * 1024L, null));
                assertThat(HostMemory.parse("MemTotal: 16384 kB\n")).as("step 7: a missing line reads nothing")
                    .isNull();
                assertThat(DockerStatsFeed.workingSetOf(Map.of("usage", 300 * MIB,
                        "stats", Map.of("inactive_file", 100 * MIB))))
                    .as("step 7: cgroup v2 usage less its inactive file cache").isEqualTo(200 * MIB);
                assertThat(DockerStatsFeed.workingSetOf(Map.of("usage", 300 * MIB,
                        "stats", Map.of("total_inactive_file", 50 * MIB, "inactive_file", 10 * MIB))))
                    .as("step 7: cgroup v1 names the cache total_inactive_file").isEqualTo(250 * MIB);
                assertThat(DockerStatsFeed.workingSetOf(Map.of("usage", 300 * MIB)))
                    .as("step 7: no cache figure leaves the usage").isEqualTo(300 * MIB);
            } finally {
                MemoryHistory.clear();
                for (int id : apps) {
                    HardDeletes.byId(Models.get(InstanceModel.class), id);
                }
                for (int id : hosts) {
                    HardDeletes.byId(Models.get(HostModel.class), id);
                }
            }
        });
    }

    /** A history watched for {@code watched}, one sample every five minutes of {@code mb.of(minute)} MB. */
    private static MemoryHistory.Track track(Instant now, Duration watched, MbAt mb) {
        List<MemoryHistory.Sample> samples = new ArrayList<>();
        for (long minute = watched.toMinutes(); minute >= 0; minute -= 5) {
            samples.add(new MemoryHistory.Sample(now.minus(Duration.ofMinutes(minute)), mb.of(minute) * MIB));
        }
        return new MemoryHistory.Track(samples);
    }

    /** Records into the history what the sampler would have: a sample every sampler interval over {@code watched}. */
    private static void recordEvery(int instanceId, Instant now, Duration watched, MbAt mb) {
        long index = 0;
        for (Instant at = now.minus(watched); !at.isAfter(now); at = at.plus(MemoryHistory.SAMPLE_INTERVAL)) {
            MemoryHistory.record(instanceId, new MemoryHistory.Sample(at, mb.of(index++) * MIB), THRESHOLDS.longest());
        }
    }

    @FunctionalInterface
    private interface MbAt {
        long of(long step);
    }

    /** A running Docker container app of this memory limit, as the reconciler would have confirmed it. */
    private static int app(List<Integer> made, String name, int serverId, int limitMb, Integer reservedMb) {
        Row row = TestWorkloads.dockerContainer(PREFIX + name, serverId, limitMb, reservedMb);
        Models.get(InstanceModel.class).save(row);
        int id = row.get(InstanceModel.ID);
        made.add(id);
        Models.get(InstanceModel.class).find().where(InstanceModel.ID.eq(id))
            .assign(InstanceModel.STATUS, InstanceModel.STATUS_RUNNING)
            .bypassBehaviours()
            .updateAll();
        return id;
    }

    /** A host whose shell reaches no machine (no sampler sweep re-measures it), carrying a stored memory reading. */
    private static int measuredHost(List<Integer> made, String name, long total, long available, long swapTotal,
                                    long swapFree, Instant at) {
        HostModel servers = Models.get(HostModel.class);
        Row row = servers.createEmptyRow();
        row.set(HostModel.NAME, PREFIX + name);
        row.set(HostModel.MODE, HostModel.MODE_SSH);
        row.set(HostModel.CAPABILITIES, Map.of("mem_total", total));
        HostMemory.record(row, new HostMemory.Reading(total, available, swapTotal, swapFree, null), at);
        servers.save(row);
        int id = row.get(HostModel.ID);
        made.add(id);
        return id;
    }

    private static List<String> appItems(Instant now, String language) {
        List<AttentionItem> items = new ArrayList<>();
        MemoryAttention.appMemory(items, THRESHOLDS, now);
        return said(items, language);
    }

    private static List<String> hostItems(String language) {
        List<AttentionItem> items = new ArrayList<>();
        MemoryAttention.hostsShortOfMemory(items);
        return said(items, language).stream()
            .filter(line -> line.contains(PREFIX) && !line.contains(PREFIX + "apps")).toList();
    }

    /** Each item as "severity title | detail -> target" in {@code language}. */
    private static List<String> said(List<AttentionItem> items, String language) {
        LocaleChain reader = LocaleChain.ofTags(language);
        List<String> lines = new ArrayList<>();
        for (AttentionItem item : items) {
            lines.add(item.severity().key() + " " + item.title().resolve(reader, Zenit.getMessageResolver()) + " | "
                + (item.detail() == null ? "" : item.detail().resolve(reader, Zenit.getMessageResolver()))
                + (item.target() == null ? "" : " -> " + item.target().toUrl()));
        }
        return lines;
    }
}
