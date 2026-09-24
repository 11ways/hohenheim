package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
import be.elevenways.plumage.component.Pager;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.comms.inbox.CommsInboxItemView;
import be.elevenways.zenit.comms.server.CommsInbox;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.data.PageWindow;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The operator's own notification inbox: the shipped zenit-comms surface mounted
 * under this panel's route and gate.
 *
 * AIDEV-NOTE: this is where a platform alert becomes VISIBLE with nothing
 * configured. Every alert fans out to the inbox of every administrator
 * ({@code Alerts.administrators}), so the page needs no channel row, no transport
 * and no credential -- which is the whole point, because zero configured channels
 * is exactly the state every production installation was in.
 *
 * Ownership is the module's: {@code CommsInbox} scopes every read to the
 * requesting principal, so this page shows the reader their OWN items and the
 * panel permission is the only gate it needs.
 */
public final class AdminInboxPage extends PanelPage {

    @Override public @NonNull Identifier id() { return Identifier.of("hohenheim", "inbox"); }
    @Override public @NonNull String slug() { return "inbox"; }
    @Override public @NonNull Icon icon() { return Icon.of("envelope"); }
    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 91; }

    @Override
    public @NonNull Microcopy label() {
        return Microcopy.of("plural").withFilter("scope", "admin_inbox");
    }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "admin_inbox");
    }

    /**
     * The reader's unread items, so an alert that lands while any page of the panel is open is
     * seen without opening the inbox.
     *
     * AIDEV-NOTE: counted by comms for the request's own recipient, exactly as the page lists; a
     * request-less context (a nav projection outside a request) has no recipient, so no badge.
     */
    @Override
    public @Nullable Long navBadge(@NonNull AccessContext access) {
        Conduit conduit = access.conduit();
        return conduit == null ? null : CommsInbox.unreadCount(conduit);
    }

    /** Re-counted in place whenever an inbox item lands or is read (a delivery, a mark-read). */
    @Override
    public @NonNull Set<Identifier> navBadgeFeeds() {
        return Set.of(CommsInbox.FEED);
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull Conduit conduit, @NonNull AccessContext accessContext) {
        long total = CommsInbox.itemCount(conduit);
        PageWindow window = PageWindow.of(requestedPage(conduit), total,
            CommsInbox.DEFAULT_LIMIT, PageWindow.OutOfRange.CLAMP);
        List<CommsInboxItemView> items =
            CommsInbox.itemsFor(conduit, window.pageSize(), window.offset());

        Map<String, Object> vars = new HashMap<>();
        vars.put("title", label().resolve(conduit.getLocales(), conduit.getMessageResolver()));
        vars.put("items", items);
        vars.put("markAllTarget", CommsInbox.markAllTarget(conduit));
        vars.put("pager", Pager.of(window, total, page -> pageUrl(page).toUrl()));
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/inbox"), vars);
    }

    /**
     * This page at a different page number, composed typed -- never a concatenated query.
     * Page 1 is the bare route, the parameter's absence.
     */
    private @NonNull RouteTarget pageUrl(int page) {
        BoundEndpoint<?> list = CmsRoutes.list(HohenheimPanel.SLUG, slug());
        return page <= 1 ? list : list.with(HohenheimParams.INBOX_PAGE, page);
    }

    /** The page the URL asks for; anything absent or unreadable is page 1. */
    private static int requestedPage(@NonNull Conduit conduit) {
        Integer requested = CmsSupport.prefill(conduit, HohenheimParams.INBOX_PAGE);
        return requested == null || requested < 1 ? 1 : requested;
    }
}
