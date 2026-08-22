package be.elevenways.hohenheim.test.source;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Give a site fixture a git source the way production carries one now: on the APPLICATION
 * instance the site exposes, never on the site.
 *
 * AIDEV-NOTE: this exists because {@code sites.source}/{@code source_settings} were dropped
 * in phase 0 brief 5 while the webhook and preview lanes that read them are re-keyed in
 * briefs 7 and 8. Fixtures route through here so those lanes keep being exercised through
 * their NEW keying (site -> instance_id -> settings, which is what {@code SiteSources}
 * reads) instead of being deleted and rewritten from nothing later.
 */
public final class TestSources {

    private TestSources() {
    }

    /**
     * Create an application instance carrying these git source settings and point the site
     * at it, saving both.
     *
     * @return the id of the created application instance
     */
    public static int attachGitSource(@NonNull Row site,
                                      @NonNull Map<String, Object> sourceSettings) {

        InstanceModel instances = Models.get(InstanceModel.class);
        Row application = instances.createEmptyRow();
        application.set(InstanceModel.NAME, "src-" + site.get(SiteModel.SLUG));
        application.set(InstanceModel.KIND, "hohenheim:application");
        application.set(InstanceModel.SETTINGS, new LinkedHashMap<>(sourceSettings));
        instances.save(application);

        int instanceId = application.get(InstanceModel.ID);
        site.set(SiteModel.UPSTREAM_KIND, "hohenheim:instance");
        site.set(SiteModel.INSTANCE_ID, instanceId);
        return instanceId;
    }

    /** Read back the source settings a fixture attached, so a test can mutate them. */
    @SuppressWarnings("unchecked")
    public static @NonNull Map<String, Object> sourceSettingsOf(@NonNull Row site) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        Row instance = instanceId == null ? null
            : Models.get(InstanceModel.class).findById(instanceId);
        Object settings = instance == null ? null : instance.get(InstanceModel.SETTINGS);
        return settings instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    /** Write source settings back onto the site's application instance. */
    public static void updateSourceSettings(@NonNull Row site,
                                            @NonNull Map<String, Object> settings) {
        Integer instanceId = site.get(SiteModel.INSTANCE_ID);
        if (instanceId == null) {
            throw new IllegalStateException("site exposes no instance to carry a source");
        }
        InstanceModel instances = Models.get(InstanceModel.class);
        Row instance = instances.findById(instanceId);
        instance.set(InstanceModel.SETTINGS, new LinkedHashMap<>(settings));
        instances.save(instance);
    }
}
