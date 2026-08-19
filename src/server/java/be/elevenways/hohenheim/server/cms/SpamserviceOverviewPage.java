package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.spamservice.SpamserviceManager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.spamservice.client.ServiceStatus;
import be.elevenways.spamservice.client.ServiceSummary;
import be.elevenways.spamservice.client.SpamserviceApiException;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.security.AccessContext;
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

    public static final String SLUG = "spamservice";

    /**
     * One demoted sub-surface: its slug plus the label and hint it already owns, so the
     * front door never spells a second name for a page that has one.
     */
    private record Section(@NonNull String slug, @NonNull Microcopy label,
                           @NonNull Microcopy hint, @NonNull Icon icon) {}

    /** The demoted sub-surfaces, in the order an operator meets them. */
    private static final List<Section> SECTIONS = List.of(
        new Section(SpamserviceInstallationResource.SLUG,
            Microcopy.of("installation").withFilter("scope", "spamservice"),
            Microcopy.of("installation_hint").withFilter("scope", "spamservice"),
            Icon.of("download")),
        new Section(SpamserviceClientsResource.SLUG,
            Microcopy.of("plural").withFilter("scope", "spamservice_client"),
            CmsSupport.navHint("spamservice_client"), Icon.of("users")),
        new Section(SpamserviceSamplesResource.SLUG,
            Microcopy.of("plural").withFilter("scope", "spamservice_sample"),
            CmsSupport.navHint("spamservice_sample"), Icon.of("file-lines")),
        new Section(SpamserviceSecurityEventsResource.SLUG,
            Microcopy.of("plural").withFilter("scope", "spamservice_event"),
            CmsSupport.navHint("spamservice_event"), Icon.of("shield-halved")),
        new Section(SpamserviceWordsResource.SLUG,
            Microcopy.of("plural").withFilter("scope", "spamservice_word"),
            CmsSupport.navHint("spamservice_word"), Icon.of("book")),
        new Section(SpamserviceReputationPage.SLUG,
            Microcopy.of("reputation").withFilter("scope", "spamservice"),
            Microcopy.of("reputation_hint").withFilter("scope", "spamservice"),
            Icon.of("magnifying-glass")));

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "spamservice_overview"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("overview").withFilter("scope", "spamservice"); }
    @Override public @NonNull String slug() { return SLUG; }
    @Override public @NonNull NavGroup navGroup() { return HohenheimPanel.SECURITY_GROUP; }
    @Override public int navOrder() { return 10; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "spamservice");
    }
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
        vars.put("sections", sections(conduit));
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

    /** The demoted sub-surfaces as render state: resolved label, hint, icon token and URL. */
    private static @NonNull List<Map<String, Object>> sections(@NonNull Conduit conduit) {
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Section section : SECTIONS) {
            resolved.add(Map.of(
                "label", section.label().resolve(conduit.getLocales(), conduit.getMessageResolver()),
                "hint", section.hint().resolve(conduit.getLocales(), conduit.getMessageResolver()),
                "icon", section.icon().name(),
                "url", CmsRoutes.list(CmsSupport.panelSlug(conduit), section.slug()).toUrl()));
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
