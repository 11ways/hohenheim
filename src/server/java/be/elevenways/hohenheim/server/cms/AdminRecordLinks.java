package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.page.CmsRecordLinks;
import be.elevenways.zenit.cms.common.page.CmsRoutes;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelPeer;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.ActivityResource;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.common.routing.BoundEndpoint;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The (model id, record id) to detail-page derivation of {@link CmsRecordLinks}, answered
 * inside the OPERATOR panel only: what every /admin surface links a stored record through.
 *
 * AIDEV-NOTE: this exists because of a zenit-cms defect, not a hohenheim preference.
 * CmsRecordLinks.detail walks {@code PanelRegistry.all()}, which iterates a ConcurrentHashMap
 * (hash order, not the registration order its docblock claims), and it is not told which panel
 * the caller renders in. Hohenheim mounts most models twice (the admin resource and its /manage
 * narrowing), so admin activity rows linked into /manage/sites/N -- a page an operator's session
 * reaches through a different panel's scope and chrome. The framework fix is a panel-aware
 * overload (prefer the CURRENT panel, then a deterministic order); when it lands, this class
 * becomes a one-line delegation. Same peer rule as the framework: the first RowResource over
 * the model, activity resources skipped.
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
        if (modelId == null || recordId == null || recordId.isBlank()) {
            return null;
        }
        Panel admin = PanelRegistry.getBySlug(HohenheimPanel.SLUG);
        if (admin == null) {
            return null;
        }
        for (PanelPeer peer : admin.peers()) {
            if (peer instanceof ActivityResource || !(peer instanceof RowResource resource)) {
                continue;
            }
            if (modelId.equals(resource.model().getModelId())) {
                return CmsRoutes.detail(admin.slug(), resource.slug(), recordId);
            }
        }
        return null;
    }

    /** The stored-token overload: the shape the activity log persists. */
    public static @Nullable BoundEndpoint<?> detailForToken(@Nullable String modelToken,
                                                            @Nullable String recordId) {
        Identifier modelId = modelToken == null || modelToken.isBlank()
            ? null : Identifier.tryParse(modelToken);
        return detail(modelId, recordId);
    }

    /** @return {@link #detail} rendered to a URL, for consumers whose contract is a plain href */
    public static @Nullable String detailUrl(@Nullable Identifier modelId, @Nullable String recordId) {
        BoundEndpoint<?> target = detail(modelId, recordId);
        return target != null ? target.toUrl() : null;
    }
}
