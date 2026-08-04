package be.elevenways.hohenheim.test.build;

import be.elevenways.hohenheim.server.build.BuildCredentials;
import be.elevenways.hohenheim.server.build.BuildLog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The credential lane and the log capture as one journey: what a build may see, for how
 * long, and what can come back out of it.
 */
class BuildCredentialsTest {

    @Test
    void aLeasedSecretIsScopedBoundedRevokedAndUnloggable() {
        int build = 4242;
        int rival = 4243;
        String secret = "hunter2-registry-password";

        // 1. A lease resolves for the build it was issued to, and ONLY that build:
        //    a leaked token cannot be replayed by a sibling build on the same host.
        BuildCredentials.Lease lease = BuildCredentials.issue(build,
            BuildCredentials.Purpose.REGISTRY_AUTH, secret, 60_000);
        assertThat(BuildCredentials.resolve(build, lease.token()))
            .as("step 1: the owning build resolves its lease").isEqualTo(secret);
        assertThat(BuildCredentials.resolve(rival, lease.token()))
            .as("step 1: another build may not claim it").isNull();

        // 2. The docker config a builder reads is derived from the TOKEN, so there is
        //    no path that hands a build a value it did not lease.
        String config = BuildCredentials.dockerConfigJson(build, lease.token(), "ghcr.io", "bob");
        assertThat(config).as("step 2: the auth file is materialized from the lease")
            .contains("ghcr.io").contains("auth");
        // The file carries the credential in the registry wire format (base64 of
        // user:password), which is ENCODING and not protection -- asserting the literal
        // string is absent would be the kind of check that cannot fail. What matters is
        // that it exists only inside the sandbox, and only while the lease does.
        assertThat(config).as("step 2: it is the real credential, wire-encoded")
            .contains(java.util.Base64.getEncoder().encodeToString(
                ("bob:" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // 3. Revocation is the end: THE property the wave claims. After the build, the
        //    same token buys nothing -- and the auth file cannot be re-derived either.
        lease.revoke();
        assertThat(BuildCredentials.resolve(build, lease.token()))
            .as("step 3: a revoked lease resolves to nothing").isNull();
        assertThat(BuildCredentials.dockerConfigJson(build, lease.token(), "ghcr.io", "bob"))
            .as("step 3: and no credential file can be built from it").isNull();
        assertThat(BuildCredentials.leaseCount(build))
            .as("step 3: nothing of this build survives").isZero();

        // 4. A TTL that has passed is the same refusal without anyone revoking, which is
        //    what covers a controller that died mid-build.
        BuildCredentials.Lease expired = BuildCredentials.issue(build,
            BuildCredentials.Purpose.REGISTRY_AUTH, secret, 1);
        sleep(20);
        assertThat(BuildCredentials.resolve(build, expired.token()))
            .as("step 4: an expired lease resolves to nothing").isNull();

        // 5. A blank secret is REFUSED rather than leased: an empty lease reads as
        //    "credentials are configured" at every call site and is one nowhere.
        assertThat(catchThrowable(() -> BuildCredentials.issue(build,
                BuildCredentials.Purpose.REGISTRY_AUTH, "  ", 60_000)))
            .as("step 5: a blank lease is refused by name")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");

        // 6. revokeAll is what the sandbox's finally block runs; it clears every purpose.
        BuildCredentials.issue(build, BuildCredentials.Purpose.REGISTRY_AUTH, secret, 60_000);
        BuildCredentials.issue(build, BuildCredentials.Purpose.SOURCE_AUTH, secret + "-src", 60_000);
        assertThat(BuildCredentials.leaseCount(build)).as("step 6: two leases live").isEqualTo(2);
        BuildCredentials.revokeAll(build);
        assertThat(BuildCredentials.leaseCount(build))
            .as("step 6: the teardown clears every lease of the build").isZero();
    }

    @Test
    void theCapturedLogRedactsSecretsAndBoundsItself() {
        // 1. A registered secret never survives into the captured text, wherever the
        //    builder printed it -- the builder is not ours and decides what it echoes.
        BuildLog log = new BuildLog(4096);
        log.redact("hunter2-registry-password");
        log.line("authenticating with hunter2-registry-password to ghcr.io");
        assertThat(log.text()).as("step 1: the secret is gone")
            .doesNotContain("hunter2-registry-password");
        assertThat(log.text()).as("step 1: and the line still explains itself")
            .contains("[redacted]").contains("ghcr.io");

        // 2. A short value is NOT redacted, deliberately: redacting "abc" would replace
        //    half the log and hide the failure the log exists to explain.
        BuildLog shortSecret = new BuildLog(4096);
        shortSecret.redact("abc");
        assertThat(shortSecret.redactedSecretCount())
            .as("step 2: a too-short secret is not registered").isZero();

        // 3. The cap bounds MEMORY and says so; output past it is dropped with a marker
        //    rather than the reader believing the build went quiet.
        BuildLog bounded = new BuildLog(1024);
        for (int i = 0; i < 500; i++) {
            bounded.line("line " + i + " of a build that prints forever");
        }
        assertThat(bounded.isTruncated()).as("step 3: the cap bound").isTrue();
        assertThat(bounded.text()).as("step 3: and the reader is told")
            .contains("truncated");
        assertThat(bounded.text().length())
            .as("step 3: memory stayed bounded").isLessThan(2048);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
