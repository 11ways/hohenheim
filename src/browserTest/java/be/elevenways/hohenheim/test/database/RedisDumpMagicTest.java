package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.HohenheimSettings;
import be.elevenways.hohenheim.server.database.DumpOverCap;
import be.elevenways.hohenheim.server.database.ManagedDatabase;
import be.elevenways.protoblast.common.i18n.LocaleChain;
import be.elevenways.protoblast.common.i18n.Microcopy;
import be.elevenways.protoblast.common.i18n.wording.ByteUnit;
import be.elevenways.zenit.common.Zenit;
import be.elevenways.zenit.server.microcopy.ShippedCatalogs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * exec/archive plumbing itself stays with {@code ManagedDatabaseTest}'s live journeys. The dump cap's refusal is
 * proved here too, before any transfer.
 */
class RedisDumpMagicTest {

    @Test
    void aDumpThatIsNotAnRdbFailsAtCaptureEvenWhenRedisCliExitsZero() throws IOException {
        Path staging = Files.createTempDirectory("hohenheim-redis-magic");
        staging.toFile().deleteOnExit();

        // 1. redis-cli "succeeded" (exit 0) but the file it left holds an error reply:
        //    the capture must refuse NOW, naming the magic, never ship the file.
        Path bad = staging.resolve("bad.rdb");
        ScriptedDumpDocker failingDocker = new ScriptedDumpDocker(
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
        new ManagedDatabase(new ScriptedDumpDocker(genuine))
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
        ScriptedDumpDocker first = new ScriptedDumpDocker(rdb);
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
        ScriptedDumpDocker second = new ScriptedDumpDocker(rdb);
        new ManagedDatabase(second).backupToFile("engine", ManagedDatabase.Engine.REDIS,
            "user", "secret", "db", staging.resolve("two.rdb"));
        assertThat(second.privateDirectory())
            .as("step 2: every dump gets its own directory")
            .isNotEqualTo(firstDirectory);

        // 3. A FAILED capture still removes its directory.
        ScriptedDumpDocker failing = new ScriptedDumpDocker("NOAUTH".getBytes(StandardCharsets.US_ASCII));
        catchThrowable(() -> new ManagedDatabase(failing).backupToFile("engine",
            ManagedDatabase.Engine.REDIS, "user", "secret", "db", staging.resolve("three.rdb")));
        assertThat(failing.removed(failing.privateDirectory()))
            .as("step 3: a refused capture removes its directory too")
            .isTrue();

        // 4. mongodump: same private directory, and the password never rides the argv --
        //    it reaches mongodump through a --config file written from the env.
        ScriptedDumpDocker mongo = new ScriptedDumpDocker("archive".getBytes(StandardCharsets.US_ASCII));
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

    /**
     * A dump sized over {@code database.max_dump_mb} before any transfer is refused by name: its technical message
     * names the setting and the value to raise it to, and its words for the operator carry both sizes as sizes.
     */
    @Test
    void anOverCapDumpIsRefusedWithItsSizesWordedForTheOperator() throws IOException {
        Path staging = Files.createTempDirectory("hohenheim-dump-cap");
        staging.toFile().deleteOnExit();
        Integer previous = Zenit.SETTINGS_VALUES.getValue(HohenheimSettings.Database.MAX_DUMP_MB);
        Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, 1);
        try {
            // 1. A dump of one and a half megabytes against a one-megabyte cap is refused before it is fetched.
            ScriptedDumpDocker docker = new ScriptedDumpDocker(new byte[(int) (ByteUnit.MEGABYTE.factor() * 3 / 2)]);
            Throwable refusal = catchThrowable(() -> new ManagedDatabase(docker).backupToFile("db-handle",
                ManagedDatabase.Engine.REDIS, "user", "secret", "db", staging.resolve("big.rdb")));
            assertThat(refusal).as("step 1: refused, naming the setting and the value that admits the dump")
                .isInstanceOf(IOException.class).hasMessageContaining("database.max_dump_mb")
                .hasMessageContaining("at least 2").hasMessageContaining("nothing was transferred");
            assertThat(docker.fetched).as("step 1: nothing was fetched").isEmpty();

            // 2. THE ASSERTION: the operator's words carry the dump's size and the cap as sizes, in their language.
            Microcopy words = DumpOverCap.wordsOf(refusal);
            assertThat(words).as("step 2: the refusal carries words for the operator").isNotNull();
            ShippedCatalogs catalogs = new ShippedCatalogs();
            assertThat(words.resolve(LocaleChain.ofTags("en"), catalogs)).as("step 2: in English")
                .isEqualTo("the dump is 1.5 MB, more than the 1 MB the Max dump size setting allows; raise it to at"
                    + " least 1.5 MB. Nothing was transferred.");
            assertThat(words.resolve(LocaleChain.ofTags("nl"), catalogs)).as("step 2: in Dutch")
                .contains("Max. dumpgrootte").contains(" MB");

            // 3. The technical message spells no size by hand: a size is worded for its reader.
            assertThat(refusal.getMessage()).as("step 3: no hand-built size").doesNotContain("MB")
                .doesNotContain("bytes");
        } finally {
            Zenit.SETTINGS_VALUES.setValue(HohenheimSettings.Database.MAX_DUMP_MB, previous);
        }
    }
}
