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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrapper's auth-first ordering and message retention, against a real ONE-SHOT
 * child that emits {@code ready} exactly once, immediately. The old wrapper failed
 * this twice over: a message emitted before the connect callback entered the socket's
 * Writable queue AHEAD of the auth line (the parent refused the peer), and a message
 * emitted while no socket existed was dropped outright -- a one-shot {@code ready}
 * during a reconnect was lost forever and the parent killed the child at the ready
 * timeout. The fix queues on the wrapper itself, gated on AUTH WRITTEN, with the
 * one-shot {@code ready} held as a sticky value outside the bounded buffer.
 */
class ChildWrapperIpcJourneyTest {

    @Test
    void authIsTheFirstLineAndAOneShotReadySurvivesAnInitialRefusal() throws Exception {
        // Reserve a loopback port but do NOT listen on it yet, so the wrapper's
        // first connection attempts are refused while the child is already emitting.
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        String token = "journey-secret-token-1234";

        // A ONE-SHOT child: ready exactly once, immediately, then two ordered
        // follow-ups -- all emitted while the wrapper is still disconnected.
        Path childScript = Files.createTempFile("hh-oneshot-child", ".js");
        Files.writeString(childScript,
            "process.send({type:'ready'});"
            + "setTimeout(function(){process.send({type:'remcache_set',key:'first',value:'1'});},50);"
            + "setTimeout(function(){process.send({type:'remcache_set',key:'second',value:'2'});},100);"
            + "setTimeout(function(){process.exit(0);},15000);");
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
            // Only NOW, after the wrapper has been refused at least once and the child
            // has already emitted ready + both follow-ups, start listening.
            Thread.sleep(700);
            try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
                server.setSoTimeout(20_000);
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(20_000);
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));

                    // 1. Auth is the observed FIRST line on the wire -- nothing the child
                    //    emitted earlier may overtake it.
                    String first = reader.readLine();
                    assertThat(first)
                        .as("1. auth is the observed first line on the wire")
                        .isNotNull()
                        .contains("\"type\":\"auth\"")
                        .contains(token);

                    // 2. Both buffered follow-ups arrive, in FIFO order, and the one-shot
                    //    ready arrived despite every pre-listen connect being refused.
                    List<String> lines = new ArrayList<>();
                    while (lines.stream().noneMatch(line -> line.contains("\"key\":\"second\""))
                            && lines.size() < 32) {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        lines.add(line);
                    }
                    long readyCount = lines.stream()
                        .filter(line -> line.contains("\"type\":\"ready\"")).count();
                    assertThat(readyCount)
                        .as("2. the one-shot ready arrives exactly once after the initial refusals")
                        .isEqualTo(1);
                    int firstIdx = indexOfLine(lines, "\"key\":\"first\"");
                    int secondIdx = indexOfLine(lines, "\"key\":\"second\"");
                    assertThat(firstIdx)
                        .as("2. the first follow-up was retained across the refusals")
                        .isNotNegative();
                    assertThat(secondIdx)
                        .as("2. the second follow-up was retained across the refusals")
                        .isNotNegative();
                    assertThat(firstIdx)
                        .as("2. FIFO order is preserved for the buffered follow-ups")
                        .isLessThan(secondIdx);
                }
            }
        } finally {
            proc.destroy();
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
            }
        }
    }

    private static int indexOfLine(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
