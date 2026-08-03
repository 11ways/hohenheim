package be.elevenways.hohenheim.server.backup;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The instance-backup archive format: a zip ({@code manifest.dry} + one
 * {@code volumes/<name>.tar} per payload) encrypted WHOLE with a key from the field-
 * encryption keyring -- settings carry secret variables and volume data is tenant
 * data, so nothing of either leaves the controller in the clear. The GCM tag
 * authenticates the whole ciphertext; per-entry sha256s inside the manifest pin each
 * plaintext payload on top of it.
 *
 * File layout: magic {@code HIB1}, key id (2-byte length + UTF-8), 12-byte IV, then
 * AES-256-GCM ciphertext of the zip. Magic and key id ride as GCM additional data,
 * so a header swap is an authentication failure, not a different decryption.
 *
 * AIDEV-NOTE: decryption FULLY verifies the GCM tag (doFinal) before anything reads
 * the plaintext zip -- a partially-decrypted stream must never feed a restore, which
 * is why this class decrypts to a staging file instead of handing out a
 * CipherInputStream.
 */
public final class BackupArchive {

    /** The four magic bytes every archive starts with. */
    public static final byte[] MAGIC = {'H', 'I', 'B', '1'};

    public static final String MANIFEST_ENTRY = "manifest.dry";
    public static final String VOLUME_PREFIX = "volumes/";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private BackupArchive() {}

    /** A decrypt-and-verify result: the manifest plus the verified plaintext zip. */
    public record Opened(@NonNull BackupManifest manifest, @NonNull Path zip) {}

    /**
     * Build and encrypt one archive. {@code volumeFiles} maps each manifest volume
     * entry's {@code file} name to its local tar.
     *
     * @return the encrypted archive's size in bytes
     */
    public static long create(@NonNull BackupManifest manifest,
                              @NonNull Map<String, Path> volumeFiles,
                              @NonNull Path outFile,
                              @NonNull EncryptionKeyring keyring) throws IOException {
        Path plainZip = Files.createTempFile(outFile.getParent(), ".backup-plain", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plainZip,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
                zip.write(Zenit.DRY.stringify(manifest.toMap()).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (BackupManifest.VolumeEntry volume : manifest.volumes()) {
                    Path tar = volumeFiles.get(volume.file());
                    if (tar == null) {
                        throw new IOException("No local payload for manifest volume file '"
                            + volume.file() + "'");
                    }
                    zip.putNextEntry(new ZipEntry(VOLUME_PREFIX + volume.file()));
                    Files.copy(tar, zip);
                    zip.closeEntry();
                }
            }
            encrypt(plainZip, outFile, keyring);
            return Files.size(outFile);
        } finally {
            Files.deleteIfExists(plainZip);
        }
    }

    /**
     * Decrypt an archive into {@code workDir} and verify it END TO END: GCM tag over
     * the whole ciphertext, manifest parseable, every volume entry present with the
     * recorded sha256 and size. Nothing else may touch live state before this returns.
     *
     * @return the manifest plus the verified plaintext zip (caller deletes it when done)
     * @throws IOException naming the corruption; the plaintext staging file is removed
     */
    public static @NonNull Opened openVerified(@NonNull Path archive, @NonNull Path workDir,
                                               @NonNull EncryptionKeyring keyring) throws IOException {
        Files.createDirectories(workDir);
        Path plainZip = Files.createTempFile(workDir, ".backup-open", ".zip");
        boolean keep = false;
        try {
            decrypt(archive, plainZip, keyring);
            BackupManifest manifest;
            try (ZipFile zip = new ZipFile(plainZip.toFile())) {
                ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY);
                if (manifestEntry == null) {
                    throw new IOException("Backup archive has no " + MANIFEST_ENTRY + " entry");
                }
                Object parsed;
                try (InputStream in = zip.getInputStream(manifestEntry)) {
                    parsed = Zenit.DRY.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (RuntimeException unparseable) {
                    throw new IOException("Backup archive manifest is unparseable", unparseable);
                }
                manifest = BackupManifest.fromMap(parsed);
                for (BackupManifest.VolumeEntry volume : manifest.volumes()) {
                    ZipEntry entry = zip.getEntry(VOLUME_PREFIX + volume.file());
                    if (entry == null) {
                        throw new IOException("Backup archive is missing payload "
                            + VOLUME_PREFIX + volume.file());
                    }
                    Hashed hashed = hashEntry(zip, entry);
                    if (hashed.size() != volume.size() || !hashed.sha256().equals(volume.sha256())) {
                        throw new IOException("Backup payload " + volume.file() + " does not match"
                            + " its recorded checksum (expected sha256 " + volume.sha256() + ", "
                            + volume.size() + " bytes; found " + hashed.sha256() + ", "
                            + hashed.size() + " bytes). The backup is corrupt and is refused"
                            + " whole -- nothing was restored from it");
                    }
                }
            }
            keep = true;
            return new Opened(manifest, plainZip);
        } finally {
            if (!keep) {
                Files.deleteIfExists(plainZip);
            }
        }
    }

    /**
     * Extract the verified archive's volume payloads into {@code directory}, one tar
     * per manifest volume entry, keyed by LOGICAL volume name.
     */
    public static @NonNull Map<String, Path> extractVolumes(@NonNull Opened opened,
                                                            @NonNull Path directory) throws IOException {
        Files.createDirectories(directory);
        Map<String, Path> tars = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(opened.zip().toFile())) {
            for (BackupManifest.VolumeEntry volume : opened.manifest().volumes()) {
                ZipEntry entry = zip.getEntry(VOLUME_PREFIX + volume.file());
                if (entry == null) {
                    throw new IOException("Backup archive is missing payload "
                        + VOLUME_PREFIX + volume.file());
                }
                Path tar = directory.resolve(volume.name() + ".tar");
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(tar,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(out);
                }
                tars.put(volume.name(), tar);
            }
        }
        return tars;
    }

    /** Stream a local file through SHA-256. */
    public static @NonNull String sha256Of(@NonNull Path file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return SecureTokens.hex(digest.digest());
    }

    // -- encryption -----------------------------------------------------------

    private static void encrypt(Path plainFile, Path outFile, EncryptionKeyring keyring)
            throws IOException {
        String keyId = keyring.activeKeyId();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try (OutputStream out = Files.newOutputStream(outFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] keyIdBytes = keyId.getBytes(StandardCharsets.UTF_8);
            out.write(MAGIC);
            out.write((keyIdBytes.length >> 8) & 0xFF);
            out.write(keyIdBytes.length & 0xFF);
            out.write(keyIdBytes);
            out.write(iv);

            Cipher cipher = gcm(Cipher.ENCRYPT_MODE, keyring.activeKey(), iv, keyIdBytes);
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
                throw new IOException("Backup archive encryption failed", impossible);
            }
        }
    }

    private static void decrypt(Path archive, Path plainOut, EncryptionKeyring keyring)
            throws IOException {
        try (InputStream in = Files.newInputStream(archive)) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (magic.length != MAGIC.length || !MessageDigest.isEqual(magic, MAGIC)) {
                throw new IOException("Backup archive does not start with the HIB1 magic;"
                    + " not a hohenheim instance backup or truncated at byte zero");
            }
            byte[] lengthBytes = in.readNBytes(2);
            if (lengthBytes.length != 2) {
                throw new IOException("Backup archive header is truncated");
            }
            int keyIdLength = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
            byte[] keyIdBytes = in.readNBytes(keyIdLength);
            byte[] iv = in.readNBytes(IV_BYTES);
            if (keyIdBytes.length != keyIdLength || iv.length != IV_BYTES) {
                throw new IOException("Backup archive header is truncated");
            }
            String keyId = new String(keyIdBytes, StandardCharsets.UTF_8);
            if (!keyring.containsKey(keyId)) {
                throw new IOException("Backup archive was encrypted under key '" + keyId
                    + "', which this controller's keyring does not contain; restore needs"
                    + " the keyring the backup was written under");
            }
            Cipher cipher = gcm(Cipher.DECRYPT_MODE, keyring.keyById(keyId), iv, keyIdBytes);
            try (OutputStream out = Files.newOutputStream(plainOut,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    byte[] chunk = cipher.update(buffer, 0, read);
                    if (chunk != null && chunk.length > 0) {
                        out.write(chunk);
                    }
                }
                try {
                    out.write(cipher.doFinal());
                } catch (AEADBadTagException tampered) {
                    throw new IOException("Backup archive fails authentication: the ciphertext"
                        + " does not match its GCM tag. The backup is corrupt or tampered with"
                        + " and is refused whole -- nothing was restored from it", tampered);
                } catch (GeneralSecurityException error) {
                    throw new IOException("Backup archive decryption failed", error);
                }
            }
        }
    }

    private static Cipher gcm(int mode, javax.crypto.spec.SecretKeySpec key, byte[] iv, byte[] aadKeyId)
            throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(MAGIC);
            cipher.updateAAD(aadKeyId);
            return cipher;
        } catch (GeneralSecurityException error) {
            throw new IOException("AES-GCM unavailable or key invalid", error);
        }
    }

    private record Hashed(@NonNull String sha256, long size) {}

    private static Hashed hashEntry(ZipFile zip, ZipEntry entry) throws IOException {
        MessageDigest digest = sha256();
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = zip.getInputStream(entry)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                size += read;
            }
        }
        return new Hashed(SecureTokens.hex(digest.digest()), size);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
