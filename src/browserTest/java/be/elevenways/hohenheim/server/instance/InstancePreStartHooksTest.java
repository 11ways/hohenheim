package be.elevenways.hohenheim.server.instance;

import be.elevenways.protoblast.common.registry.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private static final Identifier SITE_DB = Identifier.of("hohenheim", "site_database_links");
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
            .contains(GAME, SITE_DB, STACK);
        assertThat(ids.indexOf(GAME))
            .as("step 1: the game-domain hook is dispatched before the site-database hook")
            .isLessThan(ids.indexOf(SITE_DB));
        assertThat(ids.indexOf(SITE_DB))
            .as("step 1: the site-database hook is dispatched before the stack hook")
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
            .as("step 3: the source scan found the implementations (a scan finding none"
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

    /** Fully-qualified names of every {@link InstancePreStartHook} implementation in the tree. */
    private static List<String> declaredHookClasses() throws IOException {
        Path root = sourceRoot();
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String body;
                try {
                    body = Files.readString(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (body.contains("implements InstancePreStartHook")) {
                    String relative = root.relativize(path).toString();
                    found.add(relative.substring(0, relative.length() - ".java".length())
                        .replace(java.io.File.separatorChar, '.'));
                }
            });
        }
        return found;
    }

    /** The server source root, located by walking up from the working directory. */
    private static Path sourceRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path source = candidate.resolve("src/server/java");
            if (Files.isDirectory(source)) {
                return source;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate src/server/java from "
            + Path.of("").toAbsolutePath());
    }
}
