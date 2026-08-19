package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ActivityResource;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The framework activity log with a hohenheim-authored sidebar description.
 *
 * AIDEV-NOTE: group, order, slug and every behaviour stay the framework's -- this exists
 * only because {@code description()} is a per-panel editorial decision and the shared
 * resource cannot know which sentence fits this product.
 */
public final class AdminActivityResource extends ActivityResource {

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "activity");
    }
}
