package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.setting.SettingDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.BindException;

/**
 * A listener port the kernel refused, in the words of what an operator changes: the port, the setting that moves it,
 * and for a privileged port the capability that allows it.
 *
 * AIDEV-NOTE: the JDK carries a bind's errno only as its message text ("Permission denied" is EACCES, "Address already
 * in use" is EADDRINUSE); every other failure keeps its raw message.
 *
 * @author Jelle De Loecker
 * @since  0.1.0
 */
final class ListenerBindFailures {

    private static final String PERMISSION_DENIED = "Permission denied";
    private static final String ADDRESS_IN_USE = "Address already in use";

    private ListenerBindFailures() {}

    /**
     * @param failure the listener's start failure, its bind refusal anywhere in its cause chain
     * @param port    the port it tried to listen on
     * @param setting the setting naming that port
     * @return what to change, null when the failure is no bind refusal this knows
     */
    static @Nullable Microcopy explain(@NonNull Throwable failure, int port, @NonNull SettingDefinition<Integer> setting) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof BindException bind) || bind.getMessage() == null) {
                continue;
            }
            String key = bind.getMessage().contains(PERMISSION_DENIED) ? "listener_port_privileged"
                : bind.getMessage().contains(ADDRESS_IN_USE) ? "listener_port_in_use" : null;
            if (key != null) {
                return HohenheimMicrocopy.ATTENTION_DETAIL.of(key).withArg("port", String.valueOf(port))
                    .withArg("setting", setting.getPath());
            }
        }
        return null;
    }
}
