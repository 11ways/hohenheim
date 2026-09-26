package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.host.HostCapacityView;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.host.HostPreflight;
import be.elevenways.hohenheim.server.quota.ChargedDimension;
import be.elevenways.hohenheim.server.quota.ChargedModel;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.time.Now;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PER-HOST memory capacity: the InstanceQuota shape, but the bucket is a HOST rather than
 * an owner, so the thing being rationed is physics instead of policy.
 *
 * THE PRODUCT DECISION (2026-08-07), which the previous wave deferred on an input problem
 * and which is recorded in full in docs/proxmox-use-inventory.md item 12:
 *
 * A workload is admitted as its DECLARED {@code memory_limit_mb}, and when it declares
 * none, as its KIND's declared footprint ({@link InstanceKindHandler#defaultFootprintMb}).
 * The denominator is therefore never zero -- the exact defect that made a limits-summed
 * budget decoration. And because {@code ResourceLimits.fromSettings(settings, default)}
 * hands that same number to the daemon as the cgroup / VM memory cap, the booking is not
 * an estimate: a workload cannot grow past what was booked for it. Charge == cap.
 *
 * What the operator gets asked in exchange: nothing. The kind's footprint is the DEFAULT
 * value of a field the form already has, so declaring a bigger workload is one number on
 * a form the operator was already looking at.
 *
 * The two honest questions, answered:
 *
 * 1. The declared footprints exceed the host: the reservation refuses BY NAME
 *    ({@code host_capacity_reached}, naming the host, what was asked and what is free),
 *    and placement never offers such a host in the first place, so the common path is a
 *    different host rather than a refusal. Overcommit is available and DELIBERATE
 *    ({@code capacity.memory_overcommit_ratio}) -- an operator may book more than the
 *    host has, and the setting's own description says who settles that bet.
 * 2. The footprints fit but the host is really full: for MEMORY this cannot silently
 *    happen, because every booking is also an enforced cap. It can happen for anything
 *    NOT booked -- page cache, the daemon itself, a stack container, disk and CPU -- which
 *    is what {@code capacity.host_memory_reserve_mb} exists for, and which is the honest
 *    limit of this gate: it rations declared workload memory, and claims nothing else.
 *
 * AIDEV-NOTE: a workload is booked on its RECORD'S EXISTENCE, not on whether it is
 * running. A stopped guest can be started again without asking anyone, so a control plane
 * that only booked running workloads would accept guests it then could not start -- the
 * refusal would land on START, after the operator already believed the thing existed. The
 * visible consequence, and it is intended: a fleet of defined-but-stopped guests consumes
 * the host budget. Freeing it means destroying a record (or shrinking its declared
 * memory), which is exactly the decision an operator should be making.
 *
 * AIDEV-NOTE: the budget is read from the STORED preflight fact {@code mem_total}, which
 * is a measurement, and measurements go stale. An unreadable or too-old reading yields NO
 * budget -- never an unlimited one -- and a host with no budget is one
 * {@link InstancePlacement} refuses to CHOOSE, by its own name
 * ({@code host_capacity_unproven}, telling the operator which host to preflight). The
 * freshness bound is {@code capacity.facts_max_age_hours}, and it answers a different
 * question from {@code hosts.contact_max_age_minutes}: this one asks how old a MEASUREMENT
 * may be before it stops being a budget, that one asks how long a host may go without
 * ANSWERING before it stops receiving work. A host can fail either independently -- a
 * chatty daemon nobody has preflighted in a year, or a freshly preflighted host that went
 * silent an hour later -- so neither bound subsumes the other. The kernel-truth gate
 * deliberately still has no bound at all, for the reason recorded on
 * {@code HostAdmission.requireKernelTruth}: it pairs its stored evidence with a LIVE read
 * of whether the record still declares the lane.
 *
 * AIDEV-NOTE: every terminating path must release, and InstanceService.destroy soft-
 * deletes through save() so the remove hooks NEVER fire there. The transitions
 * {@link ChargedModel} runs for {@link #HOST_MEMORY} are therefore the whole contract:
 * create, soft-delete, restore, HOST CHANGE (an admin repointing InstanceModel.SERVER_ID
 * through the CMS form -- NOT the migration handoff, which is a fenced updateAll that fires
 * no hooks at all and moves its charge explicitly through openMigrationWindow), footprint
 * change, and hard delete. A refusal later in the same charge hook (the root disk) unwinds
 * this booking; a refusal after the hook does so only inside an ambient transaction, and
 * the QuotaReconciler repairs the rest.
 */
public final class InstanceCapacity {

    /** Consumer-namespaced bucket prefix; the host id follows it. */
    static final String BUCKET_PREFIX = "hohenheim:host_mem_mb:";

    private InstanceCapacity() {
    }

    /** The capacity bucket of one host; host ids are short, so this never needs folding. */
    public static @NonNull String bucketOf(int serverId) {
        return BUCKET_PREFIX + serverId;
    }

    /** How much workload memory (MB) is currently booked on a host. */
    public static long bookedMbOn(int serverId) {
        return Quotas.usedOf(bucketOf(serverId));
    }

    /**
     * THE ceiling the instance tier is judged against on one host.
     *
     * AIDEV-NOTE: ONE derivation, on purpose, and it is the fix for a chooser and a write
     * that disagreed -- {@link #reserve} and {@link InstancePlacement#chooseForBucket} once
     * compared against different numbers, so placement could CHOOSE a host whose write then
     * refused {@code host_capacity_reached}. Both sides call THIS; never re-spell it at a
     * call site. The subtraction it used to carry was the managed-process tier's separate
     * bucket, deleted with the host-user lane (phase-0 design section 7); the instance tier
     * is now the only bucket on a host.
     */
    public static long bookableMbOn(int serverId, long budgetMb) {
        return Math.max(0, budgetMb);
    }

    /**
     * THE capacity ledger of one host as every surface reads it: the admin overview page's
     * bar and facts, and the {@code /api/v1/hosts} lane behind {@code hoh host}.
     *
     * AIDEV-NOTE: one derivation on purpose. The panel and the CLI quoting different
     * numbers for the same host is the whole reason the operator was reading
     * {@code docker stats} instead. {@code measured} false is an explicit state, never a
     * zero budget: a host whose reading cannot carry a decision is not an empty host.
     */
    public static @NonNull HostCapacityView viewOf(@NonNull Row server, int serverId) {
        Long budget = budgetMbOf(server);
        boolean hasReading = server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> map
            && map.get(HostPreflight.MEM_TOTAL_FACT) instanceof Number;
        Instant measuredAt = HostPreflight.factMeasuredAt(server, HostPreflight.MEM_TOTAL_FACT);
        if (measuredAt == null && hasReading) {
            measuredAt = server.get(ServerModel.PROBED_AT);
        }
        Integer maxAge = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS);
        return new HostCapacityView(
            budget != null,
            budget == null && hasReading,
            budget != null ? clampInt(budget) : 0,
            clampInt(bookedMbOn(serverId)),
            budget != null ? clampInt(bookableMbOn(serverId, budget)) : 0,
            measuredAt != null ? measuredAt.toString() : null,
            maxAge != null ? maxAge : 0);
    }

    private static int clampInt(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    // -- the budget -----------------------------------------------------------

    /**
     * The bookable workload memory (MB) of a host, or null when the stored reading cannot
     * carry a decision (never probed, no memory fact, or older than the freshness bound).
     */
    public static @Nullable Long budgetMbOf(@NonNull Row server) {
        Long totalMb = measuredMemoryMbOf(server);
        if (totalMb == null || !readingIsFresh(server)) {
            return null;
        }
        Integer reserve = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB);
        Double ratio = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Capacity.MEMORY_OVERCOMMIT_RATIO);
        long bookable = totalMb - (reserve == null ? 0 : Math.max(0, reserve));
        if (bookable <= 0) {
            return 0L;
        }
        double factor = ratio == null || ratio <= 0 ? 1.0 : ratio;
        return (long) (bookable * factor);
    }

    /** The host's measured total memory in MB, from the stored preflight facts. */
    private static @Nullable Long measuredMemoryMbOf(@NonNull Row server) {
        if (!(server.get(ServerModel.CAPABILITIES) instanceof Map<?, ?> capabilities)
                || !(capabilities.get(HostPreflight.MEM_TOTAL_FACT) instanceof Number bytes)) {
            return null;
        }
        long mb = bytes.longValue() / (1024L * 1024L);
        return mb > 0 ? mb : null;
    }

    /**
     * Whether the stored reading is inside the declared freshness bound.
     *
     * AIDEV-NOTE: the age is the MEASUREMENT's own, never {@code probed_at}. Since
     * {@link be.elevenways.hohenheim.server.host.HostPreflight#store} merges rather than
     * replaces, a preflight that could not reach the daemon stamps {@code probed_at} while
     * re-measuring nothing -- reading that column here would refresh the apparent age of a
     * number the run never looked at, which is the same lie the wholesale replace told in
     * the other direction. The fallback exists only for records stored before per-fact
     * provenance, whose reading really is as old as their last probe.
     */
    private static boolean readingIsFresh(@NonNull Row server) {
        Integer hours = Zenit.SETTINGS_VALUES.getValue(
            HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS);
        if (hours == null || hours <= 0) {
            return true;
        }
        Instant measuredAt = HostPreflight.factMeasuredAt(server,
            HostPreflight.MEM_TOTAL_FACT);
        if (measuredAt == null) {
            measuredAt = server.get(ServerModel.PROBED_AT);
        }
        return measuredAt != null
            && measuredAt.isAfter(Now.instant().minus(Duration.ofHours(hours)));
    }

    // -- the footprint --------------------------------------------------------

    /**
     * The memory (MB) one instance row is booked at: its declared limit, else its kind's
     * declared footprint.
     *
     * @return the footprint, or 0 when the kind is unknown (a kind whose handler class is
     *         gone cannot be deployed either, so booking nothing for it is honest)
     */
    public static int footprintMbOf(@NonNull Row instance) {
        InstanceKindHandler handler = InstanceKinds.getHandler(instance.get(InstanceModel.KIND));
        if (handler == null) {
            return 0;
        }
        return footprintMbOf(handler, settingsOf(instance));
    }

    /** The settings map of an instance row; a SchemaField answers Object. */
    @SuppressWarnings("unchecked")
    static @NonNull Map<String, Object> settingsOf(@NonNull Row instance) {
        return instance.get(InstanceModel.SETTINGS) instanceof Map<?, ?> map
            ? (Map<String, Object>) map : Map.of();
    }

    /** The same derivation from a kind and its settings, before any record exists. */
    public static int footprintMbOf(@NonNull InstanceKindHandler handler,
                                    @NonNull Map<String, Object> settings) {
        return ResourceLimits.fromSettings(settings, handler.defaultFootprintMb(settings))
            .bookedMemoryMb();
    }

    // -- the gate -------------------------------------------------------------

    /**
     * Book {@code amountMb} on a host, atomically against its budget.
     *
     * AIDEV-NOTE: THE scope decision, and it is the difference between a gate and a
     * roadblock. A host with no usable reading is NOT refused here -- the booking still
     * happens (usage is counted either way, the MAX_INSTANCES_PER_OWNER precedent
     * verbatim) and it is judged against nothing. That is not "pretending capacity
     * exists": {@link InstancePlacement} never CHOOSES an unmeasured host, so the only way
     * to land on one is for an operator to name it, or for an operator-tier workload (a
     * site's lowered container on the implicit local daemon) to be converged onto it. Both
     * are explicit operator choices about the operator's own machine, and refusing them
     * would mean a fresh install could not run a single site until someone ran preflight.
     * The moment a preflight lands, the ledger already holds honest numbers.
     *
     * The property that matters survives intact: on every host placement can choose, the
     * denominator is a real measurement and this gate CAN fail.
     *
     * @throws Violations {@code host_capacity_reached} when the workload does not fit
     */
    public static void reserve(int serverId, long amountMb) {
        if (amountMb <= 0) {
            return;
        }
        Row server = Models.get(ServerModel.class).findById(serverId);
        Long budget = server == null ? null : budgetMbOf(server);
        try {
            Quotas.reserve(bucketOf(serverId), amountMb,
                budget == null ? Long.MAX_VALUE
                    : bookableMbOn(serverId, budget) + pendingReleaseOn(serverId));
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(violation("host_capacity_reached")
                .withArg("name", hostLabel(serverId))
                .withArg("needed", amountMb)
                .withArg("free", Math.max(0, full.getLimit() - full.getUsed())));
        }
    }

    /** Per thread: host id -> memory a workload this thread is replacing is about to release. */
    private static final ThreadLocal<Map<Integer, Long>> PENDING_RELEASE =
        ThreadLocal.withInitial(HashMap::new);

    /**
     * Run {@code body} with {@code amountMb} on {@code serverId} counted as ABOUT TO BE
     * RELEASED, so a reservation the body takes may exceed the host budget by exactly
     * that much -- the shape of a database moving onto a shared engine: the engine must
     * be booked while the dedicated container it absorbs still holds its charge, and a
     * full host refused the engine for the very memory the move was freeing (robbedoes,
     * 2026-09-02: 9,984 booked of 10,915, engine 1,024, dedicated 512 about to go).
     *
     * AIDEV-NOTE: this is a CREDIT ON THE LIMIT, never a release: the bucket really is
     * charged for both until the replaced workload's row is destroyed, which is the
     * migration window's "booked on both" rule again. A move that fails after the
     * reservation leaves the host over its budget by at most the credit -- the
     * survivable direction (placement refuses one workload too many until the next
     * successful move or an operator resize) -- and never hands memory out twice. Host
     * side only: the owner memory quota is tenant policy and the engine is charged to
     * the operator regardless.
     */
    public static <T> T withPendingRelease(int serverId, long amountMb, @NonNull Supplier<T> body) {
        if (amountMb <= 0) {
            return body.get();
        }
        Map<Integer, Long> credits = PENDING_RELEASE.get();
        long before = credits.getOrDefault(serverId, 0L);
        credits.put(serverId, before + amountMb);
        try {
            return body.get();
        } finally {
            if (before == 0) {
                credits.remove(serverId);
            } else {
                credits.put(serverId, before);
            }
            if (credits.isEmpty()) {
                PENDING_RELEASE.remove();
            }
        }
    }

    private static long pendingReleaseOn(int serverId) {
        return PENDING_RELEASE.get().getOrDefault(serverId, 0L);
    }

    /** Hand booked memory back; a non-positive amount is a no-op, never a clamp slog. */
    public static void release(int serverId, long amountMb) {
        if (amountMb > 0) {
            Quotas.release(bucketOf(serverId), amountMb);
        }
    }

    // -- the migration window -------------------------------------------------

    /**
     * THE migration ledger contract, and the reason it is a pair of explicit calls rather
     * than a hook: {@link InstanceOperationGuard#handoff} repoints the host with a FENCED
     * {@code updateAll}, which by the ORM's own contract fires no write hooks -- so
     * the host-memory rebook never ran on a migration or a drain. The source kept its charge
     * forever (a fully drained host read as fully booked and {@code chooseForBucket} then
     * refused it {@code no_placement_capacity} -- draining a host removed it from the pool
     * permanently), the destination booked nothing, and a later {@code save()} could not
     * heal it because a rebook moves nothing when host and amount both match.
     *
     * ORDERING, which is the real question, since the ledger move and the fenced write can
     * never be one statement: the destination is booked when the migration WINDOW OPENS
     * (here), and the source is released only when the fenced handoff has MATCHED. For the
     * whole window the workload is charged on BOTH hosts, which is exactly right -- its
     * data really does exist on both -- and over-conservative is the only acceptable
     * direction: booked on both refuses one placement too many, booked on NEITHER hands
     * the same megabytes out twice.
     *
     * That also makes the crash windows settle honestly, because the ledger is a table and
     * survives the controller: a killed controller leaves the destination booking behind,
     * and the boot settle performs exactly ONE of the two releases -- forward
     * ({@code handoff}) releases the source, rollback ({@code clearMigration}) releases the
     * destination. Neither ever re-reserves, so a crash cannot double-book. The one
     * remaining gap is deliberate and is why this runs BEFORE {@code stampMigrating}: a
     * kill between the two statements leaks a destination booking no settle will find,
     * because the record never became recoverable. Booked-too-much is survivable
     * (a preflight-driven number an operator can see); the reverse order would have a
     * settle release a booking that was never taken, and an over-release is the shape that
     * ZEROES a bucket and wipes every other live workload's charge on that host.
     *
     * AIDEV-NOTE: the reserved amount is RETURNED so {@code stampMigrating} can persist
     * it as {@code MIGRATE_RESERVED_MB} in the very statement that opens the window, and
     * both settle halves release that stored amount ({@link #windowReservedOf}) instead
     * of recomputing it. The recompute was the drift bug: a window is minutes long, and
     * any change to the row's booked amount inside it (a footprint edit, a kind default
     * changing across an upgrade) made the settle release a number the window never
     * reserved -- the over-release shape the paragraph above names. The mid-window freeze
     * ({@link #refuseMidWindowMove}) closes the editing lane; the stamp closes every other one.
     *
     * @return the amount (MB) reserved on the destination
     * @throws Violations {@code host_capacity_reached} when the destination has no room
     */
    static long openMigrationWindow(int instanceId, int targetServerId) {
        long amount = bookedOfInstance(instanceId);
        reserve(targetServerId, amount);
        return amount;
    }

    /** What one instance record holds against a host bucket; 0 for an absent record. */
    public static long bookedOfInstance(int instanceId) {
        Row row = Models.get(InstanceModel.class).findById(instanceId);
        return row == null ? 0 : bookedOf(row);
    }

    /**
     * The exact amount the open migration window reserved on the destination: the stamp
     * written by {@code stampMigrating}, else (a window opened before the stamp existed)
     * the old recompute, logged because it can be the wrong number.
     */
    static long windowReservedOf(@Nullable Row stored) {
        if (stored == null) {
            return 0;
        }
        Integer reserved = stored.get(InstanceModel.MIGRATE_RESERVED_MB);
        if (reserved != null) {
            return reserved;
        }
        long derived = bookedOf(stored);
        Blast.log("CAPACITY: migration window of instance", stored.get(InstanceModel.ID),
            "carries no reserved stamp; releasing the current booking", derived);
        return derived;
    }

    /**
     * What the SOURCE host is actually holding for a mid-migration row: its stamp, or 0
     * for a hostless row -- a row with no host was never booked anywhere
     * ({@link #book} skips it), so a handoff from the implicit local daemon must not
     * release a charge the local bucket never received.
     */
    static long sourceBookedOf(@Nullable Row stored) {
        if (stored == null || stored.get(InstanceModel.SERVER_ID) == null) {
            return 0;
        }
        return bookedOf(stored);
    }

    /** The host's name, or a bare id spelling -- the refusal path may never itself fail. */
    public static @NonNull String hostLabel(int serverId) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        String name = server != null ? server.get(ServerModel.NAME) : null;
        return name != null ? name : "#" + serverId;
    }

    // -- the dimension ----------------------------------------------------------

    /**
     * The host-memory dimension of {@link ChargedModel#INSTANCES}: a live row's footprint on
     * the host it names, the booked amount stamped as {@code capacity_mb} so the release
     * always hands back exactly what was taken even after the settings change.
     *
     * AIDEV-NOTE: the bucket FOLLOWS the row's host, so a host change is a bucket move --
     * ChargedModel releases the source first and then reserves the destination. The reverse
     * would need the destination's headroom while the source still holds the charge, so
     * migrating between two equally full hosts would refuse itself; a refused destination
     * now also unwinds the source release, so the record neither moves nor loses its booking.
     * A row with no host (the implicit local daemon's null spelling) was never booked.
     */
    public static final ChargedDimension HOST_MEMORY = new ChargedDimension() {

        @Override
        public @NonNull String key() {
            return "host_memory";
        }

        @Override
        public @NonNull String prefix() {
            return BUCKET_PREFIX;
        }

        @Override
        public @Nullable Charge held(@NonNull Row stored) {
            Integer serverId = stored.get(InstanceModel.SERVER_ID);
            if (serverId == null) {
                return null;
            }
            return new Charge(bucketOf(serverId), bookedMbOf(stored),
                stored.get(InstanceModel.CAPACITY_MB) == null);
        }

        @Override
        public @Nullable Charge claim(@NonNull Row row, @Nullable Row stored,
                                      @NonNull Transition transition) {
            Integer serverId = effectiveServerId(row, stored);
            Charge claim = serverId == null ? null
                : new Charge(bucketOf(serverId), effectiveFootprintMb(row, stored), false);
            if (transition == Transition.REBOOK && stored != null) {
                refuseMidWindowMove(stored, claim);
            }
            return claim;
        }

        @Override
        public void reserve(@NonNull String bucket, long amount) {
            InstanceCapacity.reserve(serverIdOf(bucket), amount);
        }

        @Override
        public void stamp(@NonNull Row row, @Nullable Charge charge) {
            row.set(InstanceModel.CAPACITY_MB, charge == null ? 0 : (int) charge.amount());
        }

        /**
         * A live row mid-migration holds its booking on the SOURCE and the open window's on
         * the DESTINATION ({@code migrate_reserved_mb}); the reconcile must count both, or it
         * releases the window's booking as drift and the settle then over-releases.
         */
        @Override
        public @NonNull List<Charge> reconciled(@NonNull Row live) {
            List<Charge> charges = new ArrayList<>();
            Charge held = this.held(live);
            if (held != null) {
                charges.add(held);
            }
            Integer target = live.get(InstanceModel.MIGRATE_TARGET_ID);
            if (target != null) {
                Integer reserved = live.get(InstanceModel.MIGRATE_RESERVED_MB);
                charges.add(new Charge(bucketOf(target),
                    reserved != null ? Math.max(0, reserved) : bookedMbOf(live), reserved == null));
            }
            return charges;
        }
    };

    /**
     * THE mid-window freeze (fix decision 2026-08-10, paired with the MIGRATE_RESERVED_MB
     * stamp).
     *
     * AIDEV-NOTE: the charge hook runs on ANY save of a live row -- a plain CMS form edit
     * included -- and a migration window is minutes long. Letting a footprint or host change
     * land mid-window restamps CAPACITY_MB and shifts the source bucket while the window's
     * destination booking stays at the OLD amount, so the settle's arithmetic can no longer
     * match what was reserved. Amount-neutral edits (a rename) stay allowed; requireOperable
     * cannot cover this lane because it guards only the service funnels.
     *
     * @throws Violations {@code instance_busy} when the write would move a mid-window booking
     */
    private static void refuseMidWindowMove(@NonNull Row stored,
                                            ChargedDimension.@Nullable Charge claim) {
        ChargedDimension.Charge held = HOST_MEMORY.held(stored);
        boolean moves = held == null ? claim != null : !held.sameBooking(claim);
        if (moves && InstanceModel.STATUS_MIGRATING.equals(stored.get(InstanceModel.STATUS))) {
            throw Violations.ofForm(violation("instance_busy")
                .withArg("name", String.valueOf((Object) stored.get(InstanceModel.NAME)))
                .withArg("status", InstanceModel.STATUS_MIGRATING));
        }
    }

    /** The host id behind one of this dimension's buckets -- the inverse of {@link #bucketOf}. */
    private static int serverIdOf(@NonNull String bucket) {
        return Integer.parseInt(bucket.substring(BUCKET_PREFIX.length()));
    }

    /**
     * THE amount one instance row holds against its host bucket: the STAMP when it has
     * one, else the footprint its settings imply.
     *
     * AIDEV-NOTE: public because every OTHER reader of this fact -- the reconciler's
     * expected-bucket sum and the host API's per-workload rows -- must get the number the
     * release will actually hand back. Two spellings of "what is this row booked at" is
     * how a reconciler reports drift that does not exist.
     */
    public static long bookedMbOf(@NonNull Row instance) {
        Integer stamped = instance.get(InstanceModel.CAPACITY_MB);
        return Math.max(0, stamped != null ? stamped : footprintMbOf(instance));
    }

    /**
     * {@link #bookedMbOf} on a RELEASE path, which slogs the missing stamp.
     *
     * AIDEV-NOTE: the fallback exists for rows written before the stamp column and for a
     * row the backfill could not price; it is slogged rather than silent, because a
     * release computed from settings that changed since is exactly the accounting drift
     * the stamp exists to prevent. The slog lives HERE and not in the shared arithmetic:
     * a read of the host page must not sound an accounting alarm.
     */
    private static long bookedOf(@NonNull Row stored) {
        if (stored.get(InstanceModel.CAPACITY_MB) == null) {
            Blast.log("CAPACITY: instance", stored.get(InstanceModel.ID),
                "carries no booked amount; releasing the derived footprint",
                footprintMbOf(stored));
        }
        return bookedMbOf(stored);
    }

    /** The server_id the write will END UP with: staged when carried, else stored. */
    private static @Nullable Integer effectiveServerId(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.SERVER_ID.getName())) {
            return row.get(InstanceModel.SERVER_ID);
        }
        return stored != null ? stored.get(InstanceModel.SERVER_ID) : null;
    }

    /**
     * The footprint the write will END UP being charged -- a partial CMS update carries
     * only the changed keys (the SiteDomainModel.effective idiom), so pricing the staged
     * row alone would charge a settings-less edit as a zero-footprint workload.
     *
     * AIDEV-NOTE: package-visible because {@link InstanceQuota}'s per-OWNER memory budget
     * must book the very same number this host budget does. Two derivations of "how much
     * memory is this write worth" is how one ledger ends up charging what the other does
     * not, and charge == cap only holds while there is ONE answer.
     */
    static int effectiveFootprintMb(@NonNull Row row, @Nullable Row stored) {
        String kind = row.has(InstanceModel.KIND.getName()) || stored == null
            ? row.get(InstanceModel.KIND) : stored.get(InstanceModel.KIND);
        InstanceKindHandler handler = InstanceKinds.getHandler(kind);
        if (handler == null) {
            return 0;
        }
        Map<String, Object> settings =
            row.has(InstanceModel.SETTINGS.getName()) || stored == null
                ? settingsOf(row) : settingsOf(stored);
        return footprintMbOf(handler, settings);
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
