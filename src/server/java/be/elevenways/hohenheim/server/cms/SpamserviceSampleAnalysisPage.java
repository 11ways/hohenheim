package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.SampleDetail;
import be.elevenways.spamservice.client.SampleSummary;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.RecordScopedPage;
import be.elevenways.zenit.cms.server.page.RecordTabs;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Analysis tab for a remotely stored Spamservice sample. */
public final class SpamserviceSampleAnalysisPage implements RecordScopedPage<SampleSummary> {

    public static final String SLUG = "analysis";
    private final SpamserviceSamplesResource resource;

    public SpamserviceSampleAnalysisPage(@NonNull SpamserviceSamplesResource resource) {
        this.resource = Objects.requireNonNull(resource, "resource cannot be null");
    }

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_sample_analysis"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("analysis").withFilter("scope", "spamservice_sample"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull Icon icon() { return Icon.of("magnifying-glass-chart"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext context,
                                           @NonNull SampleSummary record) {
        SampleDetail detail = this.resource.requireClient().sample(record.id());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, "spamservice_sample",
            value(record.ip(), record.id())));
        vars.put("summary", summary(detail));
        vars.put("location", entries(detail.location()));
        vars.put("asn", entries(detail.asn()));
        vars.put("properties", detail.properties().stream().map(property -> Map.<String, Object>of(
            "name", property.name(), "value", value(property.value(), ""),
            "language", value(property.language(), ""))).toList());
        vars.put("breakdown", detail.breakdown().stream().map(line -> Map.<String, Object>of(
            "flag", line.flag(), "points", line.points(), "detail", value(line.detail(), ""))).toList());
        Panel panel = PanelRegistry.getBySlug("admin");
        vars.put("recordTabs", RecordTabs.build(panel, this.resource, record.id(), record, context, SLUG));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/spamservice-sample-analysis"), vars);
    }

    private static Map<String, Object> summary(SampleDetail detail) {
        SampleSummary row = detail.summary();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spam", row.spam());
        result.put("score", row.score());
        result.put("confirmed", row.confirmed());
        result.put("threshold", detail.threshold());
        result.put("heuristicScore", detail.heuristicScore());
        result.put("confirmedOrigin", value(detail.confirmedOrigin(), ""));
        result.put("clientId", value(row.clientId(), ""));
        result.put("ip", value(row.ip(), ""));
        result.put("useragent", value(detail.useragent(), ""));
        result.put("languages", value(row.languages(), ""));
        result.put("flags", value(row.flags(), ""));
        result.put("createdAt", row.createdAt() != null ? row.createdAt().toString() : "");
        return result;
    }

    private static List<Map<String, Object>> entries(Map<String, Object> values) {
        return values.entrySet().stream().map(entry -> Map.<String, Object>of(
            "name", entry.getKey(), "value", String.valueOf(entry.getValue()))).toList();
    }

    private static String value(@Nullable String value, String fallback) {
        return value != null ? value : fallback;
    }
}
