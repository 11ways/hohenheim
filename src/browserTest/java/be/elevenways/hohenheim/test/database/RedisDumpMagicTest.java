package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The redis capture path must assert the RDB magic on the bytes it PRODUCED, exactly as
 * the restore path asserts it on the bytes it was handed: {@code redis-cli} has shipped
 * exit 0 while writing an error reply (NOAUTH) into the dump file, and a backup that
 * restore would reject is not a backup. What this class cannot prove: no real redis or
 * docker daemon is involved, so the exec/archive plumbing itself stays with
 * {@code ManagedDatabaseTest}'s live journeys.
 */
class RedisDumpMagicTest {

    /** A docker whose exec always "succeeds" and whose archive fetch returns scripted bytes. */
    private static DockerClient scriptedDocker(byte[] dumpBytes) {
        return new DockerClient((DockerTransport) null) {
            @Override
            public ExecResult exec(String containerId, List<String> command)
                    throws IOException {
                return new ExecResult(0, "", "");
            }

            @Override
            public ExecResult exec(String containerId, List<String> command,
                                   List<String> env) throws IOException {
                return new ExecResult(0, "", "");
            }

            @Override
            public long getArchiveFileTo(String containerId, String path,
                                         Path outFile, long maxBytes) throws IOException {
                Files.write(outFile, dumpBytes);
                return dumpBytes.length;
            }
        };
    }

    @Test
    void aDumpThatIsNotAnRdbFailsAtCaptureEvenWhenRedisCliExitsZero() throws IOException {
        Path staging = Files.createTempDirectory("hohenheim-redis-magic");
        staging.toFile().deleteOnExit();

        // 1. redis-cli "succeeded" (exit 0) but the file it left holds an error reply:
        //    the capture must refuse NOW, naming the magic, never ship the file.
        Path bad = staging.resolve("bad.rdb");
        ManagedDatabase failing = new ManagedDatabase(scriptedDocker(
            "NOAUTH Authentication required.\n".getBytes(StandardCharsets.US_ASCII)));
        Throwable refusal = catchThrowable(() -> failing.backupToFile("db-handle",
            ManagedDatabase.Engine.REDIS, "user", "secret", "db", bad));
        assertThat(refusal)
            .as("step 1: an exit-0 dump without the REDIS magic is refused at capture")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("REDIS magic");

        // 2. A genuine RDB header passes and lands byte-for-byte.
        Path good = staging.resolve("good.rdb");
        byte[] genuine = "REDIS0011payload".getBytes(StandardCharsets.US_ASCII);
        new ManagedDatabase(scriptedDocker(genuine))
            .backupToFile("db-handle", ManagedDatabase.Engine.REDIS, "user", "secret",
                "db", good);
        assertThat(Files.readAllBytes(good))
            .as("step 2: a genuine RDB dump is shipped unchanged")
            .isEqualTo(genuine);
    }
}
