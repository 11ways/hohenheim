package be.elevenways.hohenheim.test.instance;

import org.junit.jupiter.api.extension.RegisterExtension;
import be.elevenways.zenit.test.support.ServedApp;
import be.elevenways.hohenheim.test.HohenheimServedApp;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.sftp.server.SftpSessionInfo;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.server.cms.AttentionCollector;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.files.HohenheimSftp;
import be.elevenways.hohenheim.server.files.InstanceSftpRealm;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import be.elevenways.hohenheim.server.security.HohenheimSecurity;
import be.elevenways.hohenheim.server.security.NftService;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.docker.TestImages;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.GrantService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.hohenheim.test.RecordedChanges;
import be.elevenways.hohenheim.test.SftpTestClient;
import be.elevenways.hohenheim.test.SftpTestClient.Connection;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.AccountabilityOrigin;
import be.elevenways.zenit.common.security.SecurityEventTypes;
import be.elevenways.zenit.common.setting.SettingDefinition;
import be.elevenways.zenit.server.http.RateLimitMiddleware;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import be.elevenways.zenit.server.security.SecurityEvent;
import be.elevenways.zenit.server.security.SecurityEventSink;
import be.elevenways.zenit.server.security.SecurityEvents;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static be.elevenways.hohenheim.test.SftpTestClient.names;
import static be.elevenways.hohenheim.test.SftpTestClient.readBytes;
import static be.elevenways.hohenheim.test.SftpTestClient.readText;
import static be.elevenways.hohenheim.test.SftpTestClient.refusal;
import static be.elevenways.hohenheim.test.SftpTestClient.writeBytes;

/**
 * Hohenheim's SFTP lane end to end: MINA's own SFTP client against the server the boot stage starts, over a real
 * Docker instance with a volume, signed in with SFTP passwords minted exactly as the Files tab's card mints them.
 *
 * Every step asserts against the CONTAINER (an in-container read is the oracle), never against what the client was
 * told, and every refusal is followed by proof that nothing landed.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@Tag("slow") // live lane: needs a real daemon and image; runs via `zenit-dev test --all` or a class filter
class SftpJourneyTest {

    @RegisterExtension
    static final ServedApp APP = HohenheimServedApp.app();

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final String LOOPBACK = "127.0.0.1";

    /** The browser lane's cap during this class: the upload below is larger, to prove SFTP spools past it. */
    private static final int BROWSER_MAX_FILE_KB = 64;

    private static SqlDatasource datasource;
    private static PrivateNetns netns;
    private static Path work;
    private static MessageResolver resolverBefore;
    private static final List<Runnable> restorers = new ArrayList<>();
    private static String dataPathBefore;

    @BeforeAll
    static void setUp() throws Exception {
        datasource = APP.database();
        netns = PrivateNetns.installEnforcing();
        work = Files.createTempDirectory("hohenheim-sftp");
        // A booted host words refusals through its shipped catalogs; this class reads those words back.
        resolverBefore = Zenit.getMessageResolver();
        Zenit.setMessageResolver(ShippedCatalogs.shared());
        remember(HohenheimSettings.Files.MAX_FILE_KB);
        remember(HohenheimSettings.Sftp.ENABLED);
        remember(HohenheimSettings.Sftp.PORT);
        remember(HohenheimSettings.Sftp.BIND_ADDRESS);
        dataPathBefore = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Files.MAX_FILE_KB, BROWSER_MAX_FILE_KB);
        RateLimitMiddleware.limiter().clear();
    }

    @AfterAll
    static void tearDown() throws IOException {
        HohenheimSftp.stop();
        restorers.forEach(Runnable::run);
        Zenit.setMessageResolver(resolverBefore);
        RateLimitMiddleware.limiter().clear();
        PrivateNetns.uninstall(netns);
        netns = null;
        try (var walk = Files.walk(work)) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * One journey: the boot stage starts the server; a wrong password is refused and scored; a read grant lists and
     * reads but cannot write; a write grant uploads past the browser's cap, downloads, renames, deletes and changes a
     * mode, each recorded with origin sftp; no path leaves the volume; a revoked grant refuses the very next request;
     * the SFTP port joins the SSH ban rule; and a server that cannot bind finds the operator.
     */
    @Test
    void anSftpClientReachesExactlyWhatTheFilesTabReaches() throws Exception {
        LiveLane.require(LiveLane.Need.DOCKER_SOCKET, Files.exists(SOCKET), "Docker socket not present");
        DockerClient docker = new DockerClient();
        LiveLane.requireImage(docker, TestImages.ALPINE);
        LiveLane.require(LiveLane.Need.NETNS, netns != null,
            "no private netns: the instance tier refuses to deploy unprotected");

        AtomicReference<String> handleRef = new AtomicReference<>();
        AtomicReference<String> volumeRef = new AtomicReference<>();
        List<SecurityEvent> events = new CopyOnWriteArrayList<>();
        SecurityEventSink sink = events::add;
        SecurityEvents.addSink(sink);
        SftpTestClient client = SftpTestClient.start();
        try {
            Db.run(datasource, () -> {
                try {
                    journey(docker, client, events, handleRef, volumeRef);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            client.close();
            SecurityEvents.removeSink(sink);
            HohenheimSftp.stop();
            cleanup(docker, handleRef.get(), volumeRef.get());
        }
    }

    private static void journey(DockerClient docker, SftpTestClient client, List<SecurityEvent> events,
                                AtomicReference<String> handleRef, AtomicReference<String> volumeRef)
        throws IOException {
        HostFixtures.admitLocal();
        int id = instanceRecord();
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
        handleRef.set(handle);
        volumeRef.set(handle + "-vol-data");
        new InstanceService().deploy(id);
        inContainer(docker, handle, "printf 'hello\\n' > /data/hello.txt && ln -sfn /etc /data/etcdir");

        UUID reader = ApiSupport.user("sftp-reader@live.test");
        UUID writer = ApiSupport.user("sftp-writer@live.test");
        UUID stranger = ApiSupport.user("sftp-stranger@live.test");
        // An operator holds every permission through the wildcard and no grant on this instance at all.
        UUID operator = ApiSupport.user("sftp-operator@live.test");
        GrantService.createDirectGrant(GrantSubjectType.USER, operator, "*", true);
        grant(reader, id, HohenheimCapabilities.FILES_READ);
        grant(writer, id, HohenheimCapabilities.FILES_READ);
        grant(writer, id, HohenheimCapabilities.FILES_WRITE);
        String readerPassword = password(reader, false);
        String writerPassword = password(writer, true);
        String operatorPassword = password(operator, true);
        String strangerPassword = ApiKeyService.create(stranger, "SFTP", InstanceSftpRealm.FILE_SCOPES, null)
            .plaintext();

        // 1. The boot stage starts the server from hohenheim.sftp.*, spooling under the data path.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.ENABLED, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.PORT, 0);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.BIND_ADDRESS, LOOPBACK);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, work.toString());
        HohenheimSftp.startIfEnabled();
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Storage.DATA_PATH, dataPathBefore);
        assertThat(HohenheimSftp.failure()).as("step 1: the enabled server started").isNull();
        assertThat(HohenheimSftp.server()).as("step 1: and is running").isNotNull();
        int port = HohenheimSftp.server().port();
        assertThat(work.resolve("sftp").resolve("spool")).as("step 1: its spool lives under the data path")
            .isDirectory();

        // 2. A wrong password is refused, reported as a failed SSH password on lane sftp, and scored.
        int scoreBefore = HohenheimSecurity.scorer().scoreOf(LOOPBACK);
        assertThat(catchThrowable(() -> client.signIn(port, "sftp-writer@live.test." + id, "znit_wrong_password")))
            .as("step 2: a wrong password is refused").isNotNull();
        assertThat(events).as("step 2: it is reported as a failed password on the sftp lane")
            .anySatisfy(event -> {
                assertThat(event.type()).isEqualTo(SecurityEventTypes.SSH_PASSWORD_FAILED);
                assertThat(event.detail()).containsEntry("lane", "sftp");
            });
        assertThat(HohenheimSecurity.scorer().scoreOf(LOOPBACK))
            .as("step 2: and the threat scorer counted it against the address").isGreaterThan(scoreBefore);

        // 3. A valid password without a grant on the instance reads like the wrong one, as does an unknown id.
        assertThat(catchThrowable(() -> client.signIn(port, "sftp-stranger@live.test." + id, strangerPassword)))
            .as("step 3: an account without files.read on the instance cannot sign in").isNotNull();
        assertThat(catchThrowable(() -> client.signIn(port, "sftp-reader@live.test." + (id + 987_654),
            readerPassword))).as("step 3: an unknown instance id reads the same").isNotNull();
        RateLimitMiddleware.limiter().clear();

        // 4. files.read: the tree leads to the volume, lists and reads; a write is refused and lands nothing.
        try (Connection read = client.signIn(port,
                InstanceSftpRealm.username("sftp-reader@live.test", String.valueOf(id)),
            readerPassword)) {
            assertThat(names(read.sftp(), "/")).as("step 4: the root leads to the volume").containsExactly("data");
            assertThat(names(read.sftp(), "/data")).as("step 4: the volume lists its entries")
                .contains("hello.txt", "etcdir");
            assertThat(readText(read.sftp(), "/data/hello.txt")).as("step 4: a file reads whole")
                .isEqualTo("hello\n");
            SftpException refused = refusal(catchThrowable(
                () -> writeBytes(read.sftp(), "/data/sneaky.txt", "nope".getBytes(StandardCharsets.UTF_8))));
            assertThat(refused.getStatus()).as("step 4: a read grant cannot upload")
                .isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED);
            assertThat(refused.getMessage()).as("step 4: in the file manager's own words")
                .contains("You are not allowed to perform this action on this app");
            assertThat(refusal(catchThrowable(() -> read.sftp().mkdir("/data/by-reader"))).getStatus())
                .as("step 4: nor create a directory").isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED);
            assertThat(inContainer(docker, handle, "ls /data"))
                .as("step 4: nothing the reader attempted landed").doesNotContain("sneaky", "by-reader");
        }

        // 4b. An operator's password carries the files of every app (and the gate), so it reaches this app's files
        //     without a grant on it, and nothing past them: the key holds no admin permission.
        assertThat(InstanceSftpRealm.passwordScopes(holder(operator), true))
            .as("step 4b: an operator's SFTP password carries the files of every app")
            .contains(HohenheimAccess.FILES_ALL.value());
        assertThat(InstanceSftpRealm.passwordScopes(holder(reader), false))
            .as("step 4b: a tenant's never does").doesNotContain(HohenheimAccess.FILES_ALL.value());
        try (Connection operating = client.signIn(port,
                InstanceSftpRealm.username("sftp-operator@live.test", String.valueOf(id)),
            operatorPassword)) {
            assertThat(names(operating.sftp(), "/data")).as("step 4b: the operator lists the app's volume")
                .contains("hello.txt");
            assertThat(readText(operating.sftp(), "/data/hello.txt")).as("step 4b: and reads its files")
                .isEqualTo("hello\n");
        }

        // 5. files.write: an upload past the browser's cap spools and lands whole; it reads back byte for byte.
        byte[] big = new byte[(BROWSER_MAX_FILE_KB + 136) * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 + 7);
        }
        try (Connection write = client.signIn(port,
                InstanceSftpRealm.username("sftp-writer@live.test", String.valueOf(id)),
            writerPassword)) {
            writeBytes(write.sftp(), "/data/big.bin", big);
            assertThat(inContainer(docker, handle, "wc -c < /data/big.bin").trim())
                .as("step 5: the container holds the whole upload, larger than the browser lane's cap")
                .isEqualTo(String.valueOf(big.length));
            assertThat(readBytes(write.sftp(), "/data/big.bin")).as("step 5: and it downloads byte for byte")
                .isEqualTo(big);

            // 6. mkdir, rename, a mode change, delete and rmdir, each observed in the container.
            write.sftp().mkdir("/data/made");
            write.sftp().rename("/data/big.bin", "/data/made/big.bin");
            assertThat(inContainer(docker, handle, "ls /data/made")).as("step 6: the rename moved the file")
                .isEqualTo("big.bin\n");
            write.sftp().setStat("/data/hello.txt", new SftpClient.Attributes().perms(0600));
            assertThat(inContainer(docker, handle, "stat -c %a /data/hello.txt").trim())
                .as("step 6: a mode change lands").isEqualTo("600");
            write.sftp().remove("/data/made/big.bin");
            write.sftp().rmdir("/data/made");
            assertThat(inContainer(docker, handle, "test -e /data/made && echo yes || echo no"))
                .as("step 6: delete and rmdir removed both").isEqualTo("no\n");

            // 7. Every change wrote the browser's own history verb, by the writer, with origin sftp.
            List<RecordedChanges.Recorded> rows = RecordedChanges.of(Models.get(InstanceModel.class), id).stream()
                .filter(recorded -> AccountabilityOrigin.SFTP.token().equals(recorded.origin())).toList();
            assertThat(rows.stream().flatMap(recorded -> recorded.facts().keySet().stream()).toList())
                .as("step 7: one fact per change, under the file manager's verbs")
                .containsExactlyInAnyOrder(
                    HohenheimActivityAction.FILES_UPLOAD.id().toString(),
                    HohenheimActivityAction.FILES_MKDIR.id().toString(),
                    HohenheimActivityAction.FILES_RENAME.id().toString(),
                    HohenheimActivityAction.FILES_DELETE.id().toString(),
                    HohenheimActivityAction.FILES_DELETE.id().toString());
            assertThat(rows).as("step 7: each names the writer as the actor")
                .allSatisfy(recorded -> assertThat(recorded.actor()).isEqualTo(writer.toString()));

            // 8. No path leaves the volume: a symlinked component, an outside path and a climb are all refused.
            SftpException throughLink = refusal(catchThrowable(() -> readText(write.sftp(), "/data/etcdir/passwd")));
            assertThat(throughLink.getMessage()).as("step 8: a symlinked component is refused")
                .contains("That path is not inside the app's volumes, which are mounted at");
            assertThat(catchThrowable(() -> readText(write.sftp(), "/etc/passwd")))
                .as("step 8: a path outside every volume is refused").isNotNull();
            assertThat(catchThrowable(() -> readText(write.sftp(), "/data/../etc/passwd")))
                .as("step 8: a climb out of the volume is refused").isNotNull();
            assertThat(catchThrowable(() -> writeBytes(write.sftp(), "/escaped.txt",
                "x".getBytes(StandardCharsets.UTF_8)))).as("step 8: so is a write above the volume").isNotNull();
            assertThat(inContainer(docker, handle, "test -e /escaped.txt && echo yes || echo no"))
                .as("step 8: and nothing landed outside it").isEqualTo("no\n");

            // 9. The grant is revoked mid-session: the very next request is refused.
            RecordGrants.revoke(GrantSubjectType.USER, writer, InstanceModel.MODEL_ID, id,
                HohenheimCapabilities.FILES_READ);
            RecordGrants.revoke(GrantSubjectType.USER, writer, InstanceModel.MODEL_ID, id,
                HohenheimCapabilities.FILES_WRITE);
            SftpException revoked = refusal(catchThrowable(() -> names(write.sftp(), "/data")));
            assertThat(revoked.getStatus()).as("step 9: the next request after the revocation is refused")
                .isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED);
        }

        // 9b. The Files tab lists a viewer's own open session and no one else's; End closes it at once.
        AccessContext readerAccess = AccessContext.detached(new UserPrincipal(reader, "user-" + reader));
        AccessContext writerAccess = AccessContext.detached(new UserPrincipal(writer, "user-" + writer));
        try (Connection read = client.signIn(port,
                InstanceSftpRealm.username("sftp-reader@live.test", String.valueOf(id)),
            readerPassword)) {
            List<SftpSessionInfo> own = HohenheimSftp.openOn(readerAccess, String.valueOf(id));
            assertThat(own).as("step 9b: the reader's session is listed for the reader").hasSize(1);
            assertThat(own.get(0).attribution()).as("step 9b: named by who signed in")
                .contains("sftp-reader@live.test");
            assertThat(HohenheimSftp.openOn(writerAccess, String.valueOf(id)))
                .as("step 9b: never for another person").isEmpty();
            assertThat(catchThrowable(() -> HohenheimSftp.end(writerAccess, String.valueOf(id), own.get(0).id())))
                .as("step 9b: nor can another person end it").isInstanceOf(Violations.class);
            HohenheimSftp.end(readerAccess, String.valueOf(id), own.get(0).id());
            assertThat(catchThrowable(() -> names(read.sftp(), "/data")))
                .as("step 9b: once ended, the connection answers nothing more").isNotNull();
        }

        // 10. While SFTP is on, its port joins the SSH ban rule; off, it leaves it.
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.PORT, 2022);
        assertThat(NftService.configuredSshPorts()).as("step 10: the SFTP port is banned with SSH")
            .contains(22, 2022);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.ENABLED, false);
        assertThat(NftService.configuredSshPorts()).as("step 10: and only while SFTP is on")
            .doesNotContain(2022);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.ENABLED, true);

        // 11. A server that cannot bind is no refused boot, but the dashboard names it.
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Sftp.PORT, taken.getLocalPort());
            HohenheimSftp.startIfEnabled();
            assertThat(HohenheimSftp.server()).as("step 11: the server could not bind").isNull();
            assertThat(HohenheimSftp.failure()).as("step 11: and kept why").isNotNull();
            List<AttentionItem> items = new ArrayList<>();
            AttentionCollector.sftpServer(items);
            assertThat(items).as("step 11: the attention band says SFTP is on but not running").singleElement()
                .satisfies(item -> assertThat(item.title().key()).isEqualTo("sftp_server"));
        }
        HohenheimSftp.stop();
        new InstanceService().destroy(id);
    }

    // -- plumbing -------------------------------------------------------------

    /** Puts the setting's value back after the class, whatever the journey set it to. */
    private static <T> void remember(SettingDefinition<T> setting) {
        T before = Zenit.SETTINGS_VALUES.getValue(setting);
        restorers.add(() -> Zenit.SETTINGS_VALUES.setValue(setting, before));
    }

    private static int instanceRecord() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("image", TestImages.ALPINE);
        settings.put("command", "sleep 600");
        settings.put("volumes", Map.of("data", "/data"));
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, "sftp-journey");
        row.set(InstanceModel.KIND, "hohenheim:docker_container");
        row.set(InstanceModel.SETTINGS, settings);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    private static void grant(UUID userId, int instanceId, String capability) {
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId, capability, true);
    }

    /** An SFTP password minted the way the Files tab's card mints one: by its holder, narrowed to what it holds. */
    private static String password(UUID userId, boolean write) {
        return ApiKeyService.create(holder(userId), userId, "SFTP", InstanceSftpRealm.passwordScopes(holder(userId),
            write), null).plaintext();
    }

    /** The account's own session, as the Files tab reads it. */
    private static AccessContext holder(UUID userId) {
        return AccessContext.detached(new UserPrincipal(userId, "user-" + userId));
    }

    /** Run a command INSIDE the container and return its stdout; the assertion oracle. */
    private static String inContainer(DockerClient docker, String handle, String script) {
        try {
            return docker.exec(handle, List.of("/bin/sh", "-c", script)).stdout();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void cleanup(DockerClient docker, String container, String volume) {
        if (container == null) {
            return;
        }
        try {
            docker.removeContainer(container, true);
        } catch (IOException ignored) {
            // already gone
        }
        try {
            docker.removeNetwork(WorkloadNetworks.networkName(container));
        } catch (IOException ignored) {
            // a deploy that never reached the network has none to remove
        }
        if (volume != null) {
            try {
                docker.removeVolume(volume, true);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
