package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.zenit.cms.common.panel.Panel;
import be.elevenways.zenit.cms.common.panel.PanelRegistry;
import be.elevenways.zenit.cms.common.resource.RowResource;
import be.elevenways.zenit.cms.server.panel.PanelResourceViews;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.Objects;

/**
 * A booted panel's registered row entry as the panel's own programmatic view, for tests that ask its declarations,
 * verbs and writes directly.
 *
 * @author Jelle De Loecker
 * @since  0.9.0
 */
public final class PanelEntryViews {

    private PanelEntryViews() {
    }

    /** @throws IllegalStateException when the panel is not registered or declares no row entry under that slug */
    public static @NonNull RowResource of(@NonNull String panelSlug, @NonNull String entrySlug) {
        Panel panel = Objects.requireNonNull(PanelRegistry.getBySlug(panelSlug), "panel " + panelSlug);
        return (RowResource) PanelResourceViews.forCaller(CmsSupport.rowEntry(panel, entrySlug), panel);
    }
}
