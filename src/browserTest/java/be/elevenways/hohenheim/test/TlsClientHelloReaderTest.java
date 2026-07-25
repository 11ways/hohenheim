package be.elevenways.hohenheim.test;

import be.elevenways.hohenheim.server.proxy.TlsClientHelloReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers bounded fragmented and multi-record TLS ClientHello inspection. */
class TlsClientHelloReaderTest {

    @Test
    void extractsNormalizedSniAndPreservesExactWireBytes() throws Exception {
        byte[] wire = tlsRecords(clientHello("WWW.Example.TEST"));

        TlsClientHelloReader.Result result = TlsClientHelloReader.read(new ByteArrayInputStream(wire));

        assertThat(result.serverName()).contains("www.example.test");
        assertThat(result.replayBytes()).containsExactly(wire);

        byte[] mutableCopy = result.replayBytes();
        mutableCopy[0] = 0;
        assertThat(result.replayBytes()).containsExactly(wire);
    }

    @Test
    void clientHelloWithoutSniIsAccepted() throws Exception {
        byte[] wire = tlsRecords(clientHello(null));

        TlsClientHelloReader.Result result = TlsClientHelloReader.read(new ByteArrayInputStream(wire));

        assertThat(result.serverName()).isEmpty();
        assertThat(result.replayBytes()).containsExactly(wire);
    }

    @Test
    void arbitraryInputFragmentationDoesNotChangeParsing() throws Exception {
        byte[] wire = tlsRecords(clientHello("fragmented.example"));

        TlsClientHelloReader.Result result = TlsClientHelloReader.read(new FragmentedInputStream(wire, 1));

        assertThat(result.serverName()).contains("fragmented.example");
        assertThat(result.replayBytes()).containsExactly(wire);
    }

    @Test
    void clientHelloMaySpanSeveralTlsRecords() throws Exception {
        byte[] hello = clientHello("records.example");
        byte[] wire = tlsRecords(hello, 2, 17, hello.length - 19);

        TlsClientHelloReader.Result result = TlsClientHelloReader.read(new FragmentedInputStream(wire, 3));

        assertThat(result.serverName()).contains("records.example");
        assertThat(result.replayBytes()).containsExactly(wire);
    }

    @Test
    void malformedRecordAndHandshakeTypesAreRejected() throws Exception {
        byte[] wrongRecordType = tlsRecords(clientHello("example.test"));
        wrongRecordType[0] = 23;
        assertThatThrownBy(() -> TlsClientHelloReader.read(new ByteArrayInputStream(wrongRecordType)))
            .isInstanceOf(ProtocolException.class);

        byte[] wrongHandshakeType = tlsRecords(clientHello("example.test"));
        wrongHandshakeType[5] = 2;
        assertThatThrownBy(() -> TlsClientHelloReader.read(new ByteArrayInputStream(wrongHandshakeType)))
            .isInstanceOf(ProtocolException.class);

        byte[] wrongRecordVersion = tlsRecords(clientHello("example.test"));
        wrongRecordVersion[6] = 2;
        assertThatThrownBy(() -> TlsClientHelloReader.read(new ByteArrayInputStream(wrongRecordVersion)))
            .isInstanceOf(ProtocolException.class);
    }

    @Test
    void malformedClientHelloLengthsAndNamesAreRejected() throws Exception {
        byte[] hello = clientHello("example.test");
        int extensionsLengthOffset = 4 + 2 + 32 + 1 + 2 + 2 + 1 + 1;
        hello[extensionsLengthOffset + 1]++;
        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(tlsRecords(hello))))
            .isInstanceOf(ProtocolException.class);

        byte[] nonAscii = clientHello("example.test");
        nonAscii[nonAscii.length - 1] = (byte) 0xff;
        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(tlsRecords(nonAscii))))
            .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(tlsRecords(clientHello("example.test.")))))
            .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(tlsRecords(clientHello("192.0.2.1")))))
            .isInstanceOf(ProtocolException.class);

        byte[] invalidRecordVersion = tlsRecords(clientHello("example.test"));
        invalidRecordVersion[2] = 4;
        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(invalidRecordVersion)))
            .isInstanceOf(ProtocolException.class);
    }

    @Test
    void oversizedAndTruncatedClientHellosAreRejected() throws Exception {
        byte[] declaredOversized = {
            22, 3, 3, 0, 4,
            1, 0, 1, 0
        };
        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(declaredOversized), 64))
            .isInstanceOf(ProtocolException.class);

        byte[] valid = tlsRecords(clientHello("example.test"));
        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(valid), valid.length - 1))
            .isInstanceOf(ProtocolException.class);

        assertThatThrownBy(() -> TlsClientHelloReader.read(
            new ByteArrayInputStream(Arrays.copyOf(valid, valid.length - 1))))
            .isInstanceOf(EOFException.class);
    }

    private static byte[] clientHello(String serverName) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeShort(body, 0x0303);
        body.write(new byte[32]);
        body.write(0);
        writeShort(body, 2);
        writeShort(body, 0x1301);
        body.write(1);
        body.write(0);

        if (serverName != null) {
            byte[] host = serverName.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream serverNameExtension = new ByteArrayOutputStream();
            writeShort(serverNameExtension, host.length + 3);
            serverNameExtension.write(0);
            writeShort(serverNameExtension, host.length);
            serverNameExtension.write(host);

            ByteArrayOutputStream extensions = new ByteArrayOutputStream();
            writeShort(extensions, 0);
            writeShort(extensions, serverNameExtension.size());
            serverNameExtension.writeTo(extensions);
            writeShort(body, extensions.size());
            extensions.writeTo(body);
        }

        byte[] bodyBytes = body.toByteArray();
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        handshake.write(1);
        writeMedium(handshake, bodyBytes.length);
        handshake.write(bodyBytes);
        return handshake.toByteArray();
    }

    private static byte[] tlsRecords(byte[] handshake, int... fragmentLengths) throws IOException {
        if (fragmentLengths.length == 0) {
            fragmentLengths = new int[]{handshake.length};
        }

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int offset = 0;
        for (int length : fragmentLengths) {
            if (length <= 0 || offset + length > handshake.length) {
                throw new IllegalArgumentException("invalid test record fragment");
            }
            wire.write(22);
            writeShort(wire, 0x0303);
            writeShort(wire, length);
            wire.write(handshake, offset, length);
            offset += length;
        }
        if (offset != handshake.length) {
            throw new IllegalArgumentException("test fragments do not cover the handshake");
        }
        return wire.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeMedium(ByteArrayOutputStream output, int value) {
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static final class FragmentedInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private final int fragmentSize;

        private FragmentedInputStream(byte[] bytes, int fragmentSize) {
            this.delegate = new ByteArrayInputStream(bytes);
            this.fragmentSize = fragmentSize;
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, Math.min(length, fragmentSize));
        }
    }
}
