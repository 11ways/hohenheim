package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimStatsFunctions;
import be.elevenways.hohenheim.HohenheimStatsFunctions.Metric;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.instance.InstanceResize;
import be.elevenways.hohenheim.server.instance.InstanceStats;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
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
public final class InstanceStatsPage implements RecordTab.Rendered<Row> {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("instance_stats"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.INSTANCE.of("stats"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.STATS; }
    @Override public @NonNull Icon icon() { return Icon.of("chart-line"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull Row instance) {
        Conduit conduit = request.conduit();
        Integer instanceId = instance.get(InstanceModel.ID);
        String status = instance.get(InstanceModel.STATUS);
        List<InstanceStats.Sample> history = InstanceStats.history(instanceId);

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", instance.get(InstanceModel.NAME));
        vars.put("instanceName", instance.get(InstanceModel.NAME));
        vars.put("instanceId", instanceId);
        vars.put("running", InstanceModel.STATUS_RUNNING.equals(status)
            || InstanceModel.STATUS_STARTING.equals(status));
        // Scaled through the SAME Metric divisors the browser folds live samples with, so
        // the seeded half of a series can never drift from the live half.
        vars.put("cpuSeed", seriesOf(history,
            sample -> Metric.CPU.scaled(sample.cpuPercent())));
        vars.put("memorySeed", seriesOf(history,
            sample -> Metric.MEMORY.scaled(sample.memoryBytes())));
        // Received and sent are running totals on the wire; they plot as a rate, through the
        // SAME rate the browser folds live samples with.
        List<Map<String, Object>> wire = new ArrayList<>();
        for (InstanceStats.Sample sample : history) {
            wire.add(sample.toMap());
        }
        vars.put("rxSeed", ratesOf(wire, Metric.RX));
        vars.put("txSeed", ratesOf(wire, Metric.TX));
        // The two newest samples: the "now" readings render from them before the first
        // live sample arrives, and the first live rate is taken against the newest.
        vars.put("latestSeed", wire.isEmpty() ? null : wire.get(wire.size() - 1));
        vars.put("previousSeed", wire.size() < 2 ? null : wire.get(wire.size() - 2));
        Double cpus = ResourceLimits.fromSettings(InstanceResize.settingsOf(instance)).cpus();
        boolean capped = cpus != null && cpus > 0;
        vars.put("cpuLimit", capped ? cpus : null);
        vars.put("cpuCores", capped ? coresText(cpus) : null);
        // The PERSISTED half, beside the live ring: the disk sweeper's stored observation
        // is the only number on this page that survives a restart, and on a runtime that
        // enforces no root quota there is deliberately none. Stating both is the point --
        // a live-only page that silently omits the one stored figure reads as "we measure
        // nothing", which is wrong in one direction and right in the other.
        vars.put("disk", InstanceOverview.diskViewOf(instance));
        vars.put("head", recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.INSTANCE_STATS, vars);
    }

    /** A configured core count as written: "1", "2", "1.5". */
    static @NonNull String coresText(double cores) {
        if (cores == Math.rint(cores)) {
            return String.valueOf((long) cores);
        }
        return String.valueOf(Math.round(cores * 10d) / 10d);
    }

    private static @NonNull List<Object> ratesOf(@NonNull List<Map<String, Object>> wire, @NonNull Metric metric) {
        List<Object> points = new ArrayList<>();
        for (int i = 1; i < wire.size(); i++) {
            double rate = HohenheimStatsFunctions.rateBetween(wire.get(i - 1), wire.get(i), metric);
            if (rate >= 0) {
                points.add(metric.scaled(rate));
            }
        }
        return points;
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
