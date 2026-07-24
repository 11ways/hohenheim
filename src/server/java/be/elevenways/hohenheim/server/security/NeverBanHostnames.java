package be.elevenways.hohenheim.server.security;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.protoblast.common.Blast;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hostname support for the {@code security.never_ban} allowlist: entries that
 * are neither a literal IP nor a CIDR are hostnames, resolved (A + AAAA) in
 * the BACKGROUND only -- the ban paths consult the pre-resolved snapshot and
 * never touch DNS. A failed resolution keeps the last successfully resolved
 * addresses (fail-safe for a dynamic-IP operator), and resolved v6 addresses
 * protect their whole /64, consistent with the /64 ban granularity.
 */
public final class NeverBanHostnames {
    private static final Resolver DEFAULT_RESOLVER = hostname -> {
        List<String> addresses = new ArrayList<>();
        for (InetAddress address : InetAddress.getAllByName(hostname)) {
            addresses.add(address.getHostAddress());
        }
        return addresses;
    };

    // AIDEV-NOTE: DEFAULT_RESOLVER must be initialized before INSTANCE. Java
    // initializes static fields in source order; constructing INSTANCE first
    // leaves its resolver permanently null on a real boot (tests that replace
    // the resolver after class loading can accidentally mask this).
    public static final NeverBanHostnames INSTANCE = new NeverBanHostnames();

    /** DNS seam so tests never hit real DNS. */
    interface Resolver {
        /** @throws Exception when resolution fails (previous addresses stay protected) */
        @NonNull List<String> resolve(@NonNull String hostname) throws Exception;
    }

    private volatile Resolver resolver;

    // Last successfully resolved protection keys (v4 address or v6 /64) per hostname.
    private final ConcurrentHashMap<String, Set<String>> keysByHost = new ConcurrentHashMap<>();
    // Flat snapshot the ban-path checks read; swapped atomically after a refresh.
    private volatile Set<String> protectedKeys = Set.of();

    NeverBanHostnames() {
        this.resolver = Objects.requireNonNull(DEFAULT_RESOLVER);
    }

    /**
     * O(1) ban-path check: whether this normalized ban key (exact v4 address
     * or v6 {@code <network>/64}) belongs to a resolved never_ban hostname.
     */
    public boolean protects(@NonNull String banKey) {
        return this.protectedKeys.contains(banKey);
    }

    /**
     * Re-resolve every hostname entry of {@code security.never_ban}; called by
     * the SecuritySweep task (boot + hourly), NEVER from a request/ban path.
     */
    public synchronized void refresh() {
        List<String> list = HohenheimSettings.VALUES.getValue(HohenheimSettings.Security.NEVER_BAN);
        Set<String> hostnames = extractHostnames(list);

        // Hostnames removed from the setting lose their protection.
        this.keysByHost.keySet().retainAll(hostnames);

        for (String hostname : hostnames) {
            try {
                Set<String> keys = new HashSet<>();
                for (String address : this.resolver.resolve(hostname)) {
                    String key = IpLiterals.subnetKey(address);
                    if (key != null) {
                        keys.add(key);
                    }
                }
                if (keys.isEmpty()) {
                    throw new IllegalStateException("resolved to no usable addresses");
                }
                this.keysByHost.put(hostname, Set.copyOf(keys));
            } catch (Exception e) {
                // Fail-safe: the previously resolved addresses stay protected.
                Blast.log("SECURITY: never_ban hostname", hostname,
                    "did not resolve -", e.getMessage(),
                    this.keysByHost.containsKey(hostname)
                        ? "(keeping the previously resolved addresses)"
                        : "(no previous resolution, entry currently protects nothing)");
            }
        }

        Set<String> snapshot = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : this.keysByHost.entrySet()) {
            snapshot.addAll(entry.getValue());
        }
        this.protectedKeys = Set.copyOf(snapshot);
    }

    /** The hostname entries of a never_ban value: not blank, no slash, not a literal IP. */
    static @NonNull Set<String> extractHostnames(@Nullable List<String> list) {
        Set<String> hostnames = new LinkedHashSet<>();
        if (list == null || list.isEmpty()) {
            return hostnames;
        }
        for (String raw : list) {
            String entry = raw.trim();
            if (entry.isEmpty() || entry.indexOf('/') >= 0 || IpLiterals.isLiteral(entry)) {
                continue;
            }
            hostnames.add(entry);
        }
        return hostnames;
    }

    /** Test seam: replace the DNS resolver (null restores the real one). */
    void setResolver(@Nullable Resolver replacement) {
        this.resolver = replacement != null ? replacement : DEFAULT_RESOLVER;
    }

    /** Test seam: forget every resolved address. */
    void resetForTests() {
        this.keysByHost.clear();
        this.protectedKeys = Set.of();
    }
}
