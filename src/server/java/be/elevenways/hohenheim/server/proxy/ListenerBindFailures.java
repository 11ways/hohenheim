package be.elevenways.hohenheim.server.proxy;

import be.elevenways.hohenheim.HohenheimMicrocopy;
import be.elevenways.hohenheim.model.SystemUserModel;
import be.elevenways.hohenheim.ports.StreamProtocol;
import be.elevenways.hohenheim.server.util.LoopbackPeers;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.zenit.common.orm.datasource.Row;
import be.elevenways.zenit.common.orm.model.Models;
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

    /** The bind refusals this explains, each the suffix of its sentence's key. */
    private static final String PRIVILEGED = "port_privileged";
    private static final String IN_USE = "port_in_use";

    private ListenerBindFailures() {}

    /**
     * @param failure the listener's start failure, its bind refusal anywhere in its cause chain
     * @param port    the port it tried to listen on
     * @param setting the setting naming that port
     * @return what to change, null when the failure is no bind refusal this knows
     */
    static @Nullable Microcopy explain(@NonNull Throwable failure, int port, @NonNull SettingDefinition<Integer> setting) {
        String refusal = refusalOf(failure);
        return refusal == null ? null : HohenheimMicrocopy.ATTENTION_DETAIL.of("listener_" + refusal)
            .withArg("port", String.valueOf(port)).withArg("setting", setting.getPath());
    }

    /**
     * A stream port the proxy could not open: which port, who holds it when the kernel says it is taken, and what to
     * do; a failure this does not know keeps its raw message.
     */
    static @NonNull Microcopy explainStream(@NonNull Throwable failure, @NonNull String name, int port,
                                            @NonNull StreamProtocol protocol) {
        String refusal = refusalOf(failure);
        Microcopy words = HohenheimMicrocopy.ATTENTION_DETAIL.of("stream_" + (refusal == null ? "port_failed" : refusal))
            .withArg("name", name).withArg("port", String.valueOf(port)).withArg("protocol", protocol.token())
            .withArg("reason", String.valueOf(failure.getMessage()));
        if (IN_USE.equals(refusal)) {
            words = words.withArg("holder", holderOf(port, protocol));
        }
        return words;
    }

    /** @return who holds a port this process could not bind: the account its socket runs as, when the kernel says */
    private static @NonNull Microcopy holderOf(int port, @NonNull StreamProtocol protocol) {
        // A UDP-only stream asks the UDP tables; one forwarding TCP fails on its TCP bind first.
        Integer uid = LoopbackPeers.listenerOwnerOf(port, !protocol.forwardsTcp());
        if (uid == null) {
            return HohenheimMicrocopy.ATTENTION_DETAIL.of("stream_port_holder_unknown");
        }
        Row user = Models.get(SystemUserModel.class).find().where(SystemUserModel.UID.eq(uid)).first();
        String account = user != null && user.get(SystemUserModel.NAME) instanceof String known ? known
            : "uid " + uid;
        return HohenheimMicrocopy.ATTENTION_DETAIL.of("stream_port_holder_user").withArg("user", account);
    }

    /** @return the known bind refusal anywhere in the cause chain, null when there is none */
    private static @Nullable String refusalOf(@NonNull Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof BindException bind) || bind.getMessage() == null) {
                continue;
            }
            String refusal = bind.getMessage().contains(PERMISSION_DENIED) ? PRIVILEGED
                : bind.getMessage().contains(ADDRESS_IN_USE) ? IN_USE : null;
            if (refusal != null) {
                return refusal;
            }
        }
        return null;
    }
}
