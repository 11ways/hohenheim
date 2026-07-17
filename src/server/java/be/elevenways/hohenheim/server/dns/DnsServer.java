package be.elevenways.hohenheim.server.dns;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Authoritative-only DNS listeners on public UDP and TCP (default port 53).
 * Serves whatever {@link DnsZoneStore} currently holds; zone edits swap the
 * snapshot and need no listener restart.
 */
public final class DnsServer {

    private static final int UDP_RECEIVE_SIZE = 4096;
    private static final int TCP_MAX_QUERY = 16384;
    private static final int TCP_IDLE_TIMEOUT_MS = 30_000;
    private static final int TCP_MAX_CONNECTIONS = 128;

    private final DnsResponder responder = new DnsResponder(DnsZoneStore.INSTANCE);
    private final Semaphore tcpConnections = new Semaphore(TCP_MAX_CONNECTIONS);

    private volatile boolean running;
    private volatile @Nullable String startupError;
    private @Nullable DatagramSocket udpSocket;
    private @Nullable ServerSocket tcpSocket;
    private @Nullable ExecutorService workers;

    /** @return true when the server is enabled in settings and both listeners bound */
    public boolean startIfEnabled() {
        Boolean enabled = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.ENABLED);
        if (!Boolean.TRUE.equals(enabled)) {
            return false;
        }

        String bindAddress = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.BIND_ADDRESS);
        Integer port = HohenheimSettings.VALUES.getValue(HohenheimSettings.Dns.PORT);

        try {
            this.start(bindAddress != null ? bindAddress : "0.0.0.0", port != null ? port : 53);
            return true;
        }
        catch (IOException e) {
            this.startupError = e.getMessage();
            Blast.log("DNS: failed to bind listeners:", e.getMessage());
            return false;
        }
    }

    public synchronized void start(@NonNull String bindAddress, int port) throws IOException {
        if (this.running) {
            return;
        }

        InetAddress address = InetAddress.getByName(bindAddress);

        // Both transports must share ONE port. With an ephemeral request
        // (port 0) the UDP bind picks the number, and the TCP bind on that
        // same number can lose the race, so retry with a fresh pick.
        DatagramSocket udp = null;
        ServerSocket tcp = null;
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5 && tcp == null; attempt++) {
            udp = new DatagramSocket(new InetSocketAddress(address, port));
            try {
                tcp = new ServerSocket();
                tcp.bind(new InetSocketAddress(address, udp.getLocalPort()));
            }
            catch (IOException e) {
                lastFailure = e;
                udp.close();
                udp = null;
                tcp = null;
                if (port != 0) {
                    break;
                }
            }
        }
        if (udp == null || tcp == null) {
            throw lastFailure != null ? lastFailure : new IOException("DNS listeners could not bind");
        }
        final DatagramSocket udpBound = udp;
        final ServerSocket tcpBound = tcp;

        this.udpSocket = udpBound;
        this.tcpSocket = tcpBound;
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.running = true;
        this.startupError = null;

        Thread udpThread = new Thread(() -> this.udpLoop(udpBound), "hohenheim-dns-udp");
        udpThread.setDaemon(true);
        udpThread.start();

        Thread tcpThread = new Thread(() -> this.tcpLoop(tcpBound), "hohenheim-dns-tcp");
        tcpThread.setDaemon(true);
        tcpThread.start();

        Blast.log("DNS: authoritative listeners on", bindAddress + ":" + udpBound.getLocalPort(), "(udp+tcp)");
    }

    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        closeQuietly(this.udpSocket);
        closeSocketQuietly(this.tcpSocket);
        this.udpSocket = null;
        this.tcpSocket = null;
        if (this.workers != null) {
            this.workers.shutdown();
            this.workers = null;
        }
    }

    public boolean isRunning() {
        return this.running;
    }

    /** @return the bind failure message from the last enabled start attempt, or null */
    public @Nullable String getStartupError() {
        return this.startupError;
    }

    public int getUdpPort() {
        DatagramSocket socket = this.udpSocket;
        return socket != null ? socket.getLocalPort() : -1;
    }

    private void udpLoop(@NonNull DatagramSocket socket) {
        while (this.running) {
            byte[] buffer = new byte[UDP_RECEIVE_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            }
            catch (IOException e) {
                if (this.running) {
                    Blast.log("DNS: udp receive failed:", e.getMessage());
                }
                continue;
            }

            byte[] wire = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), wire, 0, packet.getLength());
            InetSocketAddress client = (InetSocketAddress) packet.getSocketAddress();

            ExecutorService pool = this.workers;
            if (pool == null) {
                return;
            }
            pool.execute(() -> {
                byte[] reply = this.responder.respondToWire(wire, false);
                if (reply == null) {
                    return;
                }
                try {
                    socket.send(new DatagramPacket(reply, reply.length, client));
                }
                catch (IOException e) {
                    if (this.running) {
                        Blast.log("DNS: udp send failed:", e.getMessage());
                    }
                }
            });
        }
    }

    private void tcpLoop(@NonNull ServerSocket serverSocket) {
        while (this.running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            }
            catch (IOException e) {
                if (this.running) {
                    Blast.log("DNS: tcp accept failed:", e.getMessage());
                }
                continue;
            }

            if (!this.tcpConnections.tryAcquire()) {
                closeSocketQuietly(socket);
                continue;
            }

            ExecutorService pool = this.workers;
            if (pool == null) {
                this.tcpConnections.release();
                closeSocketQuietly(socket);
                return;
            }
            pool.execute(() -> {
                try {
                    this.handleTcpConnection(socket);
                }
                finally {
                    this.tcpConnections.release();
                    closeSocketQuietly(socket);
                }
            });
        }
    }

    private void handleTcpConnection(@NonNull Socket socket) {
        try {
            socket.setSoTimeout(TCP_IDLE_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            while (this.running) {
                int length;
                try {
                    length = in.readUnsignedShort();
                }
                catch (EOFException | SocketTimeoutException e) {
                    return;
                }

                if (length == 0 || length > TCP_MAX_QUERY) {
                    return;
                }

                byte[] wire = new byte[length];
                in.readFully(wire);

                byte[] reply = this.responder.respondToWire(wire, true);
                if (reply == null) {
                    return;
                }

                out.writeShort(reply.length);
                out.write(reply);
                out.flush();
            }
        }
        catch (IOException e) {
            // Peer went away mid-exchange; nothing to salvage.
        }
    }

    private static void closeQuietly(@Nullable DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    private static void closeSocketQuietly(@Nullable ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }
    }

    private static void closeSocketQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            }
            catch (IOException ignored) {
            }
        }
    }
}
