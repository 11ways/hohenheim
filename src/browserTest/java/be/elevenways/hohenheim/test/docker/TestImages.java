package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.DockerClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Synthesizes a MINIMAL docker-format image tar and loads it, which is how tests get
 * distinct, freshly-created images without running a build.
 *
 * AIDEV-NOTE: the reclaim/lifecycle tests used to call {@code DockerClient.buildImage},
 * which no longer exists -- executing a Dockerfile inside the daemon is the
 * control-plane-trust-domain problem the sandboxed builders wave removed. Producing the
 * image tar directly is also strictly better for those tests: it is deterministic,
 * takes milliseconds, needs no network, and costs a few hundred BYTES of disk instead of
 * a base image per case. Tests that need a REAL build go through the sandbox on purpose.
 */
final class TestImages {

    private TestImages() {}

    /**
     * Load a one-layer image carrying {@code marker}, tagged {@code tag}.
     *
     * @return the loaded image's content-addressed id
     */
    static String load(DockerClient docker, String tag, String marker) throws IOException {
        Path work = Files.createTempDirectory("hohenheim-test-image");
        try {
            Path layerRoot = work.resolve("layerdir");
            Files.createDirectories(layerRoot);
            Files.writeString(layerRoot.resolve("marker.txt"), marker + "\n");

            Path layer = work.resolve("layer.tar");
            run(work, List.of("tar", "-cf", layer.toString(), "-C", layerRoot.toString(), "."));
            String layerDigest = sha256(Files.readAllBytes(layer));

            String created = Instant.now().toString().replaceAll("\\.\\d+Z$", "Z");
            String config = "{\"architecture\":\"amd64\",\"os\":\"linux\",\"created\":\"" + created
                + "\",\"config\":{\"Cmd\":[\"/bin/true\"]},\"rootfs\":{\"type\":\"layers\","
                + "\"diff_ids\":[\"sha256:" + layerDigest + "\"]},"
                + "\"history\":[{\"created\":\"" + created + "\",\"created_by\":\"" + marker + "\"}]}";
            byte[] configBytes = config.getBytes(StandardCharsets.UTF_8);
            String configName = sha256(configBytes) + ".json";
            Files.write(work.resolve(configName), configBytes);
            Files.writeString(work.resolve("manifest.json"),
                "[{\"Config\":\"" + configName + "\",\"RepoTags\":[\"" + tag
                    + "\"],\"Layers\":[\"layer.tar\"]}]");

            Path imageTar = work.resolve("image.tar");
            run(work, List.of("tar", "-cf", imageTar.toString(), "-C", work.toString(),
                configName, "layer.tar", "manifest.json"));
            docker.loadImage(imageTar);
            String id = idOf(docker, tag);
            if (id == null) {
                throw new IOException("The daemon loaded no image tagged " + tag);
            }
            return id;
        } finally {
            deleteRecursively(work);
        }
    }

    /** The content-addressed id behind a local tag, or null. */
    static String idOf(DockerClient docker, String tag) throws IOException {
        for (Object entry : docker.listImages()) {
            if (entry instanceof Map<?, ?> image
                    && image.get("RepoTags") instanceof List<?> tags && tags.contains(tag)) {
                return String.valueOf(image.get("Id"));
            }
        }
        return null;
    }

    private static void run(Path directory, List<String> command) throws IOException {
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("tar timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("tar failed (exit " + process.exitValue() + "): "
                    + new String(output, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted");
        }
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
