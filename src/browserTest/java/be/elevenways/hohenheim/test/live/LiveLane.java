package be.elevenways.hohenheim.test.live;

import be.elevenways.hohenheim.server.docker.DockerClient;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * THE gate every live test asks before it needs a daemon, a host or an image: it either
 * passes, or it aborts with the missing capability NAMED in the abort message so
 * {@link LiveLaneReport} can report the run's skips instead of letting them read as green.
 *
 * <p>Why a vocabulary rather than a {@code live-required} / {@code live-optional} tag pair:
 * whether a skip is a DEFECT is a property of the HOST, not of the test. Of the 24 classes
 * that gate in {@code @BeforeAll} (so the whole class skips silently), 17 need an enrolled
 * Incus or remote host that no ordinary machine has -- a hard "required" tag on them would
 * be permanently red or permanently excluded, which is two ways of saying useless. The
 * honest axis is WHAT IS MISSING, so a host declares what it can satisfy:
 * {@code -Dhohenheim.live.require=docker-socket,netns} fails the run on any skip for those
 * needs, and unset (the default) reports every skip without failing.
 *
 * <p>The pre-pulled-image category is removed rather than reported: a host with a working
 * daemon and a cold image cache is one command away from running the test, so
 * {@link #requireImage} PULLS. Only a pull that fails is a skip.
 *
 * AIDEV-NOTE: this NEVER weakens a gate. Every call site kept its own predicate; what
 * changed is that the abort now carries a machine-readable need. Adding a need means
 * adding a {@link Need} constant -- never a bare {@code assumeTrue} with a prose reason,
 * because a prose-only abort is invisible to the report by construction.
 */
public final class LiveLane {

    /** The prefix every live-lane abort message carries; the report parses it back out. */
    static final String MARKER_PREFIX = "[live:";

    /** Host capabilities a live test can need. The policy and the report both key on these. */
    public enum Need {

        /** A reachable Docker daemon socket. */
        DOCKER_SOCKET("docker-socket"),

        /** A local image; {@link #requireImage} pulls before it ever reports this. */
        DOCKER_IMAGE("docker-image"),

        /** A private (or uid-mapped) network namespace the workload tier can deploy into. */
        NETNS("netns"),

        /** An enrolled LIVE Incus host. */
        INCUS_HOST("incus-host"),

        /** An enrolled remote (ssh) host. */
        REMOTE_HOST("remote-host"),

        /** OpenSSH binaries on this machine. */
        SSH_TOOLS("ssh"),

        /** Enforceable process confinement (cgroup delegation). */
        CONFINEMENT("confinement"),

        /** A {@code node} binary. */
        NODE("node"),

        /**
         * A filesystem that can REFUSE this process. Running the suite as root satisfies
         * nothing that needs a denied write, so a test proving a failure path cannot be
         * driven there.
         */
        UNPRIVILEGED_FS("unprivileged-fs");

        private final @NonNull String key;

        Need(@NonNull String key) {
            this.key = key;
        }

        /** The stable name the report prints and the policy property matches. */
        public @NonNull String key() {
            return this.key;
        }

        static Need ofKey(@NonNull String key) {
            for (Need need : values()) {
                if (need.key.equals(key)) {
                    return need;
                }
            }
            throw new IllegalArgumentException("unknown live-lane need: " + key);
        }
    }

    /**
     * Needs a skip of which must FAIL this run; from {@code -Dhohenheim.live.require}.
     * Not final so {@link #withRequired} can prove the escalation actually escalates --
     * a policy nothing exercises is a policy nobody can trust.
     */
    private static volatile Set<Need> required = parseRequired(
        System.getProperty("hohenheim.live.require", ""));

    /** Whether a missing image may be pulled; {@code -Dhohenheim.live.pull=false} disables. */
    private static final boolean PULL =
        !"false".equalsIgnoreCase(System.getProperty("hohenheim.live.pull", "true"));

    private LiveLane() {
    }

    /**
     * Pass when {@code satisfied}, otherwise abort (or fail, under the policy) naming
     * {@code need}. The caller keeps its own predicate: this replaces the ABORT, never
     * the check.
     */
    public static void require(@NonNull Need need, boolean satisfied, @NonNull String reason) {
        if (!satisfied) {
            skip(need, reason);
        }
    }

    /**
     * The image gate: present passes, absent PULLS, and only a pull that leaves the image
     * still absent is a skip. A daemon-bearing host with a cold cache used to report green
     * for ~36 classes; it now runs them.
     *
     * @throws AssertionError under {@code -Dhohenheim.live.require=docker-image}
     */
    public static void requireImage(@NonNull DockerClient docker, @NonNull String reference) {
        if (imagePresent(docker, reference)) {
            return;
        }
        if (!PULL) {
            skip(Need.DOCKER_IMAGE, reference + " not present locally (pulling is disabled)");
            return;
        }
        try {
            docker.ensureImage(reference, null);
        } catch (IOException | RuntimeException pullFailed) {
            skip(Need.DOCKER_IMAGE, reference + " not present locally and the pull failed: "
                + describe(pullFailed));
            return;
        }
        // The pull reporting success is not the same fact as the image being there, and
        // this gate exists precisely because a step that does less than it claims reads
        // as a pass. Ask the daemon again.
        if (!imagePresent(docker, reference)) {
            skip(Need.DOCKER_IMAGE,
                reference + " is still absent after a pull that reported success");
        }
    }

    /** Whether the daemon holds this reference (tag, digest-pinned or content-addressed). */
    public static boolean imagePresent(@NonNull DockerClient docker, @NonNull String reference) {
        try {
            return docker.inspectImage(reference) != null;
        } catch (IOException | RuntimeException absent) {
            return false;
        }
    }

    /**
     * Abort (or fail) naming {@code need}. Public because a few gates compute their
     * predicate in a shape {@link #require} cannot express.
     */
    public static void skip(@NonNull Need need, @NonNull String reason) {
        String message = MARKER_PREFIX + need.key() + "] " + reason;
        if (required.contains(need)) {
            throw new AssertionError("live lane REQUIRED on this host but unsatisfied: "
                + message);
        }
        Assumptions.abort(message);
    }

    /** The needs this run declares must be satisfiable; empty means "report, never fail". */
    static @NonNull Set<Need> required() {
        return required;
    }

    /** Run {@code body} under a declared policy, then restore this JVM's own. */
    static void withRequired(@NonNull Set<Need> needs, @NonNull Runnable body) {
        Set<Need> restore = required;
        required = needs;
        try {
            body.run();
        } finally {
            required = restore;
        }
    }

    private static @NonNull Set<Need> parseRequired(@NonNull String declared) {
        Set<Need> needs = new LinkedHashSet<>();
        for (String part : declared.split(",")) {
            String key = part.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() || "none".equals(key)) {
                continue;
            }
            if ("all".equals(key)) {
                needs.addAll(Set.of(Need.values()));
                continue;
            }
            needs.add(Need.ofKey(key));
        }
        return needs;
    }

    private static @NonNull String describe(@NonNull Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.toString();
    }
}
