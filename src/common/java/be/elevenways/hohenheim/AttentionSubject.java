package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The record an attention item is about, so an item caused by it can name it as its root.
 *
 * @param model the record's model
 * @param id    the record's id
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record AttentionSubject(@NonNull Identifier model, int id) {

    /** @return the host with this id */
    public static @NonNull AttentionSubject host(int serverId) {
        return new AttentionSubject(ServerModel.MODEL_ID, serverId);
    }

    /** @return the workload (instance) with this id */
    public static @NonNull AttentionSubject instance(int instanceId) {
        return new AttentionSubject(InstanceModel.MODEL_ID, instanceId);
    }

    /** @return the managed database with this id */
    public static @NonNull AttentionSubject database(int databaseId) {
        return new AttentionSubject(DatabaseModel.MODEL_ID, databaseId);
    }

    /** @return the site address (domain row) with this id */
    public static @NonNull AttentionSubject address(int domainId) {
        return new AttentionSubject(SiteDomainModel.MODEL_ID, domainId);
    }
}
