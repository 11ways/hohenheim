package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.server.security.IpLiterals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Matches a domain's optional listener-address restriction against an accepted connection. */
final class ListenerAddressMatcher {

    private ListenerAddressMatcher() {}

    static List<String> parse(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.split("[,\\s]+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                String normalized = normalize(part);
                if ("any".equals(normalized)) return List.of();
                result.add(normalized);
            }
        }
        result = new ArrayList<>(new LinkedHashSet<>(result));
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    static boolean matches(List<String> configured, String listenerIp) {
        if (configured.isEmpty() || listenerIp == null || listenerIp.isBlank()) {
            return true;
        }
        String normalized = normalize(listenerIp);
        for (String candidate : configured) {
            if (normalized.equals(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }

    /** @return the overlapping listener scope, empty for any, or null when disjoint */
    static List<String> intersection(List<String> first, List<String> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        List<String> overlap = new ArrayList<>();
        for (String candidate : first) {
            if (second.contains(candidate)) overlap.add(candidate);
        }
        return overlap.isEmpty() ? null : List.copyOf(overlap);
    }

    static int specificity(List<String> configured) {
        return configured.isEmpty() ? 0 : 10_000 - configured.size();
    }

    private static String normalize(String value) {
        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("::ffff:")) {
            normalized = normalized.substring("::ffff:".length());
        }
        byte[] bytes = IpLiterals.parse(normalized);
        if (bytes == null) return normalized;
        if (isIpv4Mapped(bytes)) bytes = Arrays.copyOfRange(bytes, 12, 16);
        StringBuilder key = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) key.append(String.format("%02x", item & 0xff));
        return key.toString();
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        if (bytes.length != 16 || bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff) return false;
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return true;
    }
}
