package be.elevenways.hohenheim;

import be.elevenways.domino.common.DominoElement;
import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.key.IdentityKey;
import be.elevenways.zenit.common.channel.ChannelClient;
import be.elevenways.zenit.common.channel.ClientChannelLink;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Template bridge to the instance-stats channel (namespace {@code InstanceStats}): open a
 * live link for one instance, and fold arriving samples into a plottable series.
 *
 * <p>AIDEV-NOTE: every method is a no-op on the server -- the link belongs to the MOUNTED
 * element (the QQChatFunctions shape), never to a render expression. The previous shape
 * subscribed inside a {@code returnsReference} template function; hydration revives values
 * without re-running {@code {% let %}} calls, so after a hard load the subscription simply
 * never existed, and nothing ever closed the links a client render did open.
 */
public final class HohenheimStatsFunctions {

    /** Points plotted per series; matches the hub's server-side ring. */
    private static final int WINDOW = 60;

    private static final IdentityKey<ClientChannelLink<Object, Object>> LINK_KEY =
        IdentityKey.create("hohenheim.instance_stats.link");

    private HohenheimStatsFunctions() {
    }

    /**
     * The metric vocabulary: the wire key each sample carries and the divisor that turns
     * the raw value into what the chart plots (percent, MiB, KiB). ONE home -- the server's
     * {@code Sample.toMap}, the page's seed derivation and the browser's fold all read it,
     * so the seeded half of a series can never be scaled differently from the live half.
     */
    public enum Metric {

        CPU("cpu", 1d),
        MEMORY("memory", 1048576d),
        RX("rx", 1024d),
        TX("tx", 1024d);

        private final @NonNull String key;
        private final double divisor;

        Metric(@NonNull String key, double divisor) {
            this.key = key;
            this.divisor = divisor;
        }

        public @NonNull String key() {
            return this.key;
        }

        /** The raw wire value scaled to plot units. */
        public double scaled(double raw) {
            return raw / this.divisor;
        }

        static @Nullable Metric byKey(@Nullable String key) {
            for (Metric metric : values()) {
                if (metric.key.equals(key)) {
                    return metric;
                }
            }
            return null;
        }
    }

    /**
     * Opens the live stats link for an instance and routes every arriving sample to
     * {@code onSample}. Re-opening on an element that already holds a link is a no-op, so
     * a remount cannot stack two subscriptions.
     */
    @HawkeyeFunction(
        name = "connect",
        namespace = "InstanceStats",
        description = "Open the live stats channel for an instance",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = DominoElement.class, expectsReference = false,
                 description = "The element that owns the link"),
            @Arg(name = "instanceId", required = true, type = Integer.class, expectsReference = false,
                 description = "The instance to watch"),
            @Arg(name = "onSample", required = true, type = LambdaReference1.class, expectsReference = false,
                 description = "Called with every sample map that arrives")
        }
    )
    public static void connect(RenderContext context,
                               @Nullable DominoElement owner,
                               @Nullable Integer instanceId,
                               @Nullable LambdaReference1<Object, ?> onSample) {

        if (!Blast.IS_TEAVM || owner == null || instanceId == null || onSample == null) {
            return;
        }
        if (owner.getAttachment(LINK_KEY) != null) {
            return;
        }

        var link = ChannelClient.shared().open(
                HohenheimChannels.INSTANCE_STATS,
                instanceId,
                sample -> onSample.invoke(context, sample));

        owner.setAttachment(LINK_KEY, link);
    }

    /** Closes the element's stats link; safe to call when none was ever opened. */
    @HawkeyeFunction(
        name = "disconnect",
        namespace = "InstanceStats",
        description = "Close the live stats channel",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = DominoElement.class, expectsReference = false,
                 description = "The element that owns the link")
        }
    )
    public static void disconnect(RenderContext context, @Nullable DominoElement owner) {

        if (!Blast.IS_TEAVM || owner == null) {
            return;
        }

        var link = owner.getAttachment(LINK_KEY);
        if (link == null) {
            return;
        }

        owner.setAttachment(LINK_KEY, null);
        link.close();
    }

    /**
     * Folds one sample into a series: the current points (or the seed on the first
     * sample), plus this sample's value for {@code metric}, trimmed to the window.
     *
     * @return the new list, or null when the sample carries no such metric
     */
    @HawkeyeFunction(
        name = "appended",
        namespace = "InstanceStats",
        description = "A series with one sample's metric appended, trimmed to the window",
        returnType = List.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "current", required = false, type = List.class, expectsReference = false,
                 description = "The series so far, or null before the first sample"),
            @Arg(name = "seed", required = false, type = List.class, expectsReference = false,
                 description = "The server-rendered history the series starts from"),
            @Arg(name = "sample", required = true, type = Map.class, expectsReference = false,
                 description = "The arrived sample map"),
            @Arg(name = "metric", required = true, type = String.class, expectsReference = false,
                 description = "Which metric key to read")
        }
    )
    public static @Nullable List<Double> appended(@Nullable List<Object> current,
                                                  @Nullable List<Object> seed,
                                                  @Nullable Object sample,
                                                  @Nullable String metric) {

        Metric resolved = Metric.byKey(metric);
        if (resolved == null || !(sample instanceof Map<?, ?> map)) {
            return null;
        }
        Object raw = map.get(resolved.key());
        if (!(raw instanceof Number number)) {
            return null;
        }

        List<Object> base = current != null ? current : seed;
        List<Double> next = new ArrayList<>();
        if (base != null) {
            for (Object value : base) {
                next.add(value instanceof Number n ? n.doubleValue() : 0d);
            }
        }
        next.add(resolved.scaled(number.doubleValue()));
        if (next.size() > WINDOW) {
            // A new trimmed list per fold, never a mutation in place: the tag assigns the
            // RESULT to its property, which is what makes the chart repaint.
            return new ArrayList<>(next.subList(next.size() - WINDOW, next.size()));
        }
        return next;
    }
}
