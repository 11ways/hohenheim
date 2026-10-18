package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.runtime.ConsoleStreamSupport;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.hohenheim.test.Poll;
import be.elevenways.protoblast.common.thread.JobRunner;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.kvm.server.ScreenRefusalReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A VM's serial console holds the instance's one console the way Incus counts it, and only while its stream lives:
 * its control websocket links at once, the VM's screen reads it as held, and a screen asked for while the stream is
 * being closed waits for it and opens without taking anything over.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class IncusSerialConsoleTest {

    @Test
    void theSerialConsoleHoldsTheOneConsoleOnlyUntilItsStreamCloses() throws Exception {
        JobRunner screens = JobRunner.create("serial-console-screen");
        try (ServerSocket console = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            FakeVgaConsoleDaemon daemon = new FakeVgaConsoleDaemon(console.getLocalPort());
            IncusClient incus = new IncusClient(daemon);

            // 1. The serial console starts an operation and links its control websocket at once.
            ConsoleStreamSupport.Console serial = new IncusInstanceRuntime(incus).openConsole("hohenheim-instance-9");
            assertThat(daemon.requests.getFirst()).as("step 1: a serial console of the instance")
                .contains("POST /1.0/instances/hohenheim-instance-9/console").contains("\"type\":\"console\"");
            assertThat(daemon.controlLinked()).as("step 1: its control websocket linked")
                .containsExactly("/1.0/operations/op-vga-1");

            // 2. While it runs, the VM's screen finds the console held, after asking for it a few times.
            assertThatThrownBy(() -> IncusSpice.open(incus, "hohenheim-instance-9", false))
                .as("step 2: the screen reads the console as held").isInstanceOfSatisfying(DomainRefusal.class,
                    refusal -> assertThat(refusal.reason()).isEqualTo(ScreenRefusalReason.HELD));
            assertThat(vgaAsks(daemon)).as("step 2: asked for every attempt").isEqualTo(IncusSpice.HELD_ATTEMPTS);

            // 3. A screen asked for just before the serial console is let go (a page moving from the console to the
            //    screen) waits for it instead of reading it held, and opens without forcing anything.
            AtomicReference<IncusSpice.Console> opened = new AtomicReference<>();
            CompletableFuture<IncusSpice.Console> asked = screens.runOnThread(() -> {
                try {
                    opened.set(IncusSpice.open(incus, "hohenheim-instance-9", false));
                } catch (IOException failed) {
                    throw new UncheckedIOException(failed);
                }
            }).thenApply(done -> opened.get());
            Poll.until("step 3: the screen was refused once while the serial console runs", Duration.ofSeconds(5),
                () -> vgaAsks(daemon) > IncusSpice.HELD_ATTEMPTS);
            serial.stream().close();
            IncusSpice.Console screen = asked.get(10, TimeUnit.SECONDS);
            assertThat(daemon.requests.getLast()).as("step 3: the screen was not forced").contains("\"force\":false");
            assertThat(daemon.running()).as("step 3: only the screen's console runs").hasSize(1);

            // 4. Closing the screen leaves no console operation running.
            screen.close();
            assertThat(daemon.running()).as("step 4: no console operation is left running").isEmpty();
        } finally {
            screens.shutdownNow();
        }
    }

    private static long vgaAsks(FakeVgaConsoleDaemon daemon) {
        return daemon.requests.stream().filter(request -> request.contains("\"type\":\"vga\"")).count();
    }
}
