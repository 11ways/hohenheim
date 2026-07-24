package be.elevenways.hohenheim.server;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.options.SystemUserOptions;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a site's configured {@code system_user_id} to the numeric unix uid used
 * for per-site privilege drop. Single source of truth for the node,
 * command, and git provisioning site paths.
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
        List<String> result = new ArrayList<>();
        if (newSession) {
            // Keep the session leader outside sudo: sudo may fork a command monitor, but
            // every process it creates still inherits the group whose id is the Java PID.
            result.add("/usr/bin/setsid");
            result.add("--wait");
            result.add("--");
        }
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
        }
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
            String username = SystemUserOptions.usernameFromKey(key);
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
