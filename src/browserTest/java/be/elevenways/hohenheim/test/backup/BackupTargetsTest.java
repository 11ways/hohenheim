package be.elevenways.hohenheim.test.backup;

import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.zenit.common.orm.datasource.Datasources;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.backup.BackupTarget;
import be.elevenways.hohenheim.server.backup.BackupTargetKinds;
import be.elevenways.hohenheim.server.backup.FilesystemBackupTarget;
import be.elevenways.hohenheim.server.docker.ServerService;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.host.Sshd;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.orm.SqliteDatasource;
import be.elevenways.zenit.server.orm.migration.MigrationRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The target seam's contracts against real destinations: the filesystem target on a
 * real directory, and the SSH target against a real sshd on loopback (REAL transport,
 * SIMULATED failure domain -- one machine cannot prove two; LiveOffHostBackupTest is
 * where the second machine is real). Staging-then-commit is the load-bearing
 * behavior: nothing under the committed key until every byte landed.
 *
 * The SSH half doubles as the trust proof, because an ssh target now addresses a HOST
 * RECORD and inherits its ceremony: the same fixture is walked through unpinned,
 * pinned-but-unconfirmed, healthy, quarantined and host-key-changed, so every refusal
 * has the working case standing beside it.
 */
class BackupTargetsTest {

    @TempDir
    Path tmp;

    private static SqliteDatasource datasource;
    private static Path sandbox;
    private static Sshd sshd;

    private String previousDataPath;

    @BeforeAll
    static void startTheSshd() throws Exception {
        if (!Sshd.available()) {
            return;
        }
        File db = File.createTempFile("hohenheim-backup-targets-test", ".db");
        db.delete();
        db.deleteOnExit();
        datasource = new SqliteDatasource("jdbc:sqlite:" + db.getAbsolutePath());
        new MigrationRunner(datasource).migrate().requireSuccess();
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        Datasources.register(Datasources.DEFAULT, datasource);
        HohenheimTestRuntime.ensureBooted();
        sandbox = Files.createTempDirectory("hohenheim-backup-targets");
        sshd = Sshd.start(sandbox.resolve("sshd"));
    }

    @AfterAll
    static void stopTheSshd() {
        if (sshd != null) {
            sshd.stop();
            sshd = null;
        }
        deleteTree(sandbox);
    }

    /**
     * The ssh target materialises its pinned known_hosts under Hohenheim's OWN store, so
     * a test that builds one must own that store too.
     */
    @BeforeEach
    void isolateTheSshStore() throws Exception {
        HohenheimTestRuntime.ensureBooted();
        this.previousDataPath = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Storage.DATA_PATH);
        HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            this.tmp.resolve("controller-data").toString());
    }

    @AfterEach
    void restoreTheSshStore() {
        if (this.previousDataPath != null) {
            HohenheimSettings.VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
                this.previousDataPath);
        }
    }

    private Path payload(String content) throws IOException {
        Path file = tmp.resolve("payload.bin");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void filesystemTargetStoresVerifiesRetrievesAndDeletes() throws IOException {
        BackupTarget target = new FilesystemBackupTarget(tmp.resolve("store"));
        Path file = payload("artifact-bytes");

        // 1. Health + store + committed visibility.
        target.healthCheck();
        target.store("instance-1/a.hib", file);
        assertThat(target.exists("instance-1/a.hib"))
            .as("step 1: committed key exists after store").isTrue();
        assertThat(Files.exists(tmp.resolve("store/instance-1/a.hib.part")))
            .as("step 1: no staging debris survives a clean store").isFalse();

        // 2. The stored sha is computed from the TARGET's own bytes.
        String sha = target.storedSha256("instance-1/a.hib");
        assertThat(sha).as("step 2: sha256 shape").matches("[0-9a-f]{64}");

        // 3. Retrieve round-trips.
        Path back = tmp.resolve("back.bin");
        target.retrieve("instance-1/a.hib", back);
        assertThat(Files.readString(back)).as("step 3: retrieved bytes").isEqualTo("artifact-bytes");

        // 4. Delete removes committed AND staging names.
        target.delete("instance-1/a.hib");
        assertThat(target.exists("instance-1/a.hib")).as("step 4: gone after delete").isFalse();

        // 5. Traversal refusal: a key cannot escape the root.
        Throwable escape = catchThrowable(() -> target.store("../escape.hib", file));
        assertThat(escape).as("step 5: traversal keys are refused")
            .isInstanceOf(IOException.class).hasMessageContaining("escapes");
    }

    /**
     * One fixture, every trust state: the ssh target refuses an unpinned host, refuses
     * an unconfirmed pin, WORKS end to end once the ceremony is walked, refuses a
     * quarantined host, and fails closed when the real host key changes underneath it.
     */
    @Test
    void sshTargetInheritsItsHostRecordsPinnedIdentity() throws IOException {
        LiveLane.require(LiveLane.Need.SSH_TOOLS, sshd != null,
            "no OpenSSH binaries: the ssh target lane is not exercised here");
        Path remoteBase = tmp.resolve("remote");
        Path file = payload("ssh-artifact");

        Db.run(datasource, () -> {
            ServerService servers = new ServerService();
            servers.add("backup-host", sshd.target());
            ServerModel model = Models.get(ServerModel.class);
            Row host = model.findByName("backup-host");
            HostKeys.ensureIdentity(host);
            String publicKey = host.get(ServerModel.IDENTITY_PUBLIC_KEY);
            run(() -> {
                sshd.authorize(publicKey);
                return null;
            });

            Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
            targetRow.set(BackupTargetModel.NAME, "sshbox");
            targetRow.set(BackupTargetModel.KIND, "hohenheim:ssh");
            targetRow.set(BackupTargetModel.SETTINGS, Map.of(
                "server", ServerModel.registryKeyOf(host.get(ServerModel.ID)),
                "path", remoteBase.toString()));
            Models.get(BackupTargetModel.class).save(targetRow);

            // 1. An UNPINNED host cannot be a backup destination: the refusal lands at
            //    resolution, before an argv exists, let alone a connection.
            assertThat(catchThrowable(() -> BackupTargetKinds.targetOf(targetRow)))
                .as("step 1: an unpinned destination host is refused outright")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(keysOf(violations)).contains("host_key_unverified"));

            // 2. A pin alone is not enough -- a scan pins UNVERIFIED, and an operator
            //    who never compared digests has verified nothing.
            HostKeys.scanAndPin(host);
            Row pinned = model.findByName("backup-host");
            assertThat((String) pinned.get(ServerModel.HOST_KEY_FINGERPRINT))
                .as("step 2: the pin is the running sshd's real key")
                .isEqualTo(sshd.hostKeyFingerprint());
            assertThat(catchThrowable(() -> BackupTargetKinds.targetOf(targetRow)))
                .as("step 2: an unconfirmed pin is still refused")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(keysOf(violations)).contains("host_key_unverified"));

            // 3. THE POSITIVE ANCHOR: the operator confirms out of band, and the very
            //    same target now speaks the whole seam over the real ssh transport.
            HostKeys.confirm(pinned);
            BackupTarget target = BackupTargetKinds.targetOf(targetRow);
            run(() -> {
                target.healthCheck();
                return null;
            });
            assertThat(Files.isDirectory(remoteBase))
                .as("step 3: the health check created the remote base directory").isTrue();
            run(() -> {
                target.store("instance-9/b.hib", file);
                return null;
            });
            assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib")))
                .as("step 3: the committed artifact exists on the remote filesystem").isTrue();
            assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib.part")))
                .as("step 3: with no staging debris after a clean store").isFalse();
            assertThat(run(() -> target.storedSha256("instance-9/b.hib")))
                .as("step 3: and its sha comes from the REMOTE side")
                .isEqualTo(run(() -> BackupArchive.sha256Of(
                    remoteBase.resolve("instance-9/b.hib"))));
            Path back = tmp.resolve("ssh-back.bin");
            run(() -> {
                target.retrieve("instance-9/b.hib", back);
                return null;
            });
            assertThat(run(() -> Files.readString(back))).as("step 3: retrieved bytes")
                .isEqualTo("ssh-artifact");
            assertThat(run(() -> target.exists("instance-9/b.hib")))
                .as("step 3: and the target reports the artifact present").isTrue();

            // 4. The argv this lane runs on is the inventoried-host argv: strict checking
            //    against Hohenheim's OWN store and never the OS user's ~/.ssh. The target
            //    builds no argv of its own, so there is no second place to check.
            String flat = String.join(" ", HostKeys.sshArgv(pinned, List.of("true")));
            assertThat(flat)
                .as("step 4: strict, pinned, identity-only, and no ambient known_hosts")
                .contains("StrictHostKeyChecking=yes")
                .contains("GlobalKnownHostsFile=/dev/null")
                .contains("IdentitiesOnly=yes")
                .doesNotContain("accept-new")
                .doesNotContain(System.getProperty("user.home") + "/.ssh");

            // 5. A key is refused before any remote command runs.
            assertThat(catchThrowable(() -> target.store("../escape.hib", file)))
                .as("step 5: unsafe keys are refused")
                .isInstanceOf(IOException.class).hasMessageContaining("not a safe remote path");

            // 5b. A failing store commits NOTHING (unwritable destination directory).
            Path blocked = remoteBase.resolve("blocked");
            run(() -> {
                Files.createDirectories(blocked);
                return null;
            });
            assertThat(blocked.toFile().setWritable(false))
                .as("step 5b: the fixture could actually make the base unwritable").isTrue();
            try {
                Row blockedRow = Models.get(BackupTargetModel.class).createEmptyRow();
                blockedRow.set(BackupTargetModel.NAME, "sshbox-blocked");
                blockedRow.set(BackupTargetModel.KIND, "hohenheim:ssh");
                blockedRow.set(BackupTargetModel.SETTINGS, Map.of(
                    "server", ServerModel.registryKeyOf(host.get(ServerModel.ID)),
                    "path", blocked.resolve("sub").toString()));
                Models.get(BackupTargetModel.class).save(blockedRow);
                BackupTarget blockedTarget = BackupTargetKinds.targetOf(blockedRow);
                assertThat(catchThrowable(() -> blockedTarget.store("c.hib", file)))
                    .as("step 5b: a store into an unwritable base fails loudly")
                    .isInstanceOf(IOException.class);
                assertThat(Files.exists(blocked.resolve("sub/c.hib")))
                    .as("step 5b: and commits nothing").isFalse();
            } finally {
                blocked.toFile().setWritable(true);
            }

            // 6. QUARANTINE: a rescan that disagreed with the pin left the offered key as
            //    evidence. The destination is refused by NAME while the artifact it holds
            //    is untouched -- backups do not silently continue to a machine whose
            //    identity we can no longer reconcile.
            pinned.set(ServerModel.HOST_KEY_OFFERED, "ssh-ed25519 AAAAsomethingelse");
            model.save(pinned);
            assertThat(catchThrowable(() -> BackupTargetKinds.targetOf(targetRow)))
                .as("step 6: a quarantined destination is refused")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(keysOf(violations)).contains("host_quarantined"));
            assertThat(catchThrowable(() -> target.exists("instance-9/b.hib")))
                .as("step 6: and an already-resolved target refuses on its NEXT exchange")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(keysOf(violations)).contains("host_quarantined"));
            assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib")))
                .as("step 6: the artifact itself is untouched by the refusal").isTrue();

            // 7. Clearing the quarantine restores exactly the behaviour of step 3, so the
            //    refusal above measured the quarantine and nothing else.
            pinned.set(ServerModel.HOST_KEY_OFFERED, null);
            model.save(pinned);
            assertThat(run(() -> target.exists("instance-9/b.hib")))
                .as("step 7: the same fixture works again once the quarantine clears")
                .isTrue();

            // 8. The host key CHANGES underneath a confirmed pin. There is no ambient
            //    known_hosts to fall back to and no accept-new, so the exchange dies at
            //    verification -- and exists() RAISES rather than answering "absent",
            //    which is what a retention sweep would have acted on.
            sshd.regenerateHostKey();
            assertThat(catchThrowable(() -> target.exists("instance-9/b.hib")))
                .as("step 8: a changed host key fails closed instead of trusting on first use")
                .isInstanceOfSatisfying(IOException.class, failure ->
                    assertThat(failure.getMessage()).contains("Host key verification failed"));
            assertThat((String) model.findByName("backup-host").get(ServerModel.HOST_KEY))
                .as("step 8: and nothing re-pinned the new key")
                .isEqualTo((String) pinned.get(ServerModel.HOST_KEY));

            // 9. A non-ssh host is not a backup destination at all: the local daemon has
            //    no wire identity to pin, and pretending otherwise would sell a
            //    same-machine directory as an off-host copy.
            Row localTarget = Models.get(BackupTargetModel.class).createEmptyRow();
            localTarget.set(BackupTargetModel.NAME, "not-really-off-host");
            localTarget.set(BackupTargetModel.KIND, "hohenheim:ssh");
            localTarget.set(BackupTargetModel.SETTINGS, Map.of(
                "server", ServerModel.registryKeyOf(ServerModel.localServerId()),
                "path", remoteBase.toString()));
            Models.get(BackupTargetModel.class).save(localTarget);
            assertThat(catchThrowable(() -> BackupTargetKinds.targetOf(localTarget)))
                .as("step 9: the local host is refused as an ssh backup destination")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(keysOf(violations)).contains("host_not_ssh"));

            // 9b. And a target that named NO host says so, instead of folding a blank
            //     spelling to the local host and answering step 9's riddle.
            Row hostlessTarget = Models.get(BackupTargetModel.class).createEmptyRow();
            hostlessTarget.set(BackupTargetModel.NAME, "no-host-at-all");
            hostlessTarget.set(BackupTargetModel.KIND, "hohenheim:ssh");
            hostlessTarget.set(BackupTargetModel.SETTINGS, Map.of("path", remoteBase.toString()));
            Models.get(BackupTargetModel.class).save(hostlessTarget);
            assertThat(catchThrowable(() -> BackupTargetKinds.targetOf(hostlessTarget)))
                .as("step 9b: a target with no host named is refused as such")
                .isInstanceOfSatisfying(Violations.class, violations ->
                    assertThat(String.valueOf(violations.all().get(0).message()
                        .args().get("reason")))
                        .as("step 9b: naming the missing host, not the local one")
                        .contains("names no destination host"));
        });
    }

    // -- plumbing -------------------------------------------------------------

    private static List<String> keysOf(Violations violations) {
        List<String> keys = new ArrayList<>();
        for (Violation violation : violations.all()) {
            keys.add(violation.message().key());
        }
        return keys;
    }

    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    private static <T> T run(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.toList());
            for (int index = paths.size() - 1; index >= 0; index--) {
                Files.deleteIfExists(paths.get(index));
            }
        } catch (IOException ignored) {
            // A leftover temp directory is not worth failing a green run over.
        }
    }
}
