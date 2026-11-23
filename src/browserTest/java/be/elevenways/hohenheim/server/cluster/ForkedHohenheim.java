package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.cms.LinkedServerParts;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.test.support.MigratedDatabase;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.OutboundTlsStub;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A second, real Hohenheim in a child JVM on this JVM's classpath, for the journeys of two linked servers: its own
 * SQLite file and keyring, HTTP on a free port, every role off, and its outbound calls to the other server's host
 * name routed to that server's port through a TLS relay of its own.
 *
 * AIDEV-NOTE: the child stays up until {@link #close()}, says when it is ready and on which port, and reads its
 * commands from stdin: stdin ending (this JVM gone, or closing it) stops its runtime and ends it, so a child never
 * outlives a dead parent. Commands run its REAL entry points (the operations, the probe), never a stub. The gap this
 * fills belongs in zenit-test-support beside RuntimeStopProbe: a long-lived forked server with a command channel.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class ForkedHohenheim implements AutoCloseable {

    /** Every line the child answers starts with this. */
    private static final String MARK = "FORKED_HOHENHEIM ";

    /** The heap a whole Hohenheim runtime with every role off boots in. */
    private static final long HEAP = 512L * 1024 * 1024;

    /** How long a cold child may take to boot; far above what one takes, so a failure names the boot. */
    private static final Duration BOOT_LIMIT = Duration.ofMinutes(3);

    private static final Duration ANSWER_LIMIT = Duration.ofSeconds(60);

    private final RunningProcess process;
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private final Deque<String> tail = new ArrayDeque<>();
    private final int port;
    private final String token;
    private final Duration bootTime;

    private ForkedHohenheim(@NonNull RunningProcess process, @NonNull Path log) throws Exception {
        this.process = process;
        long started = System.nanoTime();
        Thread.ofVirtual().name("forked-hohenheim-output").start(() -> this.read(log));
        String ready = this.next(BOOT_LIMIT);
        this.bootTime = Duration.ofNanos(System.nanoTime() - started);
        String[] parts = ready.split(" ");
        if (parts.length != 3 || !"ready".equals(parts[0])) {
            throw new IllegalStateException("the forked Hohenheim did not boot: " + ready + "\n" + this.tail());
        }
        this.port = Integer.parseInt(parts[1]);
        this.token = parts[2];
    }

    /**
     * Boots a child Hohenheim.
     *
     * @param directory    its working directory, holding its keyring: every child of one journey shares it
     * @param database     its SQLite file, migrated from the template when new
     * @param name         its own server name
     * @param masterHost   the host name its calls to the other server use
     * @param masterPort   the other server's plain HTTP port
     * @param freshIdentity whether it mints a controller identity of its own on this database
     */
    static @NonNull ForkedHohenheim start(@NonNull Path directory, @NonNull Path database, @NonNull String name,
                                          @NonNull String masterHost, int masterPort, boolean freshIdentity)
            throws Exception {
        RunningProcess process = Subprocess.ofJavaMain(Child.class, database.toString(), name, masterHost,
                String.valueOf(masterPort), String.valueOf(freshIdentity))
            .maxHeap(HEAP)
            .directory(directory)
            .environment("JANEWAY_DISABLED", "1")
            .mergeStderr()
            .streamStdout()
            .stdinPipe()
            .start();
        try {
            return new ForkedHohenheim(process, directory.resolve(name + ".log"));
        } catch (Exception | Error failed) {
            process.stopTree();
            throw failed;
        }
    }

    /** @return the port its HTTP server listens on */
    int port() {
        return this.port;
    }

    /** @return its controller identity */
    @NonNull String token() {
        return this.token;
    }

    /** @return how long it took from the fork to ready */
    @NonNull Duration bootTime() {
        return this.bootTime;
    }

    /** @return what the child answered one command with, after the command's word */
    @NonNull String ask(@NonNull String command) throws Exception {
        OutputStream stdin = this.process.stdin();
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        return this.next(ANSWER_LIMIT);
    }

    /** Ends the child: its stdin closes, so it stops its runtime and exits. */
    @Override
    public void close() throws Exception {
        try {
            this.process.stdin().close();
            this.process.await(Duration.ofSeconds(60));
        } catch (IOException | TimeoutException stuck) {
            this.process.stopTree();
        }
    }

    private @NonNull String next(@NonNull Duration within) throws Exception {
        String answer = this.answers.poll(within.toMillis(), TimeUnit.MILLISECONDS);
        if (answer == null) {
            throw new IllegalStateException("the forked Hohenheim answered nothing within " + within + "\n"
                + this.tail());
        }
        return answer;
    }

    private void read(@NonNull Path log) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(this.process.stdout(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                synchronized (this.tail) {
                    this.tail.addLast(line);
                    if (this.tail.size() > 200) {
                        this.tail.removeFirst();
                    }
                }
                if (line.startsWith(MARK)) {
                    this.answers.add(line.substring(MARK.length()));
                }
            }
        } catch (IOException ended) {
            // The child is gone.
        }
        this.answers.add("gone");
    }

    private @NonNull String tail() {
        synchronized (this.tail) {
            return String.join("\n", this.tail);
        }
    }

    /** The child's entry point: boot, say ready, answer commands until stdin ends. */
    public static final class Child {

        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            String name = args[1];
            String masterHost = args[2];
            int masterPort = Integer.parseInt(args[3]);
            boolean freshIdentity = Boolean.parseBoolean(args[4]);

            HohenheimSettingsBoot.forceDefinitions();
            for (HohenheimRoles.Role role : HohenheimRoles.Role.values()) {
                Zenit.SETTINGS_VALUES.setValue(role.setting(), false);
            }
            MigratedDatabase file = MigratedDatabase.existingSqliteFile(() -> database);
            file.beforeAll(null);
            ServerSettings.VALUES.setValue(ServerSettings.Database.URL, file.location().url());
            HohenheimDatabase.init();
            ServerSettings.VALUES.setValue(ServerSettings.Network.AUTO_START_HTTP, false);

            ServerMain.main(new String[0]);
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Cluster.NAME, name);
            // Its calls to the other server: that host name, over TLS, to the other server's plain port; installed
            // once the runtime holds its outbound network, before anything is served or asked.
            TlsRelay relay = TlsRelay.open();
            OutboundTlsStub stub = OutboundFixture.routeTls(masterHost, relay.port());
            relay.start(stub.serverContext(), masterPort);
            ZenitHttpServer http = ServerZenitRuntime.createServer(0);
            http.start();
            ExecutionIdentity.runAsSystem(HohenheimSystemPurposes.BOOT, () -> {
                if (freshIdentity) {
                    Db.run(HohenheimDatabase.datasource(), () -> Models.get(ControllerIdentityModel.class).find()
                        .all().forEach(row -> Models.get(ControllerIdentityModel.class)
                            .delete(row.get(ControllerIdentityModel.ID))));
                    ControllerIdentity.forgetForTest();
                }
                ControllerIdentity.resolve();
            });
            answer("ready " + http.getPort() + " " + ControllerIdentity.token());

            BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String command;
            while ((command = commands.readLine()) != null) {
                String asked = command;
                try {
                    answer(ExecutionIdentity.supplyAsSystem(HohenheimSystemPurposes.BOOT, () -> run(asked)));
                } catch (Violations refused) {
                    answer("refused " + words(refused));
                } catch (DomainRefusal refused) {
                    answer("refused "
                        + refused.shown().tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault()));
                } catch (RuntimeException failed) {
                    answer("error " + failed);
                }
            }
            ServerZenitRuntime.stop();
            http.stop();
            stub.close();
            relay.close();
            System.exit(0);
        }

        /** Runs one command through the server's own entry points; answers its outcome. */
        private static @NonNull String run(@NonNull String command) {
            String[] words = command.split(" ");
            return switch (words[0]) {
                case "offer" -> {
                    // "offer" issues the code of the test account; "offer N" that of test account N, so two
                    // administrators can hold a code each at once.
                    AccessContext caller = words.length < 2 ? TestAccessContexts.allAllowed()
                        : AccessContext.of(TestAccessContexts.stubConduit(), TestAccessContexts.authenticated(
                            TestAccessContexts.accountId(Long.parseLong(words[1])), "p" + words[1]),
                            TestAccessContexts.allowAll());
                    SecretResult<?> issued = OperationPipeline.invoke(OperationRequest.of(LinkedServerParts.OFFER_JOIN,
                        CmsPlacementSurface.ADMIN_ACTION).caller(caller)).value();
                    yield "code " + issued.secret().reveal();
                }
                case "add" -> "added " + OperationPipeline.invoke(OperationRequest.of(LinkedServerParts.ADD,
                        CmsPlacementSurface.ADMIN_ACTION).caller(TestAccessContexts.allAllowed())
                    .form(Map.of("name", words[1], "address", words[2], "join_code", words[3]))).value();
                case "remove-master" -> {
                    Row master = LinkedServers.master();
                    if (master == null) {
                        yield "none";
                    }
                    yield "removed " + OperationPipeline.invoke(OperationRequest.of(LinkedServerParts.REMOVE,
                            CmsPlacementSurface.ADMIN_ACTION).caller(TestAccessContexts.allAllowed())
                        .subjectKeys(List.of(String.valueOf((Object) master.get(LinkedServerModel.ID))))).value();
                }
                case "probe" -> {
                    LinkedServers.sweep();
                    yield rows();
                }
                case "rows" -> rows();
                case "offset" -> {
                    Now.setOffset(Duration.ofMinutes(Long.parseLong(words[1])));
                    yield "ok";
                }
                default -> "unknown " + command;
            };
        }

        /** @return every linked server as {@code name|role|state|failure|pinned token}, joined by semicolons */
        private static @NonNull String rows() {
            List<String> rows = new ArrayList<>();
            for (Row row : LinkedServers.all()) {
                LinkFailure failure = LinkFailure.byToken(row.get(LinkedServerModel.LAST_FAILURE));
                rows.add(row.get(LinkedServerModel.NAME) + "|" + row.get(LinkedServerModel.ROLE) + "|"
                    + LinkState.of(row, LinkedServers.ownVersion()).token() + "|"
                    + (failure == null ? "-" : failure.token()) + "|" + row.get(LinkedServerModel.REMOTE_TOKEN));
            }
            return "rows " + (rows.isEmpty() ? "-" : String.join(";", rows));
        }

        private static @NonNull String words(@NonNull Violations refused) {
            List<String> words = new ArrayList<>();
            for (Violation violation : refused) {
                words.add(violation.message().tryResolve(LocaleChain.ofTags("en"), MessageResolvers.getDefault()));
            }
            return String.join(" / ", words);
        }

        private static void answer(@NonNull String line) {
            System.out.println(MARK + line.replace('\n', ' '));
            System.out.flush();
        }
    }
}
