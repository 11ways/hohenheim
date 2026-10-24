package be.elevenways.hohenheim.server.cms;

import be.elevenways.hohenheim.HohenheimCapabilities;
import be.elevenways.hohenheim.HohenheimEndpoints;
import be.elevenways.hohenheim.StoredSecret;
import be.elevenways.hohenheim.model.DatabaseModel;
import be.elevenways.hohenheim.model.InstanceModel;
import be.elevenways.hohenheim.server.application.ApplicationReleases;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.hohenheim.source.GitSourceSchema;
import be.elevenways.zenit.cms.common.ZenitCmsActivityAction;
import be.elevenways.zenit.common.conduit.Conduit;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.history.Change;
import be.elevenways.zenit.common.orm.history.History;
import be.elevenways.zenit.common.orm.model.Model;
import be.elevenways.zenit.common.orm.model.Models;
import be.elevenways.zenit.common.result.ActionResult;
import be.elevenways.zenit.common.result.DryResult;
import be.elevenways.zenit.common.security.AccessContext;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * The handler of {@link HohenheimEndpoints#SECRETS_REVEAL}: answers one record's stored secret to a caller the
 * secret's own gate admits and records the reveal on that record, naming the field and never the value.
 *
 * AIDEV-NOTE: absence and refusal are ONE answer (404), as on the backup download: a reveal URL is keyed by record id,
 * and telling "not yours" from "no such record" would be an oracle over other tenants' records. The activity entry is
 * zenit-cms's own revealed-secret fact (the verb its masked-field reveal records), which CmsBoot permits on every
 * panel resource's model, so a reveal reads the same in the history whichever lane disclosed it.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class SecretReveals {

    private SecretReveals() {
    }

    /** One admitted disclosure: the model the reveal is recorded on and the stored value. */
    private record Disclosure(@NonNull Model model, @NonNull String value) {
    }

    public static void init() {
        HohenheimEndpoints.SECRETS_REVEAL.setHandler(SecretReveals::reveal);
    }

    private static @Nullable ActionResult<String> reveal(@NonNull Conduit conduit) {
        StoredSecret secret = StoredSecret.forToken(conduit.getParameter(HohenheimEndpoints.SECRET));
        Integer recordId = conduit.getParameter(HohenheimEndpoints.SECRET_RECORD_ID);
        Disclosure disclosure = secret == null || recordId == null ? null
            : admitted(secret, recordId, AccessContext.of(conduit));
        if (disclosure == null) {
            conduit.notFound();
            return null;
        }
        History.event(disclosure.model(), recordId,
            Change.fact(ZenitCmsActivityAction.SECRET_REVEALED, Map.of("field", secret.field())));
        return new DryResult<>(disclosure.value());
    }

    /** @return the disclosure the caller may have, null for a missing record or a caller the secret's gate refuses */
    private static @Nullable Disclosure admitted(@NonNull StoredSecret secret, int recordId,
                                                 @NonNull AccessContext access) {
        return switch (secret) {
            case DATABASE_PASSWORD -> {
                Model databases = Models.get(DatabaseModel.class);
                Row database = databases.findById(recordId);
                yield database == null || !databasePasswordReadableBy(database, access) ? null
                    : new Disclosure(databases, Objects.toString(database.get(DatabaseModel.DB_PASSWORD), ""));
            }
            case WEBHOOK_SECRET -> {
                // The Deploys tab shows the push address and secret to the operator alone.
                if (!HohenheimAccess.isAdmin(access)) {
                    yield null;
                }
                Model instances = Models.get(InstanceModel.class);
                Row instance = instances.findById(recordId);
                yield instance == null ? null : new Disclosure(instances, Objects.toString(
                    ApplicationReleases.storedSettings(instance).get(GitSourceSchema.WEBHOOK_SECRET), ""));
            }
        };
    }

    /**
     * Whether the caller may read the database's password: the operator (the admin panel's permission), or the
     * {@code credentials} capability on that database, the one delegated door ({@link ManageDatabaseCredentialsPage}).
     *
     * AIDEV-NOTE: never {@link DatabaseConnectionCard#passwordReadableBy} here. That judges the admin Restore tab's
     * record gate alone, which opens for any reader of the record; on a page the admin panel's admission stands in
     * front of it, but a reveal request passes no panel admission, so it would admit a view-only tenant.
     */
    private static boolean databasePasswordReadableBy(@NonNull Row database, @NonNull AccessContext access) {
        return HohenheimAccess.isAdmin(access)
            || HohenheimAccess.hasDatabaseCapability(access, database.get(DatabaseModel.ID),
                HohenheimCapabilities.CREDENTIALS);
    }
}
