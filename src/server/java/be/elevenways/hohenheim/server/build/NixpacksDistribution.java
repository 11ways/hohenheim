package be.elevenways.hohenheim.server.build;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.zenit.common.setting.SettingDefinition;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The control plane's cache of the PINNED nixpacks CLI binary the detection phase runs.
 *
 * <p>The binary is the {@code x86_64-unknown-linux-musl} release build: STATIC, so it
 * runs in any detector image without a libc contract. It is downloaded once per pinned
 * version, verified against the pinned sha256 BEFORE anything is extracted, and cached
 * under the data directory; a hash mismatch is a refusal, never a warning.</p>
 *
 * AIDEV-NOTE: version and hash are pinned TOGETHER in settings -- bumping the version
 * without the matching hash refuses every nixpacks build rather than trusting whatever
 * GitHub serves. The pin is also what makes detection reproducible: a floating "latest"
 * detector could silently change what an unchanged repository builds into.
 */
public final class NixpacksDistribution {

    private NixpacksDistribution() {}

    /** Filename of the cached binary; also the staged name inside the phase. */
    public static final String BINARY_NAME = "nixpacks";

    private static final String DOWNLOAD_URL =
        "https://github.com/railwayapp/nixpacks/releases/download/v%s/nixpacks-v%s-x86_64-unknown-linux-musl.tar.gz";

    /**
     * @return the directory containing the verified {@code nixpacks} binary
     * @throws IOException when the host is not x86_64, the download fails, or the
     *                     archive does not match the pinned sha256
     */
    public static synchronized @NonNull Path ensureBinary() throws IOException {
        String arch = System.getProperty("os.arch", "");
        if (!"amd64".equals(arch) && !"x86_64".equals(arch)) {
            throw new IOException("REFUSED to run a nixpacks build: the pinned detector"
                + " binary is x86_64 and this host is '" + arch + "'. Pin an arch-matching"
                + " build in hohenheim.builds.nixpacks_version/nixpacks_sha256 first.");
        }
        String version = setting(HohenheimSettings.Builds.NIXPACKS_VERSION, "nixpacks_version");
        String sha256 = setting(HohenheimSettings.Builds.NIXPACKS_SHA256, "nixpacks_sha256")
            .toLowerCase(Locale.ROOT);

        Path dir = cacheDir(version);
        Path binary = dir.resolve(BINARY_NAME);
        if (Files.isExecutable(binary)) {
            return dir;
        }

        byte[] archive = download(version);
        String actual = sha256Hex(archive);
        if (!actual.equals(sha256)) {
            throw new IOException("REFUSED to install nixpacks " + version + ": the"
                + " downloaded archive hashes to " + actual + " but the pinned sha256 is "
                + sha256 + ". Refusing to run an unverified detector over tenant code.");
        }

        Files.createDirectories(dir);
        Path tmpDir = Files.createTempDirectory("hohenheim-nixpacks");
        try {
            Path extracted = extractBinary(archive, tmpDir);
            if (!extracted.toFile().setExecutable(true, false)) {
                throw new IOException("Could not mark the nixpacks binary executable");
            }
            Files.move(extracted, binary, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteRecursively(tmpDir);
        }
        return dir;
    }

    private static @NonNull String setting(@NonNull SettingDefinition<String> definition,
                                           @NonNull String name) throws IOException {
        String value = HohenheimSettings.VALUES.getValue(definition);
        if (value == null || value.isBlank()) {
            throw new IOException("REFUSED to run a nixpacks build: hohenheim.builds."
                + name + " is empty; the detector must be pinned, never floating");
        }
        return value.trim();
    }

    private static @NonNull Path cacheDir(@NonNull String version) {
        String dataPath = HohenheimSettings.VALUES.getValue(HohenheimSettings.Storage.DATA_PATH);
        return Path.of(dataPath == null || dataPath.isBlank() ? "data" : dataPath)
            .resolve("nixpacks").resolve(version);
    }

    private static byte[] download(@NonNull String version) throws IOException {
        String url = DOWNLOAD_URL.formatted(version, version);
        try {
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("Downloading " + url + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Downloading " + url + " was interrupted");
        }
    }

    // Extraction via the system `tar`, the same primitive DockerClient's archive
    // handling already standardizes on.
    private static @NonNull Path extractBinary(byte[] archive, @NonNull Path tmpDir)
            throws IOException {
        Path tarFile = tmpDir.resolve("archive.tar.gz");
        Files.write(tarFile, archive);
        try {
            Process process = new ProcessBuilder("tar", "-xzf", tarFile.toString(),
                "-C", tmpDir.toString()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Extracting the nixpacks archive timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Extracting the nixpacks archive failed (tar exit "
                    + process.exitValue() + ")");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Extracting the nixpacks archive was interrupted");
        }
        try (var walk = Files.walk(tmpDir)) {
            return walk.filter(file -> BINARY_NAME.equals(file.getFileName().toString())
                    && Files.isRegularFile(file))
                .findFirst()
                .orElseThrow(() -> new IOException(
                    "The nixpacks release archive contains no '" + BINARY_NAME + "' binary"));
        }
    }

    private static void deleteRecursively(@NonNull Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    private static @NonNull String sha256Hex(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}
