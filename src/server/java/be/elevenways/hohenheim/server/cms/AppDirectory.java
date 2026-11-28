package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.hohenheim.server.instance.InstanceKindHandler;
import be.elevenways.hohenheim.server.instance.InstanceKinds;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.key.IdentifierKey;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
import be.elevenways.zenit.cms.common.resource.HealthTone;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.cms.server.page.CmsRecordSources;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSource;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.field.EnumField;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.KeyedRows;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The apps a panel lists for one viewer: every workload with the sites serving it, every site serving no workload of
 * its own, and every stack, joined from the rows that panel's own Sites, Instances and Stacks entries list for that
 * viewer.
 *
 * AIDEV-NOTE: an app is a READING, never a record: the data model stays sites, instances and stacks, and each app row
 * opens the record page decision J1 picks (the workload's, else the site's, else the stack's). The rows are read
 * through each entry's own panel source ({@link CmsRecordSources#panelSource}) under the entry's admission, so this
 * list can never show a viewer a row the Sites or Instances list hides from them (the /manage twin reads the tenant
 * scopes the same way), and never a raw model find. A generated instance (a database container, a database engine) is
 * managed through its owning record and is never an app; a stack's services are its members, never apps of their own.
 *
 * @author Jelle De Loecker
 * @since  0.2.0
 */
final class AppDirectory {

    /** What an app row is read from: the closed set of records that make an app. */
    enum Source {

        /** A workload, together with the sites that serve it. */
        WORKLOAD(HohenheimSlugs.INSTANCES),

        /** A site that serves no workload the viewer may open. */
        WEBSITE(HohenheimSlugs.SITES),

        /** A Docker stack; its services are its members. */
        STACK(HohenheimSlugs.STACKS);

        private final @NonNull String entrySlug;

        Source(@NonNull String entrySlug) {
            this.entrySlug = entrySlug;
        }

        /** @return the stable token the list's kind filter and the row key carry */
        @NonNull String token() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /** @return the slugs of the entries whose rows the apps are read from, one per {@link Source} */
    static @NonNull String[] sourceEntrySlugs() {
        String[] slugs = new String[Source.values().length];
        for (Source source : Source.values()) {
            slugs[source.ordinal()] = source.entrySlug;
        }
        return slugs;
    }

    /**
     * One app as a list draws it: this server's, opening its record page ({@link App}), or a linked server's as that
     * server last told it, opening nothing here yet ({@link HeldApp}).
     */
    sealed interface Listed permits App, HeldApp {

        /** @return its cells, worded for the viewer it was read for */
        @NonNull Cells cells();
    }

    /**
     * The cells of one app, worded for the viewer it was read for.
     *
     * @param key         unique over the list: the source's token and the record's id, behind the linked server's
     *                    key for an app of another server
     * @param source      the kind of record it is read from, null for a linked server's app this build cannot read
     * @param kind        what it is, in the words its own record page uses
     * @param addressText the address cell as plain text, for the list's search; null when it has none
     * @param host        the host it runs on (a website's: the host of the instance serving it), null without one and
     *                    on the delegated panel
     * @param https       what HTTPS gives its main address (the one the address cell shows), in the Addresses list's
     *                    words; null without an exact address
     * @param health      the verdict its own record page leads with; an app of this server's offers its fixes on the
     *                    record it speaks for, a told one carries none
     */
    record Cells(@NonNull String key, @Nullable Source source, int id, @NonNull String name, @NonNull String kind,
                 @Nullable SiteHostnamesCell address, @Nullable String addressText, @Nullable String host,
                 @Nullable StateLineCell https, @NonNull RecordHealth health) {

        private static @NonNull Cells of(@NonNull String key, @NonNull AppFacts facts, @Nullable StateLineCell https,
                                         @NonNull RecordHealth health, @NonNull Wording words) {
            return new Cells(key, facts.source(), facts.id(), facts.name(), words.say(facts.kind()), facts.address(),
                facts.address() == null ? null : facts.address().primary(), facts.host(), https, health);
        }
    }

    /**
     * One app of this server.
     *
     * @param target its record page's front door
     */
    record App(@NonNull Cells cells, @NonNull RouteTarget target) implements Listed {

        /** @return this server's app drawn from its facts, opening its record page */
        static @NonNull App of(@NonNull AppFacts facts, @NonNull RouteTarget target, @NonNull Wording words) {
            // AIDEV-NOTE: an app is a reading, so its verdict's fixes are ROW actions of the record behind it (or of
            // the site a workload's verdict already speaks for), never of the Apps entry: ResourceHealth.fixColumn()
            // offers them on that record exactly as its band does ("Get a certificate").
            RecordHealth health = facts.health().fixesOn() == null
                ? facts.health().on(facts.source().entrySlug, facts.id()) : facts.health();
            return new App(Cells.of(facts.key(), facts, facts.https(), health, words), target);
        }

        /** @return how the dashboard and the sidebar count this app, by the verdict its record page leads with */
        @NonNull Count count() {
            return Count.of(this.cells.health().tone());
        }
    }

    /**
     * One app of a linked server, drawn by this server's own cell code from the facts that server last told.
     *
     * @param held the linked server it lives on
     * @param note the plain line under its name saying it opens nothing here yet, in the viewer's words
     */
    record HeldApp(@NonNull Cells cells, @NonNull Held held, @NonNull String note) implements Listed {

        /**
         * @return a linked server's app; a silent server's keeps what it told of the app itself, while its verdict and
         *         HTTPS, no longer current, say only when the server last answered
         */
        static @NonNull HeldApp of(@NonNull AppFacts facts, @NonNull Held held, @NonNull Wording words) {
            String key = held.peer() + "-" + facts.key();
            String note = words.say(HohenheimMicrocopy.APP_LIST.of("held_no_page"));
            if (!held.silent()) {
                return new HeldApp(Cells.of(key, facts, facts.https(), facts.health(), words), held, note);
            }
            Microcopy lastSeen = held.lastSeen() == null ? HohenheimMicrocopy.APP_LIST.of("held_never_seen")
                : HohenheimMicrocopy.APP_LIST.of("held_last_seen").withArg("at", held.lastSeen());
            StateLineCell https = new StateLineCell(HealthTone.SILENT.token(), BadgeVariant.SECONDARY, lastSeen, null,
                null, null, null);
            RecordHealth silent = RecordHealth.silent(HohenheimMicrocopy.APP_LIST.of("held_silent")
                .withArg("server", held.server()));
            return new HeldApp(Cells.of(key, facts, https, silent, words), held, note);
        }

        /**
         * @return a linked server's app whose told state this build cannot read (told by a newer build, or damaged):
         *         listed under the name its record is kept by, saying so, so it never vanishes from the list unseen
         * @param recordKey the key its state is kept under on its server
         * @param title     the name its state is kept by
         */
        static @NonNull HeldApp unreadable(@NonNull String recordKey, @NonNull String title, @NonNull Held held,
                                           @NonNull Wording words) {
            RecordHealth unread = new RecordHealth(HealthTone.UNKNOWN, HohenheimMicrocopy.APP_LIST.of("held_unreadable")
                .withArg("server", held.server()), null, List.of(), null);
            Cells cells = new Cells(held.peer() + "-" + recordKey, null, 0, title, "", null, null, null, null, unread);
            return new HeldApp(cells, held, words.say(HohenheimMicrocopy.APP_LIST.of("held_no_page")));
        }
    }

    /**
     * The linked server an app lives on, as this server last heard of it.
     *
     * @param peer     the key the record of that server is kept under
     * @param server   its name
     * @param silent   whether it does not answer now, so what it told is no longer current
     * @param lastSeen when it last answered, null when it never did
     */
    record Held(@NonNull String peer, @NonNull String server, boolean silent, @Nullable Instant lastSeen) {
    }

    /**
     * How an app is counted: live, with a problem, neither (starting, deploying or not read yet; or off, not set up or
     * switched off, a tone no app's verdict carries today but which would ask for no attention either), or retired (out
     * of service on purpose, so never a problem and never waiting to be read).
     */
    enum Count {
        LIVE,
        PROBLEM,
        NEITHER,
        RETIRED;

        static @NonNull Count of(@NonNull HealthTone tone) {
            return switch (tone) {
                case OK -> LIVE;
                case ATTENTION, BROKEN -> PROBLEM;
                case UNKNOWN, OFF, SILENT -> NEITHER;
                case RETIRED -> RETIRED;
            };
        }
    }

    private AppDirectory() {
    }

    /**
     * The records the apps are made of, from the rows the Sites, Instances and Stacks entries hold: the instances that
     * are no generated workload, the sites serving none of those, and the stacks. THE rule of what an app is, read by
     * the Apps list and by the installation's own app count ({@link InstallationCounts}).
     *
     * @param workloads the instances that are apps, in the order given
     * @param websites  the sites that are apps of their own, in the order given
     * @param stacks    the stacks, each one app
     */
    record Members(@NonNull List<Row> workloads, @NonNull List<Row> websites, @NonNull List<Row> stacks) {

        static @NonNull Members of(@NonNull List<Row> sites, @NonNull List<Row> instances, @NonNull List<Row> stacks) {
            List<Row> workloads = new ArrayList<>();
            Set<Integer> workloadIds = new HashSet<>();
            for (Row instance : instances) {
                if (!InstanceParts.isGenerated(instance)) {
                    workloads.add(instance);
                    workloadIds.add(instance.get(InstanceModel.ID));
                }
            }
            List<Row> websites = new ArrayList<>();
            for (Row site : sites) {
                Integer instanceId = site.get(SiteModel.INSTANCE_ID);
                if (instanceId == null || !workloadIds.contains(instanceId)) {
                    websites.add(site);
                }
            }
            return new Members(workloads, websites, stacks);
        }

        /** @return how many apps these records make */
        int apps() {
            return this.workloads.size() + this.websites.size() + this.stacks.size();
        }
    }

    /**
     * @return how many of these apps have a problem: the Apps tile's "with a problem" and the Apps entry's sidebar
     *         badge, one count
     */
    static int withProblem(@NonNull List<App> apps) {
        int problems = 0;
        for (App app : apps) {
            if (app.count() == Count.PROBLEM) {
                problems++;
            }
        }
        return problems;
    }

    /**
     * @return the apps this panel lists for this viewer, by name; read once per request, because the sidebar's badge,
     *         the dashboard and the Apps list of one page all ask
     */
    static @NonNull List<App> read(@NonNull Panel panel, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        if (conduit == null) {
            return readUncached(panel, access);
        }
        return conduit.memo(IdentifierKey.of("hohenheim", "app_directory_" + panel.slug()),
            () -> readUncached(panel, access));
    }

    private static @NonNull List<App> readUncached(@NonNull Panel panel, @NonNull AccessContext access) {
        boolean delegated = HohenheimSlugs.MANAGE.equals(panel.slug());
        Wording words = Wording.of(access);
        Members records = Members.of(listed(panel, HohenheimSlugs.SITES, access),
            listed(panel, HohenheimSlugs.INSTANCES, access), listed(panel, HohenheimSlugs.STACKS, access));
        List<App> apps = new ArrayList<>();
        for (Located located : located(records, delegated, access, panel.slug())) {
            apps.add(App.of(located.facts(), located.target(), words));
        }
        apps.sort(Comparator.comparing((App app) -> app.cells().name().toLowerCase(Locale.ROOT))
            .thenComparing(app -> app.cells().key()));
        return List.copyOf(apps);
    }

    /**
     * The apps the Apps list of this panel shows this viewer: this server's ({@link #read}) and, on the operator's
     * panel for an administrator, every linked server's as it last told them ({@link AppStates#held}), by name; read
     * once per request.
     *
     * AIDEV-NOTE: the master decides who sees a linked server's apps, and today it can decide that only by
     * permission: an app's owners and grants are the linked server's own rows (RecordOwners over zenit-auth grants,
     * no shared kind), so a viewer who is no administrator sees none of them until grants follow the master. Never
     * widen this with a rule of its own.
     */
    static @NonNull List<Listed> everyServer(@NonNull Panel panel, @NonNull AccessContext access) {
        Supplier<List<Listed>> read = () -> {
            List<Listed> apps = new ArrayList<>(read(panel, access));
            if (showsLinkedServers(panel, access)) {
                apps.addAll(AppStates.held(Wording.of(access)));
                apps.sort(Comparator.comparing((Listed app) -> app.cells().name().toLowerCase(Locale.ROOT))
                    .thenComparing(app -> app.cells().key()));
            }
            return List.copyOf(apps);
        };
        Conduit conduit = access.conduit();
        return conduit == null ? read.get()
            : conduit.memo(IdentifierKey.of("hohenheim", "app_directory_all_" + panel.slug()), read);
    }

    /**
     * @return whether this panel's Apps list shows this viewer the apps of the servers this one manages: the
     *         operator's panel, an administrator, and a server to manage
     */
    static boolean showsLinkedServers(@NonNull Panel panel, @NonNull AccessContext access) {
        return HohenheimSlugs.ADMIN.equals(panel.slug()) && HohenheimAccess.isAdmin(access)
            && LinkedServers.managesAny();
    }

    /**
     * The facts of every app of this installation as the operator's Apps list holds them, read without a viewer from
     * the rows the operator's entries hold (as {@link InstallationCounts} counts them): what this server tells its
     * master ({@link AppStates}). A fixed number of reads whatever the installation holds.
     *
     * AIDEV-NOTE: read as nobody (the request-less anonymous context, which widens nothing), yet equal to what an
     * administrator's own list holds: on the operator's face every fact a state document carries is the record's,
     * never the reader's (a verdict's "deploying" reads whether any run is open, OperationRuns.anyOpenOn; a placement
     * refusal is the gate's own words there). The one reader-dependent part, an HTTPS cell's certificate name and
     * link, is never told (AppFacts.document). A new fact read for the viewer breaks this: read it for nobody.
     */
    static @NonNull List<AppFacts> operatorFacts() {
        Members records = Members.of(Models.get(SiteModel.class).find().noCache().all(),
            Models.get(InstanceModel.class).find().noCache().where(InstanceParts.listedByOperator()).all(),
            Models.get(StackModel.class).find().noCache().all());
        List<AppFacts> facts = new ArrayList<>();
        for (Located located : located(records, false, AccessContext.anonymous(), HohenheimSlugs.ADMIN)) {
            facts.add(located.facts());
        }
        return facts;
    }

    /** One app's facts and its record page's front door on the panel they were read for. */
    private record Located(@NonNull AppFacts facts, @NonNull RouteTarget target) {
    }

    /** The facts of the apps these records make, read from this process for this reader. */
    private static @NonNull List<Located> located(@NonNull Members records, boolean delegated,
                                                  @NonNull AccessContext access, @NonNull String panelSlug) {
        List<Row> instances = records.workloads();
        List<Row> stacks = records.stacks();
        Set<String> working = AppHealth.workingNames();
        // The HTTPS column's certificate rows, read once for the whole list (and only when a main name asks), shared
        // with the site and instance verdicts below.
        Supplier<List<Row>> certificates = AppHealth.certificateRows(access);
        Map<Integer, Row> workloads = new LinkedHashMap<>();
        for (Row instance : instances) {
            workloads.put(instance.get(InstanceModel.ID), instance);
        }
        List<Row> websites = records.websites();
        // AIDEV-NOTE: a workload's address is read from EVERY live site serving it, listed for this viewer or not.
        // The names are the app's public face, which its visitors already type; reading them grants nothing on the
        // site record, whose row and page stay hidden from a viewer the Sites entry does not list it for. Without this
        // a tenant granted only the instance saw its bare port and "No address".
        Map<Integer, List<Row>> servingSites = servingSitesOf(new ArrayList<>(workloads.keySet()));
        List<Row> named = new ArrayList<>(websites);
        servingSites.values().forEach(named::addAll);
        Map<Integer, List<Row>> domains = domainsBySite(named);

        List<Located> apps = new ArrayList<>();
        Function<Row, RecordHealth> workloadHealth = AppHealth.instances(delegated).read(instances, access);
        for (Row instance : instances) {
            int id = instance.get(InstanceModel.ID);
            List<Row> served = servingSites.getOrDefault(id, List.of());
            List<Row> names = new ArrayList<>();
            for (Row site : served) {
                names.addAll(domains.getOrDefault(site.get(SiteModel.ID), List.of()));
            }
            // A machine no site can serve has no web address to lack: with no port either, its cell stays empty
            // rather than saying "No address yet" (its overview's Network card holds its addresses).
            InstanceKindHandler kind = InstanceKinds.handlerOf(instance);
            SiteHostnamesCell address = !names.isEmpty() ? SiteParts.hostnamesCellOf(names)
                : endpointAddress(id, delegated, kind != null && kind.supportsSiteUpstream());
            apps.add(new Located(new AppFacts(Source.WORKLOAD, id,
                String.valueOf((Object) instance.get(InstanceModel.NAME)), kindOf(instance), address,
                delegated ? null : HostModel.canonicalNameOf(instance.get(InstanceModel.HOST_ID)),
                mainHttps(names, served, working, certificates, access, panelSlug), workloadHealth.apply(instance)),
                InstanceParts.recordRoute(panelSlug, instance, null)));
        }

        Function<Row, RecordHealth> websiteHealth = AppHealth.sites(delegated).read(websites, access);
        Map<Integer, Row> servedBy = delegated ? Map.of() : servingInstancesOf(websites);
        for (Row site : websites) {
            int id = site.get(SiteModel.ID);
            List<Row> names = domains.getOrDefault(id, List.of());
            SiteHostnamesCell address = SiteParts.addressCellOf(site, names);
            apps.add(new Located(new AppFacts(Source.WEBSITE, id, String.valueOf((Object) site.get(SiteModel.NAME)),
                SiteParts.upstreamLabel(site), address, websiteHost(site, servedBy),
                mainHttps(names, List.of(site), working, certificates, access, panelSlug),
                websiteHealth.apply(site)), SiteParts.recordRoute(panelSlug, id)));
        }

        Function<Row, RecordHealth> stackHealth = AppHealth.stacks().read(stacks, access);
        Map<Integer, Long> members = memberCounts(stacks);
        for (Row stack : stacks) {
            int id = stack.get(StackModel.ID);
            apps.add(new Located(new AppFacts(Source.STACK, id, String.valueOf((Object) stack.get(StackModel.NAME)),
                HohenheimMicrocopy.APP_LIST.of("stack_kind").withArg("count", members.getOrDefault(id, 0L)),
                new SiteHostnamesCell(null, 0),
                delegated ? null : HostModel.canonicalNameOf(stack.get(StackModel.HOST_ID)), null,
                stackHealth.apply(stack)),
                CmsRoutes.subpage(panelSlug, HohenheimSlugs.STACKS, id, HohenheimSlugs.Tab.SERVICES)));
        }
        return apps;
    }

    /** @return a workload's kind in the words its record's kind field declares, its stored key when it declares none */
    private static @NonNull Microcopy kindOf(@NonNull Row instance) {
        String stored = String.valueOf((Object) instance.get(InstanceModel.KIND));
        EnumField.EnumValue value = InstanceModel.KIND.getValues().get(stored);
        return value == null ? Microcopy.literal(stored) : value.getLabel();
    }

    /**
     * The name of the app a site belongs to, as the Apps list names it: the workload's when the site serves one that is
     * an app (not a generated database or engine container, not removed), else the site's own.
     *
     * AIDEV-NOTE: the one answer every operator surface that names an app from a site reads (the attention items' "Fix
     * on", the Addresses list's App column), so one app never reads "alchemy-skeleton" in the lists and "Alchemy
     * skeleton" in the band. A surface a viewer reads without the workload in their scope (the /manage
     * Addresses twin) names the site, as their Apps list does: {@link #nameOf(Row, boolean)}.
     */
    static @NonNull String nameOf(@NonNull Row site) {
        return nameOf(site, true);
    }

    /** @param workloadVisible whether the reader's Apps list holds the site's workload as the app */
    static @NonNull String nameOf(@NonNull Row site, boolean workloadVisible) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        if (workloadVisible && instanceId != null) {
            Row instance = Models.get(InstanceModel.class).findById(instanceId);
            if (instance != null && !InstanceParts.isGenerated(instance)
                    && instance.get(InstanceModel.DELETED_AT) == null) {
                return String.valueOf((Object) instance.get(InstanceModel.NAME));
            }
        }
        return String.valueOf((Object) site.get(SiteModel.NAME));
    }

    /** @return the app name of the site with this id ({@link #nameOf(Row)}), or the given fallback when it is gone */
    static @NonNull String nameOfSite(int siteId, @NonNull String fallback) {
        Row site = Models.get(SiteModel.class).findById(siteId);
        return site == null ? fallback : nameOf(site);
    }

    /**
     * The rows one entry of this panel lists for this viewer, through that entry's own source: nothing for an entry
     * the panel does not register (a node without that role) or does not admit this viewer to.
     */
    static @NonNull List<Row> listed(@NonNull Panel panel, @NonNull String slug, @NonNull AccessContext access) {
        if (!offers(panel, slug, access)) {
            return List.of();
        }
        PanelEntry entry = panel.entryBySlug(slug);
        RecordSource<?> source = CmsRecordSources.panelSource(panel, entry);
        if (source == null || !source.authorizes(access)) {
            return List.of();
        }
        return source.buildQuery(null, null, null, SortOrder.ASC, null, access).all();
    }

    /** @return whether this panel registers the entry and admits this viewer to it */
    static boolean offers(@NonNull Panel panel, @NonNull String slug, @NonNull AccessContext access) {
        PanelEntry entry = panel.entryBySlug(slug);
        return entry != null && panel.admits(entry, access);
    }

    /**
     * What HTTPS gives an app's main address, the first name its address cell shows, through the Addresses list's own
     * cell ({@link DomainParts#certificateCell}): one name's HTTPS reads the same words on both lists, never the app's
     * overall verdict.
     *
     * @param sites        the sites the names belong to, which decide whether the name is a TLS passthrough
     * @param certificates the certificate rows, read once for the request ({@link AppHealth#certificateRows})
     * @return the cell, null without a name; a pattern's says HTTPS works per name a certificate covers
     */
    private static @Nullable StateLineCell mainHttps(@NonNull List<Row> names, @NonNull List<Row> sites,
                                                     @NonNull Set<String> working,
                                                     @NonNull Supplier<List<Row>> certificates,
                                                     @NonNull AccessContext access, @NonNull String panelSlug) {
        if (names.isEmpty()) {
            return null;
        }
        Row main = names.get(0);
        boolean passthrough = false;
        for (Row site : sites) {
            if (Objects.equals(site.get(SiteModel.ID), main.get(SiteDomainModel.SITE_ID))) {
                passthrough = SiteParts.tlsPassthrough(site);
            }
        }
        return DomainParts.certificateCell(main, passthrough, working, certificates, access, panelSlug);
    }

    /**
     * The instances these websites serve that are no app of their own here (a generated instance, one this viewer's
     * Instances list does not show), read once, by id: the website runs where its instance runs.
     */
    private static @NonNull Map<Integer, Row> servingInstancesOf(@NonNull List<Row> websites) {
        List<Integer> ids = new ArrayList<>();
        for (Row site : websites) {
            Integer instanceId = site.get(SiteModel.INSTANCE_ID);
            if (instanceId != null) {
                ids.add(instanceId);
            }
        }
        Map<Integer, Row> instances = new HashMap<>();
        KeyedRows.byAsked(Models.get(InstanceModel.class).find(), InstanceModel.ID, ids)
            .forEach((id, rows) -> instances.put(id, rows.get(0)));
        return instances;
    }

    /**
     * @param servedBy the instances websites serve, by id ({@link #servingInstancesOf}); empty on the delegated panel
     * @return the host of the instance serving this website, null for a website no instance serves
     */
    private static @Nullable String websiteHost(@NonNull Row site, @NonNull Map<Integer, Row> servedBy) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        Row instance = instanceId == null ? null : servedBy.get(instanceId);
        return instance == null ? null : HostModel.canonicalNameOf(instance.get(InstanceModel.HOST_ID));
    }

    /** The live (untrashed) sites serving each of these workloads, read once, in stored order. */
    private static @NonNull Map<Integer, List<Row>> servingSitesOf(@NonNull List<Integer> workloadIds) {
        Map<Integer, List<Row>> serving = new HashMap<>();
        KeyedRows.byAsked(Models.get(SiteModel.class).find().orderBy(SiteModel.ID, SortOrder.ASC),
                SiteModel.INSTANCE_ID, workloadIds)
            .forEach((instanceId, sites) -> {
                for (Row site : sites) {
                    if (site.get(SiteModel.DELETED_AT) == null) {
                        serving.computeIfAbsent(instanceId, id -> new ArrayList<>()).add(site);
                    }
                }
            });
        return serving;
    }

    /** Every name of these sites, read once, in each site's stored order. */
    private static @NonNull Map<Integer, List<Row>> domainsBySite(@NonNull List<Row> sites) {
        List<Integer> ids = new ArrayList<>();
        for (Row site : sites) {
            ids.add(site.get(SiteModel.ID));
        }
        return KeyedRows.byAsked(Models.get(SiteDomainModel.class).find().orderBy(SiteDomainModel.ID, SortOrder.ASC),
            SiteDomainModel.SITE_ID, ids);
    }

    /** How many services each of these stacks declares, counted in one grouped read. */
    private static @NonNull Map<Integer, Long> memberCounts(@NonNull List<Row> stacks) {
        List<Integer> ids = new ArrayList<>();
        for (Row stack : stacks) {
            ids.add(stack.get(StackModel.ID));
        }
        return ids.isEmpty() ? Map.of()
            : Models.get(StackServiceModel.class).find().countBy(StackServiceModel.STACK_ID, ids);
    }

    /**
     * Where a workload no site serves is reached: its first claimed port on its host's public address (the overview's
     * own endpoint read), or no address at all: "No address yet" for a kind a site could serve, nothing for one no
     * site can.
     */
    private static @Nullable SiteHostnamesCell endpointAddress(int instanceId, boolean delegated,
                                                               boolean siteServable) {
        List<InstanceEndpointView> endpoints = InstanceOverview.endpointsOf(instanceId, delegated);
        if (endpoints.isEmpty()) {
            return siteServable ? new SiteHostnamesCell(null, 0) : null;
        }
        InstanceEndpointView first = endpoints.get(0);
        return new SiteHostnamesCell(first.address() + ":" + first.port(), endpoints.size() - 1);
    }

    /** The viewer's language, for the words a row carries as text (so the list sorts and searches what it shows). */
    record Wording(@NonNull LocaleChain locales, @Nullable MessageResolver resolver) {

        static @NonNull Wording of(@NonNull AccessContext access) {
            Conduit conduit = access.conduit();
            LocaleChain locales = conduit != null ? conduit.getLocales() : null;
            // A conduit that negotiated no language (a detached or test conduit) words the rows in the default one.
            return locales != null ? new Wording(locales, conduit.getMessageResolver())
                : new Wording(LocaleChain.ofTags("en"), MessageResolvers.getDefault());
        }

        @NonNull String say(@NonNull Microcopy copy) {
            return copy.resolve(this.locales, this.resolver);
        }

        /** @return the parts said one after the other ("3 live, 1 with a problem"), null when there are none */
        @Nullable String join(@NonNull List<Microcopy> parts) {
            if (parts.isEmpty()) {
                return null;
            }
            List<String> said = new ArrayList<>(parts.size());
            for (Microcopy part : parts) {
                said.add(this.say(part));
            }
            return String.join(", ", said);
        }
    }
}
