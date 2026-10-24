package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimIds;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.protoblast.common.annotation.BlastAutoLoad;
import be.elevenways.zenit.common.secret.RevealableSecret;
import be.elevenways.zenit.common.secret.SecretGate;

/**
 * The stored secrets Hohenheim's pages reveal on request, through core's {@link RevealableSecret}: a page renders the
 * reference ({@code pl-secret-reveal}), never the value.
 *
 * AIDEV-NOTE: the database password's gate is the operator OR the {@code credentials} capability on that database,
 * the one delegated door ({@link ManageDatabaseCredentialsPage}); never {@link DatabaseConnectionCard#passwordReadableBy},
 * which judges the admin Restore tab's record gate alone and opens for any reader of the record: a reveal request
 * passes no panel admission, so that gate would admit a view-only tenant. The webhook secret is the operator's alone,
 * as the Deploys tab that offers it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
@BlastAutoLoad
public final class HohenheimSecrets {

    /** A managed database's password. */
    public static final RevealableSecret DATABASE_PASSWORD = RevealableSecret.onModel(
            HohenheimIds.id("database_password"), DatabaseModel.MODEL_ID, DatabaseModel.DB_PASSWORD.getName())
        .gate(SecretGate.permission(HohenheimPanel.ACCESS)
            .or(SecretGate.capability(HohenheimCapabilities.CREDENTIALS)))
        .declare();

    /** The push webhook secret of an instance carrying a git source, a key of its settings map. */
    public static final RevealableSecret WEBHOOK_SECRET = RevealableSecret.onModel(
            HohenheimIds.id("webhook_secret"), InstanceModel.MODEL_ID, "settings." + GitSourceSchema.WEBHOOK_SECRET)
        .gate(SecretGate.permission(HohenheimPanel.ACCESS))
        .value(instance -> ApplicationReleases.storedSettings(instance).get(GitSourceSchema.WEBHOOK_SECRET))
        .declare();

    private HohenheimSecrets() {
    }
}
