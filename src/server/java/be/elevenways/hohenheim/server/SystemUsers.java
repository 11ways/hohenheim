package be.elevenways.hohenheim.server;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.protoblast.common.registry.Identifier;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a configured system user to the numeric unix uid used for privilege drop,
 * and builds the hardened ProcessBuilder every spawn goes through. Single source of truth
 * for the git provisioning and spamservice paths.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class SystemUsers {

    private static final String SAFE_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private SystemUsers() {}

    /**
     * @param userKeyObj the site's {@code user} setting (a {@code hohenheim:<username>} registry
     *                   key; a legacy Integer id is still honored), or null when unset
     * @return the numeric uid, or null when no user is configured (the child then inherits
     *         the daemon's own user)
     * @throws IllegalStateException when a user IS configured but cannot be resolved, or resolves
     *         to uid 0 -- fail closed, so a dangling reference can't silently escalate an
     *         intended-sandboxed process back to the privileged Hohenheim user, and no site can
     *         be explicitly configured to run as root (callers isolate this per-site)
     */
    public static @Nullable Integer resolveUid(Object userKeyObj) {
        RunAsUser user = resolve(userKeyObj);
        return user != null ? user.uid() : null;
    }

    /** The identity a site's child processes run as: uid plus its primary gid and home. */
    public record RunAsUser(String name, int uid, @Nullable Integer gid, @Nullable String home) {}

    /** Builds the explicit baseline inherited by site runtime, build, and git commands. */
    public static Map<String, String> safeEnvironment(@Nullable String home) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("PATH", SAFE_PATH);
        result.put("LANG", "C.UTF-8");
        if (home != null && !home.isBlank()) {
            result.put("HOME", home);
        }
        return result;
    }

    /** Replaces ProcessBuilder's daemon environment rather than overlaying secrets onto it. */
    public static void setEnvironment(ProcessBuilder builder, Map<String, String> environment) {
        builder.environment().clear();
        builder.environment().putAll(environment);
    }

    /** Builds a process with an explicit environment, optional uid drop, and optional new session. */
    public static ProcessBuilder executionBuilder(@Nullable RunAsUser runAs,
                                                   Map<String, String> environment,
                                                   List<String> command,
                                                   boolean newSession) {
        return executionBuilder(runAs, environment, command, newSession, List.of());
    }

    /**
     * The same builder with a CONFINEMENT prefix (a cgroup scope, see
     * {@link ProcessConfinement#scopePrefix}) wrapped around the spawn.
     *
     * AIDEV-NOTE: the layer order is load-bearing in both directions and is the whole
     * hardening floor of the host-process tier. OUTSIDE the privilege drop: setsid (the
     * session leader must not be sudo's child, see below) and the cgroup scope (created by
     * the DAEMON's identity -- the site uid has no manager to ask). INSIDE it:
     * {@code prlimit --nproc} and {@code setpriv --no-new-privs}, and they may not move
     * out. no_new_privs set BEFORE sudo would make the setuid-root sudo binary fail
     * outright, and RLIMIT_NPROC is counted per REAL UID, so setting it while the daemon's
     * own uid is still in force would cap the CONTROL PLANE's process table -- the same
     * shape of mistake as keying an isolation rule on the daemon's identity. That is also
     * why {@code --nproc} is applied ONLY when a dedicated run-as user exists: without one
     * the child IS the daemon's uid and the limit would be shared with the controller.
     *
     * AIDEV-NOTE: the capability BOUNDING SET is deliberately not dropped here even though
     * {@code setpriv --bounding-set} exists. Dropping it needs CAP_SETPCAP, which the
     * process no longer has once sudo has dropped to an unprivileged uid, and doing it
     * before the drop would need sudo-to-root instead of sudo-to-uid (a far wider grant).
     * no_new_privs closes the lane the bounding set protects: an unprivileged child cannot
     * gain capabilities through a setuid or file-capability exec at all.
     */
    public static ProcessBuilder executionBuilder(@Nullable RunAsUser runAs,
                                                   Map<String, String> environment,
                                                   List<String> command,
                                                   boolean newSession,
                                                   List<String> confinementPrefix) {
        List<String> result = new ArrayList<>();
        if (newSession) {
            // Keep the session leader outside sudo: sudo may fork a command monitor, but
            // every process it creates still inherits the group whose id is the Java PID.
            result.add("/usr/bin/setsid");
            result.add("--wait");
            result.add("--");
        }
        result.addAll(confinementPrefix);
        if (runAs != null) {
            result.add("/usr/bin/sudo");
            result.add("-n");
            // ProcessBuilder has already reduced the environment to the explicit map below.
            // Preserving it carries secrets in envp, never in the inspectable argument vector.
            result.add("--preserve-env");
            result.add("-u");
            result.add("#" + runAs.uid());
            if (runAs.gid() != null) {
                result.add("-g");
                result.add("#" + runAs.gid());
            }
            result.add("--");
            // Per-UID process cap: meaningful only because WorkloadIdentity makes the site
            // uid exclusive, and never applied to the daemon's own shared identity.
            result.add("/usr/bin/prlimit");
            result.add("--nproc=" + ProcessConfinement.pidsLimit());
            result.add("--");
        }
        result.add("/usr/bin/setpriv");
        result.add("--no-new-privs");
        result.add("--");
        result.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(result);
        setEnvironment(builder, environment);
        return builder;
    }

    /**
     * Full run-as identity for a site's configured system user, or null when none is
     * configured (the child then inherits the daemon's own user). Same fail-closed
     * contract as {@link #resolveUid}.
     */
    public static @Nullable RunAsUser resolve(Object userKeyObj) {
        Row row;
        if (userKeyObj instanceof Integer id) {
            if (id <= 0) {
                return null;
            }
            row = Models.get(SystemUserModel.class).findById(id);
        } else if (userKeyObj instanceof String key && !key.isBlank()) {
            Identifier parsed = Identifier.tryParse(key);
            String username = parsed != null ? parsed.getPath() : key;
            row = Models.get(SystemUserModel.class).find()
                .where(SystemUserModel.NAME.eq(username))
                .first();
        } else {
            return null;
        }
        if (row == null) {
            throw new IllegalStateException("Configured system user '" + userKeyObj + "' does not exist");
        }
        Integer uid = row.get(SystemUserModel.UID);
        if (uid == null) {
            throw new IllegalStateException("System user '" + userKeyObj + "' has no uid");
        }
        if (uid == 0) {
            throw new IllegalStateException("System user '" + userKeyObj + "' is root (uid 0); refusing to run site processes as root");
        }
        return new RunAsUser(row.get(SystemUserModel.NAME), uid,
            row.get(SystemUserModel.GID), row.get(SystemUserModel.HOME));
    }

    /**
     * @return true when the Hohenheim daemon itself runs as root, so children of sites
     *         without a configured system user would inherit root
     */
    public static boolean daemonRunsAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }
}
