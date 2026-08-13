package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.orm.HistorySecretPurge;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Break-glass step 1 of 2: report which history rows still hold a value the CURRENT schema
 * calls secret, writing nothing.
 *
 * Safe on a live install: it only reads, and it prints locations and counts -- never a value,
 * not even a redacted one. This is the step an operator runs first, and its output is the
 * whole basis for deciding whether to run the irreversible one.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class HistorySecretSurveyCommand implements OfflineCommand {

    public static final String FLAG = "--history-secret-survey";

    @Override
    public @NonNull String flag() {
        return FLAG;
    }

    @Override
    public @NonNull String describe() {
        return "report which zenit_revisions/zenit_activity rows still hold a value the current"
            + " schema calls secret (read-only, prints no values; run this before "
            + PurgeHistorySecretsCommand.FLAG + ")";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        SoleOfflineFlag.require(context, FLAG);

        for (String line : HistorySecretPurge.survey(HohenheimDatabase.datasource()).describe()) {
            context.print(line);
        }
    }
}
