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
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Network card of a machine's overview: how its guest is connected ({@link GuestNetworkMode}) and the addresses
 * the guest holds, as its host's daemon reports them.
 *
 * AIDEV-NOTE: the one daemon read this overview makes, bounded by {@link #READ_TIMEOUT_MS} and only for a running
 * machine: a guest's DHCP addresses are stored nowhere, and a machine page without its address is what a Proxmox
 * user leaves the page over. An unreachable host says so in words; the daemon's own text stays out (the card renders
 * on /manage too).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class InstanceNetwork {

    /** How long the overview waits for the host; past it the card says the host did not answer. */
    static final long READ_TIMEOUT_MS = 3_000;

    private InstanceNetwork() {
    }

    /** The card for one machine. */
    static @NonNull WidgetInstance card(@NonNull Row instance, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        List<WidgetFact> facts = new ArrayList<>();
        GuestNetworkMode mode = modeOf(instance);
        facts.add(WidgetFact.of(text("network_mode", locales, resolver), mode.label().resolve(locales, resolver)));
        facts.add(WidgetFact.of(text("network_reach", locales, resolver),
            mode.description().resolve(locales, resolver)));
        facts.addAll(addressFacts(instance, locales, resolver));
        return CardWidget.of(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("network"),
            new WidgetTree(List.of(new WidgetInstance(FactListWidget.ID, Map.of()).withData(facts))));
    }

    /** The mode this machine's guest is attached in; every guest is HIDDEN until the next mode lands. */
    static @NonNull GuestNetworkMode modeOf(@NonNull Row instance) {
        return GuestNetworkMode.HIDDEN;
    }

    private static @NonNull List<WidgetFact> addressFacts(@NonNull Row instance, @NonNull LocaleChain locales,
                                                          @Nullable MessageResolver resolver) {
        String addresses = text("network_addresses", locales, resolver);
        if (!InstanceModel.STATUS_RUNNING.equals(instance.get(InstanceModel.STATUS))) {
            return List.of(WidgetFact.of(addresses, text("network_not_running", locales, resolver)));
        }
        List<GuestAddressSupport.GuestInterface> interfaces;
        try {
            InstanceService.Resolved resolved = new InstanceService().resolve(instance.get(InstanceModel.ID));
            if (!(resolved.runtime() instanceof GuestAddressSupport support)) {
                return List.of();
            }
            interfaces = support.guestAddresses(resolved.spec().handle(), READ_TIMEOUT_MS);
        } catch (Exception unreadable) {
            Blast.log("NETWORK CARD: addresses of instance", instance.get(InstanceModel.ID), "unreadable:",
                unreadable.getMessage());
            return List.of(WidgetFact.of(addresses, text("network_unreachable", locales, resolver)));
        }
        List<WidgetFact> facts = new ArrayList<>();
        for (GuestAddressSupport.GuestInterface nic : interfaces) {
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

    private static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                        @Nullable MessageResolver resolver) {
        return HohenheimMicrocopy.INSTANCE_OVERVIEW.of(key).resolve(locales, resolver);
    }
}
