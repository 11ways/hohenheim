package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
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
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The apps a panel lists for one viewer: every workload with the sites serving it, every site serving no workload of
 * its own, and every stack, joined from the rows that panel's own Sites, Instances and Stacks entries list for that
 * viewer.
 *
 * AIDEV-NOTE: an app is a READING, never a record: the data model stays sites, instances and stacks, and each app row
 * opens the record page decision J1 picks (the workload's, else the site's, else the stack's). The rows are read
 * through each entry's own panel source ({@link CmsRecordSources#panelSource}) under the entry's admission, so this list
 * can never show a viewer a row the Sites or Instances list hides from them (the /manage twin reads the tenant scopes
 * the same way), and never a raw model find. A generated instance (a database container, a database engine) is
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

    /**
     * One app as the list draws it, worded for the viewer it was read for.
     *
     * @param key         unique over the list: the source's token and the record's id
     * @param kind        what it is, in the words its own record page uses
     * @param addressText the address cell as plain text, for the list's search; null when it has none
     * @param host        the host it runs on (a website's: the host of the instance serving it), null without one and
     *                    on the delegated panel
     * @param https       what HTTPS gives its main address (the one the address cell shows), in the Addresses list's
     *                    words; null without an exact address
     * @param health      the verdict its own record page leads with, its fixes offered on the record it speaks for
     * @param target      its record page's front door
     */
    record App(@NonNull String key, @NonNull Source source, int id, @NonNull String name, @NonNull String kind,
               @NonNull SiteHostnamesCell address, @Nullable String addressText, @Nullable String host,
               @Nullable StateLineCell https, @NonNull RecordHealth health, @NonNull RouteTarget target) {

        // AIDEV-NOTE: an app is a reading, so its verdict's fixes are ROW actions of the record behind it (or of the
        // site a workload's verdict already speaks for), never of the Apps entry: ResourceHealth.fixColumn() offers
        // them on that record exactly as its band does (board Apps-List's "Get a certificate").
        App {
            if (health.fixesOn() == null) {
                health = health.on(source.entrySlug, id);
            }
        }

        /** @return how the dashboard and the sidebar count this app, by the verdict its record page leads with */
        @NonNull Count count() {
            return Count.of(this.health.tone());
        }
    }

    /** How an app is counted: live, with a problem, or neither (starting, deploying or not read yet). */
    enum Count {
        LIVE,
        PROBLEM,
        NEITHER;

        static @NonNull Count of(@NonNull HealthTone tone) {
            return switch (tone) {
                case OK -> LIVE;
                case ATTENTION, BROKEN -> PROBLEM;
                case UNKNOWN -> NEITHER;
            };
        }
    }

    private AppDirectory() {
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
        return CmsSupport.memo(conduit, IdentifierKey.of("hohenheim", "app_directory_" + panel.slug()),
            () -> readUncached(panel, access));
    }

    private static @NonNull List<App> readUncached(@NonNull Panel panel, @NonNull AccessContext access) {
        boolean delegated = HohenheimSlugs.MANAGE.equals(panel.slug());
        Wording words = Wording.of(access);
        List<Row> sites = listed(panel, HohenheimSlugs.SITES, access);
        List<Row> instances = new ArrayList<>();
        for (Row instance : listed(panel, HohenheimSlugs.INSTANCES, access)) {
            if (!InstanceParts.isGenerated(instance)) {
                instances.add(instance);
            }
        }
        List<Row> stacks = listed(panel, HohenheimSlugs.STACKS, access);

        Set<String> working = AppHealth.workingNames();
        Map<Integer, Row> workloads = new LinkedHashMap<>();
        for (Row instance : instances) {
            workloads.put(instance.get(InstanceModel.ID), instance);
        }
        List<Row> websites = new ArrayList<>();
        for (Row site : sites) {
            Integer instanceId = site.get(SiteModel.INSTANCE_ID);
            if (instanceId == null || !workloads.containsKey(instanceId)) {
                websites.add(site);
            }
        }
        // AIDEV-NOTE: a workload's address is read from EVERY live site serving it, listed for this viewer or not.
        // The names are the app's public face, which its visitors already type; reading them grants nothing on the
        // site record, whose row and page stay hidden from a viewer the Sites entry does not list it for. Without this
        // a tenant granted only the instance saw its bare port and "No address".
        Map<Integer, List<Row>> servingSites = servingSitesOf(new ArrayList<>(workloads.keySet()));
        List<Row> named = new ArrayList<>(websites);
        servingSites.values().forEach(named::addAll);
        Map<Integer, List<Row>> domains = domainsBySite(named);

        List<App> apps = new ArrayList<>();
        Function<Row, RecordHealth> workloadHealth = AppHealth.instances(delegated).read(instances, access);
        for (Row instance : instances) {
            int id = instance.get(InstanceModel.ID);
            List<Row> served = servingSites.getOrDefault(id, List.of());
            List<Row> names = new ArrayList<>();
            for (Row site : served) {
                names.addAll(domains.getOrDefault(site.get(SiteModel.ID), List.of()));
            }
            SiteHostnamesCell address = names.isEmpty() ? endpointAddress(id, delegated)
                : SiteParts.hostnamesCellOf(names);
            apps.add(new App(Source.WORKLOAD.token() + "-" + id, Source.WORKLOAD, id,
                String.valueOf((Object) instance.get(InstanceModel.NAME)),
                WidgetBadge.of(InstanceModel.KIND, instance.get(InstanceModel.KIND), words.locales, words.resolver)
                    .label(),
                address, address.primary(),
                delegated ? null : ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID)),
                mainHttps(names, served, working, access, panel.slug()), workloadHealth.apply(instance),
                InstanceParts.recordRoute(panel.slug(), instance, null)));
        }

        Function<Row, RecordHealth> websiteHealth = AppHealth.sites(delegated).read(websites, access);
        Map<Integer, Row> servedBy = delegated ? Map.of() : servingInstancesOf(websites);
        for (Row site : websites) {
            int id = site.get(SiteModel.ID);
            List<Row> names = domains.getOrDefault(id, List.of());
            SiteHostnamesCell address = SiteParts.hostnamesCellOf(names);
            apps.add(new App(Source.WEBSITE.token() + "-" + id, Source.WEBSITE, id,
                String.valueOf((Object) site.get(SiteModel.NAME)), words.say(SiteParts.upstreamLabel(site)),
                address, address.primary(), websiteHost(site, servedBy),
                mainHttps(names, List.of(site), working, access, panel.slug()), websiteHealth.apply(site),
                SiteParts.recordRoute(panel.slug(), id)));
        }

        Function<Row, RecordHealth> stackHealth = AppHealth.stacks().read(stacks, access);
        Map<Integer, Integer> members = memberCounts(stacks);
        for (Row stack : stacks) {
            int id = stack.get(StackModel.ID);
            apps.add(new App(Source.STACK.token() + "-" + id, Source.STACK, id,
                String.valueOf((Object) stack.get(StackModel.NAME)),
                words.say(HohenheimMicrocopy.APP_LIST.of("stack_kind")
                    .withArg("count", members.getOrDefault(id, 0))),
                new SiteHostnamesCell(null, 0), null,
                delegated ? null : ServerModel.canonicalNameOf(stack.get(StackModel.SERVER_ID)),
                null, stackHealth.apply(stack),
                CmsRoutes.subpage(panel.slug(), HohenheimSlugs.STACKS, id, HohenheimSlugs.Tab.SERVICES)));
        }
        apps.sort(Comparator.comparing((App app) -> app.name().toLowerCase(Locale.ROOT)).thenComparing(App::key));
        return List.copyOf(apps);
    }

    /**
     * The name of the app a site belongs to, as the Apps list names it: the workload's when the site serves one that is
     * an app (not a generated database or engine container, not removed), else the site's own.
     *
     * AIDEV-NOTE: the one answer every operator surface that names an app from a site reads (the attention items' "Fix
     * on", the Addresses list's App column), so one app never reads "alchemy-skeleton" in the lists and "Alchemy
     * skeleton" in the band (DEP10). A surface a viewer reads without the workload in their scope (the /manage
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
     * @param sites the sites the names belong to, which decide whether the name is a TLS passthrough
     * @return the cell, null without a name; a pattern's says HTTPS works per name a certificate covers
     */
    private static @Nullable StateLineCell mainHttps(@NonNull List<Row> names, @NonNull List<Row> sites,
                                                     @NonNull Set<String> working, @NonNull AccessContext access,
                                                     @NonNull String panelSlug) {
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
        return DomainParts.certificateCell(main, passthrough, working, access, panelSlug);
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
        AppHealth.rowsByKey(Models.get(InstanceModel.class), InstanceModel.ID, ids)
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
        return instance == null ? null : ServerModel.canonicalNameOf(instance.get(InstanceModel.SERVER_ID));
    }

    /** The live (untrashed) sites serving each of these workloads, read once, in stored order. */
    private static @NonNull Map<Integer, List<Row>> servingSitesOf(@NonNull List<Integer> workloadIds) {
        Map<Integer, List<Row>> serving = new HashMap<>();
        AppHealth.rowsByKey(Models.get(SiteModel.class), SiteModel.INSTANCE_ID, workloadIds)
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
        return AppHealth.rowsByKey(Models.get(SiteDomainModel.class), SiteDomainModel.SITE_ID, ids);
    }

    /** How many services each of these stacks declares, read once. */
    private static @NonNull Map<Integer, Integer> memberCounts(@NonNull List<Row> stacks) {
        List<Integer> ids = new ArrayList<>();
        for (Row stack : stacks) {
            ids.add(stack.get(StackModel.ID));
        }
        Map<Integer, Integer> counts = new HashMap<>();
        AppHealth.rowsByKey(Models.get(StackServiceModel.class), StackServiceModel.STACK_ID, ids)
            .forEach((stack, services) -> counts.put(stack, services.size()));
        return counts;
    }

    /**
     * Where a workload no site serves is reached: its first claimed port on its host's public address (the overview's
     * own endpoint read), or no address at all.
     */
    private static @NonNull SiteHostnamesCell endpointAddress(int instanceId, boolean delegated) {
        List<InstanceEndpointView> endpoints = InstanceOverview.endpointsOf(instanceId, delegated);
        if (endpoints.isEmpty()) {
            return new SiteHostnamesCell(null, 0);
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
