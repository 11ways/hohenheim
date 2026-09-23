package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.hohenheim.server.docker.DockerClient;
import be.elevenways.hohenheim.server.docker.DockerTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The binary capture paths (redis RDB, mongodump archive), against a scripted daemon.
 *
 * The redis capture must assert the RDB magic on the bytes it PRODUCED, exactly as the
 * restore path asserts it on the bytes it was handed: {@code redis-cli} has shipped exit 0
 * while writing an error reply (NOAUTH) into the dump file, and a backup that restore would
 * reject is not a backup. And every binary dump must land in a PRIVATE directory of its own
 * that is removed afterwards: one fixed path per engine let two overlapping dumps on a shared
 * engine hand one tenant the other's archive, and left the last dump in /tmp.
 *
 * What this class cannot prove: no real redis or docker daemon is involved, so the
 * exec/archive plumbing itself stays with {@code ManagedDatabaseTest}'s live journeys.
 */
class RedisDumpMagicTest {

    /**
     * A docker whose exec "succeeds", answers {@code stat} with the scripted dump's size,
     * records every command with its env, and serves the scripted bytes from any path.
     */
    private static final class ScriptedDocker extends DockerClient {

        final List<List<String>> commands = new ArrayList<>();
        final List<List<String>> envs = new ArrayList<>();
        final List<String> fetched = new ArrayList<>();
        private final byte[] dumpBytes;

        ScriptedDocker(byte[] dumpBytes) {
            super((DockerTransport) null);
            this.dumpBytes = dumpBytes;
        }

        @Override
        public ExecResult exec(String containerId, List<String> command) throws IOException {
            return exec(containerId, command, List.of());
        }

        @Override
        public ExecResult exec(String containerId, List<String> command,
                               List<String> env) throws IOException {
            this.commands.add(List.copyOf(command));
            this.envs.add(List.copyOf(env));
            if (!command.isEmpty() && command.get(0).equals("stat")) {
                return new ExecResult(0, this.dumpBytes.length + "\n", "");
            }
            return new ExecResult(0, "", "");
        }

        @Override
        public long getArchiveFileTo(String containerId, String path,
                                     Path outFile, long maxBytes) throws IOException {
            this.fetched.add(path);
            Files.write(outFile, this.dumpBytes);
            return this.dumpBytes.length;
        }

        /** The directory argument of the {@code mkdir -m 700 <dir>} this capture ran. */
        String privateDirectory() {
            for (List<String> command : this.commands) {
                if (command.size() == 4 && command.get(0).equals("mkdir")) {
                    return command.get(3);
                }
            }
            return null;
        }

        /** Whether an {@code rm -rf} removed the directory (and the legacy fixed dump paths). */
        boolean removed(String directory) {
            for (List<String> command : this.commands) {
                if (command.size() > 2 && command.get(0).equals("rm") && command.contains(directory)
                        && command.contains("/tmp/hohenheim-dump.rdb")
                        && command.contains("/tmp/hohenheim-dump.archive")) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void aDumpThatIsNotAnRdbFailsAtCaptureEvenWhenRedisCliExitsZero() throws IOException {
        Path staging = Files.createTempDirectory("hohenheim-redis-magic");
        staging.toFile().deleteOnExit();

        // 1. redis-cli "succeeded" (exit 0) but the file it left holds an error reply:
        //    the capture must refuse NOW, naming the magic, never ship the file.
        Path bad = staging.resolve("bad.rdb");
        ScriptedDocker failingDocker = new ScriptedDocker(
            "NOAUTH Authentication required.\n".getBytes(StandardCharsets.US_ASCII));
        Throwable refusal = catchThrowable(() -> new ManagedDatabase(failingDocker)
            .backupToFile("db-handle", ManagedDatabase.Engine.REDIS, "user", "secret", "db", bad));
        assertThat(refusal)
            .as("step 1: an exit-0 dump without the REDIS magic is refused at capture")
            .isInstanceOf(IOException.class)
            .hasMessageContaining("REDIS magic");
        assertThat(Files.exists(bad)).as("step 1: and no file passes for a backup").isFalse();

        // 2. A genuine RDB header passes and lands byte-for-byte.
        Path good = staging.resolve("good.rdb");
        byte[] genuine = "REDIS0011payload".getBytes(StandardCharsets.US_ASCII);
        new ManagedDatabase(new ScriptedDocker(genuine))
            .backupToFile("db-handle", ManagedDatabase.Engine.REDIS, "user", "secret",
                "db", good);
        assertThat(Files.readAllBytes(good))
            .as("step 2: a genuine RDB dump is shipped unchanged")
            .isEqualTo(genuine);
    }

    @Test
    void everyBinaryDumpUsesAPrivateDirectoryOfItsOwnAndRemovesIt() throws IOException {
        Path staging = Files.createTempDirectory("hohenheim-private-dump");
        staging.toFile().deleteOnExit();
        byte[] rdb = "REDIS0011payload".getBytes(StandardCharsets.US_ASCII);

        // 1. A redis dump creates a 0700 directory with a random name under /tmp, dumps
        //    INSIDE it, fetches from inside it, and removes it.
        ScriptedDocker first = new ScriptedDocker(rdb);
        new ManagedDatabase(first).backupToFile("engine", ManagedDatabase.Engine.REDIS,
            "user", "secret", "db", staging.resolve("one.rdb"));
        String firstDirectory = first.privateDirectory();
        assertThat(firstDirectory)
            .as("step 1: the dump directory is created private (mkdir -m 700) under /tmp")
            .isNotNull()
            .startsWith("/tmp/hohenheim-dump-");
        assertThat(first.fetched)
            .as("step 1: the fetched dump lives inside that private directory")
            .containsExactly(firstDirectory + "/dump.rdb");
        assertThat(first.removed(firstDirectory))
            .as("step 1: and the directory is removed afterwards, not left in /tmp -- with the"
                + " fixed paths older dumps left behind")
            .isTrue();

        // 2. A second, overlapping dump on the SAME engine gets a different directory: the
        //    fixed path is what handed one tenant another's archive.
        ScriptedDocker second = new ScriptedDocker(rdb);
        new ManagedDatabase(second).backupToFile("engine", ManagedDatabase.Engine.REDIS,
            "user", "secret", "db", staging.resolve("two.rdb"));
        assertThat(second.privateDirectory())
            .as("step 2: every dump gets its own directory")
            .isNotEqualTo(firstDirectory);

        // 3. A FAILED capture still removes its directory.
        ScriptedDocker failing = new ScriptedDocker("NOAUTH".getBytes(StandardCharsets.US_ASCII));
        catchThrowable(() -> new ManagedDatabase(failing).backupToFile("engine",
            ManagedDatabase.Engine.REDIS, "user", "secret", "db", staging.resolve("three.rdb")));
        assertThat(failing.removed(failing.privateDirectory()))
            .as("step 3: a refused capture removes its directory too")
            .isTrue();

        // 4. mongodump: same private directory, and the password never rides the argv --
        //    it reaches mongodump through a --config file written from the env.
        ScriptedDocker mongo = new ScriptedDocker("archive".getBytes(StandardCharsets.US_ASCII));
        new ManagedDatabase(mongo).backupToFile("engine", ManagedDatabase.Engine.MONGO,
            "root", "p4ssw0rd-secret", "tenantdb", staging.resolve("four.archive"));
        String mongoDirectory = mongo.privateDirectory();
        assertThat(mongo.fetched)
            .as("step 4: the archive is fetched from the private directory")
            .containsExactly(mongoDirectory + "/dump.archive");
        assertThat(mongo.commands)
            .as("step 4: no command line carries the password")
            .allSatisfy(command -> assertThat(String.join(" ", command))
                .doesNotContain("p4ssw0rd-secret"));
        assertThat(mongo.envs)
            .as("step 4: the dump reads it from its environment instead")
            .anySatisfy(env -> assertThat(env).anySatisfy(entry ->
                assertThat(entry).endsWith("=p4ssw0rd-secret")));
        assertThat(mongo.removed(mongoDirectory))
            .as("step 4: and the directory, config file included, is removed")
            .isTrue();
    }
}
