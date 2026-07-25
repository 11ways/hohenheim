package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.security.IpLiterals;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves and connects TLS passthrough backends within one bounded deadline. */
final class BackendConnector {

    private static final long DNS_CACHE_MILLIS = 60_000;
    private static final ExecutorService DNS = new ThreadPoolExecutor(
        4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "tls-backend-dns");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private static final ConcurrentHashMap<String, CachedAddresses> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>> IN_FLIGHT =
        new ConcurrentHashMap<>();

    private record CachedAddresses(List<InetAddress> addresses, long resolvedAt) {}

    private BackendConnector() {}

    static Socket connect(String host, int port, int timeoutMillis, int publicTlsPort) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<InetAddress> addresses = resolve(host, deadline);
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
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), attemptMillis);
                return socket;
            } catch (IOException e) {
                lastFailure = e;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            remainingCandidates--;
        }
        if (lastFailure != null) throw lastFailure;
        throw new SocketTimeoutException("TLS passthrough connection deadline exceeded");
    }

    private static List<InetAddress> resolve(String host, long deadline) throws IOException {
        byte[] literal = IpLiterals.parse(host);
        if (literal != null) {
            return List.of(InetAddress.getByAddress(literal));
        }
        long now = System.currentTimeMillis();
        CachedAddresses cached = CACHE.get(host);
        if (cached != null && now - cached.resolvedAt() < DNS_CACHE_MILLIS) {
            return cached.addresses();
        }

        CompletableFuture<List<InetAddress>> lookup = sharedLookup(host);
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
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("backend DNS lookup failed", cause);
        }
    }

    private static CompletableFuture<List<InetAddress>> sharedLookup(String host) throws IOException {
        CompletableFuture<List<InetAddress>> created = new CompletableFuture<>();
        CompletableFuture<List<InetAddress>> existing = IN_FLIGHT.putIfAbsent(host, created);
        if (existing != null) return existing;
        try {
            DNS.execute(() -> {
                try {
                    List<InetAddress> addresses = happyEyeballsOrder(
                        Arrays.asList(resolveAll(host)));
                    if (addresses.isEmpty()) throw new UnknownHostException(host);
                    List<InetAddress> frozen = List.copyOf(addresses);
                    CACHE.put(host, new CachedAddresses(frozen, System.currentTimeMillis()));
                    created.complete(frozen);
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                } finally {
                    IN_FLIGHT.remove(host, created);
                }
            });
        } catch (RejectedExecutionException e) {
            IN_FLIGHT.remove(host, created);
            throw new IOException("backend DNS queue is full", e);
        }
        return created;
    }

    private static InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
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
