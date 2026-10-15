package be.elevenways.hohenheim.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.field.EnumField;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * How a machine's guest is attached to the network: the one vocabulary its {@code network_mode} setting, its driver's
 * main NIC, its isolation checks, its published ports and its Network and "Connect to" cards read.
 *
 * AIDEV-NOTE: every fact is an exhaustive switch on the member, so a third mode refuses to compile until it answers
 * each one. An unknown or missing token reads as HIDDEN ({@link #of}): the isolated mode is the closed failure.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public enum GuestNetworkMode {

    /** NAT through its host behind the isolation rule: it reaches out, nothing outside its host reaches in. */
    HIDDEN("hidden"),

    /**
     * Joined to its host's local network through the host's admitted {@code lan_parent} (bridged or macvlan, the
     * Proxmox shape): it takes an address from that network and anything on it reaches the guest directly.
     */
    LOCAL_NETWORK("local_network");

    /** The settings key a machine kind declares this vocabulary under. */
    public static final String SETTING = "network_mode";

    private final @NonNull String token;

    GuestNetworkMode(@NonNull String token) {
        this.token = token;
    }

    /** @return the stored settings token */
    public @NonNull String token() {
        return this.token;
    }

    /** @return whether the guest's main NIC carries the isolation ACL; one outside a managed network cannot */
    public boolean carriesIsolationAcl() {
        return switch (this) {
            case HIDDEN -> true;
            case LOCAL_NETWORK -> false;
        };
    }

    /** @return whether the guest may publish ports through its host; a LAN guest is reached on its own address */
    public boolean publishesPorts() {
        return switch (this) {
            case HIDDEN -> true;
            case LOCAL_NETWORK -> false;
        };
    }

    /** @return whether "Connect to" names the guest's own address on its network rather than ports of its host */
    public boolean connectsAtGuestAddress() {
        return switch (this) {
            case HIDDEN -> false;
            case LOCAL_NETWORK -> true;
        };
    }

    /** @return whether only an operator may choose this mode: it places a guest beside the host on its network */
    public boolean operatorOnly() {
        return switch (this) {
            case HIDDEN -> false;
            case LOCAL_NETWORK -> true;
        };
    }

    /** @return the mode in a few words */
    public @NonNull Microcopy label() {
        return switch (this) {
            case HIDDEN -> HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network_mode_hidden");
            case LOCAL_NETWORK -> HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network_mode_local_network");
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
            case LOCAL_NETWORK -> HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network_mode_local_network_detail");
        };
    }

    /** @return the member stored as {@code token}; HIDDEN for a missing or unknown one */
    public static @NonNull GuestNetworkMode fromToken(@Nullable Object token) {
        for (GuestNetworkMode mode : values()) {
            if (mode.token.equals(token)) {
                return mode;
            }
        }
        return HIDDEN;
    }

    /** @return the mode a machine's settings declare under {@link #SETTING} */
    public static @NonNull GuestNetworkMode of(@NonNull Map<String, ?> settings) {
        return fromToken(settings.get(SETTING));
    }

    /**
     * @return the settings field over this vocabulary, HIDDEN by default; its picker offers no clear (an empty mode
     *         would read as HIDDEN while showing nothing), yet a settings write without it stays valid
     */
    public static @NonNull EnumField field() {
        EnumField.Builder builder = EnumField.builder(SETTING);
        for (GuestNetworkMode mode : values()) {
            builder.value(mode.token, value -> value.label(mode.label()));
        }
        return builder.defaultValue(HIDDEN.token).keepsAValue()
            .label(HohenheimMicrocopy.HOHENHEIM_FIELD.of(SETTING))
            .help(HohenheimMicrocopy.HELP.of(SETTING)).build();
    }
}
