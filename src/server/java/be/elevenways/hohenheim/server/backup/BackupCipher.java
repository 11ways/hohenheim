package be.elevenways.hohenheim.server.backup;

import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * Whole-file AES-256-GCM under the field-encryption keyring, the one cipher every artifact stored on a backup target
 * goes through: four magic bytes naming the format, the key id (2-byte length + UTF-8), a 12-byte IV, then the
 * ciphertext. Magic and key id ride as GCM additional data, so a header swap is an authentication failure.
 *
 * AIDEV-NOTE: BOTH directions stream through BouncyCastle's GCM ({@link #gcm}), never the JDK Cipher. The JDK's
 * AES/GCM decrypt holds back ALL ciphertext until doFinal, so an artifact larger than the heap could be written and
 * never read back; its encrypt counts its input in an int and throws "SunJCE provider only supports input size up to
 * 2147483647 bytes" past 2 GiB (GaloisCounterMode.GCMEncrypt.checkDataLength), which failed every nightly dump of a
 * database over 2 GiB from 2026-11-08 on. BouncyCastle releases output as it goes and checks the tag at the end; the
 * plaintext lands in a file {@link #decrypt} deletes when the tag fails, so nothing unauthenticated ever reaches a
 * restore. The format is standard AES-256-GCM with a 128-bit tag, byte-identical to what the JDK cipher wrote, so
 * every artifact written before still opens. One key and IV cover at most {@link #MAX_PLAINTEXT_BYTES}: a larger
 * plaintext is refused before a byte is written, never cut into an artifact that cannot be read back.
 *
 * AIDEV-NOTE: {@link #encryptDeflated} compresses the plaintext (zlib) in the same pass, BEFORE the cipher: ciphertext
 * does not compress, so a compression after encryption saves nothing. The magic is the caller's statement of which
 * of the two an artifact is ({@link #readMagic}); {@link #decryptInflated} reads it back the same way.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class BackupCipher {

    /**
     * The most plaintext one AES-GCM key and IV may cover (NIST SP 800-38D: 2^39 - 256 bits), the 2^32 - 2 counter
     * blocks BouncyCastle's GCM counts down before it refuses with "Attempt to process too many blocks".
     */
    public static final long MAX_PLAINTEXT_BYTES = ((1L << 32) - 2) * 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int CHUNK_BYTES = 64 * 1024;

    private BackupCipher() {
    }

    /**
     * Encrypt {@code plainFile} into {@code outFile} under the keyring's active key, stamped with {@code magic}.
     *
     * @throws IOException naming the size and {@link #MAX_PLAINTEXT_BYTES} when the plaintext is larger than one GCM
     *                     key and IV may cover; {@code outFile} is removed whenever the encryption does not finish
     */
    public static void encrypt(byte @NonNull [] magic, @NonNull Path plainFile, @NonNull Path outFile,
                               @NonNull EncryptionKeyring keyring) throws IOException {
        long size = Files.size(plainFile);
        if (size > MAX_PLAINTEXT_BYTES) {
            throw tooLarge(plainFile, size, MAX_PLAINTEXT_BYTES);
        }
        try (InputStream in = Files.newInputStream(plainFile)) {
            encryptStream(magic, in, plainFile, outFile, keyring);
        }
    }

    /**
     * {@link #encrypt}, the plaintext zlib-compressed on its way into the cipher; {@link #MAX_PLAINTEXT_BYTES} holds on
     * the compressed bytes.
     *
     * @throws IOException as {@link #encrypt} does; {@code outFile} is removed whenever the encryption does not finish
     */
    public static void encryptDeflated(byte @NonNull [] magic, @NonNull Path plainFile, @NonNull Path outFile,
                                       @NonNull EncryptionKeyring keyring) throws IOException {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (InputStream in = new DeflaterInputStream(Files.newInputStream(plainFile), deflater, CHUNK_BYTES)) {
            encryptStream(magic, in, plainFile, outFile, keyring);
        } finally {
            deflater.end();
        }
    }

    /** The one encryption of both forms: the header, then everything {@code in} yields through the cipher. */
    private static void encryptStream(byte @NonNull [] magic, @NonNull InputStream in, @NonNull Path source,
                                      @NonNull Path outFile, @NonNull EncryptionKeyring keyring) throws IOException {
        String keyId = keyring.activeKeyId();
        byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        GCMModeCipher cipher = gcm(true, magic, keyring.activeKey().getEncoded(), iv, keyIdBytes);
        OutputStream out = Files.newOutputStream(outFile,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        // Removed only once this call has opened it: an outFile it never reached stays as it was.
        boolean written = false;
        try {
            try (out) {
                out.write(magic);
                out.write((keyIdBytes.length >> 8) & 0xFF);
                out.write(keyIdBytes.length & 0xFF);
                out.write(keyIdBytes);
                out.write(iv);
                // The file may have grown since it was measured: the limit holds on what is actually read.
                stream(cipher, in, out, source, MAX_PLAINTEXT_BYTES);
                try {
                    finish(cipher, out);
                } catch (InvalidCipherTextException impossible) {
                    throw new IOException("Backup encryption failed", impossible);
                }
            }
            written = true;
        } finally {
            if (!written) {
                Files.deleteIfExists(outFile);
            }
        }
    }

    /**
     * Reads an artifact's stamped magic without decrypting anything.
     *
     * @return the first {@code length} bytes of an artifact, the magic its writer stamped; fewer when it is shorter
     */
    public static byte @NonNull [] readMagic(@NonNull Path artifact, int length) throws IOException {
        try (InputStream in = Files.newInputStream(artifact)) {
            return in.readNBytes(length);
        }
    }

    /**
     * Decrypt {@code encrypted} into {@code plainOut}, verifying the GCM tag over the whole ciphertext.
     *
     * @throws IOException naming the corruption (wrong magic, truncated header, unknown key, failed tag, longer than
     *                     any artifact can be); the plaintext file is removed before it is thrown
     */
    public static void decrypt(byte @NonNull [] magic, @NonNull Path encrypted, @NonNull Path plainOut,
                               @NonNull EncryptionKeyring keyring) throws IOException {
        decrypt(magic, encrypted, plainOut, keyring, false);
    }

    /**
     * {@link #decrypt} of an artifact {@link #encryptDeflated} wrote, inflating the plaintext as it lands.
     *
     * @throws IOException as {@link #decrypt} does, and when the compressed stream does not end where the ciphertext
     *                     does; the plaintext file is removed before it is thrown
     */
    public static void decryptInflated(byte @NonNull [] magic, @NonNull Path encrypted, @NonNull Path plainOut,
                                       @NonNull EncryptionKeyring keyring) throws IOException {
        decrypt(magic, encrypted, plainOut, keyring, true);
    }

    private static void decrypt(byte @NonNull [] magic, @NonNull Path encrypted, @NonNull Path plainOut,
                                @NonNull EncryptionKeyring keyring, boolean inflate) throws IOException {
        boolean verified = false;
        Inflater inflater = inflate ? new Inflater() : null;
        try (InputStream in = Files.newInputStream(encrypted)) {
            byte[] found = in.readNBytes(magic.length);
            if (found.length != magic.length || !MessageDigest.isEqual(found, magic)) {
                throw new IOException("Backup artifact does not start with the "
                    + new String(magic, StandardCharsets.US_ASCII) + " magic; not this kind of hohenheim backup"
                    + " or truncated at byte zero");
            }
            byte[] lengthBytes = in.readNBytes(2);
            if (lengthBytes.length != 2) {
                throw new IOException("Backup artifact header is truncated");
            }
            int keyIdLength = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
            byte[] keyIdBytes = in.readNBytes(keyIdLength);
            byte[] iv = in.readNBytes(IV_BYTES);
            if (keyIdBytes.length != keyIdLength || iv.length != IV_BYTES) {
                throw new IOException("Backup artifact header is truncated");
            }
            String keyId = new String(keyIdBytes, StandardCharsets.UTF_8);
            if (!keyring.containsKey(keyId)) {
                throw new IOException("Backup artifact was encrypted under key '" + keyId
                    + "', which this controller's keyring does not contain; restore needs"
                    + " the keyring the backup was written under");
            }
            GCMModeCipher cipher = gcm(false, magic, keyring.keyById(keyId).getEncoded(), iv, keyIdBytes);
            OutputStream file = Files.newOutputStream(plainOut,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            InflaterOutputStream inflating = inflater == null ? null
                : new InflaterOutputStream(file, inflater, CHUNK_BYTES);
            try (OutputStream out = inflating == null ? file : inflating) {
                stream(cipher, in, out, encrypted, MAX_PLAINTEXT_BYTES + GCM_TAG_BITS / 8);
                try {
                    finish(cipher, out);
                } catch (InvalidCipherTextException tampered) {
                    throw new IOException("Backup artifact fails authentication: the ciphertext"
                        + " does not match its GCM tag. The backup is corrupt or tampered with"
                        + " and is refused whole -- nothing was restored from it", tampered);
                }
                if (inflating != null && inflater != null) {
                    inflating.finish();
                    if (!inflater.finished()) {
                        throw new IOException("Backup artifact's compressed content ends before its stream does;"
                            + " it is refused whole");
                    }
                }
            }
            verified = true;
        } finally {
            if (inflater != null) {
                inflater.end();
            }
            if (!verified) {
                Files.deleteIfExists(plainOut);
            }
        }
    }

    /** The one GCM construction of both directions: magic, then key id, as the additional data. */
    private static @NonNull GCMModeCipher gcm(boolean forEncryption, byte @NonNull [] magic, byte @NonNull [] key,
                                              byte @NonNull [] iv, byte @NonNull [] keyIdBytes) {
        GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
        cipher.init(forEncryption, new AEADParameters(new KeyParameter(key), GCM_TAG_BITS, iv));
        cipher.processAADBytes(magic, 0, magic.length);
        cipher.processAADBytes(keyIdBytes, 0, keyIdBytes.length);
        return cipher;
    }

    /**
     * Feeds all of {@code in} through {@code cipher} into {@code out}, a chunk at a time.
     *
     * @throws IOException naming {@code source} once more than {@code maxInputBytes} were read, before the chunk
     *                     that crosses the limit reaches the cipher
     */
    private static void stream(@NonNull GCMModeCipher cipher, @NonNull InputStream in, @NonNull OutputStream out,
                               @NonNull Path source, long maxInputBytes) throws IOException {
        byte[] buffer = new byte[CHUNK_BYTES];
        byte[] chunk = new byte[cipher.getUpdateOutputSize(buffer.length)];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > maxInputBytes) {
                throw tooLarge(source, total, maxInputBytes);
            }
            int needed = cipher.getUpdateOutputSize(read);
            if (chunk.length < needed) {
                chunk = new byte[needed];
            }
            int produced = cipher.processBytes(buffer, 0, read, chunk, 0);
            out.write(chunk, 0, produced);
        }
    }

    /** Writes what the cipher still holds: the tag when encrypting, the last plaintext once the tag held. */
    private static void finish(@NonNull GCMModeCipher cipher, @NonNull OutputStream out)
            throws IOException, InvalidCipherTextException {
        byte[] tail = new byte[cipher.getOutputSize(0)];
        int produced = cipher.doFinal(tail, 0);
        out.write(tail, 0, produced);
    }

    private static @NonNull IOException tooLarge(@NonNull Path source, long bytes, long limit) {
        return new IOException("Backup input " + source + " is at least " + bytes + " bytes, over the " + limit
            + "-byte limit of one AES-GCM key and IV (" + MAX_PLAINTEXT_BYTES
            + " bytes of plaintext, plus the tag in an artifact); refused, and nothing of it is kept");
    }
}
