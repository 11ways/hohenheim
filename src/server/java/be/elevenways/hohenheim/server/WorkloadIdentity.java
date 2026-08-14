package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.model.SiteModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.server.sitetype.SiteTypeHandler;
import be.elevenways.hohenheim.server.sitetype.SiteTypes;
import be.elevenways.protoblast.common.Blast;
import be.elevenways.zenit.auth.model.RecordGrantModel;
import be.elevenways.zenit.auth.server.RecordGrants;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.criteria.Criteria;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enforces the identity premise the IPC channel's security argument rests on: a managed
 * site's child processes run under a system user that no other site shares. Ownership is
 * RECORDED ({@code system_users.site_id}), never inferred from configuration alone.
 *
 * AIDEV-NOTE: identity is enforced here rather than documented, because the documented
 * version was false: the site user setting is optional and /etc/passwd discovery never
 * tracked which site used which uid, so two sites sharing www-data (or the daemon's own
 * user) could read each other's /proc environments -- IPC tokens, security-report tokens
 * and injected DATABASE_* credentials included. Refusal is an IllegalArgumentException so
 * every site type's createHandler turns it into an explicit FaultedSiteHandler 503.
 *
 * @author Jelle De Loecker
 * @since 0.2.0
 */
public final class WorkloadIdentity {

    /** One site's identity problem, for the attention list and the settings gate. */
    public record Finding(int siteId, @Nullable String siteName, @NonNull String problem) {}

    private WorkloadIdentity() {
    }

    /**
     * Resolve and CLAIM a site's run-as identity, or refuse.
     *
     * @param userKey the site's {@code user} setting (registry key or legacy id), or null
     * @return the claimed identity, or null when no user is configured and the operator
     *         deliberately turned {@code process.require_dedicated_user} off
     * @throws IllegalArgumentException when the identity is missing while required, does
     *         not resolve, is root, is the daemon's own user, or is claimed by another site
     */
    public static SystemUsers.@Nullable RunAsUser forSite(int siteId, @Nullable Object userKey) {
        SystemUsers.RunAsUser user;
        try {
            user = SystemUsers.resolve(userKey);
        } catch (IllegalStateException dangling) {
            // SystemUsers keeps its fail-closed IllegalStateException contract for other
            // callers; handler construction converts it so createHandler's catch (which
            // only covers IllegalArgumentException) produces a FaultedSiteHandler instead
            // of the site silently vanishing from routing.
            throw new IllegalArgumentException(dangling.getMessage(), dangling);
        }

        boolean required = enforcementRequired(siteId);

        if (user == null) {
            if (required) {
                throw new IllegalArgumentException(
                    "no dedicated system user configured (required by process.require_dedicated_user"
                        + " or because the site is tenant-managed)");
            }
            return null;
        }

        String daemonProblem = daemonIdentityProblem(user);
        if (daemonProblem != null) {
            throw new IllegalArgumentException(daemonProblem);
        }

        Integer owner = claim(siteId, user.name());
        if (owner != null) {
            String problem = "system user '" + user.name() + "' is already claimed by site "
                + owner + "; every site needs its own system user";
            if (required) {
                throw new IllegalArgumentException(problem);
            }
            Blast.log("PROCESS: site", siteId, "shares a system user:", problem,
                "(tolerated because process.require_dedicated_user is off)");
        }
        return user;
    }

    /**
     * Read-only identity audit of every enabled, non-deleted, managed-process site.
     * Never claims or mutates anything; feeds the dashboard attention list.
     */
    public static @NonNull List<Finding> auditAll() {
        List<Finding> findings = new ArrayList<>();
        List<Row> sites = Models.get(SiteModel.class).find()
            .where(SiteModel.ENABLED.eq(true))
            .where(SiteModel.DELETED_AT.isNull())
            .all();
        for (Row site : sites) {
            Integer siteId = site.get(SiteModel.ID);
            if (siteId == null) {
                continue;
            }
            SiteTypeHandler type = SiteTypes.getHandler((String) site.get(SiteModel.SITE_TYPE));
            if (type == null || !type.managedProcessEnvironment()) {
                continue;
            }
            Object settings = site.get(SiteModel.SETTINGS);
            Object userKey = settings instanceof Map<?, ?> map ? map.get("user") : null;
            String problem = audit(siteId, userKey);
            if (problem != null) {
                findings.add(new Finding(siteId, site.get(SiteModel.NAME), problem));
            }
        }
        return findings;
    }

    /** @return the site's identity problem, or null when it has a dedicated resolvable user */
    static @Nullable String audit(int siteId, @Nullable Object userKey) {
        SystemUsers.RunAsUser user;
        try {
            user = SystemUsers.resolve(userKey);
        } catch (IllegalStateException dangling) {
            return dangling.getMessage();
        }
        if (user == null) {
            return "no dedicated system user configured";
        }
        String daemonProblem = daemonIdentityProblem(user);
        if (daemonProblem != null) {
            return daemonProblem;
        }
        Row row = Models.get(SystemUserModel.class).find()
            .where(SystemUserModel.NAME.eq(user.name()))
            .first();
        Integer owner = row != null ? row.get(SystemUserModel.SITE_ID) : null;
        if (owner != null && owner != siteId && siteExists(owner)) {
            return "system user '" + user.name() + "' is already claimed by site " + owner;
        }
        return null;
    }

    /** @return true when this site must have a dedicated identity (setting, or tenant-managed) */
    public static boolean enforcementRequired(int siteId) {
        if (Boolean.TRUE.equals(HohenheimSettings.VALUES
                .getValue(HohenheimSettings.Process.REQUIRE_DEDICATED_USER))) {
            return true;
        }
        return isTenantManaged(siteId);
    }

    /**
     * A site any non-admin subject holds the "manage" capability on is tenant code:
     * its refusal is UNCONDITIONAL, whatever {@code require_dedicated_user} says.
     */
    static boolean isTenantManaged(int siteId) {
        try {
            for (Row grant : RecordGrants.listForRecord(SiteModel.MODEL_ID, siteId)) {
                if (HohenheimAccess.MANAGE.equals(grant.get(RecordGrantModel.CAPABILITY))
                        && Boolean.TRUE.equals(grant.get(RecordGrantModel.VALUE))) {
                    return true;
                }
            }
        } catch (IllegalStateException notInstalled) {
            // ZenitAuth.init never ran (tools, minimal tests): no tenants can exist.
            return false;
        } catch (RuntimeException e) {
            Blast.log("PROCESS: could not check tenant grants for site", siteId, "-", e.getMessage());
            // Fail closed: an unreadable grant table must not silently loosen the boundary.
            return true;
        }
        return false;
    }

    /**
     * Atomically claim the system user row for this site.
     *
     * @return null when claimed (or already ours); otherwise the owning site's id
     */
    private static @Nullable Integer claim(int siteId, @NonNull String userName) {
        SystemUserModel model = Models.get(SystemUserModel.class);
        for (int attempt = 0; attempt < 2; attempt++) {
            // Release BEFORE claiming: the unique index on site_id forbids a site
            // holding its old row and its new one even for the duration of one
            // statement. A crash in between merely leaves the site claimless until
            // its next handler construction re-claims.
            releaseOtherClaims(siteId, userName);
            long claimed = model.find()
                .where(SystemUserModel.NAME.eq(userName))
                .where(Criteria.or(
                    SystemUserModel.SITE_ID.isNull(),
                    SystemUserModel.SITE_ID.eq(siteId)))
                .assign(SystemUserModel.SITE_ID, siteId)
                .updateAll();
            if (claimed > 0) {
                return null;
            }
            Row row = model.find().where(SystemUserModel.NAME.eq(userName)).first();
            Integer owner = row != null ? row.get(SystemUserModel.SITE_ID) : null;
            if (owner == null) {
                continue;   // lost a race against a release; retry the claim once
            }
            if (siteExists(owner)) {
                return owner;
            }
            // The owning site is gone (deleted): release the stale claim and retry, so
            // a removed site never permanently blocks its user from being re-adopted.
            model.find()
                .where(SystemUserModel.NAME.eq(userName))
                .where(SystemUserModel.SITE_ID.eq(owner))
                .assign(SystemUserModel.SITE_ID, (Integer) null)
                .updateAll();
        }
        Row row = model.find().where(SystemUserModel.NAME.eq(userName)).first();
        return row != null ? row.get(SystemUserModel.SITE_ID) : null;
    }

    /** A site that switched users releases its previous claim in the same breath. */
    private static void releaseOtherClaims(int siteId, @NonNull String keptUserName) {
        Models.get(SystemUserModel.class).find()
            .where(SystemUserModel.SITE_ID.eq(siteId))
            .where(SystemUserModel.NAME.ne(keptUserName))
            .assign(SystemUserModel.SITE_ID, (Integer) null)
            .updateAll();
    }

    private static boolean siteExists(int siteId) {
        Row site = Models.get(SiteModel.class).find()
            .where(SiteModel.ID.eq(siteId))
            .where(SiteModel.DELETED_AT.isNull())
            .first();
        return site != null;
    }

    /** @return a problem string when the uid is the daemon's own identity, else null */
    private static @Nullable String daemonIdentityProblem(SystemUsers.@NonNull RunAsUser user) {
        Integer daemonUid = daemonUid();
        if (daemonUid != null && daemonUid.intValue() == user.uid()) {
            return "system user '" + user.name() + "' (uid " + user.uid()
                + ") is the Hohenheim daemon's own identity; site processes must not share it";
        }
        if (daemonUid == null && user.name().equals(System.getProperty("user.name"))) {
            return "system user '" + user.name()
                + "' is the Hohenheim daemon's own identity; site processes must not share it";
        }
        return null;
    }

    /** The daemon's real uid from /proc/self/status, or null when unreadable (non-Linux). */
    public static @Nullable Integer daemonUid() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.substring(4).trim().split("\\s+");
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // Fall through to the name-based comparison.
        }
        return null;
    }
}
