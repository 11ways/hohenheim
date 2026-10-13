package be.elevenways.hohenheim.ports;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.zenit.common.validation.Violations;
import be.elevenways.protoblast.common.util.BlastString;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One port a workload publishes on every address of its host, as its kind settings' {@code published_ports} map
 * declares it: the port's name (its {@link PortPurpose}) to Docker's own {@code [host:]container[/protocol]} spelling.
 *
 * AIDEV-NOTE: a template declares {@code "game": "25565/tcp"} (no host port); putting it online writes the host port
 * the ledger claimed ({@code "30001:25565/tcp"}), so the number is fixed from creation, as a Pterodactyl allocation is.
 * An empty host part means the deploy claims a free one. The single {@code container_port} publication beside it is
 * the routing tier's port and is not one of these.
 *
 * @param name          the port's name, its purpose when the vocabulary knows it
 * @param hostPort      the host port, or null for one the ledger picks
 * @param containerPort the port inside the workload
 * @param protocol      {@code tcp} or {@code udp}
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public record PublishedPort(@NonNull String name, @Nullable Integer hostPort, int containerPort,
                            @NonNull String protocol) {

    /** The kind settings key holding the map. */
    public static final String FIELD = "published_ports";

    /** The kind settings key naming the SRV service a game address publishes ({@code minecraft}). */
    public static final String SRV_SERVICE = "srv_service";

    public static final String TCP = "tcp";
    public static final String UDP = "udp";

    /** @return whether the settings declare any published port, without judging their spelling */
    public static boolean declaredIn(@NonNull Map<String, Object> settings) {
        return settings.get(FIELD) instanceof Map<?, ?> map && !map.isEmpty();
    }

    /**
     * @return every published port the settings declare, in declaration order
     * @throws Violations naming the first entry that is not {@code [host:]container[/protocol]}
     */
    public static @NonNull List<PublishedPort> of(@NonNull Map<String, Object> settings) {
        List<PublishedPort> ports = new ArrayList<>();
        if (settings.get(FIELD) instanceof Map<?, ?> map) {
            map.forEach((name, spec) -> ports.add(parse(String.valueOf(name), spec == null ? "" : spec.toString())));
        }
        return ports;
    }

    /**
     * @return the entry Docker's port spelling describes
     * @throws Violations when the spelling is not {@code [host:]container[/protocol]} with ports in 1-65535
     */
    public static @NonNull PublishedPort parse(@NonNull String name, @NonNull String spec) {
        String text = spec.trim();
        String protocol = TCP;
        int slash = text.indexOf('/');
        if (slash >= 0) {
            protocol = BlastString.lower(text.substring(slash + 1).trim());
            text = text.substring(0, slash).trim();
        }
        Integer host = null;
        int colon = text.indexOf(':');
        if (colon >= 0) {
            String hostText = text.substring(0, colon).trim();
            host = hostText.isEmpty() ? null : port(name, spec, hostText);
            text = text.substring(colon + 1).trim();
        }
        if (!TCP.equals(protocol) && !UDP.equals(protocol)) {
            throw invalid(name, spec);
        }
        return new PublishedPort(name.trim(), host, port(name, spec, text), protocol);
    }

    /** @return the purpose this port's name declares */
    public @NonNull PortPurpose purpose() {
        return PortPurpose.forName(this.name);
    }

    /** @return Docker's spelling of this entry, the value stored under its name */
    public @NonNull String spec() {
        return (this.hostPort == null ? "" : this.hostPort + ":") + this.containerPort + "/" + this.protocol;
    }

    /** @return this entry published on {@code port} of its host */
    public @NonNull PublishedPort withHostPort(int port) {
        return new PublishedPort(this.name, port, this.containerPort, this.protocol);
    }

    /** @return the settings map value for these entries, keyed by name */
    public static @NonNull Map<String, String> toSettings(@NonNull List<PublishedPort> ports) {
        Map<String, String> map = new LinkedHashMap<>();
        for (PublishedPort port : ports) {
            map.put(port.name(), port.spec());
        }
        return map;
    }

    private static int port(@NonNull String name, @NonNull String spec, @NonNull String text) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // refused below with the entry named
        }
        throw invalid(name, spec);
    }

    private static @NonNull Violations invalid(@NonNull String name, @NonNull String spec) {
        return Violations.ofField("settings." + FIELD, spec, HohenheimMicrocopy.VIOLATIONS.of("port_spec_invalid")
            .withArg("name", name).withArg("spec", spec));
    }
}
