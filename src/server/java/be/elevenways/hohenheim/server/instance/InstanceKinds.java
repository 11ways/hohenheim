package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.app.PutOnlineGroup;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.instance.WorkloadIsolation;
import be.elevenways.hohenheim.app.PutOnlineReach;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.model.InstanceStatus;
import be.elevenways.hohenheim.model.ServerModel;
import be.elevenways.hohenheim.server.runtime.WorkloadLane;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Registration hook for the compile-time-discovered instance kinds plus the
 * server-side handler lookup (the UpstreamKindHandlers shape). Concrete InstanceKindHandler
 * implementations arrive via the generated BlastAutoLoadInit; nothing is
 * registered manually.
 *
 * AIDEV-NOTE: a handler is read out of THE registry, never a private handler map beside it;
 * an entry that is not a server handler fails closed as "unknown kind".
 */
public final class InstanceKinds {

    private InstanceKinds() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(InstanceKindHandler handler) {
        InstanceKindRegistry.REGISTRY.add(handler.typeId(), handler);
    }

    /** Deliberately points a kind's id at another handler (a test standing a fake in for a production kind). */
    public static void replace(InstanceKindHandler handler) {
        InstanceKindRegistry.registry().replace(handler.typeId(), handler);
    }

    public static InstanceKindHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return handlerFor(id);
    }

    /** @return the handler of the instance row's kind, or null for an unknown kind */
    public static @Nullable InstanceKindHandler handlerOf(@NonNull Row instance) {
        return getHandler(instance.get(InstanceModel.KIND));
    }

    /** @return how the instance is reached from outside, by its kind and its settings; NONE for an unknown kind */
    public static @NonNull PutOnlineReach reachOf(@NonNull Row instance) {
        InstanceKindHandler handler = handlerOf(instance);
        return handler == null ? PutOnlineReach.NONE : handler.reachOf(InstanceModel.settingsOf(instance));
    }

    private static @Nullable InstanceKindHandler handlerFor(@Nullable Identifier id) {
        return id != null && InstanceKindRegistry.registry().get(id) instanceof InstanceKindHandler handler
            ? handler : null;
    }

    /**
     * @return whether records of this kind deploy through the release engine rather than
     *         owning a container; an unknown kind is NOT release-managed (fail closed)
     */
    public static boolean isReleaseManaged(@Nullable String kind) {
        InstanceKindHandler handler = kind == null ? null : getHandler(kind);
        return handler != null && handler.releaseManaged();
    }

    /**
     * Whether a PERSON may power a record of this kind, or whether its lifecycle belongs
     * to the product tier that generated it.
     *
     * AIDEV-NOTE: it reads {@code generatedOnly()} rather than adding a fact: a kind only
     * its owning tier may WRITE is a kind only its owning tier may DEPLOY -- the engine
     * container of a database is started by allocating the database, never by an operator
     * pressing Deploy in the fleet list. Adding a second declaration would let the two
     * drift, which is the defect requireAuthorable's note already records.
     *
     * @return false for an UNKNOWN kind (fail closed): a kind with no handler has no
     *         driver to deploy through, so the affordance could only refuse
     */
    public static boolean isUserDeployable(@Nullable String kind) {
        InstanceKindHandler handler = getHandler(kind);
        return handler != null && !handler.generatedOnly();
    }

    /**
     * Refuse a kind only an owning tier may author, in the ONE place that decides it.
     *
     * AIDEV-NOTE: the offer ({@link #authorableOptions}) and this refusal answer to one
     * declaration -- {@code generatedOnly()} -- for the reason requirePlaceableOn records:
     * a second copy of a refusal is the drift defect this seam removes. OwnedInstances'
     * write hook is a CALLER of this, not a second spelling of it.
     *
     * @throws Violations when the kind's handler declares itself generated-only
     */
    public static void requireAuthorable(@Nullable String kind) {

        InstanceKindHandler handler = getHandler(kind);

        if (handler == null || !handler.generatedOnly()) {
            return;
        }

        // getLabel(), never getDisplayName(): the display name is an English literal and
        // this sentence is translated, so the raw name would render a half-Dutch refusal.
        // A Microcopy ARGUMENT resolves in the reader's locale (protoblast MessageEvaluator).
        throw Violations.ofField(InstanceModel.KIND.getName(), kind,
            HohenheimMicrocopy.VIOLATIONS.of("instance_kind_owner_managed")
                .withArg("kind", handler.getLabel()));
    }

    /**
     * THE kind-versus-host runtime comparison, and the one spelling of its refusal.
     *
     * AIDEV-NOTE: the host runtime is passed in rather than derived from a row, because
     * the three callers legitimately disagree about an ABSENT host -- resolve folds a
     * missing row onto the local docker daemon, a migration target names it "absent" --
     * and only the comparison and the message must exist once.
     *
     * @return the named refusal, or null when the host runs what the kind requires
     */
    public static @Nullable Microcopy runtimeMismatch(@NonNull String hostName,
                                                      @NonNull String hostRuntime,
                                                      @NonNull Set<String> supportedRuntimes) {
        if (supportedRuntimes.contains(hostRuntime)) {
            return null;
        }
        return HohenheimMicrocopy.VIOLATIONS.of("host_runtime_mismatch")
            .withArg("name", hostName)
            .withArg("runtime", ServerModel.runtimeLabel(hostRuntime))
            .withArg("required", ServerModel.runtimeLabels(supportedRuntimes));
    }

    /** @throws Violations naming the host when its runtime is not one the kind supports */
    public static void requireRuntimeMatch(@NonNull String hostName, @NonNull String hostRuntime,
                                           @NonNull Set<String> supportedRuntimes) {
        Microcopy refusal = runtimeMismatch(hostName, hostRuntime, supportedRuntimes);
        if (refusal != null) {
            throw Violations.ofForm(refusal);
        }
    }

    /**
     * The kinds a human may actually create, as select options.
     *
     * AIDEV-NOTE: derived by SKIPPING what requireAuthorable refuses, never by a hand-kept
     * list -- a seventh kind answers for itself. Iteration follows REGISTRY order because
     * registry order is the display order EnumBadgeState derives its badge colours from,
     * so the picker and the badges must agree. This narrows the
     * OFFER only: every label-rendering path reads EnumField.getValues() and still
     * enumerates the whole registry, so an existing generated-only row keeps its label.
     */
    public static @NonNull List<FieldOption<String>> authorableOptions() {

        List<FieldOption<String>> options = new ArrayList<>();

        for (InstanceKindInfo entry : InstanceKindRegistry.registry()) {

            Identifier id = InstanceKindRegistry.registry().idOf(entry);

            if (id == null) {
                continue;
            }

            // generatedOnly() is a SERVER declaration, so the skip asks the entry as a handler.
            InstanceKindHandler handler = handlerFor(id);

            if (handler != null && handler.generatedOnly()) {
                continue;
            }

            Icon icon = entry.getIcon();
            FieldOption<String> option = FieldOption.of(id.toString(), entry.getLabel())
                .withDescription(entry.getDescription());

            options.add(icon == null ? option : option.withIcon(icon.name()));
        }

        return options;
    }

    /**
     * The kinds a human may create, each card saying what it needs when no host of a runtime it supports exists yet:
     * an LXC or VM card on an installation without an Incus host used to read like any other choice.
     *
     * AIDEV-NOTE: the card stays CHOOSABLE. The host pick's narrowing is the gate and explains an empty result itself
     * ("No admitted host currently accepts this workload"), so a dead card would only hide kinds on a fresh install
     * whose one host is not admitted yet, and a disabled value would also refuse the edit of a record that has it.
     * Inventory, not admission, decides: an inventoried host that is not admitted yet is the host pick's story.
     */
    public static @NonNull List<FieldOption<String>> placeableOptions() {
        Set<String> inventoried = new HashSet<>();
        for (Row server : Models.get(ServerModel.class).find().all()) {
            inventoried.add(ServerModel.runtimeOf(server));
        }
        List<FieldOption<String>> options = new ArrayList<>();
        for (FieldOption<String> option : authorableOptions()) {
            InstanceKindHandler handler = getHandler(option.value());
            if (handler == null || handler.supportedRuntimes().stream().anyMatch(inventoried::contains)) {
                options.add(option);
                continue;
            }
            Set<String> runtimes = new TreeSet<>(handler.supportedRuntimes());
            options.add(option.withDescription(runtimes.size() == 1
                ? HohenheimMicrocopy.INSTANCE.of("kind_needs_runtime_host")
                    .withArg("runtime", ServerModel.RUNTIME.getValues().get(runtimes.iterator().next()).getLabel())
                : HohenheimMicrocopy.INSTANCE.of("kind_needs_host")));
        }
        return options;
    }

    /**
     * The kind cards a form shows for {@code record}: every placeable kind on a create, only the record's own
     * {@link #kindChangesOf} on a stored one.
     *
     * @param record the edited record from the form's edit context, null (or an unsaved row) on a create
     */
    public static @NonNull List<FieldOption<String>> kindOptionsFor(@Nullable Object record) {
        List<FieldOption<String>> placeable = placeableOptions();
        if (!(record instanceof Row stored) || !stored.has(InstanceModel.ID.getName())
                || stored.get(InstanceModel.ID) == null) {
            return placeable;
        }
        Set<String> changes = kindChangesOf(stored);
        List<FieldOption<String>> options = new ArrayList<>();
        for (FieldOption<String> option : placeable) {
            if (changes.contains(option.value())) {
                options.add(option);
            }
        }
        return options;
    }

    /**
     * Whether deploying this record means deploying CODE: a release-managed kind always does, another kind when it
     * says the record builds a source of its own ({@link InstanceKindHandler#deploysSource}). The start verb and the
     * Deployments tab both read this.
     *
     * @return false for an unknown kind (fail closed)
     */
    public static boolean deploysCode(@NonNull Row instance) {
        InstanceKindHandler handler = handlerOf(instance);
        return handler != null && (handler.releaseManaged() || handler.deploysSource(instance));
    }

    /**
     * The "Put something online" group whose words the record's start verb speaks: none where it deploys code (it
     * reads Deploy), else its kind's own group. The verb's words and its availability both read this.
     */
    public static @Nullable PutOnlineGroup startGroupOf(@NonNull Row instance) {
        InstanceKindHandler kind = handlerOf(instance);
        return kind == null || deploysCode(instance) ? null : kind.putOnlineGroup();
    }

    /**
     * Whether the record's start verb is offered: always where it deploys (deploying again ships it again), and where
     * its group only starts what is down ({@link PutOnlineGroup#startsOnlyWhenDown}, a machine) only while its status
     * says a start would bring something up ({@link InstanceStatus#startable}); an unknown status offers none.
     */
    public static boolean offersStart(@NonNull Row instance) {
        PutOnlineGroup group = startGroupOf(instance);
        if (group == null || !group.startsOnlyWhenDown()) {
            return true;
        }
        InstanceStatus status = InstanceStatus.forToken(instance.get(InstanceModel.STATUS));
        return status != null && status.startable();
    }

    /** Stored kind values whose handler passes the predicate, registry order. */
    public static @NonNull List<String> kindsWhere(@NonNull Predicate<InstanceKindHandler> predicate) {
        List<String> kinds = new ArrayList<>();
        for (InstanceKindInfo entry : InstanceKindRegistry.registry()) {
            Identifier id = InstanceKindRegistry.registry().idOf(entry);
            InstanceKindHandler handler = handlerFor(id);
            if (handler != null && predicate.test(handler)) {
                kinds.add(id.toString());
            }
        }
        return kinds;
    }

    /**
     * The isolations the registered kinds that run on {@code runtime} provide: what a host of that runtime could be
     * asked to place.
     *
     * @return the isolations, SHARED_KERNEL alone for a runtime no kind declares (the weakest, the conservative answer)
     */
    public static @NonNull Set<WorkloadIsolation> isolationsOn(@NonNull String runtime) {
        Set<WorkloadIsolation> isolations = EnumSet.noneOf(WorkloadIsolation.class);
        for (Identifier id : InstanceKindRegistry.registry().ids()) {
            InstanceKindHandler handler = handlerFor(id);
            if (handler != null && handler.supportedRuntimes().contains(runtime)) {
                isolations.add(handler.isolation());
            }
        }
        return isolations.isEmpty() ? EnumSet.of(WorkloadIsolation.SHARED_KERNEL) : isolations;
    }

    /** @return the runtime of the host this record names (a missing host reads as the local Docker daemon) */
    public static @NonNull String hostRuntimeOf(@NonNull Row instance) {
        Row server = Models.get(ServerModel.class).findById(
            ServerModel.canonicalServerId(instance.get(InstanceModel.SERVER_ID)));
        return server == null ? ServerModel.RUNTIME_DOCKER : ServerModel.runtimeOf(server);
    }

    /**
     * Whether this record's driver offers {@code lane}: its kind's declared driver on its host's runtime.
     *
     * @return false for an unknown kind or a runtime its kind does not declare (fail closed)
     */
    public static boolean offers(@NonNull Row instance, @NonNull WorkloadLane lane) {
        InstanceKindHandler handler = handlerOf(instance);
        return handler != null && lane.offeredBy(handler.driverOn(hostRuntimeOf(instance)));
    }

    /**
     * The kinds a stored record may be changed to: the ones its host's runtime runs, and, once a workload exists, only
     * those its driver can converge that workload into (the same isolation: a container never becomes a virtual
     * machine or back, which the Incus driver refuses by name at deploy). Its own kind is always among them.
     */
    public static @NonNull Set<String> kindChangesOf(@NonNull Row stored) {
        String current = stored.get(InstanceModel.KIND);
        InstanceKindHandler own = getHandler(current);
        String runtime = hostRuntimeOf(stored);
        boolean deployed = InstanceStatus.forToken(stored.get(InstanceModel.STATUS)) instanceof InstanceStatus status
            && status.deployed();
        Set<String> kinds = new HashSet<>();
        if (current != null) {
            kinds.add(current);
        }
        for (FieldOption<String> option : authorableOptions()) {
            InstanceKindHandler handler = getHandler(option.value());
            if (handler != null && handler.supportedRuntimes().contains(runtime)
                    && (!deployed || own == null || handler.isolation() == own.isolation())) {
                kinds.add(option.value());
            }
        }
        return kinds;
    }

    /** @throws Violations on the kind entry when {@code stored} cannot be changed to {@code kind} */
    public static void requireKindChange(@NonNull Row stored, @Nullable String kind) {
        if (kind == null || kind.equals(stored.get(InstanceModel.KIND)) || kindChangesOf(stored).contains(kind)) {
            return;
        }
        InstanceKindHandler handler = getHandler(kind);
        throw Violations.ofField(InstanceModel.KIND.getName(), kind,
            HohenheimMicrocopy.VIOLATIONS.of("instance_kind_change_refused")
                .withArg("kind", handler == null ? Microcopy.literal(kind) : handler.getLabel()));
    }
}
