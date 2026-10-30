package be.elevenways.hohenheim.server.util;

import be.elevenways.protoblast.common.util.Closeables;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A listener whose bind fails closes the channel it opened.
 *
 * @author Jelle De Loecker
 * @since 0.1.0
 */
class SameUidListenerBindFailureTest {

    @Test
    void aFailedBindLeavesNoOpenChannel() {
        AtomicReference<ServerSocketChannel> opened = new AtomicReference<>();

        // A channel already bound makes the constructor's own bind throw.
        assertThatThrownBy(() -> new SameUidListener("bind-failure-", "the bind failure test", (peer, port) -> null,
                null, Closeables::closeQuietly, () -> {
                    ServerSocketChannel channel = ServerSocketChannel.open();
                    opened.set(channel);
                    channel.bind(new InetSocketAddress("127.0.0.1", 0));
                    return channel;
                }))
            .isInstanceOf(AlreadyBoundException.class);

        assertThat(opened.get().isOpen()).as("the channel it opened is closed").isFalse();
    }
}
