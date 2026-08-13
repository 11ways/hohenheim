package be.elevenways.hohenheim.server.cli;

import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Break-glass: report how many stored values still read under each field-encryption key,
 * without writing anything.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class EncryptionKeySurveyCommand implements OfflineCommand {

    @Override
    public @NonNull String flag() {
        return EncryptionKeyLane.SURVEY;
    }

    @Override
    public @NonNull String describe() {
        return "report how many stored values still read under each field-encryption key"
            + " (read-only; this is what proves a key is retirable)";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        EncryptionKeyLane.printSurvey(context);
    }
}
