package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ServiceStatus;
import be.elevenways.spamservice.client.ServiceSummary;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime readiness and aggregate Spamservice control-plane overview, and THE front door
 * of the abuse-protection subsystem.
 *
 * AIDEV-NOTE: this page is the reason the five spamservice sub-resources and the reputation
 * page are showInNav(false). Six near-identical "Spamservice ..." entries used to open the
 * Security group and taught a newcomer nothing about which one to click. The links below are
 * the reachability half of that demotion -- deleting one hides a working surface behind a
 * URL nobody types, so they are covered by AdminNavigationJourneyTest.
 */
public final class SpamserviceOverviewPage extends PanelPage {

    /**
     * One demoted sub-surface: its slug plus the label and hint it already owns, so the
     * front door never spells a second name for a page that has one.
     */
    private record Section(@NonNull String slug, @NonNull Microcopy label,
                           @NonNull Microcopy hint, @NonNull Icon icon) {}

    /** The demoted sub-surfaces, in the order an operator meets them. */
    private static final List<Section> SECTIONS = List.of(
        new Section(HohenheimSlugs.SPAMSERVICE_INSTALLATION,
            HohenheimMicrocopy.SPAMSERVICE.of("installation"),
            HohenheimMicrocopy.SPAMSERVICE.of("installation_hint"),
            Icon.of("download")),
        new Section(HohenheimSlugs.SPAMSERVICE_CLIENTS,
            HohenheimMicrocopy.SPAMSERVICE_CLIENT.of("plural"),
            CmsSupport.navHint(HohenheimMicrocopy.SPAMSERVICE_CLIENT), Icon.of("users")),
        new Section(HohenheimSlugs.SPAMSERVICE_SAMPLES,
            HohenheimMicrocopy.SPAMSERVICE_SAMPLE.of("plural"),
            CmsSupport.navHint(HohenheimMicrocopy.SPAMSERVICE_SAMPLE), Icon.of("file-lines")),
        new Section(HohenheimSlugs.SPAMSERVICE_SECURITY_EVENTS,
            HohenheimMicrocopy.SPAMSERVICE_EVENT.of("plural"),
            CmsSupport.navHint(HohenheimMicrocopy.SPAMSERVICE_EVENT), Icon.of("shield-halved")),
        new Section(HohenheimSlugs.SPAMSERVICE_WORDS,
            HohenheimMicrocopy.SPAMSERVICE_WORD.of("plural"),
            CmsSupport.navHint(HohenheimMicrocopy.SPAMSERVICE_WORD), Icon.of("book")),
        new Section(HohenheimSlugs.SPAMSERVICE_REPUTATION,
            HohenheimMicrocopy.SPAMSERVICE.of("reputation"),
            HohenheimMicrocopy.SPAMSERVICE.of("reputation_hint"),
            Icon.of("magnifying-glass")));

    @Override public @NonNull Identifier id() { return HohenheimIds.id("spamservice_overview"); }
    @Override public @NonNull Microcopy label() { return HohenheimMicrocopy.SPAMSERVICE.of("overview"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.SPAMSERVICE; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 30; }

    @Override
    public @Nullable Microcopy description() {
        return HohenheimMicrocopy.SPAMSERVICE.of("nav_hint");
    }
    @Override public @NonNull Icon icon() { return Icon.of("shield"); }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        SpamserviceManager manager = SpamserviceManager.get();
        SpamserviceManager.Snapshot snapshot = manager.snapshot();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("title", HohenheimMicrocopy.SPAMSERVICE.of("overview")
            .asString(conduit));
        vars.put("runtime", Map.ofEntries(
            Map.entry("configured", snapshot.configured()), Map.entry("enabled", snapshot.enabled()),
            Map.entry("state", snapshot.state()), Map.entry("pid", snapshot.pid() != null ? snapshot.pid() : ""),
            Map.entry("baseUrl", snapshot.baseUrl() != null ? snapshot.baseUrl() : ""),
            Map.entry("artifactHash", snapshot.artifactHash() != null ? snapshot.artifactHash() : ""),
            Map.entry("crashes", snapshot.consecutiveCrashes()),
            Map.entry("error", snapshot.lastError() != null ? snapshot.lastError() : "")));
        vars.put("sections", sections(request));
        vars.put("connected", false);
        vars.put("ready", snapshot.ready());
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
        return new RenderTemplateResult(HohenheimTemplateIds.SPAMSERVICE_OVERVIEW, vars);
    }

    /** The demoted sub-surfaces as render state: resolved label, hint, icon token and URL. */
    private static @NonNull List<Map<String, Object>> sections(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Section section : SECTIONS) {
            resolved.add(Map.of(
                "label", section.label().asString(conduit),
                "hint", section.hint().asString(conduit),
                "icon", section.icon().name(),
                "url", CmsRoutes.list(request.panelSlug(), section.slug()).toUrl()));
        }
        return resolved;
    }

    private static Map<String, Object> summary(ServiceSummary summary) {
        return Map.of("clients", summary.clients(), "activeKeys", summary.activeKeys(),
            "securityEvents", summary.securityEvents(), "spamWords", summary.spamWords(),
            "samples", summary.samples(), "spamSamples", summary.spamSamples(),
            "confirmedSpam", summary.confirmedSpam(), "confirmedHam", summary.confirmedHam());
    }
}
