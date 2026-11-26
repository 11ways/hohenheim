package be.elevenways.hohenheim.server.files;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * THE record whose tree of files the file manager reaches: an app's volumes, or a site's own stored files.
 *
 * AIDEV-NOTE: a closed vocabulary of two; every fact that differs per member (the capability a verb asks, where the
 * tree lives, what records the activity) is an exhaustive switch over it in {@link InstanceFiles}, so a third member
 * breaks the build until each fact is decided. A site holds one capability, manage, so both of the file manager's
 * verbs ask it there: who manages the site reads and changes its files.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public sealed interface FileRoot {

    /** An app's own volumes, through its workload's driver. */
    record App(int instanceId) implements FileRoot {
    }

    /** A static-files site's own stored files, through the storage it names. */
    record Site(int siteId) implements FileRoot {
    }

    /** @return the app's files */
    static @NonNull App app(int instanceId) {
        return new App(instanceId);
    }

    /** @return the site's files */
    static @NonNull Site site(int siteId) {
        return new Site(siteId);
    }

    /** @return the model whose history records a change of these files */
    default @NonNull Model model() {
        return switch (this) {
            case App app -> Models.get(InstanceModel.class);
            case Site site -> Models.get(SiteModel.class);
        };
    }

    /** @return the record's id */
    default int recordId() {
        return switch (this) {
            case App app -> app.instanceId();
            case Site site -> site.siteId();
        };
    }

    /**
     * Whether the context may use one of the file manager's capabilities here, as the panel asks before drawing.
     *
     * @param capability {@link InstanceFiles#READ} or {@link InstanceFiles#WRITE}
     */
    default boolean permits(@NonNull AccessContext access, @NonNull String capability) {
        return switch (this) {
            case App app -> HohenheimAccess.hasInstanceCapability(access, app.instanceId(), capability);
            case Site site -> HohenheimAccess.canManageSite(access, site.siteId());
        };
    }

    /**
     * THE service gate of a verb: the work in flight must hold the capability here.
     *
     * @throws be.elevenways.zenit.common.validation.Violations the uniform refusal of its tier
     */
    default void require(@NonNull String capability) {
        switch (this) {
            case App app -> HohenheimAccess.requireOperationCapability(app.instanceId(), capability);
            case Site site -> HohenheimAccess.requireSiteManage(site.siteId());
        }
    }
}
