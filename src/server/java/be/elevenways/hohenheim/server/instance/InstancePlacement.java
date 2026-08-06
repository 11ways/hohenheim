package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * WHERE a new instance lands, and who gets to decide.
 *
 * THE DECISION (2026-08-04, closing the plan's open "placement authority" item): a
 * tenant NEVER names a host. An operator names hosts by ADMITTING them and declaring a
 * posture; the control plane picks one from that declared set. A request-supplied
 * {@code server_id} is honoured for an ADMIN (who is the operator) and IGNORED
 * OUTRIGHT for everyone else -- not validated, not defaulted, ignored -- so no tenant
 * input reaches placement on any surface, and there is no shape a forged field could
 * take that would matter.
 *
 * The eligible set is the intersection of what already exists: {@code admitted}
 * admission, a VERIFIED identity (re-checked here, not trusted from the stored
 * decision), and a posture that accepts a hostile container. A {@code dedicated} host
 * is additionally exclusive: it accepts a tenant only while every live instance on it
 * already answers to that SAME owner -- the posture claims "the whole host belongs to
 * one tenant", and a chooser that co-located a second one would have made that claim a
 * lie. Selection among the survivors is fewest-live-instances, lowest id as the
 * tie-break: deterministic, so a placement is reproducible and testable, and never a
 * function of anything the caller sent.
 *
 * AIDEV-NOTE: the owner label compared on a dedicated host is the QUOTA BUCKET, which
 * is the packed manage-subject set the create charged. It is deliberately the same
 * derivation HohenheimAccess.creationOwnerSubjects feeds, so "who is charged", "who
 * holds the grant" and "who may share this host" can never be three different answers.
 * HostAdmission still runs at DEPLOY: this chooser decides, that gate enforces, and a
 * host cordoned between the two refuses the deploy rather than silently placing.
 */
public final class InstancePlacement {

    private InstancePlacement() {
    }

    /**
     * The host a create by {@code ctx} lands on.
     *
     * @param requested the caller-supplied host; honoured for admins, IGNORED otherwise
     * @throws Violations {@code no_placement_available} when no admitted host accepts
     *         this owner's workload -- named, never a silent fall back to the local daemon
     */
    public static int forActor(@Nullable AccessContext ctx, @Nullable Integer requested,
                               @NonNull String requiredRuntime) {
        if (ctx == null || HohenheimAccess.isAdmin(ctx)) {
            if (requested != null) {
                return requested;
            }
            // The implicit local daemon is a DOCKER host; a kind needing another runtime
            // has no implicit default and walks the same chooser a tenant create does.
            if (ServerModel.RUNTIME_DOCKER.equals(requiredRuntime)) {
                return ServerModel.localServerId();
            }
        }
        return chooseForOwner(HohenheimAccess.packSubjects(
            HohenheimAccess.creationOwnerSubjects(ctx)), requiredRuntime);
    }

    /**
     * @param packedOwner the packed manage-subject set the new instance will answer to
     * @param requiredRuntime the kind's declared host runtime; only matching hosts qualify
     * @return the chosen host id
     * @throws Violations {@code no_placement_available}
     */
    public static int chooseForOwner(@NonNull String packedOwner,
                                     @NonNull String requiredRuntime) {
        return chooseForBucket(InstanceQuota.bucketKeyOf(packedOwner), requiredRuntime, null);
    }

    /**
     * The chooser over an already-derived quota bucket -- the migration/drain lane,
     * where the workload's stored {@code QUOTA_BUCKET} is the owner claim and the
     * host being drained must never be its own destination.
     *
     * @param bucket the charged bucket key (a stored {@code InstanceModel.QUOTA_BUCKET})
     * @param excludeServerId a host that may not be chosen, or null
     * @throws Violations {@code no_placement_available}
     */
    public static int chooseForBucket(@NonNull String bucket,
                                      @NonNull String requiredRuntime,
                                      @Nullable Integer excludeServerId) {
        Integer chosen = null;
        long chosenLoad = Long.MAX_VALUE;

        for (Row server : Models.get(ServerModel.class).find()
                .orderBy(ServerModel.ID, SortOrder.ASC).all()) {
            Integer serverId = server.get(ServerModel.ID);
            if (serverId == null || serverId.equals(excludeServerId)
                    || !ServerModel.runtimeOf(server).equals(requiredRuntime)
                    || !acceptsTenantWorkload(server, bucket)) {
                continue;
            }
            long load = liveInstancesOn(serverId);
            if (load < chosenLoad) {
                chosen = serverId;
                chosenLoad = load;
            }
        }

        if (chosen == null) {
            throw refusal();
        }
        return chosen;
    }

    /** Whether this host may receive a workload owned by {@code bucket}. */
    private static boolean acceptsTenantWorkload(@NonNull Row server, @NonNull String bucket) {
        if (!ServerModel.ADMISSION_ADMITTED.equals(server.get(ServerModel.ADMISSION))) {
            return false;
        }
        String posture = server.get(ServerModel.POSTURE);
        if (posture == null || ServerModel.POSTURE_TRUSTED_ONLY.equals(posture)) {
            return false;
        }
        try {
            HostAdmission.requireVerifiedIdentity(server);
        } catch (Violations unverified) {
            return false;
        }
        if (!ServerModel.POSTURE_DEDICATED.equals(posture)) {
            return true;
        }
        // Dedicated: exclusive to one owner. A live instance charged to any OTHER bucket
        // (the operator's empty-set bucket included) makes this host unavailable.
        Integer serverId = server.get(ServerModel.ID);
        for (Row instance : Models.get(InstanceModel.class).find()
                .where(InstanceModel.SERVER_ID.eq(serverId))
                .where(InstanceModel.DELETED_AT.isNull()).all()) {
            String charged = instance.get(InstanceModel.QUOTA_BUCKET);
            if (charged == null || !charged.equals(bucket)) {
                return false;
            }
        }
        return true;
    }

    private static long liveInstancesOn(int serverId) {
        return Models.get(InstanceModel.class).find()
            .where(InstanceModel.SERVER_ID.eq(serverId))
            .where(InstanceModel.DELETED_AT.isNull())
            .count();
    }

    private static @NonNull Violations refusal() {
        return Violations.ofForm(Microcopy.of("no_placement_available")
            .withFilter("scope", "violations"));
    }
}
