package be.elevenways.hohenheim.server.database;

import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceDatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.docker.InstanceDatabaseNetworks;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The RECORD half of instance-database attachments: which live workloads depend on a
 * database, and the explicit cleanup a soft delete does not do for us.
 *
 * AIDEV-NOTE: ATTACHING does NO daemon work on purpose. The credentials only enter a
 * workload's environment when {@code InstanceService.resolve} derives them, which happens
 * at the next deploy -- so joining the link network at attach time would open reachability
 * for an environment that does not yet name the database. Row first, converge on deploy, in
 * both tiers. DETACHING is NOT the mirror of that and never was: see {@link #install}.
 */
public final class InstanceDatabaseLinks {

    private static final String DETACHED_INSTANCES = "hohenheim.instance-db-links.detached";

    private static boolean installed;

    private InstanceDatabaseLinks() {
    }

    /**
     * Install the DETACH sweep: dropping an attachment row revokes the workload's
     * reachability at the daemon immediately, on every delete path (admin form, API,
     * criteria delete), not at some later deploy.
     *
     * AIDEV-NOTE: the site lane sweeps in {@code SiteReleases.completeDrain} instead,
     * because a site detach changes the release FINGERPRINT and therefore mints a new
     * release seconds later -- "the next converge" is immediate there. An instance detach
     * triggers no converge at all, so the same deferral would leave a running container
     * dialling a database whose authorization row is gone, indefinitely. Attaching stays
     * deferred (fail-CLOSED: no credential is in the environment yet); detaching cannot
     * be, because deferring it fails OPEN. That asymmetry, not symmetry with attach, is
     * why this is a write hook.
     *
     * AIDEV-NOTE: a sweep that fails is LOGGED, not thrown -- the site lane's precedent
     * (it records a WARNING step and completes the release). The leftover is not lost:
     * with its attachment row gone the link network classifies as ORPHANED, so the
     * reconciler surfaces it and OrphanActions removes it. That safety net only became
     * true when the reconciler learned the instance_database branch; the two fixes are
     * one batch for that reason.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        // Captured BEFORE the delete because the sweep runs AFTER it: the sweep reads
        // the surviving rows to decide what stays, and the doomed row must be gone by
        // then or it would vote to keep its own network.
        InstanceDatabaseModel.SCHEMA.addBeforeRemoveHook(context -> {
            Model model = context.getModel();
            if (model == null) {
                return;
            }
            QueryContext query = context.getQueryContext();
            Criteria criteria = query != null ? query.getCriteria() : null;
            QueryBuilder<Row> builder = model.find();
            if (criteria != null) {
                builder.where(criteria);
            }
            Set<Integer> doomed = new LinkedHashSet<>();
            for (Row row : builder.all()) {
                Integer instanceId = row.get(InstanceDatabaseModel.INSTANCE_ID);
                if (instanceId != null) {
                    doomed.add(instanceId);
                }
            }
            if (!doomed.isEmpty()) {
                context.setAttribute(DETACHED_INSTANCES, doomed);
            }
        });
        InstanceDatabaseModel.SCHEMA.addAfterRemoveHook(context -> {
            if (!(context.getAttribute(DETACHED_INSTANCES) instanceof Set<?> doomed)) {
                return;
            }
            for (Object instanceId : doomed) {
                if (instanceId instanceof Integer id) {
                    InstanceDatabaseNetworks.sweepFor(id, false);
                }
            }
        });
        // The DATABASE side of the same rows: a database still attached to a LIVE workload
        // refuses to go (the workload keeps injected credentials pointing at a dead engine
        // with nothing saying why), and one that is not takes its attachment rows with it --
        // through this model's own hooks above, so the link networks come down too.
        // Correlated over the pending delete's criteria: every delete lane, no re-read.
        DatabaseModel.SCHEMA.addBeforeRemoveHook(context -> {
            refuseWhileAttachedToLiveWorkloads(context);
            PendingDeletes.deleteDependents(Models.get(InstanceDatabaseModel.class),
                InstanceDatabaseModel.DATABASE, context);
        });
    }

    /**
     * @throws Violations {@code database_in_use} naming the database and the live workloads
     *         still attached to it
     */
    private static void refuseWhileAttachedToLiveWorkloads(@NonNull RemoveFromDatasource context) {
        List<Row> live = Models.get(InstanceDatabaseModel.class).find()
            .where(PendingDeletes.dependents(InstanceDatabaseModel.DATABASE, context))
            .where(Criteria.related(InstanceDatabaseModel.INSTANCE, InstanceModel.DELETED_AT.isNull()))
            .all();
        if (live.isEmpty()) {
            return;
        }
        Row database = live.get(0).get(InstanceDatabaseModel.DATABASE);
        List<String> names = new ArrayList<>();
        for (Row link : live) {
            Row instance = link.get(InstanceDatabaseModel.INSTANCE);
            if (instance != null) {
                names.add(String.valueOf((Object) instance.get(InstanceModel.NAME)));
            }
        }
        throw Violations.ofForm(CmsSupport.violationText("database_in_use")
            .withArg("name", database != null
                ? String.valueOf((Object) database.get(DatabaseModel.NAME)) : "")
            .withArg("workloads", String.join(", ", names)));
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
        // AIDEV-NOTE: NO "no rows, nothing to do" shortcut. An instance whose links were
        // detached earlier still owns their link NETWORKS at the daemon if any detach
        // sweep ever failed, and skipping the sweep here was the last moment in the
        // record's life anything would have reclaimed them.
        links.find().where(InstanceDatabaseModel.INSTANCE_ID.eq(instanceId)).delete();
        // Rows gone first, then the daemon: the sweep reads the rows to decide what
        // survives, and the container is already destroyed by the time this runs.
        InstanceDatabaseNetworks.sweepFor(instanceId, true);
    }

}
