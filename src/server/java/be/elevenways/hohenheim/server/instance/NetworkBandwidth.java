package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimFormCopy;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Schema;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * The per-workload network bandwidth cap, declared ONCE for every kind whose driver can
 * actually enforce it -- the {@link RootDisk} shape, for the wire instead of the disk.
 *
 * AIDEV-NOTE: declaring the field IS the capability declaration, exactly as for the root
 * disk, and for the same reason: a kind that cannot enforce a rate must not OFFER the
 * number. Only the Incus kinds declare it, because only Incus has a per-NIC rate limiter
 * hohenheim can hand a value to ({@code limits.ingress} / {@code limits.egress} on the
 * bridged NIC device). Docker has no HostConfig key for bandwidth at all -- shaping a
 * container's veth means a {@code tc} qdisc on an interface the daemon owns and rewrites,
 * which is exactly the "accepted and enforcing nothing" shape the root-disk decision
 * struck for the Docker tier -- so {@code DockerInstanceRuntime.create} refuses a spec
 * carrying one BY NAME.
 *
 * AIDEV-NOTE: the cap is a RATE, so unlike memory and disk it is deliberately NOT charged
 * into a reservation ledger. A ledger books a stock of something a host has a fixed amount
 * of; bandwidth is shared, bursty and routinely oversubscribed on purpose, and a per-owner
 * "total Mbit" cap would refuse a placement for a number nobody was actually consuming.
 * What the declaration buys is a CEILING on one workload, which is the availability
 * property the threat model's network clause asks for.
 *
 * CONNECTION limits are NOT here, and that is a decision rather than an omission: Incus
 * network ACLs have no connection-count rule, so the only way to express one is an
 * nftables {@code ct count} beside the daemon's own table -- the precise arrangement
 * {@link be.elevenways.hohenheim.server.incus.IncusNetworkPolicy} refuses to build,
 * because incusd rewrites that ruleset on every network reload and would flush it out
 * from under us. See docs/instance-tier-plan.md for the recorded verdict.
 */
public final class NetworkBandwidth {

    /** The settings key every declaring kind uses and every consumer reads. */
    public static final String SETTING = "network_limit_mbit";

    private NetworkBandwidth() {
    }

    /** Add the knob to a kind's settings schema; the returned field is the kind's constant. */
    public static @NonNull IntegerField addTo(@NonNull Schema schema) {
        return schema.addField(IntegerField.builder().name(SETTING)
            .label(HohenheimFormCopy.label("network_limit_mbit"))
            .help(HohenheimFormCopy.help("network_limit_mbit"))
            .suffix("Mbit/s")
            .build());
    }

    /**
     * The DECLARED cap in Mbit/s, or null when the settings leave the wire unshaped.
     *
     * A non-positive or unparseable declaration reads as null, i.e. UNLIMITED, which is
     * the same answer an absent key gives: there is no ledger to keep honest here, so
     * there is nothing a zero could mean except "no cap".
     */
    public static @Nullable Integer declaredMbit(@NonNull Map<String, Object> settings) {
        Object raw = settings.get(SETTING);
        Integer value = null;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                value = Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return value != null && value > 0 ? value : null;
    }

    /** The daemon's own spelling of a rate: {@code 100Mbit}. */
    public static @NonNull String rateOf(int mbit) {
        return mbit + "Mbit";
    }
}
