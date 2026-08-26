package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.cms.common.resource.ActivityResource;
import be.elevenways.zenit.cms.common.resource.ListChrome;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The framework activity log with a hohenheim-authored sidebar description.
 *
 * AIDEV-NOTE: group, order, slug and every behaviour stay the framework's -- this exists
 * only because {@code description()} is a per-panel editorial decision and the shared
 * resource cannot know which sentence fits this product.
 */
public final class AdminActivityResource extends ActivityResource {

    /**
     * The notice {@code /admin/activity} shows while recording is off, for any other
     * surface rendering the same log; null while recording is on.
     *
     * AIDEV-NOTE: asks the framework resource's own {@code emptyDescription()} instead of
     * reading {@code activity.enabled} a second time -- the fact and the sentence keep one
     * declaring home, so a dashboard band can never disagree with the activity page about
     * whether anything is being written down.
     */
    public static @Nullable Microcopy recordingNotice() {
        return new AdminActivityResource().emptyDescription();
    }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "activity");
    }

    /**
     * The widest table in the panel and the highest row count, so the column gear stays --
     * but an audit trail is read forwards from now, never through a saved view.
     */
    @Override
    public @NonNull ListChrome listChrome() {
        return CmsSupport.WIDE_LIST;
    }
}
