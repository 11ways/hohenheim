package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.IntegerField;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * power buttons' availability), why the proxy turns visitors away is its {@link RoutingProblem} (the attention item's
 * reason), and the live answer is the proxy handler's own {@link SiteHealth}. The order below is
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
            return site -> siteVerdict(site, facts, delegated, access).health();
        });
    }

    /** @param delegated whether the twin is /manage, whose row actions are a subset and whose words name no host */
    static @NonNull ResourceHealth<Row> instances(boolean delegated) {
        return ResourceHealth.batch((instances, access) -> {
            InstanceFacts facts = InstanceFacts.of(instances);
            return instance -> instanceVerdict(instance, facts, delegated, access).health();
        });
    }

    /** This site's verdict in the operator's words, for a surface that names the site itself (the attention band). */
    static @NonNull RecordHealth siteHealth(@NonNull Row site) {
        return siteReading(site).health();
    }

    /** This site's whole verdict in the operator's words: its health, serving half and cause (the attention band). */
    static @NonNull Verdict siteReading(@NonNull Row site) {
        return siteVerdict(site, SiteFacts.of(List.of(site)), false, null);
    }

    /**
     * What each host holds back: the authored workloads whose verdict is that host's placement refusal (the verdict's
     * cause half), keyed by host id.
     */
    static @NonNull Map<Integer, HeldBack> heldBackByHost() {
        List<Row> instances = Models.get(InstanceModel.class).find().where(InstanceModel.liveAuthored()).all();
        InstanceFacts facts = InstanceFacts.of(instances);
        Map<Integer, HeldBack> held = new LinkedHashMap<>();
        for (Row instance : instances) {
            Verdict verdict = instanceVerdict(instance, facts, false, null);
            if (verdict.heldBy() != null) {
                held.merge(verdict.heldBy(), new HeldBack(1, verdict.health().detail()),
                    (first, next) -> new HeldBack(first.apps() + 1, first.reason()));
            }
        }
        return held;
    }

    /**
     * The apps one host holds back.
     *
     * @param apps   how many
     * @param reason why, in the first held app's verdict words (the placement gate's refusal)
     */
    record HeldBack(int apps, @Nullable Microcopy reason) {}

    /** Whether visitors reach this site: its verdict's serving half (Open site's condition). */
    static boolean siteServes(@NonNull Row site) {
        return siteVerdict(site, SiteFacts.of(List.of(site)), false, null).serving();
    }

    /** Whether this instance runs and serves what visitors reach of it: its verdict's serving half. */
    static boolean instanceServes(@NonNull Row instance) {
        return instanceVerdict(instance, InstanceFacts.of(List.of(instance)), false, null).serving();
    }

    /**
     * Whether anything on this installation is online: an authored app that runs, or a website whose verdict serves
     * its visitors (a redirect or a proxy needs no workload). The first-run checklist's "Put your first app online".
     */
    static boolean anyOnline() {
        if (Models.get(InstanceModel.class).find().where(InstanceModel.liveAuthored())
                .where(InstanceModel.STATUS.eq(InstanceModel.STATUS_RUNNING)).count() > 0) {
            return true;
        }
        List<Row> sites = Models.get(SiteModel.class).find().all();
        SiteFacts facts = SiteFacts.of(sites);
        for (Row site : sites) {
            if (siteVerdict(site, facts, false, null).serving()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A stack's verdict from its stored status, the one fact its list badge reads too. A status this method does not
     * know reads as unknown, never as healthy.
     */
    static @NonNull ResourceHealth<Row> stacks() {
        return ResourceHealth.of((stack, access) -> stackVerdict(stack));
    }

    // -- sites -----------------------------------------------------------------------

    /**
     * @param viewer who reads the words, null where only the serving half is asked
     */
    private static @NonNull Verdict siteVerdict(@NonNull Row site, @NonNull SiteFacts facts, boolean delegated,
                                                @Nullable AccessContext viewer) {
        Integer siteId = site.get(SiteModel.ID);
        if (site.get(SiteModel.DELETED_AT) != null) {
            return Verdict.notServing(RecordHealth.unknown(copy("in_trash")));
        }
        if (!Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
            return Verdict.notServing(RecordHealth.attention(copy("switched_off")).detail(copy("switched_off_detail"))
                .fixedBy(SiteOperations.ENABLE.id()));
        }
        List<Row> domains = facts.domains.getOrDefault(siteId, List.of());
        if (domains.isEmpty()) {
            return Verdict.notServing(RecordHealth.attention(copy("no_address")).detail(copy("no_address_detail"))
                .fixedBy(SiteActions.ADD_ADDRESS));
        }
        List<RoutingProblem> problems = facts.problems.getOrDefault(siteId, List.of());
        for (RoutingProblem problem : problems) {
            // Its own refusal, never "does not answer": the proxy turns every visitor away and says why. Every such
            // refusal is the site's own settings (what it serves, its upstream, its sign-in provider), so the fix is
            // its configuration; /manage's form cannot change those, so the delegated verdict offers none.
            if (problem.reason().refusesEveryVisitor()) {
                RecordHealth refused = RecordHealth.broken(copy("error_page")).detail(ProxyAttention.reasonOf(problem));
                return Verdict.notServing(delegated ? refused : refused.fixedBy(SiteActions.CHANGE_CONFIGURATION));
            }
        }
        boolean passthrough = SiteParts.tlsPassthrough(site);
        String forced = forcedWithoutCertificate(domains, facts.working, passthrough);
        if (forced != null) {
            return Verdict.notServing(RecordHealth.broken(copy("error_page"))
                .detail(copy("forced_without_certificate").withArg("host", forced))
                .fixedBy(SiteActions.FIX_HTTPS, SiteOperations.STOP_FORCING_HTTPS.id()));
        }
        Row instance = facts.instances.get(site.get(SiteModel.INSTANCE_ID));
        if (instance != null) {
            Verdict workload = workloadVerdict(instance, delegated, viewer);
            if (workload != null) {
                return workload;
            }
        }
        SiteHealth live = facts.live.get(siteId);
        if (live == SiteHealth.DOWN) {
            return Verdict.notServing(RecordHealth.broken(copy("error_page")).detail(copy("upstream_down")));
        }
        Row open = firstOpenPath(facts.paths.getOrDefault(siteId, List.of()));
        if (open != null) {
            return Verdict.serving(RecordHealth.attention(copy("path_open")
                    .withArg("path", open.get(ProtectedPathModel.PATH)))
                .detail(copy("path_open_detail"))
                .fixedBy(SiteActions.FIX_PROTECTION));
        }
        if (!problems.isEmpty()) {
            return Verdict.serving(RecordHealth.attention(copy("routed_in_part"))
                .detail(ProxyAttention.reasonOf(problems.get(0))));
        }
        if (live == SiteHealth.DEGRADED) {
            return Verdict.serving(RecordHealth.attention(copy("degraded")).detail(copy("degraded_detail")));
        }
        return Verdict.serving(RecordHealth.ok(copy("live_at")
            .withArg("address", liveAddress(domains, facts.working, passthrough))));
    }

    /**
     * Why a site's workload keeps visitors out, or null when it serves: the site page says it in the site's words and
     * leaves the fix to the instance page, where the host check lives.
     */
    private static @Nullable Verdict workloadVerdict(@NonNull Row instance, boolean delegated,
                                                     @Nullable AccessContext viewer) {
        Microcopy refusal = OwnedInstances.placementRefusal(instance);
        if (refusal != null) {
            return Verdict.heldBy(OwnedInstances.placementHost(instance), RecordHealth.broken(copy("error_page"))
                .detail(OwnedInstances.placementReason(refusal, delegated, viewer)));
        }
        InstanceStatus status = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        if (status != null && workloadServes(status)) {
            return null;
        }
        return Verdict.notServing(RecordHealth.broken(copy("error_page"))
            .detail(copy("workload_not_running").withArg("name", instance.get(InstanceModel.NAME))));
    }

    /**
     * Whether a workload in this status leaves visitors something to reach. servable() keeps a route through an ERROR
     * (the handler answers its own failure page), which is exactly what a visitor experiences as an error page.
     */
    private static boolean workloadServes(@NonNull InstanceStatus status) {
        return status.servable() && status != InstanceStatus.ERROR;
    }

    // -- instances -------------------------------------------------------------------

    /**
     * @param viewer who reads the words, null where only the serving half is asked
     */
    private static @NonNull Verdict instanceVerdict(@NonNull Row instance, @NonNull InstanceFacts facts,
                                                    boolean delegated, @Nullable AccessContext viewer) {
        String installError = instance.get(InstanceModel.INSTALL_ERROR);
        if (installError != null && !installError.isBlank()) {
            return Verdict.notServing(RecordHealth.broken(copy("install_failed"))
                .detail(copy("install_failed_detail")));
        }
        Microcopy refusal = OwnedInstances.placementRefusal(instance);
        if (refusal != null) {
            RecordHealth blocked = RecordHealth.attention(
                    copy("cannot_start").withArg("name", instance.get(InstanceModel.NAME)))
                .detail(OwnedInstances.placementReason(refusal, delegated, viewer));
            return Verdict.heldBy(OwnedInstances.placementHost(instance),
                delegated ? blocked : blocked.fixedBy(InstanceActions.CHECK_HOST));
        }
        InstanceStatus status = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        if (status == null) {
            return Verdict.notServing(RecordHealth.unknown(copy("status_unknown")));
        }
        RecordHealth health = switch (status) {
            case RUNNING -> runningVerdict(facts.sitesByInstance.get(instance.get(InstanceModel.ID)), facts.sites,
                delegated, viewer);
            case ERROR -> delegated
                ? RecordHealth.broken(copy("stopped_after_error"))
                : RecordHealth.broken(copy("stopped_after_error")).fixedBy(InstanceOperations.RESTART.id());
            case STOPPED, CREATED -> RecordHealth.attention(copy("not_running")).detail(copy("not_running_detail"))
                .fixedBy(InstanceOperations.START.id());
            case STARTING, CAPTURING, RESTORING, MIGRATING -> RecordHealth.unknown(status.label());
        };
        // A running workload whose site turns visitors away serves nothing, though it runs.
        return new Verdict(health, workloadServes(status) && health.tone() != HealthTone.BROKEN, null);
    }

    // -- stacks ----------------------------------------------------------------------

    private static @NonNull RecordHealth stackVerdict(@NonNull Row stack) {
        String status = stack.get(StackModel.STATUS);
        if (StackModel.STATUS_ACTIVE.equals(status)) {
            return RecordHealth.ok(copy("running"));
        }
        if (StackModel.STATUS_FAILED.equals(status)) {
            String reason = StackFailures.reasonOf(stack);
            return RecordHealth.broken(copy("deploy_failed"))
                .detail(reason == null ? null : Microcopy.literal(reason));
        }
        if (StackModel.STATUS_DEGRADED.equals(status)) {
            return RecordHealth.attention(copy("degraded")).detail(copy("stack_degraded_detail"));
        }
        if (StackModel.STATUS_STOPPED.equals(status) || StackModel.STATUS_INACTIVE.equals(status)) {
            return RecordHealth.attention(copy("not_running")).detail(copy("not_running_detail"));
        }
        // Deploying, and any status a later version stores: nobody can tell yet.
        return RecordHealth.unknown(status == null ? copy("status_unknown")
            : Microcopy.of(status).withFilter("scope", "stack_status"));
    }

    /**
     * A running workload is only as healthy as what its visitors get: the first serving site (switched on, with an
     * address) whose own verdict is not OK speaks for it, broken before attention. Its words and its fixes carry over,
     * the fixes still the site's own row actions ({@link RecordHealth#on}), offered on the workload's page wherever this
     * node registers the sites entry at all.
     */
    private static @NonNull RecordHealth runningVerdict(@Nullable List<Row> sites, @NonNull SiteFacts facts,
                                                        boolean delegated, @Nullable AccessContext viewer) {
        RecordHealth attention = null;
        if (sites != null) {
            for (Row site : sites) {
                if (site.get(SiteModel.DELETED_AT) != null || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))
                        || facts.domains.getOrDefault(site.get(SiteModel.ID), List.of()).isEmpty()) {
                    continue;
                }
                RecordHealth verdict = spokenFor(siteVerdict(site, facts, delegated, viewer).health(), site);
                if (verdict.tone() == HealthTone.BROKEN) {
                    return verdict;
                }
                if (attention == null && verdict.tone() == HealthTone.ATTENTION) {
                    attention = verdict;
                }
            }
        }
        return attention != null ? attention : RecordHealth.ok(liveHeadline(sites, facts.working));
    }

    /** A site's verdict as its workload says it: the same words, the fixes still the site's own actions. */
    private static @NonNull RecordHealth spokenFor(@NonNull RecordHealth verdict, @NonNull Row site) {
        return SiteParts.registered() ? verdict.on(HohenheimSlugs.SITES, site.get(SiteModel.ID))
            : verdict.fixedBy();
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
            if (forcedUncovered(domain, working)) {
                return domain.get(SiteDomainModel.HOSTNAME);
            }
        }
        return null;
    }

    /**
     * Whether this one name is forced to HTTPS while no working certificate covers it: the name the error-page verdict
     * names and the Stop forcing HTTPS fix switches back.
     */
    static boolean forcedUncovered(@NonNull Row domain, @NonNull Set<String> working) {
        return exact(domain) && Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL))
            && !CertificateCoverage.covers(working, domain.get(SiteDomainModel.HOSTNAME));
    }

    /**
     * What HTTPS gives the visitors of one address: the one per-name answer the Addresses list, a site's Addresses tab
     * and the app overview's Addresses card all read.
     *
     * AIDEV-NOTE: a name forced to HTTPS that no working certificate covers is ERROR whatever its certificate's own
     * status says, the same rule {@link #forcedWithoutCertificate} turns into the app's broken verdict.
     *
     * @param passthrough whether the name belongs to a TLS passthrough site, which terminates nothing here
     * @param working     the names a working certificate covers ({@code CertificateCoverage.activeNames()})
     * @return the coverage, null for a pattern (no single name to judge)
     */
    static @Nullable CertCoverage httpsOf(@NonNull Row domain, boolean passthrough, @NonNull Set<String> working) {
        if (passthrough) {
            return CertCoverage.NOT_USED;
        }
        if (!exact(domain)) {
            return null;
        }
        String hostname = domain.get(SiteDomainModel.HOSTNAME);
        if (CertificateCoverage.covers(working, hostname)) {
            return CertCoverage.ACTIVE;
        }
        Row cert = CertificateCoverage.coveringCertificate(hostname);
        CertCoverage coverage = CertCoverage.ofCertificateStatus(cert == null ? null : cert.get(CertificateModel.STATUS));
        return Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL)) && coverage != CertCoverage.ACTIVE
            ? CertCoverage.ERROR : coverage;
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

    /** Whether the Change its configuration fix applies: the running proxy turns every visitor of this site away. */
    static boolean refusedBySettings(@NonNull Row site) {
        var proxy = ServerMain.getProxyServer();
        Integer siteId = site.get(SiteModel.ID);
        if (proxy == null || siteId == null) {
            return false;
        }
        for (RoutingProblem problem : proxy.getDispatcher().routingProblems()) {
            if (problem.siteId() == siteId && problem.reason().refusesEveryVisitor()) {
                return true;
            }
        }
        return false;
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
        return rowsByKey(Models.get(SiteModel.class), SiteModel.INSTANCE_ID, ids);
    }

    /**
     * The rows of {@code model} whose {@code key} is one of these ids, read in one query and grouped by that key, each
     * group in stored order: the one batch read a page of verdicts or the Apps list makes per related table.
     */
    static @NonNull Map<Integer, List<Row>> rowsByKey(@NonNull Model model, @NonNull IntegerField key,
                                                      @NonNull List<Integer> ids) {
        Map<Integer, List<Row>> byKey = new HashMap<>();
        if (ids.isEmpty()) {
            return byKey;
        }
        for (Row row : model.find().where(key.in(ids)).orderBy(model.getPrimaryKeyField(), SortOrder.ASC).all()) {
            byKey.computeIfAbsent(row.get(key), id -> new ArrayList<>()).add(row);
        }
        return byKey;
    }

    private static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "app_health");
    }

    /**
     * One app's verdict, whether visitors reach it and what causes it. The serving half is what Open site and the
     * first-run checklist ask: a link to an app that cannot start, is stopped or answers an error page offers nothing.
     * The cause half is what the dashboard folds by: an app held back by its host is that host's problem first.
     *
     * AIDEV-NOTE: serving and cause are decided where the verdict is, at each branch, never re-derived from the tone or
     * the words: an ATTENTION verdict can mean "cannot start" (nothing served) or "a path is open" (served), and only
     * the branch that asked the placement gate knows the host refused.
     *
     * @param heldBy the host whose placement refusal this verdict is, null when no host holds the app back
     */
    record Verdict(@NonNull RecordHealth health, boolean serving, @Nullable Integer heldBy) {

        static @NonNull Verdict serving(@NonNull RecordHealth health) {
            return new Verdict(health, true, null);
        }

        static @NonNull Verdict notServing(@NonNull RecordHealth health) {
            return new Verdict(health, false, null);
        }

        static @NonNull Verdict heldBy(int host, @NonNull RecordHealth health) {
            return new Verdict(health, false, host);
        }
    }

    /** Everything a page of instance verdicts reads: the sites serving each, and those sites' own facts. */
    private record InstanceFacts(@NonNull Map<Integer, List<Row>> sitesByInstance, @NonNull SiteFacts sites) {

        static @NonNull InstanceFacts of(@NonNull List<Row> instances) {
            Map<Integer, List<Row>> sitesByInstance = sitesServing(instances);
            List<Row> serving = new ArrayList<>();
            for (List<Row> sites : sitesByInstance.values()) {
                serving.addAll(sites);
            }
            return new InstanceFacts(sitesByInstance, SiteFacts.of(serving));
        }
    }

    /** Everything a page of site verdicts reads, read once for the page. */
    private record SiteFacts(@NonNull Map<Integer, List<Row>> domains, @NonNull Map<Integer, List<Row>> paths,
                             @NonNull Map<Integer, Row> instances, @NonNull Map<Integer, SiteHealth> live,
                             @NonNull Map<Integer, List<RoutingProblem>> problems, @NonNull Set<String> working) {

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
            Map<Integer, List<Row>> domains = rowsByKey(Models.get(SiteDomainModel.class), SiteDomainModel.SITE_ID,
                siteIds);
            Map<Integer, List<Row>> paths = rowsByKey(Models.get(ProtectedPathModel.class), ProtectedPathModel.SITE_ID,
                siteIds);
            Map<Integer, Row> instances = new HashMap<>();
            Map<Integer, SiteHealth> live = new HashMap<>();
            Map<Integer, List<RoutingProblem>> problems = new HashMap<>();
            if (!siteIds.isEmpty()) {
                var proxy = ServerMain.getProxyServer();
                if (proxy != null) {
                    for (Integer siteId : siteIds) {
                        SiteHealth health = proxy.getDispatcher().healthOf(siteId);
                        if (health != null) {
                            live.put(siteId, health);
                        }
                    }
                    for (RoutingProblem problem : proxy.getDispatcher().routingProblems()) {
                        if (siteIds.contains(problem.siteId())) {
                            problems.computeIfAbsent(problem.siteId(), id -> new ArrayList<>()).add(problem);
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
            return new SiteFacts(domains, paths, instances, live, problems, CertificateCoverage.activeNames());
        }
    }
}
