package be.elevenways.hohenheim.server.cluster;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.cluster.LinkFailure;
import be.elevenways.hohenheim.cluster.LinkState;
import be.elevenways.hohenheim.model.ControllerIdentityModel;
import be.elevenways.hohenheim.model.EnvironmentModel;
import be.elevenways.hohenheim.model.HostModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceVariableModel;
import be.elevenways.hohenheim.model.LinkedServerModel;
import be.elevenways.hohenheim.model.ProjectModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ControllerIdentity;
import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.HohenheimSystemPurposes;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.auth.ProteusSignIn;
import be.elevenways.hohenheim.server.cms.LinkedServerParts;
import be.elevenways.hohenheim.server.cms.SiteWrites;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.instance.InstanceVariables;
import be.elevenways.hohenheim.test.TenantConduits;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.hohenheim.test.instance.FakeNativeDaemons;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.protoblast.server.process.RunningProcess;
import be.elevenways.protoblast.server.process.Subprocess;
import be.elevenways.protoblast.testsupport.thread.ThreadWaits;
import be.elevenways.zenit.ai.server.mcp.host.McpCarriedCalls;
import be.elevenways.zenit.auth.model.UserModel;
import be.elevenways.zenit.auth.server.ApiKeyService;
import be.elevenways.zenit.auth.server.identity.IdentityProviderRegistry;
import be.elevenways.zenit.auth.test.TestSessions;
import be.elevenways.zenit.cms.common.action.CmsPlacementSurface;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.operation.OperationRunModel;
import be.elevenways.zenit.common.operation.SecretResult;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.code.CodeBlocks;
import be.elevenways.zenit.common.orm.code.CodeWant;
import be.elevenways.zenit.common.orm.code.RecordCodeModel;
import be.elevenways.zenit.common.orm.history.ActionModel;
import be.elevenways.zenit.common.orm.history.HistoryText;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.peer.SharedModels;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.security.ExecutionIdentity;
import be.elevenways.zenit.common.security.PrincipalRef;
import be.elevenways.zenit.common.session.SessionToken;
import be.elevenways.zenit.common.validation.Violation;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.http.SessionCookies;
import be.elevenways.zenit.server.http.ZenitHttpServer;
import be.elevenways.zenit.server.operation.OperationPipeline;
import be.elevenways.zenit.server.operation.RecordCodes;
import be.elevenways.zenit.server.operation.OperationRequest;
import be.elevenways.zenit.server.setting.ServerSettings;
import be.elevenways.zenit.test.support.CommittedWrites;
import be.elevenways.zenit.test.support.MigratedDatabase;
import be.elevenways.zenit.test.support.OutboundFixture;
import be.elevenways.zenit.test.support.OutboundTlsStub;
import be.elevenways.zenit.test.support.TestAccessContexts;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

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

    /** The host of Kuifje's own the fake runtime's apps run on ("fake-app"). */
    static final String FAKE_HOST = "kuifje-fake-host";

    /** The heap a whole Hohenheim runtime with every role off boots in. */
    private static final long HEAP = 512L * 1024 * 1024;

    /** How long a cold child may take to boot; far above what one takes, so a failure names the boot. */
    private static final Duration BOOT_LIMIT = Duration.ofMinutes(3);

    private static final Duration ANSWER_LIMIT = Duration.ofSeconds(60);

    /** The Proteus realm both servers of a journey sign people in with: its endpoint and realm client. */
    static final String PROTEUS_ENDPOINT = "https://proteus.cluster.test/";
    static final String PROTEUS_REALM_CLIENT = "hohenheim";

    /**
     * Sets this process's Proteus sign-in up as every server of a journey has it, so a person is named alike on both;
     * its provider is registered by the boot that follows, or by ProteusSignIn.registerIfConfigured.
     */
    static void proteusSettings() {
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.ENABLED, true);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.ENDPOINT, PROTEUS_ENDPOINT);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.REALM_CLIENT, PROTEUS_REALM_CLIENT);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.ACCESS_KEY, "realm-key");
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.AuthProteus.REQUIRED_PERMISSION, "hohenheim.cluster");
    }

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

    /** @return the child's last lines of output, for a failure to name what it said */
    @NonNull String tail() {
        synchronized (this.tail) {
            return String.join("\n", this.tail);
        }
    }

    /** The child's entry point: boot, say ready, answer commands until stdin ends. */
    public static final class Child {

        /** Its calls to the other server; "cut" points them at a port nothing listens on, "mend" back. */
        private static TlsRelay relay;

        /** The other server's plain HTTP port. */
        private static int masterPort;

        /** The latch a held start waits on ("hold-start"), null while none is held. */
        private static final AtomicReference<CountDownLatch> HELD_START = new AtomicReference<>();

        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            String name = args[1];
            String masterHost = args[2];
            masterPort = Integer.parseInt(args[3]);
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
            // Its Proteus sign-in, the realm the journey's servers share; offered here once its settings are.
            proteusSettings();
            if (IdentityProviderRegistry.get(ProteusSignIn.SLUG) == null) {
                ProteusSignIn.registerIfConfigured();
            }
            // Its calls to the other server: that host name, over TLS, to the other server's plain port; installed
            // once the runtime holds its outbound network, before anything is served or asked.
            relay = TlsRelay.open();
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
                    // The check asks the master for code numbers in the background: that ends before the answer.
                    ClusterCodes.awaitIdle(Duration.ofSeconds(50));
                    yield rows();
                }
                // "cut" makes the master stop answering, as this server sees it; "mend" makes it answer again.
                case "cut" -> {
                    relay.retarget(closedPort());
                    yield "ok";
                }
                case "mend" -> {
                    relay.retarget(masterPort);
                    yield "ok";
                }
                // "app NAME" stores an app as a deploy does before it starts: "app <id> <code>".
                case "app" -> {
                    Row app = Models.get(InstanceModel.class).createEmptyRow();
                    app.set(InstanceModel.NAME, words[1]);
                    app.set(InstanceModel.KIND, "hohenheim:docker_container");
                    app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "alpine")));
                    app.set(InstanceModel.STATUS, InstanceModel.STATUS_CREATED);
                    Models.get(InstanceModel.class).save(app);
                    String key = String.valueOf((Object) app.get(InstanceModel.ID));
                    yield "app " + key + " " + RecordCodes.codeUnjudged(InstanceModel.MODEL_ID, key);
                }
                // "fake-app NAME" deploys an app on the daemon-free fake runtime of a host of Kuifje's own, so it runs:
                // "app <id> <code>".
                case "fake-app" -> {
                    FakeNativeDaemons.register();
                    Row host = Models.get(HostModel.class).findByName(FAKE_HOST);
                    int hostId = host != null ? host.get(HostModel.ID) : HostFixtures.admittedIncusHost(FAKE_HOST);
                    Row app = Models.get(InstanceModel.class).createEmptyRow();
                    app.set(InstanceModel.NAME, words[1]);
                    app.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
                    app.set(InstanceModel.SETTINGS, new LinkedHashMap<>(Map.of("image", "fake/image")));
                    app.set(InstanceModel.HOST_ID, hostId);
                    Models.get(InstanceModel.class).save(app);
                    int id = app.get(InstanceModel.ID);
                    new InstanceService().deploy(id);
                    yield "app " + id + " " + RecordCodes.codeUnjudged(InstanceModel.MODEL_ID, String.valueOf(id));
                }
                // "started ID": "started <status> <when what runs last started>" of the app's record here.
                case "started" -> {
                    Row app = Models.get(InstanceModel.class).find().noCache()
                        .where(InstanceModel.ID.eq(Integer.parseInt(words[1]))).first();
                    yield app == null ? "none" : "started " + app.get(InstanceModel.STATUS) + " "
                        + app.get(InstanceModel.STARTED_AT);
                }
                // "hold-start": the next start on the fake runtime waits there until "release-start".
                case "hold-start" -> {
                    CountDownLatch latch = new CountDownLatch(1);
                    HELD_START.set(latch);
                    FakeNativeDaemons.DURING_START.set(() -> ThreadWaits.hold(latch));
                    yield "ok";
                }
                case "release-start" -> {
                    CountDownLatch latch = HELD_START.getAndSet(null);
                    if (latch != null) {
                        latch.countDown();
                    }
                    yield "ok";
                }
                // "run-stop INVOCATION": whether a stop was asked of the run carried here under that invocation,
                // waiting for it as its row is written.
                case "run-stop" -> {
                    UUID invocation = UUID.fromString(words[1]);
                    try {
                        yield "run-stop " + CommittedWrites.await(OperationRunModel.MODEL_ID, Duration.ofSeconds(30),
                            () -> {
                                Row run = Models.get(OperationRunModel.class).find().noCache()
                                    .where(OperationRunModel.INVOCATION_ID.eq(invocation)).first();
                                return run != null && run.get(OperationRunModel.STOP_REQUESTED_AT) != null;
                            });
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        yield "error interrupted";
                    }
                }
                // "code MODEL KEY": the record's current code here.
                case "code" -> "code " + RecordCodes.codeUnjudged(model(words[1]), words[2]);
                // "key-of MODEL CODE": the key of the record a code, a retired spelling too, names here.
                case "key-of" -> "key " + RecordCodes.keyOfCode(model(words[1]), words[2]);
                // Every current allocated code here, sorted, joined by semicolons.
                case "codes" -> {
                    List<String> codes = new ArrayList<>();
                    for (Row code : new RecordCodeModel(Models.get(LinkedServerModel.class).getResolvedDatasource())
                            .find().noCache().where(RecordCodeModel.NUMBER.isNotNull())
                            .where(RecordCodeModel.RETIRED_AT.isNull()).all()) {
                        codes.add(code.get(RecordCodeModel.CODE));
                    }
                    Collections.sort(codes);
                    yield "codes " + String.join(";", codes);
                }
                // How many things the join card says get a new code when this server joins.
                case "renumbers" -> "renumbers " + ClusterCodes.renumberedAtJoin();
                // "left PREFIX": the numbers this server's granted blocks of the prefix still hold.
                case "left" -> {
                    long left = 0;
                    for (CodeWant want : CodeBlocks.wants(Models.get(LinkedServerModel.class)
                            .getResolvedDatasource())) {
                        if (want.prefix().equals(words[1])) {
                            left = want.left();
                        }
                    }
                    yield "left " + left;
                }
                case "rows" -> rows();
                case "offset" -> {
                    Now.setOffset(Duration.ofMinutes(Long.parseLong(words[1])));
                    yield "ok";
                }
                // "site NAME" makes a site through the site create operation, as an administrator's form does.
                case "site" -> "site " + OperationPipeline.invoke(OperationRequest.of(SiteWrites.CREATE,
                        CmsPlacementSurface.ADMIN_ACTION).caller(TestAccessContexts.allAllowed())
                    .form(Map.of("name", words[1], "upstream_kind", "hohenheim:static",
                        "settings", Map.of("root_path", "/tmp/" + words[1]), "enabled", false))).value();
                // "sites N" stores N sites in ONE transaction: one commit, so one nudge and one sync.
                case "sites" -> {
                    int many = Integer.parseInt(words[1]);
                    SiteModel sites = Models.get(SiteModel.class);
                    sites.getResolvedDatasource().withTransaction(transaction -> {
                        for (int at = 0; at < many; at++) {
                            String name = "batch-" + Now.millis() + "-" + at;
                            Row site = sites.createEmptyRow();
                            site.set(SiteModel.NAME, name);
                            site.set(SiteModel.SLUG, name);
                            site.set(SiteModel.STATUS, SiteModel.STATUS_ACTIVE);
                            site.set(SiteModel.UPSTREAM_KIND, "hohenheim:static");
                            site.set(SiteModel.SETTINGS, Map.of("root_path", "/tmp/" + name));
                            site.set(SiteModel.ENABLED, false);
                            sites.save(site);
                        }
                    });
                    yield "ok";
                }
                case "describe" -> {
                    Row site = Models.get(SiteModel.class).findById(Integer.parseInt(words[1]));
                    site.set(SiteModel.DESCRIPTION, words[2]);
                    Models.get(SiteModel.class).save(site);
                    yield "ok";
                }
                case "unsite" -> "unsite " + OperationPipeline.invoke(OperationRequest.of(SiteWrites.DELETE,
                        CmsPlacementSurface.ADMIN_ACTION).caller(TestAccessContexts.allAllowed())
                    .subjects(List.of(Models.get(SiteModel.class).findById(Integer.parseInt(words[1]))))).value();
                // "secret VALUE" stores an environment's variable holding VALUE as its secret.
                case "secret" -> {
                    Row project = Models.get(ProjectModel.class).createEmptyRow();
                    project.set(ProjectModel.NAME, "kept-project-" + Now.millis());
                    Models.get(ProjectModel.class).save(project);
                    Row environment = Models.get(EnvironmentModel.class).createEmptyRow();
                    environment.set(EnvironmentModel.PROJECT_ID, project.get(ProjectModel.ID));
                    environment.set(EnvironmentModel.NAME, "kept-environment");
                    Models.get(EnvironmentModel.class).save(environment);
                    Row variable = Models.get(InstanceVariableModel.class).createEmptyRow();
                    variable.set(InstanceVariableModel.ENVIRONMENT_ID, environment.get(EnvironmentModel.ID));
                    variable.set(InstanceVariableModel.KEY, "KEPT_SECRET");
                    variable.set(InstanceVariableModel.KIND, InstanceVariableModel.KIND_SECRET);
                    variable.set(InstanceVariableModel.SECRET_VALUE, words[1]);
                    Models.get(InstanceVariableModel.class).save(variable);
                    yield "variable " + variable.get(InstanceVariableModel.ID);
                }
                // Every shared kind this server holds rows of, as "model=count" sorted, joined by semicolons.
                case "counts" -> {
                    List<String> counts = new ArrayList<>();
                    for (Model model : SharedModels.served()) {
                        long count = model.find().noCache().count();
                        if (count > 0) {
                            counts.add(model.getModelId() + "=" + count);
                        }
                    }
                    Collections.sort(counts);
                    yield "counts " + String.join(";", counts);
                }
                // Waits until every nudge this server asked has been sent to its master.
                case "settle" -> "settled " + ClusterNudge.awaitIdle(Duration.ofSeconds(50));
                // Asks its MASTER for that master's shared generations with the key it holds: it is not their master.
                case "read-master" -> {
                    Row master = LinkedServers.master();
                    if (master == null) {
                        yield "none";
                    }
                    LinkCalls.Answer answer = LinkCalls.generations(master.get(LinkedServerModel.BASE_URL),
                        master.get(LinkedServerModel.LINK_KEY));
                    yield "status " + (answer instanceof LinkCalls.Answered answered ? answered.status() : -1);
                }
                // "api-key" mints an API key of this server's administrator, as its key page does.
                case "api-key" -> "api-key " + ApiKeyService.create(TenantConduits.operatorUser().get(UserModel.ID),
                    "cluster-refusal-admin", List.of("hohenheim.*"), null).plaintext();
                // "session" signs this server's administrator in: the Cookie header its browser then sends.
                case "session" -> "session " + SessionCookies.requestHeaderValue(Zenit.getSessionStore().get(
                    SessionToken.of(TestSessions.signedIn(TenantConduits.operatorUser().get(UserModel.ID)))));
                // "mcp-key SCOPES" mints this server's administrator a key with the comma-joined scopes.
                case "mcp-key" -> "mcp-key " + ApiKeyService.create(TenantConduits.operatorUser().get(UserModel.ID),
                    "cluster-carried-own", List.of(words[1].split(",")), null).plaintext();
                // "variables APP": the app's variables as "key=value" sorted, joined by semicolons.
                case "variables" -> {
                    List<String> variables = new ArrayList<>();
                    new InstanceVariables().valuesFor(Integer.parseInt(words[1]))
                        .forEach((key, value) -> variables.add(key + "=" + value));
                    Collections.sort(variables);
                    yield "variables " + String.join(";", variables);
                }
                // The newest action recorded here: "action <actor kind> <through kind> <actor as history names it>".
                case "last-action" -> {
                    Row action = new ActionModel(Models.get(LinkedServerModel.class).getResolvedDatasource()).find()
                        .noCache().orderBy(ActionModel.CREATED_AT, SortOrder.DESC)
                        .orderBy(ActionModel.ID, SortOrder.DESC).first();
                    PrincipalRef actor = action == null ? null : ActionModel.ACTOR_PRINCIPAL.read(action);
                    Microcopy named = actor == null ? null : HistoryText.named(actor);
                    yield action == null ? "none" : "action " + action.get(ActionModel.ACTOR_KIND) + " "
                        + action.get(ActionModel.THROUGH_KIND) + " " + (named == null ? "nobody"
                        : AccessContext.anonymous().text(named));
                }
                // "tool-master CODE PERSON": this server, with the key it holds for its master, asks that master to
                // run a read of CODE as the person PERSON names: it is not their master.
                case "tool-master" -> {
                    Row master = LinkedServers.master();
                    if (master == null) {
                        yield "none";
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("person", words[2]);
                    body.put("name", "Somebody");
                    body.put("permissions", List.of("*"));
                    body.put("scopes", List.of("mcp:hohenheim", "*"));
                    body.put("surface", McpCarriedCalls.SURFACE.toString());
                    body.put("call", Map.of("server", "hohenheim", "tool", "read",
                        "arguments", Map.of("codes", List.of(words[1]))));
                    LinkCalls.Answer answer = LinkCalls.carried(master.get(LinkedServerModel.BASE_URL),
                        master.get(LinkedServerModel.LINK_KEY), body, "en");
                    yield "status " + (answer instanceof LinkCalls.Answered answered ? answered.status() : -1);
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

        private static @NonNull Identifier model(@NonNull String id) {
            return Objects.requireNonNull(Identifier.tryParse(id), "a model id");
        }

        /** @return a loopback port nothing listens on */
        private static int closedPort() {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            } catch (IOException failed) {
                throw new IllegalStateException(failed);
            }
        }
    }
}
