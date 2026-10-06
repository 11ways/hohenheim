package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.instance.InstanceEndpointView;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.StackModel;
import be.elevenways.hohenheim.model.StackServiceModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.site.SiteHostnamesCell;
import be.elevenways.hohenheim.site.SiteTlsCell;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.MessageResolvers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelEntry;
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
        WORKLOAD,

        /** A site that serves no workload the viewer may open. */
        WEBSITE,

        /** A Docker stack; its services are its members. */
        STACK;

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
     * @param host        the host it runs on, null for a website and on the delegated panel
     * @param health      the verdict its own record page leads with
     * @param target      its record page's front door
     */
    record App(@NonNull String key, @NonNull Source source, int id, @NonNull String name, @NonNull String kind,
               @NonNull SiteHostnamesCell address, @Nullable String addressText, @Nullable String host,
               @NonNull SiteTlsCell https, @NonNull RecordHealth health, @NonNull RouteTarget target) {}

    private AppDirectory() {
    }

    /** @return the apps this panel lists for this viewer, by name */
    static @NonNull List<App> read(@NonNull Panel panel, @NonNull AccessContext access) {
        boolean delegated = ManagePanel.SLUG.equals(panel.slug());
        Wording words = Wording.of(access);
        List<Row> sites = listed(panel, HohenheimSlugs.SITES, access);
        List<Row> instances = new ArrayList<>();
        for (Row instance : listed(panel, InstanceParts.SLUG, access)) {
            if (!InstanceParts.isGenerated(instance)) {
                instances.add(instance);
            }
        }
        List<Row> stacks = listed(panel, StackParts.SLUG, access);

        Set<String> working = CertificateCoverage.activeNames();
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
            boolean passthrough = !served.isEmpty();
            for (Row site : served) {
                names.addAll(domains.getOrDefault(site.get(SiteModel.ID), List.of()));
                passthrough &= SiteParts.tlsPassthrough(site);
            }
            SiteHostnamesCell address = names.isEmpty() ? endpointAddress(id) : SiteParts.hostnamesCellOf(names);
            apps.add(new App(Source.WORKLOAD.token() + "-" + id, Source.WORKLOAD, id,
                String.valueOf((Object) instance.get(InstanceModel.NAME)),
                WidgetBadge.of(InstanceModel.KIND, instance.get(InstanceModel.KIND), words.locales, words.resolver)
                    .label(),
                address, address.primary(),
                delegated ? null : hostOf(instance.get(InstanceModel.SERVER_ID)),
                SiteParts.tlsCellOf(names, passthrough, working), workloadHealth.apply(instance),
                InstanceParts.recordRoute(panel.slug(), instance, null)));
        }

        Function<Row, RecordHealth> websiteHealth = AppHealth.sites(delegated).read(websites, access);
        for (Row site : websites) {
            int id = site.get(SiteModel.ID);
            List<Row> names = domains.getOrDefault(id, List.of());
            SiteHostnamesCell address = SiteParts.hostnamesCellOf(names);
            apps.add(new App(Source.WEBSITE.token() + "-" + id, Source.WEBSITE, id,
                String.valueOf((Object) site.get(SiteModel.NAME)), words.say(SiteParts.upstreamLabel(site)),
                address, address.primary(), null,
                SiteParts.tlsCellOf(names, SiteParts.tlsPassthrough(site), working), websiteHealth.apply(site),
                SiteParts.recordRoute(panel.slug(), id)));
        }

        Function<Row, RecordHealth> stackHealth = AppHealth.stacks().read(stacks, access);
        Map<Integer, Integer> members = memberCounts(stacks);
        for (Row stack : stacks) {
            int id = stack.get(StackModel.ID);
            apps.add(new App(Source.STACK.token() + "-" + id, Source.STACK, id,
                String.valueOf((Object) stack.get(StackModel.NAME)),
                words.say(Microcopy.of("stack_kind").withFilter("scope", "app_list")
                    .withArg("count", members.getOrDefault(id, 0))),
                new SiteHostnamesCell(null, 0), null,
                delegated ? null : hostOf(stack.get(StackModel.SERVER_ID)),
                new SiteTlsCell(SiteTlsCell.NONE), stackHealth.apply(stack),
                CmsRoutes.subpage(panel.slug(), StackParts.SLUG, id, StackServicesPage.SLUG)));
        }
        apps.sort(Comparator.comparing((App app) -> app.name().toLowerCase(Locale.ROOT)).thenComparing(App::key));
        return List.copyOf(apps);
    }

    /**
     * The rows one entry of this panel lists for this viewer, through that entry's own source: nothing for an entry
     * the panel does not register (a node without that role) or does not admit this viewer to.
     */
    private static @NonNull List<Row> listed(@NonNull Panel panel, @NonNull String slug,
                                             @NonNull AccessContext access) {
        PanelEntry entry = panel.entryBySlug(slug);
        if (entry == null || !panel.admits(entry, access)) {
            return List.of();
        }
        RecordSource<?> source = CmsRecordSources.panelSource(panel, entry);
        if (source == null || !source.authorizes(access)) {
            return List.of();
        }
        return source.buildQuery(null, null, null, SortOrder.ASC, null, access).all();
    }

    /** The name of the host a workload or stack runs on, the way its record page's lead line names it. */
    private static @NonNull String hostOf(@Nullable Integer serverId) {
        return ServerModel.nameOf(ServerModel.canonicalServerId(serverId));
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
    private static @NonNull SiteHostnamesCell endpointAddress(int instanceId) {
        List<InstanceEndpointView> endpoints = InstanceOverview.endpointsOf(instanceId);
        if (endpoints.isEmpty()) {
            return new SiteHostnamesCell(null, 0);
        }
        InstanceEndpointView first = endpoints.get(0);
        return new SiteHostnamesCell(first.address() + ":" + first.port(), endpoints.size() - 1);
    }

    /** The viewer's language, for the words a row carries as text (so the list sorts and searches what it shows). */
    private record Wording(@NonNull LocaleChain locales, @Nullable MessageResolver resolver) {

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
    }
}
