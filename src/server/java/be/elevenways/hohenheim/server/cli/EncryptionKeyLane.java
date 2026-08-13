package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.orm.crypto.EncryptionRekey;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * What the three halves of a field-encryption key rotation share.
 *
 * They are separate commands on purpose: rotating is instant, re-encrypting walks every
 * encrypted row and may be re-run after a crash, and retiring is irreversible. One
 * "--rotate-everything" would hide the only step that can destroy data behind the two that
 * cannot. Every one of them exits without booting a server.
 *
 * AIDEV-NOTE: TOMBSTONE. This lane used to carry its own {@code requireSoleFlag} guard, and
 * so did every other hohenheim command (a shared {@code SoleOfflineFlag}), because
 * {@code OfflineCommands} dispatched exactly ONE command chosen in classpath DISCOVERY order
 * -- so {@code --rotate-encryption-key --reencrypt-secrets} performed an arbitrary one of the
 * two and reported success, after which an operator would retire a key still decrypting live
 * rows. Both guards are DELETED because zenit's {@code OfflineCommands.requireSoleCommand}
 * now refuses a multi-command invocation before any command's run() is entered: a per-command
 * guard could never be the real fix (whichever command wins discovery decides, and zenit-auth's
 * --set-password guarded nothing), and a guard that can no longer be reached is untestable
 * code that reads as the thing protecting you. Do not reintroduce one here. The ORDERING
 * advice the old message carried now lives where an operator actually reads it: each command's
 * describe(), which is what {@code --offline-help} prints.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
final class EncryptionKeyLane {

    static final String ROTATE = "--rotate-encryption-key";
    static final String REENCRYPT = "--reencrypt-secrets";
    static final String SURVEY = "--encryption-key-survey";
    static final String RETIRE = "--retire-encryption-key";

    private EncryptionKeyLane() {
    }

    /** Prints how many stored values still read under each key; the proof retiring is safe. */
    static void printSurvey(@NonNull OfflineCommandContext context) {
        EncryptionRekey.Survey result = EncryptionRekey.survey(HohenheimDatabase.datasource());
        for (var entry : result.valuesByKeyId().entrySet()) {
            context.print("field-encryption key " + entry.getKey() + ": " + entry.getValue()
                + " stored value(s) still read under it");
        }
    }
}
