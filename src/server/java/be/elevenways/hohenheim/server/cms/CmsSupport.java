package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.server.ServerMain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.action.HeaderAction;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared helpers for the CMS resources: proxy reload and coerced-map copies.
 * Record-tab strips come from the framework (RecordScopedPage.recordTabs);
 * mutation-driven reloads ride {@code ProxyReloadHooks}.
 *
 * AIDEV-NOTE: this used to state "audit writes ride the framework activity log" as an
 * invariant. It is a CONVENTION, not a guarantee: an action whose whole effect is a
 * {@code updateAll()} or a daemon call writes no activity row at all, however it is
 * wrapped (ActivityLog.withAction only RENAMES rows the model hooks already write).
 * An action that must be accountable records itself explicitly.
 */
public final class CmsSupport {

    /**
     * The framework activity record source's token, as {@code ActivitySources} derives it
     * from {@code ActivityModel}. One home here because three surfaces name it (the admin
     * dashboard feed plus the per-record bands on the instance and server overviews) and a
     * typo in any of them degrades to an empty widget with only a log line to show for it.
     */
    public static final String ACTIVITY_SOURCE = "zenit.activity";

    private CmsSupport() {
    }

    /** Rebuild the proxy routing table from the current configuration. */
    public static void reloadProxy() {
        var proxy = ServerMain.getProxyServer();
        if (proxy != null) {
            proxy.reload();
        }
    }

    /** The coerced maps the CMS hands to persist/update are immutable; copy before staging values. */
    public static @NonNull Map<String, Object> mutable(@NonNull Map<String, Object> coerced) {
        return new LinkedHashMap<>(coerced);
    }

    /** A violation-scoped microcopy message (catalog entries carry {@code scope=violations}). */
    public static @NonNull Microcopy violationText(@NonNull String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }

    /**
     * THE sidebar description of a peer, and the tooltip of the header action that leads to
     * a demoted one: microcopy key {@code nav_hint} in the peer's OWN scope, so one sentence
     * has one home no matter which of the two surfaces renders it.
     */
    public static @NonNull Microcopy navHint(@NonNull String scope) {
        return Microcopy.of("nav_hint").withFilter("scope", scope);
    }

    /**
     * A header-action link from a parent list to a peer that was demoted out of the sidebar.
     *
     * AIDEV-NOTE: this is the reachability half of the sidebar curation -- showInNav(false)
     * removes the entry, this puts the entry back where an operator is already standing. The
     * label is the target's own {@code plural} microcopy and the tooltip its own
     * {@code nav_hint}, so a demoted peer never grows a second name. The target is always the
     * ADMIN panel: every caller is an operator-only list, and the /manage subclasses blank
     * their inherited header actions rather than relaying a tenant to /admin.
     *
     * @param scope the target peer's microcopy scope, supplying BOTH its label and tooltip
     */
    public static @NonNull HeaderAction relatedList(@NonNull String actionId, @NonNull String slug,
                                                    @NonNull String scope, @NonNull Icon icon) {
        return HeaderAction.Url.builder(Identifier.of("hohenheim", actionId))
            .label(Microcopy.of("plural").withFilter("scope", scope))
            .description(navHint(scope))
            .icon(icon)
            .route(CmsRoutes.list("admin", slug))
            .build();
    }

    /**
     * THE page title of a record subpage: microcopy key {@code page_title} in the page's
     * OWN scope, carrying the record's name as {@code {$name}}.
     *
     * AIDEV-NOTE: one key name in one place per page, because the alternative is what
     * this replaced -- eleven call sites concatenating an English literal onto a record
     * name, one of which was written AFTER the defect had already been listed. The
     * `{page}_title`-in-a-shared-scope spelling the first three fixed pages used is gone:
     * two pages of the same record type (a zone's records and its zone file) would have
     * had to share one scope, and the whole point of the per-page scope is that each
     * surface owns its own short keys. `page_title` is guarded against reintroduction by
     * PageTitleLocalizationTest.
     *
     * @param scope the page's microcopy scope, e.g. {@code instance_device}
     * @param name the record's own name, never translated (it is user data)
     */
    public static @NonNull String pageTitle(@NonNull Conduit conduit, @NonNull String scope,
                                            @Nullable Object name) {
        return Microcopy.of("page_title").withFilter("scope", scope)
            .withArg("name", String.valueOf(name))
            .resolve(conduit.getLocales(), conduit.getMessageResolver());
    }

    /**
     * The hosting panel's slug during a cms dispatch (subpages render under /admin AND
     * /manage).
     *
     * AIDEV-NOTE: this replaced a {@code panelBase} that returned {@code "/" + slug} and
     * had ~20 call sites concatenating onto it. It is deliberately a SLUG and not a
     * path: a slug is what {@code CmsRoutes} takes, so there is nothing to concatenate
     * onto and the hand-built-URL failure mode cannot come back through this door.
     */
    public static @NonNull String panelSlug(@NonNull Conduit conduit) {
        String slug = conduit.getParameter(CmsEndpoints.PANEL_PARAM);
        return slug != null && !slug.isBlank() ? slug : "admin";
    }

    /**
     * Whether this render is the DELEGATED tenant panel rather than the operator one.
     *
     * AIDEV-NOTE: the projection question is about the SURFACE, never about the viewer.
     * An operator who opens /manage must see exactly what a tenant sees there, or the
     * projection is untestable by anyone who can also reach /admin -- which is everyone
     * who would notice a leak. Never rewrite this as an isAdmin check.
     *
     * AIDEV-NOTE: shared subpages ({@code InstanceOverviewPage},
     * {@code InstanceProvisioningPage}) render under BOTH panels, so "the delegated
     * resource omits it" only covers the FORM. Anything a subpage puts in its template
     * vars -- a host name, a server id inside a route target, a daemon's own error text
     * -- reaches a tenant unless the page asks this.
     */
    public static boolean isDelegatedPanel(@NonNull Conduit conduit) {
        return ManagePanel.SLUG.equals(panelSlug(conduit));
    }
}
