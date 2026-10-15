package be.elevenways.hohenheim.server.incus;

import be.elevenways.hohenheim.server.host.PreflightFinding;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * THE admission of a host's {@code lan_parent}: the interface a guest "on the local network" joins, and the NIC type
 * its kind derives from what the daemon reports for it. The host's preflight check, placement and the driver's main
 * NIC all ask {@link #admit}, never a copy of it.
 *
 * AIDEV-NOTE: Hohenheim never creates or edits a host bridge (a bridge over the uplink moves the host's own address,
 * which must never be done remotely). A bridge the operator made gives {@code bridged} (host and guest reach each
 * other, the Proxmox shape); a physical, bond or VLAN interface gives {@code macvlan}, which needs no host change but
 * keeps the guest and its own host apart. An interface the daemon manages (incusbr0, an hhx- bridge) is refused: a
 * guest there is on a NAT bridge, not on the local network.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class IncusLanParent {

    /** The NIC types a LAN guest's main NIC can take, each derived from the parent's reported type. */
    public enum NicType {

        /** Onto an existing host bridge: the guest and its host reach each other. */
        BRIDGED("bridged"),

        /** Beside a physical, bond or VLAN interface: no host change, and the guest cannot reach its own host. */
        MACVLAN("macvlan");

        private final @NonNull String token;

        NicType(@NonNull String token) {
            this.token = token;
        }

        /** @return the daemon's {@code nictype} value */
        public @NonNull String token() {
            return this.token;
        }

        /** @return whether the daemon shapes this NIC's bandwidth; it applies no {@code limits.*} to a macvlan NIC */
        public boolean carriesLimits() {
            return switch (this) {
                case BRIDGED -> true;
                case MACVLAN -> false;
            };
        }

        /** @return the NIC type a parent of this reported type gives, or null for a type a guest cannot join */
        public static @Nullable NicType forNetworkType(@Nullable Object type) {
            if ("bridge".equals(type)) {
                return BRIDGED;
            }
            if ("physical".equals(type) || "bond".equals(type) || "vlan".equals(type)) {
                return MACVLAN;
            }
            return null;
        }

        /** @return the member whose daemon value this is, or null */
        public static @Nullable NicType ofToken(@Nullable Object token) {
            for (NicType type : values()) {
                if (type.token.equals(token)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** An admitted parent: the interface and the NIC type a guest joins it with. */
    public record Lan(@NonNull String parent, @NonNull NicType nicType) {

        /**
         * The main NIC's device: no {@code network}, no ACL, no pinned address.
         *
         * @param limitMbit the declared bandwidth ceiling, or null
         * @throws IOException when a ceiling is declared on a NIC type the daemon cannot shape
         */
        public @NonNull Map<String, Object> device(@Nullable Integer limitMbit) throws IOException {
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("type", "nic");
            device.put("nictype", this.nicType.token());
            device.put("parent", this.parent);
            if (limitMbit != null && limitMbit > 0) {
                if (!this.nicType.carriesLimits()) {
                    throw new IOException("REFUSED to cap the bandwidth of a guest on the local network through '"
                        + this.parent + "': the daemon applies no limits to a " + this.nicType.token()
                        + " NIC. Clear the network limit, or give the host a bridge as its LAN parent.");
                }
                device.put("limits.ingress", IncusNetworkPolicy.rate(limitMbit));
                device.put("limits.egress", IncusNetworkPolicy.rate(limitMbit));
            }
            return device;
        }
    }

    /** The parent cannot be joined; {@link #found()} says why in the preflight vocabulary. */
    public static final class Unusable extends IOException {

        private final PreflightFinding.@NonNull Found found;

        Unusable(PreflightFinding.@NonNull Found found) {
            super(found.text());
            this.found = found;
        }

        /** @return why, with its evidence */
        public PreflightFinding.@NonNull Found found() {
            return this.found;
        }
    }

    private IncusLanParent() {
    }

    /**
     * Admit {@code parent} on this daemon: it exists, the daemon does not manage it, its type can be joined and it is
     * up.
     *
     * @throws Unusable naming the first of those that does not hold
     * @throws IOException when the daemon cannot be asked
     */
    public static @NonNull Lan admit(@NonNull IncusClient incus, @NonNull String parent) throws IOException {
        Map<String, Object> network = incus.network(parent);
        if (network == null) {
            throw new Unusable(PreflightFinding.LAN_PARENT_ABSENT.with("parent", parent));
        }
        if (!Boolean.FALSE.equals(network.get("managed"))) {
            throw new Unusable(PreflightFinding.LAN_PARENT_MANAGED.with("parent", parent));
        }
        NicType nicType = NicType.forNetworkType(network.get("type"));
        if (nicType == null) {
            throw new Unusable(PreflightFinding.LAN_PARENT_UNSUPPORTED.with("parent", parent,
                "type", String.valueOf(network.get("type"))));
        }
        Map<String, Object> state = incus.networkState(parent);
        Object up = state == null ? null : state.get("state");
        if (!"up".equals(up)) {
            throw new Unusable(PreflightFinding.LAN_PARENT_DOWN.with("parent", parent, "state", String.valueOf(up)));
        }
        return new Lan(parent, nicType);
    }

    /**
     * @return whether a device is a LAN guest's NIC as {@link Lan#device} writes it: a NIC type and a parent, and none
     *         of the isolating NIC's keys ({@link IncusNetworkPolicy#ISOLATION_KEYS})
     */
    public static boolean isLanNic(@Nullable Object device) {
        return device instanceof Map<?, ?> nic && "nic".equals(nic.get("type"))
            && NicType.ofToken(nic.get("nictype")) != null && nic.get("parent") instanceof String parent
            && !parent.isBlank() && IncusNetworkPolicy.ISOLATION_KEYS.stream().noneMatch(nic::containsKey);
    }
}
