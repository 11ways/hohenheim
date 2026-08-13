package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.orm.crypto.EncryptionRekey;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * What the three halves of a field-encryption key rotation share, and the guard that keeps
 * them one deliberate step each.
 *
 * They are separate commands on purpose: rotating is instant, re-encrypting walks every
 * encrypted row and may be re-run after a crash, and retiring is irreversible. One
 * "--rotate-everything" would hide the only step that can destroy data behind the two that
 * cannot. Every one of them exits without booting a server.
 *
 * AIDEV-NOTE: {@link #requireSoleFlag} exists because the migration onto the OfflineCommand
 * registry changed one thing about the old hand-rolled helper: that helper ran EVERY flag
 * present in one invocation, while {@code OfflineCommands} dispatches exactly ONE command
 * and picks it in classpath DISCOVERY order. Without this guard,
 * {@code --rotate-encryption-key --reencrypt-secrets} would silently perform an arbitrary
 * one of the two and report success -- a rotation with no re-encryption looks identical to
 * a completed one from the outside, and the operator would retire a key that is still
 * decrypting live rows. A loud refusal is the only safe reading of an ambiguous invocation.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
final class EncryptionKeyLane {

    static final String ROTATE = "--rotate-encryption-key";
    static final String REENCRYPT = "--reencrypt-secrets";
    static final String SURVEY = "--encryption-key-survey";
    static final String RETIRE = "--retire-encryption-key";

    private static final List<String> STEPS = List.of(ROTATE, REENCRYPT, SURVEY, RETIRE);

    private EncryptionKeyLane() {
    }

    /**
     * @throws OfflineCommandException when another key-rotation step was requested in the
     *         same invocation, naming every one of them
     */
    static void requireSoleFlag(@NonNull OfflineCommandContext context, @NonNull String flag) {
        List<String> others = new ArrayList<>();
        for (String step : STEPS) {
            if (!step.equals(flag) && context.has(step)) {
                others.add(step);
            }
        }
        if (others.isEmpty()) {
            return;
        }
        throw new OfflineCommandException("REFUSED: " + flag + " was requested together with "
            + String.join(" and ", others) + ", and only ONE key-rotation step runs per"
            + " invocation. Run them one at a time, in order: " + ROTATE + ", then "
            + REENCRYPT + ", then " + RETIRE + " <old key id>.");
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
