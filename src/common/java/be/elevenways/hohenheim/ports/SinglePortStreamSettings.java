package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.RawValues;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single-port shape a TCP/UDP stream site's settings had before a stream held a list of ports, and its one
 * conversion to and from that list ({@link StreamPort}'s {@code ports} map).
 *
 * AIDEV-NOTE: FROZEN. The old shape will never change: these are literal facts of 2026-11-25 (the keys
 * {@code listen_port}, {@code forward_port}, {@code protocol}, {@code proxy_protocol_v2} and the spelling
 * {@code "<forward>/<protocol>[ proxy-v2]"}), never StreamUpstreamKind's or StreamPort's constants, so a later change
 * to today's shape cannot move what M008_StreamPortLists converted. M008 converts the stored sites through it and
 * SiteModel reads every revision stored before M008 through {@link #revisionFields}.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SinglePortStreamSettings {

    /** The upstream kind of a TCP/UDP stream site. */
    public static final String STREAM_KIND = "hohenheim:stream";

    /** The site column holding its kind. */
    public static final String UPSTREAM_KIND = "upstream_kind";

    /** The site column holding its settings. */
    public static final String SETTINGS = "settings";

    private static final String PORTS = "ports";
    private static final String LISTEN_PORT = "listen_port";
    private static final String FORWARD_PORT = "forward_port";
    private static final String PROTOCOL = "protocol";
    private static final String PROXY_PROTOCOL_V2 = "proxy_protocol_v2";
    private static final String PROXY_V2 = "proxy-v2";
    private static final String TCP = "tcp";

    private SinglePortStreamSettings() {
    }

    /**
     * Turns one stream's single-port settings into its list of one, in place: no forward port forwards to the listen
     * port, as the relay did, no protocol is tcp, and every other key stays.
     *
     * @return whether the settings were in the single-port shape and now hold the list instead
     */
    public static boolean listOfOne(@NonNull Map<String, Object> settings) {
        if (settings.containsKey(PORTS) || settings.get(LISTEN_PORT) == null) {
            return false;
        }
        String listen = number(settings.remove(LISTEN_PORT));
        Object forward = settings.remove(FORWARD_PORT);
        Object protocol = settings.remove(PROTOCOL);
        String transport = protocol == null || String.valueOf(protocol).isBlank() ? TCP
            : String.valueOf(protocol).trim();
        // The single-port save refused the header beside UDP, so it only ever rode a TCP-only stream.
        boolean proxy = isOn(settings.remove(PROXY_PROTOCOL_V2)) && TCP.equals(transport);
        String spelled = (forward == null ? listen : number(forward)) + "/" + transport
            + (proxy ? " " + PROXY_V2 : "");
        Map<String, String> ports = new LinkedHashMap<>();
        ports.put(listen, spelled);
        settings.put(PORTS, ports);
        return true;
    }

    /**
     * Turns one stream's list of one port back into the single-port shape, in place.
     *
     * @return whether the settings held exactly one port and now hold the single-port keys instead
     */
    public static boolean singlePort(@NonNull Map<String, Object> settings) {
        if (!(settings.get(PORTS) instanceof Map<?, ?> ports) || ports.size() != 1) {
            return false;
        }
        settings.remove(PORTS);
        Map.Entry<?, ?> port = ports.entrySet().iterator().next();
        String[] words = String.valueOf(port.getValue()).trim().split("\\s+");
        String[] target = words[0].split("/", 2);
        settings.put(LISTEN_PORT, Integer.valueOf(String.valueOf(port.getKey()).trim()));
        settings.put(FORWARD_PORT, Integer.valueOf(target[0].trim()));
        settings.put(PROTOCOL, target.length > 1 ? target[1].trim() : TCP);
        settings.put(PROXY_PROTOCOL_V2, words.length > 1 && PROXY_V2.equals(words[1]));
        return true;
    }

    /** @return the public ports a stream's stored list holds, empty for the single-port shape or none */
    public static @NonNull Map<?, ?> portsOf(@NonNull Map<String, Object> settings) {
        return settings.get(PORTS) instanceof Map<?, ?> ports ? ports : Map.of();
    }

    /**
     * @return a site revision's fields in today's shape: a stream's single-port settings as their list of one
     *     ({@link #listOfOne}), any other revision as it was stored
     */
    public static @NonNull Map<String, Object> revisionFields(@NonNull Map<String, Object> fields) {
        if (STREAM_KIND.equals(String.valueOf(fields.get(UPSTREAM_KIND)))) {
            Map<String, Object> settings = RawValues.storedMapCopy(fields.get(SETTINGS));
            if (settings != null && listOfOne(settings)) {
                fields.put(SETTINGS, settings);
            }
        }
        return fields;
    }

    /** @return a stored number as the text of a whole number ({@code 25}, never {@code 25.0}) */
    private static @NonNull String number(@Nullable Object stored) {
        if (stored instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        return String.valueOf(stored).trim();
    }

    private static boolean isOn(@Nullable Object stored) {
        return Boolean.TRUE.equals(stored) || "true".equalsIgnoreCase(String.valueOf(stored).trim());
    }
}
