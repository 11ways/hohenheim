package be.elevenways.hohenheim.server;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.options.SystemUserOptions;
import be.elevenways.zenit.common.orm.datasource.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Resolves a site's configured {@code system_user_id} to the numeric unix uid used
 * for per-site privilege drop (sudo -u). Single source of truth for the node,
 * command, and git provisioning site paths.
 *
 * @author  Jelle De Loecker
 * @since   0.1.0
 */
public final class SystemUsers {

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
    public record RunAsUser(int uid, @Nullable Integer gid, @Nullable String home) {}

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
        return new RunAsUser(uid, row.get(SystemUserModel.GID), row.get(SystemUserModel.HOME));
    }

    /**
     * @return true when the Hohenheim daemon itself runs as root, so children of sites
     *         without a configured system user would inherit root
     */
    public static boolean daemonRunsAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }
}
