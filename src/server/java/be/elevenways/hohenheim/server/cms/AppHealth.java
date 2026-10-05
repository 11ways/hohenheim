package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.sitetype.SiteRequestHandler;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The health verdict of an app, for both of its record pages: a site and the instance behind it. The overview's first
 * band, the list's health column and every other health reader ask this one producer, so a green badge can never sit
 * beside a page that tells visitors an error.
 *
 * AIDEV-NOTE: the verdict READS the facts other surfaces already own, never a second copy of their rules: whether
 * HTTPS works is {@link CertificateCoverage} (the TLS cell's read), whether a path is open is
 * {@link ProtectedPathInvariant#isOpen}, why a workload cannot start is {@link OwnedInstances#placementRefusal} (the
 * power buttons' availability), and the live answer is the proxy handler's own {@link SiteHealth}. The order below is
 * the order a visitor would hit them: no answer at all, an error page, then what only the operator notices.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class AppHealth {

    private AppHealth() {
    }

    /** @param delegated whether the twin is /manage, whose row actions are a subset and whose words name no host */
    static @NonNull ResourceHealth<Row> sites(boolean delegated) {
        return ResourceHealth.batch((sites, access) -> {
            SiteFacts facts = SiteFacts.of(sites);
            return site -> siteVerdict(site, facts, delegated);
        });
    }

    /** @param delegated whether the twin is /manage, whose row actions are a subset and whose words name no host */
    static @NonNull ResourceHealth<Row> instances(boolean delegated) {
        return ResourceHealth.batch((instances, access) -> {
            Map<Integer, List<Row>> sitesByInstance = sitesServing(instances);
            Set<String> working = CertificateCoverage.activeNames();
            return instance -> instanceVerdict(instance, sitesByInstance, working, delegated);
        });
    }

    // -- sites -----------------------------------------------------------------------

    private static @NonNull RecordHealth siteVerdict(@NonNull Row site, @NonNull SiteFacts facts, boolean delegated) {
        Integer siteId = site.get(SiteModel.ID);
        if (site.get(SiteModel.DELETED_AT) != null) {
            return RecordHealth.unknown(copy("in_trash"));
        }
        if (!Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
            return RecordHealth.attention(copy("switched_off")).detail(copy("switched_off_detail"))
                .fixedBy(SiteOperations.ENABLE.id());
        }
        List<Row> domains = facts.domains.getOrDefault(siteId, List.of());
        if (domains.isEmpty()) {
            return RecordHealth.attention(copy("no_address")).detail(copy("no_address_detail"))
                .fixedBy(SiteActions.ADD_ADDRESS);
        }
        boolean passthrough = SiteParts.tlsPassthrough(site);
        String forced = forcedWithoutCertificate(domains, facts.working, passthrough);
        if (forced != null) {
            return RecordHealth.broken(copy("error_page"))
                .detail(copy("forced_without_certificate").withArg("host", forced))
                .fixedBy(SiteActions.FIX_HTTPS);
        }
        Row instance = facts.instances.get(site.get(SiteModel.INSTANCE_ID));
        if (instance != null) {
            RecordHealth workload = workloadVerdict(instance, delegated);
            if (workload != null) {
                return workload;
            }
        }
        SiteHealth live = facts.live.get(siteId);
        if (live == SiteHealth.DOWN) {
            return RecordHealth.broken(copy("error_page")).detail(copy("upstream_down"));
        }
        Row open = firstOpenPath(facts.paths.getOrDefault(siteId, List.of()));
        if (open != null) {
            return RecordHealth.attention(copy("path_open").withArg("path", open.get(ProtectedPathModel.PATH)))
                .detail(copy("path_open_detail"))
                .fixedBy(SiteActions.FIX_PROTECTION);
        }
        if (live == SiteHealth.DEGRADED) {
            return RecordHealth.attention(copy("degraded")).detail(copy("degraded_detail"));
        }
        return RecordHealth.ok(copy("live_at").withArg("address", liveAddress(domains, facts.working, passthrough)));
    }

    /**
     * Why a site's workload keeps visitors out, or null when it serves: the site page says it in the site's words and
     * leaves the fix to the instance page, where the host check lives.
     */
    private static @Nullable RecordHealth workloadVerdict(@NonNull Row instance, boolean delegated) {
        Microcopy refusal = OwnedInstances.placementRefusal(instance);
        if (refusal != null) {
            return RecordHealth.broken(copy("error_page"))
                .detail(OwnedInstances.placementReason(refusal, delegated));
        }
        InstanceStatus status = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        // servable() keeps a route through an ERROR (the handler answers its own failure page), which is exactly what a
        // visitor experiences as an error page.
        if (status == null || status.servable() && status != InstanceStatus.ERROR) {
            return null;
        }
        return RecordHealth.broken(copy("error_page"))
            .detail(copy("workload_not_running").withArg("name", instance.get(InstanceModel.NAME)));
    }

    // -- instances -------------------------------------------------------------------

    private static @NonNull RecordHealth instanceVerdict(@NonNull Row instance,
                                                         @NonNull Map<Integer, List<Row>> sitesByInstance,
                                                         @NonNull Set<String> working, boolean delegated) {
        String installError = instance.get(InstanceModel.INSTALL_ERROR);
        if (installError != null && !installError.isBlank()) {
            return RecordHealth.broken(copy("install_failed")).detail(copy("install_failed_detail"));
        }
        Microcopy refusal = OwnedInstances.placementRefusal(instance);
        if (refusal != null) {
            RecordHealth blocked = RecordHealth.attention(
                    copy("cannot_start").withArg("name", instance.get(InstanceModel.NAME)))
                .detail(OwnedInstances.placementReason(refusal, delegated));
            return delegated ? blocked : blocked.fixedBy(InstanceActions.CHECK_HOST);
        }
        InstanceStatus status = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        if (status == null) {
            return RecordHealth.unknown(copy("status_unknown"));
        }
        return switch (status) {
            case RUNNING -> RecordHealth.ok(liveHeadline(sitesByInstance.get(instance.get(InstanceModel.ID)), working));
            case ERROR -> delegated
                ? RecordHealth.broken(copy("stopped_after_error"))
                : RecordHealth.broken(copy("stopped_after_error")).fixedBy(InstanceOperations.RESTART.id());
            case STOPPED, CREATED -> RecordHealth.attention(copy("not_running")).detail(copy("not_running_detail"))
                .fixedBy(InstanceOperations.START.id());
            case STARTING, CAPTURING, RESTORING, MIGRATING -> RecordHealth.unknown(status.label());
        };
    }

    private static @NonNull Microcopy liveHeadline(@Nullable List<Row> sites, @NonNull Set<String> working) {
        if (sites != null) {
            for (Row site : sites) {
                List<Row> domains = SiteParts.domainsOf(site);
                if (!domains.isEmpty() && Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
                    return copy("live_at").withArg("address",
                        liveAddress(domains, working, SiteParts.tlsPassthrough(site)));
                }
            }
        }
        return copy("running");
    }

    // -- the rules the verdict and its fix actions share ----------------------------------

    /** @return the first exact name forced to HTTPS that no working certificate covers, null when none is */
    static @Nullable String forcedWithoutCertificate(@NonNull List<Row> domains, @NonNull Set<String> working,
                                                     boolean passthrough) {
        if (passthrough) {
            return null;
        }
        for (Row domain : domains) {
            String hostname = domain.get(SiteDomainModel.HOSTNAME);
            if (exact(domain) && Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL))
                    && !CertificateCoverage.covers(working, hostname)) {
                return hostname;
            }
        }
        return null;
    }

    /** @return the first of these paths whose protection admits everyone, null when none does */
    static @Nullable Row firstOpenPath(@NonNull List<Row> paths) {
        for (Row path : paths) {
            if (ProtectedPathInvariant.isOpen(path)) {
                return path;
            }
        }
        return null;
    }

    /** Whether the Get a certificate fix applies to this one site (its row action's visibility). */
    static boolean needsCertificate(@NonNull Row site) {
        return forcedWithoutCertificate(SiteParts.domainsOf(site), CertificateCoverage.activeNames(),
            SiteParts.tlsPassthrough(site)) != null;
    }

    /** Whether the Add an address fix applies to this one site. */
    static boolean needsAddress(@NonNull Row site) {
        return SiteParts.domainsOf(site).isEmpty();
    }

    /** Whether the Fix protection fix applies to this one site. */
    static boolean hasOpenPath(@NonNull Row site) {
        return firstOpenPath(Models.get(ProtectedPathModel.class).find()
            .where(ProtectedPathModel.SITE_ID.eq(site.get(SiteModel.ID))).all()) != null;
    }

    // -- shared ----------------------------------------------------------------------


    /** The address a visitor types: the first exact name, with the scheme that actually works for it. */
    static @NonNull String liveAddress(@NonNull List<Row> domains, @NonNull Set<String> working, boolean passthrough) {
        String url = exactUrl(domains, working, passthrough);
        return url != null ? url : String.valueOf((Object) domains.get(0).get(SiteDomainModel.HOSTNAME));
    }

    /**
     * Where a site's Open site link goes: its first exact name over the scheme that works, or null while visitors
     * reach nothing there (trashed, switched off, no exact name, or a name forced to HTTPS without a certificate).
     */
    static @Nullable String openUrl(@NonNull Row site) {
        if (site.get(SiteModel.DELETED_AT) != null || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
            return null;
        }
        List<Row> domains = SiteParts.domainsOf(site);
        Set<String> working = CertificateCoverage.activeNames();
        boolean passthrough = SiteParts.tlsPassthrough(site);
        if (forcedWithoutCertificate(domains, working, passthrough) != null) {
            return null;
        }
        return exactUrl(domains, working, passthrough);
    }

    /** Where an instance's Open site link goes: the first site serving it that visitors reach, null when none does. */
    static @Nullable String openUrlOfInstance(@NonNull Row instance) {
        for (Row site : Models.get(SiteModel.class).find()
                .where(SiteModel.INSTANCE_ID.eq(instance.get(InstanceModel.ID))).all()) {
            String url = openUrl(site);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /** @return the first exact name with the scheme that works for it, null when these domains hold no exact name */
    private static @Nullable String exactUrl(@NonNull List<Row> domains, @NonNull Set<String> working,
                                             boolean passthrough) {
        for (Row domain : domains) {
            if (exact(domain)) {
                String hostname = domain.get(SiteDomainModel.HOSTNAME);
                boolean https = passthrough || CertificateCoverage.covers(working, hostname);
                return (https ? "https://" : "http://") + hostname;
            }
        }
        return null;
    }

    static boolean exact(@NonNull Row domain) {
        return SiteDomainModel.MATCH_EXACT.equals(SiteDomainModel.effectiveMatchType(
            domain.get(SiteDomainModel.HOSTNAME), domain.get(SiteDomainModel.MATCH_TYPE)));
    }

    private static @NonNull Map<Integer, List<Row>> sitesServing(@NonNull List<Row> instances) {
        List<Integer> ids = new ArrayList<>();
        for (Row instance : instances) {
            ids.add(instance.get(InstanceModel.ID));
        }
        Map<Integer, List<Row>> byInstance = new HashMap<>();
        if (ids.isEmpty()) {
            return byInstance;
        }
        for (Row site : Models.get(SiteModel.class).find().where(SiteModel.INSTANCE_ID.in(ids)).all()) {
            byInstance.computeIfAbsent(site.get(SiteModel.INSTANCE_ID), id -> new ArrayList<>()).add(site);
        }
        return byInstance;
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "app_health");
    }

    /** Everything a page of site verdicts reads, read once for the page. */
    private record SiteFacts(@NonNull Map<Integer, List<Row>> domains, @NonNull Map<Integer, List<Row>> paths,
                             @NonNull Map<Integer, Row> instances, @NonNull Map<Integer, SiteHealth> live,
                             @NonNull Set<String> working) {

        static @NonNull SiteFacts of(@NonNull List<Row> sites) {
            List<Integer> siteIds = new ArrayList<>();
            List<Integer> instanceIds = new ArrayList<>();
            for (Row site : sites) {
                siteIds.add(site.get(SiteModel.ID));
                Integer instanceId = site.get(SiteModel.INSTANCE_ID);
                if (instanceId != null) {
                    instanceIds.add(instanceId);
                }
            }
            Map<Integer, List<Row>> domains = new HashMap<>();
            Map<Integer, List<Row>> paths = new HashMap<>();
            Map<Integer, Row> instances = new HashMap<>();
            Map<Integer, SiteHealth> live = new HashMap<>();
            if (!siteIds.isEmpty()) {
                for (Row domain : Models.get(SiteDomainModel.class).find()
                        .where(SiteDomainModel.SITE_ID.in(siteIds)).all()) {
                    domains.computeIfAbsent(domain.get(SiteDomainModel.SITE_ID), id -> new ArrayList<>()).add(domain);
                }
                for (Row path : Models.get(ProtectedPathModel.class).find()
                        .where(ProtectedPathModel.SITE_ID.in(siteIds)).all()) {
                    paths.computeIfAbsent(path.get(ProtectedPathModel.SITE_ID), id -> new ArrayList<>()).add(path);
                }
                var proxy = ServerMain.getProxyServer();
                if (proxy != null) {
                    for (Integer siteId : siteIds) {
                        SiteRequestHandler handler = proxy.getDispatcher().findHandlerBySiteId(siteId);
                        if (handler != null) {
                            live.put(siteId, handler.getHealth());
                        }
                    }
                }
            }
            if (!instanceIds.isEmpty()) {
                for (Row instance : Models.get(InstanceModel.class).find()
                        .where(InstanceModel.ID.in(instanceIds)).all()) {
                    instances.put(instance.get(InstanceModel.ID), instance);
                }
            }
            return new SiteFacts(domains, paths, instances, live, CertificateCoverage.activeNames());
        }
    }
}
