package be.elevenways.hohenheim.test;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks ports for tests that bind, publish or dial a number the kernel must not hand to anything else meanwhile.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
// AIDEV-NOTE: never new ServerSocket(0) for a port that is bound or dialled later: the number comes from the
// kernel's ephemeral range, which any process's outgoing sockets (a DNS lookup's UDP source port) also draw from,
// so it can be taken between the probe and the real bind. A full lane lost a UDP rebind that way ("Address already
// in use"). A socket the test itself holds open for the whole assertion is not affected and needs no picker.
public final class FreePorts {

    /** The lowest port handed out: above the well-known and most registered services' ports. */
    private static final int LOWEST_PORT = 20_000;

    /** Linux's default ephemeral floor, used when the host does not say. */
    private static final int DEFAULT_EPHEMERAL_FLOOR = 32_768;

    /** Ports this JVM already handed out, so two tests in one JVM never receive the same number. */
    private static final Set<Integer> HANDED_OUT = new HashSet<>();

    private FreePorts() {
    }

    /**
     * Picks a port below the kernel's ephemeral range that is free on every address for TCP and for UDP alike.
     *
     * @return the port; it stays reserved against other callers of this class for the life of the JVM
     * @throws IOException when no such port is found
     */
    public static synchronized int tcpAndUdp() throws IOException {
        int ceiling = ephemeralFloor();
        if (ceiling <= LOWEST_PORT) {
            throw new IllegalStateException("the kernel's ephemeral range starts at " + ceiling
                + ", leaving no port below it for a test");
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(LOWEST_PORT, ceiling);
            if (!HANDED_OUT.contains(port) && freeForBoth(port)) {
                HANDED_OUT.add(port);
                return port;
            }
        }
        throw new IOException("no port below " + ceiling + " is free for TCP and UDP");
    }

    private static int ephemeralFloor() {
        try {
            // AIDEV-NOTE: never Files.readString here: a /proc file reports size 0, and JDK 25's readString then
            // returned only its first byte ("3" of "32768").
            List<String> range = Files.readAllLines(Path.of("/proc/sys/net/ipv4/ip_local_port_range"));
            return Integer.parseInt(range.getFirst().trim().split("\\s+")[0]);
        } catch (IOException | RuntimeException unreadable) {
            return DEFAULT_EPHEMERAL_FLOOR;
        }
    }

    private static boolean freeForBoth(int port) {
        try (ServerSocket tcp = new ServerSocket(); DatagramSocket udp = new DatagramSocket(null)) {
            tcp.bind(new InetSocketAddress("0.0.0.0", port));
            udp.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException taken) {
            return false;
        }
    }
}
