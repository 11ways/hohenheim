package be.elevenways.hohenheim.test.backup;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.BackupTargetModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.server.backup.BackupArchive;
import be.elevenways.hohenheim.server.backup.BackupStorages;
import be.elevenways.hohenheim.server.backup.HohenheimStorages;
import be.elevenways.zenit.server.storage.StorageAdapter;
import be.elevenways.zenit.server.storage.StorageKinds;
import be.elevenways.zenit.storage.sftp.server.SftpStorageKind;
import be.elevenways.zenit.test.support.PrivateNetworkBootFixture;
import be.elevenways.hohenheim.server.docker.HostService;
import be.elevenways.hohenheim.server.host.HostKeys;
import be.elevenways.hohenheim.test.host.Sshd;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A backup target stored before storages, kind {@code hohenheim:ssh} naming a host record, read as the SFTP storage
 * it is now, against a REAL SSH DESTINATION: a real sshd on loopback serving SFTP (real transport, simulated failure
 * domain; LiveOffHostBackupTest is where the second machine is real).
 *
 * The journey doubles as the trust proof, because such a storage addresses a HOST RECORD and inherits its ceremony:
 * one fixture is walked through unpinned, pinned-but-unconfirmed, healthy, quarantined and host-key-changed, so
 * every refusal has the working case standing beside it.
 *
 * AIDEV-NOTE: @Tag("slow") is a CLASS property and this journey needs OpenSSH's sshd; add hermetic storage
 * assertions to DatabaseDumpTargetTest, never here.
 */
@Tag("slow") // live lane: needs a real daemon/host/image; runs via `zenit-dev test --all`
class BackupTargetsTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    @TempDir
    Path tmp;

    private static SqlDatasource datasource;
    private static Path sandbox;
    private static Sshd sshd;

    private String previousDataPath;

    @BeforeAll
    static void startTheSshd() throws Exception {
        if (!Sshd.available()) {
            return;
        }
        // ONE database per test class: the controller identity (and therefore every
        // daemon resource name) resolves through the CURRENT datasource, and a Db scope
        // is thread-local -- so a second, unregistered database would hand any
        // thread-hopping work a different controller's token than the records came from.
        datasource = APP.database();
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
        this.previousDataPath = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Storage.DATA_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
            this.tmp.resolve("controller-data").toString());
    }

    @AfterEach
    void restoreTheSshStore() {
        if (this.previousDataPath != null) {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH,
                this.previousDataPath);
        }
    }

    private Path payload(String content) throws IOException {
        Path file = tmp.resolve("payload.bin");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * One fixture, every trust state: the storage refuses an unpinned host, refuses an unconfirmed pin, WORKS end to
     * end over SFTP once the ceremony is walked, with the host record's account, key and pin and the stored base
     * path, refuses a quarantined host, and fails closed when the real host key changes underneath it.
     */
    @Test
    void anSshTargetIsTheSftpStorageOfItsHostRecordsPinnedIdentity() throws Exception {
        LiveLane.require(LiveLane.Need.SSH_TOOLS, sshd != null,
            "no OpenSSH binaries: the ssh target lane is not exercised here");
        // The base folder exists, as on every host an SSH target wrote to: the SFTP kind refuses a missing folder
        // rather than creating a mistyped one.
        Path remoteBase = Files.createDirectories(tmp.resolve("remote"));
        Path file = payload("ssh-artifact");

        try (PrivateNetworkBootFixture ignored = new PrivateNetworkBootFixture()) {
            // The sshd listens on loopback. The host record's admission decides where an SSH-era storage reaches, as
            // it decided where ssh went: no boot opt-in is needed, so this journey declares it off.
            PrivateNetworkBootFixture.declare(StorageKinds.PRIVATE_NETWORKS, false);
            Db.run(datasource, () -> {
                HostService servers = new HostService();
                servers.add("backup-host", sshd.target());
                HostModel model = Models.get(HostModel.class);
                Row host = model.findByName("backup-host");
                HostKeys.ensureIdentity(host);
                String publicKey = host.get(HostModel.IDENTITY_PUBLIC_KEY);
                run(() -> {
                    sshd.authorize(publicKey);
                    return null;
                });

                Row targetRow = Models.get(BackupTargetModel.class).createEmptyRow();
                targetRow.set(BackupTargetModel.NAME, "sshbox");
                targetRow.set(BackupTargetModel.KIND, "hohenheim:ssh");
                targetRow.set(BackupTargetModel.SETTINGS, Map.of(
                    "server", HostModel.registryKeyOf(host.get(HostModel.ID)),
                    "path", remoteBase + "/"));
                Models.get(BackupTargetModel.class).save(targetRow);

                // 1. An UNPINNED host cannot be a backup destination: refused at resolution, before any connection.
                assertThat(keysOf(catchThrowable(() -> BackupStorages.of(targetRow))))
                    .as("step 1: an unpinned destination host is refused outright").contains("host_key_unverified");

                // 2. A pin alone is not enough: a scan pins UNVERIFIED.
                HostKeys.scanAndPin(host);
                Row pinned = model.findByName("backup-host");
                assertThat((String) pinned.get(HostModel.HOST_KEY_FINGERPRINT))
                    .as("step 2: the pin is the running sshd's real key").isEqualTo(sshd.hostKeyFingerprint());
                assertThat(keysOf(catchThrowable(() -> BackupStorages.of(targetRow))))
                    .as("step 2: an unconfirmed pin is still refused").contains("host_key_unverified");

                // 3. THE POSITIVE ANCHOR: confirmed out of band, the row is the SFTP kind with the host record's
                //    address, account, client key and pin, and the base path as stored (its trailing slash dropped,
                //    as the SSH target joined keys), and it speaks the whole storage over SFTP.
                HostKeys.confirm(pinned);
                Map<String, Object> settings = HohenheimStorages.declarationOf(targetRow).settings();
                assertThat(HohenheimStorages.kindOf(targetRow)).as("step 3: the stored kind resolves to SFTP")
                    .isEqualTo(SftpStorageKind.ID);
                assertThat(settings).as("step 3: the host record's identity, the stored base path")
                    .containsEntry("folder", remoteBase.toString())
                    .containsEntry("sign_in", SftpStorageKind.SIGN_IN_KEY)
                    .containsEntry("host_key", sshd.hostKeyFingerprint())
                    .containsEntry("port", sshd.port());
                StorageAdapter target = BackupStorages.of(targetRow);
                run(() -> {
                    BackupStorages.requireUsable(target);
                    BackupStorages.store(target, "instance-9/b.hib", file);
                    return null;
                });
                assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib")))
                    .as("step 3: the committed artifact is where the SSH target put it").isTrue();
                assertThat(run(() -> BackupStorages.storedSha256(target, "instance-9/b.hib")))
                    .as("step 3: and its sha comes from the REMOTE side")
                    .isEqualTo(run(() -> BackupArchive.sha256Of(remoteBase.resolve("instance-9/b.hib"))));
                Path back = tmp.resolve("ssh-back.bin");
                run(() -> {
                    BackupStorages.retrieve(target, "instance-9/b.hib", back);
                    return null;
                });
                assertThat(run(() -> Files.readString(back))).as("step 3: retrieved bytes").isEqualTo("ssh-artifact");

                // 3b. Deleting a namespace's last artifact removes the folders it leaves empty there, never the base.
                run(() -> {
                    Files.createDirectories(remoteBase.resolve("instance-9/leftover"));
                    BackupStorages.delete(target, "instance-9/b.hib");
                    return null;
                });
                assertThat(Files.exists(remoteBase.resolve("instance-9")))
                    .as("step 3b: the emptied namespace is gone, its leftover folder with it").isFalse();
                assertThat(Files.isDirectory(remoteBase)).as("step 3b: the base stays").isTrue();

                // 4. QUARANTINE: a rescan that disagreed with the pin left the offered key as evidence; the storage
                //    opened before is released at once and resolving it again is refused by NAME.
                run(() -> {
                    BackupStorages.store(target, "instance-9/b.hib", file);
                    return null;
                });
                pinned.set(HostModel.HOST_KEY_OFFERED, "ssh-ed25519 AAAAsomethingelse");
                model.save(pinned);
                assertThat(keysOf(catchThrowable(() -> BackupStorages.of(targetRow))))
                    .as("step 4: a quarantined destination is refused").contains("host_quarantined");
                assertThat(catchThrowable(() -> BackupStorages.exists(target, "instance-9/b.hib")))
                    .as("step 4: and the storage in hand refuses its next exchange").isInstanceOf(IOException.class);
                assertThat(Files.exists(remoteBase.resolve("instance-9/b.hib")))
                    .as("step 4: the artifact itself is untouched by the refusal").isTrue();

                // 5. Clearing the quarantine restores step 3, so the refusal measured the quarantine alone.
                pinned.set(HostModel.HOST_KEY_OFFERED, null);
                model.save(pinned);
                assertThat(run(() -> BackupStorages.exists(BackupStorages.of(targetRow), "instance-9/b.hib")))
                    .as("step 5: the same fixture works again once the quarantine clears").isTrue();

                // 6. The host key CHANGES underneath a confirmed pin: the pinned SFTP check refuses, and exists()
                //    RAISES rather than answering "absent", which a retention sweep would have acted on.
                sshd.regenerateHostKey();
                assertThat(catchThrowable(() -> BackupStorages.exists(BackupStorages.of(targetRow),
                        "instance-9/b.hib")))
                    .as("step 6: a changed host key fails closed").isInstanceOfSatisfying(IOException.class,
                        failure -> assertThat(failure.getMessage()).contains("does not pin"));
                assertThat((String) model.findByName("backup-host").get(HostModel.HOST_KEY))
                    .as("step 6: and nothing re-pinned the new key").isEqualTo((String) pinned.get(HostModel.HOST_KEY));

                // 7. The local daemon is no SSH destination at all.
                Row localTarget = Models.get(BackupTargetModel.class).createEmptyRow();
                localTarget.set(BackupTargetModel.NAME, "not-really-off-host");
                localTarget.set(BackupTargetModel.KIND, "hohenheim:ssh");
                localTarget.set(BackupTargetModel.SETTINGS, Map.of(
                    "server", HostModel.registryKeyOf(HostModel.localHostId()),
                    "path", remoteBase.toString()));
                Models.get(BackupTargetModel.class).save(localTarget);
                assertThat(keysOf(catchThrowable(() -> BackupStorages.of(localTarget))))
                    .as("step 7: the local host is refused as an ssh backup destination").contains("host_not_ssh");
            });
        }
    }

    // -- plumbing -------------------------------------------------------------

    private static List<String> keysOf(Throwable refusal) {
        assertThat(refusal).as("a refusal").isInstanceOf(Violations.class);
        List<String> keys = new ArrayList<>();
        for (Violation violation : ((Violations) refusal).all()) {
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
