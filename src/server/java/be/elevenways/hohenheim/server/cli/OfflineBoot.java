package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.server.cli.HostConsole;
import be.elevenways.zenit.server.cli.OfflineCommandException;
import be.elevenways.zenit.server.cli.OfflineCommands;
import be.elevenways.zenit.server.cli.ServerCli;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.function.Consumer;

/**
 * Hohenheim's dispatch of the framework break-glass lane: settings, then the datasource,
 * then {@link OfflineCommands}, then exit -- never an HTTP boot.
 *
 * AIDEV-NOTE: this call is the whole reason the lane exists on this host. Until 2026-08-13
 * NO application in the workspace dispatched {@code OfflineCommands.runIfRequested}, so
 * zenit-auth's {@code --set-password} -- the documented lost-administrator recovery path --
 * was unreachable in every deployed jar and {@code --offline-help} printed nothing anywhere.
 * The registry existed, the command existed, the test existed, and the operator lane did not.
 * Do not delete this call; a scheduled task or an admin page cannot replace it, because it
 * exists precisely for the state in which neither runs.
 *
 * AIDEV-NOTE: the ORDER is load-bearing and is the framework's documented host shape --
 * settings, register the datasource, THEN dispatch. A command sees the same ORM a booted
 * server would (that is the contract {@code OfflineCommand} is written against), while
 * nothing request-facing is started: no listeners, no site types, no process monitor, no
 * task runtime. Migrations stay AHEAD of this lane, as in the framework's own example --
 * a break-glass command against an unmigrated schema would fail for the wrong reason.
 *
 * @author Jelle De Loecker
 * @since 0.7.0
 */
public final class OfflineBoot {

    private OfflineBoot() {
    }

    /**
     * Runs the requested offline command and reports whether {@code main} must stop.
     *
     * AIDEV-NOTE: the leading option scan is a COST guard, not a second flag registry.
     * Discovery is a full ClassGraph pass over the fat jar, and a normal boot passes no
     * options at all, so paying for the scan (plus a settings load and a pool) on every
     * start would be a real regression for a lane that runs by hand a few times a year.
     * An option this build does not declare is REFUSED by the framework's argv gate
     * before anything opens; only declared framework options fall through to a boot.
     *
     * @return true when a command (or {@code --offline-help}) ran
     */
    public static boolean runIfRequested(String[] args) {
        return runIfRequested(args, HostConsole.SYSTEM);
    }

    /**
     * The host face over a console: a refusal (an undeclared option, a typo) is printed on
     * the console's stderr and exits it with 1, instead of escaping as a stack trace.
     *
     * @return true when {@code main} must stop, a refusal included
     */
    public static boolean runIfRequested(String[] args, @NonNull HostConsole console) {
        return console.answer(out -> runIfRequested(args, out));
    }

    /**
     * The output-capturing variant, mirroring {@link OfflineCommands}; tests capture here.
     *
     * AIDEV-NOTE: the argv gate ({@link ServerCli#answerProbeArguments}) runs FIRST, before
     * the settings load and before any datasource exists. A mistyped break-glass flag
     * ({@code --restore-control-plan}) must be refused having touched nothing -- the
     * operator typed it precisely because they meant to replace this database -- and an
     * undeclared option must never fall through to a normal boot.
     *
     * @throws OfflineCommandException when the invocation names an option or token this
     *         build does not understand, or when the selected command refuses
     */
    public static boolean runIfRequested(String[] args, @NonNull Consumer<String> out) {
        if (args == null || !namesAnOption(args)) {
            return false;
        }
        if (ServerCli.answerProbeArguments(args, out)) {
            return true;
        }

        HohenheimSettingsBoot.load();
        // Registry writes only, and the same ones the migration lane does: a command that
        // touches a grant-aware model must see the same liveness declarations a boot would.
        HohenheimAccess.declareGrantableModels();
        HohenheimDatabase.openDatasource();

        try {
            return OfflineCommands.runIfRequested(args, out);
        } finally {
            HohenheimDatabase.closeDatasource();
        }
    }

    private static boolean namesAnOption(String[] args) {
        for (String arg : args) {
            if (arg != null && arg.startsWith("--")) {
                return true;
            }
        }
        return false;
    }
}
