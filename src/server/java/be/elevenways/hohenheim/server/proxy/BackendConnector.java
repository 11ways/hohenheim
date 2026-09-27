package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.security.IpLiterals;
import be.elevenways.hohenheim.server.upstream.TenantUpstreams;
import be.elevenways.zenit.server.net.OutboundNetwork;
import be.elevenways.zenit.server.net.OutboundUrlGuard;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Vets and connects TLS passthrough backends within one bounded deadline.
 *
 * AIDEV-NOTE: the host is judged by zenit's outbound guard through {@link TenantUpstreams#vet}
 * (public addresses only for a tenant-owned route, every address for an operator's) and only the
 * addresses it vetted are dialed, each through {@link OutboundNetwork#connect}: no resolver, DNS
 * cache or address classification of its own. What stays here is the passthrough's own need: a
 * lookup the accept path waits for at most until the connect deadline (a bounded pool, one lookup
 * per host in flight), the address-family interleave and the refusal to dial Hohenheim's own
 * public listener.
 */
final class BackendConnector {

    private static final ExecutorService VETTING = new ThreadPoolExecutor(
        4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "tls-backend-vetting");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static final ConcurrentHashMap<String, CompletableFuture<OutboundUrlGuard.Verdict>> IN_FLIGHT =
        new ConcurrentHashMap<>();

    private BackendConnector() {}

    /**
     * @param publicOnly the route belongs to a tenant-owned site: only public addresses are vetted,
     *                   and the connector dials exactly the addresses the guard judged
     * @throws IOException when the target is refused, cannot be vetted in time or no address connects
     */
    static Socket connect(String host, int port, int timeoutMillis, int publicTlsPort,
                          boolean publicOnly) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<InetAddress> addresses = happyEyeballsOrder(vet(host, publicOnly, deadline).addresses());
        IOException lastFailure = null;
        int remainingCandidates = addresses.size();
        for (InetAddress address : addresses) {
            if (isLocalListener(address, port, publicTlsPort)) {
                lastFailure = new IOException("TLS passthrough target resolves to Hohenheim's public listener");
                remainingCandidates--;
                continue;
            }
            long remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) break;
            int attemptMillis = (int) Math.min(remainingMillis,
                Math.max(250, remainingMillis / Math.max(1, remainingCandidates)));
            try {
                return OutboundNetwork.connect(address, port, attemptMillis);
            } catch (IOException e) {
                lastFailure = e;
            }
            remainingCandidates--;
        }
        if (lastFailure != null) throw lastFailure;
        throw new SocketTimeoutException("TLS passthrough connection deadline exceeded");
    }

    /** The guard's verdict for the host, a literal inline and a name within the deadline. */
    private static OutboundUrlGuard.Allowed vet(String host, boolean publicOnly, long deadline) throws IOException {
        OutboundUrlGuard.Verdict verdict = IpLiterals.parse(host) != null
            ? TenantUpstreams.vet("https", host, publicOnly)
            : awaitVerdict(host, publicOnly, deadline);
        return switch (verdict) {
            case OutboundUrlGuard.Allowed allowed -> allowed;
            case OutboundUrlGuard.Refused refused ->
                throw new IOException("TLS passthrough target refused: " + refused.reason());
        };
    }

    private static OutboundUrlGuard.Verdict awaitVerdict(String host, boolean publicOnly, long deadline)
            throws IOException {
        CompletableFuture<OutboundUrlGuard.Verdict> lookup = sharedLookup(host, publicOnly);
        try {
            long remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) throw new SocketTimeoutException("backend DNS deadline exceeded");
            return lookup.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new SocketTimeoutException("backend DNS deadline exceeded for " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("backend DNS lookup interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("backend vetting failed", e.getCause());
        }
    }

    private static CompletableFuture<OutboundUrlGuard.Verdict> sharedLookup(String host, boolean publicOnly)
            throws IOException {
        String key = (publicOnly ? "public|" : "any|") + host;
        CompletableFuture<OutboundUrlGuard.Verdict> created = new CompletableFuture<>();
        CompletableFuture<OutboundUrlGuard.Verdict> existing = IN_FLIGHT.putIfAbsent(key, created);
        if (existing != null) return existing;
        try {
            VETTING.execute(() -> {
                try {
                    created.complete(TenantUpstreams.vet("https", host, publicOnly));
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    IN_FLIGHT.remove(key, created);
                }
            });
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.remove(key, created);
            throw new IOException("backend DNS queue is full", e);
        }
        return created;
    }

    /** Alternates address families while preserving each family's resolver order. */
    private static List<InetAddress> happyEyeballsOrder(List<InetAddress> input) {
        List<InetAddress> v4 = new ArrayList<>();
        List<InetAddress> v6 = new ArrayList<>();
        for (InetAddress address : input) {
            if (address instanceof Inet6Address) v6.add(address);
            else if (address instanceof Inet4Address) v4.add(address);
        }
        boolean preferV6 = !input.isEmpty() && input.getFirst() instanceof Inet6Address;
        List<InetAddress> result = new ArrayList<>(input.size());
        for (int i = 0; i < Math.max(v4.size(), v6.size()); i++) {
            if (preferV6) {
                if (i < v6.size()) result.add(v6.get(i));
                if (i < v4.size()) result.add(v4.get(i));
            } else {
                if (i < v4.size()) result.add(v4.get(i));
                if (i < v6.size()) result.add(v6.get(i));
            }
        }
        return result;
    }

    private static boolean isLocalListener(InetAddress address, int port, int publicTlsPort) {
        if (port != publicTlsPort) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()) return true;
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static long remainingMillis(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }
}
