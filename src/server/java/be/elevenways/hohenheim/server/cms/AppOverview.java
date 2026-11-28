package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.app.AppAddress;
import be.elevenways.hohenheim.app.AppProtection;
import be.elevenways.hohenheim.model.*;
import be.elevenways.hohenheim.server.cluster.LinkedServers;
import be.elevenways.hohenheim.server.cms.AppDirectory.Source;
import be.elevenways.hohenheim.server.cms.AppDirectory.Wording;
import be.elevenways.hohenheim.server.cms.AppDirectory.Cells;
import be.elevenways.hohenheim.server.cms.AppDirectory.HeldApp;
import be.elevenways.zenit.cms.common.resource.HeldRecords;
import be.elevenways.zenit.cms.common.resource.RecordHealth;
import be.elevenways.zenit.server.peer.KeptRow;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.hohenheim.StateLineCell;
import be.elevenways.hohenheim.server.tls.CertificateExpiry;
import be.elevenways.hohenheim.server.upstream.kinds.StreamUpstreamKind;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.HealthCellState;
import be.elevenways.zenit.cms.common.resource.ActivitySources;
import be.elevenways.zenit.cms.common.resource.PanelResource;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.cms.common.resource.ResourceVerb;
import be.elevenways.zenit.cms.server.panel.PartsForms;
import be.elevenways.zenit.cms.server.panel.ResourceVerbs;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.ItemListWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.UsageBarWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import be.elevenways.hohenheim.RawValues;
import be.elevenways.hohenheim.ports.PublishedPort;
import be.elevenways.hohenheim.ports.StreamPort;
import java.util.Set;

import static be.elevenways.hohenheim.server.cms.CmsSupport.opens;

/**
 * The ONE composition of an app's overview, drawn by each of its pages (an instance's, a site's when it serves no
 * workload of its own, and the Apps list's page of a linked server's app): Addresses, Protection and what the workload
 * uses in the wide column, Details and Recent in the narrow one, each a framework card ({@link CardWidget}). The
 * record's actions are the record heading's (zenitcms:record-head) and the verdict is the resource's health part
 * ({@link AppHealth}), the page's first band.
 *
 * AIDEV-NOTE: every card READS the facts other surfaces own (certificate coverage, the protected-path invariant, the
 * activity source), so the overview, the list columns and the health band say the same thing. The Details card leads
 * with the app's facts ({@link #identity}): for this server's record read through the one facts source
 * ({@link AppDirectory#cellsOf}), for a linked server's app what that server told, never this process's fact for its
 * id. A card only this server's own record can fill (Resources, Recent, the record's own lines) is not drawn for a
 * linked server's app. The delegated panel draws the same composition; the host and the audit log are never added
 * there (see InstanceOverview).
 * AIDEV-NOTE: localized content: the copy is the reader's (a told kind, verdict and HTTPS words revive as copy); the
 * name, host, server and addresses are content.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class AppOverview {

    private AppOverview() {
    }

    /**
     * The site's front door: the app composition over the site alone; a linked server's website opens it too, drawn
     * from what that server told ({@link #heldWidgets}).
     */
    static @NonNull RecordOverview<Row> siteTab() {
        return RecordOverview.<Row>fields(RecordOverview.SLUG, HohenheimMicrocopy.INSTANCE.of("overview"))
            .withoutFields()
            .widgets(AppOverview::siteWidgets)
            .servesHeld((site, access) -> heldWidgets(site, Source.WEBSITE, access));
    }

    private static @NonNull WidgetTree siteWidgets(@NonNull Row site, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        boolean delegated = CmsSupport.isDelegatedPanel(conduit);
        List<WidgetInstance> main = new ArrayList<>();
        // A stream is reached on public ports of its own: where clients connect, never addresses or paths. Each port's
        // row names where it forwards (host:port), as one site may forward many ports to as many ports upstream.
        if (SiteParts.reachOf(site).asksAddress()) {
            main.add(addresses(List.of(site), access));
            addIfPresent(main, protection(List.of(site), access));
        } else {
            Map<String, Object> settings = RawValues.map(site.get(SiteModel.SETTINGS));
            Object host = settings.get(StreamUpstreamKind.FORWARD_HOST.getName());
            List<PublishedPort> forwards = new ArrayList<>();
            for (StreamPort port : StreamUpstreamKind.portsOf(settings)) {
                for (String transport : port.protocol().protocols()) {
                    forwards.add(new PublishedPort(host + ":" + port.forwardPort(), port.listenPort(),
                        port.forwardPort(), transport, false));
                }
            }
            main.add(InstanceOverview.portsCard(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("connect"),
                HohenheimMicrocopy.APP_OVERVIEW.of("stream_connect_hint"),
                InstanceOverview.endpointsOf(SiteModel.MODEL_ID, site.get(SiteModel.ID), forwards,
                    AppHealth.forwarding(site), delegated)));
        }

        List<WidgetInstance> side = new ArrayList<>();
        side.add(details(siteFacts(site, access)));
        if (!delegated) {
            side.add(recent(Models.get(SiteModel.class), site.get(SiteModel.ID), access));
        }
        return compose(List.of(), main, side);
    }

    /**
     * A stack's overview, the app composition over the stack: its Details (the facts every app page leads with) and
     * its Recent activity; a linked server's stack opens it too, drawn from what that server told ({@link
     * #heldWidgets}). Its Services tab stays the landing tab of this server's own stack.
     */
    static @NonNull RecordOverview<Row> stackTab() {
        return RecordOverview.<Row>fields(RecordOverview.SLUG, HohenheimMicrocopy.INSTANCE.of("overview"))
            .withoutFields()
            .widgets(AppOverview::stackWidgets)
            .servesHeld((stack, access) -> heldWidgets(stack, Source.STACK, access));
    }

    private static @NonNull WidgetTree stackWidgets(@NonNull Row stack, @NonNull AccessContext access) {
        Integer hostId = stack.get(StackModel.HOST_ID);
        boolean delegated = CmsSupport.isDelegatedPanel(access.conduit());
        String hostUrl = delegated || hostId == null ? null : CmsRoutes.subpage(CmsSupport.panelSlug(access.conduit()),
            HohenheimSlugs.HOSTS, HostModel.canonicalHostId(hostId), RecordOverview.SLUG).toUrl();
        List<WidgetInstance> side = new ArrayList<>();
        if (!delegated) {
            side.add(recent(Models.get(StackModel.class), stack.get(StackModel.ID), access));
        }
        return compose(List.of(), List.of(details(identity(AppDirectory.cellsOf(Source.STACK, stack, access), hostUrl,
            null, access))), side);
    }

    // -- composition ------------------------------------------------------------------

    /** The overview's grid: an optional full-width row on top, a wide column and a narrow one below it. */
    static @NonNull WidgetTree compose(@NonNull List<WidgetInstance> top, @NonNull List<WidgetInstance> main,
                                       @NonNull List<WidgetInstance> side) {
        List<WidgetInstance> regions = new ArrayList<>();
        if (!top.isEmpty()) {
            regions.add(section("hh-app-top", top));
        }
        regions.add(section("hh-app-main", main));
        regions.add(section("hh-app-side", side));
        return new WidgetTree(List.of(section("hh-app-overview", regions)));
    }

    // -- cards ------------------------------------------------------------------------

    /** Every name these sites answer on, each with whether HTTPS works for it. */
    static @NonNull WidgetInstance addresses(@NonNull List<Row> sites, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        String panelSlug = CmsSupport.panelSlug(conduit);
        Set<String> working = AppHealth.workingNames();
        List<AppAddress> rows = new ArrayList<>();
        for (Row site : sites) {
            boolean passthrough = SiteParts.tlsPassthrough(site);
            boolean first = true;
            for (Row domain : SiteParts.domainsOf(site)) {
                rows.add(address(domain, passthrough, first, working, access));
                first = false;
            }
        }
        Integer siteId = sites.size() == 1 ? sites.get(0).get(SiteModel.ID) : null;
        String addUrl = siteId != null && opens(access, HohenheimSlugs.SITES, siteId, SiteParts.DOMAINS_TAB)
            ? CmsRoutes.subpage(panelSlug, HohenheimSlugs.SITES, siteId, SiteParts.DOMAINS_TAB).toUrl()
            : null;
        return card(HohenheimMicrocopy.APP_OVERVIEW.of("addresses"),
            new WidgetInstance(HohenheimWidgets.APP_ADDRESSES.id(), Map.of()).withData(rows),
            HohenheimMicrocopy.APP_OVERVIEW.of("add_address"), addUrl, "plus");
    }

    private static @NonNull AppAddress address(@NonNull Row domain, boolean passthrough, boolean main,
                                               @NonNull Set<String> working, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        String hostname = String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME));
        Integer domainId = domain.get(SiteDomainModel.ID);
        String url = opens(access, HohenheimSlugs.DOMAINS, domainId, null)
            ? CmsRoutes.detail(CmsSupport.panelSlug(conduit), HohenheimSlugs.DOMAINS, domainId).toUrl() : null;
        List<String> notes = new ArrayList<>();
        if (main) {
            notes.add(text("main_address", locales, resolver));
        }
        if (passthrough) {
            CertCoverage notUsed = CertCoverage.NOT_USED;
            return new AppAddress(hostname, url, notUsed.label(), notUsed.variant(), String.join(" · ", notes));
        }
        if (!AppHealth.exact(domain)) {
            notes.add(text("pattern_note", locales, resolver));
            return new AppAddress(hostname, url, CertCoverage.PATTERN.label(), CertCoverage.PATTERN.variant(),
                String.join(" · ", notes));
        }
        boolean forced = Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
        if (forced) {
            notes.add(text("https_forced", locales, resolver));
        }
        CertCoverage coverage = AppHealth.httpsOf(domain, false, working);
        Row certificate = coverage == CertCoverage.ACTIVE ? CertificateCoverage.coveringCertificate(hostname) : null;
        Instant expires = certificate == null ? null : certificate.get(CertificateModel.EXPIRES_ON);
        if (expires != null) {
            // "Certificate valid until ...", in the one expiry wording the HTTPS cells and the tile use.
            notes.add(HohenheimMicrocopy.APP_OVERVIEW.of("certificate_note")
                .withArg("expiry", CertificateExpiry.inSentence(expires))
                .resolve(locales, resolver));
        }
        return new AppAddress(hostname, url, coverage.label(), coverage.variant(), String.join(" · ", notes));
    }

    /**
     * The protected paths of these sites, and each site's line for the rest of it ({@link #wholeApp}): "Whole app"
     * while the site protects no path, "Everything else" beside the paths that carry their own; null when no site
     * terminates HTTP here (a TLS passthrough site protects nothing).
     */
    static @Nullable WidgetInstance protection(@NonNull List<Row> sites, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        String panelSlug = CmsSupport.panelSlug(conduit);
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        List<Row> served = new ArrayList<>();
        for (Row site : sites) {
            if (SiteParts.servesHttp(site)) {
                served.add(site);
            }
        }
        if (served.isEmpty()) {
            return null;
        }
        boolean prefix = served.size() > 1;
        List<Integer> siteIds = new ArrayList<>();
        for (Row site : served) {
            siteIds.add(site.get(SiteModel.ID));
        }
        // Every served site's paths, their lists and rules: the same reads for one path as for many.
        List<Row> allPaths = Models.get(ProtectedPathModel.class).find()
            .where(ProtectedPathModel.SITE_ID.in(siteIds)).all();
        AccessListRules read = AccessListRules.ofPaths(allPaths);
        Set<String> openPaths = CmsSupport.openable(access, HohenheimSlugs.PROTECTED_PATHS,
            allPaths.stream().map(path -> path.get(ProtectedPathModel.ID)).toList(), null);
        List<AppProtection> rows = new ArrayList<>();
        for (Row site : served) {
            String lead = prefix ? site.get(SiteModel.NAME) + " " : "";
            List<Row> paths = allPaths.stream()
                .filter(path -> Objects.equals(path.get(ProtectedPathModel.SITE_ID), site.get(SiteModel.ID)))
                .toList();
            for (Row path : paths) {
                boolean open = read.isOpen(path);
                Integer listId = path.get(ProtectedPathModel.ACCESS_LIST_ID);
                // How visitors get in, in words, when the list is plain; otherwise the list it follows, by name.
                Microcopy how = open ? null
                    : AccessRuleSummaries.protectionOf(AccessRuleSummaries.wayOf(read.rulesOf(listId)));
                Integer pathId = path.get(ProtectedPathModel.ID);
                rows.add(new AppProtection(lead + path.get(ProtectedPathModel.PATH),
                    openPaths.contains(String.valueOf(pathId))
                        ? CmsRoutes.detail(panelSlug, HohenheimSlugs.PROTECTED_PATHS, pathId).toUrl() : null,
                    (how != null ? how : HohenheimMicrocopy.APP_OVERVIEW.of(open ? "list_admits_everyone" : "by_list")
                        .withArg("list", read.nameOf(listId)))
                        .resolve(locales, resolver),
                    open, true));
            }
            rows.add(wholeApp(site, lead + text(paths.isEmpty() ? "whole_app" : "everything_else", locales,
                resolver), access));
        }
        Integer siteId = served.size() == 1 ? served.get(0).get(SiteModel.ID) : null;
        String protectUrl = siteId != null && opens(access, HohenheimSlugs.SITES, siteId,
            HohenheimSlugs.PROTECTED_PATHS)
            ? CmsRoutes.subpage(panelSlug, HohenheimSlugs.SITES, siteId, HohenheimSlugs.PROTECTED_PATHS).toUrl()
            : null;
        return card(HohenheimMicrocopy.APP_OVERVIEW.of("protection"), protectionRows(rows),
            HohenheimMicrocopy.APP_OVERVIEW.of("protect_path"), protectUrl, "lock");
    }

    /**
     * The Protection tab's band ({@code ChildList.widgets}): the Whole app card over the site's {@link #wholeApp}
     * line, above the paths that carry their own protection.
     *
     * AIDEV-NOTE: the board draws two header doors, "Edit who gets in" and "Change"; a framework card carries one
     * ({@link CardWidget#withLink}). While a list guards the app, the header's door is its rules (for a viewer who may
     * open them, else none) and "Change" is a quiet link on the row beside its badge; without one, "Change" is the
     * header's door. Change is offered to a viewer
     * who may change which list it is ({@link #changesWholeApp}) and leads to the site's form, the one writer of that
     * field: zenit-cms has no plain-link address that opens a placed operation's input in a dialog, so a one-field
     * dialog cannot be linked from a widget yet.
     */
    static @NonNull WidgetTree protectionBand(@NonNull Row site, @NonNull AccessContext access) {
        Integer listId = site.get(SiteModel.ACCESS_LIST_ID);
        String rules = listId == null ? null : rulesUrl(access, listId);
        String change = changesWholeApp(site, access)
            ? CmsRoutes.detail(CmsSupport.panelSlug(access.conduit()), HohenheimSlugs.SITES, site.get(SiteModel.ID))
                .toUrl()
            : null;
        AppProtection line = wholeApp(site, null, access);
        // CardWidget.ofFacts' title-and-lead shape, around the overview's own protection rows.
        WidgetInstance card = new WidgetInstance(CardWidget.ID, Map.of(
                "title", HohenheimMicrocopy.APP_OVERVIEW.of("whole_app"),
                "lead", HohenheimMicrocopy.APP_OVERVIEW.of("whole_app_lead")),
            new WidgetTree(List.of(protectionRows(List.of(listId != null ? line.withChange(change) : line)))));
        if (rules != null) {
            card = CardWidget.withLink(card, HohenheimMicrocopy.APP_OVERVIEW.of("edit_who_gets_in"), rules, "key");
        } else if (listId == null && change != null) {
            card = CardWidget.withLink(card, HohenheimMicrocopy.APP_OVERVIEW.of("change_whole_app"), change, "sliders");
        }
        return new WidgetTree(List.of(card));
    }

    /**
     * THE whole-app line of one site, on its overview's Protection card and atop its Protection tab: the access list
     * every visitor passes on every address, linked to that list's rules for a viewer who may open them, with how it
     * lets visitors in; "Open to everyone" without one. A list that lets everyone through reads as open, never as
     * protected, by the gate's own reading ({@link ProtectedPathInvariant#listAdmitsEveryone}).
     *
     * @param label the line's own words ("Whole app" on the overview), null to lead with the list's name (the tab)
     */
    static @NonNull AppProtection wholeApp(@NonNull Row site, @Nullable String label, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        Integer listId = site.get(SiteModel.ACCESS_LIST_ID);
        if (listId == null) {
            return new AppProtection(label != null ? label
                : HohenheimMicrocopy.PROTECTED_PATH.of("open_to_everyone").resolve(locales, resolver), null,
                text("whole_app_anyone", locales, resolver), false, false);
        }
        boolean open = ProtectedPathInvariant.listAdmitsEveryone(listId);
        Microcopy list = HohenheimMicrocopy.APP_OVERVIEW.of("by_list").withArg("list", listName(listId));
        Microcopy letsIn = AccessRuleSummaries.letsInOf(listId);
        String url = rulesUrl(access, listId);
        if (label == null) {
            return new AppProtection(list.resolve(locales, resolver), url, letsIn.resolve(locales, resolver), open,
                true);
        }
        Microcopy summary = open
            ? HohenheimMicrocopy.APP_OVERVIEW.of("list_admits_everyone").withArg("list", listName(listId))
            : HohenheimMicrocopy.APP_OVERVIEW.of("whole_app_list").withArg("list", list).withArg("lets_in", letsIn);
        return new AppProtection(label, url, summary.resolve(locales, resolver), open, true);
    }

    /** The protection rows as the overview's Protection widget draws them. */
    private static @NonNull WidgetInstance protectionRows(@NonNull List<AppProtection> rows) {
        return new WidgetInstance(HohenheimWidgets.APP_PROTECTION.id(), Map.of()).withData(rows);
    }

    /** @return the list's Rules tab in this panel, null for a viewer who may not open it ({@link CmsSupport#opens}) */
    private static @Nullable String rulesUrl(@NonNull AccessContext access, @NonNull Integer listId) {
        return opens(access, HohenheimSlugs.ACCESS_LISTS, listId, HohenheimSlugs.Tab.RULES)
            ? CmsRoutes.subpage(CmsSupport.panelSlug(access.conduit()), HohenheimSlugs.ACCESS_LISTS, listId,
                HohenheimSlugs.Tab.RULES).toUrl()
            : null;
    }

    /**
     * Whether this viewer may change which list guards the whole site: this panel's site form edits that field for
     * them and they may update the site. The delegated form carries no such field, so /manage never offers it.
     */
    private static boolean changesWholeApp(@NonNull Row site, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        Panel panel = conduit == null ? null : PanelRegistry.getBySlug(CmsSupport.panelSlug(conduit));
        PanelResource<Row> entry = panel == null ? null : CmsSupport.declaredRowEntry(panel, HohenheimSlugs.SITES);
        String field = SiteModel.ACCESS_LIST_ID.getName();
        return entry != null && panel.admits(entry, access)
            && PartsForms.formSpec(entry).findEntry(field) != null
            && PartsForms.editableAlong(entry, field, access, site)
            && ResourceVerbs.allows(panel, entry, ResourceVerb.UPDATE, site, access);
    }

    /** The workload's measured resources, each the framework's usage gauge, so NOT MEASURED stays an answer. */
    static @NonNull WidgetInstance resources(@NonNull List<WidgetInstance> gauges) {
        return CardWidget.of(HohenheimMicrocopy.APP_OVERVIEW.of("resources"), new WidgetTree(gauges));
    }

    /** One resource under its label, for {@link #resources}; without usage it is a gauge a live element fills. */
    static @NonNull WidgetInstance gauge(@NonNull Microcopy label, @Nullable UsageData usage) {
        WidgetInstance gauge = new WidgetInstance(UsageBarWidget.ID, Map.of("label", label));
        return usage == null ? gauge : gauge.withData(usage);
    }

    static @NonNull WidgetInstance details(@NonNull List<WidgetFact> facts) {
        return CardWidget.ofFacts(HohenheimMicrocopy.APP_OVERVIEW.of("details"), facts);
    }

    /**
     * The record's latest activity: what people did to it or filed on it ({@link AdminActivityResource#recentFor});
     * operator-only by omission (see InstanceOverview's note on the audit log's audience).
     */
    static @NonNull WidgetInstance recent(@NonNull Model model, @NonNull Integer id, @NonNull AccessContext access) {
        return CardWidget.of(HohenheimMicrocopy.APP_OVERVIEW.of("recent"), new WidgetTree(List.of(ItemListWidget.of(
            AdminActivityResource.recentFor(model, id, ActivitySources.recent(), access, 6), null))));
    }

    /**
     * A titled framework card around one widget, with its header link (Add address, Protect a path) only when the
     * caller resolved a url: a reader who may not follow it ({@link CmsSupport#opens}) is not shown a door that
     * refuses.
     */
    private static @NonNull WidgetInstance card(@NonNull Microcopy title, @NonNull WidgetInstance body,
                                                @NonNull Microcopy linkLabel, @Nullable String linkUrl,
                                                @NonNull String linkIcon) {
        return CardWidget.withLink(CardWidget.of(title, new WidgetTree(List.of(body))), linkLabel, linkUrl,
            linkIcon);
    }

    /** The lead line under a site's heading: the app it serves, as the board names it, else what it serves. */
    static @NonNull String siteLead(@NonNull Row site, @NonNull Conduit conduit) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null) {
            return SiteParts.upstreamLabel(site).asString(conduit);
        }
        return HohenheimMicrocopy.APP_OVERVIEW.of("serves_app")
            .withArg("app", Models.get(InstanceModel.class).getDisplayTitle(instance)).asString(conduit);
    }

    /** The lead line under an instance's heading: what it runs and (for the operator) where. */
    static @NonNull String instanceLead(@NonNull Row instance, @NonNull Conduit conduit) {
        Object kind = instance.get(InstanceModel.KIND);
        String what = kind == null ? ""
            : WidgetBadge.of(InstanceModel.KIND, kind, conduit.getLocales(), conduit.getMessageResolver()).label();
        if (CmsSupport.isDelegatedPanel(conduit)) {
            return what;
        }
        String where = HohenheimMicrocopy.APP_OVERVIEW.of("on_host").withArg("host",
                HostModel.canonicalNameOf(instance.get(InstanceModel.HOST_ID)))
            .asString(conduit);
        return what.isEmpty() ? where : what + " · " + where;
    }

    // -- site facts -------------------------------------------------------------------

    private static @NonNull List<WidgetFact> siteFacts(@NonNull Row site, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        String panelSlug = CmsSupport.panelSlug(conduit);
        List<WidgetFact> facts = new ArrayList<>(identity(AppDirectory.cellsOf(AppDirectory.Source.WEBSITE, site,
            access), null, null, access));
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        Row instance = Models.get(InstanceModel.class).findById(instanceId);
        if (instance != null) {
            String workload = Models.get(InstanceModel.class).getDisplayTitle(instance);
            facts.add(opens(access, HohenheimSlugs.INSTANCES, instanceId, null)
                ? WidgetFact.link(text("workload", locales, resolver), workload,
                    InstanceParts.recordRoute(panelSlug, instance, null).toUrl())
                : WidgetFact.of(text("workload", locales, resolver), workload));
        }
        Instant created = site.get(SiteModel.CREATED_AT);
        if (created != null) {
            facts.add(WidgetFact.instant(text("created", locales, resolver), created.toString()));
        }
        return facts;
    }

    // -- the facts every app page leads with ------------------------------------------

    /**
     * The Details lines every app page leads with, drawn from the app's facts alone: its state in its verdict's one
     * word, its kind, the host it runs on, the linked server it lives on, and since when what runs started.
     *
     * @param hostUrl the host's page, null where the reader opens none here (a linked server's host, a tenant)
     * @param server  the name of the linked server it lives on, null for this server's own
     */
    static @NonNull List<WidgetFact> identity(AppDirectory.@NonNull Cells cells, @Nullable String hostUrl,
                                              @Nullable String server, @NonNull AccessContext access) {
        List<WidgetFact> facts = new ArrayList<>();
        // The verdict's one word ("Working"), never its headline: the band above says the sentence ("Live at
        // https://..."), which as a pill outgrew this narrow card. An unknown tone has no word of its own, so its
        // short headline ("Not deployed yet") speaks, agreeing with the band (AppParts.stateWord).
        HealthCellState verdict = HealthCellState.of(cells.health(), access);
        facts.add(WidgetFact.badge(access.text(HohenheimMicrocopy.APP_OVERVIEW.of("state")),
            new WidgetBadge(access.text(AppParts.stateWord(cells.health())), verdict.variant(), null, verdict.icon(),
                true)));
        if (!cells.kind().isEmpty()) {
            facts.add(WidgetFact.of(access.text(HohenheimMicrocopy.APP_OVERVIEW.of("kind")), cells.kind()));
        }
        if (cells.host() != null) {
            String label = access.text(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("host"));
            facts.add(hostUrl != null ? WidgetFact.link(label, cells.host(), hostUrl)
                : WidgetFact.of(label, cells.host()));
        }
        if (server != null) {
            facts.add(WidgetFact.of(access.text(HohenheimMicrocopy.APP_LIST.of("server_column")), server));
        }
        // How long it has run ("Running for 2 hours"): since the last start of what runs, null while it does not.
        if (cells.started() != null) {
            facts.add(WidgetFact.instant(access.text(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("started")),
                cells.started().toString()));
        }
        return facts;
    }

    /**
     * What a linked server's app made of a record the master keeps of it draws on that record's own page: the same
     * composition as a local app's, drawn from what that server told alone ({@link AppStates}), never this server's
     * process read under the kept record's id, which names another server's record.
     *
     * @param kept   the kept row of the app's record (a workload's instance, a website's site)
     * @param source the kind of record it is
     */
    static @NonNull WidgetTree heldWidgets(@NonNull Row kept, @NonNull Source source, @NonNull AccessContext access) {
        KeptRow row = (KeptRow) kept;
        HeldRecords.Holder server = Objects.requireNonNull(ClusterLists.holderOf(row), "a held app's server");
        AppFacts facts = AppStates.told(server, source.token() + "-" + row.key());
        List<KeptRow> domains = keptDomains(row, source);
        if (server.silent()) {
            return remembered(facts, domains, server, access);
        }
        Wording words = Wording.of(access);
        HeldApp app = facts == null ? HeldApp.unreadable(row.key(), row.title(), server, words)
            : HeldApp.of(facts, server, words);
        return heldWidgets(app, domains, facts == null ? null : facts.open(), access);
    }

    /**
     * @return the addresses a linked server keeps of its app, as its record shows them: a website's own, the ones of
     *         every site serving a workload, none of a stack; read with the kept rows of the request
     */
    private static @NonNull List<KeptRow> keptDomains(@NonNull KeptRow row, @NonNull Source source) {
        List<KeptRow> sites = switch (source) {
            case WEBSITE -> List.of(row);
            case WORKLOAD -> ClusterLists.grouped(Models.get(SiteModel.class), SiteModel.MODEL_ID,
                SiteModel.INSTANCE_ID.getName()).getOrDefault(ClusterLists.sibling(row, row.key()), List.of());
            case STACK -> List.of();
        };
        List<KeptRow> domains = new ArrayList<>();
        for (KeptRow site : sites) {
            for (Row domain : SiteParts.keptDomainsOf(site)) {
                domains.add((KeptRow) domain);
            }
        }
        return domains;
    }

    /**
     * @return the verdict a linked server's app made of a kept record leads its page with, as that server told it;
     *         while that server does not answer, the page's one saying so ({@link #silentBand})
     */
    static @NonNull RecordHealth heldHealth(@NonNull Row kept, @NonNull Source source) {
        HeldRecords.Holder server = ClusterLists.holderOf(kept);
        return server != null && server.silent() ? silentBand(server)
            : toldApp(kept, source, Wording.of(AccessContext.anonymous())).cells().health();
    }

    /**
     * The band of a silent server's thing's page, the one place it says so (board FO119, "App page while its server
     * does not answer"): since when it does not answer and that nothing can be changed, with Try again, the Servers
     * list's own re-check of that server's link (LinkedServerParts.CHECK) offered here.
     */
    static @NonNull RecordHealth silentBand(HeldRecords.@NonNull Holder server) {
        Instant lastSeen = server.lastSeen();
        Microcopy since = lastSeen == null
            ? HohenheimMicrocopy.HELD_APP.of("held_app_silent_never").withArg("server", server.name())
            : HohenheimMicrocopy.HELD_APP.of("held_app_silent_since").withArg("server", server.name())
                .withArg("since", lastSeen)
                .withArg("day", AppDirectory.recentDay(AccessContext.anonymous().getReader(), lastSeen));
        return RecordHealth.silent(since)
            .detail(HohenheimMicrocopy.HELD_APP.of("held_app_silent_body").withArg("server", server.name())
                .withArg("master", LinkedServers.ownName()))
            .fixedBy(LinkedServerParts.CHECK.id()).on(HohenheimSlugs.SERVERS, server.key());
    }

    /**
     * What this server remembers of a silent server's app, as one card (board FO119): what it is, its addresses, its
     * host and its state when its server last answered, all as that server last told them.
     */
    private static @NonNull WidgetTree remembered(@Nullable AppFacts facts, @NonNull List<KeptRow> domains,
                                                  HeldRecords.@NonNull Holder server, @NonNull AccessContext access) {
        List<WidgetFact> lines = new ArrayList<>();
        if (facts != null) {
            lines.add(WidgetFact.of(access.text(HohenheimMicrocopy.APP_OVERVIEW.of("kind")),
                access.text(facts.kind())));
            List<String> names = new ArrayList<>();
            for (KeptRow domain : domains) {
                names.add(String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME)));
            }
            if (names.isEmpty() && facts.address() != null && facts.address().primary() != null) {
                names.add(facts.address().primary());
            }
            if (!names.isEmpty()) {
                lines.add(WidgetFact.of(access.text(HohenheimMicrocopy.APP_OVERVIEW.of("addresses")),
                    String.join(", ", names)));
            }
            if (facts.host() != null) {
                lines.add(WidgetFact.of(access.text(HohenheimMicrocopy.INSTANCE_OVERVIEW.of("host")), facts.host()));
            }
            if (server.lastSeen() != null) {
                lines.add(WidgetFact.of(access.text(HohenheimMicrocopy.HELD_APP.of("held_app_last_state")),
                    access.text(HohenheimMicrocopy.HELD_APP.of("held_app_state_at")
                        .withArg("state", AppParts.stateWord(facts.health())).withArg("at", server.lastSeen())
                        .withArg("day", AppDirectory.recentDay(access.getReader(), server.lastSeen())))));
            }
        }
        return compose(List.of(), List.of(CardWidget.ofFacts(HohenheimMicrocopy.HELD_APP.of("held_app_remembers")
            .withArg("master", LinkedServers.ownName()), lines)), List.of());
    }

    /**
     * @return the app a kept record makes, as its server last told it; one whose state that server did not tell (not
     *         an app of its own, or not told yet) or this build cannot read is the unreadable app under its kept name
     */
    private static @NonNull HeldApp toldApp(@NonNull Row kept, @NonNull Source source, @NonNull Wording words) {
        KeptRow row = (KeptRow) kept;
        HeldRecords.Holder server = Objects.requireNonNull(ClusterLists.holderOf(row), "a held app's server");
        HeldApp told = AppStates.held(row.peer() + "-" + source.token() + "-" + row.key(), words);
        return told != null ? told : HeldApp.unreadable(row.key(), row.title(), server, words);
    }

    /**
     * A linked server's app's composition: the waiting tabs said in words, its Addresses card as its server keeps its
     * addresses (with what HTTPS gives each, as told) and Open site where that server says visitors reach it, and its
     * Details. An app its server keeps no address of (a stack, an app not online yet) draws no Addresses card: its
     * empty card here could only ask for an address this server cannot add.
     *
     * @param open where its Open site goes, as its server told it; null for none
     */
    private static @NonNull WidgetTree heldWidgets(@NonNull HeldApp app, @NonNull List<KeptRow> domains,
                                                   @Nullable String open, @NonNull AccessContext access) {
        Cells cells = app.cells();
        String server = app.held().name();
        List<WidgetInstance> top = new ArrayList<>();
        top.add(notice(AlertVariant.INFO, access.text(HohenheimMicrocopy.HELD_APP.of("held_app_waiting_tabs_title")),
            access.text(HohenheimMicrocopy.HELD_APP.of("held_app_waiting_tabs").withArg("server", server))));
        List<WidgetInstance> main = new ArrayList<>();
        if (!domains.isEmpty()) {
            List<AppAddress> rows = new ArrayList<>();
            for (KeptRow domain : domains) {
                rows.add(heldAddress(domain, rows.isEmpty(), app.held(), access));
            }
            main.add(card(HohenheimMicrocopy.APP_OVERVIEW.of("addresses"),
                new WidgetInstance(HohenheimWidgets.APP_ADDRESSES.id(), Map.of()).withData(rows),
                HohenheimMicrocopy.APP_OVERVIEW.of("open_site"), open, "up-right-from-square"));
        }
        return compose(top, main, List.of(details(identity(cells, null, server, access))));
    }

    /**
     * One address a linked server keeps of its app, as {@link #address} draws one of this server's: its HTTPS state as
     * that server told it (an exact name that told none reads as not used), a pattern as a pattern, its notes; no
     * link, since the address's own page is that server's.
     */
    private static @NonNull AppAddress heldAddress(@NonNull KeptRow domain, boolean main,
                                                   HeldRecords.@NonNull Holder server, @NonNull AccessContext access) {
        LocaleChain locales = access.conduit().getLocales();
        MessageResolver resolver = access.conduit().getMessageResolver();
        String hostname = String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME));
        List<String> notes = new ArrayList<>();
        if (main) {
            notes.add(text("main_address", locales, resolver));
        }
        if (!AppHealth.exact(domain)) {
            notes.add(text("pattern_note", locales, resolver));
            return new AppAddress(hostname, null, CertCoverage.PATTERN.label(), CertCoverage.PATTERN.variant(),
                String.join(" · ", notes));
        }
        if (Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL))) {
            notes.add(text("https_forced", locales, resolver));
        }
        StateLineCell https = DomainParts.told(domain, server, "https");
        return https == null || https.label() == null
            ? new AppAddress(hostname, null, CertCoverage.NOT_USED.label(), CertCoverage.NOT_USED.variant(),
                String.join(" · ", notes))
            : new AppAddress(hostname, null, https.label(), https.variant(), String.join(" · ", notes));
    }

    private static @NonNull WidgetInstance notice(@NonNull AlertVariant variant, @NonNull String title,
                                                  @NonNull String body) {
        return new WidgetInstance(AlertWidget.ID, Map.of("variant", variant.token())).withData(NoticeData.of(title,
            body));
    }
    // -- helpers ----------------------------------------------------------------------

    private static @NonNull String listName(@Nullable Integer accessListId) {
        Row list = Models.get(AccessListModel.class).findById(accessListId);
        return list == null ? "#" + accessListId : String.valueOf((Object) list.get(AccessListModel.NAME));
    }

    private static void addIfPresent(@NonNull List<WidgetInstance> widgets, @Nullable WidgetInstance widget) {
        if (widget != null) {
            widgets.add(widget);
        }
    }

    private static @NonNull WidgetInstance section(@NonNull String cssClass, @NonNull List<WidgetInstance> children) {
        return new WidgetInstance(SectionWidget.ID, Map.of("css_class", cssClass), new WidgetTree(children));
    }

    static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                @Nullable MessageResolver resolver) {
        return HohenheimMicrocopy.APP_OVERVIEW.of(key).resolve(locales, resolver);
    }
}
