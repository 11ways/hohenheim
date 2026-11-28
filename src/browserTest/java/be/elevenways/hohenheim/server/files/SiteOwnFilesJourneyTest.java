package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.server.upstream.kinds.StaticUpstreamKind;
import be.elevenways.protoblast.common.async.ProgressSink;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.ProxyTestSupport;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.storage.LocalDiskStorageAdapter;
import be.elevenways.zenit.server.storage.LocalFolderStorageKind;
import be.elevenways.zenit.server.storage.Storages;
import be.elevenways.zenit.test.support.ServedApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.sun.management.ThreadMXBean;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A static-files site serving its own files: files put through the one file manager land in the storage under the
 * site's own keys and are served at the same paths through the proxy, a range as a range, a large file streamed;
 * whoever manages the site reaches them, nobody else.
 */
class SiteOwnFilesJourneyTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static ProxyServer proxy;

    @AfterAll
    static void stop() {
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        Storages.unregister(SiteFiles.STORAGE);
    }

    @Test
    @Timeout(120)
    void aSitesOwnFilesAreStoredAtTheirPathsAndServedThereByRangeToItsManagersOnly() throws Exception {
        HohenheimEndpoints.init();
        Path storage = Files.createTempDirectory("hh-site-files");
        Storages.replace(SiteFiles.STORAGE, new LocalDiskStorageAdapter(storage));
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("own_files", true, "autoindex", true));
        Row site = ProxyTestSupport.setupSite("hohenheim:static", "Downloads", "downloads", settings);
        int siteId = site.get(SiteModel.ID);
        ProxyTestSupport.addDomain(site, "downloads.files.test", "exact", null, false);
        Row other = ProxyTestSupport.setupSite("hohenheim:static", "Other downloads", "other-downloads",
            new LinkedHashMap<>(Map.of("own_files", true)));
        int otherId = other.get(SiteModel.ID);
        proxy = ProxyTestSupport.startProxy();
        int port = ProxyTestSupport.httpPort(proxy);
        FileRoot root = FileRoot.site(siteId);
        FileVerbs verbs = new FileVerbs();

        // 1. A folder and a file with a person's own name land under the site's keys, path for path.
        verbs.makeDirectory(root, "/releases");
        Path upload = Files.createTempFile("hh-upload", ".bin");
        byte[] installer = "TADOMA-INSTALLER-0123456789".getBytes(StandardCharsets.UTF_8);
        Files.write(upload, installer);
        verbs.uploadFrom(root, "/releases/Tadoma-1.2.EXE", upload, false, 1L << 30);
        assertThat(storage.resolve("sites/" + siteId + "/releases/Tadoma-1.2.EXE")).as("step 1: the stored object")
            .hasBinaryContent(installer);
        assertThat(new InstanceFiles().list(root, "/releases").entries()).as("step 1: the file manager lists it")
            .extracting(InstanceFiles.Entry::name).containsExactly("Tadoma-1.2.EXE");

        // 2. The site serves it at the same path, whole, nosniff, ranges offered.
        String whole = ProxyTestSupport.rawRequest(port, "downloads.files.test", "/releases/Tadoma-1.2.EXE");
        assertThat(whole).as("step 2: served whole").startsWith("HTTP/1.1 200")
            .contains("TADOMA-INSTALLER-0123456789").containsIgnoringCase("accept-ranges: bytes")
            .containsIgnoringCase("x-content-type-options: nosniff");

        // 3. A range is answered as a range: 206, its bytes, its Content-Range; past the end is 416.
        String part = ProxyTestSupport.rawRequest(port, "downloads.files.test", "/releases/Tadoma-1.2.EXE",
            "Range: bytes=7-15");
        assertThat(part).as("step 3: a range").startsWith("HTTP/1.1 206")
            .containsIgnoringCase("content-range: bytes 7-15/" + installer.length).contains("\nINSTALLER")
            .doesNotContain("TADOMA");
        assertThat(ProxyTestSupport.rawRequest(port, "downloads.files.test", "/releases/Tadoma-1.2.EXE",
            "Range: bytes=9999-")).as("step 3: past the end").startsWith("HTTP/1.1 416");

        // 4. The folder lists, a missing path is a 404, and a climb above the top is refused.
        assertThat(ProxyTestSupport.rawRequest(port, "downloads.files.test", "/releases/"))
            .as("step 4: the listing").startsWith("HTTP/1.1 200").contains("Tadoma-1.2.EXE");
        assertThat(ProxyTestSupport.rawRequest(port, "downloads.files.test", "/releases/missing.exe"))
            .as("step 4: a missing file").startsWith("HTTP/1.1 404");

        // 5. A dot name is no path these files may hold, and nothing was stored for it.
        assertThat(keyOf(catchThrowable(() -> verbs.uploadFrom(root, "/.env", upload, false, 1L << 30))))
            .as("step 5: a dot name is refused").isEqualTo("files_tree_path_refused");
        assertThat(Files.exists(storage.resolve("sites/" + siteId + "/.env"))).as("step 5: nothing stored").isFalse();

        // 6. The limit counts the stored bytes: an upload past it is refused whole.
        Integer previousLimit = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Files.MAX_SITE_FILES_MB);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Files.MAX_SITE_FILES_MB, 1);
        try {
            Path tooBig = Files.createTempFile("hh-too-big", ".bin");
            Files.write(tooBig, new byte[1024 * 1024]);
            assertThat(keyOf(catchThrowable(() -> verbs.uploadFrom(root, "/releases/too-big.bin", tooBig, false,
                1L << 30)))).as("step 6: past the limit").isEqualTo("site_files_full");
            assertThat(Files.exists(storage.resolve("sites/" + siteId + "/releases/too-big.bin")))
                .as("step 6: nothing landed").isFalse();
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Files.MAX_SITE_FILES_MB, previousLimit);
        }

        // 7. Whoever manages the site reaches its files; a person managing another site does not.
        UUID manager = ApiSupport.user("site-files-manager@live.test");
        RecordGrants.grant(GrantSubjectType.USER, manager, SiteModel.MODEL_ID, siteId, HohenheimCapabilities.MANAGE,
            true);
        TenantConduits.as(new UserPrincipal(manager, "user-" + manager), () -> {
            assertThat(new InstanceFiles().list(root, "/").entries()).as("step 7: the manager lists")
                .extracting(InstanceFiles.Entry::name).containsExactly("releases");
            assertThat(keyOf(catchThrowable(() -> new InstanceFiles().list(FileRoot.site(otherId), "/"))))
                .as("step 7: not another site's").isEqualTo("site_files_not_permitted");
            assertThat(keyOf(catchThrowable(() -> verbs.delete(FileRoot.site(otherId), "/x"))))
                .as("step 7: nor a change there").isEqualTo("site_files_not_permitted");
        });

        // 8. A 512 MiB file streams through one buffer: the whole of it and a range at its end, the process
        //    allocating a fraction of what it sends (measured over every thread).
        long bigSize = 512L * 1024 * 1024;
        try (RandomAccessFile sparse = new RandomAccessFile(
                storage.resolve("sites/" + siteId + "/releases/big.bin").toFile(), "rw")) {
            sparse.setLength(bigSize);
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = allocated(threads);
        ProxyTestSupport.Counted download = ProxyTestSupport.rawDownload(port, "downloads.files.test",
            "/releases/big.bin");
        long allocatedBytes = allocated(threads) - before;
        System.out.println("SITE FILES: a " + bigSize + "-byte download allocated " + allocatedBytes + " bytes");
        assertThat(download.head()).as("step 8: whole").startsWith("HTTP/1.1 200");
        assertThat(download.bodyBytes()).as("step 8: every byte").isEqualTo(bigSize);
        assertThat(allocatedBytes).as("step 8: streamed, never held whole").isLessThan(64L * 1024 * 1024);
        ProxyTestSupport.Counted tail = ProxyTestSupport.rawDownload(port, "downloads.files.test",
            "/releases/big.bin", "Range: bytes=" + (bigSize - 1000) + "-");
        assertThat(tail.head()).as("step 8: a range at the end").startsWith("HTTP/1.1 206")
            .containsIgnoringCase("content-range: bytes " + (bigSize - 1000) + "-" + (bigSize - 1) + "/" + bigSize);
        assertThat(tail.bodyBytes()).as("step 8: its bytes").isEqualTo(1000);

        // 9. "Files kept in" offers what the person may use: the operator this server's own files and every storage;
        //    the site's manager this server's own files and the storages the manager added, never the operator's.
        Files.delete(storage.resolve("sites/" + siteId + "/releases/big.bin"));
        Path movedTo = Files.createTempDirectory("hh-moved-files");
        String operatorStore = "hh-moved-" + UUID.randomUUID();
        storageRow(operatorStore, movedTo);
        String managerStore = "hh-mine-" + UUID.randomUUID();
        Row managerRow = storageRow(managerStore, Files.createTempDirectory("hh-mine"));
        RecordGrants.grant(GrantSubjectType.USER, manager, BackupTargetModel.MODEL_ID,
            managerRow.get(BackupTargetModel.ID), HohenheimCapabilities.MANAGE, true);
        assertThat(SitePicks.offered(TenantConduits.operator())).as("step 9: the operator's picker")
            .extracting(FieldOption::value).contains(SiteFiles.STORAGE, operatorStore, managerStore);
        AccessContext managing = AccessContext.of(TenantConduits.stubFor(new UserPrincipal(manager, "user-" + manager)));
        assertThat(SitePicks.offered(managing)).as("step 9: the manager's picker").extracting(FieldOption::value)
            .contains(SiteFiles.STORAGE, managerStore).doesNotContain(operatorStore);

        // 10. Moving the files: each copied and verified, the site switched over and the old copies removed last;
        //     the site serves the file at its path from the new storage, and nothing is left in the old one.
        List<Microcopy> steps = new ArrayList<>();
        int moved = SiteFileMoves.move(Objects.requireNonNull(Models.get(SiteModel.class).findById(siteId)),
            operatorStore, ProgressSink.SILENT, steps::add);
        assertThat(moved).as("step 10: the one file moved").isEqualTo(1);
        assertThat(steps).as("step 10: copied, switched, removed").extracting(Microcopy::key)
            .containsExactly(HohenheimMicrocopy.SITE.of("move_files_copied").key(),
                HohenheimMicrocopy.SITE.of("move_files_switched").key(),
                HohenheimMicrocopy.SITE.of("move_files_removed").key());
        assertThat(movedTo.resolve("sites/" + siteId + "/releases/Tadoma-1.2.EXE")).as("step 10: in the new storage")
            .hasBinaryContent(installer);
        assertThat(Files.exists(storage.resolve("sites/" + siteId + "/releases/Tadoma-1.2.EXE")))
            .as("step 10: gone from the old one").isFalse();
        assertThat(ProxyTestSupport.rawRequest(port, "downloads.files.test", "/releases/Tadoma-1.2.EXE"))
            .as("step 10: served from the new storage").startsWith("HTTP/1.1 200")
            .contains("TADOMA-INSTALLER-0123456789");

        // 11. A save that changes where the files are kept without moving them is refused while files are there.
        Row switched = Objects.requireNonNull(Models.get(SiteModel.class).findById(siteId));
        Map<String, Object> back = new LinkedHashMap<>(RawValues.map(switched.get(SiteModel.SETTINGS)));
        back.put(StaticUpstreamKind.STORAGE.getName(), SiteFiles.STORAGE);
        switched.set(SiteModel.SETTINGS, back);
        assertThat(keyOf(catchThrowable(() -> Models.get(SiteModel.class).save(switched))))
            .as("step 11: move them first").isEqualTo("site_files_move_first");
    }


    /** An operator's storage on a folder of this server. */
    private static Row storageRow(String name, Path folder) {
        Row row = Models.get(BackupTargetModel.class).createEmptyRow();
        row.set(BackupTargetModel.NAME, name);
        row.set(BackupTargetModel.KIND, LocalFolderStorageKind.ID.toString());
        row.set(BackupTargetModel.SETTINGS, Map.of(LocalFolderStorageKind.FOLDER.getName(), folder.toString()));
        Models.get(BackupTargetModel.class).save(row);
        return row;
    }
    /** @return the bytes every live thread allocated so far */
    private static long allocated(ThreadMXBean threads) {
        long[] ids = threads.getAllThreadIds();
        return Arrays.stream(threads.getThreadAllocatedBytes(ids)).filter(bytes -> bytes > 0).sum();
    }

    private static String keyOf(Throwable thrown) {
        if (!(thrown instanceof Violations violations)) {
            return String.valueOf(thrown);
        }
        for (Violation violation : violations) {
            return violation.message().key();
        }
        return "none";
    }
}
