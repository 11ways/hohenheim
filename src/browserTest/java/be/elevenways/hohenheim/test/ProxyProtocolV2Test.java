package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.ProxyProtocolV2;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers strict PROXY protocol v2 encoding, decoding, and optional ingress detection. */
class ProxyProtocolV2Test {

    @Test
    void ipv4RoundTripPreservesEndpointsAndExactLength() throws Exception {
        InetSocketAddress source = address("192.0.2.17", 54321);
        InetSocketAddress destination = address("198.51.100.8", 443);

        byte[] encoded = ProxyProtocolV2.encode(source, destination);
        ProxyProtocolV2.Header parsed = ProxyProtocolV2.parse(encoded);

        assertThat(encoded).hasSize(ProxyProtocolV2.IPV4_HEADER_LENGTH);
        assertThat(parsed.command()).isEqualTo(ProxyProtocolV2.Command.PROXY);
        assertThat(parsed.sourceAddress()).isEqualTo(source);
        assertThat(parsed.destinationAddress()).isEqualTo(destination);
    }

    @Test
    void ipv6RoundTripPreservesEndpointsAndExactLength() throws Exception {
        InetSocketAddress source = address("2001:db8::1234", 65535);
        InetSocketAddress destination = address("2001:db8:1::9", 8443);

        byte[] encoded = ProxyProtocolV2.encode(source, destination);
        ProxyProtocolV2.Header parsed = ProxyProtocolV2.parse(encoded);

        assertThat(encoded).hasSize(ProxyProtocolV2.IPV6_HEADER_LENGTH);
        assertThat(parsed.command()).isEqualTo(ProxyProtocolV2.Command.PROXY);
        assertThat(parsed.sourceAddress()).isEqualTo(source);
        assertThat(parsed.destinationAddress()).isEqualTo(destination);
    }

    @Test
    void localHeaderHasNoAddresses() throws Exception {
        byte[] encoded = ProxyProtocolV2.encodeLocal();
        ProxyProtocolV2.Header parsed = ProxyProtocolV2.parse(encoded);

        assertThat(encoded).hasSize(ProxyProtocolV2.FIXED_HEADER_LENGTH);
        assertThat(parsed.command()).isEqualTo(ProxyProtocolV2.Command.LOCAL);
        assertThat(parsed.sourceAddress()).isNull();
        assertThat(parsed.destinationAddress()).isNull();
    }

    @Test
    void optionalReadLeavesNonProxyBytesUntouched() throws Exception {
        byte[] tlsPrefix = "not-a-proxy-header".getBytes(StandardCharsets.US_ASCII);
        BufferedInputStream input = new BufferedInputStream(new ByteArrayInputStream(tlsPrefix));

        assertThat(ProxyProtocolV2.readOptional(input)).isEmpty();
        assertThat(input.readAllBytes()).containsExactly(tlsPrefix);

        byte[] proxy = ProxyProtocolV2.encode(address("192.0.2.1", 1234), address("192.0.2.2", 443));
        byte[] payload = "payload".getBytes(StandardCharsets.US_ASCII);
        byte[] combined = concat(proxy, payload);
        BufferedInputStream proxiedInput = new BufferedInputStream(new ByteArrayInputStream(combined));

        assertThat(ProxyProtocolV2.readOptional(proxiedInput)).isPresent();
        assertThat(proxiedInput.readAllBytes()).containsExactly(payload);
    }

    @Test
    void malformedSignatureVersionFamilyAndLengthsAreRejected() throws Exception {
        byte[] valid = ProxyProtocolV2.encode(address("192.0.2.1", 1), address("192.0.2.2", 2));

        byte[] badSignature = valid.clone();
        badSignature[0] ^= 1;
        assertProtocolFailure(badSignature);

        byte[] badVersion = valid.clone();
        badVersion[12] = 0x11;
        assertProtocolFailure(badVersion);

        byte[] badFamily = valid.clone();
        badFamily[13] = 0x12;
        assertProtocolFailure(badFamily);

        byte[] badLength = valid.clone();
        badLength[15] = 11;
        assertProtocolFailure(badLength);

        byte[] localWithPayload = ProxyProtocolV2.encodeLocal();
        localWithPayload[15] = 1;
        assertThatThrownBy(() -> ProxyProtocolV2.parse(localWithPayload))
            .isInstanceOf(EOFException.class);

        assertThatThrownBy(() -> ProxyProtocolV2.parse(concat(valid, new byte[]{1})))
            .isInstanceOf(ProtocolException.class);
    }

    @Test
    void validTlvsAndLocalPayloadsAreAcceptedButMalformedTlvsAreRejected() throws Exception {
        byte[] valid = ProxyProtocolV2.encode(address("192.0.2.1", 1), address("192.0.2.2", 2));
        byte[] withTlv = Arrays.copyOf(valid, valid.length + 5);
        withTlv[15] = 17;
        withTlv[28] = 0x01;
        withTlv[29] = 0;
        withTlv[30] = 2;
        withTlv[31] = 0x12;
        withTlv[32] = 0x34;
        assertThat(ProxyProtocolV2.parse(withTlv).sourceAddress()).isEqualTo(address("192.0.2.1", 1));

        byte[] malformedTlv = withTlv.clone();
        malformedTlv[30] = 3;
        assertProtocolFailure(malformedTlv);

        byte[] local = Arrays.copyOf(ProxyProtocolV2.encodeLocal(), 19);
        local[15] = 3;
        local[16] = 1;
        local[17] = 2;
        local[18] = 3;
        assertThat(ProxyProtocolV2.parse(local).command()).isEqualTo(ProxyProtocolV2.Command.LOCAL);
    }

    @Test
    void truncatedHeadersAreRejectedWithoutAllocatingDeclaredPayload() throws Exception {
        byte[] valid = ProxyProtocolV2.encode(address("2001:db8::1", 1), address("2001:db8::2", 2));

        assertThatThrownBy(() -> ProxyProtocolV2.parse(new byte[8]))
            .isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> ProxyProtocolV2.parse(Arrays.copyOf(valid, valid.length - 1)))
            .isInstanceOf(EOFException.class);

        BufferedInputStream partialSignature = new BufferedInputStream(
            new ByteArrayInputStream(Arrays.copyOf(valid, 8)));
        assertThatThrownBy(() -> ProxyProtocolV2.readOptional(partialSignature))
            .isInstanceOf(EOFException.class);
    }

    @Test
    void encodingRejectsMixedAndUnresolvedAddressFamilies() throws Exception {
        assertThatThrownBy(() -> ProxyProtocolV2.encode(
            address("192.0.2.1", 1), address("2001:db8::1", 2)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyProtocolV2.encode(
            InetSocketAddress.createUnresolved("example.test", 1), address("192.0.2.1", 2)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static InetSocketAddress address(String value, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(value), port);
    }

    private static void assertProtocolFailure(byte[] header) {
        assertThatThrownBy(() -> ProxyProtocolV2.parse(header)).isInstanceOf(ProtocolException.class);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
