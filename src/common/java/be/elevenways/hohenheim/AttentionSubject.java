package be.elevenways.hohenheim;

import be.elevenways.hawkeye.common.annotation.HawkeyeClass;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.DnsZoneModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.model.SiteDomainModel;
import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * The record an attention item is about, so an item caused by it can name it as its root.
 *
 * AIDEV-NOTE: two subjects are no stored record. {@link #httpsTermination()}, the HTTPS this installation's proxy
 * answers with (its listener and the certificates it loaded): while it cannot answer, every site sent to HTTPS gets
 * an error page for that one cause, so their items fold under its item instead of each naming its own address.
 * {@link #controlPlaneBackup()}, where this installation's own backups go: while none is chosen, the nightly backup's
 * refused run folds under the item saying where to choose one.
 *
 * @param model the record's model ({@link #HTTPS_TERMINATION} or {@link #CONTROL_PLANE_BACKUP} for the installation's)
 * @param id    the record's id (0 for an installation-wide subject, of which there is one)
 * @author Jelle De Loecker
 * @since  0.9.0
 */
@HawkeyeClass
public record AttentionSubject(@NonNull Identifier model, int id) {

    /** The {@link #model} of {@link #httpsTermination()}, a subject no model stores. */
    public static final Identifier HTTPS_TERMINATION = HohenheimIds.id("https_termination");

    /** @return the HTTPS this installation's proxy answers with: its listener and the certificates it loaded */
    public static @NonNull AttentionSubject httpsTermination() {
        return new AttentionSubject(HTTPS_TERMINATION, 0);
    }

    /** The {@link #model} of {@link #controlPlaneBackup()}, a subject no model stores. */
    public static final Identifier CONTROL_PLANE_BACKUP = HohenheimIds.id("control_plane_backup");

    /** @return where this installation's own database and keys are backed up to */
    public static @NonNull AttentionSubject controlPlaneBackup() {
        return new AttentionSubject(CONTROL_PLANE_BACKUP, 0);
    }

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

    /** @return the DNS zone with this id */
    public static @NonNull AttentionSubject zone(int zoneId) {
        return new AttentionSubject(DnsZoneModel.MODEL_ID, zoneId);
    }

    /** @return the site address (domain row) with this id */
    public static @NonNull AttentionSubject address(int domainId) {
        return new AttentionSubject(SiteDomainModel.MODEL_ID, domainId);
    }
}
