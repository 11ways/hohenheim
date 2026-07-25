package be.elevenways.hohenheim.server.proxy;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Encodes and strictly parses canonical PROXY protocol v2 TCP headers. */
public final class ProxyProtocolV2 {

    public static final int FIXED_HEADER_LENGTH = 16;
    public static final int IPV4_HEADER_LENGTH = 28;
    public static final int IPV6_HEADER_LENGTH = 52;

    private static final byte[] SIGNATURE = {
        0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a
    };
    private static final int VERSION = 0x20;
    private static final int COMMAND_LOCAL = 0x00;
    private static final int COMMAND_PROXY = 0x01;
    private static final int FAMILY_UNSPEC = 0x00;
    private static final int FAMILY_INET_STREAM = 0x11;
    private static final int FAMILY_INET6_STREAM = 0x21;
    private static final int IPV4_ADDRESS_LENGTH = 12;
    private static final int IPV6_ADDRESS_LENGTH = 36;
    private static final int MAX_PAYLOAD_LENGTH = 0xffff;

    private ProxyProtocolV2() {
    }

    public enum Command {
        LOCAL,
        PROXY
    }

    /** Represents either a canonical LOCAL header or the original proxied TCP endpoints. */
    public record Header(Command command, @Nullable SocketAddress sourceAddress,
                         @Nullable SocketAddress destinationAddress) {

        public Header {
            Objects.requireNonNull(command, "command");
            if (command == Command.LOCAL) {
                if (sourceAddress != null || destinationAddress != null) {
                    throw new IllegalArgumentException("LOCAL headers cannot contain addresses");
                }
            } else if (!(sourceAddress instanceof InetSocketAddress)
                || !(destinationAddress instanceof InetSocketAddress)) {
                throw new IllegalArgumentException("PROXY headers require internet socket addresses");
            }
        }
    }

    /** Encodes a canonical TCP-over-IPv4 or TCP-over-IPv6 PROXY header. */
    public static byte[] encode(SocketAddress sourceAddress, SocketAddress destinationAddress) {
        InetSocketAddress source = requireInetAddress(sourceAddress, "source");
        InetSocketAddress destination = requireInetAddress(destinationAddress, "destination");
        byte[] sourceBytes = source.getAddress().getAddress();
        byte[] destinationBytes = destination.getAddress().getAddress();

        if (sourceBytes.length != destinationBytes.length) {
            throw new IllegalArgumentException("source and destination address families differ");
        }

        int family;
        int addressLength;
        if (source.getAddress() instanceof Inet4Address && destination.getAddress() instanceof Inet4Address) {
            family = FAMILY_INET_STREAM;
            addressLength = IPV4_ADDRESS_LENGTH;
        } else if (source.getAddress() instanceof Inet6Address
            && destination.getAddress() instanceof Inet6Address) {
            family = FAMILY_INET6_STREAM;
            addressLength = IPV6_ADDRESS_LENGTH;
        } else {
            throw new IllegalArgumentException("only IPv4 and IPv6 addresses are supported");
        }

        ByteBuffer result = ByteBuffer.allocate(FIXED_HEADER_LENGTH + addressLength);
        result.put(SIGNATURE);
        result.put((byte) (VERSION | COMMAND_PROXY));
        result.put((byte) family);
        result.putShort((short) addressLength);
        result.put(sourceBytes);
        result.put(destinationBytes);
        result.putShort((short) source.getPort());
        result.putShort((short) destination.getPort());
        return result.array();
    }

    /** Encodes the canonical address-free LOCAL header. */
    public static byte[] encodeLocal() {
        ByteBuffer result = ByteBuffer.allocate(FIXED_HEADER_LENGTH);
        result.put(SIGNATURE);
        result.put((byte) (VERSION | COMMAND_LOCAL));
        result.put((byte) FAMILY_UNSPEC);
        result.putShort((short) 0);
        return result.array();
    }

    /** Parses exactly one complete header and rejects trailing bytes. */
    public static Header parse(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        Header result = read(input);
        if (input.available() != 0) {
            throw new ProtocolException("PROXY v2 header contains trailing bytes");
        }
        return result;
    }

    /** Reads one required PROXY v2 header without reading backend protocol bytes. */
    public static Header read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] fixedHeader = readExactly(input, FIXED_HEADER_LENGTH);
        verifySignature(fixedHeader);
        return readAfterFixedHeader(input, fixedHeader);
    }

    /** Reads an optional header while resetting a buffered stream when the signature is absent. */
    public static Optional<Header> readOptional(BufferedInputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        input.mark(SIGNATURE.length);
        byte[] signature = new byte[SIGNATURE.length];
        int read = readUpTo(input, signature);

        if (read < SIGNATURE.length) {
            boolean signaturePrefix = read > 0;
            for (int i = 0; i < read && signaturePrefix; i++) {
                signaturePrefix = signature[i] == SIGNATURE[i];
            }
            input.reset();
            if (signaturePrefix) {
                throw new EOFException("truncated PROXY v2 signature");
            }
            return Optional.empty();
        }
        if (!Arrays.equals(signature, SIGNATURE)) {
            input.reset();
            return Optional.empty();
        }

        byte[] fixedHeader = new byte[FIXED_HEADER_LENGTH];
        System.arraycopy(signature, 0, fixedHeader, 0, signature.length);
        byte[] remainder = readExactly(input, FIXED_HEADER_LENGTH - SIGNATURE.length);
        System.arraycopy(remainder, 0, fixedHeader, signature.length, remainder.length);
        return Optional.of(readAfterFixedHeader(input, fixedHeader));
    }

    private static Header readAfterFixedHeader(InputStream input, byte[] fixedHeader) throws IOException {
        int versionAndCommand = fixedHeader[12] & 0xff;
        if ((versionAndCommand & 0xf0) != VERSION) {
            throw new ProtocolException("unsupported PROXY protocol version");
        }

        int command = versionAndCommand & 0x0f;
        int family = fixedHeader[13] & 0xff;
        int payloadLength = unsignedShort(fixedHeader, 14);

        if (command == COMMAND_LOCAL) {
            readExactly(input, payloadLength);
            return new Header(Command.LOCAL, null, null);
        }
        if (command != COMMAND_PROXY) {
            throw new ProtocolException("unsupported PROXY v2 command");
        }

        int expectedLength;
        int addressSize;
        if (family == FAMILY_INET_STREAM) {
            expectedLength = IPV4_ADDRESS_LENGTH;
            addressSize = 4;
        } else if (family == FAMILY_INET6_STREAM) {
            expectedLength = IPV6_ADDRESS_LENGTH;
            addressSize = 16;
        } else {
            throw new ProtocolException("unsupported PROXY v2 family or transport");
        }
        if (payloadLength < expectedLength || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new ProtocolException("invalid PROXY v2 address length");
        }

        byte[] addresses = readExactly(input, payloadLength);
        byte[] sourceBytes = Arrays.copyOfRange(addresses, 0, addressSize);
        byte[] destinationBytes = Arrays.copyOfRange(addresses, addressSize, addressSize * 2);
        int sourcePort = unsignedShort(addresses, addressSize * 2);
        int destinationPort = unsignedShort(addresses, addressSize * 2 + 2);

        InetAddress sourceIp = InetAddress.getByAddress(sourceBytes);
        InetAddress destinationIp = InetAddress.getByAddress(destinationBytes);
        validateTlvs(addresses, expectedLength);
        return new Header(
            Command.PROXY,
            new InetSocketAddress(sourceIp, sourcePort),
            new InetSocketAddress(destinationIp, destinationPort)
        );
    }

    /** Validates the standard type/length/value framing while leaving TLV interpretation to consumers. */
    private static void validateTlvs(byte[] payload, int offset) throws ProtocolException {
        while (offset < payload.length) {
            if (payload.length - offset < 3) {
                throw new ProtocolException("truncated PROXY v2 TLV header");
            }
            int length = unsignedShort(payload, offset + 1);
            offset += 3;
            if (length > payload.length - offset) {
                throw new ProtocolException("truncated PROXY v2 TLV value");
            }
            offset += length;
        }
    }

    private static InetSocketAddress requireInetAddress(SocketAddress address, String role) {
        if (!(address instanceof InetSocketAddress internetAddress) || internetAddress.isUnresolved()) {
            throw new IllegalArgumentException(role + " must be a resolved internet socket address");
        }
        return internetAddress;
    }

    private static void verifySignature(byte[] fixedHeader) throws ProtocolException {
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (fixedHeader[i] != SIGNATURE[i]) {
                throw new ProtocolException("invalid PROXY v2 signature");
            }
        }
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int read = readUpTo(input, result);
        if (read != length) {
            throw new EOFException("truncated PROXY v2 header");
        }
        return result;
    }

    private static int readUpTo(InputStream input, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = input.read(target, offset, target.length - offset);
            if (count == -1) {
                break;
            }
            if (count == 0) {
                int value = input.read();
                if (value == -1) {
                    break;
                }
                target[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
        return offset;
    }
}
