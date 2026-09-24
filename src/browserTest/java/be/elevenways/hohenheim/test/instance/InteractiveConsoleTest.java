package be.elevenways.hohenheim.test.instance;

import be.elevenways.hohenheim.instance.ConsoleKind;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.instance.InstanceConsoleHandler;
import be.elevenways.hohenheim.server.instance.InstanceConsoles;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.test.ApiSupport;
import be.elevenways.hohenheim.test.HohenheimTestRuntime;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.hohenheim.test.TestDatabases;
import be.elevenways.hohenheim.test.host.HostFixtures;
import be.elevenways.zenit.auth.model.GrantSubjectType;
import be.elevenways.zenit.auth.model.UserPrincipal;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Db;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.sql.SqlDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.routing.ParameterDefinition;
import be.elevenways.zenit.common.security.Principal;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.zenit.common.websocket.WebSocketSession;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The interactive console ({@code console_kind=tty}) as a DAEMON-FREE journey through the
 * real socket handler and the real hub, against {@link FakeNativeDaemons}: the declared
 * kind reaches the driver as a pseudo-terminal, keystrokes and resize frames reach the
 * workload, output is relayed raw, and a PLAIN console keeps every one of the old
 * guarantees (read-only socket, newline translation). The real-daemon half is
 * {@code InstanceConsoleLiveTest}.
 */
class InteractiveConsoleTest {

    /** Bounded wait: the console output and stdin are pumped by threads of their own. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static SqlDatasource datasource;
    private static int hostId;
    private static final String HOST = "interactive-console-host";

    @BeforeAll
    static void setUp() throws Exception {
        datasource = TestDatabases.freshDatasource();
        HohenheimTestRuntime.ensureBooted();
        FakeNativeDaemons.register();
        Db.run(datasource, () -> hostId = HostFixtures.admittedIncusHost(HOST));
    }

    @AfterEach
    void closeStreams() {
        FakeNativeDaemons.resetStreams();
    }

    @Test
    void anInteractiveConsoleCarriesKeystrokesAndGeometryAndRelaysOutputRaw() {
        Db.run(datasource, () -> {
            // 1. The setting is the declaration: the driver gets a pseudo-terminal.
            int instanceId = instanceRecord("console-tty", ConsoleKind.TTY.token());
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            FakeNativeDaemons.FakeWorkload workload = workload(handle);
            assertThat(workload.tty)
                .as("step 1: console_kind=tty creates the workload with a pseudo-terminal")
                .isTrue();
            assertThat(workload.geometry)
                .as("step 1: nobody has sized it yet").isNull();

            // 2. A granted viewer opens the socket: the hub attaches interactively.
            FakeSession session = new FakeSession(grantedViewer("tty-viewer", instanceId));
            InstanceConsoleHandler handler = new InstanceConsoleHandler(session, instanceId);
            handler.onOpen();
            assertThat(session.isOpen()).as("step 2: the viewer is admitted").isTrue();
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);
            assertThat(stream).as("step 2: the hub attached to the driver's console").isNotNull();

            // 3. Terminal bytes are relayed VERBATIM: a pseudo-terminal already emits \r\n
            //    and escape sequences, and re-translating them would corrupt the TUI.
            String painted = "[2J[Hready\r\n";
            stream.push(painted);
            Poll.until("step 3: the output reached the viewer", WAIT, () -> !session.texts.isEmpty());
            assertThat(String.join("", session.texts))
                .as("step 3: raw terminal bytes, no newline translation")
                .isEqualTo(painted);

            // 4. Keystrokes reach the workload's stdin exactly as typed: no newline is
            //    appended (a terminal gets \r from the Enter key, not a line).
            handler.onTextMessage("ls\r");
            Poll.until("step 4: the keystrokes reached stdin", WAIT, () -> !stream.stdinWrites().isEmpty());
            assertThat(stream.stdinWrites())
                .as("step 4: keystrokes are written verbatim").containsExactly("ls\r");

            // 5. A resize control frame becomes the pseudo-terminal's geometry and is
            //    NOT typed into the workload.
            handler.onTextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}");
            assertThat(workload.geometry)
                .as("step 5: the viewer's geometry reached the driver").isEqualTo("120x40");
            assertThat(stream.stdinWrites())
                .as("step 5: the control frame never reaches stdin").containsExactly("ls\r");

            // 6. Closing the socket detaches the viewer; the session itself lives on.
            handler.onClose(1000, "bye");
            stream.push("after close\r\n");
            assertThat(String.join("", session.texts))
                .as("step 6: a closed viewer receives nothing more")
                .doesNotContain("after close");
            assertThat(InstanceConsoles.peek(instanceId))
                .as("step 6: the hub keeps the workload's session").isNotNull();
        });
    }

    @Test
    void aPlainConsoleStaysReadOnlyAndTranslatesNewlines() {
        Db.run(datasource, () -> {
            // 1. No declaration is the plain default: a pipe, not a terminal.
            int instanceId = instanceRecord("console-plain", null);
            new InstanceService().deploy(instanceId);
            String handle = FakeNativeDaemons.handleOf(instanceId);
            assertThat(workload(handle).tty)
                .as("step 1: the default console is a plain pipe").isFalse();

            FakeSession session = new FakeSession(grantedViewer("plain-viewer", instanceId));
            InstanceConsoleHandler handler = new InstanceConsoleHandler(session, instanceId);
            handler.onOpen();
            FakeNativeDaemons.ScriptedStream stream = FakeNativeDaemons.CONSOLE_STREAMS.get(handle);

            // 2. A pipe's bare \n leaves the terminal cursor mid-line: translated.
            stream.push("line one\nline two\n");
            Poll.until("step 2: the output reached the viewer", WAIT, () -> !session.texts.isEmpty());
            assertThat(String.join("", session.texts))
                .as("step 2: the plain console translates \\n to \\r\\n")
                .isEqualTo("line one\r\nline two\r\n");

            // 3. Inbound text is IGNORED: a non-TTY container never echoes, so keystrokes
            //    would be invisible typing -- commands ride the form's POST endpoint.
            handler.onTextMessage("ls\r");
            handler.onTextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}");
            assertThat(stream.stdinWrites())
                .as("step 3: nothing typed on the socket reaches a plain console").isEmpty();
            assertThat(workload(handle).geometry)
                .as("step 3: and a plain pipe has no geometry to set").isNull();

            // 4. The form's lane still works and still appends the line ending.
            InstanceConsoles.sendCommand(instanceId, "status");
            assertThat(stream.stdinWrites())
                .as("step 4: the command form remains the plain console's input")
                .containsExactly("status\n");
            handler.onClose(1000, "bye");
        });
    }

    @Test
    void anUnknownConsoleKindRefusesToDeployByName() {
        Db.run(datasource, () -> {
            // The reserved word from the old plan is NOT a member: fail closed, never plain.
            int instanceId = instanceRecord("console-unknown", "janeway");
            assertThatThrownBy(() -> new InstanceService().deploy(instanceId))
                .isInstanceOf(Violations.class)
                .hasMessageContaining("console_kind");
            assertThat(FakeNativeDaemons.DAEMONS.getOrDefault(HOST, Map.of()))
                .as("nothing was created for a declaration this build does not know")
                .doesNotContainKey(FakeNativeDaemons.handleOf(instanceId));
        });
    }

    // -----------------------------------------------------------------------

    private static FakeNativeDaemons.FakeWorkload workload(String handle) {
        FakeNativeDaemons.FakeWorkload workload = FakeNativeDaemons.DAEMONS
            .getOrDefault(HOST, Map.of()).get(handle);
        assertThat(workload).as("the fake daemon holds " + handle).isNotNull();
        return workload;
    }

    /** A user holding {@code manage} on the record, which implies {@code console}. */
    private static Principal grantedViewer(String label, int instanceId) {
        int userId = ApiSupport.user(label + "@hohenheim.local", "Console " + label);
        RecordGrants.grant(GrantSubjectType.USER, userId, InstanceModel.MODEL_ID, instanceId,
            HohenheimAccess.MANAGE, true);
        return new UserPrincipal(userId, "Console " + label);
    }

    private static int instanceRecord(String name, @Nullable String consoleKind) {
        Row row = Models.get(InstanceModel.class).createEmptyRow();
        row.set(InstanceModel.NAME, name);
        row.set(InstanceModel.KIND, FakeNativeDaemons.FakeNativeKind.ID.toString());
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("image", "fake/image"));
        if (consoleKind != null) {
            settings.put(ConsoleKind.SETTING, consoleKind);
        }
        row.set(InstanceModel.SETTINGS, settings);
        row.set(InstanceModel.SERVER_ID, hostId);
        Models.get(InstanceModel.class).save(row);
        return row.get(InstanceModel.ID);
    }

    /** A minimal in-process session that records what the handler did to it. */
    private static final class FakeSession implements WebSocketSession {

        private final Principal principal;
        final List<String> texts = new CopyOnWriteArrayList<>();
        int closeCode = -1;

        FakeSession(Principal principal) {
            this.principal = principal;
        }

        @Override
        public <T> @Nullable T getParameter(ParameterDefinition<T> parameter) {
            return null;
        }

        @Override public @Nullable Principal getPrincipal() { return this.principal; }
        @Override public void sendText(String message) { this.texts.add(message); }
        @Override public void sendBinary(byte[] data) {}
        @Override public void close() { if (this.closeCode < 0) this.closeCode = 1000; }
        @Override public void close(int code, String reason) {
            if (this.closeCode < 0) this.closeCode = code;
        }
        @Override public boolean isOpen() { return this.closeCode < 0; }
    }
}
