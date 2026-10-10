package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.server.quota.OwnerBudget;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.host.HostAdmission;
import be.elevenways.hohenheim.server.host.PlacementRefusal;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.SortOrder;
import be.elevenways.zenit.common.security.AccessContext;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * WHERE a new instance lands, and who gets to decide.
 *
 * THE DECISION on "placement authority": a
 * tenant NEVER names a host. An operator names hosts by ADMITTING them and declaring a
 * posture; the control plane picks one from that declared set. A request-supplied
 * {@code server_id} is honoured for an ADMIN (who is the operator) and IGNORED
 * OUTRIGHT for everyone else -- not validated, not defaulted, ignored -- so no tenant
 * input reaches placement on any surface, and there is no shape a forged field could
 * take that would matter.
 *
 * THE ELIGIBLE SET IS DECIDED BEFORE THE SCORE, and it is decided by asking the DEPLOY
 * PATH'S OWN AUTHORITY. This class used to re-state a subset of
 * {@link HostAdmission#requireInstancePlacement} inline -- admission, posture, verified
 * identity -- which meant every gate added to the deploy path since was missing here, and
 * placement could CHOOSE a host whose deploy then refused by name (the kernel-truth gate
 * was the live instance of that; the prepared-image constraint was the other). A wrong
 * eligible set is a worse defect than a wrong score, so the predicate now CALLS the gate
 * instead of imitating it, and a kind that has host-specific requirements of its own
 * answers for them through {@link InstanceKindHandler#requirePlaceableOn}.
 *
 * Three things stay placement's OWN, because they are not deploy refusals:
 * the {@code dedicated} posture's exclusivity (a host that accepts this owner would accept
 * the deploy fine -- it is the CO-LOCATION that placement must not create), the runtime
 * match, and the exclude argument.
 *
 * SUPERSEDED 2026-08-12, on the exclusivity clause only -- the runtime match and the
 * exclude argument stay placement's own. Exclusivity is now
 * {@link HostAdmission#requireDedicationRespected}, called from the gate, because the
 * premise above does not survive this class's OWN admin lane: {@link #forActor} honours a
 * caller-supplied {@code server_id} for an admin and returns it without ever walking
 * {@link #chooseForOwner}, so the check at the bottom of this file never ran for the one
 * actor who can name a host -- an admin could place a second tenant's workload onto a
 * dedicated machine and the deploy would accept it. Dedication is
 * "enforced by the allocator, not operator memory"; a rule only the tenant path runs is
 * operator memory. In the gate it binds every lane, and this class keeps consulting the
 * gate rather than re-stating it.
 *
 * Selection among the survivors is MOST REMAINING HEADROOM, lowest id as the tie-break: a
 * host with 128 GB and two small workloads outranks one with 4 GB and one large VM, which
 * a count of rows could never express. It is still deterministic, so a placement is
 * reproducible and testable, and never a function of anything the caller sent.
 *
 * CORRECTED 2026-08-13, and the worked example above is why. The score used to be FEWEST
 * BOOKED MEMORY -- the instance bucket alone, compared across hosts -- which answers a
 * different question from the one the example asks in two independent ways. It is
 * BUDGET-BLIND: a 4 GB host holding nothing scores 0 and outranks a 128 GB host holding
 * 1 GB, so the example's own 128 GB machine loses to any idle small one. (It was also
 * blind to the host-user process tier's separate bucket, deleted with that
 * lane.) Nothing ever overspent -- the eligibility CEILING has always been
 * {@link InstanceCapacity#bookableMbOn} and is unchanged -- but the fleet was packed onto
 * whichever machine happened to hold the fewest instance megabytes. The score is now the
 * same subtraction the ceiling and the {@code no_placement_capacity} refusal already
 * report: {@code bookable - booked}, maximised.
 *
 * AIDEV-NOTE: the owner label compared on a dedicated host is the QUOTA BUCKET, which
 * is the packed manage-subject set the create charged. It is deliberately the same
 * derivation HohenheimAccess.creationOwnerSubjects feeds, so "who is charged", "who
 * holds the grant" and "who may share this host" can never be three different answers.
 * HostAdmission still runs at DEPLOY: this chooser decides, that gate enforces, and a
 * host cordoned between the two refuses the deploy rather than silently placing.
 */
public final class InstancePlacement {

    /**
     * What a create knows about the workload it is placing, beyond its runtime: enough to
     * ask a host whether it could run THIS, and to price it against the host's budget.
     *
     * @param handler the kind, or null when the caller could not resolve one
     * @param settings the workload's settings, as the record will carry them
     * @param supportedRuntimes the daemon flavours it can run on; derived from the kind
     *        by {@link #of}, and carried explicitly only so a kind-less caller can still
     *        route by runtime
     */
    public record Workload(@Nullable InstanceKindHandler handler,
                           @NonNull Map<String, Object> settings,
                           @NonNull Set<String> supportedRuntimes) {

        /** The workload a create is about: the kind decides the runtime and the price. */
        public static @NonNull Workload of(@Nullable InstanceKindHandler handler,
                                           @NonNull Map<String, Object> settings) {
            return new Workload(handler, settings, handler != null
                ? handler.supportedRuntimes() : Set.of(ServerModel.RUNTIME_DOCKER));
        }

        /** The workload an EXISTING record describes (the migration/drain lane). */
        public static @NonNull Workload of(@Nullable InstanceKindHandler handler,
                                           @NonNull Row instance) {
            return of(handler, InstanceModel.settingsOf(instance));
        }

        /**
         * The runtime-only shape: no kind-specific eligibility and NO priced footprint.
         *
         * AIDEV-NOTE: only for a caller that genuinely has no kind (the runtime-routing
         * tests). It books nothing, so it would be admitted onto a host with no headroom
         * left -- never reach for it on a create path, where the kind is always known.
         */
        public static @NonNull Workload forRuntime(@NonNull String runtime) {
            return new Workload(null, Map.of(), Set.of(runtime));
        }

        int footprintMb() {
            return this.handler == null ? 0
                : InstanceCapacity.footprintMbOf(this.handler, this.settings);
        }

        /**
         * The boundary this workload provides, derived from the kind exactly like the
         * footprint is -- a kind-less caller answers SHARED_KERNEL, because the weakest
         * isolation is the only conservative answer to "we do not know".
         */
        @NonNull WorkloadIsolation isolation() {
            return this.handler == null ? WorkloadIsolation.SHARED_KERNEL
                : this.handler.isolation();
        }
    }

    private InstancePlacement() {
    }

    /**
     * The host a create by {@code ctx} lands on.
     *
     * AN ACTOR WHO NAMES NO HOST GETS THE CHOOSER (corrected 2026-08-26). An admin used
     * to get the local daemon back UNCONDITIONALLY for a Docker-capable workload with no
     * host named -- no admission, no posture, no capacity, none of the gates every other
     * placement walks. A fresh installation's local row is {@code blocked} until an
     * operator admits it, so the create form that narrows its host pick to ELIGIBLE hosts
     * (and therefore offers nothing at all) still landed the submit on the blocked
     * machine: the visible form and the authoritative write disagreed, which is the one
     * thing a placement decision may never do. An admin who chooses nothing now either
     * lands on a host that ACCEPTS the workload or is refused by name.
     *
     * THE SEAM IS "IS THERE AN ACTOR", and it is the signature's own fact rather than a
     * guess: a null {@code ctx} is in-process work (there is no HTTP caller today that
     * passes one -- every create surface hands over the requesting context), so it has no
     * form, no picker and nobody to show a refusal to, and it keeps the implicit local
     * daemon. What it does NOT keep is a pass at deploy: {@link HostAdmission} runs there
     * either way, so a system create aimed at a blocked local daemon refuses to RUN
     * rather than running unadmitted. AIDEV-NOTE: the remaining hole is that this lane
     * still bypasses the eligible set; closing it means every fixture that creates
     * without an actor must first admit AND measure a host.
     *
     * @param requested the caller-supplied host; honoured for admins, IGNORED otherwise
     * @throws Violations one of three, each naming what to DO: {@code no_placement_capacity}
     *         (eligible hosts, all out of memory), {@code host_capacity_unproven} (eligible
     *         hosts, none measured -- run preflight on the named one) or
     *         {@code no_placement_available} (nothing accepts this owner's workload at all).
     *         Never a silent fall back to the local daemon on an ACTOR's create
     */
    public static int forActor(@Nullable AccessContext ctx, @Nullable Integer requested,
                               @NonNull Workload workload) {
        if (requested != null && (ctx == null || HohenheimAccess.isAdmin(ctx))) {
            return requested;
        }
        if (ctx == null && workload.supportedRuntimes().contains(ServerModel.RUNTIME_DOCKER)) {
            // The implicit local daemon is a DOCKER host; a kind that CANNOT run on Docker
            // has no implicit default and walks the chooser even here.
            return ServerModel.localServerId();
        }
        return chooseForOwner(HohenheimAccess.packSubjects(
            HohenheimAccess.creationOwnerSubjects(ctx)), workload);
    }

    /**
     * @param packedOwner the packed manage-subject set the new instance will answer to
     * @return the chosen host id
     * @throws Violations {@code no_placement_capacity}, {@code host_capacity_unproven} or
     *         {@code no_placement_available}
     */
    public static int chooseForOwner(@NonNull String packedOwner, @NonNull Workload workload) {
        return chooseForBucket(OwnerBudget.INSTANCES.bucketOf(packedOwner), workload, null);
    }

    /**
     * The chooser over an already-derived quota bucket -- the migration/drain lane,
     * where the workload's stored {@code QUOTA_BUCKET} is the owner claim and the
     * host being drained must never be its own destination.
     *
     * @param bucket the charged bucket key (a stored {@code InstanceModel.QUOTA_BUCKET})
     * @param excludeServerId a host that may not be chosen, or null
     * @throws Violations {@code no_placement_capacity}, {@code host_capacity_unproven} or
     *         {@code no_placement_available}
     */
    public static int chooseForBucket(@NonNull String bucket, @NonNull Workload workload,
                                      @Nullable Integer excludeServerId) {
        Integer chosen = null;
        long chosenFreeMb = Long.MIN_VALUE;
        // Distinguishing "nothing accepts this workload" from "everything is full" is the
        // whole difference between an operator admitting a host and an operator finding
        // one that already refused for another reason.
        boolean somethingWasFull = false;
        Integer unmeasured = null;
        // A host that passed every ADMISSION gate and was excluded only by the kind's own
        // requirement carries the one refusal an operator can act on directly (publish
        // this image on that host), so it is kept rather than folded into a generic
        // "nothing accepts this workload".
        Violations kindRefusal = null;
        long largestFreeMb = -1;
        int footprint = workload.footprintMb();

        for (Row server : Models.get(ServerModel.class).find()
                .orderBy(ServerModel.ID, SortOrder.ASC).all()) {
            Integer serverId = server.get(ServerModel.ID);
            if (serverId == null || serverId.equals(excludeServerId)
                    || !workload.supportedRuntimes().contains(ServerModel.runtimeOf(server))) {
                continue;
            }
            if (!acceptsTenantWorkload(serverId, bucket, workload)) {
                continue;
            }
            KindGate gate = kindGateFor(serverId, workload);
            if (!gate.placeable()) {
                if (gate.reason() != null) {
                    kindRefusal = gate.reason();
                }
                continue;
            }
            Long budget = InstanceCapacity.budgetMbOf(server);
            if (budget == null) {
                // Eligible in every other respect, but nothing has measured it: it cannot
                // be RATIONED, so it is not something to reason about here. The operator
                // is told which host to preflight rather than left with a bare "nothing
                // accepts this".
                unmeasured = serverId;
                continue;
            }
            // The SAME ceiling the write is judged against, never a second expression that
            // happens to agree: this used to compare against the bare budget while
            // InstanceCapacity.reserve subtracted the managed-process tier's bookings, so
            // on any host running child processes placement could choose a host whose
            // write then refused host_capacity_reached by name.
            long bookable = InstanceCapacity.bookableMbOn(serverId, budget);
            long booked = InstanceCapacity.bookedMbOn(serverId);
            long free = bookable - booked;
            if (footprint > free) {
                somethingWasFull = true;
                largestFreeMb = Math.max(largestFreeMb, free);
                continue;
            }
            // AIDEV-NOTE: the SAME `free` the refusal above quotes and the write judges
            // against, maximised -- never `booked` on its own, which is budget-blind and
            // blind to the managed-process tier (see the class docblock's 2026-08-13
            // correction). The comparison is strict, and the walk is ordered by id ASC,
            // which is what makes the lowest id the tie-break without a second clause.
            if (free > chosenFreeMb) {
                chosen = serverId;
                chosenFreeMb = free;
            }
        }

        if (chosen == null) {
            if (kindRefusal != null) {
                throw kindRefusal;
            }
            if (somethingWasFull) {
                throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("no_placement_capacity")
                    .withArg("needed", footprint)
                    .withArg("free", Math.max(0, largestFreeMb)));
            }
            if (unmeasured != null) {
                throw Violations.ofForm(capacityUnproven(unmeasured));
            }
            throw Violations.ofForm(HohenheimMicrocopy.VIOLATIONS.of("no_placement_available"));
        }
        return chosen;
    }

    /** @return the chooser's refusal of a host it cannot ration: no memory reading inside the freshness bound */
    private static @NonNull Microcopy capacityUnproven(int serverId) {
        return PlacementRefusal.CAPACITY_UNPROVEN.text().withArg("name", ServerModel.labelOf(serverId));
    }

    /**
     * Why the chooser would place no new app on this host, or null when it would: THE host-level placement verdict
     * the Hosts list, the host page, the attention band and the checklist read.
     *
     * AIDEV-NOTE: it asks what {@link #chooseForBucket} asks of every host, minus the one workload-specific question
     * (the kind's own placeability): the admission gate for every isolation a kind on this runtime provides (refused
     * only when all refuse, so a VM-isolated Incus host that takes VMs reads as taking apps), with the owner of a
     * workload already there (a dedicated host takes its owner's apps), then the memory budget the chooser rations
     * against. An admitted host the chooser never picks (a stale memory reading) therefore never reads "Takes new
     * apps" (as Starfleet's local host once did, measured 2026-08-29).
     *
     * @return the first refusal in the gate's own words, null when an ordinary new app could land here
     */
    public static @Nullable Microcopy hostRefusal(@NonNull Row server) {
        Integer serverId = server.get(ServerModel.ID);
        if (serverId == null) {
            return PlacementRefusal.NOT_ADMITTED.text()
                .withArg("name", String.valueOf((Object) server.get(ServerModel.NAME)));
        }
        Row resident = Models.get(InstanceModel.class).find().where(InstanceModel.SERVER_ID.eq(serverId)).first();
        String owner = resident == null ? null : resident.get(InstanceModel.QUOTA_BUCKET);
        Microcopy first = null;
        for (WorkloadIsolation isolation : InstanceKinds.isolationsOn(ServerModel.runtimeOf(server))) {
            Microcopy refusal = HostAdmission.instancePlacementRefusal(serverId, isolation, owner);
            if (refusal == null) {
                return InstanceCapacity.budgetMbOf(server) == null ? capacityUnproven(serverId) : null;
            }
            if (first == null) {
                first = refusal;
            }
        }
        return first;
    }

    /**
     * Whether this host may receive this workload owned by {@code bucket}, as far as
     * ADMISSION is concerned.
     *
     * AIDEV-NOTE: this call IS the deploy gate, not a copy of it. Adding a refusal to
     * HostAdmission.requireInstancePlacement automatically narrows the eligible set, which
     * is the property this seam exists to hold -- do NOT re-inline admission, posture,
     * isolation, dedication or identity checks here, however convenient the early exit
     * looks. The dedication check was the last one still living outside it and moved into
     * the gate on 2026-08-12; see the class docblock for why.
     */
    private static boolean acceptsTenantWorkload(int serverId, @NonNull String bucket,
                                                 @NonNull Workload workload) {
        return HostAdmission.instancePlacementRefusal(serverId, workload.isolation(), bucket) == null;
    }

    /**
     * The kind's verdict on one host: placeable, refused for a reason worth telling an
     * operator, or simply unaskable.
     */
    private record KindGate(boolean placeable, @Nullable Violations reason) {

        static final KindGate OK = new KindGate(true, null);

        /** Excluded, with nothing an operator could act on -- an unreachable daemon. */
        static final KindGate UNASKABLE = new KindGate(false, null);
    }

    /**
     * Ask the KIND whether this host could run these settings.
     *
     * AIDEV-NOTE: a named refusal is KEPT because this host already passed every admission
     * gate, so "publish that image here" is the one actionable sentence in the whole walk;
     * folding it into no_placement_available is what would make
     * {@code host_prepared_image_missing} unreachable microcopy. An unreachable daemon is
     * excluded SILENTLY: the deploy there would fail on the same daemon, and pointing the
     * operator at a transient connection error as though it were the placement reason is
     * worse than the generic refusal.
     */
    private static @NonNull KindGate kindGateFor(int serverId, @NonNull Workload workload) {
        if (workload.handler() == null) {
            return KindGate.OK;
        }
        try {
            workload.handler().requirePlaceableOn(
                ServerModel.nameOf(serverId), workload.settings());
            return KindGate.OK;
        } catch (Violations refused) {
            return new KindGate(false, refused);
        } catch (RuntimeException unreachable) {
            return KindGate.UNASKABLE;
        }
    }

}
