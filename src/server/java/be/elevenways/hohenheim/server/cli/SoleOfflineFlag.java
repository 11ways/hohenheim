package be.elevenways.hohenheim.server.cli;

import be.elevenways.zenit.server.cli.OfflineCommandContext;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.cli.OfflineCommands;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses an invocation that names more than one offline command.
 *
 * AIDEV-NOTE: {@code OfflineCommands.runIfRequested} runs exactly ONE command and picks it in
 * classpath DISCOVERY order, which is effectively arbitrary. A two-command invocation
 * therefore performs an unpredictable one of them and reports success -- the silent-success
 * shape, with the operator's other request simply never happening. EncryptionKeyLane already
 * guarded this WITHIN its own four flags; that is not enough, because the pair that gets
 * picked apart can span lanes ({@code --purge-history-secrets --rotate-encryption-key}), and a
 * per-lane guard cannot see a flag outside its lane. This one asks the REGISTRY, so every
 * discovered command counts and a command added later is covered without being told.
 *
 * KNOWN GAP: a command that never calls this stays a hole from its own side -- if IT wins
 * discovery, nothing refuses. Every hohenheim command calls it; zenit-auth's
 * {@code --set-password} does not, and cannot be made to from here. The real fix is for
 * {@code OfflineCommands} itself to refuse a multi-command invocation before dispatching,
 * which is a framework change.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
final class SoleOfflineFlag {

    private SoleOfflineFlag() {
    }

    /**
     * @throws OfflineCommandException when any OTHER discovered command's flag is also present
     */
    static void require(@NonNull OfflineCommandContext context, @NonNull String flag) {
        List<String> others = new ArrayList<>();
        for (String candidate : OfflineCommands.commandsByFlag().keySet()) {
            if (!candidate.equals(flag) && context.has(candidate)) {
                others.add(candidate);
            }
        }
        if (others.isEmpty()) {
            return;
        }
        throw new OfflineCommandException("REFUSED: " + flag + " was requested together with "
            + String.join(" and ", others) + ". Exactly ONE offline command runs per invocation,"
            + " chosen in classpath discovery order, so this would silently perform an arbitrary"
            + " one of them and report success. Run them one at a time.");
    }
}
