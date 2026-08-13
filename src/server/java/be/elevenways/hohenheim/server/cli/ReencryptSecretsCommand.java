package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.orm.crypto.EncryptionRekey;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Break-glass step 2 of 3: rewrite every encrypted value under the active key, then survey
 * what is left so the operator can see whether the old key is retirable.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class ReencryptSecretsCommand implements OfflineCommand {

    @Override
    public @NonNull String flag() {
        return EncryptionKeyLane.REENCRYPT;
    }

    @Override
    public @NonNull String describe() {
        return "rewrite every encrypted value under the active key (step 2 of 3; walks every"
            + " encrypted row and is safe to re-run after a crash)";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        EncryptionRekey.Report report = EncryptionRekey.reencrypt(HohenheimDatabase.datasource());
        context.print("Re-encrypted under key " + report.activeKeyId() + ": "
            + report.rowsRewritten() + " of " + report.rowsScanned() + " rows rewritten across "
            + report.models().size() + " models");
        // The survey rides this step because "did the rewrite cover everything" is the only
        // question that decides whether the next step is safe.
        EncryptionKeyLane.printSurvey(context);
    }
}
