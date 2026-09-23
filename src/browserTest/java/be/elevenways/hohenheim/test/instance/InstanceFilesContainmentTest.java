package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceFileModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.files.InstanceFiles;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.FakeContainerFiles;
import be.elevenways.hohenheim.test.docker.TestTars;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The file manager's OWN checks, hermetically: the per-component symlink walk, the leaf
 * refusals, the size caps, the managed-config boundary (ancestors included) and the owner
 * check on every mutating verb -- over the real {@code InstanceFiles}, the real
 * {@code DockerInstanceRuntime} and the real {@code DockerClient}, against an in-memory
 * container filesystem ({@link FakeContainerFiles}).
 *
 * AIDEV-NOTE: these checks used to be proven ONLY by InstanceFilesLiveTest, which needs a
 * Docker socket and a private netns and therefore skips on most hosts -- a skipped proof of
 * a security boundary is no proof. The live test stays the proof of what the DAEMON does;
 * this one is the proof of what WE do, and it runs everywhere. The fake reproduces the one
 * daemon behaviour the walk exists for: an intermediate symlink is resolved and the leaf
 * reported as an ordinary file.
 */
class InstanceFilesContainmentTest {

    private static final int MAX_FILE_KB = 1;

    private static SqlDatasource datasource;
    private static Integer previousMaxFileKb;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        previousMaxFileKb = HohenheimSettings.VALUES.getValue(HohenheimSettings.Files.MAX_FILE_KB);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, MAX_FILE_KB);
    }

    @AfterAll
    static void tearDown() {
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, previousMaxFileKb);
        DockerClient.overrideLocalTransportForTest(null);
    }

    private static String keyOf(Throwable refused) {
        assertThat(refused).as("a refusal is a Violations").isInstanceOf(Violations.class);
        return ((Violations) refused).all().get(0).message().key();
    }

    @Test
    void everyContainmentManagedAndOwnershipRefusalHoldsAndWritesNothing() {
        Db.run(datasource, () -> {
            int id = instanceRecord();
            String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
            FakeContainerFiles daemon = new FakeContainerFiles(handle,
                OwnerLabels.of(InstanceModel.MODEL_ID, id))
                .directory("/data").directory("/data/sub").directory("/etc")
                .file("/data/hello.txt", "hello\n")
                .file("/etc/passwd", "root:x:0:0")
                .file("/etc/shadow", "root:SECRET-HASH")
                .symlink("/data/etcdir", "/etc")
                .symlink("/data/escape", "/etc/shadow");
            DockerClient.overrideLocalTransportForTest(() -> daemon);
            InstanceFiles files = new InstanceFiles();

            // 1. The volume lists, directories first, the symlinks AS symlinks.
            InstanceFiles.Listing listing = files.list(id, "/data");
            assertThat(listing.entries().stream().map(InstanceFiles.Entry::name).toList())
                .as("step 1: the listing is the volume's immediate children")
                .containsExactly("sub", "escape", "etcdir", "hello.txt");
            assertThat(files.readText(id, "/data/hello.txt"))
                .as("step 1: a regular file reads").isEqualTo("hello\n");

            // 2. A symlinked COMPONENT is refused by the walk -- and the daemon's own lstat of
            //    the full path reports an ordinary file, so a leaf-only check would pass it.
            assertThat(daemonSaysRegularFile(daemon, handle, "/data/etcdir/passwd"))
                .as("step 2: counterfactual -- the daemon reports the escaped leaf as a file")
                .isTrue();
            assertThat(keyOf(catchThrowable(() -> files.read(id, "/data/etcdir/passwd"))))
                .as("step 2: the component walk refuses the symlinked directory")
                .isEqualTo("files_path_refused");
            assertThat(keyOf(catchThrowable(() -> files.write(id, "/data/etcdir/passwd",
                    "x".getBytes(StandardCharsets.UTF_8)))))
                .as("step 2: and the write lane walks the same components")
                .isEqualTo("files_path_refused");
            assertThat(keyOf(catchThrowable(() -> files.read(id, "/data/escape"))))
                .as("step 2: a symlink LEAF is never followed").isEqualTo("files_not_a_file");

            // 3. The race the walk cannot close: the leaf is a file when stat-ed and a LINK
            //    when read. The archive answer is parsed, so the link is refused and not a
            //    byte of what it names comes back.
            daemon.answerArchive("/data/hello.txt",
                new TestTars().symlink("hello.txt", "/etc/shadow").build());
            Throwable raced = catchThrowable(() -> files.read(id, "/data/hello.txt"));
            assertThat(keyOf(raced)).as("step 3: a link swapped in after the walk is refused")
                .isEqualTo("files_failed");
            assertThat(raced.getMessage()).as("step 3: nothing of the target leaks")
                .doesNotContain("SECRET-HASH");

            // 4. Size caps, BEFORE the daemon is asked: an over-cap write sends nothing, and
            //    a file the stat says is over the cap is not transferred at all.
            int before = daemon.calls().size();
            assertThat(keyOf(catchThrowable(() -> files.write(id, "/data/big.bin",
                    new byte[MAX_FILE_KB * 1024 + 1]))))
                .as("step 4: an over-cap write is refused").isEqualTo("files_too_large");
            daemon.file("/data/huge.bin", new byte[MAX_FILE_KB * 1024 * 4]);
            assertThat(keyOf(catchThrowable(() -> files.read(id, "/data/huge.bin"))))
                .as("step 4: an over-cap read is refused").isEqualTo("files_too_large");
            assertThat(daemon.calls()).as("step 4: neither reached a mutating daemon call")
                .hasSize(before);

            // 5. The managed-config boundary covers the file, its ANCESTORS and mkdir.
            daemon.directory("/data/conf").file("/data/conf/app.conf", "managed");
            Row managed = Models.get(InstanceFileModel.class).createEmptyRow();
            managed.set(InstanceFileModel.INSTANCE_ID, id);
            managed.set(InstanceFileModel.CONTAINER_PATH, "/data/conf/app.conf");
            managed.set(InstanceFileModel.CONTENT, "managed");
            managed.set(InstanceFileModel.MODE, "0644");
            Models.get(InstanceFileModel.class).save(managed);
            before = daemon.calls().size();
            assertThat(keyOf(catchThrowable(() -> files.write(id, "/data/conf/app.conf",
                    "edit".getBytes(StandardCharsets.UTF_8)))))
                .as("step 5: the managed file refuses a write").isEqualTo("files_managed_config");
            assertThat(keyOf(catchThrowable(() -> files.delete(id, "/data/conf"))))
                .as("step 5: deleting its DIRECTORY would delete it too -- refused")
                .isEqualTo("files_managed_ancestor");
            assertThat(keyOf(catchThrowable(() -> files.rename(id, "/data/conf", "/data/moved"))))
                .as("step 5: renaming the directory would move it -- refused")
                .isEqualTo("files_managed_ancestor");
            assertThat(keyOf(catchThrowable(() -> files.makeDirectory(id, "/data/conf/app.conf"))))
                .as("step 5: mkdir at the managed path is refused").isEqualTo("files_managed_config");
            assertThat(daemon.calls()).as("step 5: no refused verb touched the container")
                .hasSize(before);
            assertThat(daemon.text("/data/conf/app.conf")).as("step 5: the file is intact")
                .isEqualTo("managed");
            files.write(id, "/data/conf/app.conf.bak", "sibling".getBytes(StandardCharsets.UTF_8));
            assertThat(daemon.text("/data/conf/app.conf.bak"))
                .as("step 5: a SIBLING whose name merely starts the same is not managed")
                .isEqualTo("sibling");

            // 6. A dash-leading name is a file like any other, and the ownership fix-up after
            //    the write re-owns the ENTRY (chown -h), never what it might point at.
            files.write(id, "/data/-rf", "dash".getBytes(StandardCharsets.UTF_8));
            assertThat(daemon.text("/data/-rf")).as("step 6: the dash-named file landed")
                .isEqualTo("dash");
            assertThat(daemon.calls()).as("step 6: its owner was fixed with chown -h")
                .contains("exec:chown-h /data/-rf");

            // 7. A same-named FOREIGN container refuses EVERY mutating verb, rename and
            //    delete included (they used to carry no owner check at all).
            Map<String, String> foreign = new LinkedHashMap<>(OwnerLabels.of(InstanceModel.MODEL_ID, id));
            foreign.put(OwnerLabels.CONTROLLER, "someoneelse");
            daemon.relabel(foreign);
            before = daemon.calls().size();
            for (String verb : List.of("rename", "delete", "mkdir", "write")) {
                Throwable refused = catchThrowable(() -> {
                    switch (verb) {
                        case "rename" -> files.rename(id, "/data/hello.txt", "/data/moved.txt");
                        case "delete" -> files.delete(id, "/data/hello.txt");
                        case "mkdir" -> files.makeDirectory(id, "/data/new");
                        default -> files.write(id, "/data/hello.txt",
                            "hijack".getBytes(StandardCharsets.UTF_8));
                    }
                });
                assertThat(keyOf(refused)).as("step 7: '" + verb + "' on a foreign container")
                    .isEqualTo("files_failed");
                assertThat(refused.getMessage()).as("step 7: named as not attributably ours")
                    .contains("not attributably ours");
            }
            assertThat(daemon.calls()).as("step 7: nothing ran inside the foreign container")
                .hasSize(before);
            assertThat(daemon.text("/data/hello.txt")).as("step 7: its file is untouched")
                .isEqualTo("hello\n");
        });
    }

    /** The daemon's own lstat of a path, asked directly -- the counterfactual oracle. */
    private static boolean daemonSaysRegularFile(FakeContainerFiles daemon, String handle,
                                                 String path) {
        try {
            return new DockerClient(daemon).statArchivePath(handle, path).isRegularFile();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static int instanceRecord() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", "alpine");
        settings.put("tag", "latest");
        settings.put("volumes", Map.of("data", "/data"));
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "files-containment");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }
}
