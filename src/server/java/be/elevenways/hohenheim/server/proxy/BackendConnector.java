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
import java.net.UnknownHostException;
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
 * AIDEV-NOTE: a tenant-owned route's host is judged by zenit's outbound guard through
 * {@link TenantUpstreams#vet} (public addresses only) and only the addresses it vetted are dialed.
 * An operator route resolves through the installed {@link OutboundNetwork} resolver and nothing
 * else, as InetAddress.getAllByName did before: the guard's URL parse refuses a Docker-style name
 * such as backend_1, which the resolver accepts. Every address is dialed through
 * {@link OutboundNetwork#connect}. An operator lookup that fails is never remembered, so a backend
 * that starts resolving is dialed on the next connection. What stays here is the passthrough's own
 * need: a lookup the accept path waits for at most until the connect deadline (a bounded pool, one
 * lookup per host in flight), the address-family interleave and the refusal to dial Hohenheim's own
 * public listener.
 */
final class BackendConnector {

    private static final ExecutorService VETTING = new ThreadPoolExecutor(
        4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "tls-backend-vetting");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static final ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>> IN_FLIGHT =
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
        List<InetAddress> addresses = happyEyeballsOrder(IpLiterals.parse(host) != null
            ? addresses(host, publicOnly)
            : awaitAddresses(host, publicOnly, deadline));
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

    /**
     * The addresses a route may dial: the guard's vetted ones for a tenant route, whatever the
     * resolver answers for an operator route. May block on DNS.
     */
    private static List<InetAddress> addresses(String host, boolean publicOnly) throws IOException {
        if (publicOnly) {
            return switch (TenantUpstreams.vet("https", host, true)) {
                case OutboundUrlGuard.Allowed allowed -> allowed.addresses();
                case OutboundUrlGuard.Refused refused ->
                    throw new IOException("TLS passthrough target refused: " + refused.reason());
            };
        }
        InetAddress[] resolved = OutboundNetwork.SEAM.require().resolver().resolve(host);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException("TLS passthrough target " + host + " resolves to no address");
        }
        return List.of(resolved);
    }

    private static List<InetAddress> awaitAddresses(String host, boolean publicOnly, long deadline)
            throws IOException {
        CompletableFuture<List<InetAddress>> lookup = sharedLookup(host, publicOnly);
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
            if (e.getCause() instanceof IOException failure) throw failure;
            throw new IOException("backend vetting failed", e.getCause());
        }
    }

    /** The one lookup in flight for {@code host}, joined by every connection that asks while it runs. */
    static CompletableFuture<List<InetAddress>> sharedLookup(String host, boolean publicOnly)
            throws IOException {
        String key = (publicOnly ? "public|" : "any|") + host;
        CompletableFuture<List<InetAddress>> created = new CompletableFuture<>();
        CompletableFuture<List<InetAddress>> existing = IN_FLIGHT.putIfAbsent(key, created);
        if (existing != null) return existing;
        try {
            VETTING.execute(() -> {
                List<InetAddress> answer = null;
                Throwable failure = null;
                try {
                    answer = addresses(host, publicOnly);
                } catch (Throwable thrown) {
                    failure = thrown;
                }
                // AIDEV-NOTE: the lookup leaves IN_FLIGHT BEFORE its answer is published. A waiter
                // woken by the answer (a dependent stage runs on this very thread) must start a fresh
                // lookup when it asks again; removing after completion handed it this finished,
                // failed future back, so a failed operator lookup WAS remembered for that retry.
                IN_FLIGHT.remove(key, created);
                if (failure == null) {
                    created.complete(answer);
                } else {
                    created.completeExceptionally(failure);
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
