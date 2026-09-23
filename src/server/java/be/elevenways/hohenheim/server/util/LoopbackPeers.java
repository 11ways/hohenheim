package be.elevenways.hohenheim.server.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Who owns the other end of a loopback TCP connection, read from the kernel's socket tables.
 *
 * AIDEV-NOTE: this is the loopback stand-in for SO_PEERCRED, which only AF_UNIX offers. A
 * loopback listener is reachable by every local account, so a bridge that splices whatever
 * connects to it hands that account whatever the bridge can reach. The kernel records the
 * owning uid of every TCP socket in /proc/net/tcp and /proc/net/tcp6; the connecting socket's
 * row (its local port is the peer port we accepted, its remote port is ours) names the uid
 * that opened it. Anything unreadable, missing or ambiguous answers null, and every caller
 * treats null as REFUSE. The tables are streamed, not buffered: their size grows with the
 * host's socket count, and the read happens once per bridged connection.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class LoopbackPeers {

    private static final List<Path> TABLES = List.of(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"));

    /** The ESTABLISHED state code in the kernel's socket tables. */
    private static final String ESTABLISHED = "01";

    private LoopbackPeers() {
    }

    /**
     * The uid owning the peer socket of an accepted loopback connection.
     *
     * @param peerPort  the connecting socket's port (the accepted channel's remote port)
     * @param localPort the port the connection was accepted on
     * @return the owning uid, or null when it cannot be established unambiguously
     */
    public static @Nullable Integer ownerOf(int peerPort, int localPort) {
        Integer owner = null;
        for (Path table : TABLES) {
            Integer found;
            try (BufferedReader reader = Files.newBufferedReader(table, StandardCharsets.US_ASCII)) {
                found = ownerIn(reader.lines()::iterator, peerPort, localPort);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            if (found == null) {
                continue;
            }
            if (owner != null && !owner.equals(found)) {
                return null;
            }
            owner = found;
        }
        return owner;
    }

    /**
     * The uid in one socket table whose row has this local and remote port, both on loopback.
     *
     * @return the uid, or null when no row or disagreeing rows match
     */
    public static @Nullable Integer ownerIn(@NonNull Iterable<String> lines, int peerPort, int localPort) {
        String wantedLocal = ":" + hexPort(peerPort);
        String wantedRemote = ":" + hexPort(localPort);
        Integer owner = null;
        for (String line : lines) {
            String[] columns = line.trim().split("\\s+");
            // sl local_address rem_address st tx:rx tr:when retrnsmt uid ...
            if (columns.length < 8 || !columns[0].endsWith(":")) {
                continue;
            }
            if (!columns[1].toUpperCase(Locale.ROOT).endsWith(wantedLocal)
                    || !columns[2].toUpperCase(Locale.ROOT).endsWith(wantedRemote)
                    || !ESTABLISHED.equals(columns[3])
                    || !isLoopback(columns[1]) || !isLoopback(columns[2])) {
                continue;
            }
            int uid;
            try {
                uid = Integer.parseInt(columns[7]);
            } catch (NumberFormatException malformed) {
                return null;
            }
            if (owner != null && owner != uid) {
                return null;
            }
            owner = uid;
        }
        return owner;
    }

    /** The uid this process runs as, or null when it cannot be read. */
    public static @Nullable Integer selfUid() {
        try {
            Object uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid");
            return uid instanceof Integer value ? value : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String hexPort(int port) {
        return String.format(Locale.ROOT, "%04X", port);
    }

    /**
     * Whether a table address (hex, host byte order) is loopback: 127.0.0.0/8 in either byte
     * order for IPv4, ::1 or an IPv4-mapped 127.x for IPv6.
     */
    private static boolean isLoopback(String column) {
        int colon = column.indexOf(':');
        if (colon < 0) {
            return false;
        }
        String address = column.substring(0, colon).toUpperCase(Locale.ROOT);
        if (address.length() == 8) {
            return address.endsWith("7F") || address.startsWith("7F");
        }
        if (address.length() == 32) {
            if (address.equals("00000000000000000000000001000000")
                    || address.equals("00000000000000000000000000000001")) {
                return true;
            }
            // ::ffff:a.b.c.d is stored as three 32-bit words of prefix and the IPv4 word.
            String prefix = address.substring(0, 24);
            String ipv4 = address.substring(24);
            boolean mapped = prefix.equals("0000000000000000FFFF0000")
                || prefix.equals("00000000000000000000FFFF");
            return mapped && (ipv4.endsWith("7F") || ipv4.startsWith("7F"));
        }
        return false;
    }
}
