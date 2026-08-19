package be.elevenways.hohenheim.server.cms;

import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.cms.common.panel.NavGroup;
import be.elevenways.zenit.cms.server.page.SettingsPage;
import be.elevenways.zenit.common.ui.Icon;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;

/**
 * The panel's settings editor, placed in the System group beside the activity log.
 *
 * AIDEV-NOTE: zenit-cms's {@link SettingsPage} declares no group of its own (it lands in
 * NavGroup.DEFAULT, which rendered it as a stray unlabelled entry in the top block). It is a
 * plain class, so a three-line subclass is the whole fix -- no framework change, and the
 * group/order/description stay where every other hohenheim peer declares them.
 */
public final class AdminSettingsPage extends SettingsPage {

    public AdminSettingsPage(@NonNull List<Mount> mounts) {
        super(Identifier.of("hohenheim", "settings"), "settings",
            Microcopy.of("title").withFilter("scope", "settings"), Icon.of("gear"), mounts);
    }

    @Override public @NonNull NavGroup navGroup() { return NavGroup.SYSTEM; }
    @Override public int navOrder() { return 95; }

    @Override
    public @Nullable Microcopy description() {
        return Microcopy.of("nav_hint").withFilter("scope", "settings");
    }
}
