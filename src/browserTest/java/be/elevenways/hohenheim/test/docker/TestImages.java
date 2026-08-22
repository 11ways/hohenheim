package be.elevenways.hohenheim.test.docker;

import be.elevenways.hohenheim.server.docker.ContainerHardening;
import be.elevenways.hohenheim.server.docker.DockerClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
public final class TestImages {

    private TestImages() {}

    /**
     * Load a one-layer image carrying {@code marker}, tagged {@code tag}.
     *
     * @return the loaded image's content-addressed id
     */
    public static String load(DockerClient docker, String tag, String marker) throws IOException {
        Path work = Files.createTempDirectory("hohenheim-test-image");
        try {
            Path layerRoot = work.resolve("layerdir");
            Files.createDirectories(layerRoot);
            Files.writeString(layerRoot.resolve("marker.txt"), marker + "\n");
            return buildAndLoad(docker, work, layerRoot, tag, "[\"/bin/true\"]", marker);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Load a minimal HTTP-SERVING image, tagged {@code tag}: busybox (borrowed from the
     * local alpine image, so no registry is involved) answering every request on port
     * 8080 with a 200 carrying {@code body}. Distinct bodies produce distinct digests --
     * exactly what the release tests need to tell WHICH release answered a request.
     *
     * @return the loaded image's content-addressed id
     */
    public static String loadHttpServer(DockerClient docker, String tag, String body)
            throws IOException {
        return loadResponder(docker, tag, "HTTP/1.1 200 OK", body);
    }

    /**
     * Load an HTTP-answering image with an arbitrary status line -- e.g. a listener
     * that ANSWERS 500, which a health gate must reject even though it "responds".
     * Served by {@code busybox nc -lk -e} (a persistent forking listener, so
     * concurrent requests are safe; alpine's busybox has no httpd applet).
     *
     * @return the loaded image's content-addressed id
     */
    public static String loadResponder(DockerClient docker, String tag, String statusLine,
                                       String body) throws IOException {
        Path work = Files.createTempDirectory("hohenheim-test-image");
        String sourceName = "hohenheim-test-imgsrc-" + System.nanoTime();
        try {
            Path layerRoot = work.resolve("layerdir");
            Files.createDirectories(layerRoot);

            // Borrow /bin (busybox + applet links) and /lib (musl) from local alpine.
            docker.createContainer(sourceName,
                new LinkedHashMap<>(Map.of("Image", "alpine:latest")),
                ContainerHardening.SERVICE);
            try {
                Path binTar = work.resolve("bin.tar");
                Path libTar = work.resolve("lib.tar");
                docker.getArchiveTar(sourceName, "/bin", binTar, 64L * 1024 * 1024);
                docker.getArchiveTar(sourceName, "/lib", libTar, 64L * 1024 * 1024);
                run(work, List.of("tar", "-xf", binTar.toString(),
                    "-C", layerRoot.toString()));
                run(work, List.of("tar", "-xf", libTar.toString(),
                    "-C", layerRoot.toString()));
            } finally {
                docker.removeContainer(sourceName, true);
            }
            Path answer = layerRoot.resolve("answer.sh");
            // Read the request headers BEFORE answering: responding and closing while
            // the client is still writing races into "connection closed" 503s upstream.
            Files.writeString(answer, "#!/bin/busybox sh\n"
                + "CR=$(printf '\\r')\n"
                + "while read -r line; do\n"
                + "  line=${line%$CR}\n"
                + "  [ -z \"$line\" ] && break\n"
                + "done\n"
                + "printf '" + statusLine
                + "\\r\\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\\r\\nConnection: close\\r\\n\\r\\n" + body + "'\n");
            Files.setPosixFilePermissions(answer,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

            return buildAndLoad(docker, work, layerRoot, tag,
                "[\"/bin/busybox\",\"nc\",\"-lk\",\"-p\",\"8080\",\"-e\",\"/answer.sh\"]",
                statusLine + " " + body);
        } finally {
            deleteRecursively(work);
        }
    }

    private static String buildAndLoad(DockerClient docker, Path work, Path layerRoot,
                                       String tag, String cmdJson, String marker)
            throws IOException {
        Path layer = work.resolve("layer.tar");
        run(work, List.of("tar", "-cf", layer.toString(), "-C", layerRoot.toString(), "."));
        String layerDigest = sha256(Files.readAllBytes(layer));

        String created = Instant.now().toString().replaceAll("\\.\\d+Z$", "Z");
        String config = "{\"architecture\":\"amd64\",\"os\":\"linux\",\"created\":\"" + created
            + "\",\"config\":{\"Cmd\":" + cmdJson + "},\"rootfs\":{\"type\":\"layers\","
            + "\"diff_ids\":[\"sha256:" + layerDigest + "\"]},"
            + "\"history\":[{\"created\":\"" + created + "\",\"created_by\":\""
            + marker.replace("\"", "'").replace("\n", " ") + "\"}]}";
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
    }

    /** The content-addressed id behind a local tag, or null. */
    public static String idOf(DockerClient docker, String tag) throws IOException {
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
