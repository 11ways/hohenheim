package be.elevenways.hohenheim.server.source;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.hohenheim.server.instance.ApplicationKind;
import be.elevenways.hohenheim.server.instance.WorkspaceKind;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;

/**
 * WHERE a site's source lives now that it no longer lives on the site: the instance the
 * site exposes carries it, in that instance kind's settings.
 *
 * AIDEV-NOTE: this class exists because {@code sites.source} / {@code sites.source_settings}
 * were dropped in phase 0 brief 5 (design section 3) while the git and preview lanes that
 * read them are re-keyed in briefs 7 and 8. Rather than leave those six readers reading
 * nothing -- the silent-success shape this codebase hunts by name -- they read THROUGH the
 * site's {@code instance_id} at the record that actually owns the source. Every method here
 * disappears when the callers themselves become instance-keyed; nothing new should call it.
 */
public final class SiteSources {

    /** The instance kinds that carry a git source in their settings. */
    private static final List<String> SOURCED_KINDS =
        List.of(ApplicationKind.ID.toString(), WorkspaceKind.ID.toString());

    private SiteSources() {
    }

    /**
     * @return the git-source settings of the instance this site exposes, or null when the
     *         site exposes nothing or the instance carries no source
     */
    public static @Nullable Map<String, Object> settingsOf(@Nullable Row site) {

        Row instance = instanceOf(site);

        if (instance == null) {
            return null;
        }

        return settingsOfInstance(instance);
    }

    /** @return the source settings ON an instance row, or null when its kind carries none */
    @SuppressWarnings("unchecked")
    public static @Nullable Map<String, Object> settingsOfInstance(@Nullable Row instance) {

        if (instance == null || !SOURCED_KINDS.contains(instance.get(InstanceModel.KIND))) {
            return null;
        }

        Object settings = instance.get(InstanceModel.SETTINGS);
        return settings instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** @return whether the site exposes an instance whose source is a git repository */
    public static boolean isGitSourced(@Nullable Row site) {
        Map<String, Object> settings = settingsOf(site);
        return settings != null && hasRepository(settings);
    }

    /** @return the instance this site exposes, or null */
    public static @Nullable Row instanceOf(@Nullable Row site) {

        if (site == null) {
            return null;
        }

        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        return instanceId == null ? null : Models.get(InstanceModel.class).findById(instanceId);
    }

    /** @return whether these source settings name a repository at all */
    public static boolean hasRepository(@NonNull Map<String, Object> settings) {
        return notBlank(settings.get(GitSourceSchema.REPOSITORY_URL))
            || notBlank(settings.get(GitSourceSchema.REPOSITORY));
    }

    private static boolean notBlank(@Nullable Object value) {
        return value != null && !value.toString().isBlank();
    }
}
