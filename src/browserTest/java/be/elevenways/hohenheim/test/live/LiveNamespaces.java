package be.elevenways.hohenheim.test.live;

import be.elevenways.hohenheim.server.ControllerScope;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.OwnerLabels;
import be.elevenways.hohenheim.server.runtime.WorkloadNetworks;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * THE reaper for the Docker debris a live run leaves behind, scoped to the controller
 * namespaces THIS machine's dead test JVMs minted and never to a running session's.
 *
 * <p>The failure it removes is a false failure in an unrelated test: every live run mints
 * a fresh {@code ControllerIdentity} token per database (see
 * {@code TestDatabases.remintControllerIdentity}) and every PRIVATE-posture workload gets
 * its own Docker network named {@code hohenheim-<token>-<kind>-<id>-net}. A test that
 * fails, or a worker JVM killed by a task timeout, never reaches the {@code destroy} that
 * would call {@link WorkloadNetworks#teardown}. Docker's default address pool subnets
 * roughly thirty user-defined networks and then refuses every further creation with "all
 * predefined address pools have been fully subnetted" -- which surfaces as a REFUSED
 * deploy in whatever test happens to run next, saying nothing about what that test
 * asserts. Two sessions lost real time diagnosing it (see {@link WorkloadNetworks}'s host
 * ceiling note, which describes the same ceiling from the production side).
 *
 * <p>Two halves, both riding {@link LiveLaneReport}'s per-forked-JVM lifecycle rather than
 * a new mechanism -- that listener is registered through {@code META-INF/services}, so it
 * observes every JVM this suite forks and needs no per-class wiring:
 * <ul>
 *   <li>{@link #sweepOwn()} at plan finish removes what THIS JVM's namespaces still hold,
 *       whatever the verdict. An {@code @AfterAll} cannot do this: it belongs to one
 *       class, it does not run when the JVM dies, and a shared-JVM lane runs dozens of
 *       classes against dozens of namespaces.</li>
 *   <li>{@link #sweepAbandoned()} at plan start reaps the ledgers of PREVIOUS JVMs that
 *       are gone -- the SIGKILL case the shutdown hook structurally cannot cover.</li>
 * </ul>
 *
 * AIDEV-NOTE: the safety property is that a namespace is reaped only when the JVM that
 * minted it is PROVABLY dead. The ledger header carries pid AND that process's start
 * instant, so pid reuse reads as "alive" and leaks rather than reaping a stranger. Two
 * agents can run live lanes concurrently; a live session's ledger is never touched, and
 * its tokens are unguessable to another session anyway (one token per SQLite database).
 * Claiming a ledger is an ATOMIC_MOVE, so six parallel forks cannot double-reap.
 *
 * AIDEV-NOTE: the ledger directory is deliberately NOT the per-task, wiped-in-doFirst
 * shape {@code hohenheim.truncation.dir} uses. Its whole job is to survive the invocation
 * that wrote it -- a directory emptied at the start of the next run would delete the
 * evidence of exactly the kill it exists to clean up after.
 *
 * AIDEV-NOTE: volumes ARE reaped here, which looks like a violation of the DockerReclaim
 * rule that {@code OrphanActions} enforces (a volume is the one unrecoverable resource, so
 * an OPERATOR sweep must refuse it) and is not one. What that rule protects is data a
 * tenant cannot get back; a namespace here belongs to a throwaway per-fork SQLite database
 * whose owning JVM is provably dead, so its volumes hold nothing but a finished test's
 * scratch. Leaving them behind is how a live host fills up instead.
 *
 * AIDEV-NOTE: this reaps DOCKER resources only, and it is the whole namespace on a
 * Docker-backed host. What it deliberately cannot reach: Incus instances, their storage
 * subvolumes and btrfs qgroups on an enrolled REMOTE host (measured on daystrom
 * 2026-08-23: a killed workspace run left a running container, a network, and a 2 GiB
 * quota'd btrfs subvolume under /opt/hohenheim/data/volumes). Those live on another
 * machine behind another client; a reaper for them belongs beside the Incus live fixtures,
 * not here, and until one exists an operator sweeps them by namespace prefix.
 */
public final class LiveNamespaces {

    /** Where the per-JVM namespace ledgers live; survives a run, unlike the truncation markers. */
    static final String DIR_PROPERTY = "hohenheim.namespace.dir";

    private static final String DEFAULT_DIR = "build/live-lane-namespaces";

    /** Ledger file name shape; the pid is in the name so a claim is visible in a listing. */
    private static final String LEDGER_PREFIX = "jvm-";
    private static final String LEDGER_SUFFIX = ".txt";

    /** Namespace tokens this JVM has minted, in order. */
    private static final Set<String> OWNED = new LinkedHashSet<>();

    private LiveNamespaces() {
    }

    /**
     * Record a namespace token this JVM just minted, so a kill mid-run still leaves a
     * reapable trace of it on disk.
     */
    public static synchronized void note(@NonNull String token) {
        if (token.isBlank() || !OWNED.add(token)) {
            return;
        }
        writeLedger();
    }

    /**
     * Reap every namespace whose owning JVM is gone, then forget its ledger.
     *
     * @return the resources removed, for the caller to report
     */
    static synchronized @NonNull List<String> sweepAbandoned() {
        List<String> removed = new ArrayList<>();
        Path dir = ledgerDir();
        if (!Files.isDirectory(dir)) {
            return removed;
        }
        try (Stream<Path> ledgers = Files.list(dir)) {
            for (Path ledger : ledgers.toList()) {
                String name = ledger.getFileName().toString();
                if (!name.startsWith(LEDGER_PREFIX) || !name.endsWith(LEDGER_SUFFIX)) {
                    continue;
                }
                Ledger parsed = readLedger(ledger);
                if (parsed == null || parsed.isAlive()) {
                    continue;   // unreadable, or a session that is still running
                }
                Path claimed = claim(ledger);
                if (claimed == null) {
                    continue;   // a sibling fork got there first
                }
                for (String token : parsed.tokens()) {
                    removed.addAll(reap(token));
                }
                quietlyDelete(claimed);
            }
        } catch (IOException unreadable) {
            // A ledger listing that cannot be read leaks; it must never fail a suite.
        }
        return removed;
    }

    /**
     * Reap everything this JVM's own namespaces still hold and drop its ledger.
     *
     * @return the resources removed, for the caller to report
     */
    static synchronized @NonNull List<String> sweepOwn() {
        List<String> removed = new ArrayList<>();
        for (String token : OWNED) {
            removed.addAll(reap(token));
        }
        OWNED.clear();
        quietlyDelete(ledgerFile(ProcessHandle.current().pid()));
        return removed;
    }

    /**
     * Remove everything ONE controller namespace holds on the local daemon: its
     * containers first (they pin the rest), then its networks, then its named volumes.
     *
     * @return a description of each resource removed; empty when the namespace is clean
     *         or no daemon is reachable
     */
    static @NonNull List<String> reap(@NonNull String token) {
        List<String> removed = new ArrayList<>();
        if (!Files.exists(Path.of(DockerClient.DEFAULT_SOCKET))) {
            return removed;
        }
        String prefix = ControllerScope.PREFIX + "-" + token + "-";
        DockerClient docker = new DockerClient();
        try {
            for (Object entry : docker.listContainers(true)) {
                if (!(entry instanceof Map<?, ?> container)) {
                    continue;
                }
                String name = containerName(container);
                if (name == null || !name.startsWith(prefix)) {
                    continue;
                }
                try {
                    docker.removeContainer(name, true);
                    removed.add("container " + name);
                } catch (IOException stuck) {
                    removed.add("container " + name + " REFUSED: " + stuck.getMessage());
                }
            }
            for (Object entry : docker.listNetworks()) {
                if (!(entry instanceof Map<?, ?> network)) {
                    continue;
                }
                String name = String.valueOf(network.get("Name"));
                if (!name.startsWith(prefix) && !token.equals(controllerLabel(network))) {
                    continue;
                }
                try {
                    docker.removeNetwork(name);
                    removed.add("network " + name);
                } catch (IOException stuck) {
                    removed.add("network " + name + " REFUSED: " + stuck.getMessage());
                }
            }
            for (Object entry : docker.listVolumes()) {
                if (!(entry instanceof Map<?, ?> volume)) {
                    continue;
                }
                String name = String.valueOf(volume.get("Name"));
                if (!name.startsWith(prefix) && !token.equals(controllerLabel(volume))) {
                    continue;
                }
                try {
                    docker.removeVolume(name, true);
                    removed.add("volume " + name);
                } catch (IOException stuck) {
                    removed.add("volume " + name + " REFUSED: " + stuck.getMessage());
                }
            }
        } catch (IOException | RuntimeException unreachable) {
            // No daemon, or one that will not answer: there is nothing to clean and a
            // reaper must never be the reason a run fails.
        }
        return removed;
    }

    /**
     * The controller token a resource's owner labels claim.
     *
     * AIDEV-NOTE: the label is checked BESIDE the name prefix because a handful of test
     * fixtures build a handle by hand ({@code netlimit-docker}, {@code rootdisk-docker})
     * instead of through {@link ControllerScope#handle}, so their networks carry the
     * namespace only in the label. Both spellings are the same namespace.
     */
    private static @Nullable String controllerLabel(@NonNull Map<?, ?> resource) {
        return resource.get("Labels") instanceof Map<?, ?> labels
            && labels.get(OwnerLabels.CONTROLLER) instanceof String controller
            ? controller : null;
    }

    /** The first (canonical) name of a {@code /containers/json} entry. */
    private static @Nullable String containerName(@NonNull Map<?, ?> container) {
        if (!(container.get("Names") instanceof List<?> names) || names.isEmpty()) {
            return null;
        }
        String name = String.valueOf(names.get(0));
        return name.startsWith("/") ? name.substring(1) : name;
    }

    // -- the ledger -----------------------------------------------------------

    /** One JVM's ledger: who wrote it, and which namespaces it owns. */
    private record Ledger(long pid, long startedAtMillis, @NonNull List<String> tokens) {

        /**
         * Whether the writing process is still running.
         *
         * AIDEV-NOTE: the start instant is compared too, so a REUSED pid reads as alive.
         * Erring that way leaks a namespace; erring the other way would reap a running
         * session's networks out from under it.
         */
        boolean isAlive() {
            Optional<ProcessHandle> handle = ProcessHandle.of(this.pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return false;
            }
            Optional<Instant> started = handle.get().info().startInstant();
            return started.isEmpty()
                || Math.abs(started.get().toEpochMilli() - this.startedAtMillis) < 2000;
        }
    }

    private static @NonNull Path ledgerDir() {
        return Path.of(System.getProperty(DIR_PROPERTY, DEFAULT_DIR));
    }

    private static @NonNull Path ledgerFile(long pid) {
        return ledgerDir().resolve(LEDGER_PREFIX + pid + LEDGER_SUFFIX);
    }

    /** Rewrite this JVM's ledger; a write that fails only costs the SIGKILL safety net. */
    private static void writeLedger() {
        ProcessHandle self = ProcessHandle.current();
        long started = self.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
        StringBuilder text = new StringBuilder()
            .append(self.pid()).append(' ').append(started).append('\n');
        OWNED.forEach(token -> text.append(token).append('\n'));
        try {
            Path file = ledgerFile(self.pid());
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException ignored) {
            // Best effort: without it a killed JVM's namespaces survive until an operator
            // sweeps, which is exactly the state this class improves on.
        }
    }

    private static @Nullable Ledger readLedger(@NonNull Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            String[] header = lines.get(0).trim().split("\\s+");
            if (header.length != 2) {
                return null;
            }
            List<String> tokens = new ArrayList<>();
            for (String line : lines.subList(1, lines.size())) {
                String token = line.trim();
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            return new Ledger(Long.parseLong(header[0]), Long.parseLong(header[1]), tokens);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * Take exclusive ownership of an abandoned ledger by renaming it.
     *
     * @return the claimed path, or null when a sibling fork claimed it first
     */
    private static @Nullable Path claim(@NonNull Path ledger) {
        Path claimed = ledger.resolveSibling("claimed-" + ProcessHandle.current().pid()
            + "-" + ledger.getFileName());
        try {
            Files.move(ledger, claimed, StandardCopyOption.ATOMIC_MOVE);
            return claimed;
        } catch (IOException lost) {
            return null;
        }
    }

    private static void quietlyDelete(@NonNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover ledger is re-read (and re-claimed) next run; harmless.
        }
    }
}
