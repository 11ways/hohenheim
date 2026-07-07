package be.elevenways.hohenheim.server;

import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.options.SystemUserOptions;
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
     * @param userKeyObj the site's {@code user} setting (a {@code hohenheim:<username>} registry
     *                   key; a legacy Integer id is still honored), or null when unset
     * @return the numeric uid, or 0 when no user is configured (run as the current user)
     * @throws IllegalStateException when a user IS configured but cannot be resolved -- fail closed,
     *         so a dangling reference can't silently escalate an intended-sandboxed process back to
     *         the privileged Hohenheim user (callers isolate this per-site)
     */
    public static int resolveUid(Object userKeyObj) {
        Row row;
        if (userKeyObj instanceof Integer id) {
            if (id <= 0) {
                return 0;
            }
            row = Models.get(SystemUserModel.class).findById(id);
        } else if (userKeyObj instanceof String key && !key.isBlank()) {
            String username = SystemUserOptions.usernameFromKey(key);
            row = Models.get(SystemUserModel.class).find()
                .where(SystemUserModel.NAME.eq(username))
                .first();
        } else {
            return 0;
        }
        if (row == null) {
            throw new IllegalStateException("Configured system user '" + userKeyObj + "' does not exist");
        }
        Integer uid = row.get(SystemUserModel.UID);
        if (uid == null) {
            throw new IllegalStateException("System user '" + userKeyObj + "' has no uid");
        }
        return uid;
    }
}
