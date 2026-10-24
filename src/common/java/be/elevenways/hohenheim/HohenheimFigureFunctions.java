package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.customelement.CustomElement;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.hohenheim.instance.LiveFigureState;
import be.elevenways.hohenheim.instance.StatusProbe;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.channel.ChannelLinks;
import be.elevenways.zenit.common.ui.BadgeVariant;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Template bridge to the instance-figure channel (namespace {@code InstanceFigure}): follow one instance's live figure
 * over the page's one link on {@link HohenheimChannels#INSTANCE_FIGURE_KEYS} (core's {@code ChannelLinks.joinShared})
 * and say the figure that arrives, and the wire keys the server writes that figure under.
 *
 * AIDEV-NOTE: a figure's moment rides {@link HohenheimStatsFunctions#AT_KEY}, so a seeded element folds arrivals with
 * {@code InstanceStats.newer} like the gauges do; the element's membership of the page's link belongs to the mounted
 * element (the {@code connect} rule of {@link HohenheimStatsFunctions}).
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class HohenheimFigureFunctions {

    /** Wire key of the {@link InstanceStatus} token a {@link LiveFigureState#NOT_RUNNING} figure says. */
    public static final String STATUS_KEY = "status";

    /** Wire key of the figure's {@link LiveFigureState} token. */
    public static final String STATE_KEY = "state";

    /** Wire key of the {@link StatusProbe} token that read the figure. */
    public static final String PROBE_KEY = "probe";

    /** Wire key of the count the answer reports (the players online). */
    public static final String ONLINE_KEY = "online";

    /** Wire key of the ceiling the answer reports, 0 when it names none. */
    public static final String CAPACITY_KEY = "capacity";

    private HohenheimFigureFunctions() {
    }

    /**
     * Follows the instance's live figure over the page's one figure link and routes every arriving figure to
     * {@code onFigure}, for as long as {@code owner} is connected; connecting again replaces the previous one. A link
     * that ends without the element leaving hands it {@link #ended} last, so it never shows a stale count as live.
     */
    @HawkeyeFunction(
        name = "connect",
        namespace = "InstanceFigure",
        description = "Follow an instance's live figure over the page's figure link",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element whose connection the following lasts"),
            @Arg(name = "instanceId", required = true, type = Integer.class, expectsReference = false,
                 description = "The instance to watch"),
            @Arg(name = "onFigure", required = true, type = LambdaReference1.class, expectsReference = false,
                 description = "Called with every figure map that arrives")
        }
    )
    public static void connect(RenderContext context,
                               @Nullable CustomElement owner,
                               @Nullable Integer instanceId,
                               @Nullable LambdaReference1<Object, ?> onFigure) {
        if (owner == null || instanceId == null || onFigure == null) {
            return;
        }
        ChannelLinks.joinShared(owner, HohenheimChannels.INSTANCE_FIGURE_KEYS, instanceId,
            figure -> onFigure.invoke(context, figure), refused -> onFigure.invoke(context, ended(instanceId)));
    }

    /**
     * @return the figure an element folds in once its link ended: no figure (silent), at a moment no arrival
     *         outdates, since none follows
     */
    public static @NonNull Map<String, Object> ended(int instanceId) {
        Map<String, Object> figure = HohenheimChannels.INSTANCE_FIGURE_KEYS.frame(instanceId);
        figure.put(HohenheimStatsFunctions.AT_KEY, Long.MAX_VALUE);
        figure.put(STATE_KEY, LiveFigureState.SILENT.token());
        return figure;
    }

    /** @return the figure's state token, {@code waiting} before the first figure */
    @HawkeyeFunction(
        name = "state",
        namespace = "InstanceFigure",
        description = "The state token of a live figure",
        returnType = String.class,
        returnsReference = false,
        arguments = @Arg(name = "figure", required = false, type = Map.class, expectsReference = false,
                         description = "The newest figure map, or null")
    )
    public static @NonNull String state(@Nullable Object figure) {
        return stateOf(figure).token();
    }

    /** @return the badge variant the figure's state reads in */
    @HawkeyeFunction(
        name = "variant",
        namespace = "InstanceFigure",
        description = "The badge variant of a live figure",
        returnType = BadgeVariant.class,
        returnsReference = false,
        arguments = @Arg(name = "figure", required = false, type = Map.class, expectsReference = false,
                         description = "The newest figure map, or null")
    )
    public static @NonNull BadgeVariant variant(@Nullable Object figure) {
        return stateOf(figure).variant();
    }

    /**
     * The figure in words: an answer in its probe's words, an unanswered probe as not answering, an instance not meant
     * to run in its own state's words, nothing yet as being checked.
     */
    @HawkeyeFunction(
        name = "words",
        namespace = "InstanceFigure",
        description = "A live figure in words",
        returnType = Microcopy.class,
        returnsReference = false,
        arguments = @Arg(name = "figure", required = false, type = Map.class, expectsReference = false,
                         description = "The newest figure map, or null")
    )
    public static @NonNull Microcopy words(@Nullable Object figure) {
        return switch (stateOf(figure)) {
            case ANSWERING -> {
                StatusProbe probe = StatusProbe.forToken(((Map<?, ?>) figure).get(PROBE_KEY));
                yield probe == null ? HohenheimMicrocopy.STATUS_PROBE.of("not_answering")
                    : probe.figure(count(figure, ONLINE_KEY), count(figure, CAPACITY_KEY));
            }
            case SILENT -> HohenheimMicrocopy.STATUS_PROBE.of("not_answering");
            case NOT_RUNNING -> {
                InstanceStatus status = InstanceStatus.forToken(String.valueOf(((Map<?, ?>) figure).get(STATUS_KEY)));
                yield status == null ? HohenheimMicrocopy.STATUS_PROBE.of("checking") : status.label();
            }
            case WAITING -> HohenheimMicrocopy.STATUS_PROBE.of("checking");
        };
    }

    private static @NonNull LiveFigureState stateOf(@Nullable Object figure) {
        return figure instanceof Map<?, ?> map ? LiveFigureState.forToken(map.get(STATE_KEY)) : LiveFigureState.WAITING;
    }

    private static int count(@NonNull Object figure, @NonNull String key) {
        return figure instanceof Map<?, ?> map && map.get(key) instanceof Number number ? number.intValue() : 0;
    }
}
