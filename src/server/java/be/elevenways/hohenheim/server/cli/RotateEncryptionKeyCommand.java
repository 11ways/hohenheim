package be.elevenways.hohenheim.server.cli;

import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.orm.crypto.FieldEncryption;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Break-glass step 1 of 3: prepend a new active key to the field-encryption keyring.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class RotateEncryptionKeyCommand implements OfflineCommand {

    @Override
    public @NonNull String flag() {
        return EncryptionKeyLane.ROTATE;
    }

    @Override
    public @NonNull String describe() {
        return "mint a new active field-encryption key (step 1 of 3; nothing already stored"
            + " becomes safer until " + EncryptionKeyLane.REENCRYPT + " has run)";
    }

    @Override
    public void run(@NonNull OfflineCommandContext context) {
        String minted = FieldEncryption.requireKeyring().rotate();
        context.print("Rotated the field-encryption keyring; the new active key is " + minted
            + " -- NOTHING already stored is safer yet. Run " + EncryptionKeyLane.REENCRYPT
            + ", then " + EncryptionKeyLane.RETIRE + " <old id>.");
    }
}
