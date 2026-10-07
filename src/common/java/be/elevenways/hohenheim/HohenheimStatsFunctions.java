package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.customelement.CustomElement;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.common.channel.ChannelLinks;
import be.elevenways.zenit.common.text.ByteText;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Template bridge to the instance-stats channel (namespace {@code InstanceStats}): open a
 * live link for one instance, and fold arriving samples into a plottable series.
 *
 * AIDEV-NOTE: every method is a no-op on the server -- the link belongs to the MOUNTED
 * element, never to a render expression: zenit's {@link ChannelLinks} link, released by the
 * element's own disconnect, so there is no close to call. The previous shape subscribed inside
 * a {@code returnsReference} template function; hydration revives values without re-running
 * {@code {% let %}} calls, so after a hard load the subscription simply never existed.
 */
public final class HohenheimStatsFunctions {

    /** Points plotted per series; matches the hub's server-side ring. */
    private static final int WINDOW = 60;

    /** Wire key of a sample's timestamp (epoch millis). */
    public static final String AT_KEY = "at";

    /** Wire key of a sample's memory cap in bytes (0 when the runtime reports none). */
    public static final String MEMORY_LIMIT_KEY = "memory_limit";

    private HohenheimStatsFunctions() {
    }

    /**
     * The metric vocabulary: the wire key each sample carries and the divisor that turns
     * the raw value into what the chart plots (percent, MiB, KiB). ONE home -- the server's
     * {@code Sample.toMap}, the page's seed derivation and the browser's fold all read it,
     * so the seeded half of a series can never be scaled differently from the live half.
     */
    public enum Metric {

        CPU("cpu", 1d, false),
        MEMORY("memory", 1048576d, false),
        RX("rx", 1024d, true),
        TX("tx", 1024d, true);

        private final @NonNull String key;
        private final double divisor;
        private final boolean cumulative;

        Metric(@NonNull String key, double divisor, boolean cumulative) {
            this.key = key;
            this.divisor = divisor;
            this.cumulative = cumulative;
        }

        public @NonNull String key() {
            return this.key;
        }

        /** Whether the wire value is a running total, plotted as its rate per second. */
        public boolean cumulative() {
            return this.cumulative;
        }

        /** The raw wire value scaled to plot units. */
        public double scaled(double raw) {
            return raw / this.divisor;
        }

        /**
         * The raw per-second rate between two samples of a cumulative counter.
         *
         * @return the rate, or -1 when the pair has no honest rate (no time passed, or the
         *         counter went backwards because the workload restarted)
         */
        public double perSecond(double previousRaw, long previousAt, double raw, long at) {
            if (at <= previousAt || raw < previousRaw) {
                return -1d;
            }
            return (raw - previousRaw) / ((at - previousAt) / 1000d);
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
     * {@code onSample}, for as long as {@code owner} is connected; opening again replaces the
     * link, never stacks one.
     */
    @HawkeyeFunction(
        name = "connect",
        namespace = "InstanceStats",
        description = "Open the live stats channel for an instance",
        returnType = Void.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "owner", required = true, type = CustomElement.class, expectsReference = false,
                 description = "The element that owns the link"),
            @Arg(name = "instanceId", required = true, type = Integer.class, expectsReference = false,
                 description = "The instance to watch"),
            @Arg(name = "onSample", required = true, type = LambdaReference1.class, expectsReference = false,
                 description = "Called with every sample map that arrives")
        }
    )
    public static void connect(RenderContext context,
                               @Nullable CustomElement owner,
                               @Nullable Integer instanceId,
                               @Nullable LambdaReference1<Object, ?> onSample) {

        if (!Blast.IS_TEAVM || owner == null || instanceId == null || onSample == null) {
            return;
        }
        ChannelLinks.openWhileConnected(owner, HohenheimChannels.INSTANCE_STATS, instanceId,
                sample -> onSample.invoke(context, sample));
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
        return appendTrimmed(current != null ? current : seed, resolved.scaled(number.doubleValue()));
    }

    /**
     * Folds one sample of a cumulative counter (received, sent) into its per-second series:
     * the rate since {@code previous}, scaled to plot units.
     *
     * @return the new list, or null when there is no previous sample or no honest rate
     */
    @HawkeyeFunction(
        name = "appendedRate",
        namespace = "InstanceStats",
        description = "A series with the per-second rate since the previous sample appended",
        returnType = List.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "current", required = false, type = List.class, expectsReference = false,
                 description = "The series so far, or null before the first sample"),
            @Arg(name = "seed", required = false, type = List.class, expectsReference = false,
                 description = "The server-rendered rates the series starts from"),
            @Arg(name = "sample", required = true, type = Map.class, expectsReference = false,
                 description = "The arrived sample map"),
            @Arg(name = "previous", required = false, type = Map.class, expectsReference = false,
                 description = "The sample before it"),
            @Arg(name = "metric", required = true, type = String.class, expectsReference = false,
                 description = "Which cumulative metric key to read")
        }
    )
    public static @Nullable List<Double> appendedRate(@Nullable List<Object> current,
                                                      @Nullable List<Object> seed,
                                                      @Nullable Object sample,
                                                      @Nullable Object previous,
                                                      @Nullable String metric) {

        Metric resolved = Metric.byKey(metric);
        if (resolved == null || !resolved.cumulative()) {
            return null;
        }
        double rate = rateBetween(previous, sample, resolved);
        if (rate < 0) {
            return null;
        }
        return appendTrimmed(current != null ? current : seed, resolved.scaled(rate));
    }

    /**
     * The raw per-second rate of a cumulative metric between two sample maps; the server
     * seeds its rate series through this same method.
     *
     * @return the rate, or -1 when either sample lacks the metric or no honest rate exists
     */
    public static double rateBetween(@Nullable Object previous, @Nullable Object sample, @NonNull Metric metric) {
        if (!(previous instanceof Map<?, ?> before) || !(sample instanceof Map<?, ?> after)) {
            return -1d;
        }
        if (!(before.get(metric.key()) instanceof Number previousRaw)
                || !(after.get(metric.key()) instanceof Number raw)
                || !(before.get(AT_KEY) instanceof Number previousAt)
                || !(after.get(AT_KEY) instanceof Number at)) {
            return -1d;
        }
        return metric.perSecond(previousRaw.doubleValue(), previousAt.longValue(),
            raw.doubleValue(), at.longValue());
    }

    /**
     * CPU use as a whole percentage of what the app may use: of its CPU limit when it has
     * one, else of one core (a sample reads 0..100 per core).
     *
     * @return the percentage, or null when the sample carries no CPU figure
     */
    @HawkeyeFunction(
        name = "cpuShare",
        namespace = "InstanceStats",
        description = "CPU use as a percentage of the app's CPU limit, or of one core",
        returnType = Integer.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                 description = "The latest sample map"),
            @Arg(name = "cpuLimit", required = false, type = Double.class, expectsReference = false,
                 description = "The configured CPU limit in cores, or null for none")
        }
    )
    public static @Nullable Integer cpuShare(@Nullable Object sample, @Nullable Double cpuLimit) {
        if (!(sample instanceof Map<?, ?> map) || !(map.get(Metric.CPU.key()) instanceof Number cpu)) {
            return null;
        }
        double cores = cpuLimit != null && cpuLimit > 0 ? cpuLimit : 1d;
        return (int) Math.round(cpu.doubleValue() / cores);
    }

    /** @return the memory in use as a size ("842.0 MB"), or "" when the sample has none */
    @HawkeyeFunction(
        name = "memoryUsed",
        namespace = "InstanceStats",
        description = "The memory in use as a size",
        returnType = String.class,
        returnsReference = false,
        arguments = @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                         description = "The latest sample map")
    )
    public static @NonNull String memoryUsed(@Nullable Object sample) {
        return sizeOf(sample, Metric.MEMORY.key());
    }

    /** @return the memory cap as a size ("1.0 GB"), or "" when the runtime reports none */
    @HawkeyeFunction(
        name = "memoryLimit",
        namespace = "InstanceStats",
        description = "The memory cap as a size, or empty when there is none",
        returnType = String.class,
        returnsReference = false,
        arguments = @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                         description = "The latest sample map")
    )
    public static @NonNull String memoryLimit(@Nullable Object sample) {
        return sizeOf(sample, MEMORY_LIMIT_KEY);
    }

    /** @return the per-second rate of a cumulative metric as a size ("1.4 MB"), or "" without one */
    @HawkeyeFunction(
        name = "rate",
        namespace = "InstanceStats",
        description = "The per-second rate between two samples as a size",
        returnType = String.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "previous", required = false, type = Map.class, expectsReference = false,
                 description = "The sample before the latest"),
            @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                 description = "The latest sample map"),
            @Arg(name = "metric", required = true, type = String.class, expectsReference = false,
                 description = "Which cumulative metric key to read")
        }
    )
    public static @NonNull String rate(@Nullable Object previous, @Nullable Object sample, @Nullable String metric) {
        Metric resolved = Metric.byKey(metric);
        if (resolved == null || !resolved.cumulative()) {
            return "";
        }
        double rate = rateBetween(previous, sample, resolved);
        return rate < 0 ? "" : ByteText.human(Math.round(rate));
    }

    private static @NonNull String sizeOf(@Nullable Object sample, @NonNull String key) {
        if (!(sample instanceof Map<?, ?> map) || !(map.get(key) instanceof Number bytes) || bytes.longValue() <= 0) {
            return "";
        }
        return ByteText.human(bytes.longValue());
    }

    private static @NonNull List<Double> appendTrimmed(@Nullable List<Object> base, double value) {
        List<Double> next = new ArrayList<>();
        if (base != null) {
            for (Object point : base) {
                next.add(point instanceof Number n ? n.doubleValue() : 0d);
            }
        }
        next.add(value);
        if (next.size() > WINDOW) {
            // A new trimmed list per fold, never a mutation in place: the tag assigns the
            // RESULT to its property, which is what makes the chart repaint.
            return new ArrayList<>(next.subList(next.size() - WINDOW, next.size()));
        }
        return next;
    }
}
