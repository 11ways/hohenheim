package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import be.elevenways.hohenheim.server.security.IpLiterals;

/** Reads one bounded TLS ClientHello and retains every consumed wire byte for replay. */
public final class TlsClientHelloReader {

    public static final int DEFAULT_MAX_BYTES = 64 * 1024;

    private static final int TLS_HEADER_LENGTH = 5;
    private static final int MAX_TLS_PLAINTEXT_LENGTH = 16 * 1024;
    private static final int CONTENT_TYPE_HANDSHAKE = 22;
    private static final int HANDSHAKE_TYPE_CLIENT_HELLO = 1;
    private static final int EXTENSION_SERVER_NAME = 0;
    private static final int SERVER_NAME_HOST_NAME = 0;

    private TlsClientHelloReader() {
    }

    /** Contains the normalized SNI name, when present, and an immutable replay-byte snapshot. */
    public static final class Result {

        private final @Nullable String serverName;
        private final byte[] replayBytes;

        private Result(@Nullable String serverName, byte[] replayBytes) {
            this.serverName = serverName;
            this.replayBytes = replayBytes.clone();
        }

        public Optional<String> serverName() {
            return Optional.ofNullable(serverName);
        }

        public byte[] replayBytes() {
            return replayBytes.clone();
        }
    }

    /** Reads a ClientHello using the default wire-byte budget. */
    public static Result read(InputStream input) throws IOException {
        return read(input, DEFAULT_MAX_BYTES);
    }

    /** Reads a ClientHello without configuring or taking ownership of the input source. */
    public static Result read(InputStream input, int maxBytes) throws IOException {
        Objects.requireNonNull(input, "input");
        if (maxBytes < TLS_HEADER_LENGTH + 4) {
            throw new IllegalArgumentException("maxBytes is too small for a TLS handshake");
        }

        ByteArrayOutputStream wire = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        ByteArrayOutputStream handshake = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        int expectedHandshakeLength = -1;
        int firstHandshakeByte = -1;

        while (expectedHandshakeLength < 0 || handshake.size() < expectedHandshakeLength) {
            if (wire.size() + TLS_HEADER_LENGTH > maxBytes) {
                throw new ProtocolException("ClientHello exceeds the wire-byte limit");
            }

            byte[] recordHeader = readExactly(input, TLS_HEADER_LENGTH);
            wire.write(recordHeader);
            validateRecordHeader(recordHeader);

            int recordLength = unsignedShort(recordHeader, 3);
            if (recordLength == 0 || recordLength > MAX_TLS_PLAINTEXT_LENGTH) {
                throw new ProtocolException("invalid TLS handshake record length");
            }
            if (wire.size() + recordLength > maxBytes) {
                throw new ProtocolException("ClientHello exceeds the wire-byte limit");
            }

            byte[] payload = readExactly(input, recordLength);
            wire.write(payload);
            handshake.write(payload);

            if (firstHandshakeByte < 0 && payload.length > 0) {
                firstHandshakeByte = payload[0] & 0xff;
            }
            if (firstHandshakeByte >= 0 && firstHandshakeByte != HANDSHAKE_TYPE_CLIENT_HELLO) {
                throw new ProtocolException("first TLS handshake message is not a ClientHello");
            }
            if (handshake.size() >= 4 && expectedHandshakeLength < 0) {
                byte[] handshakeBytes = handshake.toByteArray();
                expectedHandshakeLength = 4 + unsignedMedium(handshakeBytes, 1);
                if (expectedHandshakeLength > maxBytes) {
                    throw new ProtocolException("ClientHello exceeds the configured limit");
                }
            }
        }

        byte[] handshakeBytes = handshake.toByteArray();
        String serverName = parseClientHello(handshakeBytes, expectedHandshakeLength);
        return new Result(serverName, wire.toByteArray());
    }

    private static void validateRecordHeader(byte[] header) throws ProtocolException {
        if ((header[0] & 0xff) != CONTENT_TYPE_HANDSHAKE) {
            throw new ProtocolException("expected a TLS handshake record");
        }
        int major = header[1] & 0xff;
        int minor = header[2] & 0xff;
        if (major != 3 || minor < 1 || minor > 3) {
            throw new ProtocolException("unsupported TLS record version");
        }
    }

    private static @Nullable String parseClientHello(byte[] message, int messageLength)
        throws ProtocolException {
        Cursor cursor = new Cursor(message, 4, messageLength);
        int legacyVersion = cursor.readUnsignedShort("ClientHello legacy version");
        if (legacyVersion < 0x0300 || legacyVersion > 0x0303) {
            throw new ProtocolException("unsupported ClientHello legacy version");
        }

        cursor.skip(32, "ClientHello random");
        int sessionIdLength = cursor.readUnsignedByte("session id length");
        if (sessionIdLength > 32) {
            throw new ProtocolException("invalid ClientHello session id length");
        }
        cursor.skip(sessionIdLength, "session id");

        int cipherSuitesLength = cursor.readUnsignedShort("cipher suites length");
        if (cipherSuitesLength < 2 || (cipherSuitesLength & 1) != 0) {
            throw new ProtocolException("invalid ClientHello cipher suites length");
        }
        cursor.skip(cipherSuitesLength, "cipher suites");

        int compressionMethodsLength = cursor.readUnsignedByte("compression methods length");
        if (compressionMethodsLength == 0) {
            throw new ProtocolException("ClientHello has no compression methods");
        }
        cursor.skip(compressionMethodsLength, "compression methods");

        if (cursor.remaining() == 0) {
            return null;
        }

        int extensionsLength = cursor.readUnsignedShort("extensions length");
        if (extensionsLength != cursor.remaining()) {
            throw new ProtocolException("ClientHello extensions length does not match its contents");
        }

        String serverName = null;
        Set<Integer> extensionTypes = new HashSet<>();
        while (cursor.remaining() > 0) {
            int type = cursor.readUnsignedShort("extension type");
            int length = cursor.readUnsignedShort("extension length");
            if (!extensionTypes.add(type)) {
                throw new ProtocolException("duplicate ClientHello extension type");
            }
            Cursor extension = cursor.readSlice(length, "extension contents");
            if (type == EXTENSION_SERVER_NAME) {
                serverName = parseServerName(extension);
            }
        }
        return serverName;
    }

    private static @Nullable String parseServerName(Cursor extension) throws ProtocolException {
        int namesLength = extension.readUnsignedShort("server name list length");
        if (namesLength == 0 || namesLength != extension.remaining()) {
            throw new ProtocolException("invalid server name list length");
        }

        String hostName = null;
        Set<Integer> nameTypes = new HashSet<>();
        while (extension.remaining() > 0) {
            int type = extension.readUnsignedByte("server name type");
            int length = extension.readUnsignedShort("server name length");
            if (length == 0 || !nameTypes.add(type)) {
                throw new ProtocolException("invalid or duplicate server name type");
            }
            byte[] value = extension.readBytes(length, "server name");
            if (type == SERVER_NAME_HOST_NAME) {
                hostName = normalizeHostName(value);
            }
        }
        return hostName;
    }

    private static String normalizeHostName(byte[] value) throws ProtocolException {
        for (byte item : value) {
            if ((item & 0x80) != 0 || item == 0) {
                throw new ProtocolException("SNI host_name must contain ASCII characters");
            }
        }

        String result = new String(value, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        if (result.endsWith(".")) {
            throw new ProtocolException("SNI host_name must not end with a dot");
        }
        if (result.isEmpty() || result.length() > 253) {
            throw new ProtocolException("invalid SNI host_name length");
        }

        String[] labels = result.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                throw new ProtocolException("invalid SNI host_name label");
            }
            for (int i = 0; i < label.length(); i++) {
                char character = label.charAt(i);
                if (!(character >= 'a' && character <= 'z')
                    && !(character >= '0' && character <= '9') && character != '-') {
                    throw new ProtocolException("invalid SNI host_name character");
                }
            }
        }
        if (IpLiterals.isLiteral(result)) {
            throw new ProtocolException("SNI host_name must not be an IP literal");
        }
        return result;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count == -1) {
                throw new EOFException("truncated TLS ClientHello");
            }
            if (count == 0) {
                int value = input.read();
                if (value == -1) {
                    throw new EOFException("truncated TLS ClientHello");
                }
                result[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
        return result;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static int unsignedMedium(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 16)
            | ((bytes[offset + 1] & 0xff) << 8)
            | (bytes[offset + 2] & 0xff);
    }

    private static final class Cursor {

        private final byte[] bytes;
        private final int end;
        private int position;

        private Cursor(byte[] bytes, int start, int end) throws ProtocolException {
            if (start < 0 || end < start || end > bytes.length) {
                throw new ProtocolException("ClientHello length exceeds available handshake bytes");
            }
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        private int remaining() {
            return end - position;
        }

        private int readUnsignedByte(String field) throws ProtocolException {
            require(1, field);
            return bytes[position++] & 0xff;
        }

        private int readUnsignedShort(String field) throws ProtocolException {
            require(2, field);
            int result = unsignedShort(bytes, position);
            position += 2;
            return result;
        }

        private void skip(int length, String field) throws ProtocolException {
            require(length, field);
            position += length;
        }

        private byte[] readBytes(int length, String field) throws ProtocolException {
            require(length, field);
            byte[] result = new byte[length];
            System.arraycopy(bytes, position, result, 0, length);
            position += length;
            return result;
        }

        private Cursor readSlice(int length, String field) throws ProtocolException {
            require(length, field);
            Cursor result = new Cursor(bytes, position, position + length);
            position += length;
            return result;
        }

        private void require(int length, String field) throws ProtocolException {
            if (length < 0 || length > remaining()) {
                throw new ProtocolException("truncated " + field);
            }
        }
    }
}
