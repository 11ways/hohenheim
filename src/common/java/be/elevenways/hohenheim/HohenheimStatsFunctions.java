package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.hawkeye.common.customelement.CustomElement;
import be.elevenways.hawkeye.common.lambda.LambdaReference1;
import be.elevenways.hawkeye.common.render.RenderContext;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.channel.ChannelLinks;
import be.elevenways.zenit.common.text.ByteText;
import be.elevenways.zenit.widget.common.data.UsageData;
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

    /** Wire key a sample carries, true, when the runtime gives the workload no CPU reading at all. */
    public static final String CPU_UNREPORTED_KEY = "cpu_unreported";

    /** Wire key of the CPUs the daemon reports online for the workload, what a CPU figure is a share of (100 each). */
    public static final String CORES_KEY = "cores";

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

    /** @return true when the sample says the runtime gives no CPU reading (said in words, never a chart) */
    @HawkeyeFunction(
        name = "cpuUnreported",
        namespace = "InstanceStats",
        description = "Whether the runtime gives the workload no CPU reading at all",
        returnType = Boolean.class,
        returnsReference = false,
        arguments = @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                         description = "The latest sample map")
    )
    public static boolean cpuUnreported(@Nullable Object sample) {
        return sample instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get(CPU_UNREPORTED_KEY));
    }

    /** @return the CPU chart's top edge in plot units (100 per core of the limit), or null with no limit */
    @HawkeyeFunction(
        name = "cpuCeiling",
        namespace = "InstanceStats",
        description = "The CPU chart's top edge: the app's CPU limit in plot units",
        returnType = Double.class,
        returnsReference = false,
        arguments = @Arg(name = "cpuLimit", required = false, type = Double.class, expectsReference = false,
                         description = "The configured CPU limit in cores, or null for none")
    )
    public static @Nullable Double cpuCeiling(@Nullable Double cpuLimit) {
        return cpuLimit != null && cpuLimit > 0 ? Metric.CPU.scaled(cpuLimit * 100d) : null;
    }

    /** @return the memory chart's top edge in plot units (the cap the runtime reports), or null without one */
    @HawkeyeFunction(
        name = "memoryCeiling",
        namespace = "InstanceStats",
        description = "The memory chart's top edge: the memory cap in plot units",
        returnType = Double.class,
        returnsReference = false,
        arguments = @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                         description = "The latest sample map")
    )
    public static @Nullable Double memoryCeiling(@Nullable Object sample) {
        if (!(sample instanceof Map<?, ?> map) || !(map.get(MEMORY_LIMIT_KEY) instanceof Number bytes)
                || bytes.longValue() <= 0) {
            return null;
        }
        return Metric.MEMORY.scaled(bytes.doubleValue());
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

    /**
     * The memory gauge of one reading, for a template: {@link #memoryGauge} with its words resolved for the render.
     */
    @HawkeyeFunction(
        name = "memoryUsage",
        namespace = "InstanceStats",
        description = "A reading's memory as a usage gauge's data",
        returnType = UsageData.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                 description = "The latest sample map, or null before the first"),
            @Arg(name = "bookedBytes", required = false, type = Long.class, expectsReference = false,
                 description = "What the host booked for the workload, measured against when the runtime has no cap"),
            @Arg(name = "waiting", required = true, type = Microcopy.class, expectsReference = false,
                 description = "The words for no reading yet"),
            @Arg(name = "noLimit", required = true, type = Microcopy.class, expectsReference = false,
                 description = "The words for an amount in use with nothing to measure it against")
        }
    )
    public static @NonNull UsageData memoryUsage(RenderContext context, @Nullable Object sample,
                                                 @Nullable Long bookedBytes, @Nullable Microcopy waiting,
                                                 @Nullable Microcopy noLimit) {
        return memoryGauge(sample, bookedBytes, words(context, waiting), words(context, noLimit));
    }

    /**
     * The memory gauge of one reading: in use against the cap the runtime reports, else against what the host booked
     * for the workload; the server's seed and the live element both read it, so the two halves cannot disagree.
     *
     * @return measured, or not measured with {@code waiting} (no reading yet) or {@code noLimit} (nothing to measure
     *         against), never a zero
     */
    public static @NonNull UsageData memoryGauge(@Nullable Object sample, @Nullable Long bookedBytes,
                                                 @Nullable String waiting, @Nullable String noLimit) {
        long used = numberOf(sample, Metric.MEMORY.key());
        if (used < 0) {
            return UsageData.unmeasured(waiting);
        }
        long cap = numberOf(sample, MEMORY_LIMIT_KEY);
        long limit = cap > 0 ? cap : bookedBytes != null ? bookedBytes : 0;
        if (limit <= 0) {
            return UsageData.unmeasured(noLimit);
        }
        return UsageData.measured(used, limit, ByteText.human(used), ByteText.human(limit), null);
    }

    /** The CPU gauge of the newest window, for a template: {@link #cpuGauge} with its words resolved for the render. */
    @HawkeyeFunction(
        name = "cpuUsage",
        namespace = "InstanceStats",
        description = "The newest window's CPU as a usage gauge's data",
        returnType = UsageData.class,
        returnsReference = false,
        arguments = {
            @Arg(name = "previous", required = false, type = Map.class, expectsReference = false,
                 description = "The sample before the latest, or null"),
            @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                 description = "The latest sample map, or null before the first"),
            @Arg(name = "waiting", required = true, type = Microcopy.class, expectsReference = false,
                 description = "The words for no reading yet"),
            @Arg(name = "unreported", required = true, type = Microcopy.class, expectsReference = false,
                 description = "The words for a runtime that gives no CPU reading"),
            @Arg(name = "firstReading", required = true, type = Microcopy.class, expectsReference = false,
                 description = "The words for a single reading, which is no window yet"),
            @Arg(name = "coresLabel", required = true, type = Microcopy.class, expectsReference = false,
                 description = "The cores the share is measured against, said as words")
        }
    )
    public static @NonNull UsageData cpuUsage(RenderContext context, @Nullable Object previous,
                                              @Nullable Object sample, @Nullable Microcopy waiting,
                                              @Nullable Microcopy unreported, @Nullable Microcopy firstReading,
                                              @Nullable Microcopy coresLabel) {
        return cpuGauge(previous, sample, words(context, waiting), words(context, unreported),
            words(context, firstReading), words(context, coresLabel));
    }

    /**
     * The CPU gauge of the newest window: the reading's share (100 per core) against the cores the daemon reports.
     *
     * @return measured, or not measured with {@code waiting} (no reading), {@code unreported} (the runtime gives none)
     *         or {@code firstReading} (one reading is no window yet)
     */
    public static @NonNull UsageData cpuGauge(@Nullable Object previous, @Nullable Object sample,
                                              @Nullable String waiting, @Nullable String unreported,
                                              @Nullable String firstReading, @Nullable String coresLabel) {
        if (!(sample instanceof Map<?, ?> latest)) {
            return UsageData.unmeasured(waiting);
        }
        if (cpuUnreported(sample)) {
            return UsageData.unmeasured(unreported);
        }
        if (!(previous instanceof Map<?, ?>) || !(latest.get(Metric.CPU.key()) instanceof Number cpu)) {
            return UsageData.unmeasured(firstReading);
        }
        long share = Math.round(cpu.doubleValue());
        return UsageData.measured(share, 100L * Math.max(1, cores(sample)), share + "%", coresLabel, null);
    }

    /** @return the words resolved the way the render resolves its own copy; null stays null */
    private static @Nullable String words(RenderContext context, @Nullable Microcopy words) {
        return words == null ? null : words.asString(context);
    }

    /** @return the CPUs the reading says are online for the workload, 0 when it says none */
    @HawkeyeFunction(
        name = "cores",
        namespace = "InstanceStats",
        description = "The CPUs a reading says are online for the workload",
        returnType = Integer.class,
        returnsReference = false,
        arguments = @Arg(name = "sample", required = false, type = Map.class, expectsReference = false,
                         description = "The latest sample map")
    )
    public static int cores(@Nullable Object sample) {
        return (int) Math.max(0, numberOf(sample, CORES_KEY));
    }

    /** @return the sample's whole number under {@code key}, or -1 when it carries none */
    private static long numberOf(@Nullable Object sample, @NonNull String key) {
        return sample instanceof Map<?, ?> map && map.get(key) instanceof Number number ? number.longValue() : -1;
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
