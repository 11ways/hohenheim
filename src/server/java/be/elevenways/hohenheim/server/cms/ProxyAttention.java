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
import be.elevenways.hohenheim.server.proxy.SiteDispatcher;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.tls.AcmeService;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.server.tls.CertificateExpiry;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
     *
     * AIDEV-NOTE: a failed HTTPS listener is the ROOT of every site sent to HTTPS refusing plain HTTP: it is about the
     * installation's HTTPS ({@link AttentionSubject#httpsTermination()}), which those sites' verdicts name as their
     * cause, says what it holds back, and {@link #httpsUnavailableWithForceSsl} stays silent while it does, so the one
     * cause is one item.
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
                action("act_open_certificates"))
                .about(AttentionSubject.httpsTermination(), refusedOverHttp(proxy)));
        } else if (httpsDegraded(proxy)) {
            // Partial mode: passthrough listens but termination failed, so the listener
            // reads healthy while every force_ssl vhost answers 503.
            items.add(item(AttentionSeverity.ERROR, "certificate",
                copy("proxy_https_degraded", "attention_title"),
                literal(proxy.getHttpsFailureReason()),
                CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES),
                action("act_open_certificates"))
                .about(AttentionSubject.httpsTermination(), refusedOverHttp(proxy)));
        }
    }

    /** @return whether the HTTPS listener runs while its termination failed (passthrough only) */
    private static boolean httpsDegraded(@NonNull ProxyServer proxy) {
        return proxy.getHttpsState() == ProxyServer.State.RUNNING && proxy.getHttpsFailureReason() != null;
    }

    /**
     * HTTPS cannot be served while sites are sent to HTTPS: those sites answer plain HTTP with an error page (the
     * fail-closed force_ssl gate in SiteDispatcher), so the operator must SEE it instead of a control that silently
     * stopped mattering. The detail says why nothing can be served; what it holds back names each site by what sends it
     * to HTTPS (its own Force HTTPS, or the global setting), never claiming a site forces what the setting forces.
     *
     * AIDEV-NOTE: raised only while no listener item speaks: with the listener failed or degraded, that item is the
     * root and names the same sites. What remains is a listener with nothing to terminate with: no certificate could
     * be loaded.
     */
    public static void httpsUnavailableWithForceSsl(List<AttentionItem> items) {
        var proxy = ServerMain.getProxyServer();
        if (proxy == null || proxy.isHttpsTerminationAvailable()
                || proxy.getHttpState() != ProxyServer.State.RUNNING
                || proxy.getHttpsState() == ProxyServer.State.FAILED || httpsDegraded(proxy)) {
            return;
        }
        Microcopy refused = refusedOverHttp(proxy);
        if (refused == null) {
            return;
        }
        long active = Models.get(CertificateModel.class).find()
            .where(CertificateModel.STATUS.eq(CertificateModel.STATUS_ACTIVE)).count();
        items.add(item(AttentionSeverity.ERROR, "certificate",
            copy("https_unavailable", "attention_title"),
            active == 0 ? copy("https_no_certificate", "attention_detail")
                : copy("https_certificates_unloaded", "attention_detail", "count", active),
            CmsRoutes.list(ADMIN, HohenheimSlugs.CERTIFICATES),
            action("act_open_certificates"))
            .about(AttentionSubject.httpsTermination(), refused));
    }

    /**
     * @return whose visitors get an error page over plain HTTP while HTTPS cannot be served, each named by what sends
     *         it to HTTPS; null when no site is sent there or plain HTTP is not served at all
     */
    private static @Nullable Microcopy refusedOverHttp(@NonNull ProxyServer proxy) {
        if (proxy.getHttpState() != ProxyServer.State.RUNNING) {
            return null;
        }
        SiteDispatcher.ForcedSites forced = proxy.getDispatcher().forcedSites();
        String own = appNames(forced.own());
        String bySetting = appNames(forced.bySetting());
        if (forced.isEmpty()) {
            return null;
        }
        if (forced.bySetting().isEmpty()) {
            return copy("https_refused_forced", "attention_detail", "sites", own);
        }
        return forced.own().isEmpty() ? copy("https_refused_setting", "attention_detail", "sites", bySetting)
            : copy("https_refused_both", "attention_detail", "forced", own, "sent", bySetting);
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
        if (!AppHealth.httpsTerminates()) {
            return;
        }
        Set<String> working = AppHealth.workingNames();
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
            // The address is the root of its site's error page (the site verdict's cause), so that item folds here.
            items.add(item(AttentionSeverity.ERROR, "lock",
                copy("forced_without_certificate", "attention_title", "hostname", hostname),
                copy("forced_without_certificate", "attention_detail"),
                SiteParts.recordRoute(ADMIN, site.get(SiteModel.ID)),
                action("act_fix_on", "name", AppDirectory.nameOf(site)))
                .about(AttentionSubject.address(domain.get(SiteDomainModel.ID)), null));
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
                    "site", AppDirectory.nameOf(site)),
                copy("open_protected_path", "attention_detail"),
                CmsRoutes.detail(ADMIN, ProtectedPathParts.SLUG, path.get(ProtectedPathModel.ID)),
                action("act_protect_path", "path", path.get(ProtectedPathModel.PATH))));
        }
    }

    /**
     * Working certificates that expire within the expiry alert's window ({@code CERT_EXPIRING}, the same rows the alert
     * fires for): an upload never renews itself and a Let's Encrypt certificate this close is one whose renewal is
     * stuck, so the condition stays here until it is renewed or replaced, where the alert said it once. A failed one
     * is {@link #errorCertificates}'s item.
     */
    public static void expiringCertificates(List<AttentionItem> items) {
        Instant cutoff = Now.instant().plus(AcmeService.EXPIRY_ALERT_DAYS, ChronoUnit.DAYS);
        for (Row cert : Models.get(CertificateModel.class).findExpiringSoon(cutoff)) {
            Instant expires = cert.get(CertificateModel.EXPIRES_ON);
            if (expires == null || !CertificateModel.STATUS_ACTIVE.equals(cert.get(CertificateModel.STATUS))) {
                continue;
            }
            String renewalError = cert.get(CertificateModel.RENEWAL_ERROR);
            boolean renews = Boolean.TRUE.equals(cert.get(CertificateModel.AUTO_RENEW))
                && CertificateModel.PROVIDER_LETSENCRYPT.equals(cert.get(CertificateModel.PROVIDER));
            Microcopy detail = renewalError != null && !renewalError.isBlank()
                ? copy("certificate_renewal_failing", "attention_detail", "reason", renewalError)
                : copy(renews ? "certificate_renewal_late" : "certificate_never_renews", "attention_detail");
            AttentionSeverity severity = CertificateExpiry.daysLeft(expires) < 0 ? AttentionSeverity.ERROR
                : AttentionSeverity.WARNING;
            items.add(item(severity, "certificate",
                copy("certificate_expiring", "attention_title", "name", cert.get(CertificateModel.NICE_NAME),
                    "expiry", CertificateExpiry.inSentence(expires)),
                detail,
                CmsRoutes.detail(ADMIN, HohenheimSlugs.CERTIFICATES, cert.get(CertificateModel.ID)),
                action("act_open_certificate")));
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
     * a verdict with no reason of its own, and a degraded site, read the item's plain sentence. A site names the cause
     * its verdict names (the host holding its app back, its workload that does not run, its address forced to HTTPS
     * without a certificate), so the dashboard folds it under that record's own item while that item is shown.
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
                items.add(item(down ? AttentionSeverity.ERROR : AttentionSeverity.WARNING, "globe",
                    copy(key, "attention_title", "name", AppDirectory.nameOf(site)),
                    reason != null ? reason : copy(key, "attention_detail"),
                    SiteParts.recordRoute(ADMIN, siteId),
                    action("act_open_app", "name", AppDirectory.nameOf(site)))
                    .causedBy(verdict != null ? verdict.cause() : null));
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
     * this panel said "All clear". The two shapes get different titles off
     * {@link RoutingProblem.Reason#unrouted()}; missing from routing or refusing every visitor is an error (the
     * site's verdict is broken either way), refusing only some requests a warning. The detail sentence is keyed by
     * the reason's own name under the {@code routing_problem} scope, so the reason vocabulary keeps its one home on
     * the enum; RoutingProblemsTest binds every member to a shipped sentence in en and nl.
     *
     * @param problems the dispatcher's recorded problems, injectable so the projection is testable
     */
    public static void routingProblems(@NonNull List<AttentionItem> items,
                                       @NonNull List<RoutingProblem> problems) {
        for (RoutingProblem problem : problems) {
            boolean unrouted = problem.reason().unrouted();
            String name = AppDirectory.nameOfSite(problem.siteId(), problem.siteName());
            // A site that turns every visitor away is as broken as one missing from routing (its app row's glyph).
            boolean broken = unrouted || problem.reason().refusesEveryVisitor();
            items.add(item(broken ? AttentionSeverity.ERROR : AttentionSeverity.WARNING, "route",
                copy(unrouted ? "site_unrouted" : "site_refusing", "attention_title",
                    "name", name),
                reasonOf(problem),
                SiteParts.recordRoute(ADMIN, problem.siteId()),
                action("act_fix_on", "name", name)));
        }
    }

    /** @return these sites by the names of the apps they belong to ({@link AppDirectory#nameOf}), joined */
    private static @NonNull String appNames(@NonNull List<Integer> siteIds) {
        List<String> names = new ArrayList<>(siteIds.size());
        for (int siteId : siteIds) {
            names.add(AppDirectory.nameOfSite(siteId, "#" + siteId));
        }
        return String.join(", ", names);
    }

    /** The localized sentence for a problem's reason, carrying its specific cause (in words when it has them). */
    static @NonNull Microcopy reasonOf(@NonNull RoutingProblem problem) {
        Object cause = problem.cause() != null ? problem.cause() : problem.detail() != null ? problem.detail() : "-";
        return copy(problem.reason().name().toLowerCase(Locale.ROOT), "routing_problem", "detail", cause);
    }
}
