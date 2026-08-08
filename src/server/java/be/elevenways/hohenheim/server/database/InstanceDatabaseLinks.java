package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The RECORD half of instance-database attachments: which live workloads depend on a
 * database, and the explicit cleanup a soft delete does not do for us.
 *
 * AIDEV-NOTE: attaching does NO daemon work on purpose. The credentials only enter a
 * workload's environment when {@code InstanceService.resolve} derives them, which happens
 * at the next deploy -- so joining the link network at attach time would open reachability
 * for an environment that does not yet name the database. Row first, converge on deploy, in
 * both tiers.
 */
public final class InstanceDatabaseLinks {

    private InstanceDatabaseLinks() {
    }

    /**
     * The NAMES of the live (non-deleted) instances a database is attached to: what a
     * destroy has to refuse for. A link to an already soft-deleted instance is debris and
     * names nobody.
     */
    public static @NonNull List<String> liveInstanceNames(int databaseId) {
        InstanceModel instances = Models.get(InstanceModel.class);
        List<String> names = new ArrayList<>();
        for (Row link : Models.get(InstanceDatabaseModel.class).findByDatabaseId(databaseId)) {
            Row instance = instances.find()
                .where(InstanceModel.ID.eq(link.get(InstanceDatabaseModel.INSTANCE_ID)))
                .where(InstanceModel.DELETED_AT.isNull())
                .first();
            if (instance != null) {
                names.add(String.valueOf((Object) instance.get(InstanceModel.NAME)));
            }
        }
        return names;
    }

    /**
     * Drop every attachment of one instance and remove its link networks.
     *
     * AIDEV-NOTE: called EXPLICITLY from {@code InstanceService.destroy}, because destroy
     * SOFT-deletes the instance row and a soft delete fires no remove hooks -- the
     * {@code GameDomains.deleteForInstance} precedent. Without this the rows would keep a
     * destroyed workload named in the database's in-use refusal forever, so the tenant
     * could never destroy the database either.
     */
    public static void deleteForInstance(int instanceId) {
        InstanceDatabaseModel links = Models.get(InstanceDatabaseModel.class);
        if (links.findByInstanceId(instanceId).isEmpty()) {
            return;
        }
        links.find().where(InstanceDatabaseModel.INSTANCE_ID.eq(instanceId)).delete();
        // Rows gone first, then the daemon: the sweep reads the rows to decide what
        // survives, and the container is already destroyed by the time this runs.
        InstanceDatabaseNetworks.sweepFor(instanceId, true);
    }

    /** Drop every attachment pointing at one database (its record is going away). */
    public static void deleteForDatabase(int databaseId) {
        for (Row link : Models.get(InstanceDatabaseModel.class).findByDatabaseId(databaseId)) {
            Integer instanceId = link.get(InstanceDatabaseModel.INSTANCE_ID);
            Models.get(InstanceDatabaseModel.class).find()
                .where(InstanceDatabaseModel.ID.eq(link.get(InstanceDatabaseModel.ID))).delete();
            if (instanceId != null) {
                InstanceDatabaseNetworks.sweepFor(instanceId, false);
            }
        }
    }
}
