package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.orm.HistorySecretPurge;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Break-glass step 2 of 2: rewrite every {@code zenit_revisions} / {@code zenit_activity}
 * value the CURRENT schema calls secret, and every value it cannot judge.
 *
 * IRREVERSIBLE: the historical plaintext is gone afterwards, which is the point. It is a
 * COMMAND and not a migration on purpose -- see the class note on why an unattended boot is
 * the wrong place for a destructive rewrite of audit history, and why this is a step that
 * gets re-run every time a field is newly declared secret rather than a one-time heal.
 *
 * Idempotent: a second run reports nothing to do, because the rewrite converges on exactly
 * what RevisionableBehaviour and ActivityLog write today.
 *
 * Stop the server first. The survey half is a pure read and is safe against a live install,
 * but this half writes to the same SQLite file the running server holds open, and an operator
 * who is about to destroy audit history should be taking a backup at that moment anyway.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class PurgeHistorySecretsCommand implements OfflineCommand {

    public static final String FLAG = "--purge-history-secrets";

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "rewrite pre-redaction secrets out of zenit_revisions/zenit_activity (step 2 of 2;"
            + " IRREVERSIBLE, rewrites values in place and never deletes a row; stop the server"
            + " first, and run " + HistorySecretSurveyCommand.FLAG + " first and read what it names)";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        SoleOfflineFlag.require(context, FLAG);

        for (String line : HistorySecretPurge.purge(HohenheimDatabase.datasource()).describe()) {
            context.print(line);
        }
    }
}
