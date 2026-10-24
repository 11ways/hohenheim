package be.elevenways.hohenheim.server.instance;

import be.elevenways.hohenheim.RawValues;
import be.elevenways.protoblast.common.dry.Dry;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The Minecraft Java edition server-list ping: a handshake asking for the status state and a status request, answered
 * by one length-prefixed packet holding the server's status JSON.
 *
 * AIDEV-NOTE: the handshake names protocol version -1, the convention for "I only ask your status": a server answers
 * with its own version instead of refusing an old client, and a Velocity proxy answers for its whole network. The ping
 * packet that normally follows is never sent; the answer is complete once its declared length has arrived.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
final class MinecraftStatusPing {

    /** The largest answer read: the status JSON with a server icon fits many times over. */
    static final int MAX_ANSWER = 256 * 1024;

    /** The address the handshake names: the probe asks the workload host's loopback. */
    private static final String ADDRESS = "127.0.0.1";

    /**
     * What a status answer reports.
     *
     * @param online   the players online
     * @param capacity the most players the server says it takes
     */
    record Reading(int online, int capacity) {
    }

    private MinecraftStatusPing() {
    }

    /** @return the handshake and status request for a server on {@code port} */
    static byte @NonNull [] request(int port) {
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, -1);
        byte[] address = ADDRESS.getBytes(StandardCharsets.UTF_8);
        writeVarInt(handshake, address.length);
        handshake.writeBytes(address);
        handshake.write((port >> 8) & 0xFF);
        handshake.write(port & 0xFF);
        writeVarInt(handshake, 1);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        frame(request, handshake.toByteArray());
        frame(request, new byte[]{0x00});
        return request.toByteArray();
    }

    /** @return whether the bytes hold one whole packet, or a length prefix no answer could have */
    static boolean complete(byte @NonNull [] soFar) {
        int[] length = readVarInt(soFar, 0);
        if (length == null) {
            return soFar.length >= 5;
        }
        return length[0] < 0 || length[0] > MAX_ANSWER || soFar.length - length[1] >= length[0];
    }

    /**
     * The players the answer reports; a malformed or hostile answer (lengths past its end, an overlong VarInt, JSON
     * nested past the parser's depth) reads as no answer, never as a failure.
     *
     * @return null when the bytes are no status answer
     */
    static @Nullable Reading read(byte @NonNull [] answer) {
        try {
            return decode(answer);
        } catch (RuntimeException | StackOverflowError malformed) {
            return null;
        }
    }

    private static @Nullable Reading decode(byte @NonNull [] answer) {
        int[] length = readVarInt(answer, 0);
        if (length == null || length[0] <= 0 || length[0] > answer.length - length[1]) {
            return null;
        }
        int end = length[1] + length[0];
        int[] packetId = readVarInt(answer, length[1]);
        if (packetId == null || packetId[0] != 0x00 || packetId[1] > end) {
            return null;
        }
        int[] textLength = readVarInt(answer, packetId[1]);
        if (textLength == null || textLength[0] < 0 || textLength[1] > end || textLength[0] > end - textLength[1]) {
            return null;
        }
        String json = new String(answer, textLength[1], textLength[0], StandardCharsets.UTF_8);
        // Strict JSON with the parser's own depth bound and no revivers: the text comes from whatever answers the port.
        Map<String, Object> status = RawValues.mapOrNull(new Dry().parseJsonTree(json));
        Map<String, Object> players = status == null ? null : RawValues.mapOrNull(status.get("players"));
        if (players == null || !(players.get("online") instanceof Number online)) {
            return null;
        }
        return new Reading(count(online), players.get("max") instanceof Number max ? count(max) : 0);
    }

    /** @return the number as a count: negative reads as 0, past {@link Integer#MAX_VALUE} as that */
    static int count(@NonNull Number number) {
        double value = number.doubleValue();
        if (!(value > 0)) {
            return 0;
        }
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static void frame(@NonNull ByteArrayOutputStream out, byte @NonNull [] packet) {
        writeVarInt(out, packet.length);
        out.writeBytes(packet);
    }

    private static void writeVarInt(@NonNull ByteArrayOutputStream out, int value) {
        int rest = value;
        do {
            int part = rest & 0x7F;
            rest >>>= 7;
            out.write(rest != 0 ? part | 0x80 : part);
        } while (rest != 0);
    }

    /**
     * @return the value and the offset after it, or null when the bytes end inside it or it does not fit 32 bits (more
     *         than five bytes, or a fifth byte carrying more than the top four bits)
     */
    private static int @Nullable [] readVarInt(byte @NonNull [] bytes, int offset) {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            if (offset < 0 || index >= bytes.length - offset) {
                return null;
            }
            int part = bytes[offset + index] & 0xFF;
            if (index == 4 && (part & 0xF0) != 0) {
                return null;
            }
            value |= (part & 0x7F) << (7 * index);
            if ((part & 0x80) == 0) {
                return new int[]{value, offset + index + 1};
            }
        }
        return null;
    }
}
