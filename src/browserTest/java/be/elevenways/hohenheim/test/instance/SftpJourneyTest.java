package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.HohenheimActivityAction;
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
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.docker.TestImages;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.live.LiveLane;
import be.elevenways.hohenheim.test.network.PrivateNetns;
import be.elevenways.protoblast.common.async.AsyncFailures;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
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
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    private static final Path SOCKET = Path.of(DockerClient.DEFAULT_SOCKET);
    private static final Duration WAIT = Duration.ofSeconds(20);
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
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.declareAccessModelsOnce();
        HohenheimTestRuntime.ensureBooted();
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
        SshClient client = SshClient.setUpDefaultClient();
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setServerKeyVerifier((session, address, key) -> true);
        client.start();
        try {
            Db.run(datasource, () -> {
                try {
                    journey(docker, client, events, handleRef, volumeRef);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } finally {
            client.stop();
            SecurityEvents.removeSink(sink);
            HohenheimSftp.stop();
            cleanup(docker, handleRef.get(), volumeRef.get());
        }
    }

    private static void journey(DockerClient docker, SshClient client, List<SecurityEvent> events,
                                AtomicReference<String> handleRef, AtomicReference<String> volumeRef)
        throws IOException {
        HostFixtures.admitLocal();
        int id = instanceRecord();
        String handle = ControllerScope.handle(ControllerScope.KIND_INSTANCE, id);
        handleRef.set(handle);
        volumeRef.set(handle + "-vol-data");
        new InstanceService().deploy(id);
        inContainer(docker, handle, "printf 'hello\\n' > /data/hello.txt && ln -sfn /etc /data/etcdir");

        int reader = ApiSupport.user("sftp-reader@live.test");
        int writer = ApiSupport.user("sftp-writer@live.test");
        int stranger = ApiSupport.user("sftp-stranger@live.test");
        grant(reader, id, HohenheimCapabilities.FILES_READ);
        grant(writer, id, HohenheimCapabilities.FILES_READ);
        grant(writer, id, HohenheimCapabilities.FILES_WRITE);
        String readerPassword = password(reader, false);
        String writerPassword = password(writer, true);
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
        assertThat(catchThrowable(() -> signIn(client, port, "sftp-writer@live.test." + id, "znit_wrong_password")))
            .as("step 2: a wrong password is refused").isNotNull();
        assertThat(events).as("step 2: it is reported as a failed password on the sftp lane")
            .anySatisfy(event -> {
                assertThat(event.type()).isEqualTo(SecurityEventTypes.SSH_PASSWORD_FAILED);
                assertThat(event.detail()).containsEntry("lane", "sftp");
            });
        assertThat(HohenheimSecurity.scorer().scoreOf(LOOPBACK))
            .as("step 2: and the threat scorer counted it against the address").isGreaterThan(scoreBefore);

        // 3. A valid password without a grant on the instance reads like the wrong one, as does an unknown id.
        assertThat(catchThrowable(() -> signIn(client, port, "sftp-stranger@live.test." + id, strangerPassword)))
            .as("step 3: an account without files.read on the instance cannot sign in").isNotNull();
        assertThat(catchThrowable(() -> signIn(client, port, "sftp-reader@live.test." + (id + 987_654),
            readerPassword))).as("step 3: an unknown instance id reads the same").isNotNull();
        RateLimitMiddleware.limiter().clear();

        // 4. files.read: the tree leads to the volume, lists and reads; a write is refused and lands nothing.
        try (Connection read = signIn(client, port, InstanceSftpRealm.username("sftp-reader@live.test", id),
            readerPassword)) {
            assertThat(names(read.sftp, "/")).as("step 4: the root leads to the volume").containsExactly("data");
            assertThat(names(read.sftp, "/data")).as("step 4: the volume lists its entries")
                .contains("hello.txt", "etcdir");
            assertThat(readText(read.sftp, "/data/hello.txt")).as("step 4: a file reads whole")
                .isEqualTo("hello\n");
            SftpException refused = sftpRefusal(catchThrowable(
                () -> writeBytes(read.sftp, "/data/sneaky.txt", "nope".getBytes(StandardCharsets.UTF_8))));
            assertThat(refused.getStatus()).as("step 4: a read grant cannot upload")
                .isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED);
            assertThat(refused.getMessage()).as("step 4: in the file manager's own words")
                .contains("You are not allowed to perform this action on this instance");
            assertThat(sftpRefusal(catchThrowable(() -> read.sftp.mkdir("/data/by-reader"))).getStatus())
                .as("step 4: nor create a directory").isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED);
            assertThat(inContainer(docker, handle, "ls /data"))
                .as("step 4: nothing the reader attempted landed").doesNotContain("sneaky", "by-reader");
        }

        // 5. files.write: an upload past the browser's cap spools and lands whole; it reads back byte for byte.
        byte[] big = new byte[(BROWSER_MAX_FILE_KB + 136) * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 + 7);
        }
        try (Connection write = signIn(client, port, InstanceSftpRealm.username("sftp-writer@live.test", id),
            writerPassword)) {
            writeBytes(write.sftp, "/data/big.bin", big);
            assertThat(inContainer(docker, handle, "wc -c < /data/big.bin").trim())
                .as("step 5: the container holds the whole upload, larger than the browser lane's cap")
                .isEqualTo(String.valueOf(big.length));
            assertThat(readBytes(write.sftp, "/data/big.bin")).as("step 5: and it downloads byte for byte")
                .isEqualTo(big);

            // 6. mkdir, rename, a mode change, delete and rmdir, each observed in the container.
            write.sftp.mkdir("/data/made");
            write.sftp.rename("/data/big.bin", "/data/made/big.bin");
            assertThat(inContainer(docker, handle, "ls /data/made")).as("step 6: the rename moved the file")
                .isEqualTo("big.bin\n");
            write.sftp.setStat("/data/hello.txt", new SftpClient.Attributes().perms(0600));
            assertThat(inContainer(docker, handle, "stat -c %a /data/hello.txt").trim())
                .as("step 6: a mode change lands").isEqualTo("600");
            write.sftp.remove("/data/made/big.bin");
            write.sftp.rmdir("/data/made");
            assertThat(inContainer(docker, handle, "test -e /data/made && echo yes || echo no"))
                .as("step 6: delete and rmdir removed both").isEqualTo("no\n");

            // 7. Every change wrote the browser's own activity verb, by the writer, with origin sftp.
            List<Row> rows = Models.get(ActivityModel.class).find()
                .where(ActivityModel.MODEL.eq(InstanceModel.MODEL_ID.toString()))
                .where(ActivityModel.RECORD_ID.eq(String.valueOf(id)))
                .where(ActivityModel.ORIGIN.eq(AccountabilityOrigin.SFTP.token()))
                .all();
            assertThat(rows.stream().map(row -> row.get(ActivityModel.ACTION)).toList())
                .as("step 7: one row per change, under the file manager's verbs")
                .containsExactlyInAnyOrder(
                    HohenheimActivityAction.FILES_UPLOAD.id().toString(),
                    HohenheimActivityAction.FILES_MKDIR.id().toString(),
                    HohenheimActivityAction.FILES_RENAME.id().toString(),
                    HohenheimActivityAction.FILES_DELETE.id().toString(),
                    HohenheimActivityAction.FILES_DELETE.id().toString());
            assertThat(rows).as("step 7: each names the writer as the actor")
                .allSatisfy(row -> assertThat(String.valueOf((Object) row.get(ActivityModel.ACTOR_LABEL)))
                    .contains("sftp-writer@live.test"));

            // 8. No path leaves the volume: a symlinked component, an outside path and a climb are all refused.
            SftpException throughLink = sftpRefusal(catchThrowable(() -> readText(write.sftp, "/data/etcdir/passwd")));
            assertThat(throughLink.getMessage()).as("step 8: a symlinked component is refused")
                .contains("That path is not inside this instance");
            assertThat(catchThrowable(() -> readText(write.sftp, "/etc/passwd")))
                .as("step 8: a path outside every volume is refused").isNotNull();
            assertThat(catchThrowable(() -> readText(write.sftp, "/data/../etc/passwd")))
                .as("step 8: a climb out of the volume is refused").isNotNull();
            assertThat(catchThrowable(() -> writeBytes(write.sftp, "/escaped.txt",
                "x".getBytes(StandardCharsets.UTF_8)))).as("step 8: so is a write above the volume").isNotNull();
            assertThat(inContainer(docker, handle, "test -e /escaped.txt && echo yes || echo no"))
                .as("step 8: and nothing landed outside it").isEqualTo("no\n");

            // 9. The grant is revoked mid-session: the very next request is refused.
            RecordGrants.revoke(GrantSubjectType.USER, writer, InstanceModel.MODEL_ID, id,
                HohenheimCapabilities.FILES_READ);
            RecordGrants.revoke(GrantSubjectType.USER, writer, InstanceModel.MODEL_ID, id,
                HohenheimCapabilities.FILES_WRITE);
            SftpException revoked = sftpRefusal(catchThrowable(() -> names(write.sftp, "/data")));
            assertThat(revoked.getStatus()).as("step 9: the next request after the revocation is refused")
                .isEqualTo(SftpConstants.SSH_FX_PERMISSION_DENIED);
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

    private static void grant(int userId, int instanceId, String capability) {
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId, capability, true);
    }

    /** An SFTP password minted the way the Files tab's card mints one: by its holder, narrowed to what it holds. */
    private static String password(int userId, boolean write) {
        AccessContext holder = AccessContext.detached(new UserPrincipal((long) userId, "user-" + userId));
        return ApiKeyService.create(holder, userId, "SFTP", InstanceSftpRealm.passwordScopes(write), null)
            .plaintext();
    }

    private static Connection signIn(SshClient client, int port, String username, String password) throws IOException {
        ClientSession session = client.connect(username, LOOPBACK, port).verify(WAIT).getSession();
        try {
            session.addPasswordIdentity(password);
            session.auth().verify(WAIT);
            return new Connection(session, SftpClientFactory.instance().createSftpClient(session));
        } catch (IOException | RuntimeException failure) {
            session.close(true);
            throw failure;
        }
    }

    /** @return the SFTP status a client call failed with; a listing reports it wrapped in an unchecked exception */
    private static SftpException sftpRefusal(Throwable thrown) {
        SftpException refusal = thrown == null ? null : AsyncFailures.first(thrown, SftpException.class);
        assertThat(refusal).as("the call failed with an SFTP status").isNotNull();
        return refusal;
    }

    private static List<String> names(SftpClient sftp, String path) throws IOException {
        List<String> names = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.readDir(path)) {
            if (!".".equals(entry.getFilename()) && !"..".equals(entry.getFilename())) {
                names.add(entry.getFilename());
            }
        }
        return names;
    }

    private static String readText(SftpClient sftp, String path) throws IOException {
        return new String(readBytes(sftp, path), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(SftpClient sftp, String path) throws IOException {
        try (InputStream in = sftp.read(path)) {
            return in.readAllBytes();
        }
    }

    private static void writeBytes(SftpClient sftp, String path, byte[] content) throws IOException {
        try (OutputStream out = sftp.write(path)) {
            out.write(content);
        }
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

    /** One signed-in client connection with its SFTP channel. */
    private record Connection(ClientSession session, SftpClient sftp) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                this.sftp.close();
            } catch (IOException ended) {
                // The server already ended the session.
            }
            this.session.close(true);
        }
    }
}
