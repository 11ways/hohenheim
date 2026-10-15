package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * How a machine's guest is attached to the network: the one vocabulary its Network card and, later, its choice read.
 *
 * AIDEV-NOTE: HIDDEN is the only mode that exists today: every Incus guest NIC sits on the managed bridge behind the
 * isolation ACL (IncusNetworkPolicy). The decided next mode, "on the local network" (bridged to the host LAN, the
 * Proxmox shape), is one member here with its words; the switches below then refuse to compile until it says them.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum GuestNetworkMode {

    /** NAT through its host behind the isolation rule: it reaches out, nothing outside its host reaches in. */
    HIDDEN;

    /** @return the mode in a few words */
    public @NonNull Microcopy label() {
        return switch (this) {
            case HIDDEN -> HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network_mode_hidden");
        };
    }

    /**
     * @param publishesPorts whether the machine holds published ports, which the outside reaches through its host
     * @return what the mode means for who can reach the machine
     */
    public @NonNull Microcopy description(boolean publishesPorts) {
        return switch (this) {
            case HIDDEN -> HohenheimMicrocopy.INSTANCE_OVERVIEW.of(publishesPorts
                ? "network_mode_hidden_ports_detail" : "network_mode_hidden_detail");
        };
    }
}
