package be.elevenways.hohenheim.server.cli;

import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.orm.crypto.EncryptionRekey;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Break-glass step 3 of 3: drop a superseded key from the keyring. Irreversible -- anything
 * still encrypted under it becomes unreadable.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class RetireEncryptionKeyCommand implements OfflineCommand {

    @Override
    public @NonNull String flag() {
        return EncryptionKeyLane.RETIRE;
    }

    @Override
    public @NonNull String describe() {
        return "<key id>  drop a superseded field-encryption key (step 3 of 3; IRREVERSIBLE,"
            + " run " + EncryptionKeyLane.SURVEY + " first)";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        String keyId = context.require(EncryptionKeyLane.RETIRE);
        EncryptionRekey.retire(keyId);
        context.print("Retired field-encryption key " + keyId
            + " -- it can no longer decrypt anything, and nothing needed it");
    }
}
