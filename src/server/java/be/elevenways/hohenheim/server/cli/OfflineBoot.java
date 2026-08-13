package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsFiles;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.server.ServerZenitRuntime;
import be.elevenways.zenit.server.cli.OfflineCommands;
import be.elevenways.zenit.server.setting.ServerSettings;
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
     * An option we do not recognise still falls through to a normal boot; it costs one
     * discarded SQLite pool, which is why the guard tests for options and nothing finer.
     *
     * @return true when a command (or {@code --offline-help}) ran
     */
    public static boolean runIfRequested(String[] args) {
        return runIfRequested(args, System.out::println);
    }

    /** The output-capturing variant, mirroring {@link OfflineCommands}; tests capture here. */
    public static boolean runIfRequested(String[] args, @NonNull Consumer<String> out) {
        if (args == null || !namesAnOption(args)) {
            return false;
        }

        HohenheimSettingsFiles.load();
        ServerSettings.VALUES.loadFrom(ServerZenitRuntime.defaultSettingsSources());
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
