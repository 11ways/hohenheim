package be.elevenways.hohenheim.server.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pre-start hook registry: who is registered, in what declared order, that EVERY
 * implementation in the tree is registered, that dispatch visits all of them, and that a
 * failing hook stops the deploy instead of letting a half-linked workload start.
 */
class InstancePreStartHooksTest {

    private static final Identifier GAME = Identifier.of("hohenheim", "game_domain_links");
    /**
     * AIDEV-NOTE: was {@code site_database_links}. Phase-0 brief 7 deleted the site-keyed
     * database lane, so the instance one is the only database hook there is -- and it now
     * covers an application's releases too, resolving the link OWNER off the deploying row.
     */
    private static final Identifier INSTANCE_DB =
        Identifier.of("hohenheim", "instance_database_links");
    private static final Identifier STACK = Identifier.of("hohenheim", "stack_service_links");

    /** A probe that records that it ran; the resolved argument is deliberately ignored. */
    private record Probe(Identifier id, int weight, List<Identifier> log,
                         boolean fails) implements InstancePreStartHook {

        @Override public Identifier id() {
            return this.id;
        }

        @Override public int weight() {
            return this.weight;
        }

        @Override public void beforeStart(InstanceService.Resolved resolved, int instanceId)
                throws IOException {
            this.log.add(this.id);
            if (this.fails) {
                throw new IOException("probe " + this.id + " cannot enforce its links");
            }
        }
    }

    private static Probe probe(String name, int weight, List<Identifier> log) {
        return new Probe(Identifier.of("test", name), weight, log, false);
    }

    @Test
    void registryIsCompleteOrderedAndFullyDispatched() throws Exception {
        // 1. Positive anchor: the three link-network owners are actually registered,
        //    and are dispatched in their DECLARED weight order -- not discovery order.
        List<Identifier> ids = new ArrayList<>();
        for (InstancePreStartHook hook : InstancePreStartHooks.all()) {
            ids.add(hook.id());
        }
        assertThat(ids)
            .as("step 1: every link-network owner registers itself")
            .contains(GAME, INSTANCE_DB, STACK);
        assertThat(ids.indexOf(GAME))
            .as("step 1: the game-domain hook is dispatched before the database hook")
            .isLessThan(ids.indexOf(INSTANCE_DB));
        assertThat(ids.indexOf(INSTANCE_DB))
            .as("step 1: the database hook is dispatched before the stack hook")
            .isLessThan(ids.indexOf(STACK));
        assertThat(ids).as("step 1: no hook id is registered twice").doesNotHaveDuplicates();

        // 2. The order is total and declared: weights are non-decreasing along the
        //    dispatch list, so nothing is placed by hash or registration accident.
        int previous = Integer.MIN_VALUE;
        for (InstancePreStartHook hook : InstancePreStartHooks.all()) {
            assertThat(hook.weight())
                .as("step 2: %s sits at a declared weight, in ascending order", hook.id())
                .isGreaterThanOrEqualTo(previous);
            previous = hook.weight();
        }

        // 3. COMPLETENESS: every implementation that exists in the server tree is in the
        //    registry. This is the one thing the registry itself cannot answer -- a hook
        //    that silently never registers is invisible to any test that only checks the
        //    hooks it already knows about.
        List<String> declared = declaredHookClasses();
        assertThat(declared)
            .as("step 3: the type scan found the implementations (a scan finding none"
                + " would make this assertion vacuous)")
            .hasSizeGreaterThanOrEqualTo(3);
        List<String> registered = new ArrayList<>();
        for (InstancePreStartHook hook : InstancePreStartHooks.all()) {
            registered.add(hook.getClass().getName());
        }
        assertThat(registered)
            .as("step 3: every InstancePreStartHook in the tree is registered")
            .containsAll(declared);

        // 4. Dispatch visits EVERY hook it is given, in list order, and reports exactly
        //    what ran -- the deploy's evidence that dispatch was complete, not attempted.
        List<Identifier> log = new ArrayList<>();
        List<InstancePreStartHook> probes = List.of(
            probe("first", 10, log), probe("second", 20, log), probe("third", 30, log));
        List<Identifier> ran = InstancePreStartHooks.dispatch(probes, null, 7);
        assertThat(log)
            .as("step 4: every probe ran, in order")
            .containsExactly(Identifier.of("test", "first"), Identifier.of("test", "second"),
                Identifier.of("test", "third"));
        assertThat(ran).as("step 4: the reported ids ARE the ids that ran").isEqualTo(log);

        // 5. A hook that cannot do its work ABORTS the chain: the exception propagates
        //    (deploy fails) and no later hook runs. A workload must never start believing
        //    its links exist.
        List<Identifier> abortLog = new ArrayList<>();
        List<InstancePreStartHook> failing = List.of(
            probe("before", 10, abortLog),
            new Probe(Identifier.of("test", "broken"), 20, abortLog, true),
            probe("after", 30, abortLog));
        assertThatThrownBy(() -> InstancePreStartHooks.dispatch(failing, null, 7))
            .as("step 5: the failure reaches the deploy")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cannot enforce its links");
        assertThat(abortLog)
            .as("step 5: dispatch stopped at the failure")
            .containsExactly(Identifier.of("test", "before"), Identifier.of("test", "broken"));

        // 6. Two hooks answering to one id would leave one of them silently undispatched,
        //    so registration refuses it by name.
        assertThatThrownBy(() -> InstancePreStartHooks.register(
                new Probe(GAME, 1, new ArrayList<>(), false)))
            .as("step 6: a duplicate id is refused, naming both classes")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("game_domain_links");
    }

    /**
     * Binary names of every concrete {@link InstancePreStartHook} in the compiled server
     * classes, decided by the JVM's own type graph.
     *
     * AIDEV-NOTE: this used to grep the SOURCE for the text "implements
     * InstancePreStartHook", which a subclass of an existing hook, an intermediate
     * interface, a generic or a line break all escaped. Every class is now loaded WITHOUT
     * initialization and asked isAssignableFrom, so any concrete type the runtime would
     * treat as a hook is found however it got there. A class that cannot even link is
     * skipped: the generated autoload loader references every hook directly, so an
     * unlinkable hook fails boot loudly and can never be a SILENTLY unregistered one.
     */
    private static List<String> declaredHookClasses() throws IOException, URISyntaxException {
        Path root = Path.of(InstancePreStartHooks.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        assertThat(root)
            .as("step 3: the compiled server classes are a directory on the test classpath")
            .isDirectory();
        ClassLoader loader = InstancePreStartHooks.class.getClassLoader();
        List<String> found = new ArrayList<>();
        List<Path> classFiles;
        try (Stream<Path> files = Files.walk(root)) {
            classFiles = files.filter(path -> path.toString().endsWith(".class")).toList();
        }
        for (Path file : classFiles) {
            String relative = root.relativize(file).toString();
            String name = relative.substring(0, relative.length() - ".class".length())
                .replace(File.separatorChar, '.');
            if (name.endsWith("module-info") || name.endsWith("package-info")) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError unlinkable) {
                continue;
            }
            if (InstancePreStartHook.class.isAssignableFrom(type) && !type.isInterface()
                    && !Modifier.isAbstract(type.getModifiers())) {
                found.add(type.getName());
            }
        }
        return found;
    }
}
