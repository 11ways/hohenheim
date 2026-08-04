package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.Arg;
import be.elevenways.hawkeye.common.annotation.HawkeyeFunction;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.holder.MutableRef;
import be.elevenways.zenit.common.channel.ChannelClient;
import be.elevenways.zenit.common.channel.ClientChannelLink;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The BROWSER half of live instance stats: a reference the Stats tab plots, seeded with the
 * server-rendered history and then fed by the {@code hohenheim:instance_stats} channel.
 *
 * There is no {@code <script>} anywhere in this feature and there cannot be: the channel is
 * a framework mechanism (reconnect, resume and ping included), the samples land in a
 * MutableRef, and template reactivity re-renders the chart. On the SERVER the same call is
 * the seed and nothing else, so SSR paints a real chart before any socket exists.
 */
public final class HohenheimStatsFunctions {

    /** Points plotted per series; matches the hub's server-side ring. */
    private static final int WINDOW = 60;

    private HohenheimStatsFunctions() {
    }

    /**
     * A live series for one instance metric.
     *
     * @param metric one of {@code cpu}, {@code memory}, {@code rx}, {@code tx} -- the keys
     *               the hub's sample map carries
     * @param seed   the server-rendered history, which is also the entire value on the server
     * @return a ref of plottable points, updated in place as samples arrive
     */
    @HawkeyeFunction(
        name = "series",
        namespace = "InstanceStats",
        description = "Live series of one instance metric, seeded with the rendered history",
        returnTypeExpression = "MutableRef<List>",
        returnsReference = true,
        arguments = {
            @Arg(name = "instanceId", required = true, type = Integer.class),
            @Arg(name = "metric", required = true, type = String.class),
            @Arg(name = "seed", required = true, type = List.class)
        }
    )
    public static @NonNull MutableRef<List<Double>> series(@NonNull Integer instanceId,
                                                           @NonNull String metric,
                                                           @NonNull List<Object> seed) {
        List<Double> initial = new ArrayList<>();
        for (Object value : seed) {
            initial.add(value instanceof Number number ? number.doubleValue() : 0d);
        }
        MutableRef<List<Double>> ref = MutableRef.of(trim(initial));
        if (Blast.IS_TEAVM) {
            follow(instanceId, metric, ref);
        }
        return ref;
    }

    /**
     * Attach one channel link per plotted series. Deliberately per-series and not shared:
     * the channel client multiplexes links over ONE socket, so the cost is a link id, and
     * a shared subscription would need a lifetime this call has no way to observe.
     */
    private static void follow(@NonNull Integer instanceId, @NonNull String metric,
                               @NonNull MutableRef<List<Double>> ref) {
        ClientChannelLink<Object, Object> link = ChannelClient.shared()
            .open(HohenheimChannels.INSTANCE_STATS, instanceId);
        link.onMessage(message -> {
            Double value = numberOf(message, metric);
            if (value == null) {
                return;
            }
            List<Double> next = new ArrayList<>(ref.peek() == null ? List.of() : ref.peek());
            next.add(value);
            // A new list per push, never a mutation in place: reactivity observes the REF,
            // and an in-place add would change the value without ever changing the ref.
            ref.setValue(trim(next));
        });
    }

    private static @Nullable Double numberOf(@Nullable Object message, @NonNull String metric) {
        if (!(message instanceof Map<?, ?> sample)) {
            return null;
        }
        Object value = sample.get(metric);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    /** Keep the plotted window bounded; an unbounded series is an unbounded page. */
    private static @NonNull List<Double> trim(@NonNull List<Double> values) {
        if (values.size() <= WINDOW) {
            return values;
        }
        return new ArrayList<>(values.subList(values.size() - WINDOW, values.size()));
    }
}
