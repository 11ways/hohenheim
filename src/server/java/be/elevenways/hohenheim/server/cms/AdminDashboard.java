package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.protoblast.common.http.Uri;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.action.ActionStyle;
import be.elevenways.zenit.cms.common.render.action.LinkActionState;
import be.elevenways.hohenheim.AttentionItem;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimWidgets;
import be.elevenways.hohenheim.OnboardingStep;
import be.elevenways.hohenheim.app.AppSummary;
import be.elevenways.hohenheim.app.DashboardStat;
import be.elevenways.hohenheim.app.AppsBand;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelDashboard;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.HealthCellState;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.ColumnSplit;
import be.elevenways.zenit.widget.common.builtin.ColumnsWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /admin landing dashboard (board Main): the readiness checklist until the first app is online, what needs
 * attention, the count tiles, the apps and the most recent activity-log entries.
 */
public final class AdminDashboard extends PanelDashboard {

    /** The dashboard is the OPERATOR surface; every tile links into the admin panel. */
    private static final String ADMIN = HohenheimSlugs.ADMIN;

    @Override public @NonNull Identifier id() { return HohenheimIds.id("dashboard"); }
    @Override public @NonNull Microcopy label() { return Microcopy.of("dashboard").withFilter("scope", "admin"); }
    @Override public @NonNull String slug() { return "dashboard"; }
    @Override public @NonNull Icon icon() { return Icon.LAYOUT_DASH; }
    @Override public int navOrder() { return 1; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "admin");
    }
    /** The board's primary way onward, offered where the instance tier can put something online. */
    @Override
    public @NonNull List<LinkActionState> headerLinks(@NonNull PanelRequest request) {
        return putOnlineLinks(request, HohenheimAccess.isAdmin(request.access()));
    }

    /**
     * Both dashboards' primary way onward: Put something online on this panel, where the instance tier exists.
     *
     * @param offered whether this viewer may start something there
     */
    static @NonNull List<LinkActionState> putOnlineLinks(@NonNull PanelRequest request, boolean offered) {
        if (!HohenheimRoles.enabled(Role.INSTANCES) || !offered) {
            return List.of();
        }
        return List.of(new LinkActionState(HohenheimIds.id("dashboard_put_online"), PutOnline.copy("put_online"),
            Icon.of("rocket"), ActionStyle.PRIMARY,
            new Uri(CmsRoutes.list(request.panelSlug(), PutOnlinePage.SLUG).toUrl()), false, null));
    }

    /** Role-gated bands: a tile must not link to a resource this install has no route for. */
    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        List<WidgetInstance> widgets = new ArrayList<>();
        Panel admin = PanelRegistry.getBySlug(ADMIN);
        List<AppDirectory.App> apps = admin == null ? List.of() : AppDirectory.read(admin, accessContext);

        // The readiness checklist RETIRES ITSELF: no dismissed flag, it is simply absent once the first app is online
        // (or every step is done). Before it, nothing said a host must be checked and admitted before anything can
        // run, so the first session's natural arc (create -> deploy -> silence) had no visible way forward. It and the
        // attention band are read together, so a problem an open step presents, or a root already holds back, is
        // drawn once; a retired checklist presents nothing, so what its steps stood for is the band's from then on.
        DashboardAttention.Reading reading = DashboardAttention.read();
        List<OnboardingStep> onboarding = reading.checklist();
        List<AttentionItem> attention = reading.attention();
        WidgetInstance checklist = OnboardingCollector.hasWork(onboarding)
            ? new WidgetInstance(HohenheimWidgets.ONBOARDING_CHECKLIST.id(), Map.of()).withData(onboarding)
            : null;

        if (apps.isEmpty()) {
            // A fresh install (board Empty-Dashboard): the way onward beside the steps still to take, and nothing that
            // counts or lists what does not exist yet.
            // AIDEV-NOTE: the attention band still shows whenever it holds items, unlike the board: a failing
            // certificate or a stopped backup must never hide just because no app exists yet.
            List<WidgetInstance> lead = new ArrayList<>(2);
            lead.add(new WidgetInstance(HohenheimWidgets.ONBOARDING.id(), Map.of()).withData(Map.of(
                "putOnline", CmsRoutes.list(ADMIN, PutOnlinePage.SLUG),
                "pointAddress", CmsRoutes.list(ADMIN, PutOnlinePage.SLUG)
                    .withFragment(PutOnlinePage.ADDRESSES_ANCHOR))));
            if (checklist != null) {
                lead.add(checklist);
            }
            widgets.add(section(columns(lead)));
            if (!attention.isEmpty()) {
                widgets.add(section(new WidgetInstance(HohenheimWidgets.ATTENTION.id(), Map.of())
                    .withData(attention)));
            }
            return new WidgetTree(widgets);
        }

        if (checklist != null) {
            widgets.add(section(checklist));
        }
        // AIDEV-NOTE: "All clear" under a checklist with an open step would contradict it: the step IS the problem
        // (the band folded it there). The band's all-clear is drawn only when no checklist shows.
        if (checklist == null || !attention.isEmpty()) {
            widgets.add(section(new WidgetInstance(HohenheimWidgets.ATTENTION.id(), Map.of())
                .withData(attention)));
        }
        // AIDEV-NOTE: ONE stat grid, whatever the role mix: roles decide WHICH tiles exist (DashboardStats offers a
        // tile only where its list does), never how many grids there are. The board's four are Apps, Hosts,
        // Certificates and Backups; the Sites, Access lists and Active bans tiles were replaced by them (D10a), so the
        // count of blocked addresses lives on the Blocked addresses list, as its trend always did.
        List<WidgetInstance> tiles = new ArrayList<>(4);
        if (admin != null) {
            for (DashboardStat stat : DashboardStats.read(admin, apps, accessContext)) {
                tiles.add(new WidgetInstance(HohenheimWidgets.STAT.id(), Map.of()).withData(stat));
            }
        }
        if (!tiles.isEmpty()) {
            widgets.add(section(columns(tiles)));
        }
        // The board's lower half: the apps, read from the one App directory, beside what happened lately.
        widgets.add(section(columns(List.of(
            new WidgetInstance(HohenheimWidgets.APPS.id(), Map.of()).withData(
                appsBand(Microcopy.of("apps").withFilter("scope", "dashboard"), apps, accessContext)),
            new WidgetInstance(SectionWidget.ID, Map.of(), new WidgetTree(recentActivity(accessContext)))),
            ColumnSplit.LEAD)));
        return new WidgetTree(widgets);
    }

    /**
     * The dashboard's Apps band under the placement's heading, its rows: health, name, what and where, and whether
     * HTTPS works.
     */
    static @NonNull AppsBand appsBand(@NonNull Microcopy heading, @NonNull List<AppDirectory.App> apps,
                                      @NonNull AccessContext access) {
        List<AppSummary> summaries = new ArrayList<>(apps.size());
        for (AppDirectory.App app : apps) {
            String detail = app.addressText() == null || app.addressText().isBlank()
                ? app.kind() : app.kind() + " · " + app.addressText();
            summaries.add(new AppSummary(app.name(), detail, app.target().toUrl(), app.https(),
                HealthCellState.of(app.health(), access)));
        }
        return new AppsBand(heading, summaries);
    }

    static @NonNull WidgetInstance columns(@NonNull List<WidgetInstance> children) {
        return columns(children, ColumnSplit.EVEN);
    }

    /** @param split how the columns share the width; the boards' lower bands lead with the apps (3 to 2) */
    static @NonNull WidgetInstance columns(@NonNull List<WidgetInstance> children, @NonNull ColumnSplit split) {
        return new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", Math.min(children.size(), 4),
            "split", split.token()), new WidgetTree(children));
    }

    /**
     * The recent-activity band: the ten latest log rows, under the SAME notice
     * {@code /admin/activity} renders while recording is switched off.
     *
     * AIDEV-NOTE: without it this band shows the generic "no records found" -- which reads
     * as "the fleet was quiet", when the truth is that nothing is being written down. The
     * fact and the sentence come from {@link AdminActivityResource#recordingNotice()}
     * (the framework resource's own), never from a second read of {@code activity.enabled}.
     * The notice is resolved here because {@code NoticeData} is display-ready strings; with
     * no conduit there is no locale chain and no viewer either, so the band is just the list.
     */
    private static @NonNull List<WidgetInstance> recentActivity(@NonNull AccessContext accessContext) {

        List<WidgetInstance> band = new ArrayList<>(2);
        Microcopy notice = AdminActivityResource.recordingNotice();
        Conduit conduit = accessContext.conduit();

        if (notice != null && conduit != null) {
            band.add(new WidgetInstance(AlertWidget.ID,
                    Map.of("variant", AlertVariant.WARNING.token()))
                .withData(NoticeData.of(
                    notice.resolve(conduit.getLocales(), conduit.getMessageResolver()), null)));
        }

        band.add(new WidgetInstance(RecordsWidget.ID, Map.of(
            "title", HohenheimWidgetCopy.localized("recent_activity", "dashboard"),
            "source", CmsSupport.ACTIVITY_SOURCE,
            "rules", AdminActivityResource.recentActions(),
            "sort", "created_at",
            "descending", true,
            "limit", 10)));

        return band;
    }

    /** A band claiming the widget grid's full width: the dashboards' bands and a list's attention band. */
    static @NonNull WidgetInstance section(@NonNull WidgetInstance child) {
        return section(new WidgetTree(List.of(child)));
    }

    private static @NonNull WidgetInstance section(@NonNull WidgetTree children) {
        return new WidgetInstance(SectionWidget.ID, Map.of("css_class", "hh-dashboard-band"),
            children);
    }

}
