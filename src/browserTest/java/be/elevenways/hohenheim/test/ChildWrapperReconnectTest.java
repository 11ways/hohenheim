package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.process.ChildWrapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The child wrapper's BOUNDED reconnect loop: a transient refusal (the parent
 * restarting, or a hostile local peer briefly saturating the pre-auth cap) must
 * not permanently lose the IPC bridge. The wrapper's first connect is refused
 * because nothing is listening yet; the wrapper must retry and, once the parent
 * starts listening, authenticate and bridge the child's messages.
 *
 * AIDEV-NOTE: this fails against the OLD wrapper, which called net.connect EXACTLY
 * ONCE and only tolerated the error -- the delayed server would never see an auth
 * line and this test would time out on accept().
 */
class ChildWrapperReconnectTest {

    @Test
    void wrapperRetriesUntilTheParentStartsListening() throws Exception {
        // Reserve a loopback port but do NOT listen on it yet, so the wrapper's
        // first connection attempt is refused.
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        String token = "recon-secret-token-1234";

        // A trivial child that emits ready on an interval: a message sent while the
        // wrapper is still disconnected is dropped, so emitting repeatedly guarantees
        // one lands AFTER the wrapper reconnects. It exits on its own so nothing lingers.
        Path childScript = Files.createTempFile("hh-recon-child", ".js");
        Files.writeString(childScript,
            "setInterval(function(){try{process.send({type:'ready'});}catch(e){}},200);"
            + "setTimeout(function(){process.exit(0);},9000);");
        childScript.toFile().deleteOnExit();

        Path wrapper = ChildWrapper.path();
        ProcessBuilder pb = new ProcessBuilder("node", wrapper.toString(),
            "--hh-exec", childScript.toString());
        pb.environment().put("HOHENHEIM_IPC_PORT", String.valueOf(port));
        pb.environment().put("HOHENHEIM_IPC_TOKEN", token);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();

        try {
            // Only NOW, after the wrapper has already been refused at least once,
            // start listening on that exact port.
            Thread.sleep(700);
            try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                server.setSoTimeout(20_000);
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(20_000);
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));

                    // 1. The reconnecting wrapper authenticates on its first line.
                    String first = reader.readLine();
                    assertThat(first)
                        .as("the reconnecting wrapper authenticates first")
                        .isNotNull()
                        .contains("\"type\":\"auth\"")
                        .contains(token);

                    // 2. The child's ready is bridged once the wrapper is reconnected.
                    boolean gotReady = false;
                    for (int i = 0; i < 60 && !gotReady; i++) {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        if (line.contains("\"type\":\"ready\"")) {
                            gotReady = true;
                        }
                    }
                    assertThat(gotReady)
                        .as("the child's ready is bridged after the wrapper reconnects")
                        .isTrue();
                }
            }
        } finally {
            proc.destroy();
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        }
    }
}
