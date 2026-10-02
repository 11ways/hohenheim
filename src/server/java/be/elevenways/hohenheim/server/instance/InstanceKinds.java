package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.HohenheimViolations;
import be.elevenways.hohenheim.instance.InstanceKindInfo;
import be.elevenways.hohenheim.instance.InstanceKindRegistry;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.edit.FieldOption;
import be.elevenways.zenit.common.ui.Icon;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups
     * work regardless of which class the JVM touched first. MUST be the LAST
     * static field.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private InstanceKinds() {}

    /** Compile-time discovery hook (BlastAutoLoadInit). */
    public static void register(InstanceKindHandler handler) {
        InstanceKindRegistry.REGISTRY.add(handler.typeId(), handler);
    }

    /** Deliberately points a kind's id at another handler (a test standing a fake in for a production kind). */
    public static void replace(InstanceKindHandler handler) {
        InstanceKindRegistry.REGISTRY.replace(handler.typeId(), handler);
    }

    public static InstanceKindHandler getHandler(String typeIdentifier) {
        if (typeIdentifier == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(typeIdentifier);
        return handlerFor(id);
    }

    private static @Nullable InstanceKindHandler handlerFor(@Nullable Identifier id) {
        return id != null && InstanceKindRegistry.REGISTRY.get(id) instanceof InstanceKindHandler handler
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
            HohenheimViolations.text("instance_kind_owner_managed")
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
        return HohenheimViolations.text("host_runtime_mismatch")
            .withArg("name", hostName)
            .withArg("runtime", hostRuntime)
            .withArg("required", String.join(", ", new TreeSet<>(supportedRuntimes)));
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

        for (InstanceKindInfo entry : InstanceKindRegistry.REGISTRY) {

            Identifier id = InstanceKindRegistry.REGISTRY.idOf(entry);

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
     * Every registered kind's supported runtimes, keyed by the stored kind value --
     * the data the dependent host picker's resolver carries
     * ({@code HohenheimPickRules.KindHostRules}).
     */
    public static @NonNull Map<String, List<String>> runtimesByKind() {
        Map<String, List<String>> runtimes = new HashMap<>();
        for (Identifier id : InstanceKindRegistry.REGISTRY.ids()) {
            InstanceKindHandler handler = handlerFor(id);
            if (handler != null) {
                runtimes.put(id.toString(), List.copyOf(new TreeSet<>(handler.supportedRuntimes())));
            }
        }
        return runtimes;
    }

    /** Stored kind values whose handler passes the predicate, registry order. */
    public static @NonNull List<String> kindsWhere(@NonNull Predicate<InstanceKindHandler> predicate) {
        List<String> kinds = new ArrayList<>();
        for (InstanceKindInfo entry : InstanceKindRegistry.REGISTRY) {
            Identifier id = InstanceKindRegistry.REGISTRY.idOf(entry);
            InstanceKindHandler handler = handlerFor(id);
            if (handler != null && predicate.test(handler)) {
                kinds.add(id.toString());
            }
        }
        return kinds;
    }
}
