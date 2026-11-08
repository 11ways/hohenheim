package be.elevenways.hohenheim.test.host;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.cms.HostAttention;
import be.elevenways.hohenheim.server.host.DiskSpace;
import be.elevenways.hohenheim.server.host.HostDisk;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A disk filling up is said on the dashboard before backups start failing: the controller's own disks and each host's
 * stored reading, one item per filesystem, each proven both ways.
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
                int full = host(made, "disk-full", ServerModel.MODE_SSH, new DiskSpace(100 * GIB, 4 * GIB),
                    Now.instant());
                int warm = host(made, "disk-warm", ServerModel.MODE_SSH, new DiskSpace(100 * GIB, 13 * GIB),
                    Now.instant());
                host(made, "disk-roomy", ServerModel.MODE_SSH, new DiskSpace(100 * GIB, 60 * GIB), Now.instant());
                host(made, "disk-stale", ServerModel.MODE_SSH, new DiskSpace(100 * GIB, 1 * GIB),
                    Now.instant().minus(Duration.ofDays(30)));
                List<String> hosts = raised(true, store -> new DiskSpace(100 * GIB, 50 * GIB), new ArrayList<>())
                    .stream().filter(item -> item.contains(" on disk-")).toList();
                assertThat(hosts).as("step 4: the full host errs, the warm one warns, nothing else")
                    .containsExactlyInAnyOrder(
                        "error Disk almost full on disk-full | 96% in use, 4 GB free of 100 GB. " + ADVICE
                            + " -> /admin/servers/" + full + "/open",
                        "warning Disk almost full on disk-warm | 87% in use, 13 GB free of 100 GB. " + ADVICE
                            + " -> /admin/servers/" + warm + "/open");

                // 5. Without host workloads the hosts' disks are not this installation's to watch.
                assertThat(raised(false, store -> new DiskSpace(100 * GIB, 50 * GIB), new ArrayList<>()))
                    .as("step 5: no host items when hosts carry no workloads").isEmpty();

                // 6. A controller disk that cannot be measured covers nothing: a LOCAL host on it still speaks from
                //    its own stored reading, and no controller item is guessed.
                int local = host(made, "disk-local", ServerModel.MODE_LOCAL, new DiskSpace(100 * GIB, 2 * GIB),
                    Now.instant());
                assertThat(raised(true, store -> null, new ArrayList<>()))
                    .as("step 6: the unmeasured controller says nothing, the full local host still errs")
                    .filteredOn(item -> item.contains(" on disk-local") || item.contains("The disk holding"))
                    .containsExactly("error Disk almost full on disk-local | 98% in use, 2 GB free of 100 GB. "
                        + ADVICE + " -> /admin/servers/" + local + "/open");
            } finally {
                for (int id : made) {
                    HardDeletes.byId(Models.get(ServerModel.class), id);
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
        ServerModel servers = Models.get(ServerModel.class);
        Row row = servers.createEmptyRow();
        row.set(ServerModel.NAME, name);
        row.set(ServerModel.MODE, mode);
        HostDisk.record(row, space, at);
        servers.save(row);
        int id = row.get(ServerModel.ID);
        made.add(id);
        return id;
    }
}
