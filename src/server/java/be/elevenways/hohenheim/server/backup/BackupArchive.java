package be.elevenways.hohenheim.server.backup;

import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.orm.crypto.EncryptionKeyring;
import be.elevenways.zenit.server.security.SecureTokens;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The instance-backup archive format: a zip ({@code manifest.dry} + one
 * {@code volumes/<name>.tar} per payload) encrypted WHOLE through {@link BackupCipher} under the
 * {@link #MAGIC} {@code HIB1} -- settings carry secret variables and volume data is tenant
 * data, so nothing of either leaves the controller in the clear. The GCM tag
 * authenticates the whole ciphertext; per-entry sha256s inside the manifest pin each
 * plaintext payload on top of it.
 *
 * AIDEV-NOTE: decryption FULLY verifies the GCM tag before anything reads the plaintext
 * zip -- a partially-decrypted stream must never feed a restore, which is why this class
 * decrypts to a staging file instead of handing out a CipherInputStream. The cipher (and
 * why it streams through BouncyCastle) lives in {@link BackupCipher}, shared with the
 * managed-database dumps; the format is unchanged, so every archive written before still opens.
 */
public final class BackupArchive {

    /** The four magic bytes every archive starts with. */
    public static final byte[] MAGIC = {'H', 'I', 'B', '1'};

    public static final String MANIFEST_ENTRY = "manifest.dry";
    public static final String VOLUME_PREFIX = "volumes/";

    public static final String APPLICATION_PREFIX = "application/";

    /** Archive entry prefix of each data disk's payload ({@link BackupManifest.DiskEntry}). */
    public static final String DISK_PREFIX = "disks/";

    /** Archive entry prefix of each host volume's payload ({@link BackupManifest.HostVolumeEntry}). */
    public static final String HOST_VOLUME_PREFIX = "host-volumes/";

    private BackupArchive() {}

    /** A decrypt-and-verify result: the manifest plus the verified plaintext zip. */
    public record Opened(@NonNull BackupManifest manifest, @NonNull Path zip) {}

    /**
     * Build and encrypt one archive. {@code volumeFiles} maps each manifest volume
     * entry's {@code file} name to its local tar; application and disk files are keyed with
     * {@link #APPLICATION_PREFIX} and {@link #DISK_PREFIX} so a volume cannot shadow them.
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
                for (Map.Entry<String, BackupManifest.PayloadEntry> payload : sidePayloads(manifest).entrySet()) {
                    Path file = volumeFiles.get(payload.getKey());
                    if (file == null) {
                        throw new IOException("Backup payload " + payload.getKey() + " is missing");
                    }
                    zip.putNextEntry(new ZipEntry(payload.getKey()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            BackupCipher.encrypt(MAGIC, plainZip, outFile, keyring);
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
            BackupCipher.decrypt(MAGIC, archive, plainZip, keyring);
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
                for (Map.Entry<String, BackupManifest.PayloadEntry> payload : sidePayloads(manifest).entrySet()) {
                    ZipEntry entry = zip.getEntry(payload.getKey());
                    if (entry == null) {
                        throw new IOException("Backup payload " + payload.getKey() + " is missing");
                    }
                    Hashed hashed = hashEntry(zip, entry);
                    if (hashed.size() != payload.getValue().size()
                            || !hashed.sha256().equals(payload.getValue().sha256())) {
                        throw new IOException("Backup payload " + payload.getKey() + " does not match its"
                            + " recorded checksum; refused whole");
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

    /**
     * The payloads beside the volume inventory, by archive entry name: the application's source and image, then
     * each data disk, then each host volume.
     */
    private static Map<String, BackupManifest.PayloadEntry> sidePayloads(BackupManifest manifest) {
        Map<String, BackupManifest.PayloadEntry> payloads = new LinkedHashMap<>();
        if (manifest.application() != null) {
            for (BackupManifest.PayloadEntry payload
                    : List.of(manifest.application().artifact(), manifest.application().image())) {
                payloads.put(APPLICATION_PREFIX + payload.file(), payload);
            }
        }
        for (BackupManifest.DiskEntry disk : manifest.disks()) {
            payloads.put(DISK_PREFIX + disk.payload().file(), disk.payload());
        }
        for (BackupManifest.HostVolumeEntry volume : manifest.hostVolumes()) {
            payloads.put(HOST_VOLUME_PREFIX + volume.payload().file(), volume.payload());
        }
        return payloads;
    }

    /** Extract the already authenticated source and runtime image, never volume payloads. */
    public static @NonNull Map<String, Path> extractApplication(@NonNull Opened opened,
                                                               @NonNull Path directory)
            throws IOException {
        BackupManifest.ApplicationEntry application = opened.manifest().application();
        if (application == null) {
            return Map.of();
        }
        Map<String, Path> files = new LinkedHashMap<>();
        for (BackupManifest.PayloadEntry payload : List.of(application.artifact(), application.image())) {
            files.put(payload.file(), extractEntry(opened, APPLICATION_PREFIX + payload.file(),
                directory.resolve(payload.file())));
        }
        return files;
    }

    /**
     * Extract the already authenticated data disk payloads, keyed by device name.
     *
     * @return empty for a manifest without disks
     */
    public static @NonNull Map<String, Path> extractDisks(@NonNull Opened opened, @NonNull Path directory)
            throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        for (BackupManifest.DiskEntry disk : opened.manifest().disks()) {
            files.put(disk.device(), extractEntry(opened, DISK_PREFIX + disk.payload().file(),
                directory.resolve(disk.payload().file())));
        }
        return files;
    }

    /**
     * Extract the already authenticated host volume payloads, keyed by volume name.
     *
     * @return empty for a manifest without host volumes
     */
    public static @NonNull Map<String, Path> extractHostVolumes(@NonNull Opened opened, @NonNull Path directory)
            throws IOException {
        Map<String, Path> files = new LinkedHashMap<>();
        for (BackupManifest.HostVolumeEntry volume : opened.manifest().hostVolumes()) {
            files.put(volume.declaration().name(), extractEntry(opened,
                HOST_VOLUME_PREFIX + volume.payload().file(), directory.resolve(volume.payload().file())));
        }
        return files;
    }

    private static @NonNull Path extractEntry(@NonNull Opened opened, @NonNull String entryName,
                                              @NonNull Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (ZipFile zip = new ZipFile(opened.zip().toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Backup archive is missing payload " + entryName);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, target);
            }
        }
        return target;
    }

    /** Stream a local file through SHA-256 (zenit's {@link SecureTokens#sha256Hex(Path)}). */
    public static @NonNull String sha256Of(@NonNull Path file) throws IOException {
        return SecureTokens.sha256Hex(file);
    }

    private record Hashed(@NonNull String sha256, long size) {}

    /** The sha256 and the byte count of what the entry actually streams, not its header. */
    private static Hashed hashEntry(ZipFile zip, ZipEntry entry) throws IOException {
        long[] size = new long[1];
        String sha;
        try (InputStream in = new FilterInputStream(zip.getInputStream(entry)) {
            @Override
            public int read() throws IOException {
                int read = super.read();
                if (read >= 0) {
                    size[0]++;
                }
                return read;
            }

            @Override
            public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
                int read = super.read(buffer, offset, length);
                if (read > 0) {
                    size[0] += read;
                }
                return read;
            }
        }) {
            sha = SecureTokens.sha256Hex(in);
        }
        return new Hashed(sha, size[0]);
    }
}
