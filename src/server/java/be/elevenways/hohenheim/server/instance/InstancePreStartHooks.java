package be.elevenways.hohenheim.server.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ordered, compile-time-discovered set of {@link InstancePreStartHook}s, and the ONE
 * place a deploy dispatches them (the InstanceKinds shape).
 *
 * @author Jelle De Loecker
 */
public final class InstancePreStartHooks {

    private static final Map<Identifier, InstancePreStartHook> HOOKS = new LinkedHashMap<>();

    /**
     * Entries arrive via the generated BlastAutoLoadInit; force it so lookups work
     * regardless of which class the JVM touched first. MUST be the LAST static field:
     * the loader re-enters register() while this class is mid init and needs HOOKS
     * assigned.
     */
    @SuppressWarnings("unused")
    private static final Object AUTO_LOAD_TRIGGER =
            be.elevenways.protoblast.generated.BlastAutoLoadInit.loaded;

    private InstancePreStartHooks() {}

    /**
     * Compile-time discovery hook (BlastAutoLoadInit).
     *
     * @throws IllegalStateException on a duplicate id -- two hooks answering to one name
     *         means one of them silently never dispatches
     */
    public static void register(@NonNull InstancePreStartHook hook) {
        InstancePreStartHook previous = HOOKS.putIfAbsent(hook.id(), hook);
        if (previous != null && previous.getClass() != hook.getClass()) {
            throw new IllegalStateException("Duplicate pre-start hook id " + hook.id()
                + ": " + previous.getClass().getName() + " and " + hook.getClass().getName());
        }
    }

    /** Every registered hook in DISPATCH order: ascending weight, ties broken on id. */
    public static @NonNull List<InstancePreStartHook> all() {
        List<InstancePreStartHook> hooks = new ArrayList<>(HOOKS.values());
        hooks.sort(Comparator.comparingInt(InstancePreStartHook::weight)
            .thenComparing(hook -> hook.id().toString()));
        return List.copyOf(hooks);
    }

    /**
     * Run every registered hook between container create and start.
     *
     * @return the ids that ran, in order -- the caller's evidence that dispatch was
     *         complete rather than merely attempted
     * @throws IOException from the first hook that cannot enforce its work; the deploy
     *         must fail there rather than start a half-linked workload
     */
    public static @NonNull List<Identifier> run(InstanceService.@NonNull Resolved resolved,
                                                int instanceId) throws IOException {
        return dispatch(all(), resolved, instanceId);
    }

    /**
     * The ordering-and-dispatch half on an EXPLICIT hook list.
     *
     * AIDEV-NOTE: separated from {@link #run} on purpose. The risk a registry adds is a
     * hook that silently does not run, and proving the loop visits every element must not
     * require a live deploy -- a test drives this with its own probes (the resolved
     * argument is theirs to ignore, which is why it carries no non-null claim here).
     */
    static @NonNull List<Identifier> dispatch(@NonNull List<InstancePreStartHook> hooks,
                                              InstanceService.Resolved resolved,
                                              int instanceId) throws IOException {
        List<Identifier> ran = new ArrayList<>(hooks.size());
        for (InstancePreStartHook hook : hooks) {
            hook.beforeStart(resolved, instanceId);
            ran.add(hook.id());
        }
        return List.copyOf(ran);
    }
}
