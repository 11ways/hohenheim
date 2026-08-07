package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.docker.ResourceLimits;
import be.elevenways.hohenheim.server.process.ProcessCapacity;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.orm.query.QueryContext;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import be.elevenways.zenit.common.orm.quota.QuotaExceeded;
import be.elevenways.zenit.common.orm.quota.Quotas;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * ({@code host_capacity_unproven}, telling the operator which host to preflight). That is
 * also the reason a freshness bound ({@code capacity.facts_max_age_hours}) exists here
 * while the admission gate still has none: admission's staleness is an availability
 * decision about hosts already carrying production work, capacity's staleness would let a
 * placement reason about a machine whose RAM nobody has looked at in a year.
 *
 * AIDEV-NOTE: every terminating path must release, and InstanceService.destroy soft-
 * deletes through save() so the remove hooks NEVER fire there. The transitions handled
 * below are therefore the whole contract: create, soft-delete, restore, HOST CHANGE (the
 * migration/drain handoff -- a charge that did not move would drift the budget on every
 * drain), footprint change, and hard delete. A create refused AFTER this hook spent the
 * reservation unwinds with it, because the ledger rides the caller's transaction and
 * because every later refusal in the create funnel throws before save() returns.
 */
public final class InstanceCapacity {

    /** Consumer-namespaced bucket prefix; the host id follows it. */
    static final String BUCKET_PREFIX = "hohenheim:host_mem_mb:";

    /** Where the before-remove hook stashes what the after-remove hook releases. */
    private static final String DOOMED = "hohenheim.capacity.doomed";

    private static boolean installed;

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
        Integer reserve = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Capacity.HOST_MEMORY_RESERVE_MB);
        Double ratio = HohenheimSettings.VALUES.getValue(
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
                || !(capabilities.get("mem_total") instanceof Number bytes)) {
            return null;
        }
        long mb = bytes.longValue() / (1024L * 1024L);
        return mb > 0 ? mb : null;
    }

    /** Whether the stored reading is inside the declared freshness bound. */
    private static boolean readingIsFresh(@NonNull Row server) {
        Integer hours = HohenheimSettings.VALUES.getValue(
            HohenheimSettings.Capacity.FACTS_MAX_AGE_HOURS);
        if (hours == null || hours <= 0) {
            return true;
        }
        Instant probedAt = server.get(ServerModel.PROBED_AT);
        return probedAt != null
            && probedAt.isAfter(Instant.now().minus(Duration.ofHours(hours)));
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
            // The host budget is shared with the managed-process tier, whose children book
            // their own declared caps in a separate bucket -- see ProcessCapacity for why
            // it is separate and what the cross-subtraction costs.
            Quotas.reserve(bucketOf(serverId), amountMb, budget == null ? Long.MAX_VALUE
                : Math.max(0, budget - ProcessCapacity.bookedMbOn(serverId)));
        } catch (QuotaExceeded full) {
            throw Violations.ofForm(violation("host_capacity_reached")
                .withArg("name", hostLabel(serverId))
                .withArg("needed", amountMb)
                .withArg("free", Math.max(0, full.getLimit() - full.getUsed())));
        }
    }

    /** Hand booked memory back; a non-positive amount is a no-op, never a clamp slog. */
    public static void release(int serverId, long amountMb) {
        if (amountMb > 0) {
            Quotas.release(bucketOf(serverId), amountMb);
        }
    }

    /** The host's name, or a bare id spelling -- the refusal path may never itself fail. */
    public static @NonNull String hostLabel(int serverId) {
        Row server = Models.get(ServerModel.class).findById(serverId);
        String name = server != null ? server.get(ServerModel.NAME) : null;
        return name != null ? name : "#" + serverId;
    }

    // -- installation ---------------------------------------------------------

    /** Install the book/release hooks on the instance write funnel (MODULES stage). */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        InstanceModel.SCHEMA.addBeforeWriteHook(context -> {
            Row row = context.getRow();
            if (row == null) {
                return;
            }
            Row stored = storedOf(row);
            boolean storedLive = stored != null && stored.get(InstanceModel.DELETED_AT) == null;
            boolean willBeLive = effectiveDeletedAt(row, stored) == null;

            if (stored == null) {
                if (willBeLive) {
                    book(row, effectiveServerId(row, null));
                }
            } else if (storedLive && !willBeLive) {
                releaseStored(stored);
            } else if (!storedLive && willBeLive) {
                book(row, effectiveServerId(row, stored));
            } else if (storedLive) {
                rebook(row, stored);
            }
        });

        InstanceModel.SCHEMA.addBeforeRemoveHook(InstanceCapacity::captureDoomed);
        InstanceModel.SCHEMA.addAfterRemoveHook(InstanceCapacity::releaseDoomed);
    }

    // -- hook internals -------------------------------------------------------

    /**
     * Book a live row's footprint on its host and STAMP the booked amount, so the release
     * always hands back exactly what was taken even after the settings change.
     */
    private static void book(@NonNull Row row, @Nullable Integer serverId) {
        if (serverId == null) {
            return;
        }
        int amount = effectiveFootprintMb(row, storedOf(row));
        if (amount <= 0) {
            row.set(InstanceModel.CAPACITY_MB, 0);
            return;
        }
        reserve(serverId, amount);
        row.set(InstanceModel.CAPACITY_MB, amount);
    }

    /**
     * A live row that stays live: the host and the footprint may both have changed, and a
     * migration handoff is exactly the case where they change TOGETHER.
     *
     * AIDEV-NOTE: the move is release-then-reserve on purpose, and in that order. The
     * reverse would need the destination's headroom while the source still holds the
     * charge, so migrating between two equally-full hosts would refuse itself. Losing the
     * source's charge and then failing to book the destination leaves the budget too
     * GENEROUS on the source for one write -- the safe direction, and the write refuses so
     * the record never moves.
     */
    private static void rebook(@NonNull Row row, @NonNull Row stored) {
        Integer storedServer = stored.get(InstanceModel.SERVER_ID);
        Integer targetServer = effectiveServerId(row, stored);
        // A row that never had a host was never booked, so it has no missing stamp to
        // complain about -- reading one would slog a false accounting alarm on every edit.
        long booked = storedServer == null ? 0 : bookedOf(stored);
        int amount = effectiveFootprintMb(row, stored);
        boolean sameHost = targetServer != null && targetServer.equals(storedServer);
        if (sameHost && booked == amount) {
            return;
        }
        if (storedServer != null && booked > 0) {
            release(storedServer, booked);
        }
        if (targetServer != null && amount > 0) {
            reserve(targetServer, amount);
        }
        row.set(InstanceModel.CAPACITY_MB, targetServer == null ? 0 : amount);
    }

    private static void releaseStored(@NonNull Row stored) {
        Integer serverId = stored.get(InstanceModel.SERVER_ID);
        if (serverId == null) {
            return;
        }
        release(serverId, bookedOf(stored));
    }

    /**
     * What the stored row is holding: the STAMP when it has one, else the footprint its
     * settings imply.
     *
     * AIDEV-NOTE: the fallback exists for rows written before the stamp column and for a
     * row the backfill could not price; it is slogged rather than silent, because a
     * release computed from settings that changed since is exactly the accounting drift
     * the stamp exists to prevent.
     */
    private static long bookedOf(@NonNull Row stored) {
        Integer stamped = stored.get(InstanceModel.CAPACITY_MB);
        if (stamped != null) {
            return stamped;
        }
        int derived = footprintMbOf(stored);
        Blast.log("CAPACITY: instance", stored.get(InstanceModel.ID),
            "carries no booked amount; releasing the derived footprint", derived);
        return derived;
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
     */
    private static int effectiveFootprintMb(@NonNull Row row, @Nullable Row stored) {
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

    private static @Nullable Object effectiveDeletedAt(@NonNull Row row, @Nullable Row stored) {
        if (row.has(InstanceModel.DELETED_AT.getName())) {
            return row.get(InstanceModel.DELETED_AT.getName());
        }
        return stored != null ? stored.get(InstanceModel.DELETED_AT) : null;
    }

    private static @Nullable Row storedOf(@NonNull Row row) {
        if (!row.has(InstanceModel.ID.getName()) || row.get(InstanceModel.ID) == null) {
            return null;
        }
        return Models.get(InstanceModel.class).findById(row.get(InstanceModel.ID));
    }

    /** One doomed row's release: the host it was booked on and the booked amount. */
    private record Doomed(int serverId, long amountMb) {}

    private static void captureDoomed(@NonNull RemoveFromDatasource context) {
        Model model = context.getModel();
        if (model == null) {
            return;
        }
        QueryContext queryContext = context.getQueryContext();
        Criteria criteria = queryContext != null ? queryContext.getCriteria() : null;
        QueryBuilder<Row> builder = model.find();
        if (criteria != null) {
            builder.where(criteria);
        }
        List<Doomed> doomed = new ArrayList<>();
        for (Row row : builder.all()) {
            // Trashed rows already released on their soft-delete transition.
            if (row.get(InstanceModel.DELETED_AT) != null) {
                continue;
            }
            Integer serverId = row.get(InstanceModel.SERVER_ID);
            if (serverId == null) {
                continue;
            }
            long booked = bookedOf(row);
            if (booked > 0) {
                doomed.add(new Doomed(serverId, booked));
            }
        }
        if (!doomed.isEmpty()) {
            context.setAttribute(DOOMED, doomed);
        }
    }

    private static void releaseDoomed(@NonNull RemoveFromDatasource context) {
        if (!(context.getAttribute(DOOMED) instanceof List<?> doomed)) {
            return;
        }
        for (Object entry : doomed) {
            if (entry instanceof Doomed release) {
                release(release.serverId(), release.amountMb());
            }
        }
    }

    private static Microcopy violation(String key) {
        return Microcopy.of(key).withFilter("scope", "violations");
    }
}
