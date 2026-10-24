package be.elevenways.hohenheim.server.backup;

import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.checkerframework.checker.nullness.qual.NonNull;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Whole-file AES-256-GCM under the field-encryption keyring, the one cipher every artifact stored on a backup target
 * goes through: four magic bytes naming the format, the key id (2-byte length + UTF-8), a 12-byte IV, then the
 * ciphertext. Magic and key id ride as GCM additional data, so a header swap is an authentication failure.
 *
 * AIDEV-NOTE: decryption STREAMS through BouncyCastle's GCM, never the JDK Cipher: the JDK's AES/GCM decrypt holds
 * back ALL ciphertext until doFinal, so an artifact larger than the heap could be written and never read back.
 * BouncyCastle releases plaintext as it goes and checks the tag at the end; the plaintext lands in a file
 * {@link #decrypt} deletes when the tag fails, so nothing unauthenticated ever reaches a restore. Encryption keeps the
 * JDK cipher, which streams on that side. The format is standard AES-256-GCM with a 128-bit tag.
 *
 * @author Jelle De Loecker
 * @since  0.10.0
 */
public final class BackupCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private BackupCipher() {
    }

    /** Encrypt {@code plainFile} into {@code outFile} under the keyring's active key, stamped with {@code magic}. */
    public static void encrypt(byte @NonNull [] magic, @NonNull Path plainFile, @NonNull Path outFile,
                               @NonNull EncryptionKeyring keyring) throws IOException {
        String keyId = keyring.activeKeyId();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try (OutputStream out = Files.newOutputStream(outFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
            out.write(magic);
            out.write((keyIdBytes.length >> 8) & 0xFF);
            out.write(keyIdBytes.length & 0xFF);
            out.write(keyIdBytes);
            out.write(iv);

            Cipher cipher = gcm(magic, keyring.activeKey(), iv, keyIdBytes);
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(plainFile)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    byte[] chunk = cipher.update(buffer, 0, read);
                    if (chunk != null && chunk.length > 0) {
                        out.write(chunk);
                    }
                }
            }
            try {
                out.write(cipher.doFinal());
            } catch (GeneralSecurityException impossible) {
                throw new IOException("Backup encryption failed", impossible);
            }
        }
    }

    /**
     * Decrypt {@code encrypted} into {@code plainOut}, verifying the GCM tag over the whole ciphertext.
     *
     * @throws IOException naming the corruption (wrong magic, truncated header, unknown key, failed tag); the
     *                     plaintext file is removed before it is thrown
     */
    public static void decrypt(byte @NonNull [] magic, @NonNull Path encrypted, @NonNull Path plainOut,
                               @NonNull EncryptionKeyring keyring) throws IOException {
        boolean verified = false;
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
            GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
            cipher.init(false, new AEADParameters(
                new KeyParameter(keyring.keyById(keyId).getEncoded()), GCM_TAG_BITS, iv));
            cipher.processAADBytes(magic, 0, magic.length);
            cipher.processAADBytes(keyIdBytes, 0, keyIdBytes.length);
            try (OutputStream out = Files.newOutputStream(plainOut,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                byte[] chunk = new byte[cipher.getUpdateOutputSize(buffer.length)];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    int needed = cipher.getUpdateOutputSize(read);
                    if (chunk.length < needed) {
                        chunk = new byte[needed];
                    }
                    int produced = cipher.processBytes(buffer, 0, read, chunk, 0);
                    out.write(chunk, 0, produced);
                }
                byte[] tail = new byte[cipher.getOutputSize(0)];
                int produced;
                try {
                    produced = cipher.doFinal(tail, 0);
                } catch (InvalidCipherTextException tampered) {
                    throw new IOException("Backup artifact fails authentication: the ciphertext"
                        + " does not match its GCM tag. The backup is corrupt or tampered with"
                        + " and is refused whole -- nothing was restored from it", tampered);
                }
                out.write(tail, 0, produced);
            }
            verified = true;
        } finally {
            if (!verified) {
                Files.deleteIfExists(plainOut);
            }
        }
    }

    private static Cipher gcm(byte[] magic, SecretKeySpec key, byte[] iv, byte[] aadKeyId) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(magic);
            cipher.updateAAD(aadKeyId);
            return cipher;
        } catch (GeneralSecurityException error) {
            throw new IOException("AES-GCM unavailable or key invalid", error);
        }
    }
}
