package be.elevenways.hohenheim;

import be.elevenways.zenit.common.channel.Channel;
import be.elevenways.zenit.common.channel.KeyedChannel;

/**
 * Hohenheim's typed channels, declared in COMMON so the browser opens the same object the
 * server handles. The handler factories are installed server-side at the MODULES boot
 * stage, because the handlers read the daemon and the record grants.
 */
public final class HohenheimChannels {

    /**
     * Live per-instance resource samples (docker stats). openData is the instance id; the
     * server handler decides admission from the per-record {@code view} capability (live
     * stats are observation, the same verb the page carrying the chart is scoped by),
     * which is why the channel itself only declares {@code loginRequired} -- a record
     * capability is not a permission and a flat one here would either lock tenants out or
     * hand them every instance.
     */
    public static final Channel<Object, Object> INSTANCE_STATS = Channel.builder()
        .identifier(HohenheimIds.id("instance_stats"))
        .requiresLogin()
        .build();

    /**
     * The live log of one instance's deploy: its release steps and its build output as they are written. openData is
     * the instance id; like {@link #INSTANCE_STATS} the handler admits per record ({@code view}), and it hands the
     * release's step log to operators alone. Frames are {@link HohenheimDeployFunctions#frame} maps.
     */
    public static final Channel<Object, Object> DEPLOY_LOG = Channel.builder()
        .identifier(HohenheimIds.id("deploy_log"))
        .requiresLogin()
        .build();

    /**
     * The small live figures ("8 of 20 players online") of the instances one page shows, over ONE link however many
     * rows show one: a keyed channel ({@link #INSTANCE_FIGURE_KEYS}), so openData and every later message are the list
     * of instance ids the page shows now, of which core's key guard hands the server handler those it may view.
     * {@link HohenheimFigureFunctions} maps naming their instance.
     */
    public static final Channel<Object, Object> INSTANCE_FIGURE = Channel.builder()
        .identifier(HohenheimIds.id("instance_figure"))
        .requiresLogin()
        .build();

    /**
     * {@link #INSTANCE_FIGURE}'s keys: instance ids, named under {@code instance} in every frame. Every page that shows
     * figures shows fewer than the cap (the instance lists page TableSpec's default 25 rows, which
     * LiveFigureDeliveryTest asserts), so it only bites a hostile or broken client, which is refused TOO_LARGE.
     */
    public static final KeyedChannel<Integer> INSTANCE_FIGURE_KEYS =
        KeyedChannel.integers(INSTANCE_FIGURE, "instance", 200);

    private HohenheimChannels() {
    }

    /** Force class loading so the channels register before any open frame arrives. */
    public static void init() {
    }
}
