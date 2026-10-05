package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.CertCoverage;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.app.AppAddress;
import be.elevenways.hohenheim.app.AppProtection;
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ProtectedPathModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.tls.CertificateCoverage;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.MessageResolver;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.resource.RecordOverview;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.activity.ActivityModel;
import be.elevenways.zenit.common.orm.activity.ActivityRules;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.BadgeVariant;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.CardWidget;
import be.elevenways.zenit.widget.common.builtin.FactListWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.UsageBarWidget;
import be.elevenways.zenit.widget.common.data.UsageData;
import be.elevenways.zenit.widget.common.data.WidgetBadge;
import be.elevenways.zenit.widget.common.data.WidgetFact;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ONE composition of an app's overview, drawn by both of its record pages (an instance's, and a site's when it
 * serves no workload of its own): Addresses, Protection and what the workload uses in the wide column, Details and
 * Recent in the narrow one, each a framework card ({@link CardWidget}). The record's actions are the record heading's
 * (zenitcms:record-head) and the verdict is the resource's health part ({@link AppHealth}), the page's first band.
 *
 * AIDEV-NOTE: every card READS the facts other surfaces own (certificate coverage, the protected-path invariant, the
 * activity source), so the overview, the list columns and the health band say the same thing. The delegated panel
 * draws the same composition; the host and the audit log are never added there (see InstanceOverview).
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class AppOverview {

    static final String SLUG = RecordOverview.SLUG;

    private AppOverview() {
    }

    /** The site's front door: the app composition over the site alone. */
    static @NonNull RecordOverview<Row> siteTab() {
        return RecordOverview.<Row>fields(SLUG, Microcopy.of("overview").withFilter("scope", "instance"))
            .withoutFields()
            .widgets(AppOverview::siteWidgets);
    }

    private static @NonNull WidgetTree siteWidgets(@NonNull Row site, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        boolean delegated = CmsSupport.isDelegatedPanel(conduit);
        List<WidgetInstance> main = new ArrayList<>();
        main.add(addresses(List.of(site), access));
        addIfPresent(main, protection(List.of(site), access));

        List<WidgetInstance> side = new ArrayList<>();
        side.add(details(siteFacts(site, conduit)));
        if (!delegated) {
            side.add(recent(Models.get(SiteModel.class), site.get(SiteModel.ID)));
        }
        return compose(List.of(), main, side);
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
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        Set<String> working = CertificateCoverage.activeNames();
        List<AppAddress> rows = new ArrayList<>();
        for (Row site : sites) {
            boolean passthrough = SiteParts.tlsPassthrough(site);
            boolean first = true;
            for (Row domain : SiteParts.domainsOf(site)) {
                rows.add(address(domain, passthrough, first, working, panelSlug, locales, resolver));
                first = false;
            }
        }
        String addUrl = sites.size() == 1
            ? CmsRoutes.subpage(panelSlug, HohenheimSlugs.SITES, sites.get(0).get(SiteModel.ID),
                SiteParts.DOMAINS_TAB).toUrl()
            : null;
        return card(copy("addresses"), new WidgetInstance(HohenheimWidgets.APP_ADDRESSES.id(), Map.of()).withData(rows),
            copy("add_address"), addUrl, "plus");
    }

    private static @NonNull AppAddress address(@NonNull Row domain, boolean passthrough, boolean main,
                                               @NonNull Set<String> working, @NonNull String panelSlug,
                                               @NonNull LocaleChain locales, @Nullable MessageResolver resolver) {
        String hostname = String.valueOf((Object) domain.get(SiteDomainModel.HOSTNAME));
        String url = CmsRoutes.detail(panelSlug, DomainParts.SLUG, domain.get(SiteDomainModel.ID)).toUrl();
        List<String> notes = new ArrayList<>();
        if (main) {
            notes.add(text("main_address", locales, resolver));
        }
        if (passthrough) {
            CertCoverage notUsed = CertCoverage.NOT_USED;
            return new AppAddress(hostname, url, notUsed.label(), notUsed.badgeVariant(), String.join(" · ", notes));
        }
        if (!AppHealth.exact(domain)) {
            notes.add(text("pattern_note", locales, resolver));
            return new AppAddress(hostname, url, copy("pattern"), BadgeVariant.OUTLINE, String.join(" · ", notes));
        }
        boolean forced = Boolean.TRUE.equals(domain.get(SiteDomainModel.FORCE_SSL));
        if (forced) {
            notes.add(text("https_forced", locales, resolver));
        }
        Row cert = CertificateCoverage.coveringCertificate(hostname);
        CertCoverage coverage = CertificateCoverage.covers(working, hostname) ? CertCoverage.ACTIVE
            : CertCoverage.ofCertificateStatus(cert == null ? null : cert.get(CertificateModel.STATUS));
        // A forced name no working certificate covers is the error page visitors get: always the red state.
        if (forced && coverage != CertCoverage.ACTIVE) {
            coverage = CertCoverage.ERROR;
        }
        return new AppAddress(hostname, url, coverage.label(), coverage.badgeVariant(), String.join(" · ", notes));
    }

    /**
     * The protected paths of these sites, and the line for everything else; null when no site terminates HTTP here
     * (a TLS passthrough site protects nothing).
     */
    static @Nullable WidgetInstance protection(@NonNull List<Row> sites, @NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        String panelSlug = CmsSupport.panelSlug(conduit);
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        List<Row> served = new ArrayList<>();
        for (Row site : sites) {
            if (!SiteParts.tlsPassthrough(site)) {
                served.add(site);
            }
        }
        if (served.isEmpty()) {
            return null;
        }
        boolean prefix = served.size() > 1;
        List<AppProtection> rows = new ArrayList<>();
        for (Row site : served) {
            String lead = prefix ? site.get(SiteModel.NAME) + " " : "";
            for (Row path : Models.get(ProtectedPathModel.class).find()
                    .where(ProtectedPathModel.SITE_ID.eq(site.get(SiteModel.ID))).all()) {
                boolean open = ProtectedPathInvariant.isOpen(path);
                String list = listName(path.get(ProtectedPathModel.ACCESS_LIST_ID));
                rows.add(new AppProtection(lead + path.get(ProtectedPathModel.PATH),
                    CmsRoutes.detail(panelSlug, ProtectedPathParts.SLUG, path.get(ProtectedPathModel.ID)).toUrl(),
                    Microcopy.of(open ? "list_admits_everyone" : "by_list").withFilter("scope", "app_overview")
                        .withArg("list", list).resolve(locales, resolver),
                    open, true));
            }
            Integer siteList = site.get(SiteModel.ACCESS_LIST_ID);
            rows.add(new AppProtection(lead + text("everything_else", locales, resolver), null,
                siteList == null ? text("public", locales, resolver)
                    : Microcopy.of("by_list").withFilter("scope", "app_overview")
                        .withArg("list", listName(siteList)).resolve(locales, resolver),
                false, siteList != null));
        }
        String protectUrl = served.size() == 1
            ? CmsRoutes.subpage(panelSlug, HohenheimSlugs.SITES, served.get(0).get(SiteModel.ID),
                ProtectedPathParts.SLUG).toUrl()
            : null;
        return card(copy("protection"), new WidgetInstance(HohenheimWidgets.APP_PROTECTION.id(), Map.of()).withData(rows),
            copy("protect_path"), protectUrl, "lock");
    }

    /** The workload's measured resources, each the framework's usage gauge, so NOT MEASURED stays an answer. */
    static @NonNull WidgetInstance resources(@NonNull List<WidgetInstance> gauges) {
        return CardWidget.of(copy("resources"), new WidgetTree(gauges));
    }

    /** One measured resource under its label, for {@link #resources}. */
    static @NonNull WidgetInstance gauge(@NonNull Microcopy label, @NonNull UsageData usage) {
        return new WidgetInstance(UsageBarWidget.ID, Map.of("label", label)).withData(usage);
    }

    static @NonNull WidgetInstance details(@NonNull List<WidgetFact> facts) {
        return CardWidget.of(copy("details"),
            new WidgetTree(List.of(new WidgetInstance(FactListWidget.ID, Map.of()).withData(facts))));
    }

    /**
     * The record's latest activity through the shared activity source; operator-only by omission (see
     * InstanceOverview's note on the audit log's audience).
     */
    static @NonNull WidgetInstance recent(@NonNull Model model, @NonNull Integer id) {
        return CardWidget.of(copy("recent"), new WidgetTree(List.of(new WidgetInstance(RecordsWidget.ID, Map.of(
            "source", CmsSupport.ACTIVITY_SOURCE,
            "rules", ActivityRules.forRecord(model, id),
            "sort", ActivityModel.CREATED_AT.getName(),
            "descending", true,
            "limit", 6)))));
    }

    /**
     * A titled framework card around one widget, with its header link (Add address, Protect a path) only when the
     * caller resolved a url: a reader who may not follow it is not shown a door that refuses.
     */
    private static @NonNull WidgetInstance card(@NonNull Microcopy title, @NonNull WidgetInstance body,
                                                @NonNull Microcopy linkLabel, @Nullable String linkUrl,
                                                @NonNull String linkIcon) {
        return CardWidget.withLink(CardWidget.of(title, new WidgetTree(List.of(body))), linkLabel, linkUrl,
            linkIcon);
    }

    /** The lead line under a site's heading: what it serves. */
    static @NonNull String siteLead(@NonNull Row site, @NonNull Conduit conduit) {
        String kind = upstreamLabel(site).resolve(conduit.getLocales(), conduit.getMessageResolver());
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        Row instance = instanceId == null ? null : Models.get(InstanceModel.class).findById(instanceId);
        if (instance == null) {
            return kind;
        }
        return kind + " · " + Models.get(InstanceModel.class).getDisplayTitle(instance);
    }

    /** The lead line under an instance's heading: what it runs and (for the operator) where. */
    static @NonNull String instanceLead(@NonNull Row instance, @NonNull Conduit conduit) {
        Object kind = instance.get(InstanceModel.KIND);
        String what = kind == null ? ""
            : WidgetBadge.of(InstanceModel.KIND, kind, conduit.getLocales(), conduit.getMessageResolver()).label();
        if (CmsSupport.isDelegatedPanel(conduit)) {
            return what;
        }
        String where = copy("on_host").withArg("host",
                ServerModel.nameOf(ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID))))
            .resolve(conduit.getLocales(), conduit.getMessageResolver());
        return what.isEmpty() ? where : what + " · " + where;
    }

    // -- site facts -------------------------------------------------------------------

    private static @NonNull List<WidgetFact> siteFacts(@NonNull Row site, @NonNull Conduit conduit) {
        LocaleChain locales = conduit.getLocales();
        MessageResolver resolver = conduit.getMessageResolver();
        String panelSlug = CmsSupport.panelSlug(conduit);
        List<WidgetFact> facts = new ArrayList<>();
        facts.add(WidgetFact.of(text("state", locales, resolver),
            text(Boolean.TRUE.equals(site.get(SiteModel.ENABLED)) ? "switched_on" : "switched_off", locales,
                resolver)));
        facts.add(WidgetFact.of(text("kind", locales, resolver),
            upstreamLabel(site).resolve(locales, resolver)));
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        Row instance = instanceId == null ? null : Models.get(InstanceModel.class).findById(instanceId);
        if (instance != null) {
            facts.add(WidgetFact.link(text("workload", locales, resolver),
                Models.get(InstanceModel.class).getDisplayTitle(instance),
                CmsRoutes.detail(panelSlug, HohenheimSlugs.INSTANCES, instanceId).toUrl()));
        }
        Instant created = site.get(SiteModel.CREATED_AT);
        if (created != null) {
            facts.add(WidgetFact.instant(text("created", locales, resolver), created.toString()));
        }
        return facts;
    }

    /** What the site's upstream is, in the words its list cell uses. */
    private static @NonNull Microcopy upstreamLabel(@NonNull Row site) {
        return SiteParts.upstreamCellOf(site).kindLabel();
    }

    // -- helpers ----------------------------------------------------------------------

    private static @NonNull String listName(@Nullable Integer accessListId) {
        Row list = accessListId == null ? null : Models.get(AccessListModel.class).findById(accessListId);
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

    static @NonNull Microcopy copy(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "app_overview");
    }

    static @NonNull String text(@NonNull String key, @NonNull LocaleChain locales,
                                @Nullable MessageResolver resolver) {
        return copy(key).resolve(locales, resolver);
    }
}
