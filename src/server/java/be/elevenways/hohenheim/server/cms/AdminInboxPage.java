package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimParams;
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
import be.elevenways.zenit.common.routing.RouteTarget;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The operator's own notification inbox: the shipped zenit-comms surface mounted
 * under this panel's route and gate.
 *
 * <p>AIDEV-NOTE: this is where a platform alert becomes VISIBLE with nothing
 * configured. Every alert fans out to the inbox of every administrator
 * ({@code Alerts.administrators}), so the page needs no channel row, no transport
 * and no credential -- which is the whole point, because zero configured channels
 * is exactly the state every production installation was in.
 *
 * <p>Ownership is the module's: {@code CommsInbox} scopes every read to the
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
        vars.put("page", window.page());
        vars.put("pageCount", window.pageCount());
        vars.put("previousPage", window.page() > 1 ? pageUrl(window.page() - 1) : null);
        vars.put("nextPage", window.hasMore() ? pageUrl(window.page() + 1) : null);
        return new RenderTemplateResult(Identifier.of("hohenheim", "cms/inbox"), vars);
    }

    /** This page at a different page number, composed typed -- never a concatenated query. */
    private @NonNull RouteTarget pageUrl(int page) {
        return CmsRoutes.list("admin", slug()).with(HohenheimParams.INBOX_PAGE, page);
    }

    /** The page the URL asks for; anything absent or unreadable is page 1. */
    private static int requestedPage(@NonNull Conduit conduit) {
        String raw = conduit.getQueryParam(HohenheimParams.INBOX_PAGE_NAME);

        if (raw == null || raw.isBlank()) {
            return 1;
        }
        Integer requested = HohenheimParams.INBOX_PAGE.parse(raw.trim());
        return requested == null ? 1 : requested;
    }
}
