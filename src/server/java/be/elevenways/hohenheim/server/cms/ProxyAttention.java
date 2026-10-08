package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.AttentionSeverity;
import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static be.elevenways.hohenheim.server.cms.AttentionItems.action;
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
                CmsRoutes.list(ADMIN, SettingsPage.DEFAULT_SLUG),
                action("act_open_settings")));
        }
        if (proxy.getHttpsState() == ProxyServer.State.FAILED) {
            items.add(item(AttentionSeverity.ERROR, "certificate",
                copy("proxy_https_listener", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES),
                action("act_open_certificates")));
        } else if (proxy.getHttpsState() == ProxyServer.State.RUNNING
                && proxy.getHttpsFailureReason() != null) {
            // Partial mode: passthrough listens but termination failed, so the listener
            // reads healthy while every force_ssl vhost answers 503.
            items.add(item(AttentionSeverity.ERROR, "certificate",
                copy("proxy_https_degraded", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES),
                action("act_open_certificates")));
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
        // The dispatcher's own forcing rule names the refusing sites (the global force_https included), so a fresh
        // install with nothing forced raises nothing and the list is never empty.
        List<String> sites = proxy.getDispatcher().forceSslSiteNames();
        if (sites.isEmpty()) {
            return;
        }
        items.add(item(AttentionSeverity.ERROR, "certificate",
            copy("https_unavailable", "attention_title"),
            copy("https_unavailable", "attention_detail", "sites", String.join(", ", sites)),
            CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES),
            action("act_open_certificates")));
    }

    /**
     * One item per name that forces HTTPS while no working certificate covers it: its visitors get an error page
     * (a 503, or a redirect into a handshake with no certificate for the name). New names are no longer forced before
     * a certificate works (ForceSslLatch), so this finds what earlier rows and explicit choices left behind.
     *
     * AIDEV-NOTE: silent while HTTPS termination is down: {@link #httpsUnavailableWithForceSsl} then names every
     * refusing site at once, and repeating each name would bury the cause.
     */
    public static void forcedWithoutCertificate(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null && !proxy.isHttpsTerminationAvailable()) {
            return;
        }
        Set<String> working = CertificateCoverage.activeNames();
        for (Row domain : Models.get(SiteDomainModel.class).find()
                .where(SiteDomainModel.FORCE_SSL.eq(true)).all()) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (!SiteDomainModel.MATCH_EXACT.equals(
                    SiteDomainModel.effectiveMatchType(hostname, domain.get(SiteDomainModel.MATCH_TYPE)))
                    || CertificateCoverage.covers(working, hostname)) {
                continue;
            }
            Row site = Models.get(SiteModel.class).findById(domain.get(SiteDomainModel.SITE_ID));
            if (site == null || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))
                    || site.get(SiteModel.DELETED_AT) != null) {
                continue;
            }
            items.add(item(AttentionSeverity.ERROR, "lock",
                copy("forced_without_certificate", "attention_title", "hostname", hostname),
                copy("forced_without_certificate", "attention_detail"),
                SiteParts.recordRoute(ADMIN, site.get(SiteModel.ID)),
                action("act_fix_on", "name", site.get(SiteModel.NAME))));
        }
    }

    /**
     * Protected paths on live sites whose access list lets every visitor through: they read as protection and guard
     * nothing. New writes cannot create one ({@link ProtectedPathInvariant}); these are the ones stored before.
     */
    public static void openProtectedPaths(List<AttentionItem> items) {
        for (Row path : Models.get(ProtectedPathModel.class).find().all()) {
            Row site = Models.get(SiteModel.class).findById(path.get(ProtectedPathModel.SITE_ID));
            if (site == null || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))
                    || site.get(SiteModel.DELETED_AT) != null || !ProtectedPathInvariant.isOpen(path)) {
                continue;
            }
            items.add(item(AttentionSeverity.ERROR, "lock-open",
                copy("open_protected_path", "attention_title", "path", path.get(ProtectedPathModel.PATH),
                    "site", site.get(SiteModel.NAME)),
                copy("open_protected_path", "attention_detail"),
                CmsRoutes.detail(ADMIN, ProtectedPathParts.SLUG, path.get(ProtectedPathModel.ID)),
                action("act_protect_path", "path", path.get(ProtectedPathModel.PATH))));
        }
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
                CmsRoutes.detail(ADMIN, HohenheimSlugs.CERTIFICATES, row.get(CertificateModel.ID)),
                action("act_open_certificate")));
        }
    }

    /**
     * Enabled sites whose live handler reports DOWN or DEGRADED (the handler's own health, no probe), each worded like
     * every other item: what visitors get, why in the app verdict's own words, and the way to the site.
     *
     * AIDEV-NOTE: a site the proxy turns away over its settings is DOWN too, and its routing-problem item already says
     * so with the proxy's own reason; it is not drawn twice. A down site's detail is its verdict's reason
     * ({@link AppHealth}, an error page at that point), so the band and the site's problem band give the same reason;
     * a verdict with no reason of its own, and a degraded site, read the item's plain sentence. A site whose app its host
     * holds back names that host as its cause (the verdict's own cause half), so the dashboard folds it under the host.
     */
    static void unhealthySites(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null) {
            return;
        }
        Set<Integer> refused = new HashSet<>();
        for (RoutingProblem problem : proxy.getDispatcher().routingProblems()) {
            if (problem.reason().refusesEveryVisitor()) {
                refused.add(problem.siteId());
            }
        }
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.ENABLED.eq(true))
            .all();
        for (Row site : sites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null || refused.contains(siteId)) {
                continue;
            }
            SiteHealth health = proxy.getDispatcher().healthOf(siteId);
            if (health == SiteHealth.DOWN || health == SiteHealth.DEGRADED) {
                boolean down = health == SiteHealth.DOWN;
                String key = down ? "site_down" : "site_degraded";
                AppHealth.Verdict verdict = down ? AppHealth.siteReading(site) : null;
                Microcopy reason = verdict != null ? verdict.health().detail() : null;
                Integer heldBy = verdict != null ? verdict.heldBy() : null;
                items.add(item(down ? AttentionSeverity.ERROR : AttentionSeverity.WARNING, "globe",
                    copy(key, "attention_title", "name", site.get(SiteModel.NAME)),
                    reason != null ? reason : copy(key, "attention_detail"),
                    SiteParts.recordRoute(ADMIN, siteId),
                    action("act_open_app", "name", site.get(SiteModel.NAME)))
                    .causedBy(heldBy != null ? AttentionSubject.host(heldBy) : null));
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
                SiteParts.recordRoute(ADMIN, problem.siteId()),
                action("act_fix_on", "name", problem.siteName())));
        }
    }

    /** The localized sentence for a problem's reason, carrying its specific cause (in words when it has them). */
    static @NonNull Microcopy reasonOf(@NonNull RoutingProblem problem) {
        Object cause = problem.cause() != null ? problem.cause() : problem.detail() != null ? problem.detail() : "-";
        return copy(problem.reason().name().toLowerCase(Locale.ROOT), "routing_problem", "detail", cause);
    }
}
