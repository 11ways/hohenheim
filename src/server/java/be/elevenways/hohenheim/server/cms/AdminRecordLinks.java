package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimSlugs;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsEndpoints;
import be.elevenways.zenit.cms.common.page.CmsRecordLinks;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The framework's panel-aware {@link CmsRecordLinks} asked from the OPERATOR panel, answered
 * only when the link stays inside it: what every /admin surface links a stored record through.
 *
 * AIDEV-NOTE: the one rule this adds over the framework call. {@code CmsRecordLinks.detail(panel,
 * ...)} prefers the given panel and then FALLS BACK to any other panel serving the model, which is
 * right for a caller outside any panel but wrong here: an /admin row linking into /manage lands
 * an operator in a tenant panel's scope and chrome. So a target the fallback resolved into another
 * panel is refused and the row renders unlinked, exactly as when nothing serves the model. This
 * class disappears if zenit-cms grows a "within this panel only" form of the walk.
 *
 * @author Jelle De Loecker <jelle@elevenways.be>
 * @since 0.1.0
 */
public final class AdminRecordLinks {

    private AdminRecordLinks() {
    }

    /**
     * @return the record's /admin detail target, or null when the model id is absent, the
     *         record id blank, or no admin resource serves the model
     */
    public static @Nullable BoundEndpoint<?> detail(@Nullable Identifier modelId, @Nullable String recordId) {
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        return admin == null ? null : withinAdmin(CmsRecordLinks.detail(admin, modelId, recordId));
    }

    /** The stored-token overload: the shape the activity log persists. */
    public static @Nullable BoundEndpoint<?> detailForToken(@Nullable String modelToken,
                                                            @Nullable String recordId) {
        Panel admin = PanelRegistry.getBySlug(HohenheimSlugs.ADMIN);
        return admin == null ? null
            : withinAdmin(CmsRecordLinks.detailForToken(admin, modelToken, recordId));
    }

    /** @return {@link #detail} rendered to a URL, for consumers whose contract is a plain href */
    public static @Nullable String detailUrl(@Nullable Identifier modelId, @Nullable String recordId) {
        BoundEndpoint<?> target = detail(modelId, recordId);
        return target != null ? target.toUrl() : null;
    }

    /** @return the target when it is an /admin page, null when the framework's fallback left the panel */
    private static @Nullable BoundEndpoint<?> withinAdmin(@Nullable BoundEndpoint<?> target) {
        return target != null
            && HohenheimSlugs.ADMIN.equals(target.getParameters().get(CmsEndpoints.PANEL_PARAM))
            ? target : null;
    }
}
