package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Stats tab on an instance: live CPU, memory and network, plotted from the hub's ring and
 * kept live by the instance-stats channel.
 *
 * LIVE OBSERVATION ONLY, by decision -- the ring is whatever a currently-watched instance
 * has accumulated and nothing is stored. An instance nobody is watching renders an empty
 * chart that fills in as soon as the channel link opens, which is the honest rendering of
 * "there is no history because we keep none".
 */
public final class InstanceStatsPage implements RecordScopedPage<Row> {

    public static final String SLUG = "stats";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "instance_stats_page"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("stats").withFilter("scope", "instance"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("chart-line"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit,
                                           @NonNull AccessContext accessContext,
                                           @NonNull Row instance) {
        Integer instanceId = instance.get(InstanceModel.ID);
        String status = instance.get(InstanceModel.STATUS);
        List<InstanceStats.Sample> history = InstanceStats.history(instanceId);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status)
            || InstanceModel.STATUS_STARTING.equals(status));
        vars.put("cpuSeed", seriesOf(history, InstanceStats.Sample::cpuPercent));
        vars.put("memorySeed", seriesOf(history, sample -> sample.memoryBytes() / 1048576d));
        vars.put("rxSeed", seriesOf(history, sample -> sample.rxBytes() / 1024d));
        vars.put("txSeed", seriesOf(history, sample -> sample.txBytes() / 1024d));
        // The PERSISTED half, beside the live ring: the disk sweeper's stored observation
        // is the only number on this page that survives a restart, and on a runtime that
        // enforces no root quota there is deliberately none. Stating both is the point --
        // a live-only page that silently omits the one stored figure reads as "we measure
        // nothing", which is wrong in one direction and right in the other.
        vars.put("disk", InstanceOverviewPage.diskViewOf(instance));
        vars.put("recordTabs", recordTabs(conduit));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/instance-stats"), vars);
    }

    private static @NonNull List<Object> seriesOf(@NonNull List<InstanceStats.Sample> history,
                                                  @NonNull ToDoubleFunction<InstanceStats.Sample> value) {
        List<Object> points = new ArrayList<>();
        for (InstanceStats.Sample sample : history) {
            points.add(value.applyAsDouble(sample));
        }
        return points;
    }
}
