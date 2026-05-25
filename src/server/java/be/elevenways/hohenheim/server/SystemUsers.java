package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.zenit.common.orm.datasource.Row;

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
     * @param systemUserIdObj the site's {@code system_user_id} setting (an Integer, or null/0 when unset)
     * @return the numeric uid, or 0 when no user is configured (run as the current user)
     * @throws IllegalStateException when a user IS configured but cannot be resolved -- fail closed,
     *         so a dangling reference can't silently escalate an intended-sandboxed process back to
     *         the privileged Hohenheim user (callers isolate this per-site)
     */
    public static int resolveUid(Object systemUserIdObj) {
        if (!(systemUserIdObj instanceof Integer id) || id <= 0) {
            return 0;
        }
        Row row = new SystemUserModel(HohenheimDatabase.datasource()).findById(id);
        if (row == null) {
            throw new IllegalStateException("Configured system_user_id " + id + " does not exist");
        }
        Integer uid = row.get(SystemUserModel.UID);
        if (uid == null) {
            throw new IllegalStateException("System user " + id + " has no uid");
        }
        return uid;
    }
}
