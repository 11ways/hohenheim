package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.AttentionSubject;
import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimActivityAction;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.model.BuildOperationModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.ReleaseOperationModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.proxy.ProxyServer;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.database.InstanceDatabaseLinks;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.server.instance.OwnedInstances;
import be.elevenways.hohenheim.server.proxy.RoutingProblem;
import be.elevenways.hohenheim.server.proxy.StreamBindFailure;
import be.elevenways.hohenheim.server.sitetype.SiteHealth;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.instance.InstanceOperations;
import be.elevenways.zenit.server.operation.OperationRuns;
import be.elevenways.hohenheim.site.SiteOperations;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.HealthFixCellState;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.common.resource.ResourceHealth;
import be.elevenways.zenit.cms.server.page.RecordHealthReads;
import be.elevenways.zenit.cms.server.panel.PanelGate;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /** The certificate rows one request's site verdicts and HTTPS cells share ({@link #certificateRows}). */
    private static final IdentifierKey<Supplier<List<Row>>> CERTIFICATE_ROWS =
        IdentifierKey.of("hohenheim", "app_health_certificate_rows");

    private AppHealth() {
    }

    /**
     * The certificate rows a covering certificate is looked up in, read at most once per request and only when a name
     * asks ({@link CertificateCoverage#candidatesOnce()}): the Apps list's HTTPS column, its site verdicts and its
     * instance verdicts share one read. Without a request each call reads on its own.
     *
     * @param access the reader, whose request holds the shared supplier; null for a read outside one
     */
    static @NonNull Supplier<List<Row>> certificateRows(@Nullable AccessContext access) {
        var conduit = access == null ? null : access.conduit();
        return conduit == null ? CertificateCoverage.candidatesOnce()
            : conduit.memo(CERTIFICATE_ROWS, CertificateCoverage::candidatesOnce);
    }

    /** @param delegated whether the twin is /manage, whose row actions are a subset and whose words name no host */
    static @NonNull ResourceHealth<Row> sites(boolean delegated) {
        return ResourceHealth.batch((sites, access) -> {
            SiteFacts facts = SiteFacts.of(sites, access);
            return spokenTo(sites, site -> siteVerdict(site, facts, delegated, access).health(), delegated,
                HohenheimSlugs.SITES, access);
        });
    }

    /** @param delegated whether the twin is /manage, whose row actions are a subset and whose words name no host */
    static @NonNull ResourceHealth<Row> instances(boolean delegated) {
        return ResourceHealth.batch((instances, access) -> {
            InstanceFacts facts = InstanceFacts.of(instances, access);
            return spokenTo(instances, instance -> instanceVerdict(instance, facts, delegated, access).health(),
                delegated, HohenheimSlugs.INSTANCES, access);
        });
    }

    /**
     * A delegated verdict naming fixes of which the reader may use none says who can: /manage offers a fix only where
     * the reader's grants open it, so a tenant read a problem with no way forward and nobody named (an app
     * whose site the tenant holds no grant on). The fixes of a whole page are offered in one read
     * ({@link RecordHealthReads#fixCells}, the fix list cells' own answer).
     *
     * @param rows     the records of one page, judged together
     * @param verdicts each record's own verdict
     * @param ownSlug  the entry of the records the verdicts are read for
     * @return each record's verdict as this reader reads it
     */
    private static @NonNull Function<Row, RecordHealth> spokenTo(@NonNull List<Row> rows,
                                                                @NonNull Function<Row, RecordHealth> verdicts,
                                                                boolean delegated, @NonNull String ownSlug,
                                                                @NonNull AccessContext reader) {
        Map<Row, RecordHealth> judged = new IdentityHashMap<>();
        for (Row row : rows) {
            judged.put(row, verdicts.apply(row));
        }
        Function<Row, RecordHealth> verdictOf = row -> judged.computeIfAbsent(row, verdicts);
        if (!delegated) {
            return verdictOf;
        }
        // A verdict read off-request offers none.
        Panel manage = PanelRegistry.getBySlug(HohenheimSlugs.MANAGE);
        PanelEntry entry = manage != null ? manage.entryBySlug(ownSlug) : null;
        Function<Row, @Nullable HealthFixCellState> offered = entry != null && reader.conduit() != null
            ? RecordHealthReads.fixCells(PanelGate.request(manage, reader), entry, rows, verdictOf) : row -> null;
        Microcopy who = CmsSupport.operatorWords(HohenheimMicrocopy.APP_HEALTH, "operator_fixes");
        return row -> {
            RecordHealth health = verdictOf.apply(row);
            if (health.fixes().isEmpty() || offered.apply(row) != null) {
                return health;
            }
            return health.detail(health.detail() == null ? who
                : Microcopy.concat(List.of(health.detail(), Microcopy.literal(" "), who)));
        };
    }

    /**
     * The verdict's headline naming the app, for a surface listing many apps (the /manage landing's attention band):
     * the same key in the {@code attention_title} scope, given the name ("Visitors of shop get an error page").
     *
     * AIDEV-NOTE: every problem headline ({@link Headline}, {@link Stoppage}) declares that variant in en and nl
     * (ManagePanelJourneyTest's title step); a headline that is no single key (a literal) is shown as it is.
     */
    static @NonNull Microcopy titleOf(@NonNull RecordHealth health, @NonNull String name) {
        Microcopy headline = health.headline();
        // A literal, composed copy or a standalone list is no single key to re-scope: it is shown as it is.
        if (headline.isLiteral() || headline.isComposed() || headline.isList()) {
            return headline;
        }
        return Microcopy.of(headline.key(),
            headline.filters().with("scope", HohenheimMicrocopy.ATTENTION_TITLE.scope()),
            headline.args()).withArg("name", name);
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
            AttentionSubject cause = instanceVerdict(instance, facts, false, null).cause();
            Microcopy refusal = OwnedInstances.placementReasonOf(instance, false, null);
            if (cause != null && ServerModel.MODEL_ID.equals(cause.model()) && refusal != null) {
                // The gate's own words, never the verdict's headline: a held app whose site is broken on its own
                // (HTTPS forced without a certificate) leads with that site, while the host still holds it back.
                held.merge(cause.id(), new HeldBack(1, refusal),
                    (first, next) -> new HeldBack(first.apps() + 1, first.reason()));
            }
        }
        return held;
    }

    /**
     * How many switched-on websites each record keeps from their visitors: the sites whose verdict names that record
     * as its cause (a workload that does not run, an address forced to HTTPS without a certificate). A root item about
     * that record says it ("Visitors of its site get an error page") while the sites' own items fold under it.
     */
    static @NonNull Map<AttentionSubject, Integer> sitesHeldBack() {
        List<Row> sites = Models.get(SiteModel.class).find().where(SiteModel.ENABLED.eq(true)).all();
        SiteFacts facts = SiteFacts.of(sites);
        Map<AttentionSubject, Integer> held = new LinkedHashMap<>();
        for (Row site : sites) {
            AttentionSubject cause = siteVerdict(site, facts, false, null).cause();
            if (cause != null) {
                held.merge(cause, 1, Integer::sum);
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

    /** Whether this instance runs and serves what visitors reach of it: its verdict's serving half. */
    static boolean instanceServes(@NonNull Row instance) {
        return instanceReading(instance, false, null).serving();
    }

    /**
     * This instance's whole verdict for one reader: what its overview's Status reads and what its dashboard item folds
     * under (the cause half).
     *
     * @param viewer who reads the words, null where only the serving half or the cause is asked
     */
    static @NonNull Verdict instanceReading(@NonNull Row instance, boolean delegated, @Nullable AccessContext viewer) {
        return instanceVerdict(instance, InstanceFacts.of(List.of(instance)), delegated, viewer);
    }

    /**
     * Whether anything on this installation is online: an authored app that runs, or a website whose verdict serves
     * its visitors (a redirect or a proxy needs no workload). The first-run checklist's "Put your first app online".
     */
    static boolean anyOnline() {
        List<Row> authored = Models.get(InstanceModel.class).find().where(InstanceModel.liveAuthored()).all();
        for (Row runtime : ApplicationReleases.runtimeRows(authored).values()) {
            if (InstanceModel.STATUS_RUNNING.equals(runtime.get(InstanceModel.STATUS))) {
                return true;
            }
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
            return Verdict.notServing(RecordHealth.unknown(HohenheimMicrocopy.APP_HEALTH.of("in_trash")));
        }
        if (!Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
            return Verdict.notServing(RecordHealth.attention(Headline.SWITCHED_OFF.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("switched_off_detail")).fixedBy(SiteOperations.ENABLE.id()));
        }
        switch (SiteParts.reachOf(site)) {
            case PORTS -> {
                // A stream is reached on a public port of its own, never at an address: forwarding is its health.
                Integer port = RawValues.parsedInt(RawValues.map(site.get(SiteModel.SETTINGS)).get("listen_port"));
                return forwarding(site)
                    ? Verdict.serving(RecordHealth.ok(HohenheimMicrocopy.APP_HEALTH.of("stream_forwarding")
                        .withArg("port", port)))
                    : Verdict.notServing(RecordHealth.broken(Headline.NOT_FORWARDING.copy())
                        .detail(notForwardingDetail(site, port))
                        .fixedBy(SiteActions.CHANGE_CONFIGURATION));
            }
            case WEBSITE, NONE -> {
                // reached at its addresses, judged below
            }
        }
        List<Row> domains = facts.domains.getOrDefault(siteId, List.of());
        if (domains.isEmpty()) {
            return Verdict.notServing(RecordHealth.attention(Headline.NO_ADDRESS.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("no_address_detail")).fixedBy(SiteActions.ADD_ADDRESS));
        }
        List<RoutingProblem> problems = facts.problems.getOrDefault(siteId, List.of());
        for (RoutingProblem problem : problems) {
            // Its own refusal, never "does not answer": the proxy turns every visitor away and says why. Every such
            // refusal is the site's own settings (what it serves, its upstream, its sign-in provider), so the fix is
            // its configuration; /manage's form cannot change those, so the delegated verdict offers none.
            if (problem.reason().refusesEveryVisitor()) {
                RecordHealth refused = RecordHealth.broken(Headline.ERROR_PAGE.copy())
                    .detail(ProxyAttention.reasonOf(problem));
                return Verdict.notServing(delegated ? refused : refused.fixedBy(SiteActions.CHANGE_CONFIGURATION));
            }
        }
        boolean passthrough = SiteParts.tlsPassthrough(site);
        Row forced = forcedUncoveredDomain(domains, facts.working, passthrough);
        if (forced != null) {
            // The address is the cause: its own item ("Visitors of shop.example get an error page") is the root. While
            // this proxy answers no HTTPS at all, that one cause is every forced address's: "HTTPS is unavailable".
            return Verdict.causedBy(httpsTerminates() ? AttentionSubject.address(forced.get(SiteDomainModel.ID))
                    : AttentionSubject.httpsTermination(),
                RecordHealth.broken(Headline.ERROR_PAGE.copy())
                    .detail(HohenheimMicrocopy.APP_HEALTH.of("forced_without_certificate")
                        .withArg("host", forced.get(SiteDomainModel.HOSTNAME)))
                    .fixedBy(SiteActions.FIX_HTTPS, SiteOperations.STOP_FORCING_HTTPS.id()));
        }
        Row instance = facts.instances.get(site.get(SiteModel.INSTANCE_ID));
        if (instance != null) {
            Verdict workload = workloadVerdict(instance, facts.runtimes.get(instance.get(InstanceModel.ID)), delegated,
                viewer);
            if (workload != null) {
                return workload;
            }
        }
        SiteHealth live = facts.live.get(siteId);
        if (live == SiteHealth.DOWN) {
            // A workload that runs while its database does not serve: the database is the cause, its own item the
            // root ("Database shop-db is not running"), so this site's error page folds under it.
            DatabaseAttention.NotServing down = instance == null ? null
                : DatabaseAttention.firstNotServing(instance.get(InstanceModel.ID));
            if (down != null) {
                return Verdict.causedBy(AttentionSubject.database(down.database().get(DatabaseModel.ID)),
                    RecordHealth.broken(Headline.ERROR_PAGE.copy())
                        .detail(HohenheimMicrocopy.APP_HEALTH.of("database_not_serving")
                        .withArg("name", down.database().get(DatabaseModel.NAME))
                        .withArg("state", down.verdict().state().label().withFilter("case", "sentence"))));
            }
            return Verdict.notServing(RecordHealth.broken(Headline.ERROR_PAGE.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("upstream_down")));
        }
        Row open = firstOpenPath(facts.paths.getOrDefault(siteId, List.of()));
        if (open != null) {
            return Verdict.serving(RecordHealth.attention(Headline.PATH_OPEN.copy()
                    .withArg("path", open.get(ProtectedPathModel.PATH)))
                .detail(HohenheimMicrocopy.APP_HEALTH.of("path_open_detail"))
                .fixedBy(SiteActions.FIX_PROTECTION));
        }
        if (!problems.isEmpty()) {
            return Verdict.serving(RecordHealth.attention(Headline.ROUTED_IN_PART.copy())
                .detail(ProxyAttention.reasonOf(problems.get(0))));
        }
        if (live == SiteHealth.DEGRADED) {
            return Verdict.serving(RecordHealth.attention(Headline.DEGRADED.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("degraded_detail")));
        }
        // A name its stored certificate cannot be served for: plain HTTP works, HTTPS ends in an error. The verdict
        // says it in the HTTPS cell's own reason, so an app row never reads OK beside a "Not working" HTTPS badge.
        for (Row domain : domains) {
            if (httpsOf(domain, passthrough, facts.working, facts.certificates) == CertCoverage.ERROR) {
                return Verdict.serving(RecordHealth.attention(Headline.HTTPS_NOT_WORKING.copy())
                    .detail(DomainParts.httpsDetail(domain, CertCoverage.ERROR,
                        CertificateCoverage.coveringCertificate(domain.get(SiteDomainModel.HOSTNAME),
                            facts.certificates.get()))));
            }
        }
        return Verdict.serving(RecordHealth.ok(liveWords(domains, facts.working, passthrough)));
    }

    /**
     * Whether this process's proxy listens on every transport of a stream site's public port; false where the port
     * could not be bound, or no proxy runs here.
     */
    static boolean forwarding(@NonNull Row site) {
        ProxyServer proxy = ServerMain.getProxyServer();
        StreamProtocol protocol = StreamProtocol.ofToken(RawValues.map(site.get(SiteModel.SETTINGS))
            .getOrDefault(StreamProtocol.SETTING, StreamProtocol.TCP.token()));
        Integer siteId = site.get(SiteModel.ID);
        if (proxy == null || protocol == null || siteId == null) {
            return false;
        }
        return (!protocol.forwardsTcp() || proxy.getStreamTcpAddress(siteId) != null)
            && (!protocol.forwardsUdp() || proxy.getStreamUdpAddress(siteId) != null);
    }

    /** @return why a stream site does not forward: the proxy's own bind refusal when it has one */
    private static @NonNull Microcopy notForwardingDetail(@NonNull Row site, @Nullable Integer port) {
        ProxyServer proxy = ServerMain.getProxyServer();
        Integer siteId = site.get(SiteModel.ID);
        StreamBindFailure failure = proxy == null || siteId == null ? null : proxy.getStreamFailure(siteId);
        return failure != null ? failure.words()
            : HohenheimMicrocopy.APP_HEALTH.of("not_forwarding_detail").withArg("port", port);
    }

    /**
     * Why a site's workload keeps visitors out, or null when it serves: the site page says it in the site's words and
     * leaves the fix to the instance page, where the host check lives.
     *
     * @param runtime the row whose status speaks for the workload's runtime (ApplicationReleases.runtimeRows)
     */
    private static @Nullable Verdict workloadVerdict(@NonNull Row instance, @NonNull Row runtime, boolean delegated,
                                                     @Nullable AccessContext viewer) {
        Microcopy refusal = OwnedInstances.placementReasonOf(instance, delegated, viewer);
        if (refusal != null) {
            return Verdict.heldBy(OwnedInstances.placementHost(instance),
                RecordHealth.broken(Headline.ERROR_PAGE.copy()).detail(refusal));
        }
        InstanceStatus status = InstanceStatus.forToken(runtime.get(InstanceModel.STATUS));
        if (status != null && workloadServes(status)) {
            return null;
        }
        // The workload is the cause: a crashed or failed-to-deploy workload's own item is the root, never the site's.
        return Verdict.causedBy(AttentionSubject.instance(instance.get(InstanceModel.ID)),
            RecordHealth.broken(Headline.ERROR_PAGE.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("workload_not_running")
                    .withArg("name", instance.get(InstanceModel.NAME))));
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
            return Verdict.notServing(RecordHealth.broken(Headline.INSTALL_FAILED.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("install_failed_detail")));
        }
        Row runtime = facts.runtimeOf(instance);
        if (deployingWhileStopped(instance, runtime)) {
            return Verdict.notServing(RecordHealth.unknown(HohenheimMicrocopy.APP_HEALTH.of("deploying"))
                .detail(HohenheimMicrocopy.APP_HEALTH.of("deploying_detail")));
        }
        // The host is the root while it refuses the start: an older recorded failure (the ERROR status a failed start
        // stamped) is history, Recent's to tell, never a second current verdict beside this one.
        Microcopy refusal = OwnedInstances.placementReasonOf(instance, delegated, viewer);
        if (refusal != null) {
            RecordHealth blocked = RecordHealth.attention(Headline.CANNOT_START.copy()).detail(refusal);
            RecordHealth broken = siteBrokenOnItsOwn(facts.sitesByInstance.get(instance.get(InstanceModel.ID)),
                facts.sites, delegated, viewer);
            return Verdict.heldBy(OwnedInstances.placementHost(instance),
                broken != null ? broken : delegated ? blocked : blocked.fixedBy(InstanceActions.CHECK_HOST));
        }
        InstanceStatus status = InstanceStatus.forToken(runtime.get(InstanceModel.STATUS));
        if (status == null) {
            return Verdict.notServing(RecordHealth.unknown(HohenheimMicrocopy.APP_HEALTH.of("status_unknown")));
        }
        // An errored workload's own root (the host that refused its start, the database whose old engine it is) is the
        // cause its dashboard item folds under. An application's is its serving release's (ApplicationReleases).
        AttentionSubject root = status == InstanceStatus.ERROR ? WorkloadErrors.rootOf(runtime) : null;
        RecordHealth health = switch (status) {
            case RUNNING -> changesNotLiveOr(instance, facts, runningVerdict(facts.sitesByInstance.get(
                instance.get(InstanceModel.ID)), facts.sites, () -> facts.deployedAt(instance), delegated, viewer));
            // What stopped it, as its recorded cause says (the dashboard item's detail too), the failure's own message
            // as the band's technical line; a tenant reads the sentence without the daemon's own message.
            case ERROR -> {
                WorkloadErrors.Reading reading = WorkloadErrors.readingOf(runtime, !delegated);
                RecordHealth stopped = RecordHealth.broken(WorkloadErrors.stoppageOf(runtime).headline())
                    .detail(reading.worded()).technical(reading.technical());
                // Restart is its fix, unless its root is another record's (the host that refuses its start, the
                // database whose old engine it is): a restart would be refused or bring back what should go. On
                // /manage it is offered to a holder of power; everyone else reads who can (spokenTo).
                yield root != null ? stopped : stopped.fixedBy(InstanceOperations.RESTART.id());
            }
            // A stored STOPPED is always a settled stop someone asked for (Stop, a typed stop command, a backup's or a
            // move's cold stop): an unobserved death settles to ERROR or a restart (InstanceStatusReconciler). So it
            // reads as stopped, the neutral tone CREATED wears too; ATTENTION's word "Needs attention" told the
            // operator who had just stopped it that something was wrong.
            case STOPPED -> {
                RecordHealth broken = siteBrokenOnItsOwn(facts.sitesByInstance.get(instance.get(InstanceModel.ID)),
                    facts.sites, delegated, viewer);
                yield broken != null ? broken : RecordHealth.unknown(status.label())
                    .detail(HohenheimMicrocopy.APP_HEALTH.of("not_running_detail"))
                    .fixedBy(InstanceOperations.DEPLOY.id());
            }
            // Nobody deployed it yet: nothing went wrong, so it asks for nothing but its first deploy, and while the run
            // that created it is still starting it (put online), it is deploying.
            case CREATED -> firstStartUnderWay(instance, viewer)
                ? RecordHealth.unknown(HohenheimMicrocopy.APP_HEALTH.of("deploying"))
                    .detail(HohenheimMicrocopy.APP_HEALTH.of("first_start_detail"))
                : RecordHealth.unknown(HohenheimMicrocopy.APP_HEALTH.of("not_deployed"))
                    .detail(HohenheimMicrocopy.APP_HEALTH.of("not_running_detail"))
                    .fixedBy(InstanceOperations.DEPLOY.id());
            // A destroy whose end failed too often waits for an operator's Delete (UnfinishedDestroys): it says why.
            case DESTROYING -> InstanceModel.stoppageCauseOf(runtime) == HohenheimActivityAction.DESTROY_UNFINISHED
                ? destroyUnfinished(runtime, delegated, status) : RecordHealth.unknown(status.label());
            case STARTING, CAPTURING, RESTORING, MIGRATING -> RecordHealth.unknown(status.label());
        };
        // A running workload whose site turns visitors away serves nothing, though it runs.
        return new Verdict(health, workloadServes(status) && health.tone() != HealthTone.BROKEN, root);
    }

    /** @return the verdict of a record whose destroy's end was given up on: why, and the failure's own words */
    private static @NonNull RecordHealth destroyUnfinished(@NonNull Row runtime, boolean delegated,
                                                           @NonNull InstanceStatus status) {
        WorkloadErrors.Reading reading = WorkloadErrors.readingOf(runtime, !delegated);
        return RecordHealth.attention(status.label()).detail(reading.worded()).technical(reading.technical());
    }

    /**
     * Whether an application that serves nothing yet has a release on its way: its build or release runs, so it is
     * deploying, whatever its host would say about a start and however stopped its record still reads.
     *
     * @param runtime the row whose status speaks for its runtime: its serving release, else its own record
     */
    private static boolean deployingWhileStopped(@NonNull Row instance, @NonNull Row runtime) {
        InstanceStatus status = InstanceStatus.forToken(runtime.get(InstanceModel.STATUS));
        InstanceKindHandler kind = InstanceKinds.handlerOf(instance);
        return (status == InstanceStatus.STOPPED || status == InstanceStatus.CREATED)
            && kind != null && kind.releaseManaged() && ApplicationReleases.deploying(instance.get(InstanceModel.ID));
    }

    /** Whether a background run on this never-deployed record (the put-online run that made it) is still open. */
    private static boolean firstStartUnderWay(@NonNull Row instance, @Nullable AccessContext viewer) {
        return viewer != null && OperationRuns.openFor(InstanceOperations.INSTANCE,
            String.valueOf((Object) instance.get(InstanceModel.ID)), viewer) != null;
    }

    /**
     * A running workload whose saved configuration is not what it runs says so, whatever the running verdict already
     * flags speaking first. An application whose serving release was not produced from its saved configuration asks
     * for Deploy: the change is stored, not live. Any other workload attached to a database whose password changed
     * after it was deployed asks for Restart: it still connects with the old password, which the database refuses.
     *
     * AIDEV-NOTE: ONE notice per workload. An application's database password is part of its release fingerprint
     * (ApplicationReleases.configPending), so its rotation already reads as changes not live; the attachment's
     * credential generation (InstanceDatabaseLinks.staleCredentials) is the fact for every workload no release
     * fingerprint covers, and is never asked of an application.
     */
    private static @NonNull RecordHealth changesNotLiveOr(@NonNull Row instance, @NonNull InstanceFacts facts,
                                                          @NonNull RecordHealth running) {
        InstanceKindHandler kind = InstanceKinds.handlerOf(instance);
        if (running.tone() != HealthTone.OK || kind == null) {
            return running;
        }
        if (!kind.releaseManaged()) {
            Row database = facts.staleCredentialsOf(instance);
            return database == null ? running : RecordHealth.attention(Headline.CREDENTIALS_NOT_LIVE.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("credentials_not_live_detail")
                    .withArg("database", database.get(DatabaseModel.NAME)))
                .fixedBy(InstanceOperations.RESTART.id());
        }
        if (ApplicationReleases.deploying(instance.get(InstanceModel.ID))
                || !ApplicationReleases.configPending(instance)) {
            return running;
        }
        return RecordHealth.attention(Headline.CHANGES_NOT_LIVE.copy())
            .detail(HohenheimMicrocopy.APP_HEALTH.of("changes_not_live_detail"))
            .fixedBy(InstanceOperations.DEPLOY.id());
    }

    // -- stacks ----------------------------------------------------------------------

    /** A stack's verdict, for a surface that names the stack itself (its attention item). */
    static @NonNull RecordHealth stackHealth(@NonNull Row stack) {
        return stackVerdict(stack);
    }

    /**
     * What stopped a failed stack: its newest deploy failing, or (the status monitor's observation, STACK_HEALTH's
     * alert) none of its services running any more; null for a stack that is not failed.
     *
     * AIDEV-NOTE: a failed stack used to read "Its last deploy failed" whatever failed it, so a stack whose services
     * died after a good deploy blamed a deploy that had worked.
     */
    static @Nullable Stoppage stackStoppage(@NonNull Row stack) {
        if (!StackModel.STATUS_FAILED.equals(stack.get(StackModel.STATUS))) {
            return null;
        }
        return StackFailures.failedDeploymentOf(stack) != null ? Stoppage.DEPLOY_FAILED : Stoppage.AFTER_ERROR;
    }

    private static @NonNull RecordHealth stackVerdict(@NonNull Row stack) {
        String status = stack.get(StackModel.STATUS);
        if (StackModel.STATUS_ACTIVE.equals(status)) {
            return RecordHealth.ok(HohenheimMicrocopy.APP_HEALTH.of("running"));
        }
        Stoppage stoppage = stackStoppage(stack);
        if (stoppage != null) {
            String reason = StackFailures.reasonOf(stack);
            return switch (stoppage) {
                case DEPLOY_FAILED -> RecordHealth.broken(stoppage.headline())
                    .detail(Microcopy.literalOrNull(reason));
                // A stack's stoppage is never a start that failed (stackStoppage); its services stopped either way.
                case AFTER_ERROR, START_FAILED -> RecordHealth.broken(stoppage.headline())
                    .detail(HohenheimMicrocopy.APP_HEALTH.of("stack_failed_detail"));
            };
        }
        if (StackModel.STATUS_DEGRADED.equals(status)) {
            return RecordHealth.attention(Headline.DEGRADED.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("stack_degraded_detail"));
        }
        if (StackModel.STATUS_STOPPED.equals(status) || StackModel.STATUS_INACTIVE.equals(status)) {
            return RecordHealth.attention(Headline.NOT_RUNNING.copy())
                .detail(HohenheimMicrocopy.APP_HEALTH.of("not_running_detail"));
        }
        // Deploying, and any status a later version stores: nobody can tell yet.
        return RecordHealth.unknown(status == null ? HohenheimMicrocopy.APP_HEALTH.of("status_unknown")
            : HohenheimMicrocopy.STACK_STATUS.of(status));
    }

    /**
     * A running workload is only as healthy as what its visitors get: the first serving site (switched on, with an
     * address) whose own verdict is not OK speaks for it, broken before attention. Its words and its fixes carry over,
     * the fixes still the site's own row actions ({@link RecordHealth#on}), offered on the workload's page wherever
     * this node registers the sites entry at all. A healthy one says, under where it is live, when it was last deployed.
     *
     * @param deployedAt when its last successful deploy finished, null when it was never deployed through Hohenheim;
     *                   asked only of a healthy one, so a page with none reads no deploy
     */
    private static @NonNull RecordHealth runningVerdict(@Nullable List<Row> sites, @NonNull SiteFacts facts,
                                                        @NonNull Supplier<@Nullable Instant> deployedAt,
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
        if (attention != null) {
            return attention;
        }
        RecordHealth live = RecordHealth.ok(liveHeadline(sites, facts));
        Instant deployed = deployedAt.get();
        return deployed == null ? live : live.since(HohenheimMicrocopy.APP_HEALTH.of("last_deploy"), deployed);
    }

    /**
     * The first serving site of a workload that does not run whose visitors get an error page for a cause of the site's
     * own (an address forced to HTTPS without a certificate, the proxy refusing its settings), spoken for the workload;
     * null when none is. Such a site is broken whether or not the workload runs, so a held back or stopped app reads
     * broken, the way its HTTPS badge does, instead of only "cannot start" or "not running".
     *
     * AIDEV-NOTE: a site whose verdict names the workload or its host as the cause is excluded: that IS the workload's
     * own state, which the workload's verdict already says in its own words.
     */
    private static @Nullable RecordHealth siteBrokenOnItsOwn(@Nullable List<Row> sites, @NonNull SiteFacts facts,
                                                             boolean delegated, @Nullable AccessContext viewer) {
        if (sites == null) {
            return null;
        }
        for (Row site : sites) {
            if (site.get(SiteModel.DELETED_AT) != null || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))
                    || facts.domains.getOrDefault(site.get(SiteModel.ID), List.of()).isEmpty()) {
                continue;
            }
            Verdict verdict = siteVerdict(site, facts, delegated, viewer);
            AttentionSubject cause = verdict.cause();
            boolean workloads = cause != null
                && (InstanceModel.MODEL_ID.equals(cause.model()) || ServerModel.MODEL_ID.equals(cause.model()));
            if (verdict.health().tone() == HealthTone.BROKEN && !workloads) {
                return spokenFor(verdict.health(), site);
            }
        }
        return null;
    }

    /** A site's verdict as its workload says it: the same words, the fixes still the site's own actions. */
    private static @NonNull RecordHealth spokenFor(@NonNull RecordHealth verdict, @NonNull Row site) {
        return SiteParts.registered() ? verdict.on(HohenheimSlugs.SITES, site.get(SiteModel.ID))
            : verdict.fixedBy();
    }

    private static @NonNull Microcopy liveHeadline(@Nullable List<Row> sites, @NonNull SiteFacts facts) {
        if (sites != null) {
            for (Row site : sites) {
                List<Row> domains = facts.domains.getOrDefault(site.get(SiteModel.ID), List.of());
                if (!domains.isEmpty() && Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
                    return liveWords(domains, facts.working, SiteParts.tlsPassthrough(site));
                }
            }
        }
        return HohenheimMicrocopy.APP_HEALTH.of("running");
    }

    // -- the rules the verdict and its fix actions share ----------------------------------

    /** @return the first exact name forced to HTTPS that no working certificate covers, null when none is */
    static @Nullable String forcedWithoutCertificate(@NonNull List<Row> domains, @NonNull Set<String> working,
                                                     boolean passthrough) {
        Row domain = forcedUncoveredDomain(domains, working, passthrough);
        return domain == null ? null : domain.get(SiteDomainModel.HOSTNAME);
    }

    /** @return the address row of {@link #forcedWithoutCertificate}, null when none is */
    private static @Nullable Row forcedUncoveredDomain(@NonNull List<Row> domains, @NonNull Set<String> working,
                                                       boolean passthrough) {
        if (passthrough) {
            return null;
        }
        for (Row domain : domains) {
            if (forcedUncovered(domain, working)) {
                return domain;
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
     * status says, the same rule {@link #forcedWithoutCertificate} turns into the app's broken verdict. A covering
     * certificate stored ACTIVE that the working names leave out is ERROR too: the proxy cannot serve it.
     *
     * @param passthrough whether the name belongs to a TLS passthrough site, which terminates nothing here
     * @param working     the names a working certificate covers ({@link #workingNames()})
     * @return the coverage; {@link CertCoverage#PATTERN} for a pattern (no single name to judge)
     */
    static @NonNull CertCoverage httpsOf(@NonNull Row domain, boolean passthrough, @NonNull Set<String> working) {
        return httpsOf(domain, passthrough, working, CertificateCoverage.candidatesOnce());
    }

    /**
     * {@link #httpsOf(Row, boolean, Set)} over certificate rows a page reads once
     * ({@link CertificateCoverage#candidatesOnce()}).
     */
    static @NonNull CertCoverage httpsOf(@NonNull Row domain, boolean passthrough,
                                         @NonNull Set<String> working,
                                         @NonNull Supplier<List<Row>> certificates) {
        if (passthrough) {
            return CertCoverage.NOT_USED;
        }
        if (!exact(domain)) {
            return CertCoverage.PATTERN;
        }
        String hostname = domain.get(SiteDomainModel.HOSTNAME);
        if (CertificateCoverage.covers(working, hostname)) {
            return CertCoverage.ACTIVE;
        }
        Row cert = CertificateCoverage.coveringCertificate(hostname, certificates.get());
        CertCoverage coverage = CertCoverage.ofCertificateStatus(cert == null ? null
                : cert.get(CertificateModel.STATUS));
        // A certificate stored as active that the working names leave out is one the proxy cannot serve.
        return coverage == CertCoverage.ACTIVE || Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL))
            ? CertCoverage.ERROR : coverage;
    }

    /**
     * The names HTTPS works for: what the running proxy's certificate store answers a handshake for while it
     * terminates HTTPS, nothing while it cannot, and the names a served certificate row declares where no proxy runs
     * in this process (a node without the proxy role, a test), because the stored rows are then all there is to read.
     *
     * AIDEV-NOTE: a served row is not a working certificate. A shop once had an active row without loadable material,
     * and every HTTPS cell said "Works" while the proxy could serve nothing; reading the store is what makes the cells,
     * the verdicts and the attention items agree with the handshake a visitor gets. The names are
     * {@link CertificateCoverage#workingNames()}, the rule routing and the force-HTTPS latch read too; only the
     * listener half is the display's own, because a visitor's handshake also needs a listener that terminates.
     */
    static @NonNull Set<String> workingNames() {
        return httpsTerminates() ? CertificateCoverage.workingNames() : Set.of();
    }

    /**
     * Whether this process's proxy answers HTTPS at all; true where no proxy runs in this process, which then reads
     * the stored rows ({@link #workingNames()}). The one reading the forced address items and the site verdict's cause
     * share, so "HTTPS is unavailable" and an address's own item never both claim one site.
     */
    static boolean httpsTerminates() {
        var proxy = ServerMain.getProxyServer();
        return proxy == null || proxy.isHttpsTerminationAvailable();
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

    /**
     * The sites the Get a certificate fix applies to (its row actions' visibility): a name forced to HTTPS that no
     * working certificate covers. A page of sites reads its hostnames and the working certificates once.
     *
     * @return the ids, among these sites, of the ones needing a certificate
     */
    static @NonNull Set<Integer> needingCertificate(@NonNull List<? extends Row> sites) {
        Set<Integer> needing = new HashSet<>();
        if (sites.isEmpty()) {
            return needing;
        }
        Map<Integer, List<Row>> domains = KeyedRows.byAsked(Models.get(SiteDomainModel.class).find(),
            SiteDomainModel.SITE_ID,
            sites.stream().map(site -> site.get(SiteModel.ID)).filter(Objects::nonNull).toList());
        Set<String> working = workingNames();
        for (Row site : sites) {
            Integer id = site.get(SiteModel.ID);
            if (id != null && forcedWithoutCertificate(domains.getOrDefault(id, List.of()), working,
                    SiteParts.tlsPassthrough(site)) != null) {
                needing.add(id);
            }
        }
        return needing;
    }

    /**
     * The sites the Add an address fix applies to: reached by address and answering on none, the hostnames of a page
     * of sites read once.
     *
     * @return the ids, among these sites, of the ones needing an address
     */
    static @NonNull Set<Integer> needingAddress(@NonNull List<? extends Row> sites) {
        List<Integer> byAddress = sites.stream().filter(site -> SiteParts.reachOf(site).asksAddress())
            .map(site -> site.get(SiteModel.ID)).filter(Objects::nonNull).toList();
        Set<Integer> answering = KeyedRows.byAsked(Models.get(SiteDomainModel.class).find(), SiteDomainModel.SITE_ID,
            byAddress).keySet();
        Set<Integer> needing = new HashSet<>(byAddress);
        needing.removeAll(answering);
        return needing;
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

    /**
     * The sites the Fix protection fix applies to: one of its protected paths admits everyone. The paths of a page of
     * sites are read once.
     *
     * @return the ids, among these sites, of the ones with an open path
     */
    static @NonNull Set<Integer> withOpenPath(@NonNull List<? extends Row> sites) {
        Map<Integer, List<Row>> paths = KeyedRows.byAsked(Models.get(ProtectedPathModel.class).find()
                .orderBy(ProtectedPathModel.ID, SortOrder.ASC), ProtectedPathModel.SITE_ID,
            sites.stream().map(site -> site.get(SiteModel.ID)).filter(Objects::nonNull).toList());
        Set<Integer> open = new HashSet<>();
        paths.forEach((siteId, rows) -> {
            if (firstOpenPath(rows) != null) {
                open.add(siteId);
            }
        });
        return open;
    }

    // -- shared ----------------------------------------------------------------------

    /**
     * Where a serving site is live, in words: "Live at" the first exact name with the scheme that works for it, or,
     * for a site answering only patterns (a catch-all), what it catches.
     *
     * AIDEV-NOTE: a pattern is never presented as an address to visit: "Live at **.starfleet.life" read like a link
     * nobody can open.
     */
    static @NonNull Microcopy liveWords(@NonNull List<Row> domains, @NonNull Set<String> working, boolean passthrough) {
        String url = exactUrl(domains, working, passthrough);
        return url != null ? HohenheimMicrocopy.APP_HEALTH.of("live_at").withArg("address", url)
            : HohenheimMicrocopy.APP_HEALTH.of("catches")
                .withArg("pattern", String.valueOf((Object) domains.get(0).get(SiteDomainModel.HOSTNAME)));
    }

    /**
     * Where each of these sites' Open site link goes, for the ones visitors reach: its first exact name over the
     * scheme that works. A site visitors reach nothing of (trashed, switched off, no exact name, a name forced to
     * HTTPS without a certificate, or a verdict that does not serve) is absent. The page's facts are read once.
     *
     * @return site id to its address, for the sites Open site is offered on
     */
    static @NonNull Map<Integer, String> openSites(@NonNull List<Row> sites) {
        SiteFacts facts = SiteFacts.of(sites);
        Map<Integer, String> open = new HashMap<>();
        for (Row site : sites) {
            String url = openUrl(site, facts);
            if (url != null && siteVerdict(site, facts, false, null).serving()) {
                open.put(site.get(SiteModel.ID), url);
            }
        }
        return open;
    }

    /**
     * Where each of these instances' Open site link goes, for the ones that run and serve: the first site serving it
     * that visitors reach. The sites serving the page and their facts are read once.
     *
     * @return instance id to its address, for the instances Open site is offered on
     */
    static @NonNull Map<Integer, String> openInstances(@NonNull List<Row> instances) {
        InstanceFacts facts = InstanceFacts.of(instances);
        Map<Integer, String> open = new HashMap<>();
        for (Row instance : instances) {
            Integer id = instance.get(InstanceModel.ID);
            String url = null;
            for (Row site : facts.sitesByInstance.getOrDefault(id, List.of())) {
                url = openUrl(site, facts.sites);
                if (url != null) {
                    break;
                }
            }
            if (url != null && instanceVerdict(instance, facts, false, null).serving()) {
                open.put(id, url);
            }
        }
        return open;
    }

    /** @return where this site's Open site link goes by its address alone, null while visitors reach nothing there */
    private static @Nullable String openUrl(@NonNull Row site, @NonNull SiteFacts facts) {
        if (site.get(SiteModel.DELETED_AT) != null || !Boolean.TRUE.equals(site.get(SiteModel.ENABLED))) {
            return null;
        }
        List<Row> domains = facts.domains.getOrDefault(site.get(SiteModel.ID), List.of());
        boolean passthrough = SiteParts.tlsPassthrough(site);
        if (forcedWithoutCertificate(domains, facts.working, passthrough) != null) {
            return null;
        }
        return exactUrl(domains, facts.working, passthrough);
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
        return KeyedRows.byAsked(Models.get(SiteModel.class).find().orderBy(SiteModel.ID, SortOrder.ASC),
            SiteModel.INSTANCE_ID, ids);
    }

    /**
     * The headlines of a verdict that has a problem, each one key in {@code app_health} with its naming variant in
     * {@code attention_title} ({@link #titleOf}); a verdict that is fine or unknown reads its own words.
     */
    enum Headline {

        ERROR_PAGE("error_page"),
        SWITCHED_OFF("switched_off"),
        NO_ADDRESS("no_address"),
        PATH_OPEN("path_open"),
        ROUTED_IN_PART("routed_in_part"),
        DEGRADED("degraded"),
        HTTPS_NOT_WORKING("https_not_working"),
        NOT_FORWARDING("not_forwarding"),
        INSTALL_FAILED("install_failed"),
        CANNOT_START("cannot_start"),
        NOT_RUNNING("not_running"),
        CHANGES_NOT_LIVE("changes_not_live"),
        CREDENTIALS_NOT_LIVE("credentials_not_live");

        private final String key;

        Headline(@NonNull String key) {
            this.key = key;
        }

        /** @return the verdict's headline on the app's own page */
        @NonNull Microcopy copy() {
            return HohenheimMicrocopy.APP_HEALTH.of(this.key);
        }
    }

    /**
     * What stopped a workload, in words: its record's verdict headline ("Stopped after an error") and the dashboard
     * item naming it ("Shop stopped after an error") are one declaration, so the two never tell different stories.
     *
     * AIDEV-NOTE: AFTER_ERROR is the stored ERROR status, which a crash, a failed start, a failed migration and a
     * failed maintenance hold all stamp; "after an error" is what every one of them is, "after a crash" is not. Which
     * one it was is the recorded cause ({@link WorkloadErrors}), the verdict's and the item's detail. One exception has
     * its own words: a start that failed never ran, so it "could not be started" (START_FAILED,
     * {@link WorkloadErrors#stoppageOf}), never "stopped".
     */
    enum Stoppage {

        /** The runtime stamped the workload ERROR and nothing restarted it. */
        AFTER_ERROR("stopped_after_error"),

        /** Its newest deploy failed. */
        DEPLOY_FAILED("deploy_failed"),

        /** The runtime stamped it ERROR because its start failed or was refused: it never ran ("Could not start"). */
        START_FAILED("start_failed");

        private final String key;

        Stoppage(@NonNull String key) {
            this.key = key;
        }

        /** @return the verdict's headline on the workload's own page */
        @NonNull Microcopy headline() {
            return HohenheimMicrocopy.APP_HEALTH.of(this.key);
        }

        /** @return the same words naming the workload, for a surface that lists many (the attention band) */
        @NonNull Microcopy title(@Nullable String name) {
            return HohenheimMicrocopy.ATTENTION_TITLE.of(this.key).withArg("name", name);
        }
    }

    /**
     * One app's verdict, whether visitors reach it and what causes it. The serving half is what Open site and the
     * first-run checklist ask: a link to an app that cannot start, is stopped or answers an error page offers nothing.
     * The cause half is what the dashboard folds by: an app held back by its host is that host's problem first, a
     * site whose workload does not run is that workload's, and a site whose address is forced to HTTPS without a
     * certificate is that address's.
     *
     * AIDEV-NOTE: serving and cause are decided where the verdict is, at each branch, never re-derived from the tone or
     * the words: an ATTENTION verdict can mean "cannot start" (nothing served) or "a path is open" (served), and only
     * the branch that asked the placement gate knows the host refused.
     *
     * @param cause the record whose own problem this verdict is (a host, a workload, an address), null for none
     */
    record Verdict(@NonNull RecordHealth health, boolean serving, @Nullable AttentionSubject cause) {

        static @NonNull Verdict serving(@NonNull RecordHealth health) {
            return new Verdict(health, true, null);
        }

        static @NonNull Verdict notServing(@NonNull RecordHealth health) {
            return new Verdict(health, false, null);
        }

        static @NonNull Verdict heldBy(int host, @NonNull RecordHealth health) {
            return new Verdict(health, false, AttentionSubject.host(host));
        }

        static @NonNull Verdict causedBy(@NonNull AttentionSubject cause, @NonNull RecordHealth health) {
            return new Verdict(health, false, cause);
        }
    }

    /**
     * Everything a page of instance verdicts reads: the sites serving each, those sites' own facts, the row whose
     * status speaks for each one's runtime, and when each was last deployed and which attached database's password
     * each runs behind (both read once, and only when a healthy verdict asks).
     */
    private record InstanceFacts(@NonNull Map<Integer, List<Row>> sitesByInstance, @NonNull SiteFacts sites,
                                 @NonNull Map<Integer, Row> runtimes,
                                 @NonNull Supplier<Map<Integer, Instant>> deploys,
                                 @NonNull Supplier<Map<Integer, Row>> staleCredentials) {

        static @NonNull InstanceFacts of(@NonNull List<Row> instances) {
            return of(instances, null);
        }

        /** @param access the reader whose request shares the certificate rows ({@link #certificateRows}) */
        static @NonNull InstanceFacts of(@NonNull List<Row> instances, @Nullable AccessContext access) {
            Map<Integer, List<Row>> sitesByInstance = sitesServing(instances);
            List<Row> serving = new ArrayList<>();
            for (List<Row> sites : sitesByInstance.values()) {
                serving.addAll(sites);
            }
            AtomicReference<Map<Integer, Instant>> deploys = new AtomicReference<>();
            AtomicReference<Map<Integer, Row>> stale = new AtomicReference<>();
            return new InstanceFacts(sitesByInstance, SiteFacts.of(serving, access),
                ApplicationReleases.runtimeRows(instances),
                () -> deploys.updateAndGet(read -> read != null ? read : lastDeploys(instances)),
                () -> stale.updateAndGet(read -> read != null ? read
                    : InstanceDatabaseLinks.staleCredentials(idsOf(instances))));
        }

        /** @return the row whose status speaks for this instance's runtime, the instance itself when none other */
        @NonNull Row runtimeOf(@NonNull Row instance) {
            return this.runtimes.getOrDefault((Integer) instance.get(InstanceModel.ID), instance);
        }

        /** @return when this instance's last successful deploy finished, null when none did */
        @Nullable Instant deployedAt(@NonNull Row instance) {
            return this.deploys.get().get((Integer) instance.get(InstanceModel.ID));
        }

        /** @return the first attached database whose password this instance was deployed before, null for none */
        @Nullable Row staleCredentialsOf(@NonNull Row instance) {
            return this.staleCredentials.get().get((Integer) instance.get(InstanceModel.ID));
        }

        /** @return the ids of the stored instances among these rows */
        private static @NonNull List<Integer> idsOf(@NonNull List<Row> instances) {
            List<Integer> ids = new ArrayList<>();
            for (Row instance : instances) {
                Integer id = instance.get(InstanceModel.ID);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }

        /**
         * When each instance was last deployed: its last-finished succeeded release (an application) or build (a
         * workspace, a git-sourced app), whichever finished later. A workload nothing ever built or released is absent.
         */
        private static @NonNull Map<Integer, Instant> lastDeploys(@NonNull List<Row> instances) {
            List<Integer> ids = idsOf(instances);
            String owner = InstanceModel.MODEL_ID.toString();
            Map<Integer, Instant> deployed = new HashMap<>();
            Models.get(BuildOperationModel.class).lastSucceededAmong(owner, ids).forEach((id, build) ->
                laterOf(deployed, id, build.get(BuildOperationModel.FINISHED_AT)));
            Models.get(ReleaseOperationModel.class).lastSucceededAmong(owner, ids).forEach((id, release) ->
                laterOf(deployed, id, release.get(ReleaseOperationModel.FINISHED_AT)));
            return deployed;
        }

        /** Keeps {@code at} as the record's moment when it is later than the one kept; a null moment keeps nothing. */
        private static void laterOf(@NonNull Map<Integer, Instant> kept, @NonNull Integer id, @Nullable Instant at) {
            if (at != null) {
                kept.merge(id, at, (before, after) -> after.isAfter(before) ? after : before);
            }
        }
    }

    /**
     * Everything a page of site verdicts reads, read once for the page; the certificate rows only when a verdict asks
     * which certificate covers a name.
     */
    private record SiteFacts(@NonNull Map<Integer, List<Row>> domains, @NonNull Map<Integer, List<Row>> paths,
                             @NonNull Map<Integer, Row> instances, @NonNull Map<Integer, Row> runtimes,
                             @NonNull Map<Integer, SiteHealth> live,
                             @NonNull Map<Integer, List<RoutingProblem>> problems, @NonNull Set<String> working,
                             @NonNull Supplier<List<Row>> certificates) {

        static @NonNull SiteFacts of(@NonNull List<Row> sites) {
            return of(sites, null);
        }

        /** @param access the reader whose request shares the certificate rows ({@link #certificateRows}) */
        static @NonNull SiteFacts of(@NonNull List<Row> sites, @Nullable AccessContext access) {
            List<Integer> siteIds = new ArrayList<>();
            List<Integer> instanceIds = new ArrayList<>();
            for (Row site : sites) {
                siteIds.add(site.get(SiteModel.ID));
                Integer instanceId = site.get(SiteModel.INSTANCE_ID);
                if (instanceId != null) {
                    instanceIds.add(instanceId);
                }
            }
            Map<Integer, List<Row>> domains = KeyedRows.byAsked(Models.get(SiteDomainModel.class).find()
                .orderBy(SiteDomainModel.ID, SortOrder.ASC), SiteDomainModel.SITE_ID, siteIds);
            Map<Integer, List<Row>> paths = KeyedRows.byAsked(Models.get(ProtectedPathModel.class).find()
                .orderBy(ProtectedPathModel.ID, SortOrder.ASC), ProtectedPathModel.SITE_ID, siteIds);
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
            return new SiteFacts(domains, paths, instances, ApplicationReleases.runtimeRows(new ArrayList<>(
                instances.values())), live, problems, workingNames(), certificateRows(access));
        }
    }
}
