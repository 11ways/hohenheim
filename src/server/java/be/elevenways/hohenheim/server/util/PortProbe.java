package be.elevenways.hohenheim.server.util;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * THE local-host bind probe: answers whether a port is free on THIS machine, only for
 * the instant it runs. The one observer that can see consumers no ledger will ever
 * contain (IpcChannel's kernel-ephemeral sockets, testcontainers, orphaned children),
 * and worthless for a REMOTE host -- probing locally answers a question about the
 * controller, never about the remote machine.
 */
public final class PortProbe {

    private PortProbe() {
    }

    /**
     * @param address bind address to probe; blank probes the whole host ({@code 0.0.0.0})
     * @param protocol {@code tcp} or {@code udp}
     * @return whether the bind succeeded (the port was free at that instant)
     */
    public static boolean isFree(String address, int port, String protocol) {
        String bind = address == null || address.isBlank() ? "0.0.0.0" : address;
        if ("udp".equals(protocol)) {
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(bind, port));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bind, port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
