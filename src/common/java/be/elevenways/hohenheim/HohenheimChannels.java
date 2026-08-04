package be.elevenways.hohenheim;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.channel.Channel;

/**
 * Hohenheim's typed channels, declared in COMMON so the browser opens the same object the
 * server handles. The handler factories are installed server-side at the MODULES boot
 * stage, because the handlers read the daemon and the record grants.
 */
public final class HohenheimChannels {

    /**
     * Live per-instance resource samples (docker stats). openData is the instance id; the
     * server handler decides admission from the per-record {@code manage} capability, which
     * is why the channel itself only declares {@code loginRequired} -- a record capability
     * is not a permission and a flat one here would either lock tenants out or hand them
     * every instance.
     */
    public static final Channel<Object, Object> INSTANCE_STATS = Channel.builder()
        .identifier(Identifier.of("hohenheim", "instance_stats"))
        .requiresLogin()
        .build();

    private HohenheimChannels() {
    }

    /** Force class loading so the channels register before any open frame arrives. */
    public static void init() {
    }
}
