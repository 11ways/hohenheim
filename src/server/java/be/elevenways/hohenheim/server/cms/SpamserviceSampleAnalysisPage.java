package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.SampleDetail;
import be.elevenways.spamservice.client.SampleSummary;
import be.elevenways.spamservice.client.SpamserviceClient;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.RecordTab;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Analysis tab for a remotely stored Spamservice sample. */
public final class SpamserviceSampleAnalysisPage implements RecordTab.Rendered<SampleSummary> {

    private final Supplier<SpamserviceClient> clients;

    SpamserviceSampleAnalysisPage(@NonNull Supplier<SpamserviceClient> clients) {
        this.clients = Objects.requireNonNull(clients, "clients cannot be null");
    }

    @Override public @NonNull Identifier id() { return HohenheimIds.id("spamservice_sample_analysis"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("analysis"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.Tab.ANALYSIS; }
    @Override public @NonNull Icon icon() { return Icon.of("magnifying-glass-chart"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request, @NonNull SampleSummary record) {
        Conduit conduit = request.conduit();
        SampleDetail detail = SpamserviceRemoteStore.require(this.clients).sample(record.id());
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", CmsSupport.pageTitle(conduit, HohenheimMicrocopy.SPAMSERVICE_SAMPLE,
            Objects.toString(record.ip(), record.id())));
        vars.put("summary", summary(detail));
        vars.put("location", SpamserviceRemoteStore.nameValueRows(detail.location()));
        vars.put("asn", SpamserviceRemoteStore.nameValueRows(detail.asn()));
        vars.put("properties", detail.properties().stream().map(property -> Map.<String, Object>of(
            "name", property.name(), "value", Objects.requireNonNullElse(property.value(), ""),
            "language", Objects.requireNonNullElse(property.language(), ""))).toList());
        vars.put("breakdown", detail.breakdown().stream().map(line -> Map.<String, Object>of(
            "flag", line.flag(), "points", line.points(), "detail", Objects.requireNonNullElse(line.detail(), ""))).toList());
        vars.put("head", this.recordHead(conduit));
        return new RenderTemplateResult(HohenheimTemplateIds.SPAMSERVICE_SAMPLE_ANALYSIS, vars);
    }

    private static Map<String, Object> summary(SampleDetail detail) {
        SampleSummary row = detail.summary();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spam", row.spam());
        result.put("score", row.score());
        result.put("confirmed", row.confirmed());
        result.put("threshold", detail.threshold());
        result.put("heuristicScore", detail.heuristicScore());
        result.put("confirmedOrigin", Objects.requireNonNullElse(detail.confirmedOrigin(), ""));
        result.put("clientId", Objects.requireNonNullElse(row.clientId(), ""));
        result.put("ip", Objects.requireNonNullElse(row.ip(), ""));
        result.put("useragent", Objects.requireNonNullElse(detail.useragent(), ""));
        result.put("languages", Objects.requireNonNullElse(row.languages(), ""));
        result.put("flags", Objects.requireNonNullElse(row.flags(), ""));
        result.put("createdAt", row.createdAt() != null ? row.createdAt().toString() : "");
        return result;
    }
}
