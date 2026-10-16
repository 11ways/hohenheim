package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimTemplateIds;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.common.panel.PanelRequest;
import be.elevenways.zenit.cms.common.resource.PanelPage;
import be.elevenways.zenit.notifications.server.NotificationInbox;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.RenderTemplateResult;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The operator's own notification inbox: zenit-notifications' shared inbox screen ({@code NotificationInbox.page},
 * drawn by {@code <znt-inbox-page>}) mounted under this panel's route and gate.
 *
 * AIDEV-NOTE: this is where a platform alert becomes VISIBLE with nothing
 * configured. Every alert fans out to the inbox of every administrator
 * ({@code Alerts.administrators}), so the page needs no channel row, no transport
 * and no credential -- which is the whole point, because zero configured channels
 * is exactly the state every production installation was in.
 *
 * Ownership is the module's: {@code NotificationInbox} scopes every read to the
 * requesting principal, so this page shows the reader their OWN items and the
 * panel permission is the only gate it needs.
 *
 * AIDEV-NOTE: no sidebar badge: the one count the sidebar carries is the Apps entry's apps with a
 * problem. A lasting condition an alert reports reaches the dashboard's Needs attention at its root; the inbox is the
 * history of what was sent, its repeats folded by the alert's repeat key (AlertNotification).
 */
public final class AdminInboxPage extends PanelPage {

    @Override public @NonNull Identifier id() { return HohenheimIds.id("inbox"); }
    @Override public @NonNull String slug() { return HohenheimSlugs.INBOX; }
    @Override public @NonNull Icon icon() { return Icon.of("envelope"); }
    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 91; }

    @Override
    public @NonNull Microcopy label() {
        return HohenheimMicrocopy.ADMIN_INBOX.of("plural");
    }

    @Override
    public @Nullable Microcopy description() {
        return HohenheimMicrocopy.ADMIN_INBOX.of("nav_hint");
    }

    @Override
    public @NonNull ActionResult<?> render(@NonNull PanelRequest request) {
        Conduit conduit = request.conduit();
        Map<String, Object> vars = new HashMap<>();
        vars.put("title", label().asString(conduit));
        vars.put("lead", description());
        vars.put("page", NotificationInbox.page(conduit, CmsRoutes.list(HohenheimSlugs.ADMIN, slug()).toUrl()));
        return new RenderTemplateResult(HohenheimTemplateIds.INBOX, vars);
    }
}
