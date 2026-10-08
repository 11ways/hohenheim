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
import be.elevenways.hohenheim.model.AccessListModel;
import be.elevenways.hohenheim.model.BanModel;
import be.elevenways.hohenheim.model.CertificateModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.HohenheimRoles.Role;
import be.elevenways.hohenheim.server.HohenheimRoles;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.protoblast.common.typed.CoreTypes;
import be.elevenways.protoblast.common.typed.rule.Condition;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelDashboard;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.render.table.HealthCellState;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.RecordSourceRegistry;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.widget.common.WidgetInstance;
import be.elevenways.zenit.widget.common.WidgetTree;
import be.elevenways.zenit.widget.common.builtin.AlertVariant;
import be.elevenways.zenit.widget.common.builtin.AlertWidget;
import be.elevenways.zenit.widget.common.builtin.ColumnsWidget;
import be.elevenways.zenit.widget.common.builtin.RecordsWidget;
import be.elevenways.zenit.widget.common.builtin.SectionWidget;
import be.elevenways.zenit.widget.common.builtin.StatWidget;
import be.elevenways.zenit.widget.common.data.NoticeData;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /admin landing dashboard: entity-count stat tiles plus the most
 * recent activity-log entries.
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
        if (!HohenheimRoles.enabled(Role.INSTANCES) || !HohenheimAccess.isAdmin(request.access())) {
            return List.of();
        }
        return List.of(new LinkActionState(HohenheimIds.id("dashboard_put_online"), PutOnline.copy("put_online"),
            Icon.of("rocket"), ActionStyle.PRIMARY,
            new Uri(CmsRoutes.list(request.panelSlug(), PutOnlinePage.SLUG).toUrl()), false, null));
    }

    /** Role-gated bands: a tile must not link to a resource this install has no route for. */
    @Override
    public @NonNull WidgetTree widgets(@NonNull AccessContext accessContext) {
        boolean proxy = HohenheimRoles.enabled(Role.PROXY);
        boolean firewall = HohenheimRoles.enabled(Role.FIREWALL);

        // AIDEV-NOTE: ONE stat grid, whatever the role mix. The firewall tile used to be a
        // band of its own with its own column count, so a proxy+firewall install rendered
        // four tiles as a 3-wide grid followed by a lone half-width card underneath -- two
        // grids the operator reads as two unrelated groups. Roles decide WHICH tiles exist,
        // never how many grids there are.
        List<WidgetInstance> tiles = new ArrayList<>();
        if (proxy) {
            tiles.add(stat("site", SiteModel.MODEL_ID, "sites", "globe"));
            tiles.add(stat("certificate", CertificateModel.MODEL_ID, "certificates", "lock"));
            tiles.add(stat("access_list", AccessListModel.MODEL_ID, "access-lists", "shield-halved"));
        }
        if (firewall) {
            // The active-ban count (event analytics live in spamservice now, so bans are
            // the only security records here).
            tiles.add(new WidgetInstance(StatWidget.ID, Map.of(
                "label", HohenheimWidgetCopy.localized("active_bans", "dashboard"),
                "source", sourceToken(BanModel.MODEL_ID),
                "rules", Condition.all(Condition.test(BanModel.ACTIVE.getName(), CoreTypes.IS_TRUE)),
                "icon", "ban",
                // StatWidget's stored "link" is a String, so the typed target renders here.
                "link", CmsRoutes.list(ADMIN, "bans").toUrl())));
        }

        List<WidgetInstance> widgets = new ArrayList<>();
        Panel admin = PanelRegistry.getBySlug(ADMIN);
        List<AppDirectory.App> apps = admin == null ? List.of() : AppDirectory.read(admin, accessContext);

        // The readiness checklist RETIRES ITSELF: no dismissed flag, it is simply absent once every step is done.
        // Before it, nothing said a host must be checked and admitted before anything can run, so the first session's
        // natural arc (create -> deploy -> silence) had no visible way forward.
        List<OnboardingStep> onboarding = OnboardingCollector.collect();
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
            List<AttentionItem> attention = AttentionCollector.collect();
            if (!attention.isEmpty()) {
                widgets.add(section(new WidgetInstance(HohenheimWidgets.ATTENTION.id(), Map.of())
                    .withData(attention)));
            }
            return new WidgetTree(widgets);
        }

        if (checklist != null) {
            widgets.add(section(checklist));
        }
        widgets.add(section(new WidgetInstance(HohenheimWidgets.ATTENTION.id(), Map.of())
            .withData(AttentionCollector.collect())));
        if (!tiles.isEmpty()) {
            widgets.add(section(columns(tiles)));
            // AIDEV-NOTE: the 30-day bans chart used to live beside these and is deliberately
            // gone. On any fleet that is not under attack it is an all-zero series, i.e. ~450px
            // of flat line above the content an operator opened the page for. The count itself
            // stays, as a tile. If the trend is wanted, it belongs on the firewall operator's
            // own overview page, which they open on purpose.
        }
        // The board's lower half: the apps, read from the one App directory, beside what happened lately.
        widgets.add(section(columns(List.of(
            new WidgetInstance(HohenheimWidgets.APPS.id(), Map.of()).withData(summaries(apps, accessContext)),
            new WidgetInstance(SectionWidget.ID, Map.of(), new WidgetTree(recentActivity(accessContext)))))));
        return new WidgetTree(widgets);
    }

    /** The dashboard's Apps band rows: health, name, what and where, and whether HTTPS works. */
    private static @NonNull List<AppSummary> summaries(@NonNull List<AppDirectory.App> apps,
                                                       @NonNull AccessContext access) {
        List<AppSummary> summaries = new ArrayList<>(apps.size());
        for (AppDirectory.App app : apps) {
            String detail = app.addressText() == null || app.addressText().isBlank()
                ? app.kind() : app.kind() + " · " + app.addressText();
            summaries.add(new AppSummary(app.name(), detail, app.target().toUrl(), app.https(),
                HealthCellState.of(app.health(), access)));
        }
        return summaries;
    }

    private static @NonNull WidgetInstance columns(@NonNull List<WidgetInstance> children) {
        return new WidgetInstance(ColumnsWidget.ID, Map.of("column_count", Math.min(children.size(), 4)),
            new WidgetTree(children));
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

    /** The tile label resolves the model's "plural" microcopy per content locale. */
    private static @NonNull WidgetInstance stat(@NonNull String modelScope, @NonNull Identifier modelId,
                                                @NonNull String resourceSlug, @NonNull String icon) {
        return new WidgetInstance(StatWidget.ID, Map.of(
            "label", HohenheimWidgetCopy.localized("plural", modelScope),
            "source", sourceToken(modelId),
            "icon", icon,
            "link", CmsRoutes.list(ADMIN, resourceSlug).toUrl()));
    }

    /**
     * The token a tile names its source by: the source registered over the model (a default source's id IS
     * its model's), spelled by that source, so a renamed model can never leave a tile counting nothing.
     */
    private static @NonNull String sourceToken(@NonNull Identifier modelId) {
        return RecordSourceRegistry.INSTANCE.requireById(modelId).idToken();
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
