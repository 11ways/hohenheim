package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.instance.GuestNetworkMode;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceService;
import be.elevenways.hohenheim.server.runtime.GuestAddressSupport;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Network card of a machine's overview: how its guest is connected ({@link GuestNetworkMode}) and the addresses
 * the guest holds, as its host's daemon reports them; for a mode reached on the guest's own address, its "Connect to"
 * card naming that address.
 *
 * AIDEV-NOTE: the one daemon read this overview makes, bounded by {@link #READ_TIMEOUT_MS} and only for a running
 * machine: a guest's DHCP addresses are stored nowhere, and a machine page without its address is what a Proxmox
 * user leaves the page over. Both cards draw from that ONE read. An unreachable host says so in words; the daemon's
 * own text stays out (the card renders on /manage too).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class InstanceNetwork {

    /** How long the overview waits for the host; past it the card says the host did not answer. */
    static final long READ_TIMEOUT_MS = 3_000;

    private InstanceNetwork() {
    }

    /**
     * The cards for one machine: its Network card, then its "Connect to" card when its mode is reached on its own
     * address.
     *
     * @param publishesPorts whether it holds published ports, so the outside does reach it on those
     */
    static @NonNull List<WidgetInstance> cards(@NonNull Row instance, boolean publishesPorts,
                                               @NonNull LocaleChain locales, @Nullable MessageResolver resolver) {
        GuestNetworkMode mode = modeOf(instance);
        Addresses read = addressesOf(instance);
        List<WidgetFact> facts = new ArrayList<>();
        facts.add(WidgetFact.of(text("network_mode", locales, resolver), mode.label().resolve(locales, resolver)));
        facts.add(WidgetFact.of(text("network_reach", locales, resolver),
            mode.description(publishesPorts).resolve(locales, resolver)));
        facts.addAll(addressFacts(read, locales, resolver));
        List<WidgetInstance> cards = new ArrayList<>();
        cards.add(CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network"), facts));
        if (mode.connectsAtGuestAddress()) {
            cards.add(CardWidget.ofFacts(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("connect"),
                connectFacts(read, locales, resolver)));
        }
        return cards;
    }

    /** The mode this machine's guest is attached in, as its settings declare it (unknown reads as HIDDEN). */
    static @NonNull GuestNetworkMode modeOf(@NonNull Row instance) {
        return GuestNetworkMode.of(InstanceModel.settingsOf(instance));
    }

    /**
     * What the one read found: the guest's interfaces, or the key saying why there are none to show.
     *
     * @param missing null when {@code interfaces} is the answer
     */
    private record Addresses(@NonNull List<GuestAddressSupport.GuestInterface> interfaces, @Nullable String missing) {
    }

    private static @NonNull Addresses addressesOf(@NonNull Row instance) {
        if (!InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))) {
            return new Addresses(List.of(), "network_not_running");
        }
        try {
            InstanceService.Resolved resolved = new InstanceService().resolve(instance.get(InstanceModel.ID));
            if (!(resolved.runtime() instanceof GuestAddressSupport support)) {
                return new Addresses(List.of(), null);
            }
            return new Addresses(support.guestAddresses(resolved.spec().handle(), READ_TIMEOUT_MS), null);
        } catch (Exception unreadable) {
            Blast.log("NETWORK CARD: addresses of instance", instance.get(InstanceModel.ID), "unreadable:",
                unreadable.getMessage());
            return new Addresses(List.of(), "network_unreachable");
        }
    }

    private static @NonNull List<WidgetFact> addressFacts(@NonNull Addresses read, @NonNull LocaleChain locales,
                                                          @Nullable MessageResolver resolver) {
        String addresses = text("network_addresses", locales, resolver);
        if (read.missing() != null) {
            return List.of(WidgetFact.of(addresses, text(read.missing(), locales, resolver)));
        }
        List<WidgetFact> facts = new ArrayList<>();
        for (GuestAddressSupport.GuestInterface nic : read.interfaces()) {
            Microcopy name = nic.network().isEmpty()
                ? Microcopy.literal(nic.name())
                : HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network_interface").withArg("name", nic.name())
                    .withArg("network", nic.network());
            facts.add(WidgetFact.of(name.resolve(locales, resolver), nic.addresses().isEmpty()
                ? text("network_no_address", locales, resolver) : String.join(", ", nic.addresses())));
        }
        return facts.isEmpty() ? List.of(WidgetFact.of(addresses, text("network_no_address", locales, resolver)))
            : facts;
    }

    /** The "Connect to" facts of a guest reached on its own address: its lead, then its main NIC's addresses. */
    private static @NonNull List<WidgetFact> connectFacts(@NonNull Addresses read, @NonNull LocaleChain locales,
                                                          @Nullable MessageResolver resolver) {
        List<String> own = new ArrayList<>();
        for (GuestAddressSupport.GuestInterface nic : read.interfaces()) {
            if (nic.primary()) {
                own.addAll(nic.addresses());
            }
        }
        String value = read.missing() != null ? text(read.missing(), locales, resolver)
            : own.isEmpty() ? text("network_no_address", locales, resolver) : String.join(", ", own);
        return List.of(WidgetFact.of(text("connect_lan", locales, resolver), value));
    }

    private static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return HohenheimMicrocopy.INSTANCE_OVERVIEW.of(key).resolve(locales, resolver);
    }
}
