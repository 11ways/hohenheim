package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.server.cms.HostAttention;
import be.elevenways.hohenheim.server.host.DiskSpace;
import be.elevenways.hohenheim.server.host.HostDisk;
import be.elevenways.hohenheim.server.host.HostFact;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.test.HardDeletes;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.test.support.ServedApp;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.FileStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A disk filling up, or a host's networks running out, is said on the dashboard before backups or deploys start
 * failing: the controller's own disks and each host's stored reading, one item per filesystem or host, each proven
 * both ways.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
class HostDiskAttentionTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final long GIB = 1024L * 1024 * 1024;

    private static final String ADVICE = "Backups fail once it runs out, so make room now.";

    @Test
    void aDiskFillingUpRaisesOneItemPerFilesystemAndOnlyThen() {
        Db.run(APP.database(), () -> {
            List<Integer> made = new ArrayList<>();
            try {
                // 1. A roomy controller disk says nothing, and it WAS measured: silence is a reading, not a skip.
                List<FileStore> asked = new ArrayList<>();
                assertThat(raised(false, store -> new DiskSpace(100 * GIB, 50 * GIB), asked))
                    .as("step 1: half a disk in use raises nothing").isEmpty();
                assertThat(asked).as("step 1: the controller's disks were measured").isNotEmpty();

                // 2. At 88% the controller disk warns, ONCE per filesystem however many of its stores share it.
                List<FileStore> measured = new ArrayList<>();
                List<String> warned = raised(false, store -> new DiskSpace(100 * GIB, 12 * GIB), measured);
                assertThat(measured).as("step 2: each controller filesystem is measured once").doesNotHaveDuplicates()
                    .isNotEmpty();
                assertThat(warned).as("step 2: one warning per filesystem, naming the controller's disk")
                    .hasSize(measured.size())
                    .allSatisfy(item -> assertThat(item).startsWith("warning The disk holding /")
                        .endsWith("88% in use, 12 GB free of 100 GB. " + ADVICE));

                // 3. At 97% it is an error, not a warning.
                assertThat(raised(false, store -> new DiskSpace(100 * GIB, 3 * GIB), new ArrayList<>()))
                    .as("step 3: 97% in use is about to break").isNotEmpty()
                    .allSatisfy(item -> assertThat(item).startsWith("error "));

                // 4. Hosts: only a FRESH reading over the threshold speaks; a stale one is no evidence and a roomy one
                //    is silence. Each item leads to its host.
                int full = host(made, "disk-full", HostModel.MODE_SSH, new DiskSpace(100 * GIB, 4 * GIB),
                    Now.instant());
                int warm = host(made, "disk-warm", HostModel.MODE_SSH, new DiskSpace(100 * GIB, 13 * GIB),
                    Now.instant());
                host(made, "disk-roomy", HostModel.MODE_SSH, new DiskSpace(100 * GIB, 60 * GIB), Now.instant());
                host(made, "disk-stale", HostModel.MODE_SSH, new DiskSpace(100 * GIB, 1 * GIB),
                    Now.instant().minus(Duration.ofDays(30)));
                List<String> hosts = raised(true, store -> new DiskSpace(100 * GIB, 50 * GIB), new ArrayList<>())
                    .stream().filter(item -> item.contains(" on disk-")).toList();
                assertThat(hosts).as("step 4: the full host errs, the warm one warns, nothing else")
                    .containsExactlyInAnyOrder(
                        "error Disk almost full on disk-full | 96% in use, 4 GB free of 100 GB. " + ADVICE
                            + " -> /admin/hosts/" + full + "/open",
                        "warning Disk almost full on disk-warm | 87% in use, 13 GB free of 100 GB. " + ADVICE
                            + " -> /admin/hosts/" + warm + "/open");

                // 5. Without host workloads the hosts' disks are not this installation's to watch.
                assertThat(raised(false, store -> new DiskSpace(100 * GIB, 50 * GIB), new ArrayList<>()))
                    .as("step 5: no host items when hosts carry no workloads").isEmpty();

                // 6. A controller disk that cannot be measured covers nothing: a LOCAL host on it still speaks from
                //    its own stored reading, and no controller item is guessed.
                int local = host(made, "disk-local", HostModel.MODE_LOCAL, new DiskSpace(100 * GIB, 2 * GIB),
                    Now.instant());
                assertThat(raised(true, store -> null, new ArrayList<>()))
                    .as("step 6: the unmeasured controller says nothing, the full local host still errs")
                    .filteredOn(item -> item.contains(" on disk-local") || item.contains("The disk holding"))
                    .containsExactly("error Disk almost full on disk-local | 98% in use, 2 GB free of 100 GB. "
                        + ADVICE + " -> /admin/hosts/" + local + "/open");
            } finally {
                for (int id : made) {
                    HardDeletes.byId(Models.get(HostModel.class), id);
                }
            }
        });
    }

    /** A host whose fresh reading leaves few free networks warns, one with none left errs, and only then. */
    @Test
    void aHostRunningOutOfNetworksRaisesOneItemAndOnlyThen() {
        Db.run(APP.database(), () -> {
            List<Integer> made = new ArrayList<>();
            try {
                // 1. Under the warning count warns, none left errs; roomy and stale readings say nothing.
                int low = host(made, "nets-low", HostModel.MODE_SSH, freeNetworks(3, Now.instant()));
                int none = host(made, "nets-none", HostModel.MODE_SSH, freeNetworks(0, Now.instant()));
                host(made, "nets-roomy", HostModel.MODE_SSH, freeNetworks(500, Now.instant()));
                host(made, "nets-stale", HostModel.MODE_SSH,
                    freeNetworks(3, Now.instant().minus(Duration.ofDays(30))));
                List<AttentionItem> items = new ArrayList<>();
                HostAttention.networksRunningOut(items);
                String advice = "; widen " + HohenheimSettings.Networks.ADDRESS_RANGE.getPath()
                    + " before none are left.";
                assertThat(words(items).stream().filter(item -> item.contains(" on nets-")).toList())
                    .as("step 1: the low host warns, the empty one errs, nothing else")
                    .containsExactlyInAnyOrder(
                        "warning Few app networks left on nets-low | 3 more networks fit in its address range. Every"
                            + " new app takes one, and one more for each database it links to" + advice
                            + " -> /admin/hosts/" + low + "/open",
                        "error No app networks left on nets-none | 0 more networks fit in its address range. Every"
                            + " new app takes one, and one more for each database it links to" + advice
                            + " -> /admin/hosts/" + none + "/open");
            } finally {
                for (int id : made) {
                    HardDeletes.byId(Models.get(HostModel.class), id);
                }
            }
        });
    }

    /** Each item as "severity title | detail -> target", the controller's disks measured by {@code measure}. */
    private static List<String> raised(boolean hosts, Function<FileStore, @Nullable DiskSpace> measure,
                                       List<FileStore> measured) {
        List<AttentionItem> items = new ArrayList<>();
        HostAttention.disksFillingUp(items, hosts, store -> {
            measured.add(store);
            return measure.apply(store);
        });
        return words(items);
    }

    /** Each item as "severity title | detail -> target", in English. */
    private static List<String> words(List<AttentionItem> items) {
        LocaleChain english = LocaleChain.ofTags("en");
        List<String> rendered = new ArrayList<>();
        for (AttentionItem item : items) {
            rendered.add(item.severity().key() + " " + item.title().resolve(english, Zenit.getMessageResolver())
                + " | " + (item.detail() == null ? "" : item.detail().resolve(english, Zenit.getMessageResolver()))
                + (item.target() == null ? "" : " -> " + item.target().toUrl()));
        }
        return rendered;
    }

    /** A host of {@code mode} carrying a stored disk reading taken at {@code at}. */
    private static int host(List<Integer> made, String name, String mode, DiskSpace space, Instant at) {
        return host(made, name, mode, row -> HostDisk.record(row, space, at));
    }

    /** A host of {@code mode} carrying the readings {@code reading} records on its row. */
    private static int host(List<Integer> made, String name, String mode, Consumer<Row> reading) {
        HostModel servers = Models.get(HostModel.class);
        Row row = servers.createEmptyRow();
        row.set(HostModel.NAME, name);
        row.set(HostModel.MODE, mode);
        reading.accept(row);
        servers.save(row);
        int id = row.get(HostModel.ID);
        made.add(id);
        return id;
    }

    /** @return what records a reading of {@code free} free networks, taken at {@code at} */
    private static Consumer<Row> freeNetworks(int free, Instant at) {
        return row -> HostPreflight.recordReadings(row, Map.of(HostFact.NETWORKS_FREE, free), at);
    }
}
