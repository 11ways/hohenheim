package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ServiceStatus;
import be.elevenways.spamservice.client.ServiceSummary;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime readiness and aggregate Spamservice control-plane overview. */
public final class SpamserviceOverviewPage extends PanelPage {

    public static final String SLUG = "spamservice";

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_overview"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("overview").withFilter("scope", "spamservice"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 1; }
    @Override public @NonNull Icon icon() { return Icon.of("shield"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext context) {
        SpamserviceManager manager = SpamserviceManager.get();
        SpamserviceManager.Snapshot snapshot = manager.snapshot();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", Microcopy.of("overview").withFilter("scope", "spamservice")
            .resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("runtime", Map.ofEntries(
            Map.entry("configured", snapshot.configured()), Map.entry("enabled", snapshot.enabled()),
            Map.entry("state", snapshot.state()), Map.entry("pid", snapshot.pid() != null ? snapshot.pid() : ""),
            Map.entry("baseUrl", snapshot.baseUrl() != null ? snapshot.baseUrl() : ""),
            Map.entry("artifactHash", snapshot.artifactHash() != null ? snapshot.artifactHash() : ""),
            Map.entry("crashes", snapshot.consecutiveCrashes()),
            Map.entry("error", snapshot.lastError() != null ? snapshot.lastError() : "")));
        vars.put("connected", false);
        vars.put("ready", "ready".equals(snapshot.state()));
        vars.put("service", Map.of());
        vars.put("checks", List.of());
        vars.put("summary", Map.of());
        vars.put("error", snapshot.lastError() != null ? snapshot.lastError() : "");

        if (manager.client() != null) {
            try {
                ServiceStatus status = manager.requireClient().status();
                ServiceSummary summary = manager.requireClient().summary();
                vars.put("connected", true);
                // The readiness FACT off the clientlib's own vocabulary: an unrecognized or
                // absent token parses to UNKNOWN, which is never ready.
                vars.put("ready", status.status().isReady());
                vars.put("service", Map.of("status", status.status().token(),
                    "service", status.service(),
                    "managementApi", status.managementApi()));
                vars.put("checks", status.checks().entrySet().stream().map(entry -> Map.<String, Object>of(
                    "name", entry.getKey(), "ok", entry.getValue())).toList());
                vars.put("summary", summary(summary));
                vars.put("error", "");
            } catch (SpamserviceApiException failure) {
                vars.put("error", failure.getMessage());
            }
        }
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/spamservice-overview"), vars);
    }

    private static Map<String, Object> summary(ServiceSummary summary) {
        return Map.of("clients", summary.clients(), "activeKeys", summary.activeKeys(),
            "securityEvents", summary.securityEvents(), "spamWords", summary.spamWords(),
            "samples", summary.samples(), "spamSamples", summary.spamSamples(),
            "confirmedSpam", summary.confirmedSpam(), "confirmedHam", summary.confirmedHam());
    }
}
