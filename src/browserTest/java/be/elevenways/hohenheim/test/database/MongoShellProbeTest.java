package be.elevenways.hohenheim.test.database;

import be.elevenways.hohenheim.server.database.ManagedDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of the Mongo readiness probe, daemon-free: it picks whichever shell the image
 * ships (`mongosh` on 5.0+, the legacy `mongo` on 4.4, the last engine that runs without
 * AVX) and never spells the password on the command line. The real-daemon half, against
 * both images, is {@code MongoShellProbeLiveTest}.
 */
class MongoShellProbeTest {

    @Test
    void theProbePicksTheShellTheImageHasAndKeepsThePasswordOffTheArgv() {
        String password = "s3cret-probe-pw";
        List<String> command = ManagedDatabase.Engine.MONGO.readyCommand("appuser", password, "appdb");
        List<String> env = ManagedDatabase.Engine.MONGO.readyEnv(password);

        // 1. One shell script decides at run time; both client names are candidates.
        assertThat(command).as("step 1: the probe is a shell script").startsWith("sh", "-c");
        assertThat(command.get(2))
            .as("step 1: mongosh where it exists, the legacy mongo shell otherwise")
            .contains("command -v mongosh").contains("shell=mongosh").contains("shell=mongo;");

        // 2. Port and user travel as positional arguments, the password through the env.
        assertThat(command).as("step 2: port and user are arguments").contains("27017", "appuser");
        assertThat(String.join(" ", command))
            .as("step 2: the password is never on the argv").doesNotContain(password);
        assertThat(env)
            .as("step 2: the env carries the password under the probe's own variable")
            .containsExactly(ManagedDatabase.Engine.MONGO_PROBE_PASSWORD + "=" + password);
        assertThat(command.get(2))
            .as("step 2: and the script reads it from that variable")
            .contains("$" + ManagedDatabase.Engine.MONGO_PROBE_PASSWORD);

        // 3. The other engines are untouched by this: their probes are still plain argv.
        assertThat(ManagedDatabase.Engine.REDIS.readyCommand("u", "p", "d"))
            .as("step 3: redis keeps its client argv").startsWith("redis-cli");
    }
}
