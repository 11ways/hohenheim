package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.List;
import java.util.Locale;

import static be.elevenways.hohenheim.server.cms.AttentionItems.copy;
import static be.elevenways.hohenheim.server.cms.AttentionItems.item;
import static be.elevenways.hohenheim.server.cms.AttentionItems.literal;

/**
 * The PROXY role's attention items: certificates, listeners, force-SSL, site health and routing.
 *
 * Every projection reads in-memory proxy state or stored rows; nothing here dials an upstream.
 * The collectors are public so a test proves each projection directly, positive and negative,
 * instead of asserting against whatever the whole dashboard happens to hold.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
public final class ProxyAttention {

    private static final String ADMIN = HohenheimSlugs.ADMIN;

    private ProxyAttention() {
    }

    /**
     * A dead or degraded proxy listener, REGARDLESS of force_ssl population. The Aug 04
     * 2026 port-443 outage stayed invisible for six days partly because the only listener
     * attention item required force_ssl sites; this one fires on listener state alone.
     * The force-SSL twin below stays because it names the affected sites.
     */
    public static void failedProxyListeners(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) return;
        if (proxy.getHttpState() == ProxyServer.State.FAILED) {
            items.add(item(AttentionSeverity.ERROR, "sitemap",
                copy("proxy_http_listener", "attention_title"),
                literal(proxy.getHttpFailureReason()),
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG)));
        }
        if (proxy.getHttpsState() == ProxyServer.State.FAILED) {
            items.add(item(AttentionSeverity.ERROR, "certificate",
                copy("proxy_https_listener", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES)));
        } else if (proxy.getHttpsState() == ProxyServer.State.RUNNING
                && proxy.getHttpsFailureReason() != null) {
            // Partial mode: passthrough listens but termination failed, so the listener
            // reads healthy while every force_ssl vhost answers 503.
            items.add(item(AttentionSeverity.ERROR, "certificate",
                copy("proxy_https_degraded", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES)));
        }
    }

    /**
     * HTTPS termination is down while force-SSL routes exist: those sites answer plain
     * HTTP with a 503 (the fail-closed force_ssl gate in SiteDispatcher), so the operator
     * must SEE the inert control instead of a checkbox that silently stopped mattering.
     */
    public static void httpsUnavailableWithForceSsl(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null || proxy.isHttpsTerminationAvailable()
                || proxy.getHttpState() != ProxyServer.State.RUNNING) {
            return;
        }
        List<String> sites = proxy.getDispatcher().forceSslSiteNames();
        boolean globalForce = Boolean.TRUE.equals(
            Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Proxy.FORCE_HTTPS));
        boolean anyRoutes = proxy.getDispatcher().getExactRouteCount()
            + proxy.getDispatcher().getWildcardRouteCount()
            + proxy.getDispatcher().getRegexRouteCount() > 0;
        if (sites.isEmpty() && !(globalForce && anyRoutes)) {
            return;
        }
        items.add(item(AttentionSeverity.ERROR, "certificate",
            copy("https_unavailable", "attention_title"),
            copy("https_unavailable", "attention_detail",
                "sites", sites.isEmpty() ? "-" : String.join(", ", sites)),
            CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES)));
    }

    /** Certificates whose last renewal failed, linked to their detail page. */
    public static void errorCertificates(List<AttentionItem> items) {
        List<Row> rows = Models.get(CertificateModel.class).find()
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ERROR))
            .all();
        for (Row row : rows) {
            items.add(item(AttentionSeverity.ERROR, "certificate",
                copy("certificate", "attention_title", "name", row.get(CertificateModel.NICE_NAME)),
                literal(row.get(CertificateModel.RENEWAL_ERROR)),
                CmsRoutes.detail(ADMIN, HohenheimSlugs.CERTIFICATES, row.get(CertificateModel.ID))));
        }
    }

    /** Enabled sites whose live handler reports DOWN or DEGRADED (the handler's own health, no probe). */
    static void unhealthySites(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            return;
        }
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.ENABLED.eq(true))
            .all();
        for (Row site : sites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
            SiteHealth health = handler != null ? handler.getHealth() : null;
            if (health == SiteHealth.DOWN || health == SiteHealth.DEGRADED) {
                items.add(item(health == SiteHealth.DOWN ? AttentionSeverity.ERROR : AttentionSeverity.WARNING,
                    "globe",
                    copy("site", "attention_title", "name", site.get(SiteModel.NAME)),
                    copy(health == SiteHealth.DOWN ? "down" : "degraded", "attention_detail"),
                    CmsRoutes.detail(ADMIN, HohenheimSlugs.SITES, siteId)));
            }
        }
    }

    /** The running proxy's routing problems; nothing when no proxy runs in this process. */
    public static void routingProblems(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            return;
        }
        routingProblems(items, proxy.getDispatcher().routingProblems());
    }

    /**
     * One item per enabled site the last route load could not route as configured.
     *
     * AIDEV-NOTE: these used to reach the log only, so an enabled site could vanish from
     * routing (every hostname it owns answering 404) or answer 503 for every request while
     * this panel said "All clear". The two shapes get different titles and severities off
     * {@link RoutingProblem.Reason#unrouted()}: MISSING from routing is an error, routed but
     * refusing is a warning. The detail sentence is keyed by the reason's own name under the
     * {@code routing_problem} scope, so the reason vocabulary keeps its one home on the enum;
     * RoutingProblemsTest binds every member to a shipped sentence in en and nl.
     *
     * @param problems the dispatcher's recorded problems, injectable so the projection is testable
     */
    public static void routingProblems(@NonNull List<AttentionItem> items,
                                       @NonNull List<RoutingProblem> problems) {
        for (RoutingProblem problem : problems) {
            boolean unrouted = problem.reason().unrouted();
            items.add(item(unrouted ? AttentionSeverity.ERROR : AttentionSeverity.WARNING, "route",
                copy(unrouted ? "site_unrouted" : "site_refusing", "attention_title",
                    "name", problem.siteName()),
                reasonOf(problem),
                CmsRoutes.detail(ADMIN, HohenheimSlugs.SITES, problem.siteId())));
        }
    }

    /** The localized sentence for a problem's reason, carrying its specific cause. */
    static @NonNull Microcopy reasonOf(@NonNull RoutingProblem problem) {
        return copy(problem.reason().name().toLowerCase(Locale.ROOT), "routing_problem",
            "detail", problem.detail() != null ? problem.detail() : "-");
    }
}
