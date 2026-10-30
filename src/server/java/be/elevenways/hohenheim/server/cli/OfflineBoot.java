package be.elevenways.hohenheim.server.cli;

import be.elevenways.hohenheim.server.HohenheimDatabase;
import be.elevenways.hohenheim.server.HohenheimSettingsBoot;
import be.elevenways.hohenheim.server.auth.HohenheimAccess;
import be.elevenways.zenit.server.cli.OfflineCommand;
import be.elevenways.zenit.server.cli.OfflineCommands;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Hohenheim's preparation of the framework break-glass lane: settings, then the datasource, then the command, then
 * exit -- never an HTTP boot. A command that needs no host ({@code --build-info}) runs without either.
 *
 * AIDEV-NOTE: the dispatch ({@code ServerZenitRuntime.answerLanes} over HohenheimHost) is the whole reason the lane
 * exists on this host. Until 2026-08-13 NO application in the workspace dispatched
 * {@code OfflineCommands.runIfRequested}, so zenit-auth's {@code --set-password} -- the documented lost-administrator
 * recovery path -- was unreachable in every deployed jar and {@code --offline-help} printed nothing anywhere. The
 * registry existed, the command existed, the test existed, and the operator lane did not. Do not delete that dispatch;
 * a scheduled task or an admin page cannot replace it, because it exists precisely for the state in which neither
 * runs.
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
     * Settings and grant declarations, plus the datasource for a command that needs the whole host; released after.
     */
    public static OfflineCommands.@NonNull PreparedHost prepareHost(OfflineCommand.@NonNull Needs needs) {
        HohenheimSettingsBoot.load();
        // Registry writes only, and the same ones the migration lane does: a command that
        // touches a grant-aware model must see the same liveness declarations a boot would.
        HohenheimAccess.declareGrantableModels();
        return switch (needs) {
            case NOTHING, SETTINGS -> () -> { };
            case HOST -> {
                HohenheimDatabase.openDatasource();
                yield HohenheimDatabase::closeDatasource;
            }
        };
    }
}
