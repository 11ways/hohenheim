package be.elevenways.hohenheim.server;

import be.elevenways.hohenheim.model.SpamserviceInstallationModel;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.server.cms.CmsSupport;
import be.elevenways.hohenheim.server.orm.PendingDeletes;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.datasource.context.RemoveFromDatasource;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.orm.query.QueryBuilder;
import be.elevenways.zenit.common.validation.Violations;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * A system user the Spamservice installation runs as refuses to go, on every delete lane.
 *
 * AIDEV-NOTE: no admin surface deletes a system user -- {@code UpdateSystemUsers} marks a
 * user that left {@code /etc/passwd} OBSOLETE precisely so stored references keep
 * resolving -- so the only writers this guards are a direct model delete and a criteria
 * delete. Without it, {@code SystemUsers.resolve} fails closed at the next Spamservice
 * start ("does not exist"), hours after the row went, which is the refusal-at-use shape
 * this repo refuses to ship. The site-side claim column ({@code system_users.site_id})
 * has no writer left and is deliberately not consulted.
 */
public final class SystemUserGuards {

    private static volatile boolean installed;

    private SystemUserGuards() {
    }

    /** Install the system-user hook; idempotent, called at the MODULES boot stage. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        SystemUserModel.SCHEMA.addBeforeRemoveHook(SystemUserGuards::refuseWhileSpamserviceRunsAsIt);
    }

    /** @throws Violations {@code system_user_in_use} naming the user */
    private static void refuseWhileSpamserviceRunsAsIt(@NonNull RemoveFromDatasource context) {
        QueryBuilder<Row> installations = Models.get(SpamserviceInstallationModel.class).find()
            .where(PendingDeletes.dependents(SpamserviceInstallationModel.SYSTEM_USER, context));
        if (installations.count() == 0) {
            return;
        }
        Row user = installations.first().get(SpamserviceInstallationModel.SYSTEM_USER);
        throw Violations.ofForm(CmsSupport.violationText("system_user_in_use")
            .withArg("name", user != null ? String.valueOf((Object) user.get(SystemUserModel.NAME)) : ""));
    }
}
