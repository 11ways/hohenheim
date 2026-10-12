package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.runtime.ConsoleStreamSupport;
import be.elevenways.hohenheim.server.runtime.IncusInstanceRuntime;
import be.elevenways.zenit.common.refusal.DomainRefusal;
import be.elevenways.zenit.kvm.server.ScreenRefusalReason;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A VM's serial console holds the instance's one console the way Incus counts it, and only while its stream lives:
 * its control websocket links at once, the VM's screen reads it as held, and closing the stream ends the operation so
 * the screen opens without taking anything over.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
class IncusSerialConsoleTest {

    @Test
    void theSerialConsoleHoldsTheOneConsoleOnlyUntilItsStreamCloses() throws Exception {
        try (ServerSocket console = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            FakeVgaConsoleDaemon daemon = new FakeVgaConsoleDaemon(console.getLocalPort());
            IncusClient incus = new IncusClient(daemon);

            // 1. The serial console starts an operation and links its control websocket at once.
            ConsoleStreamSupport.Console serial = new IncusInstanceRuntime(incus).openConsole("hohenheim-instance-9");
            assertThat(daemon.requests.getFirst()).as("step 1: a serial console of the instance")
                .contains("POST /1.0/instances/hohenheim-instance-9/console").contains("\"type\":\"console\"");
            assertThat(daemon.controlLinked()).as("step 1: its control websocket linked")
                .containsExactly("/1.0/operations/op-vga-1");

            // 2. While it runs, the VM's screen finds the console held.
            assertThatThrownBy(() -> IncusSpice.open(incus, "hohenheim-instance-9", false))
                .as("step 2: the screen reads the console as held").isInstanceOfSatisfying(DomainRefusal.class,
                    refusal -> assertThat(refusal.reason()).isEqualTo(ScreenRefusalReason.HELD));

            // 3. Closing the stream ends the operation, so the screen opens without forcing anything.
            serial.stream().close();
            assertThat(daemon.running()).as("step 3: no console operation is left running").isEmpty();
            IncusSpice.Console screen = IncusSpice.open(incus, "hohenheim-instance-9", false);
            assertThat(daemon.requests.getLast()).as("step 3: the screen was not forced").contains("\"force\":false");
            screen.close();
        }
    }
}
